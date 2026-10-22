package com.motochat.controller;

import com.motochat.common.ApiException;
import com.motochat.dto.ChangePasswordRequest;
import com.motochat.dto.LoginRequest;
import com.motochat.dto.RevealKeyRequest;
import com.motochat.dto.VerifyPasswordRequest;
import com.motochat.entity.User;
import com.motochat.security.AuthFilter;
import com.motochat.security.CurrentUser;
import com.motochat.security.LoginRateLimiter;
import com.motochat.security.RequireAdmin;
import com.motochat.security.RequireLogin;
import com.motochat.security.SessionUser;
import com.motochat.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口。契约与 Python 版 api/auth.py 完全一致。
 * 前端 auth.js 直接消费这些字段，改任何一个都会导致登录页失灵。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter rateLimiter;

    /**
     * 是否信任 X-Forwarded-For。默认 false——该头由客户端完全可控，
     * 无条件信任它等于让登录限流失效（每个请求换个头值就换个"IP"）。
     */
    private final boolean trustForwardedFor;

    public AuthController(AuthService authService, LoginRateLimiter rateLimiter,
                          @Value("${motochat.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.trustForwardedFor = trustForwardedFor;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String ip = clientIp(request);
        AuthService.LoginResult result = authService.login(req, ip);
        User user = result.user();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("token", result.token());
        body.put("username", user.getUsername());
        body.put("role", user.getRole());
        body.put("persona", user.getPersonaName());
        body.put("conversation_id", user.getConversationId());

        // 本地 HTTP 环境，不能设 secure=true；部署到 HTTPS 时应打开。
        ResponseCookie cookie = ResponseCookie.from(AuthFilter.COOKIE_NAME, result.token())
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(7))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(body);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        String token = (String) request.getAttribute(AuthFilter.ATTR_TOKEN);
        authService.logout(token);

        ResponseCookie cookie = ResponseCookie.from(AuthFilter.COOKIE_NAME, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("success", true));
    }

    @GetMapping("/me")
    public SessionUser me(@CurrentUser SessionUser user) {
        if (user == null) {
            throw ApiException.unauthorized("未登录");
        }
        return user;
    }

    @PostMapping("/verify-password")
    @RequireAdmin
    public Map<String, Object> verifyPassword(@CurrentUser(required = true) SessionUser user,
                                              @Valid @RequestBody VerifyPasswordRequest req,
                                              HttpServletRequest request) {
        String ip = clientIp(request);
        if (!rateLimiter.allow(ip)) {
            throw new ApiException(429, "请求过于频繁，请稍后再试");
        }
        try {
            authService.verifyPassword(user, req.password());
        } catch (ApiException e) {
            rateLimiter.recordFailure(ip);
            throw e;
        }
        rateLimiter.clear(ip);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("success", true);
        return body;
    }

    @PostMapping("/reveal-key")
    @RequireAdmin
    public Map<String, Object> revealKey(@CurrentUser(required = true) SessionUser user,
                                         @RequestBody RevealKeyRequest req,
                                         HttpServletRequest request) {
        String ip = clientIp(request);
        if (!rateLimiter.allow(ip)) {
            throw new ApiException(429, "请求过于频繁，请稍后再试");
        }
        try {
            authService.verifyPassword(user, req.password());
        } catch (ApiException e) {
            rateLimiter.recordFailure(ip);
            throw e;
        }
        rateLimiter.clear(ip);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("key", authService.revealKey(req.keyType()));
        return body;
    }

    @PostMapping("/change-password")
    @RequireLogin
    public Map<String, Object> changePassword(@CurrentUser(required = true) SessionUser user,
                                              @Valid @RequestBody ChangePasswordRequest req,
                                              HttpServletRequest request) {
        String token = (String) request.getAttribute(AuthFilter.ATTR_TOKEN);
        authService.changePassword(user, token, req);
        return Map.of("success", true);
    }

    /**
     * 取客户端 IP。默认只认 remoteAddr；X-Forwarded-For 仅在部署在受信反代之后
     * （motochat.trust-forwarded-for=true）才采信，否则限流可被自定义头绕过。
     */
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
