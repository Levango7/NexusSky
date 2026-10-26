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

1. **空地协同面板的"空中单位"是两层坏叠在一起**：后端 `collectDroneStatuses()` **恒空**
   （`return List.of()`，注释自陈"简化实现"），而**即使补上真数据，前端也拿不到**——
   后端吐 `droneStatuses`，前端读 `aerialUnits || drones`，键名对不上。
2. **重启后"航线没了"看起来像"航线是空的"**：`GET /mapping/tasks/{id}/waypoints` 先查库确认任务存在，
   再从内存 map `getOrDefault(id, new ArrayList<>())` 取值 ⇒ 返回 **200 + 空数组**；
   而 API 文档把 404 定义为"任务不存在"，所以客户端**无法区分"航线丢了"与"本来就没航线"**，
   拿到的是"一份看起来合法的空航线"。这与本仓 2026-10-25 清掉的「0 被当缺失」是同一族病。
3. **拦截决策日志被对外承诺，实现是内存环形队列**：`GET /intercept/logs` 在 API 文档里是契约，
   实现是 `ConcurrentLinkedDeque` + `MAX_LOGS=1000` + 超限丢最旧，零写库点；
   而**同一条围栏链上的"越界事件"是有表的**（`geofence_breach_event`）。
4. **#15 的真实形状不是"不能落库"，是"开关默认关"**：三个 `persist` 开关在 base profile 全 false；
   其中 `aerofleet.audit.persist-to-db` **在 staging 没有覆盖行**（prod 有）⇒ 预发环境验不到审计落库通路。
   更极端的一例：空域预约的**实体和迁移表都已就绪**，但五个 profile 里没有任何一处把开关打开。
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

### 3.1 空地协同"空中单位"——假面 + 键名漂移（**主会话已逐行验证**）

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

### 3.5 两处待定（不硬定性）

* `vision/ExternalVisionSource.java:67`：`detect()` 元数据重载恒空，但类注释 `:36/:66` 已声明；
  `CaptureService.java:343` 只在**抛异常**时回退 truth 源，**空数组不回退**且仍标
  `source:"vision-source"`；`application.properties:212` 的 `aerofleet.vision.source` 是注释态。
  卡点：只读审计判不了"真实部署里有没有人把 source 覆成 external" ⇒ 需要一次带该配置的手测。
* `ai/DecisionMonitorController.java:47`：租户不匹配时返回 200 + 空数组而非 403/404。
  卡点：这是**假面端点**问题还是**鉴权语义**问题，属另一条线（`feedback-api-contract-audit` 的五层里
  属于"响应形状"那层）。

### 3.6 本轮**没扫**的同族（写清边界，免得被当成已核完）

`getOrDefault(key, List.of())` 形态 **17 处**（如 `inspection/InspectionTaskService.java:193/199`、
`edge/EdgeCoordinationService.java:33`、`api/service/SatLinkMonitorService.java:121`）不在上面的正则里；
它们的 map **是否曾被写入**决定"恒空"还是"真数据"，本轮未读消费端。
`vision/RadarController.java:96` 的 `targets` 是否只由 `RadarScanMsg` 填（sim 不发则恒空）未实测。

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

---

## 6. 建议的门禁（这条审计的价值在于它不该再退回去）

先说现状：`scripts/` 下 16 个 `check-*` 门禁**没有任何一个**看"实现体是不是恒空"
（`grep -l "emptyList\|List.of()" scripts/*.py` 零命中）。所以这类面只能靠人读——正是本仓一直在消灭的失效面。

| 建议门禁 | 判据（可复算） | 验收姿势（沿用 `check-spi-coverage.py` 的四元账形状：接口/实现/未验/豁免） |
|---|---|---|
| `check-statefulness.py` | 对 §2.1 的 9 个域，逐个要求"存在 `@Entity`/`Repository`/迁移表 或 显式豁免登记" | 新增一条内存态业务字段而未登记 ⇒ 红；把已落库的域从豁免账删掉 ⇒ 绿 |
| `check-dead-wiring.py`（恒空面） | 匹配"私有方法直接 `return List.of()` 且被 `@*Mapping` 调用链引用"；命中即要求注释含 `未实现` 或登记豁免 | 用 §3.1 那条作合成夹具自证（它现在就在，天然回归探针） |
| profile 开关对账 | `@Value` 里带 `:false` 的 `*.persist*` 开关，若 prod/staging 都没覆盖 ⇒ 报告并列出 | 这条最便宜，先把"表建了但开关没人开"（§5.1）那类抓出来 |

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

- Java **5260**（613 个测试类，0 失败 / 0 错误 / 0 跳过），`cloud-backend` 3088 → **3098**。
- 前端 **580/580，42 个测试文件**（`test/` 40 + `src/` 2）。
- `check-test-count-docs.py` 全部一致；`check-repo-hygiene.py` 通过；
  `check-claimed-endpoint-count.py` 414 = 414 通过。

**没在本批做的，以及为什么**：§2.1 那 9 条"必须落库"每一条都要新增迁移表 + 实体 + 恢复路径，
属于一批一批的独立改动，不能和"接通一个恒空面板"混在一个 PR 里；
§4.2 的死枚举/死 getter 删除要逐个确认写入方（有 6 组只有测试在用），单列下一批。

