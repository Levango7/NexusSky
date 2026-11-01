# 状态性与死面审计：哪些数据重启就丢、哪些能力是恒空的

> **这份文档的规矩**（沿用 `docs/todo.md`）：每条结论必须带**可核对的 `file:line`**，
> 以及**这条结论怎么被复算**（搜过哪些反证词）。没有锚点的话不写。
>
> 与 `docs/todo.md` 的分工：那份是**待办台账**（一条一行、做完划掉）；这份是**审计报告**
> （为什么是这一批、判据是什么、哪些是"故意保留"不是债）。做完的条目要回填台账。
>
> 审计基线：master `a78a8c92`（2026-10-26）。三路扫描（内存态 / 恒空返回 / 死声明）
> 由并行子代理执行，**主会话对吃重的行做了逐条复核**，复核覆盖率见文末。

---

## 0. 结论摘要（按"客户当场问得出"排序）

> **⚠️ 2026-10-29 复核**：本节与 §2.1/§3.1/§3.3/§4.2 记的是**审计基线（master `a78a8c92`，
> 2026-10-26）当时的状态**。下列第 1、2 两条已在后续批次修掉（#177 三列落库 + §9 记录的
> 恒空/键名修复；V34 航线落库），**正文保留的是历史叙述，不是当前事实**——
> 读现状请看 §9/§10 与 `scripts/check-statefulness.py`（9/9 证据齐全、0 违规）。

1. **空地协同面板的"空中单位"是两层坏叠在一起**（**已修，2026-10-26 #177**）：后端
   `collectDroneStatuses()` 当时恒空（`return List.of()`，注释自陈"简化实现"），且
   后端吐 `droneStatuses`、前端读 `aerialUnits || drones`，键名也对不上。
   **现状**：`AirGroundCoordinationService:1009` 已从 `DeviceRegistry` 真实取数（空返回只剩
   降级路径）；`AirGroundCoordinationController:395` 吐 `droneStatuses`；
   前端 `UnifiedCommandPanel.jsx:1592` 已把 `droneStatuses` 纳入读取链。
2. **重启后"航线没了"看起来像"航线是空的"**：`GET /mapping/tasks/{id}/waypoints` 先查库确认任务存在，
   再从内存 map `getOrDefault(id, new ArrayList<>())` 取值 ⇒ 返回 **200 + 空数组**；
   而 API 文档把 404 定义为"任务不存在"，所以客户端**无法区分"航线丢了"与"本来就没航线"**，
   拿到的是"一份看起来合法的空航线"。这与本仓 2026-10-25 清掉的「0 被当缺失」是同一族病。
3. **拦截决策日志被对外承诺，实现是内存环形队列**：`GET /intercept/logs` 在 API 文档里是契约，
   实现是 `ConcurrentLinkedDeque` + `MAX_LOGS=1000` + 超限丢最旧，零写库点；
   而**同一条围栏链上的"越界事件"是有表的**（`geofence_breach_event`）。
4. **#15 的真实形状不是"不能落库"，是"开关默认关"**（**已闭合，2026-10-29 复核**）：
   当时三个 `persist` 开关在 base profile 全 false，`audit.persist-to-db` 在 staging 缺覆盖行，
   空域预约的实体与迁移表就绪但无 profile 打开。
   **现状**：prod/staging **四个开关全部对齐 true**（`application-staging.properties:34/57/63/66`；
   `application-prod.properties:35/87/93/96`），由 `scripts/check-persist-switches.py` 钉住
   （4 开关 / 4 对齐 / 0 违规）。
5. **粗数被校正**：台账写的"10+ 个模块只存内存"实测为**必须落库 9 条 / 需产品定 10 条 / 可接受 11 条**；
   "28 个迁移脚本建 34 张表"实为 **33 张**（去重表名计数）；"33 处恒空返回"实为 **32 处 + 1 处三目写法**。

---

## 1. 方法与总数（写出来才要被复算）

| 扫描面 | 口径 | 命中 |
|---|---|---|
| 内存态容器 | `cloud-backend/src/main/java`（550 个 `.java`）中字段声明为 `ConcurrentHashMap/HashMap/CopyOnWriteArrayList/ArrayDeque/ConcurrentLinkedDeque/AtomicLong/AtomicInteger` 且**承载业务产出** | 85 文件含 `ConcurrentHashMap<`；142 处集合字段；38 处 Atomic 计数；剔除纯缓存/限流/指标/路由镜像后 **22 个候选**，逐域查落库通路后定性 **19 条** |
| 恒空返回 | `return Collections.emptyList() \| List.of() \| Map.of() \| new ArrayList<>()` | **32 处 / 18 文件**（台账粗筛 33，差的那 1 处是 `vision/RadarController.java:96` 的三目写法，不是 `return 空`）；其中 Controller 层仅 3 处，Entity 属性转换器 3 处，其余 26 处在 service/store/解析器内部 |
| 落库通路判据 | 同类/同域是否存在 `@Entity` / `Repository` / `JdbcTemplate` / `save(` / `insert` / `@PostConstruct` 恢复 / 文件写点 | 见 §2 各行"证据"列 |
| 表覆盖基线 | `cloud-backend/src/main/resources/db/migration/V*.sql` | **28 个脚本 / 33 张去重表名**；`grep -riE 'maintenance\|dispatch\|coordination\|incident\|seat\|terrain\|celltower\|report_stat'` 在迁移目录**零命中**（即这些域确实一张表都没有） |
| 正例参照 | 已趟通"内存为主 + 落库 + 启动恢复"的类 | `mission/formation/FormationService.java:91`、`mission/spray/SprayTaskService.java:67`、`mission/delivery/DeliveryService.java:66`、`tracking/FlightTrackStore`（均带 `@PostConstruct` 恢复）——**所以 §2 的"必须落库"批不是技术未知，是没做** |

---

## 2. P2 #15：内存态清单与定级

### 2.1 必须落库（对外承诺了持久性，或丢了破坏合规叙事）

> **⚠️ 2026-10-29 复核：本表九条已全部落库**——"落库现状"列的 `no`/`partial` 是**审计当天
> （2026-10-26）的状态**。V26/V27（空域预约）、V30（维护记录）、V31（出警历史）、V32（合规状态）、
> V33（协同记录）、V34（测绘航点）、V35（序列）、V36（配送六阶段）、V37（应急编排）均已建表，
> 且各域都有 Repository + 启动水合 + 写穿/读穿。
> **机械证据**：`python scripts/check-statefulness.py` → 域 9 / 证据齐全 9 / 豁免 0 / **违规 0**。
> 下表保留原文，作为"当时为什么是债"的记录。

| `file:line` | 存的是什么 | 落库现状 | 重启丢什么 | 证据（另一处锚点） | 主会话复核 |
|---|---|---|---|---|---|
| `mapping/MappingController.java:66` | 测绘航线航点 | no | `requireTask(id)` 查的是库（`mapping_task` 行还在）→ `getOrDefault(id, new ArrayList<>())`（:306）⇒ **重启后这个端点返回 200 + 空数组**，而 `docs/api-reference.md:1680` 把 404 定义为"任务不存在"，所以客户端**分不清"航线丢了"和"本来就没有航线"**，看起来像一份合法的空航线 | 航点只在内存：`:163-164` 生成后仅 `waypointsByTask.put(...)`，无任何写库点；`MappingTask.java` 全文 `waypoint` **0 命中**（实体没有航点列）；契约见 `docs/api-reference.md:1680` | ✅ 方法体与契约都逐行读过 |
| `delivery2/DeliveryStatusTracker.java:20` | 配送六阶段状态 | no | 任务在库，但 `GET /tasks/{id}/status` 对已存在任务抛 404 | `delivery2/DeliveryTask2.java:43`（库里是另一套 `PENDING/IN_PROGRESS` 词表——**两套状态词汇**） | ✅ 读过字段 |
| `health/MaintenanceController.java:56` | 维护记录（POST 创建） | no | 维护面板清空，预测性维护/工单叙事断 | 迁移目录 `grep maintenance` **零命中**（无表） | ✅ 读过字段 |
| `autodispatch/AutoDispatchService.java:67` | 出警历史（上限 500） | no | `GET /autodispatch/history` 归零，出警不可回溯 | 同目录 9 个文件零 `Repository` | ✅ 读过字段（`ConcurrentLinkedDeque`） |
| `mission/emergency/AirGroundCoordinationService.java:85` | 六阶段协同记录 + 指挥报告 | no | `GET /air-ground/report/{id}` 404，事件时间线无处归档 | 见 §3.1（同类的 `:919` 恒空已实测） | ✅ 字段读过（`ConcurrentHashMap<String, CoordinationRecord> coordinationRecords`） |
| `regulator/ComplianceStateManager.java:31` | 实名/激活合规状态 + SN 绑定 | no | **已激活设备回到 UNVERIFIED**，监管上报丢身份关联 | `:20` 类注释自陈"C1 阶段以内存态管理，**C4 持久化后切换为 Repository 实现**"——切换没做 | ✅ 读过自述注释 |
| `audit/AuditService.java:61` | 审计哈希链缓冲（1000） | **partial** | dev/test 默认档：审计链与 `/audit/verify` 全归零 | `application.properties:179 aerofleet.audit.persist-to-db=false`（prod `:87` 才 true） | ✅ 开关实测 |
| `api/service/EmergencyOrchService.java:43` | M9 编排计划/阶段进度 | no | planId 失效，编排进度与事件列表不可查 | `orch/entity/OrchestrationPlanEntity.java` 存在但是**另一域**，本类零引用 | ✅ 字段读过（`ConcurrentHashMap<Long, Map<String,Object>> plans`）；档位待 §5 定 |
| `scheduling/ConflictAvoidanceService.java:76` | 空域占用预约 | **partial** | 冲突避免失去历史占用 | `@Value("${aerofleet.scheduling.reservation.persist:false}")`（:76）+ **五个 profile 无一覆盖**（`grep reservation.persist` 在 `resources/` 零命中）+ 表 `airspace_reservations` 已在迁移里 | ✅ 三处都自己验过 |

### 2.2 需产品定（取决于业务口径，不猜）

`flightlog/FlightLogService.java:65`（`persist-to-db` 默认 false，false 时落容器本地 `./*.jsonl` ⇒ **Pod 重启即丢**；prod/staging 为 true）、
`gateway/DeviceRegistry.java:36`（`device-registry.persist` 默认 false；dev 默认档下 ADMIN 登记条目消失 ⇒ 会复现"白名单死锁"那一族）、
`regulator/TelemetryReportService.java:72`（上报对账计数无表；且 `sink-type` 无 profile 覆盖 ⇒ 真实去向本就是 `NoopReportSink`，见 `docs/todo.md` #16）、
`api/service/LoRaRelayService.java:61`（LoRa 回传计数从零起算，`AtomicLong` totalReceived/Success/Failed
在 :61/:63/:65，事件本身落库）、
`alarm/AlarmLinkageEngine.java:53`（操作员建的联动规则全失；`docs/PRODUCT-POSITIONING.md:129` 自陈"内存态配置，未持久化"）、
`scenario/ScenarioTemplateController.java:54`（18 个预设可由 `ScenarioPresetFactory` 重建，但 POST 的自定义模板消失）、
`roc/IncidentDispatchService.java:57`（警情与派遣确认不可回溯）、
`api/service/TerrainMapService.java:36`（地形/限制区变更史清空；限制区本身有 `geofence_zone` 表，**变更史没有**）、
`mission/emergency/EmergencyCommandWorkflow.java:35`（命令生命周期转移史消失，应急复盘断链）、
`roc/RocSeatService.java:43`（席位会话；`:18-19` 明示"重启清零——与席位语义一致，**非缺陷**" ⇒ 建议归"可接受"）。

### 2.3 可接受（有自我声明锚点，别当债清）

`vision/CvEvalService.java:42`（滑窗 500 帧，API 文档明示重启清零）、
`vision/ThermalTaskService.java:23` / `vision/MultispectralTaskService.java:24`（自述"纯内存状态，进程重启后恢复默认"）、
`mapping/MappingResultService.java:35` + `PhotoCaptureService.java:28`（一律 `simulated=true` 且已声明无真实存储）、
`rid/RidStateManager.java:41`、`api/service/CellTowerTopologyService.java:41`、
`api/service/MeshTopologyService.java:34` / `SatLinkMonitorService.java:35` / `DisasterCommService.java:67`
（设备上报镜像 / 实时快照，`docs/PRODUCT-POSITIONING.md:76` 已声明路由表为内存态）、
`geofence/InterceptLogStore.java:29`（见 §3.2——**这条被从"可接受"提到"必须"**，因为它对外有契约）。

---

## 3. P2 #18：恒空面与对外承诺不符

### 3.1 空地协同"空中单位"——假面 + 键名漂移（**已修复：2026-10-26 #177，2026-10-29 复核**）

> **现状**：`collectDroneStatuses()` 已从 `gateway.DeviceRegistry` 真实取数
> （`AirGroundCoordinationService.java:1009`；`:999` 的注释自陈"这里曾经是 `return List.of();`"）；
> `AirGroundCoordinationController.java:395` 已吐 `droneStatuses`
> （并补了此前缺失的 `coordinationTasks` 读口）；
> 前端 `UnifiedCommandPanel.jsx:1592` 已改为 `droneStatuses || aerialUnits || drones`。
> 下方保留的是**审计当时的坏法**，作为"两层坏叠在一起"的案例记录。

* 后端恒空：`mission/emergency/AirGroundCoordinationService.java:919`
  ```java
  private List<AirGroundSituation.DroneStatus> collectDroneStatuses() {
      // 简化实现：EmergencyOrchService 的 plans map 不直接暴露无人机状态
      return List.of();
  }
  ```
  （:910-913 的注释自陈"真实实现应从遥测服务或编排计划中提取"。）
* 端点：`mission/emergency/AirGroundCoordinationController.java:66` `@GetMapping("/situation")`。
* 消费链：`gcs-web/src/api.js:1425` `getAirGroundSituation()` →
  `gcs-web/src/components/UnifiedCommandPanel.jsx:17` import、`:413` 调用、`:1592-1595` 渲染。
* **第二层坏**：`situationToMap`（Controller :375 起）吐的键是 `droneStatuses`，
  而前端 `:1592` 读的是 `airGroundSituation.aerialUnits || airGroundSituation.drones`
  —— 两边都不含 `droneStatuses`。**所以补了真数据也还是显示"暂无空中单位"。**
  反证搜索：在 `mission/emergency/` 全包搜 `aerialUnits|groundUnits|coordinationTasks` **零命中**。
* 派生面同为恒零：`buildMeshTopology:952`、`computeConnectivityRate:677`、
  `computeResourceConsumption:705/709` 都由这个恒空列表派生 ⇒ `/situation` 与
  `/evaluate/{id}` 的拓扑与比率恒 0，但 HTTP 200。
* 文档侧的诚实度**比面板好**：`docs/api-reference.md` 对 `/situation` 只写 `200 SituationView`，
  没有逐字段承诺非空——**所以这条的误导面在 UI，不在 API 文档**（子代理初稿把这行写成
  "文档承诺了恒 0 字段"，主会话核对后收回）。
* 测试为什么没拦住：`gcs-web/test/unifiedCommandWiring.test.jsx:64` 把
  `getAirGroundSituation` mock 成 `null`（任何键名断言都不会红）；
  `AirGroundCoordinationServiceTest.java:427` 反而**把零值拓扑钉成了期望**。
  ⇒ 修法必须同时改这三处，否则"补实现"这一步本身仍验不到。
* 定性：**假面——要么实现、要么把面板这一格显式标"未实现"**；且必须先定"键名以哪侧为准"。

### 3.2 拦截日志对外有契约、对内是环形队列（**主会话已逐行验证**）

* 实现：`geofence/InterceptLogStore.java:29` `ConcurrentLinkedDeque` + `:26 MAX_LOGS = 1000`，
  `:52/:63` 读出即快照；全类无 `Repository`/`JdbcTemplate`/文件写点。
* 对外承诺：`docs/api-reference.md:2328` `GET /intercept/logs → 200 {items,total}`，
  并在 `:2343` 有字段级说明（`sysid,command,lat,lon,verdict,reason?,zoneInfo?,timestampMs`）。
* 同链正例：`geofence/GeofenceBreachEventEntity` + `GeofenceBreachEventRepository` + 迁移表
  `geofence_breach_event` ——**"越界事件"落库了，"拦截决策"没有**。
  后者才是"平台拒绝了多少次越权命令"的合规证据。
* 消费端：`gcs-web/src` 搜 `intercept/logs` **零命中** ⇒ 目前只有 API 契约面在承诺它。
* 定性：**必须落库**（或把文档契约降到"进程生命周期内最多 1000 条，重启清零"并写清）。

### 3.3 报警离线缓存在超容量与重启两个窗口会丢，且**丢多少不可知**（**主会话已逐行验证**）

* 承诺：`surveillance/offline/OfflineAlarmCache.java:22` "网络恢复后批量上传到
  `AlarmEventStore`，**确保报警事件不丢失**"。
* 实现：`:38` `ConcurrentLinkedQueue`、`:36 DEFAULT_MAX_CACHE_SIZE = 500`、
  `:69-73` 超限 `poll()` 掉最旧并 `log.warn(...)`。⇒ **断网期间 >500 条时最旧的被丢**；
  且 flush 之前进程重启 ⇒ 缓存内全部丢。
* 公平地说：驱逐**有 WARN**、也有测试钉着（`OfflineAlarmCacheTest.java:51`
  "超容量时驱逐最旧事件（FIFO），不是丢最新"），所以不是"偷偷丢"。
* 真缺口是**事后不可量化**：`getCacheStats()`（`:162-179`）只给
  `totalCount/maxCacheSize/oldestTimestampMs/newestTimestampMs`，**没有 evictedCount**；
  全仓 `grep evict` 在 metric/counter 语境零命中。运维只能翻日志条数。
* 落库目标本身是好的：`alarm/AlarmEventStore.java:21` "基于 JPA Repository 的有界存储…重启不丢失"，
  `:96/:144` 有 `repository.save()`。
* 定性：**口径必须降或指标必须补**（建议补 evicted 计数 + `getCacheStats` 暴露，
  并把类注释的"不丢失"改成"上限 500，超出丢最旧、重启丢失未上传部分"）。

### 3.4 其余 26 处空返回 = 合法空态

`tracking/FlightTrackStore.java:276/313/327`、`flightlog/FlightLogService.java:401/442`、
`webhook/WebhookService.java:168`、`geofence/GeofenceStore.java:288`、
`commadapt/FailoverManager.java:174`、`orch/ResourceManager.java:149`、
`surveillance/SurveillanceDeviceRegistry.java:166`、`mission/common/DroneCommandService.java:529`、
`geofence/LocalFileRestrictionSource.java:66`（各有 deque/文件/租户/MAVLink count 的真实非空分支）；
`EmergencyOrchController.java:260/273` 是请求体解析默认值，非端点；
`orch/StepExecutor.java:349/355`、`orch/OrchestrationPlanService.java:753/759/771/777`、
`AirGroundCoordinationService.java:932/939`、`ExternalVisionSource.java:73/84/90/121`、
`SprayTaskEntity.java:246`、`DeliverySequenceEntity.java:182`、`GeofenceZoneEntity.java:197`
为私有 JSON 解析器 / JPA `convertToEntityAttribute` / IO 降级。⇒ **合法空态，保留**。

### 3.5 两处待定（**2026-10-28 复核定性：都不是缺陷，不改代码**）

* `vision/ExternalVisionSource.java` 的 `detect()` 元数据重载恒空——**spec 内的契约**：
  注释原文「元数据级调用：无 JPEG 像素可用（外部源只走 jpeg 重载）」（`:66-67`）；失败语义
  （spec N1）规定非 200 / 超时 / 非法 JSON / 单条字段非法 → WARN 并返回已解析部分
  （全失败 = 空列表），**刻意不抛异常**（`:32-33`）。`CaptureService.detectFromExternal`
  （`:267-293`）对空结果**不回退 truth 也正确**——回退到 `ProjectionVisionSource` 会在推理
  服务宕机时**伪造检出**，正是本仓最反对的假面；`truth(fallback)` 标签（spec S1）只保留给
  **异常**路径（`:283-289`），空结果如实标 `external` 并由 `ExternalVisionSource` 的 WARN 留痕。
  `aerofleet.vision.source` 有代码内默认（`CaptureService:83` 的 `:truth`），base properties 的
  注释态不构成死键（外部源由 `@ConditionalOnProperty(havingValue="external")` 条件装配）。
  ⇒ **维持现状**。复核锚点：`ExternalVisionSource:32-33/:54-57/:64-92`、
  `CaptureService:83/:154-164/:267-293`。
* `ai/DecisionMonitorController.java:47` 租户不匹配返回 200 + 空数组而非 403/404——**换线裁决**：
  属 `feedback-api-contract-audit` 的「响应形状」层；本仓同族（按租户过滤后返回空列表）
  是既定形态，单点改 403 会造出两套鉴权语义。⇒ **维持现状**；要统一，整批按 API 契约审计处理。

### 3.6 本轮**没扫**的同族（**2026-10-28 已扫清：零恒空面**）

- `getOrDefault(key, 集合默认值)`：全仓 main Java 实测 **14 处**（10 处 `List.of()/emptyList()`
  + 4 处 `new ArrayList<>()/CopyOnWriteArrayList<>()`；审计当时报「17 处」是更宽的扫描口径）。
  逐条读消费端与写入路径，**没有一处是恒空面**：

  | 位置 | 写入路径 |
  |---|---|
  | `ai/DecisionMonitorService:33` | `computeIfAbsent` :21 记决策 |
  | `api/service/SatLinkMonitorService:121` | `compute` :80 收卫星过境 |
  | `edge/EdgeCoordinationService:33` | `computeIfAbsent` :21 |
  | `inspection/InspectionTaskService:106/145/193/199` | `put` :75/76/77/162/167 |
  | `orch/OrchestrationPlanService:569` | 局部依赖图 `put` :536（无依赖步骤走空表） |
  | `scheduling/ConflictAvoidanceService:651` | `computeIfAbsent` :487（+ V26 重启水合 :99） |
  | `vision/TargetTracker:130` | `computeIfAbsent` :85 |
  | `mapping/MappingController:372` | `put` :164（V34 航点落库） |
  | `mapping/MappingResultService:173` | `computeIfAbsent` :197 |
  | `mapping/PhotoCaptureService:88/99` | `computeIfAbsent` :75 |

- `vision/RadarController.java:96` 的 `targets`：由 `compute` :119 写入；drone-sim 在 FR-06
  雷达扫描任务里**真实发送** `RadarScanMsg(30017)`/`RadarTargetMsg(30018)`
  （`VirtualDrone:2472-2504`）⇒ 不是恒空面，「空」只出现在没跑雷达任务的场景（合法空态）。
- 结论：§3.6 登记的两个同族不存在假面；本条**收口**，不再重复扫描。

---

## 4. 死枚举 / 死声明 / 死配置键

### 4.1 方法与总数

- **枚举**：全仓 161 个含 `enum` 的文件 / **139 个枚举声明**，逐个做「类名 + 裸常量名 +
  `Enum.CONST` + `case` 标签 + 前端/JSON/SQL 字符串字面量 + `values()/valueOf()` 表驱动」反查。
- **假声明字段**：原判据用 `@Schema`，**全仓 `@Schema` 命中 0**（rg 实测），
  故该类改为「public 访问器零调用 + 前端零消费」口径 → 12 个死 getter。
- **配置键**：**109 个 `aerofleet.*` 键**（含 profile 去重）双向对账
  `@Value` / `@ConfigurationProperties` / `@Scheduled(fixedRateString)` / `Environment` 前缀扫描。

### 4.2 确认死码（节选，全部带反证搜索）

| `file:line` | 符号 | 生产/测试引用 | 反证词 | 定性 | 主会话复核 |
|---|---|---|---|---|---|
| `application.properties:250` | `aerofleet.geofence.intercept.enabled` | 0 / 0 | 全键 + leaf + deploy/compose/docs | **假开关**：真正在用的是 `:240` `restriction.intercept-enabled`（`GeofenceInterceptService.java:62` 读） | ✅ 两侧都读过 |
| `application.properties:252` | `…intercept.log-max-size` | 0 / 0 | 同上 | **声称可调，实现硬编码**（`InterceptLogStore` 里当时是常量 1000） | ✅ 本批已接线 |
| `application.properties:226` | `…restriction.source-path` | 0 / 0 | 两键名 + `sourcePath` | **键名漂移**：代码实际读的是 `aerofleet.geofence.restriction-file`（`LocalFileRestrictionSource.java:53`） | ✅ 两侧都读过 |
| `drone-sim/.../celltower/AccessSelectionStrategy.java:8` | 枚举整类 + 3 常量 | 0 / 0 | 类名、3 个裸常量、`FR-TERM-03`（docs 查无）、前端字面量 | 确认死码 | ✅ 全仓 grep 只命中自身文件 |
| `drone-sim/.../SearchLightPattern.java:15` | 枚举整类 + `brightnessAt` | 0 / 0 | 类名、4 常量、方法名 | 确认死码（仅 `BuzzerPattern` 的 javadoc `{@code}` 提了一句） | ✅ 全仓 grep 只命中自身 + 那处注释 |
| `drone-sim/.../mesh/MeshRouter.java:67` | `TopologyEvent` | 0 / 0 | 5 个常量名全仓 + gcs-web | 确认死码；真推送用的是 `MeshTopologyService.MeshTopologyEvent` | ✅ 全仓 grep `TopologyEvent` 只命中 `MeshTopologyEvent` 那套 |
| `orch/enums/TriggerType.java:9,12` | `EMERGENCY_START/END` | 0 / 0 | 裸名、前端串、DB 串 | 死常量（**类在用**）——但可经请求体写入并落库，删常量＝改契约 | 未复核 |
| `commadapt/CommAdaptConfig.java:58,68,78,88,98` | 5 个阈值 getter | 0 / 0 | getter 名；真实读路径是 `snapshot()` | 死访问器 | 未复核 |
| `dock/DockProperties.java:42,52,54,56` | 4 个 getter | 0 / 0 | `props.*` 枚举（在用的 5 个另算） | 死访问器 + 对应键绑定后零消费 | 未复核 |

另有 6 组「**只有测试在用**」的常量/访问器（`GroundCover`、`DeliveryStatus.DAMAGED`、
`CityModel.OSGB/TILES_3D`、`TerrainZoneType.OPEN_FIELD`、`WorkOrderStateMachine.DISPATCH/RESOLVE`、
`LightPattern.BLINK/BREATHE/CHASE`）——**不许直接删**：删之前要确认写入方，见 §5。

### 4.3 判为在用、不许当债清（表驱动与协议对齐）

`link-sim/LinkProfile.WIFI5/WIFI24_FAR/RADIO_SIK`（`of(String)` 按名字反查，`--profile wifi5` 实走）、
`delivery2/DeliveryMethod.ROPE_LOWER`（`DeliveryPanel.jsx:43` 字符串契约）、
`SituationMarker.MarkerType.POINT`（`CityTwinPanel.jsx:97`）、
以及 `mavlink-core/enums/` 与 `MavlinkMessageChecksum.FieldType` 这类**与官方消息表/私有方言段
逐位对齐**的常量——`scripts/mavlink-compatibility-check.py` 把 Java 常量表当唯一真相源，
这类"看着零引用"的是在用。按 ordinal 上/下行的 `AdjustmentReason/TelemVerdict/SearchRescueCapability`
在 wire 文档逐位核对之前**不能判死**。

---

## 5. 需要产品/外部口径才能定的问题（不许扫描器替产品决定）

1. 空域预约：`airspace_reservations` 表和实体都在，**没有 profile 打开开关**——是故意的 prod 遗漏，
   还是"空域预约对外还不算承诺"？（这一条决定它是债还是未完成的上半截。）
2. `audit.persist-to-db` 在 staging 缺覆盖行：预发**该不该**跑落库通路？如果不该，
   那 prod 的落库路径就是**从未被任何非生产环境验证过**。
3. `TelemetryReportService` 的 UOM 上报计数 + `sink-type` 默认 `Noop`（台账 #16）：
   对外话术里"上报 UOM"要不要继续写？
4. `dock/SimLoopbackGateway.java:35 tidSeq`：机巢事务号是否要求**跨重启单调**（监管上报常见要求）。
5. `scenario/ScenarioLauncherService.java:85 planIdSeq`：若 `planId` 进过客户侧报表，
   重启后 ID 复用比"丢数据"更严重。
6. `mission/formation/FormationKeeper.java:43 correctionCount`：队形修复次数是否算考核/计费依据。
7. 告警离线缓存：接受"上限 500 + 重启丢未上传"作为产品语义，还是要真做非易失（磁盘溢出/落库暂存）？
8. `/air-ground/situation` 的键名以哪侧为准（后端 `droneStatuses` vs 前端 `aerialUnits|drones`）。
9. **`spi/` 插件框架要不要留**（2026-10-30 实测，见 §11.2/§11.4）：12 个类 / 1266 行的
   `PluginRegistry` + `PluginConfig` + 4 个插件接口 + 上下文/元数据/生命周期，**零实现、零外部引用**；
   它名下 8 个 `aerofleet.plugins.*.enabled` 假开关已随本批从 properties 删除。
   两条路都能走：**A. 补实现把开关接进真实执行路径**（载荷/传输/决策/航线四处各一次判定 +
   关闭时的对外语义），**B. 删空壳**，把"可插拔"从声称里摘掉，等真有第三方插件再做。
   `docs/architecture-deepening-plan.md` 记的是 A 的排期，但**没人认领**——
   留在这儿的意思是：别让一具没人接的框架顶着"能力"的名字躺在主干里。
   > **✅ 2026-10-30 裁决（owner 选"删空壳"，落实见 §12.1）**：走 B。
   > 动手前复核两处：① 规模写错了——不是"12 个类"，`git ls-tree` 实测是 **13 个文件 / 1266 行**
   > （`Waypoint` / `RoutePlanRequest` / `PluginContext` 这些 DTO 与枚举没计进"类"），
   > 行数没错、结论没错；② 删除前按 13 个类名逐个全仓反查（`.java/.py/.yml/.properties/.md/.ts/.ps1/.sh`），
   > **零 `.java` 引用**，命中的只剩 CHANGELOG 历史条目、properties/ci.yml 的取证注释、
   > `docs/architecture-deepening-plan.md` 的设计正文、`demo-scenarios.md:283` 与
   > `scripts/e2e-spray.sh:15` 两处"夹爪无 REST 端点"的说明——都已就地标注去向。
   > 接口形状没有丢：`docs/architecture-deepening-plan.md` §2.3/§2.4 仍在，将来真要开放
   > 第三方扩展，从那里重建即可。

---

## 6. 建议的门禁（这条审计的价值在于它不该再退回去）

先说现状（2026-10-26 审计当时的）：`scripts/` 下 16 个 `check-*` 门禁**没有任何一个**看"实现体是不是恒空"
（`grep -l "emptyList\|List.of()" scripts/*.py` 零命中）。所以这类面只能靠人读——正是本仓一直在消灭的失效面。

**2026-10-28 更新：三条建议里两条已落地**（各带八情形离线自测进 CI repo-gates）：

| 建议门禁 | 判据（可复算） | 状态 |
|---|---|---|
| `check-statefulness.py` | 对 §2.1 的 9 个域，逐个要求"锚点证据齐全（实体/仓库/迁移表/水合方法）或显式豁免登记（理由+到期日）" | ✅ **已落地**。四类红：证据不齐且无豁免 / 豁免已满足（棘轮只准拧紧）/ 豁免到期 / 锚点失明；无域可判 ⇒ exit 2（不许"没查到"冒充"没问题"）。当前账：**9 域全部证据齐全、豁免清零**——落地时只带两条豁免（V36/V37 在上游 PR），#186 合并、本分支 rebase 后门禁**当场把两条豁免判成「豁免可以撤销」**，按棘轮删账（这条自动抓账即它的第一次真实世界验收） |
| `check-persist-switches.py`（profile 开关对账） | `@Value` 里 `:false` 默认的 `*.persist*` 开关，prod 与 staging **都**必须显式覆盖；显式关闭必须进豁免账 | ✅ **已落地**。当前账：4 个开关全部 `prod:true / staging:true`（含 §5.1 的空域预约与 #185 的审计链）。判据只盯出厂姿势（prod/staging），test/dev 不在内 |
| `check-dead-wiring.py`（恒空面） | 匹配"私有方法直接 `return List.of()` 且被 `@*Mapping` 调用链引用"；命中即要求注释含 `未实现` 或登记豁免 | ⏸ **暂缓（写清原因）**：同文件一跳的文本级启发式误报面大，且 §3.1 修复后当前树零命中——做出来的第一版只能对着合成夹具证明自己"能拦"，拦不到任何真东西。**先由上面两条覆盖最贵的两档**（域级"没落库"+ 开关级"没打开"），恒空面与 §3.6 的未扫同族（**2026-10-28 已实测扫清：14 处集合默认值 getOrDefault 零恒空**，见 §3.6）一起评估更实的形式（编译级调用图或按端点契约的门禁）再上 |

**不要**顺手做"内存态全部落库"的大重构：§2.3 那 11 条是设备镜像/快照，落库只会加写放大。
按 §2.1 → §2.2 分档推进，每档一批带测试。

---

## 7. 本轮审计过程中撞到的另一条（dev 体验，不是产品缺陷，但会咬人）

本机跑全 reactor 时，`drone-sim` 有 **4 个用例**在 `AdaptivePathExecutionTest` /
`MissionUploadSessionTest` 报 `java.net.BindException: Address already in use`——
它们绑的是**固定 UDP 端口**（24780 / 24805 等 24xxx 段）。当时机器上正跑着另一个会话的测试进程；
端口空出来之后重跑，同一批 **1452 条全绿**。

结论与边界：
- 这不是产品回归，也不是"CI 抖动"，而是**同一台机器不能并行跑两套本仓测试**——
  与 #162 在 regulator-sim 上修掉的是同一类病（那边 19090 被别的项目的容器占死）。
- 当时那两个进程属于别的会话，**没有被终止**。
- 后续动作：把 `drone-sim` 测试的固定 UDP 端口改成 `new DatagramSocket(0)` + 读回端口，
  与 #162 同形；单独一批做，不动生产监听端口。

---

## 8. 复核覆盖率（诚实声明，免得这份文档被当成"全验过"）

* 三路扫描由并行子代理执行；主会话**只对自己写进本文的锚点负责**。
* 主会话逐行复核并采信的：§3.1（后端方法体 + Controller 映射 + `api.js` + 面板三处 +
  `situationToMap` 键名 + 反证搜索）、§3.2、§3.3、
  **§2.1 九行的字段声明全部亲自读过**（`MappingController:66`、`DeliveryStatusTracker:20`、
  `MaintenanceController:56`、`AutoDispatchService:67`、`AirGroundCoordinationService:85`、
  `ComplianceStateManager:31` + `:20` 自述、`AuditService` 的开关默认值、
  `ConflictAvoidanceService:76` 的 `@Value(...:false)` 与 resources 侧零覆盖、`EmergencyOrchService:43`）、
  迁移脚本 28 个 / 33 张表（子代理报 34，主会话去重计数为 33，**采用 33**）。
* 采信但**未逐行复核**的：§2.1 各行"重启丢什么"的**后果推断**（字段是真的，后果是推的）、
  §2.2 全部、§2.3 全部、§3.4 的 26 处分类、§4。
  这些行若要进入施工，动工前先按 `file:line` 回读一次。
* 子代理结论里被主会话**推翻或收回**的：`/situation` 那条"文档承诺恒 0 字段"（文档实际只写
  `200 SituationView`，逐字段承诺不存在）；"33 处空返回"（实为 32 处 + 1 处三目写法）；
  "34 张表"（33 张）。

---

## 9. 本批已落实（审计不停在清单上）

| 审计条目 | 落实动作 | 验收（改坏⇒红的那条） |
|---|---|---|
| §3.1 空中单位恒空 | `collectDroneStatuses()` 改接机队注册表 `gateway.DeviceRegistry.all()`（新增可选九参构造器，旧八参保留给既有测试）；注册表缺席才空，且留 WARN | `AirGroundCoordinationServiceTest.fusePullsDronesFromFleetRegistry`：两架机进、字段逐项断言，NaN 位置落 0；`fuseDegradesWhenFleetRegistryAbsent` 钉住降级态 |
| §3.1 键名漂移（后端 `droneStatuses`/`surveillanceDevices` vs 前端 `aerialUnits`/`groundUnits`） | 前端三列改读后端真实键名（旧猜测保留为兜底）；后端补发前端已在读但从来没发的 `coordinationTasks`（服务侧新增 `listCoordinationRecords()`，倒序） | `gcs-web/test/unifiedCommandWiring.test.jsx` 新增 3 条：真实键名三列都出数 / 空数组才是"暂无" / 离线机原样显示 |
| §3.1 附带发现：未上报电量夹成 0 会被当"电量告急"、还会拉低平均电量 | `DroneStatus` 增 `batteryKnown`（夹取**前**判定，签名不变），`/situation` 响应带出该标志，`avgBatteryPct` 按它过滤 | 原"电池钳位"用例扩展：`150 / -10` 都是 `batteryKnown=false`，`55` 是 true |
| §3.2 拦截日志丢了多少不可知 + §4.2 `log-max-size` 假可调 | `InterceptLogStore` 上限改由 `aerofleet.geofence.intercept.log-max-size` 注入（默认仍 1000，非正值回退），并累计 `evictedCount`；`/intercept/logs` 响应带 `maxLogs/evictedCount` | 新文件 `InterceptLogStoreTest` 3 条：上限真由参数决定且留最新三条 / 非正值回退默认 / 6 进 2 留 ⇒ `evictedCount=4` |
| §4.2 `aerofleet.geofence.intercept.enabled` 是**假开关**（运维改它以为关了硬拦截，真正生效的是 `restriction.intercept-enabled`） | 删除该死键，原位留注释指向真键 | 键本身没了；`GeofenceInterceptServiceTest` 继续盯真开关的行为 |
| §3.3 "确保报警事件不丢失"这句超出实现 | `OfflineAlarmCache` 增 `evictedCount` 并进 `getCacheStats()`；类注释改成写清两个丢失窗口的口径 | `evictionIsCountable`（驱逐 2 条必须报 2）+ `clearKeepsCumulativeEvictedCount`（累计数不因 clear 被抹）+ 空态统计新增该键 |
| `/situation` 文档只写 `200 SituationView`（空话） | `docs/api-reference.md` 补该端点的逐键字段说明与语义（含 `batteryKnown`、顺序、面板归属） | 文档条目本身即验收面；契约由上面那组前端测试钉住 |
| `/situation` 的**响应形状此前零测试** | 新文件 `AirGroundCoordinationControllerTest` 3 条：必须发前端真在读的那几个键 / `batteryKnown` 是契约的一部分 / 机数据来自注册表且 mesh 节点数随在线机数走 | 删掉控制器里任何一行 `m.put(...)`，这里就红 |

### 9.1 A/B 变异反证（新用例不是摆设的唯一证据）

| 把修复改坏成 | 结果 |
|---|---|
| 去掉离线报警的 `evictedCount.incrementAndGet()` | **恰好 2 条红**（`evictionIsCountable` / `clearKeepsCumulativeEvictedCount`），其余 13 条不动 |
| `collectDroneStatuses()` 改回 `return List.of()` | **恰好 3 条红**（服务 1 + 控制器 2）；"注册表缺席才是空"那条按设计保持绿 |
| `InterceptLogStore` 忽略注入上限、回到硬编码 | **恰好 2 条红**（上限可配 / 丢弃可计数） |
| 前端键名改回审计发现的错键 `aerialUnits\|drones` | **恰好 2 条红**（三列出数 / sysid 原样显示），"空数组才是暂无"保持绿 |

四个变异逐个还原；`grep MUTATION-PROBE` 确认树中无残留探针；
还原后前端复跑 17/17，全 reactor `mvn -B -o clean test` **BUILD SUCCESS**。

### 9.2 最终口径（本机实测，门禁逐格核对）

- Java **5274**（614 个测试类，0 失败 / 0 错误 / 0 跳过），`cloud-backend` 3102 → **3112**。
- 前端 **580/580，42 个测试文件**（`test/` 40 + `src/` 2）。
- `check-test-count-docs.py` 全部一致；`check-repo-hygiene.py` 通过；
  `check-claimed-endpoint-count.py` 414 = 414 通过。

**没在本批做的，以及为什么**：§2.1 那 9 条"必须落库"每一条都要新增迁移表 + 实体 + 恢复路径，
属于一批一批的独立改动，不能和"接通一个恒空面板"混在一个 PR 里；
§4.2 的死枚举/死 getter 删除要逐个确认写入方（有 6 组只有测试在用），单列下一批。

---

## 10. P2 #18 余项落实（2026-10-26，本批）

### 10.1 本批删除（删前反证 + 验收）

| 符号 | 删前反证（命中面） | 验收 |
|---|---|---|
| `drone-sim/.../celltower/AccessSelectionStrategy.java`（整类 + 3 常量） | 类名、3 个裸常量、`FR-TERM-03`（docs 查无）、前端字面量——全仓只命中自身文件 | 全 reactor 编译通过，全量测试无引用失败 |
| `drone-sim/.../SearchLightPattern.java`（整类 + `brightnessAt` + SOS 时间线） | 类名/方法名/4 常量只命中自身；唯一提及是 `BuzzerPattern` javadoc 的 `{@code SearchLightPattern.SOS_MORSE}`，已改写为文字（三短三长三短），不再指向被删类型 | 同上 |
| `drone-sim/.../mesh/MeshRouter.java` 的嵌套 `TopologyEvent`（5 常量） | 5 个常量名全仓只命中自身；真正推送用的是 `MeshTopologyService.MeshTopologyEvent` | 同上 |
| `commadapt/CommAdaptConfig.java` 5 个 getter | 5 个 getter 名零调用；真实读路径是 `snapshot()`（组合读一致性，见类注释） | 同上；`CommAdaptConfig` 的 setter 仍被配置端点/测试使用，未动 |
| `dock/DockProperties.java` 的 `schedulePollMs` 字段 + getter/setter | `props.getSchedulePollMs()` 零调用；**配置键本身仍在用**（见 §10.3） | 同上 |

### 10.2 复核结论：TriggerType 与 6 组"只有测试在用"——保留，不删

判据从"有没有调用"换成"**是不是对外契约或生产语义的一部分**"：以下每一组都能指出生产侧消费点。

| 符号 | 生产/契约侧真引用 | 结论 |
|---|---|---|
| `TriggerType.EMERGENCY_START/END` | `TriggerRequest.type`（`OrchestrationController.java:318,321`）→ `POST /plans` 请求体（同文件 `:61,73`）→ `@Enumerated(STRING)` 落库（`ConditionTriggerEntity.java:37-39`） | **保留**：删常量＝客户端发送即 400、历史行反序列化失败。**但补一条缺口**：`TriggerManager` 只评估 `STEP_COMPLETE`/`TIMER`（`:148,:173`），`EMERGENCY_*` **可存不可触发**；这属于 §5 的"要产品口径"问题，不是死码 |
| `GroundCover`（3 常量） | 生产方法签名 `SimulatedMultispectralSource.synthesizeBands(..., GroundCover[][])`（`:95`）；三个常量都是生产 `switch` 分支（`:104,:108,:112`） | **保留**：删任一常量都会破坏穷尽 switch |
| `DeliveryStatus.PayloadCondition.DAMAGED` | `GET /tasks/{id}/status` 响应体字段（`DeliveryController2.java:257-259`）的取值域；生产只写 `NORMAL`（`DeliveryStatusTracker.java:55`），`DAMAGED` 仅测试写 | **保留**：响应枚举收窄＝对外契约变更；注明"当前无生产写入方"。**校正**：§4.2 原写"前端色表消费 DAMAGED"有误——`gcs-web` 里 `payloadCondition` 零命中，`EmergencyOrchPanel.jsx:55`/`UnifiedCommandPanel.jsx:74` 的 `DAMAGED` 是**无人机状态**色，与负载状况无关 |
| `CityModel.ModelType.OSGB/TILES_3D` | `POST /api/v1/city/models` 的 `@RequestBody CityModel`（`CityModelController.java:60-63`）；JSON 契约由 `CityModelControllerTest.java:61,76` 钉住 | **保留**：请求枚举取值域 |
| `TerrainZoneType.OPEN_FIELD` | 生产保守默认：`fromCode` 非法 code 返回它（`TerrainZoneType.java:51`）、`fromTerrainType` 的 `default` 分支（同文件 `:75`） | **保留**：删它没有合法兜底值 |
| `WorkOrderStateMachine.Transition.DISPATCH/RESOLVE` | 生产迁移表 `ALLOWED`（`WorkOrderStateMachine.java:27,29`）与目标状态映射（`:35,:37`） | **保留**：工单状态机的边本身 |
| `LightPattern.BLINK/BREATHE/CHASE` | 生产按 ordinal 取值 `LightPattern.values()[pattern]`（`LedState.java:51-53`）、按 name 回显（`VirtualDrone.java:2003-2004`）；`brightnessAt` 的 switch 分支（`LightPattern.java:35-37`）；SDK 文档已把 `"pattern": "BLINK"` 写成调用示例（`docs/sdk-reference.md:165`） | **保留**：wire/文档双重契约 |

### 10.3 口径校正（原审计数字为什么改）

1. **`DockProperties` 原报"4 个死 getter"→ 实际 1 个**：`getTempWarnC/getTempCritC` 在
   `DockService.java:75-76` 判温度告警/FAULT，`getMetricsRollupDays` 在 `DockMetricsService.java:59`
   决定日结回溯窗口；本批只删 `schedulePollMs`。
   注意删的是"Java 字段重复绑定"而**不是配置键**：`aerofleet.dock.schedule-poll-ms` 仍被
   `UnattendedScheduler.java:72` 的 `@Scheduled(fixedDelayString=...)` 消费。
2. 因此 §4.1 的"12 个死 getter"口径应为 **≤9**；本批核实 6 个（CommAdapt 5 + Dock 1），
   其余未复核，不下结论。
3. `DeliveryStatus.DAMAGED` 的前端证据更正，见 §10.2 对应行。

### 10.4 配置键名漂移收口（`restriction.source-path`）

- 原问题（§4.2）：properties 提供 `aerofleet.geofence.restriction.source-path`，代码实际只读旧键
  `aerofleet.geofence.restriction-file`。
- 落实：`LocalFileRestrictionSource` 可选注入 `RestrictionSourceConfig`；`effectivePath()` 语义＝
  **source-path 非空则优先**（文档键），否则回退旧键；两者同时配置且不一致时打 WARN 指明以哪个为准。
  `getSourceId()` 与 `resolveFilePath()` 都走同一入口。
- 验收：`LocalFileRestrictionSourceTest` 新增 `testSourcePathKeyWins` / `testBlankSourcePathFallsBack`
  （8/8 绿）；A/B 变异：忽略 `sourceConfig` ⇒ 恰好第一条红，去掉 `isBlank` 兜底 ⇒ 恰好第二条红；
  还原后 `grep MUTATION-PROBE` 无残留。

### 10.5 本批验证口径（本机实测）

- 全 reactor `mvn -B -o clean test`（rebase 到 `0f78c991` 后）：Java **5440** 用例 / 0 失败 /
  0 错误 / 0 跳过（639 个测试类；`cloud-backend` 3268 → **3270**）。前端未动，仍 **585**。
- 十份口径文档按实测重驱动（README / ROADMAP / whitepaper / sales-pitch-deck / pricing-strategy /
  demo-scenarios / customer-onboarding-guide / competitive-analysis /
  low-altitude-economy-demand-research / product-brief）；`check-test-count-docs.py` 一致。
- 仓门全过：卫生（CHANGELOG 小节 223 → **224**）/ 测试计数 / 端点计数 / 自测覆盖 / 前端端点 /
  目录计数 / 回退式 diff / MAVLink 兼容自测 / 变异自测。

---

## 11. P2 #18 续批：死访问器与配置键的**完整**对账（2026-10-30）

### 11.1 为什么还得再来一批

§4.1 当年那两个数（「12 个死 getter」「6 个死配置键」）是**扫描器粗筛之后的节选**，
从来没有做过全仓清点；§10.3 只校了 `DockProperties` 一处。本批把两件事一次核到底：
先按可复算的口径重扫，再逐条定性，最后把判据本身变成门禁。

### 11.2 配置键：实测数字、假开关与落实

- **口径**：只看 6 个模块 `src/main/resources` 下 `application*.properties` 与 `*.yml`
  里声明的 `aerofleet.*` 键（含 profile 覆盖文件），去重后 **69 个**。
  读者形态四类：`@Value("${k…}")` / `Environment.getProperty(k)`、
  `@ConfigurationProperties(prefix)` 的字段（Spring 宽松绑定，`sinkType` ⇔ `sink-type`）、
  `@ConditionalOnProperty(prefix,name)` 拼出的全名、以及"动态前缀枚举"。
- **8 个假开关（本批删除）**：`aerofleet.plugins.{payload.spray, payload.gripper,
  transport.mavlink-udp, ai.rtl, ai.avoid, ai.adapt-path, route.astar, route.rrt}.enabled`。
  证据链三段，缺一不可：
  1. 唯一读者 `spi/PluginConfig` 在 **main + test 全仓零注入点**——除自身文件外没有任何 `.java`
     引用这个类名（`grep -rl "PluginConfig"` 去掉自身 = 0）；
  2. 四个插件接口 `PayloadPlugin` / `TransportPlugin` / `RoutePlannerPlugin` /
     `DecisionStrategyPlugin` **没有任何实现类**（`grep "implements <接口>"` 零命中）
     ⇒ `PluginRegistry` 构造时拿到的是空列表；
  3. ⇒ 运维把其中任何一个改成 `false`，**没有任何东西会红，也没有任何东西变化**。
  处理沿用 §9 里 `aerofleet.geofence.intercept.enabled` 的先例：**删键 + 原位注释指向真相**，
  properties 里现在写的是"这 8 个键曾是假开关、为什么删、框架在哪"。
- **§4.2 那条「反向缺口：2 个键被代码读取但 properties 里根本没有」按危险口径实测为 0**。
  危险口径 = `@Value("${aerofleet.*}")` **无默认值**且未声明——这种形状会在装配时直接启动失败，
  实测 **0 个**。按"有代码内默认"的宽口径则是 **24 个**键（出厂值只写在代码里，
  运维在配置文件里看不见）——属**可见性**问题而非缺陷，本批不动，登记为遗留（见 §11.6）。
- **新门禁 `scripts/check-config-keys.py`**（本批落地，10 情形离线自测，已接进 CI `repo-gates`
  的自测步与独立步）：盯「properties 声明但代码不读」，并且**要求动态前缀的读者自身可达**。
  这条前提校验正是本批抓出来的洞：旧门禁 `check-doc-config-keys.py` 的动态白名单写着
  「PluginConfig 按 `aerofleet.plugins.*` 动态枚举」，而这条前提**从没人核过**，实测为假。
  该白名单条目已撤销，原位留注释指向本节。
- **框架本体没有删**：`spi/` 包里 12 个类 / 1266 行的插件框架仍在（零实现、零外部引用）。
  「补实现把它接真」还是「删掉空壳」是架构裁决，登记在 §5 第 9 条——不由扫描器替产品决定。
  > **2026-10-30 当日追记**：这条裁决当天就回来了——owner 选「删空壳」，`spi/` 包已整体删除
  > （**13 个文件**，本行的"12 个类"是当时数错，见 §5 第 9 条的复核）。原话留着不改，
  > 是为了让读者看到"扫描器不替产品决定"这条纪律是真的走到了裁决那一步。落实见 §12.1。

### 11.3 死访问器：实测与四类定性

**方法（可复算）**：对 6 个模块 main 源码抽 `public get*/is*()` 声明，与一次建成的
「`name(` 调用点索引」做集合差（不逐个 grep，避免口径漂移）。结果
**224 个「除声明文件外零引用」的 public getter**：

| 分类 | 数量 | 判据 | 本批处置 |
|---|---|---|---|
| 随响应出网 | 174 | 类名能从任一 `@RestController` 文件沿引用扩散到达 ⇒ getter 决定 JSON 字段，删了对外就少一个键 | **不删**。这条判据是**过近似**（只要类名在控制器里出现过就算），方向保守：宁可少删，不多删 |
| 属性名出现在文档/前端 | 10 | 去掉 `get` 后的小写属性名在 `gcs-web` / `docs` / `deploy` 里出现 | 不删；其中 **2 条经逐条读代码确认是同名词碰撞**，见 §11.4 |
| 只有测试在用 | 27 | 仅测试调用 | 不删（同 §10.2 判据：测试钉的是行为，删访问器等于拆自己的锚点） |
| 真死候选 | 13 | 三条都不成立 | **删 12**、留 1（见 §11.4） |

**对 §4.1 的校正**：「12 个死 getter」这个数**不存在过**——它不是全仓清点结果，而是当年节选表里
`CommAdaptConfig`(5) + `DockProperties`(4) 等条目的加总。实测「零外部引用」面是 **224**，
其中真死 **13**，本批删 **12**。§10.3 里我写的「12 个死 getter 口径应为 ≤9」同样偏小，一并作废。

### 11.4 逐条处置（删 12 / 留 2）

删除的 12 个访问器（字段本身在类内仍被读，只是访问器没人调；`@return` 注释一并摘掉）：

| 位置（删除前） | 访问器 | 类内是否仍读该字段 |
|---|---|---|
| `sim/gnss/GnssSimulator.java:182` | `getReportedAlt()` | 派生值（spoof 只改 lat/lon，不改 alt）；外部只调 `getReportedLat/Lon` |
| `sim/gnss/GnssSimulator.java:194` | `getReceiver()` | 是（类内 8 处直接用 `receiver`）——**它的"文档证据"是假阳性，见下** |
| `sim/gnss/GnssSimulator.java:198` | `getBaseStation()` | 是（类内 8 处） |
| `sim/mesh/CrossZoneRelayPolicy.java:91` | `getAllowedRelayTypes()` | 是（类内 9 处，`isAllowed`/`toString` 走字段） |
| `sim/mesh/DisasterZoneManager.java:314` | `getRelayPolicy()` | 是（类内 9 处，判定走 `relayPolicy.isRejected(...)`） |
| `sim/mesh/GeoBoundary.java:75` | `getVertices()` | 是（类内 16 处；外部只消费 `contains()` / `vertexCount()`） |
| `sim/orch/DynamicReconfigurator.java:135` | `getHeartbeatTimeoutMs()` | 是（类内 6 处） |
| `sim/orch/DynamicReconfigurator.java:140` | `getLowBatteryThreshold()` | 是（类内 13 处） |
| `sim/orch/DynamicReconfigurator.java:145` | `getCriticalBatteryThreshold()` | 是（类内 9 处） |
| `sim/orch/DynamicReconfigurator.java:150` | `getCoverageDeclineReplanThreshold()` | 是（类内 9 处） |
| `sim/orch/PriorityTask.java:146` | `getSubmitTimeMs()` | 是（类内 6 处） |
| `sim/orch/PriorityTask.java:171` | `getPreemptedTaskId()` | 是（类内 7 处） |

**分类器自己的两处假阳性**（不写出来，这份文档就会给错的判据背书）：

- `GnssSimulator.getReceiver()` 的"文档证据"是 `deploy/monitoring/alertmanager.yml` 里
  Prometheus 的 `receiver:` 配置项，跟这个属性毫无关系 ⇒ 它其实是零引用访问器 ⇒ **已删**。
- `AdaptiveRouteMetric.getW3()` 的"前端证据"是 `VideoFusionPanel.jsx` 里 SVG 字符串中的
  `http://www.w3.org`（`w3` 同名）⇒ 读代码发现它在测试里被断言 4 次 ⇒ 归类改为**只有测试在用**，保留。

保留的 2 个：

- `sim/sat/AdaptiveHysteresis.getHysteresisMs()`——同文件 `:75` / `:122` 两处内部调用
  （它落进"真死候选"是因为扫描按"除本文件外零引用"筛，没先扣掉同文件调用；已在本批纠正口径后仍保留）。
- `cloud-backend/.../spi/PluginConfig.getDisabledPlugins()`——随整个插件框架一起留给 §5 第 9 条的
  架构裁决；单点删它既不省什么，又会让"框架还在"这件事更难看清。
  （2026-10-30 追记：裁决已下——整个框架按 §12.1 删除，这个方法随之消失。）

### 11.5 本批验证

- 全 reactor `mvn -B -o clean test`：**5466 用例 / 0 失败 / 0 错误 / 0 跳过**（643 个测试类；
  cloud-backend 3296 / drone-sim 1452 / link-sim 139 / mavlink-core 519 / regulator-sim 41 /
  sdk-java 19）。本批**零测试增删** ⇒ 10 份口径文档无需改数，`check-test-count-docs.py` 实测"全部一致"。
  第 12 个删除（`GnssSimulator.getReceiver()`）单独复跑 `mvn -o -pl drone-sim -am test`：**1452 / 0 失败**。
- 新门禁 `check-config-keys.py`：`--self-test` **10/10**；真实仓库首跑通过（69 个声明键全部有读者）。
  A/B 变异：往 `application.properties` 掺一个 `aerofleet.plugins.probe.enabled=true`
  ⇒ 门禁 rc=1 且**点名该键**；还原 ⇒ rc=0，`grep` 确认无探针残留。
- `check-doc-config-keys.py` 撤销 `aerofleet.plugins.` 白名单后：自测 4/4、真实仓库仍绿
  （可解析配置键 220 个 / 扫描 8 篇当前口径文档）。
- 其余八门禁（卫生 / 测试计数 / 端点计数 / 自测覆盖 / 前端端点 / 目录计数 / 回退式 diff /
  MAVLink 兼容自测 / 变异自测）全过。
- **删除类改动的验收形状要说清**：死访问器没有任何用例会因删除而变红——"改坏⇒红"对它们不适用，
  能给的证据只有**编译 + 全量测试 + 零引用面复算**。这条本身就是本批要登记的事实。

### 11.6 遗留（写清"为什么这批不做"）

> **2026-10-30 同日状态**：三条都已由 owner 裁决并落实，见 **§12**。原文按留档规矩不改，
> 只在每条后面补状态；其中第 1 条的数字在下一次实测中从 24 变成了 **30**（口径差别见 §12.2）。

1. **24 个"代码内默认、properties 未声明"的键**：要不要统一声明进 `application.properties`，
   是运维可见性口径问题（声明了就多 24 行要跟代码同步的账），本批不动，等口径。
   ⇒ **已裁决**：只把文档/部署/前端点过名的 13 个声明进 properties，其余保持代码内默认（§12.2）。
2. **`spi/` 插件框架 12 类 / 1266 行空壳**：补实现还是删，见 §5 第 9 条。
   ⇒ **已裁决：删**（实测 13 个文件，§12.1）。
3. **「随响应出网」这条判据是过近似**：174 个里可能混有真死的访问器。要收紧需要编译级调用图
   （或按端点响应 schema 逐个核），与 §6 里 `check-dead-wiring.py` 暂缓是同一个原因——
   **文本级启发式在这里已经到能力边界**，宁可把它登记成"已知弱点"也不假装它精确。
   ⇒ **已裁决：不收紧**，改为加一条 **report-only**（只提示、恒退 0）的零外引用 getter 清单，
   让"新写出的死访问器"至少看得见（§12.3）。

---

## 12. P2 #18 三条遗留的 owner 裁决与落实（2026-10-30）

> 来路：§5 第 9 条（spi 框架）与 §11.6 三条遗留。用户在两条路上都选了"最小可信"那一侧：
> **该删的删干净、该看不见的才不声明、判据不够硬就不判红**。三件事互相独立，可拆 PR。

### 12.1 `spi/` 插件框架：删（方案 B）

- **规模更正**：§5/§11 里写的"12 个类"实测是 **13 个文件 / 1266 行**
  （`git ls-tree -r origin/master -- .../spi | wc -l` = 13；行数 1266 不变）。
  少算的是 `Waypoint` / `RoutePlanRequest` 这类 DTO 与枚举——"类"这个词当时是按"主要类型"数的。
- **删前复核**（按 13 个类名逐个全仓反查，`.java/.py/.yml/.properties/.md/.ts/.ps1/.sh`）：
  **零 `.java` 引用**；命中项只有 ① CHANGELOG 历史条目（不删，历史就是历史）
  ② properties / `ci.yml` 里的取证注释 ③ `docs/architecture-deepening-plan.md` §2.3–2.4 的设计正文
  ④ `demo-scenarios.md:283` 与 `scripts/e2e-spray.sh:15` 两处"夹爪无 REST 端点"的说明。
- **改了哪些面**：删 13 个文件；properties 注释改写成"键删了、框架也删了、形状在哪"；
  `check-config-keys.py` 去掉静态表 `DYNAMIC_PREFIX_CLAIMS`（改由代码现场扫"谁声称按前缀枚举"，
  少一处会漂移的手工账）；`check-doc-config-keys.py` 注释追记；`ci.yml` 注释追记；两处文档就地加日期说明。
- **为什么删**：它承诺的四类能力（载荷 / 传输 / 决策 / 航线）在 `drone-sim` 侧**都已真实交付**
  （`PathPlanner` / `DecisionEngine` / `ObstacleAvoidanceStrategy` / ROADMAP M2 的 Actuator），
  cloud 这套只是同名的第二套类型且零消费者；ROADMAP 的 M0–M13 也没有"第三方插件生态"里程碑。
- **形状留档**：`docs/architecture-deepening-plan.md` §2.3（四份 SPI 定义）与 §2.4（注册中心设计）
  原样保留——将来真要开放扩展，从那里重建，成本是重写而不是重新设计。
- **验收**：`mvn -o test-compile` 通过；全 reactor 回归见 §12.4。删除类没有用例会因此变红，
  能给的证据仍是"编译 + 全量测试 + 零引用面复算"（与 §11.5 同形）。

### 12.2 只在代码里有默认值的键：只声明 13 个

- **重测**：当前 **30 个**。§11.2 记的 24 已归因——**旧扫描只认单行 `@Value("${k:默认}")`**，
  漏掉两类同等有效的读者形态，正好 6 个（在审计基线 `cce0f10d` 上复算：30 − 6 = 24）：
  - `@Scheduled(fixedDelayString = "${k:默认}")` **5 个**：`comm-adapt.detection-interval-ms`
    （`LinkQualityMonitor.java:41`）、`comm-adapt.evaluate-interval-ms`（`CommAdaptAdvisoryService.java:75`）、
    `sensing.aggregate-ms`（`SensingTrackService.java:53`）、`health.check-interval-ms`
    （`HealthMonitorService.java:333`）、`signing.posture.watchdog-interval-ms`（`SigningPostureService.java:182`）；
  - **跨行书写**的 `@Value` **1 个**：`drone-sysid`（`DroneCommandService.java:102-103`，
    `"${aerofleet.drone-sysid:1}"` 落在注解续行上，按行匹配的扫描器看不到它前面的 `@Value(`）。
  **不是 master 前进带来的**：三个基点（`cce0f10d` / `c6676144` / `dd19dc74`）的"有代码内默认"键集合
  完全相同（**79 个**），未声明集合也同为 **30**。复算方式：用 `git grep` 在任意提交上现扫
  声明面（`*/src/main/resources/*`）与读者面（`*/src/main/java/*`），不检出工作区、不写临时文件。
  本批仍**不按 30 全量声明**，只按"文档/部署/前端点过名"筛。声明 13 个后，仍只存在于代码里的为 **17 个**，
  其中 **5 个**的读者仍是 `@Scheduled`/跨行 `@Value` 形态（`sensing.aggregate-ms` 已随本批声明）
  ——将来若要扩大声明面，扫描器必须按"读者形态"而不是"单行注解"来认。

  **剩下的 17 个逐条列在这里**（键 / 代码默认 / 读者位置），免得"17"变成又一个不可核的数：
  - 内部调参 10 个：`audit.capacity`=1000（AuditService.java:91）、
    `comm-adapt.detection-interval-ms`=5000（LinkQualityMonitor.java:41）、
    `comm-adapt.evaluate-interval-ms`=5000（CommAdaptAdvisoryService.java:75）、
    `device-registry.signing-verify-write-interval-seconds`=60（DeviceRegistry.java:187）、
    `health.check-interval-ms`=5000（HealthMonitorService.java:333）、`orbit-pool-size`=2（OrbitJobManager.java:70）、
    `signing.posture.watchdog-interval-ms`=30000（SigningPostureService.java:182）、
    `tracking.max-points`=3600（FlightTrackStore.java:51）、
    `tracking.search.default-wind-speed`=5.0（LostDroneAlertService.java:54）、
    `tracking.search.min-radius`=50.0（LostDroneAlertService.java:50）。
  - **部署相关 7 个**（这一类是本次盘点的新发现，见 §12.5）：`drone-host`=127.0.0.1（UdpGateway.java:151）、
    `drone-sysid`=1（DroneCommandService.java:103）、`gb28181.sip-host`=127.0.0.1:5060 与
    `gb28181.sip-domain`=00000000000000000001（Gb28181Adapter.java:30-31）、
    `sensing.base-lat`=22.5907 / `sensing.base-lon`=113.9345（CounterDroneRadarSource.java:42-43）、
    `instance-id`=unknown（MetricsConfig.java:33）。
- **入选 13 个**（属性名在 `gcs-web/src` / `docs` / `deploy` 出现过）：
  `geofence.restriction-file`、`report.cost-per-hour`、
  `scheduling.reservation.auto-radius-m`、`scheduling.reservation.auto-window-ms`、`scheduling.retry.max`、
  `security.bootstrap-admin-username` / `-password` / `-tenant-id`、
  `sensing.aggregate-ms`、`sensing.proximity-alert-m`、`sensing.track-ttl-ms`、
  `sim-truth-base`、`vision.source`。
- **值与代码默认逐项比对**：12/13 逐字节相等；唯一不同的 `bootstrap-admin-password` 是刻意的——
  properties 里写 `${AEROFLEET_SECURITY_BOOTSTRAP_ADMIN_PASSWORD:}`，env 未设时解析为空 = 与代码默认
  "空值不引导"同义，且不把口令写进仓库。这条偏差写在这里，免得下次有人"对齐"成明文。
- **刻意不声明的 17 个**：容量、内部轮询/校验间隔、线程池大小、仿真侧设备指纹
  （`drone-host` / `drone-sysid` / `sensing.base-lat|lon`）、GB28181 SIP 地址、失联搜索兜底常数
  （`tracking.search.*`）。写进配置文件会被当成可调旋钮，而其中一部分调了会让设备/对端对不上——
  这属于 §11.2 修掉的那种"看起来可调"的另一面。
- **新增声明的副作用检查**：全仓 `containsProperty(...)` / `getProperty("aerofleet...")` 调用点为 0，
  所以"声明一个此前缺失的键"不可能翻转任何 presence 分支；值又全部等于代码默认 ⇒ 零行为变化。
- **门禁**：`check-config-keys.py` 实测声明键 69 → **82**，全部有真实读者，通过。

### 12.3 getter 判据：不收紧，改 report-only 清单

- **裁决理由**：判据是过近似（Jackson 按 getter 定 JSON 字段名，"代码里没人调"≠"没人消费"）；
  收紧到编译级调用图要 2–3 天且收益不确定，而误报会把仓门变成"经常红但没人理"。
- **落地**：新脚本 `scripts/list-unreferenced-getters.py`，**恒退 0**，只打印四档计数与最暗一档名单：
  有调用点 / 契约候选（属性名见于前端·文档·部署）/ 只有测试在用 / 零外引用候选。
  注释与字符串字面量先剥掉再数调用点（§11.4 抓过"注释里的方法名当证据"的假阳性）。
- **实测（本批树）**：扫 1304 个 public 无参 getter → 有调用点 1104 / 契约候选 79 / 只有测试在用 74 /
  零外引用候选 **47**。**这 47 与 §11.3 的 13 不可直接比较**：本脚本不算"随响应出网"那一档，
  序列化的会被留在最暗一档里（更保守但更暗）；反过来"属性名出现在 docs"的口径更宽，会把一些
  真死的挡在最暗档外。两个方向都朝"宁可漏报、不误删"偏。
- **自测**：7 情形（四档归类、注释/字符串不算调用点、有候选仍退 0、`--strict` 才退 1）。
- **CI**：进 `repo-gates` 的自测步 + 独立报告步；不加 `|| true`——脚本自身崩溃属于工具故障，应当响。

### 12.4 验证与计数

- 全 reactor `mvn -B -o clean test`：**5505 / 0 失败 / 0 错误**，六个模块全 SUCCESS
  （mavlink-core / drone-sim / link-sim / regulator-sim / cloud-backend / sdk-java）；
  本批零测试增删；数字在 rebase 到最新 master（`dd19dc74`）后重跑得到，不是沿用旧基线。
- 仓门：`check-config-keys` 10/10 自测 + 实跑通过；`check-doc-config-keys` 4/4；
  新脚本 7/7；`check-doc-counts` README scripts 96 → **97**；CHANGELOG 小节 244 → **245**（rebase 后重驱动）。
- 未核（如实登记）：① ~~24 → 30 的差集未逐条归因~~ → **已归因**（旧扫描只认单行 `@Value`，漏 5 个 `@Scheduled(fixedDelayString)` + 1 个跨行 `@Value`，逐条见 §12.2；三个基点集合一致，与 master 前进无关）；② 47 个最暗候选**一个都没复核**，
  本批不删任何 getter；③ 删 spi 对 Spring 启动的影响只做了编译级验证，没有起一次应用对比 bean 数；
  ④ 7 个部署相关键的声明口径未定（§12.5）。

### 12.5 新发现：17 个里有 7 个其实是"部署相关"，不是内部参数

本批声明口径是"文档/部署/前端点过名的才声明"。逐条核对剩下的 17 个时发现，其中 7 个虽然
**没有任何文档提过**，但语义上明显随部署环境变化：`drone-host`/`drone-sysid`（UDP 网关与指令下发指向哪台机）、
`gb28181.sip-host`/`sip-domain`（SIP 服务器与域）、`sensing.base-lat`/`base-lon`（反制雷达站址）、
`instance-id`（metrics 标签）。它们之所以没进"13 个"，唯一原因是**文档没写**——
这是文档缺口，不是"应该藏在代码里"。

- **不改的原因**：改声明面属于口径变更，超出本批裁决；而且 `gb28181.sip-domain` 这类值
  写进 base properties 后，运维改错会静默连不上对端（需要在文档里同步"怎么校验"）。
- **建议的下一步（等 owner 一句话）**：A. 把这 7 个连同说明写进 `docs/` 的配置参考 + 声明进
  properties（一批小 PR，含一条"改 host 后启动日志能看见"的可观测性检查）；
  B. 只补文档、不声明（保持"代码内默认 + 环境变量覆盖"）；C. 维持现状，登记为已知缺口。
- **✅ 2026-10-31 裁决：走 A，已落实**。7 个键按代码默认值声明进 base properties（零行为变化）；
  `docs/deployment-guide.md` 新增 §3.3「部署相关键」表（值 / 含义 / **改完在哪一行启动日志看见**）；
  原先看不见的两处补了日志：`Gb28181Adapter` 构造时打 `sip-domain/sip-host`，
  `DroneCommandService` 构造时打默认目标 `sysid`（另三处本来就能看见：UdpGateway 的 discovery 行、
  CounterDroneRadarSource 的 base 行、instance-id 走 `/actuator/prometheus` 标签）。
  验证：`check-config-keys` 声明键 88 → **95** 全有读者（本批 +7；#218 落库时到 89，其后 #220 加 5 个 retention 键、#222 加 1 个关键帧限速键）；全 reactor 回归见 CHANGELOG 该批小节。
