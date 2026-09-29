-- V19__api_keys_role.sql: API Key 携带签发者角色（P0-2 RBAC）
-- SDK 仅用 X-API-Key 认证（NexusSkyClient 只添加 X-API-Key 头），若 Key 上没有角色，
-- 任何标注 @RequireRole 的端点都会对 SDK 路径返回 403。
-- 签发时从 JWT 的 role claim 写入 ApiKeyController；历史 Key 回填 OPERATOR
-- （可下发指令与控制设备，但不可管理用户/租户/Key，后者要求 ADMIN）。

ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS role VARCHAR(20);

UPDATE api_keys SET role = 'OPERATOR' WHERE role IS NULL;
