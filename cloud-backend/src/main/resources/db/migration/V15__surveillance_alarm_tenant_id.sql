-- V15__surveillance_alarm_tenant_id.sql: 为安防设备表和报警事件表添加 tenant_id 列
-- 支持 Surveillance/Alarm 域的租户隔离，与 V10__tenant_isolation.sql 保持一致
-- tenant_id 列允许 NULL（兼容现有数据，NULL 表示全局数据或未分配租户）

-- ===== 安防设备表 (surveillance_device) =====
ALTER TABLE surveillance_device ADD COLUMN IF NOT EXISTS tenant_id INTEGER;

-- ===== 报警事件表 (alarm_event) =====
ALTER TABLE alarm_event ADD COLUMN IF NOT EXISTS tenant_id INTEGER;