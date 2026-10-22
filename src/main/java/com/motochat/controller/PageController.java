package com.motochat.controller;

import com.motochat.security.AuthFilter;
import com.motochat.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 页面路由。对应 Python 版 server.py 的 @app.get("/") 那段。
 *
 * <p>三个 HTML 是完整的单页应用，服务端只负责按角色把对的那个吐出去。
 * 这里不用模板引擎 —— 原版就是直接读文件返回，保持等价。
 */
@RestController
public class PageController {

    private static final Logger log = LoggerFactory.getLogger(PageController.class);

    private final ResourceLoader resourceLoader;

    public PageController(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /** 根路径：已登录按角色分流，未登录给登录页 */
    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> index(HttpServletRequest request) {
        SessionUser user = currentUser(request);
        if (user == null) {
            return html("login.html");
        }
        return html(user.isAdmin() ? "index.html" : "user.html");
    }

    @GetMapping(value = "/login", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> login() {
        return html("login.html");
    }

    @GetMapping(value = "/admin", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> admin(HttpServletRequest request) {
        SessionUser user = currentUser(request);
        if (user == null || !user.isAdmin()) {
            return redirect("/login");
        }
        return html("index.html");
    }

    @GetMapping(value = "/user", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> user(HttpServletRequest request) {
        SessionUser user = currentUser(request);
        if (user == null || !"user".equals(user.role())) {
            return redirect("/login");
        }
        return html("user.html");
    }

    /** 旧路径兼容：用户管理已内嵌在主页面 */
    @GetMapping("/admin/users")
    public ResponseEntity<Void> adminUsers() {
        return redirect("/admin");
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    private static SessionUser currentUser(HttpServletRequest request) {
        return (SessionUser) request.getAttribute(AuthFilter.ATTR_USER);
    }

    private ResponseEntity<String> html(String name) {
        Resource resource = resourceLoader.getResource("classpath:static/" + name);
        if (!resource.exists()) {
            log.warn("静态页面缺失: {}", name);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body("<h1>页面未找到</h1><p>缺少 static/" + name + "</p>");
        }
        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .body(content);
        } catch (IOException e) {
            log.error("页面读取失败: {}", name, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static <T> ResponseEntity<T> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", location).build();
    }
}
