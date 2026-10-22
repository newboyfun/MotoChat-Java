package com.motochat.config;

import com.motochat.security.AuthFilter;
import com.motochat.ws.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Arrays;
import java.util.Map;

/**
 * WebSocket 注册。路径 /ws/chat 与原版一致。
 *
 * <p>握手阶段把 Cookie 里的 token 取出来放进 session attributes，
 * 真正的有效性校验在 {@link ChatWebSocketHandler#afterConnectionEstablished} 里做
 * （无效就 close 4001），这样前端拿到的错误码与原版相同。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final String[] allowedOrigins;

    /**
     * @param allowedOrigins 允许的 Origin，逗号分隔；* 表示全部（默认）。
     *   Cookie 是 SameSite=Lax，跨站请求带不上 Cookie，默认 * 即可；
     *   需要收紧时在 motochat.ws-allowed-origins 填具体 origin（如 http://127.0.0.1:8080）。
     */
    public WebSocketConfig(ChatWebSocketHandler handler,
                           @Value("${motochat.ws-allowed-origins:*}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat")
                .addInterceptors(new TokenHandshakeInterceptor())
                .setAllowedOriginPatterns(allowedOrigins.length > 0 ? allowedOrigins : new String[]{"*"});
    }

    static class TokenHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                String token = AuthFilter.resolveToken(servletRequest.getServletRequest());
                if (token != null) {
                    attributes.put(AuthFilter.ATTR_TOKEN, token);
                }
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // 无需处理
        }
    }
}
