-- V25__devices_last_flight_state.sql: devices 补三个"最后已知飞行态"列
--
-- 背景：重启后 DeviceRegistry.restoreFromRepository() 只还原 sysid 与 tenantId，
-- GCS 首屏拿不到"这架机最后在做什么"。位置/电量已由 drone_last_known_position
-- （V5，经 FlightTrackStore 按节流落库 + @PostConstruct 加载）恢复，
-- 但 mode / armed / protocol 没有任何持久化出路。
--
-- 1) last_mode / last_armed / last_protocol：与 last_seen 同批写（心跳路径本来就在
--    写 devices 行），不新增写放大；只补"低频、有决策价值"的三个标量。
-- 2) 全部可空：历史行没有这三列，NULL = 无记录。刻意不用 NOT NULL + 默认值——
--    那会把"没有记录"伪装成"记录为 mavlink/未锁定"，两者语义不同。
-- 3) 列名与 Hibernate SpringPhysicalNamingStrategy 生成结果一致（camelCase -> snake_case）。
-- 4) 不建索引：这三列只按 sysid 主键单行读，没有查询维度。

ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_mode VARCHAR(64);

ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_armed BOOLEAN;

ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_protocol VARCHAR(64);
