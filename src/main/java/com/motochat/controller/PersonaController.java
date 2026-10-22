package com.motochat.controller;

import com.motochat.common.ApiException;
import com.motochat.security.RequireAdmin;
import com.motochat.security.RequireLogin;
import com.motochat.service.PersonaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统角色管理。契约对齐 Python 版 api/personas.py 的 11 个端点。
 */
@RestController
@RequestMapping("/api/personas")
public class PersonaController {

    private final PersonaService personaService;

    public PersonaController(PersonaService personaService) {
        this.personaService = personaService;
    }

    @GetMapping
    @RequireLogin
    public Map<String, Object> list() {
        return Map.of("personas", personaService.list());
    }

    @GetMapping("/{name}")
    @RequireLogin
    public Map<String, Object> detail(@PathVariable String name) {
        String safe = com.motochat.common.FileHelper.safePersonaName(name);
        if (safe == null) {
            throw ApiException.badRequest("名称不合法");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", safe);
        body.put("content", personaService.readContent(safe));
        return body;
    }

    @GetMapping("/{name}/prompt")
    @RequireLogin
    public Map<String, Object> prompt(@PathVariable String name) {
        String safe = com.motochat.common.FileHelper.safePersonaName(name);
        if (safe == null) {
            throw ApiException.badRequest("名称不合法");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", safe);
        body.put("content", personaService.readContent(safe).strip());
        return body;
    }

    @PostMapping("/create")
    @RequireAdmin
    public Map<String, Object> create(@Valid @RequestBody CreatePersonaRequest req) {
        personaService.create(req.name(), req.content());
        return Map.of("success", true);
    }

    @PostMapping("/{name}/update")
    @RequireAdmin
    public Map<String, Object> update(@PathVariable String name, @RequestBody ContentRequest req) {
        personaService.update(name, req.content());
        return Map.of("success", true);
    }

    @PostMapping("/{name}/delete")
    @RequireAdmin
    public Map<String, Object> delete(@PathVariable String name) {
        personaService.delete(name);
        return Map.of("success", true);
    }

    @PostMapping("/{name}/prompt/update")
    @RequireAdmin
    public Map<String, Object> updatePrompt(@PathVariable String name, @RequestBody ContentRequest req) {
        personaService.update(name, req.content());
        return Map.of("success", true);
    }

    @PostMapping("/switch")
    @RequireAdmin
    public Map<String, Object> switchPersona(@Valid @RequestBody SwitchPersonaRequest req) {
        personaService.switchTo(req.name());
        return Map.of("success", true);
    }

    /** 头像：优先用户上传，否则 302 到内置静态资源（与原版行为一致） */
    @GetMapping("/{name}/avatar")
    public ResponseEntity<Resource> avatar(@PathVariable String name) {
        Path file = personaService.avatarFile(name);
        if (file != null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new FileSystemResource(file));
        }
        String builtin = personaService.builtinAvatarFor(name);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/static/assets/" + builtin)
                .build();
    }

    @PostMapping("/{name}/avatar")
    @RequireAdmin
    public Map<String, Object> uploadAvatar(@PathVariable String name,
                                            @RequestParam("file") MultipartFile file) {
        try {
            byte[] data = file.getBytes();
            long version = personaService.saveAvatar(name, data);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("version", version);
            return body;
        } catch (IOException e) {
            throw ApiException.badRequest("文件读取失败");
        }
    }

    @DeleteMapping("/{name}/avatar")
    @RequireAdmin
    public Map<String, Object> deleteAvatar(@PathVariable String name) {
        personaService.deleteAvatar(name);
        return Map.of("success", true);
    }

    // ==================== 请求体 ====================

    public record CreatePersonaRequest(
            @NotBlank @Size(max = 32, message = "名称最长 32 字") String name,
            String content
    ) {
        public CreatePersonaRequest {
            if (content == null || content.isBlank()) {
                content = "# 角色\n\n你是一个友好的AI助手。";
            }
        }
    }

    public record ContentRequest(String content) {
    }

    public record SwitchPersonaRequest(@NotBlank String name) {
    }
}
