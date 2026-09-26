-- V16__mapping_show_delivery2_tenant_id.sql: 为测绘/表演/配送任务表添加 tenant_id 列，支持数据租户隔离
-- 所有 tenant_id 列允许 NULL（兼容现有数据，NULL 表示全局数据或未分配租户）

-- ===== 测绘任务表 =====
ALTER TABLE mapping_task ADD COLUMN IF NOT EXISTS tenant_id INTEGER;

-- ===== 表演任务表 =====
ALTER TABLE show_task ADD COLUMN IF NOT EXISTS tenant_id INTEGER;

-- ===== 配送任务表 =====
ALTER TABLE delivery_task2 ADD COLUMN IF NOT EXISTS tenant_id INTEGER;