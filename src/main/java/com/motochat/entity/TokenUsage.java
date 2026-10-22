package com.motochat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("token_usage")
public class TokenUsage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String model;
    private LocalDateTime createdAt;
}
