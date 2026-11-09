-- V39__dock_inspection_link.sql: 机巢无人值守任务 ↔ 巡检任务关联（黄金路径硬化第一批）
--
-- 背景：机巢定时任务（dock_schedules）按 cron 直接上传 waypoints 飞一趟并写 run log；
-- 巡检模块（inspection）是另一套内存态任务，两者不相干——一次自动巡检飞完，
-- 没有巡检任务、没有报告、也没有任何记录把「这次飞行」和「哪次巡检」对上。
--
-- 设计口径：
-- 1) inspection_template_id 为空 ⇒ 保持旧行为（只飞 waypoints，不产巡检任务）。
-- 2) inspection_task_id 写进 run log ⇒ 飞行结果（OK/FAILED/timeout）能反查到巡检任务；
--    巡检任务本身仍由 inspection 域持有（后续批次再落库，本批不搬存储）。
-- 3) 两列都可空：存量行无关联，不伪造一个 taskId。

ALTER TABLE dock_schedules ADD COLUMN inspection_template_id VARCHAR(64);
ALTER TABLE dock_run_log ADD COLUMN inspection_task_id VARCHAR(64);
CREATE INDEX idx_dock_run_log_inspection_task ON dock_run_log (inspection_task_id);
