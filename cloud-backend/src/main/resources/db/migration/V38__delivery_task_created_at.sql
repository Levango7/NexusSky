-- V38__delivery_task_created_at.sql: delivery_task2 补创建时刻（留存策略前置）
--
-- 背景：delivery_task2 此前只有 start_time（/tasks/{id}/start 时写入）——PENDING 与
-- 「未启动即中止」的行没有任何时间基准，留存任务无从按龄裁剪，而它们恰是容易长期
-- 滞留的存量。只增表留存批要求每张可裁剪的表都有一个可信的「出生时间」。
--
-- 设计口径：
-- 1) 由应用写入（@PrePersist），不给 DB 默认值——与 V28/V30 口径一致：
--    缺失就是缺失，不造「看起来正确」的时间。
-- 2) 存量行为 NULL（无从追溯真实创建时刻，不伪造）；留存条件用
--    coalesce(created_at, start_time)——存量已启动行按 start_time 照常可裁，
--    存量未启动行不裁（无时间基准），新行一律有 created_at。
-- 3) TIMESTAMP WITH TIME ZONE：与 V30 created_at 同型（H2/PG 双兼容，Instant 直存）。

ALTER TABLE delivery_task2 ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
