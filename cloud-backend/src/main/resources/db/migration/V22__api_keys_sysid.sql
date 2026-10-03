-- V22__api_keys_sysid.sql: 按设备签发 API Key（收口"整部署一把共享摄取 key"）
--
-- 1) api_keys.sysid：把 Key 绑定到具体设备（MAVLink sysid）。NULL = 普通用户/租户级
--    Key（历史行为不变）；非 NULL = 设备 Key（role 固定 OPERATOR，tenantId 取自
--    devices.tenant_id，撤销/轮换粒度 = 单台设备）。
-- 2) 删除 devices.device_token：该列自 V1 起没有任何读写调用方（仅 DeviceEntity
--    的字段与 getter/setter，全仓零引用），是死列；设备认证统一走 api_keys。

ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS sysid INTEGER;

ALTER TABLE devices DROP COLUMN IF EXISTS device_token;
