-- V30__maintenance_records.sql: 维护记录落库（审计 §2.1「必须落库」第 3 条）
--
-- 背景：MaintenanceController 的维护记录此前是纯内存 Map（idSeq "m-N" 自增）——
-- 重启即失，维护面板清空、预测性维护/工单叙事断。本表把 CRUD 全路径落库。
--
-- 设计口径：
-- 1) id 沿用既有 "m-N" 字符串（API 契约里 id 就是 String，前端已有存量引用形态），
--    主键 VARCHAR ——不改成自增数字，避免"两套 id 词汇"（delivery2 那种坑的先例）。
-- 2) 枚举一律 VARCHAR 存名字（ComponentType/MaintenanceType/Status），与
--    @Enumerated(STRING) 对齐；改枚举名即数据迁移，这是可接受的显式成本。
-- 3) notes 1024：API 文档无长度承诺，给足余量又不至于 TEXT。
-- 4) cost 用 DOUBLE PRECISION（H2/PG 双兼容；协议层就是 JSON number）。
-- 5) created_at 由应用写入（记录创建时刻，供审计追溯），不是 DB 默认值——
--    与 V28 的教训一致：不给"看起来正确"的默认，缺失就是缺失。

CREATE TABLE maintenance_records (
    id               VARCHAR(64) PRIMARY KEY,
    sysid            INTEGER NOT NULL,
    component_type   VARCHAR(32),
    maintenance_type VARCHAR(32),
    scheduled_date   DATE,
    completed_date   DATE,
    status           VARCHAR(32) NOT NULL,
    technician       VARCHAR(128),
    notes            VARCHAR(1024),
    cost             DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_maintenance_records_sysid ON maintenance_records (sysid);
CREATE INDEX idx_maintenance_records_status ON maintenance_records (status);
