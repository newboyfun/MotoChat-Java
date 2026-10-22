package com.motochat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motochat.common.FileHelper;
import com.motochat.llm.EmotionTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 语音合成（Fish Audio 协议）。
 * 对应 Python 版 services/tts.py。
 *
 * <p>原版用 fish_audio_sdk；Java 没有官方 SDK，这里直接按 HTTP 协议发请求：
 * POST {base_url}/v1/tts，body {reference_id, text}，响应体是音频字节流。
 *
 * <p>未配置 key 或 model_id 时返回 null，上层据此返回 500 —— 与原版一致，
 * 前端会提示"语音生成失败，请检查 TTS 配置"。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final Pattern EMOJI_RE = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]", Pattern.UNICODE_CASE);

    private final ConfigService configService;
    private final FileHelper fileHelper;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public TtsService(ConfigService configService, FileHelper fileHelper) {
        this.configService = configService;
        this.fileHelper = fileHelper;
    }

    public boolean isConfigured() {
        return !configService.getString("tts.api_key", "").isBlank()
                && !configService.getString("tts.model_id", "").isBlank();
    }

    /** 生成语音文件，返回路径；失败或未配置返回 null */
    public Path generate(String text) {
        String apiKey = configService.getString("tts.api_key", "");
        String modelId = configService.getString("tts.model_id", "");
        String baseUrl = configService.getString("tts.base_url", "https://api.fish.audio");
        if (apiKey.isBlank() || modelId.isBlank()) {
            log.warn("TTS 未配置");
            return null;
        }

        Path output = null;
        try {
            String clean = cleanText(text);
            if (clean.isBlank()) {
                return null;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reference_id", modelId);
            body.put("text", clean);
            body.put("format", "mp3");

            String url = baseUrl.replaceAll("/+$", "");
            if (!url.endsWith("/v1/tts")) {
                url = url + "/v1/tts";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                log.error("TTS 生成失败 HTTP {}", response.statusCode());
                return null;
            }

            output = fileHelper.voices().resolve("voice_" + UUID.randomUUID().toString().replace("-", "") + ".mp3");
            Files.createDirectories(output.getParent());
            // 必须显式关闭：Files.copy(InputStream, Path) 不会关闭输入流，
            // 不关就等于每合成一次泄漏一个 HTTP 连接。
            try (InputStream audio = response.body()) {
                Files.copy(audio, output);
            }
            return output;
        } catch (Exception e) {
            log.error("TTS 生成异常: {}", e.getMessage());
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (Exception ignored) {
                    // 清理失败不影响主流程
                }
            }
            return null;
        }
    }

    /** 清理文本：去 emoji、去表情标签、把换行和 $ 换成逗号（Fish Audio 对符号敏感） */
    public String cleanText(String text) {
        if (text == null) {
            return "";
        }
        String s = EMOJI_RE.matcher(text).replaceAll("");
        s = s.replace("$", ",").replace("[SPLIT]", ",").replace("\r", "").replace("\n", ",");
        s = EmotionTags.PATTERN.matcher(s).replaceAll("");
        return s.strip();
    }

    /** 清理超过 maxAgeHours 的音频文件，防止 voices 目录无限增长 */
    public int cleanupOld(int maxAgeHours) {
        int count = 0;
        try {
            Instant cutoff = Instant.now().minusSeconds(maxAgeHours * 3600L);
            Path dir = fileHelper.voices();
            if (!Files.isDirectory(dir)) {
                return 0;
            }
            try (var stream = Files.list(dir)) {
                for (Path p : stream.toList()) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith("voice_") || !name.endsWith(".mp3")) {
                        continue;
                    }
                    try {
                        if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                            Files.deleteIfExists(p);
                            count++;
                        }
                    } catch (Exception ignored) {
                        // 单个文件失败跳过
                    }
                }
            }
            if (count > 0) {
                log.info("已清理 {} 个过期 TTS 文件", count);
            }
        } catch (Exception e) {
            log.debug("TTS 文件清理失败: {}", e.getMessage());
        }
        return count;
    }
}
