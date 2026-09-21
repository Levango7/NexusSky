-- V5__track_last_known.sql: 无人机最后已知位置表
-- 仅存储每架无人机的最后已知位置快照，用于重启恢复
-- 列名与 Hibernate SpringPhysicalNamingStrategy 生成结果一致

CREATE TABLE IF NOT EXISTS drone_last_known_position (
    sysid INTEGER PRIMARY KEY,
    timestamp_ms BIGINT,
    lat DOUBLE PRECISION,
    lon DOUBLE PRECISION,
    alt DOUBLE PRECISION,
    vx DOUBLE PRECISION,
    vy DOUBLE PRECISION,
    vz DOUBLE PRECISION,
    heading DOUBLE PRECISION,
    battery_pct DOUBLE PRECISION
);