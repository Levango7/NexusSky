-- V11__api_keys_key_hash.sql: API Key 安全强化 — 添加 key_hash 列
-- 将 API Key 认证从明文存储改为 SHA-256 哈希存储
-- key_hash 存储 API Key 的 SHA-256 哈希（Hex 编码，64 字符），用于认证查询
-- 明文 API Key 仅在创建时返回一次，数据库中不存储明文

ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS key_hash VARCHAR(128);

-- 为 key_hash 创建索引，认证查询使用此字段
CREATE INDEX IF NOT EXISTS idx_api_keys_key_hash ON api_keys (key_hash);