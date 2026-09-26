-- V8__api_keys_table.sql: API Key 认证机制
-- 覆盖 1 个 JPA 实体表 (ApiKeyEntity)
-- 列名与 Hibernate SpringPhysicalNamingStrategy 生成结果一致（下划线命名）

-- ===== API Key 表 (ApiKeyEntity) =====
CREATE TABLE IF NOT EXISTS api_keys (
    key_id VARCHAR(80) PRIMARY KEY,
    tenant_id INTEGER,
    user_id INTEGER,
    name VARCHAR(100) NOT NULL,
    scopes VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    last_used_at TIMESTAMP,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    masked_key VARCHAR(80)
);