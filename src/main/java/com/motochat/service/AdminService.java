package com.motochat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.motochat.common.ApiException;
import com.motochat.common.FileHelper;
import com.motochat.common.JsonHelper;
import com.motochat.entity.User;
import com.motochat.llm.LlmGateway;
import com.motochat.mapper.UserMapper;
import com.motochat.security.LegacyPbkdf2Encoder;
import com.motochat.security.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 管理员业务：用户管理、用户视角查看、角色模板库。
 * 对应 Python 版 api/admin.py。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_\\-\\u4e00-\\u9fff]{1,32}$");

    private final UserMapper userMapper;
    private final LegacyPbkdf2Encoder passwordEncoder;
    private final SessionStore sessionStore;
    private final ConversationService conversationService;
    private final MemoryService memoryService;
    private final ConfigService configService;
    private final FileHelper fileHelper;

    public AdminService(UserMapper userMapper,
                        LegacyPbkdf2Encoder passwordEncoder,
                        SessionStore sessionStore,
                        ConversationService conversationService,
                        MemoryService memoryService,
                        ConfigService configService,
                        FileHelper fileHelper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.conversationService = conversationService;
        this.memoryService = memoryService;
        this.configService = configService;
        this.fileHelper = fileHelper;
    }

    // ==================== 用户列表 ====================

    /** 列出普通用户（不含 admin，与原版只遍历 web.users 的行为一致） */
    public List<Map<String, Object>> listUsers() {
        List<User> rows = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .ne(User::getRole, "admin")
                .orderByAsc(User::getId));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (User u : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.getUsername());
            m.put("role", u.getRole() == null ? "user" : u.getRole());
            m.put("persona", u.getPersonaName() == null || u.getPersonaName().isBlank() ? "MONO" : u.getPersonaName());
            m.put("conversation_id", u.getConversationId() == null
                    ? "user_" + u.getUsername() : u.getConversationId());
            m.put("has_custom_persona_prompt", false);
            m.put("persona_prompt", "");
            m.put("persona_override", JsonHelper.parseStringMap(u.getPersonaOverride()));
            m.put("pending_persona_override", JsonHelper.parseStringMap(u.getPendingOverride()));
            m.put("library_access", Boolean.TRUE.equals(u.getLibraryAccess()));
            m.put("allowed_personas", u.getAllowedPersonas() == null || u.getAllowedPersonas().isBlank()
                    ? null : JsonHelper.parseStringList(u.getAllowedPersonas()));
            m.put("user_personas", JsonHelper.parseStringMap(u.getUserPersonas()));
            out.add(m);
        }
        return out;
    }

    // ==================== 用户 CRUD ====================

    @Transactional
    public void createUser(String username, String password, String persona) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw ApiException.badRequest("用户名只能包含字母、数字、下划线、中划线和中文");
        }
        if (password == null || password.length() < 4) {
            throw ApiException.badRequest("密码至少 4 位");
        }
        if (findUser(username) != null) {
            throw ApiException.badRequest("用户名已存在");
        }
        String safePersona = FileHelper.safePersonaName(persona);
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRole("user");
        u.setPersonaName(safePersona == null ? "MONO" : safePersona);
        u.setConversationId("user_" + username);
        u.setUserPersonas("{}");
        u.setPersonaOverride("{}");
        u.setPendingOverride("{}");
        u.setAllowedPersonas(null);
        u.setLibraryAccess(false);
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) {
            // 上面的重名预检与 insert 之间有竞态：并发建同名用户时由唯一索引拦截，
            // 这里转成友好的 400，而不是漏成 500
            throw ApiException.badRequest("用户名已存在");
        }
        log.info("创建用户: {}", username);
    }

    @Transactional
    public void deleteUser(String username) {
        User target = findUser(username);
        if (target == null) {
            throw new ApiException(404, "操作失败");
        }
        // 管理员与普通用户现在同表（迁移时 config.json 的 web.admin 并入了 users 表），
        // 而原版的管理员根本不在 web.users 里、结构上就删不到。这里必须拦住：
        // 删掉唯一管理员后没人能登录管理端，且下面的级联删除不可回滚。
        if (target.isAdmin()) {
            throw ApiException.badRequest("不能删除管理员账号");
        }
        userMapper.deleteById(target.getId());
        sessionStore.removeByUsername(username);

        String cid = target.getConversationId() == null ? "user_" + username : target.getConversationId();
        conversationService.deleteConversationData(cid);

        // 清理各角色下的记忆目录（记忆目录名通常是用户名，兼容旧版用 cid）
        try {
            if (Files.isDirectory(fileHelper.avatarsRoot())) {
                try (var stream = Files.list(fileHelper.avatarsRoot())) {
                    for (Path personaDir : stream.filter(Files::isDirectory).toList()) {
                        for (String key : List.of(username, cid)) {
                            Path memDir = personaDir.resolve("memory").resolve(key);
                            if (Files.isDirectory(memDir)) {
                                deleteRecursively(memDir);
                                log.info("已清理用户记忆: {}", memDir);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("删除用户 {} 数据清理失败: {}", username, e.getMessage());
        }
        log.info("已删除用户: {}", username);
    }

    @Transactional
    public void updateUser(Map<String, Object> req) {
        String username = str(req.get("username"));
        if (username == null || username.isBlank()) {
            throw ApiException.badRequest("用户名不能为空");
        }
        User user = findUser(username);
        if (user == null) {
            throw new ApiException(404, "操作失败");
        }

        var update = Wrappers.<User>lambdaUpdate().eq(User::getId, user.getId());
        boolean changed = false;

        String password = str(req.get("password"));
        if (password != null && !password.isBlank()) {
            if (password.length() < 4) {
                throw ApiException.badRequest("密码至少 4 位");
            }
            update.set(User::getPasswordHash, passwordEncoder.encode(password));
            changed = true;
        }

        String persona = str(req.get("persona"));
        if (persona != null && !persona.isBlank()) {
            String safe = FileHelper.safePersonaName(persona);
            if (safe == null) {
                throw ApiException.badRequest("无效的角色名");
            }
            update.set(User::getPersonaName, safe);
            changed = true;
        }

        if (req.containsKey("persona_override") && req.get("persona_override") != null) {
            Object override = req.get("persona_override");
            if (!(override instanceof Map)) {
                // 非对象（如字符串）会被 toJson 存成 JSON 字符串，之后 parseStringMap
                // 解析失败静默变成 {}——管理员以为保存成功，实际覆盖内容丢失
                throw ApiException.badRequest("persona_override 格式不正确，应为 {\"角色名\": \"内容\"} 对象");
            }
            update.set(User::getPersonaOverride, JsonHelper.toJson(override));
            changed = true;
        }

        if (req.containsKey("library_access") && req.get("library_access") != null) {
            update.set(User::getLibraryAccess, toBoolean(req.get("library_access")));
            changed = true;
        }

        // allowed_personas 必须按「字段是否存在」判断：前端用 null 表示恢复"全部允许"
        if (req.containsKey("allowed_personas")) {
            Object allowed = req.get("allowed_personas");
            update.set(User::getAllowedPersonas, allowed == null ? null : JsonHelper.toJson(allowed));
            changed = true;
        }

        String apiKey = str(req.get("api_key"));
        String baseUrl = str(req.get("base_url"));
        String model = str(req.get("model"));
        if (apiKey != null || baseUrl != null || model != null) {
            Map<String, String> cfg = JsonHelper.parseStringMap(user.getApiConfig());
            if (apiKey != null) {
                cfg.put("api_key", apiKey);
            }
            if (baseUrl != null) {
                cfg.put("base_url", baseUrl);
            }
            if (model != null) {
                cfg.put("model", model);
            }
            update.set(User::getApiConfig, JsonHelper.toJson(cfg));
            changed = true;
        }

        if (!changed) {
            return;
        }
        userMapper.update(null, update);
        if (password != null && !password.isBlank()) {
            // 改密后强制重新登录，与原版行为一致
            sessionStore.removeByUsername(username);
        }
    }

    @Transactional
    public void resetPassword(String username, String newPassword) {
        if (newPassword == null || newPassword.length() < 4) {
            throw ApiException.badRequest("密码至少 4 位");
        }
        User user = findUser(username);
        if (user == null) {
            throw new ApiException(404, "操作失败");
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getPasswordHash, passwordEncoder.encode(newPassword)));
        sessionStore.removeByUsername(username);
    }

    @Transactional
    public void setPersona(String username, String rawPersona) {
        String persona = FileHelper.safePersonaName(rawPersona);
        if (persona == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User user = findUser(username);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        boolean systemPersona = Files.isRegularFile(fileHelper.personaMarkdown(persona));
        if (!systemPersona) {
            Map<String, String> userPersonas = JsonHelper.parseStringMap(user.getUserPersonas());
            if (!userPersonas.containsKey(persona)) {
                throw new ApiException(404, "角色 " + persona + " 不存在");
            }
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId()).set(User::getPersonaName, persona));
    }

    // ==================== 用户视角查看 ====================

    public Map<String, Object> userMessages(String username, int limit, int offset) {
        User user = findUser(username);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        String cid = user.getConversationId() == null ? "user_" + username : user.getConversationId();
        ConversationService.HistoryPage page = conversationService.getHistory(cid, limit, offset);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("conversation_id", cid);
        body.put("messages", page.messages());
        body.put("has_more", page.hasMore());
        body.put("next_offset", page.nextOffset());
        return body;
    }

    public Map<String, Object> userMemory(String username, int limit) {
        User user = findUser(username);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        String persona = (user.getPersonaName() == null || user.getPersonaName().isBlank())
                ? "MONO" : user.getPersonaName();
        List<LlmGateway.ChatMessage> history = memoryService.getRecentContext(persona, username);
        // limit 来自 query 参数，可能是负数或极大值：负数会让 from 大于 size，
        // subList 直接抛 IndexOutOfBoundsException 变成 500
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int from = Math.max(0, history.size() - safeLimit * 2);
        List<Map<String, String>> mem = new ArrayList<>();
        for (LlmGateway.ChatMessage m : history.subList(from, history.size())) {
            mem.add(Map.of("role", m.role(), "content", m.content()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("persona", persona);
        body.put("memory", mem);
        return body;
    }

    public Map<String, Object> userPersonaDetail(String username, String rawName) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        Path md = fileHelper.personaMarkdown(name);
        if (!Files.isRegularFile(md)) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        String base;
        try {
            base = Files.readString(md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        User user = findUser(username);
        Map<String, String> overrides = JsonHelper.parseStringMap(user == null ? null : user.getPersonaOverride());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("name", name);
        body.put("base_content", base);
        body.put("override_content", overrides.getOrDefault(name, ""));
        return body;
    }

    @Transactional
    public void saveUserPersona(String username, String rawName, String content) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User user = findUser(username);
        if (user == null) {
            throw new ApiException(404, "操作失败");
        }
        Map<String, String> overrides = JsonHelper.parseStringMap(user.getPersonaOverride());
        overrides.put(name, content == null ? "" : content);
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getPersonaOverride, JsonHelper.toJson(overrides)));
    }

    public Map<String, Object> userApiConfig(String username) {
        User user = findUser(username);
        if (user == null) {
            throw new ApiException(404, "用户不存在");
        }
        Map<String, String> cfg = JsonHelper.parseStringMap(user.getApiConfig());
        String apiKey = cfg.getOrDefault("api_key", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("api_key_masked", FileHelper.maskKey(apiKey));
        body.put("has_api_key", !apiKey.isBlank());
        body.put("base_url", cfg.getOrDefault("base_url", ""));
        body.put("model", cfg.getOrDefault("model", ""));
        body.put("has_custom_api", !apiKey.isBlank());
        return body;
    }

    public List<Map<String, Object>> allApiConfigs() {
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .ne(User::getRole, "admin").orderByAsc(User::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users) {
            Map<String, String> cfg = JsonHelper.parseStringMap(u.getApiConfig());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.getUsername());
            m.put("has_custom_api", !cfg.getOrDefault("api_key", "").isBlank());
            m.put("base_url", cfg.getOrDefault("base_url", ""));
            m.put("model", cfg.getOrDefault("model", ""));
            out.add(m);
        }
        return out;
    }

    // ==================== 管理员人设覆盖 ====================

    @Transactional
    public void setAdminPersonaOverride(String persona, String content) {
        String name = FileHelper.safePersonaName(persona);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User admin = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getRole, "admin")
                .last("LIMIT 1"));
        if (admin == null) {
            throw new ApiException(404, "管理员账号不存在");
        }
        Map<String, String> overrides = JsonHelper.parseStringMap(admin.getPersonaOverride());
        overrides.put(name, content == null ? "" : content);
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, admin.getId())
                .set(User::getPersonaOverride, JsonHelper.toJson(overrides)));
    }

    // ==================== 角色模板库 ====================

    public List<Map<String, Object>> personaLibrary() {
        return configService.personaLibrary();
    }

    public void saveLibraryItem(String name, String content, String personaName, boolean isPublic) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("名称不能为空");
        }
        List<Map<String, Object>> library = configService.personaLibrary();
        boolean found = false;
        for (Map<String, Object> item : library) {
            if (name.equals(String.valueOf(item.get("name")))) {
                item.put("name", name);
                item.put("content", content == null ? "" : content);
                item.put("persona_name", personaName == null ? "" : personaName);
                item.put("public", isPublic);
                found = true;
                break;
            }
        }
        if (!found) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("content", content == null ? "" : content);
            item.put("persona_name", personaName == null ? "" : personaName);
            item.put("public", isPublic);
            library.add(item);
        }
        configService.update("web.persona_library", library);
    }

    public void deleteLibraryItem(String name) {
        List<Map<String, Object>> library = configService.personaLibrary();
        library.removeIf(item -> name != null && name.equals(String.valueOf(item.get("name"))));
        configService.update("web.persona_library", library);
    }

    // ==================== 内部 ====================

    private User findUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 宽松布尔转换。
     * 不能用 Boolean.parseBoolean —— 它对数字 1 返回 false（它只认 "true" 字符串），
     * 前端/脚本传 1 会被静默当成关闭。
     */
    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.deleteIfExists(dir);
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(dir);
    }
}
