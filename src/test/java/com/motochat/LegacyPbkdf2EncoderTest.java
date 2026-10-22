package com.motochat;

import com.motochat.security.LegacyPbkdf2Encoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PBKDF2 兼容编码器测试。登录流程的时间侧信道防护（对 dummy 哈希跑 matches）
 * 依赖"合法格式的哈希能正常参与 matches 且不抛异常"，这里兜住。
 */
class LegacyPbkdf2EncoderTest {

    private final LegacyPbkdf2Encoder encoder = new LegacyPbkdf2Encoder();

    @Test
    @DisplayName("编码后能校验通过，错误密码校验失败")
    void roundtrip() {
        String hash = encoder.encode("密码test123");
        assertTrue(encoder.matches("密码test123", hash));
        assertFalse(encoder.matches("wrong-password", hash));
    }

    @Test
    @DisplayName("编码结果符合 Python 版格式 $pbkdf2-sha256$310000$salt$dk")
    void formatMatchesPython() {
        String hash = encoder.encode("admin123");
        String[] parts = hash.split("\\$");
        assertTrue(parts.length == 5, "格式应为 ['', 'pbkdf2-sha256', iter, salt, dk]，实际: " + hash);
        assertTrue("pbkdf2-sha256".equals(parts[1]));
        assertTrue("310000".equals(parts[2]));
        assertTrue(parts[3].length() == 32, "salt 应为 16 字节 hex");
        assertTrue(parts[4].length() == 64, "dk 应为 32 字节 hex");
    }

    @Test
    @DisplayName("畸形哈希不抛异常，直接返回 false")
    void malformedHashesReturnFalse() {
        assertFalse(encoder.matches("x", null));
        assertFalse(encoder.matches("x", ""));
        assertFalse(encoder.matches("x", "not-a-hash"));
        assertFalse(encoder.matches("x", "$pbkdf2-sha256$310000$zz$zz"));      // 非 hex
        assertFalse(encoder.matches("x", "$pbkdf2-sha256$310000$aabb$ccdd"));  // 长度不匹配
        assertFalse(encoder.matches("x", "$pbkdf2-sha256$abc$aabbccddeeff00112233445566778899$aabbccddeeff00112233445566778899"));
    }

    @Test
    @DisplayName("isHashed 只认自家格式")
    void isHashedRecognizesOwnFormat() {
        assertTrue(LegacyPbkdf2Encoder.isHashed(encoder.encode("x")));
        assertFalse(LegacyPbkdf2Encoder.isHashed("base64$salt$hash"));
        assertFalse(LegacyPbkdf2Encoder.isHashed(null));
    }
}
