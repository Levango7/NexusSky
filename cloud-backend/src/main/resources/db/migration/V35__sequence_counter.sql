-- V35__sequence_counter.sql: 单调序列计数器（§5 Q4/Q5 裁决：持久化续号，2026-10-27 拍板）
--
-- 用途：机巢事务号（dock_tid，SimLoopbackGateway）与编排计划号（orch_plan_id，
-- EmergencyOrchService）在重启后会从头复用——监管对账/客户报表场景下"ID 复用
-- 比丢数据更严重"。本表存每个命名序列的"最后发出值"，重启从 max(base, stored)
-- 续起。两个序列都低频（每命令/每编排一次写），单行 upsert 的量级完全可接受。
--
-- 设计口径：name 主键（序列名即身份）；value = 最后发出的值（不是下一个——
-- "已发出"才是必须不重来的事实，下一发在内存里 +1 即可）。

CREATE TABLE sequence_counter (
    name       VARCHAR(64) PRIMARY KEY,
    -- 列名不用 ``value``：H2 2.x 把它当保留字，迁移在 ``value BIGINT`` 处直接
    -- 语法错误（CI run 4047 系列 e2e 全红抓到的）。last_value = 最后发出值。
    last_value BIGINT NOT NULL
);
