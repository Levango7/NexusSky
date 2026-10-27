-- V33__coordination_records.sql: 空地协同记录与指挥报告归档（审计 §2.1「必须落库」第 6 条）
--
-- 背景：AirGroundCoordinationService.coordinationRecords 是内存 Map；总结完成后
-- 内存记录被清理（防 OOM），**但归档没有任何去处**——`GET /air-ground/report/{id}`
-- 在总结后/重启后一律 404（审计："事件时间线无处归档"）。
--
-- 语义裁决（本迁移与接线一起定）：**内存清理 ≠ 归档删除**。
--   * 内存仍然有界（总结后清理 + 超 1000 条批量清），行为不变；
--   * 库行是归档：总结后仍可从 DB 读回指挥报告与时间线（读穿路径）。
-- 量级：每起警情一条，低频；不设裁剪（留存策略属产品口径，见 real-integration-program.md 待定项 9）。
--
-- 设计口径：
-- 1) 顶层字段落列（可查询）；四个复合对象（assessment/deployment/evaluation/report）
--    以 JSON 文本存快照——它们是**报告文档**不是可聚合实体，拆表换不来查询价值。
-- 2) 复合对象的 (反)序列化不走"Jackson 直接照反射读写"：CoordinationReport 等是不可变
--    类（只有 getter/全参构造），直接反序列化会踩无默认构造的坑——实体里用显式 Map/DTO
--    逐字段映射（序列化与反序列化同一个映射表，改字段时两边一起看见）。

CREATE TABLE coordination_records (
    coordination_id     VARCHAR(64) PRIMARY KEY,
    alarm_event_id      VARCHAR(128),
    cmd_id              VARCHAR(64),
    phase               VARCHAR(32) NOT NULL,
    plan_id             BIGINT,
    start_time_ms       BIGINT NOT NULL,
    assess_time_ms      BIGINT NOT NULL DEFAULT 0,
    deploy_time_ms      BIGINT NOT NULL DEFAULT 0,
    execute_time_ms     BIGINT NOT NULL DEFAULT 0,
    assessment_result   VARCHAR(2048),
    deployment_info     TEXT,
    evaluation          TEXT,
    report              TEXT,
    updated_at          TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_coordination_records_alarm ON coordination_records (alarm_event_id);
CREATE INDEX idx_coordination_records_start ON coordination_records (start_time_ms);
