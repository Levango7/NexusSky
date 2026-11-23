-- V43__scenario_registries.sql: 低空经济场景缺口表（阳台落点 / 农资库存 / 应急节点角色）
--
-- 背景（2026-11-23 容量与场景设计评审，见 docs/capacity-planning.md）：
--   * 高楼阳台配送：现有 airspace_reservations 能预约垂直时空窗，但没有「楼层落点」实体，
--     无法记录承重/可投时段/精确坐标。
--   * 农业作业：spray_tasks 只有 remaining_chemical 快照，没有领用/使用/回收/损耗的流水，
--     规模化后对不上账。
--   * 应急救援：仓库已有「搜救/指挥/测绘」策略占比，但主控/通讯/中继/执行四类节点
--     没有可持久化的角色登记，演练复盘与链路预算无从查起。
--
-- 口径：只建表与索引，不写数据、不改既有表（非破坏性）；每表带 tenant_id 做租户隔离。
-- 不设外键（与 V41/inspection_tasks 同口径，归属校验在服务层）。
-- 不用部分索引/特殊方言：H2 2.2.224（CI 的 Flyway 路径）必须能执行，V42 踩过。

CREATE TABLE IF NOT EXISTS balcony_drop_point (
    point_id        VARCHAR(64) PRIMARY KEY,
    tenant_id       INTEGER NOT NULL,
    building_id     VARCHAR(64) NOT NULL,
    floor           INTEGER NOT NULL,
    lat             DOUBLE PRECISION NOT NULL,
    lon             DOUBLE PRECISION NOT NULL,
    alt_m           DOUBLE PRECISION NOT NULL,
    max_payload_kg  DOUBLE PRECISION,
    allowed_from_ms BIGINT,
    allowed_to_ms   BIGINT,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at_ms   BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_balcony_drop_point_building
    ON balcony_drop_point (tenant_id, building_id);

CREATE TABLE IF NOT EXISTS agri_supply_ledger (
    entry_id       VARCHAR(64) PRIMARY KEY,
    tenant_id      INTEGER NOT NULL,
    task_id        VARCHAR(64),
    sysid          INTEGER,
    supply_type    VARCHAR(32) NOT NULL,
    action         VARCHAR(16) NOT NULL,
    quantity       DOUBLE PRECISION NOT NULL,
    unit           VARCHAR(16) NOT NULL,
    balance_after  DOUBLE PRECISION,
    operator       VARCHAR(64),
    recorded_at_ms BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agri_supply_ledger_task
    ON agri_supply_ledger (tenant_id, task_id);

CREATE TABLE IF NOT EXISTS emergency_node_role (
    role_id        VARCHAR(64) PRIMARY KEY,
    tenant_id      INTEGER NOT NULL,
    plan_id        VARCHAR(64),
    task_id        VARCHAR(64),
    sysid          INTEGER NOT NULL,
    role           VARCHAR(24) NOT NULL,
    link_budget_ms INTEGER,
    seq            INTEGER,
    status         VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at_ms  BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_emergency_node_role_plan
    ON emergency_node_role (tenant_id, plan_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_emergency_node_role
    ON emergency_node_role (tenant_id, plan_id, sysid, role);
