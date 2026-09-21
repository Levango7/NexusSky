-- V4__surveillance_tables.sql: 安防设备持久化表
-- 覆盖 SurveillanceDeviceEntity 的 JPA 实体表
-- 列名与 Hibernate SpringPhysicalNamingStrategy 生成结果一致

-- ===== 安防设备表 (SurveillanceDeviceEntity) =====
CREATE TABLE IF NOT EXISTS surveillance_device (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255),
    vendor VARCHAR(50),
    ip VARCHAR(50),
    port INTEGER,
    username VARCHAR(255),
    password VARCHAR(255),
    status VARCHAR(20),
    capabilities VARCHAR(500),
    rtsp_url VARCHAR(500),
    last_heartbeat_ms BIGINT
);