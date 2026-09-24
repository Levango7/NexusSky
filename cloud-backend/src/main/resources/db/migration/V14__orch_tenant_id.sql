-- V14__orch_tenant_id.sql: 为 Orchestration 域的三张表添加 tenant_id 列，支持数据租户隔离
-- 所有 tenant_id 列允许 NULL（兼容现有数据，NULL 表示全局数据或未分配租户）

-- ===== 编排计划表 =====
ALTER TABLE orch_plan ADD COLUMN IF NOT EXISTS tenant_id INTEGER;

-- ===== 条件触发器表 =====
ALTER TABLE orch_trigger ADD COLUMN IF NOT EXISTS tenant_id INTEGER;

-- ===== 任务步骤表 =====
ALTER TABLE orch_step ADD COLUMN IF NOT EXISTS tenant_id INTEGER;