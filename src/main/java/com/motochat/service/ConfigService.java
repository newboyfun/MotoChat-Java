package com.motochat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置读写。保持与 Python 版一致的 config/config.json 格式，便于双版本共存与对比。
 *
 * <p>原版每次请求都重新读文件以支持"管理端改完立即生效"的热更新。
 * 这里加了一层 1 秒 TTL 的内存缓存：既保留热更新语义，又避免高频请求反复打磁盘。
 *
 * <p>M1 只实现读取，写入（config/save）留到 M4。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final long TTL_MS = 1_000L;

    private final ObjectMapper objectMapper;
    private final Path configPath = Paths.get("config", "config.json");

    private volatile Map<String, Object> cache = Collections.emptyMap();
    private volatile long loadedAt = 0L;

    public ConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 返回配置快照（只读，调用方不要修改） */
    public Map<String, Object> raw() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < TTL_MS) {
            return cache;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAt < TTL_MS) {
                return cache;
            }
            cache = load();
            loadedAt = System.currentTimeMillis();
            return cache;
        }
    }

    private Map<String, Object> load() {
        if (!Files.exists(configPath)) {
            return Collections.emptyMap();
        }
        try {
            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (IOException e) {
            log.warn("配置文件读取失败: {} - {}", configPath, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 按 "llm.api_key" 这种点分路径取值，取不到返回默认值 */
    @SuppressWarnings("unchecked")
    public String getString(String dottedPath, String defaultValue) {
        Object cur = raw();
        for (String key : dottedPath.split("\\.")) {
            if (!(cur instanceof Map)) {
                return defaultValue;
            }
            cur = ((Map<String, Object>) cur).get(key);
            if (cur == null) {
                return defaultValue;
            }
        }
        return cur instanceof String s ? s : String.valueOf(cur);
    }

    @SuppressWarnings("unchecked")
    public int getInt(String dottedPath, int defaultValue) {
        Object cur = raw();
        for (String key : dottedPath.split("\\.")) {
            if (!(cur instanceof Map)) {
                return defaultValue;
            }
            cur = ((Map<String, Object>) cur).get(key);
            if (cur == null) {
                return defaultValue;
            }
        }
        if (cur instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(cur).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    public double getDouble(String dottedPath, double defaultValue) {
        Object cur = raw();
        for (String key : dottedPath.split("\\.")) {
            if (!(cur instanceof Map)) {
                return defaultValue;
            }
            cur = ((Map<String, Object>) cur).get(key);
            if (cur == null) {
                return defaultValue;
            }
        }
        if (cur instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(cur).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 取某个顶层节的 map 视图，不存在返回空 map */
    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String name) {
        Object v = raw().get(name);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    public void invalidate() {
        loadedAt = 0L;
    }

    // ==================== 写入 ====================

    /**
     * 批量更新。先全部校验路径再一次性落盘 —— 原版注释里写明了原因：
     * 逐条 update 会在第 k 个字段非法时留下前面已写入的半成品配置。
     */
    public synchronized void updateMany(Map<String, Object> pairs) {
        Map<String, Object> root = deepCopy(raw());
        for (Map.Entry<String, Object> e : pairs.entrySet()) {
            setPath(root, e.getKey(), e.getValue());
        }
        persist(root);
    }

    public synchronized void update(String dottedPath, Object value) {
        updateMany(Map.of(dottedPath, value));
    }

    @SuppressWarnings("unchecked")
    private static void setPath(Map<String, Object> root, String dottedPath, Object value) {
        String[] keys = dottedPath.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = cur.get(keys[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(keys[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(keys[keys.length - 1], value);
    }

    private void persist(Map<String, Object> root) {
        try {
            Files.createDirectories(configPath.getParent());
            // 原子替换：先写临时文件再 move。原地覆盖时进程崩溃会留下半截 JSON，
            // 之后 load() 解析失败返回空配置——API key/base_url 全部"消失"且无备份。
            // move 是原子操作，并发读者只会看到完整的旧文件或完整的新文件。
            Path tmp = configPath.resolveSibling("config.json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            try {
                Files.move(tmp, configPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            synchronized (this) {
                cache = root;
                loadedAt = System.currentTimeMillis();
            }
        } catch (IOException e) {
            throw new IllegalStateException("配置写入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 递归深拷贝。只拷一层是不够的：三级路径（如 behavior.auto_message.min_hours）
     * 的 setPath 会原地修改与旧缓存共享的子 map，persist 一旦失败，
     * 内存缓存就含有未落盘的改动，与磁盘不一致。
     */
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k, copyValue(v)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> child = new LinkedHashMap<>();
            m.forEach((ck, cv) -> child.put(String.valueOf(ck), copyValue(cv)));
            return child;
        }
        if (v instanceof List<?> list) {          // web.persona_library 是 List<Map>
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(copyValue(item));
            }
            return out;
        }
        return v;
    }

    /** 读取 web.persona_library（角色模板库） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> personaLibrary() {
        Object lib = section("web").get("persona_library");
        if (lib instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    m.forEach((k, v) -> entry.put(String.valueOf(k), v));
                    out.add(entry);
                }
            }
            return out;
        }
        return new ArrayList<>();
    }
}
