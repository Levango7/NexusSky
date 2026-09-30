-- V13__spray_delivery_persistence.sql: Spray/Delivery 域持久化
-- 为喷洒任务和配送任务创建持久化表，支持重启后状态恢复和租户隔离
-- 方言说明：PostgreSQL 不认裸 DOUBLE（H2 认），数值列统一 DOUBLE PRECISION（同 V1 写法）。
-- 实测红因：Pass C（prod profile + PG）报 ERROR: type "double" does not exist。

-- ===== 喷洒任务表 =====
CREATE TABLE IF NOT EXISTS spray_tasks (
    id                  INTEGER PRIMARY KEY,
    sysid               INTEGER NOT NULL,
    status              VARCHAR(20) NOT NULL,
    flow_rate           DOUBLE PRECISION,
    total_volume        DOUBLE PRECISION,
    sprayed_volume      DOUBLE PRECISION,
    gripper_open        BOOLEAN,
    covered_area        DOUBLE PRECISION,
    spray_width         DOUBLE PRECISION,
    current_segment     INTEGER,
    remaining_chemical  DOUBLE PRECISION,
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