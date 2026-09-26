-- V17__webhook.sql: Webhook 注册与事件推送机制
-- 覆盖 1 个 JPA 实体表 (WebhookEntity)
-- 列名与 Hibernate SpringPhysicalNamingStrategy 生成结果一致（下划线命名）

-- ===== Webhook 表 (WebhookEntity) =====
CREATE TABLE IF NOT EXISTS webhooks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    url VARCHAR(500) NOT NULL,
    secret VARCHAR(200),
    events VARCHAR(500),
    tenant_id INTEGER,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);