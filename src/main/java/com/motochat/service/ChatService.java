package com.motochat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.motochat.common.JsonHelper;
import com.motochat.entity.User;
import com.motochat.llm.EmotionTags;
import com.motochat.llm.LlmGateway;
import com.motochat.llm.OutputFilter;
import com.motochat.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天业务主流程：人设解析 → 组装 prompt → 调 LLM → 写记忆与数据库。
 * 对应 Python 版 services/chat.py。
 *
 * <p>流式和非流式两条路径共用同一套「解析人设 / 读记忆 / 建 prompt / 落库」逻辑，
 * 只有调用 LLM 的方式不同 —— 原版也是这么组织的，避免两条路径行为漂移。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    private final LlmGateway llmGateway;
    private final MemoryService memoryService;
    private final ConversationService conversationService;
    private final PersonaState personaState;
    private final UserMapper userMapper;
    private final com.motochat.common.FileHelper fileHelper;

    private final String basePrompt;

    public ChatService(LlmGateway llmGateway,
                       MemoryService memoryService,
                       ConversationService conversationService,
                       PersonaState personaState,
                       UserMapper userMapper,
                       com.motochat.common.FileHelper fileHelper) {
        this.llmGateway = llmGateway;
        this.memoryService = memoryService;
        this.conversationService = conversationService;
        this.personaState = personaState;
        this.userMapper = userMapper;
        this.fileHelper = fileHelper;
        this.basePrompt = loadResource("static/prompt_base.md");
    }

    // ==================== 对外入口 ====================

    /** 非流式。返回回复文本与用量。 */
    public LlmGateway.LlmResult handle(String content, String conversationId, String user, boolean saveToDb) {
        try {
            if (content == null || content.isBlank()) {
                return new LlmGateway.LlmResult("你好呀~", LlmGateway.LlmUsage.empty(), null);
            }
            if (content.strip().startsWith("/")) {
                String reply = runCommand(content, user, conversationId, saveToDb);
                return new LlmGateway.LlmResult(reply, LlmGateway.LlmUsage.empty(), null);
            }
            return chat(content, conversationId, user, saveToDb);
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage(), e);
            return new LlmGateway.LlmResult("抱歉，处理消息时出了点问题，请稍后再试~",
                    LlmGateway.LlmUsage.empty(), e.getMessage());
        }
    }

    /** 流式。增量通过 sink 推出，结束后自动落库。 */
    public void handleStream(String content, String conversationId, String user, boolean saveToDb, StreamSink sink) {
        try {
            if (content == null || content.isBlank()) {
                sink.onText("你好呀~");
                sink.onUsage(LlmGateway.LlmUsage.empty());
                return;
            }
            if (content.strip().startsWith("/")) {
                String reply = runCommand(content, user, conversationId, saveToDb);
                sink.onText(reply);
                sink.onUsage(LlmGateway.LlmUsage.empty());
                return;
            }

            Avatar avatar = resolveAvatar(user);
            String sysPrompt = buildSystemPrompt(user, avatar);
            String memUser = memKey(user);
            String coreMem = memoryService.getCoreMemory(avatar.name(), memUser);
            List<LlmGateway.ChatMessage> history = memoryService.getRecentContext(avatar.name(), memUser);

            List<LlmGateway.ChatMessage> messages = buildMessages(sysPrompt, history, coreMem, content);

            StringBuilder full = new StringBuilder();
            java.util.concurrent.atomic.AtomicBoolean errored =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            LlmGateway.LlmUsage usage = llmGateway.streamChat(user, messages, delta -> {
                full.append(delta);
                sink.onText(delta);
            }, sink::isCancelled, err -> {
                // LLM 出错：错误文本仍然展示给客户端（转成普通 chunk，与原行为一致），
                // 但不进 full、不落库
                errored.set(true);
                sink.onText(err);
            });

            String reply = clean(full.toString());
            sink.onUsage(usage);

            // 客户端已断开或 LLM 出错时不落库：半截回复/错误文案写进历史只会污染上下文。
            // （Python 原版的流式路径同样不在异常时落库——保存代码在 try 块内，异常即跳过。）
            if (saveToDb && !sink.isCancelled() && !errored.get()) {
                memoryService.addConversation(avatar.name(), content, reply, memUser, false);
                conversationService.saveMessage(conversationId, "user", content, "text");
                conversationService.saveMessage(conversationId, "assistant", reply, "text");
                if (usage.totalTokens() > 0) {
                    conversationService.saveTokenUsage(user, usage.promptTokens(),
                            usage.completionTokens(), usage.totalTokens(),
                            llmGateway.resolveConfig(user).model());
                }
            }
        } catch (Exception e) {
            log.error("流式处理失败: {}", e.getMessage(), e);
            sink.onText("\n[错误: 处理消息时出了点问题，请稍后再试~]");
        }
    }

    // ==================== 对话核心 ====================

    private LlmGateway.LlmResult chat(String content, String cid, String user, boolean saveToDb) {
        Avatar avatar = resolveAvatar(user);
        String sysPrompt = buildSystemPrompt(user, avatar);
        String memUser = memKey(user);
        String coreMem = memoryService.getCoreMemory(avatar.name(), memUser);
        List<LlmGateway.ChatMessage> history = memoryService.getRecentContext(avatar.name(), memUser);

        String cleanMsg = EmotionTags.clean(content);
        List<LlmGateway.ChatMessage> messages = buildMessages(sysPrompt, history, coreMem,
                (cleanMsg == null || cleanMsg.isBlank()) ? content : cleanMsg);

        LlmGateway.LlmResult result = llmGateway.chat(user, messages);
        String reply = clean(result.reply());

        // LLM 出错（未配置 key / HTTP 失败 / 异常）时不落库：错误文案会成为正式回复，
        // 污染 messages 表、短期记忆和后续每一轮对话的上下文。
        // 注意：这是相对 Python 原版的有意行为改进（原版非流式路径会连错误文案一起存），
        // 接口响应本身不变——错误文本照样返回给前端，只是不再进历史。
        if (saveToDb && result.error() == null) {
            memoryService.addConversation(avatar.name(), content, reply, memUser, false);
            conversationService.saveMessage(cid, "user", content, "text");
            conversationService.saveMessage(cid, "assistant", reply, "text");
            if (result.usage().totalTokens() > 0) {
                conversationService.saveTokenUsage(user, result.usage().promptTokens(),
                        result.usage().completionTokens(), result.usage().totalTokens(),
                        llmGateway.resolveConfig(user).model());
            }
        }
        return new LlmGateway.LlmResult(reply, result.usage(), result.error());
    }

    /**
     * 组装 messages：system(人设+时间) → system(核心记忆) → 历史 → 当前消息。
     * 时间信息塞进第一条 system 而不是单独一条，少一个 message 的开销（原版同样处理）。
     */
    private List<LlmGateway.ChatMessage> buildMessages(String systemPrompt,
                                                       List<LlmGateway.ChatMessage> history,
                                                       String coreMemory,
                                                       String message) {
        LocalDateTime now = LocalDateTime.now();
        String timeInfo = "\n\n当前时间: " + now.format(TIME_FMT) + " "
                + WEEKDAYS[now.getDayOfWeek().getValue() - 1] + "。用中文回答。";

        List<LlmGateway.ChatMessage> messages = new ArrayList<>();
        messages.add(new LlmGateway.ChatMessage("system", systemPrompt + timeInfo));
        if (coreMemory != null && !coreMemory.isBlank()) {
            messages.add(new LlmGateway.ChatMessage("system", "## 核心记忆\n" + coreMemory));
        }
        messages.addAll(history);
        if (message != null && !message.isBlank()) {
            messages.add(new LlmGateway.ChatMessage("user", message));
        }
        return messages;
    }

    /** 按用户解析生效人设：普通用户选择的自定义角色优先，管理员/系统走全局角色 */
    public Avatar resolveAvatar(String user) {
        String globalName = personaState.name();
        String globalPrompt = personaState.prompt();

        if (user == null || user.isBlank() || "admin".equals(user)
                || "local_user".equals(user) || "System".equals(user)) {
            return new Avatar(globalName, globalPrompt);
        }

        User dbUser = findUser(user);
        if (dbUser == null) {
            return new Avatar(globalName, globalPrompt);
        }
        String selected = com.motochat.common.FileHelper.safePersonaName(dbUser.getPersonaName());
        if (selected == null || selected.equals(globalName)) {
            return new Avatar(globalName, globalPrompt);
        }
        // 用户自建角色：内容存在 user_personas 里
        Map<String, String> userPersonas = JsonHelper.parseStringMap(dbUser.getUserPersonas());
        String custom = userPersonas.get(selected);
        if (custom != null && !custom.isBlank()) {
            return new Avatar(selected, custom);
        }
        // 系统角色：从角色目录读 avatar.md
        String prompt = readPersonaPrompt(selected);
        if (prompt != null && !prompt.isBlank()) {
            return new Avatar(selected, prompt);
        }
        log.warn("用户 {} 选择的角色 {} 不可用，回退全局角色 {}", user, selected, globalName);
        return new Avatar(globalName, globalPrompt);
    }

    private String readPersonaPrompt(String name) {
        try {
            Path md = fileHelper.personaMarkdown(name);
            if (!Files.isRegularFile(md)) {
                return null;
            }
            return Files.readString(md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 拼最终 system prompt：基础规范 + （用户覆盖设定 | 角色设定）。
     * 用户覆盖的优先级最高，会明确标注在提示词里 —— 原版就是这么做的。
     */
    public String buildSystemPrompt(String user, Avatar avatar) {
        String extra = "";
        if (user != null && !user.isBlank()) {
            User dbUser = findUser(user);
            if (dbUser != null) {
                Map<String, String> overrides = JsonHelper.parseStringMap(dbUser.getPersonaOverride());
                extra = overrides.getOrDefault(avatar.name(), "");
            }
        }
        String prompt = extra.isBlank()
                ? avatar.prompt()
                : "## 最高优先级角色设定\n" + extra + "\n\n## 基础角色设定\n" + avatar.prompt();

        if (basePrompt != null && !basePrompt.isBlank()) {
            return basePrompt + "\n\n" + prompt;
        }
        return prompt;
    }

    // ==================== 命令 ====================

    private String runCommand(String content, String user, String conversationId, boolean saveToDb) {
        String reply = execCommand(content.strip(), user);
        if (saveToDb) {
            String memUser = memKey(user);
            memoryService.addConversation(resolveAvatar(user).name(), content, reply, memUser, false);
            conversationService.saveMessage(conversationId, "user", content, "command");
            conversationService.saveMessage(conversationId, "assistant", reply, "text");
        }
        return reply;
    }

    private String execCommand(String cmd, String user) {
        String name = cmd.substring(1).split("\\s+", 2)[0];
        log.info("[指令] 执行: /{} 用户={}", name, user);
        return switch (name) {
            case "help" -> """
                    可用命令:
                    /help - 帮助
                    /diary - 角色日记
                    /state - 角色状态
                    /list - 备忘录
                    /mem - 查看记忆
                    /clear - 清空核心记忆
                    /gen_core_mem - 手动生成核心记忆""";
            case "diary" -> generateContent("diary", user);
            case "state" -> generateContent("state", user);
            case "list" -> generateContent("list", user);
            case "clear" -> {
                memoryService.clearCoreMemory(resolveAvatar(user).name(), memKey(user));
                yield "核心记忆已清空";
            }
            case "mem" -> {
                String core = memoryService.getCoreMemory(resolveAvatar(user).name(), memKey(user));
                yield "核心记忆:\n" + (core == null || core.isBlank() ? "(空)" : core);
            }
            case "gen_core_mem" -> {
                Avatar avatar = resolveAvatar(user);
                String memUser = memKey(user);
                if (memoryService.updateCoreMemory(avatar.name(), memUser)) {
                    yield "核心记忆已更新:\n" + memoryService.getCoreMemory(avatar.name(), memUser);
                }
                yield "核心记忆更新失败(可能没有足够的对话记录)";
            }
            default -> "未知命令: " + name + "。输入 /help 查看可用命令。";
        };
    }

    private String generateContent(String contentType, String user) {
        String template = memoryService.loadPromptTemplate(contentType + ".md");
        if (template == null || template.isBlank()) {
            return "找不到 /" + contentType + " 的提示模板";
        }
        Avatar avatar = resolveAvatar(user);
        List<LlmGateway.ChatMessage> history = memoryService.getRecentContext(avatar.name(), memKey(user));

        StringBuilder recent = new StringBuilder();
        if (history.isEmpty()) {
            recent.append("(暂无对话记录)");
        } else {
            for (int i = 0; i + 1 < history.size(); i += 2) {
                recent.append("用户: ").append(history.get(i).content())
                        .append("\n回复: ").append(history.get(i + 1).content()).append("\n");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        String dateCn = now.getYear() + "年" + now.getMonthValue() + "月" + now.getDayOfMonth() + "日 "
                + WEEKDAYS[now.getDayOfWeek().getValue() - 1];
        String prompt = template.replace("{avatar_name}", avatar.name()).replace("{date_cn}", dateCn);

        String system = "你是 " + avatar.name() + "。根据角色设定和最近对话生成内容。不要使用表情符号。保持角色语气。\n\n"
                + prompt + "\n\n最近对话:\n" + recent;

        LlmGateway.LlmResult result = llmGateway.chat(user, List.of(
                new LlmGateway.ChatMessage("system", system),
                new LlmGateway.ChatMessage("user", "请生成" + contentType)
        ));
        return result.reply();
    }

    // ==================== 辅助 ====================

    /**
     * 记忆目录名。user_ 开头的保持原名，其余用用户名本身，null/空归到 local_user。
     * （与原版 chat.py 的 _mem_key 逐行一致——admin 的记忆目录就是 "admin"。）
     */
    public String memKey(String user) {
        if (user != null && user.startsWith("user_")) {
            return user;
        }
        return (user == null || user.isBlank()) ? "local_user" : user;
    }

    public String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return OutputFilter.clean(text);
    }

    private User findUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    private static String loadResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public String activePersonaName() {
        return personaState.name();
    }

    /** 生效人设（角色名 + 提示词） */
    public record Avatar(String name, String prompt) {
    }

    /** 流式输出回调 */
    public interface StreamSink {
        void onText(String text);

        void onUsage(LlmGateway.LlmUsage usage);

        /**
         * LLM 出错（HTTP 失败 / 异常 / 读取超时）时回调，参数是展示给客户端的错误文本。
         *
         * <p>实现方通常直接转给 {@link #onText}（错误文本作为普通 chunk 展示，客户端观感不变），
         * 但 ChatService 会据此跳过落库——错误文案不该成为正式回复写进 messages 表和短期记忆。
         */
        void onError(String error);

        /**
         * 客户端是否已断开。返回 true 时 LLM 网关会中止读取、关闭上游连接。
         * 默认 false，非交互场景（如内部生成）无需实现。
         */
        default boolean isCancelled() {
            return false;
        }
    }
}
