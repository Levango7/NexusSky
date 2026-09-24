-- V12__formation_tenant_id.sql: 为编队表添加 tenant_id 列，支持数据租户隔离
-- tenant_id 列允许 NULL（兼容现有数据，NULL 表示全局数据或未分配租户）

ALTER TABLE formation ADD COLUMN IF NOT EXISTS tenant_id INTEGER;