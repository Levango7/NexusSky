# 从模拟到真实：剩余路径立项书（2026-10-27）

> **定位**：把散落在 `docs/todo.md`、两份审计（`docs/persistence-and-dead-surface-audit.md`、
> `docs/decisions-2026-10-26.md`）与各处"属生产阶段"声明里的**剩余项**，收成一个有优先级、
> 有前置条件、有验收标准的 program。规矩与本仓一致：**每条必须有证据锚点与验收标准**，
> 拿不准的口径不猜——列进「待口径」等产品/外部决定。
>
> 当日背景：2026-10-26/27 已合并 13 条 PR（净收口），`GAPS.md` 开项清零、四决策落账
> （C5 prod 保持关闭 / GB46750-UOM 立项 / --rtk 上界 / G-1 销账）。本文件是该轮之后的
> 续接：**剩下的活按"能做的 / 等的"分栏，不再颗粒化堆在 todo 里**。

---

## P1 · 持久化（能做的——审计 §2.1「必须落库」9 条）

来源：`docs/persistence-and-dead-surface-audit.md` §2.1（每条带 `file:line` 与"重启丢什么"）。

| # | 模块 | 状态 |
|---|---|---|
| 1 | 空域预约 `ConflictAvoidanceService` | ✅ **2026-10-27 启用**（staging/prod profile 打开 `reservation.persist`；持久化路径与往返测试早已建成，此前五个 profile 无一打开——审计 §5 Q1 定案「未完成的上半截」） |
| 2 | 维护记录 `MaintenanceController` | ✅ **2026-10-27 落库**（V30 `maintenance_records` + 实体/仓库 + id 序列从库续起防覆盖） |
| 3 | 出警历史 `AutoDispatchService` | ✅ **2026-10-27 落库**（V31 `dispatch_history` + 启动恢复最新 500 + 同口径裁剪；容量语义不变） |
| 4 | 测绘航线航点 `MappingController` | ✅ **2026-10-27 落库**（V34 `mapping_task.waypoints` + MappingWaypointCodec 显式映射 + 读穿；**契约裁决：任务存在⇒200+真实航点 / 从未规划⇒空数组 / 任务不存在⇒404**，三态唯一） |
| 5 | 配送六阶段状态 `DeliveryStatusTracker` | ⬜ 待做，**先解词汇冲突**：库内 `delivery2` 是 `PENDING/IN_PROGRESS`，tracker 是另一套六阶段词表——两套词汇并存时直接落库会造出更难拆的债 |
| 6 | 空地协同记录 `AirGroundCoordinationService` | ✅ **2026-10-27 归档落库**（V33 + 写穿 ×2 [启动完成/总结前] + **读穿修掉总结后/重启后 GET report 404**；语义裁决：内存清理 ≠ 归档删除） |
| 7 | 合规状态 `ComplianceStateManager` | ✅ **2026-10-27 落库**（V32 + 启动水合 + 写穿；**顺带修掉三个"声明了却从未写入"的 last* 时间戳**——RegulatorController 上报的永远是 0 的修复） |
| 8 | 应急编排 `EmergencyOrchService` | ⬜ 待做；`orch/entity/OrchestrationPlanEntity` 存在但属另一域——先定"复用还是新表" |
| 9 | 审计哈希链 dev 档 | 🔶 配置口径（prod 已 true；staging 缺覆盖行——审计 §5 Q2 待口径） |

**验收标准（全批）**：新增迁移在 H2/Postgres 双跑；每条落库配"重启恢复"测试（本轮三条约 13 例为模板：
写穿断言 + 启动恢复顺序 + 异常降级不炸主链 + 无仓库纯内存语义不变）；计数门禁同步。

## P2 · SITL 首次真实验证（能做的——但需一个 Linux 环境）

来源：`docs/sitl-integration.md`（环境搭建/对接配置/验证清单已成文）、GAPS §九（"PX4 SITL 脚本写好但从未运行"）。

- **目标**：后端**零改动**接上真实飞控固件（ArduPilot SITL 优先——有预编译二进制，比 PX4 全工具链轻），
  走通"连接 → 任务上传 → 起飞 → 航点 → RTL"最小闭环。
- **前置**：一台 Linux/WSL2（本机 Windows 直跑不了）；ArduPilot `sim_vehicle.py` 或 PX4 SITL；
  与 `docs/sitl-integration.md` §4 的对接配置核对（端口 14550/14540、udp/tcp 差异——注意
  `tcp://` 目前实为 UDP 顶替，接 SITL 前先把这个谎修了或明确降级）。
- **验收**：`docs/sitl-integration.md` §5 兼容性验证清单逐项打勾；产出一份"真实固件 vs drone-sim
  行为差异"记录（哪怕结论是"没有差异"也要写）。
- **状态**：立项（等环境）。一旦有 Linux 盒子/愿意在 WSL2 上装 SITL 工具链，这是**全仓最高价值的验证**。

## P3 · UOM 真实联调（等的——外部依赖）

来源：`docs/decisions-2026-10-26.md` 决策二（就绪清单五项已登记）。本项目 track 的是**仓内可先行件**：

- ⬜ **差异比对框架**（先行件，不等外部）：以公开的 UOM 接口规范为输入，把 `regulator-sim`
  六端点的请求/响应逐字段建对照表（列：字段 / sim 形状 / 规范形状 / 差异 / 风险），
  产出 `docs/uom-contract-diff.md` 骨架 + `scripts/` 下可复跑的比对器（对捕获的真实响应回放）。
- ⬜ `UomReportSink` 真实现骨架 + 契约测试（SPI 门禁会把"未验"账顶出来）——**需要先有规范细节**，
  骨架可先按 sim 形状写接口合同，字段以规范为准逐一对齐。
- ⏸ 等待项：#1 确定对接主体（**用户侧行动项**）→ 拿到测试环境 → 真实联调 → C1/C2 边界改口径。

## P4 · ONVIF PullPoint 事件订阅（能做的）

来源：`docs/todo.md` #14 备注（#178 协议级 live 客户端落地时"事件面 live 下不假装（REST 先回 501）……
ONVIF 事件订阅（PullPoint）另立"）。

- **目标**：`OnvifLiveBackend` 补 PullPoint 订阅（CreatePullPointSubscription + PullMessages 循环），
  把厂商事件（移动侦测等）接入既有告警总线。
- **验收**：simulated 模式行为不变（缺省路径）；live 模式下 REST 从 501 变为真实事件流；
  与 `SurveillanceController` 现有事件形状对齐（不得再造一套词汇）。
- **状态**：立项（可做，需要 fictional ONVIF server 测试桩——#178 的测试已建了 SOAP 桩，可扩展）。

## P5 · 厂商私有 SDK 与 GB28181 媒体面（等的——外部）

- 海康/大华/宇视私有 SDK：需厂商授权 + 真设备（GAPS §三 已登记前置）。
- GB28181 媒体面（SIP 传输 + RTP/PS）：形状层已交付；媒体面属生产阶段。
- 机巢 MQTT 真实 transport：硬件到位再动（todo #17）。

---

## 待口径（产品/外部决定；扫描器与 agent 不替产品决定——审计 §5 原样转录）

1. 空域预约开关缺 profile 覆盖——**已于 2026-10-27 定案为"未完成的上半截"并启用**（staging/prod）。
2. `audit.persist-to-db` 在 staging 缺覆盖行：预发该不该跑落库通路？（不打开 ⇒ prod 落库路径从未被非生产验证过）
3. UOM 上报计数 + `sink-type` 默认 Noop：对外话术里"上报 UOM"要不要继续写？（与 P3 联动）
4. 机巢事务号 `tidSeq` 是否要求跨重启单调？
5. `planIdSeq`：若 planId 进过客户侧报表，重启 ID 复用比"丢数据"更严重——是否需要持久化序列？
6. `FormationKeeper.correctionCount` 是否算考核/计费依据？
7. 告警离线缓存"上限 500 + 重启丢未上传"接受为产品语义，还是做非易失暂存？
8. `/air-ground/situation` 键名以哪侧为准？（#177 已把三列落实，键名口径如仍有分歧在此登记）
9. 出警历史/维护记录等新表的**留存策略**：本轮与内存时期同口径（最新 500 / 全量），
   长期留存与归档策略待定。
10. `delivery2` 与 `DeliveryStatusTracker` 两套状态词汇的归一方向（P1 #5 前置）。

## 与现有文档的关系

- 缺口登记（曾用）：`.coord/GAPS.md`（已清零，新缺口开新段）。
- 证据与定级：`docs/persistence-and-dead-surface-audit.md`（本文件 P1 的唯一权威来源）。
- 决策记录：`docs/decisions-2026-10-26.md`（C5/GB46750/--rtk/G-1）。
- 部署/合规操作面：`docs/signing-prod-rollout.md`、`docs/sitl-integration.md`。
