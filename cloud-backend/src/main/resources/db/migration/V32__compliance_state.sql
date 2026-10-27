-- V32__compliance_state.sql: 合规状态落库（审计 §2.1「必须落库」第 7 条，本批最高优先）
--
-- 背景：ComplianceStateManager 是纯内存态（类注释自陈"C1 阶段以内存态管理，
-- C4 持久化后切换为 Repository 实现"——切换一直没做）。重启后果最重：
-- **已实名/已激活的设备回到 UNVERIFIED**，监管上报丢身份关联（丢的是合规叙事本身）。
--
-- 设计口径：
-- 1) sysid 主键（一机一行）；状态机只允许正向流转，所以行只会被更新/删除，
--    不需要历史表——但 updated_at 记最后变更时刻（审计追溯）。
-- 2) product_serial_no / activation_id / cancellation_id 全部可空且**不给默认**：
--    NULL = 无记录，与 V28 同款教训（假默认会把"未知"伪装成"已知"）。
-- 3) 三个 last* 时间戳列可空（0 在域对象里=从未发生；落库时保留 0 也行，
--    这里允许 NULL 并在实体转换层把 NULL 归一为 0——单一的"从未发生"表示）。
-- 4) 管理员注销（remove）是真删除：CANCELLED 状态本身留在行里，remove 是"设备离线
--    清理"语义；两者不要混（remove 会删行，CANCELLED 只是 status 值）。

CREATE TABLE compliance_state (
    sysid                      INTEGER PRIMARY KEY,
    product_serial_no          VARCHAR(64),
    status                     VARCHAR(32) NOT NULL,
    activation_id              VARCHAR(64),
    cancellation_id            VARCHAR(64),
    last_verify_time           BIGINT,
    last_activation_time       BIGINT,
    last_telemetry_report_time BIGINT,
    updated_at                 TIMESTAMP WITH TIME ZONE
);
