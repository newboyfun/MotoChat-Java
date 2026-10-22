package com.motochat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接注册表与广播。
 * 对应 Python 版 api/ws.py 的 _ws_clients / broadcast。
 *
 * <p>两个必须注意的点：
 * <ol>
 *   <li><b>sendMessage 不是线程安全的</b>。定时任务和聊天流可能同时往一个连接推消息，
 *       裸调会抛 {@code TEXT_PARTIAL_WRITING}。这里用 {@link ConcurrentWebSocketSessionDecorator} 包一层。</li>
 *   <li><b>定向投递</b>：cid 非空时只发给该会话的用户和管理员，
 *       否则 A 用户的定时提醒会弹到 B 用户界面上。</li>
 * </ol>
 */
@Component
public class WsSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WsSessionRegistry.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Client(WebSocketSession session, String cid, String role) {
    }

    public void register(WebSocketSession session, String cid, String role) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        clients.put(session.getId(), new Client(safe, cid == null ? "" : cid, role == null ? "user" : role));
    }

    public void unregister(WebSocketSession session) {
        clients.remove(session.getId());
    }

    public int count() {
        return clients.size();
    }

    /**
     * 广播。
     * @param cid 为 null 时发给所有连接；非 null 时只发给该会话 + 管理员
     */
    public void broadcast(Map<String, Object> payload, String cid) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("广播序列化失败: {}", e.getMessage());
            return;
        }
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Client> entry : clients.entrySet()) {
            Client c = entry.getValue();
            if (cid != null && !cid.equals(c.cid()) && !"admin".equals(c.role())) {
                continue;
            }
            if (!c.session().isOpen()) {
                dead.add(entry.getKey());
                continue;
            }
            try {
                c.session().sendMessage(new TextMessage(json));
            } catch (IOException | IllegalStateException e) {
                log.debug("WS 发送失败: {}", e.getMessage());
                dead.add(entry.getKey());
            }
        }
        dead.forEach(clients::remove);
    }

    /** 向单个连接发送 */
    public void send(WebSocketSession session, Map<String, Object> payload) throws IOException {
        Client c = clients.get(session.getId());
        String json = mapper.writeValueAsString(payload);
        if (c != null) {
            c.session().sendMessage(new TextMessage(json));
        } else if (session.isOpen()) {
            session.sendMessage(new TextMessage(json));
        }
    }

    public static CloseStatus unauthorized() {
        return new CloseStatus(4001);
    }
}
