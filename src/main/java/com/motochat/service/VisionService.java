package com.motochat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motochat.common.FileHelper;
import com.motochat.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片识别，走 OpenAI 兼容的视觉接口。
 * 对应 Python 版 services/vision.py。
 *
 * <p>原版用 Pillow 压缩大图；这里用 ImageIO 做同样的事（缩放到 1024 内、转 JPEG）。
 * 压缩失败就退回原图 —— 与原版"压缩失败用原图"的兜底一致。
 */
@Service
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);
    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;
    private static final long COMPRESS_THRESHOLD = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 1024;

    private final ConfigService configService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public VisionService(ConfigService configService) {
        this.configService = configService;
    }

    public boolean isConfigured() {
        return !configService.getString("vision.api_key", "").isBlank()
                && !configService.getString("vision.base_url", "").isBlank();
    }

    public String recognize(Path imagePath) {
        String apiKey = configService.getString("vision.api_key", "");
        String baseUrl = configService.getString("vision.base_url", "");
        String model = configService.getString("vision.model", "");
        if (apiKey.isBlank() || baseUrl.isBlank() || model.isBlank()) {
            log.warn("Vision 未配置，跳过识别");
            return null;
        }

        try {
            long size = Files.size(imagePath);
            if (size > MAX_IMAGE_SIZE) {
                return "图片过大，无法识别（最大 10MB）";
            }

            byte[] bytes = Files.readAllBytes(imagePath);
            // 解码前校验真实尺寸（ compress() 里会 ImageIO.read 全量解码）：
            // 头部声明超大尺寸的图片会让解码申请数 GB 光栅内存，直接 OOM
            if (!FileHelper.imageWithinLimit(bytes, FileHelper.MAX_IMAGE_PIXELS)) {
                return "图片尺寸过大，无法识别";
            }
            String mime = "image/jpeg";
            if (size > COMPRESS_THRESHOLD) {
                byte[] compressed = compress(bytes);
                if (compressed != null) {
                    bytes = compressed;
                    mime = "image/jpeg";
                    log.info("图片已压缩: {}KB", compressed.length / 1024);
                } else {
                    mime = guessMime(imagePath);
                }
            } else {
                mime = guessMime(imagePath);
            }

            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", configService.getDouble("vision.temperature", 0.7));
            body.put("max_tokens", 1000);
            body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("type", "text", "text", "请描述这张图片的内容。"),
                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
            ))));

            String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("图片识别失败 HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            return content.isMissingNode() ? null : content.asText("");
        } catch (Exception e) {
            log.error("图片识别异常: {}", e.getMessage());
            return null;
        }
    }

    /** 缩放到 1024 以内并转 JPEG，失败返回 null */
    private byte[] compress(byte[] original) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
            if (src == null) {
                return null;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = Math.min(1.0, (double) MAX_DIMENSION / Math.max(w, h));
            int newW = Math.max(1, (int) Math.round(w * scale));
            int newH = Math.max(1, (int) Math.round(h * scale));

            BufferedImage rgb = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, newW, newH, null);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("图片压缩失败，使用原图: {}", e.getMessage());
            return null;
        }
    }

    private static String guessMime(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
