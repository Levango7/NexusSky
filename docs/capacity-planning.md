# 低空经济场景容量规划与写入设计（含实测数字）

> 更新：2026-11-23 ｜ 口径：所有「实测」均在本机 PG 15.19 + Docker 容器 `opsmesh-p0a3-pg` 上跑出，
> 造数器 `scripts/synthesize-show-traffic.py`；「估算」按实测值线性外推并明确标注。
> 不把合成数据当生产 RTO。

## 1. 实测环境与口径

- 表结构与生产同构（`flight_log`，含 V42 新增 `task_id/formation_id/seq` 及 3 个索引）。
- 轨迹模型：每机 `10 Hz × 900 s = 9000` 行，24 组 × 50 架 = 1200 架，共 **10,800,000 行**。
- 写入方式：`COPY ... FROM STDIN` 流式灌入（自动化程序实际应走 JDBC batch，见 §5）。

## 2. 场景盘点（每类 ≥5，含分组与通讯）

| 场景 | ≥5 个子场景 | 典型机数 | 分组方式 | 通讯要点 |
|---|---|---|---|---|
| 无人机表演 | 灯光编队、图案变换、字幕墙、尾迹秀、焰火配合 | 200 / 400 / 600 / 1200 | 编队单元（每 50 架一组，`formation_id`） | 轨迹预生成 + 时间触发；地面站只收每组 1 路汇聚状态；PTP/GPS 授时 |
| 应急救援 | 地震、泥石流、火灾、洪涝、危化品 | 8–15（仓库 3 种预设） | 主控 1–2 / 通讯 2–4 / 中继 3–8 / 执行 5–15 | 指令小包高优先级 ACK <300ms；mesh ≤3 跳；断链 store-and-forward |
| 物流（交通不便） | 血液医药、快递、外卖、山区补给、危化样件 | 5–20 | 按航线/枢纽 | BVLOS，4G/5G + 卫星兜底 |
| 物流（高楼阳台） | 分层航路、垂直走廊预约、RTK 落点、末端投放、楼层高度带 | 5–30 | 按楼宇群/楼层 | `airspace_reservations(alt,start_ms,end_ms,radius_m)` 预约时空窗 |
| 农业 | 正射遥感、倾斜摄影、多光谱长势、病虫害识别、播种、撒药、撒肥 | 5–30 | 按地块/作业区 | 遥测低频率；`waypoints`/影像是大字段 |
| 电力/管线巡检 | 输电、配电、光伏、风电、油气管线 | 5–20 | 按线路段 | 边缘识别 + 回传摘要 |
| 安防监控 | 园区、边界、大型活动、交通、景区 | 5–30 | 按防区/巡逻路线 | GB28181 视频 + 告警帧 |

## 3. 数据量：实测 + 外推

| 规模 | 行数/场 | 灌入耗时 | 实测吞吐 | 含索引表体积 |
|---|---|---|---|---|
| 200 架 | 1,800,000 | 10.83 s | 166,205 行/s | 377 MB |
| 1200 架（干净表） | 10,800,000 | 78.10 s | 138,284 行/s | **2250 MB**（heap 1623 + 索引 627） |
| 1200 架（DELETE 后带膨胀的表） | 10,800,000 | 187 s | 57,754 行/s | 2250 MB |

**外推（按 1200 架一场 2.25 GB 线性）**：400 架 ≈ 0.75 GB/场，600 架 ≈ 1.13 GB/场；
每天 2 场 × 30 天 ≈ **135 GB/月**。这是含索引的实测体积，比此前纸面估算（6.5 GB/场）低约 3 倍——
以实测为准。

**查询实测**（`ANALYZE` 后，10.8 M 行）：按 `(tenant_id, task_id)` 计数 1.07 s；
按 `formation_id` 聚合 1.33 s；每机最后一条 1.11 s。`ANALYZE` 前同一聚合曾耗时 8.2 s——
**大批量灌入后必须 ANALYZE**，否则统计信息陈旧导致全表扫描。

## 4. 分库分表设计

1. **先分区，再分片**：`flight_log` 按 `timestamp` RANGE 月分区（蓝图见
   `deploy/postgres/flight_log_partitioning.sql`）。保留期到点 `DROP TABLE` 分区：
   实测 **0.176 s**；对非分区表 `DELETE` 同量 1 个月数据 **11.9 s**，且留下膨胀。
2. **分区裁剪实测生效**：`EXPLAIN` 查询 12 月窗口只扫 `flight_log_p_2026_12`，不碰 11 月分区。
3. **分片键按场景选**：表演 `(tenant_id, task_id, timestamp)`；物流 `(tenant_id, 航线, time)`；
   农业 `(tenant_id, plot_id, time)`；字典表（devices/docks/license/formation）每库全量复制。
4. **分库**：按 `tenant_id` 做故障域隔离；业务库 / 日志库 / 统计库物理分开。
5. **ID**：`FlightLogEntity.id` 用 IDENTITY；PG 15.19 实测在分区表上可用（含 attach partition）。
6. **去重**：V42 唯一索引 `(tenant_id, sysid, seq)` 实测拦住了重复灌入
   （`duplicate key ... (1,1,0) already exists`）——即 at-least-once + 去重可用。
   SQL 标准下 NULL 互不相等，历史行 `seq IS NULL` 不受约束，所以不需要部分索引；
   H2 2.2.224 不支持 `WHERE ...` 部分索引，写成部分索引会让 Flyway 启动失败（CI 实测踩过）。

## 5. 自动化实时写入链路

```
UDP/TCP 接收 → 解析/校验/签名 → 有界内存队列(RingBuffer)
  → 按 sysid hash 分片的多线程批写器 → PostgreSQL batch
  → 队列满/DB 故障 → 本地 JSONL/WAL 兜底 → 恢复后按 seq 幂等补写
```

仓库已具备：`BatchedWriteQueue`（队列 20000、批 50、flush 200 ms；队列满写 JSONL）、
接收线程外写库、`scripts/capacity-baseline.sh --drones N --hz H --clients C --duration D` 压测入口。

必须处理的 10 个问题：
1. 批提交（50–500 行/批，100–200 ms flush，JDBC batch + 预编译 + 关 autocommit）；
2. 有界队列 + 背压，满时不阻塞收包线程，落盘兜底并告警；
3. 同 `sysid` 单线程保序，不同 `sysid` 并行；
4. `(sysid, seq, timestamp_ms)` 去重（V42 索引）；
5. 索引克制——写入密集表索引越少越好（本次 3 个索引已占 627 MB / 28%）；
6. 服务端时间做分区键，客户端时间仅业务字段；NTP/PTP 对齐；
7. 指数退避 + 抖动重试、熔断、死信队列；
8. Micrometer 指标：入库速率、批大小、队列深度、丢弃数、补写延迟；不要逐行打日志；
9. 批量导入后 `ANALYZE`，否则查询退化（实测 8.2 s → 1.33 s）；
10. 合规留存：按租户配置归档，审计表只增不改。

## 6. 备份恢复容量（2.25 GB 合成库实测）

| 操作 | 实测 | 产物 |
|---|---|---|
| `pg_dump -Fc` | 46.2 s | 355 MB（1.1 GB 重复 md5 场景为 25.3 MB，压缩率取决于数据） |
| `pg_restore` | 83.3 s | 10,800,000 行 / 2253 MB，行数与体积一致 |

资源建议：预留 **2–3× 库大小** 的磁盘（dump + 恢复副本 + WAL/膨胀）；
内存够 PG `shared_buffers`/`maintenance_work_mem` 工作集即可。
Docker 默认 `/dev/shm` 64 MB 会让并行 VACUUM 失败
（`could not resize shared memory segment ... No space left on device`）——生产容器需加大 `shm_size`。
本数字是合成库机制值，**不是生产 RTO**；给生产库规模与日增量后可等体量复测。

## 7. 写入侧闭环与已知缺口（诚实标注）

**已闭环（V42 + 后续改造）**：`TaskAssignmentService` 分配/取消/完成/失败时把
`sysid → taskId` 登记到 `FlightLogTaskContext`，`FlightLogService` 组装每条事件时写入
`task_id` 和 `seq`；`seq` 用 `max(上次+1, 当前毫秒)` 保证进程重启后不撞 V42 唯一索引。
无上下文时（单测/未接调度）行为与改造前完全一致，不新增键。

**V43 新增三张场景表**（只建表与索引，尚无 REST API/UI，属于数据模型就位）：

| 表 | 用途 | 关键列 |
|---|---|---|
| `balcony_drop_point` | 高楼阳台落点注册 | `building_id/floor/lat/lon/alt_m/max_payload_kg/allowed_from_ms/allowed_to_ms` |
| `agri_supply_ledger` | 农资领用/使用/回收/损耗流水 | `supply_type/action/quantity/balance_after/task_id/sysid` |
| `emergency_node_role` | 应急四类节点角色登记 | `role(CONTROL/COMMS/RELAY/EXECUTOR)/link_budget_ms/plan_id/sysid` |

**仍未做**：

- `formation_id` 目前仍为 NULL：表演轨迹的编队归因需要 `FormationService` 暴露
  「sysid → formationId」 membership 查询后再接入；现有种子数据不回填。
- 三张新表没有对应的服务层/REST API，当前仅可被直接 SQL 或后续代码使用。
- 分区表蓝图未自动套用到生产表（需维护窗口），仓库门禁也会拦截含 DROP/RENAME 的自动迁移。
- 生产 RTO 仍缺真实库规模输入；合成数据不能当生产数字。
