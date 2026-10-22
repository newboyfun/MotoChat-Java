package com.motochat.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 字段的读写辅助。
 *
 * <p>users 表里有 5 个字段是 JSON 文本（user_personas / api_config / persona_override /
 * pending_override / allowed_personas），原版这些是 Python dict。这里统一收口，
 * 避免每个 service 各写一遍 try-catch，也保证解析失败时返回空容器而不是 null。
 */
public final class JsonHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonHelper() {
    }

    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<>() {
            });
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 解析 {name: content} 这种纯字符串 map */
    public static Map<String, String> parseStringMap(String json) {
        Map<String, Object> raw = parseObject(json);
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v != null) {
                out.put(k, String.valueOf(v));
            }
        });
        return out;
    }

    public static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = MAPPER.readValue(json, new TypeReference<>() {
            });
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 从对象里安全取 string 字段 */
    public static String str(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }
}
