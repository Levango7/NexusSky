-- V40__inspection_task_report.sql: 巡检任务与报告落库（黄金路径硬化第二批）
--
-- 背景：InspectionTaskService 的任务/报告/航点/照片/异常全是内存 ConcurrentHashMap——
-- 重启即失。机巢无人值守任务（V39 已把 inspection_task_id 写进 dock_run_log）飞行结束后
-- 产生的巡检任务与报告随之蒸发，黄金路径「自动飞行产出可追溯结果」在重启后断链。
--
-- 设计口径：
-- 1) inspection_tasks 存任务生命周期（状态/起止/进度/照片数/异常数）与完整航线
--    （waypoints JSON 数组，每航点 8 字段 [seq,lat,lon,alt,heading,speed,action,hold]，
--    供重启后 hydrate 复原——UnattendedScheduler 只读 lat/lon/alt，但航向/速度/
--    停留是航线的一部分，丢了就是静默降级）。
-- 2) inspection_reports 存报告全字段（by_severity 与 recommendations 存 JSON 文本，
--    是快照不是可聚合实体，拆子表换不来查询价值——同 V30/V31 口径）。
-- 3) 照片/异常明细列表不落库：生产路径（completeFromRun）只传计数，明细列表只有
--    simulateCompletion（演示）会填充；计数已在任务与报告两行，留明细的诚实口径是
--    重启后 getPhotos/getAnomalies 对老任务返回空（提交说明里写明）。
-- 4) 两表都不设外键（与 dock_schedules/dock_run_log 同口径：归属校验在服务层）。
-- 5) 主键 VARCHAR(64)——UUID 由服务层分配，不改自增，避免"两套 id 词汇"。

CREATE TABLE inspection_tasks (
    task_id         VARCHAR(64) PRIMARY KEY,
    template_id     VARCHAR(64) NOT NULL,
    sysid           INTEGER NOT NULL,
    status          VARCHAR(32) NOT NULL,
    start_time      BIGINT,
    end_time        BIGINT,
    progress_pct    DOUBLE PRECISION,
    photos_captured INTEGER,
    anomalies_found INTEGER,
    waypoints       TEXT,
    created_at_ms   BIGINT NOT NULL
);

CREATE INDEX idx_inspection_tasks_template ON inspection_tasks (template_id);

CREATE TABLE inspection_reports (
    report_id           VARCHAR(64) PRIMARY KEY,
    task_id             VARCHAR(64) NOT NULL,
    template_id         VARCHAR(64),
    generated_at_ms     BIGINT NOT NULL,
    total_photos        INTEGER NOT NULL,
    total_anomalies     INTEGER NOT NULL,
    by_severity_json    TEXT NOT NULL,
    route_coverage_pct  DOUBLE PRECISION NOT NULL,
    actual_duration_min DOUBLE PRECISION NOT NULL,
    distance_flown_km   DOUBLE PRECISION NOT NULL,
    summary             TEXT NOT NULL,
    recommendations     TEXT
);

CREATE INDEX idx_inspection_reports_task ON inspection_reports (task_id);
