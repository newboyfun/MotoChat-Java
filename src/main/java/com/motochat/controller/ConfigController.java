package com.motochat.controller;

import com.motochat.common.ApiException;
import com.motochat.common.FileHelper;
import com.motochat.common.JsonHelper;
import com.motochat.entity.User;
import com.motochat.mapper.UserMapper;
import com.motochat.security.CurrentUser;
import com.motochat.security.RequireAdmin;
import com.motochat.security.RequireLogin;
import com.motochat.security.SessionUser;
import com.motochat.service.ChatService;
import com.motochat.service.ConfigService;
import com.motochat.service.ConversationService;
import com.motochat.service.LogService;
import com.motochat.service.PersonaState;
import com.motochat.service.ReminderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置、统计、日志、Token 用量、用户 API 配置。
 * 契约对齐 Python 版 api/config_routes.py 的 9 个端点。
 */
@RestController
@RequestMapping("/api")
public class ConfigController {

    /** 允许通过 /config/save 修改的字段白名单，与原版逐项一致 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "llm.api_key", "llm.base_url", "llm.model", "llm.max_tokens", "llm.temperature",
            "llm.max_context_rounds", "llm.auto_model_switch",
            "vision.api_key", "vision.base_url", "vision.model", "vision.temperature",
            "tts.api_key", "tts.model_id", "tts.base_url",
            "behavior.avatar_dir",
            "behavior.auto_message.content", "behavior.auto_message.min_hours", "behavior.auto_message.max_hours",
            "behavior.quiet_time.start", "behavior.quiet_time.end",
            "behavior.queue_timeout");

    private final ConfigService configService;
    private final ConversationService conversationService;
    private final ReminderService reminderService;
    private final ChatService chatService;
    private final PersonaState personaState;
    private final LogService logService;
    private final UserMapper userMapper;
    private final FileHelper fileHelper;

    public ConfigController(ConfigService configService,
                            ConversationService conversationService,
                            ReminderService reminderService,
                            ChatService chatService,
                            PersonaState personaState,
                            LogService logService,
                            UserMapper userMapper,
                            FileHelper fileHelper) {
        this.configService = configService;
        this.conversationService = conversationService;
        this.reminderService = reminderService;
        this.chatService = chatService;
        this.personaState = personaState;
        this.logService = logService;
        this.userMapper = userMapper;
        this.fileHelper = fileHelper;
    }

    // ==================== 配置 ====================

    @GetMapping("/config")
    @RequireLogin
    public Map<String, Object> getConfig(@CurrentUser(required = true) SessionUser user) {
        if (!user.isAdmin()) {
            // 普通用户只看到自己的角色名，不下发服务器路径
            User dbUser = findUser(user.username());
            Map<String, String> overrides = JsonHelper.parseStringMap(dbUser == null ? null : dbUser.getPersonaOverride());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user_persona", personaState.name());
            body.put("persona_prompt", overrides.getOrDefault(personaState.name(), ""));
            return body;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key_masked", FileHelper.maskKey(configService.getString("llm.api_key", "")));
        body.put("base_url", configService.getString("llm.base_url", ""));
        body.put("model", configService.getString("llm.model", ""));
        body.put("temperature", configService.getDouble("llm.temperature", 0));
        body.put("max_tokens", configService.getInt("llm.max_tokens", 0));
        body.put("max_context_rounds", configService.getInt("llm.max_context_rounds", 0));
        body.put("vision_api_key", FileHelper.maskKey(configService.getString("vision.api_key", "")));
        body.put("vision_base_url", configService.getString("vision.base_url", ""));
        body.put("vision_model", configService.getString("vision.model", ""));
        User admin = findUser(user.username());
        body.put("admin_persona_override",
                JsonHelper.parseStringMap(admin == null ? null : admin.getPersonaOverride()));
        body.put("tts_api_key", FileHelper.maskKey(configService.getString("tts.api_key", "")));
        body.put("tts_base_url", configService.getString("tts.base_url", ""));
        body.put("tts_model_id", configService.getString("tts.model_id", ""));
        return body;
    }

    @PostMapping("/config/save")
    @RequireAdmin
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> req) {
        // 先整体校验，任一项不合法则一条都不写（避免留下半成品配置）
        for (Map.Entry<String, Object> e : req.entrySet()) {
            String path = e.getKey();
            Object value = e.getValue();
            if (!ALLOWED_KEYS.contains(path)) {
                throw ApiException.badRequest("不允许修改 " + path);
            }
            if (path.endsWith(".api_key") && value instanceof String s && s.contains("***")) {
                throw ApiException.badRequest(path + " 为掩码值，已拒绝保存；如需修改请输入完整 key");
            }
            String err = validateConfigValue(path, value);
            if (err != null) {
                throw ApiException.badRequest(err);
            }
        }
        // 跨字段校验：主动消息的空闲区间 min_hours 不能大于 max_hours，
        // 否则 AutoSendService 的随机排期会算出负延迟
        Double minH = effectiveHours(req, "behavior.auto_message.min_hours");
        Double maxH = effectiveHours(req, "behavior.auto_message.max_hours");
        if (minH != null && maxH != null && minH > maxH) {
            throw ApiException.badRequest("behavior.auto_message.min_hours 不能大于 max_hours");
        }
        configService.updateMany(new LinkedHashMap<>(req));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("updated", req.size());
        return body;
    }

    /**
     * 单字段值校验。返回 null 表示合法，否则为错误信息。
     *
     * <p>为什么需要：白名单只保证了"哪些键能改"，没管"值是什么形态"。
     * 温度传 99、max_tokens 传 -5、静默时段传 "25:99" 都能存进去，
     * 然后 LLM 请求带着非法参数被提供商拒绝、或静默时段永远不成立——
     * 现象是"聊天突然全废"但配置页看起来正常，极难排查。
     */
    static String validateConfigValue(String path, Object value) {
        if (value == null) {
            return null;                       // null 视为清空，由读取方的默认值兜底
        }
        // 字符串类字段
        if (path.endsWith(".api_key") || path.endsWith(".base_url") || path.endsWith(".model")
                || path.endsWith(".model_id") || path.endsWith(".content")
                || path.endsWith(".avatar_dir")) {
            if (!(value instanceof String s)) {
                return path + " 必须是字符串";
            }
            if (s.length() > 2048) {
                return path + " 过长（最长 2048 字符）";
            }
            if (path.endsWith(".base_url") && !s.isBlank()
                    && !s.startsWith("http://") && !s.startsWith("https://")) {
                return path + " 必须以 http:// 或 https:// 开头";
            }
            return null;
        }
        // 数值类字段
        if (path.endsWith(".temperature")) {
            Double d = toDouble(value);
            if (d == null) {
                return path + " 必须是数字";
            }
            if (d < 0 || d > 2) {
                return path + " 应在 0 ~ 2 之间";
            }
            return null;
        }
        if (path.endsWith(".max_tokens")) {
            Long n = toLong(value);
            if (n == null) {
                return path + " 必须是整数";
            }
            if (n < 1 || n > 200_000) {
                return path + " 应在 1 ~ 200000 之间";
            }
            return null;
        }
        if (path.endsWith(".max_context_rounds")) {
            Long n = toLong(value);
            if (n == null) {
                return path + " 必须是整数";
            }
            if (n < 1 || n > 100) {
                return path + " 应在 1 ~ 100 之间";
            }
            return null;
        }
        if (path.endsWith(".min_hours") || path.endsWith(".max_hours")) {
            Double d = toDouble(value);
            if (d == null) {
                return path + " 必须是数字";
            }
            if (d <= 0 || d > 168) {
                return path + " 应在 0 ~ 168 小时之间";
            }
            return null;
        }
        if (path.endsWith(".start") || path.endsWith(".end")) {
            if (!(value instanceof String s) || !s.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                return path + " 格式应为 HH:mm（如 23:00）";
            }
            return null;
        }
        if (path.endsWith(".queue_timeout")) {
            Long n = toLong(value);
            if (n == null) {
                return path + " 必须是整数";
            }
            if (n < 1 || n > 600) {
                return path + " 应在 1 ~ 600 秒之间";
            }
            return null;
        }
        return null;   // 其余白名单字段（如 auto_model_switch）不限制取值形态
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.floor(d) ? (long) d : null;
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取本次请求或当前配置里的空闲小时数，用于跨字段比较；两边都没有返回 null */
    private Double effectiveHours(Map<String, Object> req, String path) {
        Object v = req.get(path);
        if (v == null) {
            return toDouble(configService.getString(path, ""));
        }
        return toDouble(v);
    }

    // ==================== 统计 ====================

    @GetMapping("/stats")
    @RequireAdmin
    public Map<String, Object> stats() {
        int personaCount = 0;
        try {
            Path root = fileHelper.avatarsRoot();
            if (Files.isDirectory(root)) {
                try (var stream = Files.list(root)) {
                    personaCount = (int) stream.filter(Files::isDirectory)
                            .filter(p -> Files.isRegularFile(p.resolve("avatar.md")))
                            .count();
                }
            }
        } catch (IOException ignored) {
            // 目录不可读时按 0 计
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active_persona", personaState.name());
        body.put("persona_count", personaCount);
        body.put("total_messages", conversationService.countMessages());
        body.put("api_key_configured", !configService.getString("llm.api_key", "").isBlank());
        body.put("reminder_count", reminderService.count());
        return body;
    }

    @GetMapping("/logs")
    @RequireAdmin
    public Map<String, Object> logs(@RequestParam(defaultValue = "all") String level,
                                    @RequestParam(defaultValue = "100") int limit) {
        return Map.of("logs", logService.tail(level, limit));
    }

    // ==================== Token 用量 ====================

    @GetMapping("/token-usage")
    @RequireLogin
    public Map<String, Object> tokenUsage(@CurrentUser(required = true) SessionUser user) {
        return Map.of("usage", conversationService.userTokenUsage(user.username()));
    }

    @GetMapping("/admin/token-usage")
    @RequireAdmin
    public Map<String, Object> adminTokenUsage() {
        List<Map<String, Object>> users = conversationService.allTokenUsage();
        Map<String, Object> total = new LinkedHashMap<>();
        int prompt = 0;
        int completion = 0;
        int sum = 0;
        int requests = 0;
        for (Map<String, Object> u : users) {
            prompt += (int) u.get("prompt_tokens");
            completion += (int) u.get("completion_tokens");
            sum += (int) u.get("total_tokens");
            requests += (int) u.get("request_count");
        }
        total.put("prompt_tokens", prompt);
        total.put("completion_tokens", completion);
        total.put("total_tokens", sum);
        total.put("request_count", requests);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("users", users);
        body.put("total", total);
        return body;
    }

    // ==================== 用户 API 配置 ====================

    @GetMapping("/user/api-config")
    @RequireLogin
    public Map<String, Object> userApiConfig(@CurrentUser(required = true) SessionUser user) {
        User dbUser = findUser(user.username());
        Map<String, Object> cfg = JsonHelper.parseObject(dbUser == null ? null : dbUser.getApiConfig());
        String apiKey = JsonHelper.str(cfg, "api_key", "");
        boolean hasCustom = !apiKey.isBlank();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", FileHelper.maskKey(apiKey));
        body.put("api_key_masked", true);
        body.put("base_url", JsonHelper.str(cfg, "base_url", ""));
        body.put("model", JsonHelper.str(cfg, "model", ""));
        body.put("has_custom_api", hasCustom);
        return body;
    }

    @PostMapping("/user/api-config")
    @RequireLogin
    public Map<String, Object> updateUserApiConfig(@RequestBody UserApiConfigRequest req,
                                                   @CurrentUser(required = true) SessionUser user) {
        if (req.apiKey() != null && req.apiKey().contains("***")) {
            throw ApiException.badRequest("api_key 为掩码值，请输入完整 key");
        }
        User dbUser = findUser(user.username());
        if (dbUser == null) {
            throw new ApiException(404, "用户不存在");
        }
        boolean hasAny = (req.apiKey() != null && !req.apiKey().isBlank())
                || (req.baseUrl() != null && !req.baseUrl().isBlank())
                || (req.model() != null && !req.model().isBlank());

        if (hasAny) {
            Map<String, String> old = JsonHelper.parseStringMap(dbUser.getApiConfig());
            Map<String, Object> cfg = new LinkedHashMap<>();
            // key 留空时保留原 key，允许只改 base_url / model
            cfg.put("api_key", (req.apiKey() == null || req.apiKey().isBlank())
                    ? old.getOrDefault("api_key", "") : req.apiKey());
            cfg.put("base_url", req.baseUrl() == null ? "" : req.baseUrl());
            cfg.put("model", req.model() == null ? "" : req.model());
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, dbUser.getId())
                    .set(User::getApiConfig, JsonHelper.toJson(cfg)));
        } else {
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, dbUser.getId())
                    .set(User::getApiConfig, null));
        }
        return Map.of("success", true);
    }

    @GetMapping("/user/home-stats")
    @RequireLogin
    public Map<String, Object> homeStats(@CurrentUser(required = true) SessionUser user) {
        String cid = user.conversationId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", cid == null ? "" : cid);
        body.put("persona", user.persona() == null ? personaState.name() : user.persona());
        body.put("message_count", (cid == null || cid.isBlank()) ? 0 : conversationService.countMessages(cid));
        return body;
    }

    // ==================== 内部 ====================

    private User findUser(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    public record UserApiConfigRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("api_key") String apiKey,
            @com.fasterxml.jackson.annotation.JsonProperty("base_url") String baseUrl,
            String model
    ) {
    }
}
