package com.motochat.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 会话中的用户快照。
 * 字段名必须与 Python 版返回的 JSON 完全一致（conversation_id 是下划线）。
 */
public record SessionUser(
        String username,
        String role,
        String persona,
        @JsonProperty("conversation_id") String conversationId
) {
    /** @JsonIgnore 必须加：否则 Jackson 会把 isAdmin() 当成 getter，多吐一个 "admin": true 字段 */
    @JsonIgnore
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
