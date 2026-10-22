package com.motochat.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 兼容 Python 版密码哈希的编码器。
 *
 * <p><b>为什么不能直接用 Spring Security 的 Pbkdf2PasswordEncoder：</b>
 * 它输出的格式是 {@code base64(salt)$base64(hash)} 之类，与旧版
 * {@code $pbkdf2-sha256$310000$<salt_hex>$<dk_hex>} 完全不兼容。
 * 直接换用会导致所有已有账号"密码正确却提示错误"，且极难排查。
 *
 * <p>对齐 Python 侧实现（src/data/config.py）：
 * <pre>
 * dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 310000)
 * return f"$pbkdf2-sha256${iter}${salt.hex()}${dk.hex()}"
 * </pre>
 *
 * <p>Java 的 {@link PBEKeySpec} 接收 char[]，SunJCE 的 PBKDF2 实现按 UTF-8 编码，
 * 与 Python 的 {@code password.encode("utf-8")} 行为一致（含中文密码）。
 */
@Component
public class LegacyPbkdf2Encoder implements PasswordEncoder {

    private static final String PREFIX = "$pbkdf2-sha256$";
    private static final int ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;   // 32 字节输出

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            return "";
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(rawPassword.toString(), salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + HEX.formatHex(salt) + "$" + HEX.formatHex(dk);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null || !storedPassword.startsWith(PREFIX)) {
            return false;
        }
        // split 后：['', 'pbkdf2-sha256', '310000', '<salt_hex>', '<dk_hex>']
        String[] parts = storedPassword.split("\\$");
        if (parts.length != 5) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = HEX.parseHex(parts[3]);
            byte[] expected = HEX.parseHex(parts[4]);
            byte[] actual = pbkdf2(rawPassword.toString(), salt, iterations);
            // 定长比较，避免时序侧信道
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 判断一个字符串是否已经是本编码器产出的哈希 */
    public static boolean isHashed(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
