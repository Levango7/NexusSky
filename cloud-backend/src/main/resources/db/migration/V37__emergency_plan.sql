-- V37__emergency_plan.sql: 应急编排计划落库（P1#8 裁决：新表独立落库，2026-10-27 用户拍板）
--
-- 背景：EmergencyOrchService.plans 是内存 Map（含 phases/events/priorityQueues 嵌套）——
-- 重启后 planId 失效、编排进度与事件列表不可查（审计 §2.1）。
--
-- 裁决：**新表独立落库**——orch 域的 OrchestrationPlanEntity（带 steps/triggers 的重实体）
-- 与本服务的轻量场景描述不是同一种东西，不强塞。
--
-- 存储：计划 map 整体以 JSON 快照落库（它是**进度文档**不是可聚合实体；Jackson 对
-- Map<String,Object> 双向无损，无不可变类构造陷阱）。plan_id 由 V35 的 sequence_counter
-- 续号（跨重启不复用）——两批衔接，ID 语义完整。
-- status/tenant_id 提列供排查时直接 SQL 看，不依赖解析 JSON。

CREATE TABLE emergency_plan (
    plan_id    BIGINT PRIMARY KEY,
    tenant_id  INTEGER,
    status     VARCHAR(32),
    snapshot   TEXT,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_emergency_plan_tenant ON emergency_plan (tenant_id);
