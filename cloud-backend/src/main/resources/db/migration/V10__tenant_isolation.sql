-- V10__tenant_isolation.sql: 为业务表添加 tenant_id 列，支持数据租户隔离
-- 所有 tenant_id 列允许 NULL（兼容现有数据，NULL 表示全局数据或未分配租户）

-- ===== 设备注册表 =====
ALTER TABLE devices ADD COLUMN IF NOT EXISTS tenant_id INTEGER;

-- ===== 围栏区域表 =====
ALTER TABLE geofence_zone ADD COLUMN IF NOT EXISTS tenant_id INTEGER;

-- ===== 越界事件表 =====
ALTER TABLE geofence_breach_event ADD COLUMN IF NOT EXISTS tenant_id INTEGER;

-- ===== 无人机最后已知位置表 =====
ALTER TABLE drone_last_known_position ADD COLUMN IF NOT EXISTS tenant_id INTEGER;