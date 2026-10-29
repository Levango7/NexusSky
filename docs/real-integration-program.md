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
> **2026-10-27：9 条「必须落库」全部落地（9/9）**。

| # | 模块 | 状态 |
|---|---|---|
| 1 | 空域预约 `ConflictAvoidanceService` | ✅ **2026-10-27 启用**（staging/prod profile 打开 `reservation.persist`；持久化路径与往返测试早已建成，此前五个 profile 无一打开——审计 §5 Q1 定案「未完成的上半截」） |
| 2 | 维护记录 `MaintenanceController` | ✅ **2026-10-27 落库**（V30 `maintenance_records` + 实体/仓库 + id 序列从库续起防覆盖） |
| 3 | 出警历史 `AutoDispatchService` | ✅ **2026-10-27 落库**（V31 `dispatch_history` + 启动恢复最新 500 + 同口径裁剪；容量语义不变） |
| 4 | 测绘航线航点 `MappingController` | ✅ **2026-10-27 落库**（V34 `mapping_task.waypoints` + MappingWaypointCodec 显式映射 + 读穿；**契约裁决：任务存在⇒200+真实航点 / 从未规划⇒空数组 / 任务不存在⇒404**，三态唯一） |
| 5 | 配送六阶段状态 `DeliveryStatusTracker` | ✅ **2026-10-27 落地**（V36）：`delivery_task2` 加 phase/位置五列；tracker 写穿时**粗状态由六阶段派生**（单一写入点=永不漂移）；重启读穿修掉"任务在库、状态 404"；存量回填只做无歧义映射（FAILED/ABORTED 保持 NULL 不假造） |
| 6 | 空地协同记录 `AirGroundCoordinationService` | ✅ **2026-10-27 归档落库**（V33 + 写穿 ×2 [启动完成/总结前] + **读穿修掉总结后/重启后 GET report 404**；语义裁决：内存清理 ≠ 归档删除） |
| 7 | 合规状态 `ComplianceStateManager` | ✅ **2026-10-27 落库**（V32 + 启动水合 + 写穿；**顺带修掉三个"声明了却从未写入"的 last* 时间戳**——RegulatorController 上报的永远是 0 的修复） |
| 8 | 应急编排 `EmergencyOrchService` | ✅ **2026-10-27 落地**（V37）：`emergency_plan` 新表 + 整 Map 快照 JSON（Jackson 对 Map 双向无损）；写穿于 start/abort/上报回调三处、读穿于 getPlan——重启后编排进度与事件列表可查；planId 由 V35 sequence_counter 续号（两批衔接） |
| 9 | 审计哈希链 dev 档 | ✅ **已闭合（2026-10-29 复核）**：prod/staging 均 `audit.persist-to-db=true`（`application-staging.properties:66`）；空域预约开关同样已开（`application-staging.properties:63`），`check-persist-switches.py` 4/4 对齐 |

**验收标准（全批）**：新增迁移在 H2/Postgres 双跑；每条落库配"重启恢复"测试（第一批三条约 13 例为模板：
写穿断言 + 启动恢复顺序 + 异常降级不炸主链 + 无仓库纯内存语义不变）；计数门禁同步。

**续接点（2026-10-27 第二批后）**：余 3 条 = #5 配送（前置：先解 `delivery2` 与 `DeliveryStatusTracker`
两套状态词汇的归一方向，属设计决策）、#8 应急编排（前置：定 `OrchestrationPlanEntity` 复用还是新表）、
#9 audit 链 staging 口径（待产品）。另两条单调序列（tidSeq / planIdSeq，§5 Q4/Q5）建议随 #5/#8 一起做——
都是"重启后 ID/序号复用比丢数据更隐蔽"的同族。迁移号已用到 V34，下一批从 **V35** 起。

## P2 · SITL 首次真实验证（能做的——但需一个 Linux 环境）

来源：`docs/sitl-integration.md`（环境搭建/对接配置/验证清单已成文）、GAPS §九（"PX4 SITL 脚本写好但从未运行"）。

- **目标**：后端**零改动**接上真实飞控固件（ArduPilot SITL 优先——有预编译二进制，比 PX4 全工具链轻），
  走通"连接 → 任务上传 → 起飞 → 航点 → RTL"最小闭环。
- **前置**：一台 Linux/WSL2（本机 Windows 直跑不了）；ArduPilot `sim_vehicle.py` 或 PX4 SITL；
  与 `docs/sitl-integration.md` §4 的对接配置核对（端口 14550/14540、udp/tcp 差异——
  `tcp://` 的 UDP 顶替已修：2026-10-28 起**明确拒绝 + 指路**（`ArduPilotAdapter`），
  SITL 一律走 `--out=udp:<host>:<port>` + `udp://`）。
- **验收**：`docs/sitl-integration.md` §5 兼容性验证清单逐项打勾；产出一份"真实固件 vs drone-sim
  行为差异"记录（哪怕结论是"没有差异"也要写）。
- **状态**：✅ **2026-10-28 已首验（ArduPilot Copter SITL，WSL2 本机）**。完整闭环全绿
  （连接→任务上传→起飞→航点→RTL→降落→自动 disarm，轨迹 200 点），§5 清单逐项打勾并加
  `[AP-实测]/[离线]/[仿真]` 证据图例；差异记录（8 条差异 + 4 条无差异）见
  `docs/sitl-integration.md` §11.2。首验抓出并修复**三个真机协议兼容问题**：
  ① v2 尾零裁剪致 MISSION_ACK 解码崩溃（上传永远"失败"）② AP 任务槽 0=HOME 致首条命令
  静默丢失（单点任务等于没传）③ 模式标签 PX4 口径硬解致 GUIDED 误显示 RTL。共 +13 回归测试
  （`MissionTrailingZeroPayloadTest` / `ArduPilotMissionUploadTest` / `FlightModeLabelTest`）。
- **P2-F1（真机接入前必须闭环）**：**AP 地面派飞序列适配**——AP 两条硬契约叠加：
  ⑥ AUTO 模式下 arm 被拒（"Auto mode not armable"，需先切 GUIDED）；⑦ AUTO 从地面启动要求
  任务首条命令是 TAKEOFF（"init failed"），单点"去某点"任务起不来。原
  "arm → 单点任务 → start" 流程（`IncidentDispatchService.dispatch` 等）在 AP 实机上必失败。
  **方案 A 已落地并 SITL 验收（2026-10-29）**：`DroneCommandService` 对 AP+AUTO 先切
  GUIDED 再 arm、对地面任务在 home 槽后前置 `NAV_TAKEOFF`（判据 `landedState`，
  armed ≠ 空中）；红/绿对照与验收记录见 `docs/sitl-integration.md` §11.3。
  **方案 B（目标形态）已立项待触发**：`GUIDED + TAKEOFF + DO_REPOSITION(192)` 惯用法
  （Mission Planner "Fly To" 同款），不传任务；无阻塞需求，与 A 并存；**其命令地基已由
  P2-F2 铺平**（下条）。
- **P2-F2（带位置命令 COMMAND_INT 契约）**：**2026-10-30 已落地**——AP 对携带经纬度的命令
  强制 INT 报文（DO_REPOSITION 用 COMMAND_LONG 一律 `MAV_RESULT=8` COMMAND_INT_ONLY，
  与模式无关），而全栈缺 `CommandInt(75)` → 编队修正/队形机动在真机上 100% 静默失效。
  修法：新增 `CommandInt`（与 pymavlink 官方帧逐字节一致）+ `command()` 自动路由
  `COMMAND_INT_REQUIRED` + sim 入站 INT；SITL 修复前/后对照（非 GUIDED `8`→`2`、
  GUIDED `8`→ACCEPTED+飞行）见 `docs/sitl-integration.md` §11.2-⑨、§11.3。
  **这是"sim 测不出"家族第 4 例**（前 3 例：尾零裁剪 / 任务槽 0=HOME / 模式枚举）。
- **附带口径**：`tcp://` 的 UDP 顶替已在 2026-10-28 修掉（改为**明确拒绝 + 指路**，
  `ArduPilotAdapter.connect`）——本次首验全程 UDP，未经过该路径；口径已闭合，不再挂"仍开放"。

## P3 · UOM 真实联调（等的——外部依赖）

来源：`docs/decisions-2026-10-26.md` 决策二（就绪清单五项已登记）。本项目 track 的是**仓内可先行件**：

- ✅ **差异比对框架（先行件，机械部分已交付——#191）**：把 `regulator-sim` 六端点的请求/响应
  与规范逐字段建对照表（列：字段 / 规范形状 / 差异 / 风险）的**比对器与手册已入库**：
  `scripts/uom-contract-diff.py`（六类差异、退出码 0/1/2、9 情形自测）+
  `docs/uom-contract-diff.md`（`spec.json` + `captures/` 两个输入；捕获源可为真实环境或
  `regulator-sim` sim 基线回放）。**剩余**：等 #1 拿到规范与测试环境后填输入跑一次——
  语义裁决（"字段该不该有"）不自动化，留人工对照。
- ✅ **`UomReportSink` 已实现 + 契约测试**（2026-10-28 复核订正：不是"待写骨架"——
  `UomReportSink.java` 四个操作齐全（verify 查询串 / activate / cancel / telemetry 请求体）、
  API Key + HMAC-SHA256 签名头（`METHOD\nPATH\nTS\nBODY` Base64）、指数退避重试 +
  4xx 终态不重试（429 除外）、姓名脱敏；`UomReportSinkContractTest` 8 例在册
  （线格式 / 签名可被平台方复算 / 不配签名键不发头 / 403 终态 / 503 重试 / 429 重试 /
  脱敏 / 未知状态→ERROR））。**剩余**：字段名、枚举、错误码按**真实 API 文档**逐一对齐
  （等 #1）——当前形状是本仓按 sim 约定的，不是规范原文。
- ⏸ 等待项：#1 确定对接主体（**用户侧行动项**）→ 拿到测试环境 → 真实联调 → C1/C2 边界改口径。

## P4 · ONVIF PullPoint 事件订阅（已交付）

来源：`docs/todo.md` #14 备注（#178 协议级 live 客户端落地时"事件面 live 下不假装（REST 先回 501）……
ONVIF 事件订阅（PullPoint）另立"）。

- **目标**：`OnvifLiveBackend` 补 PullPoint 订阅（CreatePullPointSubscription + PullMessages 循环），
  把厂商事件（移动侦测等）接入既有告警总线。
- **验收**：simulated 模式行为不变（缺省路径）；live 模式下 REST 从 501 变为真实事件流；
  与 `SurveillanceController` 现有事件形状对齐（不得再造一套词汇）。
- **状态**：✅ **2026-10-27 已交付（P2 #14 第二刀）**：`OnvifPullPointClient` 实现
  `CreatePullPointSubscription`（PT1H）+ `PullMessages`（PT1S / 16 条），
  `OnvifLiveBackend` 每订阅一条守护轮询线程、取消即停、连续 3 次失败摘除（不静默重连）；
  REST 订阅失败在建立 SSE 前映射 **502**（原 501「未实现」），模拟模式行为不变。
  边界：订阅到期不自动续期（设备以 Fault 拒绝后由上层重订）；只做 PullPoint，不做 Push；
  事件载荷是原始 `NotificationMessage` XML，未做主题归一化。

## P5 · 厂商私有 SDK 与 GB28181 媒体面（等的——外部）

- 海康/大华/宇视私有 SDK：需厂商授权 + 真设备（GAPS §三 已登记前置）。
- GB28181 媒体面（SIP 传输 + RTP/PS）：形状层已交付；媒体面属生产阶段。
  事件订阅面同步：`Gb28181Adapter.supportsEventSubscription()` 已改为 `false`
  ——只构造 SUBSCRIBE 形状、没有 SIP 传输，REST 现在在建立 SSE 之前回 501
  （此前是先回 200 再挂一条永远不会触发回调的空流）。
- 机巢 MQTT 真实 transport：硬件到位再动（todo #17）。

---

## 待口径（产品/外部决定；扫描器与 agent 不替产品决定——审计 §5 原样转录）

1. 空域预约开关缺 profile 覆盖——**已于 2026-10-27 定案为"未完成的上半截"并启用**（staging/prod）。
2. `audit.persist-to-db` 在 staging——**已拍板：staging 打开**（2026-10-27，用户确认；理由：不打开则 prod
   落库路径从未被任何非生产环境验证过，风险不对称。已实现 + profile 钉测试）。
3. UOM 上报计数 + `sink-type` 默认 Noop：对外话术里"上报 UOM"要不要继续写？（与 P3 联动）
4. 机巢事务号 `tidSeq`——**已拍板：持久化续号**（2026-10-27，用户确认；`sequence_counter` 表 V35 已实现）。
5. `planIdSeq`——**已拍板：持久化续号**（同上；"ID 复用比丢数据更严重"成立，已随 V35 实现）。
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
