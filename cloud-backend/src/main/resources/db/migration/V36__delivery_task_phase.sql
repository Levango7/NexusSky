-- V36__delivery_task_phase.sql: 配送六阶段上位（P1#5 裁决，2026-10-27 用户拍板）
--
-- 背景：delivery_task2 表只有粗状态（PENDING/IN_PROGRESS/DELIVERED/CONFIRMED/FAILED/ABORTED），
-- 而 DeliveryStatusTracker 的七阶段细词表（CREATED→…→DELIVERED）只在内存里——重启后
-- GET /tasks/{id}/status 对已存在任务抛 404（审计 §2.1）。
--
-- 裁决：**六阶段上位**——本表加 phase 列存细粒度；对外粗状态由六阶段**派生**
-- （tracker 每次写阶段时同步更新 status 列，单一写入点=始终一致）；API 兼容不变。
--
-- 存量回填：只回填无歧义的映射；FAILED/ABORTED 在阶段词表里没有对应（词表无失败态），
-- **保持 NULL 不假造**——读穿路径对 NULL 行如实返回"无状态"（404 语义不被污染）。

ALTER TABLE delivery_task2 ADD COLUMN phase VARCHAR(32);
ALTER TABLE delivery_task2 ADD COLUMN current_lat DOUBLE PRECISION;
ALTER TABLE delivery_task2 ADD COLUMN current_lon DOUBLE PRECISION;
ALTER TABLE delivery_task2 ADD COLUMN remaining_distance_km DOUBLE PRECISION;
ALTER TABLE delivery_task2 ADD COLUMN estimated_arrival_min DOUBLE PRECISION;
ALTER TABLE delivery_task2 ADD COLUMN payload_condition VARCHAR(32);

UPDATE delivery_task2 SET
    phase = CASE status
        WHEN 'PENDING' THEN 'CREATED'
        WHEN 'IN_PROGRESS' THEN 'IN_TRANSIT'
        WHEN 'DELIVERED' THEN 'DELIVERED'
        WHEN 'CONFIRMED' THEN 'DELIVERED'
        ELSE NULL END,
    current_lat = 0,
    current_lon = 0,
    remaining_distance_km = 0,
    estimated_arrival_min = 0,
    payload_condition = CASE status
        WHEN 'PENDING' THEN 'NORMAL'
        WHEN 'IN_PROGRESS' THEN 'NORMAL'
        WHEN 'DELIVERED' THEN 'NORMAL'
        WHEN 'CONFIRMED' THEN 'NORMAL'
        ELSE NULL END;
