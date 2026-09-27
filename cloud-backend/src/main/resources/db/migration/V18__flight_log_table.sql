-- V18__flight_log_table.sql: FlightLog 持久化表
-- 覆盖 1 个 JPA 实体表 (FlightLogEntity)
-- 列名与 Hibernate SpringPhysicalNamingStrategy 生成结果一致（下划线命名）

-- ===== flight_log 表 (FlightLogEntity) =====
CREATE TABLE IF NOT EXISTS flight_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    timestamp TIMESTAMP,
    type VARCHAR(50),
    sysid INTEGER,
    lat DOUBLE,
    lon DOUBLE,
    relative_alt DOUBLE,
    groundspeed DOUBLE,
    battery INTEGER,
    voltage INTEGER,
    mode VARCHAR(50),
    armed BOOLEAN,
    online BOOLEAN,
    severity INTEGER,
    text VARCHAR(2000),
    tenant_id INTEGER
);

-- ===== 索引 =====
CREATE INDEX IF NOT EXISTS idx_flight_log_type_sysid ON flight_log (type, sysid);
CREATE INDEX IF NOT EXISTS idx_flight_log_timestamp ON flight_log (timestamp);
CREATE INDEX IF NOT EXISTS idx_flight_log_tenant_id ON flight_log (tenant_id);