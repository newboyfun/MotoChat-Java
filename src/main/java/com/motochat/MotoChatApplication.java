package com.motochat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排除 UserDetailsServiceAutoConfiguration：本项目的认证走自定义内存 token，
 * 不需要 Spring Security 的默认用户，否则启动日志会打印一个没用的随机密码。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@MapperScan("com.motochat.mapper")
public class MotoChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(MotoChatApplication.class, args);
    }
}
