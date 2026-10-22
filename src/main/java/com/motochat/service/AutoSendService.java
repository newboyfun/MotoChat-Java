package com.motochat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.motochat.entity.User;
import com.motochat.llm.LlmGateway;
import com.motochat.mapper.UserMapper;
import com.motochat.ws.WsSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主动消息。对应 Python 版 services/autosend.py。
 *
 * <p>规则：距上次对话超过 min_hours 后，在 [min_hours, max_hours] 区间内随机挑一个时刻，
 * 让角色主动发一条消息；落在静默时段则跳过并重新排期。
 *
 * <p>原版用 threading.Timer 链式调度。这里用 Spring 的 TaskScheduler，
 * 每次触发后重新排下一次 —— 逻辑等价，但不需要手动管理 Timer 的取消。
 */
@Service
public class AutoSendService {

    private static final Logger log = LoggerFactory.getLogger(AutoSendService.class);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ConfigService configService;
    private final ChatService chatService;
    private final ConversationService conversationService;
    private final WsSessionRegistry wsRegistry;
    private final UserMapper userMapper;
    private final TaskScheduler taskScheduler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile LocalDateTime lastChatTime = LocalDateTime.now();

    public AutoSendService(ConfigService configService,
                           ChatService chatService,
                           ConversationService conversationService,
                           WsSessionRegistry wsRegistry,
                           UserMapper userMapper,
                           TaskScheduler taskScheduler) {
        this.configService = configService;
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.wsRegistry = wsRegistry;
        this.userMapper = userMapper;
        this.taskScheduler = taskScheduler;
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduleNext();
            log.info("自动消息服务已启动");
        }
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        running.set(false);
    }

    /** 用户有实际对话时重置空闲计时 */
    public void updateLastChatTime() {
        this.lastChatTime = LocalDateTime.now();
    }

    private void scheduleNext() {
        if (!running.get()) {
            return;
        }
        double minHours = configService.getDouble("behavior.auto_message.min_hours", 2);
        double maxHours = configService.getDouble("behavior.auto_message.max_hours", 6);
        if (maxHours <= 0) {
            maxHours = Math.max(minHours, 1);
        }
        double delaySeconds = minHours * 3600
                + ThreadLocalRandom.current().nextDouble() * Math.max(0, (maxHours - minHours) * 3600);
        taskScheduler.schedule(this::fire, Instant.now().plusSeconds((long) delaySeconds));
        log.debug("下次主动消息约在 {} 秒后", (long) delaySeconds);
    }

    private void fire() {
        if (!running.get()) {
            return;
        }
        try {
            if (isQuietTime()) {
                log.debug("处于静默时段，跳过主动消息");
                return;
            }
            double minIdleHours = configService.getDouble("behavior.auto_message.min_hours", 2);
            if (Duration.between(lastChatTime, LocalDateTime.now()).toSeconds() < minIdleHours * 3600 - 1) {
                return;
            }
            String content = configService.getString("behavior.auto_message.content", "最近怎么样？");
            sendMessage(content);
        } catch (Exception e) {
            log.error("自动消息失败: {}", e.getMessage());
        } finally {
            scheduleNext();
        }
    }

    private void sendMessage(String theme) {
        String prompt = "现在是一位AI角色主动联系用户的时候。提示主题: " + theme
                + "。请以你的人设身份，用自然亲切的语气发起对话。不要超过两句话。";
        LlmGateway.LlmResult result = chatService.handle(prompt, "default", "System", false);
        String reply = (result.reply() == null || result.reply().isBlank()) ? "(无回复)" : result.reply();

        for (String cid : targetConversations()) {
            try {
                conversationService.saveNotification(cid, "主动消息", reply);
            } catch (Exception e) {
                log.debug("自动消息持久化失败: {}", e.getMessage());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "notification");
        payload.put("title", "主动消息");
        payload.put("content", reply);
        wsRegistry.broadcast(payload, null);

        updateLastChatTime();
        log.info("自动消息已发送: {}", reply.length() > 60 ? reply.substring(0, 60) + "..." : reply);
    }

    private List<String> targetConversations() {
        try {
            List<User> users = userMapper.selectList(
                    Wrappers.<User>lambdaQuery().select(User::getConversationId));
            List<String> cids = new ArrayList<>();
            for (User u : users) {
                if (u.getConversationId() != null && !u.getConversationId().isBlank()) {
                    cids.add(u.getConversationId());
                }
            }
            return cids.isEmpty() ? List.of("default") : cids;
        } catch (Exception e) {
            return List.of("default");
        }
    }

    /** 静默时段判断，支持跨零点（22:00 ~ 07:00） */
    private boolean isQuietTime() {
        try {
            LocalTime now = LocalTime.now();
            String startStr = configService.getString("behavior.quiet_time.start", "23:00");
            String endStr = configService.getString("behavior.quiet_time.end", "07:00");
            LocalTime start = LocalTime.parse(startStr, HH_MM);
            LocalTime end = LocalTime.parse(endStr, HH_MM);
            if (!start.isAfter(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            }
            return !now.isBefore(start) || !now.isAfter(end);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
