package com.motochat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.motochat.entity.Conversation;
import com.motochat.entity.Message;
import com.motochat.entity.Notification;
import com.motochat.entity.Reminder;
import com.motochat.entity.TokenUsage;
import com.motochat.mapper.ConversationMapper;
import com.motochat.mapper.MessageMapper;
import com.motochat.mapper.NotificationMapper;
import com.motochat.mapper.ReminderMapper;
import com.motochat.mapper.TokenUsageMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话、消息、通知、提醒、Token 用量的持久化。
 * 对应 Python 版 app/database.py 的全部 DAO 函数。
 */
@Service
public class ConversationService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final NotificationMapper notificationMapper;
    private final ReminderMapper reminderMapper;
    private final TokenUsageMapper tokenUsageMapper;
    private final PersonaState personaState;

    public ConversationService(ConversationMapper conversationMapper,
                               MessageMapper messageMapper,
                               NotificationMapper notificationMapper,
                               ReminderMapper reminderMapper,
                               TokenUsageMapper tokenUsageMapper,
                               PersonaState personaState) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.notificationMapper = notificationMapper;
        this.reminderMapper = reminderMapper;
        this.tokenUsageMapper = tokenUsageMapper;
        this.personaState = personaState;
    }

    // ===== 会话 =====

    public List<Map<String, Object>> listConversations() {
        List<Conversation> rows = conversationMapper.selectList(
                Wrappers.<Conversation>lambdaQuery().orderByDesc(Conversation::getUpdatedAt));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Conversation c : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("title", c.getTitle());
            m.put("avatar", c.getAvatar());
            m.put("created_at", c.getCreatedAt() == null ? "" : c.getCreatedAt().format(ISO));
            out.add(m);
        }
        return out;
    }

    public String createConversation(String title, String avatar) {
        String cid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Conversation c = new Conversation();
        c.setId(cid);
        c.setTitle(title == null || title.isBlank() ? "新会话" : title);
        c.setAvatar(avatar == null || avatar.isBlank() ? "MONO" : avatar);
        conversationMapper.insert(c);
        return cid;
    }

    public void ensureConversation(String cid, String avatarName) {
        if (conversationMapper.selectById(cid) != null) {
            return;
        }
        Conversation c = new Conversation();
        c.setId(cid);
        c.setTitle("默认会话");
        c.setAvatar(avatarName == null || avatarName.isBlank() ? "MONO" : avatarName);
        try {
            conversationMapper.insert(c);
        } catch (DuplicateKeyException e) {
            // 同一会话的首条消息并发到达时，另一个请求已经建好会话，唯一索引拦截。
            // 这正是我们想要的结果，静默吞掉即可——漏成异常会让用户看到 500。
        }
    }

    public void touchConversation(String cid) {
        Conversation c = conversationMapper.selectById(cid);
        if (c != null) {
            c.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(c);
        }
    }

    @Transactional
    public void deleteConversationData(String cid) {
        if (cid == null || cid.isBlank()) {
            return;
        }
        messageMapper.delete(Wrappers.<Message>lambdaQuery().eq(Message::getConversationId, cid));
        notificationMapper.delete(Wrappers.<Notification>lambdaQuery().eq(Notification::getConversationId, cid));
        reminderMapper.delete(Wrappers.<Reminder>lambdaQuery().eq(Reminder::getConversationId, cid));
        conversationMapper.deleteById(cid);
    }

    public long countMessages() {
        return messageMapper.selectCount(null);
    }

    public long countMessages(String cid) {
        return messageMapper.selectCount(Wrappers.<Message>lambdaQuery().eq(Message::getConversationId, cid));
    }

    // ===== 消息 =====

    @Transactional
    public Long saveMessage(String cid, String role, String content, String contentType) {
        Message m = new Message();
        m.setConversationId(cid);
        m.setRole(role);
        m.setContentType(contentType == null ? "text" : contentType);
        m.setContent(content);
        messageMapper.insert(m);

        Conversation c = conversationMapper.selectById(cid);
        if (c != null) {
            c.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(c);
        } else {
            // 会话记录不存在时自动补建 —— 原版同样处理，否则新用户首次对话后
            // 会话列表里看不到自己刚聊过的那个会话。
            ensureConversation(cid, personaState == null ? "MONO" : personaState.name());
        }
        return m.getId();
    }

    /**
     * 历史消息分页。
     *
     * <p>返回三元组（消息列表、是否还有更多、下一页 offset），与 Python 版
     * {@code get_history} 的语义完全一致：
     * <ul>
     *   <li>limit=0 返回全部</li>
     *   <li>多查一条判断 has_more，避免额外 COUNT</li>
     *   <li>按 id 升序返回，offset 用于往前翻更早的消息</li>
     * </ul>
     */
    public HistoryPage getHistory(String cid, int limit, int offset) {
        if (limit == 0) {
            List<Message> rows = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                    .eq(Message::getConversationId, cid).orderByAsc(Message::getId));
            return new HistoryPage(toMaps(rows), false, 0);
        }
        int safeLimit = Math.min(Math.max(limit, 1), 1000);
        List<Message> rows = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, cid)
                .orderByDesc(Message::getId)
                .last("LIMIT " + (safeLimit + 1) + " OFFSET " + Math.max(offset, 0)));

        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, safeLimit));
        }
        java.util.Collections.reverse(rows);
        List<Map<String, Object>> msgs = toMaps(rows);
        int nextOffset = hasMore ? offset + msgs.size() : 0;
        return new HistoryPage(msgs, hasMore, nextOffset);
    }

    private static List<Map<String, Object>> toMaps(List<Message> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Message r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("role", r.getRole());
            m.put("content_type", r.getContentType());
            m.put("content", r.getContent());
            m.put("created_at", r.getCreatedAt() == null ? "" : r.getCreatedAt().format(ISO));
            out.add(m);
        }
        return out;
    }

    public record HistoryPage(List<Map<String, Object>> messages, boolean hasMore, int nextOffset) {
    }

    // ===== 通知 =====

    public Long saveNotification(String cid, String title, String content) {
        Notification n = new Notification();
        n.setConversationId(cid);
        n.setTitle(title == null || title.isBlank() ? "提醒" : title);
        n.setContent(content);
        n.setIsRead(false);
        notificationMapper.insert(n);
        return n.getId();
    }

    public List<Map<String, Object>> unreadNotifications(String cid) {
        List<Notification> rows = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getConversationId, cid)
                .eq(Notification::getIsRead, false)
                .orderByAsc(Notification::getId));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Notification r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("title", r.getTitle());
            m.put("content", r.getContent());
            m.put("created_at", r.getCreatedAt() == null ? "" : r.getCreatedAt().format(ISO));
            out.add(m);
        }
        return out;
    }

    public void markNotificationsRead(String cid) {
        Notification update = new Notification();
        update.setIsRead(true);
        notificationMapper.update(update, Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getConversationId, cid)
                .eq(Notification::getIsRead, false));
    }

    // ===== 提醒 =====

    public void saveReminder(String rid, String cid, LocalDateTime targetTime, String content) {
        Reminder existing = reminderMapper.selectById(rid);
        if (existing != null) {
            existing.setTargetTime(targetTime);
            existing.setContent(content);
            reminderMapper.updateById(existing);
            return;
        }
        Reminder r = new Reminder();
        r.setId(rid);
        r.setConversationId(cid);
        r.setTargetTime(targetTime);
        r.setContent(content);
        reminderMapper.insert(r);
    }

    public boolean deleteReminder(String rid) {
        return reminderMapper.deleteById(rid) > 0;
    }

    public List<Reminder> loadAllReminders() {
        return reminderMapper.selectList(null);
    }

    public List<Reminder> loadDueReminders(LocalDateTime now) {
        return reminderMapper.selectList(Wrappers.<Reminder>lambdaQuery()
                .le(Reminder::getTargetTime, now).orderByAsc(Reminder::getTargetTime));
    }

    // ===== Token 用量 =====

    public void saveTokenUsage(String username, int promptTokens, int completionTokens, int totalTokens, String model) {
        TokenUsage t = new TokenUsage();
        t.setUsername(username);
        t.setPromptTokens(promptTokens);
        t.setCompletionTokens(completionTokens);
        t.setTotalTokens(totalTokens);
        t.setModel(model == null ? "" : model);
        tokenUsageMapper.insert(t);
    }

    public Map<String, Object> userTokenUsage(String username) {
        Map<String, Object> row = tokenUsageMapper.sumByUsername(username);
        return normalizeUsageRow(row);
    }

    public List<Map<String, Object>> allTokenUsage() {
        List<Map<String, Object>> rows = tokenUsageMapper.sumGroupByUsername();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = normalizeUsageRow(r);
            m.put("username", r.get("username"));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> normalizeUsageRow(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prompt_tokens", toInt(row == null ? null : row.get("prompt_tokens")));
        m.put("completion_tokens", toInt(row == null ? null : row.get("completion_tokens")));
        m.put("total_tokens", toInt(row == null ? null : row.get("total_tokens")));
        m.put("request_count", toInt(row == null ? null : row.get("request_count")));
        return m;
    }

    private static int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
