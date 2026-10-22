package com.motochat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表。对应 Python 版存在 config.json 里的 web.admin + web.users[]。
 *
 * <p>原版把用户属性（人设、API 配置、自定义角色）全塞在 JSON 里，
 * 这里改成一表：JSON 类属性仍用 TEXT 存，但行级并发更新不再互相覆盖。
 */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String role;
    private String conversationId;
    private String personaName;
    /** JSON: {角色名: 内容}，用户自己创建的角色 */
    private String userPersonas;
    /** JSON: {api_key, base_url, model} */
    private String apiConfig;
    /** JSON: {角色名: 覆盖内容}，用户对系统角色的个性化覆盖（已生效） */
    private String personaOverride;
    /** JSON: {角色名: 内容}，等待管理员审批的覆盖 */
    private String pendingOverride;
    /** JSON 数组，允许访问的系统角色；null 表示不限制 */
    private String allowedPersonas;
    private Boolean libraryAccess;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
