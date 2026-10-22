package com.motochat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天接口限流：每 IP 每分钟 30 次。
 * 对应 Python 版 server.py 的 rate_limit_middleware（只拦 /api/chat 前缀）。
 *
 * <p>WebSocket 的帧不走这里，它在 {@code ChatWebSocketHandler} 里单独限流，
 * 与原版一致。
 */
@Component
public class ChatRateLimitFilter extends OncePerRequestFilter {

    private static final int LIMIT = 30;
    private static final long WINDOW_MS = 60_000L;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 是否信任 X-Forwarded-For。默认 false：该头由客户端完全可控，
     * 信任它等于允许攻击者换个头值就绕过聊天限流。
     */
    private final boolean trustForwardedFor;

    public ChatRateLimitFilter(@Value("${motochat.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/chat");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = clientIp(request);
        long now = System.currentTimeMillis();
        Deque<Long> times = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) {
                times.pollFirst();
            }
            if (times.size() >= LIMIT) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(mapper.writeValueAsString(
                        Map.of("error", "请求过于频繁，请稍后再试")));
                return;
            }
            times.addLast(now);
        }
        // 防膨胀：超过 1000 个 IP 时清理。注意必须先清掉队列里的过期时间戳再判断是否为空，
        // 否则长期不活跃的 IP 队列永远非空、条目永远删不掉（缓慢内存泄漏）。
        if (hits.size() > 1000) {
            hits.entrySet().removeIf(e -> {
                Deque<Long> q = e.getValue();
                synchronized (q) {
                    while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) {
                        q.pollFirst();
                    }
                    return q.isEmpty();
                }
            });
        }
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        String ip = request.getRemoteAddr();
        return (ip == null || ip.isBlank()) ? "unknown" : ip;
    }
}
