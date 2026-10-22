package com.motochat.controller;

import com.motochat.security.RequireAdmin;
import com.motochat.service.AdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理员接口。契约对齐 Python 版 api/admin.py 的 16 个端点。
 *
 * <p>整类标注 @RequireAdmin，由 AuthInterceptor 统一拦截（未登录 401、非管理员 403）。
 */
@RestController
@RequestMapping("/api/admin")
@RequireAdmin
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    // ==================== 用户管理 ====================

    @GetMapping("/users")
    public Map<String, Object> users() {
        return Map.of("users", adminService.listUsers());
    }

    @PostMapping("/users/create")
    public Map<String, Object> createUser(@Valid @RequestBody CreateUserRequest req) {
        adminService.createUser(req.username(), req.password(), req.persona());
        return Map.of("success", true);
    }

    @PostMapping("/users/delete")
    public Map<String, Object> deleteUser(@RequestBody DeleteUserRequest req) {
        adminService.deleteUser(req.username() == null ? "" : req.username().strip());
        return Map.of("success", true);
    }

    /**
     * 更新用户。用 Map 接收而不是 record —— 需要区分「字段不存在」和「字段为 null」：
     * allowed_personas 传 null 表示恢复"全部允许"，用 record 无法区分这两种情况。
     */
    @PostMapping("/users/update")
    public Map<String, Object> updateUser(@RequestBody Map<String, Object> req) {
        adminService.updateUser(req);
        return Map.of("success", true);
    }

    @PostMapping("/users/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        adminService.resetPassword(req.username(), req.newPassword());
        return Map.of("success", true);
    }

    @PostMapping("/users/set-persona")
    public Map<String, Object> setPersona(@Valid @RequestBody SetPersonaRequest req) {
        adminService.setPersona(req.username(), req.persona());
        return Map.of("success", true);
    }

    // ==================== 用户视角查看 ====================

    @GetMapping("/users/{username}/messages")
    public Map<String, Object> userMessages(@PathVariable String username,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        return adminService.userMessages(username, limit, offset);
    }

    @GetMapping("/users/{username}/memory")
    public Map<String, Object> userMemory(@PathVariable String username,
                                          @RequestParam(defaultValue = "20") int limit) {
        return adminService.userMemory(username, limit);
    }

    @GetMapping("/users/{username}/persona/{name}")
    public Map<String, Object> userPersona(@PathVariable String username, @PathVariable String name) {
        return adminService.userPersonaDetail(username, name);
    }

    @PostMapping("/users/{username}/persona/{name}")
    public Map<String, Object> saveUserPersona(@PathVariable String username,
                                               @PathVariable String name,
                                               @RequestBody SaveContentRequest req) {
        adminService.saveUserPersona(username, name, req.content());
        return Map.of("success", true);
    }

    @GetMapping("/users/{username}/api-config")
    public Map<String, Object> userApiConfig(@PathVariable String username) {
        return adminService.userApiConfig(username);
    }

    @GetMapping("/users/api-configs")
    public Map<String, Object> allApiConfigs() {
        return Map.of("users", adminService.allApiConfigs());
    }

    // ==================== 管理员人设覆盖 ====================

    @PostMapping("/persona-override")
    public Map<String, Object> personaOverride(@Valid @RequestBody PersonaOverrideRequest req) {
        adminService.setAdminPersonaOverride(req.persona(), req.content());
        return Map.of("success", true);
    }

    // ==================== 角色模板库 ====================

    @GetMapping("/persona-library")
    public Map<String, Object> personaLibrary() {
        return Map.of("library", adminService.personaLibrary());
    }

    @PostMapping("/persona-library/save")
    public Map<String, Object> saveLibraryItem(@Valid @RequestBody PersonaLibraryRequest req) {
        adminService.saveLibraryItem(req.name(), req.content(), req.personaName(), req.isPublic());
        return Map.of("success", true);
    }

    @PostMapping("/persona-library/delete")
    public Map<String, Object> deleteLibraryItem(@Valid @RequestBody DeleteLibraryRequest req) {
        adminService.deleteLibraryItem(req.name());
        return Map.of("success", true);
    }

    // ==================== 请求体 ====================

    public record CreateUserRequest(
            @NotBlank @Size(max = 32) String username,
            @NotBlank @Size(min = 4, max = 128, message = "密码至少 4 位") String password,
            String persona
    ) {
        public CreateUserRequest {
            if (persona == null || persona.isBlank()) {
                persona = "MONO";
            }
        }
    }

    public record DeleteUserRequest(String username) {
    }

    public record ResetPasswordRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 4, max = 128, message = "密码至少 4 位")
            @com.fasterxml.jackson.annotation.JsonProperty("new_password") String newPassword
    ) {
    }

    public record SetPersonaRequest(@NotBlank String username, @NotBlank String persona) {
    }

    public record SaveContentRequest(String content) {
    }

    public record PersonaOverrideRequest(@NotBlank String persona, String content) {
        public PersonaOverrideRequest {
            if (content == null) {
                content = "";
            }
        }
    }

    public record PersonaLibraryRequest(
            @NotBlank String name,
            String content,
            @com.fasterxml.jackson.annotation.JsonProperty("persona_name") String personaName,
            @com.fasterxml.jackson.annotation.JsonProperty("public") Boolean isPublic
    ) {
        public PersonaLibraryRequest {
            if (content == null) {
                content = "";
            }
            if (personaName == null) {
                personaName = "";
            }
            if (isPublic == null) {
                isPublic = true;
            }
        }
    }

    public record DeleteLibraryRequest(@NotBlank String name) {
    }
}
