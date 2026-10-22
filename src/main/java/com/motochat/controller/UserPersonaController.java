package com.motochat.controller;

import com.motochat.common.ApiException;
import com.motochat.security.AuthFilter;
import com.motochat.security.CurrentUser;
import com.motochat.security.RequireLogin;
import com.motochat.security.SessionStore;
import com.motochat.security.SessionUser;
import com.motochat.service.UserPersonaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户级角色接口。契约对齐 Python 版 api/user_persona.py 的 12 个端点。
 */
@RestController
@RequestMapping("/api")
public class UserPersonaController {

    private final UserPersonaService userPersonaService;
    private final SessionStore sessionStore;

    public UserPersonaController(UserPersonaService userPersonaService, SessionStore sessionStore) {
        this.userPersonaService = userPersonaService;
        this.sessionStore = sessionStore;
    }

    @GetMapping("/user/personas")
    @RequireLogin
    public Map<String, Object> list(@CurrentUser(required = true) SessionUser user) {
        return userPersonaService.list(user.username());
    }

    @GetMapping("/user/persona/{name}/preview")
    @RequireLogin
    public Map<String, Object> preview(@PathVariable String name,
                                       @CurrentUser(required = true) SessionUser user) {
        return userPersonaService.preview(name, user.username());
    }

    @PostMapping("/user/persona/create")
    @RequireLogin
    public Map<String, Object> create(@Valid @RequestBody CreateUserPersonaRequest req,
                                      @CurrentUser(required = true) SessionUser user) {
        userPersonaService.create(user.username(), req.name(), req.content());
        return Map.of("success", true);
    }

    @PostMapping("/user/persona/rename")
    @RequireLogin
    public Map<String, Object> rename(@Valid @RequestBody RenameRequest req,
                                      @CurrentUser(required = true) SessionUser user) {
        userPersonaService.rename(user.username(), req.oldName(), req.newName());
        return Map.of("success", true);
    }

    @GetMapping("/user/persona/{name}")
    @RequireLogin
    public Map<String, Object> detail(@PathVariable String name,
                                      @CurrentUser(required = true) SessionUser user) {
        return userPersonaService.detail(name, user.username());
    }

    @PostMapping("/user/persona/reset")
    @RequireLogin
    public Map<String, Object> reset(@RequestBody PersonaNameRequest req,
                                     @CurrentUser(required = true) SessionUser user) {
        userPersonaService.reset(user.username(), req.name());
        return Map.of("success", true);
    }

    @PostMapping("/user/persona/select")
    @RequireLogin
    public Map<String, Object> select(@RequestBody PersonaNameRequest req,
                                      @CurrentUser(required = true) SessionUser user,
                                      HttpServletRequest request) {
        userPersonaService.select(user.username(), req.name());
        // 同步会话快照，让 /api/auth/me 立即反映新角色
        String token = (String) request.getAttribute(AuthFilter.ATTR_TOKEN);
        sessionStore.update(token, new SessionUser(user.username(), user.role(), req.name(), user.conversationId()));
        return Map.of("success", true);
    }

    @PostMapping("/user/persona/save")
    @RequireLogin
    public Map<String, Object> save(@Valid @RequestBody SaveUserPersonaRequest req,
                                    @CurrentUser(required = true) SessionUser user) {
        userPersonaService.save(user.username(), req.name(), req.content());
        return Map.of("success", true);
    }

    @GetMapping("/user/pending-overrides")
    @RequireLogin
    public Map<String, Object> pendingOverrides(@CurrentUser(required = true) SessionUser user) {
        return userPersonaService.pendingOverrides(user.username());
    }

    @PostMapping("/user/approve-override")
    @RequireLogin
    public Map<String, Object> approveOverride(@RequestBody ApproveOverrideRequest req,
                                               @CurrentUser(required = true) SessionUser user) {
        userPersonaService.approveOverride(user.username(), req.persona(), req.approve());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("action", req.approve() ? "approved" : "rejected");
        return body;
    }

    @GetMapping("/user/persona-library")
    @RequireLogin
    public Map<String, Object> library(@CurrentUser(required = true) SessionUser user) {
        return userPersonaService.library(user.username());
    }

    @PostMapping("/user/persona/apply-library")
    @RequireLogin
    public Map<String, Object> applyLibrary(@RequestBody ApplyLibraryRequest req,
                                            @CurrentUser(required = true) SessionUser user) {
        userPersonaService.applyLibrary(user.username(), req.libraryName(), req.persona());
        return Map.of("success", true);
    }

    // ==================== 请求体 ====================

    public record CreateUserPersonaRequest(
            @NotBlank @Size(max = 32) String name,
            String content
    ) {
        public CreateUserPersonaRequest {
            if (content == null || content.isBlank()) {
                content = "# 角色\n\n你是一个友好的AI助手。";
            }
        }
    }

    public record RenameRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("old_name") String oldName,
            @com.fasterxml.jackson.annotation.JsonProperty("new_name") @NotBlank @Size(max = 32) String newName
    ) {
    }

    public record PersonaNameRequest(@NotBlank String name) {
    }

    public record SaveUserPersonaRequest(
            @NotBlank @Size(max = 32) String name,
            @Size(max = 50000) String content
    ) {
    }

    public record ApproveOverrideRequest(String persona, Boolean approve) {
        public ApproveOverrideRequest {
            if (approve == null) {
                approve = true;
            }
        }
    }

    public record ApplyLibraryRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("library_name") String libraryName,
            String persona
    ) {
    }
}
