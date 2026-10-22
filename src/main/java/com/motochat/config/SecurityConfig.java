package com.motochat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.motochat.security.LegacyPbkdf2Encoder;

/**
 * Spring Security 配置。
 *
 * <p>本项目不用 Security 的表单登录 / Basic / session，凭据处理全在
 * {@link com.motochat.security.AuthFilter} + {@link com.motochat.security.AuthInterceptor}。
 * 这里把 Security 的默认行为全部关掉，只保留它的过滤器链和密码编码器。
 *
 * <p>为什么不走 Security 的 {@code authorizeHttpRequests}：我们的会话是自定义
 * 内存 token，不是 UserDetails；硬塞进 SecurityContext 要多写一层适配器，
 * 而拦截器方案代码量更少、也更好排查。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 默认 DENY 会导致页面无法被 iframe 预览，放宽到同源
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            // 鉴权交给 AuthInterceptor，这里全部放行
            .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder(LegacyPbkdf2Encoder encoder) {
        return encoder;
    }
}
