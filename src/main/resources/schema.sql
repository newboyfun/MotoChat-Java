-- MotoChat 数据库结构（MySQL 8）
-- 与原 SQLite 版字段一一对应，仅做两处索引优化，见 MIGRATION_PLAN.md §5.2

CREATE TABLE IF NOT EXISTS users (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    username          VARCHAR(64)  NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    role              VARCHAR(16)  NOT NULL DEFAULT 'user',   -- admin / user
    conversation_id   VARCHAR(64)  NOT NULL,
    persona_name      VARCHAR(100) NULL,
    user_personas     TEXT         NULL,                      -- JSON: 用户自定义角色 {name: content}
    api_config        TEXT         NULL,                      -- JSON: {api_key, base_url, model}
    persona_override  TEXT         NULL,                      -- JSON: {name: content}
    pending_override  TEXT         NULL,                      -- JSON: {name: content}
    allowed_personas  TEXT         NULL,                      -- JSON 数组
    library_access    TINYINT(1)   NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversations (
    id         VARCHAR(64)  NOT NULL,
    title      VARCHAR(200) NOT NULL DEFAULT '',
    avatar     VARCHAR(100) NOT NULL DEFAULT 'MONO',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_conv_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS messages (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64) NOT NULL,
    role            VARCHAR(20) NOT NULL,                     -- user / assistant / system
    content_type    VARCHAR(20) NOT NULL DEFAULT 'text',
    content         MEDIUMTEXT  NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_msg_cid_id (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notifications (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64)  NOT NULL,
    title           VARCHAR(200) NOT NULL DEFAULT '',
    content         TEXT         NOT NULL,
    is_read         TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_noti_cid_read (conversation_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reminders (
    id              VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    content         TEXT        NOT NULL,
    target_time     DATETIME    NOT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rem_cid (conversation_id),
    KEY idx_rem_time (target_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS token_usage (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    username          VARCHAR(64)  NOT NULL,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    model             VARCHAR(100) NOT NULL DEFAULT '',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tu_user (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初始管理员：admin / admin123
-- 哈希由 Python 侧 hashlib.pbkdf2_hmac('sha256', pw, salt, 310000) 生成，
-- 格式与旧版 config.json 完全一致，验证 §5.3 的兼容性。
INSERT IGNORE INTO users (username, password_hash, role, conversation_id, persona_name)
VALUES ('admin',
        '$pbkdf2-sha256$310000$a1b2c3d4e5f60718293a4b5c6d7e8f90$824de8b696405246d9d702d69045f391205ff1db14cc335b7775e2bda43ac641',
        'admin', 'admin', 'MONO');
