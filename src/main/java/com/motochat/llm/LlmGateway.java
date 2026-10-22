package com.motochat.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motochat.common.JsonHelper;
import com.motochat.entity.User;
import com.motochat.mapper.UserMapper;
import com.motochat.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;

/**
 * OpenAI 兼容 API 网关。
 *
 * <p>没有引入 openai-java SDK —— 这个项目只需要 chat/completions 一个接口，
 * 用 JDK 自带的 HttpClient 直接发请求，反而少一层版本兼容风险，SSE 解析也更可控。
 *
 * <p>与原版 services/llm.py 对齐的行为：
 * <ul>
 *   <li>每次调用都重新读配置，管理端改完立即生效（热更新）</li>
 *   <li>用户有自定义 api_config 时优先用用户的</li>
 *   <li>流式取不到 usage 时按字符数估算（与 Python 的 len//2 一致）</li>
 * </ul>
 */
@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    /**
     * 流式读取的空闲超时：超过这么久没有新数据就认为上游僵死，强制中断。
     * 之所以需要看门狗：{@code HttpRequest.timeout()} 不覆盖 body 的惰性读取
     * （{@code BodyHandlers.ofInputStream()} 拿到响应头就返回），上游中途不发数据时
     * {@code readLine()} 会无限期阻塞，虚拟线程和 HTTP 连接都不释放。
     */
    private static final long IDLE_READ_NANOS = Duration.ofSeconds(60).toNanos();

    private final ConfigService configService;
    private final UserMapper userMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 流式读取看门狗：所有流共享一个守护线程，每 15 秒检查一次各流的读取进度 */
    private final ScheduledExecutorService idleWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-idle-watchdog");
        t.setDaemon(true);
        return t;
    });

    public LlmGateway(ConfigService configService, UserMapper userMapper) {
        this.configService = configService;
        this.userMapper = userMapper;
    }

    @PreDestroy
    public void shutdown() {
        idleWatchdog.shutdownNow();
    }

    // ==================== 配置 ====================

    public record LlmConfig(String apiKey, String baseUrl, String model,
                            double temperature, int maxTokens, int maxContextRounds) {
    }

    /** 解析本次请求该用哪套配置：用户自定义优先，否则全局 */
    public LlmConfig resolveConfig(String username) {
        String apiKey = configService.getString("llm.api_key", "");
        String baseUrl = configService.getString("llm.base_url", "");
        String model = configService.getString("llm.model", "");
        double temperature = configService.getDouble("llm.temperature", 1.1);
        int maxTokens = configService.getInt("llm.max_tokens", 2000);
        int maxRounds = configService.getInt("llm.max_context_rounds", 15);

        if (username != null && !username.isBlank()) {
            User user = userMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<User>lambdaQuery()
                            .eq(User::getUsername, username));
            if (user != null) {
                Map<String, Object> cfg = JsonHelper.parseObject(user.getApiConfig());
                String userKey = JsonHelper.str(cfg, "api_key", "");
                if (!userKey.isBlank()) {
                    apiKey = userKey;
                    baseUrl = JsonHelper.str(cfg, "base_url", baseUrl);
                    String userModel = JsonHelper.str(cfg, "model", "");
                    if (!userModel.isBlank()) {
                        model = userModel;
                    }
                }
            }
        }
        return new LlmConfig(apiKey, normalizeBaseUrl(baseUrl), model, temperature, maxTokens, maxRounds);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String v = baseUrl.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    // ==================== 数据结构 ====================

    public record LlmUsage(int promptTokens, int completionTokens, int totalTokens) {
        public static LlmUsage empty() {
            return new LlmUsage(0, 0, 0);
        }

        public Map<String, Integer> toMap() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("prompt_tokens", promptTokens);
            m.put("completion_tokens", completionTokens);
            m.put("total_tokens", totalTokens);
            return m;
        }
    }

    public record LlmResult(String reply, LlmUsage usage, String error) {
    }

    public record ChatMessage(String role, String content) {
        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("role", role);
            m.put("content", content);
            return m;
        }
    }

    // ==================== 非流式 ====================

    public LlmResult chat(String username, List<ChatMessage> messages) {
        return chat(username, messages, null, null);
    }

    /**
     * 带参数覆盖的非流式调用。
     *
     * <p>用于「AI 生成人设」这类特殊场景：原版这里写死 {@code max_tokens=2000,
     * temperature=0.8}，因为推理模型会先消耗大量 token 做隐藏推理，
     * 沿用全局的 500 很可能导致 content 为空。
     *
     * @param maxTokensOverride 为空则用全局配置
     * @param temperatureOverride 为空则用全局配置
     */
    public LlmResult chat(String username, List<ChatMessage> messages,
                          Integer maxTokensOverride, Double temperatureOverride) {
        LlmConfig cfg = resolveConfig(username);
        if (cfg.apiKey() == null || cfg.apiKey().isBlank() || cfg.baseUrl().isBlank()) {
            return new LlmResult("抱歉，AI 服务不可用。请在设置中配置 API 密钥。",
                    LlmUsage.empty(), "client_missing");
        }
        if (maxTokensOverride != null || temperatureOverride != null) {
            cfg = new LlmConfig(cfg.apiKey(), cfg.baseUrl(), cfg.model(),
                    temperatureOverride != null ? temperatureOverride : cfg.temperature(),
                    maxTokensOverride != null ? maxTokensOverride : cfg.maxTokens(),
                    cfg.maxContextRounds());
        }
        try {
            String body = buildRequestBody(cfg, messages, false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.error("LLM 调用失败 HTTP {}: {}", response.statusCode(), truncate(response.body()));
                return new LlmResult("AI服务暂时不可用，请查看服务器日志", LlmUsage.empty(),
                        "http_" + response.statusCode());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String reply = OutputFilter.clean(contentNode.isMissingNode() ? "" : contentNode.asText(""));

            JsonNode usageNode = root.path("usage");
            LlmUsage usage = usageNode.isMissingNode() ? LlmUsage.empty() : new LlmUsage(
                    usageNode.path("prompt_tokens").asInt(0),
                    usageNode.path("completion_tokens").asInt(0),
                    usageNode.path("total_tokens").asInt(0));

            log.info("[LLM] 完成 model={} len={} tokens={}", cfg.model(), reply.length(), usage.totalTokens());
            return new LlmResult(reply, usage, null);
        } catch (Exception e) {
            log.error("LLM 调用异常: {}", e.getMessage());
            return new LlmResult("AI服务暂时不可用，请查看服务器日志", LlmUsage.empty(), e.getMessage());
        }
    }

    // ==================== 流式 ====================

    /**
     * 流式调用。每个增量经过 OutputFilter 清洗后交给 onDelta。
     *
     * @return 本次调用的 usage（取不到时按字符估算，与 Python 版一致）
     */
    public LlmUsage streamChat(String username, List<ChatMessage> messages, DeltaConsumer onDelta) {
        return streamChat(username, messages, onDelta, () -> false);
    }

    /**
     * 可取消的流式调用。
     *
     * <p>为什么要显式传 {@code shouldStop}：客户端关掉页面后，如果只是"不再往下写"，
     * LLM 那边仍会一直生成到结束（长回复等于白烧几千 token）。
     * 这里每读到一行就检查一次，一旦取消就跳出循环 —— try-with-resources 会关闭
     * 底层响应流，HTTP 连接随之断开，上游服务也会停止生成。
     *
     * <p>注意不能依赖"容器发现客户端断开后中断线程"这种副作用：那是 Tomcat 的
     * 实现细节，换容器或换异步模型就不成立了。
     */
    public LlmUsage streamChat(String username, List<ChatMessage> messages,
                               DeltaConsumer onDelta, java.util.function.BooleanSupplier shouldStop) {
        return streamChat(username, messages, onDelta, shouldStop, err -> {});
    }

    /**
     * 可取消、可上报错误的流式调用。
     *
     * <p>{@code onError} 与 {@code onDelta} 的分工：出错时（HTTP 失败 / 异常 / 读取超时）
     * 错误文本走 {@code onError} 而不是 {@code onDelta}。调用方（ChatService）据此知道
     * "这次调用失败了"，从而跳过落库 —— 否则错误文案会混进 full 变成正式回复，
     * 写进 messages 表和短期记忆，污染后续每一轮对话的上下文。
     * 展示层通常把 onError 的文本转成普通 chunk 写出，客户端观感与原来一致。
     *
     * @param onError 出错回调，参数是展示给客户端的错误文本（可能为 null 表示无需展示）
     */
    public LlmUsage streamChat(String username, List<ChatMessage> messages,
                               DeltaConsumer onDelta, java.util.function.BooleanSupplier shouldStop,
                               ErrorConsumer onError) {
        LlmConfig cfg = resolveConfig(username);
        if (cfg.apiKey() == null || cfg.apiKey().isBlank() || cfg.baseUrl().isBlank()) {
            // 走 onError 而不是 onDelta：让调用方知道"这次没成功"，从而跳过落库，
            // 与非流式路径（result.error() != null 时不落库）保持一致。
            // 文本仍会展示给客户端（onError 的实现方通常转成普通 chunk）。
            onError.accept("抱歉，AI 服务不可用。请在设置中配置 API 密钥。");
            return LlmUsage.empty();
        }

        OutputFilter.Streaming filter = new OutputFilter.Streaming();
        StringBuilder full = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        boolean cancelled = false;
        boolean timedOut = false;

        try {
            String body = buildRequestBody(cfg, messages, true);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody;
                try (InputStream errStream = response.body()) {
                    errBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.error("LLM 流式调用失败 HTTP {}: {}", response.statusCode(), truncate(errBody));
                onError.accept("\n[错误: AI服务异常，请查看服务器日志]");
                return LlmUsage.empty();
            }

            // 读空闲看门狗：HttpRequest.timeout 不覆盖 body 的惰性读取，上游中途僵死时
            // readLine() 会无限期阻塞。超过 IDLE_READ 没有新数据就强制关闭流，
            // 阻塞中的 readLine() 会抛 IOException，在下面的 catch 里按超时处理。
            final InputStream bodyStream = response.body();
            AtomicLong lastProgress = new AtomicLong(System.nanoTime());
            ScheduledFuture<?> guard = idleWatchdog.scheduleAtFixedRate(() -> {
                try {
                    if (System.nanoTime() - lastProgress.get() > IDLE_READ_NANOS) {
                        bodyStream.close();
                    }
                } catch (Exception ignored) {
                    // 看门狗只负责兜底，异常直接吞掉
                }
            }, 15, 15, TimeUnit.SECONDS);

            try {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastProgress.set(System.nanoTime());
                        if (shouldStop.getAsBoolean()) {
                            cancelled = true;
                            log.info("[LLM] 客户端已断开，中止生成（已收到 {} 字符）", full.length());
                            break;
                        }
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String payload = line.substring(5).trim();
                        if (payload.isEmpty() || "[DONE]".equals(payload)) {
                            continue;
                        }
                        JsonNode node;
                        try {
                            node = mapper.readTree(payload);
                        } catch (Exception parseErr) {
                            continue;
                        }
                        JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                        if (!delta.isMissingNode() && !delta.isNull()) {
                            String text = delta.asText("");
                            if (!text.isEmpty()) {
                                full.append(text);
                                String safe = filter.feed(text);
                                if (!safe.isEmpty()) {
                                    onDelta.accept(safe);
                                }
                            }
                        }
                        JsonNode usageNode = node.path("usage");
                        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                            promptTokens = Math.max(promptTokens, usageNode.path("prompt_tokens").asInt(0));
                            completionTokens = Math.max(completionTokens, usageNode.path("completion_tokens").asInt(0));
                        }
                    }
                }
            } catch (IOException e) {
                if (System.nanoTime() - lastProgress.get() >= IDLE_READ_NANOS) {
                    timedOut = true;
                    log.warn("[LLM] 流式读取空闲超时，已中断（已收到 {} 字符）", full.length());
                } else {
                    throw e;   // 普通 IO 错误，走外层统一异常处理
                }
            } finally {
                guard.cancel(false);
            }

            if (timedOut) {
                onError.accept("\n[错误: AI服务响应超时，请稍后重试]");
                return LlmUsage.empty();
            }

            if (!cancelled) {
                String rest = filter.flush();
                if (!rest.isEmpty()) {
                    onDelta.accept(rest);
                }
            }
        } catch (Exception e) {
            log.error("LLM 流式调用异常: {}", e.getMessage());
            onError.accept("\n[错误: AI服务异常，请查看服务器日志]");
            return LlmUsage.empty();
        }

        // 服务端没给 usage 时按字符估算，与 Python 的 len(content)//2 一致
        if (promptTokens == 0) {
            promptTokens = messages.stream().mapToInt(m -> m.content().length() / 2).sum();
        }
        if (completionTokens == 0) {
            completionTokens = full.length() / 2;
        }
        LlmUsage usage = new LlmUsage(promptTokens, completionTokens, promptTokens + completionTokens);
        log.info("[LLM] 流式完成 len={} tokens={} cancelled={}", full.length(), usage.totalTokens(), cancelled);
        return usage;
    }

    @FunctionalInterface
    public interface DeltaConsumer {
        void accept(String delta);
    }

    /** 流式调用出错回调：与 DeltaConsumer 分开，让调用方知道"失败了"从而跳过落库 */
    @FunctionalInterface
    public interface ErrorConsumer {
        void accept(String error);
    }

    // ==================== 请求构造 ====================

    private String buildRequestBody(LlmConfig cfg, List<ChatMessage> messages, boolean stream) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        List<Map<String, String>> msgList = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            msgList.add(m.toMap());
        }
        body.put("messages", msgList);
        body.put("temperature", cfg.temperature());
        body.put("max_tokens", cfg.maxTokens());
        if (stream) {
            body.put("stream", true);
        }
        return mapper.writeValueAsString(body);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
