package com.motochat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析请求凭据并注入当前用户。
 *
 * <p>凭据来源与原版 core/deps.py 一致，只接受两种：
 * <ol>
 *   <li>{@code Authorization: Bearer <token>}</li>
 *   <li>HttpOnly Cookie {@code kc_token}</li>
 * </ol>
 * <b>刻意不支持 {@code ?token=} query 参数</b> —— 原版注释里写明了原因：
 * access log 会把带 token 的 URL 落盘，造成凭据泄露。这条设计要保留。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER = "motochat.currentUser";
    public static final String ATTR_TOKEN = "motochat.token";
    public static final String COOKIE_NAME = "kc_token";

    private final SessionStore sessionStore;

    public AuthFilter(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            SessionUser user = sessionStore.get(token);
            if (user != null) {
                request.setAttribute(ATTR_USER, user);
                request.setAttribute(ATTR_TOKEN, token);
            }
        }
        chain.doFilter(request, response);
    }

    /** 从 Authorization header 或 Cookie 取 token，取不到返回 null */
    public static String resolveToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = auth.substring(7).trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE_NAME.equals(c.getName())) {
                    String v = c.getValue();
                    return (v == null || v.isBlank()) ? null : v.trim();
                }
            }
        }
        return null;
    }
}
