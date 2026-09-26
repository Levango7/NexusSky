-- V1__init.sql: 初始化全部 JPA 实体表
-- 覆盖 6 个实体 + 2 个 @ElementCollection 关联表
-- 列名与 Hibernate SpringPhysicalNamingStrategy 生成结果一致

-- ===== 设备注册表 (DeviceEntity) =====
CREATE TABLE IF NOT EXISTS devices (
    sysid INTEGER PRIMARY KEY,
    online BOOLEAN,
    last_heartbeat_ms BIGINT,
    first_seen TIMESTAMP WITH TIME ZONE,
    last_seen TIMESTAMP WITH TIME ZONE,
    name VARCHAR(255),
    device_token VARCHAR(255)
);

-- ===== 报警事件表 (AlarmEvent) =====
CREATE TABLE IF NOT EXISTS alarm_event (
    id VARCHAR(255) PRIMARY KEY,
    acknowledged BOOLEAN NOT NULL,
    alt DOUBLE PRECISION NOT NULL,
    description VARCHAR(255),
    event_type VARCHAR(255),
    lat DOUBLE PRECISION NOT NULL,
    lon DOUBLE PRECISION NOT NULL,
    severity VARCHAR(255),
    source_device_id VARCHAR(255),
    source_device_name VARCHAR(255),
    timestamp_ms BIGINT NOT NULL
);

-- ===== 配送任务表 (DeliveryTask2 + Payload @Embedded) =====
CREATE TABLE IF NOT EXISTS delivery_task2 (
    id VARCHAR(255) PRIMARY KEY,
    actual_delivery_time TIMESTAMP,
    assigned_sysid INTEGER,
    estimated_delivery_time TIMESTAMP,
    description VARCHAR(255),
    fragile BOOLEAN,
    payload_id VARCHAR(255),
    temperature_range VARCHAR(255),
    payload_type VARCHAR(255),
    volumem3 DOUBLE PRECISION,
    weight_kg DOUBLE PRECISION,
    priority VARCHAR(255),
    receiver_lat DOUBLE PRECISION NOT NULL,
    receiver_lon DOUBLE PRECISION NOT NULL,
    receiver_name VARCHAR(255),
    route_distance_km DOUBLE PRECISION NOT NULL,
    sender_lat DOUBLE PRECISION NOT NULL,
    sender_lon DOUBLE PRECISION NOT NULL,
    start_time TIMESTAMP,
    status VARCHAR(255),
    type VARCHAR(255)
);

-- ===== 测绘任务表 (MappingTask) =====
CREATE TABLE IF NOT EXISTS mapping_task (
    id VARCHAR(255) PRIMARY KEY,
    altitudem DOUBLE PRECISION NOT NULL,
    area VARCHAR(255),
    assigned_sysid INTEGER,
    camera_angle_deg DOUBLE PRECISION NOT NULL,
    end_time TIMESTAMP,
    gsd_cm DOUBLE PRECISION NOT NULL,
    name VARCHAR(255),
    overlap_pct DOUBLE PRECISION NOT NULL,
    photos_captured INTEGER NOT NULL,
    progress_pct DOUBLE PRECISION NOT NULL,
    sidelap_pct DOUBLE PRECISION NOT NULL,
    start_time TIMESTAMP,
    status VARCHAR(255),
    type VARCHAR(255)
);

-- ===== 表演任务表 (ShowTask) =====
CREATE TABLE IF NOT EXISTS show_task (
    id VARCHAR(255) PRIMARY KEY,
    altitudem DOUBLE PRECISION NOT NULL,
    center_lat DOUBLE PRECISION NOT NULL,
    center_lon DOUBLE PRECISION NOT NULL,
    duration_sec INTEGER NOT NULL,
    formation_id VARCHAR(255),
    name VARCHAR(255),
    start_time TIMESTAMP,
    status VARCHAR(255)
);

-- ===== 表演任务关联无人机表 (ShowTask @ElementCollection) =====
CREATE TABLE IF NOT EXISTS show_task_drones (
    task_id VARCHAR(255) NOT NULL,
    sysid INTEGER
);

-- ===== 编队表 (FormationEntity) =====
CREATE TABLE IF NOT EXISTS formation (
    formation_id INTEGER PRIMARY KEY,
    heading DOUBLE PRECISION NOT NULL,
    leader_sysid INTEGER NOT NULL,
    ref_alt DOUBLE PRECISION NOT NULL,
    ref_lat DOUBLE PRECISION NOT NULL,
    ref_lon DOUBLE PRECISION NOT NULL,
    shape VARCHAR(255),
    spacing DOUBLE PRECISION NOT NULL,
    state VARCHAR(255)
);

-- ===== 编队成员表 (FormationEntity @ElementCollection) =====
CREATE TABLE IF NOT EXISTS formation_members (
    formation_id INTEGER NOT NULL,
    member_sysid INTEGER
);