package com.motochat;

import com.motochat.llm.LlmGateway;
import com.motochat.service.ChatService;
import com.motochat.service.ConversationService;
import com.motochat.service.ReminderService;
import com.motochat.ws.WsSessionRegistry;
import com.motochat.entity.Reminder;
import com.motochat.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提醒服务的取消/触发竞态测试。
 *
 * <p>竞态窗口：触发在虚拟线程里等 LLM（最长两分钟），这期间用户点"取消"。
 * 原版 Python 的触发与取消同在调度线程、结构上遇不到；Java 版触发挪到
 * 虚拟线程后必须显式处理，否则取消返回 false、提醒照样弹出通知。
 */
class ReminderServiceTest {

    private ConversationService conversationService;
    private ChatService chatService;
    private WsSessionRegistry wsRegistry;
    private UserMapper userMapper;
    private ReminderService service;

    private static final LlmGateway.LlmResult OK_RESULT =
            new LlmGateway.LlmResult("提醒回复", LlmGateway.LlmUsage.empty(), null);

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        chatService = mock(ChatService.class);
        wsRegistry = mock(WsSessionRegistry.class);
        userMapper = mock(UserMapper.class);
        service = new ReminderService(conversationService, chatService, wsRegistry, userMapper);
        when(userMapper.selectOne(any())).thenReturn(null);       // 反查不到用户 → "System"
        when(chatService.handle(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(OK_RESULT);
    }

    private String addDueReminder() {
        return service.add("cid", LocalDateTime.now().minusSeconds(1), "喝水");
    }

    @Test
    @DisplayName("取消待触发提醒：返回 true 且删库（原行为）")
    void cancelPendingReminder() {
        String rid = addDueReminder();
        assertTrue(service.cancel(rid));
        verify(conversationService).deleteReminder(rid);
        assertEquals(0, service.count());
    }

    @Test
    @DisplayName("取消不存在的提醒：返回 false 且不删库（原行为）")
    void cancelUnknownReminder() {
        assertFalse(service.cancel("rem_nope"));
        verify(conversationService, times(0)).deleteReminder(anyString());
    }

    @Test
    @DisplayName("正常触发：发通知 + 广播 + 删库 + 移出待触发")
    void tickFiresNormally() throws InterruptedException {
        CountDownLatch notified = new CountDownLatch(1);
        when(conversationService.saveNotification(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    notified.countDown();
                    return 1L;
                });

        String rid = addDueReminder();
        service.tick();

        assertTrue(notified.await(5, TimeUnit.SECONDS), "触发应在 5 秒内完成");
        // 触发跑在虚拟线程上，saveNotification 之后还有广播/删库几步，
        // 用 timeout 验证等它跑完，避免主线程抢跑造成偶发失败
        verify(conversationService, timeout(3000)).saveNotification("cid", "提醒", "提醒回复");
        verify(wsRegistry, timeout(3000)).broadcast(any(), eq("cid"));
        verify(conversationService, timeout(3000)).deleteReminder(rid);
        assertEquals(0, service.count());
    }

    @Test
    @DisplayName("触发中被取消：不发通知、不重试、取消返回 true")
    void cancelDuringFireSuppressesNotification() throws InterruptedException {
        CountDownLatch handleEntered = new CountDownLatch(1);
        CountDownLatch releaseHandle = new CountDownLatch(1);
        CountDownLatch handleReturned = new CountDownLatch(1);
        when(chatService.handle(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> {
                    handleEntered.countDown();
                    releaseHandle.await(5, TimeUnit.SECONDS);
                    handleReturned.countDown();
                    return OK_RESULT;
                });

        String rid = addDueReminder();
        service.tick();

        // 等触发线程进入 LLM 调用（虚拟线程已启动并在阻塞中）
        assertTrue(handleEntered.await(5, TimeUnit.SECONDS), "触发应已进入 LLM 调用");
        // 此时点取消：必须识别出"正在触发中"
        assertTrue(service.cancel(rid), "触发中的取消应返回 true");
        verify(conversationService).deleteReminder(rid);

        // 放行 LLM 返回，等触发收尾
        releaseHandle.countDown();
        assertTrue(handleReturned.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);   // 收尾代码是微秒级同步操作，300ms 足够覆盖

        // 通知绝不能发出；不能重复删库；不能进 60 秒重试
        verify(conversationService, times(0)).saveNotification(anyString(), anyString(), anyString());
        verify(wsRegistry, times(0)).broadcast(any(), any());
        verify(conversationService, times(1)).deleteReminder(rid);
        assertEquals(0, service.count(), "被取消的提醒不应进入重试队列");
    }

    /** Mockito.any() 与业务字符串混用的可读性包装 */
    private static String eq(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
