-- V3__geofence_tables.sql: 电子围栏模块两张表
-- 覆盖 2 个 JPA 实体表
-- 列名与 Hibernate SpringPhysicalNamingStrategy 生成结果一致

-- ===== 围栏区域表 (GeofenceZoneEntity) =====
CREATE TABLE IF NOT EXISTS geofence_zone (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255),
    type VARCHAR(20),
    center_lat DOUBLE PRECISION,
    center_lon DOUBLE PRECISION,
    radius_m DOUBLE PRECISION,
    points TEXT,
    action VARCHAR(20),
    enabled BOOLEAN,
    created_at_ms BIGINT
);

-- ===== 越界事件表 (GeofenceBreachEventEntity) =====
CREATE TABLE IF NOT EXISTS geofence_breach_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sysid INTEGER,
    zone_id INTEGER,
    zone_name VARCHAR(255),
    breach_type VARCHAR(20),
    lat DOUBLE PRECISION,
    lon DOUBLE PRECISION,
    timestamp_ms BIGINT
);