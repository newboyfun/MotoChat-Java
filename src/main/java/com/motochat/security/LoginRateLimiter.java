package com.motochat.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败限流，对应 Python 版 auth.py 的 _LOGIN_ATTEMPTS。
 *
 * <p>规则完全一致：同一 IP 60 秒内失败 10 次后拒绝；登录成功后立刻清零。
 * 刻意只统计<b>失败</b>次数 —— 正常用户连续登录成功不该被限流。
 */
@Component
public class LoginRateLimiter {

    private final int limit;
    private final Cache<String, AtomicInteger> failures;

    public LoginRateLimiter(@Value("${motochat.login-rate-limit:10}") int limit) {
        this.limit = Math.max(1, limit);
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(10_000)
                .build();
    }

    /** 是否还允许尝试 */
    public boolean allow(String ip) {
        AtomicInteger c = failures.getIfPresent(ip);
        return c == null || c.get() < limit;
    }

    public void recordFailure(String ip) {
        AtomicInteger c = failures.get(ip, k -> new AtomicInteger());
        if (c != null) {
            c.incrementAndGet();
        }
    }

    public void clear(String ip) {
        failures.invalidate(ip);
    }
}
