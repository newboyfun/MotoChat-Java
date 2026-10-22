package com.motochat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.motochat.common.ApiException;
import com.motochat.common.FileHelper;
import com.motochat.common.JsonHelper;
import com.motochat.entity.User;
import com.motochat.llm.LlmGateway;
import com.motochat.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户级角色：自建角色、对系统角色的个性化覆盖、审批流、模板库。
 * 对应 Python 版 api/user_persona.py 的 12 个端点。
 *
 * <p>原版这些数据全在 config.json 的 web.users[] 里；这里放在 users 表的
 * 5 个 JSON 字段中（user_personas / persona_override / pending_override /
 * allowed_personas / library_access），语义完全对应。
 */
@Service
public class UserPersonaService {

    private static final Logger log = LoggerFactory.getLogger(UserPersonaService.class);

    private final UserMapper userMapper;
    private final ConfigService configService;
    private final PersonaState personaState;
    private final MemoryService memoryService;
    private final FileHelper fileHelper;

    public UserPersonaService(UserMapper userMapper,
                              ConfigService configService,
                              PersonaState personaState,
                              MemoryService memoryService,
                              FileHelper fileHelper) {
        this.userMapper = userMapper;
        this.configService = configService;
        this.personaState = personaState;
        this.memoryService = memoryService;
        this.fileHelper = fileHelper;
    }

    private User requireUser(String username) {
        User u = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (u == null) {
            throw new ApiException(404, "用户不存在");
        }
        return u;
    }

    /** 当前生效的角色名 */
    public String currentPersona(User user) {
        if (user.getPersonaName() != null && !user.getPersonaName().isBlank()) {
            return user.getPersonaName();
        }
        return personaState.name();
    }

    /** 角色列表：系统角色（按 allowed 过滤）+ 用户自建角色 */
    public Map<String, Object> list(String username) {
        User user = requireUser(username);
        List<String> allowed = allowedPersonas(user);
        Map<String, String> userPersonas = JsonHelper.parseStringMap(user.getUserPersonas());
        String current = currentPersona(user);

        List<Map<String, Object>> result = new ArrayList<>();
        Path root = fileHelper.avatarsRoot();
        if (Files.isDirectory(root)) {
            try (var stream = Files.list(root)) {
                List<Path> dirs = stream.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                for (Path dir : dirs) {
                    String name = dir.getFileName().toString();
                    if (!Files.isRegularFile(dir.resolve("avatar.md"))) {
                        continue;
                    }
                    if (allowed != null && !allowed.contains(name)) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("active", name.equals(current));
                    result.add(item);
                }
            } catch (Exception e) {
                log.warn("读取角色目录失败: {}", e.getMessage());
            }
        }

        for (String name : userPersonas.keySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("active", name.equals(current));
            item.put("user_created", true);
            result.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("personas", result);
        body.put("current", current);
        return body;
    }

    /** 角色记忆预览：返回该用户在该角色下的最近 10 条上下文 */
    public Map<String, Object> preview(String rawName, String username) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User user = requireUser(username);
        List<LlmGateway.ChatMessage> history = memoryService.getRecentContext(name, username);
        int from = Math.max(0, history.size() - 10);
        List<Map<String, String>> mem = new ArrayList<>();
        for (LlmGateway.ChatMessage m : history.subList(from, history.size())) {
            mem.add(Map.of("role", m.role(), "content", m.content()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("memory", mem);
        return body;
    }

    @Transactional
    public void create(String username, String rawName, String content) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("请输入角色名称");
        }
        if (Files.isDirectory(fileHelper.personaDir(name))) {
            throw ApiException.badRequest("角色 " + name + " 已存在（系统角色）");
        }
        User user = requireUser(username);
        Map<String, String> userPersonas = JsonHelper.parseStringMap(user.getUserPersonas());
        if (userPersonas.containsKey(name)) {
            throw ApiException.badRequest("角色 " + name + " 已存在");
        }
        userPersonas.put(name, content == null ? "# 角色\n\n你是一个友好的AI助手。" : content);

        List<String> allowed = allowedPersonas(user);
        if (allowed != null && !allowed.contains(name)) {
            allowed.add(name);
        }

        updateJsonFields(user.getId(), userPersonas, allowed, null, null, null);
    }

    @Transactional
    public void rename(String username, String rawOld, String rawNew) {
        String oldName = FileHelper.safePersonaName(rawOld);
        String newName = FileHelper.safePersonaName(rawNew);
        if (oldName == null || newName == null) {
            throw ApiException.badRequest("请提供合法的新旧名称");
        }
        if (oldName.equals(newName)) {
            throw ApiException.badRequest("新旧名称相同");
        }
        User user = requireUser(username);
        Map<String, String> userPersonas = JsonHelper.parseStringMap(user.getUserPersonas());
        if (!userPersonas.containsKey(oldName)) {
            throw new ApiException(404, "角色不存在");
        }
        if (userPersonas.containsKey(newName)) {
            throw ApiException.badRequest("名称已被使用");
        }
        String content = userPersonas.remove(oldName);
        userPersonas.put(newName, content);

        Map<String, String> overrides = JsonHelper.parseStringMap(user.getPersonaOverride());
        if (overrides.containsKey(oldName)) {
            overrides.put(newName, overrides.remove(oldName));
        }
        Map<String, String> pending = JsonHelper.parseStringMap(user.getPendingOverride());
        if (pending.containsKey(oldName)) {
            pending.put(newName, pending.remove(oldName));
        }
        List<String> allowed = allowedPersonas(user);
        if (allowed != null) {
            int idx = allowed.indexOf(oldName);
            if (idx >= 0) {
                allowed.set(idx, newName);
            }
        }
        String personaName = oldName.equals(user.getPersonaName()) ? newName : null;

        updateJsonFields(user.getId(), userPersonas, allowed, overrides, pending, personaName);
    }

    public Map<String, Object> detail(String rawName, String username) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User user = requireUser(username);
        Map<String, String> userPersonas = JsonHelper.parseStringMap(user.getUserPersonas());
        Map<String, String> overrides = JsonHelper.parseStringMap(user.getPersonaOverride());
        Map<String, String> pending = JsonHelper.parseStringMap(user.getPendingOverride());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("user_content", overrides.getOrDefault(name, ""));
        body.put("pending_content", pending.getOrDefault(name, ""));

        if (userPersonas.containsKey(name)) {
            body.put("base_content", userPersonas.get(name));
            body.put("user_created", true);
            return body;
        }
        Path md = fileHelper.personaMarkdown(name);
        if (!Files.isRegularFile(md)) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        try {
            body.put("base_content", Files.readString(md, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        return body;
    }

    @Transactional
    public void reset(String username, String rawName) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User user = requireUser(username);
        Map<String, String> overrides = JsonHelper.parseStringMap(user.getPersonaOverride());
        if (overrides.remove(name) != null) {
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, user.getId())
                    .set(User::getPersonaOverride, JsonHelper.toJson(overrides)));
        }
    }

    @Transactional
    public void select(String username, String rawName) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User user = requireUser(username);
        boolean isUserPersona = JsonHelper.parseStringMap(user.getUserPersonas()).containsKey(name);
        if (!isUserPersona && !Files.isRegularFile(fileHelper.personaMarkdown(name))) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        List<String> allowed = allowedPersonas(user);
        if (!isUserPersona && allowed != null && !allowed.contains(name)) {
            throw ApiException.forbidden("你无权访问此角色");
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId()).set(User::getPersonaName, name));
        log.info("用户 {} 切换角色到 {}", username, name);
    }

    @Transactional
    public void save(String username, String rawName, String content) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User user = requireUser(username);
        List<String> allowed = allowedPersonas(user);
        if (allowed != null && !allowed.contains(name)) {
            throw ApiException.forbidden("你无权访问此角色");
        }
        Map<String, String> overrides = JsonHelper.parseStringMap(user.getPersonaOverride());
        overrides.put(name, content == null ? "" : content);
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getPersonaOverride, JsonHelper.toJson(overrides)));
    }

    public Map<String, Object> pendingOverrides(String username) {
        User user = requireUser(username);
        return Map.of("pending", JsonHelper.parseStringMap(user.getPendingOverride()));
    }

    @Transactional
    public void approveOverride(String username, String persona, boolean approve) {
        String name = FileHelper.safePersonaName(persona);
        if (name == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        User user = requireUser(username);
        Map<String, String> pending = JsonHelper.parseStringMap(user.getPendingOverride());
        if (!pending.containsKey(name)) {
            throw new ApiException(404, "无待审批内容");
        }
        Map<String, String> overrides = JsonHelper.parseStringMap(user.getPersonaOverride());
        if (approve) {
            overrides.put(name, pending.get(name));
        }
        pending.remove(name);

        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getPersonaOverride, JsonHelper.toJson(overrides))
                .set(User::getPendingOverride, JsonHelper.toJson(pending)));
    }

    /** 角色模板库：未开通访问权限时返回空列表 + access_denied */
    public Map<String, Object> library(String username) {
        User user = requireUser(username);
        boolean access = Boolean.TRUE.equals(user.getLibraryAccess());
        List<Map<String, Object>> library = configService.personaLibrary();

        List<Map<String, Object>> publicItems = new ArrayList<>();
        for (Map<String, Object> item : library) {
            Object isPublic = item.get("public");
            if (isPublic == null || Boolean.TRUE.equals(isPublic) || "true".equals(String.valueOf(isPublic))) {
                publicItems.add(item);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (!access) {
            body.put("library", List.of());
            body.put("access_denied", true);
            return body;
        }
        body.put("library", publicItems);
        return body;
    }

    @Transactional
    public void applyLibrary(String username, String libraryName, String rawPersona) {
        String persona = FileHelper.safePersonaName(rawPersona);
        if (persona == null) {
            throw ApiException.badRequest("无效的角色名");
        }
        Map<String, Object> target = null;
        for (Map<String, Object> item : configService.personaLibrary()) {
            Object isPublic = item.get("public");
            boolean visible = isPublic == null || Boolean.TRUE.equals(isPublic) || "true".equals(String.valueOf(isPublic));
            if (libraryName.equals(String.valueOf(item.get("name"))) && visible) {
                target = item;
                break;
            }
        }
        if (target == null) {
            throw new ApiException(404, "模板不存在");
        }
        User user = requireUser(username);
        Map<String, String> overrides = JsonHelper.parseStringMap(user.getPersonaOverride());
        overrides.put(persona, String.valueOf(target.getOrDefault("content", "")));
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getPersonaOverride, JsonHelper.toJson(overrides)));
    }

    // ==================== 内部 ====================

    /** allowed_personas 为 null 表示不限制 */
    private static List<String> allowedPersonas(User user) {
        if (user.getAllowedPersonas() == null || user.getAllowedPersonas().isBlank()) {
            return null;
        }
        return JsonHelper.parseStringList(user.getAllowedPersonas());
    }

    private void updateJsonFields(Long userId,
                                  Map<String, String> userPersonas,
                                  List<String> allowed,
                                  Map<String, String> overrides,
                                  Map<String, String> pending,
                                  String personaName) {
        var update = Wrappers.<User>lambdaUpdate().eq(User::getId, userId);
        if (userPersonas != null) {
            update.set(User::getUserPersonas, JsonHelper.toJson(userPersonas));
        }
        if (allowed != null) {
            update.set(User::getAllowedPersonas, JsonHelper.toJson(allowed));
        } else {
            update.set(User::getAllowedPersonas, null);
        }
        if (overrides != null) {
            update.set(User::getPersonaOverride, JsonHelper.toJson(overrides));
        }
        if (pending != null) {
            update.set(User::getPendingOverride, JsonHelper.toJson(pending));
        }
        if (personaName != null) {
            update.set(User::getPersonaName, personaName);
        }
        userMapper.update(null, update);
    }
}
