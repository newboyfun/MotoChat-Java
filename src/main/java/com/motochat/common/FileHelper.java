package com.motochat.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 路径、命名校验、图片类型识别、密钥掩码。
 * 对应 Python 版 core/utils.py，逻辑逐条照搬（包括正则范围）。
 */
@Component
public class FileHelper {

    /** 允许字母数字、下划线、中划线、中日韩字符 */
    private static final Pattern SAFE_NAME = Pattern.compile(
            "^[A-Za-z0-9_\\-\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff\\uac00-\\ud7af]+$");

    /**
     * 图片解码的像素上限（宽 × 高）。
     * 头像最终只用到 512×512、视觉识别压缩到 1024，4096² 对合法上传已非常宽松。
     */
    public static final long MAX_IMAGE_PIXELS = 4096L * 4096L;

    private final Path dataRoot;

    public FileHelper(@Value("${motochat.data-root:./userdata}") String dataRoot) {
        this.dataRoot = Paths.get(dataRoot).toAbsolutePath().normalize();
        initDirs();
    }

    private void initDirs() {
        try {
            Files.createDirectories(dataRoot.resolve("avatars"));
            Files.createDirectories(dataRoot.resolve("images/temp"));
            Files.createDirectories(dataRoot.resolve("voices"));
            Files.createDirectories(dataRoot.resolve("memory"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + dataRoot, e);
        }
    }

    public Path dataRoot() {
        return dataRoot;
    }

    public Path avatarsRoot() {
        return dataRoot.resolve("avatars");
    }

    public Path personaDir(String name) {
        return avatarsRoot().resolve(name);
    }

    public Path personaMarkdown(String name) {
        return personaDir(name).resolve("avatar.md");
    }

    public Path personaAvatar(String name) {
        return personaDir(name).resolve("avatar.jpg");
    }

    public Path imagesTemp() {
        return dataRoot.resolve("images/temp");
    }

    public Path voices() {
        return dataRoot.resolve("voices");
    }

    public Path memoryRoot() {
        return dataRoot.resolve("memory");
    }

    /**
     * 防路径遍历校验。与 Python 版一致：长度 ≤32、无 / \ .. NUL、且只含白名单字符。
     * 不合法返回 null（对应 Python 的空字符串）。
     */
    public static String safePersonaName(String name) {
        if (name == null) {
            return null;
        }
        String v = name.trim();
        if (v.isEmpty() || v.length() > 32) {
            return null;
        }
        if (v.contains("/") || v.contains("\\") || v.contains("..") || v.indexOf('\0') >= 0) {
            return null;
        }
        return SAFE_NAME.matcher(v).matches() ? v : null;
    }

    /** 密钥掩码：未配置返回"未配置"，否则 *** 加末 4 位 */
    public static String maskKey(String key) {
        if (key == null || key.isEmpty()) {
            return "未配置";
        }
        if (key.length() >= 4) {
            return "***" + key.substring(key.length() - 4);
        }
        return "***";
    }

    /**
     * 按文件头识别图片真实类型，不信任客户端声明的扩展名。
     * @return jpg / png / gif / webp，无法识别返回 null
     */
    public static String sniffImageExt(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (startsWith(data, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "jpg";
        }
        if (startsWith(data, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "png";
        }
        if (startsWith(data, new byte[]{'G', 'I', 'F', '8'})) {
            return "gif";
        }
        if (data.length >= 12 && startsWith(data, new byte[]{'R', 'I', 'F', 'F'})
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解码前校验图片真实尺寸，防解压炸弹（decompression bomb）。
     *
     * <p>{@code ImageIO.read} 不信任头部声明的尺寸：一张 5MB 的 PNG 可以声明
     * 30000×30000，解码时光栅内存数 GB，直接 OOM 打死整个 JVM —— 所有用户一起挂。
     * 这里先用 {@link ImageReader} 只读头部尺寸，不触碰像素数据。
     *
     * @return {@code true} = 尺寸在限额内，或无法解析头部（交由此后原有逻辑处理）；
     *         {@code false} = 超过 maxPixels，调用方应拒绝
     */
    public static boolean imageWithinLimit(byte[] data, long maxPixels) {
        if (data == null || data.length == 0) {
            return true;
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (iis == null) {
                return true;
            }
            var readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return true;    // ImageIO 不支持的格式（如 WebP），走原有路径
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                return (long) reader.getWidth(0) * reader.getHeight(0) <= maxPixels;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return true;        // 头部读取失败，交由原有逻辑报错
        }
    }

    public static String readText(Path path) throws IOException {
        return Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void writeText(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    public Optional<byte[]> readIfExists(Path path) {
        try {
            return Files.isRegularFile(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
