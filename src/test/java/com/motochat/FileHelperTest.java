package com.motochat;

import com.motochat.common.FileHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片尺寸校验（解压炸弹防护）测试。
 *
 * <p>只构造 PNG 头部，不产生任何像素数据——被测试的方法
 * （{@link FileHelper#imageWithinLimit}）本来就只读头部。
 */
class FileHelperTest {

    @Test
    @DisplayName("正常尺寸的真实图片放行")
    void allowsNormalImage() throws IOException {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "png", out));
        assertTrue(FileHelper.imageWithinLimit(out.toByteArray(), FileHelper.MAX_IMAGE_PIXELS));
    }

    @Test
    @DisplayName("头部声明超大尺寸的图片（解压炸弹）被拒绝")
    void rejectsDecompressionBomb() throws IOException {
        // 整个"图片"只有 60 字节左右，但 IHDR 声明 20000×20000 = 4 亿像素
        byte[] bomb = pngHeaderOnly(20000, 20000);
        assertTrue(bomb.length < 100);
        assertFalse(FileHelper.imageWithinLimit(bomb, FileHelper.MAX_IMAGE_PIXELS));
    }

    @Test
    @DisplayName("刚好等于限额的图片放行")
    void allowsImageExactlyAtLimit() throws IOException {
        byte[] edge = pngHeaderOnly(4096, 4096);
        assertTrue(FileHelper.imageWithinLimit(edge, FileHelper.MAX_IMAGE_PIXELS));
    }

    @Test
    @DisplayName("非图片数据不拦截，交由此后原有逻辑处理")
    void passesThroughUndecodableData() {
        byte[] garbage = "这不是图片".getBytes(StandardCharsets.UTF_8);
        assertTrue(FileHelper.imageWithinLimit(garbage, FileHelper.MAX_IMAGE_PIXELS));
        assertTrue(FileHelper.imageWithinLimit(new byte[0], FileHelper.MAX_IMAGE_PIXELS));
        assertTrue(FileHelper.imageWithinLimit(null, FileHelper.MAX_IMAGE_PIXELS));
    }

    /** 手工拼一个只有签名 + IHDR + IEND 的 PNG，尺寸随便声明，不碰像素数据 */
    private static byte[] pngHeaderOnly(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        byte[] ihdr = ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put((byte) 8)    // bit depth
                .put((byte) 2)    // color type: truecolor
                .put((byte) 0).put((byte) 0).put((byte) 0)
                .array();
        writeChunk(out, "IHDR", ihdr);
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
