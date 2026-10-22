package com.motochat.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 配置保存的值校验（类型 / 范围 / 格式）单测。
 *
 * <p>背景：白名单只约束"哪些键能改"，不约束"值是什么形态"。
 * 温度 99、max_tokens -5、静默时段 "25:99" 存进去后不会报错，
 * 只会让 LLM 请求被提供商拒绝或静默时段永远不成立——保存时就该拦住。
 */
class ConfigControllerValidationTest {

    private static String v(String path, Object value) {
        return ConfigController.validateConfigValue(path, value);
    }
    @Test
    @DisplayName("合法值全部放行")
    void acceptsValidValues() {
        assertNull(v("llm.temperature", 1.1));
        assertNull(v("llm.temperature", 0));
        assertNull(v("llm.temperature", 2));
        assertNull(v("llm.temperature", "1.5"));           // 数字字符串也认
        assertNull(v("llm.max_tokens", 2000));
        assertNull(v("llm.max_tokens", "2000"));
        assertNull(v("llm.max_context_rounds", 15));
        assertNull(v("llm.base_url", "https://api.openai.com/v1"));
        assertNull(v("llm.base_url", "http://127.0.0.1:9999"));
        assertNull(v("llm.base_url", ""));                 // 空串=清空，允许
        assertNull(v("llm.api_key", "sk-abc"));
        assertNull(v("llm.api_key", ""));                  // 清空 key，允许
        assertNull(v("llm.model", "gpt-4o-mini"));
        assertNull(v("tts.model_id", "xxx"));
        assertNull(v("behavior.quiet_time.start", "23:00"));
        assertNull(v("behavior.quiet_time.end", "07:59"));
        assertNull(v("behavior.quiet_time.start", "00:00"));
        assertNull(v("behavior.auto_message.min_hours", 0.5));
        assertNull(v("behavior.auto_message.max_hours", 6));
        assertNull(v("behavior.auto_message.content", "最近怎么样？"));
        assertNull(v("behavior.avatar_dir", "userdata/avatars/MONO"));
        assertNull(v("behavior.queue_timeout", 30));
        assertNull(v("llm.auto_model_switch", true));      // 白名单里无规则字段不拦
        assertNull(v("llm.model", null));                  // null=清空
    }

    @Test
    @DisplayName("数值超范围 / 非数字被拒")
    void rejectsBadNumbers() {
        assertEquals("llm.temperature 应在 0 ~ 2 之间", v("llm.temperature", 99));
        assertEquals("llm.temperature 应在 0 ~ 2 之间", v("llm.temperature", -1));
        assertEquals("llm.temperature 必须是数字", v("llm.temperature", "abc"));
        assertEquals("llm.max_tokens 应在 1 ~ 200000 之间", v("llm.max_tokens", -5));
        assertEquals("llm.max_tokens 应在 1 ~ 200000 之间", v("llm.max_tokens", 0));
        assertEquals("llm.max_tokens 应在 1 ~ 200000 之间", v("llm.max_tokens", 200001));
        assertEquals("llm.max_tokens 必须是整数", v("llm.max_tokens", "abc"));
        assertEquals("llm.max_tokens 必须是整数", v("llm.max_tokens", 1.5));   // 小数不接受
        assertEquals("llm.max_context_rounds 应在 1 ~ 100 之间", v("llm.max_context_rounds", 0));
        assertEquals("llm.max_context_rounds 应在 1 ~ 100 之间", v("llm.max_context_rounds", 101));
        assertEquals("behavior.auto_message.min_hours 应在 0 ~ 168 小时之间", v("behavior.auto_message.min_hours", 200));
        assertEquals("behavior.auto_message.min_hours 应在 0 ~ 168 小时之间", v("behavior.auto_message.min_hours", 0));
        assertEquals("behavior.queue_timeout 应在 1 ~ 600 秒之间", v("behavior.queue_timeout", 999));
    }

    @Test
    @DisplayName("静默时段必须是 HH:mm")
    void rejectsBadTimeFormat() {
        assertEquals("behavior.quiet_time.start 格式应为 HH:mm（如 23:00）", v("behavior.quiet_time.start", "25:99"));
        assertEquals("behavior.quiet_time.end 格式应为 HH:mm（如 23:00）", v("behavior.quiet_time.end", "9:00"));
        assertEquals("behavior.quiet_time.start 格式应为 HH:mm（如 23:00）", v("behavior.quiet_time.start", "23-00"));
        assertEquals("behavior.quiet_time.start 格式应为 HH:mm（如 23:00）", v("behavior.quiet_time.start", 2300));
    }

    @Test
    @DisplayName("字符串字段类型与 base_url 协议被拒")
    void rejectsBadStrings() {
        assertEquals("llm.api_key 必须是字符串", v("llm.api_key", 123));
        assertEquals("llm.base_url 必须以 http:// 或 https:// 开头", v("llm.base_url", "ftp://x"));
        assertEquals("vision.base_url 必须以 http:// 或 https:// 开头", v("vision.base_url", "api.openai.com"));
        assertEquals("llm.model 过长（最长 2048 字符）", v("llm.model", "x".repeat(2049)));
    }
}
