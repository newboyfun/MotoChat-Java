package com.motochat.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存日志缓冲，供管理端「系统日志」页面读取。
 * 对应 Python 版 server.py 里 _DualHandler 的 _buffer（deque(maxlen=2000)）。
 *
 * <p>实现方式是往 Logback 根 logger 挂一个自定义 Appender，
 * 比拦截 System.out 更可靠。
 */
@Service
public class LogService {

    private static final int MAX_ENTRIES = 2000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Deque<Map<String, Object>> buffer = new ArrayDeque<>(MAX_ENTRIES);
    private final Object lock = new Object();

    @PostConstruct
    public void register() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("time", LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault()).format(TS));
                entry.put("level", event.getLevel().toString().toLowerCase());
                entry.put("message", event.getFormattedMessage());
                add(entry);
            }
        };
        appender.setName("motochat-web-buffer");
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    private void add(Map<String, Object> entry) {
        synchronized (lock) {
            if (buffer.size() >= MAX_ENTRIES) {
                buffer.pollFirst();
            }
            buffer.addLast(entry);
        }
    }

    /** 按级别过滤后取最后 limit 条 */
    public List<Map<String, Object>> tail(String level, int limit) {
        List<Map<String, Object>> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(buffer);
        }
        if (level != null && !level.isBlank() && !"all".equalsIgnoreCase(level)) {
            String target = level.toLowerCase();
            snapshot.removeIf(e -> !target.equals(e.get("level")));
        }
        int from = Math.max(0, snapshot.size() - Math.max(1, limit));
        return snapshot.subList(from, snapshot.size());
    }
}
