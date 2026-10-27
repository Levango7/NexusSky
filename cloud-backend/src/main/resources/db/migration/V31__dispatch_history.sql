-- V31__dispatch_history.sql: 出警历史落库（审计 §2.1「必须落库」第 4 条）
--
-- 背景：AutoDispatchService 的出警历史是内存 ConcurrentLinkedDeque（上限 500）——
-- 重启即失，`GET /autodispatch/history` 归零，出警不可回溯。本表把历史持久化，
-- **容量口径与内存时期完全一致（最新 500 条）**：重启后加载最新 500 回内存队列，
-- 写入时同步落库并对库做同口径裁剪——不改"上限 500"这个既有产品语义。
--
-- 设计口径：
-- 1) dispatch_id 是 UUID 字符串（主键）；trigger_time 为 epoch 毫秒（与内存队列
--    排序同口径，不做时区换算——它是绝对时刻）。
-- 2) dispatched_drones 存 JSON 文本（数组 [{"sysid":..,"taskAssigned":..,
--    "estimatedArrivalSec":..}]）：这是**事件快照**不是可查询实体，拆子表换不来查询价值；
--    反序列化失败时按空列表降级并留痕（见实体注释），不炸历史读取。
-- 3) id 排序对 trigger_time 并列做确定性收尾（次级键 dispatch_id）。

CREATE TABLE dispatch_history (
    dispatch_id       VARCHAR(64) PRIMARY KEY,
    alarm_id          VARCHAR(128),
    trigger_time      BIGINT NOT NULL,
    lat               DOUBLE PRECISION NOT NULL,
    lon               DOUBLE PRECISION NOT NULL,
    status            VARCHAR(32),
    dispatched_drones VARCHAR(4096),
    created_at        TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_dispatch_history_trigger_time ON dispatch_history (trigger_time);
