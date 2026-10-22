package com.motochat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motochat.common.ApiException;
import com.motochat.common.FileHelper;
import com.motochat.llm.LlmGateway;
import com.motochat.security.CurrentUser;
import com.motochat.security.RequireAdmin;
import com.motochat.security.RequireLogin;
import com.motochat.security.SessionUser;
import com.motochat.service.AutoSendService;
import com.motochat.service.ChatService;
import com.motochat.service.TtsService;
import com.motochat.service.VisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天接口。契约对齐 Python 版 api/chat.py。
 *
 * <p><b>关于 SSE 为什么要手写而不是用 SseEmitter：</b>
 * 前端 app-v1.js 第 311 行的解析是 {@code if (line.startsWith('data: '))}，
 * 冒号后面要求有空格。而 Spring 的 {@code SseEmitter} 输出的是 {@code data:xxx}（无空格），
 * 直接用它会让前端一条 chunk 都收不到。所以这里用 StreamingResponseBody
 * 直接往 OutputStream 写原始帧，格式与 Python 版逐字节一致。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 性格特征 → 英文描述，与 Python 版 TRAIT_MAP 完全一致 */
    private static final Map<String, String> TRAIT_MAP = Map.ofEntries(
            Map.entry("温柔", "gentle and tender personality"),
            Map.entry("活泼", "lively and energetic personality"),
            Map.entry("高冷", "cool and aloof personality"),
            Map.entry("幽默", "humorous and witty personality"),
            Map.entry("知性", "intellectual and knowledgeable personality"),
            Map.entry("元气", "cheerful and spirited personality"),
            Map.entry("毒舌", "sharp-tongued and sarcastic personality"),
            Map.entry("呆萌", "clumsy and adorable personality"),
            Map.entry("御姐", "mature and confident personality"),
            Map.entry("正经", "serious and proper personality"),
            Map.entry("话痨", "talkative and chatty personality"),
            Map.entry("安静", "quiet and reserved personality"),
            Map.entry("傲娇", "tsundere - cold outside but warm inside"),
            Map.entry("天然呆", "airheaded and naive personality"),
            Map.entry("腹黑", "scheming and mischievous personality"),
            Map.entry("中二", "chuunibyou - grandiose delusional personality"),
            Map.entry("社恐", "socially anxious and shy personality"),
            Map.entry("治愈", "healing and comforting personality")
    );

    private final ChatService chatService;
    private final AutoSendService autoSendService;
    private final TtsService ttsService;
    private final VisionService visionService;
    private final LlmGateway llmGateway;
    private final FileHelper fileHelper;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(ChatService chatService,
                          AutoSendService autoSendService,
                          TtsService ttsService,
                          VisionService visionService,
                          LlmGateway llmGateway,
                          FileHelper fileHelper) {
        this.chatService = chatService;
        this.autoSendService = autoSendService;
        this.ttsService = ttsService;
        this.visionService = visionService;
        this.llmGateway = llmGateway;
        this.fileHelper = fileHelper;
    }

    // ==================== 同步聊天 ====================

    @PostMapping
    @RequireLogin
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody ChatRequest req,
                                                    @CurrentUser(required = true) SessionUser user) {
        autoSendService.updateLastChatTime();
        String cid = resolveCid(req.conversationId(), user);
        LlmGateway.LlmResult result = chatService.handle(req.message(), cid, user.username(), true);

        String reply = result.reply() == null ? "" : result.reply();
        if (reply.startsWith("抱歉，处理消息时出了点问题")) {
            // 与 Python 版保持一致：错误响应同时带 error 和 reply 两个字段
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", reply);
            err.put("reply", reply);
            return ResponseEntity.status(500).body(err);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reply", reply);
        body.put("usage", result.usage().toMap());
        return ResponseEntity.ok(body);
    }

    // ==================== SSE 流式 ====================

    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    @RequireLogin
    public ResponseEntity<StreamingResponseBody> stream(@Valid @RequestBody ChatRequest req,
                                                        @CurrentUser(required = true) SessionUser user) {
        autoSendService.updateLastChatTime();
        String cid = resolveCid(req.conversationId(), user);
        String message = req.message();
        String username = user.username();

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<LlmGateway.LlmUsage> usageRef = new AtomicReference<>(LlmGateway.LlmUsage.empty());
        AtomicReference<Integer> chunkCount = new AtomicReference<>(0);

        StreamingResponseBody body = outputStream -> {
            try {
                chatService.handleStream(message, cid, username, true, new ChatService.StreamSink() {
                    @Override
                    public void onText(String text) {
                        if (cancelled.get()) {
                            return;
                        }
                        try {
                            writeEvent(outputStream, "chunk", Map.of("text", text));
                            chunkCount.updateAndGet(c -> c + 1);
                        } catch (IOException e) {
                            // 客户端断开：写失败即停止生成
                            cancelled.set(true);
                        }
                    }

                    @Override
                    public void onUsage(LlmGateway.LlmUsage usage) {
                        usageRef.set(usage);
                    }

                    @Override
                    public void onError(String error) {
                        // 错误文本作为普通 chunk 展示，与修复前经 onDelta 写出的效果一致
                        onText(error);
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }
                });

                if (!cancelled.get()) {
                    Map<String, Object> endData = new LinkedHashMap<>();
                    endData.put("usage", usageRef.get().toMap());
                    endData.put("chunks", chunkCount.get());
                    writeEvent(outputStream, "end", endData);
                    outputStream.flush();
                }
                log.info("[SSE] 流式完成: chunks={} tokens={}", chunkCount.get(), usageRef.get().totalTokens());
            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.error("[SSE] 流式异常: {}", e.getMessage(), e);
                    try {
                        Map<String, Object> errData = new LinkedHashMap<>();
                        errData.put("error", "stream_error");
                        errData.put("message", "AI服务异常，请稍后重试");
                        writeEvent(outputStream, "error", errData);
                        outputStream.flush();
                    } catch (IOException ignored) {
                        // 连接已断，无需再写
                    }
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/event-stream;charset=UTF-8"))
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /** 手工拼 SSE 帧，格式与 Python 版一致：event: X\\ndata: {...}\\n\\n */
    private void writeEvent(OutputStream out, String event, Map<String, Object> data) throws IOException {
        String json = mapper.writeValueAsString(data);
        String frame = "event: " + event + "\ndata: " + json + "\n\n";
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ==================== 命令 ====================

    @PostMapping("/command")
    @RequireAdmin
    public Map<String, Object> command(@Valid @RequestBody CommandRequest req,
                                       @CurrentUser(required = true) SessionUser user) {
        LlmGateway.LlmResult result = chatService.handle(req.command(),
                req.conversationId() == null || req.conversationId().isBlank()
                        ? "default" : req.conversationId(),
                user.username(), true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reply", result.reply());
        body.put("usage", result.usage().toMap());
        return body;
    }

    // ==================== TTS ====================

    @PostMapping("/tts")
    @RequireLogin
    public ResponseEntity<Resource> tts(@Valid @RequestBody TtsRequest req) {
        if (!ttsService.isConfigured()) {
            throw new ApiException(500, "TTS 服务未配置");
        }
        Path path = ttsService.generate(req.text());
        if (path == null) {
            throw new ApiException(500, "语音生成失败，请检查 TTS 配置");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + path.getFileName() + "\"")
                .body(new FileSystemResource(path));
    }

    // ==================== 图片识别 ====================

    @PostMapping("/images/recognize")
    @RequireLogin
    public Map<String, Object> recognizeImage(@RequestParam("file") MultipartFile file) {
        if (!visionService.isConfigured()) {
            throw new ApiException(503, "视觉服务未配置");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("空文件");
        }
        if (data.length == 0) {
            throw ApiException.badRequest("空文件");
        }
        if (data.length > 10 * 1024 * 1024) {
            throw new ApiException(413, "图片大小超过 10MB 限制");
        }
        String ext = FileHelper.sniffImageExt(data);
        if (ext == null) {
            throw new ApiException(415, "仅支持 JPG/PNG/GIF/WebP 格式");
        }

        Path temp = null;
        try {
            Path dir = fileHelper.imagesTemp();
            Files.createDirectories(dir);
            // 时间戳精确到毫秒，同一毫秒的两次并发上传会撞名，加随机后缀兜底
            temp = dir.resolve("upload_" + LocalDateTime.now().format(TS)
                    + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext);
            Files.write(temp, data);

            String result = visionService.recognize(temp);
            if (result == null) {
                throw new ApiException(502, "图片识别失败");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recognized", result);
            body.put("usage", Map.of());
            return body;
        } catch (IOException e) {
            throw new ApiException(500, "图片处理失败");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
    }

    // ==================== AI 生成人设 ====================

    @PostMapping("/generate-personality")
    @RequireLogin
    public Map<String, Object> generatePersonality(@RequestBody GeneratePersonalityRequest req,
                                                   @CurrentUser(required = true) SessionUser user) {
        List<String> traits = req.traits() == null ? List.of() : req.traits();
        if (traits.isEmpty() && (req.customHint() == null || req.customHint().isBlank())) {
            throw ApiException.badRequest("请至少选择一个性格特征或填写描述");
        }

        StringBuilder prompt = new StringBuilder("你是一位AI角色设计师，请根据以下要求创建一个角色性格设定：");
        if (!traits.isEmpty()) {
            StringBuilder selected = new StringBuilder();
            for (String t : traits) {
                if (selected.length() > 0) {
                    selected.append(", ");
                }
                selected.append(TRAIT_MAP.getOrDefault(t, t));
            }
            prompt.append("性格特征：").append(selected);
        }
        if (req.customHint() != null && !req.customHint().isBlank()) {
            prompt.append("\n额外要求：").append(req.customHint());
        }
        if (req.baseRole() != null && !req.baseRole().isBlank()) {
            prompt.append("\n角色类型：").append(req.baseRole());
        }
        prompt.append("\n输出格式：\n1. 角色名字（2-3个字）\n2. 性格描述（2-3句话）\n3. 语气风格\n4. 行为禁忌\n\n直接输出角色设定，不超过200字。");

        LlmGateway.LlmConfig cfg = llmGateway.resolveConfig(user.username());
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new ApiException(503, "AI 服务不可用，请配置 API 密钥");
        }

        LlmGateway.LlmResult result = llmGateway.chat(user.username(), List.of(
                new LlmGateway.ChatMessage("user", prompt.toString())), 2000, 0.8);
        if (result.reply() == null || result.reply().isBlank()) {
            throw new ApiException(500, "模型返回为空，请重试");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("content", result.reply());
        return body;
    }

    // ==================== 内部 ====================

    /** 普通用户只能操作自己的会话，忽略前端传来的 cid */
    private static String resolveCid(String requested, SessionUser user) {
        if (!user.isAdmin()) {
            String own = user.conversationId();
            if (own != null && !own.isBlank()) {
                return own;
            }
        }
        return (requested == null || requested.isBlank()) ? "default" : requested;
    }

    // ==================== 请求体 ====================

    public record ChatRequest(
            @NotBlank @Size(max = 10000, message = "消息过长") String message,
            @com.fasterxml.jackson.annotation.JsonProperty("conversation_id") String conversationId
    ) {
        public ChatRequest {
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "default";
            }
        }
    }

    public record CommandRequest(
            @NotBlank @Size(max = 5000) String command,
            @com.fasterxml.jackson.annotation.JsonProperty("conversation_id") String conversationId
    ) {
        public CommandRequest {
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "default";
            }
        }
    }

    public record TtsRequest(@NotBlank @Size(max = 5000) String text) {
    }

    public record GeneratePersonalityRequest(
            List<String> traits,
            @com.fasterxml.jackson.annotation.JsonProperty("base_role") String baseRole,
            @com.fasterxml.jackson.annotation.JsonProperty("custom_hint") String customHint
    ) {
    }
}
