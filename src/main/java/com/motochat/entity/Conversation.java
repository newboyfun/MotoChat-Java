package com.motochat.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversations")
public class Conversation {

    @TableId
    private String id;
    private String title;
    private String avatar;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
