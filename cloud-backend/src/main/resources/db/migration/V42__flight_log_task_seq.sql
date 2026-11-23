-- V42__flight_log_task_seq.sql: flight_log 增加任务/编队/序号维度，支撑表演等场景的轨迹归因与分区。
--
-- 背景（2026-11-23 低空经济数据量设计评审）：
--   flight_log 此前只有 sysid + timestamp + tenant_id，表演/物流/农业等任务的轨迹
--   只能靠「sysid + 时间窗」反查，无法按任务分区、无法做 (sysid, seq) 去重。
--   1200 架表演一场 900s × 10Hz ≈ 10.8M 行，必须能按 task_id 裁剪与归档。
--
-- 本迁移只加列与索引，不删不改既有数据（非破坏性）。
--   task_id      ：任务/表演 ID（show_task.id / delivery_task2.id / spray_tasks.id 等）
--   formation_id ：编队组 ID（formation.formation_id），表演分组汇聚/失败隔离的单位
--   seq          ：同一 sysid 的单调序号，用于 at-least-once 写入的去重
ALTER TABLE flight_log ADD COLUMN IF NOT EXISTS task_id VARCHAR(64);
ALTER TABLE flight_log ADD COLUMN IF NOT EXISTS formation_id VARCHAR(64);
ALTER TABLE flight_log ADD COLUMN IF NOT EXISTS seq BIGINT;

-- 按 (tenant_id, task_id, timestamp) 裁剪：表演回放 / 任务审计 / 冷分区归档都走它。
CREATE INDEX IF NOT EXISTS idx_flight_log_tenant_task_time
    ON flight_log (tenant_id, task_id, timestamp);

-- 去重索引：同一 (tenant_id, sysid, seq) 只允许一行。
-- SQL 标准下 NULL 互不相等，因此历史行（seq IS NULL）照样允许多行，无需部分索引。
-- 注意：不能写成 `... WHERE seq IS NOT NULL` —— H2 2.2.224（CI/H2 配置）不支持部分索引，
-- 2026-11-23 的 CI 大面积 E2E 红就是这个语法错误导致 Flyway 启动失败（实测）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_flight_log_tenant_sysid_seq
    ON flight_log (tenant_id, sysid, seq);
