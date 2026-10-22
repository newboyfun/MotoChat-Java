package com.motochat.controller;

import com.motochat.common.ApiException;
import com.motochat.security.CurrentUser;
import com.motochat.security.RequireAdmin;
import com.motochat.security.RequireLogin;
import com.motochat.security.SessionUser;
import com.motochat.service.ConversationService;
import com.motochat.service.PersonaState;
import com.motochat.service.ReminderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话、历史、提醒、通知。契约对齐 Python 版 api/conversations.py。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ReminderService reminderService;
    private final PersonaState personaState;

    public ConversationController(ConversationService conversationService,
                                  ReminderService reminderService,
                                  PersonaState personaState) {
        this.conversationService = conversationService;
        this.reminderService = reminderService;
        this.personaState = personaState;
    }

    @GetMapping
    @RequireAdmin
    public Map<String, Object> list() {
        return Map.of("conversations", conversationService.listConversations());
    }

    @PostMapping("/create")
    @RequireLogin
    public Map<String, Object> create(@Valid @RequestBody CreateConversationRequest req) {
        String cid = conversationService.createConversation(req.title(), personaState.name());
        return Map.of("conversation_id", cid);
    }

    @GetMapping("/{cid}")
    @RequireLogin
    public Map<String, Object> history(@PathVariable String cid,
                                       @CurrentUser(required = true) SessionUser user,
                                       @RequestParam(defaultValue = "200") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        if (!user.isAdmin()) {
            // 普通用户只能看自己的会话，忽略前端传来的 cid
            cid = user.conversationId() == null || user.conversationId().isBlank()
                    ? cid : user.conversationId();
        }
        ConversationService.HistoryPage page = conversationService.getHistory(cid, limit, offset);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", page.messages());
        body.put("has_more", page.hasMore());
        body.put("next_offset", page.nextOffset());
        return body;
    }

    @PostMapping("/reminders/create")
    @RequireAdmin
    public Map<String, Object> createReminder(@Valid @RequestBody CreateReminderRequest req) {
        LocalDateTime target = parseFlexible(req.targetTime());
        if (target == null) {
            throw ApiException.badRequest("时间格式不合法");
        }
        String rid = reminderService.add(req.conversationId(), target, req.content());
        return Map.of("reminder_id", rid);
    }

    @PostMapping("/reminders/{rid}/cancel")
    @RequireAdmin
    public Map<String, Object> cancelReminder(@PathVariable String rid) {
        return Map.of("success", reminderService.cancel(rid));
    }

    @GetMapping("/notifications/{cid}")
    @RequireLogin
    public Map<String, Object> notifications(@PathVariable String cid,
                                             @CurrentUser(required = true) SessionUser user) {
        if (!user.isAdmin()) {
            cid = user.conversationId() == null || user.conversationId().isBlank()
                    ? cid : user.conversationId();
        }
        var items = conversationService.unreadNotifications(cid);
        conversationService.markNotificationsRead(cid);
        return Map.of("notifications", items);
    }

    /**
     * 兼容 Python datetime.fromisoformat 的宽松解析：
     * 接受 2026-10-21T19:00、2026-10-21T19:00:00、2026-10-21T19:00:00+08:00。
     */
    static LocalDateTime parseFlexible(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().replace(" ", "T");
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException ignored) {
            // 继续尝试带时区的格式
        }
        try {
            return OffsetDateTime.parse(v).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public record CreateConversationRequest(String title) {
        public CreateConversationRequest {
            if (title == null || title.isBlank()) {
                title = "新会话";
            }
        }
    }

    public record CreateReminderRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("conversation_id") String conversationId,
            @com.fasterxml.jackson.annotation.JsonProperty("target_time") @NotBlank String targetTime,
            @NotBlank String content
    ) {
        public CreateReminderRequest {
            if (conversationId == null || conversationId.isBlank()) {
                conversationId = "default";
            }
        }
    }
}
