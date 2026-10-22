package com.motochat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.motochat.entity.Reminder;
import com.motochat.entity.User;
import com.motochat.llm.LlmGateway;
import com.motochat.mapper.UserMapper;
import com.motochat.ws.WsSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时提醒。对应 Python 版 services/reminder.py。
 *
 * <p>原版用「线程 + Condition 精确等待」，按最近一个待触发时间唤醒。
 * 这里换成 10 秒一次的轮询扫描 —— 对"定时提醒"这个精度需求完全够用，
 * 且不用维护唤醒事件、重启后天然恢复（所有状态都在数据库里）。
 *
 * <p>触发失败（如 LLM 超时）会把目标时间推后 60 秒重试，与原版一致，
 * 避免"提醒永久丢失"。
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final int RETRY_DELAY_SECONDS = 60;

    private final ConversationService conversationService;
    private final ChatService chatService;
    private final WsSessionRegistry wsRegistry;
    private final UserMapper userMapper;

    /** 内存中的待触发集合，启动时从数据库恢复 */
    private final Map<String, Reminder> pending = new ConcurrentHashMap<>();

    /**
     * 正在触发中的提醒 id。触发在虚拟线程里跑（最长等 LLM 两分钟），
     * 期间的取消请求与触发完成存在竞态——原版 Python 触发和取消同在调度线程，
     * 结构上遇不到这个窗口，Java 版必须自己处理。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 触发完成前被取消的提醒 id：到了 handleFireResult 直接丢弃，不再发通知 */
    private final Set<String> cancelling = ConcurrentHashMap.newKeySet();

    /**
     * 提醒触发专用执行器。用虚拟线程是因为这里会阻塞等 LLM 返回，
     * 平台线程池会被少数几条慢提醒占满。
     */
    private final java.util.concurrent.ExecutorService fireExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public ReminderService(ConversationService conversationService,
                           ChatService chatService,
                           WsSessionRegistry wsRegistry,
                           UserMapper userMapper) {
        this.conversationService = conversationService;
        this.chatService = chatService;
        this.wsRegistry = wsRegistry;
        this.userMapper = userMapper;
    }

    /** 启动时恢复未触发的提醒 */
    @jakarta.annotation.PostConstruct
    public void loadFromDb() {
        try {
            List<Reminder> rows = conversationService.loadAllReminders();
            rows.forEach(r -> pending.put(r.getId(), r));
            log.info("提醒服务已就绪，加载 {} 项", pending.size());
        } catch (Exception e) {
            log.warn("加载提醒失败: {}", e.getMessage());
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        fireExecutor.shutdownNow();
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        for (Reminder r : List.copyOf(pending.values())) {
            if (r.getTargetTime() == null || r.getTargetTime().isAfter(now)) {
                continue;
            }
            // 先从待触发集合摘掉，避免同一项被连续两轮扫描重复触发
            if (!pending.remove(r.getId(), r)) {
                continue;
            }
            // 关键：fire() 会同步调用 LLM，最长可达 120 秒。绝不能在调度线程里跑，
            // 否则后面的扫描、主动消息排期、语音清理全部停摆。丢给虚拟线程，
            // 阻塞成本可以忽略。
            inFlight.add(r.getId());
            fireExecutor.submit(() -> handleFireResult(r));
        }
    }

    private void handleFireResult(Reminder r) {
        try {
            if (cancelling.remove(r.getId())) {
                // 触发开始前就被取消（窗口极小）：直接丢弃
                log.info("提醒 {} 已取消，跳过通知", r.getId());
                return;
            }
            boolean fired = fire(r);
            if (cancelling.remove(r.getId())) {
                // fire() 阻塞等 LLM 期间被取消：fire() 内部已跳过发通知，
                // 这里必须再查一次并丢弃收尾——否则会掉进下面"触发失败→60 秒后重试"的
                // 分支，取消就白点了。只查一次是不够的：取消到达时 fire() 还没返回。
                log.info("提醒 {} 触发期间被取消，跳过通知", r.getId());
                return;
            }
            if (fired) {
                try {
                    conversationService.deleteReminder(r.getId());
                } catch (Exception e) {
                    log.warn("提醒删除失败: {}", e.getMessage());
                }
            } else {
                // 触发失败：延后重试，数据库记录保留
                r.setTargetTime(LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS));
                pending.put(r.getId(), r);
            }
        } finally {
            inFlight.remove(r.getId());
        }
    }

    private boolean fire(Reminder r) {
        log.info("触发提醒: {}", r.getId());
        try {
            String prompt = "现在提醒时间到了，用户设定的提示内容为: " + r.getContent()
                    + "。请以你的人设身份主动找用户聊天。";

            String fireUser = resolveUserByConversation(r.getConversationId());
            LlmGateway.LlmResult result = chatService.handle(prompt, r.getConversationId(), fireUser, false);
            if (cancelling.contains(r.getId())) {
                // LLM 返回前用户已取消：到此为止，通知不发（handleFireResult 会丢弃收尾）
                return false;
            }
            String reply = (result.reply() == null || result.reply().isBlank()) ? "(无回复)" : result.reply();

            conversationService.saveNotification(r.getConversationId(), "提醒", reply);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "notification");
            payload.put("title", "提醒");
            payload.put("content", reply);
            wsRegistry.broadcast(payload, r.getConversationId());
            return true;
        } catch (Exception e) {
            log.error("提醒执行失败: {}", e.getMessage());
            return false;
        }
    }

    /** 按会话归属反查用户名，让提醒使用该用户选的人设 */
    private String resolveUserByConversation(String cid) {
        try {
            User u = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                    .eq(User::getConversationId, cid).last("LIMIT 1"));
            return u == null ? "System" : u.getUsername();
        } catch (Exception e) {
            return "System";
        }
    }

    public String add(String cid, LocalDateTime target, String content) {
        String rid = "rem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Reminder r = new Reminder();
        r.setId(rid);
        r.setConversationId(cid);
        r.setTargetTime(target);
        r.setContent(content);
        pending.put(rid, r);
        try {
            conversationService.saveReminder(rid, cid, target, content);
        } catch (Exception e) {
            log.warn("提醒持久化失败: {}", e.getMessage());
        }
        return rid;
    }

    public boolean cancel(String rid) {
        boolean removed = pending.remove(rid) != null;
        // 不在 pending 里，可能正在触发中（LLM 还没返回）：标记取消，
        // 触发完成后跳过通知。原版 Python 单线程调度遇不到此窗口，返回 false 即可；
        // Java 版触发已挪到虚拟线程，必须区分"不存在"和"正在触发"。
        boolean firing = !removed && inFlight.contains(rid);
        if (firing) {
            cancelling.add(rid);
        }
        if (removed || firing) {
            try {
                conversationService.deleteReminder(rid);
            } catch (Exception e) {
                log.warn("提醒删除失败: {}", e.getMessage());
            }
        }
        return removed || firing;
    }

    public List<Map<String, Object>> listAll() {
        return pending.values().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("conversation_id", r.getConversationId());
            m.put("target_time", r.getTargetTime() == null ? "" : r.getTargetTime().toString());
            m.put("content", r.getContent());
            return m;
        }).toList();
    }

    public int count() {
        return pending.size();
    }
}
