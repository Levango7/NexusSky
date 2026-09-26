-- V13__spray_delivery_persistence.sql: Spray/Delivery 域持久化
-- 为喷洒任务和配送任务创建持久化表，支持重启后状态恢复和租户隔离

-- ===== 喷洒任务表 =====
CREATE TABLE IF NOT EXISTS spray_tasks (
    id                  INTEGER PRIMARY KEY,
    sysid               INTEGER NOT NULL,
    status              VARCHAR(20) NOT NULL,
    flow_rate           DOUBLE,
    total_volume        DOUBLE,
    sprayed_volume      DOUBLE,
    gripper_open        BOOLEAN,
    covered_area        DOUBLE,
    spray_width         DOUBLE,
    current_segment     INTEGER,
    remaining_chemical  DOUBLE,
    waypoints           VARCHAR(4000),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP,
    tenant_id           INTEGER
);

-- ===== 配送任务序列表 =====
CREATE TABLE IF NOT EXISTS delivery_sequences (
    id                  INTEGER PRIMARY KEY,
    sysid               INTEGER NOT NULL,
    status              VARCHAR(30) NOT NULL,
    current_index       INTEGER,
    sites               VARCHAR(8000),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP,
    tenant_id           INTEGER
);

-- ===== 索引 =====
CREATE INDEX IF NOT EXISTS idx_spray_tasks_tenant_id ON spray_tasks (tenant_id);
CREATE INDEX IF NOT EXISTS idx_spray_tasks_sysid_status ON spray_tasks (sysid, status);
CREATE INDEX IF NOT EXISTS idx_delivery_sequences_tenant_id ON delivery_sequences (tenant_id);
CREATE INDEX IF NOT EXISTS idx_delivery_sequences_sysid_status ON delivery_sequences (sysid, status);