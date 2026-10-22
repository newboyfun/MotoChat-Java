package com.motochat.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 内存会话存储，对应 Python 版 core/deps.py 的 _auth_sessions dict。
 *
 * <p>原版用普通 dict + 手写淘汰逻辑（上限 1000、按创建时间踢最旧）。
 * 这里换成 Caffeine：过期和容量淘汰都是现成的，且并发安全。
 *
 * <p>注意：会话存在内存里，重启即失效。这与原版行为一致 —— 单机应用不为这个引入 Redis。
 */
@Component
public class SessionStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final Cache<String, Session> cache;
    private final Duration ttl;

    public SessionStore(@Value("${motochat.session-ttl-days:7}") int ttlDays,
                        @Value("${motochat.session-max:1000}") int maxSessions) {
        this.ttl = Duration.ofDays(ttlDays);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(this.ttl)
                .maximumSize(maxSessions)
                .build();
    }

    /** 生成新 token 并写入会话，返回 token（48 位十六进制，与 Python secrets.token_hex(24) 等长） */
    public String create(SessionUser user) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = HEX.formatHex(bytes);
        cache.put(token, new Session(user, System.currentTimeMillis()));
        return token;
    }

    /** 取会话用户，不存在或已过期返回 null */
    public SessionUser get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Session s = cache.getIfPresent(token);
        return s == null ? null : s.user();
    }

    public void remove(String token) {
        if (token != null) {
            cache.invalidate(token);
        }
    }

    /**
     * 清除某个用户的全部会话，用于改密 / 重置密码 / 删除用户后强制重新登录。
     * Caffeine 没有反查索引，这里遍历 asMap（会话数量上限 1000，代价可接受）。
     */
    public int removeByUsername(String username) {
        int removed = 0;
        for (var entry : cache.asMap().entrySet()) {
            SessionUser u = entry.getValue().user();
            if (u != null && username.equals(u.username())) {
                cache.invalidate(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    /**
     * 清除某个用户除当前 token 外的全部会话。
     *
     * <p>用于自助改密：改密后踢掉其他设备（包括被盗会话），但保留当前设备的登录态，
     * 避免用户改完密码反而被踢回登录页（前端改密成功后只弹提示，不做跳转）。
     */
    public int removeByUsernameExcept(String username, String keepToken) {
        int removed = 0;
        for (var entry : cache.asMap().entrySet()) {
            SessionUser u = entry.getValue().user();
            if (u != null && username.equals(u.username()) && !entry.getKey().equals(keepToken)) {
                cache.invalidate(entry.getKey());
                removed++;
            }
        }
        return removed;
    }

    /** 刷新用户信息（改密码、切人设后调用） */
    public void update(String token, SessionUser user) {
        if (token != null && cache.getIfPresent(token) != null) {
            cache.put(token, new Session(user, System.currentTimeMillis()));
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    private record Session(SessionUser user, long createdAt) {
    }
}
