-- V34__mapping_task_waypoints.sql: 测绘航线航点落库（审计 §2.1「必须落库」第 4 条）
--
-- 背景：MappingController 规划的航点只存内存 Map（waypointsByTask）；任务行在库、
-- 航点在内存 ⇒ 重启后 GET /tasks/{id}/waypoints 返回 200+空数组，而 api-reference
-- 把 404 定义为"任务不存在"——客户端**分不清"航线丢了"与"本来就没有航线"**。
--
-- 契约裁决（本迁移与接线一起定）：任务存在时航点从库读穿返回真实内容；
-- 只有任务本身不存在才 404；任务存在但从未规划（列 NULL）返回空数组——
-- 三种状态各自有唯一语义，不再重叠。
--
-- 存储：整条航线一个 JSON 数组（航点是**有序路线文档**，不是可聚合实体；
-- 拆表换不来查询价值，还要维护顺序列）。序列化走显式字段映射（MappingWaypointCodec，
-- 不可变类不交给 Jackson 反射反序列化——同 V33 的取舍）。

ALTER TABLE mapping_task ADD COLUMN waypoints TEXT;
