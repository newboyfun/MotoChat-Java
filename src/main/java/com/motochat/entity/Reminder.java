package com.motochat.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("reminders")
public class Reminder {

    @TableId
    private String id;
    private String conversationId;
    private String content;
    private LocalDateTime targetTime;
    private LocalDateTime createdAt;
}
