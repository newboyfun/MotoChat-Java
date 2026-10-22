package com.motochat.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motochat.security.AuthFilter;
import com.motochat.security.SessionStore;
import com.motochat.security.SessionUser;
import com.motochat.service.AutoSendService;
import com.motochat.service.ChatService;
import com.motochat.stream.ChunkCoalescer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /ws/chat 实时双向聊天。协议与 Python 版 api/ws.py 完全一致：
 *
 * <pre>
 * 客户端 → {"type":"ping"}                      服务端 → {"type":"pong"}
 * 客户端 → {"message":"...","conversation_id":"..."}
 * 服务端 → {"type":"start"}
 *        → {"type":"chunk","data":"..."}   (多次)
 *        → {"type":"end","usage":{...}}
 * 服务端 → {"type":"notification","title":"...","content":"..."}  (定时推送)
 * 服务端 → {"type":"error","data":"..."}
 * </pre>
 *
 * <p>认证只认 HttpOnly Cookie {@code kc_token}，不认 query token
 * （query 会落 access log，造成凭据泄露）。鉴权失败用 4001 关闭码。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final int MAX_MESSAGE_LENGTH = 10_000;
    private static final int RATE_LIMIT_PER_MINUTE = 30;

    private final SessionStore sessionStore;
    private final ChatService chatService;
    private final AutoSendService autoSendService;
    private final WsSessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 每个连接自己的消息时间窗口，用于限流 */
    private final Map<String, List<Long>> messageTimes = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatWebSocketHandler(SessionStore sessionStore,
                                ChatService chatService,
                                AutoSendService autoSendService,
                                WsSessionRegistry registry) {
        this.sessionStore = sessionStore;
        this.chatService = chatService;
        this.autoSendService = autoSendService;
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = (String) session.getAttributes().get(AuthFilter.ATTR_TOKEN);
        SessionUser user = sessionStore.get(token);
        if (user == null) {
            session.close(WsSessionRegistry.unauthorized());
            return;
        }
        session.getAttributes().put(AuthFilter.ATTR_USER, user);
        registry.register(session, user.conversationId(), user.role());
        log.info("WebSocket 已连接: 用户={} 当前连接数={}", user.username(), registry.count());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionUser user = (SessionUser) session.getAttributes().get(AuthFilter.ATTR_USER);
        if (user == null) {
            try {
                session.close(WsSessionRegistry.unauthorized());
            } catch (Exception ignored) {
                // 连接已断，无需处理
            }
            return;
        }

        JsonNode node;
        try {
            node = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            safeSend(session, errorPayload("消息格式错误"));
            return;
        }
        if (node == null) {
            safeSend(session, errorPayload("消息格式错误"));
            return;
        }

        // 限流必须在起线程之前做：消息处理要排队等单连接的 synchronized (session)，
        // 若不在入口拦住，客户端瞬间灌入大量帧时每帧都会先变成一个排队中的虚拟线程，
        // 限流形同虚设。ping 心跳不限流，与原版一致。
        boolean isPing = "ping".equals(node.path("type").asText());
        if (!isPing && !allowMessage(session.getId())) {
            safeSend(session, errorPayload("发送过于频繁，请稍后再试"));
            return;
        }

        // 关键：不能在 WS 容器线程里跑完整条 LLM 流（最长 180 秒）。
        // Spring 的 WebSocket 容器线程池很小，几个用户同时聊天就会互相排队。
        // 丢到虚拟线程后立刻返回；再用 session 对象做锁，保证同一条连接的
        // 消息按顺序处理（并发写同一个 session 会乱序、也会触发并发发送异常）。
        Thread.ofVirtual().start(() -> {
            synchronized (session) {
                processMessage(session, user, node);
            }
        });
    }

    private void processMessage(WebSocketSession session, SessionUser user, JsonNode node) {
        if ("ping".equals(node.path("type").asText())) {
            safeSend(session, Map.of("type", "pong"));
            return;
        }

        String content = node.path("message").asText("");
        if (content.length() > MAX_MESSAGE_LENGTH) {
            safeSend(session, errorPayload("消息过长（最大 10000 字）"));
            return;
        }

        String cid = node.path("conversation_id").asText("default");
        if (!user.isAdmin()) {
            // 普通用户只能在自己会话里发消息
            cid = (user.conversationId() == null || user.conversationId().isBlank())
                    ? cid : user.conversationId();
        }

        autoSendService.updateLastChatTime();
        log.info("[WS] 收到消息: 会话={} 长度={}", cid, content.length());

        safeSend(session, Map.of("type", "start"));

        ChunkCoalescer coalescer = new ChunkCoalescer();
        int[] chunkCount = {0};
        String[] usage = {"{}"};

        try {
            chatService.handleStream(content, cid, user.username(), true, new ChatService.StreamSink() {
                @Override
                public void onText(String text) {
                    String merged = coalescer.add(text);
                    if (merged != null) {
                        sendChunk(session, merged);
                        chunkCount[0]++;
                    }
                }

                @Override
                public void onUsage(com.motochat.llm.LlmGateway.LlmUsage u) {
                    String rest = coalescer.drain();
                    if (rest != null) {
                        sendChunk(session, rest);
                        chunkCount[0]++;
                    }
                    usage[0] = toJson(u.toMap());
                }

                @Override
                public void onError(String error) {
                    // 错误文本作为普通 chunk 展示，与修复前经 onDelta 写出的效果一致
                    onText(error);
                }

                @Override
                public boolean isCancelled() {
                    return !session.isOpen();
                }
            });

            if (session.isOpen()) {
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("type", "end");
                end.put("usage", mapper.readTree(usage[0]));
                registry.send(session, end);
                log.info("[WS] 流式完成: chunks={}", chunkCount[0]);
            } else {
                log.info("[WS] 连接已断，跳过 end 帧: chunks={}", chunkCount[0]);
            }
        } catch (Exception e) {
            log.error("[WS] 处理失败: {}", e.getMessage(), e);
            safeSend(session, errorPayload(e.getMessage() == null ? "处理失败" : e.getMessage()));
        }
    }

    private void safeSend(WebSocketSession session, Map<String, Object> payload) {
        try {
            registry.send(session, payload);
        } catch (Exception e) {
            log.debug("WS 发送失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
        messageTimes.remove(session.getId());
        log.info("WebSocket 已断开: code={} 当前连接数={}", status.getCode(), registry.count());
    }

    private void sendChunk(WebSocketSession session, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "chunk");
        payload.put("data", text);
        try {
            registry.send(session, payload);
        } catch (Exception e) {
            log.debug("WS chunk 发送失败: {}", e.getMessage());
        }
    }

    /** 滑动窗口限流：每分钟最多 30 条 */
    private boolean allowMessage(String sessionId) {
        long now = System.currentTimeMillis();
        List<Long> times = messageTimes.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (times) {
            times.removeIf(t -> now - t >= 60_000);
            if (times.size() >= RATE_LIMIT_PER_MINUTE) {
                return false;
            }
            times.add(now);
            return true;
        }
    }

    private static Map<String, Object> errorPayload(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("data", message);
        return m;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
