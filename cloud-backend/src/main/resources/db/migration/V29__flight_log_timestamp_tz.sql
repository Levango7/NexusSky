-- V29__flight_log_timestamp_tz.sql: 把 flight_log 的时间戳从"无时区"改成"绝对时刻"
--
-- 为什么要改（缺陷证据见 .coord/GAPS.md §七 G-1，本机实测）：
--   列原本是 `timestamp without time zone`，而写它的显式批量插入用 `Types.TIMESTAMP` 绑定
--   `Instant`（`FlightLogEntity.insertSqlTypes()`）——pgjdbc 按 **JVM 默认时区**把瞬时值摊成
--   墙钟写进去。于是 +08 机器上"UTC 11:04 的那一行"存成了 `19:04`。
--   读侧的日窗却按另一个口径算，结果：**同一天里"今天"这一页读到 0 行，`day=明天` 才读得到**。
--   CI 的 runner 是 UTC，写读两边恰好重合 ⇒ 这个缺陷在 CI 上结构性不可见（Pass C 断言 7 恒绿）。
--   对照证据：同库里 `audit_log`（走 JPA `Instant`）存的是 UTC，两张表两套口径。
--
-- 历史数据按什么时区解释：必须**显式声明**，不能猜。
--   `nexussky.legacy_naive_tz`（会话/库级 GUC）没设置时按 **UTC** 解释——
--   这与 CI/多数部署的现状一致；若某套环境的既有行是在非 UTC 机器上写的，
--   **先 `ALTER DATABASE ... SET nexussky.legacy_naive_tz = 'Asia/Shanghai'` 再跑迁移**，
--   否则历史行会整体偏移。项目当前处于 PoC 阶段（既有行都是演示/测试数据），
--   默认取 UTC 是为了"迁移可重放、不依赖机器状态"，而不是因为 UTC 一定是那些行的真身世。
--
-- 代价与边界：
--   * `ALTER TABLE ... TYPE` 会重写整表并短暂持锁。遥测表可能很大——
--     生产上线前应在维护窗执行，或按 `flight_log` 的行数量级先估时。
--   * `idx_flight_log_timestamp` 随列类型一起重建，无需额外语句。
--   * 本迁移只动 flight_log。`audit_log`/webhook/spray 等 naive 列是另一批事实
--     （它们存的就是 UTC），已单独登记，不在这里顺手改。

ALTER TABLE flight_log
    ALTER COLUMN timestamp TYPE TIMESTAMPTZ
    USING timestamp AT TIME ZONE COALESCE(current_setting('nexussky.legacy_naive_tz', true), 'UTC');
