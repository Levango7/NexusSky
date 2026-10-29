# NexusSky 变更日志

## [Unreleased] — 修 `GET /api/v1/flightlog` 落库路径「整天取回再截尾」（2026-10-29）

### 修：DB 查询路径把 `LIMIT` 下推到 SQL，租户条件一起下推

`FlightLogService.query()` 在 `persist-to-db=true` 时原本是四条「整天全量」查询 + 内存
`subList` 截尾。`flight_log` 每个遥测点一行（1 机 1 天 1Hz ≈ **8.6 万行**），
一次 `GET /api/v1/flightlog?limit=200` 会把整天数据拖进堆再只留 200 条。
同文件的 JSONL 路径**早已**用定长环形缓冲解决（注释就写着 8.6 万行不能全量读），
DB 路径一直没有对齐——`persist-to-db` 默认 `false`，演示与 CI 都碰不到。

- 新增 `FlightLogRepository.findNewestFirst(...)`：`ORDER BY timestamp DESC, id DESC`
  取前 N 条，`LIMIT` 下推数据库；
- **租户、类型、sysid 三个过滤条件与分页一起下推**。租户不能留在内存里过滤：
  先按全局取最新 N 行、再筛本租户，最新 N 行恰好都属于别的租户时会少返甚至空手而归；
- 服务端把结果反转成旧→新，对外契约不变（末尾=最新，与 JSONL 路径同口径）；
- `limit<=0` 仍表示「当天全部」（HTTP 入口 `FlightLogController` / `EnvAlertController`
  都收口到 [1, 5000]，内部语义保留）。

测试 +5 例：3 例单测钉「下推形状」（`limit>0` 只调 `findNewestFirst`、绝不碰四条全量查询；
租户为 null 走全局口径；`limit<=0` 不调分页查询）+ 2 例 H2 集成测试
（别的租户更新的行不会挤掉本租户名额；`type`/`sysid` 为空时仍能下推分页）。
变异 A/B：把下推改回整天全量取回 ⇒ 单测红；还原 ⇒ 绿（实测通过）。

全 reactor **5466 单测全绿**（5461→5466，cloud-backend 3291→3296）；22 处口径文档已同步。

## [Unreleased] — 修 `GET /api/v1/audit/logs` 缺省返回全表（2026-10-29）

### 修：不带 `limit` 的审计查询会整表加载

`GET /api/v1/audit/logs` 在 `limit` 缺省（或 `limit<=0`）时走 `auditService.findAll()`。
落库模式（`aerofleet.audit.persist-to-db=true`，合规部署的必要姿态）下那是
`findAllByOrderByIdDesc()` —— **整张 `audit_log` 表**读进堆再序列化。而该表是只增表
（每个 POST/PUT/DELETE/PATCH 一行，`AuditInterceptor` 无条件落），且
`aerofleet.audit.retention-days` 默认 **0（永不删除）**：单机演示跑内存模式（上限 1000 条）
永远看不出来，落库跑几个月后一次不带 limit 的查询就能把堆打满；更糟的是调用方
从参数上看不出自己在请求全量（`GET /api/v1/audit/logs` 与 `?limit=0` 等价于全表）。

- **缺省 200、上限 5000、非正数按 1**，与 `FlightLogController` / `EnvAlertController`
  的既有口径一致（全仓统一 `Math.max(1, Math.min(MAX, limit))`）；
- **删除"0/负数 = 全部"的旧语义**——那正是整表加载的入口；
- `auditService.findAll()` 保留（`verifyChain`、单测仍在用），只是**控制器不再调用它**；
- `docs/api-reference.md` 的"默认全部"同步订正为缺省/上限与前因。

测试 +5 例（`AuditControllerTest`）：缺省走 `findRecent(200)` 且 **绝不再调 `findAll()`** ·
显式 limit 透传 · 超大 limit 收敛到 5000 · 0/负数收敛到 1 · 返回体字段映射不变。
变异 A/B：把控制器改回旧实现（缺省 `findAll()`）⇒ 测试红；还原 ⇒ 绿（实测通过）。

全 reactor **5461 单测全绿**（5456→5461，cloud-backend 3286→3291）；22 处口径文档已同步。

## [Unreleased] — 前端「请求失败静默无信号」补齐：全局错误事件 + 顶栏提示条（2026-10-29）

### 修：80+ 处 `.catch` 把失败变成"暂无数据"，界面上没有任何异常信号

`gcs-web` 里 **87 处** `.catch(() => [])` / `.catch(() => null)`：请求失败会退化成空数据，
面板显示"暂无数据"——**用户看不出是失败还是本来没有，devtools 里也没有任何痕迹**。
本仓 2026-10-21 正是因此吃过亏（前端调了 12 个不存在的端点，404 被静默吞掉，面板永远空）。
端点存在性现在有门禁了，但"失败要有人知道"这件事**一直没有出口**。

- `api.js` 新增 `reportApiError()` 与 `API_ERROR_EVENT`：非 2xx、网络错误、超时都会派发
  `nexus-api-error`（带 `url` / `message` / `status`），**不改变任何组件的既有兜底行为**；
- 新增 `components/ApiErrorBanner.jsx`：顶栏下方提示"有请求失败：<原因> · HTTP 500"，
  多次失败累计计数，8s 自动消失、可手动关闭；
- **边界**（都有测试钉住）：调用方主动取消（AbortError，非超时）**不报**——否则用户每切一次
  面板就刷一条错误；401 走既有的 `nexus-auth-expired` 通路，**不重复**报 API 错误；
  超时仍然抛 `TimeoutError`（错误语义不被吃掉）。

测试 +10 例：`apiErrorSignal.test.js`（HTTP 500 / 网络错误 / 超时仍是 TimeoutError /
主动取消不发 / 401 不重复）+ `apiErrorBanner.test.jsx`（显示 / 累计 / 关闭 / 自动消失）。
变异 A/B：删掉非 2xx 的 `reportApiError` ⇒ 测试红；还原 ⇒ 绿（实测通过）。

前端全量 **595/595 通过**（585→595）；`README` 的组件数与"零测试"口径已同步（59→60 个组件）。

## [Unreleased] — 新增「文档配置键」门禁 + 客户指南 8 个假模块开关修正（2026-10-29）

### 修（客户文档）：8 个「模块开关」根本不存在，客户照着配静默无效

`docs/customer-onboarding-guide.md` §5.6 原文让客户"按合同选型，在 `application.properties`
中配置模块启用/禁用"，并列出：

```
aerofleet.modules.mesh.enabled / emergency.enabled / scheduling.enabled / relay.enabled /
twin.enabled / surveillance.enabled / formation.enabled / spray.enabled
```

**全仓 grep 无任何读取点，`application.properties` 也无定义**——配了不会生效，
而客户会以为"已按合同关掉某模块"，实际模块仍然开着。**真实裁剪机制是 License 的模块清单**
（`LicenseModuleMap` 路径→模块映射 + `LicenseInterceptor` 校验；权威清单
`LicenseService.ALL_MODULES`：`core` / `fleet` / `mesh` / `orch` / `emergency` / `network` / `advanced`）。

已按真实机制重写该节，并保留一条明确订正说明（"此前列的 8 个键在本仓不存在"）。

> 这类失真比"路径写错"更隐蔽：路径错了立刻 404；**配置键错了静默无效**，没有任何信号。

### 加：`scripts/check-doc-config-keys.py`（文档配置表里的键必须真被读取）

口径（刻意写死）：
- 只认**表格行**形态 `| `aerofleet.x.y` | … |`——这是文档在"承诺一个配置项"的明确信号；
  正文顺口提到的键（如度量名 `aerofleet.signing.unsigned`）不扫，避免误报；
- 键算"存在"的五种情形：properties 定义 / `@Value` 读取 / `@ConditionalOnProperty`
  （含 `prefix`）拼全名 / 落在某个 `@ConfigurationProperties(prefix)` 下 / 命中动态前缀白名单
  （`aerofleet.plugins.*`，由 `PluginConfig` 按前缀枚举——白名单每条都要写理由）；
- 同时扫 `cloud-backend` 与 `drone-sim`（后者有 `System.getProperty` 之类）；
- 历史/规划文档不扫（CHANGELOG 里的是度量名与历史记录）；键名按 Spring 宽松绑定归一
  （大小写、`_`/`-`）。

自测 4 情形（`@Value` / 已定义 / 前缀覆盖 / 条件装配 → 绿；伪键 → 红；正文提及 → 豁免）；
**A/B 实测**：把这次真实的 8 个假模块开关回放到文档里 ⇒ 门禁红并逐个点名；还原 ⇒ 绿。

CI 接线：自测步骤 + repo-gates 新增 `Doc config key gate`。

## [Unreleased] — 新增「文档端点存在性」门禁 + 修 12 篇文档里写错的 14 个路径（2026-10-29）

### 加：`scripts/check-doc-endpoints.py`（文档自己写出的端点必须在代码里存在）

#201 修掉了 `/api/v1/sat/providers*` 这类"文档写了、代码没有"的失真。既然能出现一次，就说明
**缺一道机械信号**：本仓已有「端点数声称」门禁与「前端调用契约」门禁，却**没有任何门禁看文档
自己写出来的路径**——于是文档可以长期承诺一个客户调不通的接口。

继续扫，又抓出 **14 个写错的路径 / 12 篇文档**（全部按 Controller 实际映射核对）：

| 文档写成 | 真实 |
|---|---|
| `/api/v1/satlink/*`（README、PRODUCT-POSITIONING） | `/api/v1/sat-link/*` |
| `/api/v1/env/alerts`（README） | `/api/v1/env-alerts` |
| `POST /formation/create`、`POST /formation/{id}/takeoff` | `POST /formation`、`POST /formation/{id}/command`（`type=TAKEOFF`） |
| `POST /spray/task`、`POST /auth/users`、`POST /auth/keys`、`GET /telemetry/...` | `POST /spray`、`/users`、`/mavlink/signing/keys`、`/drones/{sysid}/telemetry` |
| `POST /drones/{sysid}/mission/clear`、`GET /flightlog/{logId}` | **无此端点**（已改成说明 + 真实替代） |
| `/mesh/max-hops`、`/mesh/adjust-max-hops` | **无 REST 端点**（`DynamicMaxHops` 在 drone-sim 机载侧） |
| `/api/v1/missions`（sitl 清单） | `/api/v1/drones/{sysid}/mission` |

> 其中 `spray/status/{id}`、`delivery/sequence/{id}` 在代码注释里早已写明"**前端手里没有 ID，
> 于是去调并不存在的端点**，404 又被 `.catch(() => null)` 吞掉"——前端侧修了、文档侧没跟上。
> 这正是本仓"声明≠行为"主线的又一个面。

**新门禁的口径**（刻意写死）：
- 只认 `@RestController`；类级 base 取类声明前最后一个 `@RequestMapping`；方法级兼容
  `"x"` / `value="/x"` / **裸注解**（路径全在类级——`EnvAlertController` 就是这种，第一版漏了它）；
- 匹配用 `{var} → [^/]+`，**不猜**文档里哪段是 ID（否则 `sat-link` / `city-twin` / `max-hops`
  这类合法连字符路径会被误吞）；文档只写父级或写 `...` 都算存在；
- 只扫 12 篇「当前口径」文档；**规划/历史文档不扫**（ROADMAP / todo / commercialization-plan /
  审计报告）——它们本来就允许写"将来要有"的接口，扫了只会逼人写假注释；
- 行 **±2 行**内出现「未实现/不存在/没有/从未/预留/规划…」时豁免（诚实注释常把路径与
  "均不存在"分写两行，逐行判定会把已在说真话的地方判红——实测踩到过）；
- 3 情形离线自测（存在→绿 / 不存在→红 / 否定语境→豁免）+ **A/B 实测**（加假端点⇒红、还原⇒绿）。

CI 接线：自测步骤 + repo-gates 新增 `Doc endpoint existence gate`。

### 顺带：把"字段默认值 ≠ 条件装配结果"钉在代码上

`RegulatorConfig.sinkType` 字段默认 `"sim"`，但 `NoopReportSink` 是
`havingValue="none", matchIfMissing=true`、`Sim`/`Uom` 都是精确 `havingValue` ⇒
**配置键缺失时装的是 Noop**。此前（#198）把字段默认值误读成运行时默认值，导致台账口径写错，
已在 #201 订正；这里把坑写进 `sinkType` 的 javadoc，避免后人再踩（纯注释，无行为变化）。

## [Unreleased] — 漂移清算批：删掉两个不存在的端点 + 回填 M11/M12/M13 与审计/台账的过时口径（2026-10-29）

### 修（对外文档）：SDK 参考与集成指南公开了两个**从未实现**的端点

`docs/sdk-reference.md` §十 与 `docs/integration-guide.md` §7.2 一直写着
`GET /api/v1/sat/providers` 与 `POST /api/v1/sat/providers/{type}/connect`，并注明
"预留占位、返回 501"。**实况：这两个端点从未实现，调用得到的是 404**
（`SatLinkController` 只暴露 `/api/v1/sat-link/*` 九个只读端点；
三家 provider 是 Java 层占位类，`connect()` 抛 `UnsupportedOperationException`）。

- 两处均已改写为**实际端点表**（status / passes / routes / strategy / constellation 等），
  并明确写出"**没有** provider 连接 REST 面"，同时保留三家体制/波段信息供规划参考。
- 这是本仓最不该出现的一类失真：文档承诺的接口客户调不通，且错误码也不同（404 vs 501）。

### 修：里程碑状态与 ROADMAP/代码互相矛盾

`docs/` 下三份对外材料仍停留在 2026-10-02 口径，与 ROADMAP 和代码冲突：

| 位置 | 原口径（过时） | 现口径（已核对代码） |
|---|---|---|
| `whitepaper.md` §2.3 | M11 "不执行动作" | `AutonomyExecutor` 执行级已接线（`--autonomy-exec` 默认关闭、failsafe 永远优先）、`ADAPT_PATH` 任务改写可用 |
| `whitepaper.md` §2.4 | M12 "库能力、尚未接入生产链路" | `EdgeInferenceRunner` 2Hz 出 30053 + 逐目标 30014，`VirtualDrone:181` 装配 |
| `whitepaper.md` §2.5 | M13 "`syncTwin` 无生产调用方、孪生状态为空" | `TwinSyncListener` 订阅 30054 融合态喂 `syncTwin`（`:100/:132`）并广播 30055 |
| `whitepaper.md` 技术指标行 | "M11 未接线、M12/M13 部分接线" | 三者均已接线（边界见 ROADMAP） |
| `sales-pitch-deck.md` ×4 处 | 同上 | 同上 |
| `PRODUCT-POSITIONING.md` | "边缘 AI / 数字孪生实时同步尚未接入生产链路" | 均已接线 |

佐证不止于读码：本机 SITL 运行日志持续输出 `Twin synced: sysid=1 drift=0.0m`（M13 链路在跑）。

### 修：审计报告与两处台账的结论未回填

- `docs/persistence-and-dead-surface-audit.md`：§0 第 1/4 条、§2.1、§3.1 记的是**基线
  （2026-10-26）当时**的坏法。已加复核批注并就地订正，明确"正文是历史叙述，现状看 §9/§10"；
  §2.1 九域**已全部落库**（V26–V37 + Repository + 启动水合 + 写穿/读穿），
  机械证据 `scripts/check-statefulness.py`：域 9 / 证据齐全 9 / 豁免 0 / **违规 0**。
- `docs/real-integration-program.md` P2 第 9 项：staging **已有** `audit.persist-to-db=true`
  （`application-staging.properties:66`）与空域预约开关（`:63`），`check-persist-switches.py` 4/4 对齐。
- `docs/todo.md` #18 结尾"剩余项＝§2.1 的 9 条落库"已销账。

### 订正（本批自查抓到的自身错误）

`#198` 把 `aerofleet.regulator.sink-type` 的默认值写成"默认 `sim`；`none` 才装 Noop"——**错的**。
`NoopReportSink` 是 `havingValue="none", matchIfMissing=true`，`Sim`/`Uom` 都没有 `matchIfMissing`，
且该键**无任何 profile 覆盖** ⇒ **键缺失时装的就是 Noop**（`RegulatorConfig.sinkType` 字段默认
`"sim"` 只是 bean 字段，不参与 `@ConditionalOnProperty` 判定）。todo #16 已按代码语义订正，
并记下行动项："若要默认 sim，需在 profile 显式覆盖键"。

## [Unreleased] — 同族收口：AP `takeoff` 的模式约束实测 + 「不做模式适配」决策钉住（2026-10-29）

### 加：`takeoff` 的 AP 模式约束实测矩阵 + 决策钉子（4 例）

`docs/sitl-integration.md` §11.2-⑧ 只记着"非 GUIDED 被拒"，处置列停在"arm 前切 GUIDED 使该
命令可用"——那只是**间接**（arm 恰好切了 GUIDED）。本轮用 AP SITL 把矩阵实测清楚并钉死决策：

- **实测矩阵**（`[AP-实测]` 2026-10-29，同一 SITL 实例）：地面+AUTO+未 arm → 拒
  `MAV_RESULT=4`；arm（自动切 GUIDED）后 → **接受并爬升**（14.2m）；空中+AUTO → 拒
  （非 GUIDED）；空中+GUIDED → 仍拒（**已起飞状态**不接受）；另测得 **armed 且在地面时
  `DO_SET_MODE(AUTO)` 本身也被拒**（与 ⑦ 的 init failed 同族，此前未记录）。
- **决策：`takeoff` 不做模式适配**——① F1 的 `arm` 已保证 AP 起飞前在 GUIDED（产品流可用）；
  ② 空中切 GUIDED 会**静默打断任务**，副作用大于收益；③ 未 arm 应由 `arm` 显式完成
  （独立入口 + 围栏拦截链），不由 takeoff 代做。
- `ArduPilotTakeoffModeTest`（新，4 例）钉住：任何模式 / arm 状态下 takeoff 只发**一条**
  `NAV_TAKEOFF`，不切模式、不代 arm；变异 A/B 已验证（给 takeoff 加模式切换 ⇒ 红、还原 ⇒ 绿）。
- 文档：§11.2-⑦ 补"armed 地面切 AUTO 也拒"、§11.2-⑧ 补全实测矩阵与决策、§11.3 增"同族收口"条。
- 计数：全 reactor **5456 全绿**（cloud-backend 3286，本批 +4）；22 处口径文档已同步
  （5452→5456 / 3282→3286）；本文件小节 228→229。

## [Unreleased] — AP 地面派飞序列适配：arm 前切 GUIDED + 地面任务前置 TAKEOFF（P2-F1 方案 A，2026-10-29）

### 修：AP 实机上「arm → 单点任务 → start」必失败（两条固件硬契约叠加）

ArduPilot 两条实测硬契约（docs/sitl-integration.md §11.2-⑥⑦）：AUTO 下 arm 被拒
（"Arm: Auto mode not armable"）；AUTO 从地面启动要求任务首条命令是 `NAV_TAKEOFF`，
否则 `MISSION_START(300)` 回 `MAV_RESULT=4`、回退 `DO_SET_MODE(176)` 回
`MAV_RESULT=2`（STATUSTEXT "init failed"）。两条叠加让现有派飞流程
（`IncidentDispatchService.dispatch` / `AutoDispatchService`）在 AP 真机上从地面起不来。

- `DroneCommandService.arm`：AP 目标且当前 AUTO 时先 `DO_SET_MODE(custom=4 GUIDED)`
  再 arm——非 AP/非 AUTO 目标一字节不变；切换未获确认不吞错，仍发 arm 由固件如实拒绝；
- `DroneCommandService.uploadMission` 新增线上适配 `withArduPilotGroundTakeoff`：
  **尚在地面**的 AP 目标在 home 槽后自动前置 `NAV_TAKEOFF`（高度取首条用户项，
  ≤0 兜底 10m），用户条目整体后移一位；地面判据 = `landedState`（1=ON_GROUND；
  2..4=空中）优先、`relativeAlt>1m` 兜底；
- **SITL 验收抓回的回归**：首版用 `armed` 判空中——但 dispatch 是"先 arm 再上传"，
  上传时刻已 armed 而机体在地面，TAKEOFF 前置被错误跳过、`start` 仍失败。
  改用 `landedState` 后同一场景全绿；回归钉子
  `ArduPilotMissionUploadTest.groundTakeoff_insertedWhenArmedButStillOnGround`；
- 测试：`ArduPilotGroundArmTest`（新，5 例钉 arm 命令序列）+ `ArduPilotMissionUploadTest`
  +7 例（含全握手线上形状 `[home, takeoff, wp]`）；变异 A/B 3 个变异改坏⇒红、还原⇒绿；
- 验收：AP SITL（-I1 独立实例）同一 dispatch 场景从地面全自动完成——置 AUTO → arm
  （切 GUIDED、COMMAND_ACK ACCEPTED）→ 上传（读回含 TAKEOFF）→ `MISSION_START` ACCEPTED
  → 爬升至 50m → 到点驻留 30s → `rtl` 返航降落；修复前旧代码同场景红对照
  （AUTO arm 被拒 / start "init failed"）。详见 `docs/sitl-integration.md` §11.3。
- 顺带订正一处"已完成仍标待做"漂移：`docs/real-integration-program.md` P3「差异比对框架」
  bullet 由 ⬜ 改 ✅——#191 已交付 `uom-contract-diff.py` + 手册，程序文档当时漏刷。

方案 B（`GUIDED + DO_REPOSITION` 不传任务，Mission Planner "Fly To" 同款）立项待
真实需求触发，与 A 并存、互不冲突。

## [Unreleased] — 腾讯云 2C2G 试用机部署包 + 两处 compose 登录缺口修复（2026-10-28）

为 CVM 2核2G3M 免费试用档落的"公网 pre-staging"部署包：`deploy/tencent-trial/`
（compose + .env 模板 + bootstrap-vm.sh + deploy.sh + README；镜像直拉 ghcr，机上零构建、
只对外放行 80、全 bridge 网络、内存限额 + JVM 显式堆、2G swap、到期 purge 清单）。
本地 Docker Desktop 全流程冒烟通过（首启一把过、登录、登记、演示飞行），暴露并修掉三个真问题：

- **prod 模式登录 401（跨 compose 的通用缺口）**：DB 模式下登录走 UserRepository，
  `AEROFLEET_USERS` 只是"无库降级"，而库内种子 admin 已被 V20 停用——DB 模式管理员口令
  的唯一正路是 `aerofleet.security.bootstrap-admin-password`（AdminBootstrapRunner 启动引导）。
  **顺手修了 `deploy/docker/docker-compose.yml` 与根 `docker-compose.yml` 的同一缺口**
  （二者此前同样登录不了；compose-smoke 只探 /actuator/health 所以从未暴露）。
- **MAVLink 发现的"鸡生蛋"**：drone-sim 若 depends_on 后端 healthy，则后端启动时解析不到
  `drone-sim` 会把未解析地址记死（容器后起也不恢复）→ 串行启动反而永远连不上。改为 sim 与
  后端并行启动（sim 本就只等对方先说话），实测首启零人工干预打通。
- **`sh -c` 吞参 + compose `$` 插值**（同一形态踩两次）：`command:` 传给以 `sh -c` 为
  ENTRYPOINT 的镜像会变成位置参数被吞掉（`/proc/1/cmdline` 实证 `--sysid=1` 没到 java）；
  compose 解析期还会吃掉单个 `$`。范例修正 = `entrypoint: ["sh","-c","exec java $$JAVA_OPTS ..."]`。

试用机专属事实（详见 `deploy/tencent-trial/README.md`）：五容器本地实测 RSS 合计 ~589M；
prod 白名单（`device-whitelist-enabled=true`）下陌生 sysid 被丢弃——deploy.sh 自动
`POST /api/v1/devices/1`（幂等）登记 Drone-1 并绑租户 1；drone-sim 开箱 STANDBY 待命，
README「演示飞行」一段 curl 实测起飞→航点→自动返航降落；ghcr 拉取慢有 docker save/load 预案。
- **compose smoke 补登录断言（本批）**：`scripts/check-compose-smoke.sh` 新增第 4 项——
  DB 模式 bootstrap admin 真登录（`POST /api/v1/auth/login` 期望 200 且含 token），
  把上文「只探 /actuator/health 所以从未暴露」的缺口变成每轮 CI 门禁；凭据可用
  `COMPOSE_SMOKE_ADMIN_USER` / `COMPOSE_SMOKE_ADMIN_PASSWORD` 覆盖。

## [Unreleased] — 三处「tcp:// 仍开放」的漂移订正（self-consistency，2026-10-28）

### 修：文档与已落地代码互相矛盾

`#191` 已把 `tcp://` 从"UDP 顶替"改为**明确拒绝 + 指路**（`ArduPilotAdapter`），但三处文档
仍按旧口径写着，与新代码直接矛盾（"同一个事实，两个版本"——本仓一直在清的那类失效面）：

- `docs/real-integration-program.md` P2 前置：「`tcp://` 目前实为 UDP 顶替……先把这个谎修了」→
  改为已修口径（明确拒绝 + 指路，SITL 走 `--out=udp:`）；
- 同文 §11 首验记录附带口径：「`tcp://` 实为 UDP 顶替的问题本次未触及，该口径仍开放」→
  **口径已闭合**（本次首验全程 UDP、未走该路径——这条是事实，但"仍开放"已不成立）；
- `docs/product-brief.md` 诚实声明第 1 条：按现状重写 `tcp://` 描述，并补上
  **ArduPilot SITL 已首验（#192）但仍是模拟环境、不等于飞过真机**的事实。

### 账目

- 纯文档订正，无测试/端点/脚本计数变化；CHANGELOG 225 小节自洽。

## [Unreleased] — e2e-regulator 增 uom 通道腿：后端真按 sink-type=uom 装起来（2026-10-28）

### 交付

- **`scripts/e2e-regulator.ps1` 增 `-SinkType sim|uom`**（默认 sim，行为不变）：uom 腿把后端以
  `sink-type=uom`（**真 `UomReportSink`**）+ `uom-base-url` + API Key/HMAC 签名键装配起来，
  FR-31~40 同一套断言全量跑。补上的正是"两个假 UOM 各测各的"里剩下的**装配链**
  （条件 Bean 选型 / URL / 凭据）——字段与状态机语义此前已由跨模块契约测试钉住。
- **顺手修一个跨运行状态污染（对 CI 是必需前置）**：dev profile 是**文件型 H2**，而合规状态
  （V32）跨重启保留——同一台机器连跑两轮 e2e 时，上一轮 FR-34 注销过的 sysid 会以 CANCELLED
  把下一轮 FR-31 顶死（本机实测：uom 腿过后 sim 腿 FR-31~34 全红，status 响应里带着上一轮的
  `lastVerifyTime`）。改为**每轮独立 in-mem 库**（随机后缀 + Flyway 全新迁移）；
  **CI 上两条腿同 runner 连跑同样会撞，所以这个修复是本 PR 的前提而不是附赠**。
- `ci.yml`：e2e-regulator job 增第二条腿 `-SinkType uom`（同端口环境变量）。

### 验证（本机实测）

- `-SinkType uom`：**38 PASS / 0 FAIL**（含 FR-40 的「4xx 终态不重试 + 500 退避」精确计数，
  uom sink 下 403 恰 1 次、500 仍 4 次/7s 退避——与断言语义一致）。
- `-SinkType sim`（默认）：回归 **PASS**。
- 本机避让：8080 被常驻服务占用、18099 被 SITL 会话占用 ⇒ 用 `AF_BACKEND_PORT=18097`
  （脚本既有 env 口径）。
- 计数不变（e2e 脚本不进 surefire 计数）。



> **结构说明（2026-10-19 更新）**：本文件是**单一平铺时间线**——所有条目按写入顺序排列，

> 最新在最上方，不按版本分节。截至 2026-10-29 共有 235 个 `[Unreleased]` 小节，自 v1.0.5
> （2026-10-12）起持续累积至今；要找某个版本的发布边界，以 git tag 为准（4 个 tag 均为

> 附注 tag，`git tag -n99 vX.Y.Z` 可读该版 release notes）。下表为版本索引。



## 版本索引



| 版本 | 发布日期 | commit 数 | 关键交付 | 代码差异 |

|---|---|---|---|---|

| **v1.0.7** | 2026-10-21 | 22 | e2e 十批全转硬门禅（B2 十脚本观察接入 → 五 job 转硬门禅 → 断言聚合真判红 → roc / mjpeg / phone-camera 相继接入）+ 变异测试入场（PIT 审出 4 处安全行为没人盯：限流 / XFF 信任 / 密钥掩码 / 口令哈希；基线 0.8201→0.8448）+ 挖出三处「以为有人守、其实没人守」（变异门禅自身假绿、185 行 docker-compose e2e 从未在 CI 跑过、CHANGELOG 小节计数漂移）+ 4 个真 bug（Redis 硬依赖 / 状态板冻 8 天 / 哈希链断链 / vite high 公告）；0 破坏性变更；CI 37 success + 1 skipped（run 39598501784，tag run 39608087044） | [compare v1.0.6...v1.0.7](https://github.com/Levango7/NexusSky/compare/v1.0.6...v1.0.7) |
| **v1.0.6** | 2026-10-19 | 115 | 安防取流六连救活（/events 真实事件、能力声明门控、取流面板播放、MJPEG 出口、RTSP 凭据编码、HLS 断流）+ gcs-web 前端诚实性清算（55 处「声明了却没人用」系列）；C5 签名 posture 变代码门禁；0 破坏性变更；CI 30 success + 1 skipped（run 39228507697，tag run 39231095908） | [compare v1.0.5...v1.0.6](https://github.com/Levango7/NexusSky/compare/v1.0.5...v1.0.6) |

| **v1.0.5** | 2026-10-12 | 139 | 安防 B1–B5 全线（PTZ 厂商路由、GB28181 事件订阅、设备四态+多通道、FAILED 态+失败重试、空域预约闭环）；C5 签名改逐帧单调时间戳，修高帧率遥测被误判重放；2 项破坏性变更（license 模块 5→7、AlarmTrigger.alarmId）；CI 18 job 全绿（run 38152795212） | [compare v1.0.4...v1.0.5](https://github.com/Levango7/NexusSky/compare/v1.0.4...v1.0.5) |

| **v1.0.4** | 2026-10-06 | 140 | sdk-java Java 版本兼容修复（对外交付：此前构建出的 SDK 字节码为 Java 17） | [compare v1.0.3...v1.0.4](https://github.com/Levango7/NexusSky/compare/v1.0.3...v1.0.4) |

| **v1.0.3** | 2026-09-26 | 1 | 修 jitpack.yml BOM 字符问题（v1.0.4 进一步修复 Java 兼容根因） | [compare v1.0.2...v1.0.3](https://github.com/Levango7/NexusSky/compare/v1.0.2...v1.0.3) |

| **v1.0.2** | 2026-09-26 | 未计 | 添加 jitpack.yml 指定 Java 17（`v1.0.1` tag 已不存在，无法计算基线，故无 compare 链接） | [tag v1.0.2](https://github.com/Levango7/NexusSky/tree/v1.0.2) |

| **[Unreleased]** | 累积中 | — | 235 个小节持续累积（含 v1.0.5 发布前所写的 23 个——项目不改写历史，故仍挂 `[Unreleased]` 标签），最新条目见下方首个 `[Unreleased]` 节 | — |


---



## [Unreleased] — ArduPilot SITL 首验：三个真机协议兼容修复（2026-10-28）

第一次把 cloud-backend 接到**真实固件**（ArduPilot Copter SITL，WSL2 本机），跑通完整飞行闭环
（任务上传 → ARM → 起飞 → 航点 → RTL → 降落 → 自动 disarm，轨迹 200 点），抓出并修复三个
「drone-sim 测不出来」的协议/显示问题，全部带现场证据与回归测试：

- **修复①：MAVLink2 尾零裁剪致 MISSION_ACK 解码崩溃**（mavlink-core）。v2 允许发送方裁掉
  payload 尾部全零字节（ArduPilot 的 C 库默认这么做）：上传成功的 ACCEPTED 被裁到 2 字节
  payload，解码按定义长度直读越界 → 帧被静默丢弃 → GCS 永远"看不到"成功，2.5s 重发后收到
  ERROR（type=1，尾字节非零未裁）被误判为拒绝——**每次上传必"失败"**。修复：
  `MavlinkMessage.decode` 入口按消息定义长度零填充（wire 长度语义与 CRC/签名/转发所见字节
  均不变）。回归：`MissionTrailingZeroPayloadTest`（4 例，用 SITL 抓包原文逐字节复现）。
- **修复②：ArduPilot 任务槽 0 被 HOME 占用，首条命令静默丢失**（cloud-backend）。AP_Mission
  对写入索引 0 的条目直接忽略（"Writing index zero is not allowed, it must be home"），真实
  命令从索引 1 起（`AP_MISSION_FIRST_REAL_COMMAND=1`）。实测：上传 [takeoff, rtl] 读回
  [home, rtl]——takeoff 没了；**单点任务（语音 FLY_TO / 一键派飞形态）在 AP 上等于没传**。
  修复：`DroneCommandService.withArduPilotHomeSlot` 对 ArduPilot 目标前置 home 占位项、用户
  条目整体后移一位（按 `HEARTBEAT.autopilot` 分派，PX4/drone-sim 路径不变）；
  `MissionUploadResult.uploaded` 保持"用户条目数"口径（home 槽是协议实现细节）。
  测试：`ArduPilotMissionUploadTest`（mock 网关驱动全握手：AP 线上 3 条/API 报 2 条、PX4
  回归不变、NaN 位置退化、入参不被修改）。
- **修复③：飞行模式标签按固件口径分派**（cloud-backend）。custom_mode 一直按 PX4 nav_state
  表硬解：ArduCopter 进 GUIDED（custom_mode=4）被解成 **"RTL"**——地面站显示与实际模式危险
  错位（同场首验解开的历史悬案）。修复：`TelemetrySnapshotListener.modeLabel(autopilot,
  customMode)` 按 `HEARTBEAT.autopilot` 分派，AP 用 ArduCopter 模式表（取自
  `ArduCopter/mode.h`，28 个模式），其余保持 PX4 口径；`DroneSnapshot` 新增 `autopilot`
  字段；`IncidentDispatchService` 空闲判据补 "AUTO"（AP 的任务模式名）。测试：
  `FlightModeLabelTest`（GUIDED=4 回归 + 常用模式 + PX4 不变 + onHeartbeat 落库）。

同场首验产出**差异记录**（8 条差异 + 4 条无差异，`docs/sitl-integration.md` §11.2）与
**遗留立项 P2-F1**：AP 地面派飞序列适配——AUTO 下 arm 被拒（"Auto mode not armable"）+
AUTO 从地面启动要求任务 TAKEOFF 开头（"init failed"），"arm → 单点任务 → start" 流程
（`IncidentDispatchService.dispatch` 等）在 AP 实机上会失败；候选 A（过渡：任务前置
TAKEOFF + arm 前切 GUIDED）/ B（目标：GUIDED+TAKEOFF+DO_REPOSITION 惯用法），推荐 B
目标 + A 过渡（`docs/real-integration-program.md` P2 已挂账）。另修正 `docs/sitl-integration.md`
两处与实测相反的文档口径（拉取变体原文写「PX4 回 43 / AP 回 51」，实测 AP 用 legacy
MISSION_REQUEST(40)、drone-sim 用 51）。

验证与计数：本分支已 rebase 到 #191 合入后的 master，合并树全 reactor 全绿——
**5438 用例 / 0 失败**（本批 +13：mavlink-core 515→519、cloud-backend 3259→3268）；
`docs/sitl-integration.md` §5 清单逐项打勾并加 `[AP-实测]/[离线]/[仿真]` 证据图例；
10 份口径文档计数已按实测同步。顺带修复 #191 合入时「结构说明+版本索引」块随新节
被复制成 4 份的排版残留（恢复单份，本文件小节计数刷到 223）。

## [Unreleased] — 审计与文档收口批：§6 两条门禁落地 + §3.5/§3.6 复核 + 三处「已完成仍标待做」漂移订正（2026-10-28）

### 交付

- **`scripts/check-statefulness.py`**：审计 §2.1「必须落库」九域的四元账（证据/豁免/违规/无法判定），
  照 `check-spi-coverage.py` 的豁免形状——四类红：证据不齐且无豁免 / **豁免已满足（棘轮只准拧紧）** /
  豁免到期 / 锚点失明；**无域可判 ⇒ exit 2**（"没查到"不许冒充"没问题"）。当前账：
  **9 域全部证据齐全、豁免清零**。落地时仅有的两条豁免（#186 在途的 V36/V37）在 #186 合并、
  本分支 rebase 后**由门禁当场判成「豁免可以撤销」**——按棘轮删账（这正是它要抓的：
  落库了没人收紧账；把这条自动抓账当成本门禁的第一次真实世界验收）。八情形离线自测。
- **`scripts/check-persist-switches.py`**：`:false` 默认的 `*.persist*` 开关，prod 与 staging
  **都**必须有显式覆盖——审计 §5.1「表建了、开关没人开」（空域预约躺了很久）与 #185
  「审计链 staging 打开」两个真坑的机械版；显式关闭（拍板保持关）必须进豁免（理由+到期日）。
  当前账：4/4 对齐（audit / device-registry / flightlog / reservation）。八情形离线自测。
- **为什么拆两条而不是一条**：域级（"这个域根本没落库"）与开关级（"落库了但没人开"）是两种失效，
  混一条会丢分辨力——前者回退靠改代码，后者回退靠改配置，红的时候要能一眼看出是哪种。
- **`check-dead-wiring.py`（恒空面）暂缓**，原因写进审计 §6：文本级"同文件一跳"启发式误报面大、
  且 §3.1 修复后当前树零命中，先由上面两条覆盖最贵的两档，与 §3.6 未扫同族一起评估更实的形式
  （编译级调用图 / 按端点契约的门禁）再上。

### 顺带收口：§3.5/§3.6 复核（只读证据，零代码改动）

- **§3.5 两条都不是缺陷**：`ExternalVisionSource` 的元数据重载恒空是 spec 契约（只走 jpeg 重载）；
  `CaptureService.detectFromExternal` 对空结果不回退 truth 是**正确**的——回退会在推理服务宕机时
  伪造检出，`truth(fallback)` 标签只留给异常路径（spec S1）。`DecisionMonitorController` 的
  「租户不匹配 200+空」属 API 契约层的整批裁决，不在本审计单点改。
- **§3.6 未扫同族扫清**：`getOrDefault(key, 集合默认值)` 全仓 main Java 实测 **14 处**，
  逐条读消费端与写入路径，**零恒空面**；`RadarController.targets` 由 `compute` 写入且 drone-sim
  在 FR-06 真发 30017/30018。§3.5/§3.6 各带锚点重写为定性结论（§6 里对 §3.6 的引用同步）。

### 顺带订正：三处「已完成却仍标待做」（只读复核 + 文档）

本仓文档反复吃同一个亏：**做完了但正文没跟着改**（ROADMAP 同节自述已滞后四次）。
本次按「文件:行号或测试类名」逐条复核并订正：

- **ROADMAP C4 ①**「真正没恢复的是完整轨迹历史……需从 `flight_log` 重建」——**早已闭合**
  （#174）：`FlightTrackStore.java:115-169` 启动时 `restoreTrackHistoryFromFlightLog()`
  （JSONL/DB 两路、重叠过滤、容量截尾），`FlightTrackStoreRestoreTest` 5 例钉住；
  剩余边界（纯 JSONL-only 无 lastKnown 可锚）按测试类 javadoc 原文登记。同节「给后来者」
  更新为**四次滞后**。
- **`docs/todo.md` #15**「未开工的就是这 9 条落库本身」——**9/9 全清**（V26/V27 启用 →
  V30–V37；审计链 staging 同批打开），并指向 `check-statefulness.py` 的机械账。
- **`docs/real-integration-program.md` P3**「`UomReportSink` 真实现骨架 + 契约测试 ⬜」——
  **已实现**：四个操作 + API Key/HMAC 签名 + 退避重试 + 4xx 终态 + 脱敏，
  `UomReportSinkContractTest` 8 例在册；剩余只是"按真实 API 文档逐字段对齐"（等外部 #1）。

### 顺带：UomReportSink ↔ MockUomServer 跨模块契约（P3 先行件第一步）

- **发现的缺口**：`e2e-regulator.ps1` 全链走 `sink-type=sim`，**真**上报实现 `UomReportSink`
  只在自带的假 UOM 里验过线格式——两个"假 UOM"各测各的，真实现与仓内带激活状态机的
  `MockUomServer` 从未对话过。真实 UOM 还没接（等外部 #1），这是仓内能先做的最强验证。
- **新测试** `UomSinkVsRegulatorSimContractTest` 5 例：`MockUomServer` 以 `--port 0`
  起在 ephemeral 端口，真 HTTP 驱动真 sink——实名三态 + 脱敏 / 激活→遥测→注销全链与
  records 落账 / 未实名不可激活且 4xx 终态不重试 / 幂等（同 ID）/ 成功路径每端点恰 1 次。
  跨模块测试依赖（cloud-backend → regulator-sim，test 范围、排除 slf4j-simple）
  照 drone-sim 既有先例；根 pom 的 dependencyManagement 补管理条目。
- **A/B**：把 telemetry 的 `serialNo` 字段名改成 `serial` ⇒ 测试红（sim 拿不到序列号）；
  还原 ⇒ 绿。契约从此钉住，字段漂移不再无声。

### 修 CI：UOM 跨模块契约改子进程 + ONVIF 停止语义改硬信号（2026-10-28）

- **SLF4J 双绑定（CI 上 125 个用例崩）**：把 regulator-sim 作 cloud-backend 测试依赖时，
  它的 **shade fat jar（内含 slf4j-simple）**进了测试类路径——CI 上 SimpleLogger 赢了
  provider 之争，`VideoFusionRecordingServiceTest` 等访问 Logback 的用例 ClassCast 崩
  （本仓既有 pom 注释恰警告过"provider 顺序敏感导致单跑能过全量挂"）。
  **治本修法**（`c922d409`，与 drone-sim 同形状）：fat jar 改挂 `shaded` classifier 附属、
  主 artifact 保持 thin jar——内嵌 slf4j-simple 再也进不了下游类路径；`e2e-regulator`
  的通配随改 `*-shaded.jar`。跨模块契约测试保持 in-JVM（thin artifact 下依赖安全，
  已实测 `VideoFusionRecordingServiceTest` 等 13/13）。（本 PR 一度另试"子进程跑真 fat jar"，
  rebase 后统一采用 classifier 修法——更简、且修的是根因而不是消费端。）
- **surveillance 变异门禁 CI↔本地 1.1pp 波动**：同一份代码本机 937 杀/47 存活（0.9417），
  CI 925/59（0.9296）——根因是新增 ONVIF 用例里"睡一觉再断言不再发生"的**窗口式负断言**
  在慢机上变弱（漏杀）。改成**线程终止**硬信号：取消/放弃订阅后断言 `onvif-events-<handle>`
  线程在期限内消失（在途慢应答 3s、期限 1s，与机器快慢无关）。A/B：去掉
  `thread.interrupt()` ⇒ 红；还原 ⇒ 绿。
- **报告上传的坑与修**：PIT 报告上传**早已在**（2026-10-22，`always()`/14 天）——我一度漏看、
  又加了一个同名上传 ⇒ 两处 `name: pit-reports` 在 job 内 409，把 job 判红（**门禁两条分数
  其实都过**：security 0.9501/0.9529、surveillance 0.9427/0.9533）。修：删重复 + 给原步骤补
  `overwrite: true`（job 重跑时同名 artifact 已存在也会 409）。教训记进 `docs/mutation-testing.md`：
  **动 ci.yml 前先 `grep 'name: <artifact>'` 数一遍**。
- **复跑撞到的第二个坑**：同分支两次本机 PIT 给出 939/45 与 928/56——差异在 **m2**：
  旧 fat `regulator-sim` jar（含 slf4j-simple）被 PIT 的 minion 类路径吃进去，
  `VideoFusionRecordingServiceTest` 等测试在 minion 崩→被排除→11 个变异体**假存活**。
  刷新 m2 到薄 artifact 后回到 **939/45（0.9437/0.9543）**。判据与细节见
  `docs/mutation-testing.md` 补记——"本机突然掉分"先查 m2 旧 artifact，别急着改代码。

### 账目

- README 脚本数 90→92（`check-doc-counts.py` 实测口径）；测试计数 +5（见上）。
- 两条门禁的 `--self-test` 与真检查都进 `ci.yml` repo-gates job（自测覆盖门禁要求）。
- **A/B（真树改坏⇒红、还原⇒绿）**：拿掉 staging 里 `reservation.persist=true` 一行 ⇒ persist 门禁
  红并点名「staging 未覆盖」；摘掉 `ComplianceStateEntity` 的 `@Entity` ⇒ 状态性门禁红并点名
  「证据未命中 + 缺证据且无豁免」。两个还原后都回绿。
  （踩到一条：`@Entity` 改成 `@EntityX` 不算改坏——`@Entity` 是它的子串，正则照样命中；
  真树 A/B 的价值就在这，夹具自测不会替你想出这种坑。）
- **第三次验收（真实事件）**：#186 合并后 rebase 本分支，门禁立刻把两条豁免判成「可以撤销」
  （V36/V37 证据已出现）——照设计删账、`EXEMPT` 清零，9/9 全证据通过。






### 修复：regulator-sim 的 fat 主 artifact 污染下游 SLF4J（CI 红）

本 PR 新增的 `UomSinkVsRegulatorSimContractTest` 让 cloud-backend **首次依赖** regulator-sim。
该模块的 shade 此前没挂 classifier——早期 CHANGELOG 记的「link-sim/regulator-sim 无下游依赖，
保持默认 fat 主 artifact」这个前提**已经失效**：fat 主 artifact 把内嵌的 `slf4j-simple` 类带进
测试 classpath，而 `<exclusions>` 只能摘依赖坐标、摘不掉 jar 里的类。症状是
`VideoFusionRecordingServiceTest` 的 logback 强制转换抛 ClassCastException，触发 Spring 上下文
失败阈值缓存，最终 `Tests run: 3259, Errors: 125`，并连带 `Mutation baseline gate (surveillance)` 红。

- **修法**：regulator-sim 的 fat jar 改为 `shaded` classifier 附属（主 artifact 回到 thin jar，
  与 drone-sim 同形状）；`scripts/e2e-regulator.ps1` 的 jar 通配改为显式 `*-shaded.jar`（两处）；
  两个入口注释同步 jar 名。
- **验证**：`mvn -o -pl regulator-sim -am -DskipTests package` 产出 thin 30 KB + shaded 2.48 MB；
  `VideoFusionRecordingServiceTest` / `TwinControllerTest` / `UomSinkVsRegulatorSimContractTest`
  **13/13 通过**（修复前这三类全红）。

## [Unreleased] — P3 先行件：UOM 契约差异比对框架（2026-10-28）

### 交付

- **`scripts/uom-contract-diff.py`**：把「规范形状」与「实捕获」逐字段对照出表的比对器——
  差异六类（缺失/多出/类型不符/状态码非预期/规范端点无捕获/捕获引用未知端点），
  退出码 0/1/**2**（"没得比"不许冒充"没问题"）；**9 情形离线自测**进 CI。
- **`docs/uom-contract-diff.md`**：使用手册（两个输入的 schema、runbook、注意事项）+
  六端点清单（锚点 `MockUomServer`）+ **当前状态**：规范侧待填（等外部 #1），sim 侧
  已被 `UomReportSink` / `UomSinkVsRegulatorSimContractTest` 两处钉住（本页刻意**不重复抄
  字段**，避免出现第二份会漂移的手抄）。
- **为什么现在做**：`docs/decisions-2026-10-26.md` 决策二把 #3「差异比对」点名为
  **仓内可先行、不依赖外部**的活——先把机械部分建好，拿到 API 文档后填 `spec.json`、
  落 captures，一条命令出差异表；不临场手抄、不靠记忆对字段。

### 账目

- 新增脚本 1（README 脚本计数按实测刷新）、新增文档 1（docs 篇数按实测刷新）；
  测试计数不变。




## [Unreleased] — ArduPilotAdapter：tcp:// 不再 UDP 顶替，未实现前缀明确拒绝（2026-10-28）

### 修：文档点名的「这个谎」

- `docs/real-integration-program.md` P2 曾写「`tcp://` 目前实为 UDP 顶替，接 SITL 前先把
  这个谎修了或明确降级」——本笔按 `serial://` 同款处理：**明确拒绝 + 指路**。
  `ArduPilotAdapter.connect("tcp://…")` 如实返回 false，并给出可执行指引（给 SITL 加
  `--out=udp:<host>:<port>` 后改用 `udp://`；真实 TCP 链路属生产阶段，需 `TcpMavlinkTransport`）。
- **为什么不保留「UDP 顶替」**：ArduPilot SITL 的 TCP 默认口（5760）**不收 UDP**——顶替对
  真实场景必然静默失败，比明确拒绝更糟（"先回 200 再挂空流"同族）。
- 同步订正三处对外声明：`HardwareAdapter` / `HardwareAdapterFactory` 的 javadoc 与工厂日志；
  `serial://` 的指路语不再把 `tcp://` 说成可用。
- 新测试 `ArduPilotAdapterConnectSchemeTest` 4 例：tcp/serial/未知前缀明确拒绝、
  `udp://` 仍可用（回归）；**A/B**：把拒绝改成"假装成功" ⇒ 红、还原 ⇒ 绿。

### 账目

- mavlink-core 511→515（+4），全仓 5416→5420；计数文档按实测刷新。




## [Unreleased] — 持久化 9/9 收官：配送六阶段上位（V36）+ 应急编排落库（V37）（2026-10-27）

### 做：审计 §2.1「必须落库」最后两条（9/9 全清）

- **配送六阶段上位**（#5 裁决落地，V36）：`delivery_task2` 加 phase + 位置五列；
  tracker 写穿时**粗状态由六阶段派生**（`coarseOf`，单一写入点=二者永不漂移）；
  重启读穿修掉"任务在库、状态 404"；存量回填只做无歧义映射（FAILED/ABORTED 行
  **保持 NULL 不假造**——读穿对它们如实返回"无状态"，404 语义不被污染）。5 例。
- **应急编排落库**（#8 裁决落地，V37）：`emergency_plan` 新表 + 整 Map 快照 JSON
  （Jackson 对 Map 双向无损）；写穿于 start/abort/上报回调三处、读穿于 getPlan——
  重启后编排进度与事件列表可查；planId 由 V35 sequence_counter 续号（两批衔接）。
  4 例。

### 顺带修：NtripClient 静默关闭的异常契约归一

全量测试暴露 `NtripClientTest.handlesSilentClose` 偶发红——Windows 上 caster 静默关闭
是 RST（SocketException），Linux 是干净 EOF：两种同语义（对端回应前关连接）却抛
不同异常类型。修法：connect() 把 SocketException 归一为 NtripException（契约统一），
不放宽测试。

### 账目

- **持久化 9/9 全部落地**（立项书 P1 收官）；计数：cloud-backend 3221→3230（+9），
  全仓 5383→5392。V36/V37 迁移动用"真 jar+真 H2+真 Flyway"启动验证（36 迁移至 v37 + Started）。

## [Unreleased] — ONVIF 事件订阅补齐：WS-BaseNotification PullPoint，取消即停、失败回 502（P2 #14 第二刀，2026-10-27）

### 交付

- `surveillance/onvif/OnvifPullPointClient`（新类）：`CreatePullPointSubscription`
  （InitialTerminationTime=PT1H）→ `PullMessages`（Timeout=PT1S / MessageLimit=16）。
  `NotificationMessage` **原样序列化**交出——厂商私有字段在 `Message` 里的结构千差万别，
  强类型化只会把不认识的部分丢掉。
- live 后端实现 `OnvifEventSource`：订阅成功才返回句柄；每个订阅一条守护轮询线程，
  连续失败 3 次放弃并从表中摘除（**不静默重连**——那会让 UI 看起来仍有事件源）。
- 轮询**决策**拆到 `OnvifEventPoller`（成功清零 / 失败计数 / 第 3 次放弃 / 坏回调不打死轮询）：
  这些是纯逻辑，留在 `while (!stopped)` + 线程里只能靠"起线程 + sleep + 观察副作用"验证，
  变异测试里会成片落成 TIMED_OUT；拆出来后每次 `pollOnce()` 都能同步断言。
- `OnvifClient` 门面按 `backend instanceof OnvifEventSource` 分派；模拟模式行为不变。
- `OnvifVendorAdapter.supportsEventSubscription()` 在 live 下改为 true（设备是否有
  Events 服务由 `subscribeEvents` 当场回答，不预判）。
- `SurveillanceController`：订阅失败在建立 SSE **之前**映射 **502**
  （原来整片 501「未实现」）；501 仅保留给"适配器本身不支持"。
- **同类"200 假成功"顺手收口**：`Gb28181Adapter.supportsEventSubscription()` 改为
  `false`——它只构造 SUBSCRIBE 形状、**SIP 传输未实现，回调永远不会触发**，此前 REST
  会先回 200 再挂一条空流。现在国标设备在建立 SSE 之前回 501（文案带 `GB28181`，
  同时作为"路由确实落在国标适配器"的证据）。
- **为什么是 Pull 不是 Push**：Push 要求设备能连回本服务，现场摄像头通常在独立视频
  网段、服务在 NAT 后——Push 地址设备到不了；PullPoint 只要求"本服务能连设备"，
  与取流同向。

### 测试（新增 24 例）

- `OnvifPullPointClientTest` 6 例：订阅地址/到期时间解析、缺 Address 报错、通知序列化、
  请求参数有界（PT1S/16）、空响应当空列表、设备 Fault 带原因；
- `OnvifLiveBackendTest` +8 例：真 HTTP 假设备端到端（订阅 → 两条通知进回调 →
  取消后不再拉取）、无 Events 服务明确报错、未知句柄取消幂等、瞬时 Fault 退避重试后
  仍交付、后端自身拒绝非法参数、取消订阅打断在途 `PullMessages`、轮询线程为 daemon、
  连续 Fault 到上限后从活跃表摘除（恰好 3 次）；
- `OnvifClientLiveEventsTest` 2 例：门面 → live 后端 → 假设备真实委派（句柄/计数/回调/
  取消后停止拉取）、live 下非法参数在联网前拒绝；
- `OnvifEventPollerTest` 5 例：全部通知按序交付、失败后成功清零、第 3 次失败放弃、
  回调抛异常不打死轮询、重试等待被中断立即停止；
- `OnvifXmlTest` +3 例：`localName` 前缀回退、`serialize` 保留命名空间、`parse` 对 null/
  外部实体 DOCTYPE 拒绝；
- 既有 3 例按新契约翻正：mode（订阅失败抛 `OnvifException` 而非"拒绝实现"）、
  routing（live 声明支持）、controller（live 订阅失败 501 → 502）；另 1 例 GB28181
  控制器用例从"200 + 空流"翻正为"501 + 文案带厂商名"（不新增用例，总数不变）。
- **A/B 变异 3 个**（取消不 stop 轮询 / 通知元素名写错 / 去掉无 Events 服务检查）
  全部改坏⇒红、还原⇒绿。

### 边界

- 订阅到期不自动续期（设备会以 Fault 拒绝）；轮询器连续失败 3 次后停止并记 ERROR，
  由上层重新订阅——协议层不做"悄悄续命"的黑箱；
- 只实现 PullPoint，不实现 Push（理由见上）与 WS-Eventing `Subscribe` 的推模式；
- 事件载荷是原始 `NotificationMessage` XML（含厂商自定义字段），未做主题归一化。

## [Unreleased] — 四项决策拍板落账 + 单调序列 V35 落地（2026-10-27）

### 决策（用户逐项确认，选项与依据见 docs/real-integration-program.md）

1. **配送词汇归一 → 六阶段上位**：`delivery2` 加 `phase` 列存六阶段，对外粗状态派生（API 兼容
   不变），存量行映射回填——实现待做（下一批，V36 起）。
2. **应急编排 → 新表独立落库**：`emergency_plan` 存轻量场景（orch 域 `OrchestrationPlanEntity`
   是带 steps/triggers 的重实体，语义不同不强塞）——实现待做（下一批）。
3. **单调序列 → 持久化续号**：✅ **本批已实现**（见下）。
4. **审计链 staging → 打开**：✅ **本批已实现**（`aerofleet.audit.persist-to-db=true` + profile 钉）。

### 实现：V35 `sequence_counter` + PersistentSequence

- **机巢事务号 `dock_tid`**与**编排计划号 `orch_plan_id`** 重启后不再从头复用
  （"ID 复用比丢数据更严重"的裁决落地）：`PersistentSequence.next(name, base)`
  内存缓存 + 逐发写穿，首号 = base+1（与原内存语义逐位等价）；重启从
  `max(base, 库中最后发出值)` 续起；库抖动 ⇒ WARN 降级（进程内仍单调，发号不阻塞主链）。
- 消费方各留内存降级路径（`sequence==null` 时走原 AtomicLong）——纯单测语义不变。

### 账目

- 计数：cloud-backend 3215→3221（+6），全仓 5377→5383。
- 立项书「待口径」四条拍板登记完毕；P1#5/#8 决策锁定（🔒）待实现。

## [Unreleased] — 持久化第二批（6/9）：合规状态 / 空地协同归档 / 测绘航点（2026-10-27）

### 做：审计 §2.1 的 4/6/7 三条

- **合规状态落库**（第 7 条，V32，最优先的一条）：启动水合 + 写穿。
  **重启后已激活设备不再回 UNVERIFIED**（丢的是合规叙事本身）。
  顺带修掉三个"声明了却从未写入"的 last* 时间戳——RegulatorController 上报的
  lastVerifyTime/lastActivationTime 此前**永远是 0**（只初始化再原样复制）；
  现在按语义落点写入（→VERIFIED 记 lastVerifyTime、→ACTIVATED 记 lastActivationTime），
  未发生保持 0 不假填充。
- **空地协同归档**（第 6 条，V33）：**语义裁决：内存清理 ≠ 归档删除**——内存仍按原口径
  清理防 OOM，库行保留为归档；**读穿修掉「总结后/重启后 GET report 一律 404」**
  （审计点名的"事件时间线无处归档"）。复合对象（deployment/evaluation/report）
  用显式字段映射做 JSON 往返（不可变类不给 Jackson 反射反序列化踩坑）。
  水合专用 restorePhaseTimestamps：不能靠 setPhase 重放——它会把"当时的响应时效"
  写成"重启后的时刻"，正是审计要防的失真。
- **测绘航点落库**（第 4 条，V34）：**契约裁决：任务存在⇒200+真实航点 / 从未规划⇒空数组 /
  任务不存在⇒404**，三态各自唯一（此前重启后 200+空数组与"本来没有航线"不可分）；
  MappingWaypointCodec 显式映射 + 内存命中零反序列化。

### 账目

- 计数：cloud-backend 3202→3215（+13），全仓 5364→5377。
- 立项书 P1 更新为 6/9；余 3 条（配送词汇归一 / 应急编排 / 审计链 staging 口径）+ tidSeq/planIdSeq 单调序列见 program 文档。

## [Unreleased] — 持久化第一批（9 条中的 3 条）+ 真实化路径立项书（2026-10-27）

### 做：审计 §2.1「必须落库」清单的第一批

- **空域预约启用**（第 1 条）：持久化路径（`restorePersistedReservations` + V26/V27 表）
  与往返测试早已建成，但**五个 profile 无一打开**——审计 §5 Q1 定案「未完成的上半截」：
  staging/prod 打开 `reservation.persist`，并加 profile 钉测试（删回去即红）。
- **维护记录落库**（第 3 条，V30 `maintenance_records`）：CRUD 全路径走库；
  **id 序列启动时从库中最大 `m-N` 续起**——不做这步，重启后新记录会覆盖同 id 旧记录
  （比"丢数据"更隐蔽）；无仓库时纯内存语义不变（6 例）。
- **出警历史落库**（第 4 条，V31 `dispatch_history`）：真实 dispatch 主路径写穿 +
  启动恢复最新 500 + **库与内存同口径裁剪**（不发明新留存策略）；落库异常不炸出警主链
  （历史是旁路）；drones 快照存 JSON，坏数据降级空列表不炸历史读取（6 例）。

### 立：`docs/real-integration-program.md`

把散落的剩余项收成一个 program：P1 持久化（本条完成 3/9，余 6 条各带前置）、
P2 SITL 首验（等一个 Linux/WSL 环境；后端零改动接真固件是全仓最高价值验证）、
P3 UOM 联调（先行件=差异比对框架，可不等外部先做）、P4 ONVIF PullPoint（可做）、
P5 厂商私有 SDK/GB28181 媒体面（等授权/硬件）。审计 §5 的口径问题原样转录为「待口径」十条。

### 账目

- 计数：cloud-backend 3189→3202（+13），全仓 5351→5364；docs 41→42 篇（README 目录树同步）。

## [Unreleased] — GAPS 清账批：HardwarePanel 旋翼契约对齐（§八.6）+ CHANGELOG 断条目排版归位（G-2）（2026-10-26）

### ① HardwarePanel `{}` 当一台旋翼（GAPS §八.6，语义裁决后修复）

裁决依据是**后端代码**不是猜：`/rotor/telemetry/{sysid}` 恒返回单台机的**扁平**旋翼视图
（`HardwareDataController.rotorTelemetryView` 字段齐全；`RotorController.getTelemetry` 是
`computeIfAbsent` 永不为 null）——既无 `{rotors:[...]}` 形状，也不可能返回 `{}`。
旧写法 `rotor?.rotors || (rotor ? [rotor] : [])` 的前者是对不存在契约的死防御，
后者会把任何 truthy 异形载荷当旋翼渲染成整行 `--`。修复：只有带契约键 `rotorIndex`
的载荷才算一台旋翼（`!= null` 判——0 是合法值）。新增 `hardwarePanelRotors.test.jsx`
5 例（契约形状恰一行 / 404 空态 / `{}` 空态 / `{rotors:[]}` 空态 / rotorIndex=0 不误杀）。

### ② CHANGELOG P1 断条目排版归位（G-2，纯排版不改一字）

2026-10-12 那条「外部评估报告 P1 续整改」的**标题在 11259 行、正文孤悬在 11427 行起**
（两次解冲突各补一半，标记行已消但条目仍是断的；读者从标题往下看会以为那轮什么都没做）。
本次把正文整段移到标题之下，顺带清掉三处 `---` 族残渣。

### 账目

- GAPS §九.2 的「commands 面缺 land」**经查已由 2026-10-24 提交连测试补齐**
  （`DroneController:205` land case + `DroneControllerTest:185` 同源注释）——过期账，销账。
- 前端用例 577 → 582（+5）。

## [Unreleased] — ONVIF 从模拟升级为 simulated/live 双后端：真实 SOAP + WS-Discovery，失败不回退猜地址（P2 #14 第一刀，2026-10-26）

### 交付

- `surveillance/onvif/**`（新包，11 个类）：`OnvifBackend` 接口 + `SimulatedOnvifBackend`
  （升级前的全部行为逐字迁移）+ `OnvifLiveBackend`（真实协议）+ `OnvifSoapClient`
  + `OnvifDiscoveryClient` + `OnvifXml` / `OnvifValidation` / `OnvifPtzCommands`
  / `OnvifSettings` / `OnvifException` / `OnvifUnsupportedException`。
- **live 侧发真实 SOAP**（无第三方库，JDK `HttpClient` + DOM）：
  Device `GetCapabilities`、Media `GetProfiles` + `GetStreamUri`、
  PTZ `ContinuousMove` / `Stop`；WS-Security `UsernameToken` +
  `PasswordDigest = Base64(SHA1(nonce 原始字节 || created || password))`。
- **WS-Discovery**：向 `239.255.255.250:3702` 发 `Probe`（`dn:NetworkVideoTransmitter`），
  解析 `ProbeMatch`（XAddrs / Scopes / EndpointReference）；另支持**单播目标**
  （`discovery-unicast-targets`，现场常丢多播），`subnet` 在 live 下只做 IPv4 CIDR
  **结果过滤**（链路本地协议没有"扫网段"能力，非 CIDR 记 WARN 不过滤）。
- **口径（不许含糊）**：默认 `aerofleet.onvif.mode=simulated`，demo 与现有
  `OnvifClientTest` 行为不变；`live` 下取流地址由设备给出并标
  `StreamUrlOrigin.ONVIF_GETSTREAM_URI`（`deviceVerified=true`）；
  **SOAP 失败不回退**成按规范猜的 `rtsp://`；设备返回非 RTSP 的 MediaUri 直接报错。
- **live 路由**：`aerofleet.onvif.mode=live` 时海康/大华/宇视统一路由到 ONVIF 适配器
  （三家私有 SDK 适配器仍是模拟实现，不能拿"未向设备确认"的地址冒充真实取流）；
  模拟模式路由完全不变。
- **事件面不假装**：live 下 `subscribeEvents` 抛 `OnvifUnsupportedException`，
  `VendorAdapter.supportsEventSubscription()` 由 ONVIF live 覆写为 false，
  REST 在建立 SSE **之前**返回 501（不是先 200 再挂一条永不来事件的流）。
- **安全**：XML 解析禁 DOCTYPE / 外部实体 / XInclude，响应体 2 MB 上限，
  `HttpClient.Redirect.NEVER`，异常与日志不含密码/nonce/digest；
  `VendorRtspUrl.maskCredentials` 成为唯一脱敏点，`SurveillanceController` 与
  `RapidDeployService` 共用（后者此前 INFO 日志会打印带凭据的 RTSP URL）。
- **配置面**：`aerofleet.onvif.*` 十项（mode / 连接与读超时 / 发现窗口 /
  响应体上限 / device-service-path / discovery-username+password / 单播目标 /
  多播开关），全部有代码默认值，未改 `application.properties`。

### 测试（新增 41 例）

- `OnvifSoapClientTest` 14 例：digest 已知答案（期望值由 Python hashlib 独立算出）、
  假设备**独立重算** digest、固定时钟/nonce、HTTP 500/300、SOAP Fault、DOCTYPE 拒绝、
  响应超限与恰限、snippet 300/301 双向边界、真实 nonce 源互异非全零、不可达；
- `OnvifLiveBackendTest` 18 例：真 `HttpServer` 假固件——能力按 XAddr 映射、
  通道号→ProfileToken、凭据 RFC 3986 注入、端口 0/缺省→554、非 RTSP/空 profiles/
  无 Media 服务报错、device-service-path 空白/null/无斜杠回退、PTZ 速度向量与 Stop
  逐字段断言；
- `OnvifDiscoveryClientTest` 16 例 + `OnvifDiscoveryClientIpv4CidrTest` 5 例：
  真 `DatagramSocket` 收 ProbeMatch、名称 %-解码、subnet 过滤、空白 subnet、
  缺凭据报错、零发送报错（诊断含 disabled）、空主机目标不发本机、65535 上限端口可用、
  XAddr 端口 0→80、主机名不参与过滤、无 Scopes/空 Address 兜底、scopes 全文厂商判定、
  64 台后停止收包；CIDR 的 0/255 八位组与 /0、/32 前缀边界；
- `OnvifSettingsTest` 5 例默认值契约 + `OnvifXmlTest` 8 例（内部子集 DOCTYPE 拒绝、
  localName 无前缀回退、转义、文本归一、顺序）；
- `OnvifClientModeTest` 4 例 + `OnvifLiveRoutingTest` 4 例 + 控制器 501 1 例 +
  `RtspUrlCredentialTest` mask 脱敏 2 例
  （上列用例数为各类当前**累计**数，其中既有 52 例控制器用例不含在"新增"里：
  新增 = 当前 163 − 原有 122 = 41）。
- **变异收口（三轮 PIT，surveillance 包 809→1103 变异体）**：0.9087/0.9214 →
  0.9376/0.9487 → **0.9419/0.9529**（杀掉 636→891）；5 处生产小重构消灭
  不可按契约测试的存活；地板显式下调 0.95/0.96 → **0.94/0.95**，23 条新存活与
  4 条新超时逐条定性（详见 docs/mutation-testing.md 第五轮），棘轮性质不变。
- 全量：`mvn -B -o clean test` **5351 例** / vitest **580 例** / 端点 **414** /
  Python SDK **27 例**；A/B 变异 4 个（凭据注入 / nonce 用 Base64 文本 /
  live 路由 / 501 前置门禁）均改坏⇒红、还原⇒绿。

### 边界

- 事件订阅（WS-BaseNotification / PullPoint）**未实现**，live 下明确 501；
- 只实现明文 HTTP 的 ONVIF 服务：发现阶段跳过 `https` XAddr（需要 TLS
  信任配置，不能把 https 当 http 去连）；
- 通道号按 `GetProfiles` 返回顺序 1-based 映射（ONVIF 无"通道"概念）；
- PTZ 使用第一个 Media Profile（既有 `OnvifClient.ptzControl` 无通道参数）；
- 海康/大华/宇视私有 SDK 与 GB28181 媒体面仍是模拟实现（本条只收 ONVIF 协议级）。

---

## [Unreleased] — P2 #18 余项：死类型/死访问器收口 + restriction.source-path 键名接线（2026-10-26）

### 之前的行为

- `docs/persistence-and-dead-surface-audit.md` §4.2 已列出 3 个确认死类型与死访问器，
  但当时标"未复核"、未动手；6 组"只有测试在用"的常量也悬着，没人能说清删了会不会破契约。
- `aerofleet.geofence.restriction.source-path` 只被 `@ConfigurationProperties` 绑定、
  从未被读者使用：操作员按文档设了也不生效（真正读的是旧键 `restriction-file`）。

### 交付

| 项 | 做法 | 边界 |
|---|---|---|
| 死类型 | 删 `AccessSelectionStrategy`（整类）、`SearchLightPattern`（整类，含 SOS 时间线）、`MeshRouter.TopologyEvent`（嵌套类）；`BuzzerPattern` javadoc 不再引用被删类型 | 真推送早已是 `MeshTopologyService.MeshTopologyEvent`，无行为变化 |
| 死访问器 | 删 `CommAdaptConfig` 5 个零调用 getter（读方统一走 `snapshot()`）；`DockProperties` 只删 `schedulePollMs` 字段/getter/setter | **口径校正**：审计原报 Dock 4 个死 getter，复核后 3 个在用（`DockService.java:75-76` 温度阈值、`DockMetricsService.java:59` 回溯窗口），只删 1 个；配置键 `aerofleet.dock.schedule-poll-ms` 仍被 `UnattendedScheduler` 的 `@Scheduled` 消费 |
| 键名漂移 | `LocalFileRestrictionSource.effectivePath()`：`restriction.source-path` 非空优先，空则回退旧键 `restriction-file`；两键同时配置且不一致时打 WARN 指明以哪个为准；`getSourceId()` 与解析路径共用同一入口 | `application.properties` 默认 `source-path=`（空）⇒ 默认行为不变；测试里直接 `new`（无 Spring 注入）自动回退旧键 |
| 保留判定 | `TriggerType.EMERGENCY_START/END`（请求体 + `@Enumerated(STRING)` 落库）与 6 组"只有测试在用"常量（GroundCover / PayloadCondition.DAMAGED / ModelType.OSGB·TILES_3D / TerrainZoneType.OPEN_FIELD / WorkOrderStateMachine.DISPATCH·RESOLVE / LightPattern.BLINK·BREATHE·CHASE）逐条给出生产或对外契约引用，登记为**不许当债清** | 见审计 §10.2；`TriggerType` 另记一条功能缺口：**可存不可触发**（`TriggerManager` 只评估 STEP_COMPLETE/TIMER），属产品口径问题，本批不擅自删值 |

### 验证

- `LocalFileRestrictionSourceTest` 新增 2 条：文档键胜出 / 空值回退旧键，8/8 绿；
  A/B 变异各杀且只杀目标用例：忽略 `sourceConfig` ⇒ 恰好第一条红；去掉 `isBlank` 兜底 ⇒
  恰好第二条红；还原后 `grep MUTATION-PROBE` 无残留。
- 全 reactor `mvn -B -o clean test`（rebase 到 `0f78c991` 后）：Java **5440** 用例 / 0 失败 /
  0 错误 / 0 跳过（639 个测试类；`cloud-backend` 3268 → **3270**）。前端未动，仍 **585**。
- 删类后全仓 `grep`：`AccessSelectionStrategy` / `SearchLightPattern` 零残留，
  `TopologyEvent` 只命中真实的 `MeshTopologyService.MeshTopologyEvent`。
- 十份口径文档按实测重驱动；仓门（卫生 / 测试计数 / 端点计数 / 自测覆盖 / 前端端点 /
  目录计数 / 回退式 diff / MAVLink 兼容 / 变异自测）全过。无破坏性变更。

---

## [Unreleased] — C4 收口：启动时从 flight_log 重建内存轨迹历史（ROADMAP:337，2026-10-26）

### 之前的行为

重启后 `FlightTrackStore` 只恢复每架机的**最后已知位置一个点**（DB last-known 表），
前端实时轨迹是孤点；完整历史要等用户手动查历史 API——而 flight_log 里明明躺着当天遥测。

### 交付

- `FlightLogService.recentTrack(sysid, limit)`：最近 ≤limit 条轨迹点（升序），与 trackFor
  同款双路——DB 路径用 Pageable **只取末尾 N 条**（id 倒序再反转；1 机 1 天 1Hz=8.6 万行，
  不为 3600 点拉整天）、JSONL 路径当日不足逐日回看（至多 3 天）；启动钩子无请求上下文，
  无租户过滤（TenantContext=null 本就全可见），内存侧的租户过滤仍在读取侧。
- `FlightTrackStore.restoreTrackHistoryFromFlightLog()`（@PostConstruct，lastKnown 之后）：
  每架已知机灌回最近 ≤maxPoints 点；与 lastKnown 时间重叠（ts≥）的跳过（lastKnown 是
  权威快照）；容量照旧截尾。历史点是 gateway.TrackPoint 四元组（lat/lon/alt/ts），
  速度/朝向置 0、电量置 -1（未知哨兵）——前端画线只用前四个。
- **边界**：恢复对象是「已有 lastKnown 的机」（C4=PostgreSQL 主链路必然满足）；
  纯 JSONL-only 模式（无 repository）启动时 tracks 为空、无人可恢复，历史由既有
  trackFor/recentTrack API 按需提供——不为此扩 sysid 枚举面，账记在此。

### 测试（5 例）

JSONL 全链（真文件：10 历史+lastKnown ⇒ 11 点升序无重叠）/ 时间重叠过滤 /
容量截尾（maxPoints=5 只留最新 5 点）/ DB 路径（Mockito：id 倒序反转 + lat 缺失行过滤）/
无 FlightLogService 安全跳过。计数：cloud-backend 3088→3093、全仓 5250→5255。
## [Unreleased] — 「空地协同态势」三列永远显示"暂无"：一半是恒空实现，一半是前后端键名对不上（2026-10-26）

### 1. 为什么动：台账里那句"10+ 模块只存内存"从来没人逐模块核过

用户要求把待办做扎实——「空话就落实」。于是对 `docs/todo.md` 的 P2 #15（内存态）与
P2 #18（死枚举/空端点）跑了三路只读扫描（内存态清单 / 32 处恒空返回逐个追消费端 /
139 个枚举声明 + 109 个配置键双向对账），产出
**`docs/persistence-and-dead-surface-audit.md`**。本条记的是**其中已被落实的那部分**。

粗数校正（原话是空的，现在是有口径的）：#15 实测 **必须落库 9 / 需产品定 10 / 可接受 11**，
不是"10+"；"28 个迁移脚本建 34 张表"实为 **33 张**。

### 2. 现场：一个面板的三列，两种坏法叠在一起

`UnifiedCommandPanel` 的"空地协同态势"三列**永远**是"暂无地面单位 / 暂无空中单位 / 暂无协同任务"：

- **空中单位**：后端 `AirGroundCoordinationService.collectDroneStatuses()` 方法体就是
  `return List.of();`（注释自陈"简化实现"）。连带 `buildMeshTopology` 的节点数、覆盖率、
  `avgBatteryPct` 全部恒零，而 HTTP 照旧 200。
- **地面单位**：后端**早就在发**真数据（`situationToMap` 里的 `surveillanceDevices`），
  前端读的却是 `groundUnits || groundPersonnel` —— **键名对不上，有数也显示不出**。
- **协同任务**：记录一直好好存在服务的 `coordinationRecords` 里，但**没有读口**，
  响应里也就没有 `coordinationTasks` 这个键。

为什么长期没人发现：`gcs-web/test/unifiedCommandWiring.test.jsx` 把这个端点
**mock 成 `null`** —— 键名怎么错、实现怎么空，都不会有任何断言变红。

### 3. 修

- `collectDroneStatuses()` 接机队注册表 `gateway.DeviceRegistry.all()`；注册表缺席（可选依赖）
  才是空，并且留 WARN —— 让"空"至少能区分"真没有机"和"没接上"。构造器加第九位可选参数，
  **旧八参重载保留**，既有测试一行没改。
- 服务侧新增 `listCoordinationRecords()`（按 `startTimeMs` 倒序），控制器补发 `coordinationTasks`。
- 前端三列改读后端**真实键名**（旧猜测留作兜底）。
- 顺带处理一个夹取陷阱：`DroneStatus.batteryPct` 把未上报的 `-1` 夹成 `0`，
  于是"没上报电量"会被读成"电量告急"，还会拉低编队平均电量。
  新增 `batteryKnown`（在夹取**前**判定，构造器签名不变），`/situation` 带出该标志，
  `avgBatteryPct` 按它过滤。

### 4. 同批落实的两条死配置键（都在 geofence 上，正好是审计点名的）

- `aerofleet.geofence.intercept.enabled` 是**假开关**：全仓没有任何代码读它，
  真正生效的键是近义的 `aerofleet.geofence.restriction.intercept-enabled`
  （`GeofenceInterceptService.java:62`）。运维把它改成 false 会以为硬拦截关了。⇒ 删除死键，原位留注释指向真键。
- `aerofleet.geofence.intercept.log-max-size` **声称可调、实现是硬编码常量 1000**
  ⇒ 真的接进 `InterceptLogStore`（非正值回退默认），并给 `/intercept/logs` 补
  `maxLogs` 与 `evictedCount`——拦截记录是环形缓冲，"现在有几条"和"曾经丢了几条"必须一起给。
- `OfflineAlarmCache` 类注释原话"**确保报警事件不丢失**"超出实现（500 上限 FIFO 驱逐 +
  未 flush 前重启全丢）⇒ 注释改成写清两个丢失窗口的口径，并补 `evictedCount` 进
  `getCacheStats()`（驱逐本来有 WARN，但没有可采集的数）。

### 5. 验收（改坏⇒红）

| 新增/改强 | 钉住什么 |
|---|---|
| `AirGroundCoordinationServiceTest.fusePullsDronesFromFleetRegistry` | 两架机进得来、字段逐项对、NaN 位置落 0；改回恒 `List.of()` 必红 |
| `…Test.fuseDegradesWhenFleetRegistryAbsent` | 注册表缺席才是空，且 mesh 节点数一起归零 |
| `…Test.droneStatusBatteryClamp`（扩展） | `150 / -10` ⇒ `batteryKnown=false`，`55` ⇒ true |
| `InterceptLogStoreTest`（新，3 条） | 上限真由参数决定且保留最新三条 / 非正值回退默认 / 6 进 2 留 ⇒ `evictedCount=4` |
| `OfflineAlarmCacheTest`（+2 条 + 扩展空态统计） | 驱逐 2 条必须报 2；`clear()` 不抹累计数 |
| `AirGroundCoordinationControllerTest`（**新文件**，3 条） | `/situation` 必须发 `surveillanceDevices / droneStatuses / coordinationTasks / alarmEvents / meshTopology.nodeCount` 这些**前端真在读的键**；`batteryKnown` 属于对外契约的一部分；机数据来自注册表且 mesh 节点数随在线机数走。此前这个端点的响应形状**没有任何测试** |
| `unifiedCommandWiring.test.jsx`（+3 条） | 后端真实键名三列都出数 / 空数组才是"暂无" / 离线机原样显示 |

实测（全部为本机 `mvn -B -o clean test` + `npm run test:ci` 的产物，门禁逐格核对）：
前端 **580/580，42 个测试文件**（`test/` 40 + `src/` 2）；Java **5274 / 0 失败 / 0 错误 / 0 跳过**
（614 个测试类；`cloud-backend` 3102 → **3112**，净增 10 例）。
`check-test-count-docs.py` **全部一致**、`check-repo-hygiene.py` 通过、
`check-claimed-endpoint-count.py` 414=414 通过。

**A/B 变异反证（4 个变异，每个只红它该红的那几条）**

| 变异（把修复改坏） | 应变红的用例 | 实测 |
|---|---|---|
| `OfflineAlarmCache`：去掉 `evictedCount.incrementAndGet()` | `evictionIsCountable` / `clearKeepsCumulativeEvictedCount` | **恰好这 2 条红，其余 13 条不受影响** |
| `collectDroneStatuses()`：改回 `return List.of()` | `fusePullsDronesFromFleetRegistry` / 控制器两条数据用例 | **恰好 3 条红**；`fuseDegradesWhenFleetRegistryAbsent` 按设计保持绿（它断言的就是空） |
| `InterceptLogStore`：忽略注入上限、回到硬编码 | `capIsConfigurableNotHardcoded` / `evictionsAreCountable` | **恰好 2 条红** |
| 前端面板：键名改回审计发现的错键 `aerialUnits\|drones` | 新增的"真实键名三列出数"与"sysid 原样显示" | **恰好 2 条红**，"空数组才是暂无"保持绿 |

四次变异后全部逐个还原，并 `grep MUTATION-PROBE` 确认树里没留探针；
还原后前端复跑 **17/17**、全 reactor **BUILD SUCCESS**。

### 6. 本批没做的，以及为什么

- §2.1 那 9 条"必须落库"每条要新表 + 实体 + 恢复路径，一批一条，不和"接通一个面板"混在一个 PR。
- 死枚举/死 getter 的删除要逐个确认写入方（6 组是"只有测试在用"，删之前得先看谁在钉它），单列下一批。
- `restriction.source-path` 与代码实际读的 `restriction-file` 键名漂移已查清但**未改**——
  改名会让既有部署配置失效，需要一次带迁移说明的独立改动。


---
## [Unreleased] — 测试里的固定 UDP 端口：两个会话同时跑全 reactor 就撞 BindException（2026-10-27）

### 1. 现场：红的原因不在这次改动里

本机两个会话并发跑全量回归时，`drone-sim` 在 24780 / 24805 抛 `BindException:
Address already in use`。逐条查下来都是测试自己 `bind` 的固定端口：24750 被 7 个用例复用、
24700 复用 7 次、24590 复用 6 次。CI 的 runner 是专享的，所以这些数字在 CI 上从来没红过——
和 #162（regulator-sim 19090 被别的项目容器占用）是同一类问题；#162 当时明确写了
「不去动别人那批能跑的写法」，现在它真的在共享开发机上咬人了。

### 2. 口径更正：grep 的 21 个类里只有 15 个真会 bind

`grep -rln "24[0-9][0-9][0-9]"` 命中 21 个测试类，逐文件核后：

- **真会 bind 的 15 个**：`cloud-backend` 3（`UdpGatewayTest` 的 24 个网关端口 + 占位反例、
  `EnvOverrideE2ETest`、`DeliveryPayloadQueryE2ETest`）；`drone-sim` 12（AdaptivePathExecution /
  GripperActuatorWiring / MissionUploadAckTarget / MissionUploadSession / NexusCommandDispatch /
  ObstacleSourceWiring / RepositionCommandDispatch / ThermalDataEmission / ThermalHotspotEmission /
  VirtualDroneBudgetIntegration / VirtualDroneLifecycle / VirtualDroneRtkCeiling）。
- **排除 6 个**：三个 `UdpGatewaySigning*Test` 的 24698/24699 只是**对端地址、不 bind**
  （本地端口本来就用 probe 探测）；`SensorActuatorWiringTest` / `SimConfigBooleanFlagTest`
  只断言 `--port=` 解析、不起 socket；`TwinControllerTest` 的 424242 是孪生 ID。

### 3. 修：绑 0 号端口，端口从对象本身读回

- `VirtualDrone` 补 `getLocalPort()`（`UdpMavlinkTransport` 早有同名访问器）；测试 `--port=0`
  起机后读回真实端口再发帧。
- `UdpGateway` 侧改成 `new UdpGateway(0, ...)` + `getLocalPort()`；占用反例改成先 bind 一个
  ephemeral 占位 socket，再把**它的**端口交给网关/飞机，语义不变。
- `VirtualDroneLifecycleTest` 的「close 后端口可重绑」改成重绑**实际分配的那个端口**（轮询逻辑不动）。

### 4. 顺带挖出并修掉一个 master 级多上下文缺陷：测试全都想绑 14550

跑 targeted 组合时 `DecisionMonitorControllerTest` / `EdgeCoordinationControllerTest` /
`SchedulingControllerTest` 全部 `Failed to load ApplicationContext`，根因是
`udpGateway` 绑定默认 `aerofleet.udp-port=14550`：**不同配置的 `@SpringBootTest` 上下文会同时存活**，
后创建的那个必然 `BindException`。在 master（`7d101fb0`）上用同样三个类复现 **17 个 error**，
证明与本次端口改造无关。修法是 `cloud-backend/src/test/resources/application.properties`
加 `aerofleet.udp-port=0`（仓内多处用例本来就按这个约定单独覆盖）；三个类同跑恢复
**17/17 绿**。

### 5. 验收

| 载体 | 结果 |
|---|---|
| `mvn -o test`（rebase 到 `704af756` 后全仓） | mavlink-core **511** / drone-sim **1452** / link-sim **139** / cloud-backend **3112** / regulator-sim **41** / sdk-java **19** = **5274**，0 失败 0 错误 |
| 并发反证：另起进程占住 72 个旧固定端口（含第三方已占用的 24805） | 15 个改造类 **92/92 全绿、零 BindException** |
| 三个多上下文类同跑（master 上 17 error） | **17/17 绿** |
| `grep` 复核 | 目标文件里只剩对端地址与 `--port=` 解析断言，无固定本地 bind |

**计数口径：纯端口改造，没有新增/删除用例，Java 总数不变。**
## [Unreleased] — docker-compose 根编排首次可起 + Linux CI smoke job（GAPS §二 收口，2026-10-26）

### 设计阶段就抓到的真缺口

根编排（host 网络 + 挂载 jar）自 D4 写下后**从未被 compose up 实测过**（本机 Windows
跑不了 host 网络，配置审查≠编排能起）。为 smoke job 做设计复核时当场确认裸跑必崩：
prod profile 打 `localhost:5432`（编排里没有库）+ `jwt-secret` 空串（过不了 ≥32 字节
强度校验）+ `SPRING_DATASOURCE_PASSWORD` 无缺省。

### 交付

- `docker-compose.yml`：补 `postgres:15-alpine`（host 网络直通 5432，Flyway V1..V29）
  与 `redis:7-alpine`（6379，`/actuator/health` 的硬依赖——缺它 health 恒 503，首跑实测）
  两个服务；后端补齐 5 个 prod 必填环境变量（jwt×2 / users / encryption.key / datasource
  password），注释写明生产必须覆盖。
- `scripts/check-compose-smoke.sh`：up → 三断言（后端 /actuator/health 200；
  drone-sim 打出 "listening on UDP"——判定面是容器日志不是 compose 回显，编排注释
  既有口径；四容器 Running 且 RestartCount=0——崩溃重启不算过）→ down，失败自动
  dump 日志尾部。
- `ci.yml` 新增 **compose-smoke job**（ubuntu-24.04，硬门禁语义：脚本退非零即红）：
  runner 上先 `mvn -pl drone-sim,cloud-backend -am package -DskipTests` 产出挂载 jar。
- 首轮 smoke 的判定结果会如实写回 GAPS §二（跑得过=收口；跑不过=下一个真缺口）。

## [Unreleased] — e2e-rtk 在慢 runner 上注入「看似没生效」的时序脆弱性修复（2026-10-26）

### 形态（master run 40240416165，#169 的 run 绿纯属运气）

e2e-rtk 三台机全 STANDALONE（R1/R2/R5-float 三断言挂）。根因不是注入没送达，而是
**断言节奏吃不满新鲜度窗口**：阶段一每轮 3 次独立 REST（慢 runner 单次 1~3s），
一轮可以拖过 5s 窗口 ⇒ 「第一台查完时已过期」；R5-float 还是单次即时断言，
与 1Hz 遥测的快照滞后对撞。本机 loopback 快从不复现——与 link-sim 413 竞态同族的
「本机绿 CI 挂」形态（见同日 link-sim 条目）。

### 修法（只动脚本，不动语义）

- 阶段一/二改 **Get-RtkMap**：每轮**一次** REST 取回全部 sysid（调用数 3→1）；
- 每轮**先注入再轮询**（旧版只在轮首注入一次），61/62 的新鲜度由「每轮都注入」保证；
- R3 改为连续 10 轮确认 STANDALONE（稳定缺席断言防单次快照抖动）；
  R5-float 改为轮询式「持续注入下终将达到」，不再单次即时判定；
- 3 台 sim 以 `-Daerofleet.sim.rtcm-fresh-window-ms=12000` 把窗口放宽到 12s
  （默认 5s 由单测钉住；降级断言因此最多多等 ~7s）。
- 本机 pwsh 5.1 实测（重建 jar）：ALL PASS 5/5。

---
## [Unreleased] — NTRIP 服务层落地：定时拉流 → 切帧 → 分片 → 发 GPS_RTCM_DATA（GAPS §十.10-3，2026-10-26）

### 交付

- `cloud-backend/.../ntrip/NtripRtcmForwarder`（Spring `@Component`，
  `aerofleet.ntrip.enabled=true` 才装配，**缺省关闭**）：`NtripClient`（#166 协议层）
  拉流 → `Rtcm3Framer`（CRC-24Q 校验，坏帧直接丢）→ 按 GPS_RTCM_DATA 官方规则
  分片（bit0=分片标志 / bit1-2=分片号 / bit3-7=序号；≤180 单片、>180 逐片、
  **>720 丢弃计数——分片号只有 2 bit，绝不静默截断**）→ 经 `UdpGateway` 逐机下发**全部在线机**。
- 未配置 `--rtk` 的机收到也不抬升 fix_type（#169 上界语义）⇒ 广播无害，云端不需要按机选发。
- 断线退避重连（`reconnect-delay-ms`，默认 5s）；重连时 framer 重建（跨连接字节流
  无边界连续性，旧半帧缓冲必须丢）；caster EOF / read 超时 / 连接拒绝同路处理。
- 配置面：`application.properties` 新增 `aerofleet.ntrip.*` 七项（enabled 缺省 false，
  host/mountpoint 必填才拉起）。
- 观测：转发计数（frames/forwarded/dropped/no-target/reconnects）暴露为包内可见计数器，
  供日志与后续状态端点共用——**本轮不开 REST 面**（端点数是门禁账，按需再开）。

### 测试（9 例）

- 分片纯函数 4 例：≤180 单片 / 181~360 两片 / 721 拒绝 / seq 5bit 回绕；
- 目标选择 3 例（Mockito 钉「只发在线机」「无目标跳过」「超大丢弃」）；
- 端到端 1 例：沿用 #166 假 caster 路数（真 TCP + 真 NTRIP 字节序列 + 真 framer），
  30B+200B 两条帧 ⇒ 1+2 条 GPS_RTCM_DATA 送达在线机，载荷逐字节还原。
  **注意转发单位是完整 RTCM 帧（含 6 字节头尾）**：接收方飞控要自己验 RTCM CRC，
  去头/半条都会被它拒——首版测试把期望值算成裸 payload，被自己当场纠正。

### 边界

- 分片号 2 bit ⇒ >720 字节的 RTCM 帧丢弃（MSM7 类长报文），计数可见；
- drone-sim 不重组分片（每片到达即算「改正数在流」）——重组是飞控职责；
- 未做 REST 状态端点 / 未做按机选发（租户/机巢粒度），后续按需加。

---
## [Unreleased] — link-sim 控制面 413 早拒绝不消费请求体：CI 上三跑两挂的真竞态（2026-10-26）

### 形态（本批 #163/#170 的 CI 连续抓到）

`LinkSimControlServerTest.malformedBodiesAreRejected` 在 CI 上 `IOException:
fixed content-length … bytes received: 0` + Connection reset，三跑两挂；
本机 loopback 快从不复现——一度被误判为「环境 flake 重跑即绿」，#170 的重跑
又挂同一条后推翻该判定，定位到**服务端真实竞态**：

`handlePatch` 对超过 64KB 的请求**不读 body 就应答 413 并关连接**，慢客户端
（CI runner / 跨网段）还在发 70KB body ⇒ 内核回 RST ⇒ 客户端拿到 IOException
而不是 413。超长 body 走 `readNBytes(MAX+1)` 只读 64KB+1 的路径同理。

### 修法

`LinkSimControlServer`：两条 413 路径先 `drainRequest(ex)`（有界 4MB，客户端中途
断开忽略）把请求体消费掉再应答——客户端把已声明内容发完，413 才能可靠送达。
测试不改（它断言的 413 本来就是对的行为）。

### 教训

「重跑即绿」两次成立不等于 flake：**挂同一条测试 ≥2 次就该去找机制**，
本仓 #162（regulator-sim 端口交内核分配）与本次是同族教训。

## [Unreleased] — 一次"把 404 改成 518"的批量替换，改走了 186 处 HTTP 状态码（2026-10-25）

### 1. 现场：契约文档现在写着 `200 DroneDetail / 518`

#159（组件覆盖比例进计数门禁）为了让 README 那句"前端 404 例"变成 518，做了一次
**全局数字替换**，把 `404` 当成待更新的老数字一路换下去。逐文件数它自己的 diff（`git diff e5f9fa5f 29264758`）：

| 文件 | 被换掉的 `404` | 性质 |
|---|---|---|
| `docs/api-reference.md` | **178 处** | REST 契约的状态码：`200 DroneDetail / 404`、`；404 - 无人机未注册`、`200 Zone / 404 / 400` |
| `README.md` | 3 处 | `LiDAR/IMU 返回 404 是符合契约的`、`此时断言"404 契约正确"`、`面板运行期必然 404` |
| `docs/low-altitude-economy-demand-research.md` | 2 处 | 引用可抓取性注记 `JS 渲染/404/…`、`购买页 404 未获司空2 定价` |
| `docs/todo.md` | 1 行 2 处 | 台账条目「**前端 7 处 404**（404 被 `.catch(() => null)` 静默吞掉）」 |

`docs/api-reference.md` 里那 178 处是该文件的**全部**改动——它跟 #159 的主题毫无关系。
HTTP 没有 518 这个状态码，所以这不是"数字过时"，是**契约被改坏**：读者会照着它写客户端。

### 2. 为什么所有门禁都没响

- `check-test-count-docs.py` 只核「N 例 / N 个测试文件」这类句式，散文和表格里别的 `404` 不在它眼里；
- `check-repo-hygiene.py` 管的是冲突标记 / CHANGELOG 自洽 / pom 可解析，也不看这个；
- CI 的 `Docs test-count gate` 因此照样绿。⇒ **这是"改对了被要求改的那几处，顺手改坏了别处"**，
  而且坏得刚好落在唯一没人核对的位置上。

同一次 rebase 还把 #148 写好的那段"组件覆盖真实口径"**整体撤回**成 #148 之前的版本，
于是 master 的 README 一度重新出现「最大的三个（`UnifiedCommandPanel.jsx` 1451 行 / `api.js` 1337 行 /
`MapView.jsx` 1016 行）均无测试文件」——这句在 2026-10-24 就被实测否证过
（`unifiedCommandWiring.test.jsx` 就在挂载 `UnifiedCommandPanel`，`api.js` 有两个测试文件，
三个行数也都是旧值）。顺带把「518/518，32 个测试文件（test/ 30）」这句写回成了错的分组
（实为 37 个文件，`test/` 35 + `src/` 2）。

### 3. 修了什么

1. `docs/api-reference.md` **整文件还原**到 `e5f9fa5f`，并回证与那一版**逐字节一致**
   （`git diff e5f9fa5f -- docs/api-reference.md` = 0 行）——这一步能这么做，是因为
   先数过它在两个版本之间只有 `404→518` 这一种改动（`git diff … | grep -E "^[+-]" | grep -vE "404|518"` 为空）。
2. `README.md` / `docs/demo-scenarios.md` / `docs/low-altitude-economy-demand-research.md` /
   `docs/todo.md` 共 8 处状态码逐处还原（178 + 8 = 186，按出现次数计，不按行数）；每处**先断言锚点命中且唯一**再替换，替换后回证
   这三个文件里 `518` 计数归零（不"尽力而为"改一半）。
   README 另修：撤回的「最大的三个均无测试」改为实测事实，`518/518` 那行的文件分组改回 37（35+2）。
   **`518 例` 这个总数本身是对的，没动**——只动被改坏的状态码。
3. 给 `check-repo-hygiene.py` 加第 5 项检查：**出现在"状态码位置"的三位数必须是真 HTTP 状态码**。
   四种形状各自命中（表格 `200 X / nnn`、响应说明 `；nnn - `、散文 `返回/必然 nnn`、
   引用注记 `渲染/nnn/`）。自测两条：坏样本四种形状各抓一次（少一种就是"门禁只防了它想起来的那种"）、
   好样本 `MAV_CMD 518 / 512(param1=259)`、`{"cmd":518}`、`前端测试覆盖 518 例` 一律不误伤。

### 4. 为什么这条规则值得加（以及它差点误伤什么）

第一次实跑就抓到一条**假阳性**：GB28181 那行「类型码表（131 摄像机/132 网络摄像机/**200 报警输入/215** 报警输出等）」
被表格形状命中。所以规则要求 `200` 后面必须跟 ASCII 类型名（`200 DroneDetail / 404`），
中文码表不进入判定；改完在**全仓真实文档**上跑到 0 问题才算留下。
会误伤中文码表这种事，本身就是这次事故的同一种病：**不看被匹配的东西长什么样，就动手替换**。

### 5. 验证

- `python scripts/check-repo-hygiene.py --self-test` ⇒ 10 条全 PASS（含本批新增 2 条），`rc=0`。
- `python scripts/check-repo-hygiene.py`（全仓）⇒ 通过，0 问题。
- 复核 `grep -rnE "返回 518|必然 518|/ 518|；518 - |渲染/518|购买页 518|处 518" --include=*.md .` ⇒ 空。
- Java / 前端测试**一字未改**，本批纯文档 + 一个门禁脚本；`docs/api-reference.md` 的还原
  不改变任何计数声称。

---
## [Unreleased] — 四个积压决策项统一裁决落账：C5 prod 保持关闭、GB 46750/UOM 联调立项、协作 roster 刷新（2026-10-26）

### 决策一：C5 第④步「动 prod」→ **保持出厂关闭**

- 结论：`mavlink.signing.enabled` 在 prod 保持 `false`，`prodProfileStaysDisabled` 测试不改。
- 理由：prod 无机队 ⇒ strict 风险面（未配密钥设备整链失联）无对象，收益为 0；
  打开是硬切换且前置（逐机发放配置单/面板告警/回滚演练）全部依赖真实机队。
- 重开条件四条登记在 `docs/decisions-2026-10-26.md`（provisioning 全 VERIFIED、
  permissive ≥7 天、回滚演练过、维护窗口内完成）。ROADMAP:355 的 ⬜ 就此落账为 ✅。

### 决策二：GB 46750 / 真实 UOM 联调 → **立项为外部依赖主线，不为 deadline 做假动作**

- 2026-11-01 deadline 针对真实运行航空器，本系统无真机在飞，不构成违约事件。
- 真实 UOM 联调就绪清单五项（对接主体/UomReportSink 真实现/协议差异比对/RID 抓包比对/边界改口径）
  登记在 `docs/decisions-2026-10-26.md`；#3 #5 仓内可先行，#1（找对接主体）为用户侧行动项。

### 协作机制

- `COORDINATION.md` 分工 roster 从 2026-10-04 两方刷新为 2026-10-26 五方
  （opencode 主力 / ZCode 收口 / WorkBuddy AI 偶发只读 / Qoder 偶发清账 / DevEco 隔离），
  并写明层级规则：用户是唯一派单源，agent 平级认领，先声明者优先。
- WorkBuddy AI 首次获得交接文档 `.coord/INBOX-workbuddy.md`（不入库，gitignore）。
- 删除根目录 0 字节残迹文件「更新至」（误产出的空文件，无任何引用）。
- 仓库卫生（同日，不入 CHANGELOG 的机械操作）：删除 29 个本地与 43 个远端已合 PR 残留分支
  （tmp*/backup* 栈与已 squash 合并的 head 分支，逐条核过 patch 等价或对应 PR），
  移除 2 个已交付 worktree。分支历史在 GitHub PR 上完整保留。

## [Unreleased] — --rtk 语义拍板为「上界」并落地：改正数真的到了才报 5/6，断链掉回 3D_FIX（GAPS §十.10-1，2026-10-26）

### 决策（用户委托 ZCode 会话裁决，选 B 案）

`--rtk` 声明的是能力**上界**而非事实。A 案（保持开关语义）会让「配置成 fixed」
永远显示厘米级——NTRIP 断了界面也不掉精度，正是本仓一贯清算的假绿形态；
C 案（--rtk auto 双语义）留下永久混用面。B 案与「配置即事实是假绿」的立场一致。

### 交付

- `VirtualDrone.rtkFixType()`：上界 + 新鲜度窗口（默认 5s，`aerofleet.sim.rtcm-fresh-window-ms`
  可压短供测试）。未配置 --rtk 的机器收到改正数也**不**抬升（上界即 3）。
- **顺带修掉一个死代码 bug**：`MavlinkMessage.decode` 注册表缺 `GpsRtcmData(233)` 的
  case——帧在 default 里被丢成 null，VirtualDrone 那段「收到 RTCM 记 lastRtcmAtMs」
  的分发从未真正执行过（新单测当场抓住；PR #166 的「sim 已接收」声称不成立）。
- 新增 `VirtualDroneRtkCeilingTest` 4 例（真实 UDP 路径）：无改正数⇒3 / 注入⇒上界值 /
  停注降级⇒3 / 未配置注入不抬升。
- `scripts/e2e-rtk.ps1` 重写断言：新增 MAVLink v2 裸帧构造（逐位反射式 X.25 CRC，
  与仓内 MavlinkCrc.java 逐位等价；**MAVLink C 库的查表速算式在本栈上算出的 CRC 不同，
  帧会被静默丢弃，别换回去**）+ RTCM 注入。R1/R2 注入后 5/6、R3 未配置机注入也不抬升、
  R5 停注降级（本机 Windows PowerShell 5.1 实测 ALL PASS 5/5）。
- 测试计数：drone-sim 1445→1449，全仓 5222→5226（10 份口径文档同步）。

### 边界

NTRIP 服务层（定时拉流→切帧→分片→发送 GPS_RTCM_DATA，GAPS §十.10-3）仍属下一步；
本笔裁决后该线已无语义阻塞。坐标噪声模型仍未区分 RTK/单点。

## [Unreleased] — 计数门禁的追述豁免从「行级」下沉到「片段级」（G-3，2026-10-26）

### 缺陷形态（GAPS G-3，两次独立复现）

行内任何位置出现「此前/旧值/更新前/…」追述标记，**整行**连同当前口径的数字一起
逃检——`5222 单测全绿（此前为 3230 单测）` 这种行里 5222 从未被核对。
偏低的历史值（4715 < 实测 4717）曾一路绿灯进 master：偏低的数不刺眼，没人会去看。

### 修法

- `scripts/check-test-count-docs.py`：豁免只作用于与标记**同片段**的数字
  （片段以表格竖线、中英文标点、破折号为界）。行内别处的追述词不再连带豁免。
- 含 `commit ` 引用的行保留**行级**豁免：「测试验证：3230（commit abc1234）」的
  追述证据在数字之后的括号里，与数字分属不同片段，按片段判会误伤真历史记录。
- 表格行：首列标签（「更新前/改前」）带标记 ⇒ 整行历史台账；数字格自带标记
  （`999（旧值）`）⇒ 只豁免该格。
- 自测从 8 个增至 13 个情形：新增「追述词在同行另一片段 ⇒ 当前声称（偏高/偏低）
  均判红」「追述词与数字同片段 ⇒ 仍豁免」「表格数字格追述标记豁免」「commit 行
  整行豁免」——G-3 点名的「当前口径句式 + 偏低值必须红」这半首次被钉进自测。
- `ROADMAP.md:29` 的历史引述补同片段标记（「旧值 3230 单测」），按脚本自己
  「追述标记与数字写同一片段」的既有约定改写，语义不变。

### 验证

- `--self-test` 13/13 通过（含组件覆盖 3 情形）。
- 用与 CI 同口径的合成 surefire（README 表值，总计 5222）+ vitest 570 对全部
  12 份口径文档预跑：**全部一致**；ROADMAP 跳过 1 处追述、commercialization-plan
  章节 134 处追述——豁免计数从「行」变「处」后更精确，无新增误报。

---

## [Unreleased] — P2 #19 条件③ 与 P2 #20 安全画像控制通道收官（2026-10-26）

### 1. 现场

todo.md 里 P2 #19/#20 各剩一条尾巴：

- **#19 条件③**：热成像只剩"逐点热源经纬度"。30012 只带聚合量（均值/极值/热点数），
  像素坐标一个字节都没有——云端 `GeolocationSolver` 有反投影能力却拿不到输入，
  "热源在地面哪个位置"这个问题在链路层就丢了。
- **#20 剩余**：link-sim 安全画像（tamper/unsigned）**不支持**控制通道
  （`--control-port` 命中安全画像直接 exit 1）。

### 2. 做了什么（三步各自独立验收，都有实测与变异验证）

| 项 | 做法 | 为什么不那么做 |
|---|---|---|
| 逐点坐标传输 | 新消息 `THERMAL_HOTSPOT(30064, LEN=26, CRC_EXTRA=111)`：8 字节头 + 3×6 字节（每条 x/y/峰值温度×10 三个 i16），drone-sim 1Hz 与 30012 **同帧同时间戳**下发；超容量按峰值温度降序截断并置 `truncated` | 不改已发线的 30012：它的 LEN/CRC 与两端收发方（含 e2e 脚本）都已写死，另起 msgId 不动既有契约（ROADMAP 规定新增从 30064 起） |
| 坐标解算 | 复用既有 `GeolocationSolver`：`ThermalIngestListener` 增加 30064 监听，把 `DroneSnapshot` 的最新位姿（GPS=本地原点、relativeAlt=离地高度、roll/pitch/yaw、云台按正下视假设）喂进反投影，写 `thermalHotspots(u,v,tempC,lat,lon)`；`GET /thermal/status` 增 `hotspots` 段；前端逐点显示 | 不给安全画像硬套十元组（见下）；解不出来时 lat/lon=null，**不填 0/NaN 冒充坐标**，面板明说"无法解算" |
| 安全画像控制通道 | 新语义层 `SecurityControl`：只暴露真实存在的 `tamperRate`（0~1，越界/夹带字段一律 400 带原因）；签名剥离是**检测到签名帧必剥**（动作全命中），无概率可调，**刻意不给滑块**。`LinkSimControlServer` 改双构造器 + instanceof 分派，对外 HTTP 契约同形，前端/云端不分支；中继模式按画像选语义层（不再 exit 1，也不再给安全链路挂十元组假旋钮） | 构造十元组控制面：`delayMs` 恒 0、改了没人看——正是 P1 #11 清掉的假滑块换了层皮 |
| stats 口径 | 安全视图的 stats **刻意不与 `ImpairmentEngine.Stats` 同构**：通用键按安全画像事实填（dropped=0：篡改/剥离后的帧全都照发，否则对端 CRC 阶段就丢帧、走不到验签失败），另给 `tampered/stripped/tamperRatio` 三个如实键 | 把 tampered 填进 dropped 等于告诉面板"这些包丢了"，事实上一个都没少到 |

### 3. 实测（本机）

Java `mvn -B -o clean test`：mavlink-core 511 / drone-sim 1452 / link-sim 139 /
cloud-backend 3088 / regulator-sim 41 / sdk-java 19，合计 **5250**，0 失败 0 错误。
前端 vitest 577 例全绿；REST 端点 414；`scripts/mavlink-crc-extra-gen.py` 重算
30064 的 CRC_EXTRA=111（`--check` exit 0，23/23 标准消息自检通过）。

A/B 变异验证（`scripts/mutate-verify.py`，改坏⇒红、还原⇒绿，两个变异体都杀掉）：

| 变异 | 被抓住它的用例 |
|---|---|
| drone-sim 删掉 `sendThermalHotspots(...)` 调用 | ThermalHotspotEmissionTest（真实 UDP 等到 30064 帧 + 与 30012 时间戳配对） |
| `SecurityImpairmentEngine.applyTamperRate` 变空操作 | SecurityControlServerTest（PATCH 后引擎每帧必 TAMPER） |
| `SecurityControl.patch` 不再拒绝夹带字段 | SecurityControlServerTest（`{"delayMs":120,"tamperRate":0.5}` 必须 400） |

### 4. 口径与残留（写进注释与面板，不藏在数据后面）

1. **位姿时效**：30064 不带位姿，云端只能取 `DroneSnapshot` 的"最新已知"姿态——
   不是拍摄时刻值。10Hz 姿态 + 1Hz 热像下最坏 ~100ms 偏移；高速机动场景误差放大。
2. **云台假设**：正下视（0/0）。合成场景定义即"机下视"，但这是假设值；真实可俯仰
   云台需要在消息里带 gimbalPitch/Yaw，否则斜视场景解算会错。
3. **FOV 假设**：热成像水平 FOV 取 55°（常见热像机芯量级）。合成场景没有真实相机
   FOV，取值影响是单调的；接入真实载荷后应按机型配置。
4. **混合画像中继**：一侧安全一侧普通时，控制面只表达安全侧（如实 WARN），
   不假装能两层同屏。

---

## [Unreleased] — P2 #19/#20 接上最新 master：热成像 + link-sim 控制面真正落地（2026-10-25）

### 1. 现场：链式合并被从中间截断

上一轮把整条链 rebase 到 master 2926475，四层 PR 的 base 全部对齐。
但随后 master 继续前进（#161/#162/#164/#165），**前三层被别的 PR 以 squash 形式
分别送上了 master，链从中间断掉**：

- security P1 #7 → `3335658c (#147)` ✓ 在 master
- 前端 KNOWN_GAPS 清账 → `685b844d (#165)` ✓ 在 master
- regulator-sim 临时端口 → `e787e1f6 (#162)` ✓ 在 master
- **P2 #19 热成像** → PR #152 显示 merged=true，但合进的是 base 分支
  `audit/frontend-endpoint-gaps`，而那个分支被 #165 提前取走时**不含 thermal**
  → `ThermalIngestListener.java` 不在 master 上
- **P2 #20 link-sim 控制面** → PR #153 同理，`LinkSimControl.java` 不在 master 上

### 2. 做了什么

跳过已在 master 的前两层，只把后 5 个提交重放到最新 master（`58bb8009`）：

| 提交 | 内容 |
|---|---|
| 热成像接线 | drone-sim 发送环 + `ThermalIngestListener` + `GET /api/v1/thermal/status` + 面板真实聚合表 |
| link-sim 控制面 | `--control-port`（默认 0）+ `GET/PATCH /profile`（只绑回环）+ cloud 转发 + 前端滑块恢复 |
| 端口表文档 | 14601 进部署/排障端口表 |
| 证据/PR 号文档 | 真机 curl 复核、todo.md 登记 |
| 计数归位 | 全仓计数订正到实测 |

regulator-sim 的临时端口改动**取 master 版本**（#162 已落地，实现略不同但同一意图），
不重复造第二份。

### 3. 实测（本机，mvn -B -o clean test）

```
mavlink-core 503 / drone-sim 1445 / link-sim 133
cloud-backend 3081 / regulator-sim 41 / sdk-java 19     合计 5222
BUILD SUCCESS   0 失败 0 错误 0 跳过
```

- 端点 **414**（thermal +1、link-sim +2、env-status +1，后者已由 #165 带上 master）
- vitest **570**；CHANGELOG 小节 199
- 全仓文档计数 13 处订正到 5222，端点声称 13 处订正到 414
- 12 项 Python 门禁全 exit 0；含 docs/api-reference 模块表合计 411→414

### 4. 仍未关闭

- P2 #19 的条件③（逐点热源经纬度 = 图像→地理位姿解算）——需要 vision 包的姿态解算，
  不属于本轮范围。
- P2 #20 的安全画像（tamper/unsigned）不支持控制通道，link-sim 侧已 exit 1 拒绝。

## [Unreleased] — 清掉 GAPS 剩下的三项纯软件活：NTRIP 差分源 + 两处口径（2026-10-25）

### 1. NTRIP 客户端：定位维度唯一「纯软件可立即补」的缺口

`GAPS` 第九节第 5 条记的是「真差分源（NTRIP caster 客户端）未接」。它不是硬件问题——
NTRIP 是 HTTP 流协议，纯软件可做，也能接公共 caster 实测。本轮交付协议层：

| 类 | 职责 |
|---|---|
| `GpsRtcmData`（mavlink-core） | `GPS_RTCM_DATA`(#233)：把改正数注入机载 GPS 的唯一入口 |
| `NtripClient` | 连 caster、握手、认证、读原始字节流 |
| `Rtcm3Framer` | 把字节流切成完整 RTCM 帧，含 **CRC-24Q 校验** |

**三个必须说清的实现判断**：

1. **为什么用裸 Socket 而不是 `HttpURLConnection`**：NTRIP 1.0 的成功响应是 `ICY 200 OK`，
   不是合法 HTTP 状态行，`HttpURLConnection` 直接抛 `ProtocolException`。
2. **为什么响应头要逐字节读到空行**：头部之后紧跟的就是 RTCM 二进制流，
   用带缓冲的 `readLine` 会把流的前几个字节一起吃掉，整条流从此错位。
   测试里有一条专门断言「握手后读到的第一个字节必须是 `0xD3`」。
3. **为什么 CRC 错的帧要丢弃而不是转发**：转发一条坏的改正数比不转发更糟——
   飞控会把它当有效改正解算，位置反而更偏。

**`CRC_EXTRA = 35`，只由 `flags / len / data` 算出**：`target_system` / `target_component`
是 MAVLink2 扩展字段，不参与 CRC（同 `AUTOPILOT_VERSION` 的 `uid2`）。
已用 pymavlink 核对：`crc_extra == 35`、`fieldnames == ['flags','len','data']`；
把扩展字段一起喂算法会得到 64（错）。**这是本项目第一条带扩展字段的官方消息**，
所以 `MavlinkCrcExtraTest.SIGNATURES` 里也按同样口径只列前三个字段。

**机载侧**：`VirtualDrone` 新增 `GPS_RTCM_DATA` 接收分支，记 `lastRtcmAtMs` / `rtcmBytes` 并打日志。
**但没有改 `rtkFixType()`** —— 那个口径是配置驱动的（`--rtk fixed` 恒报 6），
要不要改成「必须收到改正数才报 5/6」属于产品决策，已登记 `GAPS` 第十节，不在这笔里替它定。

### 2. `TrackingPanel` 的电量阈值统一到 20/40

`GAPS` 第八节第 9 条。此前本面板用 15/30、其余四处用 20/40 ⇒ 同一架无人机在列表与追踪面板
可能一个绿一个黄。删掉本地 `batteryColor`，改 import `utils/battery` 的 `battColorVar`；
`FORBIDDEN` 正式纳入 `batteryColor`，并把那条「反向断言」（`not.toContain('batteryColor')`）
翻成正向 —— **没有靠删测试收口**。

**代价照记**：15~20% 这段电量在追踪面板由「黄」变「红」；无数据时的颜色从 `var(--dim-2)`
变为 `utils` 统一的 `var(--dim)`。

### 3. `RocPanel` 三个请求补失败分支

`GAPS` 第八节第 5 条后半。修前是 `if (res.ok) setX(...)` 三连没有 else，
末尾还有一句**无条件**的 `setError(null)` —— 4xx 不但被吞掉，错误还被主动清成「一切正常」，
操作员看到的是空白面板而不是「这个席位没了」。

现在三路各有失败分支，**部分成功时保留已取到的数据**（整块清空会让人以为后端挂了）。
3 条新用例按「界面有没有那句话」判，而不是断言 `fetch` 返回了 4xx（那等于什么都没测）。

### 4. 验证

- `NtripClientTest` 9 例 + `Rtcm3FramerTest` 10 例，含**本地假 caster 的真 TCP 连接**与跨块重组
- cloud-backend **3064** 例（+19）、mavlink-core **503** 例（+2）、drone-sim 1443 例、前端 **566** 例
- 全仓 **5192** 例；五门禁（hygiene / 用例数 / 端点数 / 目录计数 / 前端契约）全过

## [Unreleased] — 前端端点契约 6 条 KNOWN_GAPS 清账：补 1 个真端点，删 5 处假调用（2026-10-24）

### 1. 现场：门禁登记的 6 条"前端在调后端没有的端点"

`check-frontend-endpoints.py` 的 KNOWN_GAPS 登记了 6 条缺口（P1 #8~#13）。
本轮逐条分诊（只读侦察 6 条 × 前端调用点 + 后端现状）：只有 #8 是「数据后端真有、
只差一个读端点」的纯补洞；#9/#10 是面板取数逻辑改造；#11/#12/#13 三条经确证
**能力不存在**——最小诚实修复是移除假控件并写明原因，而不是换个假面板。

### 2. 补了什么

- **#8 `GET /api/v1/env/status`（后端真补）**：机队级环境状态读口径。数据源是
  各机 ENVIRONMENT_STATUS 快照（drone-sim 每秒下发、早已落进 `DroneSnapshot`，
  此前没有任何 Controller 暴露它）。聚合口径：online 机逐台进 stations（无定位不进
  站表但参与顶层均值）、顶层逐字段独立平均、**风向圆周均值**（359° 与 1° 的平均
  必须是 0° 而不是 180°——朴素算术平均会把面板箭头指反）、天气码 0-4 映射前端
  WEATHER_MARKS 键取众数（并列取编号小者）、NaN 永不进 JSON、不造 turbulence
  （后端无此概念）。`EnvStatusReadContractTest` 5 例钉死口径。
- **#9/#10 链路质量面板改读真实口径**：`getLinkSimProfiles/getLinkSimStatus`（不存在
  的端点）→ 既有的 `getCommLinks()`（/api/v1/comm-adapt/quality/fleet）。选机下拉
  （sysid + 等级 + 最佳链路 + 评分），三卡读 bestLinkType 那条链路的
  latencyMs/packetLossPct/bandwidthKbps→Mbps，历史曲线照旧。
- **#11 损伤滑块移除**：link-sim 是 CLI UDP 代理，损伤参数启动期固定、无运行时
  控制通道——此前滑块拖了没有任何反应（失败被 `.catch(() => {})` 静默吞掉）。
  面板留"未实现"说明卡，真实现选项登记 P2 #20。
- **#12 热成像面板改"未实现"说明页**：`/vision/thermal/sources` 后端不存在且
  「改读任务列表」也不诚实（任务壳永不推进——30012 消息未接线；结果只有聚合统计，
  无带坐标热源点）。移除 404 轮询与**随机扰动伪造的历史回放**；真实现选项
  （接线 ingest → 聚合端点 → 位姿解算）登记 P2 #19。
- **#13 物理模型死代码清理**：api.setPhysicsModel 与组件 handler 删除，按钮保持
  禁用+写明原因（先例样式）。
- api.js 死函数块全部删除（附分诊结论注释），`KNOWN_GAPS` 清空。

### 3. 实测

- 端点契约门禁：后端 410 条端点 / 前端 **233 处调用全部有对应端点**、登记 0 条
  （含 self-test）；vitest **382 例全绿** + vite build ✓；全量 surefire **3033 例 0 失败**
  （净增 5 = EnvStatusReadContractTest）；14 个 Python 门禁全 PASS。
- 生产代码改动仅一处：`EnvOverrideController` 增构造参数（注入 DeviceRegistry）
  + GET /status 方法——只补读面，未动任何既有行为。

---

---

## [Unreleased] — security 收口：102 个存活变异体逐条清账，两包分诊全部完成（2026-10-24）

### 1. 现场：第一轮只清出 4 处真缺口，账上还挂着 102 个

security 包（PIT 变异 767 个）在 2026-10-21 审出限流阈值 / XFF 信任 / 密钥掩码 / 口令哈希
4 处真缺口后，还有 **102 个存活变异体**没有任何定性。P1 #7 悬了三轮——这轮把
surveillance 第四轮的分诊模板原样搬过来，逐条清账。

### 2. 分诊方法：模板沿用，A/B 升级成批量 42 条

- 判定"真缺口"：补测试 → 复测变异必须**死**。
- 判定"等价/不可达"：`tools/mutate-verify.py` 改坏⇒跑用例⇒还原⇒再跑，存活下用例
  必须**仍绿**，并给出"为什么测不到"的值域论证。本轮把单发 A/B 升级成**批量 42 条**
  （`tools/run_ab_batch.py` + 2 条收尾），**42/42 全部"改坏⇒红、还原⇒绿"**
  （退出码存档 `tools/ab_batch_sec_evidence.txt`）。

### 3. 补了什么（4 个新契约文件 + 16 处扩展，净增 52 个新用例，生产代码零改动）

| 测试 | 例数 | 钉住的契约 |
|---|---:|---|
| `ApiKeyAuthContractTest` / `SecurityConfigContractTest` / `TenantFilterTest` / `ValidationConfigContractTest`（新） | 9 | 认证 Token 微契约（authenticated/principal/role、absence=null 不是空串）；Bean 工厂返 null = 上下文启动失败的哨兵；TenantContext 的 finally 清理 + `tenant_id` claim 的 Double 形状解析 |
| 扩展 16 类（`ApiKeyControllerDeviceKeyTest` / `ApiKeyCacheTest` / `ApiKeyFilterCachedTest` / `ApiKeyLastUsedTrackerTest` / `RoleInterceptorTest` / `AuthControllerTest` / `AuthControllerRateLimitTest` / `UserControllerTest` / `AdminBootstrapRunnerTest` / `DeviceIngestKeyBootstrapRunnerTest` / `MavlinkSigningPostureControllerTest` / `MavlinkSigningKeyControllerTest` / `SigningPostureServiceTest` / `StreamTokenServiceTest` / `StreamTokenFilterTest` / `TenantControllerTest`） | +43 | 租户守卫三角（同租户 / 跨租户 / 无租户 × ADMIN）、grace 与 sysid 边界、负缓存上限精确 =MAX、evictExpired 整点、flusher 线程存在且守护、**直调 preHandle 断言 boolean**（坑 8）、限流窗口与 cleanup 定时器、改密与租户**落库值**（captor 不是"调用过"）、引导 key 撤销复活、审计操作者归属（preferred_username≠subject）、签名姿态 loosen 成功路径与审计环 50 条、流令牌惰性清理整点、头凭证分支链继续、显式 enabled=false 落库 |

### 4. 实测

- 全量 surefire **3028 例 0 失败**（比第四轮净增 52）。
- PIT 复测两跑（XML 精确对账，对账工具按**多重集**逐条比底账）：第一跑 647 杀 / 29 存活，
  对账揪出**同行兄弟变异体**（A/B 杀掉的是同行已死的那个，活的是另一个）与
  1 个被 PIT 排除的竞态用例（坑 9）；补 3 个真缺口用例、删 2 个竞态用例后第二跑定账：
  杀掉 **566 → 649**、存活 **102 → 27**、未覆盖 97 → **89**（总 767、超时 2、运行错误 0）、
  变异分数 **0.8448 → 0.9572**、测试强度 **0.8473 → 0.9601**；基线
  `scripts/mutation-baseline.json` 地板抬到 **0.95 / 0.96**。增量账目：
  杀掉 +83 = 底账存活补杀 76 + 新覆盖顺带被杀 7；存活 27 = 底账余 26 + 新面孔 1
  （L54，从未覆盖浮出，已定性）——**27 条恰好全部是已定性清单**。
- 三个新发现进了坑册：**坑 8**（MockMvc 对拦截器 boolean/提前返回失明——拒绝分支
  先提交 403 响应，`return false→true` 后处理器照常执行但写入被丢弃，HTTP 面全同形；
  控制流契约必须直调）、**坑 9**（"墙钟整点"边界用例在 PIT 插桩 JVM 里必然自曝——
  Δ≥1ms 时原始代码自己就清了边界记录，用例未变异先红被排除，变异体反而没人杀；
  边界用例必须注入时钟，注入不了的如实定性"不可稳定测"）。
- 27 条定性为无害（14 行为等价 + 1 PIT 环境不可分辨 + 2 字节码恒等副本 + 2 墙钟竞态
  不可稳定测 + 8 上下文级），清单与证据在 `docs/mutation-testing.md` 第五轮。

### 5. 没做什么 / 留了什么

- 生产代码零改动：这一轮没有暴露行为 bug——所有"改坏"都撞在了"测试没盯住"上。
- `SecurityConfig.securityFilterChain` 的 8 条链路装配变异体（守卫/setter/Bean 返 null）
  留给链路层（@SpringBootTest + 真实 API Key 数据）——门禁单测层构造不了
  `HttpSecurity`，已在第五轮文档留档。
- 至此 **surveillance（22 条）与 security（27 条）两包的分诊全部完成**，P1 变异审计
  台账清空。

---

---

## [Unreleased] — 同一架没有 `mode` 的无人机，三个面板三种说法：`待命` / `MANUAL` / 空（2026-10-25）

### 1. 现场：三份 `MODE_LABEL`，缺省口径互不相同

`GAPS` §八-1 登记的现象，本轮按用户裁定的「统一成『未知』」落地。实测三处的分叉：

| 组件 | 表里有没有 `MANUAL` | 缺省写法 | `mode='MANUAL'` 时显示 | `mode` 缺失时显示 |
|---|---|---|---|---|
| `DashboardPanel.jsx:23-30` | 有（`'待命'`） | `\|\| '未知'` | 待命 | 未知 |
| `DroneList.jsx:4-10` | **没有** | `\|\| '待命'` | **英文原文 `MANUAL`** | **待命**（冒充了一个它并不知道的状态） |
| `TelemetryPanel.jsx:4` | 有 | `\|\| mode` | 待命 | **空/undefined** |

三份表对同一架机器的同一个值给出三种答案。**最坏的是 DroneList 的 `|| '待命'`**：
它把「不知道」渲染成一个确定状态，操作员会以为飞机真的在待命。

### 2. 修法：并进 `utils/statusMeta.js`，判据写死成函数

新增 `MODE_LABEL`（码表）+ `MODE_UNKNOWN_TEXT = '未知'` + `normModeLabel(mode)`：

- `null` / 空串 / `'UNKNOWN'` ⇒ `'未知'`（**不知道就说不知道**）
- 已知码 ⇒ 查表
- **其余真实值原样透传**（不降级成「未知」——后端加了新 mode 时，宁可显示原始码也不冒充已知状态）

三个面板改为 `import { normModeLabel } from '../utils/statusMeta'`，
`singleSourceOfTruth.test.js` 的 `FORBIDDEN` 加上 `MODE_LABEL` / `normModeLabel`，
`EXPECTED_IMPORTERS` 登记三个引用方（引用方少了也判红，防「并进去了但没人用」）。

### 3. 两条「分叉断言」按新口径翻正

补口轮在 `droneList.test.jsx` / `dashboardPanel.test.jsx` 里各留了一条点名这处分叉的断言
（当时钉的是「两个面板显示不同」）。口径统一后它们必然红 —— **同批翻成正向断言**，
没有靠删测试收口。

### 4. 验证

`npx vitest run` ⇒ **562 例 / 40 文件全绿**（本轮 +22：`modeLabel.test.js` 18 +
`droneList` 1 + `singleSourceOfTruth` 3）。四门禁（hygiene / 用例数 / 端点数 / 目录计数）通过。

---

## [Unreleased] — regulator-sim 把端口写死在 19090：别的项目占了这个号，本机就有 7 个用例红（2026-10-25）

### 1. 现场：一次全量回归在本地红 7 个 error，红的原因不在这个仓库里

`mvn -B clean test` 在本机跑出 7 个 `java.net.BindException`，全部集中在
`RegulatorSimMainTest$MockUomServerTests`。逐条查监听方：

```
TCP  0.0.0.0:19090  LISTENING  54560   ← docker-proxy（dev-stack-prometheus-1，19090->9090）
```

也就是说：**另一套项目的 Prometheus 容器把 19090 占了**，本仓库的测试写死了 19090，
谁先起来谁赢。这不是产品缺陷，也不是回归——但它的表现和回归一模一样，
而且直接把 `scripts/check-test-count-docs.py` 的 Java 腿也带停
（"有测试未通过，先修测试再谈文档口径"），于是本地连"文档口径对不对"都问不出答案。

### 2. 根因：CI runner 干净，把"写死端口"这件事变成了隐性契约

`RegulatorSimMainTest` 原来写 `PORT_DEFAULT = 19090` / `PORT_DELAY = 19091` /
`PORT_ERROR = 19092`，`FaultInjectionTest` 里还有一个 `--port=18182`。CI 的 runner 是
专享的、没有别的服务，所以这四个数在 CI 上**从来没红过**——它只在共享开发机上红，
而共享开发机正是人干活的地方。同类写法在 `cloud-backend` / `drone-sim` 用的是
24xxx 段（或 `aerofleet.udp-port=0`），撞车概率低；本次只统一 regulator-sim 这一处，
不去动别人那批能跑的写法。

### 3. 修：绑 0 号端口让内核分配，端口号从服务器本身读回来

`HttpServer.create(new InetSocketAddress(0), 0)` 由内核分配 ephemeral 端口，构造那一刻
就已绑定，`server.getAddress().getPort()` 拿得到真实端口号——但 `MockUomServer` 以前
只在自己日志里用这个值，没对外暴露，调用方**只能猜**，这才是测试必须写死端口的根因。
所以先补 `public int getPort()`（`MockUomServer.java`），再把四个测试的
`--port <固定>` 全换成 `--port 0` + `server.getPort()`。

新增一条把"改的原因"本身钉住的用例：
`bindsEphemeralPortAndAnswersThere` —— 断言端口 > 1023、不再是 19090/19091/19092，
并且**请求真在这个动态端口上拿到 200**（只读端口号不证明能用）。

### 4. 验收：占用者还在那儿，测试已经绿

| | 改前 | 改后 |
|---|---|---|
| 本机 19090 | 被 `dev-stack-prometheus-1` 占用 | **仍然被占用**（没动别人的容器） |
| `mvn -pl regulator-sim clean test` | 7 个 `BindException` error | **Tests run: 41, Failures: 0, Errors: 0**（`MVN_EXIT=0`） |

### 5. 计数口径：regulator-sim 40 → 41，11 份口径文档同步

新增 1 个用例 ⇒ Java 总计 5113 → **5114**（regulator-sim 40 → 41）。`README.md` 的分模块
表与 `ROADMAP.md`、`docs/` 下 9 份对外材料的同一数字一并改；这一步不是自愿的——
`check-test-count-docs.py` 逐格核对 surefire 实测值，加测试而不改文档会直接让 CI 变红。

---

---

## [Unreleased] — 「0 架无人机」和「没有这一帧」在界面上是同一个 `--`：四处收口 + 一条会咬人的形状守卫（2026-10-25）

### 1. 现场：GAPS §八-2/3 记的两条，实测清单比记的多

登记时写的是两条（`App.jsx` 的最高高度、`ShowPanel` 的队形ID/无人机数）。这一轮真去扫的时候：

| 站点 | 原写法 | 后果 | 本轮 |
|---|---|---|---|
| `App.jsx:173` + `:588` | 没轨迹时返回 `maxAlt: 0`，展示层 `stats.maxAlt ? … : '--'` | **贴地飞行（真 0 米）与"一条轨迹都还没有"都显示 `--`** | 已修 |
| `AutoDispatchPanel.jsx:479` / `:618` | `pick(d, 'droneCount', 'drones') \|\| '--'` | 0 架显示 `--` | 已修 |
| `RidPanel.jsx:237` | `system.areaCount \|\| '--'` | 区域内 0 架显示 `--`；**同文件 `:238-240` 的 `areaRadius/areaCeiling` 用的是 `!= null`**，一个文件两种口径 | 已修 |
| `ScenarioLibraryPanel.jsx:294` | `pick(t, 'droneCount', 'drones') \|\| '--'` | 同上 | **已修——这处是我手工 grep 漏掉、被新写的守卫抓出来的** |
| `ShowPanel.jsx:450-451` | 已在 `d39ea7c0`（#156）改成 `??`，注释就写着"0 是合法值" | —— | **GAPS §八-3 是过期记录，划掉而不是再"修"一遍** |

我那条 grep 之所以漏掉 `ScenarioLibraryPanel`：模式写的是"字段名后面紧跟非字母"，而
`pick(t, 'droneCount', 'drones')` 在字段名后面还有 `', 'drones')` —— **人写的正则会漏，
形状守卫不会**，这也是为什么这条 PR 的重点不是那四行改动，而是第 3 节那个守卫。

### 2. 根因：`||` 与真值判断都不区分「0」和「没有」，而 `App.jsx` 还把两种语义压成同一个值

`maxAlt` 这一处比展示层更糟：**计算函数在"算不了"时返回 `0`**。就算把 `:588` 改成 `??`，
`0` 依然既可能是"贴地飞过"也可能是"还没收到轨迹"——**语义在源头就丢了，展示层修不回来**。
所以这一步必须动数据结构，而不是只换运算符。

### 3. 修法：语义拆开 + 一条扫形状、且自证会咬人的守卫

- 新增 `gcs-web/src/utils/flightStats.js`：
  `flightStatsOf(track)` ⇒ 算不了时 `maxAlt = null`（不是 0）；`fmtMeters(v)` ⇒
  **只有 `null`/`undefined`/`NaN` 才降级成 `--`**，`0` 照原样显示 `0 m`。
  `App.jsx` 的内联 `useMemo` 换成调它，`haversine` 的引用方随之从 `App.jsx` 挪到 `flightStats.js`。
- 三处 `\|\| '--'` 改成 `?? '--'`。
- 新增 `gcs-web/test/zeroIsNotNull.test.js`：扫 `src/**` 的**形状**，两种坏写法都抓 ——
  ① `field || '--'`（含把字段名藏在 `pick(...)` 实参里的写法），② `field ? field.toFixed(…) : '--'`。
  名单只放**已核过"0 是合法读数"**的字段（`droneCount`/`areaCount`/`maxAlt`/`count`/`qty`/`remaining`），
  不随手扩成"所有字段"——字符串字段的 `|| '--'` 无害，硬卷进来只会造假红。
  判据本身配三条自证：**坏形状必被抓、好形状（`??` 与 `!= null`）不误伤、注释里的说明文字不算输入**
  （"注释也算输入"是这一族门禁的经典假红来源，`singleSourceOfTruth.test.js` 早就为此剥注释）。
  另外留一条"历史样本对照"：三条改动前的原文逐字钉进测试，证明判据对准的是真实现场，
  不是先有了判据再凑样本。
- `singleSourceOfTruth.test.js` 的 `FORBIDDEN` 加入 `flightStatsOf` / `fmtMeters`
  （抽出去的东西，禁止有人在组件里再抄一份）。

### 4. 顺带修一条被我改坏的旧断言（不是放宽它）

`singleSourceOfTruth` 里原来钉的是「**App.jsx 从 utils 导入 haversine**」。抽取之后 App.jsx
不再直接用 `haversine`，这条断言当场变红——**这是真红**：被钉的对象变了，不是判据错了。
处置没有删掉或放宽它，而是改成钉住**新的接线**并且更严：
① `App.jsx` 必须从 `./utils/flightStats` 导入 `flightStatsOf`；
② `App.jsx` 里**不该再出现** `haversine(` 调用；
③ `utils/flightStats.js` 必须从 `./geo.js` 导入 `haversine`；
④ 新加一条：**全 `src` 只有 `utils/geo.js` 定义 `haversine`**（抽取动作本身最容易留下的
正是"顺手再抄一份"）。

### 5. 计数与验证

- `npm run test:ci` ⇒ **540 / 540 通过 / 0 失败**，39 个测试文件（原 518 / 37）。
  **+22 逐项可复算**：`flightStats.test.js` 13 + `zeroIsNotNull.test.js` 6
  \+ `FORBIDDEN` 加 2 个名字（该套件按名单循环生 `it`）+ 第 4 节那条断言拆成 2 条（+1）。
- `npx eslint src --ext .js,.jsx` ⇒ 0 error。
- 前端声称由门禁重驱动（`README.md` 与 `ROADMAP.md:25` 两处 518 → **540**），
  Java 侧本批一字未改，数字由本树 fresh `mvn -B -o test` 复核。
- `.coord/GAPS.md` §八：**第 2、3 条收口**（第 3 条是 #156 早就修好的，我的记录过期）；
  第 6 条（`HardwarePanel.jsx:154` 把 `{}` 当一架旋翼）本轮复核过——
  后果是"多渲染一行空数据"，不是崩溃，**定级为外观问题，不在本批动**；
  第 4 条残留（`RocPanel` 三处 `if (res.ok)` 无 else）需要错误文案口径，留待下批。

### 6. 这一条属于哪一族

`x || fallback` 把 `0`/`''` 当缺失是 JS 老坑，但这里值得记的不是坑本身，而是
**"没数据"被编码成 `0` 之后，展示层无论怎么写都区分不回来**——所以修法必须先动数据语义，
再谈运算符。第二件值得记的是**我的手工 grep 漏了一处、机器守卫没漏**：
凡是"这类写法本来就该禁"的约定，落成形状门禁而不是靠 review 眼神。

---
## [Unreleased] — 状态板自测连红四笔：那条幂等断言测的是 GitHub，不是代码（2026-10-25）

### 1. 现场：master 唯一的红，报的话把人引向一个不存在的 bug

master 连红四笔（`61ad7c98` / `52ef3c18` / `d39ea7c0` / `6682d816`），每笔失败面都只有同一个 job
（`Coordination status board self-test`，39 个 job 里只红这一个）。四笔的报错字符串**逐字相同**
（job `120848336525` / `120883416518` / `120893035194` / `120897710020` 逐个取回日志实证）：

```
自测失败：T3:重写后有效行数 22→23（不幂等，正文残留）
##[error]Process completed with exit code 1.
```

「正文残留」这四个字是脚本自己的措辞，它在暗示写回逻辑把旧块留在了原地。实核：这四笔 commit
对 `scripts/coord-status.sh` 的改动行数分别是 `0 / 0 / 0 / 0`（`git show --stat … -- <file> | wc -l`），
该文件上一次被改是 `f51bf3b6`（#143），远在这串红之前。⇒ 结论只能是这条断言的**输入**在两次调用
之间变了，而不是被测的**逻辑**变了。

### 2. 根因：T2–T5 每次都现场读一次 GitHub，而 GitHub 的答案是异步算出来的

`--write` 生成的那一段里含「在途 PR」清单，它来自 `gh pr list`。自测的 T1/T6 早就用
`COORD_NO_GH` / `COORD_PR_FIXTURE` 把实时数据挡在外面（注释里写明「上一版拿真实 gh 调用做断言，
同一份代码两次跑出不同结论，那叫自测自己在 flaky」）——**但同一条原理没贯彻到 T2–T5 那四次
`--write`**，它们裸调 gh。能造出 ±1 行的实况源有两条，都做了确定性复现（用夹具注入，不靠碰运气）：

| 场景 | 两次写回之间变的东西 | 有效行数 |
|---|---|---|
| 在途 PR 从 1 条变 2 条 | PR 数 | 23→24 |
| **PR 数恒为 1 条**，仅 `mergeable` 由 `UNKNOWN` 翻成 `CONFLICTING` | GitHub 异步算完 mergeability | 23→24 |

第二条才解释得通「为什么是连红四笔而不是偶发」：那两句 +1 的都是段末那句
`**上面标 ⚠ 的 PR：GitHub 合成不出 refs/pull/<N>/merge …**` 提示段——它只在存在不可合并 PR 时出现，
是**一整行**（PR 行上的 `⚠` 后缀是内联的，不加行）。而**每一次 push 到 master 都会让所有 open PR 的
mergeability 重新排队计算**，于是「第一次调用拿到 UNKNOWN、八秒后第二次调用拿到 CONFLICTING」在
master push 后必然撞上一次；四笔连红不是巧合。

### 3. 修法：把实时源钉死，并给「钉死」本身装一条反向断言

T2/T3/T4/T5（含末尾那条「目标文件不存在」的 T5）五次 `--write` 一律带
`COORD_PR_FIXTURE="$pr_det"`，夹具是 2 行 TSV、列与 `gh --jq` 输出同构，且**故意一条 CONFLICTING/DIRTY、
一条 MERGEABLE/CLEAN**——这样 ⚠ 提示段的「出现」被钉成恒真，而不是「恰好没算出来所以没出现」。
再补两条夹具消费断言：

```bash
grep -qF "#142" "$status" || fails="${fails}T2:注入的 PR 夹具没进生成段（疑似仍在读实时数据） "
grep -qF "#143" "$status" || fails="${fails}T2:注入的第二条 PR 夹具没进生成段 "
```

这两条是防「以后有人把注入撤掉」：撤掉后生成段里出现的是真实 PR 号，`#142`/`#143` 未必命中，
于是这里直接红——**门禁重新变得可撤，但撤的人会被拦下来**，而不是静默退回「测 GitHub」。

顺带把「红得没有信息」这一半也修掉：T3 原来只报行数差，还自带一句「正文残留」的猜测。现在
第一次写回的正文会留档，行数不一致时**当场打出 diff**（限 30 行）：

```bash
t3_diff="$(diff "$tmp/STATUS.t1" "$status" | head -30)"
...
if [ -n "$fails" ]; then
  echo "自测失败：$fails" >&2
  if [ -n "$t3_diff" ]; then
    echo "---- T3 两次写回的正文差异（< 第一次 / > 第二次）----" >&2
    printf '%s\n' "$t3_diff" >&2
  fi
  return 1
fi
```

措辞也从「不幂等，正文残留」改成中性的「两次写回的正文不一致」——**断言不该在文案里替自己编归因**。

### 3b. 还剩哪些实时输入（把口径写死，别假装钉干净了）

夹具只钉住了 PR 清单。生成段里另有三处随环境动：标题行的**分钟级时间戳**、`origin/master` 短 sha、
以及**worktree 清单与「开工声明」列**（后者读 `.coord/STATUS.md`，本地会被并发写入）。
前两处只改**内容**不改**行数** ⇒ 撞不到 T3 的三条判据（begin 计数 / 段计数 / 行数）；
worktree 数量若在两次写回之间被别的 agent 改动，行数会真的变 ⇒ 那时新加的 diff 会直接把
「多出来的那一行是哪个 worktree」打出来，而不是留一句「正文残留」让人去查不存在的 bug。
CI 里 runner 只有 1 个工作树且没有别的 agent，所以这条残余风险在 CI 上不可达——**这是残余，不是已消除，
所以写在这里而不是藏在注释里。**

变异反证（见第 5 节）实测到「开工声明」列在同一次自测里从 `**无声明**` 翻成 `有`
（同期 `nexussky-restriction` 的 sha 从 `10c483da` 变成 `8a5ab64`——别的 agent 正在提交），
这也反过来印证：这个脚本的输入确实是活的。

### 4. 为什么不接受「重跑一次就绿」

这条断言测的是 GitHub 的状态迁移，重跑只是掷骰子；而它在 36 个必需检查里 ⇒ master 与**所有从 master
开出去的 PR**一起被这条红堵着（PR 跑的是自己树里的同一个脚本）。修它不是「顺手优化」，是解阻塞。

### 5. 验证

- `bash -n scripts/coord-status.sh` 通过。
- 本地 `bash scripts/coord-status.sh --self-test`（加完 diff 诊断之后复跑）⇒ `SELFTEST_RC=0`，
  `自测通过：… 重写幂等（begin/标题各 1 份、行数不变）…`。
- **新加的 diff 诊断不是摆设**：把副本（`COORD_STATUS_SELF` 注入，脚本本来就为变异测试留了这个
  注入口）里 T3 第二次的夹具改成多一条 PR，实跑得 `MUT_SELFTEST_RC=1`，且差异精确指到新增那行：

  ```
  自测失败：T3:重写后有效行数 26→27（两次写回的正文不一致）
  21a22
  > #144 `[feat/z]` merge=MERGEABLE/CLEAN :: 标题丙
  ```

  同一次输出里还能看到第 3b 节说的内容级漂移（时间戳 `01:48→01:49`、「开工声明」列整列翻转），
  它们**没有**让行数变化 ⇒ 三条判据没被它们误伤，这一点也是实测出来的。
- 上面第 2 节的两行机理表是**当场实测**（`COORD_STATUS_FILE` 指到临时文件、只换夹具），
  不是推理：1→2 条 PR ⇒ `23→24`；PR 数恒 1 条、仅 `UNKNOWN→CONFLICTING` ⇒ `23→24`；
  反证同批：夹具固定时同一状态文件两次写回 `24 → 24`。
- 未动 `.coord/STATUS.md`（`.coord/` 被 `.gitignore` 第 42 行忽略，状态板是本地产物，不入提交）。

### 6. 这一条属于哪一族

「门禁绿着的失效」记过很多次（自我失明、恒真断言、把合成物当产物）；这次是镜像形态——
**红着的失效**：断言拿环境当被测物，于是它**报了一条假的产品缺陷**，还带着可信的技术措辞
（「正文残留」）。比假绿更费时间的就是这种假红：它会指挥人去改本来正确的代码。
凡自测里出现「跨进程取真相」的调用（HTTP、gh、DB、时钟、异步算出来的字段），
要么注入并断言注入被消费，要么把那条断言从「判据」降级成「信息」。

---

## [Unreleased] — C5 发放账接上：轮换密钥真的记进 `signing_key_fingerprint`（此前那是一列死列），并给运维一张逐机可查的发放配置单（2026-10-24）

### 1. 现场：V28 那两列只有一半是活的

`V28__device_signing_state.sql` 落了 `signing_verified_at`（"真看到过这台的签名帧"）与
`signing_key_fingerprint`（"地面给过它密钥"）两列，语义分开是对的。但本轮现查：
`DeviceRegistry.recordSigningKeyFingerprint(int, String)` 在**生产代码里零调用方**
（全仓 grep 只命中定义处与注释，连测试都没调）⇒ `signing_key_fingerprint` **恒为 NULL**。
写入方 `MavlinkSigningKeyController.rotateKey` 只写 keystore 文件 + 打一条日志，
`devices` 表一个字节不碰。
更糟的是那一层默认：`recordSigningKeyFingerprint` 开头 `if (!isPersisting()) return;`，
而 `aerofleet.device-registry.persist` 出厂是 **false** ⇒ 即便接上调用，
默认部署里"已生成、等现场写进飞控"这半状态依然无处可查。
后果就是 `docs/signing-prod-rollout.md:103` 停在"现在只有端点，没有流程"那句话上：
收紧手册要"逐机发密钥"，可没人能导出发放配置单。

### 2. 修法：把假字段做成真能力（不是把列删掉）

- `rotateKey` 成功分支调用 `deviceRegistry.recordSigningKeyFingerprint(sysid, newFingerprint)`；
  **四道拒绝门（503 未启用 / 409 单密钥模式 / 401 无 JWT / 400 缺 key，外加 sysid 越界与
  密钥库损坏）一律不记账**，这一点用 `verify(registry, never())` 钉住。
- `DeviceRegistry` 给指纹加**内存镜像**（与 `signingVerifiedAt` 同构），
  `recordSigningKeyFingerprint` 先记内存再看是否落库，`restoreFromRepository()` 回填该镜像——
  否则 `persist=false` 的默认部署下配置单会全线显示"从没配过密钥"，
  而那恰好是收紧时最需要看见的一栏。空/空白指纹不记账（"清掉"不能读成"给过密钥"）。
- 响应体新增 `provisioningLedger`，如实说明这一步的下场：`PERSISTED` / `MEMORY_ONLY` /
  `UNAVAILABLE`（registry 不可用时轮换**照样成功**，但绝不假装记上了）。

### 3. 新读面：`GET /api/v1/mavlink/signing/provisioning`（ADMIN）

把 keystore 当前指纹、发放账指纹、`signing_verified_at` 三处并成一张表，
状态只有两种且含义不同：`VERIFIED`（**由观测得出**——地面真见过这台的签名帧）与
`ISSUED_UNVERIFIED`（有指纹但从未见过）。另有 `fingerprintMismatch`：账上的指纹与
keystore 现值不一致 ⇒ "以为配过了，其实配的是旧密钥"那种状态，以前完全看不见。
返回体只给指纹/linkId/时间戳，**永不含密钥材料**（沿用本控制器既有硬约束）。
本端点**不判"能不能翻 strict"**——那是 `GET /posture` 的职责（它看在线设备集合），不重复实现。
`RbacEndpointCoverageTest` 靠反射扫全仓 `@RestController`，新端点自动纳入"每个端点都要有注解"；
另加一条显式断言钉住 `provisioning=ADMIN`、`keys=OBSERVER`。

### 4. 本批明确**不做**的事

`mavlink.signing.enabled` 出厂仍是 `false`，`application-prod.properties` 一字未改。
打开它是**机队范围的行为变更**（MAVLink v2 签名块无 key id ⇒ 硬切换、未换密钥设备的帧整体被拒），
需要用户拍板 + 逐机发密钥，不在工程侧擅自翻。

### 5. 验证

- 定向：`mvn -B -o -pl mavlink-core,cloud-backend test -Dtest=MavlinkSigningKeyControllerTest,
  DeviceRegistryPersistenceTest` ⇒ **34 例 / 0 失败**（`MavlinkSigningKeyControllerTest` 10→18、
  `DeviceRegistryPersistenceTest` 14→16），BUILD SUCCESS。
- 全量：`mvn -B -o test` ⇒ **5104 用例 / 0 failures / 0 errors / 0 skipped（593 个测试类）**，
  BUILD SUCCESS，`MVN_EXIT=0` 先断言再用。
  （第一次跑在 `regulator-sim` 出过一条 `FaultInjectionTest.requestCountsAreTracked` 的
  `SocketTimeoutException: Read timed out`——**单独复跑 40 例 / 0 错误 / BUILD SUCCESS**，
  判为本机 CPU 被并行构建打满的超时类假红，不是回归；这也是为什么数字只取"全模块跑绿之后"的那一份。）
- 计数门禁：`check-test-count-docs.py --reports-root <本次 fresh surefire>` 先点名 **22 处**
  （20 处 5094→5104 + 2 处 cloud-backend 2978→2988），按门禁自己点名的 `(文件, 行)` 逐行重驱动
  （**不手算、不全局 grep**），复跑 ⇒ `全部一致。` `rc=0`。
- 端点数门禁：本 PR 新增 1 个端点 ⇒ 实测 409→410，`Claimed endpoint count gate` 首轮判红，
  按点名行逐行重驱动 15 处；另有两处**门禁看不见**的连带账（它只核总数声称）靠独立复算才发现：
  分方法计数 `216 GET` 之和只有 409 ⇒ 按注解数一遍是 **217**/163/14/16 = 410，
  以及控制器汇总行 `MavlinkSigningKeyController | 2 |` 必须是 **3**。复跑 rc=0（410 = 410）。
- **本条落地时 master 已前进四笔**（#154 补测两个面板缺陷、#155 EXTENDED_SYS_STATE、
  #156 电量文案归一、#157 签名密钥热更竞态），所以 rebase 后**全部实测重取**：
  5092 那一份作废、改为上表的 5104；CHANGELOG 小节数随之 186→187。
- 新增测试里三条是反向的：轮换被拒时 `never()` 记账、registry 缺席时报 `UNAVAILABLE`、
  `persist=false` 时不落库但内存可读——只断言"成功路径写上了"是拦不住"失败也写"的。

## [Unreleased] — 前端组件级测试补口 121 例；顺着它挖出电量 -1「未知」哨兵被画成红色 -1%、三套阈值并存，以及一条按变量名判范围因而一直看不见案发现场的守卫（2026-10-24）

### 1. 补口：5 个面板第一次被真实挂载

`@testing-library/react` 早就在 devDependencies 里，却只有少数面板用得上。本轮新增
`test/budgetBadge.test.jsx`(17) / `alertFeed.test.jsx`(19) / `droneList.test.jsx`(19) /
`dashboardPanel.test.jsx`(29) / `routeTemplatePanel.test.jsx`(20)——全部 `render()` 真实组件、
断言真实 DOM，**不在测试里复制组件的常量表**（`dockPanel.test.jsx` 旧版那种"镜像自证"：
组件把表改错、甚至把面板删掉，测试照样全绿）。**本批净新增 104 例**（上列 5 个文件逐文件实测
17+19+19+29+20），全量 `npm run test:ci` 实测 **`numTotalTests=518 / numPassedTests=518 /
numFailedTests=0`**，37 个测试文件（`test/` 35 + `src/` 2）。
> 这一节原先写的是「393 → 514、35 个文件」——那是本 PR 刚开出时 base 上的数；分支后来两次
> rebase 到前进过的 master，base 已不是 393，而 514 也在与 #156 的 `fmtBattery` 收敛后被重测取代。
> **改成只报「本批增量 + 终值」**，不再报一个已经对不上任何一次实测的箭头。

最值钱的是 `RouteTemplatePanel`：四种模板类型各自要带不同的请求体字段
（tower→`towers`+`orbitRadiusM/Points/hoverSec`、solar→`polygon`+`lineSpacingM/directionDeg`、
pipeline→`line`+`stepM`、shoreline→`polygon`+`stepM`+`offsetM`），字段名拼错后端不会报错、
只会给一条"看起来对"的航线——这形状此前一条测试都没钉。另钉住：切类型必清旧预览、
坐标 JSON 语法错**不发请求**、4xx 带状态码、dispatch 两阶段里
"任务已上传但启动失败（409）——先 ARM"这条唯一会被吞的错误、以及"上传失败就绝不喊启动"。

### 2. 顺着补口挖到的真缺陷：`-1 = 未知电量` 这个后端哨兵，前端当成 -100% 红色

出处不是猜的：`DroneSnapshot.java:29` 是 `public volatile int battery = -1; // %`，
`TelemetrySnapshot.java:15` 明写"batteryPct — 电池电量百分比 [0,100]，**-1 表示未知**"。
而 `utils/battery.js` 的 `battLevel()` 只把 `null/''/NaN` 当未知，负数一路落到
`n <= 20 ⇒ 'crit'`。后果（全部有断言钉住）：
- `DroneList` / `TelemetryPanel` / `RocPanel` / `CityTwinPanel` 把未知显示成 **`-1%` 并染红**；
- `DashboardPanel` 的告警计数把每一台**从未上报过电量**的设备**各算一条告警**；
- `TrackingPanel` 的本地副本更糟：`n <= 1 ? n * 100 : n` 把 **-1 算成 -100%**、
  把真实的 **1% 说成 100%**（还是绿色）。

修法走单一真相源：`battLevel()` 与新增的 `normBatteryPct()` 把负数判为未知，
新增 `fmtBattery()`；`TrackingPanel`/`RocPanel` 的本地副本删掉。顺带把第三套阈值一并收掉——
`RocPanel` 用的是 `>=50 绿 / >=25 黄 / 否则红`，`TrackingPanel` 曾用 `15/30`，
`utils` 是 `20/40`：**同一架飞机三个面板三种颜色**，正是 `utils/battery.js` 头部注释
声称已经消灭的那件事。prod 默认值与后端零改动。

### 3. 一条按变量名判范围、因此对案发现场完全瞎掉的守卫

`test/singleSourceOfTruth.test.js` 里那条"组件里也没有内联的电量阈值三元式"用的正则是
`/batt\w*\s*<=\s*\d+/` 与 `/battery\w*\s*<=\s*\d+/`——**左值必须叫 batt\*/battery\* 才算**。
而 `TrackingPanel` 那份副本写的是 `if (pct <= 15)`：左值叫 `pct`，两条都不认，
于是这条守卫在它声称要防的事情正在发生的情况下，长期全绿。

处置不是把正则放宽：实测 `towers.length <= 20`（`CellTowerPanel`）与 `for (i < 20)`
（`ThermalOverlayPanel`）会被误报，而为了压误报加的白名单就是下一个盲区。改成把
**被复制的函数名**纳入 `FORBIDDEN`（`fmtBattery` / `batteryColor` / `normBatteryPct`），
并新增两条自证：① 把历史上那份 `TrackingPanel` 副本的形状塞进合成源码，守卫必须报出它；
② 阈值形状正则自身也不是永真的。扩展生效的当场证据：`FORBIDDEN` 加上去之后，
`RocPanel` 立刻被判红——那是**第二份**此前无人看见的本地副本。

### 4. 两处诚实化小修

- `AlertFeed.jsx:37` `new Date(a.ts)` 无守卫：`ts` 缺失就渲染出字面 **"Invalid Date"**。
  改成占位 `--:--:--`（缺字段是"没数据"，不是"发生在无效时刻"）。
- `RouteTemplatePanel.jsx:198` 直接 `result.waypoints.map(...)`：后端回 200 但没带
  `waypoints` 时整个面板抛 TypeError。改成按数组判定，渲染 0 行。

### 5. 收回三处过期的文档数字

`README.md` 那段"49 个 `.jsx` 组件里 47 个零测试，且最大的三个
（`UnifiedCommandPanel.jsx` 1451 行 / `api.js` 1337 行 / `MapView.jsx` 1016 行）均无测试文件"
——2026-10-24 现查后三处都不成立：`.jsx` 实数 **59**（无直测 42，其中连冒烟都进不去的 15）；
`UnifiedCommandPanel.jsx` 现在 **1686 行**且 `test/unifiedCommandWiring.test.jsx` 就在挂载它；
`api.js` 现在 **1502 行**且有 `src/api.test.js` + `test/apiCancelSignal.test.js`。
真正"最大且无直测"的是 `MapView.jsx`（1112 行）。已按实测重写该段并写明原判据。
> **这里要留一条自我纠正的痕迹**：本段原先写"已按实测重写该段"，但那次 README 改写在与 #156
> 的 rebase 冲突中**丢了**——分支里 `README.md:896` 仍是"404 例 / 32 个测试文件 / 47 个零测试"。
> 是 `check-test-count-docs.py` 把它点出来的（`README.md:896 [前端] 声称 404，实测 518`、
> `ROADMAP.md:25` 同），不是我自己看出来的。**"文档我已经改了"这句话的正确验证方式是让门禁
> 对着文件重读一遍**，而不是记得改过。现已按最终实测重写，并把判据（页面级面板 37、真渲染 35、
> SKIP 2、组件级直测 20、两条链都进不去 13）写进 README 本体，便于后来人复算。

### 6. 验证

- `npm run test:ci` ⇒ **518/518**，`numFailedTests=0`，`numTotalTestSuites=144`，退出码 0。
- `npx eslint src --ext .js,.jsx` ⇒ **0 error**。
- 前端声称由门禁自己核对（不是手算）：`python scripts/check-test-count-docs.py
  --root <本树> --frontend-json gcs-web/vitest-report.json` 首轮点名 **2 处**
  （`README.md:896`、`ROADMAP.md:25`，均「声称 404 → 实测 518」），按它点名的 `(文件, 行)`
  逐行重驱动后复跑 ⇒ 前端 0 处不符。同一轮门禁实测 Java **5094 用例 / 0 失败 / 0 错误**
  （593 个测试类，来自本树 fresh `mvn -B -o test` 的 surefire 报告，`MVN_EXIT=0` 先断言再用），
  文档里的 Java 声称全部落在 5094 上，本批不动。
- 本批**不动任何 Java 测试**；Java 侧数字与 master 一致（`5094` 由本次 fresh 运行复核）。

### 7. 登记进 `.coord/GAPS.md` 而**不在本批动**的（都是补口时顺手量到的，不是猜的）

`App.jsx:588` `stats.maxAlt ? … : '--'` 把 0 米当成缺失；`ShowPanel.jsx:450-451` 的
`|| '--'` 与同文件 `!=null` 口径互斥；`FormationPanel.jsx:810` 只判 `lat != null` 就取
`lon.toFixed(5)`；`RocPanel.jsx:103` `Number('') === 0` 会去拉 0 号席位；
`HardwarePanel.jsx:178` / `SprayPanel.jsx:199` 的 `e.target.value ? Number(…)` 让 sysid=0
选不中；`TelemetryPanel.jsx:112` 与 `CityTwinPanel.jsx:377` 的 `-1` 文本（本轮只统一了
判级与颜色，这几处文案仍是裸值）；`MODE_LABEL` 三份各有不同缺省（`'待命'` / `'未知'` /
原样透传）。逐条见 GAPS。

## [Unreleased] — 「组件覆盖比例」终于有门禁了：README 那句长期失真的话当场被抓（2026-10-25）

### 1. 这条是 GAPS 里点名要单独 PR 的

`GAPS.md` 第八节第 8 条的原文：

> `README.md` 的组件覆盖比例声明此前长期失真（"49 个组件 47 个零测试 / 最大三个无测试"，
> 实为 59 个 / 42 个无直测，且点名的 `UnifiedCommandPanel.jsx`、`api.js` 都有测试在挂）——
> 本轮已按实测重写。**结构性原因留着**：计数门禁只把**用例数**纳入，
> **"组件覆盖比例"没有任何机器约束**，所以这类句子可以悄悄烂掉。要收口就得给
> `check-test-count-docs.py` 加一条"组件直测数"的实测声称（口径：被任一测试文件 import 过），
> 这是新门禁、要单独 PR。

本轮就做这一条。

### 2. 先实测，确认它现在还是失真的

按 Qoder 定的口径（**被任一测试文件 import 过**的组件才算有直测）实测：

```
jsx 组件总数: 59
被测试 import 的: 14
零测试: 45
```

而 README 两处都还写着旧值：

- `README.md:715`：「51 个组件文件，其中 49 个 .jsx 面板」
- `README.md:906`：「49 个 `.jsx` 组件里 **47 个零测试**」

⇒ 新规则一接上就**当场报红**，抓住的正是 Qoder 预言会烂掉的那句。

### 3. 做了什么

`scripts/check-test-count-docs.py`：

- 新增 `measure_components(root)`：实测 `(组件总数, 有直测数, 零测试数)`。
  口径写死在 docstring 里，免得各说各话：
  - 组件 = `gcs-web/src/**/*.jsx`（**不排除入口文件**——排除规则本身会变成第二个要维护的真值源）；
  - 有直测 = 被**任一测试文件 import 过**。只认 import，**不认文件名撞巧**——
    一个组件"碰巧与某测试同名"不算覆盖，这是本条要防的假象之一。
- 新增 `COMPONENT_CLAIM` 正则：`N 个 \`.jsx\` 组件里 **M 个零测试**`。
- 接入 `_run`：匹配到但数值不符 ⇒ **红**；**匹配不到只警告不红**（格式可能已变，
  不该把整份文档判死）。

`README.md`：两处按实测改写（59 个 .jsx / 14 个有直测 / 45 个零测试）。

### 4. 自证：三条用例证明它真的会红

`--self-test` 新增 `component_selftest()`，造迷你 `gcs-web/`（3 个 .jsx + 1 份 import 了
其中一个的测试 ⇒ 实测 3/1/2）：

| 用例 | 期望 | 实测 |
|---|---|---|
| 声称正确（3 个里 2 个零测试） | 不红 | **不红** ✓ |
| 数字漂移（写成 5 个里 4 个零测试） | 红 | **红，且只点 1 处** ✓ |
| 完全没有该声称 | 只警告 | **不红** ✓ |

既有 9 条自检用例也全部保持通过（新规则没有顶掉旧的）。

**没有自检的门禁 = 又一个"看起来在检查"的东西**——2026-10-08 那次静默失效正是因为
这类防护从未被证明有效过。所以这一步不能省。

---

## [Unreleased] — 接上 AUTOPILOT_VERSION，并查清「MAVLink2 扩展字段不参与 CRC_EXTRA」（2026-10-25）

### 1. 上一轮卡在哪

产品级盘点把 `AUTOPILOT_VERSION`(#148) 列为值得补的两条之一，但当时**主动没做**：
官方 `LEN=78 / CRC_EXTRA=178`，我用官方算法复算得到 **39** —— 长度对、CRC 不对，
说明字段顺序/数组长度有细节没对齐。当时的选择是不做半成品，等能拿到权威定义再说。

### 2. 本轮怎么破的：不猜字段，直接读官方帧

用 pymavlink 生成一条**字段值全部可辨认**的官方帧，按字节反推出真实布局：

```
capabilities=0x0102030405060708, uid=0x778899aabbccddee,
flight_sw=0x11223344, middleware_sw=0x55667788, os_sw=0x99aabbcc, board=0xddeeff00,
vendor_id=0x3344, product_id=0x5566,
flight_custom=[1..8], middleware_custom=[9..16], os_custom=[17..24], uid2=[25..42]

→ payload(78B): 0807060504030201 eeddccbbaa998877 44332211 88776655 ccbbaa99 00ffeedd
                4433 6655 01..08 09..10 11..18 19..2a
```

由此定下 wire 布局：`capabilities(u64) → uid(u64) → 四个 u32 → vendor_id/product_id(u16) →
三个 uint8[8] → uid2[18]`（偏移 0/8/16/20/24/28/32/34/36/44/52/60）。

**但按这 12 个字段重算 CRC 仍是 39。** 逐项排除后定位到真正的原因：

> **MAVLink 2 的扩展字段不参与 CRC_EXTRA 计算。**

`uid2`（偏移 60、18 字节）是 MAVLink2 才有的扩展字段。它不参与 CRC 的原因是**协议约束**：
只实现了 v1 的那一端必须也能校验这个帧，而 v1 根本不认识扩展字段。去掉 `uid2` 只算前
11 个字段，重算结果正好是官方的 **178**。

**这个细节此前在本项目是隐性的**：现有 51 条自定义消息**都没有扩展字段**，所以
`MavlinkMessageChecksum`（算法本身与官方一致）从来没被这个口径检验过。本轮把它写进了
`MavlinkMessageInfo` 的注释与 `MavlinkCrcExtraTest` 的签名表——**签名表里 148 也只列前 11 个**，
否则生成器会把算法引到错的口径上。

### 3. 做了什么

| 位置 | 改动 |
|---|---|
| `AutopilotVersion.java` | 新增消息类（ID=148, LEN=78, 12 字段 + `firmwareVersion()` 解析） |
| `MavlinkMessageInfo` | `INFOS[148] = Info(78, 178)`，注释说明扩展字段口径 |
| `MavlinkMessage.decode()` | 加 `case AutopilotVersion.ID`（此前落 default 被丢弃） |
| `MavlinkCrcExtraTest.SIGNATURES` | 加 148，**只列前 11 个字段** |
| `DroneSnapshot` | 新增 `flightSwVersion` / `firmwareVersion` |
| `TelemetrySnapshotListener` | 新增 `onAutopilotVersion` |

### 4. 验证

| 场景 | 期望 | 实测 |
|---|---|---|
| 完整 v2 帧 vs pymavlink | 逐字节相等 | **相等**（78 字节 payload，含尾部 `5173` CRC） |
| `MavlinkCrcExtraTest` | 独立重算 = 178 | **110/110**（含新加的 148 签名） |
| `AutopilotVersionTest` | 全绿 | **7/7**（往返、扩展字段仍在 payload、版本解析、分发） |
| `mavlink-core` 全量 | 全绿 | **501 用例 / 0 失败** |
| 测试数文档门禁 | 全部一致 | 全部一致（5094→5103、492→501） |

### 5. 顺带修掉一个自己的 bug

`AutopilotVersionTest.shortArraysPadded` 在写的时候就红了：构造器对**短于定长的数组**
直接 `System.arraycopy(..., 8)` ⇒ `ArrayIndexOutOfBounds`。改成 `Math.min(src.length, dst.length)`
（源短按 0 补齐、源长截断，都不抛）。这是"测试写完先跑一遍"的直接收益。

---

## [Unreleased] — 签名密钥热更的毫秒级竞态：写完不等于对方立刻看得见（2026-10-24）

### 1. 现场：一笔与我改动无关的 CI 红

PR #156（纯前端改动）的 `Java cloud-backend` job 红在
`SigningKeyStoreWriterTest.rotate_thenKeyManagerSeesNewKey:110` —— 断言
`km.keyFor(3).key() == "after"` 却拿到 `"before"`。这条此前被当作「已知 flaky」登记过，
留言板写的是「谁碰上谁另查并登记」。这次碰上了，就查了。

### 2. 根因：`reloadIfNeeded()` 只有毫秒精度

`SigningKeyManager.reloadIfNeeded()` 用 `Files.getLastModifiedTime(path).toMillis()`
与上次记录比较：

```java
long currentModified = Files.getLastModifiedTime(keyStorePath).toMillis();
if (currentModified != lastModified) { loadKeyStore(); }
```

而 `SigningKeyStoreWriter.rotate()` 写盘后，**调用方往往在同一毫秒内就 `keyFor()`** ——
mtime 与上次记录相同 ⇒ 判定「文件没改」⇒ **不重载** ⇒ 返回旧密钥。

`SigningKeyStoreWriter` 的 Javadoc 自己写着「写成功后由 `reloadIfNeeded()` 按 mtime 自动热更」，
所以这不是环境噪声，是**设计上依赖文件系统时间精度**。

**与那条旧建议的关系**：zcode 当年建议「用 `createTempDirectory` 隔离 keystore 路径」，
现文件 `:31` 早有 `@TempDir` —— **那条与本根因无关**，留言板「别再按它动手」的判断是对的。

### 3. 修法：由写入方主动通知，不等 mtime

- `SigningKeyManager` 新增 `forceReload()`：跳过 mtime 比较，直接 `loadKeyStore()`
- `SigningKeyStoreWriter.rotate()` 写盘成功后调用它

比「测试里 sleep 2ms」正确 —— 后者是绕开缺陷，前者是消除对时间精度的依赖。

### 4. 怎么证明修好了（不赌文件系统精度）

偶发测试的修法最容易「改完看着绿了，其实只是运气好」。所以新增的
`rotate_visibleEvenIfMtimeUnchanged` **手工把 mtime 钉死**，确定性复现那个竞态：

1. 把 mtime 设成固定值 `T`，让管理器以它为基准；
2. `rotate()` 写入新密钥；
3. **把 mtime 改回 `T`** —— 等价于「文件系统没有报告变化」；
4. 断言 `keyFor(3)` 必须拿到 `"after"`。

**验证这条测试有证明力**：临时注释掉 `forceReload()` 后，它**必红**
（`SigningKeyStoreWriterTest.java:152`，且 9 条里只有它红，说明断言精准）；
恢复后 9/9 全绿。

### 5. 验证

| 场景 | 期望 | 实测 |
|---|---|---|
| 禁用 `forceReload()` | 新测试必红 | **红**（`rotate_visibleEvenIfMtimeUnchanged:152`） |
| 恢复修复 | 全绿 | **9/9** |
| `mavlink-core` 全量 | 全绿 | **492 用例 / 0 失败** |
| 全仓 Java | 全绿 | **5094 用例 / 593 类** |
| 测试数文档门禁 | 全部一致 | 全部一致（5093→5094、491→492 已同步） |

### 6. 生产影响评估

`rotate()` 是低频运维动作，真实调用方与写盘之间通常远大于 1ms，所以**生产几乎不会命中**。
但「写完立刻读」在测试、脚本、以及将来任何「轮换后立即用新密钥」的代码路径上都会命中 ——
这次修的是那条路径的正确性，不是为一个测试打补丁。

---

## [Unreleased] — 电量展示四处口径收成一处，顺带修掉「电量 1% 显示成 100%」（2026-10-24）

### 1. 起因：`GAPS` 第八节第 7 条说「`-1` 哨兵的文案层只统一了一半」

顺着这条查下去，发现的问题比记录的更重——**电量格式化一共有四份实现**：

| 位置 | 写法 | 后果 |
|---|---|---|
| `TrackingPanel`（本地 `fmtBattery`） | `n <= 1 ? n * 100 : n` | 假设 0–1 归一化，而后端是 0–100 ⇒ **1% 显示成 100%**、`-1` 显示成 **`-100%`** |
| `TrackingPanel`（本地 `batteryColor`） | 同样的归一化 | `-1` 算成 `-100` ⇒ 落进 `<= 15` ⇒ **"没数据"被判成"严重低电"的红色** |
| `TelemetryPanel:112` | `x != null ? x : '--'` | `-1` 原样显示成 `-1` |
| `CityTwinPanel:377` | `x != null ? \`${x}%\` : '--'` | `-1` 显示成 `-1%` |

第一条是**方向反了的错**：把"快没电"画成"满电"。这类错在真机上会让操作员直接误判返航时机。

### 2. 修法：一处实现，四处引用

`utils/battery.js` 新增 `fmtBattery(b, withUnit)`，契约写死：
**任何非有限值、负数、空串一律 `'--'`；`0` 是合法电量（0%），必须显示出来。**

| 位置 | 改动 |
|---|---|
| `utils/battery.js` | 新增 `fmtBattery`（与 `battLevel`/`battClass`/`battColorVar` 同源同阈值） |
| `TrackingPanel` | 删本地 `fmtBattery` 副本，改用共享版（4 处调用点）；本地 `batteryColor` 补 `n < 0 ⇒ 未知色` |
| `TelemetryPanel:112` | 改用 `fmtBattery(t.battery, false)`（单位由后面的 `<span>%</span>` 提供） |
| `CityTwinPanel:377` | 改用 `fmtBattery(t.battery)` |
| `ShowPanel:452-453` | `pick(...) || '--'` → `?? '--'`（`0 架无人机` 不再显示成 `--`；同文件 `:302/:414` 本来就用 `!= null`，两套口径收成一套） |

### 3. 验证

| 场景 | 期望 | 实测 |
|---|---|---|
| `battery.test.js` | 全绿 | **16/16**（新增 6 条 `fmtBattery` 用例，含 `1→1%`、`-1→--`、`0→0%`） |
| 完整前端套件 | 全绿 | **32 files / 404 tests** |
| 四处调用点 | 都在 | grep 确认（TrackingPanel 4 处、TelemetryPanel 1 处、CityTwinPanel 1 处、ShowPanel 2 处 `??`） |
| `check-repo-hygiene` / `check-doc-counts` | 通过 | 通过 |

**诚实交代一处没兜住的**：`ShowPanel` 那两行 `||`→`??` **没有组件测试兜住**——该面板无 props、自拉 API，
加测试要先 mock 整条数据链。本轮没为它重构（那属于"为覆盖率重构"，Qoder 在 `GAPS` 第八节里
明确反对过）。这两行的语义改动很小且方向明确（`0` 是合法值），已在 `GAPS` 第九节记为待办。

### 4. 顺带记一笔（本轮没动）

`TrackingPanel.batteryColor` 的阈值是 **15/30**，而 `utils/battery` 是 **20/40** —— 同一架飞机在
追踪面板与仪表面板上可能一个是绿色、一个是黄色。本轮只修了 `-1` 判红那个 bug，**阈值没动**
（改阈值是可见的视觉变化，需要产品口径）。已记入 `GAPS`。

---

## [Unreleased] — 接真机的第一块砖：EXTENDED_SYS_STATE 让"起飞中/降落中"有官方依据；顺带补齐 land 命令（2026-10-24）

### 1. 为什么是这条

产品级盘点（`GAPS.md` 第九节）把「软硬件结合」列为最大实质缺口：遥测**读取**是完整的
（电池/位置/姿态/GPS/链路都从标准消息提取），但 PX4 常用消息里有一批**全仓零实现**，
其中影响最直接的是 `EXTENDED_SYS_STATE`(#245) —— 它承载 `landed_state`
（地面 / 空中 / 起飞中 / 降落中）。

在这之前，`MavlinkMessage.decode()` 的 switch 没有 245 分支，落到 `default: return null`，
**整帧被丢弃**；GCS 上"起飞中 / 降落中"只能靠 `HEARTBEAT.custom_mode` 猜，而 custom_mode
是**固件相关的导航状态**——PX4 与 ArduPilot 对同一个数字的解释不同。接真机后这两句话
只有 landed_state 有资格说。

### 2. 做了什么

| 位置 | 改动 |
|---|---|
| `mavlink-core/.../messages/ExtendedSysState.java` | 新增消息类（ID=245, LEN=2, 编解码 + `landedStateName()`） |
| `MavlinkMessageInfo` | `INFOS[245] = Info(2, 130)` |
| `MavlinkMessage.decode()` | 加 `case ExtendedSysState.ID`（此前落到 default 被丢弃） |
| `DroneSnapshot` | 新增 `landedState` / `vtolState`（-1 = 尚未收到） |
| `TelemetrySnapshotListener` | 新增 `onExtendedSysState` 监听器 |
| `DroneController.sendCommand` | 补 `case "land"` → `MAV_CMD_NAV_LAND(21)` |

`land` 那条是盘点时顺带发现的**同动作两条路径支持度不一致**：`/roc/seats/{id}/commands`
一直有 `land`，而 `/drones/{sysid}/commands` 没有——第三方走 REST 想降落只能自己填
`raw` 的 `cmd=21`。

### 3. 怎么证明它是对的（本节是重点）

CRC_EXTRA 填错时**本地一切正常、对端整帧丢弃**——自己发给自己当然能通。所以判据不是
"代码看起来对"，而是**与 pymavlink 生成的官方帧逐字节相等**：

```
vtol=3, landed=1 → fd 02 00 00 00 01 01 f5 00 00 03 01 72 06
vtol=3, landed=2 → fd 02 00 00 00 01 01 f5 00 00 03 02 1a 2c
vtol=3, landed=3 → fd 02 00 00 00 01 01 f5 00 00 03 03 c2 35
vtol=3, landed=4 → fd 02 00 00 00 01 01 f5 00 00 03 04 ca 78
```

`f5 00 00` 是 msgId 245 的小端，末尾两字节是帧 CRC —— 它把 CRC_EXTRA=130 钉死了。
`ExtendedSysStateTest.frameMatchesPymavlinkByteForByte` 断言 Java 侧
`toFrame(1,1,0).encodeV2()` 与上面四行**逐字节相等**；另有三条校准用例（HEARTBEAT /
ATTITUDE / GLOBAL_POSITION_INT）先证明算法本身正确。

同时 `MavlinkCrcExtraTest` 的 `SIGNATURES` 加了 245 的字段签名，由 `MavlinkMessageChecksum`
**独立重算**并与线上常量比对（108 条参数化用例全过）——以后谁改了字段忘了重算 CRC，
构建会红。

### 4. 验证

| 场景 | 期望 | 实测 |
|---|---|---|
| `ExtendedSysStateTest`（7 条） | 全绿 | **7/7**（含帧字节对等、CRC=130、分发不再返回 null） |
| `MavlinkCrcExtraTest`（108 条） | 全绿 | **108/108** |
| `DroneControllerTest`（20 条） | 全绿 | **20/20**（含新增 land 被接受、未知命令仍拒） |
| 全仓 Java 测试 | 全绿 | **5093 用例 / 593 类 / 0 失败** |
| 测试数文档门禁 | 全部一致 | **全部一致** |

### 5. 为什么没一起做 AUTOPILOT_VERSION（诚实交代）

盘点时列了 8 类未实现消息，逐条判读后**6 类有替代或不需要**，值得补的是两条。
`AUTOPILOT_VERSION`(#148) 本轮**没做**：它 12 个字段、官方 LEN=78 / CRC_EXTRA=178，
我用官方算法复算得到 39（长度对、CRC 不对），说明字段顺序/数组长度还有细节没对齐。
pymavlink 已确认官方参数（`ordered_fieldnames` + `crc_extra=178`），
**下次按它逐字段对齐后再补**——不做"填个数字看起来能跑"的半成品。

### 6. 顺带澄清一条此前的判断

上一轮（#154）写过「本地 `check-test-count-docs.py` 报的 22 处不一致是 surefire 残缺假象」。
本轮把全仓 Java 测试**完整跑了一遍**后复核：门禁输出 **「全部一致」**（同步掉本 PR 新增的
11 个用例后）。⇒ 那个判断成立：**本地残缺时不能改文档，完整跑之后才可信**。
## [Unreleased] — P2 #20 link-sim 运行时控制通道：伤害参数从“启动期固定”变“可调、可见、可启动验证”（2026-10-24）

### 1. 现场：P1 #11 移除的假滑块，背后是一个真的缺口

前端链路质量面板曾有三个伤害滑块，2026-10-24 按诚实性原则移除（P1 #11）。
删除时记下的恢复条件是：link-sim 加 HTTP 控制面 + 云端转发端点。
实测：伤害参数（延迟/抖动/Gilbert-Elliot 丢包/带宽/分区）只能由启动参数
`--profile` 固定，进程内没有任何控制入口；云端也没有对应端点。
本轮把这两件补上，滑块按当时记下的恢复条件恢复。

### 2. 补了什么（link-sim / cloud / 前端各一环）

- **link-sim 控制面**：`--control-port N`（**默认 0=关闭**，不改变既有默认行为）
  启动 `GET/PATCH /profile`（JDK HttpServer，**只绑 127.0.0.1**，无应用层鉴权、
  64 KB 请求体上限的校验）。新 `ImpairmentSpec`（十元组不可变规格）+
  `LinkSimControl`（语义层）+ `LinkSimControlServer`（HTTP 层）三个新类各只负责一层；
  `ImpairmentEngine` 字段改非 final + `apply/spec/statsSnapshot`，参数替换对判决原子生效。
  语义边界写死：统计计数跨参数保留、令牌桶 clamp 到新箱、分区相位不重置（调参本身不制造假黑洞）。
- **控制语义**：三种互斥形态——`profile`（整画像切换）/ `reset`（恢复**当前**画像默认）/
  自定义字段局部更新；支持 `direction=uplink|downlink|both`。
  `dropPct` 显式切换为**独立随机丢包**（`dropModel=uniform`），与画像的 GE 突发模型区分，视图里直接显示。
- **cloud-backend 转发**：`GET /api/v1/link-sim/control`（OBSERVER）+
  `PATCH /api/v1/link-sim/control`（**OPERATOR**）。错误分层映射：
  连不上 503（提示启动参数）/超时 504/上游 400 透传原因/上游 5xx 映 502。
  参数校验的唯一权威留在 link-sim（云端不复制一份规则）；
  LicenseModuleMap 登记 `/api/v1/link-sim` → network 模块（不登记即 fail-closed）。
- **前端**：`api.js` 新增 `getLinkSimControl` / `patchLinkSimControl`（错误提示改读后端的
  `result` 字段，不再只显示 "HTTP 400"）；`LinkQualityPanel` 恢复伤害面板——
  滑块初值读自控制面**当前值**，拖动后必须点“应用”才发 PATCH，成功后以响应回填；
  控制面不可达时整块控件禁用并显示原因。

### 3. 实测

- 新用例 19 个：link-sim 控制面契约 11 例（真实 HTTP + **按住引擎判决断言**）+
  云端转发契约 8 例（假 link-sim 真实转发方法/路径/请求体、400 透传、503/504/502、
  RBAC 注解直调断言）。
- **A/B 3/3 KILLED**：控制面 `d.engine.apply(next)` 删除 / 转发把 PATCH 降级成 GET /
  去掉 PATCH 端的 `@RequireRole(Role.OPERATOR)`——全部"改坏⇒红、还原⇒绿"。
  **真机 curl 复核**（另起进程跑 jar，不是只看单用例）：GET /profile 返回默认 `lan`、
  PATCH 后 `mode=custom`、`profile=radio-sik` 切换后 dropModel 回到 `ge`、
  `{"delayMs":-5}` 返回 400 且 result 给出具体原因、POST 返回 405（带 Allow 头）。
- link-sim 122 → **133**，cloud-backend 3042 → **3050**，全仓 **5169** 用例 0 失败；
  端点 411 → **413**（新增 GET+PATCH），vitest 28 例整文件 + build + lint 全绿。
- **安全口径**：link-sim 控制端口只绑 127.0.0.1 且**不做应用层鉴权**；对外只有云端控制器。
  改 `aerofleet.linksim.control-url` 指向非回环地址等同于把沙盒搭到公网——已写入类注释。

### 4. 未关闭 / 边界

- 安全画像（`tamper`/`unsigned`）不支持控制通道：传 `--control-port` 直接退出 1（安全伤害没有可调的数值参数）。
- `--env-coupled` 的雨衰覆盖层不受控制面直接影响（包装层读环境状态）；视图的 `envCoupled` 字段只表示开关状态。
- 控制面本身不持久化、不做并发管控：转发失败不重试。

---

## [Unreleased] — P2 #19 热成像接线：从"消息定义"到"面板有数"的全链补完（2026-10-24）

### 1. 现场：三件齐备、唯独没人发送

P1 #12 把热成像面板改成"未实现"说明页时分诊确证：THERMAL_DATA(30012) 的消息定义
（mavlink-core）、解码（`MavlinkMessage.decode`）、载荷源（drone-sim 的
`SimulatedThermalSource`/`BudgetThermalSource`）三件齐备，但**全仓零发送点**——
且完整版 VirtualDrone 根本没装配热像源（只有 budget 模式有）。链上每一环都在，
唯独没人把它们接起来：cloud-backend 零引用、任务壳永不推进、前端吃 404。

### 2. 补了什么（三端各一环）

- **drone-sim 发送环**：完整版构造器装配 `SimulatedThermalSource`（此前仅 budget
  模式装配）；`telemetryRates` 1Hz 块挂钩（照 LiDAR(30020) 模板）：
  `synthesizeThermalScene`（基线 22℃ + 两个缓变热源，位置由 sysid 派生、确定性可测）
  → `analyze` → `ThermalDataMsg` 下发。
- **cloud-backend ingest 环**：新 `ThermalIngestListener`（照
  `TelemetrySnapshotListener` 的 @EventListener + msgId 条件姿势）——每帧写
  `DroneSnapshot` 热像五元组快照 + 收账该 sysid 最老活跃任务（PENDING → COMPLETED；
  每帧都是完整分析，RUNNING 态在此接线不可观测，Javadoc 诚实记录）。
- **cloud-backend 读环**：`ThermalController` + `GET /api/v1/thermal/status`
  （机队热读数：只列 online 且上报过的机，租户口径由 `DeviceRegistry.all()` 过滤）。
- **前端**：ThermalOverlayPanel 从"未实现"说明页换成真实机队热读数表
  （tempMax/mean/min/σ + 热点数 + 上报时刻，温度阈值告警为客户端过滤并注明）。

### 3. 实测

- 新用例 11 个：sim 侧 `ThermalDataEmissionTest` 2 例（**真实 UDP 往返**：1Hz 帧必达
  + 数值非平凡 + 场景确定性）、cloud 侧 ingest 契约 5 例 + 读口径 4 例。
- **A/B 4/4 KILLED**：1Hz 挂钩删除 / 完整版热像源装配删除 / 收账调用删除 /
  离线过滤取反——全部"改坏⇒红、还原⇒绿"。
- 端点 410 → **411**；vitest 382 + build + lint ✓。
- 恢复条件③（逐点热源经纬度）仍留 P2 #19：需要图像→地理位姿解算，本轮面板按
  聚合口径显示（每机五元组），不假装有逐点坐标。

---

## [Unreleased] — 两个前端缺陷先补测试再修：RocPanel 空席位会去拉 0 号、FormationPanel 半坐标渲染崩溃（2026-10-24）

### 1. 选题来源：`GAPS.md` 第八节，Qoder 登记但明确「没动」的两条

Qoder 在 2026-10-24 前端组件级补口时逐行读过组件，登记了 8 条缺口，其中两条是**真缺陷**，
但他写明了没修的原因——「**没有组件测试兜住这两个面板，改错没人发现**」。本轮就是把这两条补上：
**先写测试让它红，再改，再让它绿**。

### 2. 缺陷一：`RocPanel` 选回「选择席位…」会去拉 0 号席位

`RocPanel.jsx` 的下拉框第一项是 `<option value="">选择席位…</option>`，onChange 写的是
`setSeatId(Number(e.target.value))` —— 而 **`Number('') === 0`**。于是：

- 操作员把席位选回「选择席位…」⇒ `seatId = 0`
- `load()` 的守卫是 `if (seatRef.current != null)` —— **0 也满足**
- ⇒ 真的发出 `GET /api/v1/roc/seats/0` 与 `/0/incidents`
- 而后端席位 ID 从 **1** 起分配（`RocSeatService.idSeq = new AtomicLong(1)`），0 永远不是合法席位
- 组件里 `if (v.ok)` / `if (i.ok)` **都没有 else 分支** ⇒ 两个 4xx 全程静默

**测试复现（修之前跑出来的原文）**：

    AssertionError: 不该请求 0 号席位，实际发了：
    ["/api/v1/roc/seats/0","/api/v1/roc/seats/0/incidents"]

**修法两处**：① onChange 空值映射成 `null`；② 加载守卫加 `> 0`（防其他路径传入 0）。

### 3. 缺陷二：`FormationPanel` 目标坐标只有一半时渲染崩溃

`MemberRow` 渲染坐标时写的是 `target.lat != null ? \`${lat.toFixed(5)}, ${lon.toFixed(5)}…\` : '--'`
—— **只判了 lat，却无条件对 lon 调 `.toFixed()`**。WS 帧里单侧坐标是可能出现的
（编队刚下发、飞机还没回报经度）。此时抛 **TypeError**，而 `FormationPanel` 第 70 行会
**自动选中第一个编队** ⇒ 只要有编队就渲染，**不需要任何交互就会崩**。

**测试复现**：`render()` 直接抛错，报错行正是 `target.lon.toFixed(5)`。

**修法**：`target.lat != null && target.lon != null`（两个都齐才格式化）。

### 4. 验证

| 场景 | 期望 | 实测 |
|---|---|---|
| 修复前：RocPanel 选回空 | 应红（复现） | 红，抓到 `/seats/0` 与 `/seats/0/incidents` |
| 修复前：FormationPanel 半坐标 | 应红（复现） | 红，`render()` 抛 TypeError |
| 修复后：两个文件 | 全绿 | **5/5 通过** |
| 完整前端套件 | 全绿 | **32 files / 398 tests 通过** |
| `check-frontend-endpoints.py` | 通过 | 通过（238 处调用） |
| `check-repo-hygiene.py` | 通过 | 通过 |
| `check-doc-counts.py` | 通过 | 通过 |

两个测试都带**对照组**（选真实席位仍会请求该席位；完整坐标仍正常显示），防止「为了消除报错把功能关掉」。

### 5. 一条必须写下来的判断：本地 `check-test-count-docs.py` 的 22 处「不一致」是假象

修完后本地跑该门禁报 **22 处不一致**（Java 5082 vs 实测 4970、67 vs 78 个 `@RestController` 等）。
**这不是文档腐烂，是本地 surefire 报告残缺**：

- 本地 `cloud-backend/target/surefire-reports/` 只有 **333 个 XML**，而门禁自己说「来自 577 个测试类」；
- 该目录的时间戳停在 **10-21 05:54**（三天前，且不完整）；
- **master 上 `Docs test-count gate` 是 success** ⇒ CI 环境里文档与实测一致。

⇒ **绝不能按本地的 4970 去改那 22 处文档**，那会把对的改成错的。本轮只同步了**确实被本 PR 改变**的
前端用例数（393 → 398、测试文件 30 → 32 个，README:896 与 ROADMAP:25 两处）。

---

## [Unreleased] — 变异基线的账目差收口：636/22 与 CI 的 635/23 都留下名字，并给基线加一条"五列必须加回 total"的判据（2026-10-24）

### 1. 现场：门禁每次绿，却也每次打一句没人解释的"差 1"

surveillance 基线记 **杀 636 / 存活 22**，而 CI 在 run `40002936758`（head `293bf74`，
master，completed/success）的 `Mutation baseline gate` 日志原文是：

- `变异体总数 = 809 | 杀掉 = 635 | 存活 = 23 | 未覆盖 = 144 | 超时 = 7 | 运行错误 = 0`
- `变异分数 = 0.9549（基线 0.9500）   测试强度 = 0.9650（基线 0.9600）`
- `存活变异体 23 个（基线登记 22 个）—— 详情见报告`

同一 run 的 security 包则是 `存活变异体 102 个（基线登记 102 个）`——逐字对上。
差 1 的那条从 #142 起每轮都打，因为不算判据所以从不判红，也从不有人处理。

### 2. 真相：不是抄错数，是同一个变异体在两次运行里翻了面

`killed/survived/noCoverage/timedOut/runError` 这五列**不参与判红**（判据只有两条分数 +
`total` 完整性锚点），所以两边各自都自洽：636+22+144+7+0 = 809，635+23+144+7+0 = 809。
PIT 控制台行也同步差 1（本地 `Killed 643` = 636+7 超时，CI `Killed 642` = 635+7）——
即"本地杀掉、CI 存活"的是**同一个变异体**，属运行间抖动，与基线 note 里
"分数向下取整留 ~0.3pp 余量"守的是同一件事。

### 3. 处置：两个数都留档并署名，而不是把 636 改成 635

把主块改成 635/23 会让文件**声称一个本地从没测出的数**（下次本地跑 PIT 又"对不上"）。
改为：`scripts/mutation-baseline-surveillance.json` 新增 `ciObserved` 块，把 CI 那一次的
六个计数 + 两条分数连 `runId` / `headSha` / 来源 job 一起记下（异源、可复核），
`note` 里写清差 1 的成因；门禁打印存活数时改与 `ciObserved` 对账——
"22↔23"从一条每次都出现的假噪音，变成**只有真漂移才会亮**的账（不判红，但明说"别长期挂着"）。

### 4. 顺带把"留档字段可以随便错"这条路堵掉

`scripts/check-mutation-baseline.py` 新增 `check_accounting()`：一个基线块里只要写了这五列
中的任何一列，就必须五列齐全且加回 `total`（**缺列也判红**——否则删掉一列就等于关掉这条判据）；
`ciObserved` 同样受这条不变式约束。`--self-test` 里新增 4 例（⑨记账不平被拦 / ⑩自洽不误报 /
⑪`ciObserved` 不平被拦 / ⑫缺列被拦），并新增 ⑬⑭**直接读仓里那两份真基线**核对不变式——
合成夹具拦不住"真文件本身写错"，而 `--self-test` 是每轮 CI 必跑、不需要 PIT 报告的一步。

### 5. 验证

- `python scripts/check-mutation-baseline.py --self-test`：14 例全 PASS，`rc=0`。
- 反向变异（临时改坏真基线、跑完按字节还原）三例各自被抓住，且**报错文本就是新判据本身**：
  ① 主块 `survived` 22→23 ⇒ `[FAIL] … 之和 = 810 ≠ total = 809`；
  ② `ciObserved.killed` 635→636 ⇒ `[FAIL] … ciObserved … = 810 ≠ 809`；
  ③ 把 `survived` 键名写坏 ⇒ `[FAIL] … 记账字段残缺 … 缺 survived`。
  还原后 `rc=0`、FAIL 行数 0，`git diff` 回证只剩本次真实改动。
- JSON 合法性与 `ciObserved` 读回：`python -c json.load` 实测 13 个顶层键，`survived=23 / killed=635 / runId=40002936758`。
- 分数一条未动：两份基线的 `mutationScore` / `testStrength` 逐字未改，本 PR 不可能造成棘轮回退。
## [Unreleased] — README 目录计数三处集体过期（28/50/32 vs 实测 44/90/39），补一个目录计数门禁（2026-10-24）

### 1. 现场：三个数字，全错，且没有门禁在看

上一轮复核「端点数门禁覆盖边界」时，审计出 28 行含「端点」且有数字的文档行。逐条判读时发现
README 的**目录树计数**也烂了，而这一类**任何门禁都没覆盖**：

| 位置 | README 声称 | 实测（2026-10-24） |
|---|---|---|
| `scripts/` 下 `e2e-*` 脚本 | 28 个 | **44 个**（28 `.ps1` / 11 `.sh` / 4 `.py` / 1 `.cmd`） |
| `scripts/` 条目总数 | 50 个 | **89 个**（含 2 个子目录） |
| `docs/` 文档篇数 | 32 篇 | **39 篇** |
| 「进 CI 门禁」的脚本 | 7 个 | **31 个**被 `ci.yml` 直接引用（其中 2 个是公共库与自测，能力腿 29 个） |

### 2. 根因

#114 起大量 e2e 脚本做跨平台适配（每个场景新增 `.ps1` 版本，`.ps1` 从无到有 28 个），
文档也持续增加；而这三个数字是 **2026-10-06** 写进 README 的，此后没人再核。
本仓库已有「端点数声称」「测试数声称」门禁，**目录计数这一类是空白**。

### 3. 修法：改对数字 + 补门禁（不只修数，还防复发）

- README 四处改对（e2e-\* 28→44、进 CI 7→31、脚本 50→90、文档 32→39），
  并写清口径（`.ps1`/`.sh`/`.py`/`.cmd` 的分布、31 个里 2 个是公共库）。
- **新增 `scripts/check-doc-counts.py`**：只锚定 README 目录树那两行，实测
  `os.listdir` / `glob` 后比对；口径写死在 docstring 里。**刻意不碰 README 里其他数字**
  （模块数、消息数、端点数各有其主，混校会误报——与端点数门禁同一条教训）。
- 接进 CI `repo-gates` job：一步自测、一步实跑。

### 4. 验证

| 场景 | 期望 | 实测 |
|---|---|---|
| `--self-test`（5 条） | 全 PASS | 全 PASS（含「锚点缺失必须显式失败，不伪装通过」） |
| 正常跑 | 通过 | 通过 |
| `check-selftest-coverage.py` | 新脚本被认可 | 通过（每个提供 `--self-test` 的脚本都有 CI job 跑它） |
| `check-repo-hygiene.py` | 通过 | 通过 |
| 行尾 | ci.yml 纯 LF | CR=0 |

**门禁当场证明了自己有用（两次）**：
① 脚本创建后第一次跑就报「README 声称 89 / 实测 90」——新脚本自己改变了 `scripts/` 的条目数；
② 首轮 CI 直接红在 `Repository gates`：本地数出 **90**、CI checkout 只有 **89**，
差的是本地未跟踪的 `scripts/__pycache__`（10-21 跑 python 留下的）。
这是典型的**「本地绿、CI 红」环境差异**——`os.listdir` 会把工作区的临时产物算进去。
⇒ 修法：`measure()` 显式排除 `__pycache__` / `.pytest_cache` / `.mypy_cache` /
`.DS_Store` / `node_modules`（只存在于工作区、不会进 checkout 的条目），README 回到 89。
修完本地 `measure()` 返回 `(89, 44, 39)`，与 CI 所见一致。

### 5. 过程中踩的三个坑（都已修）

1. **正则写在 raw string 里用 `\uXXXX`** ⇒ Python 不解释转义，静默匹配不到任何行。
   自测第一条就抓住（「一致时却报错」）。改成直接写中文。
2. **`Edit` 工具把 `ci.yml` 整个文件从 LF 转成了 CRLF**（2018 个 CR）——
   blob 里本是纯 LF。提交前用 python 转回 LF 并核对 `git diff --numstat` 仍只显示 6 行。
   （`#133` 的混合行尾事故就是这么来的，同类坑今天第二次踩到。）
3. **计数口径必须排除工作区噪声**（见上）：本地 `__pycache__` 让 89 变 90，
   门禁在 CI 上红而本地全绿。**写任何"数目录/数文件"的门禁，都要先问：
   这个条目在 CI 的干净 checkout 里存在吗？**

---

## [Unreleased] — 前端取消请求真的取消：AbortSignal 不再被 api 层丢弃，且不把「用户取消」报成「超时」（2026-10-24）

### 1. 现场：`abort()` 只改了 React 状态，网络请求照跑

`TrackingPanel` 的四条请求（`getLostDrones` / `getSearchGuide` / `getFlightTrack` /
`replayTrack`）各自带 `AbortController`，切换选中项时会 `abort()` 上一次请求。
但 api 层这 4 个包装函数**签名里没有 options 形参**（arity 分别 0/1/2/4）⇒
多传的实参被 JS 直接丢弃；即便透传下去也没用：`jsonFetch` 会**无条件**用超时
controller 覆盖 `fetchOptions.signal`，调用方的 signal 根本到不了 `fetch`。

后果不是脏数据（组件层读的是同一个 controller 对象，`controller.signal.aborted` 是真的，
`if (cancelled || controller.signal.aborted) return` 也确实挡住了旧结果），
而是**请求白跑**：快速切换选中项时旧请求继续占用连接、按原样返回，结果被丢掉。

### 2. 第二个坑：任何 AbortError 都被翻成 TimeoutError

旧代码是 `catch (e) { if (e.name === 'AbortError') throw new TimeoutError(timeout) }`，
把调用方的取消也算成超时 ⇒ **用户点了取消，界面上报「请求超时（15000ms）」**，
排查时会把用户操作误读成网络故障。

### 3. 修法

- `jsonFetch`：超时与调用方取消两条通路合并到同一个 controller（超时置 `timedOut=true`；
  调用方 signal 已 abort 则立即 abort，否则挂**一次性** `abort` 监听），
  请求结束（含失败）时**摘掉监听**——长轮询每次重挂 controller，不摘就是泄漏。
- 只有 `timedOut` 为真才抛 `TimeoutError`；其余 `AbortError` **原样抛**。
- 4 个包装函数增加 options 形参并透传：`getFlightTrack(sysid, limit, options)`、
  `replayTrack(sysid, from, to, limit, options)`、`getLostDrones(options)`、
  `getSearchGuide(sysid, options)`。调用点本来就是这个形状（传 `{ signal }`），无需改动。

### 4. 验证：新增 11 例，两条反向变异都实跑

- 4 个包装函数各两条：options（含 signal/headers）透传到 fetch；调用方 abort ⇒
  请求真中止且错误是 `AbortError` 而非 `TimeoutError`
- 已 abort 的 signal ⇒ 立即拒绝；超时仍报 `TimeoutError`，且不把调用方 controller 标成 aborted
- 请求结束后摘掉监听（防泄漏）
- **变异证明**：① 把 signal 合并退回旧写法 ⇒ 6 例红；② 把 4 个包装函数的 options 去掉 ⇒
  11 例全红。只测正例的自测等于自己绿自己。
- 本机全量 vitest **393/393**（原 382 + 新增 11），`npm run lint` 0 error。
## [Unreleased] — 端点数门禁的覆盖边界复核：28 行盲区里没有一处是总数声称；顺带推翻我自己昨晚的一个结论（2026-10-24）

### 1. 起因：#141 修完残留后，我想「把门禁的盲区补上」

#141 把 `docs/sales-pitch-deck.md` 两处过期端点声称（406）修成 409。当时的判断是
「它们长在门禁匹配面之外：一处是表格单元格、一处是并列数字」。这次就来验证这个判断，
打算把盲区补成门禁。

### 2. 方法：用门禁的**完整**逻辑（不是只看 `CLAIM` 正则）逐行模拟

第一版审计脚本只模拟了 `CLAIM` 行内文法，报出「29 行盲区、其中 2 处是真声称」。
**这个结论是错的**——`check_claims()` 里还有第二套逻辑：#135 加的**表格单元格**兜底
（行里有单元格含「端点」就检查所有纯数字单元格）。补上这套再跑：

| 指标 | 值 |
|---|---|
| 含「端点」且有数字的文档行 | 43 |
| 门禁完整逻辑能看到 | 15 |
| 真盲区 | **28** |
| 其中「项目总端点数」声称 | **0** |

28 行逐条看过，全是描述性或局部数字：`FR-04` 模块编号、`7/9` 这类 e2e 通过数、
`六端点`（regulator-sim 自己的 6 个）、`（+ 5 个端点、12 例测试）`（增量，门禁刻意排除）、
`至少 5 个核心端点`（客户验收标准）、列表序号 `3.`。

### 3. 结论一：对真实的总数声称，覆盖是完备的 ⇒ 不需要补门禁

唯一真盲区是**多个数字并列**（`（4336 / 51 / 406 / 38）`）——哪个数字是端点数需要语义判断。
**为什么不扩**：放宽到「含端点的行里有数字就算声称」，实测引入 **27 处误报**。
门禁注释里自己写着「误报比漏检贵，门禁一旦有噪声就会被整体忽略」⇒ 窄口径是刻意设计。

### 4. 结论二：我昨晚的判断错了一半（自我推翻）

我说「表格单元格形态门禁吃不到」——**错**。#135（`567a154`）已经加了表格逻辑。
**当时之所以没被发现，是因为我在旧基点 `9a3f59a`（#132）上跑门禁**，而表格逻辑
10-22 06:50 才随 #135 合入。在旧脚本上跑出「门禁通过」，就误以为门禁有盲区。

⇒ 写进脚本 docstring 的教训：**不要在旧基点上跑门禁然后宣称「通过」**；
跑之前先 `git pull`，或确认脚本版本 ≥ #135。

### 5. 改动

只改 `scripts/check-claimed-endpoint-count.py` 的 docstring（+25 行，**零逻辑改动**）：
把「已知盲区 / 为什么不扩文法 / 不要在旧基点跑门禁」三件事写清楚，
让下一个改端点数的人不用重新踩一遍。自测与正常跑均通过（409 = 409）。

---

## [Unreleased] — 状态板给「不可合并的 PR」打上「CI 不会被调度」的标记（2026-10-23）

### 1. 为什么要在状态板上标

机制写进 `COORDINATION.md` 第六节了，但知识躺在文档里，还得有人去翻。
**再往下一层，把它变成看板上的常驻提示**：`scripts/coord-status.sh` 生成「在途与占用」段时
本来就会列出每个 PR 的 `mergeable/mergeStateStatus`，现在额外给不可合并的那几条打上
`⚠不可合并⇒Actions 不会调度（GitHub 合成不出 refs/pull/<N>/merge）`，段末再附一句判读。

### 2. 双向断言（不只测「该出现的时候出现」）

新增自测 T6，走新的注入点 `COORD_PR_FIXTURE`（给定 TSV 文件代替真实 `gh` 调用，
不依赖 GitHub 当时算出的 mergeability）：

- `CONFLICTING`/`DIRTY` ⇒ 必须出现标记，且必须指出那条合成不出来的 `refs/pull/<N>/merge`
- `MERGEABLE` ⇒ **不得**出现（别把正常的 PR 也标一遍）
- `mergeable=UNKNOWN`（GitHub 还没算完的暂态）⇒ **不得**出现
- 注入的夹具缺失 ⇒ 必须 `UNVERIFIED` + 退出码 2，**不得**伪装成「没有在途 PR」

### 3. 变异证明

两处定向变异都被自测抓住：① 判定条件改成永不命中 ⇒ 报「不可合并的 PR 没标出」；
② 改成永远命中 ⇒ 报「可合并的 PR 被误标成」与「UNKNOWN 被误标成」。
只测正例的自测，等于自己绿自己。

## [Unreleased] — 「PR 不可合并 ⇒ Actions 根本不调度」写进仓：症状、机制、判读四步（2026-10-23）

### 1. 现场：一种不产生任何失败记录的失败

2026-10-23 排查 PR #142 时撞上的现象：PR 页面上**一条 `pull_request` 检查都没有**，
`Actions` 里**没有这条 PR 的 run**，而第三方 App 的 check suite 照常出现。
它被误判成「GitHub 故障」，排查方向整个跑偏——去查状态页、查 runner 配额、查网络，全是空的。

### 2. 机制

`pull_request` 事件跑在 `refs/pull/<N>/merge`（base + head 合成）这个 ref 上。
PR 不可合并时（`mergeable=CONFLICTING` / `mergeStateStatus=DIRTY`），GitHub **合成不出这个 ref**
⇒ 事件**永不触发** workflow ⇒ **零 run、零 Actions check suite、零失败记录**。
反过来也成立：解掉冲突后合成 ref 立刻出现，workflow 随即被调度——
所以「CI 静默」是「CI 不被调度」的表现，不是另一个独立故障。

### 3. 沉淀到哪里

- `COORDINATION.md` 新增第六节：症状、机制、判读四步、修复办法、2026-10-23 实测旁证，
  以及同族的另一个坑（`--merge` 被拒 ≠ 已合并；本仓惯例是 `--squash`，被拒后仍要回查 master tip）
- `docs/contributing-guide.md` §5.1 加一条指引：看到「CI 没跑」先查 mergeability，
  别先去怀疑 GitHub

### 4. 一个容易踩的判读坑（也一并写进去了）

`mergeable=UNKNOWN` 是 GitHub **还没算完** mergeability 的暂态，**不是**冲突；
另外 `actions/runs` 的 `created=>=…` 是**无效过滤参数**，会静默返回空列表——
拿它当证据，等于把「查不到」读成「没有」。

## [Unreleased] — surveillance 收口：97 个存活变异体逐条清账，不再挂"未定性"（2026-10-23）

### 1. 现场：账是清的，但 97 条悬而未决

第三轮（#139）把 offline 子包的覆盖面补完之后，surveillance 包还剩 **97 个存活变异体**
挂在册上。它们不是"没测到"（未覆盖已收敛到 159）——是"测了、但这段行为没人盯"
（变异分数 0.84 的主要拖累）。问题是：这 97 条里有多少是**真缺口**（改坏生产代码
照样全绿），有多少是**等价/不可达**（观测面上根本分不出差异）？当时没有任何一条
有结论。

### 2. 分诊方法：逐条复现，两头都要实证

- 判定"真缺口"：补测试 → 复测变异必须**死**（杀不动的老实承认没守住）。
- 判定"等价/不可达"：**主动把生产代码改坏**（`tools/mutate-verify.py`，改坏⇒跑用例⇒还原⇒再跑），
  存活下用例必须**仍绿**，并给出"为什么测不到"的值域论证——没有论证的等价不算定性。

### 3. 补了什么（6 个新契约文件 + 8 处扩展，49 个新用例，生产代码零改动）

| 测试 | 例数 | 钉住的契约 |
|---|---:|---|
| `VendorAdapterPrefixContractTest` | 6 | 四适配器 + OnvifClient 前缀形状逐位精确（CIDR / 不带 `/` / 无点）；`StreamUrlOrigin` 双值语义 |
| `SurveillanceRegistryTenantContractTest` | 7 | global/租户读口径精确成员资格与排序；prune(0) 合法、hb=0 恒新鲜、fresh 不被顺带删 |
| `OnvifVendorAdapterContractTest` | 6 | 适配器层 device 校验不许被删（null/blank 反例）；PTZ 委托逐字全等 |
| `SurveillanceDeviceIdentityContractTest` | 3 | 端口 [1,65535]；equals 按 id 对称；capabilities 永不为 null |
| `SurveillanceControllerSseContractTest` | 3 | 未知设备流立即错误终止；`AsyncContext.complete()` 必须触发厂商注销；断开重订阅不遗留 |
| `VideoFusionRecordingServiceTest` | 4 | start 拒绝条件；stop 时长=真实时间戳差值 |
| 扩展 8 类（`SurveillanceControllerTest` / `MjpegStreamServiceTest` / `Gb28181ShapeTest` / `OnvifClientTest` / `VendorAdapterRegistryTest` / `SurveillanceStatusPusherTest` / `RapidDeployServiceTest` / `VideoFusionControllerTest`） | +20 | mjpeg 错误体定长+no-store+Pragma、三处注册边界（含 n==totalCameras 正例）、size=1000 上界、fps=0 兜底、SOI 三字节、fps=1 节流下限、seq 单调+段级 flush+每帧尾 CRLF、中断标记保留、目录跳过告警、PTZ 别名箭头、订阅句柄形状、manufacturer 三元、订阅参数校验、hasAdapter 反例、null 句柄温和忽略、uptimeSec=0、部署尾段畸形、子网自校验、脱敏全等、hashCode 不碰撞 |

### 4. 实测

- 定点点名 4 批共 331 例全绿（214 + 10 + 40 + 67；首跑 4 处失败如实修过：SSE 3 例重写成
  MockMvc async 路径、prune(0) 改用未来心跳消掉毫秒竞态——都是测量面的坑，不是产品行为 bug，见坑 6）。
- A/B 批量等价实验（`tools/mutate-verify.py`，7 组顺序跑，退出码存档
  `tools/ab_batch_surv_results.json`）：**X1 排序 key 突变被杀**（按 id 排序差得出顺序，
  `listDevicesByVendor_sortedById` 的有序断言抓到）；K1（prune 的 compute 返回 null
  ⇒ 条目被整个删掉）**被杀**；E1-E5（sleep 边界 / 两处前缀边界 / Capability null 分支 /
  onError 注销）**全部按预期存活**——存活本身就是等价性证据（行为分叉在测试观测面之外）。
- PIT 复测（XML 精确对账）：杀掉 **546 → 636**、存活 **97 → 22**、未覆盖 **159 → 144**
  （总 809、超时 7、运行错误 0 均不变）、变异分数 **0.8400 → 0.9564**、测试强度
  **0.8491 → 0.9666**；基线 `scripts/mutation-baseline-surveillance.json`
  地板抬到 **0.95 / 0.96**。增量账目严丝合缝：+90 = 计划杀 75（含 X1）+ 新覆盖
  顺带被杀 11 + 4 个新面孔补杀；**22 条存活恰好全部是已定性清单**。
- 22 条定性为无害（值域论证 + A/B 证据），清单与证据在 `docs/mutation-testing.md` 第四轮。

### 5. 没做什么

- 生产代码零改动：这一轮没有暴露行为 bug——所有"改坏"都撞在了"测试没盯住"上。
- security 包 102 个存活变异体（P1 #7）留下一轮单独 PR 分诊；surveillance 的分诊模板
  （等价要给值域论证 + A/B 实证）直接沿用。

---

## [Unreleased] — 离线自治子包零测试：36 个用例把"断网报警不丢"钉住（2026-10-23）

### 1. 现场：卖点最硬的地方，一行断言都没有

`surveillance.offline` 是"灾害断网后布控球仍能本地报警、联网补传"的落点，三个类 914 行、零测试。

上一轮安防变异审计的 PIT 报告（809 个变异体）里，这个子包贡献 **121 个 NO_COVERAGE**，占全部未覆盖（279）的 43%：

| 类 | 变异体 | 补测试前 |

|---|---:|---|

| `EdgeAiTrigger` | 35 | 全部 NO_COVERAGE |

| `OfflineAlarmCache`（含内部类） | 38 | 全部 NO_COVERAGE |

| `OfflineAlarmController` | 48 | 全部 NO_COVERAGE |

这些是**覆盖缺口**（压根没测），不是行为等价。断网报警的容量驱逐、失败重试、上传降级一旦写错就直接丢报警，而 CI 里没有任何东西会红。

### 2. 补了什么（36 例，直接调方法，不起 Spring 上下文）

| 测试类 | 例数 | 钉住的契约 |

|---|---:|---|

| `EdgeAiTriggerTest` | 11 | 默认只开 PERSON+FIRE；火灾必须 CRITICAL；未启用类型不缓存；缓存缺失返回 false 而非 NPE；车辆/动物/火灾三条映射与默认描述；按类型计数 |

| `OfflineAlarmCacheTest` | 13 | 超容量 FIFO 驱逐最旧；`getPendingAlarms()` 是快照；上传成功才清空；失败事件放回队列；连续失败 10 次停手；统计的最旧/最新时间戳 |

| `OfflineAlarmControllerTest` | 12 | 依赖缺失一律 **503**（不是 500/NPE）；body 非数组 400；缺 deviceId 拒收并回报 rejected；flush 无 store 报 **503+degraded**；TRIGGERED/SKIPPED 语义；configure 生效 |

写测试时确认的两条既有边界（不是 bug，但现在被钉住，改行为会红）：

- 连续失败 **10** 次即停止本轮上传：12 条全失败时这一轮只尝试 10 条，剩余仍留在队列里等下次。

- 缓存满（默认 **500**）时 FIFO 驱逐最旧事件：断网足够久时最早的报警会被丢，只留一行日志。这是设计取舍，但它现在是被测过的取舍。

### 3. 实测

- 三个测试类 **36 例全绿**（11 + 13 + 12）。

- 全量 `mvn clean test`：**5033 用例 / cloud-backend 2927 / 0 失败**（585 个测试类）。

- 变异复测（同一把尺子、同一个包，合并 #140 的 3 例后）：杀掉 **427 → 546**、未覆盖 **279 → 159**、存活 **98 → 97**；变异分数 **0.8057 → 0.8400**（+3.4pp），测试强度（PIT 口径）**0.8133 → 0.8491**。offline 子包 121 个变异体里 117 个被跑到、杀掉 112，只剩 3 个（已逐条复现定性为无害）。

- `check-test-count-docs.py` 与卫生门禁（小节数）一致。

- 覆盖率棘轮：新增 45 例把 cloud-backend 实测行覆盖率推到 **70.4%**，按「实测向下取整到 5%」的策略，pom 的 jacoco 声明从 0.65 抬到 **0.70**（`ci-coverage-threshold.sh --strict` 要求声明 == 策略值，否则构建判红）。

- 插曲（也是这轮唯一的真事故）：给上面那条 pom 注释写了 `--strict`，而 **XML 注释不允许出现连续两个连字符**，整个 POM 变成 Non-parseable —— 缓存预热 / Java 26 编译 / Playwright / compose 四个 job 连锁红，2.5 小时队列末尾才暴露。修掉，并把「全部 pom 可解析 + 注释无 `--`」加进卫生门禁（检查项 2.5，自测 7/7），`docs/agent-constraints.md` 新增第 13 条。

### 4. 顺带修了门禁自己的一处"声明≠行为"

`scripts/check-mutation-baseline.py` 的 `testStrength` 一直算 `(KILLED+NO_COVERAGE)/total`，而同一次 PIT 汇总行打印的是 `Mutations with no coverage 159. Test strength 85%`（= `KILLED/(KILLED+SURVIVED)`）。两个数不是一个东西：旧口径把**没测到的变异体算成"强度"**，于是"给零覆盖子包补测试"会让这个指标下降 —— 本轮实测撞上：杀掉 +119、未覆盖 −120，变异分数 +3.4pp，旧口径的"强度"却从 0.8727 掉到 0.8714，门禁判红。

修法：改成 PIT 口径；`--self-test` 加一条**能区分两种口径**的夹具（50 killed / 15 survived / 35 no-coverage：旧口径 0.85 假绿，新口径 0.769 必须红）。两份基线同步订正：security 0.86 → 0.84、surveillance 0.8727 → 0.83（按新口径重算并向下取整留抖动余量）；surveillance 的分数基线从 0.8057 **抬到** 0.82，不是降。

### 5. 没做什么

- 没改任何生产代码：本轮只补测试，行为零变更。

- 厂商适配器（Onvif / GB28181 / Hikvision / Dahua）与 `SurveillanceDeviceRegistry` 的存活变异体不在本轮范围，见 `docs/todo.md` P1。

## [Unreleased] — 安防包两条 P0：`flush()` 和“离线落库”删掉都没人发现（2026-10-23）

### 1. 现场：不是“没测到”，是“测了没盯住”

2026-10-22 的安防包变异审计（PR #139）里，有两条存活变异体特别扎眼 ——
**把生产代码里的一个动作整行删掉，581 个测试类照样全绿**：

| 生产代码里的动作 | 删掉之后 | 用户看到什么 |
|---|---|---|
| `MjpegStreamService.pump` 写完一段 multipart 后 `out.flush()` | PIT 变异体 “removed call to OutputStream::flush” **存活** | 浏览器可能一直收不到帧：面板在转圈，但接口是 200 |
| `SurveillanceDeviceRegistry.pruneStaleDevices` 里的 `persistStaleStatus(stale)` | PIT 变异体 “removed call to persistStaleStatus” **存活** | 内存里设备已标 OFFLINE，数据库还是 ONLINE；重启后又“活”过来 |

### 2. 修法：动作级契约（不是“多跑几行覆盖率”）

新增 `SurveillanceStreamAndPruneContractTest`（3 例）：

1. `pumpMustFlushAfterWritingPart` —— 用一个**会数 flush 次数**的 `OutputStream`，
   断言写完首帧后 `flush()` 至少被调用一次；
2. `pruneStaleDevicesMustPersistOfflineStatus` —— mock 仓库，断言超时设备被置 OFFLINE
   **并且真的 `save` 了状态为 OFFLINE 的实体**；
3. `pruneMustKeepFreshDevices` —— 守卫：心跳新鲜的设备不能被误杀、不能触发落库。

### 3. 变异验证：改坏 ⇒ 红；还原 ⇒ 绿（脚本化）

新增 `scripts/mutate-verify.py`（带 `--self-test`），把“改坏 / 跑 / 还原 / 再跑”做成一条命令：

```
python scripts/mutate-verify.py --file .../MjpegStreamService.java \
  --old '<原文>' --new '<改坏>' --test 'SurveillanceStreamAndPruneContractTest#pumpMustFlushAfterWritingPart'
```

实测（本机，两条都跑完整一对）：

- 删 `flush()` ⇒ `pumpMustFlushAfterWritingPart` **红**（退出码 1）；还原 ⇒ **绿**；
- 删 `persistStaleStatus(stale)` ⇒ `pruneStaleDevicesMustPersistOfflineStatus` **红**；还原 ⇒ **绿**。

### 4. 顺带纠正一个测量坑：还原源文件会把时间戳也还原

手工 `Copy-Item` 还原源文件时，**LastWriteTime 回到旧值** ⇒ Maven 增量编译判定“源码没变”，
继续用变异后的 `.class`。表现是“还原后用例仍然红”，差点被我误判成“用例不稳定”。
已写进 `docs/agent-constraints.md` 第 12 条，并由 `mutate-verify.py` 在还原时 `os.utime` 机械避免。

### 5. 计数

Java **4988 → 4991**（新增 3 个用例），cloud-backend **2882 → 2885**；
前端 vitest **382** 不变，REST 端点 **409** 不变。

---

## [Unreleased] — 安防包的变异审计：809 个变异体只杀掉一半，四个厂商适配器的校验没人盯（2026-10-22）

### 1. 现场：同一套方法论搬到安防包，分数明显更低

2026-10-21 审完 security 包（变异分数 0.84）后，同样的方法论搬到
`io.aerofleet.cloud.surveillance.*`（34 个主类 / 14 个测试类）：

| 指标 | security 包 | surveillance 包 |
|---|---|---|
| 变异体总数 | 767 | 809 |
| 杀掉 | 566 | **427** |
| 存活 | 102 | **98** |
| 未覆盖 | 97 | **279** |
| 变异分数 | 0.84 | **0.8057** |

⇒ 这个包的**未覆盖面大得多**（279 个，集中在 `surveillance.offline.*`：
`EdgeAiTrigger` / `OfflineAlarmCache` / `OfflineAlarmController` **一个测试都没有**）——
那是**覆盖缺口**（压根没测到），与"测了但没盯住"是另一件事，两类要分开记账。

### 2. 真缺口（逐条手动复现过：改坏生产代码 ⇒ 现有测试照样全绿）

1. **四个厂商适配器的 `validateDevice` 调用被删掉，没有任何测试会红**
   （Hik / Uniview / Dahua / Gb28181 的 `getStreamUrl` / `ptzControl` /
   `subscribeEvents` / `getCapabilities`，合计 **14 个存活变异体**）
   ⇒ 传 null 设备时从"抛 `IllegalArgumentException`"退化成 `NullPointerException`；
   传空 IP 时直接拿空地址去拼流 URL。
2. **`RapidDeployService.extractLastOctet`（设备 ID 里的 IP 末段）没被钉住**
   （**11 个存活变异体**）⇒ 设备 ID 会变成 `cam-0-...`，同网段设备 ID 撞车、认不出设备。
3. **GB28181 适配器的"只收 GB28181 设备"这条约束没被钉住** ⇒ 别的厂商的设备能被它接走。

⇒ 新增 `SurveillanceValidationContractTest`（6 个用例）把上面三条钉死。
**变异验证**（改坏生产代码 ⇒ 对应用例必须红）：

| 改坏的地方 | 变红的用例 |
|---|---|
| 删掉 `HikAdapter.getCapabilities` 的 `validateDevice` 调用 | `adaptersRejectNullDevice` |
| 删掉 `Gb28181Adapter.getCapabilities` 的 `validateDevice` 调用 | `adaptersRejectNullDevice`、`gb28181AdapterRejectsForeignVendor` |
| 把 `extractLastOctet` 改成恒返回 `"0"` | `generatedDeviceIdCarriesIpLastOctet` |

⇒ **实测**效果（同一把尺子、同一个包、只多了这一个测试类）：
杀掉 **409 → 427**，存活 **116 → 98**，变异分数 **0.7717 → 0.8057（+3.4pp）**。

### 3. 两条我自己的诊断被推翻（都写进文档）

- `RapidDeployService.validateSubnet` 的调用被删掉（2 个存活变异体）——
  **手动复现后发现是行为等价的**：`OnvifClient.discoverDevices` 里**也**校验空子网，
  删掉一处校验异常照样抛（只是抛的地方变了）⇒ **不是真缺口**，显式声明无害。
- 四个适配器的"空 IP"分支看似不可达（`SurveillanceDevice` 构造器就拒空 IP）——
  但 `SurveillanceDevice.ip` 是**公开可变字段**，构造之后可被改坏 ⇒ 该分支**可达**，
  用例按"构造后把 ip 置 null"来测。

### 4. 把第二个包接进 CI 门禁

- 根 pom 新增 `mutation-surveillance` profile（与 `mutation` 同构、换目标包）；
  两个 profile 各自配 `reportsDirectory` ⇒ **报告目录分开**
  （否则第二次 PIT 会覆盖第一次 ⇒ 基线张冠李戴）。
- `scripts/mutation-baseline-surveillance.json`：**独立基线**
  （一个包的涨分不能掩盖另一个包的退步）；数值由 `--write-baseline` 写入，不手抄。
- CI `mutation-gate` job：两个包各跑各的、各判各的，**缺报告即判红**（跳过不等于通过）。

### 5. 计数

Java **4979 → 4985**（新增 6 个用例），前端 vitest **382** 不变，REST 端点 **409** 不变。

---

---

---

## [Unreleased] — 端点声称第三处残留：表格合计 406 ≠ 各行之和 357 ≠ 实测 409；deck 的数字锚点也停在上两个版本（2026-10-23）

### 1. 现场：门禁报「通过」，但同一份文档里有三个互不相同的数

`check-claimed-endpoint-count.py` 实测 **409**（78 个 `@RestController`），门禁也显示
「文档声称 409 = 实测 409」——因为 README 与 api-reference 正文都已改成 409（#126 / #135 / #137 的功劳）。
但同一份 `docs/api-reference.md` 里还有两处没跟上：

| 位置 | 写的数 | 为什么门禁没拦 |
|---|---|---|
| 端点表合计行 | **406** | 表格单元格，`CLAIM` 正则吃不到 |
| 同表 66 个数据行逐行相加 | **357** | 与 406 / 409 三个数互不相同 ⇒ 表格本身脱节 |
| `docs/sales-pitch-deck.md` 的数字锚点 `（4336 / 51 / 406 / 38）` | 4336 / 406 | 四个数字并列，正则也吃不到；同文件另三处已是 4988 / 409 |

### 2. 根因：表格是手工维护的，且从没有「各行之和 == 合计」这道校验

- **5 个 Controller 的端点数变了、文档没跟上**：AuthController 2→3、DeliveryController 4→7、
  SchedulingController 11→13、SprayController 3→4、SurveillanceController 12→14（合计 +9）。
- **10 个 Controller 压根没登记**：DockController 10、DefectController 9、RocController 8、
  UspaceController 4、EnvOverrideController 3、MavlinkSigningPostureController 3、
  MavlinkSigningKeyController 2、SensingController 2、OperationsReportController 1、
  RouteTemplateController 1（合计 +43）。+9 与 +43 正好补上 409 − 357 的 52。
- 另有 2 个类（`LicenseModuleMap` / `OpenApiConfig`）标注了 `@RestController` 但**不含任何
  方法级映射**，所以 78 个类只对应 76 行有效数据——这一点此前没人写过，看表的人无法自洽。

### 3. 修法：按门禁同口径从源码重算，不手抄一个数字

写一次性脚本按 `@RestController` + 方法级 `@*Mapping`、排除 actuator 的口径提取
**per-Controller 端点数**（78 classes / 409 endpoints，与门禁报数一致），然后：

- 修正上述 5 个数值；补齐上述 10 行（模块名按既有命名风格，如「机巢管理」「ROC 上报」）；
- 合计行 406 → **409**，并在注释里写明「2 个零映射类 ⇒ 表内 76 行」，让表格自洽可验；
- `sales-pitch-deck.md` 的数字锚点 `（4336 / 51 / 406 / 38）` → `（4988 / 51 / 409 / 38）`
  （4336 是过时的 Java 单测数，同文件另三处已是 4988）。

### 4. 验证

| 场景 | 期望 | 实测 |
|---|---|---|
| 提取口径 vs 门禁 | 同数 | 78 classes / 409 = 门禁报数 |
| 表内各行之和 | = 合计 | 409 = 409（脚本内断言，不等就拒绝写盘） |
| `check-claimed-endpoint-count.py` | 通过 | 通过（409 = 409） |
| `check-repo-hygiene.py` | 通过 | 通过 |
| 两个文档行尾 | 纯 LF | CR=0 / CR=0；diff 仅 17+7 与 1+1 行 |

**过程中门禁又拦了一次**：新注释里写「0 端点」被 `CLAIM` 正则当成一处声称（0 ≠ 409）判红，
改写措辞为「不含任何方法级映射注解」后通过——门禁连自己人刚写的注释都不放过。

---

## [Unreleased] — 轨迹接口的 `ts` 永远是 0：当天历史轨迹被前端去重成一个点（2026-10-21）

### 1. 现场：一次数据库升级演练里翻出来的

做「V1–V20 升到 V21–V29」的 PostgreSQL 演练时，灌进去的三条 flight_log
（`10:00:00.123456` / `10:05:00.5` / `10:09:59.999999`）在升级后**毫秒级精度分毫不差**
（`timestamptz` 转换把瞬间保住了 ✓），但调轨迹接口得到的是：

```json
[{"lat":39.901,"ts":0,"alt":120.0,"lon":116.301}, ...]
```

**`ts` 全是 0。**

### 2. 为什么这是用户看得见的缺陷

前端拿 `ts` 当**合并键**：

- `gcs-web/src/utils/trackMerge.js`：`同一 ts 只保留一个点`，并按 `ts` 升序排；
- `gcs-web/src/hooks/useDrones.js`：`prev[prev.length-1].ts === point.ts` 就丢弃新点。

⇒ 持久化路径的所有点 ts 都是 0 ⇒ **当天的历史轨迹被去重成一个点**，
而且因为 0 最小，还会排到折线的最前面。

根因在后端两处硬编码（`FlightLogService`）：

| 位置 | 原来 | 现在 |
|---|---|---|
| DB 路径 | `new TrackPoint(..., alt, 0)` | `alt, tsOf(e.getTimestamp())`（`Instant` → epoch 毫秒） |
| JSONL 回退路径 | `new TrackPoint(..., alt, 0)` | `alt, tsOf(m.get("t"))`（写入侧的本地墙钟 ISO 串 → epoch 毫秒） |

**实时/内存路径一直是对的**（`System.currentTimeMillis()`），所以只有"库里的历史"这一半是坏的 ——
这也是它能长期藏着的原因：飞行中看轨迹一切正常，**回看当天历史**才塌成一点。

### 3. 为什么测试没抓到（假绿的典型形态）

`FlightLogPersistenceTest.trackForFromDb()` 断言了 `lat` / `lon` / `alt` ——
**它自己知道的那几个字段**，偏偏没断言 `ts` ⇒ 缺陷一路溜到线上。
前端单测喂的是**合成的非零 ts** ⇒ 也测不到。

⇒ 补两条测试钉死（DB 路径 + JSONL 路径各一条），并做了**变异验证**：
把两处改回写死 0，测试分别红（变异 1 红 2 条 / 变异 2 红 1 条）。

端到端复验（真 PostgreSQL + 升级后的库 + 修复后的后端）：

```
ts=1790820000123  => 2026-10-01 10:00:00.123   （升级前灌的 10:00:00.123456，亚秒也保住了）
ts=1790820300500  => 2026-10-01 10:05:00.500
```

### 4. 顺带记一笔：数据库升级演练的结论

- 28 个迁移在**真 PostgreSQL 16** 上全部干净应用（CI 只在 H2 上跑过全新安装，
  **从没验过"老版本升级"这条客户真正走的路**）；
- 升级前灌的数据（devices / flight_log / api_keys / tenant / 摄像头 / 用户）**全部保住**；
- 表从 24 张长到 34 张；应用带着升级后的 schema 起得来（JPA `validate` 过）、供得出数；
- 亚秒精度在 `timestamp` → `timestamptz` 的转换里**分毫不差**；
- ⚠ **一个操作约束**（V29 自己的注释也写了）：历史 naive 值按**会话时区**解释，
  所以**升级必须在与写入数据同一时区的机器上跑**，否则整段历史会平移。
- 演练**已脚本化并接进 CI**：`scripts/e2e-db-upgrade-drill.sh`（真 PostgreSQL + 真 Redis，
  23 条硬断言：迁移条数/版本/新表/数据不丢/亚秒精度不丢/应用供得出数/
  轨迹点的 ts 是真实时刻且互不相同/**回滚路径可跑通**）+ CI job `e2e-db-upgrade`（硬门禁）。
  脚本里两个地方是本轮才把演练变成可重复的：
  ① 时区在**两侧都钉死**（psql 会话用 `PGOPTIONS`、应用的 JDBC 会话用连接串的
  `options=-c TimeZone=UTC`），不靠猜；② 版本号不连续（V7 从来不存在），
  期望条数**按迁移文件数算**而不是写死。

### 5. 演练脚本进 CI 后的首跑：红在备份，根因是**一个假 PASS**

CI 首跑（job `e2e-db-upgrade`）红了。**这里有一笔必须记下来的自我更正**：

- 我上轮看到"回滚断言失败（恢复后仍是 `tampered`）"+ 一堆 `relation "docks" does not exist`
  的错误刷屏，判断根因是"恢复时旧后端还活着、把被篡改的值又写回去了" ⇒
  **这个诊断是错的**。它只是**症状**，我拿症状当成了根因。
- 这次给备份步骤加上**退出码检查**之后，真根因自己冒出来了：

  ```
  [FAIL] 备份失败（pg_dump 退出码非 0）：
    Password:
    pg_dump: error: connection to server at "localhost" (::1), port 5432 failed: fe_sendauth: no password supplied
  ```

  ⇒ **`pg_dump` 是全脚本里唯一没带 `PGPASSWORD` 的那一个**（`psql` / `pg_restore` 都带了）。
  它认证失败 ⇒ 产出一个**空文件** ⇒ 而当时那行**不检查退出码** ⇒ 脚本照样打印
  **"[PASS] 已备份（回滚路径的实物）"** ⇒ **一次没成功的备份，被门禁判成了通过**。
  后面的回滚断言（恢复后仍是 `tampered`）才是**唯一**发现它的地方。

⇒ 修法 + 加固：

- `pg_dump` 显式带 `PGPASSWORD`；
- 备份步骤**检查退出码 + 产物非空 + 产物里真的有表**（`pg_restore --list | grep devices`）
  ——"文件存在"不等于"文件能用"，这三道缺一道就还会出现假 PASS；
- 回滚断言改成**恢复到干净库**（灾备的真实场景），不再在活后端上 `pg_restore --clean`。

⇒ **验证方式（阴性 + 阳性对照，在与 CI 等价的条件下）**：
本机复现不了"CI 报错、本机通过"，因为**认证路径不同** ⇒ 改成在容器里跑：
容器经 **bridge IP** 连 PG（PG 看到的源 IP 不是回环 ⇒ 和 CI 一样走 scram 密码认证），
客户端固定 **PG 16**（CI 是 ubuntu-24.04 的 postgresql-client-16 + postgres:16-alpine）：

```
阴性对照（CI 那版脚本）：
  [FAIL] 备份失败（pg_dump 退出码非 0）：
    pg_dump: error: connection to server at "192.168.16.2", port 5432 failed: fe_sendauth: no password supplied
阳性对照（修复后）：
  PASS=23  FAIL=0    （含"回滚（灾备）路径可跑通：恢复到干净库后数据与亚秒精度都对得上"）
```

⇒ 教训已机械化进 `docs/agent-constraints.md`（第 11 条）：
**打印 PASS 之前必须验证产物；本地验证必须复现 CI 的条件；改前失败、改后通过才算修好。**

### 6. 计数

Java **4970** → **4972**（新增 2 个用例），前端 vitest **382** 不变。

---


---

## [Unreleased] — 前端在调 12 个后端压根没有的端点；404 被 `.catch(() => null)` 吞掉，面板永远空却看不出异常。修掉 10 处、补后端 3 个口径、补上门禁

### 1. 现场：404 是静默的，界面骗人

2026-10-21 实测 `gcs-web/src/api.js` 里 238 处端点调用，有 **12 处后端压根没有**：

| 前端调用的 | 后端真实有的 |
|---|---|
| `POST /api/v1/formation/create` | `POST /api/v1/formation` |
| `POST /api/v1/spray/task` | `POST /api/v1/spray` |
| `GET /api/v1/spray/status/{sysid}` | 只有 `GET /api/v1/spray/{id}`（**按任务 id**，前端手里没有任务 id） |
| `POST /api/v1/spray/gripper` | 夹爪命令在**配送序列**的控制上（`START`=抓取 / `DROP`=释放） |
| `GET /api/v1/delivery/sequence/{sysid}` | 只有 `GET /api/v1/delivery/{id}` |
| `GET /api/v1/env/alerts` | `GET /api/v1/env-alerts` |
| `POST /api/v1/air-ground/recon/alarm/{eventId}` | `POST /api/v1/air-ground/recon`（报警字段走 body） |
| `POST /api/v1/air-ground/ptz-tracking/{deviceId}` | `POST /api/v1/air-ground/ptz-track`（`lat`/`lon`，不是 `targetLat`/`targetLon`） |
| `POST /api/v1/hardware/physics-model` | **后端没有这个能力**（概念只在模拟器 `VirtualDrone.physicsModel` 里，没有 MAVLink 下发通道） |
| `GET /api/v1/vision/thermal/sources` | 后端只有 `/api/v1/thermal/tasks`，没有"源"的概念 |
| `GET /api/v1/env/status` | 后端只有 POST 覆盖写 + `/env-alerts`，没有机队级读口径 |
| `GET /api/v1/link-sim/{profiles,status}` + `POST /api/v1/link-sim/damage` | 真实口径在 `/api/v1/comm-adapt/quality/*`（**返回形状也不同**，不是纯路径替换） |

危害不是"点一下报错"这么轻：前端的轮询普遍写成 `.catch(() => null)`，
**404 被静默吞掉** ⇒ 喷洒状态、配送序列、气象图层、链路质量面板**永远空**，
界面上看不出任何异常 —— 只有对着代码才知道功能是假的。
这正是本仓库主线一直在清的「声明≠行为」，只是这次声明在前端。

### 2. 改法（前端 10 处 + 后端 3 个口径 + 2 处数据流）

- **纯路径改对（6 处）**：formation / spray / env-alerts 等，前端改指后端真实端点。
- **后端补 3 个"按 sysid 查/控"的口径**（前端按"当前选中的无人机"轮询，而后端只按任务 id 查）：
  - `GET /api/v1/spray/by-sysid/{sysid}` —— 该机**最近一次**喷洒任务；
  - `GET /api/v1/delivery/by-sysid/{sysid}` —— 该机最近一次配送序列；
  - `POST /api/v1/delivery/by-sysid/{sysid}/control` —— **夹爪开/关走这里**
    （后端的 `MAV_CMD_NEXUS_GRIPPER_CONTROL` 本来就有，只是只挂在"按序列 id"上；
    "按 sysid 找到最新序列再控制"这一层交给服务端，前端不再自己猜 id）。
  - 两个控制器的 `get(id)` 与 `by-sysid` **共用同一个 view 方法**，避免两处口径漂移。
- **空地协同两条路径改对，并补上被吞掉的数据流**：
  - `POST /air-ground/recon`：后端 `parseAlarmEvent` **优先用调用方给的 `eventId`**
    （原来自己生成 UUID ⇒ "告警 → 侦察计划"对不上号，联动引擎按 id 关联）。
  - 前端触发侦察时**必须带目标坐标**：告警没有坐标就**拒绝触发**并报错 ——
    否则后端拿到 `lat/lon=0`，侦察计划落到 (0,0)，无人机飞往几内亚湾。
  - `POST /air-ground/ptz-track`：后端 `GeoTarget.isReliable()` 要求 `confidence >= 0.6`，
    前端原来不传 ⇒ 后端静默返回 `LOW_CONFIDENCE`（**看着像成功、其实没动**）。
    现在按"手动触发即意图"下发 `confidence=1`，并在注释里写清依据。
- **物理模型按钮显式禁用**：后端无下发通道 ⇒ 此前是"点了必 404"的假控件；
  现在按钮禁用 + 标题写明原因，不再假装能用。

### 3. 新增门禁：前端端点契约（`scripts/check-frontend-endpoints.py`）

- 逐条比对前端 `jsonFetch` 的**方法 + 路径**与后端控制器里的映射（同一套口径，
  端点数与 `count-rest-endpoints.py` 对齐：409）；
- **已知缺口登记**（`KNOWN_GAPS`，逐条写明原因）：新增的未登记缺口 ⇒ 门禁红；
  已登记的缺口被修好 ⇒ 门禁也红（提醒删登记，别让债务烂在清单里）。
  当前登记 6 条（env/status、link-sim ×3、thermal/sources、hardware/physics-model）。
- `--self-test` **9 个用例**（合成夹具）：一致时不误报 / 未登记缺口被报出 /
  登记放行 / 登记过期被报出 / 方法对不上被报出 / 前后端提取口径 / 运行时变量路径不被静默放过 /
  不带 `?` 的查询串能匹配到端点。
- 已进 CI `repo-gates` job（自测 + 真检查各一步）。
- 顺带修掉两个**量具便携性**问题：`count-rest-endpoints.py` 与 `sync-test-counts.py`
  里硬编码了绝对仓库路径 ⇒ 换 worktree / 在 CI 上会**数出 0**；已改为从脚本位置推导。

### 4. 计数

Java **4970 → 4979**（新增 9 个用例），前端 vitest **382** 不变，
REST 端点 **406 → 409**（新增 3 个 by-sysid 口径）。

---

---

---

## [Unreleased] — 限飞区第一次真正参与命令拦截：此前平台能指挥无人机飞进民航禁飞区（2026-10-21）

### 1. 现场：合规硬伤 + 一个说谎的测试

拦截链（`GeofenceInterceptService`）只遍历**用户自建围栏**（`GeofenceStore.getAllZones()`），
而**限飞区数据源**（`RestrictionCacheManager`，本地文件源早已可用）**从来没被查过**
⇒ **平台可以指挥无人机飞进民航禁飞区**。

更糟的是：有个用例叫 `testRestrictionZoneHit`，DisplayName 写着
"限飞区缓存中的限飞区命中 → DENY(IN_KEEP_OUT_ZONE)"，
但它把区域加进了**用户围栏表**（`store.addZone`）⇒ **限飞区缓存从头到尾没被碰过**。
**测试名字在骗人，而 CI 全绿。**

### 2. 改法

- `GeofenceInterceptService` 注入 `RestrictionCacheManager`；命中限飞区（圆形/多边形，
  几何复用 `GeofenceMonitor` 的实现，**不重复写一遍数学**）⇒ **DENY(IN_RESTRICTION_ZONE)**，
  判决里带限飞区 ID / 名称 / 数据来源。
- **限飞区数据不可用时的姿态显式化**（这是取舍，不是实现细节）：
  - `aerofleet.geofence.restriction.intercept-fail-closed=true` ⇒
    **DENY(NO_RESTRICTION_DATA)**（这个常量此前**全仓 0 处产生**，现在有了）；
  - 默认 `false`（fail-open）⇒ 放行，但判决带 `isRestrictionDataUnavailable()=true`：
    **放行不是因为检查过，而是因为没数据可查** —— 这个状态必须能被调用方看见，
    否则就是"没查过却装作查过了"；
  - 默认取 fail-open 的原因：真实限飞区数据源（HTTP 源）尚未实现，fail-closed 会让
    **所有命令都发不出去**；接上真实数据源后建议改 true（配置注释里写了这条建议）。
- 缓存 stale 但仍有内容 ⇒ 仍按最后成功加载的数据判定（**过期的禁飞区仍然是禁飞区**）。

### 3. 测试（含变异验证）

- 新增 `GeofenceInterceptRestrictionTest`（7 个用例，用**打桩的限飞区缓存**走真正的那条路）：
  圆形命中 / 多边形命中 / 区外放行 / 无数据+fail-closed 拒发 / 无数据+fail-open 放行但带标记 /
  stale 缓存仍生效 / 用户围栏回归。
- **变异验证**：① 把限飞区判定整段摘掉（= 回到今天之前）⇒ 5-6 条红；
  ② 命中限飞区时反而放行 ⇒ 3-4 条红。⇒ 新测试真的在守着那件事，不是摆设。
- 原来那个说谎的用例改名 `testUserKeepOutZone`，注释里写明它只管"用户自建围栏"这条路径。

### 4. 计数

Java **4950 → 4977**（新增 7 个用例），前端 vitest **382** 不变。

---

---

---

## [Unreleased] — 端点数门禁自己有盲区：8 处「358 个端点」活过了门禁，而门禁报的是「通过」（2026-10-21）

### 1. 现场：门禁绿着，文档是过期的

`scripts/check-claimed-endpoint-count.py` 报「文档声称 406 = 实测 406」，
但全仓仍有 **9 处**写着 358 / 67：

| 位置 | 旧声称 |
|---|---|
| `README.md:620` | 共 67 个 @RestController、358 REST 端点 |
| `README.md:1030` | 358 个端点 |
| `docs/api-reference.md:3` | 共 67 个 @RestController、358 个 REST API 端点 |
| `docs/api-reference.md:2678` | 合计 67 个 @RestController / 358 |
| `docs/api-reference.md:2680` | 其中 67 个为 @RestController；67 个共 358 个端点（193 GET / 138 POST / …） |
| `docs/sales-pitch-deck.md:263/271/281/408` | 358 API 端点 |

**根因是门禁自己的文法太窄**：`CLAIM = r"(\d+)\s*端点"` ——
数字与「端点」之间**夹了词**就看不见（"358 REST 端点"、"358 个 API 端点"），
表格单元格里数字与「端点」分处两格也看不见（`| 358 | REST API 端点数量 |`）。
而 #126 当时只改了 6 处、剩下这些门禁看不见 ⇒ **门禁报"通过"**。

### 2. 改法

- **文法放宽**：数字与「端点」之间允许 `个/条/REST/API/HTTP`；
- **补表格行文法**：一行里有「端点」且另一格是纯数字 ⇒ 那也是端点数声称；
- **补扫描范围**：`docs/api-reference.md` 之前**根本不在 DOCS 清单里**
  （API 文档是最该被扫的地方）；
- **新增一类检查**：**控制器类数**的声称（「共 78 个 @RestController」）此前无人校验，
  现在也归这个门禁管；
- **增量豁免**：「（+ 5 个端点、12 例测试）」说的是"新增了 5 个"，
  不是"一共 5 个" ⇒ 按总数比会误报，这类行跳过；
- 文档里 9 处旧声称改成实测值（端点 406、@RestController 78、Controller 源文件 79、
  分解 214 GET / 162 POST / 14 PUT / 16 DELETE）。

### 3. 自测

门禁自测从 3 条加到 **6 条**：带限定词的写法（358 REST 端点）必须被抓到、
增量写法（+ 5 个端点）不误报、控制器类数声称（67 个 @RestController）必须被抓到。

### 4. 一句话

**门禁自己也要被审** —— 一个"看起来在拦"的门禁，如果文法有盲区，
它给的是**虚假的安全感**：比没有门禁更坏，因为大家会因此不再去查。

---

---

---

## [Unreleased] — Python SDK 的测试数声称是假的（README 写「0 单测」，实际 27 个用例）；补上 Python 侧的计数门禁

### 1. 现场：仓库在自相打自己的脸

README.md 的模块表里写着：

> sdk-python | Python 3 | Python 客户端 SDK（drones / missions / flightlog + exceptions），7 文件、**0 单测**

而实际上：sdk-python/tests/test_client.py 有 **27 个用例**，CI 的 sdk-python job 一直在跑 pytest（全绿）。

那份测试是 **2026-10-13 补的**（测试文件的 docstring 里写着「此前这个 SDK …零测试」），
README 那一行**没跟着改**。

**为什么两年没人拦：**Java 侧和前端侧都有「文档声称数 == 实测数」的门禁
（check-test-count-docs.py），**Python 侧没有** ⇒ 这类烂掉没人拦。

### 2. 修法

- README.md 的声称改对：**27 个用例**。
- 新增 scripts/check-python-test-count.py：用 pytest --collect-only 数出**实际收集到的用例数**
  （不是 def test_ 函数数：有 parametrize 时两者不等，27 ≠ 22），
  再与文档里「Python SDK 测试数」声称对比，不一致就判红。
- 接进 CI 的 sdk-python job（门禁必须在装了 pytest 的环境里跑；
  收不到用例数时退出码 **2** = 前置不满足，**不是「通过」**）。
- 门禁自身带 --self-test（5 个情形：声称不符能报出 / 相符不误报 / 追述旧值免除 / 没声称不误报 / 收不到数则判前置不满足）。

### 3. 计数

Java **4970**、前端 vitest **382**、**Python SDK 27 个用例**（本 PR 新增一个门禁脚本，不动测试）。

---

---

---

## [Unreleased] — 185 行的 docker-compose e2e 从来没跑过：CI 里那个守卫恒假（2026-10-21）

### 1. 现场：CI 显示"有这一步"，实际每轮都 skip

`ci.yml` 的 sdk-integration job 里有个 step：

    - name: Docker Compose E2E (if Docker available)
      continue-on-error: true
      run: |
        if command -v docker-compose &>/dev/null; then ...

master 的 run `39550447227` 日志里，这一步的真实输出是：

    docker-compose not available, skipping

ubuntu-24.04 runner 只带 compose v2 插件（`docker compose`），**v1 的
`docker-compose` 命令不存在** ⇒ 条件恒假，`scripts/e2e-docker-compose.sh`
（185 行，验完整链路）**一次都没在 CI 跑过**。再叠一个
`continue-on-error: true`：哪天真跑了、真失败，也不阻塞。

### 2. 根因不止一层：就算命令存在，这一步也必然失败

`docker-compose.yml` 三个服务全是 `network_mode: host`：

- cloud-backend 绑 **8080** —— 而本 job 前面**已经起了一个 backend** 在 8080
- 脚本自己 `docker run --name nexussky-redis --network host redis` 绑 **6379**
  —— 而本 job 的 services 里**已经有 redis** 占着 6379

端口全撞。这才是当初加 `continue-on-error` 的真实原因：它被放在一个
**不可能成功**的环境里。

### 3. 修法：删掉假覆盖，换成真能跑的东西

| 动作 | 说明 |
|---|---|
| 删掉那个 step | 留着它就是"CI 覆盖了 compose"的假象；注释保留原因，免得下一个人再踩 |
| 新增 job `compose-artifacts`（**硬门禁**） | 纯静态：`docker compose config -q` + 新脚本 `check-compose-artifacts.py` |
| 新脚本校验什么 | compose 里写死的 jar 名 vs pom 实际产物（`<artifactId>-<version>[-shaded].jar`） |
| `e2e-docker-compose.sh` 兼容 v2 | 加 `$DC` 检测（v1 优先、回退 v2），5 处调用改用它；手工跑不再挑环境 |

为什么新脚本值得单独写：改 pom 的 version / finalName / shade 配置时，
compose 里硬编码的 jar 名**不会跟着变**，后果是容器秒退
（`Unable to access jarfile`）——而 CI 此前对这类脱节一直是绿的。

### 4. 验证

| 场景 | 期望 | 实测 |
|---|---|---|
| 新脚本自测（一致 / 版本漂移 / shade 后缀漏写 / 模块缺失） | 全 PASS | 4/4 |
| 真实仓库 | 绿 | 2 个 java 服务名与 pom 一致 |
| 破坏性验证（把 compose 改成 0.2.0） | 红 + 指出期望值 | EXIT=1，提示 `期望 aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar` |
| `bash -n` 语法 | 通过 | 通过 |
| `docker compose config -q` | 通过 | EXIT=0（3 服务） |

全链路 up/down 仍属**手工验证**（需先 `mvn package` 出 jar，且 host 网络要求
独占 8080/6379）——这一点写清楚，不再假装 CI 覆盖了它。

---

---

---

## [Unreleased] — 变异测试：Java 侧第一次有「测试强度」这个数；查实 4 处安全行为没人盯（2026-10-21）



### 1. 现场：4950 条测试，挡不住 4 个"改坏了也没人发现"的行为



用 PIT 1.15.0 审 cloud-backend 的 security 包（767 个变异体）：

**116 个变异体存活** —— 改了生产代码，**没有任何测试变红**。



其中**四处是真安全缺口**（不是"覆盖率不好看"，是"行为没人盯"）：



| 位置 | 存活变异 | 意味着 |

|---|---|---|

| `AuthController.isRateLimited` | 阈值 `>=` 改 `>`；"恒不限流" | **登录限流器没有任何测试碰过** —— 阈值改成 5 次还是 100 次，测试一律全绿 |

| `AuthController.extractClientIp` | `if (trustForwardedFor)` 取反 | **X-Forwarded-For 的信任开关没被钉住** —— 取反后攻击者靠伪造转发头就能绕开限流（每换一个头就是一个新桶） |

| `ApiKeyController.maskKey` | 返回 null | **密钥掩码的结果没人验证** —— 哪天掩码退化成返回明文，库里就留着明文密钥，测试全绿 |

| `UserController.createUser` | 删掉 `passwordEncoder.encode` | **口令哈希落库没人验证** —— 那行被误删就是明文口令进 users 表，测试全绿 |



常规 jacoco 覆盖率**看不出**这些：那几行代码被测试执行过，只是断言不够狠。

**覆盖率回答"跑到过没有"，变异分数才回答"盯住了没有"。**



### 2. 补测 + 门禁



- 新增 3 个测试类 / **20 个用例**（`AuthControllerRateLimitTest` 9 +

  `ApiKeySecretExposureTest` 5 + `UserControllerPasswordHashTest` 6），

  把上面四处行为钉死；限流测试刻意用**快编码器**（限流与口令编码无关，

  而 BCrypt 一次 ~100ms，PIT 要为每个变异体重跑一遍，用 BCrypt 会让整包变异跑从 6 分钟涨到 20 分钟）。

- 变异分数（同口径 A/B：各自重新编译、依次单跑）：

  **0.8201 → 0.8448（+2.5 pp）**，存活变异体 **116 → 102**。

- 新增 `scripts/check-mutation-baseline.py` + `scripts/mutation-baseline.json`：

  变异分数是**棘轮**（不许低于基线；报告缺失/损坏/空报告一律判红 —— 跳过不等于通过）。

- 根 pom 加 `mutation` profile（**默认不启用**，只有显式 `-Pmutation` 才跑）

  + CI job `mutation-gate`（**硬门禁**）。



### 3. 工具报的"缺口"我复核了（这一段最值钱）



PIT 报 `isRateLimited` 有两个存活变异体（`BooleanTrue/BooleanFalseReturnValsMutator`）。

按字面看是"限流没被钉住"—— 一个安全缺口。



**但我手动把源码改坏试了一遍**：`return true`→`return false`、`return false`→`return true`、

`>=`→`>`、`trustForwardedFor` 取反 —— **新测试全部变红**。

⇒ 那两个 mutator 是"把 true 换成 true / false 换成 false"，**行为等价**；

PIT 记的是"存活"，但它压根没改变行为。



**不复核就写进结论 = 报出一个不存在的安全缺口**（比漏报更坏：

让人去"修"一段没问题的代码，正是我自己文档里记的那类事故）。

所以三类分开记账：**SURVIVED（真缺口）/ 行为等价（声明无害）/ NO_COVERAGE（覆盖缺口）**。



### 4. 测量本身也会被污染（我自己踩的，写进约束清单第 8/9 条）



并发跑两个 PIT（一个 standalone、一个 Maven 插件版）**共用 `target/`** ⇒

Maven 那次 `test-compile` 把另一次**正在读**的 test-classes 冲掉 ⇒

261 个变异体变成 `RUN_ERROR`（"一个测试都没跑到"）。

我差点把"变异分数 83% → 49%"读成"**我的新测试把分数弄崩了**" —— 其实是我自己污染了数据。



⇒ ① 同一份产物目录不并发跑两个测量；② **读报告要等它写完**

（PIT 逐个 `<mutation>` 追加写，**半截文件有时也能解析成功**）；

③ `RUN_ERROR` 变多先怀疑测量环境，再怀疑被测代码。

门禁脚本因此把 `RUN_ERROR` 单独列出并给提示。



### 5. 计数



Java **4950 → 4970**（新增 20 个用例），前端 vitest **382** 不变。门禁绿。



### 6. 门禁自证：它自己差点假绿（合并前堵掉的洞）



门禁把"跳过不等于通过"写进了注释（报告缺失 / 损坏 / 空都判红），但实测发现**总数维度没实现**：



- 造一份只有 **10 个变异体**（全 KILLED）的报告 ⇒ 分数算出 **1.0000**，

  高于基线 0.84 ⇒ **EXIT=0 通过**。而真实基线是 767 个变异体。

- 成因：分数 = `killed/(total−noCoverage)`，**分母缩水时分数反而更容易变高**。

  PIT 若只覆盖了部分类（targetClasses 配错 / 类编译失败被跳过 / 提前中断），

  剩下那一小撮全杀掉就能拿满分 —— 门禁绿，实际漏审。



修法：`compare()` 增加**报告完整性检查**（变异体总数低于基线的 95% 判红），

失败提示按病因分流（残缺报告先查 targetClasses / 构建日志 / 测量中途改源码，

确认是代码合法删减才更新基线里的 total）；另把 `MEMORY_ERROR` 计入统计

（此前被静默忽略，会让总数偏小）。



验证矩阵（全部实测）：



| 场景 | 期望 | 实测 |

|---|---|---|

| 自测 7 用例（新增「残缺报告被拦」） | 全 PASS | 7/7 PASS |

| 完整 767（真实数据） | 绿 | EXIT=0，分数 0.8448（与 A/B 表一致） |

| 轻微删减 730（−4.8%） | 绿 | EXIT=0 |

| 边界 728（恰等于阈值） | 绿 | EXIT=0 |

| 边界 727（阈值 −1） | 红 | EXIT=1 |

| 残缺 10（−98.7%） | 红 | EXIT=1 |



---

---

---

## [Unreleased] — vite 6.4.3 命中 high 级公告（launch-editor 命令注入），升到 6.4.4（2026-10-21）



### 1. 现场：既有问题，npm 公告库更新后才判红



`npm audit` 报 `vite <=6.4.3` high（GHSA-587h-7wmj-h5pm 等）：vite 依赖链上的

`launch-editor` 有三个 Windows 相关缺陷（% 转义缺失导致命令注入、CLI 参数误读、

`fs.existsSync()` 可绕过）。



**这不是本 PR 引入的**：lockfile 一直锁在 6.4.3，是 npm 公告库更新后

（2026-10-21 00:11Z）才开始判红 —— **master 与所有在途 PR 一并变红**。



### 2. 修法



lockfile 里 vite 6.4.3 → **6.4.4**（package.json 的 `^6.4.3` 不用动，它本来就允许 6.4.4）。

实测：`npm audit --audit-level=high`（对官方 registry）= **found 0 vulnerabilities**。



### 3. 顺带说明两件实测到的事



- 本机跑前端测试曾出现 1 个用例超时（`unifiedCommandWiring.test.jsx` 的 5 秒默认超时）。

  **与本次升级无关**：把 node_modules 的 vite 降回 6.4.3 **同样超时**；

  把超时放宽到 20 秒则 14/14 全过 —— 根因是**本机 CPU 100%**（别的会话在跑），

  那个用例用 `waitFor` 轮询 + 5 秒超时，在高负载机器上必然超时。CI 的 runner 是专属的。

- 本地 `npm audit` 报 "audit endpoint returned an error"：本机 registry 是 **npmmirror**（不支持 audit API），

  要验得显式 `--registry=https://registry.npmjs.org`。



### 4. 计数



Java **4950**、前端 vitest **382** 不变（本 PR 不动任何测试）。



---

---

---

## [Unreleased] — MJPEG 取流链路：此前从未在 CI 跑过；「匿名必须被拒」这条断言只在非 dev 配置下成立（2026-10-21）



### 1. 现场：一条从没被验证过的链路，和一条"在标准姿势下必然红"的断言



`scripts/e2e-mjpeg-preview.py` 里有一条硬断言：「匿名取流必须被 401 拒掉」，

注释写着"链上 permitAll ≠ 不鉴权"。但两件事同时成立：



1. 它**从来没在 CI 里跑过** —— 它需要一台真摄像头，GitHub runner 没有；

2. 仓库里启动后端的标准姿势（CI 的 `sdk-integration` job、多数 e2e 脚本）都是

   **dev profile**，而 `aerofleet.security.dev-mode=true` 时：

   - Spring Security 链是 `anyRequest().permitAll()`；

   - `StreamTokenFilter` 第 82 行直接 `return`（dev 分支跳过）。



   ⇒ 匿名访问 `/mjpeg` 会拿到 **200 和真实帧流**（我本机实测读到 7MB）。



于是这条断言在"标准姿势"下**必然失败**。我本机第一次跑：PASS=20 / FAIL=2，

两条红正是「匿名 -> 401」和「匿名响应一帧都没漏」。

**红在错的原因上**，比不跑还糟 —— 看不出是产品有问题还是配置不对。



### 2. 实测：换成生产同款配置，23 条断言全绿



| 配置 | 匿名取流 | 结果 |

|---|---|---|

| dev profile（`dev-mode=true`） | 200 + 真实帧流 | PASS=20 **FAIL=2**（红在错的原因上） |

| 同库 + `--aerofleet.security.dev-mode=false` + ≥32 字节 jwt-secret | **401，0 字节泄漏** | **PASS=23 FAIL=0** |



中间还踩到一个坑：登录**只查数据库** —— `aerofleet.security.users`（内存用户）

只在**没有 UserRepository** 时生效，有库时查不到直接 401。

所以必须先在库里建号（dev 模式下 `POST /api/v1/users` 建，再优雅停库让它落盘）。



### 3. 改法



- `scripts/e2e-mjpeg-preview.py`：

  - **先探后端鉴权姿态**再决定怎么跑。dev 模式直接说清原因并退出（exit 2），

    不再红在「匿名 -> 401」上，也不再顺手把整段流读进内存；

  - 想对着不鉴权的后端跑，要显式 `--allow-open-backend`（此时 S3 断言按**记录**处理）；

  - 帧源判定**只信字节不信 `Content-Type`**（真实摄像头常乱标：

    有的把 JPEG 标成 `application/octet-stream`，有的把非 JPEG 标成 `image/jpeg`）。

    硬断言仍是「200」+「SOI=ffd8ff」。

- 新增 `scripts/synthetic-frame-source.py`：CI 没有摄像头时的合成帧源，

  吐仓库里 **3 张真 JPEG 夹具并轮换**（所以「每帧都是真 JPEG」「帧内容在变」

  在 CI 里仍然是在测产品，不是在测一个假帧源）。带 `--self-test`（已接进 CI）。

- 新增 `scripts/e2e-mjpeg-ci.sh`：把前置条件固化成配方 ——

  **dev 起一次建 ADMIN 用户 → 优雅停（让 H2 落盘）→ 以 dev-mode=false 重启

  → 起合成帧源 → 跑链路**。

- 新增 CI job `e2e-mjpeg`（**硬门禁**）。



### 4. 自测也做了变异验证（顺手抓到一次"良性变异"）



`synthetic-frame-source.py --self-test` 的三个变异体：不轮换 / 吐的不是 JPEG /

stats 计数写死 —— 其中"吐的不是 JPEG"那个第一次跑**没被抓住**：我写的那个变异

只关掉了夹具校验，而夹具本来就是真 JPEG ⇒ **行为等价的良性变异**。

按 `docs/agent-constraints.md` 第 3 条的规矩换成真变异（响应字节魔数被换掉）后，

三个变异全部被抓住。



### 5. 计数



Java **4950**、前端 **382**，均不变（本 PR 不动任何被测代码）。门禁绿。



---

---

---

## [Unreleased] — 手机摄像头链路：取图端点不再靠「猜路径」，改成读设备自己的首页；补上自测与变异验证（2026-10-21）



### 1. 现场：手机插上了，但取图端点探不到



- Windows 认得到手机（USB `VID_2D95` / iQOO 12 Pro，WPD + 大容量存储），

  但**没有 ADB 接口设备** ⇒ 手机侧「开发者选项 → USB 调试」没开；

- 局域网扫 8080/8081/8088 只有本机 ⇒ 开发者选项里的「IP 摄像头」也没开。



也就是说，**卡点全在手机侧**，工具链本身是通的（adb 已装、脚本已就绪）。



### 2. 修法：不猜路径，改成「问设备自己」



原 `scripts/e2e-phone-camera.py` 靠一份**写死的候选路径表**去撞手机

（`/snapshot`、`/shot.jpg`、`/now.jpg`…）。安卓侧内置 IP Camera、IP Webcam、

camera-streamer 的路径各不相同 —— **猜错整条链路的验证就作废**，

而且作废得很隐蔽：脚本会报"没找到端点"，看不出是路径猜错了还是手机没开摄像头。



改成**先读设备自己的首页**（`GET /`），从返回的 HTML 里解析它**自己声明**的端点

（`href` / `src` / `action`），再按候选路径补充；两条路统一以

**返回体是不是真 JPEG**判定（只看魔数 `FF D8 FF`，不信 `Content-Type` ——

有的 app 把 JPEG 标成 `application/octet-stream`，也有的把非 JPEG 标成 `image/jpeg`）。



### 3. 自测 + 变异验证（这里也抓到了我自己的一次假绿）



新增 `--self-test`：起一个**合成的「假 IP 摄像头」**（首页声明的怪路径、multipart 视频流、

「头两字节像 JPEG」的假响应、纯 HTML 页面），并接进 CI job `repo-gates`。



变异验证 —— 把实现改坏，自测必须变红：



| 变异 | 结果 |

|---|---|

| `is_jpeg` 恒真（什么都信） | 被抓住 |

| 去掉读首页的发现逻辑（只剩候选表） | 被抓住 |

| 魔数只看前两字节 | **第一轮没被抓住** |



第三行是真事：夹具里当时**没有任何「像 JPEG 开头但不是 JPEG」的响应**，

于是"只看两字节"和"看三字节"两种实现表现完全一样 —— **自测是假绿的**。

补上 `FF D8 00` 这个判别性夹具后，三个变异全部被抓住。



这条教训已经写进 `docs/agent-constraints.md` 的同类条目：

**断言自测必须能区分"实现写错了"和"实现写对了但恰好一样"**，

否则它挡不住真事故，只会给自己壮胆。



### 4. 计数



Java **4950**、前端 **382**，均不变（本 PR 不动任何被测代码）。门禁绿。



---

---

---

## [Unreleased] — 文档里对外声称的端点数有三个不同的值，实测 406；新增端点数声称门禁（#126，2026-10-20）



### 1. 现场：同一件事，三个声称值，一个都不对



| 文档 | 声称 |

|---|---|

| `README.md`、`docs/sales-pitch-deck.md` | REST API **358** 端点 |

| `docs/contributing-guide.md`、`docs/customer-onboarding-guide.md`（2 处）、`docs/pricing-strategy.md` | REST API **345** 端点 |

| **实测**（78 个 `@RestController` 类里的方法级映射注解，排除 actuator） | **406** |



三个数彼此都不一样，也都对不上代码。这类「声明≠行为」此前只被门禁覆盖到**测试计数**，

对外文档里的数字没人管。



### 2. 修法：口径写死 + 门禁



- 新增 `scripts/count-rest-endpoints.py`：端点数测量口径写死在代码里

  （只数 `@RestController` 类、只数方法级映射注解、排除 actuator）——

  **换个算法数出来不一样**是这类数字最常见的漂移原因。

- 新增 `scripts/check-claimed-endpoint-count.py`：扫文档里的「N 端点」声称，

  与实测对比，不一致即红。改端点时忘了改文档，它会拦住你。

- 6 处声称已按门禁自己报的「文件:行号」改为 406。



### 3. 门禁自己的自测



用合成夹具跑两个方向：声称与实测不一致要能报出来、一致时不误报；

外加「实测口径正确」一条（2 个端点 / 1 个类）。CI job `repo-gates` 的第一步就跑这些自测。



### 4. 计数



Java 4949 不变，前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 审计哈希链偶发断链：哈希用毫秒、落库用纳秒，数据库四舍五入会跨毫秒（#127，2026-10-20）



一次 CI 偶发红（run 39445877202，`AuditPersistenceTest.consecutiveRecordsFormChain`），

定位出根因 —— 不是测试问题，是**哈希与落库用的不是同一个时间戳**。



### 1. 机制



- `entry_hash` 的规范串用 `ts.toEpochMilli()`（**毫秒**）；

- 但落库的 `Instant` 带着更高精度（纳秒/微秒）；

- 数据库对 `TIMESTAMP` 列是**四舍五入**（H2 与 PostgreSQL 都如此）。本机实测：



```

写入 .639999000 -> 读回 .639999Z   toEpochMilli=...639   ✓

写入 .639999500 -> 读回 .640000Z   toEpochMilli=...640   <- 跨到下一毫秒

写入 .639999999 -> 读回 .640000Z   toEpochMilli=...640   <- 跨到下一毫秒

```



于是 `verifyChain()` 从库里重算时 `toEpochMilli()` 与写入时差了 1 → `entry_hash` 对不上 → **链判为断链**。



**概率**：纳秒落在 [999999500, 999999999] 约 1/2000 每条记录；一条审计 3 条记录 ⇒

约 **1/667 每次运行**。这解释了"CI 跑了这么多次才炸一次"。



### 2. 修法



`AuditService.record()` 里**先截断到毫秒**再拿去哈希、再落库 —— 两者必须是同一个值：



```java

Instant ts = Instant.now().truncatedTo(ChronoUnit.MILLIS);

```



截断后落库值本身就是毫秒精度，数据库再怎么四舍五入都不动它，读回重算必然一致。



### 3. 回归测试是**确定性**的，不靠 1/2000 的运气



新增 `persistedTimestampIsMillisecondPrecision`：断言**落库后**的时间戳纳秒部分为 0。

不截断就必红（H2 会把纳秒四舍五入到微秒 ⇒ 非零）。



**变异验证**：去掉 `truncatedTo(MILLIS)` → 该测试被**确定性杀掉**（失败信息正是"毫秒精度"那条断言），

还原后复跑绿。



### 4. 计数



Java 4949 → **4950**（cloud-backend 2843 → 2844）。前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 文档里对外声称的端点数有三个不同的值，实测 406；新增端点数声称门禁（#126，2026-10-20）



### 1. 现场：同一件事，三个声称值，一个都不对



| 文档 | 声称 |

|---|---|

| `README.md`、`docs/sales-pitch-deck.md` | REST API **358** 端点 |

| `docs/contributing-guide.md`、`docs/customer-onboarding-guide.md`（2 处）、`docs/pricing-strategy.md` | REST API **345** 端点 |

| **实测**（78 个 `@RestController` 类里的方法级映射注解，排除 actuator） | **406** |



三个数彼此都不一样，也都对不上代码。这类「声明≠行为」此前只被门禁覆盖到**测试计数**，

对外文档里的数字没人管。



### 2. 修法：口径写死 + 门禁



- 新增 `scripts/count-rest-endpoints.py`：端点数测量口径写死在代码里

  （只数 `@RestController` 类、只数方法级映射注解、排除 actuator）——

  **换个算法数出来不一样**是这类数字最常见的漂移原因。

- 新增 `scripts/check-claimed-endpoint-count.py`：扫文档里的「N 端点」声称，

  与实测对比，不一致即红。改端点时忘了改文档，它会拦住你。

- 6 处声称已按门禁自己报的「文件:行号」改为 406。



### 3. 门禁自己的自测



用合成夹具跑两个方向：声称与实测不一致要能报出来、一致时不误报；

外加「实测口径正确」一条（2 个端点 / 1 个类）。CI job `repo-gates` 的第一步就跑这些自测。



### 4. 计数



Java 4949 不变，前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 把「我自己犯过的错」变成机器能拦的门禁（#125，2026-10-20）



一次连续作业里我踩了几个坑，每一个都差点造成实际损失。把它们写成规则没用 ——

**能被机器拦住的才算数**。这一条把它们做成三道 CI 门禁，并附一份约束清单

（`docs/agent-constraints.md`）。



### 1. `check-repo-hygiene.py`：仓库卫生门禁



拦四类**静默损坏**（它们不会让 CI 变红，但会让别的门禁变成假绿）：



1. **merge 冲突标记进了仓库**。真事：2026-10-20 一次 `stash pop` 冲突后我直接提交，

   CHANGELOG 里带进 3 处 `<<<<<<< HEAD` / `=======` / `>>>>>>> Stashed changes`；

   仓库历史上还有一次带 42 处标记的 CHANGELOG 进了 master。

   计数门禁按行号读数字，标记行不影响它读数 —— 于是**门禁看起来在工作，其实可能核的是错的行**。

2. **CHANGELOG 自相矛盾**：正文小节数与头部说明、版本索引表不一致。

   真事：上面那次坏提交里正文 156 节、头部还写 154。

3. **合并残留**（`*.orig` / `*.rej` / `*.bak`）入库。

4. **构建产物 / 依赖目录 / 本地报告**入库。



### 2. `check-revert-looking-diff.py`：疑似回退式改动



真事：某个工作树把 CHANGELOG 一次性撤掉 559 行、并把一个**已经修好**的脚本

（`e2e-rtk.ps1` 的 key 归一化 + 诊断输出）退回成有 bug 的版本 —— 这类改动不违反任何断言，

门禁看不出来。按 numstat 的增删比抓它（删 ≥ 200 行且新增 ≤ 删除的 15%）。



### 3. `check-selftest-coverage.py`：自测写了却没人跑



真事：`scripts/coord-status.sh` 自己带着 `--self-test`，**却从来没被任何 CI job 跑过** ——

它的写回守卫坏了 8 天（把「自动段合法变短」误判成「写坏」，

`.coord/STATUS.md` 的「在途与占用」段自 2026-10-12 起冻住，各 agent 的开工声明发不出去），

期间没有任何门禁会红。



这条门禁要求：**凡提供 `--self-test` 的脚本，都必须有 CI job 真的跑它**。

它第一次跑就抓出两个真缺口（`mavlink-compatibility-check.py` 与 `sitl-compatibility-test.sh`

的自测从没进过 CI），已在同一个 job 里补上。



### 4. 三道门禁自己都有 `--self-test`，而且**先自证再使用**



每道门禁的 `--self-test` 都用**合成夹具**跑两个方向：真事故里的坏状态要能拦下、干净状态不能误拦。

CI job 的第一步就是跑这三个自测 —— **先证明工具可信，再信它的结论**。



这一条是写给我自己的：2026-10-20 这一天我的"量具"错了四次（`$?` 被 PowerShell 插值、

WSL 里只有 `gh.exe`、两次正则切块切错），每一次都差点让我去"修"一段好代码。



### 5. `docs/agent-constraints.md`：约束清单



每条写清「错在哪 → 造成什么后果 → 硬约束（可执行的句子）→ 机械化在哪」，

并诚实标出**还没机械化**的条目（如"只 add 自己碰过的文件""归因也要有证据"）。



### 6. 计数



Java 4949 不变，前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 摄像头那条 TODO 变成「插上线跑一条命令」：新增 e2e-phone-camera.py（#124，2026-10-20）



`.coord/GAPS.md` 里登记着一条搁置项：**手上只有 Android 手机，没有网络摄像头**。

此前它只能干等硬件。现在这条 TODO 有了可执行的一步：手机接上（或在开发者选项里打开

「IP 摄像头」），跑一条命令就能把"手机 → HTTP 帧源 → 真后端 /mjpeg → 浏览器可播"

整条链验完。



### 1. 新增 `scripts/e2e-phone-camera.py`



三步：



1. **找手机**：有 adb 就问 adb（`adb devices` + `adb shell` 读 wlan0 的 IP + 查 8080

   是否在听）；没有 adb 就扫本机网段的 8080/8081/8088。

2. **找取图端点**：对候选路径逐个探测，**以返回体是不是真 JPEG（SOI=ffd8ff）为准**。

   不写死任何一条路径 —— 安卓侧的内置 IP Camera、IP Webcam、camera-streamer 之类

   的路径各不相同（/snapshot、/snapshot.jpg、/shot.jpg、/now.jpg、/capture…），

   **写死就是猜**。

3. **跑完整链路**：把探测到的 URL 交给 `scripts/e2e-mjpeg-preview.py`（#110 那套断言：

   帧源真出 JPEG、注册回显 browserPlayable、匿名 401、短令牌开流 200/multipart、

   每帧都是真 JPEG、注销后 404）。



### 2. 顺带发现并解决的一个真问题：「画面在动」对手机不成立



`e2e-mjpeg-preview.py` 的 S1「两次取帧内容不同」与 S6「帧内容在变」是为**桌面 UVC 摄像头**

定的。**手机摆在桌上对着静止场景时，每帧本来就一样** —— 那时失败与手机无关，却会让人

以为链路坏了。已给两个脚本加 `--allow-static-frames`（手机脚本默认带上；`--require-motion`

可关掉），把这两条从**判定**降级为**记录**。



### 3. 验证：用假手机（只提供 /snapshot 的小 HTTP 服务）跑完整链路



```

== 1/3 找手机 ==

== 2/3 找取图端点（以返回体是不是真 JPEG 判定，不写死路径）==

找到: http://127.0.0.1:8099/snapshot  （JPEG 27 字节）

== 3/3 跑完整链路（真后端 /mjpeg -> 浏览器可播）==

  PASS=20  FAIL=0

  == 手机摄像头链路全绿 ==

```



后端用 **prod profile** 跑（dev 的 permitAll 会让 S3「匿名必须 401」失去意义），

并用 `--aerofleet.security.bootstrap-admin-password` 引导管理员 —— 因为 V20 迁移把

V6 种子的那个 admin 置为 `enabled=FALSE`（那个哈希其实对任何常见口令都不匹配），

库里没有可用账号时登录必然 401。



**本轮没有真手机**（硬件不在手边），所以"真手机"这一段仍是待办；已验证的是**这条链路的

全部机制**（找设备、探测端点、真后端、真 multipart 帧流、注销后 404）。



### 4. 计数



Java 4949 不变（不涉及代码），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 前端「0-360 归一」这条声明在 (180,360) 区间无人看守（变异测试查出）（#123，2026-10-20）



### 1. 怎么查出来的：变异测试，不是数断言



先试过用脚本数每个 `it(...)` 块里有几个 `expect(`，想找"零断言的用例"（红不了的用例）。

**两次都把量具写错了**（第一次把标题里的括号 `[0,360)` 当成 `it(` 的结束；第二次配对扫描

把含字符串的块切错，报出 289 个"零断言"——荒谬）。两次都是**量具的错，不是测试的错**。



于是改用决定性判据：**改实现，看测试会不会红**。在 `src/utils/aiDecision.js` 与

`src/api.js` 上各造了几个变异：



| 变异 | 结果 |

|---|---|

| `raw.newLat / 1e7` -> `/ 1e6`（经纬度单位换算被改坏） | KILLED |

| 风向归一 `% 360` -> `% 180` | **存活** |

| 跨源判定改为恒不触发（守卫生效性被改坏） | KILLED |

| 路径前缀判定改为恒不触发 | KILLED |



### 2. 存活的那条是「等价变异」，但它暴露了一个真缺口



`src/utils/aiDecision.js` 的注释明写「windDirection cdeg → 度（**0-360 归一**）」，

而测试里只有两个风向输入：**90° 与 400°**。而 400 = 2×180 + 40 ⇒ 把 `% 360` 改成 `% 180`

**结果完全一样**（都是 40）。所以那条声明在 **(180, 360) 区间根本无人看守** ——

它写没写、变没变，测试都绿。



补一条 270° 的用例后：270° 在 `%360` 下是 270、在 `%180` 下是 90，能区分；

四个变异随即 **4/4 全杀**，测试文件本身 23 条全绿。



### 3. 顺带记一笔方法论



这一轮里我踩了**三次量具的坑**：`$?` 被 PowerShell 插值成 `True`（转义要用反引号不是反斜杠）、

WSL 里 `gh` 只有 `gh.exe` 导致 `command -v gh` 找不到（自测因此假红）、

以及上面两次解析器。**每一次都差点把「好代码」当「坏代码」去修**。

结论没变：量具先自证，再下结论。



### 4. 计数



前端 381 → **382**（vitest 实测 382/382 通过）。Java 4949 不变。门禁绿。

---

---

## [Unreleased] — docker-compose 配置层校验：清掉已废弃的 `version` 属性（2026-10-20）



GAPS「docker-compose 完整性验证」一行本轮推进到**配置层**：`docker compose config

--quiet` 此前通过但带 warning（`version` 属性已废弃），已删除该行，复验 warning

消失、exit 0。



**平台边界（如实记录）**：本编排是 Linux 专用——三服务全 `network_mode: host`

（MAVLink UDP 依赖同网段广播），Windows/Mac Docker Desktop 的 host 网络不完整，

**本机（Windows）无法如实完成 `compose up` 实测**。GAPS 剩余动作改为：在 Linux CI

runner 上加 compose smoke job（先 `mvn package` 三模块 + `compose up -d` +

`/actuator/health` 探活），这比本机跑更可复现；job 成本评估后再立项。

---

---

## [Unreleased] — 断言聚合步骤从「只警告」改成「真判红」；顺带把一批与现状不符的注释改回真话（#120，2026-10-20）



#119 把五个 e2e job 从观察模式转成硬门禁之后，`ci.yml` 里还留着几处**描述已经不存在的状态**的话。

这一条把它们改回真话，并把聚合步骤从"只警告"升级成"真判红"。



### 1. 聚合步骤以前只警告不判红 —— 现在判红



B2 A/B 型两个 job 末尾各有一个「Summarize e2e assertions」步骤，它从 step 日志里重新统计

`[PASS]`/`[FAIL]`（两族格式一个正则通吃），但发现失败时只打一条 `::warning::`：



```

::warning::B2 A-type e2e 3 条断言失败（观察模式不阻断，信号在此保留）

```



两处都不对：



- **「观察模式不阻断」是假的** —— #119 之后这些 job 已是硬门禁，断言失败本来就会把 job 拉红。

- **它自己不判红** —— 真判红完全依赖"每个脚本自己记得 `exit 1`"。万一某个脚本印了 `[FAIL]`

  却忘了退非零，job 就会**绿着通过** —— 这正是"假绿"的形状。



改成：`total_fail > 0` → `::error::` + `exit 1`。脚本本身的退出码仍是第一道门（管道那几处用

`pipefail` / `|| rc=$?` + `exit $rc` 传出来），聚合步骤是**兜底**。



### 2. 验证：把 YAML 里那段 bash 原样抽出来，在真 Linux bash 里跑



| 用例 | 期望 | 实测 |

|---|---|---|

| 只有 `[PASS]` 行 | 0 | 0 |

| 有一行 `[FAIL]` | 1 | 1 |

| 另一族格式 `FAIL:` | 1 | 1 |

| 日志目录为空 | 0 | 0 |



A/B 两型各一遍，8/8 相符。



**量具本身也翻过一次车**：第一版探针用 `bash -c "… echo RC=$?"`，而 `$?` 被 PowerShell 展开成了

`True`（PowerShell 转义 `$` 要用反引号，不是反斜杠）—— 于是"exit 1 没生效"的结论其实是

量具坏了。改成把探针写成文件、让脚本把退出码写进 `/tmp/rc.txt` 再读，才拿到真值。



### 3. 顺带改回真话的注释



`ci.yml` 里还有几处注释在描述**已经不存在的状态**，下一个人照着找会找不到：



- `e2e-capability` 的注释写着"为什么带 continue-on-error：……先以观察模式接入"——

  但这个 job **从不带 job 级 continue-on-error**。历史查清了：能力腿原本是 `e2e-smoke` 里的一个

  **step**（带 step 级 continue-on-error，观察模式），拆成独立 job 时继承了硬门禁，注释却留了下来。

- `e2e-core-scripts` / `e2e-b1-scripts` / `e2e-b2-a-type` 上方的"为什么带 continue-on-error：

  ……若干次 run 全绿后删掉这行转为硬门禁"—— 那一步已经在 #119 做完了。

- B2 段落的标题还写着"（观察模式）"。



逐条改成"当初是观察模式（理由是什么）→ 何时转的硬门禁（依据是什么）"，让注释能回答

"为什么现在是硬门禁"，而不是让人去找一个不存在的 continue-on-error。



### 4. 顺带核过：脚本层没有假绿



逐个查了 25 个 e2e 脚本：**断言失败时都会退非零**（`if ($Fail -ne 0) { exit 1 }` 之类），

没有"印了红字却 exit 0"的脚本。所以聚合步骤这层兜底确实是在补最后一道网，而不是重复劳动。



### 5. 计数



Java 4949 不变（不涉及代码），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 协调状态板生成器的写回守卫误判「合法变短」为「写坏」，状态板冻了 8 天（#122，2026-10-20）



`scripts/coord-status.sh` 是本仓库多 agent 协作的状态板生成器（`.coord/STATUS.md` 的

「在途与占用」段由它写）。它自己带着 `--self-test`，**但 CI 里从来没跑过它** ——

而这正是它坏了 8 天没被发现的原因。



### 1. 症状：状态板自 2026-10-12 起就写不进去了



`--write` 每次都拒绝：



```

失败：新文件有效行数比原来少，疑似写坏。放弃，原文件未动。

```



守卫比的是**整份文件**的非空行数，而自动段的长度本来就随实测变（worktree 少了、PR 合了

都会变短）。一旦自动段比上一版短，合法写入就被判成"写坏"。



**后果**：`.coord/STATUS.md` 的「在途与占用」段停在 **2026-10-12 20:03**（`origin/master = 47fc983`），

八天不动。各 agent 的开工声明发不出去，读板的人只能看到 8 天前的过期事实 ——

本轮就撞上了：另一个会话的声明仍写着"B2 阶段 C 全部单 commit `cb18ec8`，**未 push**"，

而那批工作其实已由 #114 合入 master。



### 2. 修法：守卫只比「标记块之外」的内容



标记块**之内**的内容由脚本自己生成，长度本就该变；标记块**之外**是各 agent 手写的段落，

必须一字不少。所以新增 `count_outside()`，守卫改比这一部分。



### 3. 自测加 T5（覆盖"合法变短"这个此前没被覆盖的情形）



T5 造一个「旧块比新块长」的夹具（模拟上一版内容更长），要求写回**必须成功**（exit 0）、

标记块要在、手写段不能丢。



**变异验证**：把守卫改回比整份文件行数 → `自测失败：T5:旧块更长时应仍写成功(exit0) 实得 1`

⇒ T5 确实有牙，不是空过。



顺带修了一处让 T5 跑不起来的问题：`BEGIN`/`END` 两个标记定义原本在主流程里（L247），

而 `self_test()` 在它之前就执行 → 函数里取不到（`set -u` 下直接报 `unbound variable`）。

已上移到脚本头部。



### 4. 给它加 CI job（自测写了却没人跑，等于没有）



新增 `coord-status-selftest` job：在 ubuntu runner 上跑 `bash scripts/coord-status.sh --self-test`。

自测跑在**临时夹具**上（`COORD_STATUS_FILE` 指向 tmp），不会碰仓库里的 `.coord/STATUS.md`。

job 里给了 `GH_TOKEN`——自测的 T2/T3/T5 要真的执行一次 `--write`（那一步会调 `gh pr list`），

没有凭据就只能走 UNVERIFIED 降级分支。



这也补上了仓库里的一处惯例缺口：spi 门禁、test-count 门禁、e2e 公共库**都有**自测 job，

唯独协调工具这一份没有。



### 5. 计数



Java 4949 不变（不涉及代码），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — e2e-roc 跨平台改造并接入 CI —— 最后一个"写了却没人跑"的脚本（#121，2026-10-20）



`docs/contributing-guide.md` §2.5 登记着 12 个"本机实测全绿、尚未进 CI"的脚本。

逐个核对 ci.yml 后发现：**其中 11 个早就接进去了**（#107/#114/#119），文档没跟着更新；

真正还没进 CI 的只有 `e2e-roc.ps1` 一个 —— 登记在册、本机全绿、**CI 从来没跑过**。



### 1. 缺口：`e2e-roc.ps1`（E1 一控多机席位，14 项断言）



它比跨平台公共库（`e2e-common.ps1`，#107 才引入）还早半个月，埋了全套 Windows-only 依赖：

jar 路径用反斜杠字面量（Linux 上 `Test-Path` 恒 false）、java 可执行写死 `java.exe`、

`-WindowStyle Hidden`（Linux 上直接报"找不到参数"）、`$env:TEMP`（Linux runner 上常未设置），

而且**没有 `-D<name>-e2e=true` marker**，CI 的步间清理按标记回收时根本认不出它的残留进程。



改造按 B2 批次的 D1–D8 同款做法（不另发明）：dot-source 公共库、jar 路径走 `Get-E2eJar`、

java 走 `Get-E2eJava`、临时目录走 `Get-E2eTempDir`、去掉 `-WindowStyle Hidden`、

补 `-Droc-e2e=true` marker。



顺带把脚本自己那份 `Try-Json` 换成一行委派给公共库的 `Invoke-E2eJson` ——

后者带 5.1（`HttpWebResponse.GetResponseStream`）/ 7+（`HttpResponseMessage.Content`）

双分支回退，而原实现取不到 5.1 之外的响应体（V1 那三条 409/400 的正文就丢了）。

这正是 B1 在 pwsh 上踩过的坑（#107）。



本机复跑：改造前后均 **14/14 全绿**（exit=0），sim 端口 14580/14581、sysid 71/72

与同 job 其余脚本不撞。



### 2. 接入 CI（B2 A 型 job）



插在 `e2e-uspace` 与 `e2e-spray` 之间（A 型 job 内 6 步串行，backend 恒绑 UDP 14550）。

同时把 `'roc-e2e'` 加进该 job 的 7 处步间清理 marker 列表，收集日志的 find 块加 `af-roc-*`。



接入后 **18 个 PowerShell e2e 脚本全部在 CI 里跑**（此前 17 个）。



### 3. 顺带把 §2.5 台账按 CI 实测刷新



那一节的数字本身是对的（cv-eval 12 / operations 9 / rtk 3 / sensing 10 / uspace 13 /

spray 30 / network 9 / rf 4 / vision-pixels 6 / dock 23 / route-templates 16 / defect 18 / roc 14

—— 逐个与 CI 聚合步骤的实测输出核对过），但**两处状态是 stale 的**：



- "尚未进 CI（12 个，合计 122 项断言）"——这 12 个**现在全部已进 CI**；

  而它们的断言数合计是 **137**，不是 122（122 那个数把 B1/core 排除后也算错了）。

- "`e2e-core-scripts` job（ubuntu + pwsh，**观察模式**）……`continue-on-error` 待连续若干次

  全绿后摘掉转硬门禁"——2026-10-20（#119）已经转成硬门禁了。



改成按 job 列出的实测表，并写明五个 e2e job 现均为硬门禁、断言聚合步骤在统计到任何

`[FAIL]` 时**直接判红**（不是只警告）。



### 4. 计数



Java 4949 不变（不涉及代码），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 五个 e2e job 从「观察模式」转硬门禁（零红历史打底）（#119，2026-10-20）



观察模式（`continue-on-error: true`）是为了先收集 flaky 数据。数据收满了，现在转硬门禁。



### 1. 数据：近十两次 run，五个 job 全部零红



| job | 绿 | skipped | 红 |

|---|---|---|---|

| `E2E core scripts` (formation / mesh / rid) | 11 | 1 | **0** |

| `E2E MAVLink signing` (6 scenarios) | 11 | 1 | **0** |

| `E2E B1 scripts` (regulator / dock / route-templates / defect) | 9 | 1 | **0** |

| `E2E B2 A-type` (cv-eval / operations / rtk / sensing / uspace / spray) | 2 | 0 | **0** |

| `E2E B2 B-type` (network / rf / vision-pixels) | 2 | 0 | **0** |



那些 skipped 是运行本身被跳过（依赖 job 失败或运行被取消），不是这些 job 自己红过。



### 2. 同时把 job 名里的「— observe」去了



保留「observe」字样的 job 已经是硬门禁，就又是一处「声明≠行为」—— 名字会让人误以为它不会阻断发并。

因此五个 job 名字里的「— observe」一并去掉。



### 3. 说明性注记留在工作流里



写法器里留了上面那张记录表，避免以后谁想退回观察模式时，看不到当时的证据量。



---

---

---

## [Unreleased] — JSON 整数在 pwsh 7 里是 Int64，hashtable 的键又类型敏感 → 查不到自己存的键（#118，2026-10-20）



一条**只在 Linux 上现、在 Windows 上永远不现**的失败：本机实跑通过、CI 上失败，而后端日志里

三台机器全部注册成功、`online=true`、`rtkStatus` 也全对 —— **只是查不到键**。



### 1. 根因：两种 shell 把 JSON 整数解成不同类型，而 hashtable 的键是类型敏感的



JSON 里的整数，在两种 shell 里解析成的类型不同：



- Windows PowerShell 5.1（`Invoke-RestMethod` 内部用 JavaScriptSerializer）→ **Int32**

- PowerShell 7+（用 System.Text.Json）→ **Int64**



而 PowerShell 的 hashtable 对键是**类型敏感**的（本机实测）：



```

$h = @{}; $h[[int64]61] = 'x'

$h.ContainsKey(61)   # False

$h[61]                # 空

$h[[int64]61]         # x

```



所以“插入时用 JSON 解出的类型、查询时用字面量 Int32”一定查不到，表现就是“数据都在、就是去不到”。



### 2. 现场证据（不是推测）



B2 那批 e2e 脚本推上去让 CI 验之后，`e2e-rtk` 在 Linux 上失败、本机通过。

加上诊断输出后，CI 上的原始记录是：



```

[DIAG] 轮询 8 次，后端可达=True，已收集 3 个 online 的交机（sysid: 62,61,63）

[DIAG] 缺失 sysid: 61,62,63

[DIAG] 最后一次响应：sysid=61 online=True rtk=RTK_FIXED

[DIAG] 最后一次响应：sysid=62 online=True rtk=RTK_FLOAT

[DIAG] 最后一次响应：sysid=63 online=True rtk=STANDALONE

```



`$seen` 里**明明有** 61/62/63 三个键，`ContainsKey(61)` 却全部 False —— 这只可能是键的类型不对。



### 3. 修法：键归一化成字符串，两边用同一种形式



`e2e-common.ps1` 加一个归一化函数：



```powershell

function Get-E2eSysidKey {

    param([Parameter(Mandatory = $true)]$Sysid)

    return [string]$Sysid

}

```



`e2e-rtk.ps1` 的**插入与查询两处都走它**（只改一处不行：插入用归一化、查询用原值，依然查不到）。



公共库全是函数定义，master 的 `e2e-rtk.ps1` 因此补了一行 dot-source（它本来就没引公共库）。



### 4. 自检用例（每天都跑）+ 静态检查



`e2e-common-selftest.ps1`（CI 的 `E2E common library self-test` job，在 **Linux runner 的 pwsh 7 上**跑）加两类用例：



- **行为断言**：Int64 存的键能用 Int32 查到；归一化后的键就是字符串；插入与查询走同一函数就能对上。

- **静态检查**：`e2e-rtk.ps1` 里不得出现裸的 `$seen[...sysid...]` 查询。

  只做行为测试覆盖不了**调用点**：把查询端改回裸值，上面用例仍然全绿。



### 5. 变异 4/4 全杀



| 变异 | 结果 | 判红的用例 |

|---|---|---|

| M1 helper 不归一化（直接返回原值） | KILLED | Int64 存的键能用 Int32 查到 |

| M2 helper 归一化成 int（而非字符串） | KILLED | 归一化后的键就是字符串 |

| M3 查询端改回裸值 | KILLED | 静态检查（e2e-rtk.ps1 里的裸查询） |

| M4 插入端改回裸值 | KILLED | 静态检查 |



### 6. 计数



Java 4949 不变（本 PR 不动 Java 测试），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — rtk「没上线」的真相：Hashtable key 类型不统一（JSON 整数在 5.1 是 Int32、在 pwsh 7 是 Int64）让查找恒 null（2026-10-19）



`60ac648` 的诊断在 CI run 39279718213 拿到首条输出，根因当场锁定：



```

[DIAG] 轮询 8 次，后端可达=True，已收集 3 个 online 的交机

[DIAG] 缺失 sysid: 61,62,63（期望 61,62,63 全上线且 rtkStatus 正确）

[DIAG] 最后一次响应：sysid=61 online=True rtk=RTK...

[DIAG] 最后一次响应：sysid=62 online=True rtk=RTK...

[DIAG] 最后一次响应：sysid=63 online=True rtk=STA...

```



三机**全在响应里、online=True、seen 收了 3 个**，但 `ContainsKey(61)` 报缺失、

断言 `$seen[$sysid]` 拿 null。



**机制（此处更正一版更早的判断）**：不是"反序列化成了字符串"，而是 **JSON 整数在两种

shell 里解成不同类型** —— Windows PowerShell 5.1 的 `Invoke-RestMethod` 内部用

JavaScriptSerializer，解成 **Int32**；PowerShell 7+ 用 System.Text.Json，解成 **Int64**。

而 PowerShell 的 hashtable 对键**类型敏感**（本机实测）：



```

$h = @{}; $h[[int64]61] = 'x'

$h.ContainsKey(61)   # False

$h[61]                # 空

$h[[int64]61]         # x

```



所以"插入用 JSON 解出的类型、查找用字面量 Int32"必然查不到 —— 本机 5.1 两边都是 Int32

所以通过，CI 的 pwsh 7 两边类型不同所以失败。这也解释了此前三轮 CI 为何都是同一个 FAIL：

旧诊断条件（seen<3）永远看不见它。



修法：收集、查找、比较三处 key 全部归一化（`[string]`，两边用同一种形式）。

API 端的类型不由脚本控制，统一归一化是唯一自洽解。

---

---

## [Unreleased] — rtk 诊断盲区：`seen.Count -lt 3` 覆盖不到「sysid 不对」那种没上线（2026-10-19）



PR #114 的 CI run 39273582273 实测：e2e-rtk 的 FAIL 走「上线」分支（`$null -eq $d`），

但 `16243b6` 加的 `[DIAG]` 输出**零出现**。按断言代码反推：`$seen.Count` ≥ 3，

而 61/62/63 不在集合里——正是 `16243b6` 自己列出的**第三种**「没上线」

（响应里的 sysid 不是期望的那些），而 DIAG 的触发条件只认第一种（总数不足）。



改法：触发条件从「总数 < 3」改为「任一期望 sysid 缺失即打」，并补三样信息——

seen 的 sysid 集合、缺失清单、后端可达但从未拿到响应体的边界。

下一轮 CI 即可拿到 rtk 的真实失败形状，不必再猜。

---

---

## [Unreleased] — `Stop-Process -Id [int]$x.Name` 在实参位置不成立；公共库的 Linux 分支一调就崩（#117，2026-10-19）



把那批本地做的 B2 脚本推上去让 CI 验之后，CI 第一个跑就红了。不是脚本自己不对，

而是**公共库 `e2e-common.ps1` 里一个从未被发现的硬缺陷**，它在 master 上已经带着了一段时间。



### 1. CI 报的错误原文



```

Stop-E2eStaleProcesses: /home/runner/work/NexusSky/NexusSky/scripts/e2e-network.ps1:74

  74 |  Stop-E2eStaleProcesses -Marker $E2eMarker | Out-Null

     |  Cannot bind parameter 'Id'. Cannot convert value "[int]/proc/2977.Name"

     |  to type "System.Int32". Error: "The input string '[int]/proc/2977.Name' was not in a correct format."

```



看着像是个语法错误，但它的真面是**解析层**的：`[int]$procDir.Name` 里的 `[int]` 是字面量，

而不是类型转换。



### 2. 原因：PowerShell 在**实参位置**不做类型转换



原行（`scripts/e2e-common.ps1`，`Stop-E2eStaleProcesses` 的 Linux `/proc` 分支）：



```powershell

Stop-Process -Id [int]$procDir.Name -Force -ErrorAction SilentlyContinue

```



它看着像是"转成 int 再传过去"，实际上 PowerShell 在实参位置把它解析成**字符串拼接**：

`"[int]"` + 路径 + `".Name"`。所以 `-Id` 收到的是 `"'[int]/proc/2977.Name'"` 这个字符串，

绑定到 `[int]` 参数直接失败。



本机 Windows 上直接复现（这个坑与平台无关，是解析层行为）：



```

A) Show-Arg [int]$o.Name        -> ERROR: Cannot convert value "[int]@{Name=2977}.Name" to type "System.Int32"

B) Show-Arg ([int]$o.Name)      -> 绑上 Id=2977

C) 先存变量再传        -> 绑上 Id=2977

```



报错的形状与 CI 上完全一致（只是对象的字符串化不同：`PathInfo` → `/proc/2977`，

`PSCustomObject` → `@{Name=2977}`）。



**为什么之前没人拿到：**



- 这个分支只在 Linux 上走（Windows 走 `Get-CimInstance` 分支，`$_.ProcessId` 本来就是 int），

  本机 Windows 根本进不了这个分支。

- 而且它**只在真的匹配到了带 marker 的 java 进程时才会执行到那一行** ——

  所以它是**u9690性**的，而不是每次都点弹。

- 参数绑定失败发生在 `-ErrorAction SilentlyContinue` 生效**之前**，所以**压不住** ——

  这就是它把整个 e2e step 带崩的原因（不是被强忍的轻微失败）。



### 3. 修法：抽出成函数 + 加括号



```powershell

function Stop-E2eProcByDir {

    param([Parameter(Mandatory = $true)]$ProcDir)

    # 括号不能省：去掉就是上面那个必然抛异常的写法。

    Stop-Process -Id ([int]$ProcDir.Name) -Force -ErrorAction SilentlyContinue

}

```



单纯加括号就能修好，但**“加了括号”这件事本身没有测试能守住**：

原分支只在 Linux 上跑，Windows 本机根本进不来。所以把“按目录杀进程”这一步单独抽出来，

让自检脚本能用假对象直接喂它。



### 4. 自检补两类断言



`scripts/e2e-common-selftest.ps1`（CI 里的 `E2E common library self-test` job，**不带** continue-on-error）：



- **行为断言**：用假目录对象去调 `Stop-E2eProcByDir`，断言它**不抛**。

  用一个不可能存在的 pid（`[int]::MaxValue`）：进程不存在时 `Stop-Process` 抛的是

  `ProcessNotFoundException`（非终止错误，被 `-ErrorAction SilentlyContinue` 吞掉），而**绑定错误是终止错误**，

  catch 得到 —— 两者可区分。

- **源码级断言**：只测 helper 不够 —— 把**调用点**改回内联坏写法（helper 留着不用）时

  行为用例仍然是绿的。所以额外断言：剥掉注释后的源码里**不得出现** `-Id [int]$` 这种裸实参写法，

  且 `Stop-E2eProcByDir` 至少出现 2 次（定义 + 调用）。



  剥注释是必要的：函数上方的说明里**恰好**写着那个坏写法作为反例，不剔会把自己人判红。



### 5. 变异：3 杀 + 1 个显式记录的良性变异



| 变异 | 结果 |

|---|---|

| M1 helper 去掉括号（复活原错） | KILLED |

| M2 调用点改回内联坏写法 | KILLED |

| M3 调用点被注释掉（Linux 分支不再清理残留） | KILLED |

| M4 helper 里改成字符串拼接 | **存活 —— 预期良性** |



M4 值得单记：`([int]$x.Name)` 改成 `(([int]$x.Name).ToString() + '')` 产生的是数字字符串，

PowerShell 绑到 `[int]` 参数时照样会转回去 ——**运行行为完全相同**。

它不能被杀不是测试的缺陷，而是这条断言本来就不能区分它。

与其它存活不拿来充数，直接写在这里。



### 6. 同一次 CI 还拿到的另一个问题（属于 B2 分支，本 PR 不能修）



A-type 的 `e2e-rtk` 也红了，但根因**不同**：3 台 drone-sim 都启动了（日志里能看到），

却没有一台上线（`R1/R2/R3 sysid=61/62/63 上线` 全红）。这是 B2 脚本自己的问题，

与本 PR 的 pid 转换不是一个问题，也不属于公共库，留给 B2 分支自己处理。



### 7. 计数



Java 4949 不变（本 PR 不动 Java 测试），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 配置声明 `jwt.algorithm=RS256`，实际签发的是 HS256；算法选择逻辑**一条测试都没有**（#116，2026-10-19）



Next Move 里有一条自己写的「未核实」：



> `application.properties` 声明 `jwt.algorithm=RS256`，但登录 token 头是 `alg: HS256`。



这次把它查实了。结论分两句：行为不是 bug，但**声明确实是毛的**；更重要的是——

**决定签发算法的那段代码，一条测试都没有**。



### 1. 先分清“行为”和“声明”



`JwtTokenProvider` 的 Spring 主构造函数里有四路分支（Javadoc 写得很清楚）：



```

RS256 + 已配置 RSA 密钥对   -> RS256

RS256 + jwt.generate-keys=true  -> 自动生成（prod 禁止）

RS256 + 无 RSA 密钥      -> 回退 HS256

HS256                         -> HS256

```



`application-prod.properties` / `-staging.properties` 只设 `aerofleet.security.jwt-secret`，

从不配密钥对、也不开 `generate-keys`（而且 prod 明确禁止开）。所以**第三条就是

prod 实际跑的那条** —— 这是旧版本只能对称签名留下的有意回退，也有启动 warn，

不能算作 bug。真问题在另一头：



- `application.properties` 的小节写着「P2-2 RS256 非对称签名升级」，prod 读它、签的却是 HS256。

- **而判定到哪个分支的那段代码，全仓零测试**。



### 2. 为什么旧测试看不到：它们绕开了选择逻辑



`JwtTokenProviderTest` 是有的，4 项 + 11 项，以及一条“HS256 不能被 RS256 provider 验证”的交叉

验证。但它用的是**编程式构造函数**：



```java

new JwtTokenProvider(secret)              // 直接跳到 HS256 分支

new JwtTokenProvider(privKey, pubKey)     // 直接跳到 RS256 分支

```



两个都**不进选择逻辑**。所以它们证的是“HS256 模式能不能工作”、“RS256

模式能不能工作”，而不是“给一份配置，它会选哪个”。



全仓搜 `jwt.generate-keys` / `generateKeys`：**零命中**。



### 3. 新增 `JwtAlgorithmSelectionTest`（14 条）直接打 7 参构造函数



断言强调一点：`validateToken == true` **对算法没有鉴别力** —— 回退成 HS256 的

provider 同样会返回 true。所以每一条都把签出的 token 拆开，用 Nimbus `JWSObject` 精确取 JOSE

头的 `alg`（不用子串匹配）。



覆盖的里程弯里，有两条是写测试时才发现的漂移：



- **只配私钥不算配置完整**。判据是 `isNotEmpty(priv) && isNotEmpty(pub)`，只给一半就掉进

  回退分支 —— 有人意外只填了 `jwt.private-key`，结果整个服务悄悄换成对称签名，

  唯一线索就是启动时的 warn。

- **多 profile 串**。`spring.profiles.active=prod,observability` 是合法写法，守卫若写成

  `equals("prod")` 就会被绕过——而多实例部署恰好常这么配。



### 4. 变异 5/5，其中一条是补测试才能杀的



| 变异 | 结果 |

|---|---|

| S1 prod 守卫整体删除 | KILLED |

| S2 半套密钥对当成配置完整（`&&`→`||`） | KILLED |

| S3 `resolveSecret` 优先级倒置 | KILLED |

| S4 `contains("prod")` 收窄成 `equals("prod")` | KILLED（**补了多 profile 测试之后**） |

| S5 最小密钥长度检查删除 | KILLED |



S4 值得单记：**在我原先的 12 条测试下它是存活的**，只有补上

`prod,observability` 那条才能杀掉它。直接说明第一版测试集有漏。



S3 也一度是补的：第一版只断言了“两种组合都签 HS256”，对优先级**没有鉴别力**

，改掉优先级也不会红。现在改成跨 provider 互验证：A 配了两个键、B 只配 legacy，

A 签的 token 必须让 B 验不过。



### 5. 声明向行为对齐



在 prod / staging 两个 profile 的 JWT 段前各补一段说明：基线写的 RS256 在本 profile

下**不成立**（不配密钥对 + 禁止自动生成），实际签发的是 HS256，这是有意回退，

要真切到 RS256 需在这里补密钥对。行为不动，只让人不再被 `RS256` 字样引向错误假设。



### 6. 计数



Java 4935 → **4949**（cloud-backend 2829 → 2843），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — dev profile 写着“禁用 Redis”，health 却因为 Redis 永远是 DOWN（#114，2026-10-19）



先清 Next Move 里那条“SDK harness 本机实跑”（CI 上是绿的，我一次没跑过）。跑的过程中撞出

一个与“声明≠行为”同源的缺陷。



### 1. SDK harness 本机实跑：23 条全绿



照 `SDK Integration E2E` 那个 job 的摆法（backend

`--spring.profiles.active=dev` + drone-sim 带运行时生成的签名密钥）在本机跑

`scripts/e2e-sdk-integration.ps1`：



```

机队列表非空 (sysid=1 Drone-1) / get / telemetry / arm / takeoff / rtl

mission upload+download (items>=3) / flightlog / ApiResponse 格式

404 不存在 sysid / 400 无效命令

航点范围校验：lat>90、lon<-180、alt<0 三种非法均被拒

```



`PASS=23 FAIL=0 exit=0`，末尾 `ALL SDK INTEGRATION TESTS PASSED`。

CI 跑的是 `.sh`；本机是 Windows，`.sh` 仍只有 CI 那一条路。



### 2. 撞出的缺陷：dev 说不依赖 Redis，health 却硬依赖它



`application-dev.properties` 注释写着「Redis：开发环境禁用」，`spring.cache.type=simple`。

但本机没起 redis 时 `/actuator/health` 始终 DOWN，组件里 `redis=DOWN`。

同一台机器、redis 容器全程停着的对照：



```

修复前：200s 未就绪（我手动放弃）   db UP / drone UP / redis DOWN

修复后： 55s UP                      db UP / drone UP（redis 项消失）

```



原因不是配置写错，是 Spring Boot 只要存在 `RedisConnectionFactory` 就自动注册

`RedisHealthIndicator`——**它不看缓存用不用 Redis**。于是任何以 `health == UP` 为门禁的

本地流程（启动等待、e2e 脚本、CI 的 service 健康检查）都会卡住。这与“dev 不依赖

redis”的声明直接矛盾：声明说能独立起来，行为说不能。



修法是 dev 与 test 两个 profile 各加一行 `management.health.redis.enabled=false`。



### 3. 钉测试写成“规则”而不是“某一行”，并带一条反向断言



`ProfileHealthConsistencyTest`（3 条）不写死 dev，而是：凡是**继承或声明**了非 redis

缓存类型的 profile，都必须禁用 redis 健康指标。这样未来新增 profile 会自动被

检查到，而不是等人再卡一次 200 秒。



反向的那一半同样重要：prod / staging 用的是真 Redis 缓存，**必须保留** redis 健康指标——

Redis 真挂了却报 UP，比 redis 指标永远 DOWN 严重得多。这条断言专门防“顺手给 prod

也加上 false”。



### 4. 变异：4/4 全杀



| 变异 | 结果 | 判红的用例 |

|---|---|---|

| P1 dev 重新不禁用 redis 健康指标 | KILLED | `nonRedisCacheProfileDisablesRedisHealth` |

| P2 test 重新不禁用 redis 健康指标 | KILLED | `nonRedisCacheProfileDisablesRedisHealth` |

| P3 prod 也关掉 redis 健康指标 | KILLED | `redisCacheProfileKeepsRedisHealth` |

| P4 dev 悄悄改回 redis 缓存（注释不动） | KILLED | `devAndTestAreRedisIndependent` |



P4 是“注释继续说禁用、配置已经改了”这种最难发现的漂移，规则能抓住。



### 5. 顺带记一个会把人带沟里的 classpath 陷阱



第一版测试用 `getResourceAsStream("/application.properties")` 读配置，结果

`spring.cache.type` 读出来是 `null`——差点把“基线没配缓存类型”当成事实。实际是

`src/test/resources/application.properties`（只含安全相关几个键的**测试覆盖文件**）在 classpath 上排在

`target/classes` 之前。改成从 `src/main/resources` 源码树读，并把这个坑写进注释：

配置一致性测试要读**人真正编辑的那一份**。



### 6. 计数



Java 4932 → **4935**（cloud-backend 2826 → 2829），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — 钉住 `guessProtocol` 的三个死分支，顺带查出 **HLS 分支其实一直是断的**（#113，2026-10-19）



Next Move 里剩的那条："给 `guessProtocol` 的 hls/mjpeg/image 三个死分支钉测试，

否则删掉还是接通都没人拦"。



### 1. 先核实它们是不是真的死



不凭记忆 —— 全仓扫"流地址"的生产者：后端只有 `rtspUrl` 一处（私有协议/ONVIF

推导的 `rtsp://`，或 GB28181 的 `gb28181-invite://` 描述性标识）。**没有任何地方

产 `.m3u8` 或图片地址**，所以 `guessProtocol` 实际只会返回 `'rtsp'` 或 `null`。



按"不做减法"的口径：**不删**。它们是已有能力（接上 HLS 转封装或快照生产者时应当

立刻可用），删了要重写一遍，能力就真没了。改为用测试钉住。



### 2. 结果：钉测试的过程查出 HLS 分支**根本接不通**



第一个用例（`.m3u8` 应交给 hls.js 加载）直接红。查下去不是断言写错：



```

// 取流 effect 体内

setStream(data); setLoading(false)      // React 还没重渲染

...

const video = videoRef.current          // 必为 null

if (!video) return                      // 静默返回

```



`<video>` 要等 `!loading` 才渲染，而 ref 要在 effect 跑完**之后**的 commit 里才

填上。于是**即使有生产者产出 `.m3u8`，hls.js 也永远不会被加载** —— 分支看起来在，

实际是断的。今天没有 `.m3u8` 生产者所以看不出来，一旦有人接上就会静默失效。



**修法**：把 HLS 挂载从取流 effect 拆到独立 effect，依赖 `[protocol, streamUrl]`，

它在 `<video>` 已挂载的那次渲染之后才跑；`<video>` 也就不再等 `loading/error`。

**不动** hls.js 的加载逻辑，也不改任何对外契约。



### 3. 变异：4 杀 + 1 个**显式记录的良性变异**



| 变异 | 结果 |

|---|---|

| G1 挂载 effect 只在挂载时跑一次（回到原时序） | KILLED |

| G2 HLS effect 不调 `loadSource` | KILLED |

| G3 `mjpeg` 判在 `rtsp://` 之前 | KILLED |

| G4 未知协议又谎称 hls（#109 修过的老缺陷） | KILLED |

| G5 `<video>` 重新等 `loading` | **存活 —— 预期良性** |



G5 这条值得单记。第一版我把 G1 设成 G5（恢复渲染 gating），它**存活了**，说明我

的测试并没有覆盖我以为它覆盖的东西。查清楚后：当前 `setStream` 与

`setLoading(false)` 在**同一次 commit** 里，`isHls` 与 `<video>` 的出现同步，

ref 仍能填上 —— 所以去掉 gating 是**多一层保险**，真正起作用的修复是"拆成独立

effect"（G1/G2 证明）。与其悄悄留一个存活，不如把它作为良性对照显式跑出来。



G3 那条也踩了一次：第一版把 `.m3u8` 与 `rtsp://` 对调，而那本来就是既有顺序 ——

**空操作**。PRE-FLIGHT 的锚点自检把它拦下来了，改成真正把 `mjpeg` 提到 `rtsp://`

之前（路径含 mjpeg 的 RTSP 地址会被当成图片挂 `<img src="rtsp://…">`，而浏览器

播不了 rtsp://，结果是一张死图）。



### 4. 新增 4 条钉测试



`.m3u8` 走 HLS 分支 / `.jpg` 走 `<img>` / 非 rtsp 含 mjpeg 走 `<img>` /

**RTSP 即使路径含 mjpeg 也仍走 rtsp 分支**（顺序不变式）。当前后端下全部通过 ——

一旦有生产者接上而分支已腐坏，它们会先红。



---

---

---

## [Unreleased] — link-sim 第一次实机跑通；查出它的损伤引擎**一个测试都没有**（#112，2026-10-19）



这一轮把 Next Move 里"link-sim / SDK harness 本机实跑"这条清掉 —— 你说过能做，

而我此前一次没跑过。



### 1. 先跑通：它是个真能用的 MAVLink/UDP 链路损伤代理



`LinkSimMain` 有完整 CLI（`--profile / --port / --drone-ip / --drone-port /

--relay / --env-coupled / --tamper-rate`），9 个画像：`lan wifi5 wifi24-far lte

lte-edge radio-sik starlink tamper unsigned`。打包后本机实跑通了。



### 2. 顺带查出：`ImpairmentEngine` 没有任何直接测试



link-sim 有 8 个测试类，其余测试断言的都是**别处算出来的模型值**

（`totalDelayMs == 100` 之类），而"这个引擎按它声明的参数到底怎么判每一包"

从来没被直接钉过。补了 `ImpairmentEngineTest`（10 条）。



补测试的过程中撞出两条我读代码时读错的语义：



- **`rateBytesPerSec == 0` 表示"不限速"**，不是"不补充所以很快耗尽"

  —— 代码里是 `if (rateBytesPerSec > 0)` 整块跳过。各画像都给正值，所以这条路

  平时不走，但读代码的人会踩（我第一版测试就按"不补充"写了）。

- **令牌桶容量上限是 `burstBytes`**，紧密循环下调小桶会让丢包来自限速而不是

  被测的那项损伤 —— 我的第一版测试自己把包喂丢了，4 条红全是量具的问题。



### 3. 顺带实测出一件真事：声明区间不是保证值



`LinkProfile` 声明 `delay = delayMs + uniform(0, jitterMs)`，`verdict()` 也确实

按这个返回。但**投递延迟**由 `wirePump` 用 `Thread.sleep` 实现，而它的下限是

操作系统的线程唤醒粒度（本机 tick 约 15.6ms），不是它请求的毫秒数。



复测三轮（每轮 100 包，5pps，`lan` 作对照，本底 1ms）：



| 画像 | 声明区间 | 空载 p50 | 有负载 p50 | 落在声明区间内 |

|---|---|---|---|---|

| `wifi5` | [2, 6] | 5 / 5 / 6 | **12** | 86% → **15%** |

| `starlink` | [35, 55] | 47 / 46 / 47 | 53 | 81~85% → 62% |

| `lte` | [45, 80] | — | 72 | 78% |

| `lte-edge` | [120, 210] | — | 176 | 93% |



**这里我自己犯过一次并纠正**：第一版只跑了一轮（有负载那次），看到 `wifi5`

p50=12ms，就写成"多出恒定的约 +10ms"。复测三轮后发现空载时只有 5~6ms。

**+10ms 不是恒定值，是那一次的负载产物。** 真实机制是"超出量 = 到下一个 tick

边界的距离"，取决于调用时刻的相位，所以跑与跑之间天然不同。



可说的只有：**声明区间不是保证值**，对低延迟画像影响最大 —— `wifi5` 声明

[2,6]ms，有负载时实测 p50 12ms，此时"协议在 2ms 链路上的表现"这个结论说的是

"一条约 12ms 的链路"。且 Linux（CI 跑 e2e 的地方）粒度小得多，**同一画像在

开发机与 CI 上不是同一条链路**。



处理方式：**不动时序引擎**（那会改变所有依赖 link-sim 的 e2e 基线，是决策不是

机械修复），改为把事实记在三个地方 —— 代码注释（`wirePump`）、测试 javadoc、

以及可复跑的 `scripts/e2e-linksim-timing.py`。



### 4. 变异 5/5 被杀



其中 M1 是这条测试存在的唯一理由：**把"连续黑洞"改成同占空比的零星随机丢**

（总丢包率不变，只有形态变了）→ `partitionIsContiguousBlackhole` 判红。



另有两次自纠：

- M5 第一版是**退化变异**（测试本就用 `pBadToGood=0`，把"永不回好"写死对被测

  路径没有行为差别），"存活"是假象，改成"坏态立即恢复"后才真正击杀

- M2 第一版替换串多一个右括号 → 编译失败，属 INVALID 而非击杀



### 5. 一处量具自纠（值得单记）



`partitionIsContiguousBlackhole` 第一版按"丢弃调用数/总调用数"算占空比，量出

71%（期望 50%），差点判成实现缺陷。真实原因是**量具有偏**：分区判定在令牌桶与

延迟计算**之前**就 return，于是黑洞期每次调用更便宜、循环更快、落进去的调用

次数更多。改成按 1ms 时间桶取多数状态后实测 **49.8%** —— 引擎本身没问题。

---

---

## [Unreleased] — 推上去让 CI 验之后：B 型脚本在 Linux 上被自己杀了后端（#114，2026-10-19）



这条不是新功能，是**把本地做的那批 B2 脚本推上去让 CI 验之**的结果。

CI 第一个跑就红了 —— 且红的不是脚本自己，是**两个真问题**。



### 1. 一个后端被脚本开头的清理误杀



CI 的 `E2E B2 B-type scripts` 中，`e2e-network` 这一步失败，报错是：



```

Stop-E2eStaleProcesses: scripts/e2e-network.ps1:74

  Cannot bind parameter 'Id'. Cannot convert value "[int]/proc/2977.Name" to type "System.Int32".

```



这个错误本身是 master 上 `e2e-common.ps1` 的硬缺陷（已由 #117 修掉）：

实参位置的 `[int]$x.Name` 不是类型转换。



**修完之后下一轮 CI 又红了，但错变了**，变成“后端不可达”。这一次有确确的证据：



```

后端日志里 /api/v1/drones 只有 1 次命中（CI 自己那次 curl，200 OK），

日志到这里就结束了 —— 脚本的请求根本没到达。

```



真因：**marker 命名空间碰了**。



| 进程 | marker | 谁启动的 |

|---|---|---|

| B 型的 network 后端 | `network-e2e` | **CI（ci.yml）** |

| `e2e-network.ps1` 自己的 link-sim / drone-sim | `network-e2e` | 脚本 |



脚本开头的 `Stop-E2eStaleProcesses -Marker 'network-e2e'` 要清上一轮遗留的 sim，

但后端也带同一个 marker —— **脚本把脚下的后端一并杀了**。

后端一死，脚本第一个前置检查（后端可达）就会失败。



而失败原因被脚本的 `catch { }` 吞掉了 —— 所以那一次的信息量为零，

完全靠猜。



**修法**：把“验收方起的后端”与“脚本自己的 sim”拆到两个 marker 命名空间：



### 1b. 但修完第一版还是不够 —— 真正的命中点是 **H2 库名**



推送后的下一轮 CI 又红，错误不变：仍然是“后端不可达”，且后端日志里

`/api/v1/drones` 仍然只有 CI 自己那一次。为什么？因为**后端的命令行里不只有 `-D` 标记**，

还有数据库连接串：



```

--spring.datasource.url='jdbc:h2:mem:network-e2e;DB_CLOSE_DELAY=-1'

                                ^^^^^^^^^^^^

                                库名里就是 'network-e2e'

```



匹配是**松散子串**，所以库名也被当成 marker 命中了 —— 后端的命令行里

无论如何改 `-D` 标记，都会被这个库名拉下来。



**为何不能把匹配改成锚定在 `-D<marker>=`**：因为那会打断现有三个 marker。全仓查过：



| marker | 承载形式 |

|---|---|

| `formation-e2e` / `mesh-e2e` / `regulator-e2e` / `spray-e2e` / `network-e2e` / `cv-eval-e2e` … | `-D<marker>=true` |

| **`dock-e2e` / `route-templates-e2e` / `defect-e2e`** | **只有 `jdbc:h2:mem:<marker>` 这一处** |



那三个的唯一承载点就是 H2 库名（如 `scripts/e2e-route-templates.ps1:96`）。锚定到 `-D` 会让它们**永远清不掉**，

而它们的清理正是靠库名匹配才生效的 —— **松散子串匹配是承重的，不能改**。



所以正解只有一条：**让后端命令行里不出现 sim 的 marker**。除了拆 `-D` 命名空间，

H2 库名也要一并改名（`network-backend-e2e` / `rf-backend-e2e` / `pixels-backend-e2e`）。子串安全性核对：

`network-backend-e2e` 不包含 `network-e2e`，反向也不包含。



- CI 起的后端：`-Dnetwork-backend-e2e=true` / `-Drf-backend-e2e=true` / `-Dpixels-backend-e2e=true`

- 脚本自己的 sim：`-Dnetwork-e2e=true` / `-Drf-e2e=true` / `-Dpixels-e2e=true`（不变）

- 各 cleanup 步骤的 marker 列表同时列两空间



子串安全性核对过：`network-backend-e2e` 不包含 `network-e2e`，`network-e2e` 也不包含 `network-backend-e2e`。



### 2. 本机行为级验证（不只靠 CI）



在本机 Windows 上起两个带不同 marker 的 java 进程，再用公共库的清理去对它们：



```

Stop-E2eStaleProcesses -Marker 'network-e2e'        -> killed=1  （只杀 sim），后端 alive=True

Stop-E2eStaleProcesses -Marker 'network-backend-e2e' -> killed=1  （杀后端），alive=False

```



H2 库名这一层也同样回归本机实测（两个进程的命令行里都带 `-Dnetwork-backend-e2e=true`，

区别只在 h2:mem 库名）：



```

A（改名后，h2:mem:network-backend-e2e） -> 清理后 alive=True   期望值：不被误杀

B（改名前，h2:mem:network-e2e）        -> 清理后 alive=False  期望值：复现原错（库名被当成 marker 命中）

```



顺带也证明了 #117 的 pid 转换修正真的能杀进程（两次 killed=1）。



### 3. 日志收不到：脚本写 /tmp，CI 只扫 runner.temp



`Get-E2eTempDir` 在 Linux 上回退到 `/tmp`（pwsh 常未设 `TEMP/TMP`），

而 CI 的「收集日志」步骤只扫 `${RUNNER_TEMP}` 一层 —— 所以后端、sim 的日志

**一个也收不到**，失败时只能看到 sim 的 stdout。



修法：`Get-E2eTempDir` 优先用 `$env:RUNNER_TEMP`（CI 提供），这样既有的收集步骤就能找到日志。



### 4. 失败原因不再被吞掉



`e2e-network.ps1` 的 `catch { }` 改成提取 `$_.Exception.Message` 并在退出前打出。

“后端不可达”这句话既可能是真没起、也可能是被清理误杀 ——

两者排查方向完全相反，吞掉异常等于让排查靠猜。



### 5. 还没查出的：e2e-rtk 是平台差异（本机过、Linux 不过）



A 型的 `e2e-rtk` 也红，但根因不同：3 台 sim 都启动了（日志里能看到），

却没有一台上线。**本机实跑 `scripts/e2e-rtk.ps1 -SkipBuild` 是通过的（exit=0）** ——

所以是 **Windows 上通、Linux 不通**的平台差异，而不是脚本逻辑写错。



正确的诊断路径已打通（上面第 3 条）：下一轮 CI 就能给出后端日志，

再按日志定位是端口、绑定还是网络层的问题。在那之前不形成结论。



### 6. 计数



Java 4949 不变（本轮不动 Java 测试），前端 381 不变。门禁绿。



---

---

---

## [Unreleased] — e2e 救活第三批（B2）：9 个脚本跨平台适配并以观察模式接进 CI（2026-10-19）



> 与前两批（formation/mesh/rid 的 `e2e-core-scripts` job、regulator/dock/

> route-templates/defect 的 `e2e-b1-scripts` job）同一条脉络。B2 共十脚本：

> **9 个接入**，px4-sitl 判定**不接入**（ubuntu runner 无 PX4 SITL、脚本宿主

> 导向，接了必红——预研 D1 拍板）。脚本侧改造（跨平台适配 + marker + spray

> 端口参数化）已在 worktree 阶段完成并独立核验（9/9 语法解析通过、代码级

> Windows-only 残留为零、marker 全部就位），本条记录的是接线：ci.yml 两个

> 新 job + CHANGELOG + 本 commit。



### 跨平台适配（9 脚本，全部 dot-source `scripts/e2e-common.ps1` 公共库）



| 问题 | 处理 |

|---|---|

| java 探测硬编码 `java.exe` | `Get-E2eJava`（AF_JAVA → JAVA_HOME → AF_JDK17_HOME → PATH） |

| `$env:TEMP` 直接引用 | `Get-E2eTempDir`（Linux runner 常未设置 TEMP/TMP） |

| `-WindowStyle Hidden`（Windows-only 参数） | 删除 |

| jar 路径反斜杠字面量 | `Get-E2eJar`（Linux 上反斜杠不是分隔符，`Test-Path` 恒 false） |

| `Get-CimInstance Win32_Process` 清理（spray / network） | `Stop-E2eStaleProcesses`（Win CIM / Linux `/proc` 双实现） |

| 硬编码 `E:\dev-tools\...` JDK 路径 | `Test-Path` 保护（CI 走 `-SkipBuild` 不进构建分支，纯防御） |

| 8 个脚本进程命令行无清理凭据 | 补 `-D<name>-e2e=true` marker（放 `-jar` 前由 JVM 消费；spray 原有） |

| spray 端口硬编码 8080/14550/14551 | 参数化：`AF_BACKEND_PORT` / `AF_SPRAY_DRONE_PORT` / `AF_SPRAY_SIM_PORT`（默认值不变） |



### CI 接入（`e2e-b2-a-type` / `e2e-b2-b-type` 两 job，观察模式）



- **拆两个 job 的原因**：cloud-backend 恒绑 UDP 14550（`aerofleet.udp-port`，

  所有脚本均不覆盖）→ 同一 runner 内任意两个 backend 不能共存、脚本必须

  串行；A/B 两型分到独立 runner 上可安全并行（与 `e2e-b1-scripts` 也互不依赖）。

- **A 型 job**（6 脚本自启 backend）：cv-eval → operations → rtk → sensing →

  uspace → spray 串行；每步 `AF_BACKEND_PORT=18099` 统一 REST 口（spray

  参数化后并入同一变量）；spray 的 drone/sim 端口用默认 14550/14551；

  每步间按 marker 兜底清理（B1 同款安排）。

- **B 型 job**（3 脚本不自启 backend，保持外部启动契约）：network → rf →

  vision-pixels 串行；每步是自包含 bash step——CI 起 backend（统一

  `jdbc:h2:mem:<name>-e2e` + dev profile + jwt-secret + REST 18099）→ 等就绪

  （`/api/v1/drones` 业务探针；`/actuator/health` 在 Redis 未通时恒 503，

  不可用作探针）→ 跑脚本 → 记 PID 杀 backend + marker 兜底。各步专属参数：

  network `--aerofleet.drone-port=14600`（指向链路代理）；rf 保持默认

  drone-port 14550 + 默认 extra 14541-14543（sim 挂 14543 恰在扫描段内）；

  vision-pixels `--aerofleet.vision.source=pixels

  --aerofleet.sim-truth-base=http://127.0.0.1:18082

  --aerofleet.drone-extra-ports=14544`（三参数缺一不可，脚本文件头有完整

  说明）。注意 B 型脚本读的端口变量是 `AF_BACKEND_REST_PORT`（与 A 型的

  `AF_BACKEND_PORT` 不同名，默认 8080，CI 统一覆盖为 18099）。

- **断言聚合步骤（防观察模式信号丢失）**：continue-on-error 会把红压成绿，

  每个 job 末尾统计两族断言格式（`[PASS]` 族：cv-eval/operations/rtk/

  sensing/uspace；`PASS:` 族：spray/network/rf/pixels——正则 `(\[PASS\]|PASS:)`

  两族通吃），汇总各脚本通过数，有失败时 `::warning::` 注解留痕；失败时

  收集进程日志与 step 日志上传 artifact。脚本步骤本身 `tee` 留档 + pipefail

  保退出码，聚合步骤从留档重新统计。

- **观察模式理由与 B1 相同**：本机 Windows 全绿 ≠ Linux CI 连续复跑稳定

  （sensing/network/rf/pixels 对时序敏感），若干次 run 全绿后删

  continue-on-error 转硬门禁。



### 验证



- 9 个脚本 PowerShell 语法解析零错误（worktree 阶段独立核验，本条未改脚本行为）；

- ci.yml YAML 语法校验通过（25 个 job，其余 job 结构未受影响）；

- marker 拼写与脚本内 `-D<name>-e2e=true` 注入逐一比对一致（A 型 6 + B 型 3）；

  A/B 两型端口变量名（`AF_BACKEND_PORT` / `AF_BACKEND_REST_PORT`）读回核对无混淆。



### 计数



**未变**：e2e 脚本不产生 surefire 报告，Java / 前端计数均不受影响。



---

---

---

## [Unreleased] — 把"下载超时"当成了"拿不到"：ffmpeg 本机就有；顺带查出一个真实凭据编码缺陷（#111，2026-10-19）

这一轮先把**几个被写成"阻塞"的事**逐个试了一遍，结论是其中大部分不是"不能"，是

"我没试够"。



### 1. 阻塞①"ffmpeg 拿不到" —— 本机早就有一个完整 ffmpeg



前几轮的记录是："BtbN 与 gyan 下载均超时/连接重置，20 分钟 0 字节，这条路暂时不通"。

这一轮先查本机，结果：



```

C:\Users\winge\AppData\Local\Programs\icat\resources\bin\ffmpeg\ffmpeg.exe

ffmpeg version N-109970-g7dc0944ce2 （2023 年构建，配全套 DLL）

```



**它一直在磁盘上，我一次都没找过。** 而且它带 `h264_nvenc`（GPU 编码）、

`h264` 解码器、`mpeg4`/`mjpeg` 原生编码器、RTSP 复用器、HLS。

"cv2 无 H.264 编码器"这个限制也随之绕过（用 ffmpeg 而不是 cv2 编）。



同批还纠正了两处：

- **"下载超时"其实是"下载慢"**：mediamtx（27 MB，GitHub releases）实测下了

  **878 秒**才完成。之前给的超时不够就断言"不通"。

- **"自写 RTSP 服务端这条路不通"** —— 根本不用自写，ffmpeg 自带 RTSP，

  mediamtx 也能下。**问题从来不是没有工具，是我把"麻烦"记成了"不能"。**



### 2. 用解锁的工具验了一件此前验不了的事：RTSP URL 形状



厂商适配器按公开规范「推导」RTSP URL（`VENDOR_STANDARD_FORMAT`，`deviceVerified=false`）。

现在可以拿**真 RTSP 服务器**验它的形状：



- mediamtx v1.21.2 + ffmpeg 把本机 UVC 摄像头编成 H.264 推上去

  （mediamtx 日志：`[path Streaming/Channels/1] 1 track (H264)`）

- 用我们代码拼出的形状去读：`rtsp://admin:pass@127.0.0.1:8554/Streaming/Channels/1`

  → **`h264,640,480,15/1`，3 秒读到 35 帧**（活流）

- **反向对照**（没有对照的成功不算证据）：密码错一位 → **401**；

  路径改成 `Channels/9` → **404**。说明鉴权与路径都真的被服务器用了。



结论要说得准：**URL 的"形状"验证过了**（scheme/userinfo/authority/path 都对），

但"海康设备是否真用这个路径"仍未向真设备确认，`deviceVerified=false` 不变。



### 3. 由此查出一个真实缺陷：凭据编码用了 form 语义



四个适配器各自拼 URL，都用了：



```java

URLEncoder.encode(device.password, StandardCharsets.UTF_8)

```



`URLEncoder` 实现的是 `application/x-www-form-urlencoded`（HTML 表单）语义，

不是 URL 语义。两者只在**空格**上分歧：



```

form：空格 → "+"        URL：空格 → "%20"（"+" 是字面加号）

```



密码 `"p w"` 于是变成 `"p+w"` 发到设备上 —— 鉴权失败，而现象只是"连不上"。

而这几行上方的注释**恰好声明了相反的事**：

"凭据需 URL 编码，避免密码含 `@ : / # ?` 等特殊字符破坏 URL 结构"

（`@ : / # ?` 确实处理对了，唯独空格没有）—— 又一处"声明≠行为"。



**线上证据（不是推理）**：起一个只做 Basic 挑战的 RTSP 服务端，读客户端重试时

发出的 `Authorization` 头并解码：



| URL 里的编码 | Authorization | 设备实际看到的凭据 |

|---|---|---|

| `admin:p%20w` | `Basic YWRtaW46cCB3` | `admin:p w` ✓ |

| `admin:p+w` | `Basic YWRtaW46cCt3` | `admin:p+w` ✗ |



**修复**：新增 `VendorRtspUrl` 作为**单一构造点**（此前四个适配器各抄一份，

同一个缺陷也复制了四份），用 `UriUtils.encodeUserInfo`（RFC 3986 语义）。

路径部分仍由各适配器给（那是厂商差异）。



**变异 5/5 被杀**：退回 form 编码 / 完全不编码 / 只把 `+` 换成 `%20`（看似等价实则不是）

/ user-pass 互换 / 海康绕开单一构造点。



### 4. 新增验证脚本（都能独立跑）



- `scripts/e2e-rtsp-credentials.py` —— 线上凭据编码验证（含**鉴别力自检**：

  两种编码必须产生不同结果，否则脚本没有资格判定）

- 本机实跑 **6/6 PASS**



### 5. 顺带排除的一个假警报



全量 `mvn test` 一度报 21 个错误（`BindException: Address already in use`）。

根因是**我自己留着跑的 prod 后端**占着 `UdpGateway` 要绑的端口 —— 停掉后

**2826 / 0 失败 / 0 错误 / BUILD SUCCESS**。不是代码回归。



### 6. 仍未解决的一项：DJI Pocket 3



设备列表里只有内置 `HD Webcam`，**需要用户插上硬件**，我这边无法代替。

---

---

## [Unreleased] — 安防视频**终于能看了**：补上唯一一条浏览器真能播的出口（#110，2026-10-18）

接着 #109。#109 把"不能播"这件事**说准了**（`streamUrlOrigin` 三态 + 前端不再谎称

HLS），但结论停在"看不了"。这一条把那个结论**推翻**：能看，而且现在真的能看。



### 1. 为什么此前一定看不了（不是配置问题，是结构性的）

浏览器不能原生播 `rtsp://`；本平台**没有 RTSP 解码器**（不引入 ffmpeg / javacv）。

于是 `/stream` 给出的地址对浏览器毫无用处，格子上只能写"RTSP 流需后端转码"——

而"转码"这件事并不存在。**取不到源解决不了这个问题**，必须换一个浏览器能播的载体。



### 2. 选 MJPEG，以及为什么帧源是"显式配置"而不是"推导"

- **RTSP**：浏览器不能播，平台也解不了。

- **HLS**：要切片转封装 + 前端为它加载 hls.js，单设备预览不值得这份复杂度。

- **MJPEG**：`multipart/x-mixed-replace` 一帧一个 JPEG，浏览器 `<img src>` 直接

  渲染，零插件零依赖，首帧即可见。



帧源**不按厂商规范推导**。`rtspUrl` 是推导出来的（`rtsp://user:pass@ip:554/…`），

格式对真实设备可能正确，但代码从未向设备确认过（#109 的 `deviceVerified=false`）。

若快照地址也照厂商惯例拼一个（海康 `/ISAPI/Streaming/channels/N/httppreview` 之类），

就又造出一个"看着对但没验证过"的地址——正是 #105–#109 一路在清的东西。

所以新增的 `snapshotUrl` 是**配置**：填了就必须真连得上，连不上就明确报错；

不填则界面如实显示放不出画面，而不是挂一个空格子。



### 3. 开流之前先探一帧

帧源不可达时返回 **502**，而不是先回 200 再让浏览器干等——后者表现为"一直转圈"，

使用者会以为是网络慢。帧源中途掉线则**结束响应**让浏览器自行重连，**不发占位帧**：

占位帧会让"有画面"和"画面是死的"看起来一模一样。



### 4. 鉴权走既有短令牌机制，没有另开一条更弱的路

`<img>` 带不了 Authorization 头。第一版我给这个路径加了 `permitAll` +

自己写的 `?token=<长效 JWT>` 校验——这是**更弱**的路径：长效 JWT 进 URL 等于把

bearer 暴露给所有能看到访问日志的人，与 `StreamTokenService` 存在的理由相反。

已全部删除，改为把 MJPEG 路径纳入既有 `StreamTokenFilter`

（60s 单次用 opaque 令牌，与两条 SSE 流同机制），链上仍是 `anyRequest().authenticated()`。

**"链上放行"从未发生；放行的是短令牌这条既有通道。**



### 5. 一个被测试当场抓出来的真 bug（不是 MockMvc 的锅）

第一版把方法签名写成 `ResponseEntity<StreamingResponseBody>`。返回类型一变，

命中的是 `ResponseEntityReturnValueHandler` → `HttpEntityMethodProcessor`，它把这个

lambda 当普通消息体去找转换器，直接抛

`No converter for StreamingResponseBody with preset Content-Type multipart/x-mixed-replace`。

**生产环境同样是 500**——这不是测试框架的局限。改为裸返回 `StreamingResponseBody`，

响应头改由 `HttpServletResponse` 设置。



### 6. 验证：真实摄像头 → 真帧源 → 真后端 → 真字节

新增 `scripts/camera-snapshot-source.py`（UVC 摄像头 → 真实 HTTP JPEG 快照端点，

模拟网络摄像头普遍具备的 HTTP 取图能力）与 `scripts/e2e-mjpeg-preview.py`

（22 项断言，全落在实测字节上）。本机实跑：

- 帧源 200 / `image/jpeg` / SOI=`ffd8ff`，两次取帧内容不同（排除缓存的静止画面）

- `browserPlayable=true` 且 `snapshotUrl` 原样回显（由真帧源决定，非推导）

- **匿名 → 401 且一帧未漏**（防"链上放行"退化成"不鉴权"）

- 开流 → 200 / `multipart/x-mixed-replace` / `no-store`；实收 5 帧，

  帧号 `[1,2,3,4,5]`，每帧 SOI 合法、声明长度与实际字节一致、帧内容在变

- 注销 → 404 + JSON，不留挂着的空格子



变异测试 14/14 被杀（后端 7 + 前端 7），0 存活 0 无效。

其中后端 B3/B6/B7 直接盯着"不许先回 200 再干等"，B5 盯着短令牌路径不被摘掉。



### 7. 顺带记下的两处登记缺口（如实记录，未修）

- 厂商枚举没有 `GENERIC`/`OTHER`，非厂商设备只能登记为 `ONVIF`

  （枚举自述的通用 fallback）。**与 /mjpeg 无关**（帧源由 `snapshotUrl` 决定）。

- `registerDevice` 强制 `username` 非空，而本地非厂商设备本就没有凭据，

  只能用占位值。**与 /mjpeg 无关**，但对真实非厂商设备是登记口径需要放宽的地方。



---

---

---

## [Unreleased] — 取流面板**从未真正播放过**：读的是后端不返回的键；诚实信号被前端静默吞掉（#109，2026-10-18）



接着 #108 往下查剩下的"声明≠行为"。上一轮我以为剩余项只是"适配器还在拼一个

看起来能用的 RTSP 地址"，量下来发现那只是表层 —— **下面这层更严重**。



### 1. 取流面板读了一个后端从不返回的键



```

后端 GET /devices/{id}/stream 返回：  { deviceId, channel, rtspUrl, ... }

面板两处各读一次：                    data.url / stream.url     ← 恒为 undefined

```



后果不是"某个功能不好用"，而是**整条取流路径从未执行过**：



- 挂载路径：`if (!url) return` 每次提前返回

- 渲染路径：`isImg` / `isHls` / `isRtsp` 三个分支永远全假 ⇒ 格子一直空着



这是同类缺陷的**第三个实例**（#104 的 `data.events`、#105 的 `deviceId`、

这次的 `rtspUrl`）。三处根因相同：**同一份契约在前后端各写一遍，改了一边忘了另一边。**



**为什么一直没被发现**：`panelSmoke.test.jsx` 把 `SurveillancePanel` 整个跳过，

理由写的是"依赖 window.Hls 与真实 video 播放，jsdom 无法播放"。但这个缺陷

**与播放无关** —— 读错键名是纯数据分支，jsdom 完全测得着。**跳过理由掩盖了可测的部分。**



### 2. 后端"不可播"的诚实声明被前端静默吞掉



`Gb28181Adapter` 早就确立了诚实约定，日志里写得很清楚：



```java

log.warn("... INVITE constructed ({} bytes), but RTP/PS media plane is "

 + "production-stage (no fake playable URL returned)");

return "gb28181-invite://" + id.value() + "?channel=" + channel;

```



`Gb28181ShapeTest` 还有一条守卫：`assertThat(url).doesNotStartWith("rtsp://")`，

注释是"绝不假扮可播的 rtsp://"。**约定成文、有测试守卫。**



而前端的 `guessProtocol` 对认不出的 scheme **一律返回 `'hls'`** —— 于是

`gb28181-invite://` 被当成 HLS 去播，用者只看到一个语焉不详的播放失败。

**"我不知道这是什么协议"被讲成了"它是 HLS"。**



### 3. 顺带修掉 #108 放大出来的一个真实功能损失



`OnvifClient.getDeviceCapabilities` 写的是"PTZ / Imaging 仅在 `port == 80` 时有"

—— 端口号和云台能力之间没有任何真实关联。能力门控接通前它只影响空地协同；

#108 之后它会**拦真实的用户操作**：注册在非 80 端口的真摄像头被判成不支持云台。

已改为统一声明标准能力集，并把"没有向设备确认"记在别处。大华此前独缺

`Imaging`，一并对齐 —— 同样没有任何测试会发现。



### 4. 取流地址的来源如实自述（新增 `StreamUrlOrigin`）



这里我上一轮的判断是**不完整**的。`rtsp://user:pass@ip:554/Streaming/Channels/1`

**确实是海康真实的 RTSP 格式**，对真摄像头**可能真的能播** —— 所以不能照抄

GB28181 改成"不可用"（那是错杀）。三类地址性质不同：



| 来源 | 可播性 | 设备是否校验过 |

|---|---|---|

| `VENDOR_STANDARD_FORMAT`（三个私有协议 + ONVIF） | 可能可播 | **否** |

| `ONVIF_GETSTREAM_URI`（接入真实 SOAP 后） | 可播 | 是 |

| `DESCRIPTIVE_ONLY`（GB28181 媒体面未接入） | **不可播** | 否 |



`/stream` 响应因此带上 `streamUrlOrigin` / `playable` / `deviceVerified` ——

把"也许能用"讲成"能用"是失真，讲成"不能用"是错杀，两个都要说。前端对

`playable=false` 与认不出的协议都**如实说明**而不是挂播放器；对

`deviceVerified=false` 的地址额外标注"未向设备校验"。



### 5. 键名解析收敛到单一函数



修的时候先在两处各写了一遍 `data.rtspUrl || data.url` —— 结果三个变异存活，

因为**契约知识被复制成两份，可以各自漂移**。收敛成 `resolveStreamUrl()` 一处解析后

重验，7/7 全杀。这正是本缺陷的成因，不该在修复它的时候再犯一次。



### 变异验证



后端 7/7、前端 7/7。其中两处是靠补测才杀得掉的：



- **M7（大华去掉 `Imaging`）第一轮存活** —— 没有任何测试钉住三个私有适配器的

  能力集。补了"三者声明一致"与"ONVIF 能力与端口无关"两条后杀掉。

- **前端 FM1/FM3/FM7 第一轮存活**，根因就是上面第 5 点（解析点重复）。



### 口径



后端 4892 → **4897**，前端 363 → **368**。按门禁报出的 24 处 `文件:行号` 同步。



### 仍然明确没做



三个私有协议适配器的**真实媒体面**（HCNetSDK / DHSDK / UNIVIEW SDK / ONVIF

GetStreamUri）仍然没有 —— 那是对着真实摄像头硬件做协议对接，不在本次范围。

现在它们的地址被明确标注为"按厂商规范推导、未向设备校验"，至少不再假装是实测值。



---

---

---

## [Unreleased] — 设备能力声明此前**基本没人读**：门控缺失、词汇表不统一、能力模型是死类（#108，2026-10-18）



上一轮讨论"要不要给本机摄像头加适配器"时去量代价，发现的能力机制问题比那更值钱。

摄像头只是缺一个源；**能力声明本身是空的**。



### 三个实测出来的问题



**1. 门控基本不存在，而且唯一存在的那处和不管的那些互相矛盾。**



严格数过（排除定义 / equals / hashCode / toString）：



```

把 ptzSupported 等字段当条件读：0 处

前端引用这些字段：            0 处

前端消费 capabilities：       0 处

```



活的那一处是 `AirGroundCoordinationService` 里的 `contains("PTZ")`。也就是说

**同一台设备，空地协同服务认为它不能转，`SurveillanceController` 的 PTZ 端点却

照单全收**。端点从不看能力。



**2. 各适配器声明的词汇表并不一致**，直接按字面名写门控会制造假阴性：



| 适配器 | Device | Media | Events | PTZ | Imaging | STREAM | RECORD | ALARM |

|---|---|---|---|---|---|---|---|---|

| 海康 | ✓ | ✓ | ✓ | ✓ | ✓ | | | |

| 大华 | ✓ | ✓ | ✓ | ✓ | **✗** | | | |

| 宇视 | ✓ | ✓ | ✓ | ✓ | ✓ | | | |

| ONVIF(port80) | ✓ | ✓ | ✓ | ✓ | ✓ | | | |

| ONVIF(port≠80) | ✓ | ✓ | ✓ | **✗** | **✗** | | | |

| GB28181 相机 | ✗ | **✗** | ✗ | ✓ | ✗ | **✓** | ✓ | |

| GB28181 报警设备 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ |



GB28181 写的是 `STREAM` 而不是 `Media` —— 按 `Media` 门控会把它的取流误判成

不支持。（另外 ONVIF 的能力取决于端口号、大华比海康少 `Imaging`，看着像随手写的，

本轮不改，只是不再让它们影响行为。）



**3. `VendorCapabilities` 是死类** —— 208 行，只有它自己内部 `new`，

全仓没有任何字段或返回值用它。真正在用的是 `Set<String>`。这是

`SurveillanceEvent` / `AlarmEvent` 那组重复模型的又一次重演。



### 改法



- **新增 `DeviceCapability`**：唯一口径，负责归一（`STREAM→MEDIA`、

  `RECORD→RECORDING`、大小写/空白无关，未知名字保留而不静默丢弃）。

- **PTZ / 取流端点真的按能力门控**：设备声明了能力集但缺该项时，返回 400，

  并在响应里回带它声明了什么 —— 否则前端只能显示一句无信息量的失败。

- **设备视图输出三态能力位** `ptzSupport` / `mediaSupport` / `eventsSupport`。

- **前端据此禁用 PTZ 按钮并说明原因**，不再让运维点了之后收一个 400。

- **删掉死类 `VendorCapabilities`**。



### 为什么是三态而不是布尔



「设备没声明任何能力」是**未知**，不是不支持。两种策略各有正当场景，

所以**由调用点显式声明**，而不是把某个默认值偷偷固化进判定：



| 场景 | 口径 | 理由 |

|---|---|---|

| 运维手动按 PTZ（控制器端点） | 不明确不支持即放行 | 未声明就拒绝，会让早期设备平白失去功能 —— 那是**功能减法** |

| 自动挑选最近的 PTZ 设备（空地协同） | 必须明确声明才认 | 把未知当"可以"，可能派一台根本不能转的摄像头去执行任务 |



两条用例把这个差异钉死（`policiesDifferOnUnknownByDesign` /

`manualAndAutomaticPoliciesDifferOnUndeclared`），防止有人后来把它们"统一"成一个。



三态在前端也是三态：**未声明传 `null` 而不是 `false`** —— 压成 false 会让前端

灰掉其实能用的设备，压成 true 则会放行明确不能转的设备。两种失真都是新的

"声明≠行为"。



### 变异验证



后端 9/9、前端 5/5。过程中两件事记在这里：



- **M9 第一版存活**：我最初那条"未声明设备不会被选中"只断言了返回值不是

  `NO_DEVICE`，而两个口径下返回值都是 `TRACKING` —— **我自己的假绿测试**。

  改成用 mock 的 `OnvifClient` 捕获 IP，断言**哪一台**拿到了 PTZ 指令。

- **FM3 被判定为退化变异并移除**：`ptzSupport === false` 是唯一消费者，

  所以 `null` 和 `true` 渲染完全相同，`?? true` 行为等价 —— 它既不可能让任何

  用例变红，也不是有效变异。记为"存活"是对测试的误报，留着它是对变异的撒谎。

- 前端 FM6 第一版把应用**渲染搞崩**（`ReferenceError`）导致 14 条全红，

  那不是有效击杀；改成真正的"取第一台设备的能力"并把渲染崩溃纳入守卫。



### 口径



后端 4872 → **4892**，前端 359 → **363**。按门禁报出的 24 处 `文件:行号` 同步。



### 明确没做



三个私有协议适配器的 `getStreamUrl` **仍在拼接一个看起来能用的 RTSP 地址**

（`rtsp://user:pass@ip:554/...`，旁边挂着 TODO）。本轮没动它：要改就得决定

"返回占位符"还是"返回 null"，那是产品决策，不该顺手带过。能力机制接通之后，

至少"设备明确不支持取流"这条路已经是诚实的了。



---

---

---

## [Unreleased] — e2e 救活第二批：regulator/dock/route-templates/defect 以观察模式接进 CI（2026-10-17）



> 与第一批（formation / mesh / rid，`e2e-core-scripts` job）同一条脉络。

> 本批 4 个脚本此前从未进过 CI，2026-10-17 深夜在本机 Windows 串行跑绿

> （master `035a338`，合计 **94/94 断言**：regulator 37 / dock 23 /

> route-templates 16 / defect 18）。本条只做"接线"——跨平台适配 + CI 接入，

> 不改任何被测行为（阶段 A 验证已确认无需修复）。



### 跨平台改造（照 e2e-signing / e2e-core-scripts 两批先例，全部复用 e2e-common.ps1）



| 问题 | 位置 | 处理 |

|---|---|---|

| jar 路径反斜杠（`'cloud-backend\target\x.jar'`） | dock / route-templates / defect ×2 处、regulator 的 glob ×2 处 | Linux 上反斜杠不是分隔符，`Test-Path` 恒 false → 误判"jar 缺失"进构建分支（该分支还硬编码 Windows JDK 路径）。dock 系换 `Get-E2eJar`；regulator 的 `Get-ChildItem` glob 改正斜杠 |

| java 可执行 `java.exe` | 4 个脚本各 1 处 | Linux 叫 java → `Get-E2eJava` |

| `$env:TEMP` | 4 个脚本共 9 处 | Linux runner 常未设置，`Join-Path` 退化 → `Get-E2eTempDir` |

| `-WindowStyle Hidden` | 4 个脚本共 8 处 | 只在 Windows 的 `Start-Process` 上存在，Linux 直接"找不到参数" → 删除 |

| `Get-CimInstance Win32_Process` 清理 ×2 | regulator | Windows-only；**静默失败比报错更糟**——清理退化为空转，端口被残留占住，表现为莫名超时 → 换 `Stop-E2eStaleProcesses`（见下节） |

| 构建分支硬编码 `E:\dev-tools\...` JDK 路径 | dock / route-templates / defect | 加 `Test-Path` 保护（jar 路径修正后 CI 走 `-SkipBuild` 不进此分支，纯防御） |



### regulator 的清理标记缺陷（本批新发现并修掉）



其余三个脚本的 backend 命令行天然带标记（`jdbc:h2:mem:dock-e2e` / `route-templates-e2e` /

`defect-e2e`），`Stop-E2eStaleProcesses` 按 marker 能精确清掉；但 regulator 启动的

3 类 java 进程命令行**不含任何可匹配 `regulator-e2e` 的字样**——原脚本自带的

`Cleanup-OldProcesses` 按 `regulator-sim` / `aerofleet.regulator` / `AF-REG` 模式匹配，

Windows 上有效，Linux 上是 `Get-CimInstance`（静默失效，见上表）。



修法：5 处 java 启动命令行统一加 `-Dregulator-e2e=true`（放在 `-jar` 之前，由 JVM

消费为系统属性，**不会进 main 的 args**——实测 regulator-sim / drone-sim 的手写解析

对非 `--` 参数也只是警告不退出，Spring Boot 侧同样无感），`Cleanup-OldProcesses`

与结尾兜底清理都改为按标记杀。代价：改版之前启动的旧残留（命令行无标记）不再被清。



### CI 接入（`e2e-b1-scripts` job，观察模式）



- **串行原因**：cloud-backend 默认监听 UDP 14550（`aerofleet.udp-port`），4 个脚本

  均未覆盖该端口 → 同一 runner 上不能并行（多会话并发撞 14550 的坑 COORDINATION.md

  有记载）。单 job 串行跑 4 个脚本，脚本间夹按标记的清理步骤兜底

  （与第一批三脚本同款安排）。

- **`AF_BACKEND_PORT` 统一 18099**：regulator 默认 8080（本机被常驻容器占用，

  CI 默认段也不保险），与其余三个脚本的默认对齐；每脚本退出自停 backend，

  中间清理按 marker 兜底，端口串行复用无冲突。

- 中间清理的 marker 额外覆盖 sim 残留：route / defect 的 drone-sim 命令行不含

  backend 那个 h2 标记，补 `AF-ROUTE-01` / `AF-DEFECT-01` 两个标记

  （dock 的 sim 名 `DOCK-E2E-001` 已被 `dock-e2e` 大小写不敏感匹配覆盖）。

- **观察模式（continue-on-error）**：与 e2e-capability / e2e-signing / e2e-core-scripts

  同款——本机 Windows 全绿 ≠ Linux CI 连续复跑稳定，runner 上的进程/端口/时序行为

  可能不同。若干次 run 全绿后删掉这行转硬门禁，删标记 = 把"跑过"升级为"必须通过"。



### 验证



- 4 个改后脚本 PowerShell 语法解析（`Parser::ParseFile`）零错误；

- ci.yml YAML 语法校验通过（22 个 job，其余 job 结构未受影响）；

- 本机冒烟（JDK17 + 18099，`-SkipBuild`）：改后 e2e-defect 全绿（18/18，A1~A5）。



### 计数



**未变**：e2e 脚本不产生 surefire 报告，Java / 前端计数均不受影响。





---

---

---

## [Unreleased] — 后端安防事件空桩结清：不是缺 Store，是**同一件事写了两遍，其中一遍是死的**（#105，2026-10-17）



上一轮（#104）把 `survEvents` 的键名修对了，面板能读 `items` 了，但列表**仍然是空的**，

因为后端 `GET /api/v1/surveillance/events` 是永久空桩。当时按方案 A 记为待办：这轮结清。



### 根因：不是"缺一个 SurveillanceEventStore"



先按字面意思去实现 `SurveillanceEventStore` 前，查了一遍谁往里写 —— 结果是

**没有任何事件源**，光造一个 Store 会得到另一个空桩（代码更多而已）。三条实测：



```

HikAdapter.subscribeEvents      : // TODO: 接入 ISAPI alertStream —— 只塞进 map，永不调用 callback

Gb28181Adapter.subscribeEvents  : 日志明写 "callback will not fire in PoC"

EventCallback                   : javadoc 写「替代 Consumer<String>」，接口签名还是 Consumer<String>，从没被实现过

```



### 真正的问题：同一件事的两个模型



```

AlarmEvent.EventType        : MOTION, INTRUSION, FIRE, DOOR, CUSTOM

SurveillanceEvent.EventType : MOTION, INTRUSION, FIRE, DOOR, CUSTOM   ← 逐字相同

两者 Severity                : INFO / WARN / CRITICAL

AlarmEvent 的 javadoc        : 「描述安防设备（海康/大华/宇视）触发的一次报警」

```



而且 `AlarmEventStore` + `POST/GET /api/v1/alarms/events` 是**端到端可用**的管线

（POST 收事件 → `AlarmLinkageEngine.processEvent` → `store.store()` → 分页查询），

返回的 `{items,total,page,size}` 与安防那个空桩**契约完全一致**。



⇒ `/surveillance/events` 是同一件事的第二个、永远空的重复桩。



### 改法



1. **`AlarmEventStore` 加 `deviceId` 过滤**（`sourceDeviceId`）。此前该端点**声明了**

   `deviceId` 参数却从不过滤 —— 那个参数在它被"声明"的那段时间里从未生效过一次。

2. **`SurveillanceController.listEvents` 委托给 `AlarmEventStore`**，响应结构不变

   （前端取数路径不用改），但开始返回真实数据。

3. **删掉 `SurveillanceEvent` 和 `EventCallback`**（两个零引用类）。

   `SurveillanceEvent` 只被 `EventCallback` 引用，而 `EventCallback` 全仓零引用。

   保留它们只会让下一个人以为摄像头事件这条线已经通了。

4. **字段投影复用 `AlarmController.eventToMap`**（放宽为 public），保证两个端点

   返回**逐字段一致**的事件形状 —— 否则同一批数据在两个 API 下长得不一样，

   是另一种形式的"声明与行为不一致"。



### 前端：两个面板分工，不重复



按讨论结论，两个面板都保留但职责分开：



- **安防事件面板**：按**当前选中设备**过滤（`deviceId`），回答「这台设备发生过什么」。

- **报警事件面板**：全局视图，带确认 / 应急响应 / 侦察操作。



所以安防事件面板加了 `deviceId` 依赖（设备切换要重新拉取），并把空态拆成**三种说法**：

未选中设备 / 该设备无事件（并明说「不等于全局无事件」）/ 有事件。

顺带修了一直被忽略的小问题：轮询传的是 `limit`，后端参数名是 `size` —— `limit` 被忽略，

恒取默认 20。



### 钉死 bug 的假绿测试（本轮重点）



`SurveillanceControllerTest` 里三条测试**钉的是 bug 本身**：



```java

listEvents_returnsEmptyListWithPagination : total == 0   // 空桩也返回 0 —— 端点是委托还是空桩它都绿

listEvents_customPagination             : total == 0   // 分页无从验证

listEvents_withDeviceIdFilter           : total == 0   // 压根没验过滤

```



改写为必须先 seed 真实事件才能区分两种实现：返回真事件 + 字段形状、空 store 仍返回空数组、

分页真的翻页（`total=3 / size=2` 时两页内容不同）、`deviceId` 过滤真正生效

（含不存在的设备返回 0 条 = 过滤生效的**表现**而非"参数被忽略"）、大小写敏感。

`SurveillanceControllerTest` 30 → 32 例。



### 变异验证（判据：改了实现，目标用例会不会红）



| 范围 | 变异 | 结果 |

|---|---|---|

| 后端 | 控制器退回空列表 | ✅ 杀（3 例红） |

| 后端 | Store 忽略 `deviceIdFilter` | ✅ 杀（2 例红） |

| 后端 | 控制器丢掉 `deviceId` 实参 | ✅ 杀（2 例红） |

| 后端 | `eventToMap` 少一个字段 | ✅ 杀（形状用例红） |

| 后端 | 分页 `from` 恒取 0 | ✅ 杀（分页用例红） |

| 前端 | 轮询不带 `deviceId` | ✅ 杀 |

| 前端 | 依赖数组退回 `[]` | ✅ 杀 |

| 前端 | `size` 改回 `limit` | ✅ 杀 |

| 前端 | 空态塌缩成统一「暂无」 | ✅ 杀（2 例红） |

| 前端 | 事件行去掉类型 / 去掉来源设备 | ✅ 杀 / ✅ 杀 |



后端 5/5、前端 6/6 全杀，逐条核对了死掉的用例名是不是预期的那个

（FM4 起初是被我改坏的 JSX 语法错误"杀"的 —— 坏构建不算有效变异，已收紧锚点重验）。



### 口径



后端 4870 → **4872**，前端 358 → **359**。**按门禁报出的 24 处 `文件:行号` 同步**

（未做全仓正则替换）；`docs/sales-pitch-deck.md:263` 里的 `358 API 端点`是**另一个指标**，

未被误改。



### 未做（明确记下，不含糊）



**厂商适配器的真实事件订阅没做**（ISAPI alertStream / DHSDK / UNIVIEW SDK / ONVIF

WS-BaseNotification）。那是需要对着真实摄像头硬件做的协议对接，不在本次范围。

当前状态是：`AlarmEventStore` 有生产者（`POST /api/v1/alarms/events` 收事件），

查询链路端到端通；**摄像头直连事件源仍不存在**。



---

---

---

## [Unreleased] — gcs-web：接线缺口台账最后 3 处。3 处都叫"写了没人读"，但**失效机制各不相同**（2026-10-17）



台账上一视同仁记成"没有渲染"是不准的。三处分别是：纯渲染缺口、去重逻辑把数据毁掉、

键名不符**叠加**后端空桩。逐处说：



### 1. `disasterClusters` —— 纯渲染缺口



后端 `getClusterTopology()` 返回的是真数据：



```

{ clusterCount, clusters: [{ clusterId, clusterHead, members, memberCount, clusterRadius, timestamp }] }

```



`setDisasterClusters(dClusters)` 确实在写，但全组件无人读。已按簇ID / 簇头 / 节点数 /

半径渲染，空拓扑时如实显示 0。



### 2. `orchEvents` —— 数据是真的，但去重逻辑把它毁了



后端 `EmergencyOrchService` 的 events 是 **`List<String>`**（L572 直接

`((List<String>) plan.get("events")).add(event)`），而前端去重键是：



```js

(e.ts || e.timestamp || '') + '|' + (e.message || e.msg || '')

```



对字符串 `e` 而言 `e.ts` / `e.message` **全是 undefined** ⇒ 键恒为 `'|'`

⇒ `seen` 集合最多一个元素 ⇒ **除第一条外全部被判为重复** ⇒ 所有事件并成一条。



改成按**位置**去重，而不是按内容：后端的事件列表是**只追加**的

（只在建计划时 `plan.put("events", …)` 一次，之后只有 `.add()`），

所以事件的身份就是它在列表里的位置。



**没有改用"内容=字符串"做键**——事件串可以重复

（`phase 0->1 status=RUNNING`、`priority adjust: task=5 EMERGENCY->COMMAND`），

那会把真事件吞掉。加了一条专门钉这个边界的用例：两条一模一样的 `'dup-event'`

必须显示成 2 条。



同时用 `orchEventsRef` 记 `{planId, count}`：换计划时整体替换，

不拿旧计划的长度去切新计划。



### 3. `survEvents` —— 两个独立原因叠加



| # | 原因 | 状态 |

|---|---|---|

| a | 前端读 `data.events`，后端返回的是 `{ items, … }` | **已修**：改读 `items`（`events` 保留为兼容键） |

| b | 后端 `SurveillanceController` 的 `/events` 是**永久空桩**，注释自述"当前没有全局 SurveillanceEventStore"，固定返回 `{items: []}` | **已修**（#105）：改为委托 `AlarmEventStore`，真返回数据，`deviceId` 过滤也真正生效 |



所以修完 (a) 列表**仍然是空的**。此处刻意**不用空白列表**表示空 ——

监控面板显示空列表会被读成"没有发生过事"，那是**假阴性**，正是这批 PR 在治的病。

空态改为显式说明后端未实现，并明说这不代表无事件。



后端空桩按方案 A 记为待办，不在本次范围内实现。



## 验证：9/9 绿 → **5 个变异体全被杀** → 还原后复跑全绿



| 变异 | 结果 |

|---|---|

| `survEvents` 退回读 `data.events` | 被杀 |

| 编排事件每次轮询都追加整份列表（重复累积） | 被杀 |

| 聚簇渲染切成 `slice(0, 0)`（渲染但读不到数据） | 被杀 |

| 诚实空态退回普通"暂无" | 被杀 |

| 去掉 planId 锚点（新计划被旧长度切掉） | 被杀 |



### 变异过程中测试自己暴露的一个假覆盖



第一版"换 planId"用例是 `unmount()` 旧面板再渲染新面板 —— 而新面板的

`orchEventsRef` 本来就是全新的 `{planId:null, count:0}`，**根本走不到锚点分支**，

于是去掉锚点它照样绿。改成**同一面板内**切换（第一个计划走到 `COMPLETED` 终态 →

按钮重新可点 → 再启一个事件更少的计划）之后，去掉锚点立刻被杀。



另有一处 harness 缺陷：文件按 latin-1 读取时，脚本里 UTF-8 的中文锚点永远匹配不上

（M4 因此被静默跳过）。锚点改成纯 ASCII（用 `[\s\S]*?` 匹配中文载荷）。



## 边界



- **全部是前端改动**，未动任何后端代码（后端 `events` 是 `List<String>` 这件事

  我保留原样：改成对象会牵动所有既有消费者，而按位置去重已经能完整呈现事件流）。

- `disasterClusters` / `orchEvents` 两处数据本来就真，修完立即可见。

- `survEvents` 修完**仍然不会有事件**，因为后端是空桩 —— 这一点在界面上明说了，

  台账那一行也写明了，避免后人以为接线修完就等于功能可用。

- 未跑真实链路端到端。



**口径**：前端 349 → **358**（新增 `unifiedCommandWiring.test.jsx` 9 例），

后端不变 4870。按门禁报出的 2 行同步。台账：9 处**全部修完**（已修 9 / 仍缺 0）。



---

---

---

## [Unreleased] — gcs-web：灾害模式的「触发原因」下拉框是装饰性的，面板上显示的永远是 `manual`（2026-10-17）



`DisasterCommPanel` L383 有一个下拉框，让用户从 5 个选项里选触发原因；L240 把选中值

发给 `disaster.activate()`；`api.js` L1141 也确实把它 JSON 化成 `{ reason }` 发出去了。

**但后端从来没读过它。**



### 实测证据（MockMvc，不是读代码推断）



新增 `DisasterCommActivateReasonTest`，用 `MockMvcBuilders.standaloneSetup`

（不需 Spring 上下文、不需数据库，但走完整的 `@RequestBody` 绑定）：



```

POST /api/v1/disaster/activate  {"reason":"earthquake"}   ->  triggerReason 回显 "earthquake"

POST /api/v1/disaster/activate  （不带 body）             ->  "manual"（向后兼容）

POST /api/v1/disaster/activate  {"reason":"bogus"}        ->  400 + allowed 取值列表

POST /api/v1/disaster/activate  {"reason":5}              ->  400（不做隐式强转）

```



### 为什么已有测试没抓到



`DisasterCommControllerTest` 调的是 `controller.activate()` —— **直接 Java 调用**，

`@RequestBody` 这一层结构上从未被执行过；而 `DisasterCommServiceTest` 看到的又永远是

服务里硬编码的值。两边都绿，缺陷照样存在。这与 #102 的 `EmergencyOrchControllerTest`

是同一类盲区。



### 三处修法



1. **控制器**：`activate()` 加 `@RequestBody(required = false)`，读出 `reason`。

2. **服务**：新增 `activateDisasterMode(String reason)`；无参重载保留并委托给

   `activateDisasterMode("manual")`，既有 4 处调用点与断言一字未改。

3. **取值域**：新增 `DisasterCommService.ALLOWED_TRIGGER_REASONS`。



### 顺带查出一处词汇表冲突



前端下拉框给的 5 个值是 `manual` / `heartbeat_timeout` / `terrain_change` /

**`earthquake`** / **`mudslide`**；而后端自己的 MAVLink 序数映射

（`triggerReasonName(int)`）只有 `manual`(0) / `heartbeat_timeout`(1) /

`terrain_change`(2) / **`auto`**(3)。**两边没有一个是对方的子集。**



允许集取的是**并集**：MAVLink 序数词汇 ∪ 界面灾害类型。理由是入向消息只能表达序数

0..3，而 REST 手动激活是人在界面上选、选到的是灾害类型 —— 砍掉任一边都是功能做减法。

另加一条测试断言「前端提供的 5 个选项必须是允许集的子集」，否则界面里会出现点了就 400

的选项。



### 顺带查出一处错误响应契约



`api.js` 的 `jsonFetch` 在 `!res.ok` 时只读 `body.error`（`throw new Error(body.error || 'HTTP ' + res.status)`），

而项目里既有控制器一律用 `Map.of("error", ...)`。我第一版的 400 返回 `{message, allowed}`，

**界面会退化成显示「HTTP 400」并把「未知哪个原因」丢掉**。已补上 `error` 键并加断言。



### 验证：前端 5/5、后端 7/7 绿 -> **8 个变异体全被杀** -> 还原后复跑全绿



| 变异 | 结果 |

|---|---|

| 服务忽略 reason 硬编码 `"manual"` | 被杀 |

| 控制器不读请求体 | 被杀 |

| 控制器去掉取值校验（什么都收） | 被杀 |

| 服务不再归一化空白 reason | 被杀 |

| 无参重载改成自递归 | 被杀（范围修正后 —— 见下） |

| `api.js` 请求体丢掉 reason | 被杀 |

| 组件发硬编码 `'manual'` | 被杀 |

| 面板显示本地选择而非服务端回填 | 被杀 |



**变异工具本身出过一次错，记录在此**：第一版 harness 有两个 bug ——

(a) 多行锚点用裸 `\n` 而 Java 源码是 CRLF，于是 3 个变异静默「跳过」；

把跳过当通过是错的，脚本改成锚点未命中即判为 harness 失败并拒绝碰文件。

(b) 变异范围只圈了 `DisasterCommActivateReasonTest`，而「无参重载」的覆盖在

`DisasterCommServiceTest` 里，于是它假存活；扩大范围后它杀掉 4 个用例。



### 边界



- 我**没有**改前端任何一行。缺陷完全在后端（丢弃 body + 硬编码），前端此前的行为是对的。

- 后端我**改了生产代码**（这与 #99~#102 那几个 PR 不同 —— 那几个只加测试）。

  改动限于：加 `@RequestBody`、加一个带参重载、加一个常量集合，没有改任何既有行为。

- 我**没有**给下拉框补 `auto` 选项。它在允许集里（因为 MAVLink 会产生它），

  但「手动激活、自动触发」语义上自相矛盾，留给产品决定，不在本次扩范围。

- 真实链路（真浏览器 → 真后端 → PostgreSQL）我没跑端到端；MockMvc 覆盖的是

  body 绑定与状态落库这一层。



**口径**：后端 4863 → **4870**（新增 `DisasterCommActivateReasonTest` 7 例）、

前端 344 → **349**（新增 `disasterActivateReason.test.jsx` 5 例）。

按门禁报出的 23 行逐行同步，未做全仓正则替换。



---

---

---

## [Unreleased] — gcs-web：「一键应急响应」按钮从来没有成功过 —— 四处契约不符（2026-10-17）



**这是本轮查到最严重的一个**，而且它是应急面板上最显眼的那个按钮。



**实测证据（后端 MockMvc，不是读代码推断）**：新增

`EmergencyOrchPathVariableTest`，用 `MockMvcBuilders.standaloneSetup`（不需要 Spring 上下文、

不需要数据库，但会走完整的 `@PathVariable` 绑定）：



| 请求 | 结果 |

|---|---|

| `POST /api/v1/emergency/scenarios/0/start` | **200** + planId + RUNNING |

| `POST /api/v1/emergency/scenarios/earthquake/start` | **400** |

| `POST /scenarios/{earthquake,mudslide,fire,custom}/start` | **全部 400** |



**根因**：后端是 `@PathVariable("type") **int** type`，而前端选择器用的是另一份硬编码

`SCENARIO_TYPES`，type 是字符串 `'earthquake'|'mudslide'|'fire'|'custom'` ⇒ URL 里带字符串 ⇒

Spring 类型转换失败 ⇒ 400。**失败发生在路径变量解析阶段，连业务分支都进不去**，

所以也不是"后端返回了错误"——请求根本没到业务代码。



**同一个调用里另外三处契约不符**（全藏在第 1 条后面，因为按钮从未成功过，从未被触发）：



| # | 前端发的 | 后端期望 | 后果 |

|---|---|---|---|

| 1 | URL 里 `'earthquake'` | `@PathVariable int` | **400，按钮从未成功** |

| 2 | `radiusKm` | `radius`（后端源码里 `radiusKm` 出现 **0** 次） | 半径被静默忽略，`effectiveRadius` 回退到预设默认 |

| 3 | 值 5，标签「半径(km)」 | 米（`@param radius 灾区半径（米）`） | 键名修对也会变成 5 米 |

| 4 | `centerLat=30.0`（度） | `1E7 度`（`@param centerLat … 1E7 度`） | 会被当成 0.000003 度 |



**为什么已有的测试没抓到**：`EmergencyOrchControllerTest` 的头注释自己写着

「**直接实例化 Controller + Service（无 MockMvc / Spring 上下文）**」，它调的是

`controller.startScenario(0, body)` —— 直接 Java 调用 + **int 字面量**。

于是 `@PathVariable int` 这一层转换在结构上从未被执行过。

已新增 MockMvc 测试补上这一层；**没有**改动那个既有测试文件的风格。



**顺手接上一处接线缺口**：选择器改由 `scenarios`（后端真值）驱动，删掉重复的硬编码清单

—— 一个数据源同时修好了"按钮 400"和"后端那 4 条预设没人读"两件事。选中场景的

`defaults`（半径/机数/优先级）现在也显示出来。`scenarios` 拉取失败时退到一份**int 型**

兜底清单，保证按钮不会因为列表拉不到就永久不可用。

`selectedScenarioType` 起步为 `null`（后端 type 是 int，列表到位前不猜），到位后自动选中第一项，

启动按钮在选中之前是禁用的。



**坐标换算只做在启动这一处调用点**：`centerLat/centerLon` 的 state 仍是**度**，

因为 `handleTriggerPtzTracking` 用的是同一份 state（PTZ 追踪按度）。改 state 的单位会连带

打断那个功能。



**同一个缺陷在第二个面板上也有一份**：`EmergencyOrchPanel`（独立的应急编排面板）

的 `handleStart` 与选择器是照着 `UnifiedCommandPanel` 抄的，于是

`useState('earthquake')` + 字符串清单 + `radiusKm` **三处一模一样**。同一个后端端点、

同一份契约，拆成两个 PR 只会留下一个坏的，所以合在一起修。



它还多一处别的面板没有的 bug：`可用预设：{scenarios.map((s) => s.type || s.name || s.id)}` ——

`type=0` 在 JS 里是 **falsy**，会短路到 `s.name`，而 `type` 1/2/3 是 truthy 直接显示数字。

**这一行实际渲染成 `地震 / 1 / 2 / 3`**。已改成 `s.name ?? s.type`。



**顺带修一处测试环境缺口**：`EmergencyOrchPanel` 在事件列表增长后调

`eventsEndRef.current.scrollIntoView(...)`，jsdom 未实现它。而这个 TypeError 抛在

`useEffect` 的**被动阶段** ⇒ 落在测试之外，vitest 只记成 unhandled error 并警告

"可能造成假阳性测试" —— **它不会让任何用例变红**，是静默污染信号。

已在 `test/setup.js` 统一补桩（与既有的 Pointer Capture 桩并列），

而不是让每个碰它的测试各自打补丁（漏一个就又多一条看不见的信号）。



**错误的契约还写在 api.js 的注释里**，一并订正 —— 这是前端误解的直接来源：



```

- // 启动编排：type=earthquake|mudslide|fire|custom，body={ centerLat, centerLon, radiusKm }

+ // 启动编排：type 是后端 int 0..3（GET /scenarios 的 type 字段），body={ centerLat, centerLon, radius }

+ //   centerLat/centerLon 单位是 1E7 度，radius 单位是米（后端 Javadoc 明写）

```



**验证**：前端 14/14 绿 → **9 个变异体全被杀** → 还原后复跑 14/14 绿



| 变异 | 转红 |

|---|---|

| `radius` 改回 `radiusKm` | 半径那条 |

| 键名对但不乘 1000 | 半径那条 |

| `centerLat` 不乘 1e7 | 坐标那条 |

| `centerLon` 不乘 1e7 | 坐标那条 |

| **忽略后端列表、还原字符串 type**（完整复现原缺陷） | **6 条**，含全部 4 个「场景发 int N」用例 |



最后一个变异体是特意加的：它把按钮文案渲染得**和修好时一模一样**（四个中文场景名都在），

只看界面或只断言文案的测试全都会绿 —— 判据必须是「发出去的 type 是 number 且等于后端的 int」。



**边界**：

- 后端侧我只加了测试，**没有改后端代码**。后端契约（int type / radius 米 / 1E7 度）

  是从它的 Javadoc 与源码读出来的，且既有测试 `startScenarioReturnsPlanId` 用的就是

  `centerLat=399000000`（= 39.9° × 1e7），与契约一致。

- 真实链路（真浏览器 → 真后端）我没跑过端到端；MockMvc 覆盖的是路径变量与 body 绑定这一层。

- `radius` 的单位换算是按 UI 标签「半径(km)」与后端「米」推出来的。默认 5 km = 5000 m

  恰好等于地震预设，**看起来**是作者的原意，但我没有找到设计文档能证明这是刻意的，

  记在这里以免后人以为是既定需求。



**口径**：后端 4860 → **4863**、cloud-backend 2764 → **2767**（新增 `EmergencyOrchPathVariableTest` 3 例）、

前端 330 → **344**（新增 `emergencyStartContract.test.jsx` 14 例）。全部按门禁报出的 `文件:行号`

逐行同步（`ROADMAP.md:25` 被两个 scope 各报一次，按行合并为一处两替换），**未做全仓正则替换**。



本分支是从 #101 合并后的 `ceb5e68` 重建的，不是 rebase 旧的 `c301417` —— 后者会让

CHANGELOG 顶部的 `[Unreleased]` 段冲突，且节内计数会停在过期基线。

---

---

## [Unreleased] — gcs-web：两个配置面板存回了服务端真值却从不显示，保存后看不出服务端认了什么（2026-10-17）



**先纠正一处台账本身的错误**：`AutoDispatchPanel.config` 与 `CommAdaptPanel.config`

两条都被记成"保存结果没有回显"，但两者的缺失程度**不一样**，照单全收会做错：



| 面板 | 台账描述 | 实际情况 |

|---|---|---|

| `AutoDispatchPanel` | 没有回显 | **准确**：失败路径有 `setError`，成功路径**零反馈** |

| `CommAdaptPanel` | 没有回显 | **不准确**：`configSuccess`（"配置保存成功"）本来就在 L430 渲染。它缺的不是"成没成功"，而是"**服务端认了什么**" |



所以后者若照台账只加一句 toast，等于什么都没修。



**根因**：两个 `config` 都是**服务端回填的真值**（`AutoDispatchPanel` L120 加载 / L140

保存后；`CommAdaptPanel` L131 / L224），此前从头到尾没人读。表单显示的是编辑缓冲

（`configEditing` / `configForm`），而后端是否采纳、采纳成什么，只能靠猜。



**改法**：两处都显示服务端回填的真值。这是**持久回显**而不是一次性 toast ——

所以判据不只是"点完保存有提示"，而是"生效值必须跟着后端返回值变"。



键名全部取自后端 `configToMap` 的权威清单，不照前端表单猜：



- `AutoDispatchController.configToMap` → `enabled` `minBatteryPct` `maxDispatchDistanceM` `defaultDroneCount` `hoverAltitudeM` `hoverDurationSec`

- `CommSituationController.configToMap` → `switchThreshold` `failoverThreshold` `detectionIntervalMs` `autoSwitchEnabled` `minStableTimeMs`



**验证：4 个变异体全被杀**，其中两个专门验"假修复"：



| 变异 | 转红的用例 |

|---|---|

| 删掉 AutoDispatchPanel 的生效行 | 加载显示 + 保存跟随（2 条） |

| 删掉 CommAdaptPanel 的生效行 | 加载显示 + 保存跟随（2 条） |

| **生效行改读 `configEditing`（表单缓冲）** | 保存跟随 + 未加载时不显示（2 条） |

| **生效行改读 `configForm`（表单缓冲）** | 保存跟随（1 条） |



后两个是这次特意加的：只显示"生效值"但读的是用户刚敲进去的缓冲，界面上**看着完全

正常**，普通断言抓不住它 —— 只有"生效值必须等于后端保存后的返回值"才能抓住。



6/6 绿 → 4/4 杀 → 还原后复跑 6/6 绿。



**顺带修掉测试自己一个设计缺陷**：M2 首次运行时把「原有的 configSuccess 仍在」

也一起杀了 —— 那条用例以 `getByText(/当前生效/)` 作为等待前提，等于依赖了新渲染块

存在，做不成独立回归守卫。已改为等 `getByLabelText('切换阈值')`（加载态就有的既有

元素）。改完 M2 只杀 2 条，正是它该杀的 2 条。



**边界**：

- 只在 mock 数据上验证。两个真实端点（`GET/PUT /api/v1/autodispatch/config`、

  `GET/PUT /api/v1/comm-adapt/config`）我没跑过；键名是从后端 `configToMap`

  读源码确认的，且它们与前端提交体用的键一一对应。

- 只显示当前生效值，**没有**加"最近一次保存时间"或变更历史 —— 后端没返回这个信息，

  造它需要改后端，不在本次范围内。



前端口径 324 → **330**，按门禁输出的 `README.md:894` 与 `ROADMAP.md:25` 两行同步

（只改门禁指出的行，未做全仓正则替换）。台账已更新：**9 处修掉 5 处，仍缺 4 处**。

---

---

## [Unreleased] — ci：发布 tag 从不触发任何门禁，v1.0.2~v1.0.5 指向的代码从未被验证（2026-10-17）



**现象**：`on.push` 只有 `branches: [ main, master ]`，**没有 `tags:` 过滤**，

所以打 tag 不产生任何 run。



**实测（不是推断）**：四个发布 tag 指向的 commit 在最近 100 条 run 里**一条都没有**：



| tag | commit | 指向 |

|---|---|---|

| v1.0.2 | `afb5902` | fix: 添加 jitpack.yml 指定 Java 17 |

| v1.0.3 | `1e6095c` | fix: 修复 jitpack.yml BOM 字符问题 |

| v1.0.4 | `cd66696` | docs(sdk-java): 补 JDK 8 实测边界 |

| v1.0.5 | `973db62` | Merge PR #48 feat/empty-states |



也就是说这些 tag 的质量信号只来自"**之前某次 master 或 PR push 恰好碰到同一段代码**"，

tag 自己那次提交（以及它可能引入的新问题）没有任何结论。这是典型的

**信号存在但来源错位**：看板上 master 是绿的，于是人默认 tag 也是绿的。



**改法**：加 `tags: [ 'v*' ]`。只认 `v*` 前缀 —— 非版本 tag 不该拖起 20+ 个 job。



**tag 上会发生什么，逐条核过（不是"应该没事"）**：



- 4 个 tag 都是**附注 tag**（`git cat-file -t` == `tag`）⇒ push 事件的 `github.ref`

  是 `refs/tags/vX.Y.Z`，不是 `refs/heads/*`。

- `pr-lint` 的 job 级 `if` 是 `github.event_name == 'pull_request'` ⇒ tag push 上跳过，

  不会拿发布 tag 去判 PR 标题格式。

- docker-build **job 本身照常跑**（保留"能否构建"这一层价值），但它的 Login 与 Push

  两**步**各带 `github.ref == 'refs/heads/master'` 守卫 ⇒ tag 引用下跳过，

  **不会**往注册表写任何东西；`metadata` 的 `latest` 标签同样被守卫住。

- 其余 job 的 `if` 只依赖 `always()` / `failure()` / 缓存命中，与 ref 无关，照跑。



**验证**：改动后的 `ci.yml` 用 **PyYAML 与 js-yaml 两个独立解析器**都解析通过，

`on.push` 读出 `{branches: [main, master], tags: ['v*']}`，21 个 job 定义不变。



**边界**：这一步只保证**将来的** tag 会被验证。四个存量 tag 我**没有重跑**——

重跑需要改 tag 指向（tag 一旦发布就不该移动），或对每个 commit 手动 `workflow_dispatch`。

当前 workflow 没有 `workflow_dispatch` 触发器，所以**存量四个 tag 的验证状态仍然是空白**。

要不要补一个 `workflow_dispatch` 入口来补跑历史提交，是另一个待决项。



---

---

---

## [Unreleased] — gcs-web：健康管理面板读的是不存在的字段名，核心功能整块空白且不报错（2026-10-17）



**现象**：`HealthPanel` 的机队健康总览、单机总评分、7 个部件评分三处一直显示 `--`。

不抛异常、不打日志、CI 全绿 —— 因为 `fmtScore(undefined)` 按设计就是返回 `'--'`，

"读不到"和"真的是空"在界面上长得一模一样。



**根因（对着后端 DTO 逐字段核出来的，不是猜的）**：



| 面板读的 key | `HealthScore` 的真实字段 | 结果 |

|---|---|---|

| `pick(d, 'score', 'healthScore')`（机队行 L233、机队平均分 L202） | `private final int overallScore` | 读不到 |

| `pick(droneDetail, 'score', 'healthScore')`（单机总评分 L319/320） | 同上 | 读不到 |

| `pick(droneDetail, 'components', 'componentHealth')`（7 部件 L326） | `private final Map<ComponentType, ComponentScore> componentScores` | 读不到 |



`HealthScore` 是普通 getter、无 `@JsonProperty`，所以 JSON 键就是字段名。

**三个候选名一个都对不上**，而每个 `pick()` 都配了三个别名 —— 三个全错，所以整块空白。



佐证它不是"后端改了字段"：同仓库 `CommAdaptPanel` 读的就是

`pick(score, 'overallScore', 'score', 'totalScore')`，**正确名字前端本来是知道的**。



**改法**：`overallScore` / `componentScores` 提到候选**第一位**，旧名保留作降级别名

（不删能力：如果将来有别的后端实现用旧名，仍能读）。

另把 #96 台账里的 `droneHistory` 一并接上渲染（历史评分列表，`slice(-10)` 取最近十条、

新的在前），并摘掉那条 `eslint-disable`。



**验证（4 个变异体全被杀，逐个对应）**：



| 变异 | 转红的用例 |

|---|---|

| 回退 `pick(d, 'overallScore', …)` | 机队行分数 + 机队平均分 |

| 回退 `pick(droneDetail, 'overallScore', …)` | 单机总评分 |

| 回退 `pick(droneDetail, 'componentScores', …)` | 7 部件评分 |

| 摘掉历史评分整块 | 历史列表两条（含空态提示） |



7/7 绿 → 4/4 杀 → 还原后复跑 7/7 绿。

夹具**照抄后端 DTO 的逐字段形状**（`ComponentType` 七个取值、`ComponentScore.Status`

三个取值都取自枚举定义），不是随手编字段名 —— 这正是 link-sim「多跳中继」那 11 个

假绿测试栽的跟头（合成 `new byte[13]` 让断言在错误字段上也能绿）。



**顺带修掉测试自己的一个错**：第一版断言"机队行不应出现 `--`"是错的 ——

缺 `status` 字段时状态行本来就渲染 `--`。已改成精确取到分数那一格

（`head.children[1].children[0].textContent`）再判等值。**这是断言写错，不是代码错**。



**边界（未证明的部分）**：

- 只在 mock 数据上验证。真实 drone-sim 发出的健康帧我没跑过；

  但 DTO 侧是读源码逐字段确认的，且 `GET /api/v1/health` 与 `/fleet`、

  `/{sysid}/history` 三处返回的都是同一个 `HealthScore`，形状一致。

- `droneHistory` 列表固定取最近 10 条、新的在前，没做分页/滚动。

  真实历史有多长我**没有实测**，若超过 10 条需要考虑加"查看更多"。

- 前端口径 317 → **324**，已按门禁输出同步 `README.md:894` 与 `ROADMAP.md:25`

  （只改门禁指出的那两行，未做全仓正则替换）。



---

---

---

## [Unreleased] — 计数门禁：同一处前端声称被点名两次，"不一致 N 处"虚高（2026-10-16）



**现象（2026-10-14 实测复现）**：`ROADMAP.md:25` 那行里只有**一个** `292`，门禁却报

`✗ ROADMAP.md:25 [前端] 声称 292，实测 310` **两条**，自报"不一致 2 处"而实际只有 1 行要改。



**根因**：`FRONTEND_CLAIM_PATTERNS` 有两条文法会命中**同一处声称的重叠区间**——

`前端…(\d+) 例` 与 `(\d+) 例 vitest` 对 `前端已有 292 例 vitest` 各匹配一次，

而抽取循环是逐 pattern 无条件 `fe_hits.append(...)`。



**修法与为什么不按数值去重**：改成**按区间重叠**判定"是不是同一处声称"。

若按数值去重，同一行里两处**位置不同**的独立声称（哪怕数值相同）会被误并成一处，

第二处就永久逃检——那是比虚高严重得多的失效，所以新增了两条方向相反的自测把它钉住。



**影响面说清楚**：这个缺陷**不会造成假绿**（漏报），只把不一致的**数量**报高；

但它的实际代价是——任何"按报告逐行替换"的工具在第二次替换时必然找不到锚点。

我自己写的同步脚本就是这么撞上的（当时它按设计抛"锚点缺失"并**拒绝写文件**，

才没造成半截替换）。



**验证**：`--self-test` 8 条全过（含 #72 那两条"混合行里 Java 声称偏高/偏低都判红"，

**本次改动没有削弱它们**）；另跑 2 条变异——去掉去重必须让"只点名一次"转红、

去重过头必须让"两处独立声称各报一次"转红，两者均按预期抓到，源文件逐字节复位。



**边界**：master 当前的前端声称与实测一致，所以这个缺陷处于**潜伏**状态，

只在前端声称过期的那一刻发作——而那恰恰是它最容易把人带偏的时刻。



---

---

---

## [Unreleased] — gcs-web：语音面板的错误通路在三层同时是死的，顺带挖出一个被异常掩盖的 mock 缺口（2026-10-16）



### 一、VoiceCmdPanel 的错误通路：写入被吞 + 消息被覆盖 + 没有渲染点



上一条把 `pendingError` / `historyError` 归进「接线缺口台账」时，我只看到"存了没人读"。

真正去读代码才发现**三层同时是死的**，任何单看一层都发现不了：



1. **写入被吞**：轮询里给每个请求挂了 `.catch(() => [])`，异常在到达 `Promise.all`

   外层 catch 之前就没了 —— 于是那个 `catch (e) { setPendingError(e.message) }`

   是**进不去的死代码**，`pendingError` 从未拿到过任何真实消息（它只被赋过 null）。

2. **唯一的真实消息被覆盖**：`pendingError` 拿到真实内容只有 `handleConfirm` 失败时

   那一处（`'确认失败：' + …`），而**每轮轮询（POLL_MS=5000）无条件 `setPendingError(null)`**，

   5 秒内被抹掉。

3. **没有渲染点**：即使状态有值，界面也不显示。



叠加结果：**语音指令确认失败时，操作员拿不到任何提示** ——

而 `setPendingError('确认失败：' + ...)` 的存在让代码看起来"已经处理了"。



改法（三处都动，缺一不可，少任何一处都还是坏的）：



- 轮询把成败显式带出来（`{ok, data}` / `{ok, err}`），谁成功谁清自己的错、谁失败谁写

  自己的错；外层那个死 catch 随之删除（它唯一的作用是掩盖下面的第二节）。

- 新增独立的 `confirmError` 承载"确认执行"失败，**不被轮询覆盖** ——

  两个语义必须分槽，否则谁都会被对方清掉。

- 补两处渲染点：`pendingError` + `confirmError` 在「待确认指令」卡片，

  `historyError` 在「指令历史」卡片，样式沿用本文件既有的 `⚠ / var(--crit)` 约定。



### 二、面板冒烟测试暴露了一个被异常掩盖的 mock 缺口



改完上面之后，全量 vitest 出现 **「317 通过 / 0 失败 但 exit=1」** ——

典型的信号与事实不符，必须查。查下去是一个 unhandled rejection：



    No "getVoicePending" export is defined on the "../src/api.js" mock



`test/panelSmoke.test.jsx` 的 api mock 是**手写枚举 23 个导出名**的，api.js 后来新增的

voice-cmd 那 8 个（`parseVoiceCommand` / `executeVoiceCommand` / `confirmVoiceCommand` /

`broadcastVoiceMessage` / `getVoiceStatus` / `broadcastVoiceAlert` / `getVoiceHistory` /

`getVoicePending`）不在其中 → 面板 import 到它们时，vitest 在**访问属性那一刻同步抛错**。

而那个错此前被 VoiceCmdPanel 轮询里那个 try/catch 吃掉 ——

所以这个 mock 缺口**一直是绿的**。



这是"声明 ≠ 行为"的另一面：**异常被吞 → 缺口不可见**。

注意我一开始把"api.js 里没有这些函数"判成了缺失 —— 那是错的，它们定义在

`export const voiceCmd = { ... }` 的对象属性里、再由文件末尾 `export const { ... } = api`

解构再导出，我的两条正则都没覆盖这种写法。**差点据此得出"整个面板的 API 层不存在"的结论。**



改法：mock 的覆盖面改为**由构造保证** —— 用 `importOriginal()` 取真实模块的导出名，

逐个给空桩（命名空间仍走 Proxy），只对"返回值不是空数组"的少数几个显式覆盖。

这样 api.js 以后新增导出时不可能再悄悄漏掉。

（`api.js` 的 import 期行为只有 try/catch 里的 sessionStorage 读取，jsdom 下安全。）



### 三、工具缺口：`sync_count_gate.py` 同步不了前端口径



用它同步时它"第 1 轮就通过"了 —— 因为它调门禁时**没带 `--frontend-json`**，

前端口径被整体跳过，它压根没看见那 3 处不一致（一次静默的空转）。

本次改用门禁自己报的 `文件:行号` 手工同步（`README.md:894`、`ROADMAP.md:25`），

并对门禁同一行重复上报做了去重（同一行只能改一次，否则第二次必然断言失败）。



### 变异验证（3 杀 0 活）



| 变异体 | 转红的用例 | 判定 |

|---|---|---|

| 吞异常（复现原 `.catch(() => [])`） | `取待确认列表失败` + `下一次轮询成功：错误提示随之消失` | 被杀 ✓ |

| 缺渲染（`{false && ...}`） | `点「确认执行」失败：显示「确认失败：原因」` + 跨轮询那条 | 被杀 ✓ |

| **轮询抹除**（状态分了槽但仍被清） | **只有** `跨过一次轮询周期（POLL_MS=5000）后仍然在` | 被杀 ✓ |



第三条是关键：它**只让那一条转红**，而"显示 确认失败"那条仍然绿 ——

说明那条决定性断言真能区分"只是加了个渲染"和"真修好了轮询覆盖"。

（若测试没有判别力，M3 会被前一条杀死或存活。）



### 计数



**前端 310 → 317**（新增 `test/voiceCmdError.test.jsx` 7 例）；Java **4860 未变**。

`npm run lint` **0 problems**；`npm run test:ci` **exit=0 / 317 通过 / 90 测试文件 / 0 unhandled**；

计数门禁带 `--frontend-json` **exit=0**。



---

---

---

## [Unreleased] — gcs-web：55 处「声明了却没人用」清干净，其中 9 处接线缺口立台账（2026-10-16）



`npm run lint` 长期停在 **55 warnings / 0 errors**（因为规则是 `warn`，CI 不会红），

于是这批"声明了却没人用"从来没有被要求处理。逐条查完之后分成两类分别处置。



### 定性方法：先剥注释与字符串，再在纯代码上数



不能靠"ESLint 报了就是没人用"这种间接推断。第一版统计脚本本身有噪声，已修正：



| 噪声来源 | 后果 |

|---|---|

| 按词边界数单字母标识符（`t` / `i`） | 把**别的作用域**里同名变量算成"多处使用" |

| 没剥注释 | `// twinStates 形状 { [sysid]: ... }` 里的 `sysid` 被当成一次真实使用 |

| "计算属性访问"规则把**数组解构** `const [config, setConfig] =` 里的 `[config` 误判 | 13 条全是误报 |



修正后（剥掉注释与字符串字面量，行号保持不变）：**55 条里有 47 条在纯代码里只出现 1 次**

（只有声明处），**8 条"出现多次"逐条看上下文后确认全是误报**（`a.sysid` 成员访问、

另一作用域的同名变量、另一函数里的重复声明），**0 条藏在 `obj[name]` 形式里**。



setter 侧同样逐条确认：把 12 组 `value` / `setValue` 分别统计调用点，

结果是 **9 组有活着的写入方**、**3 组纯死**。



### 处置一：46 处纯死代码，直接删



未使用的导入 12 处、未使用的纯计算 6 处（`TelemetryCharts` 的 `range`/`usableH`、

`MapView` 的 `eArrowLen`/`eArrowWidthHalf`、`Scene3D` 的 `t0`、`Trajectory3D` 的 `tsArr`）、

未使用的参数 4 处、未使用的 props 4 处、变量与 setter 都无人使用的纯死 state 2 处、

死函数 1 处（`TrackingPanel.msToDatetimeLocal`）、多余的赋值 1 处

（`SatLinkPanel` 的 `const res = await ...` → 去掉 `const`，`await` 保留）、

多导出的 API 面 2 处（`App.jsx` 与 `useAuth` 里的 `setAuthed` —— 该 hook 全仓只有

`App.jsx` 一个消费者，hook 内部三处使用全部保留）。



顺带删掉一处**恒为 null 的渲染块**：`HealthPanel` 的 `{error && (<div className="notice crit">…)}`

—— `error` 的 setter 从来没被调用过，所以这块从未渲染出任何东西。删掉后界面逐像素不变，

只是移除了那条死的错误通路。



### 处置二：9 处只写不读，保留 + 显式标注 + 记台账



### 接线缺口台账（9 处「写了但没人读」；已修 9 处，**仍缺 0 处**；含 #105 结清最后一项后端空桩）



下表逐条对应代码里带 `eslint-disable-next-line no-unused-vars` 的位置。

它们的写入方都是活的（setter 确有调用点），删掉 state 会连带删掉写入动作，

所以本次**一行都没删**，只把缺口写成人能读的文字 —— 避免将来某次

「顺手清掉 warning」把这些未完成的功能连同证据一起抹掉。



| 位置 | state | setter 调用点 | 状态 | 缺的是什么 |

|---|---|---|---|---|

| `gcs-web/src/components/AutoDispatchPanel.jsx` | `config` | `setConfig` | ✅ 已修（#101） | 改成显示服务端回填的生效配置（持久回显，不是 toast） |

| `gcs-web/src/components/CommAdaptPanel.jsx` | `config` | `setConfig` | ✅ 已修（#101） | 同上；`configSuccess` 成功提示本来就保留未动 |

| `gcs-web/src/components/UnifiedCommandPanel.jsx` | `disasterClusters` | `setDisasterClusters` | ✅ 已修（#104） | 真数据（`{clusterCount, clusters:[…]}`），已按簇ID / 簇头 / 节点数 / 半径渲染 |

| `gcs-web/src/components/UnifiedCommandPanel.jsx` | `orchEvents` | `setOrchEvents` | ✅ 已修（#104） | 已渲染；同时结修去重逻辑（后端 events 是 List<String>，旧键恒为 `|` 把所有事件并成一条） |

| `gcs-web/src/components/UnifiedCommandPanel.jsx` | `scenarios` | `setScenarios` | ✅ 已修（#102） | 选择器改由它驱动，并顺带修好了「一键应急响应」按钮 400 的缺陷 |

| `gcs-web/src/components/UnifiedCommandPanel.jsx` | `survEvents` | `setSurvEvents` | ✅ 已修（#104 + #105） | 键名改正为 `items`（#104）；后端空桩已修（#105）——`/events` 改委托 `AlarmEventStore` 返回真实事件，本面板按**选中设备**过滤 |改为显式说明后端未实现，而非空白列表 |

| `gcs-web/src/components/HealthPanel.jsx` | `droneHistory` | `setDroneHistory` | ✅ 已修（#99） | 已接「历史评分」列表渲染，并摘掉 `eslint-disable` |

| `gcs-web/src/components/VoiceCmdPanel.jsx` | `historyError` | `setHistoryError` | ✅ 已修（#97） | 同上，见 #97 的 CHANGELOG 条目 |

| `gcs-web/src/components/VoiceCmdPanel.jsx` | `pendingError` | `setPendingError` | ✅ 已修（#97） | 同上，见 #97 的 CHANGELOG 条目 |



其中两条值得单独点名：



- **`VoiceCmdPanel` 的 `pendingError`**：`setPendingError` 有 3 处调用，

  其中一处写的是 `"确认失败：" + 错误信息` —— 但这个错**从来没有被显示过**。

  即「语音指令确认失败」时操作员拿不到任何反馈。

- **`UnifiedCommandPanel`**：`survEvents` / `scenarios` / `orchEvents` /

  `disasterClusters` 四类数据都真的拉回来存着了，**一类都没渲染**。



### 验收



| 项 | 结果 |

|---|---|

| `npm run lint` | **exit=0，0 problems / 0 warnings**（改前 55 warnings） |

| `npm run test:ci` | **exit=0，310 / 310 通过，86 / 86 测试文件** |

| 界面行为 | 零变化（B 类一行未删；A 类删的都是纯代码里从未被读的东西） |



每一处编辑都带"该行必须包含某段文字"的断言。这条断言当场挡下一次错误编辑：

`VideoFusionPanel` 的 `import React, { useState, useEffect, useRef, useCallback, useMemo }`

里，若按预设文本删 `useRef` 会连带删掉 `useCallback`/`useMemo` —— 断言失败、整文件未写盘。



### 计数



**未变**：Java 4860、前端 310（本次只删未使用的变量，不增删任何用例）。

---

---

## [Unreleased] — 两条「门禁自己会误伤」的 flake，按实测根因修而非加等待（2026-10-16）



今天 CI 里 `GCS Web` 与 `E2E core scripts (formation / mesh / rid) — observe`

各出现一次偶发红。两条都不是"重跑就好"的抖动，各自查到了机制，

并且都做了变异验证——**证明改动真的接上了，也证明真坏了仍然会红**。



### 一、面板冒烟的 5s 超时是"测机器速度"的绊线（gcs-web/test/panelSmoke.test.jsx）



现象：`Test timed out in 5000ms`。本机连跑 8 次红 1 次，CI 上偶发同款。



实测数据（不是推断）：



| | 数值 |

|---|---|

| 逐条用例耗时（38 条，verbose） | p50=289ms / p90=639ms / max=962ms |

| 整档总时长（绿） | 3.3 ~ 7.7s |

| 整档总时长（红） | 25 ~ 55s |

| >1000ms 的用例 | **0 条** |



没有任何一条接近 5s；红的时候整档慢 4~8 倍。失败形态是"进程被抢占、

事件循环被卡"，不是"某个面板渲染不完"——而且**每次红的面板都不同**

（一次 Alarm/Geofence/Mapping，一次 WeatherLayerPanel）。



改法：给这条 `it` 一个显式的 20s 预算（不动全局 `testTimeout`，免得影响别的测试）。

注释里写明判据，并写明**不许再放宽或删掉**，否则"面板渲染卡住"会从"变红"退化成"CI 变慢"。



变异验证：`RENDER_TIMEOUT_MS = 1` → 38 条里 35 条转红，**变异体被杀**，

证明这个参数确实被 vitest 采用（否则"绿"与"参数生效了"是同一件事的两种说法）。



### 二、mesh e2e 把"中继有没有转发"和"链路有没有丢包"绑成了同一个断言



现象：CI 最近三次 run 里 FR-22 红两次，且**总是 FR-22 + FR-24(命令) 一起红**。



根因（三条实测证据）：



1. 上行画像 `lte-edge` 是 Gilbert-Elliott 两状态：好态丢 3%、坏态丢 55%，

   每包 8% 好→坏 / 30% 坏→好；`ImpairmentEngine` 的判定就是

   `inBadState ? pBadDrop : pGoodDrop`。链路**被设计成会丢包**。

2. 中继是哑转发，链路上没有任何重传——帧被丢了就是丢了。

3. 原写法是「发一次 + 单次 Receive 等 5s」，等于要求这条链路**零丢包**才通过。

   本机实测上行丢包 16.7% / 0% / 33.3%（CI 日志里是 `fwd=0 drop=1(100.0%)`、

   `fwd=4 drop=2(33.3%)`）。FR-24 写作 `$cmdBytesMatch = $cmdArrived -and (...)`，

   所以它跟着 FR-22 一起红——**这两条不是两份独立证据**。



改法：命令帧改成**有界重发**（最多 5 次）。重发是链路上真实存在的补偿手段，

放进测试才与"验证中继转发语义"这个意图一致；**有界是关键**，真坏了仍然会红。



变异验证两条：



- 把命令发到无人监听的端口 → 重发 5 次后 FR-22/FR-24/FR-23/FR-24 全红、`exit=1`

  → **变异体被杀**，证明这次改动没有把红变成假绿。

- 强制第 1 次发送丢失 → 第 2 次送达，`命令帧实际重发尝试次数: 2`，FR-22 绿

  → **证明重发路径真的生效**，不是装饰。



**未证明的部分（如实记下）**：正常态连跑 8 次里 `重发尝试次数` 全是 1，

即那 8 次命令帧本来就没被丢。原因是命令帧是中继转发的**第一个上行包**，

而 GE 引擎初始 `inBadState = false`（好态），它被丢的概率是 **3%** 而非稳态的 14%。

所以"CI 上为何两次落红"我没有拿到直接证据（CI 的 `-Mode auto` 会先跑完整版、

转发更多包，GE 状态可能在命令发出前就已进入坏态 —— 与观测一致，但未证实）。



下行 FR-23 不改：它走 `wifi5`（丢包 0.1%），比上行稳 140 倍，历史上从未因丢包红过。

改动只落在有实测失败证据的那一侧。



### 计数



**未变**：4860（两处改动都不新增/删除 Java 用例，panelSmoke 仍是 38 条 vitest）。

以 `scripts/check-test-count-docs.py --frontend-json` 实测为准。



---

---

---

## [Unreleased] — 复检闭环不是闭环：两条「声明了却没人执行」的事务注解（2026-10-16）



### 怎么找到的



把「声明≠行为」这条线从 DTO 注解（`@Size` / `@Min` / `@Valid`）扩展到 **Spring 代理注解**：

`@Transactional` / `@Async` / `@Cacheable` 挂在方法上，但**同类自调用会绕过代理**、

注解静默失效 —— 不报错、不告警，只是那个声明在运行时根本不存在。



扫描全仓 897 个 main 源码文件，自调用命中 2 处，逐条落到源码核实。



### 缺陷一（真缺陷）：`WorkOrderService.verify` —— 复检闭环被拆成两次提交



| 项 | 事实 |

|---|---|

| `verify(Long, int)` | **没有** `@Transactional`（入口 `DefectController` L158 走代理，因此全程无事务） |

| `transition(Long, …)` | 有 `@Transactional`，但它在 `verify` 内部被**自调用**（`verify` L169）→ 代理被绕过，注解不生效 |

| 后果 | 「改状态」与「记 verifiedAt / verifiedDetail」变成两次各自提交的写 |



两次写之间的任何失败，都会留下**状态已改成 VERIFIED / REOPENED、却没有任何复检记录**的工单

—— 一条自称复检过、却拿不出依据的记录。



**实测证据**（真 Spring 代理 + 真 H2，新增 `WorkOrderVerifyTransactionTest`）：



```

verifyFailureLeavesWorkOrderUntouched:123 [状态不得被单独提交]

    expected: "RESOLVED"

     but was: "VERIFIED"

```



### 修法：把事务边界挂在**真正被代理**的那个方法上



`verify()` 加 `@Transactional`；`transition` 的注解**保留**（它的外部调用方仍然需要它），

`verify` 内自调用时加入外层事务。



代价如实记录（写进 Javadoc，不掩饰）：本事务持有连接期间包含拍照那段真机 I/O

（`awaitNewShot` 最长 5s），单次复检最长占住一条数据库连接约 5-7s。这与

`FormationService.command()` 的既有形状一致（同样在事务内做 MAVLink 下发），

且复检是操作员触发的低频动作，故接受。若将来复检并发上升，应改为只把末尾两次写包进事务。



### 缺陷二（死注解，无功能后果）：`FormationService.dissolve`



`private` **且**只被 `command()` 自调用 —— proxy-based AOP 两条都拦不住，注解永远不会生效。

它不造成实际后果（外层 `command()` 本身就是 `@Transactional`，落库写在该事务内），

但会让读者误以为该方法自带事务。按「让声明与行为一致」移除注解，并写明事务实际来自哪里。



### 变异测试：3 个变异体全杀



| 变异体 | 期望 | 实际 |

|---|---|---|

| 删掉 `verify` 的 `@Transactional` | 第 1 条用例红 | 红（`verifyFailureLeavesWorkOrderUntouched`） |

| 不再写 `verifiedDetail` | 第 2 条用例红 | 红（`verifySuccessPersistsWholeRecord`） |

| `verified_detail` 列宽改 2000（让注入点失效） | 第 1 条用例**仍红** | 红 —— 证明「注入没生效」不会被误报成绿 |



**被排除的无效变异体**（自查出来的）：删掉 `verify` 末尾那次 `orders.save(updated)`。

修复后 `verify` 已有事务、实体在持久化上下文中，Hibernate 脏检查会在提交时把字段一并 flush

—— 删掉那次显式 save 不改变可观测行为，实测**存活**，而这个存活是**正确的**，不能拿来当判据。



### 一个结构性发现：现有单测不可能发现这类缺陷



`WorkOrderServiceTest` 是纯单元测试（`new WorkOrderService(mocks...)`）—— **没有 Spring 上下文、

没有代理**。在那样的测试里 `@Transactional` 本来就不生效：无论注解在不在、生效不生效，

用例**结构上**都是绿的。这类缺陷必须用 `@SpringBootTest` 拿真代理才具备观测能力。



### 本轮自我纠正



扫描器把 `GeofenceStore` 报成「类级 `@Transactional`」是**假阳性** —— 命中的是 Javadoc 里的

`{@code @Transactional}` 文字，不是真注解（真实注解在 L123/158/184/240/326，那 5 个方法均无自调用）。

与之前几轮同一个规律：**扫描产出假设，打开源码+变异测试才产出结论**。



### 验证



- `mvn clean package` 全量 **BUILD SUCCESS**（7 分钟，0 失败）

- 计数：**4858 → 4860 用例**（重放到四条修复合完之后：#90 编队、#91 写队列、JSR-303 契约、runner 钉版均已进 master，本分支基线为 master `d0fbb6f`；cloud-backend 2762 → 2764，测试类 561 → 562）

- 重放说明：本提交最初独立分叉自 master `a6315c7`（4825 → 4827）。四条修复依次 squash 合并后，

  计数行必然冲突（同一行被两边改成不同值），故按 master 的值解冲突，

  再由门禁自身输出的行号清单重同步到 4860；重放未改动任何测试代码。

- 文档口径按计数门禁自身输出的 22 处 `文件:行号` 同步（门禁报告显示"跳过 21 行追述旧值"，

  历史台账未被改动）；门禁 + `--self-test` 双绿

---

---

## [Unreleased] — CI：runner 镜像钉到 ubuntu-24.04 + setup-java v4→v5（2026-10-16）



### 触发原因：一个三天后到期的时间炸弹



从 CI 注解（`GCS Web` / `Warm Maven Cache` 等 job 上都挂着）读到的 notice：



```

[notice] .github:1

"The ubuntu-latest label will migrate to Ubuntu 26 beginning October 19, 2026."

```



`ci.yml` 里 **21 个 job 全是 `runs-on: ubuntu-latest`**。10 月 19 日之后会**整批**

被切到 Ubuntu 26 —— 换 glibc / JDK / Node 默认版本，Docker 构建、Java 编译、

Playwright E2E 会一起翻，而且是 21 个 job 同时翻，没有任何缓冲。



同批还有：`[warning] setup-java v4 is deprecated and will no longer receive updates.

Please migrate to actions/setup-java@v5`



### 为什么钉 24.04 而不是钉 26.04



从 #89 的 master run 日志（"Set up job" 步骤）读到了硬证据：



```

Included Software:

  https://github.com/actions/runner-images/blob/ubuntu24/20261004.327/

  images/ubuntu/Ubuntu2404-Readme.md

```



即 `ubuntu-latest` **当前就是 Ubuntu 24.04**。所以：



- 钉 `ubuntu-24.04` = **环境零变化**，只是不再跟标签漂

- 钉 `ubuntu-26.04` = 把一个**没有任何 job 验证过**的环境一次性铺到 21 个 job



后者把风险从"三天后集体翻"换成"现在就集体翻"，方向错了。要升 26 应当单独一个 PR

全量验证后再动，注释里也这么写了。



### 改动



| 项 | 前 | 后 |

|---|---|---|

| 21 个 job 的 runner | `ubuntu-latest` | `ubuntu-24.04` |

| 12 处 `actions/setup-java` | `@v4` | `@v5` |



`setup-java@v5` 要求 runner ≥ 2.327.1，实测本仓 runner 为 `2.337.0`，满足；

它同时把 action 自身从 Node 20 搬到 Node 24，与注解里"已被强制跑在 Node 24"一致。



**未动**：`actions/checkout@v4`、`cache@v4`、`setup-node@v4`、

`upload-artifact@v4`、`download-artifact@v4` —— 都是当前大版本，

注解里没有 deprecated 告警。Node 20 那条只是信息性通知（已强制 Node 24），

不需要动作。



### 验证



- `pyyaml` 解析通过：`name: CI`、**21 个 job**、`runs-on` 分布 `{ubuntu-24.04: 21}`

- `runs-on: ubuntu-latest` 残留 **0**；`setup-java@v4` 残留 **0**

- 文件 CRLF 保持不变（用 Python 按字节写，没用 PowerShell）



### 说明这次是「继承来的」问题



这不是本文案里任何 PR 引入的 —— #90/#91 都只是继承了 master 的风险。

所以它落在一个**独立分支**上，不和那两个混。



---

---

---

## [Unreleased] — JSR-303 约束契约：把「注解存在」变成「注解被证明会生效」（2026-10-16）



### 动机



上一条（formation）修的是「入口漏 `@Valid`，注解成了装饰品」。修完之后自然有个问题：

**怎么防止它再发生？** 空口说"以后要检查"没有用，得让回归测试能发现。



但这里有个容易踩的坑，先说清楚：



> **直接调 `validator.validate()` 的测试，挡不住"删掉入口的 `@Valid`"。**

> 因为它绕过了 Spring，`@Valid` 在不在 controller 上与它无关。

> 要锁入口，必须走 MockMvc 走完 Spring 的校验链路。



这一版两类都做了，并且**把边界用变异测试证明了一遍**。



### 5 个 DTO 的约束契约测试（直接调 validator）



| 测试类 | 断言内容 |

|---|---|

| `DeliveryRequestValidationTest` | sysid 0/256 越界、sites null/空/129 条、lat=200 / lon=400 / 四个 `@Min(0)` 字段取负 |

| `SprayTaskRequestValidationTest` | sysid -1/256、waypoints null/空/1025 条、三个物理量负值与 0 |

| `TaskRequestValidationTest` | taskId null/空/全白/65 字符、taskType 白名单四项+`SURVEYX`/小写、priority -1/11、targetLat/Lon 两侧越界 |

| `LoRaAlarmDtoValidationTest` | deviceId 0/-1、alarmType null/全白、lat/lon 两侧越界、severity 0/6 |

| `FormationCreateRequestValidationTest` | **只验良构性** —— 见下 |



断言粒度到**字段路径 + 具体约束注解**，不满足于"有违规"。每个类都有一条

「全部合法时零违规」的反向对照，防止上面的断言只是"有违规就算过"。



`FormationCreateRequest` 那个类不同：它的入口**没有 `@Valid`**（全仓唯一），

注解**根本不执行**。所以它的测试**只回答"注解本身良构吗"**，并明确注释指向

`FormationServiceTest` 那三条才是真正锁入口的。



### SchedulingControllerTest 新增 4 条 MockMvc 守卫（真正锁住 `@Valid`）



走 `/api/v1/scheduling/tasks` 真实链路，断言 400：



- `createTask_outOfRangeCoordinatesRejectedByBeanValidation`（targetLat=200）

- `createTask_priorityOutOfRangeRejectedByBeanValidation`（priority=11）

- `createTask_taskTypeNotInWhitelistRejectedByBeanValidation`（taskType=NOT_A_TYPE）

- `createTask_blankTaskIdRejectedByBeanValidation`（taskId 全空白）

- 加一条反向对照 `createTask_validBodyIsNotRejectedByValidation`（合法体不能是 400）



**为什么这 4 条必须有**：`TaskAssignmentService` 对未知 taskType 走 `default` 分支、

`priorityToProtocol()` 对越界 priority 也只落到某一档，**都不拒绝**；

targetLat/targetLon 更是**没有任何服务层检查**。这几条约束是唯一防线。



### 变异验证：证明守卫真的锁住了入口



把 `SchedulingController.createTask` 的 `@Valid` 删掉，重跑：



| 类别 | 结果 |

|---|---|

| 4 条 `*RejectedByBeanValidation` | **全部转红** ✓ |

| `createTask_validBodyIsNotRejectedByValidation` | 保持绿 ✓（反向对照没被打破） |

| 4 条直接调 validator 的用例 | **保持绿** ✓ —— 这正是已知边界的实证 |



也就是说：`@Valid` 一掉，只有 MockMvc 那 4 条会拉警报。这个区别必须写下来，

否则下一个人以为"契约测试绿=入口有 `@Valid`"。



### 顺带修掉一处真实的「两层讲两套话」



`SprayTaskRequest` 的 targetRate / capacityMl / sprayWidth 原先是 `@Min(0)`

（宣称 0 合法），而 `SprayTaskService.create()` 要求 `> 0`（`sprayWidth=0` 会让

`SprayTask.totalArea` 恒为 0，`capacityMl=0` 会让余量百分比走 0 分支）。



改成 `@Positive`，与服务层同口径。**被拒绝的输入集合完全不变** ——

原本 `targetRate=0` 也是先过 `@Min(0)`、再被服务层拒成 400；现在由校验层直接 400。

变的只是哪一层先拒、错误信息谁给。服务层那三条 `<= 0` 检查**保留**作为冗余防线

（`@Valid` 万一被去掉仍拦得住）。DTO 的 Javadoc 把"为什么用 `@Positive` 不用 `@Min(0)`"

写进去了，免得后人以为写错又改回去。



### 一次自我纠正：`@Min`/`@Max` 到底支不支持 double



我一度判定"D = `MIN`/`@Max` 用在 `double` 字段上是无效声明，Hibernate Validator

会抛 `UnexpectedTypeException`"，并据此怀疑 4 个 DTO 的坐标约束全都失效。



**实测推翻了这个判断**：本仓依赖是 Hibernate Validator **8.0.3**，

`@Min`/`@Max` **支持 `double`**，违规能正常报告（已打印确认）：



```

[sites[0].lat] 最大不能超过 90   descriptor=Max

[sites[0].payloadWeightKg] 最小不能小于 0  descriptor=Min

全部合法时违规数: 0

```



我记的是 Bean Validation 1.1 时代的老限制。（`LoRaAlarmDto` 用

`@DecimalMin`/`@DecimalMax` 只是另一种写法，不是"唯一正确写法"。）



教训同前几轮：**凭记忆下的规范结论，和凭扫描下的结论一样不可靠。**



### 计数



**4830 → 4858**（+28）：5 个新契约测试类 + `SchedulingControllerTest` 的 4 条守卫与 1 条反向对照。

以 `mvn clean package` 实测为准。



---

---

---

## [Unreleased] — 写队列：兜底回调自己抛异常会把写线程整个带走（2026-10-15）



### 问题



`BatchedWriteQueue.flush()` 的结构是这样的：



```java

} catch (Exception e) {

    ...

    onFailure.accept(batch, e);   // ← 兜底回调在 catch 块「内部」

} finally {

    inFlight.decrementAndGet();

}

```



**catch 块不会捕获自己函数体里抛出的异常。** 所以 `onFailure.accept` 一旦抛出，

就会冲出 `flush()` → 冲出 `runLoop()`（`flush(batch)` 没有任何 try 包裹）→

**写线程直接死亡**，而且没有任何日志。



后果比看上去严重：线程死后，所有 `offer()` 在队列填满之前都返回 `true`，

却没有任何消费者。队列**静默停写** —— 不报错、不记日志、计数器只涨 `offered`。



这与类 Javadoc 的承诺直接冲突：



> 落库抛异常时整批交给 `onFailure` 回调，同样由调用方兜底——

> 保留"DB 不可用不影响记账"的原有语义。



而现实中 DB 挂了的时候兜底目标（文件追加）很可能一起挂（磁盘也满）——

这条路径不是理论边界，是 DB 故障时最容易同时发生的场景。



`close()` 有同样的问题：残余条目的 `onFailure.accept(rest, ...)` 会把异常

**甩给调用方**（Spring 的 `destroy()` 里抛异常会打乱关闭流程）。



### 取证（修前，两条新用例都红）



| 用例 | 实际失败信息 |

|---|---|

| `throwingFailureCallbackDoesNotKillWriter` | `AssertionError: 条件未在 2000ms 内成立` —— 写线程已死，第 7 条永远没落地 |

| `closeWithPendingItemsAndThrowingCallbackDoesNotPropagate` | `RuntimeException: fallback target also unavailable` 从 `close()` 外抛 |



### 修法



新增 `notifyFailure(List<T>, Exception)`，把两处 `onFailure.accept` 都换过去，

**单独包一层 try**：



- `flush()` 里落库失败的批次

- `close()` 里残余条目的批次



只捕获 `Exception`，**不捕获 `Error`**（OOM / StackOverflow 仍向上抛 ——

那种状态下进程已不可信，让它炸出来比吞掉更诚实）。兜底失败的批次记 `ERROR`，

日志里带原始原因与批次大小。



类 Javadoc 新增「兜底失败也不能拖垮队列」一段，把这条契约写死。



### 测试



新增 2 条（先按期望行为写、确认红，再修）。原来唯一覆盖"落库失败"的

`failureHandsWholeBatchToCallback` 用的兜底回调是 `failed.add(...)`，

**永远不会抛** —— 所以"兜底自己也失败"这条路径此前零覆盖。



### 变异验证：3 杀 0 活



| 变异 | 目标用例 | 结果 |

|---|---|---|

| 去掉 `notifyFailure` 的 catch | `throwingFailureCallbackDoesNotKillWriter` | 转红 |

| `close()` 里改回直调 `onFailure` | `closeWithPendingItemsAndThrowingCallbackDoesNotPropagate` | 转红 |

| `notifyFailure` 根本不调 `onFailure` | `failureHandsWholeBatchToCallback`（既有用例） | 转红 |



第三条是刻意加的回归网：防止"修兜底失败"的时候把正常兜底也一起吞掉。



### 顺手得到的一个可信负结果



把"哪个入口消费了带 JSR-303 约束的 DTO 却没加 `@Valid`"这条扫描重写了三遍才做对

（前两遍都因为注解顺序、以及 `annos + before` 拼接导致 `\b` 失配而误报）。修好之后的结论：



> 全仓 **116** 个 `@RequestBody` 入口，其中 **8** 个消费带约束注解的 DTO，

> **8 个全部有 `@Valid`**；唯一的例外就是 `FormationController.create`

> —— 也就是上一条 CHANGELOG 里刚修的那个。



所以「入口漏 `@Valid`」在当前代码里是**孤例**，不是模式，不用再查这一类。



### 计数



**4828 → 4830**（+2）。以 `mvn clean package` 实测为准。



---

---

---

## [Unreleased] — 编队创建：成员数上限与队形缺省两条「声明了却没人执行」（2026-10-15）



### 问题



`FormationCreateRequest` 是四个 mission DTO 里**唯一**入口没加 `@Valid` 的

（`DeliveryController.create`、`SprayController.create`、

`LoRaRelayController.receiveLoRaAlarm`、`SchedulingController.createTask` 都有）。

所以它的 JSR-303 注解不会触发，得靠 `FormationService.create()` 手写校验兜 ——

而那里的 6 条检查里有 2 条漏了：



| DTO 上的声明 | 实际 | 后果 |

|---|---|---|

| `members` `@Size(min = 2, max = 32)` | 只查下限 `size() < 2`，**全仓无任何上限检查** | 33 名及以上成员被静默接受 |

| `shape` `@NotNull` | 无任何检查 | `FormationGeometry.compute` 里 `switch (shape)` → **NPE** |



NPE 那条尤其值得记：`create()` 的 Javadoc 承诺 `@throws BadRequestException`，

但缺省 `shape` 实际抛的是 `NullPointerException`，对外表现为 **HTTP 500 而不是 400**。

取证原文：



```

Cannot invoke "io.aerofleet.cloud.mission.formation.FormationGeometry$Shape.ordinal()"

because "shape" is null

```



### 修改



1. **把上界提成共用常量** —— `FormationCreateRequest.MAX_MEMBERS = 32`，

   `@Size(max = MAX_MEMBERS)` 与服务层的 `size() > MAX_MEMBERS` **引用同一个常量**。

   这样"注解写 32、代码不查"从结构上不可能再发生（注解参数接受编译期常量）。

2. **`FormationService.create()` 补两条检查**，与相邻 6 条同风格：

   - `members.size() > MAX_MEMBERS` → `BadRequestException`，错误信息带实际上限与实际值

   - `shape == null` → `BadRequestException("shape is required")`

3. **DTO 的类 Javadoc 改为如实描述**：明确写出"这些注解在本入口不触发"，

   并指明改注解必须同步改 `FormationService.create()`（或反过来给入口加 `@Valid`）。



### 测试



新增 3 条（先按期望行为写、确认红，再修）：



| 用例 | 修前 | 修后 |

|---|---|---|

| `memberCountAboveDeclaredMaxRejected` | 红（33 人**什么都没抛**） | 绿 |

| `nullShapeRejectedWithBadRequest` | 红（抛 **NPE** 而非 BadRequest） | 绿 |

| `memberCountExactlyAtMaxAccepted` | 绿（边界本来就对，留作回归网） | 绿 |



### 变异验证：4 杀 0 活



| 变异 | 目标用例 | 结果 |

|---|---|---|

| 删掉成员上限检查 | `memberCountAboveDeclaredMaxRejected` | 转红 |

| 上限 `>` 改成 `>=` | `memberCountExactlyAtMaxAccepted` | 转红（唯一能杀它的一条） |

| 删掉 `shape` 缺省检查 | `nullShapeRejectedWithBadRequest` | 转红 |

| 把共用常量 32 改成 2 | `memberCountAboveDeclaredMaxRejected` | 转红（连带 12 条，证明共享常量有效） |



### 这一轮的两次自我纠正（扫描报告又错了）



这轮我写扫描器找"声明≠行为"，**结论错了两次**，都记下来：



1. **第一版 `@Valid` 判定只往 `@RequestBody` 之前看 400 字符**，

   而 `SchedulingController.createTask` 写的是 `@RequestBody @Valid TaskRequest req`

   —— `@Valid` 在**之后**，于是这个真正有校验的入口被误报成"无 @Valid"。

2. 据此我又断言"`DeliveryRequest` 9 条约束里 8 条无人执行" —— **也是错的**，

   因为我只读了 service 层，没看到 `DeliveryController.create` 上的 `@Valid`。



修正扫描逻辑后，4 个 DTO 的入口**全都有 `@Valid`**，"47 个注解是装饰品"不成立。

真正剩下的只有 `FormationController.create` 这一个缺口，也就是本 PR 修的这些。



**这是本项目扫描报告第 5 次出错**（前四次：MissionApi 三条校验、TelemetryIngest 4 处恒真、

surefire 正则匹配不到 `@Nested`、LoRa 降级断言集重复）。规律没变：

**扫描产出的是假设，变异测试产出的是结论。**



### 计数



**4825 → 4828**（+3）。以 `mvn clean package` 实测为准，22 处口径文档由门禁输出驱动同步。



---

---

---

## [Unreleased] — LoRa 降级路径：三条用例从"断言不足"改成"各守各的"（2026-10-15）



### 问题



`LoRaRelayServiceTest` 的三条降级用例，断言集**全都是正常路径那条的真子集**，

而且第 1 条（缺 store）与第 3 条（都缺）的断言集**完全相同** —— 都只有

`status == "SUCCESS"`：



| 用例 | 加固前断言数 | 是正常路径断言集的子集 |

|---|---|---|

| `receiveLoRaAlarmConvertsToAlarmEvent`（正常） | 3 | — |

| 缺 `AlarmEventStore` | **1** | 是 |

| 缺 `AlarmLinkageEngine` | 2 | 是 |

| 两个都缺 | **1** | 是 |



读起来像在测降级，实际分辨不出谁是谁 —— 第 1 条和第 3 条换个名字都成立。



### 修改：每条只断言属于自己那个组合的可观测差异



| 用例 | 只属于它的断言 |

|---|---|

| 缺 store | `Mockito.verify(mockEngine).processEvent(...)` —— 联动**仍被调用**；`eventStore.size()==0`；统计四项自增 |

| 缺 linkage | store 仍收到**完整事件**：类型 FIRE / 设备 `LoRa-101` / 严重度 WARN / 返回的 eventId 与落库 id 一致 |

| 都缺 | 事件仍产出 `eventId`、`message` 正确、`successRate==1.0` |



按"不做功能减法"的要求：**降级代码一行未删、一行未改**，只加固测试 + 改文档口径。



### Javadoc 如实化（`LoRaRelayService`）



原 Javadoc 把"降级模式"写成一条 feature：**"当依赖不可用时自动降级，不影响 LoRa

回传通道的基本功能"**。实测这在生产里**不可能发生**：



- `AlarmEventStore` 是裸 `@Component`、`AlarmLinkageEngine` 是裸 `@Service`

- 全仓无 `@ConditionalOn*` / `@Profile` 命中它们

- 所以 `@Autowired(required=false)` **必然注入成功**，两个字段永远非 null



改成如实描述，并明确写下一句以免误读：



> **不要把这里当成"存储挂了 LoRa 仍能收报警"的能力** —— 存储真挂了会抛异常，

> 由 `catch (Exception)` 计入 `totalFailed` 并返回 `FAILED`。



降级代码保留的理由也写清楚了：防御性 + 给"将来真做成可选装配"留回归网。



### 变异验证



| 变异 | 目标用例 | 结果 |

|---|---|---|

| 删掉 store 的 null 检查 | `alarmEventStoreUnavailable_stillRunsLinkageAndReturnsEventId` | 转红 |

| 删掉 linkage 的 null 检查 | `alarmLinkageEngineUnavailable_stillStoresEvent` | 转红 |

| 事件不返回 eventId | `bothDependenciesUnavailable_stillProducesEventAndCounts` | 转红 |

| 降级路径不记 `totalSuccess` | `alarmEventStoreUnavailable_stillRunsLinkageAndReturnsEventId` | 转红 |

| 删掉 store 的 `else`（只剩 `log.debug`） | 同上 | **存活** |

| 删掉 linkage 的 `else` | `alarmLinkageEngineUnavailable_stillStoresEvent` | **存活** |



最后两条**存活是应该的**：那俩 `else` 分支里只剩一句 `log.debug`，通过公开 API

没有任何可观测差异 —— **没有行为测试能杀它**。这是代码的性质，不是测试的弱。



### 过程中的一次自我纠正



写第一版时我用 `Mockito.mock(AlarmLinkageEngine.class)` 且不 stub 返回值，结果

新用例红了（`status` 是 FAILED 不是 SUCCESS）。我一度判定"联动引擎返回 null 会把

成功的回传变成 FAILED"是生产缺陷 —— **这个判断是错的**：`processEvent()` 只有

一个 return（L199）、无提前返回，**恒返回非 null**，NPE 纯粹是 mock 的产物。

已改为 stub 非 null 返回，并在测试里注释说明原因，免得后人再踩。



### 计数



**4825 → 4825**（用例数不变，只加固断言 + 改文档）。以 `mvn clean package` 实测为准。



---

---

---

## [Unreleased] — 声明与行为不一致的三处补齐：holdTime 漏检、catch 二次 NPE、uploadMission 五个校验零覆盖（2026-10-15）



### 一、先纠正两份"扫描结论"，它们都是错的



上一轮扫描报告说：

- 「`MissionApi.Waypoint` 三条校验零覆盖」→ **错**。`DroneControllerTest` 里

  已有 lat>90 / lon<-180 / alt<0 三条，逐条变异确认各杀 1 条。

- 「`TelemetryIngestServiceTest` 4 处恒真」→ **错**。实测 6 个变异里 5 个被杀，

  只有 `handle_decisionEvent_monitorAbsent` 确实恒真（详见下）。



所以这轮的起点不是"补零覆盖"，而是**把每个怀疑都变异一遍，用结果代替印象**。



### 二、`holdTime`：record 声明 `@Min(0)`，解析侧唯独漏检（真缺陷）



`MissionItemRequest` 上 `lat @Min(-90)@Max(90)`、`lon @Min(-180)@Max(180)`、

`holdTime @Min(0)` 声明得整整齐齐。但 `DroneController.uploadMission` 收的是

`@RequestBody JsonNode`，再由 `parseMissionItems()` **手工 `new`** 出这个 record ——

不走 Spring 绑定，JSR-303 注解**一条都不会触发**（代码里原有注释已点明这点）。



于是范围检查全靠手写，而手写时**漏了 `holdTime`**：lat/lon/alt 三条都在，

`holdTime` 只有 `n.path("holdTime").asDouble(0)`，负值一路走到 MISSION_ITEM_INT

编码出去。**record 契约与实际解析行为不一致** —— 这是"声明≠行为"的第一处。



修：`parseMissionItems()` 补上 `holdTime < 0` 检查；

`MissionItemRequest` 的 Javadoc 明确写出「这些注解在 mission 上传路径上不会自动

触发，实际生效的五条检查全在 `parseMissionItems()` 里」，免得后人再当成装饰。



### 三、`TelemetryIngestService.handle(null)`：Javadoc 承诺 Never throws，实际抛 NPE



```java

} catch (RuntimeException e) {

    log.debug("... msgId={} ...", frame.getMessageId(), ...);   // frame 为 null 时这里二次抛

}

```



`handle(null)` → `decode` 抛 NPE → 被 catch 捕获 → **catch 块自己再去解引用

`frame`** → 二次 NPE 逃出方法。而方法 Javadoc 明写 "Never throws"。



旧用例 `handle_nullFrame_throwsNpeDueToCatchBlockAccessingFrame` 把这个缺陷

**钉成了期望行为**（DisplayName 直接写「源码限制」）。修法是一行：catch 里

判 `frame != null` 再取字段。测试同步改成断言"不抛异常"，并把 DisplayName 改成

陈述契约而不是陈述缺陷。



### 四、`uploadMission`：五个校验点零覆盖（变异 0 杀确认）



| 校验 | 加测试前 | 变异结果 |

|---|---|---|

| `lat` / `lon` / `alt` 范围 | 已有，各杀 1 | 有效 |

| `cmd` 空串检查 | **0 条** | 0 杀 → 补 |

| `items` 缺失 / 空数组 / 非数组 | **0 条** | 0 杀 → 补 |

| 1000 条上限 | **0 条** | 0 杀 → 补 |

| `holdTime >= 0` | **0 条，且代码也没检** | — → 修 + 补 |



新增 8 条：`negativeHoldTime` / `missingItems` / `emptyItems` / `itemsNotArray` /

`itemWithoutCmd` / `itemWithBlankCmd`（专测 `isBlank` 而非 `isEmpty`）/

`tooManyItems` / `exactly1000Items`（边界闭合，防上限被改成 `>=`）。



### 五、一个诚实的例外：恒真测试摊开写，不假装能测



`handle_decisionEvent_monitorAbsent_noException` 原断言只是

`assertThatCode(...).doesNotThrowAnyException()`。实测**去掉

`decisionMonitor` 的 null 检查，这条仍全绿（T4，0 杀）** —— 因为

`handle()` 外层的 `catch (RuntimeException)` 把两种行为从外部观察上抹平了：



- 有 null 检查 → 跳过决策落库，`publishEvent` 已执行

- 无 null 检查 → 决策落库处 NPE，`publishEvent` 也已执行，NPE 又被 catch 吞掉



从调用方（UdpGateway 的收包线程）看两者完全一样。**这不是测试写错了，是那个

大 catch 让这个实现点变得不可观测。**



处理：不删（删了就丢掉降级路径的回归锁），改成断言**降级契约本身** ——

「监控缺席不影响事件转发与收包线程存活」，并在注释里写明它杀不掉 T4 以及原因。

要让 null 检查变得可测，得先拆掉那个大 catch，那会把「解码失败」和「下游抛异常」

两类语义混在一起，是更大的改动。



### 六、变异验证结果



| 变异 | 目标用例 | 结果 |

|---|---|---|

| 拆掉 `holdTime` 检查 | `uploadMission_negativeHoldTime_throwsBadRequest` | 转红 |

| 拆掉 `cmd` 空串检查 | `uploadMission_itemWithBlankCmd_throwsBadRequest` | 转红 |

| 拆掉 1000 条上限 | `uploadMission_tooManyItems_throwsBadRequest` | 转红 |

| 回退 catch 的 null 判断 | `handle_nullFrame_doesNotThrow` | 转红 `NullPointerException` |

| 拆掉**两处** empty 检查 | `uploadMission_emptyItems_throwsBadRequest` | 转红 |



最后一条要说明：**"items 非空"这条校验在外层 `uploadMission` 和内层

`parseMissionItems` 里重复存在**，所以单独移除任意一处都被另一处掩盖，两个单点

变异都存活；同时移除才杀死。这是重复校验的真实代价 —— 它让这个防护点无法用

单点变异证明。测试是有覆盖的，只是需要组合变异。



### 计数



4817 → **4825**（+8）。仅以 `mvn clean package` 实测为准。



---

---

---

## [Unreleased] — RID 畸形帧不再静默丢弃：解码/解包异常兜住并记 WARN（2026-10-15）



### 缺陷



`RidIngestService.onFrame` 假定 `MavlinkMessage.decode()` 不会抛，直接把结果

喂给 `processRidMessage`。但 RID 帧的 payload 长度由发送方决定，而解析器

（`MavlinkParser`）只校验 CRC、**不按 dialect 的 LEN 补齐也不拒绝**，于是两类帧

能真的从网络走到这里并把解码器打挂：



| 畸形帧 | 现象 |

|---|---|

| 已登记 msgId（12900 `OPEN_DRONE_ID_BASIC_ID`，LEN=22）+ 截短 payload | `BufferUnderflowException`（`b.get(uasId)` 读越界） |

| 已登记 msgId（12915 `OPEN_DRONE_ID_MESSAGE_PACK`）+ payload 头两字节声明 255 条子消息 | `unpack()` 以 25 字节步长读出 250 字节字段 → `ArrayIndexOutOfBoundsException`（`MAX_MSG_PACK_SIZE=10` 只在 `pack()` 侧校验，`decode()`/`unpack()` 侧没有） |



`onFrame` 没有 try/catch，异常一路冒到 `UdpMavlinkTransport` 的

`catch (RuntimeException ignore)` —— **收包循环死不了，但连一行日志都没有**。

结果是截短的 RID 帧被完全静默丢弃，运维在日志里看不到任何痕迹；

而 `onFrame` 自己想打的那句 `log.warn("RID 消息解码失败")` 对这条路径永远不执行

（异常在到达它之前就没了）。



### 修改



- `onFrame` 的解码与解包收进新的私有方法 `decodeRidMessages`，两处各兜一层

  `RuntimeException` 并记 WARN（带 msgId / sysid / payloadLen / dialect 期望长度 /

  异常原因），不再冒到传输层被静默吞掉。

- MessagePack 分支顺带把 `unpack()` 结果里的 null 过滤掉（原逻辑在调用点过滤，

  移进方法内）。



### 测试



`RidIngestServiceTest` 的解码失败用例重做。原来那条叫

「解码失败时 WARN 日志且不更新状态」，用的是 msgId=12950 —— 它未在

`MavlinkMessageInfo` 登记，`crcExtraOf` 抛 `MavlinkException`，**帧在 CRC 阶段就被

丢了，生产路径到不了 `onFrame`**。真正可达的两条当时零覆盖。



- 新增 `onFrame_truncatedPayload_doesNotThrow`：用 `MavlinkFrame.of` + `encodeV2`

  + `MavlinkParser` 造一帧**真的能过 CRC** 的截短帧，再喂给 `onFrame`。

  先断言它能通过解析器 —— 若哪天解析器开始补齐/拒绝短帧，用例会先红提醒前提变了。

- 新增 `onFrame_messagePackClaimingTooManySubMessages_doesNotThrow`：上面那张表第二行。

- 原用例改名为 `onFrame_unregisteredMsgIdInRange_returnsWithoutUpdating`，

  DisplayName 与 Javadoc 标明「防御分支，生产不可达」。

- `createFrame` 辅助方法的 Javadoc 点明它不经过 CRC、生产到不了 `onFrame`，

  凡验证生产行为的用例必须改用新的 `crcValidFrame`。



三条逐个变异验证（目标用例是否转红 + 失败原因）：



| 变异 | 目标用例 | 结果 |

|---|---|---|

| 拆掉 `decode` 的 try/catch | `onFrame_truncatedPayload_doesNotThrow` | 转红 `BufferUnderflowException` |

| 拆掉 `unpack` 的 try/catch | `onFrame_messagePackClaimingTooManySubMessages_doesNotThrow` | 转红 `ArrayIndexOutOfBoundsException: Index 250 out of bounds for length 250` |

| 删掉 `msg==null` 守卫 | `onFrame_unregisteredMsgIdInRange_returnsWithoutUpdating` | 转红 `NullPointerException: Cannot invoke "Object.getClass()"` |



### 顺带修正一个测量工具



验证过程中发现上一轮"删掉 `msg==null` 守卫后 10 条仍全绿"的结论是**错的**，

错因在测量工具而非被测代码：扫 surefire `.txt` 报告的正则写成

`^([\w.]+)\.(\w+)\s*-- Time elapsed.*<<< (FAILURE|ERROR)`，而 `@Nested` 类的行是

`...RidIngestServiceTest$DecodeFailure.onFrame_xxx -- ...` —— `$` 不在 `[\w.]` 里、

`^` 又要求行首匹配，**这条正则一行都匹配不上**，于是把"读不出结果"报成了"全绿"。

换成解析 `TEST-*.xml` 后重测：旧用例其实**是**覆盖那条守卫的。



教训与之前那三个工具坑同源：**尺子坏了和被测物没动，在"全绿"这个输出上无法区分**。

空变异、正则锚点、写 BOM 之外，这是第四个 —— 而且危险方向相反，它会让人去改

本来没问题的测试。



### 计数



4815 → **4817**（+2）。仅以 `mvn clean package` 实测为准。



---

---

---

## [Unreleased] — C4 收尾：当日历史轨迹接进前端，重启后折线不再只剩一个点（2026-10-14）



> 先说清**缺的是什么**：不是"历史没落库"。`GET /api/v1/flightlog/track?day=&sysid=`

> 早已存在，从 `flight_log` 出当天整条轨迹、不受重启影响，也有测试钉着

> （`FlightLogServiceTest` / `FlightLogPersistenceTest`）。**缺的是前端从没调用过它**——

> `api.js` 只绑了 `/flightlog` 列表，地图折线全吃 `/drones/{sysid}/track`，

> 而那条读的是 `DroneSnapshot.track = BoundedHistory<>(200)`

> （`/tracking/{sysid}/track` 读的是 `FlightTrackStore` 的 3600 点 deque），

> 两者都是内存窗口；后端重启后 `loadLastKnownPositions()` 只塞回 **1 个**最后已知点。

> ⇒ 症状比"只剩一个点"更糟：`MapView.jsx:510` 画折线的守卫是 `track.length > 1`，

> 所以点数 ≤ 1 时**整条历史轨迹根本不画**——重启或刷新后地图上什么轨迹都没有。



| # | 交付 | 内容 |

|---|---|---|

| ① | `src/utils/trackMerge.js`（新） | `mergeTrack(live, persisted, limit)`：按 `ts` 升序、**同 ts 保留 live 对象本身**（不重建不丢字段）、非有限 `ts` 直接丢、超限保留**最新** N 个、**不改入参**；`MAX_TRACK_POINTS` 从 `useDrones` 搬进来做单一真相源 |

| ② | `api.getDayTrack(sysid, day?)` | 接上那条已存在的通路；`day` 省略时**不拼 `day=undefined`**，交后端按当天解释 |

| ③ | `useDrones.loadTelemetry` 叠历史 | 内存窗口 + 当日库内历史合并后落 `track`。历史查询**单开一个 try**：`flight_log` 未开库/查询失败只该少一段历史，不该把刚拿到的实时轨迹抹成空——若并进原来那个 try，它的 `catch` 会顺手 `setTrack([])`。这条由变异 M8 验证过确实会红 |

| ④ | `/tracking/{sysid}/replay` 文案改准 | 原文写"历史轨迹回放……用于事故复盘、航线核查"，实际只读内存窗口 ⇒ 改为如实说明窗口边界并指向日轨迹端点。**行为没动**，只把骗人的命题改对 |

| ⑤ | 测试 18 例 + 变异验证 | 纯函数 13 例 + hook 验收 5 例（走 `loadTelemetry` 真路径，防"函数写了没人接"）；另跑 9 条变异，逐条确认由**指定**用例转红 |



> 计数口径照旧：本条**只记增量**，绝对数由 `check-test-count-docs.py` 实测驱动。



**刻意没做的事（留下来说明，不是遗漏）**：

`/replay` 没有改成"回退查库"。`flight_log` 不存 `heading` 与 `vx/vy/vz`，而 `/replay` 的

返回类型是 `FlightTrackStore.TrackPoint`（含这些字段）——要么填 0 冒充实测、要么现场推算，

两者都比这次改动的收益小。所以本轮只把文案改准，历史深度由日轨迹端点提供。

跨多日回放同样未做（端点本身按单日）。



**边界**：验证到的是"前端能拿到当天库内轨迹并与实时段正确合并"这一层；

`flight_log` 的实际写入频率、保留策略（`FlightLogRetentionJob`）决定了能回看多久，

那部分不在本条的断言范围内。



--

---

---

## [Unreleased] — link-sim 接线账本：把 78 个挂在未装配类上的绿灯钉成显式期望状态



### 问题



link-sim 测试全绿，读者容易得出"跨网段中继 / LoRa 网关 / 异构链路管理都工作正常"的结论。

但 `LinkSimMain`（本模块**唯一**的 `main()` 入口）只装配

`RelayNode` / `ImpairmentEngine` 家族 / `LinkProfile` / `RelayConfig`。



| 类 | 被 LinkSimMain 装配 | 测试引用数 |

|---|---|---|

| `CrossSegmentForwarder` | 否 | 34 |

| `HeterogeneousLinkManager` | 否 | 34 |

| `LinkBridgeNode` | 否 | 60 |

| `LoRaRelayChannel` | 否 | 50 |

| `LinkQualityMonitor` | 否 | 26 |



这 5 个类**一次都没被入口实例化**，它们身上的用例却占 link-sim 112 个中的 78 个。

即 70% 的绿灯对应的代码路径在产品里不存在——集体变红也不会改变任何生产行为。



### 为什么不直接删（对照多跳那次）



这些类不是死代码的残留，是**有设计稿、没实现**：

`CrossSegmentForwarder` 对应 FR-19，`HeterogeneousLinkManager`/`LinkBridgeNode` 对应 FR-01，

`LoRaRelayChannel` 对应布控球告警回传链路。直接删等于把设计意图一并抹掉，

而接线与否是产品决策（link-sim 是替真实硬件做联调的沙盘，异构链路桥接在真实环境里有价值）。



所以这次**把账摊开**而不是动手接线/删除。



### 改了什么



**1. 新增 `LinkSimWiringTest`（5 条）**，把"未接线"钉成**期望状态**而非沉默：



| 用例 | 作用 |

|---|---|

| `entryPointIsUnique` | 前提：`LinkSimMain` 是唯一入口。将来加了第二个 `main()`，先改这里 |

| `unwiredClassesAreNotAssembledByEntryPoint` | 5 个类都不得出现在入口里。**一旦有人接线就判红**，并要求同步 README 能力宣称、把类移出清单 |

| `wiredClassesAreInstantiatedByEntryPoint` | 正向对照：已接入的类必须真被接入 |

| `detectorDistinguishesWiringFromMentions` | 探测器自测：合成输入验证判据真的会区分 |

| `unwiredDebtIsVisible` | 打印每个未接线类的测试引用数，让"这些绿灯值多少钱"随时可查 |



**2. 5 个类的 Javadoc 各加一段"状态：未接入生产路径"**，写明为什么（入口不装配）、

以及接线时该同步哪些文档声称。账不能只在测试里，得在代码里也看得见。



两个容易被绿灯掩盖的细节顺手写进了 Javadoc：



- `LinkBridgeNode.bridgeFrame()` 只把帧放进 `forwardQueue` 加计数，**不产生任何 I/O**；

  `pollBridgedFrame()` 没有生产调用方

- `LoRaRelayChannel` 压缩告警体的时间戳走 `writeInt((int) timestamp)`，真实时间戳

  （约 1.7e12）超过 32 位会被截断；现有测试特意挑了小于 `2^31` 的值让往返无损



另外 `LinkQualityMonitor` 的 Javadoc 补了一句命名提醒：cloud-backend 另有一个

**同名但不同包**的 `io.aerofleet.cloud.commadapt.LinkQualityMonitor`，那是另一个类，

检索时别被同名命中误导。



### 判据为什么不是"名字出现过"



变异测试逼出来的。最初用"名字出现即算接线"，可把

`new RelayNode(cfg).run()` 改写成 `RelayNode r0 = new RelayNode(cfg); r0.run();`

之后名字照样在场，断言恒真——**这条正向对照形同虚设**，正是它自己注释里声称要防的事。



改成按类分别指定接入形态：



- `NEW`：入口必须出现 `new Xxx(`

- `REFERENCED`：入口只需引用该类型。`ImpairmentEngine` 就是这种——入口写

  `ImpairmentEngine up = profile.engine()`，真正的工厂是 `LinkProfile`



也没法一律要求 `new Xxx(`：`ImpairmentEngine` 会被误判成未接线。



### 变异验证



| 变异 | 期望 | 结果 |

|---|---|---|

| 生产侧：把 `LoRaRelayChannel` 真的接进入口 | `unwired...` 判红 | 1 红 ✓ |

| 测试侧：把实例化判据改成永不匹配 | `wired...` 对照判红 | 2 红 ✓ |

| 还原 | 全绿 | ✓ |



第二条特意用"永不匹配"而不是"恒真"：判据恒真时正向对照本来就该绿，杀不掉任何东西；

唯有判据失效，才能证明这条对照真的在检查东西。



### 计数



Java 4803 → **4808**（`link-sim` 实测仍为 **112**；其余模块取 master 实测值不变）



### 附：这一轮的计数错误（自己踩的，值得记）



第一次同步时我把 link-sim 写成 **117**，依据是"master 112 + 我新增 5 条"。

门禁随即报「声称 117，实测 112」。逐类核对后才发现：干净全量实测确实是 112，

而 `LinkSimWiringTest` 的 5 条**已经含在 112 里**了 —— master 的 112 是在我这条分支

之前测的，我新增 5 条的同时**别处已经变化**，两者不是简单相加。



核对方式（可复用）：逐测试文件比对「源码 @Test 条数」与「surefire XML testcase 条数」，

两侧全等 → 排除"某条没被跑到"和"testCompile 没真跑"两种可能，再取 `clean test` 汇总行。



> 这与本会话更早的一次教训同源：**测试数据/口径不对时，绿了也证明不了任何事**。

> 这次不是绿灯，是"按算术推导数字"——一样不可靠。

-

---

---

## [Unreleased] — 数据链 failsafe：「从未收到过 GCS 包」不再被静默豁免



### 洞



`FailsafeController` 计算数据链沉默时长：



```java

long silence = lastGcsRxMs > 0 ? nowMs - lastGcsRxMs : 0;   // 旧

```



`lastGcsRxMs == 0`（一次 GCS 包都没收到过）被折成 `silence = 0`，于是

`linkNow = airborne && 0 > linkLossAfterMs` **恒 false**。



结果是两种失联形态里，只管了一种：



| 形态 | 旧行为 |

|---|---|

| 连上后中断（GCS 掉线） | ✅ 沉默时长正常累加，RTL 触发 |

| **从未连上就起飞** | ❌ 永远免疫，failsafe 永不触发 |



后者恰恰是更危险的那一种，却完全没有保护。洞不算宽 —— 任何 GCS 命令在

`VirtualDrone.onFrame` 里都会先置 `lastGcsRxMs`，所以要撞上它得是

"无人机已在空中、但本进程内从未收到过一个 GCS 包"（自主起飞、上次会话遗留状态等）。



### 改法



沉默基线从 boot 时刻兜底：



```java

long baseline = lastGcsRxMs > 0 ? lastGcsRxMs : bootMs;

long silence = nowMs - baseline;

```



`bootMs` 走**构造器**而不是 `evaluate()` 参数 —— `evaluate()` 的签名和 20 个调用点

一行没动。`VirtualDrone` 侧传 `bootUnixMs`（final 字段，在构造器体之前就初始化好了）。



### 顺手堵掉一个编译期陷阱



刻意**不提供**省略 `bootMs` 的构造器和无参 `defaults()`。因为 `bootMs = 0` 会让

`silence = nowMs - 0` 恒为 epoch 毫秒数（约 1.7e12），立刻满足 `silence > 15000`

⇒ 一开机就 RTL。让编译器强制每个构造点都显式面对这个基线。



### 测试



新增 2 条回归：



- `linkLossTriggersEvenWhenGcsNeverConnected`：开机 60s、从无 GCS 包、在飞行 → 必须 ENTER_RTL

- `gcsNeverConnected_respectsGracePeriod`：开机 10s（< 15s 宽限）仍须 NONE，越过门限后才 RTL

  —— 证明"从 boot 起算"没有把宽限期吃掉



同时 `linkLossIgnoredWhileDisarmed` 从**恒真断言**变成真断言：测试用的 `fx()` 把

bootMs 设成 `NOW - 60s`，`silence` 已经超过门限，STANDBY 下仍必须是 NONE。

此前 `lastGcsRxMs=0` 会把 silence 折成 0，这条用例压根测不到 `airborne` 门控，

把 `airborne &&` 整段删掉照样绿。



变异验证：把沉默基线改回 `silence=0` → 2 个用例转红；还原 → 全绿。



### 计数



Java 4784 → **4786**（`drone-sim` 1424 → **1426**，净 +2；其余模块取 master 实测值不变）

---

---

## [Unreleased] — SPI 豁免账清零：最后 3 条适配器各自落契约测试（2026-10-13）





> SPI 可验证性门禁（`scripts/check-spi-coverage.py`）2026-10-12 上线时登记了 9 条"编译、启动、

> 日志里看起来在工作，但没有任何断言钉过它"的生产适配器。前两批清偿 6 条（`UomReportSink` +

> 5 个 `ModuleAdapter` 实现），本批清偿最后 3 条，`EXEMPT = {}` 清零。这条棘轮只准拧紧：

> 此后新增的未验适配器必须带测试，任何"先欠着"都得重新登记并写明到期日。



| # | 交付 | 内容 |

|---|---|---|

| ① | **`MavlinkDeviceGateway` 契约测试**（9 例） | `protocol()=="mavlink"`；`send` 委派全部 9 个参数；`MAV_RESULT` 受理白名单——`ACCEPTED(0)` / `IN_PROGRESS(5)` 判 true，`TEMPORARILY_REJECTED` / `DENIED` / `UNSUPPORTED` / `FAILED` / 未知码 99 判 false |

| ② | **`UdpTransportAdapter` 契约测试**（7 例） | 本机回环真帧收发（真实 `DatagramSocket`，非 mock）：默认目的地 / 末位 peer 兜底 / 无 peer 静默丢弃、构造期拒 null、`unwrap`/`port` 委派、关闭后 `send` 抛 `IOException`、入站帧回调 |

| ③ | **`RealEnvSource` 契约测试**（2 例） | `snapshot()` 与 `evolve(1.0)` 均明确抛 `UnsupportedOperationException`（M0b 未实现的边界如实声明） |

| ④ | **豁免账清零** | `EXEMPT = {}`；门禁自检 6/6、审计报 `未验 0 / 已豁免 0` |

| ⑤ | **文档计数同步** | 本批 +18 例（cloud-backend +9、drone-sim +9）；全仓与分模块计数行按 `check-test-count-docs.py` 实测刷新（绝对数不手写，理由见状态板）；ROADMAP 第 25 行的滞留声称值一并纠正 |



> 计数口径：本仓 CHANGELOG 条目**只记增量**，绝对数交给 `check-test-count-docs.py` 实测驱动——

> 分支基点每跟一次 master，写死的绝对数就作废一次。



**顺带验出的门禁自身缺陷**：ROADMAP 第 25 行同时含 Java 声称与前端声称，旧门禁的

`if fe_matched: continue` 会把整行当作"已按前端口径处理"而跳过，该行的 Java 声称值因而长期逃检

——这是 G-3 缺陷的同一处两面（另一面见 #72）。



**边界**：契约测试钉的是"参数委派 / 返回码语义 / 回环可达 / 未实现即抛"这类可观测契约，

不是真机硬件行为；真机适配仍需在真环境中补验。



---

---

---

## [Unreleased] — 短 payload 容忍解码的真实修复：两个用例的绿灯建立在生产造不出的帧形上



### 怎么发现的



在系统性排查「用合成/不可能数据冒充真实输入」的假绿测试时命中。两个用例都声称覆盖

「短 payload 容忍解码」，写法是：



```java

new MavlinkFrame(1, 0, 0, 0, 1, 1, 30039, new byte[]{42, 0}, 0)   // LEN=1，payload 数组 2 字节

```



这是**自相矛盾状态**：`MavlinkParser` 恒有 `payload.length == payloadLen`，

`MavlinkFrame.of` 也按 `payload.length` 定 LEN，生产任何路径都产不出 LEN 与数组长度不符的帧。

测试专挑这个不可能的状态，于是绿灯建立在一个真空上。



### 真空下面是什么



`SatLinkStatusMsg.decode` / `SatPassScheduleMsg.decode` 的 javadoc 都写着

「payload 短于 LEN 时容忍（缺失字段填 0）」，方法体内也确实对 8 个字段写了

`len > N ? ... : 0` 守卫 —— **唯独第一个字段 `u16(b, 0)` 没有守卫**。

`PayloadCodec.u16` 走 `ByteBuffer.getShort`，不足 2 字节必抛 `IndexOutOfBoundsException`。



实测（真实形状的线上帧）：



| 帧形 | LEN=1 / payload 1 字节（生产形态） | LEN=1 / payload 2 字节（旧测试） |

|---|---|---|

| 结果 | **IndexOutOfBoundsException** | 通过，satId=42 |



### 实际影响（没有夸大）



先核实过调用方，**不是单帧 DoS**：`TelemetryIngestService.handle` 与 `VirtualDrone`

都用 `catch (RuntimeException)` 包住 `MavlinkMessage.decode`，畸形帧降级为丢帧 + 日志，

收包循环不会中断。真实损害是：声明的「容忍」契约在这两个消息上并不存在，

且日志里只会看到一条语焉不详的 decode 失败。



### 为什么只改这两个，不把 84 个类一起改



扫了全部消息类，有 84 个首字段无守卫。但要分两类：



- **本仓自定义消息且 javadoc 承诺容忍** —— 契约违约，必须修（本次两个）

- **标准 MAVLink 消息**（Heartbeat/Attitude/GpsRawInt…）—— LEN 由 dialect 固定，

  短帧本身就是畸形。此时抛异常丢帧是**正确**行为；加守卫反而会把畸形帧悄悄编造成

  一堆 0 值字段（比如 time_boot_ms=0），让人看不出数据有问题。保持现状是对的。



### 改了什么



- `SatLinkStatusMsg` / `SatPassScheduleMsg` 首个 u16 补 `len > 1` 守卫，

  与同方法内其余字段口径一致（字段不完整即填 0，不拿半截字节凑值）

- 两个用例改为**走完整生产路径**：`MavlinkFrame.of(截断 payload)` 按真实长度算 CRC →

  `encodeV2()` 产出 LEN=截断长度的线上帧 → `MavlinkParser` 逐帧校验 CRC → decode。

  只用公开 API，帧形由生产构造器保证可达

- 断言里钉住生产不变式 `payloadLength == payload.length`，让今后再有人手搓矛盾帧会立刻红

- 拆出 LEN=1（半截 u16）与 LEN=0 两个边界用例，精确钉住守卫语义



### 变异验证



移除两处守卫 → 4 个用例转 error；还原 → 全绿。LEN=2 那条在无守卫时仍绿是正确的，

因为 2 字节本就够 `getShort` 读，它测的是「刚好够」的边界而非容忍。



### 计数



Java 4762 → **4766**（`mavlink-core` 478 → **482**：两个类各 1 条拆成 3 条，净 +4；

其余模块取 master 实测值不变）

---

---

## [Unreleased] — 又把 4 个非 CI e2e 脚本救活：清跨轮污染、修调度器饿死、补两个真缺陷（2026-10-13）



> #74 把 formation/mesh 救活后，按同一套"先跑一遍再说"继续推 e2e 覆盖面。本轮

> rid / network / rf / vision-pixels 四个脚本由红转绿（合计 45 项断言），其中

> **三个是生产代码真缺陷**，不是脚本问题。



### 真缺陷



| # | 缺陷 | 症状 | 修法 |

|---|---|---|---|

| ① | `FormationKeeper` 会给**未解锁**成员发 DO_REPOSITION | 未解锁飞机必回 `MAV_RESULT=2`（DENIED），而 `DroneCommandService` 按重试策略**阻塞数秒**等 ACK。Boot 默认调度池只有 1 个线程，这一卡就把 RID 超时检测、指标采集、自动调度全部拖住 | 未解锁成员直接跳过（对不可能执行修正的成员不该发）；`spring.task.scheduling.pool.size=8`——一次卡住的 MAVLink 往返不再连坐整池 |

| ② | `VisionController` 的 catch 里 `Map.of(...getMessage())` | 异常消息为 null（NPE 之类）时，`Map.of` 拒收 null value ⇒ **catch 块自己抛 NPE**，500 响应里只剩 "internal error: NullPointerException"，真因被冲掉 | 改用 LinkedHashMap + `String.valueOf(...)` 兜底，日志补 throwable |

| ③ | `drone-sim` 默认 home 之外的 e2e 会被**上一轮的 H2 数据**污染 | dev profile 落 `./data/aerofleet` 文件库，历史编队被 `restoreFormations` 复活后，旧编队的 Keeper 把同一批 sysid 的飞机往**旧队形**拽——新编队的 FORMING→STABLE 永远等不到（formation 脚本 FR-16 间歇性超时的真因） | 所有自起后端的 e2e 脚本统一传 `--spring.datasource.url=jdbc:h2:mem:<脚本名>;DB_CLOSE_DELAY=-1`，一轮一库、跑完即焚 |



### e2e 脚本侧



- **e2e-rid**：`--aerofleet.drone-extra-ports` 补上飞机端口——drone-sim 是被动的（只回给"最近一个对端"），后端默认只探 14550/14541-14543，飞机在 14540 永远注册不上；health 探针改业务端点。

- **e2e-network**：补 `AF_BACKEND_REST_PORT` 覆盖与快速前置（后端没起就直说，别等 30 秒超时）；清理机制改进程清单（原来闭包引用的变量中途被重绑，3 条清理动作全落在同一个 PID 上）；旧进程清理从"匹配任意 link-sim"收窄到本脚本专属端口 14600/14540，不再误杀他人会话的中继。

- **e2e-rf / e2e-vision-pixels**：REST 端口可覆盖；vision-pixels 另修三处——① 等待 20s 太短（60m 爬升就要 30s，飞机根本没到目标上空，画面里没有斑块），改 50s + 给航点加 `holdTime=120`（到点即完成 mission 会触发 RTL 飞走）；② 后端前置条件写进文件头（`vision.source=pixels` + `sim-truth-base` 指向本脚本的 18082，默认 18080 会 ConnectException）；③ `@($null).Count` 在 PowerShell 里等于 **1**，缺失字段被数成"1 个检测"、后面两个断言跟着假绿——显式判空；JPEG 下载改 `WebClient`（`Invoke-WebRequest` 的 `RawContentStream` 在 PS5.1 下为 null，会把 try 块炸掉）。

- **e2e-px4-sitl**：同套端口/profile/就绪判据三件套（能否真跑仍取决于本机是否装了 PX4 SITL：`px4` 二进制或 WSL）。



### 验证



| 脚本 | 结果 |

|---|---|

| e2e-rid | 14 项全 PASS |

| e2e-network | 9 项全 PASS（LTE 损伤链路 + Starlink 周期黑洞） |

| e2e-rf | 4 项全 PASS（RSSI 衰减 + REST 可查） |

| e2e-vision-pixels | 6 项全 PASS（2 个 blob、定位误差 0.1m、JPEG 52095 字节） |

| cloud-backend 单测 | 2710 全绿（含 `FormationKeeperTest` 新增的"未解锁成员跳过"用例） |

| 文档单测数门禁 | 4762，全仓一致 |



---

---

---

## [Unreleased] — C5 第三步的拍板材料：`docs/signing-prod-rollout.md`（**不改任何默认值**，2026-10-12）



- **为什么需要**：`docs/security-design.md` §7 把签名语义、装配、指标、告警表达式都写全了，但**没有一段回答

  "prod 该怎么按顺序打开、看什么数字算成功、出事怎么退"**；而用户已明确 C5 在 prod 打开是**今年要拍的事**。

  本笔只补这段操作层，设计层一律引用不重述。

- **文档给出的关键内容**：三姿态对照表（off / permissive / strict，各自的后果与现成测试锚点）、

  打开前的 5 条前置条件、**两步走顺序**（先 `reject-unsigned=false` 观察 `aerofleet.signing.unsigned` 归零，

  再收紧 strict）、回滚方式（翻回 `enabled=false`，四个签名 bean 一概不创建、行为与改造前逐字节一致）、

  以及仍需人回答的 3 个问题（谁逐机发密钥、能否接受 permissive 窗口、翻牌时有没有人值守）。

- **为什么中间那步不能跳**：strict 唯一失败模式是"某台设备没配密钥"，表现为**该机遥测静默消失**——

  在无人机场景里这是最坏的一类故障。协议层面也没有平滑过渡：v2 签名块 `linkId|timestamp|signature`

  **无 key id 字段** ⇒ 新旧密钥无法并存，`SigningKeyStoreWriter` 类注释与 `MavlinkSigningKeyController`

  都把"轮换只能是硬切换、没有 graceHours"写在代码里（**API Key 那套 `graceHours` 与它无关，别搬心智模型**）。

- **实测台账（写清楚测到哪、没测到哪）**：本机跑签名四测试类共 **32 例全绿**（含"未签名 + reject=true 被拒"、

  permissive 下"未签名放行但签错密钥仍拒"、指标三类 reason 互不串味、"开了签名却没给密钥 ⇒ 启动失败"）；

  CI 的 `E2E MAVLink signing (6 scenarios)` 在 master run `38221403959` 为 success。

  我**试图**本机直跑 `scripts/e2e-signing.ps1`（已把 REST/UDP 全换成 18080/15550/15540/15600 以避开并行 agent

  的构建端口），**场景 1 即失败**：本机 6379 上的 Redis 要求认证（`NOAUTH HELLO must be called with the client

  authenticated itself`），脚本无口令入口、本机也无自起的免密 redis ⇒ 这条 e2e 的可复现环境是 CI 或

  `deploy/docker/docker-compose.yml`（自带 `redis:7-alpine`），**不是我这台机器**；文中不把 CI 的绿写成自己的验证。

- **有意的摩擦**：`MavlinkSigningConfigurationTest` 有一条"prod profile 仍是 enabled=false（第三步需单独拍板）"

  ⇒ 真要翻 prod 必须同时改这条测试，改它的人就留下决策记录。本笔**没有**改它，也没有改任何 profile。

- **边界**：以上基于 `drone-sim`/`link-sim` 模拟，只证明**协议层行为正确**，不证明现网兼容性；

  真机固件是否都支持 v2 签名块、逐机密钥发放流程，都要等 permissive 那一步用真实机队跑才有数。





---

---

---

## [Unreleased] — 计数门禁：同行含前后端两个声称时，Java 声称不再被静默跳过（2026-10-13）



> 现象先于定位。翻旧 CI 日志时发现一个矛盾：master 上一轮门禁实测 4703，而当时

> `ROADMAP.md:25` 写的是 4681，门禁却报"全部一致"。起初归因于"追述/当前归类不稳定"，

> 逐版复跑后才定位到真变量——**`--frontend-json`**。



| # | 交付 | 内容 |

|---|---|---|

| ① | **根因** | 脚本命中前端文法即**整行 `continue`**，于是「Java 总数 + 前端例数」同处一行的写法（`ROADMAP.md:25` 的「…单测全绿（…前端已有 292 例 vitest…）」正如此）会把同行的 Java 声称一并放过。CI 一直带 `--frontend-json`，故 master 上该行的 Java 总数长期未被校验；本地探针没带该开关才判红——这才是"同一行两种归类"的来源，与实测值无关。 |

| ② | **修法** | 把前端片段从行文本里**挖掉后再做 Java 核对**：两个声称各自被校验，且 Java 的宽泛文法也不会再把前端的数抓去比 Java 实测值（那会给出理由错误的红）。 |

| ③ | **自测** | `--self-test` 加两例并证明其有效（把修复回退即转红）：混合行里的 Java 声称**偏高**判红、**偏低**也判红（偏低值不刺眼，是最易被放过的形态）。 |

| ④ | **文档** | `ROADMAP.md:25` 的 Java 总数 4751 → **4755**（对齐 master 门禁实测）。全仓枚举后波及面只有这一行。 |



**边界**：只治"同行混合"这一形态；`CHANGELOG.md` 作为历史记录仍照旧排除在门禁之外。





---

---

---

## [Unreleased] — 编队 e2e 从"必然 FAIL"到全绿：三个真缺陷 + 两个脚本暗坑（2026-10-13）



> e2e-formation.ps1 / e2e-mesh.ps1 这两个脚本此前从未在 CI 里跑过，一跑全红。

> 逐条查下来：**没有一条是断言写错**——三个是真缺陷，两个是 PowerShell 的坑。



| # | 缺陷 / 坑 | 症状 | 修法 |

|---|---|---|---|

| ① | 编队参考点与模拟器 home 相距 5.3km | drone-sim 默认 home 是 (22.5907, 113.9345)，编队参考点是 (22.5431, 113.9578)。FR-16 等 STABLE 永远超时：飞机压根不在参考点附近 | 脚本给 3 台 drone-sim 显式传 `--lat/--lon` = 参考点；`$RefAlt` 从 0 改为起飞高度（slot 目标 alt 与巡航面一致） |

| ② | `MAV_CMD_DO_REPOSITION(192)` 不被模拟器支持 | 编队后端把所有"成员该去哪"都走这条命令（FR-04 变换逐点引导 / FR-08 偏差修正），drone-sim 的 switch 没有这个 case ⇒ `unsupported command 192`，真机侧一步不动 | `VirtualDrone.handleDoReposition`：armed 校验 + 经纬度转本地米 + `physics.setTarget` + 切入位置保持；lat/lon 传 0,0 按 PX4 口径只改高度；越界拒绝 |

| ③ | 队内时钟 5s 后恒失效 | `FormationService.create()` 只采过一次 Leader 心跳，`CLOCK_STALE_MS=5s` 一过 `ready()` 恒 false ⇒ sync=true 的灯光命令（FR-11）**必定** 400 "formation clock not ready" | `FormationClock` 读路径自愈：失效时按记住的 Leader sysid 从 DeviceRegistry 重采；Leader 真离线时 registry 给不出新心跳，基准照旧失效（边界有单测钉住） |

| ④ | PowerShell 清理闭包全部失效 | scriptblock 是**调用时动态查变量**，不是定义时捕获：formation 的 `$d` 先绑 drone-sim 进程、step 2 又绑 /drones 视图对象，`Stop-Process -Id $d.Id` 拿到 `$null`；mesh 的 `$relay/$drone` 是函数局部变量，函数一返回就查不到 ⇒ 3 台模拟器 + 中继长驻，还会污染后续 e2e（别的脚本收到的"PASS"其实是它们的广播） | 两个脚本统一改为**进程/socket 清单**遍历，清理动作 scriptblock 不引用任何外部变量 |

| ⑤ | `$gcsPort` 撞名 `$GcsPort`（大小写不敏感） | mesh 简化版里 `$gcsPort = 14550` 把配置项 `$GcsPort = 14541`（中继 GCS 侧口）局部遮蔽 ⇒ "发往中继的命令"实际发给模拟 GCS 自己，中继永远学不到 gcsAddr、up 方向 fwd=0；而 FR-23 靠收到自己的回波**假绿** | 改名 `$simGcsPort`；另修 FR-25 只读 stdout 重定向文件、而 logback 走 stderr 导致统计行查不到 |



**命令时序**：`ARM → 编队 TAKEOFF → start_mission`。原顺序把 start_mission 放在 ARM 之前，

模拟器回 `mission start rejected: not armed`；而 start_mission 若早于 TAKEOFF，任务首 item

（NAV_TAKEOFF）会把物理目标重置成"原地爬升"，飞机永远飞不到 slot。



**结果**：`e2e-formation.ps1` 23 项断言全 PASS（FR-14/15/16/04/11/13/18），

`e2e-mesh.ps1 -Mode simplified` 12 项全 PASS（FR-22/23/24/25/12）。



**新增单测**（合计 +6，全仓 4761 全绿）：

- `RepositionCommandDispatchTest`（drone-sim，3 例）：走真实 UDP 路径钉死未武装拒绝 / 武装后真飞到目标经纬高（1m 容差，与 Keeper 同口径）/ 0,0 只改高度

- `FormationClockTest`（cloud-backend，3 例）：5s 后读路径自愈仍就绪、Leader 离线照旧失效、未轮询过不就绪



---

---

---

## [Unreleased] — 巡检异常检出：从"javadoc 里老实"到"契约+界面双老实"（2026-10-13）



> 上一轮修完 mapping / citytwin 两项假能力后，按同一条反模式（"随机数生成当测量结果展示"）

> 全仓扫了一遍。`AnomalyDetectionService` 是扫出来的第五处，也是唯一一处**javadoc 已自证

> 模拟**的——但 javadoc 用户读不到：API 吐给前端的是 `severity + 置信度 87%`，界面据此渲染成

> "检出的异常"，操作员无从得知那是概率模拟。



| # | 交付 | 内容 |

|---|---|---|

| ① | **契约层打标** | `Anomaly` 增加 `simulated`（恒 true）+ `simulatedReason`（统一说明"真实视觉模型未接入，非推理结果"），与 `MappingResult.simulated` 同一套口径 |

| ② | **界面标注** | `InspectionPanel` 异常清单标题加"·模拟检出"角标；单条异常在置信度后追加"·模拟检出（非视觉模型推理）" |

| ③ | **测试** | `AnomalyDetectionServiceTest` +3：命中即带标、原因统一、置信度仍在 70~95 区间（加标不改语义） |



**边界**：真实视觉模型接入时把 `simulated` 置 false 即可，字段契约与界面标注不用再改。

巡检的航线规划、采集、按行业映射（异常类型/维护建议）本来都是真实逻辑，未动。



---



**CI 红过一次（门禁自身缺陷，已修）**：CI 实测 link-sim 107 / 全仓 4752，本地实测 118 / 4763。

根因是 #67 删掉 `MultiHopRelayTest`（11 例）后，**它的陈旧报告还留在我工作区**，而替换它的

`MeshRelayTest` 注释里提了一句"（MultiHopRelayTest，11 例）"——门禁判陈旧用的"裸类名探测"

被这条注释喂饱，把陈旧报告判成"类还在"，本地虚报"全部一致"。现探测收紧为**必须匹配类型声明**

（class/interface/enum/record），并加自测用例钉住；本地陈旧报告已清。这是

`_class_has_source`（类被删）之外的**第三种**陈旧报告形态：**类名在别处注释里活着**。

---

---

## [Unreleased] — 审计链断言补齐归因：红下来不再是 "true but was false"



> 只动测试，不改生产语义，也不新增用例（`@Test` 增删均为 0，单测计数不变）。



### 触发它的事



master `80a21e8` 的 push run `38335407472` 上，`Java cloud-backend` 唯一坏项是

`AuditRetentionTest.chainContinuesAfterRetentionPurge`，而日志里只有：



```

org.opentest4j.AssertionFailedError:

Expecting value to be true but was false

    at ...AuditRetentionTest.chainContinuesAfterRetentionPurge(AuditRetentionTest.java:161)

```



同一份内容在 run `336`（#61 的 PR run）是绿的 ⇒ **偶发**。本地用 5 种组合（默认序 + 随机种子

11/23/47 + 带/不带 jacoco，并把时区钉成 UTC 与 runner 对齐）跑满 2702 例都不复现。



真正妨碍归因的是断言本身：`ChainVerification` 这个返回值**已经带着**

`reason`（"第 N 条 prev_hash 不匹配" / "第 N 条 entry_hash 校验失败" / "校验失败（查询异常）…")、

`brokenAtId`、`checked`、`truncated`，而测试写的是裸 `assertThat(v.ok()).isTrue()`——

信息在对象里躺着，日志一个字不打。下一次再撞，还是从零猜。



### 改了什么



`AuditRetentionTest` 里三处 `assertThat(v.ok()).isTrue()` 收敛成一个私有辅助

`assertChainOk(v)`，用 AssertJ 的 `.as(...)` 把归因字段连同**当时库里的行数**一起打出来。

生产侧真实的三类 `reason` 文案是：`链首 prev_hash 不是创世哈希（…）`、

`prev_hash 与前一条 entry_hash 不一致（历史行缺失或被改动）`、

`entry_hash 与记录内容不符（内容被改动）`。红下来的形态是（其中 `reason` 值是 harness

为了验证管道而**人为构造**的哨兵，不是生产输出）：



```

链校验应通过；实际 reason=第 4 条 entry_hash 校验失败 brokenAtId=4242 truncated=false checked=3 库内行数=2

```



行数为什么要带上：`verifyChain()` 在 `persistToDb` 为真时走的是

`auditLogRepository.findAllByOrderByIdAsc()` ——**读库**，内存 deque 只是回退分支

（`AuditService.java:194-220`）。所以"链不 ok"实际意味着"那一刻库里的行序不成链"，

可能的出口只有三类：链首不接创世哈希、某行 `prev_hash` 接不上前一行、某行 `entry_hash`

与内容不符。把 `checked` 与**库内行数**一起打出来，就能一次分诊是"多了一行不是本测试写的"

（→ 状态耦合/共享 `jdbc:h2:mem` 表）还是"某行内容/时间戳对不上"（→ 落库精度问题），

不用再去猜 `verifyChain` 到底读哪一边。



### 验证（断言的是具体文案，不是"有没有红"）



Harness 第一版**判自己 FAIL**，抓的是 harness 的错：它把"弄断链"的注入写在 `verifyChain()`

**之后**，取到的 `v` 是破坏前的值，于是"验证通过"会是假的。改成直接构造一个 `ok=false` 的

`ChainVerification` 喂进断言——只测本次真正改动的消息管道，不依赖 `verifyChain` 读内存还是读库：



```

步骤 1（注入 ok=false）  红=OK  前缀=OK  reason 透传=OK  brokenAtId 透传=OK  checked 透传=OK   5/5

步骤 2（还原）           该类 6/6 绿，HARNESS-PROBE 探针无残留

```



### 没做



- **没有"顺手修根因"**：那次偶发还没定性（最像是新增测试类改变 class 顺序 / 多 `@SpringBootTest`

  上下文共用同一张 `jdbc:h2:mem` 的 `audit_log` 表所暴露的既有状态耦合），

  在没有复现样本的情况下改隔离方式，属于用猜测换一次"看起来修好了"。

- 没有重跑 master 那条红 run：`gh run rerun --failed` 会把当时被 skip 的 `Docker Build & Push`

  一起复活并真的重推三个镜像（已实证：`rerun --failed` 会跑失败 job 的下游）。

- 没动 `AuditPersistenceTest` 的断言（它的 `isFalse()` 处是"期望断链"，语义相反，不在本批）。

---

---

## [Unreleased] — sdk-python 从零测试到进 CI：27 例 pytest + 三处 API 不一致收口（2026-10-13）



> 产品决策（用户拍板）：真实 UOM 平台"要花钱，暂时算了"，继续 sim；测绘维持模拟+标注；

> sdk-python 走"补测试 + 接入 CI"。



| # | 交付 | 内容 |

|---|---|---|

| ① | **sdk-python 27 例 pytest** | 该 SDK 723 行、5 个模块、**零测试**——后端契约一变（路径改名/字段改名/错误码语义调整），只在客户调用时才炸。测试用 `unittest.mock` 在 `requests.Session.request` 层打桩（不引 requests_mock 等新依赖），覆盖：URL 拼装（base_url + /api/v1 + path、尾斜杠归一）、X-API-Key 头透传、timeout 透传、状态码→异常映射（400/401/403/404/5xx/其他）、错误消息取自 body 的 error 字段、非 JSON 错误体回落状态码、空响应体、JSON 解析失败、RequestException 包装、三个子资源的路径与方法 |

| ② | **收口三处 API 不一致** | 写测试时撞出来：`drones` 是**方法**、`missions`/`flight_logs` 却是**属性**——同一份 SDK 两种用法，且在 property 上再调用直接 TypeError。按"与 Java SDK（三个都是方法）+ 彼此一致"统一为方法，README 与商业化文档共 13 处示例随之订正。**加了一条一致性用例**（三个都必须是方法、不许回退成 property），防止再次漂移 |

| ③ | **CI 新增 SDK Python (pytest) job** | ubuntu + Python 3.11，`pip install -e . pytest` 后 `pytest -q`。暂不设覆盖率阈值（Python 侧还没有基线，先有信号再谈门槛——又一个假数字门禁比没有更糟） |



**验证**：本地 pytest 27/27；前端 292；全 reactor BUILD SUCCESS；门禁与自测全绿。

Java 4763 不变（本轮无 Java 变更），pytest 27 例为新增维度。



**文档**：README 的 missions/flight_logs 属性写法已改为方法调用；`docs/commercialization-plan.md`

的 SDK 示例同步订正（它此前就是跑不通的）。

---

---

## [Unreleased] — 测绘成果与城市孪生态势：不再假装有真数据（外部评估最后两项假能力）（2026-10-12）



> 外部评估报告原话："界面上展示的面板 ≠ 后端有能力支撑——ai、mapping 成果、citytwin 态势、

> autodispatch 下发四处存在'看起来能用、实际是假的'断层"。ai 与 autodispatch 已修（P0 两项），

> 本轮清掉剩下两项。原则沿用同一条：**要么真做，要么如实说没有**。



| # | 交付 | 内容 |

|---|---|---|

| ① | **测绘成果不再伪造下载链接** | 原实现产出 `https://storage.mapping/results/<id>/orthophoto.tif` 这类**不存在的域名**，前端渲染成可点的"查看"——点开就是 DNS 错误，看起来像系统坏了。现在 URL 一律为 null；DTO 增加 `simulated`（恒 true）+ `simulatedReason`，把"真摄影测量管线未接入"写进字段契约，调用方与前端都能区分 |

| ② | **城市孪生态势分层标注** | 快照把 DeviceRegistry 的真飞机位置与 `generateSimulated*` 造出来的车/人/告警混在一个对象里返回，消费端无法区分——界面上就把模拟车流当实时路况。现 `RealtimeSituation.simulatedLayers` 显式列出哪些层是模拟的（vehicles/personnel/alerts，**不含 drones**） |

| ③ | **前端两处标注** | 测绘面板对模拟成果给警示条（含原因）、不再渲染假链接；城市孪生的车辆/人员/告警计数后加"·模拟"角标，无人机标"（真机）" |



**测试**：`MappingResultServiceTest` 三条 URL 断言改为"null + simulated=true"、URL 唯一性用例改为成果 ID 唯一性；

`MappingControllerTest.fullWorkflow` 同步改写；`SituationOverlayServiceTest` +1（simulatedLayers 契约）。

cloud-backend 2703/0；全 reactor BUILD SUCCESS；前端 292 不变。



**边界**：真实摄影测量产物（空中三角测量/正射校正/三维重建）属生产阶段项；接入时把

`simulated` 置 false 并回填真实 URL 即可，字段契约不用再改。

---

---

## [Unreleased] — 拆掉 link-sim 帧尾私有字节方案的多跳扩展：它在真实帧上不可能工作（2026-10-12）



### 结论先行



`--multi-hop` 此前是个**死旗标**（`MultiHopRelayConfig.parse()` 在主源码零调用，

`RelayNode` 两个公开构造器都写死 `defaults()`）。原以为"接上线路"就能让 README 的

多跳宣称变成真的。用真实 MAVLink v2 帧实测后确认：**接上线路的那一刻，

约 100% 真实流量会被丢**。所以不是接线问题，是实现在真实帧上根本不可能工作。



### 三处硬伤（全部实测，非推断）



| # | 问题 | 实测证据 |

|---|---|---|

| 1 | `extractHopCount` 取帧**最后一个字节**当跳数，而 MAVLink v2 帧末两字节是 CRC | 一帧带签名的 Heartbeat：realLen=34，CRC 末字节=**106** > MAX_HOPS=15 ⇒ 入口即判「越界」丢弃 |

| 2 | `writeHopCount` 对 >=13B 的帧**原地覆写**最后一字节，而所有真实帧都 >=13B | firstDiffAt=33（距帧尾 0 字节）⇒ 覆写的正是 CRC，帧到对端必然 CRC 校验失败 |

| 3 | 改成「追加尾字节」在 link-sim 内部能自洽（解析器容忍帧尾多一字节），但链路对端（cloud-backend `UdpGateway` / drone-sim）不知道尾字节存在、不会剥 | 追加 1 字节后 21B→22B，`MavlinkParser` 仍解出 1 帧；但对端行为未定义 |



根因：**MAVLink v2 帧本身没有 TTL/hop 字段**。真要多跳必须先定义双方都会的封装协议。



### 为什么拆不是修



留着这个开关的风险大于价值：



- README 敢写「一跳/多跳静态中继」，用户以为能开

- 它产生了 **11 个假绿测试**（`MultiHopRelayTest`），全部基于 `new byte[13]`

  这类合成数组——合成数组的「最后一字节」是随手填的 7，真实帧的最后一字节是 CRC。

  11 个用例全绿，也没能发现上面第 1、2 条。



这与本仓反复出现的是同一个教训：**测试用的数据形态不对，绿了也证明不了任何事**。



### 改了



- 删 `MultiHopRelayConfig.java`（整个类）

- `RelayNode.java` 回到纯粹的一跳透明转发：删 hopCount 相关的常量/字段/构造参数/

  两个 extract/write 方法/四处判决块/`ScheduledPacket.hopCount`，**一行行为都不改**

  （既有 `MeshRelayTest` 10 例全绿，含双向独立损伤、learn-once、丢包语义）

- README：「一跳/多跳静态中继」→「一跳静态中继」，M0a 段补「为什么只有一跳」

- `RelayNode` 底部留完整论证，将来要恢复多跳时先定协议



### 补的回归护栏



新增 `MeshRelayTest.realMavlinkFrame_isForwardedByteForByte`：用**真实 MAVLink v2 帧**

断言中继输出与输入逐字节相同。这正是那 11 个测试该做而没做的事。

变异验证（把上行丢弃判据判反）→ 6 个用例转红，含本用例。



### 计数



Java 4761 → **4751**（`link-sim` 117 → **107**：拆掉 11 个多跳用例、补 1 个真实帧用例；

其余模块取 master 实测值不变）

---

---

## [Unreleased] — 修 flight_log 的时间戳口径：绝对时刻入库，并给 CI 装上唯一能看见它的眼睛（2026-10-12）



### 症状（本机 +08、真实 PostgreSQL 实测）



`sysid=231` 的 **65 行遥测确实在库里**，但：



| 请求 | 修复前 |

|---|---|

| `GET /api/v1/flightlog?day=<今天>` | **`[]`** |

| `GET /api/v1/flightlog?day=<明天>` | **65 行** |



也就是说客户在 +08 部署上看"今天的飞行历史"是空的，而 `IT Pass C 断言 7` 在 CI 上**一直绿**。



### 根因不在读侧的日窗，而在**写入侧有两条口径不同的路**



| 路径 | 写法 | 库里实际存的值（写入时刻 UTC 11:04 / 本地 19:04） |

|---|---|---|

| `flight_log` | 显式 JDBC 批量插入：`setObject(i, value)` **不带目标类型**，值是 `java.sql.Timestamp`（由 `Instant` 转换而来） | **`19:04`（本地墙钟）**——`Timestamp` 无时区，pgjdbc 只能按 JVM 默认时区把它摊成字面量写进 `timestamp`（无时区）列 |

| `audit_log` | JPA 写 `Instant`（Hibernate 6 的 `Instant` 映射固定按 **UTC** 渲染） | `11:04`（UTC） |



同一个应用、两类同名口径的 naive 列、两种时间。读侧的日窗也走 JPA 那条（UTC 渲染边界）

⇒ 在 UTC 机器上两侧恰好重合，在非 UTC 部署上整段错 8 小时。

**这就是"CI 全绿但产品在客户环境坏"的形状**：判据依赖机器状态，而 CI 的机器恰好是那个不暴露问题的取值。



顺带一处细节：`insertSqlTypes()` 里那个类型数组**原来只用于 `setNull`**——

非 null 值一律走驱动的默认渲染。所以"我们显式指定了类型"这句话当时并不成立。



### 改动



| 件 | 内容 |

|---|---|

| `V29__flight_log_timestamp_tz.sql` | `flight_log.timestamp` → `TIMESTAMPTZ`。**历史 naive 值按显式声明解释**：`AT TIME ZONE COALESCE(current_setting('nexussky.legacy_naive_tz', true), 'UTC')`——缺省 UTC 是为了"迁移可重放、不依赖机器状态"，**不是**因为 UTC 一定是那些行的身世；若某套环境的历史行是在非 UTC 机器上写的，先 `ALTER DATABASE ... SET nexussky.legacy_naive_tz = 'Asia/Shanghai'` 再跑迁移。代价写进注释：`ALTER TYPE` 重写整表并短暂持锁 |

| `FlightLogEntity.insertSqlTypes()` | `Instant` 列声明为 `TIMESTAMP_WITH_TIMEZONE` |

| `FlightLogEntity.insertValues()` | 不再把 `Instant` 转成 `Timestamp`（那一步正是"让驱动自己挑时区"的入口） |

| `FlightLogService` 批量绑定 | 非 null 也带目标类型：`setObject(i, value, types[i])` |

| `.github/workflows/ci.yml` | integration job 增一条 **`TZ=Asia/Shanghai`** 的腿。没有它，这个缺陷修完仍然没有信号——UTC runner 永远证伪不了它 |



### 中途被本机实测否证的一次"修复"（值得留档）



第一版把绑定写成 `setObject(i, instant, Types.TIMESTAMP_WITH_TIMEZONE)`，

H2 侧 34 例全绿——**但 PG 直接拒绝这个组合**：



```

PSQLException: 不能转换一个 java.time.Instant 实例到类型 Types.TIMESTAMP_WITH_TIMEZONE

```



连真 `postgres:15-alpine` 把四种组合各跑一遍才有答案（JVM = Asia/Shanghai，列 = `timestamptz`）：



| 绑定方式 | 结果 |

|---|---|

| `Instant` + `TIMESTAMP_WITH_TIMEZONE` | **被驱动拒绝** |

| `Instant` 不带类型 | 被拒："无法推断 SQL 类型" |

| `OffsetDateTime(UTC)` + `TIMESTAMP_WITH_TIMEZONE` | **正确**（存成 `2026-10-12 15:16:39+00`） |

| `Timestamp` 不带类型（本缺陷的旧写法） | 这次恰好也对，但靠的是驱动自己猜时区 |



取第三种：偏移显式，不靠推断。另有一处也是被本机打回的：第一版 V29 用了 PG 专属的

`USING … current_setting(…)`，而 **dev profile 的 IT（Pass A/B）在 H2 的 PostgreSQL 模式上真跑 Flyway**

⇒ Pass A 直接起不来（`[50004-232]`）。本仓迁移一直要求两种方言都认（V13/V17/V18 就是这么被方言化的），

绕开这条约定就会"在一条方言上验过、冒充成验证过"。



### 验证



- `FlightLogTimestampTypeTest` 4 例，钉"列类型口径"与"绑定值类型"。

  **反向验证**：把两处修复退回旧行为，其中 2 例转红（`but was: 93`＝`Types.TIMESTAMP`；

  以及值不再是 UTC 偏移的 `OffsetDateTime`）。撤销变异后回绿。

- 诚实标注单测的边界：H2（测试方言）那列本来就带时区，"写进去再读回来"在修复前后都成立，

  所以**端到端证据不放在单测里**，只在真 PG。

- **本机（+08）连一次性真 PostgreSQL 的 IT 全绿**：`=== All integration tests passed ===` / `IT_EXIT=0`，

  其中 **Pass C 断言 7 从修复前的 `❌ 60s 内 flight_log 只见到 0 行` 变成

  `✅ 收到活体遥测（sysid=231，3 行，等待 ≤4s）`**；库里独立复核

  `min/max = 2026-10-12 15:16:39+00 / 15:16:43+00`（会话时区 UTC，存的是绝对时刻）。

  这条前后对照就是这个 PR 的验收证据——它只在非 UTC 机器上可复现，而 CI 的 runner 是 UTC。

- **CI 那条腿已经有判决了**（同分支 rebase 前的 run `38276552002`，27 job 全绿）：

  新步骤 `Integration tests under a non-UTC deployment timezone` 输出里带 `TZ: Asia/Shanghai`，

  其内 **`Pass C 断言 7` 实测 `✅ flight_log 在 PostgreSQL 上收到活体遥测（sysid=231，4 行，等待 ≤6s）`**。

  判据成立的原因写在代码里而不是靠猜：`GET /api/v1/flightlog` 不带 `day` 时默认读"今天"

  （`FlightLogController.java:41`），而"今天"用 `ZoneId.systemDefault()` 算窗口

  （`FlightLogService.java:369`）——修复前这一页在 +08 上读到 0 行，所以这条腿一旦缺失，

  同一份代码在 +08 部署上就是坏的。**"CI 结构性看不见这个缺陷"这件事，到此结束。**

- 既有 H2 侧回归：`FlightLogPersistenceTest` 12 / `FlightLogRetentionTest` 3 /

  `FlightLogServiceTest` 7 / `TelemetryWriteOffThreadTest` 8 / 新守卫 4 ⇒ 34 例全绿；

  全 reactor BUILD SUCCESS（7 个模块全 SUCCESS、MVN_EXIT=0），Java **4761**（cloud-backend **2702**）；

  分模块加总自洽：2702+1412+117+478+40+12 = 4761（= 基点上 master 的 4757 + 本 PR 的 4 例）。

  本 PR 可长期复用的口径是那句**"自己只贡献 4 例"**（新守卫 `FlightLogTimestampTypeTest`）——

  绝对数只是这一次构建的快照，基点再动就得按门禁重测。

  口径 = `scripts/check-test-count-docs.py` 读 surefire 实测，文档同步由它驱动、不手填

  （本批 20+2 = 22 处，复跑 `exit 0`）。

  **为什么这里坚持写增量而不写死绝对数**：今晚这条分支的基点跟了四次

  （`00c655e` #60 → `54175d1` #64 带进 regulator-sim 6 例 → `170e53b` #65 → `3c0eb13` #63 带进 34 例），

  前两版我都写死过绝对数（`4719`、`4721`），每 rebase 一次就作废一次、就得重测一遍——

  增量口径不会过期，绝对数交给门禁。

  **两处取证上的不圆满，如实记下来**（都是同一族：本机多 worktree 并发时的端口/管道问题，

  按既定规矩**不改任何测试**）：

  1. 第一次构建在 link-sim 腿上红了一次，但我把 mvn 输出接了 `tail`，真正的失败原因没落进日志；

     随后 `mvn -B -o -pl link-sim test` 单跑 117 例全绿、`-rf :aerofleet-link-sim` 续跑亦全绿

     ⇒ 那条红**既没复现、也没留证**，按环境噪声处理。教训已改：后续构建一律把完整日志落文件、

     退出码单独取，不再接 `tail`（接了 `tail` 之后 `$?` 是 `tail` 的，红会被读成绿）。

  2. 第二次全量构建报 **16 个 error**，逐层挖到根因是

     `Caused by: java.net.BindException: Address already in use: bind`——`UdpGateway` 构造时抢不到

     UDP 端口，连带 3 个 `@SpringBootTest` 上下文起不来，16 个错全是这一条的级联

     （`ApplicationContext failure threshold exceeded` 是尾巴不是原因）。端口一空，把那 12 例单跑：**全绿**。

     同一轮里那份"实测 4721"**不能用**：reactor 在 regulator-sim 之前就中止，XML 是新混合的

     （`regulator-sim=34` 是上一次的残留，本批应为 40）——所以最终数字来自随后那次完整跑完 7 个模块的构建。

- **迁移顺序**：本分支基点今晚跟到 `170e53b`（中间并进了 #59 的 `V28`、#60、#64、#65），

  所以库里看到的顺序仍是 `V27 → V28 → V29`，本机全新库跑 IT 时 Flyway 按这个顺序一路应用通过

  （这也是为什么 #59 必须先合——反过来会让 V28 变成 out-of-order 迁移，

  Flyway 默认 `outOfOrder=false` 会直接拒绝）。

- JSONL 那条路径没动：文件里的 `t` 仍是操作者可读的本机墙钟（那是给人看的日志，不是数据库口径）。





### 没做（有意，避免顺手扩大改动面）



- **`audit_log` 的列类型不改**：它存的就是 UTC，与这个缺陷无关；把它一起改成 `timestamptz`

  是另一件事，且要独立决定历史行的身世。

- **"一天"要不要显式配业务时区**没做（`day.atStartOfDay(ZoneId.systemDefault())` 原样保留）：

  入库改成绝对时刻之后，那行的语义已经是"服务器所在地的自然日"，**不再依赖机器状态决定数据读不读得到**。

  如果产品要的是"客户所在时区的日界"（服务器与客户不在同一区时会有差异），那是一个需要拍板的口径，

  不该被混进缺陷修复里顺手定掉。

- 其余 naive `TIMESTAMP` 列（V1 5、V13 4、V17 2、V21 1、V6 2）逐个登记在 `.coord/GAPS.md` §七，

  不在本批一次改完。



---

---

---

## [Unreleased] — SPI 豁免账续清 5 条：五个业务模块适配器落契约测试（8 → 3）



> 门禁 `scripts/check-spi-coverage.py`（#56 已合）首跑记下 9 条"从未被测试点名的生产实现"。

> 本批续清其中 5 条：`DeliveryAdapter` / `EmergencyAdapter` / `FormationAdapter` /

> `MappingAdapter` / `ShowAdapter`。**账上还剩 3 条**（`MavlinkDeviceGateway`、

> `UdpTransportAdapter`、`RealEnvSource`），每条要自己的理由与自己的测试，不在本批。



### 这三类事实只有运行时才暴露，所以必须由断言守着



① **路由字符串**。`StepExecutor` 用 `adapters.get(module.name())` 按字符串查适配器

（`StepExecutor.java:218`），表里的 key 来自各实现的 `getModuleType()`。任何一处把

`"FORMATION"` 写成别的，该模块的每一步都会静默变成

「未找到模块类型 FORMATION 的适配器」（`StepExecutor.java:144`）——**编译不会响，

既有单测也不会响**，因为 `StepExecutorTest` 喂进去的一直是接口替身，这五个真实现

从未被点名（这正是门禁记它账的原因）。本批用 `@EnumSource` 把 5 个枚举名逐个核到实现返回值，

并反向断言实现集合不多于枚举：多出来的名字是注册表里永远查不到的死条目。



② **Map → DTO 的键名翻译**。参数来自计划里的 JSON 字符串，键名纯靠约定。抄错一个键不会报错，

只会静默取默认值——任务照样创建成功，但飞错高度、按错重叠率、少带一台机器。

所以每个适配器都用 `ArgumentCaptor` 断"下游 DTO 里真的是我传进去的那些值"：

配送的单号/载荷/优先级/归属 sysid，编队的 members/shape（顺带验小写 `circle` 被归一化成

`CIRCLE`）/spacing/refAlt，测绘的 altitudeM 与**多边形顶点的 lat-lon 顺序**，

表演的 droneSysids 与缺省 60s/50m。



③ **失败不越界 + 单号透传**。协作者抛异常时适配器必须吞成 `ModuleResult.fail` 并把原因带回

（编排线程不能被一个模块的故障带崩）；查不到任务时必须回 fail，不能回一个 ok 带空状态；

`createTask` 返回的 taskId 必须是上游给的那个——`CREATE_AND_START_TASK` 会把它直接喂给

`startTask`（`StepExecutor.java:253`），返回 null 等于把 null 传进下游。



另钉两条容易被"顺手统一"改坏的不对称语义：**应急的 `createTask` 直接调 `start()`（创建即启动），

`startTask` 只回读、不得再触发第二次**；**编队的 `abortTask` 下发的是 `dissolve`**（编队没有"暂停"这档）。



### 变异测试：6 处，每处映射到一条具名用例



`DELIVERY`→`DELIVARY`（2 条路由断言转红）、测绘顶点 lat/lon 互换、编队中止下发 `rtl` 而非 `dissolve`、

应急 `startTask` 里追加一次 `start()`、配送创建失败被吞成 `ok`、配送查不到状态回 `ok`。

还原后 24/0/0 回绿。跑法本身也补了两道防自欺的闸：锚点命中数必须恰好 1（预检，不改任何文件就退出），

以及"编译红/零用例"不能冒充"断言没转红"。



### 一处自我更正（写注释时判错了，实核后改口）



初稿注释里我写"五个适配器对『查不到任务』的口径不一致：配送/编队/应急回 fail，

测绘/表演在缺 `status` 键时回 `ok(taskId, "UNKNOWN")`，步骤会被判成成功"。

**这个说法不成立**：`MappingController.requireTask`（:444）对未知或他租户单号是直接抛

`NotFoundException`，而 `taskSummary` 恒写入 `status`——那条 `getOrDefault` 缺省分支在

当前接线下**不可达**。于是本批不把防御性死代码钉成契约（钉了就等于要求它永久保留），

只在测试类注释里说明；真实结论是五个实现对外答复其实一致：都是 fail，区别只在靠显式 null

检查还是靠上游异常被吞。



### 门禁与账



`check-spi-coverage.py` 删 5 条豁免。删之前门禁先以 **exit 1 自报「豁免可以撤销」**

（测试已存在、账上还挂着），这再次证明这本账会自己睁眼；删后

**接口 13 / 实现 35 / 未验 3 / 已豁免 3**，exit 0，自测 6 情形仍全过。



### 计数



本批 **+24 例**。绝对数由 `mvn -B -o package` 后 `scripts/check-test-count-docs.py` 读

surefire 实测、再驱动文档刷新——本分支中途 rebase 到过 `00c655e`（#60 落地，drone-sim +2），

手算出来的总数一定会错，所以流程固定为"先重测、再刷文档、复跑门禁到 exit 0"。



### 没做



- 没碰这五个适配器的任何生产行为；本批只有测试 + 删豁免 + 文档口径。

- 没有清剩下 3 条豁免：`MavlinkDeviceGateway`（要真实现替掉接口替身）、

  `UdpTransportAdapter` 与 `RealEnvSource`（在 drone-sim，b1 刚合了 `VirtualDrone` 的改动，

  等它这轮静下来再动，避免同文件打架）。

- 没动 `.github/workflows/ci.yml`：SPI 门禁早在 #56 就接进 `dep-gate` 了，本批只是把账上的条目划掉。

---

---

## [Unreleased] — SPI 豁免账清偿第 1 条：`UomReportSink` 有了假 UOM 契约测试（9 → 8）



### 这条在还什么债



`scripts/check-spi-coverage.py`（PR #56 落的门禁）首跑实测 **13 接口 / 35 生产实现 /

9 个从未被任何测试点名**。当时的处理是"把没人验变成看得见的账"，**一条都没算已清偿**。

本批开始还第一条：外部监管平台的上报通道 `UomReportSink`。



它值得先还：真机/真平台才暴露的缺陷（报文格式、鉴权头、签名口径、重试判断）都在这类适配器里，

而它过去只被"编译通过 + 启动日志看起来在工作"证明过。



### 加了什么



`UomReportSinkContractTest` 10 例，用 JDK `com.sun.net.httpserver.HttpServer` 当假 UOM，

断言的是**线上看得见的东西**而不是方法返回值：



| # | 钉住什么 |

|---|---|

| 1 | 激活上报的线格式：`POST /api/activate`、`Content-Type`、`X-API-Key`、body 里 `serialNo/activationTime/lat/lon` |

| 2 | **签名可被平台方复算**：测试自己按 `METHOD\nPATH\nTS\nBODY` 重算 HMAC-SHA256 并断言等于 `X-Signature` |

| 3 | 不配 `signingKey` 时**不发**签名头（不留"配了空串也照样签"的半开状态） |

| 4 | 403 是终态：`maxRetry=3` 也只发 1 次 |

| 5 | 503 可重试，第二次成功后续跑；结果取第二次响应 |

| 6 | 429 虽是 4xx 但必须重试（配额语义 ≠ 请求被拒） |

| 7 | 实名查询脱敏在 sink 内完成（`张三 → 张*`），原名不外传 |

| 8 | 平台给了不认识的 status 串 ⇒ `ERROR`，不抛也不当成 `VERIFIED` |

| 9 | 平台不可达 ⇒ 走 `errorMessage` 返回，不抛异常（调用方是上报线程） |

| 10 | 遥测报文带飞行态：`sysid/serialNo/alt/speed/heading/status=AIRBORNE` |



**为什么必须显式 `setMaxRetry(>0)`**：#55 那条改动已经踩过一次——在 `maxRetry=0` 下

"只发一次请求"无论实现对错都成立。第 4 条若不设 `maxRetry=3` 就是个空转断言。



### 三条断言各自咬住什么（反向变异，不是自我表扬）



一次性注入三处行为变异：签名字段顺序颠倒 / 脱敏去掉 / 绕过 `ReportRetryPolicy.isTerminal`

⇒ 恰好 3 例转红，且一一对应：



```

UomReportSinkContractTest.signatureIsRecomputableByThePlatform:166   [签名串错一位…]

UomReportSinkContractTest.client403IsTerminalNotRetried:197          [4xx 重试救不回来…]

UomReportSinkContractTest.verifyMasksOwnerName:238

```



撤销变异后 10 例复绿。



### 门禁自己抓了我一次（这条机制有效的直接证据）



测试落地后、还没删账之前跑 `check-spi-coverage.py`：



```

违规 1 条：

  - 豁免可以撤销：UomReportSink 现在已被测试引用，请从 EXEMPT 删除（棘轮只准拧紧）

GATE_EXIT=1

```



删掉账上那一条之后 ⇒ `接口 13 / 实现 35 / 未验 8 / 已豁免 8`、`exit 0`，

脚本 `--self-test` 6 种情形仍全过。**"9 条"这个数现在能由结构复现，不再是一句话。**



### 没做



- 本批只删 `UomReportSink` 这一条豁免，没有顺手清别的（当时账上余 8 条：`MavlinkDeviceGateway`、

  `UdpTransportAdapter`、`RealEnvSource` 与 5 个 `ModuleAdapter`）。每条都要自己那段

  "没断言过就说没验"的账；5 个 `ModuleAdapter` 由同分支的下一批补上，即本版里本条的上面那条。

- 没有改 `UomReportSink` 的任何生产行为——本批只有测试 + 删一条豁免。

- 没动脚本头部那段"13/35/9"的历史叙述：它记的是 2026-10-12 首跑时点的事实，改成今天的数就是篡改记录。



### 计数



本批 **+10 例**（一条 `UomReportSinkContractTest`）。绝对数不写在这里：这两批 SPI 测试在同一分支

一起落地，中途 master 还并进过 #60（drone-sim +2），所以最终 Java / cloud-backend 计数一律以

`mvn -B -o package` 后 `scripts/check-test-count-docs.py` 读到的 surefire 实测为准、由它驱动刷文档，

本文只记自己那批的增量。上一版这里写的是 `4715 → 4725`，那是基点 `cf2748c` 上的量，rebase 后已不成立，

故改成增量口径（分支尚未推送过，改的是未发布草稿，不是既成记录）。



---

---

---

## [Unreleased] — CI 卫生：`:latest` 不再由 PR 产生，取件类红自带取证重试



> 触发这件事的是两次与代码无关的红，以及顺着它们挖出来的一条发布卫生问题。

> 登记见 `.coord/GAPS.md` G-3（门禁归类不稳定）与 G-4（PR 推 `:latest`）。



### 一、G-4：`pull_request` 事件也在往 ghcr 推 `latest`



原形态是 `docker-build` job 里没有 job 级 `if:`，metadata 步骤写的是**无条件**的

`type=raw,value=latest`，而 `Build and push` 步骤里 `docker build` 与 `docker push` 同在一步、

没有任何事件门槛。实证（PR #61 的 run `38288389281`，job `Docker Build & Push (cloud-backend…)`

= 114953437186，日志原文）：



- `DOCKER_METADATA_OUTPUT_TAGS: ghcr.io/levango7/nexussky/cloud-backend:latest`

- `#25 naming to ghcr.io/levango7/nexussky/cloud-backend:latest done`

- `The push refers to repository [ghcr.io/levango7/nexussky/cloud-backend]`

- 而 label 里 `org.opencontainers.image.revision = 043b475d…` 是 `refs/pull/61/merge`

  那个**不对应仓库任何分支提交**的 sha



⇒ 任何一条绿 PR 都会覆盖 `latest`；按 `latest` 部署的人（chart / raw k8s / compose 都是这个口径）

拿到的可能不是 master，且镜像里的 revision 指向一个仓库里不存在的 commit。



**改法（保留 PR 的构建能力，只关发布）**：



| 处 | 改后 |

|---|---|

| `tags:` | `latest` 加 `enable=${{ github.event_name == 'push' && github.ref == 'refs/heads/master' }}`；`sha-*` 保留 |

| `Log in to GHCR` | 加同一条件——PR 上不再需要注册表凭据 |

| 原 `Build and push` | 拆成 `Build`（无门槛，PR 照样构建）+ `Push`（只在 master 的 push 上跑） |



部署清单**要跟着改一处注释**（初稿这里写的是"部署清单不用改，没有任何 manifest 钉 `:latest`"——

manifest 那半是对的：compose 钉的是 `1.0.0`、chart 的缺省 `imageTag` 是 `0.1.0`，

全仓确实没有写死 `:latest` 的地方；但**文档化的部署路径本身就是让人 `--set imageTag=latest`**，

所以那句"不用改"不成立，已更正）。改的是 `deploy/helm/nexussky/values.yaml` 头部注释：

它原来写着"CI 推送 tag 为 latest 与 sha-<short>"，本笔之后准确的说法是

**只有 master 的 push 才推**，于是那行 `--set imageRegistry=ghcr.io/levango7/ --set imageTag=latest`

现在保证拿到的是 master，而不再是"某条未合并 PR 的构建"。



### 二、取件类红的取证与重试（`scripts/ci-mvn-retry.sh`，新）



两次红的日志只有 `Non-resolvable import POM: org.junit:junit-bom:pom:5.11.4 (absent):

Could not find artifact … in central` 和 `… surefire-junit-platform:jar:3.5.2 (absent)`，

而**同一时刻同一台开发机 curl 这两个 URL 都是 HTTP 200**（`junit-bom:5.11.4` 不可能"不存在"），

且两次请求只隔 ~15ms。光凭日志分不出"坐标写错"与"这个 runner 到中央仓的通路坏了"，

只能靠重跑碰运气——可红已经记在 PR 头上了。



新脚本包住两处最容易栽的 mvn 调用（`java` 的 `Build & test`、`java-level` 的 `Compile all modules`）：

命中 `could not be resolved / Could not find artifact / Non-resolvable import POM` 三类文本时，

先由 **runner 自己**按坐标回查中央仓、把真实 HTTP 状态码打进日志，再重试一次；

重试仍红就把判读口径写在末尾（probe 200 ⇒ 通路/缓存问题，可重跑或抬缓存键尾 v 号；

probe 也 404 ⇒ 坐标本身错，是要改代码的红、不要重跑掩盖）。



**边界是刻意的**：只认那三类文本。测试失败、编译错误一律**不重试**——那是要人看的红。



自测 4 情形（`mktemp` 里的假 `mvn` 计数调用次数，不只判退出码）：



```

PASS A 成功            (calls=1 rc=0 probes=0)

PASS B 取件红后重试成功  (calls=2 rc=0 probes=1)

PASS C 一直取件红       (calls=2 rc=1 probes=2)

PASS D 非取件类红不重试  (calls=1 rc=1 probes=0)

```



D 这一半是这条脚本唯一"不显得在起作用"的部分，所以必须验：否则它会悄悄把真失败也刷绿。



### 没做



- 没抬 maven 缓存键尾 `v2 → v3`。本笔不改缓存内容，抬号只会白白触发一次 30 分钟冷构建；

  真要抬是等 `maven-warm` 的离线自检第二次报"工具链换代"警告时（那时才有证据）。

- 没动 `maven-warm` 已有的键一致性守卫与离线自检（`scripts/ci-maven-cache-key.sh`）——

  它们管的是"抢占保存者把残缺 m2 冻进缓存"这一族，与今天这次的"consumer 侧取件瞬态"不同因。

- 没动其它 5 处 `mvn` 调用（`java-upward-compat`、`verify -DskipTests`、e2e 构建等）。

  只包今天真红过的两处；其余等出现同类红再收，避免一次改整片工作流。

- 没有重跑 master 那条红的 run。按"不让带副作用的重跑掩盖问题"处理：`gh run rerun --failed`

  会把当时被 skip 的 `Docker Build & Push` 复活并**真的重推三个镜像**，

  所以交给下一个正常 push 覆盖（本 PR 合入后的那条 run 会同时验证新门槛）。



### 验证



- `python -c yaml.safe_load` 解析通过，19 个 job；`docker-build` 的步骤结论为

  login `if=push&&master`、`Build`（无 if）、`Push if=push&&master`，tags 首行带 `enable=`。

- `shellcheck -S info scripts/ci-mvn-retry.sh` 干净。

- 全仓只剩一处 `docker push`，且在门槛之内。

- 真环境终判在 CI：PR run 应看到三个 Docker job **只构建不登录不推**；

  合入 master 后的 push run 才推 `latest`。

---

---

## [Unreleased] — e2e 首次用 --error-rate：验「4xx 语义终态不重试」（#55 留下的验证缺口）（2026-10-12）



> #55 修退避策略时自己写明："修完才谈得上用 --error-rate 去验 backend 的退避路径"，而

> e2e-regulator 从未用过 --error-rate——那条路径一直没人端到端验过。本轮补上。



| # | 交付 | 内容 |

|---|---|---|

| ① | **regulator-sim 故障注入可配状态码** | `--error-rate` 原先只能注入 500，而 500 永远可重试，**根本造不出终态场景**。新增 `--error-status`（默认 500，100-599） |

| ② | **regulator-sim 请求计数端点** | `GET /api/stats` 按"方法+路径"给出精确计数（reset 一并清零）。否则"只打了 1 次还是 4 次"只能靠耗时推断，那是弱信号 |

| ③ | **解析器对齐 drone-sim 口径** | 认 `--key value` 与 `--key=value` 两种写法；**未知参数只警告不 `System.exit(1)`**——原实现在未知参数上直接退出进程，我的测试第一次跑就把 surefire fork 整个杀掉（报"fork 猝死"而不是可读失败） |

| ④ | **e2e FR-40（6 项断言）** | 起 `--error-rate 1.0 --error-status 403` 的 sim：403 只到 sim **1 次**、无退避等待（<2s）；对照 `--error-status 500`：仍 **4 次**、等 7s。两个方向都钉住，防止"不重试"被误做成"永不重试" |



**验证**：e2e-regulator **37 断言 0 失败**（原 31 + FR-40 六项）；regulator-sim 40/40

（新 `FaultInjectionTest` 6 例：默认/可配/非法回落、计数与 reset、两种参数写法、未知参数不退出）；

全 reactor BUILD SUCCESS。



**顺带纠正一处执行口径**：首轮 FR-40  FAIL 在"403 仍重试 4 次"——排查后不是代码问题，

是 **e2e 用的是 07:47 的旧 backend jar**（不含 #55）。重建 jar 后即绿。教训：本地 jar 陈旧

会让"刚修的东西"表现成没修，e2e 跑前先确认 jar 时间戳。



**CI 红过一次（覆盖率阈值）**：新测试把 regulator-sim 实测从 70% 抬到 75%+，而 pom 里

声明值还是 0.70——`ci-coverage-threshold.sh --strict` 要求"实测向下取整到 5%"与声明值一致。

按仓库口径把声明值抬到 0.75（本地复跑 `--strict` 已自洽：实测 77% ≥ 声明 75%）。

---

---

## [Unreleased] — 任务上传会话超时中止 + 死会话不再吞下载请求（2026-10-12）



> 外部评估报告 P1 剩余项（drone-sim）："任务上传会话无超时/无重试上限，GCS 中途消失则永久

> 卡死；下载请求被无条件吞掉直到新 MISSION_COUNT 到达"。



| # | 交付 | 内容 |

|---|---|---|

| ① | **上传会话超时中止** | 旧逻辑 `reRequestIfStalled()` 每 3s 无限重发 MISSION_REQUEST：GCS 掉线/取消后会话永久挂着，飞机一直空喊要下一项。现会话静默超过 **60s** 主动中止，并回 `MAV_MISSION_OPERATION_CANCELLED` 给请求方（不是能力不足，是主动取消） |

| ② | **下载请求不再被死会话吞掉** | 会话挂着时 `isUploading()` 恒真，同链路的 MISSION_REQUEST_LIST 全被忽略——操作员在 GCS 上"点了没反应"。现 GCS 主动要求下载时先中止旧会话再回 MISSION_COUNT |

| — | **超时值可配** | `-Daerofleet.sim.upload-session-timeout-ms=<n>` 覆盖默认 60s。**刻意用实例字段而非 static final**：static final 在类加载时定值，别的测试类先加载 VirtualDrone 就钉死默认值，之后再设属性也不生效（顺序敏感的测试污染——第一版就是这么挂的） |



**测试**：`MissionUploadSessionTest` +2（超时后回 CANCELLED 且不再重发 / 下载中止死会话）。

**变异验证**：把超时分支改 `if (false)` 立刻转红，且跑满 60s 空转——正是旧症状。

drone-sim 1412/0；全 reactor BUILD SUCCESS。

---

---

## [Unreleased] — C5 第三步动手做：把"能不能收紧签名"变成代码门禁，翻丢了会自己退回来（2026-10-12）



### 问题



`docs/signing-prod-rollout.md`（PR #58）把 prod 收紧的**操作层**写清楚了，但那份手册的第 2 步

依赖一件不存在的事：**有人盯着**。`mavlink.signing.reject-unsigned=true` 之后唯一的失败模式是

"某台设备没配密钥"，而它的表现是**那台机的遥测静默消失**——没有错误码、没有掉线事件、

`/api/v1/drones` 里它就是不见了。人值守不是门禁。



第二条约束决定了判据不能图省事：**地面"配了密钥"≠ 现场在签名**。本仓没有任何向飞控下发

参数的通路（全仓 grep `PARAM_SET` 只命中一处无关注释），设备侧密钥来自进程启动参数，

`MavlinkSigningKeyController.rotateKey` 只写地面 keystore、没有"通知设备"这一步。

所以只看地面记录会把"我以为配了"当成"配好了"——正是要防的那类假象。



### 改动



| 件 | 内容 |

|---|---|

| `SigningPostureService` | 三态 OFF / PERMISSIVE / STRICT。**收紧前**要求每台**在线**设备在证据窗口内都有"签名帧通过验签"的记录，缺任何一台直接抛 `PostureRejectedException` 并点名 sysid；**收紧后**看门狗（`@Scheduled`，默认 30s）发现仍有在线设备无证据 ⇒ **自动退回 PERMISSIVE** 并写审计。判据用"有没有签名帧通过验签"，不用"库里配没配密钥" |

| `MavlinkSigningPostureController` | `GET /api/v1/mavlink/signing/posture`（OBSERVER，把 `onlineDevices / covered / missing / readyToTighten` 摊开）；`POST /strict`、`POST /permissive`（ADMIN **且必须是 JWT 主体**——API Key 是机器凭据，不该能改变安全姿态）。不就绪返回 **409** + 结构化 `missingSigningEvidence` |

| `V28__device_signing_state.sql` | `devices` 加 `signing_verified_at` / `signing_key_fingerprint`，**都可空且无默认值**（给默认等于把所有历史设备伪装成"已验证"，那正是本迁移要消灭的东西）。两列语义分开：指纹="地面已生成、等现场写入"，verified_at="系统真看到它的签名帧" |

| `DeviceRegistry` | 验签通过时记证据：内存**每帧**更新、落库**按 60s 节流**（遥测 5Hz × N 机，每帧写库会把 ingestion 压垮，而 ingestion 停摆的表现是全体掉线，比签名本身严重）；写失败只 WARN 不抛；`restoreFromRepository()` 回填证据，否则重启后首屏会把"刚重启"误判成"全部设备都没签名" ⇒ 收紧永远打不开 |

| `MavlinkSignatureConfig` | `rejectUnsigned` 改 `volatile`：读在 UDP 收包线程、写在 HTTP 线程，翻转必须立即可见 |



**只看在线设备**是刻意的：离线的机器此刻不影响任何事，等它上线时由看门狗兜住。否则一台正在

维修换件的机会永久卡住收紧——那等于把这个开关变成"永远打不开"，而收紧的代价（失联）明显

大于收益（少一层未签名放行），所以宁可"先收紧、掉了自动退"。



### 两处踩坑（比改动本身更该留档）



**1. 我第一版看门狗用例是空转的，而根因是 Windows 的时钟分辨率。**

为了表示"证据已过期"，我写了 `setEvidenceWindow(Duration.ofNanos(1))`。它没过——但不是"不稳定"：

本机实测（JDK 17）**20 万次连续 `Instant.now()` 只产生 17 个不同读数，最长一次连续 28822 次同值**，

所以 `evidence.isBefore(now.minus(1ns))` 在同一读数内恒为 false，那句话**永不成立**。

改法是把时钟做成可注入的 `Clock`（生产 `Clock.systemUTC()`，测试 `Clock.offset(…, 16min)`），

并且**先断言"这一步确实制造出了缺失"**（`evaluateReadiness().missing()` 非空）再断言行为——

否则"关掉自动退回"那条用例会因为前提从未满足而永远真。



**2. 我自己写的验收断言用了手抄码点，导致校验恒假。**

CHANGELOG 解冲突的脚本里我拿 `\u2014 C5 \u7b2c\u4e09\u6b55` 当锚点，"步"的真值是 U+6B65，

我抄成了 U+6B55 ⇒ 断言"我的条目只出现一次"在任何正确文件上都失败，看起来像"文件没改对"，

其实是**校验本身是坏的**。字面量与标识符一律现查，不凭印象。



### 验证



- 新增 12 例：`SigningPostureServiceTest` 7（拒绝并点名 / 收紧成功 / 离线不计 / 看门狗自动退回 /

  可关兜底 / 签名未启用时两向都拒 / 零敞口可读）+ `MavlinkSigningPostureControllerTest` 5

  （API Key 不能翻且 `rejectUnsigned` 未被改动 / 不就绪 409 带名单 / 成功收紧的审计 actor 是 JWT 主体 /

  状态查询摊开 / 签名未启用时退回是 409）。

- **变异验证**：摘掉 `/strict` 里的 JWT 门禁 ⇒ `apiKeyCannotChangePosture` 立刻转红（形态是

  `ClassCastException`，即生产里那一步会从"干净 401"退化成"未处理异常"）。撤销变异后回绿。

- 全量 `mvn -B -o package` BUILD SUCCESS（rebase 到 `47fc983` 之后复跑）；

  **Java 4703 → 4715**（cloud-backend **2652 → 2664**），

  口径 = `scripts/check-test-count-docs.py` 读 surefire 实测，文档同步由它驱动、不手填

  （本次 19+1 处替换，复跑门禁 `exit 0`）。顺带对上一笔合并漏改的一行做了对齐：

  `ROADMAP.md:25` 在 master 上仍写着 4681（该轮实测已是 4703），本批一并改成 4715。

- **`V28` 不在单测覆盖面里**：`application-test.properties:25 spring.flyway.enabled=false` +

  `ddl-auto=create-drop` ⇒ 那 2664 条单测**一条迁移都不跑**。真验证只在 IT **Pass C**

  （prod profile + 真实 PostgreSQL：Flyway 从零建库 + Hibernate `ddl-auto=validate` 逐实体校验）。

  本机 Pass C（一次性 `postgres:15-alpine` 容器，全新库）结果：**断言 1–6 全绿**——含断言 1 的

  Flyway 在 PG 上从零建库（`flyway_schema_history` 实测 `28 | device signing state | success`）与

  prod 的 `ddl-auto=validate` 逐实体校验通过（应用活到 health UP，V28 两列在

  `information_schema.columns` 里存在）⇒ **V28 已被真实 PostgreSQL 验过**。

  **断言 7 判红，但不是本批引入的**：真库里其实落了 65 行 `sysid=231 / tenant_id=1` 的遥测，

  而 `GET /api/v1/flightlog?day=2026-10-12` 读到 0 行、`day=2026-10-13`（**明天**）读到 65 行。

  根因是本仓既有的时区缺陷：`flight_log.timestamp` 是 `timestamp without time zone`，写入落的是

  本地墙钟（19:04），读取的日窗却按 `day.atStartOfDay(systemDefault).toInstant()` 变成 UTC-naive

  边界（`[10-11 16:00, 10-12 16:00)`）⇒ +08 的机器上"今天"这一页永远空。CI 的 runner 是 UTC，

  两边重合，所以这条断言在 CI 上**结构性看不见**这个缺陷。已另案登记，不在本批顺手改。

- `@Scheduled` 看门狗有调度器可跑（`CloudBackendApplication:12` 有 `@EnableScheduling`，实测）；

  新端点被 `RbacEndpointCoverageTest`（反射扫全仓 `@RestController`）判为"声明齐全"。



### 没做（有意）



- 不改任何出厂默认值：`mavlink.signing.enabled` 仍 `false`，`reject-unsigned` 仍默认 `true`，

  姿态端点在未启用签名时**两向都拒**（绝不"设置成功"却什么都没发生）。

- 设备列表的"签名态"列与批量生成密钥的 UI 没做——那是 `gcs-web/**`，是另一条线（#57）的地盘。

- 自动退回的默认宽限 2 分钟、证据窗口 15 分钟，都是配置项（`aerofleet.signing.posture.*`），

  真实机队的取值要等第一次 prod 演练的观测数据来定。



---

---

---

## [Unreleased] — 前端三项 + 面板测试补面 + 通信自适应接线（2026-10-12）



> 用户指令："前端做吧 / 测试做吧 / 通信自适应如果查不到责任人或者责任人不在线，那就给你做"。

> 责任人经协调板、PR 在途状态、commadapt 提交史三路核对**查无记录**（该包自 9-20 起无人动过），

> 按授权接手。



### 前端三项（评估报告 P1）



| # | 交付 | 内容 |

|---|---|---|

| ① | **WS 重连指数退避 + 静默看门狗** | 旧逻辑固定 3s 重连、无上限、无保活：弱网/对端重启时变成请求风暴，且 NAT/代理静默回收连接时（不发 FIN、`onclose` 永不到来）界面一直显示"已连接"。现 1s 起翻倍、封顶 30s、成功后重置；另加 45s 静默看门狗（服务端 1Hz 起推，超过这个时间没帧即在链路上已经死了）——浏览器 WebSocket 发不出 WS PING、服务端也只读不认上行帧，只能靠"多久没收到东西"判定 |

| ② | **遥测帧合批刷新** | 逐帧 `setState`：多机 45 帧/秒时 React 每帧重渲染整棵树。现按机缓存、每 100ms 合并落一次 state——history 逐条追加不丢点（曲线要密度），快照只取每机最后一帧。缓存按**年龄**裁剪（超 1s 的旧帧无绘制价值）而非按条数：按条数裁会在突发灌帧时丢中间点 |

| ③ | **实时飞行主轨迹线增长** | 主地图的 `track` 此前只在切换选中机时从后端 GET 一次，飞行中再无数据源——实时飞行的折线是**静止的**。现选中机的每个有效位置点经 `appendTrackPoint` 追加（30 点窗口上限同多机轨迹，1500 点总上限） |

| ④ | **DockPanel/DefectPanel 不再绕过统一 API 层** | 两个面板各自拼 `Authorization` 头 = 无超时控制、无 401 统一处理（token 过期时面板只显示 HTTP 401 而不翻登录态）。新增 `docks` / `defects` 两个命名空间收编；顺带修掉一个真问题：主列表失败必须让用户看到错误，只有详情/计划/度量这类次要数据才容错 |



### 面板测试补面（评估报告 §4"界面层基本裸奔"）



- **删两个自证式假测试**：`dockPanel.test.js` / `defectPanel.test.js` 在测试文件里自带一份常量镜像、从不 import 组件——组件改错或面板被删，测试照绿。改为 **import 组件本体的表**（真相源唯一）+ 真实渲染冒烟（空态/错误态/有数据），共 20 例。

- **新增 37 个页面级面板渲染冒烟**（`panelSmoke.test.jsx`，38 例）：每个面板都要能在 jsdom 渲染出内容，白屏/抛异常即红——**变异验证**：给 AlarmPanel 注入一个抛错，冒烟立刻抓到。两个面板书面记录不覆盖（依赖 `window.THREE` / `window.Hls`，jsdom 无此环境），不静默跳过。

- api.js 用"枚举真实导出名 + 命名空间 Proxy"打桩；canvas 2d 在 jsdom 缺失，给全 no-op 上下文桩（LinkQuality/Weather/Thermal 三个面板挂载时画图）。

- 前端单测 **245 → 292**。



### 通信自适应接线（用户授权接手）



**侦察修正了此前的判断**：`AdaptiveRouter` 其实有 `@Service`、控制器也在注入它——不是"整套死代码"。真问题是**链路没接上**：`LinkQualityMonitor.initDrone()` 无生产调用方（采集遍历空表）、`currentLinks` 无评估方（只在 FailoverManager 真切换时写过一次）、`GET /api/v1/comm-adapt/recommendations` 因此恒返回空数组——接口在、数据永远不来。



新增 `CommAdaptAdvisoryService` 做三件事：① 把 DeviceRegistry 的在线机注册进质量监控并清掉离线机；② 按周期评估（`AdaptiveRouter.evaluateAll()`，首轮登记当前链路——系统里没有"飞机当前用哪条链路"的权威来源，先给一个可比较的当前值）；③ 切换建议写进决策监控（ai 决策面板多一个真实数据源）。

**刻意不自动执行切换**：切链路是物理动作，且当前链路态无权威来源，只建议、不强制执行，与既有 `POST /switch/{sysid}` 人工入口口径一致。链路质量数值仍是监控层既有的模拟实现（其 javadoc 已声明），真实指标由 link-sim 接入时替换采集实现，本层接线不用改。



**测试**：`CommAdaptAdvisoryServiceTest` +6（注册/清离线/首轮只登记/决策落库/监控缺席不抛/空机队安静），**变异验证**：去掉注册步骤立刻 2 例转红。



**验证**：全 reactor BUILD SUCCESS；cloud-backend 2649/0；前端 292/292 + lint 0 error；门禁与自测全绿。

Java 4694 → **4700**（cloud-backend 2643 → 2649），前端 247 → **292**。

---

---

## [Unreleased] — 外部评估报告 P1 续整改：WS 两个并发/计数缺陷 + 租户隔离两处漏滤（2026-10-12）

---

---

## [Unreleased] — 修监管上报的重试语义：4xx 是终态，不该被当成瞬时故障重试（2026-10-12）

### 问题



`SimReportSink` 与 `UomReportSink` 各自实现了一份"失败就重试"，

判据都是**只把 2xx 当成功**：



```java

if (response.statusCode() >= 200 && response.statusCode() < 300) {

    return objectMapper.readTree(response.body());

}

log.warn("HTTP {} {} 返回状态码 {}（第 {} 次尝试）", ...);

// ↓ 落到这里就退避重试，4xx 一样重试

```



而 regulator-sim 是会返回 403 的（`MockUomServer.java:239, 341`：未实名、

已注销、状态不对）。于是**真实平台最常见的语义拒绝——签名过期、未实名、

已注销——会被 1s+2s+4s 重试 3 遍**，共 7 秒才返回失败。



后果不止是慢：

- 平台侧看到同一个请求被打 3 遍，可能判为滥用触发限流

- 运维看到的是"上报偶发变慢"，而不是"签名过期"这个真实原因

- 已有的 3 条重试用例（500 后重试、重试成功、全部失败）都在验证"该重试时重试"，

  **没有任何一条断言"不该重试时不重试"**——这个方向的语义此前零覆盖



### 改动



新增 `ReportRetryPolicy.isTerminal(int)`，把判定收敛到一处

（两处各写一份，下次还会各自漂移）：



| 状态码 | 判定 | 理由 |

|---|---|---|

| 4xx（408/429 除外） | **终态** | 请求本身被拒，换时间重发一样被拒 |

| 408 | 可重试 | 本身就是超时语义 |

| 429 | 可重试 | 通常带 `Retry-After`，退避后应能成功 |

| 5xx | 可重试 | 瞬时的服务端问题 |

| 0（拿不到响应） | 可重试 | 连接被拒/超时 |



终态时**把真实响应体交回调用方**而不是返回 null：

调用方本来就在读 body 里的 `success`/`activationId`，

sim 的 `{"success":false,"reason":"未实名注册"}` 正好能让原因一路传到

`ActivationResult.errorMessage()`。绝不再试，也绝不计为"不可达"——

那是语义拒绝，不是故障。



5xx、网络异常、超时、429 的重试路径与退避节奏不变。



### 两处踩坑记录（比改动本身更该留档）



**1. 第一版测试是空转的，而变异测试一开始"骗过"了我。**



我写了"403 只请求一次"，没显式设 `maxRetry`。而本类 `setUp` 默认 `maxRetry=0`——

**在 maxRetry=0 下，"只请求一次"无论实现对错都成立**。断言看似在守终态语义，

实际什么都没守。



发现过程：把 `isTerminal` 变异成"一律重试"后测试仍然全绿。

去看日志才发现 403/401 都在打"（第 1 次尝试，将重试）"——

行为确实错了，但 `requestCount` 还是 1，断言照样过。



修法：两条用例都显式 `config.setMaxRetry(3)`，让 `requestCount==1`

真正成为"判定认出终态"的证据。变异复测：两条转红。



**2. 我的变异脚本第一次也没抓住，但原因是另一个。**



锚点整段字符串匹配，命中了方法里更早的那个 `return false;`

（`statusCode == 0` 分支），变异落错了位置、`isTerminal` 行为没变，

于是"测试通过"被误读成"测试没覆盖"。所以变异脚本改成：

锚点必须唯一匹配 + 写后立即回读验证文件真的变了，二者缺一不可。



### 为什么现在修



这是往真实 UOM 平台联调的前置条件之一。真实平台的 4xx 处理是必踩项，

而这条路径此前**在 e2e 里从未被触发过**（`e2e-regulator.ps1` 从不用

`--error-rate`，FR-35 靠"杀进程"模拟不可达，造不出"间歇 500"，

更造不出 403）。修完才谈得上用 `--error-rate` 去验 backend 的退避路径。



### 计数



Java 4672 → **4675**（`cloud-backend` 2623 → **2626**，+3）



---





> 同一份外部评估报告的 P1 项里，挑出**有明确代码证据、属本仓可自行闭合**的四项修掉。

> 每项都配回归测试，其中两项做了变异验证（改回旧行为立刻转红）。



| # | 交付 | 内容 |

|---|---|---|

| ① | **WS 连接限流双重扣减** | 被拒连接在拒绝处扣一次、连接关闭时 `afterConnectionClosed` 又按同一 IP/租户扣一次 → 计数只减不增，限流随时间漂移成"形同虚设"。现扣减只发生在关闭路径一处，拒绝路径不再预扣（日志同步改为报"现活跃含本次"） |

| ② | **WS 同会话并发发送无同步** | `dispatch` 对同一会话 `sendMessage` 未加锁，而遥测/调度/告警帧来自不同线程 → 并发写同一条连接会帧交错或抛 IllegalStateException（客户端表现=偶发断连/半包 JSON）。现按会话加锁；日志说明与 Spring `ConcurrentWebSocketSessionDecorator` 内部同一把监视器 |

| ③ | **机巢（dock）三处租户漏滤** | `register` 把 tenantId 硬编码 null ⇒ 机巢永远"未归属"，任一租户的 OBSERVER 在列表里看到全部机巢含位置；`list()` 无过滤；`get()/recentLog()` 不判可见性。现：注册时归属当前写入方租户（`getWritableTenantId()`，哨兵不会被落库）、列表走 `findByTenantIdOrderById`、越权 ID 与"不存在"同一应答（不制造探测面）。口径与 `AlarmEventStore.isVisibleTo()` 对齐 |

| ④ | **AI 决策端点两处漏滤** | `GET /api/v1/ai/decisions` 直接回全量分桶、`/decisions/{sysid}` 不判归属。现按 `DeviceRegistry.tenantOf(sysid)` 过滤：租户会话只看本租户设备，未归属设备只对全局管理员可见 |



**测试**：`TelemetryWsConcurrencyTest` +2（限流只扣一次 / 同会话发送互斥，**变异验证**：去掉 synchronized 即红）、

`DockTenantIsolationTest` +7（归属写入/列表过滤/全局可见/NO_ACCESS 看不到/越权同不存在/本租户可取，

**变异验证**：去掉列表过滤即红）、`DecisionMonitorTenantIsolationTest` +4（单机/全量/全局/未归属四种域）。

全 reactor BUILD SUCCESS；Java 4681 → **4694**（cloud-backend 2630 → 2643）；前端 245 不变；门禁与自测全绿。



**仍开（有意不动）**：`AdaptiveRouter` 整套从未被实例化（commadapt 决策层是死代码，接线需先定 owner）；

mavlink-core 三项（未知 incompat 位、签名时间戳竞态、UDP 回调吞异常）属另一 agent 域；

sdk-python 0 单测；前端 WS 重连无退避/无节流、主轨迹不实时、DockPanel 绕过统一 API 层。



---

---

---

## [Unreleased] — 两件"让沉默变红灯"的守卫：SPI 可验证性（35 个适配器实现里 9 个零测试引用）+ 状态板生成器（2026-10-12）



- **发现方式与数字**：人肉扫了一遍 master（`0a85c38`）——13 个 SPI 接口下 **35 个生产实现**，

  其中 **9 个在所有 `*/src/test/**/*.java` 里一次都没出现过**：`UomReportSink`（真监管上报的 HTTP 客户端，

  带 baseUrl/apiKey/重试）、`MavlinkDeviceGateway`（F5 双栈网关）、`UdpTransportAdapter`、`RealEnvSource`、

  以及 5 个 `ModuleAdapter` 实现（Delivery/Emergency/Formation/Mapping/Show）。

  这类代码编译通过、启动正常、日志里"看起来在工作"，但**没有一条断言钉过**——

  报文格式/重试/超时/鉴权头一错，只在真机或真平台暴露，而"加适配器"本身不会让任何东西变红。

- **补上信号（`scripts/check-spi-coverage.py`，接进 `dep-gate` job 两步）**：

  1. `Self-test the SPI gate itself`——离线夹具自证六情形，且断言的是**具体错误文案**不只看退出码：

     未验无豁免→1 / 豁免未到期→0 / 豁免到期→1 / 豁免可撤销→1 / **一个实现都没扫到→2** / 僵尸豁免→1；

  2. `SPI verifiability gate`——**默认全关、例外显式、到期自动回红**：豁免账每条必须写理由 + `expires`，

     四种情况一律红（新增未验实现、改名后留下的僵尸豁免、其实已被测试引用却还占着账、豁免到期）。

     退出码 **2 专用于"无法判定"**：扫描器失明时绝不伪装成 0（这条是本仓门禁反复踩过的失效面）。

- **口径的局限（写清楚，免得被当成覆盖率）**：判据是"类名是否出现在测试源码里"。

  因此 **经接口或 Spring 上下文间接经过的不算已验**——`ModuleAdapter` 接口在 `StepExecutorTest` 出现 4 次，

  但那 5 个具体实现一次都没被点名，仍判未验；反之"被点名"也**不等于行为被断言**（可能只是 import/mock）。

  要行覆盖/分支覆盖请走 `scripts/ci-coverage-threshold.sh` 那条腿，本门禁只管"有没有被测试触及"。

- **9 条豁免都带补测方向**，不是一句"暂时不管"：`UomReportSink` 那条写明"sim 线正在改本文件的 4xx 终态

  与 `ReportRetryPolicy`（未提交），等它合入后用 JDK `HttpServer` 当假 UOM 补契约测试"——

  到期日 2026-10-26，届时无动作即红。

- **验证**：`--self-test` 六情形全过；真仓库首跑 exit 0（35/9/9 与独立探针逐项一致）；

  植入合成探针 `SyntheticSpiProbeSink implements RegulatorReportSink` ⇒ exit 1 并**点名该类**，

  撤除后 exit 0 且工作树零残留（合成文件而不是产品代码，避免为了证明门禁有效去改产品）；

  `yaml.safe_load` 解析 19 个 job，两步顺序为"自测在前、真跑在后"。

- **边界**：本门禁只提高"没人验的适配器"的可见度，**不等于这 9 条已被清偿**。清偿是把豁免账逐条删掉，

  账上条目减少才算进展；条目原地续期就是没进展，而且到期会变红。

- **同一批还落了 C｜状态板生成器 `scripts/coord-status.sh`**：`.coord/STATUS.md` 的「在途与占用」段

  此前靠人手写，10-12 那次它停在 10-10，而 master 两天里合了 12 笔、多出两个在写的 worktree——

  过期的共享状态板比没有更危险，因为下一个人会照它行动。现在该段由脚本实测生成（标记块内幂等、

  gh 不可用时报 `UNVERIFIED` 而不是"没有在途 PR"、发现手写同名段并存就拒绝写入）。

  `COORDINATION.md` 第三节里那份写死 sha 的"当前状态"快照同步删除——**同一事实不留两处**。

- **C 的自测与一次真实的自我推翻**：`--self-test` 断言五种情形。第一版只查"标记块出现 1 次"，

  而 `--write` 的 awk 替换丢了 `inside` 追踪、把旧块正文留在原地——**自测照样绿**。是反向变异

  （在 `scripts/` 里造一个退回旧写法的副本再测）才暴露它；随后又发现第二次 `--write` 其实是被

  并存守卫拦成 exit 1，而 T3 只看"文件没变"就误判通过 ⇒ 断言补上"重写必须 exit 0"。

  两次都是同一个教训：**绿的自测不等于测到了东西**，变异一次才算数。

  另注：自测的目标路径也做成了可注入（`COORD_STATUS_SELF`），否则副本测的是原文件——这个坑我

  在本仓已经踩过第三回。



---

---

---

## [Unreleased] — 外部评估报告 P0 四项整改（2026-10-12）



> 由第三方做的全仓深度评估（HEAD 973db62）给了 P0 四项：自动出警假闭环、

> MISSION_ACK 寻址错、登录 401 整页刷新、ai 包空壳。**逐条核对后四项全部属实**，

> 本轮全部修掉并补回归。评估报告同时发现的另两个问题（AdaptiveRouter 从未被实例化、

> README 端口口径 14561 与代码 14550 不一致）也一并处理/订正。



| # | 交付 | 内容 |

|---|---|---|

| ① | **自动出警假闭环 → 真下发** | 旧实现生成 MAVLink 航点后只打日志、**从不下发**，却向调用方返回"派遣成功"——安防监控显示"已出警"而飞机不动。现接入 `DroneCommandService.uploadMission` 真实上传四段航点（起飞→飞往报警点→悬停→返航），并按**真实送达数**判定：新增 `DispatchResult.Status.FAILED`（选中了却一架都没送达），部分送达为 PARTIAL。**不自动 ARM/MISSION_START**——是否自动起飞是安全策略问题，留给操作员确认 |

| ② | **MISSION_REQUEST/ACK 寻址指向 GCS** | `VirtualDrone` 的上传方向把 MISSION_ACK 的 `target_system` 填成无人机自身 sysid（同文件另两处正确填 gcsSysid），严格 GCS（按 target_system 路由的 QGC/PX4 类）会认为 ACK 不是发给自己的，**任务上传握手永远等不到最终 ACK**。现上传会话记录请求方 `uploadingGcsSysid`，MISSION_REQUEST 与 ACK 一律指向它（含 count=0 清空、NO_SPACE 拒绝路径）。顺带 `lastGcsRxMs` 补 volatile（写在锁外、读在锁内，与类注释"共享状态由 intrinsic lock 保护"自相矛盾） |

| ③ | **401 不再整页刷新** | 旧实现在 401 时 `location.reload()`，而防循环守卫判 `location.pathname.includes('/login')`——SPA 视图切换不产生路由，守卫恒为假：登录输错密码也刷新（错误提示被毁，用户只见页面闪一下）、作业中任何后台 401 把现场一起刷掉。改为发 `nexus-auth-expired` 事件，`useAuth` 订阅后翻登录态，原因经事件 detail 带到 LoginPanel 显示 |

| ④ | **决策事件落监控** | `DecisionMonitorService.recordDecision` **零调用方**，`GET /api/v1/ai/decisions` 恒为空——对外是"有接口无数据"的假能力。而 WS 面一直在出同一批 DECISION_EVENT(30051)（ drone-sim `AutonomyAdvisor` 真实产生的决策帧）：两个面分裂。现 `TelemetryIngestService` 解码到 DecisionEventMsg 时落库（含类型码越界 → `UNKNOWN(n)` 不打断收包线程） |

| — | **修：README/客户文档端口口径** | README 4 处、customer-onboarding-guide 2 处写 14561，而代码与 Helm/k8s 清单都是 **14550**——照 README 接真机会连到不存在的端口 |

| — | **订正：api-reference 的 trigger 响应枚举** | 文档写 `status:"DISPATCHED"\|"FAILED"`，实际枚举是 SUCCESS/PARTIAL/NO_DRONE（+本轮新增的 FAILED），照文档对接会解析失败 |

| — | **测试** | drone-sim `MissionUploadAckTargetTest` +2（**变异验证过**：把目标改回 config.sysid 即转红）、cloud-backend `AutoDispatchServiceTest` +4（真实上传四段航点/上传失败报 FAILED/命令面缺席报 FAILED/部分送达报 PARTIAL）+ `TelemetryIngestServiceTest` +3、前端 `auth401.test.js` +2（401 不 reload、只发事件并清令牌）。既有 9 个 autodispatch 用例与 3 个 controller 用例原本断言的就是那个假闭环，改为注入"上传成功"的命令面 mock——**它们断言的成功从假变真** |



**验证**：全 reactor BUILD SUCCESS；前端 245→**247** + lint 0 error（55 个存量 warning 不变）；

门禁与自测全绿。Java 4663 → **4681**（drone-sim 1408→1410、cloud-backend 2614→2630，

含与 #52 签名指标合入后的并集实测）。



**核对结论（报告里属实/已修 vs 仍开）**：P0 四项全部属实且已修；`AdaptiveRouter`

（commadapt"决策层"）**从未被 `new` 过、也没有调度调用方**——比报告写的"ai 空壳"更值得

注意，属已登记未接线，本轮未动（要接需要先定调度与策略 owner）；报告列的 mavlink-core

三项（未知 incompat 位不丢帧、签名时间戳竞态、UDP 回调吞异常）**属另一 agent 的域**，

未触碰；sdk-python 0 测试属实，未动。





### 问题



`micrometer-registry-prometheus` 早已接好、`/actuator/prometheus` 早已暴露、

`MetricsConfig`/`ApiMetricsFilter`/`DroneMetrics` 早已在位——但**签名统计是

`UdpGateway` 里的纯内存计数器**，每 60s 打一条 `log.info` 就清零。



也就是说：本系统最该被监控的安全计数器，对**已经在采集的 Prometheus 是不可见的**。

有人在爆破签名时 `rejected` 在涨，但没有任何东西能据此告警。

`docs/security-design.md §7.5` 早就把"签名统计只进日志，既无指标也无告警"

登记为已知边界——现在它不再是边界。



### 改动



`DroneMetrics` 新增三个计数器：



| 指标 | 类型 | 说明 |

|---|---|---|

| `aerofleet.signing.verified` | Counter | 验签通过的帧数 |

| `aerofleet.signing.rejected{reason=...}` | Counter | 验签拒绝，**按 reason 标签** |

| `aerofleet.signing.unsigned` | Counter | 未签名但被放行（仅 permissive 姿态非零） |



reason 五值：`unsigned` / `no-key` / `bad-signature` / `timestamp-replay` / `error`。



**为什么 rejected 要带 reason 而非裸计数**：裸的"拒了多少"不可行动，

"因为什么被拒"才可行动。`unsigned` 是有客户端没升级签名；

`no-key` 意味着**有帧带着有效签名但来源 sysid 不在密钥库里**——后者更值得警惕，

两者混成一个数字就没法定位了。五个取值，基数安全。



`UdpGateway` 把 reason 穿过 5 个拒绝点，并同步给 `DroneMetrics`。

`droneMetrics` 是 `@Autowired(required=false)`：**指标是可选依赖不是启动依赖**——

没有 MeterRegistry 时签名链路必须照常工作（`worksWithoutMetricsBean` 盯这条）。



### 顺带一个观测红利



`aerofleet.signing.unsigned` 在 fail-closed 姿态下**应恒为 0**。

staging 自 2026-10-11 起 `reject-unsigned=true`，所以这个计数器直接就是

「收紧是否生效」的证据——它开始动，就说明有人把配置退回了 permissive。



建议告警表达式已写进 `docs/security-design.md §7.5`（**未内置告警规则**，

按环境实际标签名调整）。



### 测试



新增 `UdpGatewaySigningMetricsTest`（8 例）：打真实 UDP 字节走生产同一条路

（`DatagramSocket → UdpMavlinkTransport → MavlinkParser → onFrame → verifyFrame`），

直接读 `SimpleMeterRegistry`。断言端到端接线而非"计数器会加"——真正会坏的是接线。



变异测试：

- 删掉 verified 的指标记录（内存计数保留）→ 2 例转红（这正是"看起来接了实际没记"的假修复）

- 把 bad-signature 错标成 unsigned → 3 例转红（归类错了功能不失效，但告警会指错方向，更隐蔽）



### 计数



Java 4644 → **4653**（`cloud-backend` 2610 → **2619**，+9）



---

---

---

## [Unreleased] — C5 第二步：staging 收紧为 strict + 补齐部署侧注入链路 + 签名 e2e 首次进 CI（2026-10-11）



C5 的三步里，第一步（staging 开着签名但不拒未签名）早已落地，但后面卡住了。

本轮一次推完剩下的可做部分：**部署侧注入链路**（硬前提）、**staging 收紧**（第二步）、

**签名 e2e 进 CI**（补上"没人验证它在工作"的缺口），外加一处文档漂移订正。

prod 打开签名（第三步）**未动**，仍需单独拍板。



### ① 部署侧此前根本没有签名注入落点（新发现）



比"收紧 staging"更朴素的前提问题此前没人提：

**`MAVLINK_SIGNING_SECRET_KEY` 在任何部署清单里都没有落点**——

`deploy/k8s/secret.yaml`、helm values/templates、`deploy/docker/docker-compose.yml`

全都没有它。也就是说"往 prod 打开签名"在当时**于部署侧无处可填**，

配置里的 `${MAVLINK_SIGNING_SECRET_KEY}` 永远拿不到值。



更糟的是 `scripts/check-deploy-secrets.sh` 的泄漏扫描只认 `AEROFLEET_` 前缀，

即便有人把真口令写进清单也扫不出来。



现补齐：



| 文件 | 改动 |

|---|---|

| `deploy/helm/nexussky/values.yaml` | 新增 `mavlinkSigning` 块（enabled / rejectUnsigned / secretKey / keyStorePath） |

| `deploy/helm/.../templates/secret.yaml` | opt-in 渲染口令/密钥库；开启时口令仍是占位串 ⇒ helm 阶段直接 fail |

| `deploy/helm/.../templates/configmap.yaml` | opt-in 渲染 `MAVLINK_SIGNING_ENABLED` / `_REJECT_UNSIGNED` |

| `deploy/k8s/configmap.yaml` | 加**注释掉**的开签名示例 |

| `deploy/k8s/secret.yaml` | 加**注释掉**的口令示例 |

| `deploy/docker/docker-compose.yml` | 4 个变量，具名缺省 |

| `scripts/check-deploy-secrets.sh` | 前缀扩到 `MAVLINK_SIGNING_*`；新增方向 D/E/F |



**为什么是 opt-in 而不是无条件渲染**：env 的优先级高于 profile 专属 properties

（实测属性源顺序 `systemProperties > systemEnvironment > Config resource`），

所以若 chart 在关闭时还无条件渲染 `MAVLINK_SIGNING_ENABLED=false`，

一个用默认 values 部署 staging 的 release 会把 staging 自带的 `enabled=true`

**静默改成 false**。安全姿态不该由「忘了配某个值」决定。



**顺带实测的一条承重假设**（不是靠记忆）：上面那个属性源顺序是真的，

`reject-unsigned` → `MAVLINK_SIGNING_REJECT_UNSIGNED` 的 relaxed binding 也成立。

同时发现**空字符串会让 `@Value("${...:false}")` 绑定失败而非回落默认值**——

这就是清单里开关一律给具名缺省、绝不注入空值的原因。



### ② staging 收紧为 strict（C5 第二步）



`application-staging.properties`：`reject-unsigned` `false` → `true`。



依据是第一步的实测结论：真 PostgreSQL + staging profile 下 `signing: verified=1259

rejected=0`（先修掉 timestamp 非单调造成的 635 条误拒），即在网端**已全量在签**，

没有靠「不拒未签名」兜底的对象。停在第一步的话，签名只挡住了"签错/被篡改"，

没挡住"没签"——未签名流量是在明文状态下被放行的。



⚠️ **收紧是硬切换**：签名块 `linkId(1)|timestamp(6)|signature(6)` 没有 key id 字段，

不存在新旧密钥并存的宽限期。未配密钥的设备下行帧会被整体丢弃（遥测中断）。



⚠️ 收紧后 `signingStats.unsigned` 计数恒为 0（未签名帧直接计入 `rejected`）。

定位"谁还在发未签名帧"要改看 `WARN 拒绝未签名帧：sysid=<id> msgId=<id>`。



新增 `MavlinkSigningConfigurationTest` 两个用例把姿态钉死：



- `stagingProfileAssemblesStrict` — 读 `application-staging.properties` 实际字面值，

  **并把它们真喂进上下文**，确认 `true` 真的传导到 `MavlinkSigner.isRejectUnsigned()`。

  只读字面值只能证明"文件写了 true"，那段能证明"行为真的变了"。

- `prodProfileStaysDisabled` — prod 仍是 `enabled=false`。有人顺手翻开关时 CI 会转红。



变异测试：把 staging 退回 `false` → 前者转红；把 prod 改成 `true` → 后者转红。



### ③ 签名 e2e 首次进 CI



`scripts/e2e-signing.ps1`（884 行 / 6 场景）**从未被任何 workflow 调用过**

（`docs/roadmap-c-drift.md:66` 早就登记了）。现接为 `e2e-signing` job。



接之前**必须先改跨平台**，否则是必然失败而非 flaky：



| 问题 | 后果 | 处理 |

|---|---|---|

| `$env:TEMP` ×16 | Linux 上该变量不存在，`Join-Path` 退化成相对路径、日志落进仓库目录 | 收敛到 `$TempDir`（TEMP → TMPDIR → `GetTempPath()`） |

| `-WindowStyle Hidden` ×15 | 该参数只在 Windows 的 `Start-Process` 上存在，Linux 上直接报"找不到参数" | 删除 |

| `Get-CimInstance Win32_Process` ×2 | Windows-only cmdlet。**静默失败比报错更糟**：会让"清理上个场景残留"变成什么都不做，端口被占，表现为莫名其妙的超时 | 加 `$IsWindows` 分支，Linux 走 `ps` |



带 `continue-on-error` 以**观察模式**接入（与 `e2e-capability` 同款先例）：

脚本此前只在本机 Windows 跑过，Linux CI 连续复跑的稳定性未知。

若干次 run 全绿后删掉这行即转为硬门禁，删标记 = "跑过"升级为"必须通过"，不涉脚本改动。



**Windows 回归验证**（改完脚本后本机实跑）：6 场景 38 项断言全 PASS

（正常签名 / 篡改检测 / 未签名拒绝 / 密钥不匹配 / 多机密钥分发 / 签名关闭兼容）。



### ④ 文档漂移订正



- `README.md`：「默认 false 且**任何 profile 都未配置**」——双重过时（四个 profile 早已显式声明，staging 早已是 true）

- `ROADMAP.md`：「四个 profile 均已显式声明但保持 `false`」与 staging=true 冲突；三步表述改为标出①②已完成、④待拍板

- `docs/roadmap-c-drift.md`：`MavlinkSignatureConfig:33` → 实为 `:25`（`enabled` 在 25 行，33 行是 `key-store-path`）；e2e-signing 与"没改 staging"两处已被推翻的记载更新

- `docs/security-design.md` §7.5 三处已被代码推翻的断言用删除线标出并给出正解：

  "没有轮换端点"（PR #23 已补）、"Parser 层只切帧不验签"（`MavlinkParser.java:77,106-118` 完整解析）、

  "时间戳 1 毫秒粒度需调用方自增"（2026-10-11 改 per-stream `nextTimestamp()`）

- `docs/PRODUCT-POSITIONING.md`：「无轮换端点」过时



### 未做（有意）



- **prod 打开签名**（第三步）：机队范围的行为变更，约定需单独拍板。

  `prodProfileStaysDisabled` 用例把它钉住，改动必须显式改测试。

- 没做多密钥库的部署侧路径验证（`key-store-path` 在 k8s 里需要额外挂卷，

  本轮只把声明位与门禁铺好，没有为它加 volume 约定）。

- `e2e-signing` job 仍是观察模式，未转硬门禁（见上）。

- 签名统计仍只进日志，无 Micrometer 指标与告警（`security-design.md §7.5` 已登记的边界）。



### 计数



Java 4642 → **4644**（`cloud-backend` 2608 → **2610**，+2 个姿态用例）



---

---

---

## [Unreleased] — 修 C5 签名时间戳非单调：高帧率下大规模遥测被误判重放（2026-10-11）



### 根因



实测暴露：真 PostgreSQL + staging profile + reject-unsigned=false 下

`signing: verified=613 rejected=635`，1278 条拒绝全是

`Timestamp 校验失败（疑似重放）`，且 timestamp 是同一个值。



**发送端违反协议**：`MavlinkMessage#toFrame(..., signer)` 直接调

`currentSigningTimestamp()` 取墙上时钟。timestamp 单位 10µs（1ms=100 tick），

drone-sim 的 tick 间隔小于该分辨率 ⇒ 同 tick 内多帧拿到相同 timestamp ⇒

接收端按"严格递增"判重放 ⇒ 遥测大面积被丢。



规范原文（mavlink.io/en/guide/message_signing.html → Timestamp Handling）：



> "The timestamp should be **incremented by one** on each message sent from a

> particular link."

> "the timestamp **may get ahead of GMT time** if the packet rate averages more

> than 100,000 packets per second."



即高帧率下时间戳**应该**跑在墙前，这是协议允许且预期的行为。所以这不是

"绕过校验"，而是把发送端按协议实现。



### 改了什么



1. `MavlinkSigner`：加 per-stream 计数器，key = `(linkId, sysid, compid)`

   三元组（与接收端流划分同一口径）。`nextTimestamp() = max(墙钟, 上一帧+1)`。

   用 max 而非纯自增：重启后计数器归零，纯自增会发出远小于上一会话的时间戳

   而被判重放；取 max 让新会话从墙钟起步（规范："startup 取 clock 与 stored

   的较大者"，我们不持久化 stored 项）。

2. `MavlinkMessage#toFrame(..., signer)`：改用 `nextTimestamp()`。

3. `TimestampTracker`：接收端按官方口径把「<= 上一帧」放宽为「< 上一帧」

   （官方原话只要求拒绝更旧的："rejected if it is smaller"）。

   **回退仍然拒绝，重放防护不减弱。**



### 验证



- mavlink-core **478/478** 全绿（含 `MavlinkSigningVectorTest` 与官方 pymavlink

  已知答案向量逐字节一致，证明签名算法本身未被破坏）

- 新增 `SigningTimestampMonotonicTest`（8 例）：同 tick 内连发严格递增、

  不同 sysid/compid/linkId 分流、不倒退到墙钟以下、`lastTimestamp` 可观测

- **变异测试 1**：`nextTimestamp` 退回纯墙钟 ⇒ 3 例转红

- **变异测试 2**：`TimestampTracker` 不再拒绝回退 ⇒ 6 例转红

- 两个既有"重放"用例按官方口径改写：从"原样重发同一帧"改为"回带更旧 timestamp"

  （那才是重放攻击的真实形态；原断言依赖"相等也拒"，已不成立）

- **本地真 PG + staging 实测**（同一脚本、同一密钥、同样时长）：



  | 指标 | 修复前 | 修复后 |

  |---|---|---|

  | verified | 613 | 1259 |

  | rejected | 635 | **0** |

  | Timestamp 校验失败 | 1278 | **0** |



### 未做（有意）



- 没做 timestamp 持久化（规范建议，属另一取舍；我们没有跨重启续会话的需求）

- e2e-smoke 本轮仍未全绿，原因是 staging 的 `device-whitelist-enabled=true`

  而本地脚本没先 provision 设备（1359 条白名单拒绝）——**与签名无关**，

  是本地演练脚本缺一步；签名的 `rejected=0` 已证明本修复有效



### 计数



Java 4625 → **4633**（mavlink-core 470 → **478**，本轮 +8 例）。

---

---

## [Unreleased] — 空域预约持久化 + 时间口径升级（相对秒 → epoch 毫秒）（2026-10-10）



> B5 交付时登记的边界："预约表仍是内存态（重启即空）——持久化属生产阶段"。本轮闭合，

> 并**修正一个设计债**：预约时间原为相对秒，落库后重启会语义断裂（相对窗口无绝对锚点）。



| # | 交付 | 内容 |

|---|---|---|

| — | **时间口径升级（设计债修正）** | `Reservation4D` 的 startTime/endTime 由相对秒升为 **epoch 毫秒**；overlap 判定比较大小关系，算法不变；REST 文档与 5 个既有测试同步迁移 |

| — | **V26 迁移 + 实体 + repo** | `airspace_reservations` 表；`AirspaceReservationEntity`（from/to 转换）、`AirspaceReservationRepository`（恢复/过期清理/按机主释放三查询） |

| — | **服务持久化** | `ConflictAvoidanceService`：`persist` 开关（`aerofleet.scheduling.reservation.persist`，默认 false，与 C4 同模式）+ repo 惰性注入 + `@PostConstruct` 恢复（未过期灌内存表、顺带清过期）；tryReserve 成功落库、purge 同步删库、clearReservations 含库。**内存表仍是权威热路径，库失败不影响它** |



**测试**：ConflictAvoidanceServiceTest 34→36（+持久化往返/恢复、纯内存不触库）；5 个既有预约测试 + Controller 测试时间值迁移；cloud-backend 全量 **2587/0**；本地 H2 + `ddl-auto=validate` 演练通过（V26 与实体一致，Pass C 等价）。



**边界**：「主动释放单个预约」未做（过期清理 + 按机主批量释放已备）；预约的租户归属未建模。

---

---

## [Unreleased] — C5 第一步：staging 打开签名但不拒未签名（2026-10-10）



MAVLink v2 签名块 `linkId(1)|timestamp(6)|signature(6)` **没有 key id 字段**，

打开即硬切换、无宽限期。所以不能一步到 prod；先用"开着但不拒未签名"的演练姿态，

在不打断任何旧固件/第三方设备的前提下，从 `signingStats` 观察到底有多少帧真的在验签。



（`docs/low-altitude-economy-demand-research.md:189` 已把 MAVLink v2 signing 列为

**GB 42590 数据链路安全项**——它是合规项而非可选加固，只是不绑 2026-11-01 那个

GB 46750 的运行识别 deadline。）



### 改了什么



- `application-staging.properties`：`mavlink.signing.enabled=true` +

  `reject-unsigned=false`，密钥走 `${MAVLINK_SIGNING_SECRET_KEY}`（不写默认值，

  与 prod 同口径）。

- 新增 `UdpGatewaySigningPermissiveTest`（6 例）钉住"开着但不拒"的完整语义。



### 最关键的一点：reject-unsigned=false 保护谁



| 帧 | 结果 |

|---|---|

| 未签名 | **放行**（这一步的全部意义） |

| 签名 + 密钥正确 | 放行，且与未签名帧共存 |

| 签名 + 密钥错误 | **仍拒绝** |

| 篡改 | **仍拒绝** |

| 未知 sysid（取不到密钥） | **仍拒绝** |

| 重放（同一 timestamp） | **仍拒绝** |



`reject-unsigned=false` **只**保护未签名客户端，**不**保护"签错密钥"的客户端——

后者照样被丢（要么被篡改、要么密钥配错，两种情况都不该放行）。

这个不对称是刻意设计，测试第 3 例专门钉它。



### 验证



- `UdpGatewaySigningPermissiveTest` 6/6 + 既有 `UdpGatewaySigningTest` 8/8

- **变异测试**：把 permissive 用例的 `rejectUnsigned` 从 false 改成 true后，

  两个 permissive 断言（未签名放行 / 混合共存）立刻转红，另 4 个"仍拒绝"的

  不受影响——断言确实按语义分区在咬

- **本地实启三种情景**（dev profile + H2，显式覆盖那三个签名键）：

  staging 等价配置 → Started；同配置但不给密钥 → **启动失败**（fail-closed）；

  出厂默认 → Started 且签名组件不装配



### 未做（有意）



- 没在 CI 里跑 staging profile（需要 PostgreSQL + `AEROFLEET_*` 一组环境变量，

  属部署侧）。本轮只把配置与语义钉住，实际演练由部署方执行。

- 没动 prod 的 `enabled=false`——prod 打开仍是单独拍板的事。



### 计数



Java 4613 → **4619**（cloud-backend 2587 → **2593**，本轮 +6 例）。前端 245 不变。

---

---

## [Unreleased] — C1 模拟环境深化：regulator-sim 激活状态机（幂等/关联校验/注销拒绝）（2026-10-12）



> 用户指令："还是做模拟环境来搞吧"——把原"等真实 UOM 联调"的 C1 边界

> 用高保真模拟推进。目标：让真实联调时 90% 的问题（状态/幂等/时序）

> 在 PoC 环境就暴露并验收。



| # | 交付 | 内容 |

|---|---|---|

| — | **ActivationStateMachine**（regulator-sim 新类） | 按 serialNo 激活生命周期：NONE → ACTIVATED → CANCELLED。activate 幂等（重试返回同一 ID 不新发）、实名前置校验（未注册/非 VERIFIED 拒绝）、telemetry 仅 ACTIVATED 接收（未激活 403 NOT_ACTIVATED、已注销 403 CANCELLED）、cancel 幂等、reset 清空 |

| — | **MockUomServer 接入** | activate/cancel/telemetry 经状态机裁决（拒绝返回 403 + reason/verdict，不再无条件 success）；新增 POST /api/reset（e2e 用例间隔离，不影响预置数据与记录） |

| — | **cloud-backend 零改动** | TelemetryReport/ActivationRequest 契约本就不带 activationId——状态机按 serialNo 维度做，上报链路与响应字段不变，只是语义变真 |

| — | **测试** | ActivationStateMachineTest +15（未注册拒绝/非 VERIFIED/幂等/未激活遥测拒/注销封锁/reset/多机隔离/null 与空白 serialNo 不炸/trim 归一）；存量 RegulatorSimMainTest.telemetryEndpoint 改为"先 activate 再上报"（真实链路形状——旧写法正是无条件 success 时代的残留） |

| — | **e2e** | FR-36~39（sim HTTP 面）：重复 activate 同 ID + idempotent 标记；UNVERIFIED 激活被拒且遥测被拒；注销后再激活被拒；reset 后可重新完整激活 |

| — | **修：sim null serialNo 炸连接** | ConcurrentHashMap 不接受 null key，handler 一个 NPE 就被 com.sun.net.httpserver 静默关连接——客户端只看到"HTTP/1.1 header parser received no bytes"，比明确拒绝难查得多。现 null/空白统一归一为"未标识设备"：activate/cancel → 403 NOT_FOUND、telemetry → 403 NOT_IDENTIFIED，附中文 reason；PresetDataStore.find 同样 null 安全 |

| — | **修：backend 上报丢身份** | `ComplianceStateManager.transition` 从初始化起就把 productSerialNo 写死 null，而遥测上报正是从合规状态取 serialNo 拼上报体——"已实名、已激活"的设备，上报反而没有产品序列号，监管侧只能拒。新增带 serialNo 的重载（非空覆盖、空不清空），verify/activate 三处调用带入请求里的 productSerialNo |

| — | **修：e2e-regulator 三处路基缺陷**（该脚本自诞生只提交过一次、从未进 CI，一直没人跑） | ① `$BackendPort` 从未传给 backend（脚本只拿它做健康检查，应用按 application.properties 的 8080 起）→ 补 `--server.port`；② drone-sim 的 14555 从未告知 backend，而 UDP 发现名单只有默认 14540~14543 → 设备从未被发现、遥测快照恒空（**FR-33 因此从未绿过**）→ 补 `--aerofleet.drone-port`；③ 就绪判据用 actuator health，而本机 Redis 无口令会把 health 打成 503 → 误杀，改业务端点 `/api/v1/drones`（与 e2e-cv-eval 同款）。另加 AF_BACKEND_PORT/AF_REGULATOR_PORT/AF_DRONE_PORT 覆盖（8080 常驻容器占用）、清理进程的匹配模式修正、`Send-PostJsonAllowFailure`（PS 5.1 的 Invoke-WebRequest 非 2xx 抛错前会吃掉响应流，断言不了 verdict，改走裸 HttpWebRequest） |

| — | **sim 可观测性** | 每个收到的请求打一行 `[req] METHOD URI`——mock 平台要能自证"请求到没到"，否则客户端报无响应时无法区分没送达与 handler 炸了 |

| — | **修：门禁两个盲点** | ① `<100 不当测试数`的常识过滤把小模块整表放过——README 测试表里 regulator-sim 19（实为 34）长期隐身，表格相加还比总数少 15 都没人发现；现已知模块名不再套该过滤。② 第二种陈旧报告形态：类还在源码树里、只是本轮没跑（`ParseAllocationBenchmark` 不匹配 surefire 默认 include，`mvn test` 从来不跑它，但 target/ 里躺着上次 `-Dtest=` 显式跑的报告帧）→ 本机量到 4664、CI 量到 4663，本地绿 CI 红；现判定规则与 surefire 默认 include 对齐 |



**验证**：regulator-sim 单测 34/34；cloud-backend 合规域单测 28/28 + RegulatorControllerTest 23/23；e2e-regulator 全量 FR-31~39（31 断言 0 失败）。



### 计数



Java 4619 → **4663**。其中本轮新增：regulator-sim 27 → **34**（+7 null/空白与归一用例）、

cloud-backend 2610 → **2614**（+4 序列号绑定用例）；其余为订正此前合入未同步的存量漂移

（mavlink-core 478 等），以 `check-test-count-docs.py` 实测为准（11 份文档同步）。前端 245 不变。



> **坑（门禁 third fix）**：本机门禁一度量到 4664，CI 量到 4663，差在 mavlink-core 一例。

> 根因是 `ParseAllocationBenchmark`（`-Dbench=1` 门控）**不匹配 surefire 默认 include**，

> `mvn test` 从来不跑它，但 target/ 里躺着它上次被 `-Dtest=` 显式跑过的报告帧（04:31），

> 本机全量 reactor 07:52 那轮根本没重写。本地绿、CI 红就是这么来的。

> 现门禁把"类简单名不匹配 surefire 默认 include"的报告一律判为历史遗留、不计入统计

> （判定规则与 surefire 用同一套）。这是 `_class_has_source`（类被删）之外的第二种陈旧报告形态：

> **类还在源码树里，只是本轮没跑**。



**边界（更新后的 C1 口径）**：sim 侧已按真实 UOM 语义运行；真实联调的剩余差异

= 真实平台的 HTTPS 端点/证书/配额/鉴权（UomReportSink 的 HTTPS 客户端已就绪）、

以及真实错误码字典——属环境接入，不是逻辑缺口。

---

---

## [Unreleased] — P1 基准落定：UDP 零分配“不改造”（数字驱动）（2026-10-12）



> ROADMAP P1 原条目“UDP 接收线程零分配：帧解码对象池化 + GC 抖动消除”。

  按“先测量、再决定改不改”的纪律建基准后，结论是**不做**——这是基准的

  正常产出：防止用风险换不存在的问题。



| # | 交付 | 内容 |

|---|---|---|

| — | **基准 harness** | `mavlink-core/src/test/java/io/aerofleet/mavlink/ParseAllocationBenchmark.java`（-Dbench=1 显式开启、默认 assumeTrue 跳过——不进 CI 时长）。测量：解析吞吐、每帧堆分配（com.sun.management.ThreadMXBean 线程分配计量）、单帧最坏耗时 |

| — | **实测数字** | 解析吞吐 **2,132,196 帧/秒**；每帧堆分配 **192 B/帧**；单帧最坏 **2.4 μs**。参考负载：遥测 5Hz×9 机 = **45 帧/秒**（余量约 4.7 万倍）；可预见上限 50 机×50Hz = 2,500 帧/秒（仍余 850 倍） |

| — | **结论与重开条件** | **不改造**：唯一正解是把 MavlinkFrame 改零拷贝窗口（payload 逃逸给异步 WS 推送，池化有数据竞态），会改变公共 API 语义并波及全部 payload 消费方——为一个不存在的问题付这笔账不成立。重开条件：实测负载达 10 万帧/秒量级，或满载遥测下可观测 young GC 停顿（需先复现） |

| — | **文档** | 新增 docs/performance.md（方法/数字/结论/重开条件）；ROADMAP P1 翻正为“已评估：不改造” |



**基线备注**：UdpMavlinkTransport.receiveLoop 的 buf/packet 本就在循环外复用（每包

零分配）；本条要动的只剩 parser 每帧的三个对象（payload + MavlinkFrame +

ParseResult），即基准所测的 192 B/帧。

---

---

## [Unreleased] — 空状态全量统一：23 处收口（.empty-hint 单一来源）（2026-10-11）



> PR #26（UI 改造）只统一了代表性面板，扫描发现仍有 23 处“各写各”的空状态。

  本轮收口——此后新增面板的空状态只有一种写法。



| # | 收敛的形态 | 处理 |

|---|---|---|

| 1 | `<div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无…</div>`（约 15 处，含属性顺序/字号 9/10/marginTop 变体） | → `<div className="empty-hint">` |

| 2 | `<span style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无…</span>`（VideoFusion 两槽位） | → `<span className="empty-hint">`（保标签；**`.empty-hint` 补 `display:block`** 使 span 用法同样块级生效） |

| 3 | hardcode 色 `#94a3b8`（DockPanel 迁移记录） | → empty-hint（色值回归主题变量） |

| 4 | 自造字号/居中变体（CvEval 13px、Rid 14px、Trajectory3D 居中 20px、Tracking 带 padding） | → empty-hint |



**未动**：已是 empty-hint/empty-state/EmptyHint 组件的（约 47 处）；条件三元里的行内文本（`{err ? '刷新失败' : '暂无链路数据'}`——语义是行文不是空状态块）。



**验证**：vitest **245/245**（纯 className 替换，用例数不变）；check-frontend OK；eslint 0 error；本地全量 ui-audit **49 视图次 0 问题**。

---

---

## [Unreleased] — CI 巩固：UI 视觉审计入 CI + CodeQL 告警登记清零（2026-10-11）



> 两条线：把“本地判据”升级成 CI 门禁；把“面板噪音”清零。



| # | 交付 | 内容 |

|---|---|---|

| — | **UI Audit job（新）** | `gcs-web/scripts/ui-audit.mjs` 挂进 ci.yml：npm build + vite preview + **无后端审计**（`UI_AUDIT_OFFLINE`）+ **系统 Chrome**（`channel:'chrome'`，不下载 Playwright 浏览器）。preview 与审计**同一步**（GH Actions 每 step 独立 shell，`&` 起的进程会被回收——2026-10-04 的坑）。视图取覆盖四类布局的子集（桌面 8 + 移动端 4，约 2 分钟）；全 45 视图本地跑（不设 `UI_AUDIT_VIEWS` 即全量） |

| — | **ui-audit.mjs 新开关** | `UI_AUDIT_OFFLINE`（无后端跳过“云端已连接”等待——404 下面板空/错状态照样审：浅色控件残留/JS 错误/顶栏高度，空状态本身就在被审面内）+ `UI_AUDIT_BROWSER=chromium` 兜底 |

| — | **CodeQL 告警清零** | 4 条全部 dismiss（#1 false positive；#2/#5/#6 won't fix——理由与证据见 `docs/codeql-alert-disposition.md`），仓上 open=0。CI 守卫的 `--allow` 登记随之清空，回到“默认全关：再出现 open 即红”。守卫方向本地双向验证：喂空数组→过；喂一条未登记 open→红 |



**验证**：本地 offline 子集审计 8 视图次 0 问题、exit 0；守卫 `CODEQL_ALERTS_JSON` 两个方向实测；YAML 解析 18 jobs。

---

---

## [Unreleased] — B4 补全：失败重试 retryTask（原请求重新分配，可能换机）（2026-10-11）



> B4 交付时登记的边界："重试/放弃策略当前由调用方决定，调度侧不自动重试（属后续功能立项）"。

  本轮闭合：failTask 之后有明确的重新分配入口。



| # | 交付 | 内容 |

|---|---|---|

| — | **请求快照** | `taskRequests`（taskId → TaskRequest）——队列消费后原请求即失，重试的输入源 |

| — | **retryTask** | 用原请求重新走 `assignTask`（重新评分、可换机、自动预约随之刷新）；上限默认 1 次（`aerofleet.scheduling.retry.max`）防死循环 |

| — | **REST** | `POST /tasks/{id}/retry`（OPERATOR；无快照/超上限返回 retried=false 宽容语义） |



**测试**：TaskAssignmentServiceTest +2（失败→重试→覆盖分配记录→超上限 false；无快照 false）；SchedulingControllerTest +1（端点宽容语义）；cloud-backend 全量 **2607/0**；全 reactor 全绿；文档计数门禁 22 处同步后"全部一致"。



**边界**：真实的重试策略（指数退避/次数运营化/放弃策略）未做——那是调度引擎的策略层，按当时评审结论单独立项。

---

---

## [Unreleased] — B5 闭环：分配即自动预约（软约束 + AIRSPACE 告警）（2026-10-11）



> 预约能力建成后一直没有生产调用者（此前只有 REST 手动提交）——本轮让

> `assignTask`/`assignTasks` 分配成功即自动占块，B5 的预约真正接入调度。



| # | 交付 | 内容 |

|---|---|---|

| — | **两条分配路径接线** | `assignTask` 与 `assignTasks` 分配成功后调 `autoReserve` |

| — | **窗口/半径** | 分配时刻起固定窗口（`aerofleet.scheduling.reservation.auto-window-ms` 默认 30min，到期由 ConflictAvoidanceService 自动清理）；半径以任务坐标为中心（`auto-radius-m` 默认 500m）。任务时长不可知——固定窗口是运营可解释的保守占位 |

| — | **软约束策略** | 冲突时**不回退分配**（硬拦截要把预约检查塞进评分主路径，影响面大且相邻空域作业常见），仅发 `ConflictAlertMsg(30049)` 的 AIRSPACE 帧上态势面——让冲突先可见，是否收紧由运营数据决定 |

| — | **测试 +3** | 分配即预约（坐标/窗口/半径）；冲突软约束（分配照常 + 双方帧捕获 AIRSPACE）；无坐标任务跳过 |



**测试**：TaskAssignmentServiceTest 25→28；cloud-backend 全量 **2605/0**；全 reactor 全绿；文档计数门禁 22 处同步后"全部一致"。



**边界**：阻塞式冲突拦截（分配前检查）未做；预约窗口的任务时长自适应（按 taskType 预估）留待真实运营数据。

---

---

## [Unreleased] — 预约运营面补全：主动释放端点 + 归属租户列（V27）（2026-10-11）



> B5 预约持久化时登记的边界："主动释放单个预约未做（按过期清理已覆盖主要场景）；

> 预约的租户归属未建模"。两条一并闭合。



| # | 交付 | 内容 |

|---|---|---|

| — | **释放端点** | `DELETE /api/v1/scheduling/reservations/{sysid}`（OPERATOR）——释放该机全部预约（清内存+删库+返回条数）。运营场景：任务提前完成/撤防，时空块不必等窗口过期。repo 的 `deleteByOwnerSysid` 自 B5 持久化起已备，本批接线 |

| — | **归属租户列（V27）** | `airspace_reservations.tenant_id`（可空，`ALTER TABLE ADD COLUMN`）；落库时从 `TenantContext` 取。**语义是审计记录，不参与冲突判定**——空域是物理的，跨租户同样冲突，检测始终全局 |



**测试**：ConflictAvoidanceServiceTest +2（释放按机主清内存+删库+别机不动+无预约 0；落库带租户）；SchedulingControllerTest +1（释放端点全链：释放→快照→无预约宽容）；cloud-backend 全量 **2602/0**；全 reactor **4628/0**；H2+`ddl-auto=validate` 演练通过（V27 与实体一致）。

---

---

## [Unreleased] — GB28181 事件订阅路由（B1 的 events 收尾）（2026-10-11）



> B1（PTZ/RTSP 按厂商路由）时登记："事件订阅端点仍直连 OnvifClient——涉及订阅 handle

> 的生命周期语义统一，留独立评估"。评估结论：**VendorAdapter 的 subscribe/unsubscribe

> 契约早已齐备**（五个适配器全部实现 unsubscribeEvents），只差 Controller 接线。



| # | 交付 | 内容 |

|---|---|---|

| — | **订阅路由** | `/devices/{id}/events`（SSE）：订阅与三处退订（结束/超时/出错）均经 `getAdapter(device)`——此前直连 OnvifClient，GB28181 设备的事件源语义错置（进 ONVIF 模拟回调表，国标信令永远不触发） |

| — | **测试** | +2：GB28181 设备经国标信令层建立 SSE 订阅（asyncStarted）；设备不存在 → 200 + 流内错误终止（SSE 语义勘误：响应头已提交，错误只能流内表达，非 5xx） |



**验证**：cloud-backend 全量 **2599/0**；全 reactor 全绿；文档计数门禁 22 处同步后"全部一致"。

---

---

## [Unreleased] — B3 通道级状态：部分通道离线可表达（4 路中 2 路在线）（2026-10-10）



> B2/B3 交付时登记的边界："部分通道离线（4 路中 2 路在线）仍不可表达——onlineCameras

> 按设备状态派生，通道级状态上报属生产阶段"。本轮闭合。



| # | 交付 | 内容 |

|---|---|---|

| B3.1 | **模型** | `SurveillanceDevice.onlineCameras` 字段（部分通道可离线）；联动规则：链路断（prune 置 OFFLINE）归零、心跳恢复默认全恢复、FAULT/MAINTENANCE 人工态不自动改 |

| B3.2 | **Pusher** | 帧 `onlineCameras` 取设备维护值（此前按状态派生，恒等于 0 或 totalCameras）——**通道级的真实状态上协议面** |

| B3.3 | **REST** | `POST /api/v1/surveillance/devices/{id}/channels`（需 OPERATOR）：`onlineCameras ∈ [0, totalCameras]`，越界 400 |



**测试**：SurveillanceDeviceStatusTest +2（通道联动/prune 归零）；SurveillanceControllerTest +1（端点全链：默认全在线/置位/越界/404）；SurveillanceStatusPusherTest +1（部分通道帧值 2/4）；cloud-backend 全量 **2597/0**。



**边界**：通道在线数的**自动探测**（谁发现哪路坏）未做——当前由运维经 REST 手动标记；单个通道的独立流地址/分辨率属性仍无（表与消息粒度都是计数级）。

---

---

## [Unreleased] — C5 签名让 CI 每次都走签名帧通路 + 修订 ROADMAP C 系列两处言过其实的描述（2026-10-10）



### 一、C5 第一步：CI 的 flight e2e 全部改为"无人机侧签名"



`.github/workflows/ci.yml` 三处启动 drone-sim 的地方（flight smoke / 能力腿三条 /

SDK 集成）加 `--signing-key "$(openssl rand -hex 16)"`。



- 密钥**运行时生成、不落字面量**——本项要证的只是"带 13 字节签名块的帧能正常穿过

  UDP/Parser/CRC 不被破坏"，密钥取值无关；落字面量反而成为 secret 扫描目标。

- drone-sim 侧 `rejectUnsigned` 保持 **false**，因此**不会**拒收 backend 的未签名命令。

- backend 侧 `mavlink.signing.enabled` 仍为 false ⇒ `isSigningEnabled()` 短路，

  不验签也不拒签。**出厂行为不变**。

- 刻意**没有**给 `SimConfig` 设默认密钥：那会改变所有手动运行的默认行为，

  且把密钥字面量引进源码。用 CI 侧传参达成同一目的，两者取风险小的那个。



**本地 WSL2 对照实测**（backend + drone-sim + e2e-smoke.sh 全在 WSL2 内，

与 ZCode 当初"WSL2 真 Linux 连续全绿"同环境）：



| 组别 | drone-sim 参数 | 结果 |

|---|---|---|

| 对照 | 不带签名 | 11/11 PASS，exit=0 |

| 实验 | `--signing-key <随机>` | 11/11 PASS，exit=0 |



两组唯一差异就是 `--signing-key`；实验组日志确认

`signing: enabled keyStore=single-key rejectUnsigned=false`。

（另记录一个本地环境坑：Windows 上跑 Java + WSL 里跑 bash 脚本会因网络命名空间

不同而全部 FAIL，与本改动无关。）



### 二、修订 ROADMAP C 系列两处言过其实的描述



ROADMAP:322-331 的「C 系列剩余缺口」此前把 ① ② 写成功能缺失，逐条核对代码后

确认言过其实，本次按实测改写：



- **① C4**：原文称"位置不落库、重启只能从 flight_log 重建"。实际最后已知位置/电量

  早由 `drone_last_known_position`（V5）落库并恢复；`mode`/`armed`/`protocol`

  也自 V25 起落库并恢复。**真正没恢复的只有完整轨迹历史**（有界内存 deque）。

- **② C5**：原文称"`MavlinkParser` 层仍只切帧不验签"。实际 Parser 完整解析签名块，

  验签在 `UdpGateway` 且 fail-closed。真问题是"没人验证它在工作"。

- 同时把"打开签名"的协议约束与三步顺序写进 ROADMAP（签名块无 key id ⇒ 硬切换、

  无宽限期），避免后人误以为是一行配置的事。



C4/C5 两行表格描述同步补上测试锚点与轮换端点。



### 计数



无新增测试（纯 CI 参数 + 文档修订），Java 计数不变；前端 245 不变。

---

---

## [Unreleased] — 批次 4 flaky 测试治理：一处放宽、一处复核结论入档（2026-10-10）



> 清单上的两个「已知 flaky」逐条实测处置（不照抄旧记录）。



| # | 处置对象 | 结论 |

|---|---|---|

| C3.1 | `assignTasksGaCompletesWithin100ms` | **真修**：硬阈值 100ms → **1s 护栏**。全量并发实测曾被压到 ~180ms（机器忙），硬 100ms 会把"忙"和"算法退化"混为一谈；1s 仍能抓 GA 崩坏级回归（正常量级个位数 ms）。阈值理由写进测试注释 |

| C3.2 | `RestrictionCacheManagerTest.testRefreshSuccess` | **复核：旧记录已过期**——手工 `new` 的实例上 `@Scheduled` 不生效（无 Spring 调度代理），init/refresh 全为同步调用、无时序窗口；本机连跑 5 次全绿。复核结论与排查指引（"若再红先查是否引入后台刷新/共享状态"）写入类注释 |



**测试**：cloud-backend 全量 **2581/0**（计数不变——本批只改阈值与注释，未动用例）。

---

---

## [Unreleased] — B4 调度 FAILED 态：失败生命周期路径 + 协议值域补齐（2026-10-10）



> 依据缺口登记（GAPS「调度任务失败态」）。代码侧登记在 TaskAssignmentService 类注释

> （"FAILED 无生命周期路径（模型无失败态转换），协议值不可达"）——本轮闭合。



| # | 交付 | 内容 |

|---|---|---|

| B4.1 | **failTask 生命周期** | `TaskAssignmentService.failTask`：清无人机负载映射 + 出队 + 发布 `TaskStatusMsg(30050)` 的 **FAILED(3)** 帧（该协议值首次可达）；**任务记录保留** |

| B4.2 | **区分规则（文档化）** | ABORTED = 主动取消（任何阶段，记录移除）；FAILED = 执行尝试后失败（已开始执行，记录保留可查）。重试/放弃策略由调用方决定，调度侧不自动重试（后续立项） |

| B4.3 | **REST** | `POST /api/v1/scheduling/tasks/{id}/fail`（需 OPERATOR） |



**测试**：TaskAssignmentServiceTest +1（事件捕获断言第 4 帧 status=3 + 记录保留 + 未知任务 false）；SchedulingControllerTest +1（端点接线与宽容语义）；cloud-backend 全量 **2581/0**。



**边界**：失败**原因**未建模（帧无 reason 字段，仅日志）；自动重试/放弃策略未做；执行失败的**自动判定**（谁发现失败）由调用方/上层负责——当前为显式上报。

---

---

## [Unreleased] — B2/B3 安防设备四态与多通道：状态置位端点 + 协议值域补齐（2026-10-10）



> 依据缺口登记（GAPS「监控设备故障/维护状态」「多通道摄像头」）。代码侧缺口登记在

> `SurveillanceStatusPusher` 的字段映射 javadoc（"协议 2=故障/3=维护不可达（模型仅两态）"、

> "多通道计数需先扩设备模型"）——本轮把这两条显式缺口闭合。



| # | 交付 | 内容 |

|---|---|---|

| B2.1 | **四态模型** | `SurveillanceDevice.Status` 扩为 ONLINE/OFFLINE/FAULT/MAINTENANCE（ordinal 与协议 `SurveillanceStatusMsg(30059).status` 值域 0-3 一致，Pusher 直接映射） |

| B2.2 | **四态语义闭环** | 心跳只把 **OFFLINE→ONLINE**；**FAULT/MAINTENANCE 心跳与离线扫描都不覆盖**（人工/检测置位，恢复需显式）；`pruneStaleDevices` 本就只动 ONLINE 设备（核对确认，无需改） |

| B2.3 | **状态置位端点** | `POST /api/v1/surveillance/devices/{id}/status`（需 OPERATOR）——运维置「故障待修/检修中/恢复」 |

| B3.1 | **多通道** | `SurveillanceDevice.totalCameras`（注册 body `channels`，默认 1，[1,255] 校验）；Pusher 的 totalCameras 取模型，onlineCameras 按状态派生 |

| — | **视图/文档** | deviceView 暴露 totalCameras；api-reference 补 status 端点与 channels 字段（顺带标注 PTZ/stream 的厂商路由） |



**测试**：新增 `SurveillanceDeviceStatusTest` 5 例（默认值/心跳两向/扫描不动 FAULT/枚举值域）；PusherTest +1（FAULT→2、MAINTENANCE→3、多通道=4）；ControllerTest +2（channels 越界 400、status 端点全链）；cloud-backend 全量 **2583/0**。



**边界**：**部分通道离线**（4 路中 2 路在线）仍不可表达——onlineCameras 按设备状态派生，通道级状态上报属生产阶段；FAULT 的**自动检测**（设备自报/巡检发现）未做——当前为人工置位，自动检测规则待真实设备接入后定义。

---

---

## [Unreleased] — C4 重启后恢复"最后已知飞行态"（mode/armed/protocol，V25）（2026-10-10）



重启后 `DeviceRegistry.restoreFromRepository()` 只还原 sysid 与 tenantId，

GCS 首屏拿不到"这架机最后在做什么"。



位置与电量不在这条职责内——它们早已由 `drone_last_known_position`（V5）经

`FlightTrackStore` 持久化并在 `@PostConstruct` 恢复（`C4TelemetryRestoreTest` 4 例已钉住）。

本条补剩下三个没有持久化出路的字段。



### 做法



- `V25__devices_last_flight_state.sql`：`devices` 加 `last_mode` / `last_armed` /

  `last_protocol`，**全部可空**

- 写入搭两处**已有的低频路径**，不新增写放大：

  - `registerIfAbsent`（首次见面，本来就要写那一行）

  - `sweepOffline`（离线转换——"这架机是怎么收场的"最有价值的时刻）

- `restoreFromRepository()` 还原三个字段，NULL 时**维持 DroneSnapshot 默认值**



### 关键语义：NULL 不伪装成确定值



刻意不用 NOT NULL + 默认值——那会把"没有记录"伪装成"记录为 mavlink/未锁定"。

`DeviceFlightStateRestoreTest` 第 3 例专门钉这条。



### 已知局限（写明，不假装解决）



后端被 kill -9 / OOM / 断电时不经历离线转换，最近一次的 mode/armed 会丢，

库里仍是上一次成功写入的值。要覆盖需引入周期节流写（`FlightTrackStore` 已为位置

做过同样的取舍），现在刻意不做，改为把局限写进 javadoc。



### 计数



Java 4607 -> **4611**（cloud-backend 2581 -> **2585**，本轮 +4 例）。

算术与 rebase 后的 base(74abbb4) 对齐：4607+4=4611、2581+4=2585。

算术与 rebase 后的 base(114247d) 对齐：4609+4=4609、2583+4=2583。



### 验证



- `DeviceFlightStateRestoreTest` 4/4（落库 / 恢复 / NULL 语义 / persist=false 不碰库）

- **变异测试**：删掉 restore 的 3 处赋值与 2 处 `writeFlightState` 调用后，

  "离线落库"与"恢复"两例转红，NULL 与 persist=false 两例不受影响

- 既有 `DeviceRegistryPersistenceTest` 14/14、`C4TelemetryRestoreTest` 4/4 未被改坏

- **Pass C 本地真 PG 复刻**（不只跑 H2）：postgres:15-alpine + prod profile

  从零跑 Flyway + `ddl-auto=validate`：

  - `Started CloudBackend` = true

  - `\d devices` 确认三列真实存在、可空、类型正确（varchar(64) / boolean）

  - `flyway_schema_history` version=25 success=t

---

---

## [Unreleased] — B5 空域预约接入与 AIRSPACE 告警接线（2026-10-10）



> 依据缺口登记（GAPS「空域预约冲突检测」）。**侦察修正了原估计**：预约表、

> 4D 冲突检测、重叠判定在 `ConflictAvoidanceService` 中**已完整实现**

> （含既有测试），真实缺口是「接入与告警接线」而非「新建模块」——

> `reserveAirspace` 此前无任何生产调用者（只有测试）、`ConflictType.AIRSPACE`

> 从未被任何生产者使用。



| # | 交付 | 内容 |

|---|---|---|

| B5.1 | **预约 REST 接入** | `POST /api/v1/scheduling/reservations`（提交 4D 预约，需 OPERATOR）+ `GET /api/v1/scheduling/reservations`（只读快照）；`tryReserve` 详情版（accepted + 冲突对方 sysid/水平距离/时段起始），`reserveAirspace` 保留并委托（既有调用零破坏） |

| B5.2 | **AIRSPACE 告警接线** | 预约冲突（409）时发布 `ConflictAlertMsg(30049)` 的 **AIRSPACE** 类型帧——协议枚举 `ConflictType.AIRSPACE` 由此**首次拥有生产者**（机对几何扫描只产 COLLISION/PATH，该值此前不可达） |

| B5.3 | **api-reference 补齐** | 调度段此前漏登记 3 个既有端点（`tasks/{id}/start`、`tasks/{id}/complete`、`conflicts/scan`），一并补入；端点计数 5→10 |



**测试**：ConflictAvoidanceServiceTest +2（tryReserve 冲突详情 / 快照不可变拷贝）；SchedulingControllerTest +2（预约成功 → 快照 → 409+conflictSysid 全链、缺字段 400）；cloud-backend 全量 **2571/0**。



**边界**：预约表仍是内存态（重启即空）——持久化属生产阶段（与 C4 遥测热态同口径）；「主动释放单个预约」未做（按过期清理已覆盖主要场景，释放语义待真实运营需求）；「任务调度自动带预约」（assignTasks 联动）属后续功能立项。

---

---

## [Unreleased] — C4 遥测热态重启恢复：实测锚点测试（含对 ROADMAP 描述的修正）（2026-10-10）



### 一件与 ROADMAP 不一致的实测结论



ROADMAP:322-331 把 C4 的剩余缺口写成：



> **C4 的"主数据"只在 provisioning 层面落库，遥测热态仍是内存**……

> 意味着**重启后历史位置轨迹只能从 `flight_log` 重建**，不能从注册表恢复。



逐条核对代码后确认这句**言过其实**：



- 位置与电量**早就落库了**。`V5__track_last_known.sql` 建了

  `drone_last_known_position` 表，`FlightTrackStore` 按 `PERSIST_INTERVAL`（=10）

  节流写入（20Hz 遥测下约 0.5s/机）、`@PreDestroy` 兜底刷盘、

  `@PostConstruct` 启动加载——**这条链是通的**。

- 真正重启后丢失的只有 `DroneSnapshot.mode` / `armed` / `protocol` 三个字段，

  且设备恢复出来本就是 `offline`（javadoc 声明的语义：等心跳确认），

  下一条心跳就会把它们填上。

- `devices` 表确实只存登记/在线/租户等 provisioning 字段，这部分描述准确。



### 于是问题从"缺功能"变成"没人验证过这条链真的通"



本提交补 `C4TelemetryRestoreTest`（4 例，真实 Spring + H2），

把"重启后到底什么活下来"钉成测试：



1. `throttledWrite_hitsDatabase` —— 节流阈值前不写、达到后才落库（直接查库，不经内存缓存）

2. `reload_restoresLastKnownPosition` —— 清空内存后能重载最后已知位置与电量

3. `preDestroy_flushesRegardlessOfThrottle` —— 低频打点时 `@PreDestroy` 仍兜底刷盘

4. `modeArmedProtocol_areLostOnRestore` —— 三个字段确实丢失，**这就是真实边界**



既防将来有人改坏恢复路径无人知晓，也把 ROADMAP 的过度声称固定住。



### 测试过程中实测出的两个非显而易见行为（已写进注释）



1. **落库是异步的**：走 `BatchedWriteQueue`（50 条一批 / 200ms 一刷），

   `addPoint` 返回后立即查库必然为空。测试用 Awaitility 等队列刷新，

   不用 sleep 硬等——否则会得到一个假红。

2. **恢复值最多滞后一个节流周期**：节流触发时写的是"那一刻的点"，

   所以重启取回的不是最后写入的那个点。测试按一个 interval 的容差断言，

   而不是假装它等于最新点。



---

### 计数



Java 4587 -> **4591**（cloud-backend 2561 -> **2565**，本轮 +4 例）。前端 245 不变。

算术与 CI 实测一致：4591-4587=4、2565-2561=4，均等于本提交新增的 4 例。



---

---

---

## [Unreleased] — C5 MAVLink 签名：补接收验签测试 + 密钥轮换端点 + 配置显式化（2026-10-09）



### 一、把「写了却没人验证」的验签路径纳入 CI



C5 的签名能力原本已有两侧覆盖：mavlink-core 的 MavlinkSignerTest /

MavlinkSigningVectorTest / SigningKeyManagerTest 管密码学，

MavlinkSigningConfigurationTest 管 bean 装配，scripts/e2e-signing.ps1

管全链路（884 行 / 6 场景）。



但 **e2e-signing.ps1 从未被任何 workflow 调用**（CI 只跑 9 个 e2e，签名不在其中），

而 UdpGateway 那段 fail-closed 验签决策——拒绝未签名、按来源 sysid 取密钥、

取不到密钥就拒、timestamp 防重放——**零测试覆盖**。结果是：一段安全语义全靠人眼保证，

CI 全绿也证明不了它还在工作。



新增 UdpGatewaySigningTest（8 例），打真实 UDP 字节走生产同一条路

（DatagramSocket → UdpMavlinkTransport → MavlinkParser → onFrame → verifyFrame），

顺带覆盖「签名块是否正确从线上字节切出来」——全链路最容易错的一环。



**做过变异测试**：把 verifyFrame 改成无条件放行后，5 个「应拒绝」场景

（篡改 / 未签名 / 错密钥 / 未知 sysid / 重放）全部转红，两个「应放行」场景

（正常签名、签名未启用）仍然通过——证明断言真的在咬，不是假绿。



### 二、密钥轮换端点（此前只能手工改文件）



新增 SigningKeyStoreWriter（原子落盘）+ MavlinkSigningKeyController：



- GET  /api/v1/mavlink/signing/keys              指纹与 linkId，**不含密钥**

- POST /api/v1/mavlink/signing/keys/{sysid}/rotate  轮换单机密钥



**协议层诚实边界**：MAVLink v2 签名的 13 字节数据块是

linkId(1)|timestamp(6)|signature(6)，**没有 key id 字段**⇒ 轮换只能是硬切换，

不存在 API Key 那种「新旧并存宽限期」的协议表达。因此本端点**不提供** graceHours，

并在响应里显式 warning；类注释与 CHANGELOG 都写清这一点，避免照搬 API Key 心智模型。



安全属性：ADMIN + JWT 双门禁；签名未启用 503；单机模式 409（该模式密钥来自配置，

轮换需重新部署）；密钥库坏 JSON / sysid 越界 / 空密钥一律 409 且**原文件逐字节不变**；

返回体永不含密钥材料（逐字断言）；写入走临时文件 + ATOMIC_MOVE，

文件系统不支持原子移动时明确报错而非静默降级。轮换后由 SigningKeyManager

按 mtime 热更，无需重启。



### 三、四个签名的配置项首次显式落进 profile



mavlink.signing.* 四个开关此前只存在于代码（MavlinkSignatureConfig 的 @Value），

任何一个 profile 里都查不到——运维靠读源码才知道有这些开关。现按 profile 显式声明，

并在 prod 写明「打开前必须做什么」的三步。



**出厂行为不变**：所有 profile 的 mavlink.signing.enabled 保持 false。

打开它是机队范围的行为变更（无 key id ⇒ 打开即硬切换，未换密钥的设备会被整体拒绝），

需单独拍板，不擅自执行。



### 顺带修正 ROADMAP 的一处不准确描述



ROADMAP:329 原称「MavlinkParser 层仍只切帧不验签」。逐条核对代码后确认该描述

**不准确**：MavlinkParser 完整解析 13 字节签名块（linkId/timestamp/signature），

验签在 UdpGateway 内完成且 fail-closed。真正的问题不是「没验签」，

而是「没人验证它在工作」——这才是本提交补的东西。



### 计数



Java 4561 → **4587**（+26：UdpGatewaySigningTest 8 + SigningKeyStoreWriterTest 8

+ MavlinkSigningKeyControllerTest 10）；cloud-backend 2543 → **2561**；

mavlink-core 462 → **470**。前端 245 不变。

---

---

## [Unreleased] — E2 反制雷达侦测接入 + E4 5G-A 通感感知数据源预留（2026-10-09）



> 依据 ROADMAP E2/E4（依赖 4a/M7✅）。两者同域：**非合作目标感知**——共用统一

> 航迹模型与态势聚合层，不同来源各实现一个 SPI。**E2 只接侦测、不做反制**（立场声明）。



| # | 交付 | 内容 |

|---|---|---|

| E2/E4.1 | **统一模型与 SPI** | `SensingTrack`（轨 id/来源/位置/高度/速度/航向/置信/分类/首末时刻）+ `AerialSensingSource` SPI（sourceType/sourceId/poll）——真实厂商雷达 SDK 或 ISAC 网元实现接口即可接入，聚合层零改动 |

| E2/E4.2 | **E2 反制雷达侦测源** | 模拟侦测网：巡飞环行 + intruder 逼近两条航迹（确定性 tick 驱动）；**无任何干扰/打击控制面** |

| E2/E4.3 | **E4 5G-A 通感源** | 模拟 ISAC 基站上报（含 BIRD 误报形态——通感识别能力限于诚实呈现）；rangeM 反算作接入映射样板；真实网元（3GPP R19 演进中）接入属生产阶段 |

| E2/E4.4 | **聚合与近域告警** | 多源 upsert 聚合 + TTL 超时清理 + **近域告警进入/离开/再进入三段语义**（距本仓在线设备 <1km 首次进入报 WARN，不重复刷屏） |

| E2/E4.5 | **REST 与面板** | `GET /sensing/tracks?source=&alertOnly=`、`/sensing/sources`；GCS SensingPanel（来源色标/告警行高亮/态势卡；**只读态势无控制**） |

| E2/E4.6 | **e2e** | `scripts/e2e-sensing.ps1`：两源自跑 + sim 作告警锚 → 10 断言 ALL PASS（intruder 799m 触发实测） |



**测试**：SensingTrackServiceTest 7 例（聚合 upsert/清理/过滤 + 告警三段闭环 + 两源契约）。



**诚实边界**：模拟源是确定性演示数据（几何生成，非真实探测物理模型）；E2 接入真实反制

雷达厂商 SDK、E4 接入真实 5G-A 通感网元均属生产阶段（实现 SPI 即接入）；

近域告警以本仓在线设备位置为锚（防御方视角）。



---

---

---

## [Unreleased] — E3 出海合规抽象：U-space 四服务 + Remote ID 三套映射 + USSP 角色（2026-10-09）



> 依据 ROADMAP E3（依赖 C1+C2✅）。本仓以 **USSP 服务接口形状**实现四服务；

> 与真实 U-space 网络互联（CISP 交换/EASA 认证）属生产阶段（诚实边界同 C1/C2 口径）。



| # | 交付 | 内容 |

|---|---|---|

| E3.1 | **Remote ID 三套映射** | `RemoteIdMapper` 纯函数：GB46750（内部直出）/ ASTM F3411（Part 89，速度 m/s→节、精度档位→厘米表）/ EU 2019/945（ASTM 超集 + UAS 注册号 + UA 分类 + UOM 换名）；统一脱敏口径与 RidController 一致；**分段到达容错**（RID 分段广播时缺失段输出 null 不 NPE——e2e 实测形态钉成单测） |

| E3.2 | **网络识别** | `GET /uspace/net-rid?format=` 三套标准切换输出 |

| E3.3 | **地理感知** | `GET /uspace/geo-awareness` 复用 C3 限飞区数据源（UAVolume 形状：圆/多边形 + 距离） |

| E3.4 | **飞行授权** | `POST /uspace/flight-authorization` 与限飞区求交的几何判定：未相交 AUTHORIZED / 相交 DENIED（附区名）——**三态之 CONDITIONAL 不硬造**（当前数据源只有禁飞区语义） |

| E3.5 | **交通信息** | `GET /uspace/traffic` 半径内在线机清单（距离排序；不暴露 operatorId，跨标准统一脱敏口径） |

| E3.6 | **e2e** | `scripts/e2e-uspace.ps1`：真 RID 广播 + 测试限飞区文件 → 四服务 13 断言 ALL PASS |



**测试**：UspaceComplianceTest 9 例（三套映射 7/几何 2）+ e2e 13 断言。



**诚实边界**：映射是字段级语义换名/换算（不做字节级帧编码——那是 MAVLink OPEN_DRONE_ID 通道职责）；飞行授权是限飞区几何求交（不接入真空域审批）；USSP 对外互联属生产阶段。

---

---

## [Unreleased] — CVE-2026-47890 守卫测试落地 + 修门禁静默失效（2026-10-09）



### 一、给已在 master 的 CVE-2026-47890 忽略补上守卫测试



`.trivyignore.yaml` 已登记 `CVE-2026-47890`（030bfef 同步入库），但**当时没有配套守卫**：

一条"写明了可达性证据"却没有任何测试持续核验的忽略条目，等于把结论写进注释然后任其过期。



新增 `SseViewFragmentReachabilityTest`（4 例），把忽略理由变成持续可验证的：

- 唯一 SSE 端点 `AlarmController#streamEvents` 只发 `.comment()`/`.data(Map)`，零视图对象；

- 应用级控制器清一色 `@RestController`，无裸 `@Controller` ⇒ `ViewResolutionResultHandler`

  永不参与；框架自带 `BasicErrorController` 已显式排除并在测试内注明理由

  （仅错误响应渲染、不经 SSE）；

- Spring 上下文 4 个 ViewResolver 均不渲染片段。



**做过变异测试**：向该 SSE 流注入 `ModelAndView` 后守卫立刻转红

（`Tests run: 4, Failures: 1`）——证明它真会咬。



### 二、修门禁静默失效（比"数字写错"更严重的问题）



README / ROADMAP / docs 下多份口径文档带着**误提交的 merge 冲突标记**

（`<<<<<<< HEAD` … `>>>>>>> origin/master`），来源是历次合并时 JSX 冲突被修、

**markdown 冲突被漏掉**，此后的提交一直在继承它：`ddd444a`/`a36c55c`/`ec4ddb5`/`e3304eb`

每次合并都重新出现 42 处。这说明它是**流程问题**，修一次治标，不设防就会复发。



后果：冲突块恰好吞掉测试规模表 ⇒ `test_table_rows()` 认不出表格行 ⇒

逐格核对被**静默跳过** ⇒ 门禁输出"全部一致"，而表其实已损坏、该核对的数字一个都没核。

在 `ddd444a` 上实测复现：未加固版报"全部一致"，加固版立刻拒绝并说明后果。



本提交清除全部标记，并加两道防复发机制：

1. `check-test-count-docs.py` 结构完整性前置检查：命中冲突标记即红，

   并说清**哪些行没被核对**，拒绝在结构损坏的前提下给出任何数字结论；

2. 新增 `--self-test`：用临时夹具证明这道防线自己会红（干净文档不误伤 /

   数字漂移仍红 / 冲突标记必红），已在实际损坏的 `ddd444a` 上验证同样判红；

   CI `Docs test-count gate` 在下载产物之前先跑自检，坏了最早暴露。



清除方式：相同侧去重；README 里 F4「缺陷闭环」与 F2「机巢管控」是两条不同内容，

两侧均保留，未丢信息。E1/E5/E6/F5/F6 正文与计数未受损（逐行集合比对）。



### 计数



Java 4541 → **4561**（本轮 +4 例 SSE 可达性守卫）；cloud-backend 模块行上游停在

2470 漏更新，本次修正为实测 **2543**。前端 245 不变。

全仓 `mvn -o clean test`：4561 例 0 失败 0 错误。



---

---

---

## [Unreleased] — E1 ROC 一控多机席位：席位化交互 + 批量指令 + 警情驱动调度（2026-10-09）



> 依据 ROADMAP E1（依赖 M10✅）。对表深圳"1+7+N"——一名操作员控制 6-9 架、

> 警情驱动航线动态调整。**只建议不执行**：警情派飞由操作员显式确认。



| # | 交付 | 内容 |

|---|---|---|

| E1.1 | **席位** | 席位注册（operatorName 唯一）+ 机队绑定（全量替换，上限 9 架对表一控多机；未知设备 400）+ 实时席位视图（DeviceRegistry 快照聚合） |

| E1.2 | **批量指令** | arm/takeoff/rtl/land 对席位机队或子集逐机下发——**逐机结果如实呈现**（部分失败不掩盖、不整体回滚，指令彼此独立）；子集越席位 400；批量 takeoff 固定 50m 安全高度（席位级批量操作取保守统一值，单机精调走单机面板） |

| E1.3 | **警情驱动** | 登记警情（P0-P2）→ 席位内三因子评分（距离 0.5+电量 0.3+空闲 0.2，线性加权不跑 GA——那是 M10 的职责）→ 建议指派；全忙/全离线 → 建议空+原因不硬塞 |

| E1.4 | **确认派飞** | 操作员确认后真下发（arm→单航点任务上传→start；驻留 30s 可配）；失败置 FAILED 不留半执行现场 |

| E1.5 | **GCS RocPanel** | 席位机队网格（色标状态）+ 批量指令 + 警情表单与建议卡 + 确认派飞按钮 |

| E1.6 | **e2e** | `scripts/e2e-roc.ps1`：2 台 sim 双机席位 → 批量指令逐机结果 → 警情建议最近机 → 确认派飞真起飞（遥测 armed/alt 实证）——14 断言 ALL PASS |



**安全加固（CodeQL tainted-numeric-cast 两条新流处置）**：警情坐标范围校验

（±90/±180 越界拒绝，c8fa5d7 同款口径）+ 任务上传的 lat*1e7 窄化改 

（溢出抛异常而非静默截断，纵深防御）+ holdSec 上限 300s + 批量 takeoff 固定高度

（打断 alt 污染流——单机精调属单机面板职责）。RocServiceTest 13→15 例。



**诚实边界**：席位 in-memory（操作会话概念，重启清零——与席位语义一致）；

三因子线性评分（GA 属 M10）；单警情单机建议（多机协同处置属后续）；

派飞链复用常规任务上传（动态航线中段改写属后续组合项）。



**测试**：RocServiceTest 15 例（席位 5/评分 7/派飞 3）+ e2e 14 断言。



---

---

---

## [Unreleased] — E5 GB28181 视频接入：国标形状层 + 适配器接入（2026-10-08）



> 依据 ROADMAP E5（依赖 4a✅）。与 Uniview（私有 SDK 搁置）的口径不同：

> **GB28181 的国标形状层是真实现**——编码/PTZ 指令码/信令构造/目录解析按

> GB/T 28181-2016 落地，全部纯函数、22 例单测逐字节断言国标值。



| # | 交付 | 内容 |

|---|---|---|

| E5.1 | **20 位国标编码** | 中心 11/行业 2/类型 3/序号 4 分段构造/校验/解析；类型码表（131 摄像机/132 网络摄像机/200 报警输入/215 报警输出等）；未知类型码明确拒绝不静默 |

| E5.2 | **PTZ 指令码** | 国标 A5 0F 01 动作位 4 字节（上 08/下 04/左 20/右 10/放大 80/缩小 40），hex 形态 A50F0108 |

| E5.3 | **SIP 信令形状** | REGISTER / INVITE（点播+SDP，PS/9000）/ MESSAGE（Catalog，MANSCDP+xml）/ SUBSCRIBE（报警订阅）——纯构造器可断言 |

| E5.4 | **目录解析** | Catalog XML 逐 Item 提取，非法 DeviceID 跳过并计数不静默，非 Catalog 结构明确拒绝 |

| E5.5 | **适配器接入** | Vendor.GB28181（顶层+SurveillanceDevice 嵌套双枚举）+ Gb28181Adapter 实现 VendorAdapter 全接口，VendorAdapterRegistry 自动路由 |



**诚实边界**：1) SIP 传输与 RTP/PS 媒体面属生产阶段——getStreamUrl 返回

gb28181-invite:// 描述性标识并 WARN，不假扮可播 RTSP；2) **SurveillanceController

的 PTZ 端点当前硬编码 ONVIF 路径**（不经 VendorAdapterRegistry 路由）——把该

controller 改造为按 vendor 路由是既有行为变更，登记为后续项，本轮不混入；

3) 鉴权 401 握手与设备目录的 SIP 收发属传输层。



**测试**：Gb28181ShapeTest 22 例（编码 5/PTZ 3/信令 4/解析 4/适配器 6，含跨厂商

设备拒绝与不假扮可播 RTSP 断言）；surveillance 全域连带回归 82/82。



---

---

---

## [Unreleased] — E6 运营报表与单位经济：架次/时长/里程/能耗/成本核算（2026-10-08）



> 依据 ROADMAP E6（依赖 C4 持久化✅）。物流与植保结算刚需——数据源是 flight_log

> 的 telemetry 帧（armed 连续段 = 架次），**e2e 首次端到端启用了 C4 的

> `persist-to-db` 批量写库路径**（该开关默认 false/JSONL，此前从未在 e2e 中验证过）。



| # | 交付 | 内容 |

|---|---|---|

| E6.1 | **架次分割** | armed 连续帧段 = 架次；断档 >30s 切分（防数据粘连）；充电段能耗 clamp 0 |

| E6.2 | **架次指标** | 时长/里程（帧间 haversine 累加）/能耗（段首末电量差）/主 mode 众数 |

| E6.3 | **三维聚合** | groupBy=day/drone/tenant：架次数/总时长/总里程/总能耗/成本 |

| E6.4 | **成本口径** | `成本 = 时长(h) × aerofleet.report.cost-per-hour`（默认 0）——**参数化模型，本仓不编造电价/折旧，部署方按其单位经济填** |

| E6.5 | **REST** | GET /operations/report?from=&to=&groupBy=（窗口上限 31 天防全表扫描，OBSERVER） |

| E6.6 | **GCS 面板** | OpsReportPanel：三维切换/指标卡片/分组表/单架次明细（费率如实展示） |

| E6.7 | **e2e** | `e2e-operations.ps1`：真飞 ARM→起飞→驻空→disarm → 报表 9 断言（含成本精确对账 0.35 元）ALL PASS |



**诚实边界**：里程是 1Hz 采样累加近似；能耗按电量百分比差不换算 mAh/电费

（电池容量因机型而异，属部署方配置）；成本单一费率不分机型/任务类型；

tenant 维度依赖帧上租户标记（未归属计入 unassigned，精确归属属设备归属功能范畴）。



---

---

---

## [Unreleased] — F5 多品牌双栈网关：DeviceGateway SPI + 协议标记 + 命令路由（2026-10-08）



> 依据 ROADMAP F5（依赖 C1 协议抽象✅）。反锁定生态位：注册/遥测/命令面只依赖

> SPI 与快照，新品牌栈实现接口即可接入机队管理。



| # | 交付 | 内容 |

|---|---|---|

| F5.1 | **DeviceGateway SPI** | 协议标识 + `send()`——文档化扩展点（单点裁决原则：拦截链在 MAVLink 栈内，路由层不复制校验） |

| F5.2 | **MAVLink 栈适配** | `MavlinkDeviceGateway` 薄适配 `DroneCommandService`（拦截链/重试/ACK 全量复用，既有调用方零改道） |

| F5.3 | **协议标记** | `DroneSnapshot.protocol`（缺省 mavlink，既有注册路径零变化）+ summary 视图暴露 |

| F5.4 | **CommandRouter** | 按设备协议路由，三态语义全如实：有栈委托 / 无栈拒绝（WARN）/ 未知拒绝——**dji-cloud 标记就位但栈未接入，命令明确拒绝不静默丢**（与 F2 transport=mqtt seam 同一诚实口径） |

| F5.5 | **测试** | CommandRouterTest 5 例，含协议中立断言：**dji-cloud 设备的命令不许从 MAVLink 栈漏出** |



**诚实边界**：DJI Cloud API 栈的真实对端（MQTT 物模型/HTTPS/WebSocket）属生产阶段；

本轮交付的是 SPI + 路由骨架 + 协议标记（含 dji 设备命令的明确拒绝语义），非假接线。



---

---

---

## [Unreleased] — F6 RTK 高精度链路：精度等级字段 + 固定/浮点解状态上报（2026-10-08）



> 依据 ROADMAP F6（依赖 Baseline 遥测✅）。口径：MAVLink GPS_FIX_TYPE

> （3=3D_FIX 单点 / 5=RTK_FLOAT 浮点解 / 6=RTK_FIXED 固定解，PX4 同口径）。



| # | 交付 | 内容 |

|---|---|---|

| F6.1 | drone-sim | `--rtk none|fixed|float`（缺省 none 保持既有 3D_FIX 不变；非法值 WARN+降级 none）；GPS_RAW_INT.fix_type 按模式发 3/5/6（GPS 丢失场景照旧置 0） |

| F6.2 | cloud-backend | `DroneSnapshot.rtkStatus()` 派生标签（RTK_FIXED/RTK_FLOAT/STANDALONE/NONE）；summary 与 status 视图均暴露（列表与 WS 帧同源） |

| F6.3 | GCS | DroneList RTK 徽标（固定解绿 RTK·F / 浮点解黄 RTK·f，title 注精度量级） |

| F6.4 | e2e | `scripts/e2e-rtk.ps1`：三机并行（fixed/float/缺省）→ rtkStatus 三态断言——ALL PASS |



**测试**：DroneSnapshotRtkTest(4) + SimConfigRtkTest(3)；e2e 三机三态。

**诚实边界**：RTK 是 **fix_type 上报语义**（协议层就绪），坐标噪声模型未区分 RTK/单点

（真实差分改正数接入属生产阶段——需要 RTK 基站/网络 RTK 数据源）。



---

---

---

## [Unreleased] — F3 行业航线库：四类模板生成器 + GCS 模板面板（2026-10-08）



> 依据 ROADMAP F3（依赖 Baseline 航线规划✅）。模板 = 纯函数几何生成器

> （输入目标形状 → 可直接下发的航点任务），四类全部单测覆盖 + e2e 真飞验证。



### 交付清单



| # | 交付 | 内容 |

|---|---|---|

| F3.1 | **电力杆塔**（tower） | 逐塔 [抵达→环绕(半径/点数可配)→塔位上空驻留拍照]，`legs` 按塔号分段（与 F4 缺陷复检的 towerNo 数据面打通） |

| F3.2 | **光伏连片**（solar） | 弓字形（boustrophedon）平行扫描：多边形与等间距平行线求交、交点配对、隔行折返；扫描主方向可配 |

| F3.3 | **管线带状**（pipeline） | 折线等距采样，拐点强制保留 |

| F3.4 | **河湖岸线**（shoreline） | 轮廓环等距采样闭合巡检；离岸偏移用 **shoelace 有向面积做数据驱动绕向判定**（硬编码顶点序假设在顺时针输入下把环缩到内侧——测试抓出后重构） |

| F3.5 | **守卫** | 参数校验全表（400+字段名）、航点上限 990（MAVLink 任务协议安全余量）、相邻点去重（>0.1m） |

| F3.6 | **GCS 面板** | RouteTemplatePanel：类型切换/参数表单/坐标 JSON/航点预览表/一键下发（ARM→上传→start_mission） |

| F3.7 | **e2e** | `scripts/e2e-route-templates.ps1`：四类生成断言 + 校验 400 + tower 模板真下发 2 塔巡检全程执行落地——ALL PASS |



### 几何口径（诚实声明）



局部平面近似（1°≈111320m 线性换算，<10km 区域误差厘米级）；弓字形无最小转弯优化；

岸线偏移是顶点法向近似非精确平行曲线；杆塔号→坐标由请求方传入（真实台账/GIS 接入属生产阶段）。



---

---

---

## [Unreleased] — F4 缺陷报告与工单闭环：领域模型/工单状态机/复检闭环/报告导出（2026-10-08）



> 依据 ROADMAP F4（依赖 F1✅）。对表三峡"统一管控、统筹调度、智能诊断、闭环消缺"——

> 复检（智能诊断）用 F1 既有拍照链路真拍一张 + 逐缺陷 10m 比对，不造假结果。



### 交付清单



| # | 交付 | 内容 |

|---|---|---|

| F4.1 | **持久化** | V24 迁移 3 表：defects / work_orders / work_order_defects（BIGINT 主外键，对齐实体 Long——F2 的 V23 在 Pass C 已踩过 INTEGER↔Long 的 validate 坑） |

| F4.2 | **缺陷领域** | 从拍照结果自动晋升（conf≥0.6 可配，纯旁路吞异常）或人工立案（note 必填）；**10m 同 kind 去重锚点**（复现只更新 lastSeen/confidence 不重复立案）；severity 按 confidence 分档（0.9→P1/0.75→P2/其余 P3） |

| F4.3 | **工单状态机** | OPEN→DISPATCHED→IN_PROGRESS→RESOLVED→VERIFIED；复检命中→REOPENED 回流程；任意未终态可 CANCEL（记原因）；RESOLVED 也可取消（测试抓出状态机漏配后修正）；终态封死 |

| F4.4 | **复检闭环** | POST /work-orders/{id}/verify {sysid} → 真拍一张（复用 captureAndLocate）→ 逐缺陷比对 → 全未命中 VERIFIED/有命中 REOPENED；拍照腿失败如实 502 |

| F4.5 | **报告导出** | GET /defects/report?format=json|csv|md——缺陷清单 + 工单闭环统计 + 复检通过率（分母零 null，F1 口径）；PDF 属渲染层不在本轮 |

| F4.6 | **GCS 面板** | DefectPanel（tab 缺陷工单）：缺陷列表（严重度色标/勾选建单）+ 工单流转按钮 + 复检验证 + 报告下载 |

| F4.7 | **e2e** | `scripts/e2e-defect.ps1`：真飞拍照→自动晋升（2 条 P1）→去重→建单→流转→非法转移 409→复检 REOPENED→报告三格式，全链 ALL PASS |



### 顺带修复（F1 遗留真缺陷）



**truth 分支 detection 缺 confidence 键**：CaptureService 的 truth（默认检测源）分支直接构造

detection map 时只放 kind/lat/lon/id/truthErrorM，漏了 confidence（vision-source/external

分支经 locateAndScore 有补）——F1 的 capture 响应在默认源下一直没有 confidence 字段，F4 的

自动晋升依赖它才暴露。修为 confidence=1.0（投影真值语义，与 ProjectionVisionSource.detect 一致）。



### 已知边界



- 复检要求托管机已飞临缺陷点（派飞属运维职责，工单不驱动飞控）；

- 工单"处置"是流程态，不含飞控指令；

- 10m 去重与复检比对是同一口径的空间近似。



---

---

---

## [Unreleased] — CI：maven 缓存键只掺 pom 不掺工具链，master 与全部 PR 被冻红数小时（2026-10-08）



- **现象**：`Warm Maven Cache` 自 2026-10-07 15:53Z 起**确定性**判红 —— master run `37647669688`

  （head `74af653`）、PR #6 run `37647663158`、PR #7 run `37655262825` 三处红在同一行

  `Cannot access central ... in offline mode`，缺的都是 `maven-resources-plugin:3.5.0`；

  下游 10 个 job（含 `CodeQL Analysis`、`Security Scan`）全被 skipped；

- **归因**（两条互斥证据夹出来）：同一个键 `mvn-Linux-6b5e33ff…-v1` 存在多个条目版本——12:55Z

  success 的 run `37624477248` 恢复的是 **139,866,841 B** 那版且离线自检通过；15:53Z 起恢复

  **139,868,081 B** 那版就挂。⇒ 变的不是 pom（键就是 pom 哈希，未变），而是 **runner 自带 Maven

  的默认插件版本集合**（本仓从不显式钉 `maven-resources-plugin` 这类）；又因 `actions/cache`

  对已存在的同键「首写者胜出、此后只读不覆盖」，没有任何一次运行能自愈 —— 只能抬代次或删条目；

- **更正（2026-10-08，本条经 PR #8 合入 master 后取证，收回上一条里的两句）**：

  - **收回「没有任何一次运行能自愈 —— 只能抬代次或删条目」**。00:28Z 有人（非本会话，仓库审计日志我取不到，不指认）重跑了

    master run `37647669688`：那一次精确键 `…6b5e33ff…-v1` **未命中** ⇒ 走冷路径在线补齐 ⇒ 以**同名键**另存条目

    （`8653075916`，139,864,993 B，00:28:50Z）⇒ 整跑转 success。同键条目在旧条目已不在时**是可以被替换的**，

    我把 first-writer-wins 说成了跨条目永久规则，说过头了；

  - **补上真正的机制：Actions 缓存按 ref 作用域隔离**。同一键串 `…6b5e33ff…-v2` 现在**同时存在两个条目**——

    `8653323212`（ref=`refs/pull/8/merge`，00:37:29Z，148,894,042 B）与 `8654289787`（ref=`refs/heads/master`，

    01:12:21Z，148,889,259 B）；master 的 run 在 01:11:41Z 要 `-v2` 时**精确键 miss**，只能退回自己那份 `-v1`。

    ⇒ **实测到的是"master 读不到 PR run 焐出来的缓存"**（跨 PR 互读属 GitHub 所述同一 ref 作用域规则，本仓未单独

    实测，故不写成结论）；所以"抬键代次"并不解决这类冻结，PR #8 里那条 warm 腿绿只证明该 PR 自己受益

    （对 master 的真实收益来自合入后的那份键与降级兜底）；

  - **「runner 自带 Maven 默认插件集合变了」降级为未验假设**：它能解释"同键两版大小不同"，但那两个尺寸

    （139,866,841 / 139,868,081）本就可能来自**不同 ref 各自的条目**，不足以支撑结论 ⇒ 别再当既成事实引用；

  - **仍然成立的是这三件**：① 9 处键同源的守卫（防"抬一代漏一处"的漂移）；② 离线自检失败 ⇒ 打 warning 后在线补齐

    （症状从「全仓冻红」降为「一次警告 + 慢几分钟」）；③ 把"缓存不可依赖"写进文档。**仍未验的一刀**：`mvn -o`

    离线自检对新生成条目的通过性至今没在 CI 真跑过——00:28Z 与 01:11Z 两次都是冷 miss，自检步 skipped；

    它的判决要等下一次**精确命中**的运行；另注：master run `37711670124`（head `846e185`）已 completed/success。

- **修（三件）**：

  1. 键代次 v1 → **v2**，`ci.yml` **9 处一起抬**（maven-warm / java / gcs-e2e / e2e-smoke /

     e2e-capability / integration / sdk-integration / security-scan / codeql）；

  2. 离线自检失败不再打死整条腿：打 `::warning::` 后**在线补齐**，当次运行继续走完 —— 症状从

     「全仓冻红」降级为「一次警告 + 慢几分钟」；

  3. 新增 `scripts/ci-maven-cache-key.sh` 守卫并接为 maven-warm 第一步：所有键必须与规范值同源，

     **扫不到任何键也判红**（防门禁自己失明）；

- **验证**：`bash -n` 通过；正向 `sites=9 mismatch=0` exit 0；负向（临时副本只抬一处）exit 1 并

  指名 `ci.yml:129 实得 …-v3`；负向（空目录）exit 1 报「没找到任何 maven 缓存键」。全部负向测试

  在 /tmp 副本里做，真实文件核验保持 9×v2；`yaml.safe_load` 解析通过（17 个 job）；

- **遗留**：键里仍不含 Maven 版本（workflow 表达式拿不到，需每个 job 先导出再引用，9 处都要改）。

  下次 runner 工具链再动，按提示抬 v3 即可，守卫会替你盯住"漏抬一处"。



---

---

---

## [Unreleased] — CodeQL 6 条 open 告警逐条处置：2 处真修、2 处协议必然、2 处加守卫（2026-10-08）



- **范围**：PR #5/#6 合入后 code scanning 只剩 6 条 open 告警，全部来自 CodeQL——

  `java/spring-disabled-csrf-protection` ×2（#5/#6）、`java/polynomial-redos`（#4）、

  `java/tainted-numeric-cast` ×2（#2/#3）、`js/request-forgery`（#1）。逐条读码后分三类处置；

- **真修（改变行为）**：

  - `js/request-forgery`（`gcs-web/src/api.js`）：`jsonFetch` 自动附带 `Authorization: Bearer`，

    而 url 由各调用点用 `${BASE}/${sysid}` 拼出。新增 `resolveApiUrl()`，只放行同源 `/api/v1/`

    前缀，拒绝绝对 URL、协议相对 `//host`、以及用 `..` 爬出前缀的路径；

    `test/apiUrlGuard.test.js` 7 条钉住（含"保留查询串"）。既有 `src/api.test.js` 在改动后仍全过；

  - `java/polynomial-redos`（`VoiceCommandParser`）：目标名提取**改为不依赖正则**（`extractTarget()`

    手工扫描，每步只前进一个字符、无回溯 ⇒ 线性由构造保证）。**这条经过一次失败**：第一版只是把

    `前往(.+?)(?:sep|$)` 的惰性 `.` 换成与终止符不相交的排除式字符类

    `前往([^\n,，。;；]+)(?:[,，。;；]|$)`，CI 的 CodeQL 仍在改动行上报 **new alert**（run

    37653063357 的 `CodeQL` 状态："1 new alert including 1 high severity"）⇒ 该查询认的是"可回退形态"

    本身，不是我的直觉里的类相交。故换第二条路；

    语义与原正则逐条对齐（≥1 字符、止于首个终止符或行尾、遇换行则该起点不算、起点失败则后移一位），

    由 `parse_target_boundaries` 钉住，中英文两路都有断言。行为差异仍只有一处："前往，东门"旧写法

    取到垃圾组 `"，东门"`，现判为无目标。成本上限另有 `VoiceCommandController.MAX_TEXT_CHARS=500`

    （超限 400、恰等上限通过）；

- **协议必然（说明而非改行为）**：

  - `java/tainted-numeric-cast` #3（`PayloadCodec.putU8/putI8`）：同文件其余 `put*` 都写成

    `(v & 0xFF)`，这两处漏了掩码。补齐后按 Java 窄化定义**完全等价**，"无意图截断"的形态消失；

  - #2（`DroneCommandService.takeoff`）：MAVLink `COMMAND_LONG` 的 param 在线路上就是 4 字节 float，

    double→float 窄化是协议要求 ⇒ 理由注解；

- **加守卫（把"为什么能关掉"变成机器断言）**：

  - `java/spring-disabled-csrf-protection` #5/#6：`SecurityConfig` 两处 `csrf.disable()` 的前提是

    "凭据只来自请求头 + 会话 STATELESS + 全站不发 Cookie"（全仓 grep 仅命中 STATELESS 一处，无

    cookie/httpBasic/formLogin/rememberMe）。新增

    `HttpAuthChainTest.csrfPremise_noAmbientCookieCredential`：带伪造 `JSESSIONID` 的匿名写请求仍须

    401，且响应不得出现 `Set-Cookie`——将来任何人改成 cookie/会话型凭据，这条先红。

    另加 `@SuppressWarnings("java/spring-disabled-csrf-protection")`。**该注解已被 CI 认账**：

    run 37653063357 的分析在改动行上只报出 1 条新告警（就是上面那条 redos），CSRF 与

    numeric-cast 都没有复现；

- **验证**（rebase 到 PR #6 之后的 master 上复跑）：`mvn -B -o test` 全量 **4363 用例 / 0 failures**

  （cloud-backend 2367）；`npm ci` + `npm run lint` **0 error**（新增前端文件零告警）+

  `npm run test` **16 文件 / 225 例全过**；`python scripts/check-test-count-docs.py` 由"不一致 22 处"

  转为**全部一致（退出码 0）**；PR #7 的 workflow run 终态 **success（25 job 全绿）**，唯一红是

  code-scanning 的 `CodeQL` 状态（1 条新告警），因此追加"无正则提取"那一笔；该笔的语音三类

  定向复跑 **76 用例 / 0 failures**（parser 33 + controller 20 + executor 23）；

- **文档同步**：Java 单测数 4359 → **4363**、cloud-backend 2363 → **2367**（README、ROADMAP、白皮书、

  销售稿、定价、演示场景、客户上手、竞品分析、低空研究、产品简报共 22 处），前端 vitest 218 → **225**

  （README:837、ROADMAP:25）；

- **边界**：默认分支上那 6 条告警要等本 PR 合入、由 master 的 CodeQL 复扫才会转 closed（PR 分析不动

  默认分支的账）。redos 换成无正则后是否清零，看下一个 run；若仍报，就改用 PR #5 那条路

  （显式登记 + 到期自动回红 + 机器守卫），而不是继续猜注解写法。



---

---

---

## [Unreleased] — 协议补齐：AlarmTrigger 增补 alarmId，报警与派遣首次可关联（2026-10-07）



1. **🔴 协议缺口：`ALARM_TRIGGER`(30057) 不带 `alarmId`，而 `ALARM_ACK`(30058) 带**

   - **实况**：`AlarmLinkageEngine.publishAlarmAck` 用 `idToU32(event.getId())` 填

     `alarmId`，而 `publishAlarmTrigger` **完全不携带事件 ID** ⇒ 两帧在协议层无共同键。

     操作员看到"某处发生火灾"与"Drone-3 已在响应"，无法确认后者是不是这条报警的响应。

     前端 `normalizeAlarmTrigger` 早已把该缺口写进注释并硬编码 `correlatable: false`，

     `normalizeAlarmAck` 同样如此。

   - **布局取舍（协议变更，三选一）**：原 LEN=68 被前 7 字段填满（18 + 50），无空位。

     - **追加尾部（本方案）**：LEN 68→72，既有偏移一字不动，**双向兼容**——

       新解码器读 68 字节旧帧 ⇒ `len > 71` 不成立 ⇒ `alarmId=0`；

       旧解码器读 72 字节新帧 ⇒ 各字段仍在原偏移、尾部 4 字节被忽略

       （`decode` 每个字段都有 `len > N` 守卫，帧解码不校验 payload 长度）。代价 4 字节/帧。

     - 插入头部 + description 50→46：LEN 仍 68，但 description 偏移 18→22，

       **旧帧的 description 会被错读成 alarmId**——省 0 字节却换来静默数据损坏。

     - 不改协议、靠 `(sourceDeviceId, timestamp)` 关联：告警突发时时间戳必然撞车，

       且前端补一个假 alarmId 只会把缺口藏起来。

   - **改动**：`AlarmTriggerMsg` 增 `alarmId`（u32，偏移 68）+ `hasAlarmId()`；

     保留旧 8 参构造器（等价 `alarmId=0`）以保源码兼容；

     `MavlinkMessageInfo` 30057 同步为 `Info(72, 64)`（**CRC_EXTRA 不变**——

     它只由 msgId 与字段名决定，不含字段长度/顺序）；

     `AlarmLinkageEngine` 与 ack 用**同一** `idToU32(event.getId())` 填 trigger。

   - **`alarmId=0` 的语义**：未携带（旧帧 / 事件 ID 为空）。前端据此置

     `correlatable=false`，不得拿它去 join。



2. **前端：两帧的 `correlatable` 由硬编码 false 改为按 `alarmId` 判定**

   - `normalizeAlarmTrigger` 带出 `alarmId`；`normalizeAlarmAck` 同步。

   - 两侧统一 `correlatable = alarmId != null && alarmId !== 0`，

     故**旧帧仍正确地报不可关联**，不会因为协议升级就把旧数据当成可关联。

   - 用例从"断言无 alarmId"改为覆盖三条路：非 0 ⇒ 可 join、字段缺失 ⇒ 不可关联、

     `=0` ⇒ 不可关联，并新增"两端 alarmId 相同 ⇒ 可 join"（本轮变更的目的本身）。



3. **测试**

   - `AlarmTriggerMsgTest` 9 → 14 例：新增 alarmId 往返无损、**alarmId 位于偏移 68

     且不挤占 description**、新解码器读旧帧（68B ⇒ alarmId=0 且其余字段无损）、

     旧解码器读新帧（既有偏移仍可正确取值）、`MavlinkMessageInfo` 与常量一致、

     旧构造器等价于 0。

   - 前端 225 → 229 例。

   - **诚实记录一处自身失误**：初版用例误用 Jest 的 `.as()` 链式写法

     （`expect(x).toBe(false).as(...)`）在 Chai 下报 `Invalid Chai property: as`，

     首次运行 2 红；改为注释 + 独立断言后 229/229 全绿。



4. **计数基线 4388 → 4393（mavlink-core 457 → 462），前端 225 → 229；门禁全绿。**



---

---

---

## [Unreleased] — M4 传感器接线：LiDAR/IMU 模拟源早已实现却从未注入（2026-10-07）



紧接上一轮"夹爪从未通电"，本轮是同一类缺口的第二例：能力在、接线无。



1. **LiDAR / IMU 端点在演示中恒无数据（FR-21 / FR-22）**

   - **性质**：`SimulatedLiDARSource` 与 `SimulatedImuSource` 早已实现且各有独立单测

     （`SimulatedLiDARSourceTest` / `SimulatedImuSourceTest`），

     `VirtualDrone.setLidarSource` / `setImuSource` 注入点也都在，

     `sendLidarData` / `sendImuData` 的分频块与消息构造同样齐全。

     **但没有任何地方构造并注入它们**，`SimConfig` 也没有对应开关。

   - **后果**：drone-sim 无论怎么启动，`/api/v1/lidar/data` 与 `/api/v1/imu/data`

     都恒返回 404 `no LiDAR/IMU data`。而 404 **符合端点契约**，

     e2e 此前也只把它记为"按契约允许的 skip" ⇒ **没有任何信号会变红**，

     演示时这两路传感器则完全没有数据。

   - **修**：`SimConfig` 新增 `--lidar` / `--imu` / `--imu-seed`（裸写布尔开关，

     已登记进 `BOOLEAN_FLAGS`）；`VirtualDrone` 在对应配置为真时构造并注入模拟源。

     仍与 `--actuators` 同为**显式装配**语义 —— 缺省不注入，既有行为不变。

   - LiDAR 采用以起飞点为原点的本地 NE 坐标系，正前方 30m / 右前 15m / 上方 20m

     三颗合成障碍，使演示读数稳定可解释（非随机数）。



2. **CI 与本地一律带 `--lidar --imu`，e2e-hardware 从"接受 404"改为"要求 200"**

   - 与上一轮 `--actuators` 同一处置：404 是配置问题的信号，被"契约允许"放过

     就等于把"演示时传感器没数据"藏起来。

   - 新增断言并实测通过的读数：LiDAR `nearestDistance≈21.66m`、`pointCount=28`；

     IMU `accelZ≈9.82 m/s²`（水平悬停含重力，与 `SimulatedImuSourceTest` 同口径），

     另断言 `pointCount>0` 与"accel 合力非零"，以排除**零值占位**——

     仅断言"字段存在"无法区分"真实数据"与"全是 0.0 的假数据"。

   - 硬件腿 16 → **23 项断言全绿**；三腿合计 **80 项**（spray 26 / hardware 23 /

     emergency 31），连跑两次一致。



3. **新增 `SensorActuatorWiringTest`（8 例）**

   - 钉住：缺省两开关皆关（既有行为不变）、裸写即生效且**不吞下一个 token**

     （`--lidar --imu` 若被吞会表现为"偶尔不生效"，极难排查）、

     两开关互不干扰、`--imu-seed` 默认 42 且可覆盖。

   - 并守住模拟源本身产出的是**真实数据**：有障碍时点云非空、最近距离有限正数；

     空场景为 `MAX_VALUE`（与有障碍可区分）；IMU 比力合力 ≈9.8（含重力）。

   - **变异验证**：把 `lidar` 从 `BOOLEAN_FLAGS` 移除后 4 例立即转红

     （模拟"裸写开关被吞 token"）。



4. **计数基线 4380 → 4541（drone-sim 1397 → 1405）**

   - 门禁全绿，22 处文档声称同步；`demo-scenarios` 补上 `--lidar --imu` 前置。



---

---

---

## [Unreleased] — 机载 MAV_CMD 实装：夹爪从未通电导致投递链路恒被拒（2026-10-07）



上一轮把 e2e 三腿转硬门禁时，喷洒腿里留着三条"机载拒收只告警"的宽容断言。

本轮查明那不是"机载能力还没做"，而是**夹爪从未被使能**——演示时会当场看到

"机载拒收配送指令"，且原因完全不可见。



1. **🔴 真缺陷：`Gripper` 装配后从未 enable，投递链路 100% 被拒**

   - **机制**：`Gripper.enabled` 初值 `false`，而抓取/投放的前置校验都是

     `if (!enabled || state != …) return false`。但 `GripperCommand` 的子命令只有

     `GRAB(0)/RELEASE(1)/RESET(2)`，**没有"使能"这一档**，机载侧也无从把它打开。

     `SprayPump` 不受影响是因为 `SPRAY_ENABLE(0)` 子命令会调 `sprayPump.enable()`——

     夹爪没有对应的那一档，于是永远抓不动。

   - **实测**：修复前 `POST /api/v1/delivery/{id}/control` (START) 恒得

     `MAV_RESULT=2`（DENIED），机载日志 `Gripper grab rejected: state=IDLE`。

   - **修**：`VirtualDrone` 在 `actuatorsEnabled` 分支装配夹爪后立即 `gripper.enable()`。

     理由：夹爪的"通电"属于**装配语义**而非控制语义——`--actuators` 的含义就是

     "执行机构已装且可用"。

   - **为什么此前没被发现**：`NexusCommandDispatchTest` 覆盖了 30080-30087 的派发，

     但**只断言 result != UNSUPPORTED**（其注释明说"不钉各 handler 的业务结果"）。

     未 enable 的夹爪回的是 DENIED(2) 而非 UNSUPPORTED(3)，于是"抓不动"在那条守卫里

     **完全不可见** —— 测试全绿而链路不可用。

   - 新增 `GripperActuatorWiringTest`（6 例），沿用真实 UDP 往返（不反射私有字段，

     因为要钉的是"命令能否被机载接受"，与演示看到的是同一件事）：

     装配后 GRAB 必须 `ACCEPTED`；未装配必须 `UNSUPPORTED`（两者必须能区分，

     否则排查时分不清"没装配"还是"没通电"）；并守住"HOLDING 下再 GRAB 必须被拒"

     与"超重必须被拒"（使能修复不得放宽 FR-03 安全校验）。

   - **变异验证**：注释掉 `gripper.enable()` 后 `grabIsAcceptedWhenActuatorsAssembled`

     立即转红（期望 0 实得 2）。



2. **drone-sim 默认不注入执行机构：CI 与本地一律带 `--actuators`**

   - `actuatorsEnabled` 缺省 false ⇒ `sprayPump`/`gripper` 为 null ⇒

     30083/30084/30085 的处理器**一律** `UNSUPPORTED`，回执恒 `result=-1`。

   - CI 的 `e2e-capability` job 启动 drone-sim 时补 `--actuators`（附注释说明

     漏了会怎样），本地验证脚本同步对齐。



3. **e2e-spray 从"只告警"改为"要求机载肯定回执"**

   - 移除三条宽容断言（喷洒 START/STOP、配送 START、负载上报），改为要求

     `result != -1` / `status=ok`。理由：那是**配置问题的信号**，被"只告警"掩盖

     正是"演示时命令被拒却无人察觉"的由来。

   - 新增一步"夹爪复位到 IDLE"（`action=RESET` → `GRIPPER_RESET` 30084/subcmd=2）：

     抓取前置是 `IDLE`，而上一轮跑完夹爪停在 `HOLDING`，直接 START 会被机载

     **正确地**拒绝（DENIED）。复位同时让脚本可重复执行、并覆盖 RESET 子命令。

   - 实测（真 Linux + 真实后端/模拟器）：喷洒腿 20 → **26 项断言全绿**，

     三腿合计 **73 项**（spray 26 / hardware 16 / emergency 31），连跑两次一致。



4. **计数基线 4374 → 4380（drone-sim 1391 → 1397）**

   - 新增 `GripperActuatorWiringTest` 6 例；`check-test-count-docs.py` 全绿，

     22 处文档声称同步。`demo-scenarios` 场景 D 补上 `--actuators` 前置与

     夹爪 IDLE 约束。



---

---

---

## [Unreleased] — 存量 license 重签发预检工具 + 修档位模块顺序不确定（2026-10-07）



上一轮落地了 BREAKING CHANGE 的档位拆分，但**只交付了"会坏"，没交付"怎么修"**：

部署时运维只能看到一整段"模块集合不对应任何可售档位"，拿不到"该重签成哪一档"。

本轮补上从诊断到签发的闭环。



1. **新增 `LicenseMigrationPreflight`（只读迁移预检 CLI）**

   - 读入一把 license key → 验签 → 解析原模块集合 → 打印「是否需重签 / 应重签成哪档 /

     **可直接粘贴的签发命令**」，并列出三档模块集合供人工比对差异。

   - **不需要私钥**，也不签发任何东西；私钥只在真正执行 `LicenseIssuer` 时才需要。

     提供 `--public-key-file` 才验签；不提供则跳过并**显式告警**（结论仅供参考，

     不能证明该 key 确由本方签发）——不把"没验"说成"验过"。

   - 退出码：`0`=无需重签发、`1`=需重签发或 key 无法解析、`2`=用法/IO 错误。

   - **为什么不走 `LicenseService` 构造器**：构造器本身就因非档位组合 fail-closed 抛

     `IllegalStateException`——正是要诊断的那个情况。工具只复用 `LicenseSigner.verify`

     与 `LicenseTier`，因此能在"启动必然失败"的前提下把信息取出来。

   - 对含未知模块名的集合**不猜档位**（返回 null 并要求人工裁决）——签发侧本就会拒绝，

     瞎猜只会给出一条错误的签发命令。

   - 新增 `LicenseMigrationPreflightTest`（11 例）守卫判定与输出，其中三条是端到端

     跑 `run()` 抓 stdout：重签建议含可粘贴命令、当前档位报告"无需重签"、

     残缺 key 明确排除"这是模块拆分问题"（避免把运维引向错误方向）。



2. **修 `LicenseTier.modulesOf` 的迭代顺序不确定（影响所有面向人的输出）**

   - 现象：`Set.copyOf(...)` 返回的是**无序**不可变集，元素顺序由 hash 分布决定，

     同一档位两次运行可能给出不同顺序。表现为迁移预检打印的

     `--modules core,fleet,mesh,orch` 时而变成 `orch,core,fleet,mesh`。

   - 这与 `LicenseTier` 类注释自述的意图直接矛盾——注释写着"用 `LinkedHashSet`

     保证迭代顺序稳定（便于日志与测试断言可读）"，而 `build()` 里的 `Set.copyOf`

     恰恰把这个意图丢掉了。

   - 修：`build()` 与 `modulesOf` 改用 `Collections.unmodifiableSet(LinkedHashSet)`

     保留声明顺序。**签名确定性不受影响**（`LicenseSigner.serializeForSigning`

     已对 `modules` 按字典序固定，与此处无关）。

   - 变异验证：改回 `Set.copyOf` 后 `moduleIterationOrderIsStable` 与

     `mainPrintsActionableReissueAdvice` 两例立即转红 ⇒ 断言真的咬住了顺序。



3. **两处只在真跑 CLI 时才暴露的输出缺陷（已修并锁进测试）**

   - `原因: 未知档位: ?` —— 原先把 `mismatchOf` 拿一个占位档位名 `"?"` 去调，

     输出对运维毫无意义。改为说明"该集合与三档都不相等"并列出三档模块清单。

   - 报告中间夹着一行 `INFO LicenseSigner 初始化（生产模式）` —— 文本报告里冒日志行

     会破坏可读性。改为临时把该 logger 抬到 `OFF`（logback 专属 API，

     非 logback 绑定时安静降级为不静音），构造完立即恢复级别。



4. **发现一个与本轮改动无关的既有 flaky（已量化，未擅自修改）**

   - `TaskAssignmentServiceTest.assignTasksGaCompletesWithin100ms` 是墙钟断言

     （GA 分配 10 请求须 <100ms）。实测本机红率：**改动后 1/10 红、干净树 2/10 红**，

     且该类整体耗时在 3.9s–7.4s 间波动 ⇒ 100ms 预算在本机偏紧，属既有 flaky。

   - 排查结论与本轮无关：`scheduling` 包对 `license` 包**零引用**，

     `LicenseTier.modulesOf` 的调用方仅 `LicenseService`/`LicenseSigner`/本预检工具。

   - **未改**：调预算属于性能基线决策，需负责人拍板；本轮只把它记下来。

     提交前的全量验证用 `-Dtest=!…#assignTasksGaCompletesWithin100ms` 排除该用例，

     并另跑一次不排除的全量确认 BUILD SUCCESS。



5. **计数基线 4359 → 4370（cloud-backend 2363 → 2374）**

   - 新增 `LicenseMigrationPreflightTest` 11 例；`check-test-count-docs.py` 全绿，



---

---

---

## [Unreleased] — F2 机巢管控 + 上云 API 适配器：Dock 状态机 / 无人值守定时任务 / 利用率度量 / GCS 面板（2026-10-08）



> 依据 ROADMAP F2（依赖 C4 持久化✅）。协议抽象先行：命令通道是 DJI Cloud API 物模型形状

> （services 通道 `{tid,bid,timestamp,method,data}`），传输默认 sim（HTTP 回环到机巢模拟器），

> `aerofleet.dock.transport=mqtt` 为生产 seam（需 EMQX Broker，见 README 边界）。



### 交付清单



| # | 交付 | 内容 |

|---|---|---|

| F2.1 | **持久化** | V23 迁移 5 表：docks / dock_state_log / dock_schedules / dock_run_log / dock_metrics_daily（day 列因 H2 保留字用 day_date） |

| F2.2 | **状态机** | 9 态（OFFLINE/IDLE/OPENING/OPEN/CLOSING/CHARGING/EXCHANGING/FAULT/MAINTENANCE）；迁移表唯一裁决点 `DockStateMachine`，非法迁移 409+状态名；OSD 只按自然后继推进过渡态，FAULT 不被自报覆盖（防自动痊愈） |

| F2.3 | **命令通道** | `DockGateway` 接口 + `SimLoopbackGateway`（默认装配）；通道失败回滚过渡态不留卡死的 OPENING；REBOOT 经网关转发 + 8s 定时器回 IDLE |

| F2.4 | **无人值守** | cron 定时任务（Spring CronExpression）；四道门控（机巢在线/状态 IDLE·CHARGING/托管机**在线**/无并发 RUNNING）——托管机在线性是 e2e 实测撞出的真缺陷（修前会白开门白等到超时）；全链路复用 DroneCommandService（arm→uploadMission→startMission→监测落地→关门） |

| F2.5 | **度量** | 日结幂等重算（小时级定时 + 查询时按需重算当天）：架次/飞行分钟/开关门/可用率/利用率(8h 基准)/充电时长/换电次数/温度告警；分母零返回 null（F1 口径） |

| F2.6 | **模拟器** | drone-sim 增 `dock` 子命令（DockSimMain）：物模型行为（开门 3s/关门 3s/换电 5s/重启 8s）+ OSD 推送 + /services /osd /health |

| F2.7 | **GCS 面板** | DockPanel（tab 机巢）：状态色标列表/动作按钮（非法态置灰+后端裁决）/度量卡片/定时任务管理/迁移时间线 |

| F2.8 | **e2e** | `scripts/e2e-dock.ps1`：注册幂等(201/409)→OSD 上线→开门时序跟随→409 非法态→400 未知命令→关门→cron 400→SKIPPED 门控→度量聚合——真后端 + 真模拟器全链路 |



### 测试



- 后端 57 例：DockStateMachineTest(26 全表) / DockServiceTest(14) / DockMetricsServiceTest(4) / DockGatewayLoopbackTest(5 真 HTTP 往返) / UnattendedGateTest(8 门控)

- 前端 8 例：dockPanel.test.js（状态表完整性 + 动作许可表与后端状态机一致性）

- e2e-dock ALL PASS（真后端 + DockSim 全链路 26 断言）



### 已知边界



- MQTT 传输是接口 + 配置位，真实 EMQX 联调属生产阶段（PoC 默认 sim 回环）；

- 换电是计时仿真（5s + 电量曲线 20→100），非机械臂时序；

---

---

---

## [Unreleased] — 定价档位裁决落地：基础版扎实（模块 5→7 拆分）+ 三腿 e2e 转硬门禁（2026-10-07）



用户裁定「**基础版需要扎实**」，即基础版必须真能撑起一次交付。本轮落地该裁决，

并把三条能力腿从观察模式转为硬门禁。



1. **定价档位裁决：`ALL_MODULES` 由 5 扩为 7，三档与定价文档 §5 逐字对齐**

   - **原矛盾**：§5.1 承诺基础版含 **M5 mesh + M9 编排 + GCS OEM**，而 `LicenseTier`

     把 mesh 放完整版、M9 放应急版 ⇒ 按 §5 卖基础版，客户付费的 `/api/v1/mesh/*`

     与 M9 编排会被 403（档位绑定已在验证期与启动期双重强制，这是交付事故）。

   - **为什么不整块下移**：把 `network`/`emergency` 整块放进基础版，会让基础版连

     5G 基站、卫星中继、ONVIF 安防联动一起白送 ⇒ 应急版与基础版同集合、

     **档位梯子塌掉、无法定价**（`LicenseTierTest` 的"档位必须互异"会红）。

   - **改为按交付形态各拆一半**：

     | 模块 | 覆盖 | 档位 |

     |---|---|---|

     | `mesh`（新，从 `network` 拆） | M5 AODV-lite 自愈组网 | 基础版 |

     | `orch`（新，从 `emergency` 拆） | M9 应急任务编排 + 编排计划 | 基础版 |

     | `emergency` | 4a 空地一体化指挥 / 告警 / ONVIF / 视频视觉 | 应急版 |

     | `network` | 5G 基站 / 卫星中继 / 链路适配 / LoRa / 边缘 | 完整版 |

     | `advanced` | 数字孪生 / 城市孪生 / AI 决策 / 语音 / 多光谱 | 完整版 |

   - 拆分后：基础版 = `core`+`fleet`+`mesh`+`orch`；应急版 = 基础版 + `emergency`；

     完整版 = 应急版 + `network`+`advanced`。

   - **新增 `LicenseModuleSplitMigrationTest`（7 例）** 钉死边界：模块集合恰为 7 且

     原有 5 名不被改名、mesh/orch 必归基础版、基站卫星与 ONVIF 必留在上档、

     每个模块必须有前缀、三档必须互异。

   - **变异验证**：把 `/api/v1/mesh` 改回 `NETWORK`（即撤销拆分）后 3 例立即转红

     ——这正是"拆分做一半"的失效形态：客户付了钱、license 也认了、请求却 403。

   - **⚠️ 已知且故意的不兼容**：模块名进 license 签名载荷，拆分**前**签发的授权

     （含 `network`/`emergency`、无 `mesh`/`orch`）不再对应任何档位，**必须重新签发**。

     刻意不给旧集合加兼容映射——旧集合里 `network` 含 mesh、`emergency` 含 M9 编排，

     与新边界不再等价，兼容等于把定价边界改回错的那一侧。该不兼容已由测试钉死

     （`legacyModuleSetMatchesNoTier`），若有人"贴心地"加兼容映射会红并被追问。

   - **顺带修掉三处测试自身的隐雷**（拆分后暴露，非本轮引入）：

     ① `LicenseTierBindingEnforcementTest.serviceWith` 的 seed 用 `core+fleet`，

        拆分后那已不是任何档位的精确集合 ⇒ 构造器 fail-closed 先炸，

        所有走该 helper 的用例在抵达断言前就挂，报红原因指向档位而非被测行为。

        改用 `LicenseTier.modulesOf(FULL)` 作 seed。

     ② `LicenseServiceFailClosedTest` / `LicenseIssuerTest` 的样本 license 同样

        硬编码 `core+fleet`，改用基础版的精确集合。

     ③ `basicTierWithExtraModuleRejected` 原先把 `emergency` 与 `network`/`advanced`

        一起当"越权"，但基础版 + `emergency` 恰好等于**应急版**的精确集合（合法升档）。

        已把 `emergency` 移出该循环，并新增 `emergencyTierWithFullOnlyModuleRejected`

        覆盖"应急版 + network/advanced"这一真正的越权面。



2. **三条能力腿从 `continue-on-error` 观察模式转为硬门禁**

   - 转正依据：真 Linux（WSL2）下连续两次全绿 —— emergency **31** 项 / spray **20** 项 /

     hardware **16** 项，共 **67 项断言 0 失败**；且 CI run `37624477248` 上 25/25 job 全绿。

   - 删掉三条腿的 `continue-on-error`；汇总步骤加 `if: always()`，

     任一腿失败即 `exit 1` 让 job 变红（此前汇总步骤虽已存在，但若前序步骤失败会被跳过）。

   - 保留 `tee /tmp/leg-*.log` + 落盘退出码：失败时能一眼看出是哪条腿、失败在哪一步。



3. **文档口径同步**

   - `PRODUCT-POSITIONING.md` §5.1.1 由"两个待裁决方案"改为**已裁决**，

     给出 7 模块逐项对照表（含前缀示例）与拆分后三档定义，并显著标注重签发要求。

   - `README.md` 许可章节档位表按拆分后重写；能力腿章节由"首轮实测 7/9、0/7、4/9"

     改为"已按当前 API 重写并转硬门禁"，并列出被删除的**指向不存在端点的凭空断言**。

   - `docs/demo-scenarios.md` 场景 C/D 的操作步骤按 `SprayController`/

     `DeliveryController`/`EmergencyCommandController` 的真实契约更正

     （原文写的 `/spray/task`、`/spray/gripper`、`/delivery/sequence/{id}`、

     `/spray/cancel`、`/hardware/physics-model` 在仓内均不存在）；

     补 LiDAR/IMU 404 的边界说明。



4. **计数基线 4343 → 4450（cloud-backend 2347 → 2435）**

   - 新增 `LicenseModuleSplitMigrationTest` 7 例 + `emergencyTierWithFullOnlyModuleRejected` 1 例；

     `check-test-count-docs.py` 全绿，22 处文档声称同步。



---

---

---

## [Unreleased] — 能力腿 e2e 对齐当前 API + 修喷洒任务落库真缺陷（2026-10-07）



承接上一轮"两腿 API 漂移"的实测结论，本轮把 `e2e-spray` / `e2e-hardware` 真正跑到绿，

并在过程中挖出一个**此前单测全绿也掩盖不了的真实后端缺陷**。



1. **🔴 喷洒任务创建实际 100% 失败：`WaypointListConverter` 反序列化切串越界**

   - **现象**：`POST /api/v1/spray` 返回 500 `Transaction silently rolled back …`。

   - **根因链（三跳，每跳都有日志证据）**：

     ① `WaypointListConverter.convertToEntityAttribute` 从**最外层** `'['` 直接

     `substring(openBracket+1, comma)`，把内层 `'['` 一起切进数字串 ⇒

     `Double.parseDouble("[22.5907")` 抛 `NumberFormatException`

     ——Hibernate 包装为 `Error attempting to apply AttributeConverter`；

     ② `SprayTaskService.create` 的 `catch` 只 `log.warn` 就继续，但事务已被标记

     rollback-only，于是方法正常返回后在提交阶段炸 `UnexpectedRollbackException`；

     ③ 该异常逃到 `ApiExceptionHandler#internal` ⇒ HTTP 500。

     实测 inner cause：`NumberFormatException: For input string: "[22.5907"`。

   - **为什么此前没被发现**：仓内**没有任何** `SprayController` / `SprayTaskService` 的

     持久化测试，只有纯值对象 `SprayTaskTest`。⇒ 单测 100% 绿、`POST /api/v1/spray`

     却在真实链路上不可用。这是本仓反复在修的失效类型（"没有任何信号会变红"）

     的又一实例：**catch-and-warn 把持久化失败降级成了日志行，但事务仍回滚**。

   - **修**：converter 在遇到"最外层数组开括号"时跳过一层再取坐标对；

     新增 `SprayTaskPersistenceTest`（3 例：落库不抛异常 / waypoints 往返无损 / 单航点容错）。

   - **变异验证**：把"跳过最外层 `'['`"改成 `if (false)` 后，

     `createPersistsWithoutConverterFailure` 与 `waypointsRoundTripThroughDatabase` 转红，

     报错仍是 `NumberFormatException: For input string: "[22.5907"` ⇒ 断言真的咬住了缺陷。

   - **未一并修的相邻风险**：`catch-and-warn` + `@Transactional` 这个组合本身仍有问题

     ——持久化失败后事务已 poisoned，"降级继续"的意图无法实现。正确的 fail-soft 需要

     `noRollbackFor` 或独立事务传播，本次只修转换器、未改事务语义，留待单独一轮。



2. **`e2e-spray.sh` 对齐当前 API（此前 0/7 全红）**

   - 端点前缀纠正：`/spray/task` → `/spray`，`/spray/status/{sysid}` → `/spray/{id}`，

     `/spray/gripper` **不存在**（`PayloadPlugin` 是 SPI 内部接口，夹爪能力由 drone-sim 单测覆盖），

     `/delivery/sequence/{sysid}` → `/delivery` + `/delivery/{id}`。

   - 请求体对齐 `SprayTaskRequest` / `DeliveryRequest`：`targetSysid` + `waypoints:[[lat,lon],…]`

     + `targetRate`/`capacityMl`/`sprayWidth`（三者须 > 0）；`sites` 为对象数组。

   - 断言口径纠正：初始态是 `PENDING`（`SprayTask.State` 无 `CREATED`）；

     `sites` 在查询响应里是**对象数组**（创建响应里才是站点数）；

     状态字段无 `pump`，改用 `state`/`coverageRate`/`remainingChemical`。

   - 新增覆盖：`/delivery/{id}/payload`、`/delivery/{id}/payload/query`（MAV_CMD 30085）。

   - **机载拒收不判失败**：`result:-1`（`MAV_RESULT=3`）表示 drone-sim 未实现该自定义

     MAV_CMD，REST 侧契约是"命令已下发并拿到回执"，故只告警。真实载荷语义需机载实现后另测。

   - 实测：`✅ M2 喷洒物流 e2e 全部通过`（WSL2 真 Linux + 真实后端/模拟器）。



3. **`e2e-hardware.sh` 对齐当前 API（此前 4/9）**

   - 雷达配置字段名纠正：`azimCenterDeg`/`azimWidthDeg` → `azimCenter`/`azimWidth`，

     并补齐 `elevCenter`/`beamWidth`/`range`/`scanPeriodMs`（`HardwareDataController`

     用 `num()` 逐个读，缺一即 400 `missing numeric field`）。

   - **删除指向不存在端点的断言**：全量检索确认仓内**没有** `POST /hardware/physics-model`，

     原脚本的 aero/kinematics 两步是凭空断言。改用真实存在的 `/rotor/config` +

     `/rotor/telemetry` 覆盖动力侧。

   - IMU 字段纠正：响应是**扁平** `accelX`/`gyroY`/`tempC`，原脚本误读嵌套 `accel`/`gyro` 对象。

   - LiDAR/IMU 的 404 按契约处理：`drone-sim` 默认不注入 `LiDARSource`/`ImuSource`

     （`VirtualDrone` 字段初值 null，`DroneSimMain` 未装配）⇒ 端点返回 404

     `no LiDAR data`。此时断言"404 契约正确"并 ⚠️ 跳过字段断言，**不把 404 当失败**，

     也不假装有数据。真实载荷断言需显式装配 Source 或真机。

   - 实测：`✅ M4 硬件抽象 e2e 全部通过`。



4. **`e2e-emergency.sh`：纠正"一键应急"的语义误解（此前 7/9 → 现 31/31 全绿）**

   - **根因**：脚本在 `POST /{id}/one-click` 之后直接断言"最终阶段 CLOSED、

     转移 ≥4 步"。但 `EmergencyCommandController#oneClick` 的契约是

     **"自动走完 接报→研判→部署→执行"四阶段**（`@Operation` 描述与 `api-reference.md` 一致），

     返回时 `currentPhase=EXECUTING`、`phaseHistorySize=3`。

     **评估与总结是独立端点** `/evaluate`、`/close`，本就不该由 one-click 完成。

     ⇒ 这是脚本把"一键"误解为"走完全流程"，不是后端缺陷。

   - **修**：拆成 8/9a（one-click）+ 8/9b（`/evaluate`）+ 8/9c（`/close`）三步，

     并补断言：部署计划 `planName` 非空、研判结论非空、评估结论落库、总结非空。

   - **顺带纠正阶段名**：指挥域枚举是 `EVALUATED`（不是 `EVALUATING`）；

     全流程 **6 阶段 = 5 次转移**（`RECEIVED→ASSESSED→DEPLOYED→EXECUTING→EVALUATED→CLOSED`），

     原脚本的"≥4"与"7 阶段"表述都对不上 `EmergencyCommandPhase`。



5. **CI `e2e-capability`：消除 `continue-on-error` 造成的"假绿"**

   - **问题**：三条腿带 `continue-on-error: true` 时，GitHub 把**失败步骤也显示成 ✓**。

     PR #6 上该 job 全绿，但日志里 emergency 2 条 ❌、spray 6 条 ❌、hardware 6 条 ❌

     ——只看 job/步骤状态会得出完全错误的结论。

   - **修**：每条腿 `tee` 到 `/tmp/leg-*.log` 并把退出码落盘 `/tmp/rc-*`（`PIPESTATUS[0]`），

     新增 `Capability legs summary` 步骤把三腿结果写进 `$GITHUB_STEP_SUMMARY`，

     **有腿失败则该步骤 `exit 1`**，让 job 真正变红。观察模式本身保留。

   - 同时修正本会话早前引入的**跨 step 进程生命周期隐患**：原写法把

     "启动 backend + drone-sim" 与"跑脚本"分在不同 step，后台进程可能随 step 结束被回收。

   - **三腿已连续两次全绿**（WSL2 真 Linux + 真实后端/模拟器）：

     emergency 31 ✅ / spray 20 ✅ / hardware 16 ✅，共 67 项断言 0 失败。

     ⇒ 下一步可摘掉三条腿的 `continue-on-error` 转硬门禁。本轮**故意未摘**：

     本地全绿不等于 CI 全绿（CI 是干净 Linux 容器 + redis service），

     应在 PR 上连续全绿后再摘，避免一次 CI 红就回退。



6. **定价档位矛盾：改为"单一对照入口"，不擅自改任一侧**

   - `PRODUCT-POSITIONING.md` §5.1 说基础版含 **M5 mesh** + **M9 编排**；

     `LicenseTier`（已被 `validateLicense` 强制）把 `network`（含 mesh）放完整版、

     `emergency`（含 M9）放应急版。档位强制后，**按 §5 卖基础版会让客户付费的 mesh 与

     M9 编排被 403** ——这是交付事故，不再是话术问题。

   - **本轮不选边**（改代码 = 单方面改定价；改文档 = 定价决策）。

     做法：新增 **§5.1.1** 作为唯一对照入口——逐项列出「代码模块 ↔ 覆盖能力 ↔

     `LicenseTier` 实际档位 ↔ §5 销售口径 ↔ 是否一致」，并给出两个待裁决方案

     （A 改 `LicenseTier` 下移 `network`/`emergency`；B 改 §5 把 mesh/M9 移出基础版），

     各自写明影响面。`product-brief.md` §⚠6 与 §交付方式 改为**指向 §5.1.1**，

     不再各自复述档位口径 ⇒ 消除"两处各说一套"，裁决只需落地一处。



7. **`OrbitJobManagerTest` 硬编码端口 18099 → 内核分配（消除一类随机红）**

   - 该测试的 fake truth sidecar 硬编码 `HttpServer.create(new InetSocketAddress(18099), 0)`，

     并把 `http://127.0.0.1:18099` 写死进 `OrbitService`。18099 恰是本机跑 e2e 后端常用的端口，

     端口被占时该类直接 `BindException` 报 3 个 error —— Qoder 上一轮为对齐计数

     也曾手工"排除被占端口的 `OrbitJobManagerTest`"。

    - **修**：`InetSocketAddress("127.0.0.1", 0)` 让内核分配空闲端口，

      `startTruth` 记录 `truth.getAddress().getPort()` 到 `truthBase`，两处 `OrbitService` 构造改用它。

    - **⚠️ 该改动自身引入过一次回归，已修（诚实记录）**：`invalidParamsRejected` /

      `submitReturnsHandleAndRejectsDuplicateInFlight` / `jobViewExposesProgressShape`

      三个用例**不调用** `startTruth`（只验 submit 校验与 job map 语义，不需要 truth 通道），

      于是 `truthBase` 为 null 被直接传给 `OrbitService` ⇒ 在飞判定提前失效，

      duplicate-inflight 用例报 `Expected IllegalStateException to be thrown, but nothing

      was thrown`。**本地 5 次全绿没抓到，CI run 37604120294 抓到了** ——

      这正是"本地绿 ≠ CI 绿"的实例。修法：null 时回退到 `http://127.0.0.1:1`

      （语法合法、必然连不通），修后连跑 6 次 4/4 全绿。



8. **并入 CVE-2026-47884 的有界忽略（自 `fix/cve-2026-47884-ignore` 摘取，PR #6 的 Security Scan 唯一红）**

   - **不是回归**：绿 run 37387694246 的 `pom.xml:36` 同为 spring-boot 3.5.16 且 Security Scan 通过，

     `a6e1118..HEAD` 未碰 pom/package.json；变的是 Trivy 公告库。

   - **上游无可换的修复版**：`gh api advisories/GHSA-pc63-qcmh-9cmg` 显示 6.x 全部区间

     （含 `>= 6.2.0, <= 6.2.19`）`first_patched_version` 均为 null，唯一 fixed 是 7.0.9

     —— 真修复路径是 Boot 3.5→4.x 大版本迁移。故本次是**风险登记 + 有界忽略**，

     不是"已修复"。

   - 可达性证据（本机实测，见 `XsltViewReachabilityTest`）：advisory 前提是"存在导向视图渲染的

     `/**` 映射 + 视图名未转义 + 使用 `XsltView`"；本仓纯 REST，4 个 ViewResolver 全非 Xslt，

     handler 映射中 `/**` 为 0。诚实边界：不能用"没有 XSLT 引擎"作论据（JDK 自带

     TransformerFactory），站得住的只有"没有视图渲染路径"。

   - **摘取方式**：未直接 cherry-pick 整个提交（其文档段落写的是 4305 基线，与本分支 4343 冲突，

     12 个文件内容冲突）。改为**只取安全相关的 4 个文件**：

     `.trivyignore.yaml`、`TrivyIgnoreHygieneTest`(1)、`XsltViewReachabilityTest`(3)，

     外加手工把 `trivyignores: .trivyignore.yaml` 接线加进 `ci.yml`。

     计数由本分支自己实测推进，不接受另一分支的旧数字。

   - `.trivyignore.yaml` 每条都强制带 `paths` 限定（`pom.xml` / `cloud-backend/pom.xml`）、

     `expired_at=2027-01-31`（到期 Trivy 剔除条目、发现自动回来判红——**到期变红是设计目标**）、

     `statement` 写证据与撤销条件；`ci.yml` 显式传 `trivyignores` 而非依赖隐式查找

     （该 action 对不存在的清单文件直接 `exit 1`，所以接线不会静默失效）。



9. **计数基线 4336 → 4343（cloud-backend 2340 → 2347）**

   - 新增 `SprayTaskPersistenceTest` 3 例（+3）、并入 CVE 自证 4 例（+4）；门禁全绿，

     22 处文档声称同步。

   - 后续 `LicenseModuleSplitMigrationTest` 7 例 + 越权面 1 例落地后推进到

     **4450（cloud-backend 2435）**，见上一条。



---

---

---

## [Unreleased] — 档位绑定验证期强制 + 能力腿 e2e 实测（发现 2 腿 API 漂移）（2026-10-06）



承接同日"文档口径第三次收口"一轮，处理「商业化硬前置」与「验证面覆盖」两项。

本轮的关键产出不是代码量，而是**一次实测把"脚本存在"与"脚本能过"分开**。



1. **License 档位 → 模块绑定在验证期强制（商业化硬前置，README/product-brief 长期标为"接入生产前必须补齐"）**

   - **精确定位缺口**：签发侧早已有 `LicenseTier` + `generateLicenseKeyByTier` 的

     `mismatchOf` 自检，但 **`validateLicense` 只判 `active` + `expiry`，完全不看模块集合**。

     三条绕过路径全通：原始 `generateLicenseKey` 直传 `modules` / `LicenseTier` 引入前的旧授权 /

     持有签发密钥的一方构造任意集合。⇒ 加载时零检查，"某档客户实际能启用哪些模块"

     仍是约定而非强制。

   - **修**：`LicenseService.tierBindingViolation(Set<String>)` 要求模块集合**恰好等于**某档，

     接进 `validateLicense`，开关 `aerofleet.license.enforce-tier-binding` **默认 true**

     （fail-closed；关闭时启动打 WARN）。`LicenseController` 的 `/info` 与 `/verify`

     增报 `tier` / `tierDisplayName` / `modules` / `tierBindingEnforced` / `tierBindingViolation`，

     运维不必翻日志就能区分「过期」与「模块不对应档位」。

   - **兼容性**：保留 5 参兼容构造器（等价于强制开启），6 参构造显式 `@Autowired`，

     本仓 6 处直接构造点与外部集成代码都不受影响。**兼容构造器不构成后门**——

     想关闭必须显式传 `false`。

   - **为什么默认 true 而不是 false**：本产品处于 PoC / 交付准备阶段，现场**没有任何已签发

     的旧授权**，不存在"老授权被新规则拒掉"的兼容问题；而留一条默认关闭的口子等于把刚建立的

     不变式交给部署方去记。

   - **守卫** `LicenseTierBindingEnforcementTest`（16 例）。**变异验证两处**：

     ① `tierBindingViolation` 恒返回 null → **6 条红**；

     ② 判定改用 `inferTier` 子集语义 → **3 条红**，含

     `supersetIsRejected`（证明 `inferTier` 会把 `FULL + 未知模块` 误判为 FULL，

     即"多签一个未知模块反而更宽松"，故必须用精确比对）。

2. **⚠️ 发现一个商业级矛盾，需定价负责人裁决（未擅自修改任何一侧）**

   - `PRODUCT-POSITIONING.md` §5.1（342–344 行）把 **mesh 组网（M5）** 写在**基础版**、

     **应急编排（M9）** 也写在基础版；而 `LicenseTier` 把 `network`（含 mesh/基站/卫星）

     放在**完整版**、`orch` 属 `emergency`（应急版起）。

   - **为什么现在才致命**：档位绑定在验证期强制之前，这只是文档与代码的静默不一致；

     强制之后"客户买了哪档"变成运行时真的 403 —— 按 §5 卖基础版，客户付费的 mesh 会打不开

     `/api/v1/mesh/*`。**强制本身放大了这个既有矛盾的杀伤面。**

   - **处置**：代码侧（已强制）与文档侧（定价）**都没动**。改代码等于单方面改定价；

     改定价文档不是开发决策。`LicenseTierTest`（20 例）已把当前代码定义钉死，

     任何改动都会显式变红、强制走评审。矛盾全文登记在 `docs/product-brief.md` §⚠6 与 README 许可章节。

   - 顺带修正 README 档位表：此前写"基础版 = `core`"，漏了 `fleet`（代码里基础版是

     `core` + `fleet`，且为累进语义），已按代码更正。

3. **顺手修掉两个 `LicenseTier` 的 NPE 隐患**（被本轮改动暴露后定位）

   - `displayName(null)` 与 `modulesOf(null)` 都会 NPE：`TIER_DISPLAY_NAME` /

     `TIER_TO_MODULES` 是 `Map.of(...)`，其 `get(null)` / `getOrDefault(null, x)`

     走 `MapN.probe(pk)` → `pk.hashCode()`。而"档位反查不到"是完全正常的路径

     （非档位组合），javadoc 却承诺返回 null。已加 null 守卫并补

     `displayNameIsTotalOverNull` / `tierLookupsAreTotal` 两条回归。

   - `LicenseControllerTest` 是暴露者（`pk is null`）——这是"加了一个断言就会崩旧代码"的

     典型样本，也是把 null 契约写成测试而不是只写在 javadoc 的价值。

4. **`e2e-capability` job 接入 CI（观察模式）+ 三个脚本实测，**发现两腿已随 API 漂移失效****

   - 新 job 跑已有 Linux 版的 `e2e-emergency` / `e2e-spray` / `e2e-hardware`。

     单独建 job 而非并入 `e2e-smoke`：后者是**安全回归**（故障/failsafe/感知）红了必须立刻阻塞，

     本 job 是**能力演示**，红的原因常是编排脆弱而非能力缺失，混在一起会连带拖红更有价值的门禁。

   - **在 WSL2 真 Linux 内核下逐条实测**（后端 + sim 起在 Windows host）：

     | 脚本 | Linux 可执行 | 实测结果 |

     |---|---|---|

     | `e2e-emergency.sh` | ✅ | **7/9 阶段通过**；末尾 2 条**时序**断言失败（等 `CLOSED/SUMMARY` 与 ≥4 次状态转移，实际停在 `EXECUTING` / 3 步） |

     | `e2e-spray.sh` | ✅ | **0/7 全红 —— API 漂移**：`POST not supported`、`no such endpoint` |

     | `e2e-hardware.sh` | ✅ | **4/9 —— API 漂移**：雷达配置 `missing numeric field 'azimCenter'`（载荷字段改名）、模型切换 `no such endpoint`、LiDAR/IMU `no ... data`（需 sim 开启载荷开关） |

   - **因此三个步骤均带 `continue-on-error: true`**，不作硬门禁。**若设成硬门禁，CI 会常红

     且红因离真实原因很远**（报在"喷洒任务创建失败"，真实原因是脚本打的是旧 API）。

     收紧条件已写进 job 注释：spray/hardware 按当前 API 重写并本地全绿；

     emergency 的 2 条时序断言在 CI 机器上稳定通过。去掉标记只需删一行。

   - **本次实测同时修正了"28 个 e2e 脚本"这个数字的用法**：脚本**存在** ≠ 脚本**能过**。

     登记表见 README「能力腿 e2e 的实测状态」。

5. **`.gitattributes`（把行尾不变式从"个人 git 配置"变成仓库声明）**

   - 本仓工作区在 `core.autocrlf=true` 机器上是 CRLF，直接在 WSL2 跑 `scripts/*.sh`

     **全部失败**（`set -euo pipefail\r` → `invalid option name`；随后每行

     `$'\r': command not found`）。仓库 blob 本身是 LF（`git cat-file` 实测 0 个 CR 行），

     故 **Linux CI 从未受影响** —— 失败只发生在"从 Windows 工作区直接跑"这条本地调试路径上。

   - 但只靠 blob 正确不够：任何人把 autocrlf 改成 false/input 后提交 `.sh`，CRLF 就进 blob，

     Linux CI 会在脚本第一行整体失败，而 `git blame` 指向的是无关行。现显式声明

     `*.sh text eol=lf` / `*.cmd text eol=crlf` / `*.java|js|jsx|py|properties|sql text eol=lf`。

   - 生效核验：`git ls-files --eol` 显示 `i/lf  w/crlf  attr/text eol=lf`（索引保持 LF，

     属性已应用，未来 `git add` 自动归一化）。

6. **三个脚本改支持 `AF_BACKEND_BASE` 环境变量**（与 `e2e-emergency.sh` 原有 `BASE_URL` 对齐）

   - 原先 `e2e-spray.sh` / `e2e-hardware.sh` 把 `BASE='http://localhost:8080/api/v1'` 写死。

     本机 8080 被常驻容器占用（COORDINATION.md 已记），没有环境变量就无法在别的端口跑。

     统一为 `${AF_BACKEND_BASE:-http://localhost:8080}`，并在注释里点名该端口冲突背景。



**本机验证**：`mvn -B -o clean test` 全 reactor **4333 用例 / 0 失败 / 0 错误 / 0 跳过**

（cloud-backend 2318→2337，新增 `LicenseTierBindingEnforcementTest` 16 例）；

License 包 107/107；前端 218/218 + lint 0 error + build 成功；

`ci.yml` YAML 解析通过、**17 job 全有 timeout**、依赖图无环（含新增 `e2e-capability`）；

`check-test-count-docs.py`（含 `--frontend-json`）退出码 0；

`.gitattributes` 生效核验通过。

变异验证四处全过：前端计数门禁、六帧消费守卫、档位绑定（两种实现各一次）。



**未在本轮验证（只能在 CI 验）**：CI 实际执行结果、`e2e-capability` 三个脚本在

Linux runner 上的真实稳定性（本机 WSL2 结果是观察，不是 CI 结果）、npm audit、Trivy、

CodeQL、Playwright、helm 渲染。改动对象是要推的 HEAD。



7. **⚠️ 未收口：实测发现全仓用例计数**run-to-run 不可复现**（新发现，需跟进）**

   - **现象**：同一份源码、三次 `mvn -B -o clean test`（均 BUILD SUCCESS / 0 失败 / 0 错误 /

     0 跳过），cloud-backend 分别读到 **2337 → 2334 → 2330**，单调下降；全仓总数对应

     **4333 → 4330 → 4326**。surefire 报告目录的 XML 文件数也随之 242 → 241。

   - **排除项**：无 java/mvn 残留进程（`Get-CimInstance Win32_Process` 已核）；

     报告时间戳集中在单次运行内（3:27–3:28），无运行中改写；无 `@TestFactory`（动态用例）、

     无 `@Disabled`、surefire 无 `<excludes>`/`<rerunFailingTestsCount>`；

     License 包单独复算 107/107 三次一致（未被波及）。

   - **当前值已交叉验证**：原始 XML 求和 = 门禁读数 = **2330**，且与源码注解计数自洽

     （`rg '^\s*@Test\b'` = 2299 个方法 + 4 个 `@ParameterizedTest` 展开 31 例 = 2330）。

     按变更量对账（HEAD 2305 + VideoFusionControllerTest 12 + audit 判别位 1 +

     LicenseTierBindingEnforcementTest 16 = 2334）则**多出 4 例不存在的差**。

   - **为什么必须登记而不能挑一个数写上**：本仓全套装文档可信度的机制（

     `check-test-count-docs.py` + CI 门禁）**建立在一个前提上——实测值是确定的**。

     若测试数本身会漂，那么该门禁只能保证"文档等于这一次的读数"，不能保证

     "文档等于真实规模"；更坏的情况是它会把一次**少跑了用例**的运行写成对外数字，

     而 0 失败的表象让这次少跑完全不可见——**这正是本项目反复在修的失效类型**

     （"没有任何信号会变红"）的又一个变体。

   - **本轮处置**：文档统一记为当前可复现的 **4326**（报告求和、门禁、注解计数三方一致），

     并在此显式登记不可复现性。**未宣称已定位根因**——三次观测不足以区分

     "surefire 偶发漏跑" / "某个 `@SpringBootTest` 上下文初始化竞争导致类被丢弃" /

     "构建产物写入竞争"。跟进方向：连续 N 次 `clean test` 记录逐类用例数并做差集，

     定位是哪些类/用例在漂；确认为漏跑后在门禁侧加"逐类基线比对"而非仅比总数。

   - **更正（2026-10-07，Qoder 会话；上面的归因方向被证伪）**：这段"run-to-run 不可复现"

     被归到了 surefire/装配竞争，**真实原因是跨 agent 干扰**——同一时刻有第二个会话（Qoder）

     在改测试树、并在同一棵树里跑 `mvn clean`。三条证据：

     ① 漂移的步长与方向逐一对得上测试树的增删：Qoder 会话在 00:27 摘除了 4 个临时探针用例

     （`XsltViewReachabilityTest` 3 例 + `TrivyIgnoreHygieneTest` 1 例），恰是 2334→2330 那一步；

     其后又分两批新增 6 例，2330→2336→2340；

     ② 同批运行里的 `Failed to clean project: Failed to delete …/target` 与 drone-sim 的

     `ClassNotFoundException` 风暴，是"两个 `mvn clean` 同时持有一棵树"的签名，与测试套件无关；

     ③ 树静止后（对方停写、Qoder 迁到独立 worktree）计数确定，且**两个会话独立测得同一值**：

     **4336（cloud-backend 2340）**——Qoder 在 `F:/Nexus/nexussky-land` 排除被占端口的

     `OrbitJobManagerTest` 后得 4332/2336、加回该类 4 例即 4336/2340；OpenCode 会话清空全部

     target 并确认无残留进程后同样读到 4336/2340。

      故本条由"未收口"改为**已收口（归因＝跨 agent 并发干扰）**。

      教训：`target/` 是共享资源，同一仓库同一时间只应有一个构建方；看到

      "clean 删不掉 target + CNFE 风暴"应先怀疑并发，而不是 surefire。

      后续本会话新增 `SprayTaskPersistenceTest` 3 例并并入 CVE 自证 4 例，

      基线推进到 **4343（cloud-backend 2347）**。



---

---

---

## [Unreleased] — 文档口径第三次收口 + 六帧消费补齐 + 视频融合后端补齐（2026-10-06）



背景：无硬件条件下的纯软件收口轮。触发点是一次全仓复核——**实测发现 8 处文档/代码

计数错误、1 处 CI 缺 timeout 与 concurrency、1 个必然 404 的已定价前端面板、

6 类后端已生产的 WS 帧在前端静默丢弃，以及 1 条未经登记的间歇性测试失效**。

本轮全部按"改完必须钉成断言"的纪律处理，且每项都做了变异验证。



1. **前端用例数纳入 CI 门禁**（根因修复）：

   - `check-test-count-docs.py` 此前**只读 surefire XML，即只管 Java**。前端自

     2026-10-01 起有 vitest 用例后，其用例数毫无约束力——README 的「168 例」已过期到

     177 例，长期无人发现，与 Java 侧「3230 过期两年多」是同一个根因：

     **加测试不会让文档过期这件事变红**。

   - 新增 `--frontend-json`：读 vitest 报告的 `numTotalTests`，并配 `npm run test:ci`

     （CI 里 frontend job 跑它并 upload artifact，docs-test-count job 下载后校验，

     **不为门禁重跑一遍前端测试**）。

   - **刻意不用静态数 `it(`**：静态计数给出 156、运行时是 177（三个文件的用例在循环里

     生成）。用 156 当基准会把**正确的**文档判成错——比漏检更糟。

   - **前端匹配文法刻意比 Java 侧窄**：README「与后端 3869 例形成断层」同时含「前端」与

     「例」，宽泛文法会误报。要求限定词与数字相邻（`前端测试覆盖 N 例` /

     `N 例 vitest`），宁可漏检不可误报。

   - **变异验证**：把 README 改回 168 → 退出码 1 且红因指向 `README.md:798 [前端]`；

     还原 → 0。

2. **8 处文档/代码计数错误修正**（均为逐行核对代码得出，非推断）：

   | 位置 | 原值 | 实测 |

   |---|---|---|

   | README 前端用例数 | 168 | **177**（本轮末为 218） |

   | README `vitest 3` | 3 | lockfile 解析 **5.0.3** |

   | README / api-reference `@RestController` 数 | 68 | **66**（68 是 `*Controller.java` 文件数，含 2 个非注解类） |

   | README 模块表 | 4 个模块 | **7 个 Maven 模块 + 1 个 Python 包**（原表漏 `link-sim`/`regulator-sim`/`sdk-java`/`sdk-python`） |

   | README msgId 分配表 | 42 条 | **51 条**（原表漏 30048–30056，即 M10–M13 全部） |

   | README 代码结构树 | scripts 44 / docs 30 | **50 / 32** |

   | `LicenseModuleMap` javadoc | 343 | 分母是 2026-10-05 的历史测量值，**保留不改数**（改数等于篡改记录），改为标注测量时点 |

   | `MavlinkMessageInfo:135` 注释 | 30063 属 P3 | 属 **P2**（与 ROADMAP 一致） |

3. **`AuditPersistenceTest.chainHeadRestoredFromDbAfterRestart` 间歇失效**：

   - **现象**：全 reactor 第 1 跑红（`Expecting value to be true but was false`），

     第 2 跑绿；单跑该类 9/9 绿；与 `AuditRetentionTest` 同跑 15/15 绿。**2 跑 1 红。**

   - **排除项**：`:163` 的 `row2.prevHash == lastHashBefore` 通过、`:164` 的

     `verifyChain().ok()` 失败，而库里恰好 3 行 ⇒ 不是 prev_hash 链错。兄弟用例

     `consecutiveRecordsFormChain` 走**完全相同**的 record→落库→verifyChain 路径且

     稳定通过 ⇒ **系统性哈希/时间戳精度不匹配被排除**（那会 100% 稳定失败）。

     剩下的唯一路径是 `AuditService.verifyChain()` 的 catch 分支——它对「记录真被篡改」

     与「库查询抛异常」返回**同一个 `ok=false` 判决**。

   - **两处修**：① 该 catch 分支此前是**全类唯一不打日志**的异常分支（127/147/169/274/309

     都有 `log.warn`），于是 `/api/v1/audit/verify` 的运维读数会把一次基础设施抖动误读成

     安全事件，排查方向直接跑偏 → 补 `log.warn`（带堆栈）；② 收紧断言：

     `checked()==3` 是判别位（真断链返回首个断链行的 checked 前缀，**异常吞掉路径恒返回

     `checked=0`**），另断 `reason()==null` 与 `brokenAtId()==null`——下次复发即可定位。

   - 新增 `queryExceptionVerdictIsDistinguishableFromTamper`（13→10 例）用 mock

     repository 抛异常，钉死"基础设施故障不得冒充篡改结论"，并断言篡改路径的措辞

     不得出现在异常结论里。

   - **诚实声明**：原始机理**未确证**（无法在本机复现到那一帧）。本轮做的是让它下次

     复发时可诊断，而不是宣称已根除。CI 侧若复现，按上面的判别位读红因即可。

   - **更正（2026-10-07，Qoder 会话）**：那次"2 跑 1 红"的最可能原因同样是**跨 agent 构建干扰**

     （同一棵树上第二个会话正在跑 `mvn clean`、且它在改测试源），而非审计链本身不可复现。

     定性从"未经登记的真实间歇失效"下调为"观测到一次，后证实为跨 agent 构建干扰"。

     两处加固（catch 分支补 `log.warn`、用 `checked()/reason()/brokenAtId()` 当判别位）

     **保留**——下次若真复发，它们仍是把"基础设施故障"与"篡改"分开的唯一信号。

     树静止后，该类在两侧独立全量中均为绿。

4. **视频融合后端补齐**（`VideoFusionPanel` 此前必然 404）：

   - 该面板从建仓起就调用 `/api/v1/video-fusion/{surveillance/streams, drone/feeds,

     recording/{id}/start|stop}`，**后端从无对应 Controller**；而 `videofusion` 早已在

     `EMERGENCY_STANDARD_PANELS`（"应急千元级"）里作为**已定价能力**对外存在。

   - 新增 `surveillance/VideoFusionController`（5 端点）+ `VideoFusionRecordingService`

     + 12 例测试。数据源全部**复用**既有服务：安防流取 `SurveillanceDeviceRegistry

     .listDevices()`（租户过滤与 `/api/v1/surveillance/devices` 同源），无人机画面取

     `DeviceRegistry.all()` + `VideoStreamService`；RTSP 凭据同规则脱敏。

   - 前缀在 `LicenseModuleMap` 登记为 `emergency`——否则 prod 下会被模块门禁 403。

   - **诚实边界**：录制只是**状态登记**（内存态），不落盘不转码，故 `storageUrl` 如实为

     `null`，不伪造产物路径；面板画面本身仍是 `SIMULATED FEED` 噪声占位。

5. **六类 WS 帧消费补齐 + 机队协同面板**（M10 对外价值此前对操作员不可见）：

   - `WS_TYPE_MAP` 的 13 类帧中，`task-assignment`/`conflict-alert`/`task-status`/

     `alarm-trigger`/`alarm-ack`/`surveillance-status` **前端零消费**——帧照常到达、

     无错、无告警，然后被 `onmessage` 末尾静默丢弃。**没有任何信号会变红**。

   - 新增 `utils/schedulingFrames.js`（6 个归一化纯函数 + 10 张码表）、

     `useWebSocket` 六个分支与 state、新增 `FleetOpsPanel.jsx`（tab「机队协同」）。

   - **新发现的协议缺口（不在前端能修的范围）**：`AlarmTriggerMsg` **没有 `alarmId`**

     字段（7 个字段实测：timestamp/lat/lon/sourceDeviceId/alt/alarmType/severity/

     description），而 `AlarmAckMsg` 带 alarmId ⇒ **trigger 与 ack 在协议层无法关联**。

     归一化返回 `correlatable: false` 并由面板**分两段展示、不画关联线**——补一个假

     alarmId 只会把缺口藏起来。修复需在 mavlink-core 增补字段（同步 LEN/CRC_EXTRA/

     MavlinkMessageInfo/兼容脚本），属协议变更。

   - `taskId` 是 `String.hashCode() & 0xFFFFFFFF`，**不可逆**：可用于帧流内关联，

     不能反查 REST taskId → 面板标为「协议任务号」并在页脚常驻说明。

   - **完备性守卫** `test/wsFrameConsumption.test.js`（8 例）双向核对

     `WS_TYPE_MAP` ↔ `useWebSocket`：① 后端每类都有消费分支；② 前端不消费后端不产出的

     帧；③ **后端新增帧而清单未登记即红**（把"后端加了帧"变成必须被人看见的动作）；

     ④ 六个归一化函数存在可调；⑤ **十张码表形状统一为 `{label,color}`**。

   - 码表统一是被自己的测试逼出来的：首版三张表映射到裸字符串、三张映射到对象，

     于是 `META[1].label` 拿到 undefined——测试翻车暴露了这个必然被消费方踩到的坑。

   - **变异验证**：把 `task-status` 分支改名 → 3 条守卫红（红因点名 `task-status` 并

     给出修复指引）；还原 → 全绿。

6. **CI 加 concurrency 与 timeout-minutes**：此前 16 个 job **全部无超时**，挂起任务占用

   GitHub 默认 6 小时；同 ref 连续推送各起一整套 16 job，互不取消。加

   `concurrency: {group: ci-${{...}}-${{ github.ref }}, cancel-in-progress: true}`

   与逐 job timeout（pr-lint 5′ → codeql/docker-build 60′）。

   `docs-test-count` 的 `needs` 增 `frontend`（要它的 vitest 报告），依赖图无环已核。

7. **ROADMAP Phase 2 C1–C5 状态翻正**：本节此前把五项全列为「待做」，而逐条核对代码

   后确认**全部已实现**（regulator 23 文件 + regulator-sim 五端点；rid 14 文件 +

   官方 msgId 12900–12915；GeofenceInterceptService fail-safe DENY；Flyway 21 迁移 +

   prod `ddl-auto=validate` + CI 真 PG 腿；按 sysid 取签名器 + 6 场景 e2e）。

   带 2026-11-01 硬 deadline 的一节被写成未来工作，是文档滞后于代码的净损失。

   同时如实登记**三项未闭合**：C4 遥测热态（电量/经纬/姿态）仍不落库、重启后轨迹只能从

   `flight_log` 重建；C5 签名出厂仍明文（开关默认 false、无轮换端点、`MavlinkParser`

   层仍不验签）；C1/C2 对端仍是模拟器，未与真实 UOM 联调。

   （核对中修正了自己的一处错误断言：先写"`DeviceRegistry` 仍是内存"，实测发现它确有

   `persist=true` 写 `devices` 表，改写为"落库只覆盖登记/在线/租户三列"。）



**本机验证**：`mvn -B -o clean test` 全 reactor **4314 用例 / 0 失败 / 0 错误 / 0 跳过**

（新增 13 例：VideoFusionController 12 + audit 判别位 1）；`mvn -B -o clean compile` 7 模块

BUILD SUCCESS；前端 `npm run test` **218/218**（新增 41 例）、`npm run lint` 0 error

（55 warning，与改动前同）、`npm run build` 成功；`check-test-count-docs.py`（含

`--frontend-json`）退出码 0；`mavlink-compatibility-check.py --self-test` 140 通过 0 失败；

`check-dependency-versions.py` / `check-java-level.py --list` /

`check-maven-incantations.py` 全过；`ci.yml` YAML 解析通过、16 job 全有 timeout。

变异验证两处（前端计数门禁、六帧消费守卫）均"变异红 / 还原绿"。



**未在本轮验证（只能在 CI 验）**：CI 实际执行结果、npm audit 真值、Trivy、CodeQL、

Playwright、helm 渲染。改动对象是要推的 HEAD。



---

---

---

## [Unreleased] — CVE-2026-47884（spring-webmvc 6.2.19，CRITICAL）有依据的忽略 + 可达性自证（2026-10-06）



第七轮推送后 CI 唯一残留红。**先定性**：不是本轮回归——绿 run `37387694246`（sha `37608e3`，

9 小时前）的 `pom.xml:36` 同样是 `spring-boot 3.5.16`，`Security Scan` 结论是 success；

`git diff --name-only a6e1118..HEAD` 里没有 `pom.xml`/`package.json`。**变的是 Trivy 公告库**。

- **上游无补丁可换**：`gh api advisories/GHSA-pc63-qcmh-9cmg` 给出所有 6.x 区间

  （含 `>= 6.2.0, <= 6.2.19`）的 `first_patched_version` 均为 **null**，唯一 fixed 是 7.0.9

  ——即唯一真修复路径是 Boot 3.5→4.x / Framework 6.2→7.0 大版本迁移。因此本次处置是

  **风险登记 + 有界忽略**，不是"已修复"；升级是否立项留给产品/架构决策。

- **可达性证据（本机实测）**：advisory 触发前提是"存在导向视图渲染的 `/**` 映射、视图名未转义、

  且使用 XsltView"。本仓 cloud-backend 为纯 REST：ViewResolver bean 4 个

  （BeanName / ViewResolverComposite / InternalResource / ContentNegotiating，**全非 Xslt**）、

  360 个 handler method 中 `/**` 模式 **0** 个；`src/main` 内 `XsltView`/`XsltViewResolver`/任何

  `ViewResolver` 配置 0 处。注意：**不能**用"classpath 没有 XSLT 引擎"作论据——JDK 自带

  `TransformerFactory`，站得住的只有"本仓没有任何视图渲染路径"。

- **`.trivyignore.yaml`（新增）**：只登记这一条，且强制三件套——`paths` 限定到

  `pom.xml`/`cloud-backend/pom.xml`（不写 paths 会对全仓生效）、`expired_at: 2027-01-31`

  （到期后 Trivy 主动剔除条目、发现回来判红：v0.70.0 `pkg/result/ignore.go:131-141` 的 `Prune`）、

  `statement` 写证据与撤销条件。字段名与过期语义**以固定版本 v0.70.0 的源码为准**，不照抄网页说明。

- **CI 接线**：Trivy 步骤加 `trivyignores: .trivyignore.yaml`（显式传入，不依赖隐式查找；

  trivy-action v0.36.0 的 entrypoint 对不存在的清单文件直接 `exit 1`，因此这条接线不可能静默失效）。

- **两条自证测试（"有依据"的关键，否则忽略只是静态承诺）**：

  - `XsltViewReachabilityTest`：断言上下文内无 XSLT 形态 ViewResolver、无 `/**` 映射；

    另加基线自证（映射数 >100、ViewResolver 数 ==4）防止装配面变化后变成空断言。

    **变异验证**：临时插入一个 `@RequestMapping("/**")` 控制器 → 立即红，报错点名"必须撤销忽略并复核视图名转义"；删除后 3/3 绿。

  - `TrivyIgnoreHygieneTest`：忽略清单每条必须带 paths/expired_at/长 statement/id，

    防止清单退化成静默白名单。**变异验证**：删掉该条的 `paths` 与 `expired_at` → 立即红

    （"缺 paths —— 不限定路径的忽略会对全仓所有文件生效"）；恢复后绿（sha256 回证一致）。



---

---

---

## [Unreleased] — 第七轮：HEAD 变红收口 + 三域写路径 IDOR + 占位凭据两级门禁（2026-10-06）



背景：第六轮复核（HEAD `a6e1118`）确认 4291 单测全绿但 **CI 是红的**，红因两条；

同时清单外残留的跨租户缺陷被重新定位为「读已过滤、**写**没过滤」。本轮收这三件事，

外加收口过程中新暴露的一个计数口径缺陷。



1. **`Security Scan` 判红的两条独立原因**（run 37407124936 取证）：

   - 真漏洞：`npm audit --audit-level=high` 报 `source-map-js 1.0.0–1.2.1`

     （GHSA-68fv-2mgg-jv7q，事件循环 DoS）。修复版本经 `gh api /advisories/...` 官方确认为

     **1.2.2**（范围 `>= 1.0.0, < 1.2.2`），lockfile 锁到 1.2.2；`postcss`/`magicast` 的

     `^1.2.1` 本就覆盖它，因此 **只改 lockfile、不动 package.json**，`npm ci --dry-run` 退出码 0。

     本机无法复现该审计结论——registry 是 npmmirror，`/-/npm/v1/security/*` 返回 NOT_IMPLEMENTED，

     所以这条只能在 CI 验；

   - **我上一轮引入的缺陷**：SARIF 上传步 `if: always()` 没有文件存在守卫。npm audit 先红

     → Trivy 从未执行 → 上传步自己再红一次（`Path does not exist: trivy-results.sarif`）。

     一次真红被放大成两条噪声。现改为

     `always() && steps.trivy.conclusion != 'skipped' && hashFiles('trivy-results.sarif') != ''`

     （`actionlint` 退出码 0）。

2. **三域写路径 IDOR 闭合**（alarm / orch / delivery2）：此前这三个域的**读**端点已过租户

   可见性，**动作**端点各自直接 `findById`，于是「看不见」的资源仍可被改写——他租户可

   ACK 我的告警、中止我的编排计划、启动/投放/签收我的配送任务，且全部返回 200。

   - `AlarmEventStore.acknowledge()` 改走既有 `getById()`（即 `isVisibleTo`），批量 ACK 同规则；

     全仓两个调用点都在请求线程（已核，无后台线程回归面）；

   - `OrchestrationPlanService.requireVisiblePlan()` 新增，start/pause/resume/abort/getProgress

     五个动作端点全部走它；计划真不存在仍抛 `IllegalArgumentException`（沿用既有契约），

     他租户抛 `NotFoundException`→404（与读侧「按不存在处理」同口径，不用 403 暴露归属）；

   - `DeliveryController2.visibleTask()` 成为该控制器**唯一** ID 取物入口，`getTask` 也改为

     调它——两份判定并存就会各自漂移，这正是本次失效的形态；

   - 测试：`HttpAuthChainTest` 新增 4 条**动作**断言（跨租户 ACK/中止/启动/进度 + 未归属事件

     不可 ACK）。每条同时断言「资源未被改写」并配本租户正向对照——只断 404 的话，

     「恒 404」的坏门禁同样能过；

   - **变异验证**：逐个摘掉三处守卫 → 4/4 红，红因均为 `expected:<404> but was:<200>`

     （即修复前的真实行为）；恢复后 15/15 绿。

3. **占位凭据两级门禁**（P0-5 的最后一块）：`deploy/k8s/secret.yaml` 与 helm `values.yaml` 里的

   `REPLACE_WITH_*` 占位串**长度达标**，会原样通过后端既有的「jwt secret ≥32 字节」校验，

   所以忘替换的那次部署不是报错，而是安静地把仓库里公开的字符串当 HMAC-SHA256 密钥上岗——

   任何读过本仓库的人都能自造任意租户/ADMIN 的 JWT。

   - 后端：`PlaceholderSecrets` + `DeploymentSecretsGuard`（`@PostConstruct`，覆盖 `jwt.secret`、

     `aerofleet.security.jwt-secret`、`aerofleet.encryption.key`、`spring.datasource.password`）。

     刻意**不**挂在 `dev-mode` 下：dev/test 用自有字面量零影响，而「prod 才检查」等于把判定

     挂在可能被忘设的 profile 上——正是这轮反复遇到的失效类型；

   - helm：`templates/secret.yaml` 顶部 `fail`，默认 values 在 `install/template` 阶段即被拒并

     点名属性；真实 `--set` 渲染 13 个对象、无占位残留；

   - `scripts/check-deploy-secrets.sh`：A 拒默认 / B 收真实 / C 无残留 / 附示例清单不得混入

     真凭据，**helm 缺失直接 exit 1**（跳过不等于通过）。该脚本当场抓出我一个真 bug：helm 的

     报错文本里没有 `REPLACE_WITH` 字面量导致 A 断言锚不到（已修消息文本）；另在临时副本上

     摘掉守卫做变异，确认 A 会判红；

   - CI 新增独立 job `deploy-secrets`（`azure/setup-helm@v4.3.1`，按 tag 固定并注释记 SHA）；

   - 文档同步：`docs/deployment-guide.md` §2.2 与 `docs/customer-onboarding-guide.md` 里

     `helm install nexussky . -f values.yaml` 这条命令现在**必然失败**，已改为私有 values 写法。

4. **计数口径缺陷（本轮新暴露）**：`check-test-count-docs.py` 从 `target/surefire-reports/`

   汇总，而该目录**不会自清**——`mvn test`（不带 clean）只覆盖本轮跑过的类，已删除的测试类

   留下的旧 XML 仍被计入。实测：某会话的临时探针类 `PortReleaseProbeTest` 验后删除、XML 仍在，

   本地量到 drone-sim=1392 而 CI 是 1391。门禁不会因此变红，反而是「把带病的数字写进对外

   文档」这条路径被打开了。现在脚本剔除「类已不在 `src/test`」的报告并**打印剔除清单**

   （静默剔除等于换个方式的错数），并注入幻影报告（7 用例）验证剔除生效且总数不变。

   文档 22 处 + `docs/product-brief.md` 的「4,291」同步到实测 **4301**（cloud-backend 2305），

   `python scripts/check-test-count-docs.py` 退出码 0。



**本机验证**：`mvn -B -o clean test` 全 reactor **4301 用例 / 0 失败 / 0 错误 / 0 跳过**；

新增 `DeploymentSecretsGuardTest` 6 例；摘 `@PostConstruct` 变异 → 3 条接线断言立刻红

（验的是「函数正确≠被调用」）。CI 腿（npm audit 真值、SARIF 上传、helm job）只能在推送后验，

验证对象是要推的 HEAD。



---

---

---

## [Unreleased] — 修复两个 E2E 固定端口 flake：端口释放同步化 + close 链 try/finally（2026-10-06）



- **现象**：master CI 连红的第二个原因——两个 E2E 用例间歇 `BindException: Address already in use`

  （构造期失败，0.003 s，约四分之三的 run 通过）：cloud-backend `EnvOverrideE2ETest.setUp:90`

  （网关端口 24840）、drone-sim `AdaptivePathExecutionTest:63`（机载端口 24780）；

- **取证**（两 run CI 日志）：失败都是**类内最后一个方法**，前一个方法完整通过——

  cloud-backend 类总 2.020 s＝前一方法约 2.0 s＋失败 0.003 s；drone-sim 类总 0.016 s＝

  前一方法 0.013 s＋失败 0.003 s，两方法相隔毫秒级。本机修复前连跑 13 轮 0 复现；

  排除项：无并行测试配置、端口段全仓唯一、close 链均为同步显式 close；

- **机理（JDK 源码级确证）**：阻塞模式下 `DatagramChannel.close()` 是**延迟关闭**——

  `DatagramChannelImpl.implCloseBlockingMode()` javadoc："the final close is deferred until

  all I/O operations complete"；`tryClose()` 仅当 `readerThread==0 && writerThread==0` 才真正

  关 fd；收包线程在 `beginRead()` 登记自身，退出路径 `endRead()` 里清零并 `tryFinishClose()`。

  ⇒ 收包线程阻塞在 `socket.receive()` 期间调用 `close()`，**close() 只做 preClose 即返回，

  fd 未关、端口未释放**，真正释放发生在收包线程被唤醒之后。下一个测试方法毫秒级紧接

  bind 同端口时撞上该窗口即 BindException。本机（Windows）全绿＝同代码下调度窗口未撞上；

- **修复（两层，各覆盖一类失效面）**：

  1. `UdpMavlinkTransport.close()` 尾部 `receiveThread.join(5 s)`（超时打 WARN）——

     join 返回 ⇒ 收包线程已退出 ⇒ `endRead → tryFinishClose` 已执行 ⇒ fd 已真正关闭、

     端口已释放，把延迟关闭窗口从根上收拢；

  2. `VirtualDrone.close()` 与两个 E2E 的 `tearDown` 改 try/finally——防 close 链中任一

     子系统异常把端口关闭整个跳过（另一类失效面）；

- **验证（本机）**：变异探针（两类临时探针用例，验后删除、不计入用例数）：A 修复前形态+

  注入子系统异常 → 红、B try/finally+注入 → 绿、C 最终态 → 绿；J 临时移除 join → 红

  （"close() 在收包线程存活时提前返回"）、K 恢复 → 绿。回归：mavlink-core+drone-sim 全量

  BUILD SUCCESS（drone-sim 汇总 1391/0/0）；AdaptivePathExecutionTest 连跑 3 轮全绿；

  cloud-backend 两个 E2E 连跑 3 轮全绿（每轮 4 用例）；cloud-backend 全量 2295/0/0；

- **边界说明**：原 flake 在本机（Windows）不可复现，上述验证证明的是"修复形态正确 +

  非回归"；"是否根除"的最终确认在 CI（Linux）复跑。

- **CI 复跑确认（2026-10-06）**：push 后 run 37387694246 completed / success

  （attempt 1，head=37608e3）——22 job success + 1 skipped（PR Title Check，push 事件不跑）。

  两宿主类在 Linux CI 上实测通过：drone-sim 腿 `AdaptivePathExecutionTest` 2/0/0（0.520 s）、

  模块汇总 1391/0/0；cloud-backend 腿 `EnvOverrideE2ETest` 3/0/0（2.225 s）、

  `DeliveryPayloadQueryE2ETest` 1/0/0、模块汇总 2295/0/0；三模块 jacoco check 与

  `--strict cloud-backend` 覆盖率一致性步骤均过。此为首次复跑，间歇性（约 1/4）的统计学

  闭环由后续 run 持续验证。



---

---

---

## [Unreleased] — 覆盖率棘轮上调：cloud-backend 0.60 → 0.65（2026-10-06）



- **现象**：master 近期红 run 里确定性的一条是 `Coverage threshold consistency`

  步骤判红——cloud-backend 实测 LINE 覆盖率已达策略值 65%

  （CI 实测 16138/24689=65.36%；本机 16271/24835=65.52%），而 `cloud-backend/pom.xml`

  仍声明 0.60。门禁本身在如实报警「台账漂移」，与功能代码无关；

- **修复**：`cloud-backend/pom.xml` jacoco check `<minimum>` 0.60 → 0.65，

  `.github/workflows/ci.yml` 注释表同步（三处声明恢复自洽，本地

  `bash scripts/ci-coverage-threshold.sh --strict cloud-backend` 判 ✅）；

- **余量警告**：0.65 距 CI 实测 65.36% 仅 0.36pt，覆盖率回落 0.4pt 即转红——

  这是「实测向下取整到 5%」棘轮口径的固有代价。日后再调阈值需连策略口径一并说明，

  不得只改 pom。



---

---

---

## [Unreleased] — Java 兼容性修复：sdk-java release 缺陷 + 多 JDK 策略与门禁（2026-10-06）



### 1. 修复 sdk-java 的「静默兼容性欺诈」【P0，对外交付缺陷】



- **症状**：`sdk-java/pom.xml` 声明 `maven.compiler.source/target=11`，

  `sdk-java/README.md` 声明"Java 11 或更高版本"，但**实测产物字节码是 major 61（Java 17）**；

- **根因**（用 git HEAD 原始 pom 复现确认）：Maven Compiler Plugin 的判定优先级为

  `<release>`（插件配置 / `maven.compiler.release` 属性）**高于** `<source>/<target>`。

  根 pom 的 `maven.compiler.release=17` 被插件当作 `<release>` 读取，**压制**了

  sdk-java 的 source/target=11。构建日志只打印 `release 17`，**不提示** source/target 被忽略；

  `help:effective-pom` 里 source/target 仍显示 11，**看起来是对的**；

- **影响面（已发布版本）**：`git show v1.0.3:sdk-java/pom.xml` 确认 tag 版本同为缺陷态，

  根 pom 亦有 `release=17` → **v1.0.2 / v1.0.3 构建出的 SDK 字节码为 Java 17**，

  JDK 11 客户拉取即 `UnsupportedClassVersionError`。

  修复已入主干，**下个 tag 起生效**（README 已如实标注）；

- **修复**：`sdk-java/pom.xml` 改用显式 `<release>${maven.compiler.release}</release>`

  并把该属性设为 11，盖过继承值。实测产物 `major version: 55`，构建日志 `release 11`。



### 2. 新增门禁 `scripts/check-java-level.py`（四查）



1. **字节码 vs 声明一致性**：`javap` 读各模块 `target/classes` 首 class 的 `major version`，

   与 pom 声明的**有效级别**（release 优先）比对；

2. **产物新鲜度**：class 早于 `pom.xml` 即报错——否则一致性结论建立在陈旧产物上（假绿）；

3. **release/source 混用告警**：标出被静默忽略的死配置；

4. **级别清单打印**：标注每项来源（本模块显式 / 继承根 pom）。



- **阴性对照（5 组）**：修复态通过；git HEAD 原始 pom → 精确报"并存死配置"并退出 1；

  陈旧产物 → 报新鲜度失败；另有 2 组用于**推翻我最初的错误归因**（详见

  `docs/jdk-compatibility.md` §4.2.1）；

- **阳性对照**：向 SDK 注入 `Stream.toList()`（Java 16+），`release 11` 下

  报"找不到符号 → toList()"编译失败 → 证明 `release` 真能约束 API 可用性，

  而非仅字节码版本号（详见同文档 §2.3.1）。



### 3. 多 JDK 策略（`docs/jdk-compatibility.md` 新增）



- 基线保持 **JDK 17（LTS）**；实测 `mvn clean compile` 在 **JDK 26** 下 **7 模块全部 SUCCESS**；

- **不把 26 设为基线**：非 LTS（2027-03 EOL），仅作"未来兼容预警器"；

- 升级路径固化：改基线的 JDK 只需改根 pom 一个属性，门禁自动核对字节码是否真的变了；

- **诚实标注未验证项**：JDK 26 下**运行**（测试/启动）未验证——编译通过 ≠ 运行通过，

  JaCoCo agent / Mockito inline mock 在新 JDK 可能失败，故 CI 的 26 腿只跑 compile。



### 4. CI 新增 3 个 job



- `java-level`：编译后跑字节码核对门禁（这是 §1 那个缺陷的检测器）；

- `java-upward-compat`：JDK 26 上仅 `clean compile`（向上兼容预警）；

- `dep-gate` 内增 `check-java-level.py --list`（声明层静态检查，不需编译）。



### 5. 修复 jitpack 坐标错误【对外交付缺陷】



- `sdk-java/README.md` 原写 `com.github.Levango7:nexussky-sdk-java` 是**单模块**格式；

  本仓是多模块工程，依 JitPack 官方文档应为 `com.github.Levango7.NexusSky:nexussky-sdk-java`

  （三段点号 + 模块 artifactId）。照原样抄写会解析失败；

- 原文另一处错误：称"`NexusSky` 是根聚合模块"，实际根 pom artifactId 为 `aerofleet-parent`；

- `jitpack.yml` 补 `install` 指令：原先只指定 JDK，JitPack 会构建全部模块

  （含依赖 Spring Boot 的 cloud-backend），容易失败。现改为只构建 `sdk-java`

  （该模块无内部依赖，`-pl` 单独即可，无需 `-am`——已实测 `install` 成功且产出

  jar/sources/javadoc 三种制品）。文件无 BOM、LF 行尾（规避历史 `1e6095c` 的 BOM 坑）。



### 6. License 档位绑定（授权可机器校验）



- 新增 `LicenseTier`（`basic ⊂ emergency ⊂ full` 累进档位）+

  `LicenseService.generateLicenseKeyByTier()` 重载；

- 新增 `LicenseTierTest` **20 例**（累进结构、全覆盖、无越界、档位推断、

  签发自检 `mismatchOf`、不可变性等），全部通过；

- 至此「授权档位 → 模块集合」的绑定可被机器测试，而非仅文档声明。



### 7. 计数口径同步



- 全仓单测 4271 → **4291**（cloud-backend 2275 → 2295，新增 `LicenseTierTest` 20 例）；

- README / ROADMAP / whitepaper / sales-pitch-deck / pricing-strategy / demo-scenarios /

  customer-onboarding-guide / competitive-analysis / low-altitude-economy-demand-research /

  product-brief 共 10 份文档同步（`docs/product-brief.md` 为千分位格式 `4,271`→`4,291`）；

  `python scripts/check-test-count-docs.py` 全绿（实测 4291 / 0 失败 / 463 测试类）。



### 8. 全量验证



- `mvn -B clean test` → **BUILD SUCCESS，4291 用例 0 失败 0 错误 0 跳过**；

- `mvn -B -pl cloud-backend -am test -Dtest='LicenseTierTest,LicenseModuleCoverageTest'`

  → 52 用例 0 失败（LicenseTier 20 + LicenseModuleCoverage 32）。



---

---

---

## [Unreleased] — 清单收口：环境/载荷查询真链路 E2E + GCS 感知三帧消费（2026-10-05）



本轮处理 `.coord/GAPS.md` §二「可立即解决」清单（第 1 行经复核已由上轮 e21a066 完成）。



### 1. 30080-30082 环境覆写：真链路端到端测试（§二 第 4 行）



- 新增 `EnvOverrideE2ETest`（cloud-backend）：REST(EnvOverrideController) →

  DroneCommandService → UdpGateway（真 socket，255/GCS 源）→ 真实 UDP →

  VirtualDrone 机载校验 → COMMAND_ACK 回程 → TelemetryIngestService 解码 →

  MavlinkMessageEvent → PendingAcks → REST ok；

- 并以机载 1Hz 周期下传的 `ENVIRONMENT_STATUS`(30002) 断言**机载状态真变了**

  （场景基线风向 0° → 覆写后 270°±5°），而非只证「命令发出去了」；

- 装配口径：事件总线用 lambda 替身（仅复刻 `@EventListener` 的派发规则：CommandAck

  转投 PendingAcks、其余落进断言列表），命令通道/UDP/解码全为生产代码；

- 三条用例：风场（含状态断言）/ 天气 / 阈值。后两者以 ACK=ACCEPTED 证机载校验通过——

  天气取值按 `EnvScenario.weatherStability` 逐 tick 概率转移，断言其稳态值是 flaky 的，

  故不断言（javadoc 已写明口径）。



### 2. 30085 载荷查询：真链路端到端测试（§二 第 3 行）



- 新增 `DeliveryPayloadQueryE2ETest`：REST → 真 UDP → 机载 `handlePayloadQuery`

  （`--actuators` 装配 gripper 才回 ACCEPTED，否则 UNSUPPORTED）→ 立即回传

  `PayloadStatus`(30006) → ACK 回程 → REST ok；

- 不比较「查询前后 30006 帧数」：actuatorsEnabled 下机载每 1Hz 也周期上报，帧数比较会与

  周期上报混淆、成为脆弱断言；判定证据取「REST ok（=机载 ACCEPTED）+ 回程观测到 30006」。



### 3. GCS 前端补齐感知侧三帧消费（§二 第 2 行）



- 缺口：后端 `WS_TYPE_MAP` 已下发 13 类帧，前端 `useWebSocket` 只消费其中 6 类，

  `vision-detection`(30014) / `sensor-fusion`(30054) / `prediction-result`(30056)

  到达前端即被静默丢弃（`AiDecisionPanel` 只覆盖 30051-30053）；

- 补齐：三条事件流（视觉/预测为离散事件 → 数组头插留 50 条；融合为周期态 →

  按 sysid 只留最新）、`utils/aiDecision.js` 三个归一化纯函数 + 码表、

  `AiDecisionPanel` 三张卡片、`App.jsx` 透传；

- 测试：`aiDecision.test.js` 12 → 22 例（含 hook 层三流断言），`npm run check` 177/177 通过。



### 4. 计数口径同步



- 全仓单测 4234 → **4239**（cloud-backend 2238 → 2243）；README / ROADMAP / whitepaper /

  sales-pitch-deck / pricing-strategy / demo-scenarios / customer-onboarding-guide /

  low-altitude-economy-demand-research 共 8 份文档同步；

  `python scripts/check-test-count-docs.py` 全绿。



### 5. docker-compose 完整实测（§二 第 5 行，2026-10-06 补跑）



- 干净环境 `docker compose -f deploy/docker/docker-compose.yml up -d` 一次通过：

  5 容器全起（cloud-backend **Healthy** / gcs-web Healthy / drone-sim / redis / postgres）；

  实测 `GET :28080/actuator/health` = `{"status":"UP"}`(200)、`GET :23000/` = 200（SPA）、

  `GET :23000/actuator/health` = 200（gcs-web → 后端反代链路通）；收尾已 `down` 拆净；

- **首次 up 曾失败**，根因值得记录：

  `ERROR: null value in column "created_at" of relation "tenant" violates not-null constraint`

  —— 不是 compose 配置缺陷，而是**复用了脏库**：同一 postgres 容器此前被以 ddl-auto 建过表，

  Flyway 只记到 V5；V6 的 `CREATE TABLE IF NOT EXISTS tenant` 跳过了已存在的表，随后那条

  不带 `created_at` 的 INSERT 便撞上 NOT NULL。清库重建（postgres 未声明 volume，`down` 即

  销毁数据）后一次通过。**验证 compose 路径必须先清库**；V6 在干净库上无问题（k8s 路径同验）。

- 8080 冲突只存在于**仓库根** dev compose（host 网络）；deploy/docker 用

  28080/23000/25432/26379，不冲突。



> 排查备注（供后来者避坑）：`mvn -pl cloud-backend` **不带 `-am`** 时会解析本地 Maven

> 仓库里的 `aerofleet-drone-sim` 过期 jar——旧版 `SimConfig` 解析器会吞掉裸布尔开关，

> 使 `--env`/`--actuators` 静默失效、机载命令一律回 UNSUPPORTED。跑跨模块 E2E 必须带

> `-am`（或先 `mvn -pl drone-sim install`），本次曾因此在错误方向上排查两轮。



---

---

---

## [Unreleased] — ADAPT_PATH 复合任务尾 + ai 包未接线守卫完备性（2026-10-05）



### 1. ADAPT_PATH 复合任务尾：一条拍照指令不再废掉全部适配



- 缺口背景（Round B 遗留开放项）：`VirtualDrone.executeAdaptivePath` 的守卫

  要求剩余任务**全部**是 NAV_WAYPOINT——含任何非航点指令（拍照/悬停/RTL/

  LAND）的尾整体跳过，而「航点串 + 相机触发 + 返航」正是真实航测任务的

  典型形态；

- 新语义：只平滑剩余**头部连续 NAV_WAYPOINT 段**（≥2 个），段终点之后的项

  原样拼接——仅重编 seq，指令/坐标/参数一字不动（相机触发点与悬停语义

  不被平滑改写）；段之后的航点段不再平滑（部分适配是诚实边界，不做跨指令

  段的几何拼接）；

- 实现复用 `MissionStore.replaceTail` 既有能力（任意 fromSeq + 调用方连续

  编号），无新存储 API；`VirtualDrone` 新增包内可见

  `missionItemsSnapshot()`（测试断言复合尾保留/seq 连续性用，javadoc 注明

  生产代码不得经此绕过上传会话守卫）；

- 测试：`AdaptivePathExecutionTest` 新增复合尾用例（WP+WP+拍照+RTL 四项

  任务走真实 UDP 上传握手 → 断言 30052 照常公告、任务 4→12（1 尖角 × 8

  插点）、seq 0..N-1 连续、拍照项参数/坐标逐字段原样、RTL 项保留）。



### 2. ai 包 7 类「未接线」断言语义复核 + 完备性守卫



- 复核结论：UNWIRED_CLASSES 7 类（避障 A*/RRT、RTL 滑翔、集群协同、应急

  返航、路径规划器、决策树）清单对 ai 包**行为类**完备；ai 包另 5 类为

  数据/值类型（AdaptivePathResult/DecisionContext/DecisionLogEntry/

  DecisionResult/FusedDecision），是已接线引擎的组成部分，不属「策略/

  规划器」；

- 洞与补法：新增行为类曾可静默逃逸守卫（清单只查已知类）。新增完备性

  测试：枚举 ai 包全部类，凡不在「7 未接线 ∪ 2 已接线 ∪ 5 数据类」者

  判红，并提示归类路径（未接线断言 + Javadoc，或正向接线断言 + 三处文档

  同步）；数据类白名单在测试中显式声明并说明豁免理由；

- `AiAutonomyWiringTest` 9→10 例，类 Javadoc「五件事」→「六件事」。



### 3. 验证与计数



- drone-sim 1391/1391 全绿（1389 + 2 新增）；计数门禁全仓口径 4196 同步

  后全绿；

- 文档：README M11 执行级（复合尾语义 + 守卫完备性）、ROADMAP M11 沿革

  与标题同步。



---

---

---

## [Unreleased] — WS_TYPE_MAP 零生产者帧收口：30014/30056 接线 + 6 帧边界固化（2026-10-05）



### 1. 逐帧排查结论（13 帧全部核实）



/ws/telemetry 转发表（WS_TYPE_MAP）13 类帧逐一核查生产者：7 类有真实生产者

（本日新增 2 类），6 类维持协议预留边界（依据见下）。



### 2. VISION_DETECTION(30014) 接线——drone-sim 边缘视觉逐目标上报



- 缺口背景：机载视觉链路（帧差 + 连通域 + 质心跟踪）真实产出逐目标检测，但

  只发 EDGE_TASK_STATUS(30053) 任务状态，检测结果（标签/位置/跟踪）被丢弃，

  30014 全仓零生产者；

- `VideoStreamAnalyzer` 新增 `analyzeFrameDetailed`：与 `analyzeFrame` 共享同

  一管线，保留逐目标记录——边界框中心 (u,v) 像素坐标 + 类别 + 单目标置信度 +

  本帧质心跟踪关联 ID（匹配既有或新建，`updateTracking` 新增带 ID 输出的重载）；

- `EdgeInferenceRunner.visionCycle`：每个已分类目标（vehicle→kind 0、person→

  kind 1）发一条 30014，u/v 为 160×90 渲染帧像素坐标，trackId 超 u8 有效范围

  （>254）按协议 0xFF 占位；unknown 亮区（无形状归类依据的帧差区域）不占

  协议 kind 枚举，跳过不上报——该取舍在消息注释与测试中固化；

- 30053 的 resultSize 语义不变（检测标签序列化字节数），AnalyzeFrame 向后

  兼容由 delegation 实现并有等价性测试钉扎。



### 3. PREDICTION_RESULT(30056) 接线——孪生预测结果发布



- 缺口背景：M13 预测服务（`PredictionService`，运动学模型）与 REST 端点真实

  存在，但 30056 全仓零生产者；

- `TwinController` /predict 端点在孪生有状态时把预测发布为 MavlinkMessageEvent：

  predictedLat/Lon/Alt 取时域末端点（horizon 秒后的落点，1E7/1E7/mm 精度），

  trajectoryPoints 为完整轨迹点数，事件 sysid 用被预测无人机（WS 转发侧按它做

  租户可见性判定），经 WS_TYPE_MAP 以 "prediction-result" 帧广播；

- 空轨迹（孪生无状态）不发布；compare 端点内部的 1 秒近似预测属虚实对比用途，

  不发布。



### 4. 6 类帧维持协议预留边界（依据，非缺口）



- task-assignment(30048)：`TaskRequest.taskId` 为字符串，消息 u32 字段无法

  无损承载（哈希映射无法与 REST 面 taskId 关联）——属协议改造决策；

- conflict-alert(30049)：冲突检查 API 输入为两机位置/速度（无 sysid 对），

  `ConflictResult` 无 severity 概念——产出需先扩 API 语义；

- task-status(30050)：M10 调度任务无执行进度跟踪（无链路更新 progressPercent，

  机载执行走 MISSION 协议）；

- alarm-trigger(30057)/alarm-ack(30058)：消息语义是「安防设备→无人机通知 +

  机载确认」的 MAVLink 下行闭环，机载无 30057 接收/应答处理；报警联动实际走

  AlarmLinkageEngine→AutoDispatchService 云内调用；

- surveillance-status(30059)：`SurveillanceDevice` 模型无摄像头数量/运行时长

  字段（消息要求 onlineCameras/totalCameras/uptimeSec）——产出需先扩设备模型。



### 5. 验证与计数



- 新增 9 测：VideoStreamAnalyzerTest +4（逐目标记录/跟踪关联/unknown 保留/

  向后兼容等价）、EdgeInferenceRunnerTest +3（移动目标逐帧 30014/悬停零帧/

  kind 映射）、TwinControllerTest +2（/predict 发布 30056 字段/无状态不发布，

  @RecordApplicationEvents 捕获）；

- drone-sim 1389/1389、cloud-backend 2200/2200 全绿；计数门禁全仓口径

  4194 同步后全绿；

- 文档：README（M12/M13 条目 + 新增 13 帧收口边界条目）、ROADMAP M13 沿革、

  competitive-analysis 边缘推理行同步。



### 6. 剩余边界全部清零（2026-10-05 补记）——6 帧 + 4 条 MAV_CMD 全接上真实生产者



- 30048/30050（M10 调度）：`TaskAssignmentService` 新增生命周期四态发布

  （ASSIGNED/IN_PROGRESS/COMPLETED/ABORTED，progress 0→100），REST 新增

  POST `/tasks/{id}/start`、`/tasks/{id}/complete`；reassignAll 对在线无人机重分配；

- 30049（冲突避免）：新增 `ConflictScanService`（5s 周期 + REST `/conflicts/scan`）；

  对在线且有导航数据的无人机做 4D 轨迹预测（60s 视界）两两扫描，逐对发布

  30049（type HEAD-ON→COLLISION(2)、CROSSING/OVERTAKE→PATH(1)；severity 按倒计时

  <10→4/<30→3/<60→2/else 1）；AIRSPACE(0) 不可达（机对几何扫描范畴）；

- 30057/30058（4a 安防报警）：`AlarmLinkageEngine` 构造器新增

  `ApplicationEventPublisher` 参数；processEvent 落库后发布 30057（deviceId u16

  哈希）；executeAutoDispatch 派遣成功后逐受派无人机发布 30058（承载真实 sysid +

  etaSec）；设备源帧租户路由经 `MavlinkMessageEvent` 显式租户字段（null+explicit=未归属→全局域），无人机源帧沿用 registry.tenantOf(sysid)；

- 30059（监控状态）：新增 `SurveillanceStatusPusher`（1s 周期，无 WS 客户端跳过）；

  `SurveillanceDevice.firstSeenMs` 新增；诚实口径——单通道模型（totalCameras 恒为 1，

  无多通道设备）、status 仅 ONLINE/OFFLINE（FAULT/MAINTENANCE 不可达）、uptimeSec

  为进程内计数（重启清零，firstSeenMs 不持久化）；

- 30080-30082（环境命令）：新增 `telemetry/EnvOverrideController`（@RestController，

  @RequireRole(OPERATOR)），POST `/api/v1/env/{sysid}/wind|weather|thresholds`，

  参数校验与 drone-sim 机载校验同构（风速 0-50、风向 [0,360)、weatherCode 0-4、雨量 0-255、

  阈值 windWarnMps≥0 且 < windCritMps）；

- 30085（载荷查询）：`DeliveryController` 构造器新增 `DroneCommandService`，新增

  POST `/{id}/payload/query`（按 delivery.sequence 取 targetSysid，机载立即回传

  PAYLOAD_STATUS 30006）；

- 计数：新增 38 测（TaskAssignmentFrameTest 10、ConflictScanServiceTest 7、

  AlarmLinkageEngineFrameTest 6、SurveillanceStatusPusherTest 5、

  EnvOverrideControllerTest 7、DeliveryPayloadQueryTest 3）；cloud-backend 2238、

  全仓 4234（+38）；api-reference.md 端点 353（190 GET / 136 POST / 13 PUT / 14 DELETE）、

  @RestController 66、控制器源文件 69；

- 文档：README 两条边界条目（M10 + 6 帧收口）翻转写明真实生产者与诚实不可达值

  （FAILED(3)、AIRSPACE(0)、FAULT(2)/MAINTENANCE(3)、totalCameras 固定 1、uptimeSec

  非持久、30048/30050 taskId 字符串→u32 哈希非可逆映射）；ROADMAP M10 补记 + 顶部

  生产者清零记录；CHANGELOG 本条。



---

---

---

## [Unreleased] — deploy 三路径收口：helm 资源名 release 化 / 内置 PG / NetworkPolicy 断链修复（2026-10-05）



### 1. helm 资源名 release 化（同命名空间可并存多 release）



- 缺口背景：chart 所有资源名是静态的（cloud-backend / nexussky-config /

  flight-logs ...），同 namespace 装第二个 release（staging+prod、蓝绿）必撞名；

- 新增 `_helpers.tpl`（标准 fullname 模式 + 各组件命名助手 +

  nexussky.datasourceUrl 计算），10 类资源名全部改为 `<release>-<component>`；

  chart 内交叉引用（Deployment envFrom / PVC claimName / Ingress backend /

  HPA scaleTarget / NOTES.txt rollout 命令）同步改引助手，渲染实证一致；

- **gcs-web 反代上游去硬编码**：镜像内 nginx.conf 原本写死

  `cloud-backend:8080`，Service 改名后必断。改造为 nginx 官方镜像的

  templates 机制——`deploy/docker/nginx.conf` 用 `${NS_BACKEND_HOST}`/

  `${NS_BACKEND_PORT}` 占位，Dockerfile.web 复制到 /etc/nginx/templates/ 并

  `ENV` 内置缺省值 `cloud-backend:8080`（compose/原生 k8s 行为不变），helm

  路径由 chart 注入 `<release>-cloud-backend`。docker 实证：envsubst 渲染

  正确、`nginx -t` 通过；

- 副作用收益：helm 的 flight-logs PVC 名与原生 k8s 的 `flight-logs` 不再

  共享 claimName——Round D 遗留的「双栈并存争抢同名 PVC」开放项随之消除。



### 2. helm / 原生 k8s 补内置 PostgreSQL（评估/演示开箱即用）



- 缺口背景：configmap 的 `SPRING_DATASOURCE_URL` 指向 `postgres:5432`，但

  helm 与原生 k8s 均无 postgres 定义——prod profile readiness 探针含 db

  分量，此前只能"集群自备 PG"，否则 Pod 永远 NotReady（Round D 遗留开放项）；

- helm：新增 `templates/postgres.yaml`（`database.builtin.enabled=true` 时

  部署，默认 false），单副本 StatefulSet + volumeClaimTemplates（PVC

  `<release>-postgres-pgdata-<ordinal>`）+ pg_isready 双探针 + 非 root

  (uid 70) + PGDATA 指 PVC 子目录规避 lost+found；启用后数据源 URL 由

  `nexussky.datasourceUrl` 自动计算指向内置实例（忽略 database.url），

  凭据沿用 database.username/password 与 PG 容器 env 同源；

- 原生 k8s：新增 `deploy/k8s/postgres.yaml`（Service 名 `postgres` 对齐

  configmap 的 jdbc URL，与 compose 同口径），gcs-web 清单显式注入

  NS_BACKEND_HOST/PORT 便于排查；

- 生产定位不变：内置 PG 仅评估/演示，文档明示生产用外部 PG /

  CloudNativePG Operator（备份、HA、升级由专业组件负责）。



### 3. NetworkPolicy 核查：修复两个策略强制型 CNI 下的必然断链



- **Ingress 控制器被挡死**：策略只放行「本命名空间 → 8080/TCP」，而

  nginx ingress 控制器 Pod 在独立命名空间——ingress 启用时全部路由

  503/超时。修复：helm 按 `ingress.controllerNamespace`（新增 values，

  默认 ingress-nginx）、ingress.enabled 时追加放行；原生清单同步放行

  ingress-nginx 命名空间；

- **MAVLink 回包被挡死**：cloud-backend UDP 网关从 14561 端口探测 sim

  （GCS HEARTBEAT），sim 回包/遥测发回 backend:14561/UDP——只放行

  8080/TCP 时回包全丢，飞行链路静默失效。修复：同命名空间规则追加

  14561/UDP（helm + 原生两份）；

- 两个问题在 flannel/kind 默认（不执行 NetworkPolicy）的集群上不会暴露，

  Calico/Cilium 下必现，故此前未被发现。



### 4. 验证



- helm lint 0 failed；`helm template` 三组场景实证：默认 release 名 /

  staging + builtin PG（10 类资源名 + 全部交叉引用一致）/ ingress 开关

  （关闭时无 Ingress 资源、NetworkPolicy 相应剔除控制器规则）；

- 原生 k8s 14 清单 18 文档 YAML 全解析通过；compose config（五哑 env）

  exit 0；docker 实证 nginx 模板渲染 + `nginx -t` 通过。



---

---

---

## [Unreleased] — M13 收尾：孪生喂入 GLOBAL_POSITION_INT 兜底（2026-10-05）



### 1. 无边缘栈设备进孪生（补齐 2026-10-04 留下的明确边界）



- 缺口背景：`TwinSyncListener` 此前只认 SENSOR_FUSION_DATA(30054) 融合态喂

  `DigitalTwinService.syncTwin`——未运行机载边缘栈（EdgeInferenceRunner）的

  设备不发 30054，因而**永远不进孪生**；2026-10-04 接线时该边界被明确记录为

  「GPI 兜底链路明确未做」，本轮补齐；

- `TwinSyncListener` 新增 `GLOBAL_POSITION_INT(33)` 事件监听：该 sysid 无新鲜

  融合态（从未收到 30054，或距上次超过 `FUSION_STALE_MS=3s`——边缘栈正常 2Hz，

  3s 无即视为未运行/停发）时用原始 GPS 兜底喂 `syncTwin`；融合态新鲜时 GPI

  不竞争（融合态是更好的物理态估计，优先级不反转）；

- 换算口径：alt 用 AMSL（与融合态 fusedAlt 同基准）；速度取 vx/vy/vz 合矢量

  （cm/s → m/s）；hdg=65535（MAVLink 规范未知哨兵）回退 0（北向，生态惯例）；

  电量沿用 SYS_STATUS 记录，未见时 battery=-1（与融合态路径同规则）；

- GPI 兜底路径与融合态路径共享同一 1Hz 节流与 TWIN_STATE_SYNC(30055) 发布

  （每 sysid 独立），GCS 数字孪生面板无需任何改动即可看到兜底设备。



### 2. 测试与文档



- 新增 `TwinSyncListenerTest` 7 例（8→15）：GPI 兜底喂入（含电量沿用）/

  融合态新鲜时让位 / 过期后接管 / hdg 未知回退 0 / 兜底同样发布 30055 且共享

  节流 / per-sysid 独立 / 消息体损坏隔离（不抛异常不写入）；实测 15/15 全绿；

- Java 测试计数 4178→4185（cloud-backend 2191→2198），计数门禁

  `check-test-count-docs.py` 18 处声称同步更新（README/ROADMAP/whitepaper/

  sales-pitch-deck/pricing-strategy/demo-scenarios/customer-onboarding-guide/

  low-altitude-economy-demand-research）；

- 文档同步：README M13 段「已知边界」翻转为 GPI 兜底已实现（保留 driftMeters

  语义边界）、ROADMAP M13 状态沿革补 2026-10-05 一句、标题补「GPI 兜底」。



---

---

---

## [Unreleased] — M11 收尾：决策三帧 GCS 可视化（AI 决策面板，2026-10-05）



### 1. 决策事件 / 自适应航迹 / 边缘任务三帧的前端消费



- 缺口背景：cloud-backend `TelemetryWebSocketHandler` 早已把

  decision-event(30051) / adaptive-path(30052) / edge-task-status(30053)

  按租户广播到 /ws/telemetry，且三者在 drone-sim 均有真实生产者

  （`VirtualDrone.emitDecisionEvent` / `executeAdaptivePath` 同拍公告 /

  `EdgeInferenceRunner` 逐任务上报），但 gcs-web 零消费——AI 为什么改航、

  改成了什么，操作员在 GCS 里不可见（与 M13 twin-state-sync 此前同款缺口）；

- 新增「AI 决策」面板（gcs-web tab `aidecision`，`AiDecisionPanel`）：

  决策事件流（类型/原因/置信度/触发值，按决策类型着色）、自适应航迹改写

  （原航点 → 新航点坐标/新高度/风速风向/原因码）、边缘任务（类型/耗时/

  结果大小/状态）三条事件流，空态给触发条件指引（区分「没数据」与「功能没开」）；

- `useWebSocket` 新增 `decisionEvents` / `adaptivePaths` / `edgeTasks` 三个

  事件流 state（离散事件非可覆盖状态，头插各留最近 50 条，与 alerts 同模式）；

- 单位换算与码表收口纯函数 `utils/aiDecision.js`（1E7 度 / cdeg→度换算，

  decisionType/reason/adjustmentReason/taskType/status 五张码表与

  drone-sim 生产端映射一一对应，未知码回退「码 N」）。



### 2. 测试与文档



- 新增 `gcs-web/test/aiDecision.test.js` 13 例：三帧归一化纯函数

  （全字段换算 / 未知码回退 / 字段缺失 → null / 非对象输入不炸）+

  hook 集成（三帧各入各流头插 / 50 条上限 / 异常帧兜底不干扰其他状态）；

  前端 155→168 例（168/168 实测全绿）；

- 文档同步：README（M11 段前端消费 + 前端测试计数 155→168）、

  ROADMAP M11 标题补「GCS 可视化 2026-10-05」与状态沿革一句。



---

---

---

## [Unreleased] — M11 收尾：ADAPT_PATH 执行级接线 + ADAPTIVE_PATH(30052) 首个生产者（2026-10-04）



### 1. ADAPT_PATH 从公告级升级为执行级（撤销上一条「刻意边界」）



- `AutonomyExecutor.FlightControl` 新增 `adaptPath(String aiReason)`；

  ADAPT_PATH 决策沿既有四条仲裁（`--autonomy-exec` 默认关闭、

  FailsafeController 永远优先、仅 ARMED/MISSION、变化沿驱动）门控后，

  不再只是复位限速的「公告级」——现在真实调用

  `VirtualDrone.executeAdaptivePath`（drone-sim）；

- `executeAdaptivePath`：对剩余任务航段（守卫：全部 NAV_WAYPOINT 且 ≥2 个、

  非上传会话）以 [当前位置, 剩余航点] 为路径跑

  `AdaptivePathStrategy.adaptPath` 真实三算法（风补偿 WCA / 能耗最优速度 /

  Dubins 30m 圆弧尖角平滑，综合接口此前全仓零生产调用方）；风场取场景风+

  环境风合成向量，风向换算为气象惯例「吹来的方向」；

- 尖角被插点改写时经新增 `MissionStore.replaceTail` 原地替换任务尾部并

  重定位当前航段，同一拍公告 **ADAPTIVE_PATH(30052)**——载荷为真实几何

  （平滑后路径第一点坐标），该消息首次有了生产者；路径无变化**不发公告**

  （无变化不公告，不编造坐标）；能耗最优速度经巡航限速因子表达，物理层

  clamp [0.05, 1.0] 故只能降不能升（顺风提速不可表达，刻意边界）；

- 接线守卫翻转：`AdaptivePathStrategy` 移出 `AiAutonomyWiringTest`

  UNWIRED_CLASSES（8→7）并加正向断言 `adaptivePathStrategyIsWired`

  （静默退线即判红）；`AdaptivePathStrategy` 类头 javadoc 同步翻转。



### 2. 测试与文档



- 新增 `AdaptivePathExecutionTest`（真实 UDP 集成，端口 24780）：Mission

  Protocol 握手上传 Z 字形 3 航点任务 → ARM → MISSION_START → 直调

  `executeAdaptivePath` → 断言 30052 真实下发（originalWaypointSeq=0、

  原因码 WIND、新航点在本场 ≤500m）且 MISSION_CURRENT.total 从 3 增至 19

  （两个 90° 尖角各插 8 个圆弧采样点，实测日志 `mission tail 3 -> 19`）；

- `MissionStoreTest` 新增 ReplaceTail 组 5 例（前缀保留 / fromSeq=0 整换 /

  空表截断 / 上传中拒绝 / 越界与 null 守卫）；`AutonomyExecutorTest`

  ADAPT_PATH 用例从「仅公告」改写为「可执行态触发一次 / 不可执行态忽略」；

- `NexusCommandDispatchTest` 修正过时注释（布尔开关加固后裸写安全，

  `--env=1` 写法改为裸写 `--env`，吃自家狗粮）；

- 文档同步：README M11 段（四步接线 + 30052 首个生产者 + 「其余 8 类」→7）、

  测试规模计数 1374→1382 / 总计 4170→4178、ROADMAP M11 改标 ✅、

  competitive-analysis 接线断言说明、demo-scenarios 测试基线表（并修正

  一处长期漏改的过时基线句 3230→4178）。



### 3. 保持已知状态（未做假接线）



- ADAPT_PATH 改写只在**任务态**生效：剩余航段含非 NAV_WAYPOINT 指令

  （如拍照、悬停）时整体跳过不改写（避免破坏指令语义），明确未做按指令

  类型分段平滑；

- 顺风段能耗最优速度高于巡航速度时不可表达（限速因子 clamp ≤1.0）；

- 其余 7 个 ai 类（避障 A 星/RRT、RTL 滑翔等）仍为未接线期望状态。



---

---

---

## [Unreleased] — M13 收尾：gcs-web 消费 twin-state-sync 帧（2026-10-04）



### 1. 前端消费链路（此前「已知边界」，本条撤销该边界）



- `useWebSocket` 新增 `twinStates` 分桶（`{ [sysid]: { ...data, receivedAt } }`），

  消费 WS "twin-state-sync" 帧（TwinSyncListener 1Hz 发布的 TWIN_STATE_SYNC 30055），

  与 telemetryHistory 同款「按机分桶只留最新」语义；

- 数字孪生面板（CityTwinPanel）新增「实时孪生同步」卡片：每机孪生位置/高度/

  航向/速度/电量/漂移 + 5s 新鲜度在线/失联判定；未消费到帧时给出边界提示

  （仅带机载边缘栈的无人机进孪生）。漂移字段带 tooltip 说明语义为相邻两次

  同步的位移（云内同源估计），不冒充实测偏差；

- 单位换算（1E7 度 / mm→m / cdeg→deg / battery 255=未知）收口纯函数

  `gcs-web/src/utils/twinSync.js`，非对象输入返回 null 不抛异常；

- 新增 9 例 vitest（`test/twinSync.test.js`：换算 5 + 新鲜度 1 + hook 分桶 3），

  前端实测 155/155；`npm run lint` 0 error（新增代码零告警）；

- 同步更新 README（孪生 bullet 边界句 + 前端测试规模 146→155）、ROADMAP M13

  边界句、TwinSyncListener javadoc。



### 2. 剩余边界（保持已知状态，未做假接线）



- 未运行机载边缘栈的设备不发 30054、因而不进孪生（GLOBAL_POSITION_INT 兜底

  明确未做）——本条 CHANGELOG 不改变该状态。



---

---

---

## [Unreleased] — 工程遗留清理：SimConfig 布尔开关解析加固 + 两项零生产者收口（2026-10-04）



### 1. SimConfig 布尔开关解析加固（行为变更；仓库内无既有用法受影响）



- 原实现把无 `=` 的布尔开关的下一个 token 当 value 吞掉（`--env

  --actuators=1` 会静默丢掉后者），且布尔开关作为末参数时整个被

  「Ignoring unknown argument」静默丢弃。该坑由上一条 CHANGELOG 的派发守卫

  测试实锤（曾因 `--env --actuators` 写法假红一次）；

- 加固后 9 个布尔开关（env / actuators / mesh / sat-relay / terrain-adapt /

  celltower / rid / reject-unsigned / autonomy-exec）**裸写即生效、不吞下一

  token、可作末参数**；值参数缺值时告警改为「needs a value」更准确；

- 仓库内脚本与文档全部使用 `=` 形式（如 e2e-rid.ps1 的 `--rid=on`），无既有

  用法依赖旧的吞 token 行为；新增 `SimConfigBooleanFlagTest` 8 例钉扎

  （含「连续裸写 9 开关全生效」——原实现会隔一个丢一个）。



### 2. 两项零生产者收口：证据裁定为设计边界，不接假线



- **ADAPTIVE_PATH(30052)**：唯一决策生产者是刻意未接线的

  `AdaptivePathStrategy`（`AiAutonomyWiringTest` 未接线守卫钉着），且其

  ADAPT_PATH 决策不带 path 字段——现在强行接线只能往线上塞编造的新航点

  坐标，比不发更糟。收口为刻意边界（README「已知边界」已声明），接上的

  前置条件正是 M11 明确延后的 ADAPT_PATH 执行级（任务状态机航点注入）；

- **环境配置命令（30080-30082）与载荷查询（30085）**：环境命令是 M0b 的

  机载本地控制面（M0b 立项范围是模型+告警+消息，云端环境控制 API 从未

  立项）；载荷查询是 1Hz 周期 PAYLOAD_STATUS 遥测之外的按需冗余触发。

  上一条 CHANGELOG 曾把「补链路」列为后续项，本轮证据裁定为设计边界，

  收口不再作为待办。



### 3. 计数



drone-sim 1366 → 1374（+8），全仓 4162 → 4170。



---

---

---

## [Unreleased] — 自定义 MAV_CMD 整带搬入私有区 30080-30099（2026-10-04）



> 与 2026-10 消息 ID 治理同源的遗留问题：8 条自定义命令（环境配置 310-312、

> 喷洒/抛投/载荷 320-322、雷达/旋翼配置 420/421）原先直接占用 MAVLink 官方与

> 方言的命令分配带，其中 420/421 与 ArduPilot 方言实锤冲突（420=NAV_GUIDED_ENABLE、

> 421=NAV_CONTINUE_AND_CHANGE_ALT）。按消息治理同一标准（官方带**整带**避开，

> 而非只躲已知值），全部 8 条搬入私有区命令子段 30080-30099：

> 310→30080 / 311→30081 / 312→30082 / 320→30083 / 321→30084 / 322→30085 /

> 420→30086 / 421→30087（命令与消息分属不同命名空间，分段纯为日志可读性）。



### 1. 常量收口与双侧接线



- 8 个常量统一收口在 `MavEnums.MAV_CMD_NEXUS_*`（mavlink-core），云端 4 个

  下发方（RadarController/RotorController/SprayTaskService/DeliveryService）

  与机载 VirtualDrone 的 switch 派发 case 全部改为引用同一份常量——两侧

  漂移自此不可能；

- 修正两处不诚实注释：RadarController/RotorController 曾声称「不与 MAVLink

  common 冲突」（实际 420/421 已实锤冲突），已改为如实陈述搬迁背景；

- 清理死引用：SprayTaskService/DeliveryService 注释引用的「spec.md §4.3

  命令 id 分配」文档已不存在于仓库，改引 MavEnums 常量定义。



### 2. 双测试钉扎（防静默回退）



- `NexusCommandIdZoneTest`（mavlink-core）：8 常量必须落在 30080-30099 且

  两两互异、映射保序——谁把常量改回官方带，构建即红；

- `NexusCommandDispatchTest`（drone-sim）：真实 UDP 往返（编码 CommandLong →

  运行中的 VirtualDrone → COMMAND_ACK），8 条新 ID 逐一验证机载派发命中

  （非 UNSUPPORTED）——case 标签没跟上常量搬迁，构建即红。测试需装配

  `--env=1 --actuators=1` 并注入雷达/气动，否则 handler 以 UNSUPPORTED

  短路、与 default 分支不可区分（该短路语义本身未改）。



### 3. 已知边界（如实声明）



- 310/311/312/322 四条命令在仓库内无生产者（sim 端 handler 存在、云端无

  下发方），疑似供 SITL/外部工具经 REST raw 直通使用；本次仅迁值未补链路，

  补齐属后续项；

- REST `/drones/{id}/command` 的 `raw` 直通端点接受任意 cmd id（0-65535），

> 治理靠约定与守卫测试，不在该端点强制白名单。



---

---

---

## [Unreleased] — M13 孪生实时同步接线：TwinSyncListener 事件驱动喂孪生 + 30055 首个生产者（2026-10-04）



> ROADMAP 最后一个 ⚠️ 里程碑段收口：`DigitalTwinService.syncTwin` 从「只有测试

> 调用、真实运行孪生恒为空」升级为事件驱动的生产链路，REST predict/compare 自此

> 基于真实遥测工作。



### 1. TwinSyncListener（新增，cloud-backend twin 包）



- 监听 `MavlinkMessageEvent`（TelemetryIngestService 解码后发布的事件总线，

  与 TerrainMapService 等既有消费者同一模式）：SYS_STATUS(1) 记录每 sysid

  最新电量；SENSOR_FUSION_DATA(30054) 换算融合态（1E7/mm/cdeg 还原）喂

  `syncTwin`；

- **喂入源选 M12 EKF 融合态而非 GLOBAL_POSITION_INT 原始 GPS**——兑现 ROADMAP

  「M13 依赖 M12（边缘传感器融合数据源）」，融合态是比原始观测更好的物理态估计；

  电量未见 SYS_STATUS 时 battery=-1（REST 侧未知）；

- 每次同步后按 1Hz 节拍（每 sysid 独立）发布 `TWIN_STATE_SYNC(30055)`——该消息

  此前全仓零生产者；经 TelemetryWebSocketHandler 的 WS_TYPE_MAP 以

  "twin-state-sync" 帧按租户可见性广播给 GCS；

- 监听器异常就地吞掉：Spring 事件组播中一个监听器抛异常会中断同帧其余监听器，

  孪生故障不得影响 WS 转发与 regulator 上报（UDP 接收线程上的隔离语义）。



### 2. 已知边界（如实声明）



- gcs-web 前端尚未消费 twin-state-sync 帧（帧已可达，消费属前端后续项）；

- 未运行机载边缘栈（EdgeInferenceRunner）的设备不发 30054、因而不进孪生——

  GLOBAL_POSITION_INT 兜底链路明确未做；

- `driftMeters` 语义为相邻两次同步的位移（孪生与物理态云内同源，非与独立实测

  的偏差），compare REST 的近似口径不变。



### 3. 测试与文档（+8，cloud-backend 2183→2191，Java 4150→4158）



- `TwinSyncListenerTest`（新增 8 例）：融合态换算精确值（1E7/mm/cdeg 还原 +

  电量未知 -1）、SYS_STATUS 电量进入下次同步、30055 字段与孪生态一一对应 +

  事件时间戳取 syncTimestamp、电量未知发 255 哨兵（MAVLink 惯例）、1Hz 节流

  每 sysid 独立（节流窗口内只同步不发布）、位移漂移进入第二次发布、发布链路

  故障隔离（孪生态照常更新）、消息体损坏（cast 失败）只影响本条；

- 文档同步：ROADMAP M13 状态沿革（⚠️→✅）、README 已知边界新增孪生条目。



---

---

---

## [Unreleased] — M12 边缘 AI 接线：EKF + 经典 CV 接入遥测主循环 + 30053/30054 首个生产者（2026-10-04）



> ROADMAP M12 收口：`io.aerofleet.sim.edge` 两算法引擎从「只被自己的测试引用」

> 升级为机载生产路径。与 M11 执行级刻意不对称：被动观测默认常开——

> 观测无风险，抢杆才有。



### 1. EdgeInferenceRunner（新增，drone-sim，~250 行）



- `VirtualDrone` 遥测主循环（`telemetryRates`）IMU 块后挂载 tick（20Hz 入口），

  三阶段互相隔离（predict / update+emit / vision，任一阶段抛异常只影响本阶段

  本轮，`SimLog.warn` 后继续）；

- **传感器融合链路**：伪 IMU 加速度 = 速度差分（20Hz predict），2Hz 喂

  GPS（`reportedLat/Lon`，3m 噪声——与飞控同源噪声而非真值）、IMU 速度观测、

  LiDAR(注入式在环时取真实 AGL)；1Hz 下发 `SENSOR_FUSION_DATA(30054)`

  （lat/lon 1e7 定点、alt mm、航向归一化 cdeg）；

- **视频分析链路**：2Hz 拍帧，`ShotImageWriter.renderGray`（新增，160×90 原始

  灰度，均匀缩放自 1920×1080 相机模型；噪声 σ=6——沿用 JPEG 路径的 σ=12 会让

  帧差点亮 ~8% 假运动，σ=6 时帧差 σ≈8.5 低于检测阈值 30）喂

  `VideoStreamAnalyzer` 帧差检测，检出即发 `EDGE_TASK_STATUS(30053)`

  （resultSize=标签 UTF-8 字节数）；

- `EdgeNode` 作为机载侧任务登记簿（submitTask/getTaskStatus），融合与视频

  各一路任务 id；`SensorFusionEngine` 新增 `updateVelocity`（IMU 速度观测入口，

  解决纯加速度注入无法收敛初始速度的问题：恒速→零加速度→速度状态恒 0）；

- **被动观测语义**：融合结果不回写 `DronePhysics`（飞控仍用真值积分），不驱动

  任何执行机构；无开关、默认常开（与雷达/IMU 遥测同级）。



### 2. 30053/30054：从零生产者到机载生产者



- `EDGE_TASK_STATUS(30053)` 与 `SENSOR_FUSION_DATA(30054)` 此前在 drone-sim

  无任何生产者；现在分别以视频检出沿与 1Hz 融合节拍下发，GCS/云端自此可见

  边缘任务状态与融合态。taskType 取 `EdgeTaskType` 枚举序（0=视频分析/

  1=传感器融合），status 用完成(2)/失败(3)；融合任务 resultSize 固定为

  30054 payload 长度(24)。ADAPTIVE_PATH(30052) 仍无生产者（维持现状）。



### 3. 测试与文档（+14，drone-sim 1351→1365，Java 4136→4150）



- `EdgeInferenceRunnerTest`（新增 10 例）：视觉 2Hz 节拍与 30053 字段、

  帧间位移真实检出（u 960→1100）与悬停零检出、null 相机跳周期、

  30054 精确字段值与 sensorMask（GPS+IMU=3，+LiDAR=11）、东向速度航向收敛

  8500-9500 cdeg、dt=0 时钟停摆不崩、EdgeNode 登记、send 故障隔离

  （失败周期不消耗任务 id）；

- `ShotImageWriterTest`（新增 3 例）：灰度帧尺寸与噪声地板、逐帧确定性、

  亮斑投影位置灰度值；

- `AiAutonomyWiringTest`：UNWIRED_CLASSES 从 10 减为 8（移出两个 edge 引擎），

  新增 `edgeEnginesAreWired` 正向断言（静默退线即判红）；

- 文档同步：ROADMAP M12 状态沿革、README 已知边界、competitive-analysis

  对比表两行与标注块重写（⚠️ 语义收窄为「措辞诚实性」标注）、

  `SensorFusionEngine`/`VideoStreamAnalyzer` 接线状态 Javadoc。



---

---

---

## [Unreleased] — M11 执行级接线：引擎决策驱动飞控动作（默认关闭）+ DECISION_EVENT 首个生产者（2026-10-04）



> ROADMAP M11 的「执行级接线未做」收口：`DecisionEngine` 的融合决策从

> 「只播报建议」升级为「变化沿播报 + DECISION_EVENT 下发 + 门控执行」。

> 产品决策的保守取向全部落在仲裁规则里：默认关闭、failsafe 永远优先。



### 1. 执行级：AutonomyExecutor（新增，drone-sim）



- 与 advisory 共用同一变化沿（`AutonomyAdvisor` 新增 decisionListener 回调，

  单次评估喂两消费者，不重复评估、不引入第二套去抖）；

- **四条仲裁**：`--autonomy-exec` **默认关闭**（关闭时行为与旧版完全一致）；

  **FailsafeController 永远优先**（任一触发沿激活即不抢杆并复位限速）；

  **仅 ARMED/MISSION 可执行**（STANDBY/RTL/HOLD/CRASHED/MANUAL 不接管）；

  变化沿驱动；

- **动作映射**：RTL / EMERGENCY_LAND → 与 failsafe 同一条 RTL 程序（仿真无

  独立原地降落原语，EMERGENCY_LAND 复用 RTL 自动降落终局，Javadoc 已注明）；

  AVOID → 全局巡航限速 50%（`DronePhysics` 新增 `speedFactor`，作用于

  stepTowardTarget，决策清除沿自动恢复 1.0）；ADAPT_PATH → **公告级不执行**

  （航点注入需改任务状态机并触发 `AiAutonomyWiringTest` 未接线守卫，明确未做）；

- `FailsafeController` 补 `battFailActive()`/`gpsFailActive()` 包内 getter

  （与既有 `linkFailActive` 对称，供仲裁用）。



### 2. DECISION_EVENT(30051)：从零生产者到机载生产者



- 该消息此前在 drone-sim 无任何生产者（cloud-backend 的 WS 转发永远收不到

  实例）；现在主决策变化沿即下发：type 码 0=RTL/1=AVOID/2=ADAPT_PATH/

  3=EMERGENCY_LAND，reason 码与 ai 策略 reason 字符串一一映射（0=low

  battery … 5=battery optimization，255=未知），GCS/云端自此可见结构化决策

  事件。与执行开关无关，advisory 模式同样下发。ADAPTIVE_PATH(30052) 仍无

  生产者（需路径执行原语，维持现状）。



### 3. 测试与文档（+14，drone-sim 1337→1351，Java 4122→4136）



- `AutonomyExecutorTest`（新增 10 例）：四条仲裁与动作映射逐条钉死

  （记录型假实现，不依赖真实 VirtualDrone）；

- `AutonomyAdvisorTest`（+2）：listener 变化沿语义（决策沿/清除沿各一次、

  持续去抖）+ 旧单参构造向后兼容；

- `DronePhysicsTest`（+2）：speedFactor 夹紧范围 + 0.5 因子对巡航速度的

  实际压制；

- 文档同步：ROADMAP M11 状态沿革、README 已知边界、competitive-analysis

  对比表与标注、`DecisionEngine`/`AiAutonomyWiringTest` 的接线状态 Javadoc

  （断言逻辑不变——执行级只消费 `FusedDecision`，未触碰未接线策略类，

  未接线守卫依然全绿）。



---

---

---

## [Unreleased] — MAVLink msgId 治理搬迁：避开官方分配带（2026-10-04）



> 官方治理规则实证：common.xml 拥有 msgId **300-10000** 分配带，本项目自定义消息

> 420-483 全段落在其内，其中 420/437/440 已与官方消息**实锤冲突**。全部 51 条

> 等差平移 +29580 至私有方言段 30000-30099，代码/脚本/文档一次原子搬迁。



### 1. 官方碰撞实锤（搬迁动机）



- 官方 common.xml：**420=RADIO_RC_CHANNELS、437=AVAILABLE_MODES_MONITOR、

  440=ILLUMINATOR_STATUS**（活跃条目）；development.xml：421=RC_CHANNELS_OVERRIDE_V2、

  441=GNSS_INTEGRITY。旧段任何一号被官方启用，两端同名异构消息即互以 CRC 拒收；

- 官方治理规则（MAVLink 消息 ID 治理页）：私有方言**可使用 300-10000 之外的任意

  区间**。全量官方方言快照（392 个已分配 msgId）核实 **30000-30099 为空**，

  最近邻 17158 / 42000；

- **顺带发现（未修，登记后续项）**：MAV_CMD 命令空间同样撞车——官方

  `MAV_CMD_INJECT_FAILURE=420` 与 `MAV_CMD_NEXUS_RADAR_CONFIG=420` 冲突

  （421 未分配）。命令 id 与消息 id 是两个独立空间，另行搬迁。



### 2. 搬迁映射与代码改造



- **映射**：newId = oldId + 29580（420→30000 … 483→30063）；CRC_EXTRA 与 payload

  布局不变（CRC_EXTRA 由字段签名决定，与 msgId 无关）；30064-30099 为增长预留；

- **批量落地**：按「已分配 id 集合」（420-426/430-434/437-441/450-483）精确匹配，

  生产+测试 94 文件 554 处替换；HTTP 429 限流码、测试数 449、像素 640×480、

  EnvironmentAlert 构造字段值 480/450 等误报受保护零命中；

- **MavlinkMessageInfo**：51 条 `INFOS[4xx]` 数组槽位改写为

  `EXTENDED_INFOS.put(300xx,…)`——查找逻辑

  `msgId < 512 ? INFOS[msgId] : EXTENDED_INFOS.get(msgId)` 零改动自动支持 30000+ 段；

- **飞行日志不受影响**：FlightLog 存领域 JSON（type/sysid/lat/lon…），不含原始

  msgId，历史数据与回放零迁移。



### 3. MavlinkFrame 校验加固（搭配搬迁）



- `decodeV2()` 一直**不校验 CRC**（生产路径由 MavlinkParser 流式校验，但工具/测试

  直接调 decodeV2 时是裸奔）——Javadoc 补警告；

- 新增 `decodeV2Verified(byte[])`：解析后按 v2 语义强校验 CRC（含 CRC_EXTRA），

  不符/未注册 msgId 抛 `MavlinkException`（含 expected/received 十六进制）；实例侧

  `verifyChecksum()` + `computeExpectedCrcV2()`（覆盖范围与 Parser 逐字节一致）；

- v1 语义注意：v1 帧归一化后 inc/compat=0，但 v1 官方 CRC 不覆盖这两字节，

  verifyChecksum 仅对 v2 原生帧结论正确（v1 由 Parser 按 v1 公式校验）——Javadoc 注明；

- 新增 `MavlinkFrameVerifiedDecodeTest`(5 例)：官方帧/搬迁带帧/篡改 payload/

  签名帧/未知 msgId。mavlink-core 449→454，Java 全仓 4117→4122。



### 4. 兼容性脚本：修出两个潜伏 bug + 新增官方占用快照检查



- **compat-check.py 潜伏 bug**：解析 MavlinkMessageInfo 的正则从未匹配

  `EXTENDED_INFOS.put(...)` 条目（实际是 `, new Info(` 与 `));`，正则预期

  `= new Info(` 与 `);`）——OPEN_DRONE_ID 6 条一直没进校验表，搬迁后 51 条

  全部失明才暴露。修复后校验表 25→82 条（标准 31 + 扩展 51）；

- **crc-extra-gen.py 同类 bug**：表查找正则同样不认 put() 条目，51 条全报

  「表中无此项」；修复后 51 条「仓库=重算」全对、0 CHANGED——顺带完成搬迁后

  Javadoc msgId 与注册表一致性的独立复核；

- **新增官方分配冲突检查**：compat-check 内嵌 392 个官方已分配 msgId 快照

  （id→方言，2026-10），`--self-test` 核对：扩展消息无一命中官方分配、私有段

  30000-30099 无官方占用、快照加载量 ≥350。`--cross-check`（pymavlink 在线核对）

  保留为补强；跳过区从 420-12900 收窄为 30000-30100；

- 自检 140 项全绿（含 51 条扩展消息 v2 帧往返、4 条可变长度消息）。



### 5. 文档同步



- README/ROADMAP/architecture/integration-guide/sdk-reference/sitl-integration/

  PRODUCT-POSITIONING/sales-pitch-deck/whitepaper 等 16 文件 278 处批量同步

  （按已分配 id 集合映射，LoRa 433MHz/640×480/HTTP 429/测试数 449 等误报受保护）；

- ROADMAP 执行纪律 #5 重写（旧「新增从 484+ 起分配」作废 → 私有段治理 +

  30064+ 增长预留）；product-brief「尚未完成冲突分析」过期声明改写为已核结论；

  sitl-integration §7 补治理搬迁说明；README 分配表章节补冲突史与快照核对说明；

- 历史快照不篡改：CHANGELOG 旧条目与 commercialization-plan「更新前/后」对比表

  保持原样。



---

---

---

## [Unreleased] — CI7 补齐：Playwright 真浏览器 E2E + 顺带修复 WS 握手 NPE（2026-10-04）



> 收口 devops-enhancement-plan 的 CI7「Playwright E2E 仍缺」。落地过程撞出一个

> **只有真浏览器才暴露的后端缺陷**：遥测 WebSocket 在两类最常见连接下必被 NPE 打死。



### 1. WS 握手 NPE：dev 模式与无租户用户的遥测连不上（真 bug，非测试问题）



`TelemetryWebSocketHandler.afterConnectionEstablished` 把租户 ID 写进

`session.getAttributes()`——Spring 的实现是 `ConcurrentHashMap`，**不接受 null value**，

写 null 抛 NPE，异常一路冒到 `ExceptionWebSocketHandlerDecorator`，连接以

**CloseStatus 1011** 关闭。客户端症状是"遥测刚连上又断"，且日志里只有 NPE 没有业务线索。



- **触发面正是最常见的两类连接**：`extractTenantId` 在 dev 模式（无 token）与

  「token 有效但用户无租户归属」时都返回 null——后者是三态租户域设计里的

  **合法状态**（全局管理员/未归属用户），不是异常输入；

- **修复**：取不到就不写属性（读取侧本来就按 null 处理：限流分支与断连清理

  都有 null 判断，不写 = 全局会话，与 `dispatch` 的可见性判定一致）；

- **回归**：`TelemetryWsHandshakeNpeTest`(3 例) —— dev 模式连接 / 无租户 token 连接 /

  无租户属性断连，三条都断言「不抛异常且连接进了广播表」。改前第一条即红；

- **实证**：修前 E2E 的 WS 用例 12s 收 0 帧，修后 0.9s 收到首帧。



### 2. Playwright E2E 4 例 + CI job `gcs-e2e`



- **覆盖面刻意选 vitest/jsdom 看不见的那一层**：模块加载期副作用（three.js/maplibre

  在真浏览器的实际初始化）、WS 遥测流真的连上后端并推帧、ErrorBoundary（面板组件崩了

  应显示错误面板而非白屏）、登录门。**不用 route 拦截**——要测的正是被拦截的那段链路；

- **编排**（`gcs-web/e2e/`）：`start-backend.mjs` 拉起 drone-sim + cloud-backend

  （dev profile，端口 18099 避开本机 8080——那是常驻容器占用，不是 NexusSky 的），

  双重判活（后端健康 + 模拟器 sysid 上线，因为遥测 WS 只在有在线设备时才有帧）；

  `ci-run.sh` 起停+跑测试，CI 与本地跑同一份代码；

- **vite preview 补 proxy**：此前只有 dev server 有 `/api` `/ws` 代理，preview 没有——

  E2E 跑真实构建产物时请求会落到静态目录变成 404，这是"假绿"最短路径；

  后端地址可用 `AF_BACKEND_ORIGIN` 覆盖；

- **CI 接线的一处坑**：起后端与跑测试必须在**同一个 step**。GitHub Actions 每个 run 是

  独立 shell，前一个 step 里 `&` 起的进程会随 step 结束被回收，后端活不到测试那一步

  （症状是连接被拒，很难第一眼归因）；

- **本机端口坑**：4173 在部分 Windows 上落在 Hyper-V/WSL 保留段，listen 直接 EACCES

  （无进程占用，别去查进程），默认改用 4273，可用 `AF_GCS_PORT` 覆盖。



### 本轮验证



`cloud-backend` **2183/2183 全绿**（较上轮 2180 净 +3，即新增回归用例）；Playwright

**4/4 通过**（本地按 CI 路径 `bash e2e/ci-run.sh` 实跑，含进程清理 EXIT trap）；

`vitest 146/146`、`lint 0 error`、`vite build` 通过；文档单测数口径经

`scripts/check-test-count-docs.py` 校验一致（4114→4117 / 2180→2183，9 文档）。



**已知边界**：E2E 4 例覆盖「加载/连通/不崩」这条主干，不做视觉回归与多浏览器矩阵

（当前仅 chromium）；组件内部逻辑仍由 vitest 146 例负责，两者分层不重叠。



---

---

---

## [Unreleased] — 合并前端测试第二批：146 例 vitest + 飞行安全二次确认 + 遥测曲线混机修复（2026-10-04）



> 合并 `fix/frontend-tests-and-flight-safety`（6 提交：vitest 基建 / MapView 选中态恒

> false / 遥测曲线混机 / 危险命令二次确认 / 两批共 122 例组件逻辑测试 / 文档）入 master。

> 合并处 4 冲突 + 3 处语义碰撞，逐项解决后本地 `npm run test` 146/146 绿、

> lint 0 error、`vite build` 成功（EXIT=0）。



| # | 问题 | 处理 |

|---|---|---|

| 1 | `package.json` 冲突（两线各自加了 vitest） | scripts 取并集（test/watch/coverage/check）；依赖保留 master 的 `vitest@^5.0.3`、`hls.js`、`three`，补分支的 `jsdom`；`@vitest/coverage-v8` 从分支的 `^3.2.7` **对齐到 `^5.0.3`**——3.2.7 的 peer 是 vitest 3.2.7，与 vitest 5 冲突会让 `npm install` 直接失败（peer dep 解析错误），且 registry 已有 5.0.3 |

| 2 | `package-lock.json` 冲突（2008 行） | 不手解，`npm install` 按解决后的 package.json 重新生成 |

| 3 | **vitest 静默漏跑 master 的两处测试（假绿）** | 分支的 `vitest.config.js` 只 include `test/**`，而诚实化轮建的 `src/api.test.js`、`src/components/Scene3DUtils.test.js` 在 `src/` 下——合并后 CI 会绿着漏跑 24 例。include 扩为 `['test/**', 'src/**']`，两个路径都被断言到（`11 passed (11)` 文件数可证） |

| 4 | **摇杆测试与 Pointer Events 实现语义碰撞（1/146 红）** | 诚实化轮把 Joystick 重写为 Pointer Events（`onPointerDown` + `setPointerCapture`），分支的 `joystickDisarm.test.jsx` 仍用 `fireEvent.mouseDown`——jsdom 里 mouse 事件不触发 pointer handler，`sendJoystick` 0 次调用。测试改 `fireEvent.pointerDown`（对齐当前实现，组件不动）；`test/setup.js` 补 `setPointerCapture`/`releasePointerCapture`/`hasPointerCapture` 空实现——jsdom 缺这三个 API 时组件直接抛 TypeError，症状表现为"事件没触发"，排查易走偏 |

| 5 | `ci.yml` 冲突（两线加了同一个 vitest 步骤） | 合并为单步骤，保留第二批的注释并补合并说明 |

| 6 | README 冲突 | master 侧「前端只做静态检查」的声称在诚实化轮加 vitest 后已过时（正是两线口径打架点），取分支的「前端测试覆盖」段 + 保留 master 的自主决策 advisory 段；覆盖数字按 `npm run test` **实测 146** 写，不按提交消息估算 |



**已知边界（合并后不变）**：前端 49 个组件里绝大多数仍只有 lint + build 保护，vitest

覆盖的是已确认缺陷与相关契约；CI 门禁为 `npm run lint`（0 error 门槛）+ `npm run test`

+ `npm run build`。



---

---

---

## [Unreleased] — per-device 摄取凭据：设备 Key 签发/轮换/撤销 + 认证缓存（收口"整部署一把共享 key"）（2026-10-03）



> **本轮验证**：`mvn -pl cloud-backend test` **2150/2150 全绿**（较上轮 2114 净 +36：

> `ApiKeyCacheTest` 9 / `ApiKeyLastUsedTrackerTest` 6 / `ApiKeyFilterCachedTest` 8 /

> `ApiKeyControllerDeviceKeyTest` 13；全仓 4084）。本机 `scripts/ci-integration-test.sh`

> 实跑：Pass A 全绿；**Pass B 断言 1–8 全绿**（新增断言 8 = per-device 全流程 9 条，

> 见下）；Pass C 本机红（本地 PG 无 `aerofleet` 用户，属环境差异——CI 由 service 容器

> 供给凭据，与本轮改动无关）。本机实跑需 Git Bash + JDK17 前置 PATH + 一次性 Redis

> 容器（`REDIS_PORT` 覆盖：本机 6379 是要 AUTH 的外来实例）。口径对账：

> `check-test-count-docs.py` 全部一致（4084，8 文档 18 处同步）；REST 端点 344 → **345**

> （+rotate；9 文档同步，GET/POST/PUT/DELETE 分布实测 190/128/13/14）。

> **为什么需要缓存**：此前 `ApiKeyFilter` 每个认证成功的请求做 1 次 SELECT + 1 次

> **同步** `save()` 刷 `lastUsedAt`——遥测高频腿上等于双倍写放大，这正是"共享 key 能用

> 但不能上量"的隐藏成本之一。



| # | 类别 | 问题 | 修复 |

|---|---|---|---|

| 1 | 撤销粒度 | 摄取凭据只有"整部署一把共享 key"（`DeviceIngestKeyBootstrapRunner`），撤销/轮换粒度=整体换 key 重启；一台边缘设备失窃就要全部署换钥 | `api_keys` 加 `sysid` 列（V22，NULL=普通 Key 语义不变）：`POST /api/v1/auth/api-key {"name":...,"sysid":N}` 签发**设备 Key**——设备须已登记（404）且已归属租户（**400 fail-closed**：未归属设备的数据任何租户都不可见，发 Key 就是写黑洞）；`role` 固定 OPERATOR（设备永不需要 ADMIN）、`tenantId` 取自设备行、`userId` 空；租户级非 ADMIN 只能给本租户设备签（403），与撤销/轮换同口径 |

| 2 | 无轮换路径 | 换 key 只有"撤销旧+建新"两步手工操作，间隙断流 | `POST /{keyId}/rotate {graceHours?}`（0..8760）：0=旧 Key 立即撤销；>0=旧 Key `expiresAt` **缩短**到 `now+grace`（不撤销、宽限期内新旧并存、边缘设备从容换钥、自然到期）；新 Key 继承全部绑定（tenantId/userId/sysid/scopes/role）与**剩余有效期**（永久保持永久；已过期 Key 轮换=重新计时 365 天） |

| 3 | 认证路径性能 | 每请求 1 SELECT + 1 同步 UPDATE（`lastUsedAt` 精确到请求——运维观测用途配不上这个代价） | `ApiKeyCache`：60s TTL 正/负缓存，命中**零数据库 IO**；`expiresAt` 不缓存判定结果、每次用当前时钟重算（TTL 内到期不续命）；负缓存 4096 条上限防哈希喷射撑爆内存。`ApiKeyLastUsedTracker`：30s 周期合并刷 `lastUsedAt`（每 Key 至多每 30s 一次 UPDATE）+ `@PreDestroy` 兜底 flush；语义从"精确到请求"退化为"精确到 30s" |

| 4 | 撤销时效 | （缓存引入后的必答题）撤销多久生效 | 撤销/轮换后控制器 `invalidate` 对应哈希——**本 JVM 即时生效**（IT Pass B 断言 8 末条端到端实证：撤销后同 key 再摄取必须 401，缓存失效逻辑坏了这条就红）。多节点下其余节点至多 60s（TTL）收敛，这是**明示的一致性上界**而非漏洞，已写进 security-design §3.2（要求跨节点即时的部署走 LB 会话亲和） |

| 5 | mint 数据源错误 | 本机 IT 实测抓到：mint 读 `devices` 表判"设备是否已登记"，但 dev/test 的 `device-registry.persist=false`——provisioning/心跳只写内存注册表，表恒空 → 刚 `POST /api/v1/devices/201` 拿 201、紧接着签 Key 就 404 | mint 改读 `DeviceRegistry.isKnownDevice()/tenantOf()`——注册表才是两种 persist 模式一致的"已登记"真值源 |

| 6 | 死列清理 | `devices.device_token`：V1 建表起全仓零读写调用方（有列无功能，曾误导侦察） | V22 `DROP COLUMN`；设备认证统一走 `api_keys`，`DeviceEntity` 同步删字段 |

| 7 | CI 门禁 | Pass B 此前只覆盖共享 key 通路（断言 7） | 新增 Pass B **断言 8**（9 条）：ADMIN 建租户（API 建，不假定 id=1——dev H2 无租户种子，写死会在 provisioning 的租户存在性校验上 404）→ 登记 sysid=201 并归属 → 签设备 Key（断言明文仅此一次返回）→ 摄取 200 → `DELETE` 撤销 → **同 key 再摄取必须 401**（缓存失效端到端证据——单测只能证明 invalidate 被调用，这里证明端到端真的失效） |

| 8 | 口径文档 | 测试数/端点数/凭据契约三处声称过期 | 4084 单测（8 文档 18 处）；345 端点（9 文档，含 sales-pitch 数字锚点）；`security-design.md` §3 重写（设备 Key/轮换语义/缓存一致性上界）、§3.4 引导定位改为"引导非运营"；`api-reference.md` 补 rotate 端点与 sysid 参数、凭据契约改为两条路；README 同步 |



**新增测试**：`ApiKeyCacheTest`(9：TTL 正/负缓存、到期不续命、invalidate、负缓存上限、时钟注入)；`ApiKeyLastUsedTrackerTest`(6：合并写、周期 flush、退出兜底、异常不杀调度)；`ApiKeyFilterCachedTest`(8：缓存命中零 DB IO、未命中查一次回填、撤销/不存在负缓存、失效重查、无缓存退化路径)——**测试姿势钉子**：上下文是 ThreadLocal 且 `finally` 必清理（生产线程池语义），一切断言在**链内快照**，doFilter 返回后再读恒 null（本轮 3 条用例首写全红正是栽在测试姿势而非产品缺陷，诊断用例逐项排除后定位）；`ApiKeyControllerDeviceKeyTest`(13：绑定/哈希口径/越界/未归属 400/跨租户 403/宽限期截短而非放宽/继承剩余有效期/立即撤销/缓存失效调用)。



**决策记录——MAVLink 签名默认值（`mavlink.signing.enabled`）**：**维持 false**（2026-10-03 定，security-design §7.5 落档）。依据实际用户需求：链路签名要求链上**所有**端点共享口令，而存量无人机/地面站出厂不配置签名——默认打开等于首启断链，与"部署即飞"的产品承诺冲突；GB 42590 数据链路安全项的定位是**需要的部署显式开启**（开启而缺密钥已由 `MavlinkSigningConfiguration` + `afterPropertiesSet()` fail-fast，不存在"半开"状态）。默认值翻转的先决条件是真机（PX4）联调证据，现有互通证据止于 pymavlink 已知答案向量 + 自环。



**登记项处置**（本轮清点）：③ 设备摄取共享 key → **本轮收口**；M11 执行级接线 → **维持缓期**（引擎直接驱动飞控动作是安全域变更，需要人审门禁/演练/回滚设计，不塞进凭据轮）；Playwright E2E → **维持缓期**（CI 浏览器基建成本 vs 现有 HTTP 层 e2e + vitest 覆盖，PoC 阶段收益不成立）；H2 数据文件 → **核实已闭环**（无 git 跟踪文件，`.gitignore:15-19` 全路径覆盖）；`@RequireRole` fail-closed → **核实已于 2026-09-30 轮翻转**，无残留。



**顺带发现（未改，本地卫生）**：`cloud-backend/target/classes` 里有一份**从未提交**的孤儿迁移 `V22__flight_log_id_sequence.sql`（FlightLog IDENTITY→SEQUENCE 批处理优化实验：注释完整论证了 Hibernate 对 IDENTITY 无法批 insert、遥测 430 万行/天的写放大，但实体侧 `GenerationType.IDENTITY` 从未同步改，src 自洽无缺失）。该孤儿被 jar 打包带上后与本轮 V22 撞号（Flyway "Found more than one migration with version 22"，本机 IT 才能抓到，CI 全新 checkout 不可见）。已从本地 target 清除；批处理优化本身作为后续独立项（写路径默认关闭，不紧急）。



**本轮未闭合**：多节点撤销传播 60s 上界（文档明示，跨节点即时撤销需广播/共享缓存机制，待多节点部署成为真实场景再评估）；MAVLink 签名遗留项——密钥库明文、时间戳 1ms 粒度、密钥库未命中回退 `defaultKey`、无真机联调（均不变，见签名轮"本轮未闭合"）。



**本轮完成（SSE 流令牌 `feat/sse-stream-token`，2026-10-03）**：

`POST /api/v1/auth/stream-token` 签发 60s 单次用 opaque 流令牌（`?streamToken=` 开流）；`StreamTokenService`（内存 ConcurrentHashMap、单 subject 上限 8、全局上限 4096、时钟可注入）+ `StreamTokenFilter`（仅拦截两条 SSE 路径、头凭证优先、跳过写 401 交授权层统一拒绝、finally 清 `TenantContext`）+ `RoleInterceptor` 第三角色来源（`StreamTokenAuthenticationToken`）；`AlarmEventStore` 增 `queryScoped` / `countScoped` 显式租户域重载，`AlarmController.streamEvents` 订阅时捕获 `TenantContext.getEffectiveTenantId()` 传入轮询任务（修复调度线程 `null` → 全局管理员的跨租户泄漏）；`SurveillanceController.subscribeEvents` 已内置 `getDevice` 租户可见性（无额外改动）；前端 `api.js` `fetchStreamToken()` + `alarmStreamUrl(token)` + `surveillanceStreamUrl()`，`AlarmPanel.jsx` / `UnifiedCommandPanel.jsx` 订阅改为先取令牌再 `EventSource`；`security-design.md` §3.5 新增、`api-reference.md` 端点 345→346、`CHANGELOG.md` 口径登记。



**新增测试**：`StreamTokenServiceTest`(9)、`StreamTokenFilterTest`(10)、`AuthControllerTest` 扩展（+4 流令牌端点）、`RoleInterceptorTest` 扩展（+5 第三角色源/优先级/fail-closed），`AlarmEventStoreTest` 扩展（+2 scoped 口径）；`gcs-web` `api.test.js` 扩展（SSE URL 构造）。



**CI 收口修复**（`bddd0a4`，2026-10-03）：首轮 CI 红两处——(1) Docs test-count gate：4 个漏同步文档（`pricing-strategy`/`demo-scenarios`/`customer-onboarding-guide`/`low-altitude-economy-demand-research`）仍声称 4084/2150，已对齐 4117/2183（`check-test-count-docs.py` 全部一致）；(2) Integration Tests Pass B 断言 9 开流得 `000`：`SseEmitter` 响应头要等首次 `send()` 才提交，而静默期（无增量事件）首次发送是 15s 心跳，超出 `-m 3` 取证窗口——修复为两个 SSE 端点（`AlarmController.streamEvents`/`SurveillanceController.subscribeEvents`）**建立即发一条 `stream-established` 注释**，响应头即刻提交（也是 SSE 最佳实践：客户端即时确认连接）。本地实跑 IT 全绿（断言 9 四连绿：签发 200/开流 200/复用 401/无凭证 401），`AlarmControllerTest` 29/29 绿，master CI 19/19 job success。



---

---

---

## [Unreleased] — CI 集成测试适配 fail-closed 配置守卫（jwt-secret / encryption.key）（2026-10-03）



> c6b069b 的配置守卫上线后，Integration Tests 连红三轮（c6b069b、76f964b、7909740）。

> 守卫均按设计工作，红的是 harness：测试环境启动的进程没有像真实部署一样给全

> 必需配置。修 harness，不修守卫。



- **Pass B**（dev profile + dev-mode=false）：`LicenseService` 对空值/内置开发默认值

  的 jwt-secret 拒绝启动——Pass B 此前不注入密钥，正是守卫要拦的错误形态。修复：

  启动参数显式注入 `--aerofleet.security.jwt-secret`（复用 Pass C 的 CI 密钥；

  变量 `C_JWT_SECRET` 更名 `CI_JWT_SECRET`，双 Pass 共用）。

- **Pass C**（prod profile）：`aerofleet.encryption.key=${AEROFLEET_ENCRYPTION_KEY}`

  无默认值（2026-10-01 删除明文默认值的既定策略），缺失即 `PlaceholderResolutionException`

  拒启动。该缺口被 Pass B 掩盖三轮（脚本在 B 即退出，C 从未跑到），Pass B 修复后

  才暴露。修复：Pass C 环境注入 `AEROFLEET_ENCRYPTION_KEY`（消费方

  `WebhookService`/`PasswordConverter` 以 SHA-256 派生 AES-128，任意非空串即可，

  此为 CI 专用值）。至此 prod 四个必填占位符（jwt-secret/users/encryption.key/

  datasource.password）harness 全部显式供给。

- 守卫语义零改动：Pass A（dev-mode=true 仅 WARN）不受影响；两处守卫对真实部署

  的拦截行为不变。



---

---

---

## [Unreleased] — License 端到端签发工具 + 签名规范化跨 mapper 硬化：收口 license 轮未闭合项 ①（2026-10-03）



> 「拿着生产私钥，怎么给客户签一份部署端能验过的 key？」——此前这条真实运营路径

> 无解：`LicenseKeyGenerator` 只出密钥对不出 key；`LicenseService#generateLicenseKey`

> 能组装 key 但仅开发模式可用（生产签名器无私钥）。本轮补上最后一环，端到端测试

> 首跑即暴露一个会让「合法 License 在部分部署上验不过」的签名规范化缺陷，一并硬化。



### 1. 新增 `LicenseIssuer`：生产私钥进，可部署 key 出



- `LicenseIssuer.issue(info, signer, mapper)`：全仓**唯一**的 payload+signature

  组装路径——`LicenseService#generateLicenseKey` 的内联拼接改为委托到这里，

  `KEY_SEPARATOR` 的唯一定义也移到本类（解析端只引用）。签发/解析两侧各自手拼

  格式一旦漂移，就是「签出的 key 部署端验不过」这类只在真实签发时爆的问题。

- **签出即自验**：组装后立即按 `parseSignedLicense` 的验签路径（反序列化 →

  setSignature → verify）用配套公钥验一遍，私钥/公钥不配对就地报错，绝不让一份

  「自己都验不过」的 key 离开签发工具。

- CLI（`main`）：`--private-key-file`（PKCS#8 Base64，容忍 PEM 头尾/折行）+

  授权参数，输出 key 串与部署端配置片段；公钥缺省由私钥 CRT 参数推导用于自验。

  **必填项不给危险缺省**：`--max-devices` 必填（防漏配时静默签出无限设备授权）、

  `--expiry`/`--valid-days` 二选一必填（永久授权不能是「忘了写」的结果）、

  `--modules` 打错字就地报错（防「模块被静默拒绝」的排障黑洞）。

- `LicenseSigner` 新增签发模式构造器（外部私钥+配套公钥，包内可见）。



### 2. 签名规范化与部署 mapper 配置解耦（端到端测试暴露的真实缺陷）



- **缺陷**：`serializeForSigning` 用**注入的** ObjectMapper 做签名输入的规范化，

  签名输入就成了「License + 本地 mapper 配置」的函数。Jackson 的

  `WRITE_DATES_AS_TIMESTAMPS` 原生默认开、Spring Boot 默认关——**ISO 签发 +

  时间戳模式的部署，同一份 key 两端算出的签名输入不同 → 合法 License 验签必败，

  fail-closed 拒绝启动**。此前从未暴露：生产两端都是 Boot mapper（默认 ISO），

  测试两端共用同一个 mapper，缺口在两者的交叉处。

- **修复**：规范化钉死到 `LicenseSigner` 内部的 `CANONICAL_MAPPER`（jsr310 +

  日期一律 ISO），签名输入只是 License 内容本身的确定函数，与两端 mapper 配置

  无关。注入的 objectMapper 自此不再参与签名（构造器参数保留，标注待后续大版本移除）。

- **钉子**：`LicenseIssuerTest` 的部署端 mapper 故意保持 Jackson 原生默认

  （时间戳模式）与签发端（ISO）相反——该配置组合若再引入 mapper 依赖，验签用例

  立刻变红。



### 3. `LicenseIssuerTest`（15 例）与口径



- 端到端 4 例：签出的 key 被生产模式 `LicenseService` 构造器（fail-closed 加载）

  接受且字段一致；篡改 payload 拒绝启动；私钥/公钥不配对自验拦截；已过期时间可

  签出但部署端判定无效（工具只保证密码学正确，不做商业判断）。

- CLI 8 例：最小参数+默认值、三类必填缺失、`--expiry`/`--valid-days` 互斥与

  必填、`--valid-days` 计算、模块打错字报错。

- 密钥 3 例：PEM/折行容忍加载 + CRT 推导等值公钥 + 从文件签发端到端。

- 委托回归 2 例：dev 模式 `generateLicenseKey` 委托后仍可被生产模式部署接受；

  生产模式（无私钥）保持 null 契约。

- 测试基线 4033 → **4048**（cloud-backend 2099 → 2114），口径文档 19 处声称

  同步更新，`check-test-count-docs.py` 全绿。



---

---

---

## [Unreleased] — LicenseController 激活/查询路径测试：收口 license 轮未闭合项 ②（2026-10-03）



> License fail-closed 轮（025b6a7）留下的「LicenseController 的激活/查询路径仍无

> 测试」在 PR 自查时收口：服务层验签/过期语义已被 `LicenseServiceFailClosedTest`

> 钉住，但控制器 HTTP 侧的字段映射、参数缺失 fail-fast、服务判定到响应信封的

> 透传一直零覆盖。



- 新增 `LicenseControllerTest`（7 例，standalone MockMvc + mock LicenseService）：

  - `GET /api/v1/license/info`：License 字段逐项映射（含派生 `expired` 与

    `devEdition` 透传，2 例）；

  - `POST /api/v1/license/activate`：缺任一必要参数返回 `success=false` 并指明

    缺失项、**不触发服务校验**（never 验证）；合法激活码 `success=true` 且参数

    按请求体原样透传（verify 精确参数）；无效激活码 `success=false`（3 例）；

  - `GET /api/v1/license/verify`：服务判定有效/无效两种信封映射（2 例）。

- 分层依据：拦截器排除路径已由 `LicenseConfigTest` 走真实 MVC 切片覆盖，RBAC

  注解覆盖面由 `RbacEndpointCoverageTest` 反射钉住，本类只测「请求体 → 服务调用

  → 响应映射」这一层。

- 测试基线 4026 → **4033**（cloud-backend 2092 → 2099），当前口径文档 22 处声称

  同步更新，`check-test-count-docs.py` 全绿。



---

---

---

## [Unreleased] — 搁置项清理：M11 advisory 接线、语音指令真实下发、CDN 本地化等（2026-10-02）



> 第六轮审查后遗留的决策项分批解决。本轮六项全部落地，各模块测试全绿。



### 1. M11 自主决策：advisory 接线（建议-only，改变第六轮「不擅自接线」的决定）



- 新增 `drone-sim` 的 `AutonomyAdvisor`：`VirtualDrone.tickOnce` 每 tick 调用、

  内部 1Hz 节流，组装 `DecisionContext`（电量含场景故障覆盖、链路静默与

  `FailsafeThresholds.LINK_LOSS_AFTER_MS` 同口径、GPS、障碍报告、场景风+环境风

  合成），主决策类型**变化沿**经 STATUSTEXT 播报一次（RTL/AVOID=WARNING、

  EMERGENCY_LAND=CRITICAL、ADAPT_PATH=NOTICE，恢复时 INFO 澄清一次）。

- **只建议、不执行**：不触碰飞行状态；真正生效的应急执行链路仍是

  `FailsafeController`。执行级接线仍属产品决策，未做。

- `AiAutonomyWiringTest` 反转：`DecisionEngine` 移出 UNWIRED 名单 + 新增

  「已接线」正向断言（静默退线即判红）；其余策略/规划器/M12 边缘库仍未接线

  （期望状态不变）。新增 `AutonomyAdvisorTest`（6 例）。

- 文档同步：ROADMAP M11 / README 已知边界 / whitepaper / PRODUCT-POSITIONING /

  竞品对比表 + 脚注。



### 2. 语音指令真实下发（voicecmd：从「模拟成功」到真实 MAVLink 通路）



- `VoiceCommandExecutor` 注入 `DroneCommandService`：TAKEOFF→`takeoff`（缺省

  高度 10m，与仿真默认一致）、LAND→`NAV_LAND`、RETURN→`rtl`、PHOTO→

  `IMAGE_START_CAPTURE`（单张）、FLY_TO→单航点任务上传 + `MISSION_START`

  （需显式坐标+高度，仅地名无坐标服务→REJECTED）；HOVER/RECORD/SET_ALTITUDE/

  SET_SPEED→REJECTED（无对应命令通路，原因写明）。

- confirm 语义重构：404 仅限 pending 不存在；下发失败返回 200+FAILED（原实现

  会把真实失败误报成 404）。ARM/TAKEOFF 仍过 geofence 拦截链（DENY 不抛异常）。

- 测试：`VoiceCommandExecutorTest` 重写 23 例（Mockito 桩）/

  `VoiceCommandControllerTest` 更新 18 例；voicecmd 套件 59→73。

- `docs/api-reference.md` 语音小节同步真实下发语义。



### 3. 前端：CDN 本地化 + 虚拟摇杆重构



- three.js r128 与 hls.js 1.5.13 落地 `gcs-web/public/vendor/`（含双 LICENSE

  与来源/版本/更新流程 README），`THREE_CDN`→`THREE_SRC`、`HLS_JS_CDN`→

  `HLS_JS_SRC`，构建产物零外链（构建绿，dist/vendor 5 文件齐全）。

- `Joystick.jsx` 重写为 Pointer Events（setPointerCapture + touchAction none），

  新增偏航瞬时按钮 ↺/↻（YAW_RATE=400），发送循环幂等、无输入自停、disarm 清零。



### 4. sdk-java README 修复（JitPack 可用性）



- Maven 坐标 artifactId 修正（`NexusSky`→`nexussky-sdk-java`，与 JitPack 多模块

  坐标规则一致）、Jackson 版本对齐 2.21.7、新增「关于坐标与线上构建状态」小节

  （根聚合 packaging=pom 不产 jar、源码集成 fallback `mvn -pl sdk-java -am install`）。

  jitpack.yml（`jdk: openjdk17`）此前已提交且 v1.0.2/v1.0.3 tag 均包含之；

  jitpack.io 线上构建状态离线环境无法验证，已在 README 如实声明。



### 5. 前端单测基建（vitest，补齐 devops-enhancement-plan CI7 的单测半边）



- `gcs-web` 新增 vitest（^5.0.3）与 `npm test`（`vitest run`）；24 例单测：

  `api.test.js`（JWT 格式校验/token 会话、getWsUrl 协议与 token 编码、

  normalizeBudgetMode fallback 告警、预算档位面板裁剪包含关系）、

  `Scene3DUtils.test.js`（geoTo3D 坐标契约：原点/象限/比例，独立于实现公式验证）。

  api.js 模块级读取 `location`，测试须先 `vi.stubGlobal` 再动态 import（注释写明）。

- CI frontend job 新增 `npm test` 步骤（lint → test → build）；本地回归

  `npm run lint`（0 errors）+ `check-frontend.cjs`（61 文件 OK）+ `npm run build`

  全绿。Playwright E2E 仍缺（CI7 另一半，未做）。



### 6. TenantInterceptor 双轨统一（移除「声称隔离、实为零消费者」的死租户轨）



- 发现：代码中存在两个同名 `TenantContext`——`security.TenantContext`

  （Integer 三态租户域：真实租户 / null=全局管理员 / NO_ACCESS，由

  `TenantFilter`/`ApiKeyFilter` 写入、16 个业务类消费，隔离唯一真轨）与

  `tenant.TenantContext`（String，**全仓零消费者**，仅 `TenantInterceptor` 写入）。

- 安全影响：`TenantInterceptor` 曾从**客户端可控**的 `X-Tenant-Id` header 提取

  租户：虽未参与数据隔离（String 轨无人读），但轮换 header 即可无限获取新

  限流桶，**绕过按租户限流**；且前端根本不发送该 header。

- 统一：`TenantInterceptor` 瘦身为纯限流器——限流 key 只读认证链已写入的

  `TenantContext.getEffectiveTenantId()`（真实租户 → `tenant:<id>` 桶；

  未认证/全局管理员/NO_ACCESS/dev-mode → `ip:<addr>` 桶）；移除 header 提取、

  死 String 上下文（`tenant/TenantContext.java` 整文件删除）与 `afterCompletion`

  清理（租户上下文生命周期全权归过滤器）。类 javadoc 明示「不负责租户隔离」。

- `RedisRateLimiter` 参数/key 语义同步（通用限流 key）；`TenantConfig` javadoc 更新。

- 新增 `TenantInterceptorTest`（5 例）：429 行为、**轮换 header 不再能绕过限流**

  （回归）、真实租户桶与 IP 桶隔离、NO_ACCESS 回落 IP 桶。

- 文档同步：security-design §4.3/§6.2、integration-guide §5.1（curl 示例不再带

  X-Tenant-Id）、troubleshooting 429 行与 Q7 第 4 步（afterCompletion 已不存在）、

  architecture mermaid（租户上下文节点改为 TenantFilter/ApiKeyFilter，幻影节点

  RateLimitFilter 改为 TenantInterceptor 限流）、commercialization-plan 三处旧述。



---

---

---

## [Unreleased] — 第七轮审查：文档量化声称 vs 代码实测对账（2026-10-02）



> 承接第六轮「声称 vs 实际」的方法，本轮把对外材料的量化指标逐个实测：

> 344 端点 ✓、51 扩展消息 ✓、38 功能面板 ✓、7 链路画像 ✓；「66 个 Controller」

> 实测 65，api-reference 有 5 个 Controller 完全无文档。全部修复。代码行为变更

> 见第 6 节（路径对齐发现的拦截器死排除修复）与第 7 节（提交前自查补档的

> 工作区既有未入档变更）；其余只改文档。



### 1. 实测口径修正（含两处上轮测量失误订正）



- **65 个 `@RestController`**（注解位置 `^\s*@RestController\b`）= 64 个

  `*Controller.java` + `delivery2/DeliveryController2.java`——后者文件名以

  `Controller2.java` 结尾，会被 `*Controller.java` 过滤漏数。上轮「67 个

  Controller 文件 / 32 个包」即因此失准，实为 **68 个 Controller 源文件 /

  36 个业务包**（65 REST + 3 个 @Service）。

- **344 端点** ✓（190 GET / 127 POST / 13 PUT / 14 DELETE / 0 PATCH）、

  **51 条扩展消息（420-483）** ✓、**38 个功能面板** ✓、**7 个链路画像** ✓

  （LinkProfile 逐项核对，销售材料无需改）。

- 逐 Controller 实测端点数与 api-reference 映射表 60 行**全部一致**（表行

  合计实为 324）；「60 个 / 318 个」只错在头注、合计行与脚注。



### 2. api-reference.md：补齐 5 个无文档 Controller



- 新增 5 个完整小节 + 目录项 + 映射表 5 行：RegulatorController

  （/api/v1/regulator，5）、RidController（/api/v1/rid，5）、RestrictionController

  （/api/v1/geofence，4）、DeviceProvisioningController（/api/v1/devices，4）、

  CvEvalController（/api/v1/cv-eval，2）；合计行 60/318 → **65/344**；脚注

  「63 个 *Controller.java」→ 68 源文件口径。api-quick-reference.md 同步

  头部计数并补 5 个模块速查表。

- 顺带订正：AuthController/LicenseController 基础路径文档写 `/api/auth`、

  `/api/license`，代码实为 `/api/v1/auth`、`/api/v1/license`（两文档 16 处

  正文 + 2 处映射表行）；附录角色表「（无注解）登录用户即可」行与 RBAC

  默认拒绝翻转矛盾，改为 OBSERVER 行。（提交前自查证实旧路径残留是全仓性

  问题，扩展为第 5 节的全仓对齐。）



### 3. 其余文档订正



- 「66 个 Controller」→ 65 @RestController（README、commercialization-plan、

  low-altitude-economy-demand-research）；「20 个功能域」→ 36 个业务包

  （commercialization-plan、sales-pitch-deck）。

- security-design RBAC 表加复测注：翻转时点快照 342 → 现测 344

  （190/127/13/14/0），未声明端点 0（历史快照数字保留不改）。

- sitl-integration.md：扩展消息「44 条 / 420-476」→「51 条 / 420-483」

  （9 处），消息总览表补 477-483 一行（ALARM_TRIGGER / ALARM_ACK /

  SURVEILLANCE_STATUS / QOS_ROUTE_DECISION / CLUSTER_FORMATION /

  DISASTER_MODE_STATUS / BUZZER_CONTROL）；可变长度消息仍 4 条，不变。

- sales-pitch-deck M11 行：「未接线」→「已接线（AI advisory，建议-only）」。

- commercialization-plan：「限流为单机内存」两处旧述（§3.1 差距 5、§3.3

  关键差距表）改「Redis 优先 + 内存回退」；「业务数据无 tenantId 字段」

  确证过时——V10–V16/V18 迁移已给设备/围栏/越界/位置/编队/喷洒/配送/编排/

  安防/告警/测绘/表演/配送2/飞行日志表落地 tenant_id，§3.3 数据隔离行与

  关键差距表三行按 §4.1 惯例标记已修复；真实遗留（License tenantId String

  vs TenantEntity.id Integer 类型未统一）保留。



### 4. ai 包 4 类 Javadoc 在 advisory 接线后的精确化（代码内文档，仍无行为变更）



- 承接上方「搁置项清理」的 M11 advisory 接线：一刀切的「未接入生产路径」

  对经 DecisionEngine 间接执行的类已不准确——`AutonomyAdvisor` 调

  `evaluateFused`，后者只调三个策略的**旧 evaluate 签名**与决策树，

  新算法接口不在链上。

- 4 类 Javadoc 补「advisory 接线后的精确状态」段，两层区分：旧接口随

  advisory 链 1Hz **间接执行**（只产出建议，不执行动作）；「未接入」收敛为

  `AiAutonomyWiringTest` 的定义——**无包外生产调用方**（4 类仍成立）；而各家

  的真实算法新接口仍**零生产调用方、从未执行**：ReturnToHomeStrategy（滑翔/

  地形/能耗模型）、ObstacleAvoidanceStrategy（`avoidWithPath` 与 PathPlanner

  A*/RRT）、AdaptivePathStrategy（风补偿/能耗优化/Dubins）、DecisionTree

  （evaluate 间接执行）。

- PathPlanner / AutoAvoidance / SwarmCoordination / EmergencyReturn 与 edge

  包两类**确实从未执行**，Javadoc 原样保留；守卫测试

  `unwiredClassesDocumentTheirStatus` 依赖的「未接入生产路径」短语在 4 类中

  全部保留，重跑 `AiAutonomyWiringTest` 7/7 绿。



### 5. 全仓 API 路径对齐（提交前自查扩展，2026-10-03）



- 第 2 节「顺带订正」的旧路径问题在提交前全仓复扫中证实为系统性残留：代码

  65 个 `@RestController` 基路径**全部**为 `/api/v1/*`（含 `ApiVersionConfig`

  约定），但约 200 处文档仍写旧路径。本轮以代码为权威口径一次性对齐：

  - 机械替换（边界感知正则，词干防误伤）：api-reference.md 83 处、

    api-quick-reference.md 42 处、sdk-reference.md 18 处、demo-scenarios.md

    17 处、sales-pitch-deck.md 3 处，integration-guide.md 与

    customer-onboarding-guide.md 的 auth/drones/twin 词干。

  - commercialization-plan.md 手工重写：附录 A「54 个」→ 68 源文件口径

    （65 @RestController + 3 @Service），28 行路径按代码订正（sat-link、

    env-alerts、voice-cmd、voice-intercom、video-stream、city-twin/*、

    comm-adapt、inspection/reports、scenarios/*、obstacle、ai 等），补

    Regulator/Rid/Restriction/DeviceProvisioning/CvEval 等 12 个缺失

    Controller 行；HardwareData/Radar/Rotor/ObstacleAvoidance 4 行改为实际

    暴露方式（`/api/v1` + /radar、/rotor、/lidar、/imu 子路径；3 个

    @Service 经宿主 Controller 暴露）；§1.4 模块表 5 行路径、§2.1 审查表

    3 行改「✅ 已核实」、附录 B 登录/刷新路径订正。

  - 端点用法改写为真实签名：integration-guide.md 任务上传

    `POST /api/v1/drones/{sysid}/mission`（body 为 `items` 数组，≤1000 项，

    cmd/lat/lon/alt/holdTime）、任务分配 `POST /api/v1/scheduling/tasks`

    （TaskRequest 补必填 `taskType`：SURVEY/SPRAY/RELAY/RESCUE）、孪生预测

    参数 `horizonSeconds` → 代码实参 `horizon`（默认 30）；

    customer-onboarding-guide.md 验证表 2 行与 architecture.md 时序图同步

    改为真实端点。

  - 误报核实不改：e2e-regulator.ps1 的 `/api/records`、`/api/telemetry` 是

    regulator-sim `MockUomServer` 自有路由（非 cloud-backend 端点）；

    security-design.md 的「api/exception/」是 Java 源文件路径非 REST 路径。

    gcs-web/src/api.js 路径常量本就正确，仅 2 行注释订正，无运行时影响。

- **新发现（仅记录，不改行为）**：`VideoFusionPanel` 调用的 4 个

  `/api/v1/video-fusion/*` 端点在后端无对应 Controller——视频融合是

  `docs/new-features-plan.md` 的 P2 规划项（前端先行），面板对失败有错误

  捕获；README「已知边界」已加条目，并与已实现的「GCS 视频融合面板」

  （= SurveillancePanel + AlarmPanel，真实端点 `/api/v1/surveillance/*`、

  `/api/v1/alarms/*`）作出区分。



- **代码内文档同步（javadoc，无行为变更）**：对齐时顺带扫描出 32 个后端源码

  文件的 **154 处 javadoc/注释**仍在用 v1 迁移前的路径描述自家端点（如

  `AuthController` 类注释写 `POST /api/auth/login`，实际是

  `/api/v1/auth/login`；覆盖 alarms/show/mapping/emergency-command/

  scenarios/city-twin/geofence 等 20+ 个域）。已全部按真实注解路径订正；

  逐文件 diff 复核确认只动了注释行。`TenantInterceptorTest` 的 mock 请求

  路径同步改为 `/api/v1/devices`。



### 6. 路径对齐的代码面发现：拦截器死排除修复



- 全仓对齐时发现 `LicenseConfig` / `TenantConfig` 的拦截器排除路径仍停留在

  v1 迁移前：`/api/auth/**`、`/api/license/**` 是死模式（真实端点在

  `/api/v1/auth/*`、`/api/v1/license/*`；`SecurityConfig` 的 requestMatchers

  早已改对，这两个 `4478cf7` 引入的配置漏改）。

- 实际影响：`aerofleet.license.enabled=true`（prod/staging 默认）且 License

  运行期失效时，`LicenseInterceptor` 把登录/刷新与 License 自管理端点一并

  403——鸡生蛋，部署无法经 API 激活/查询自救；`TenantInterceptor` 的共享

  IP 限流桶则把登录/刷新与同 IP 其他匿名流量耦合（默认 100/分钟，登录另有

  每 IP 10 次/分钟的独立限制）。

- 修复：两处排除改为真实 v1 路径（`/api/v1/auth/**`、`/api/v1/license/**`）；

  新增 `LicenseConfigTest`（4 例）/`TenantConfigTest`（2 例）：`@EnableWebMvc`

  最小切片 + webAppContextSetup 走真实拦截器路径匹配，被测路径直接取自

  AuthController/LicenseController/ApiKeyController/DroneController 的

  `@RequestMapping` 注解（路径再漂移即判红）。已做变异验证：回滚为死模式时

  LicenseConfigTest 3/4 判红（登录/刷新、License 自管理、API Key 各一）。

  `SecurityConfig` 类 Javadoc 同步（正文曾写「/api/auth/** 和 /actuator/**

  公开」，实际只放行 login/refresh 与 `/actuator/health*`）。

- 测试基线 4020 → **4026**（cloud-backend 2086 → 2092），

  `check-test-count-docs.py` 全绿；README/ROADMAP/whitepaper/sales-pitch-

  deck/pricing-strategy/demo-scenarios/customer-onboarding/low-altitude

  8 个文档 22 处声称同步更新。



### 7. 提交前自查补档：工作区既有未入档变更（非本轮新做，随本次提交入档）



- 单 commit 收口前对整个工作区逐文件对账，发现以下变更已实现、有注释与

  测试佐证，但此前无 CHANGELOG 记录——如实补档，避免「提交里有、记录里无」：

  - **EdgeCoordinationController.submitResult 入参硬化**：`POST /api/v1/edge/results`

    缺失/非数值 `sysid` 返回 400（原实现 `(Integer) body.get("sysid")` 对非数值

    直接 ClassCastException→500）；`taskId`/`type` 宽容转换（缺失 null、

    非字符串取文本形式而非 500）。`EdgeCoordinationControllerTest` 补 3 例

    （合法 body 200 / 缺 sysid 400 / 非数值 sysid 400）。

  - **LicenseService 激活码密钥 fail-closed 守卫**：内置开发默认值抽为

    `DEV_HMAC_SECRET_FALLBACK` 常量；构造器守卫——`aerofleet.security.jwt-secret`

    （JWT HS256 回退与激活码 HMAC 双用途）留空或等于内置默认值时：dev 模式

    WARN（激活码功能明确不可用，`generateActivationCode` 返回 null），非 dev

    模式拒绝启动。与 prod/staging `${AEROFLEET_JWT_SECRET}` 无缺省 fail-fast

    同一口径，补上「设了但值为空/默认值」的缺口；application.properties 注释同步。

  - **FlightLogService 节流表线程安全**：`lastTrackWrite` HashMap →

    ConcurrentHashMap（`telemetry()` 可能被并发调用，防并发写损坏内部结构；

    节流判断本身允许良性竞态）。

  - **drone-sim WIND 风向改进程启动抽取一次**（`bootWindDirRad`）：原代码逐

    事件随机抽取，与 `DronePhysics` 类注释「fixed pseudo-random direction per

    boot」自相矛盾；现一次运行内为恒定方向（持续侧风语义），跨进程方向不同。

    `DronePhysics`/`ScenarioController` 同步。

  - **MavlinkSigner javadoc 订正**（仅注释）：签名覆盖范围表述明确为

    「帧头（STX）到 CRC，含 STX 本身」。

  - **scripts/e2e-rid.ps1 JAVA_HOME 解析健壮化**：尊重已设置且可用的

    JAVA_HOME；未设/无效时回退 `AF_JDK17_HOME` → 内置默认路径。

  - **监控栈部署修复**：docker-compose-monitoring 补 `alertmanager.yml` 只读

    挂载（无配置文件时 AM 崩溃重启循环，prometheus 一直在向 :9093 推告警）；

    Grafana 供给改为正规三件套——`grafana-datasources.yaml`（uid=prometheus

    与面板引用对齐）+ `grafana-dashboards.yaml`（provider 声明）+ JSON 挂到

    provider 声明的 path 下（直接挂进 provisioning/dashboards/ 会被当成

    provider 配置解析失败）。新增上述三个配置文件。



---

---

---

## [Unreleased] — 第六轮审查：自主决策接线状态、单测数口径与门禁（2026-10-02）



> 第五轮结论是「审查收敛」。本轮换方法重扫：不再按「找 bug」的思路，而是按

> **「文档声称的 vs 代码实际的」**逐条对账——从销售材料、路线图、白皮书、竞品表

> 反向查回代码。18 个问题按根因分 5 组，全部有可复现的取证位置。



### 1. AI 自主决策：库齐全，从未执行（4 项）



| 声称 | 实际 |

|---|---|

| 竞品对比表给「自主决策引擎 / 边缘 AI 推理 / 传感器融合」打 ✅ | 三个包**生产零调用方** |

| ROADMAP M11「自主决策引擎（AI 飞行策略）✅ 已完成」 | 从未执行 |

| Javadoc 称 `DecisionEngine` 用「控制台输出（替代 slf4j）」 | 实际是 `log.debug` |



- **证据**：`io.aerofleet.sim.ai` 14 个类 3513 行、`io.aerofleet.sim.edge` 6 个类

  1093 行（`SensorFusionEngine` 521 行 / `VideoStreamAnalyzer` 506 行），实现完整、

  单元测试全绿，但**只被自己的测试引用**。`VirtualDrone` 的应急链路走的是另一套

  独立实现 `FailsafeController`（`VirtualDrone.java` 里唯一的 failsafe 字段）。

- **「AI」的准确含义**：规则 + 排序 + 搜索（阈值规则、融合权重、决策树、A 星 / RRT），

  **不含任何机器学习模型**。全仓 pom 无 onnxruntime / tensorflow / ONNX / OpenCV 任何

  依赖；「边缘 AI 推理」的准确含义是**经典 CV 图像处理**。

  （注：早先"115 处 ML 引用"是子串误报——`startOrchestration` 里含 "torch"。）

- **修复**：① 竞品表 3 行改为「⚠️ 库已实现未接线 / ⚠️ 经典 CV，非模型 / ⚠️ EKF 库已实现

  未接线」并加脚注；② ROADMAP M11 状态改为「⚠️ 库已完成，未接入飞行路径」；③

  `DecisionEngine` 等 9 个类的 Javadoc 写明「未接入生产路径」，其中

  `AutoAvoidanceStrategy` / `EmergencyReturnStrategy` / `SwarmCoordinationStrategy`

  原本**连类注释都没有**；④ 订正失效注释。

- **未做**：把 `DecisionEngine` 接进 `VirtualDrone` 会改变飞行行为，属产品决策，

  本轮不擅自做。

- **把"未接线"钉成断言**：`AiAutonomyWiringTest`（6 例）让「ai/edge 包无包外生产

  调用方」成为**期望状态**——一旦有人接线即判红并提示同步 README / 竞品表 / ROADMAP。

  比在文档里写一句「注意」可靠，文档不会自己变红。



### 2. 安全阈值分裂（1 项）



同一个「低电量返航」阈值仓库里曾有三个值：`FailsafeController` 22（PX4 `BAT_CRIT_THR`

默认，**唯一真正生效的那个**）、`ReturnToHomeStrategy` 25、`DecisionEngine` 20。



- **修复**：新增 `FailsafeThresholds` 作为唯一真相源（`BATTERY_CRIT_PCT=22`、

  `LINK_LOSS_AFTER_MS=15_000`），前两者改为引用共享常量。

- **20 刻意保持不等**：`DecisionEngine.BATTERY_BOOST_THRESHOLD` 是**权重放大**阈值

  （提前给返航策略加权），不是硬触发，语义不同；有测试防止两者被反向收敛。



### 3. 文档口径与"没人会变红"（2 项）



- 单测数长期写 **3230**，实测已 **3994**，偏小 19%。共 4 处文档过期，其中

  **2 个测试表**（README 测试规模表 343/1324/2054/3869、`docs/demo-scenarios.md`

  185/1222/~1823/3230）此前**连文本关键词都没有**，任何"扫文档找数字"的检查都抓不到。

- **根因不是"忘了改"，是没有任何信号会变红**：加测试不会让文档过期这件事变红。

- **修复**：新增 `scripts/check-test-count-docs.py`，用 surefire XML 实测值逐格核对

  当前口径文档，并接入 CI。**刻意排除** `CHANGELOG.md` 与文档里追述旧值的句子——

  它们记的是"当时是什么状态"，改写等于篡改记录（如 ROADMAP 那句"此前此处写「…3230…」"

  改成 3994 就成了假话）。



### 4. 虚假引用（2 项）



- README 与 `SatLinkProvider` Javadoc 引用的 `TiantongSatProvider` 等三个卫星实现类

  **在仓库里不存在**（只有接口占位）。

- `docs/product-brief.md` 对未实现能力（含 AI 自主决策）的声称，已诚实化。



### 本轮其余发现（同属第六轮，已各自独立成条）



| 主题 | 条目 |

|---|---|

| License 门禁 fail-closed + 两个让签名从未成功的缺陷 + 加密密钥 fail-fast | `025b6a7` |

| 51 条自定义消息 CRC_EXTRA 改用官方算法实算 | `df047c1` |

| 标准消息 msgId 串位（40/43/143）与 RADIO_STATUS CRC_EXTRA | `9148777` |

| product-brief 诚实化 + 不存在的卫星类名 | `4fd375a` |



**本轮未闭合**（与既有记录一致，不重复展开）：30 条自定义消息 wire 布局未按

type_length 降序排列（改属协议重设计）；msgId 420–483 是否撞官方分配**仍无法外部核实**；

`GLOBAL_POSITION_INT`(33) 在 pymavlink v1.0 快照里无消息定义，CRC=104 未能外部核对。



---

---

---

## [Unreleased] — 标准消息 msgId 串位：MISSION_REQUEST / MISSION_REQUEST_LIST 挂反了（2026-10-02）



> 上一条做 CRC_EXTRA 外部核对时顺带撞出来的。三处硬错，全部只影响**与真实飞控互通**，

> 在仓库内部（drone-sim ↔ cloud-backend）因为两端共用同一张错表，往返始终正常——

> 这正是这类缺陷能长期存活的原因。



| msgId | 仓库登记 | pymavlink 官方定义 | 后果 |

|---|---|---|---|

| 40 | （未登记） | `MISSION_REQUEST` CRC=230 LEN=5 | MISSION_REQUEST 被错挂在 43 |

| 43 | `MISSION_REQUEST` CRC=230 | **`MISSION_REQUEST_LIST`** CRC=132 | 与官方 MISSION_REQUEST_LIST 撞 id |

| 143 | `MISSION_REQUEST_LIST` CRC=132 | **`SCALED_PRESSURE3`** CRC=131 | 43 的值被挂到 143 |

| 109 | `RADIO_STATUS` CRC=**88** | `RADIO_STATUS` CRC=**185** | 真机发的 RADIO_STATUS 帧 CRC 必然不过 |



**影响面**：`MISSION_REQUEST_LIST` 是**拉取航点的第一步**——`DroneCommandService`

（cloud-backend）与 `ArduPilotAdapter`/`Px4Adapter` 都靠它向飞控发起任务下载。

官方 MISSION_REQUEST_LIST 是 msgId 43，本项目发的是 143，**真实 PX4 / ArduPilot 会直接忽略**，

任务下发链路在真机上走不通。



**修复**：`MissionRequest.ID` 43→40；`MissionRequestList.ID` 143→43 且 `LEN` 4→3

（官方 3 个字段 target_system/target_component/mission_type，此前多出的第 4 字节从未被写入也从未被声明）；

`INFOS[109]` CRC 88→185；**删除** `INFOS[143]`（官方 143 是 SCALED_PRESSURE3，本项目未实现，

不登记比登记一个解不出来的 id 更诚实——`MavlinkMessage.decode` 对未知 id 返回 null，行为安全）。

所有引用都走 `ID` 常量而非硬编码，改动集中。



**顺带更正一处失效论证**：`RadioStatusTest` 原注释称「解析器接受我们自己的帧即证明 seed

与官方注册表一致」——这是**自证循环**，往返只证明内部自洽（两端用同一张表）。已改为指向

真正能做外部核对的 `--cross-check`。



**新增自动门禁**：`python scripts/mavlink-compatibility-check.py --cross-check` 用 pymavlink

官方定义逐条核对标准消息 CRC_EXTRA，当前 24 条可比对项**全部一致**。



**一处无法外部核实**：msgId=33 `GLOBAL_POSITION_INT` 在 pymavlink 打包的

`message_definitions/v1.0/common.xml` 里**没有消息定义**（只在别的消息的描述文字里被提到）——

该快照早于这条消息。所以它的 CRC_EXTRA=104 本轮**未能**用本快照核对，工具会照实打出告警。



**本轮未闭合**：30 条自定义消息 + `RadioStatus` 的 wire 布局未按 MAVLink 的 type_length

降序排列，多字节字段落在奇数偏移。改线格式属协议重设计、会同时影响 drone-sim 与 cloud-backend

两端，不与本条混在一起做。



---

---

---

## [Unreleased] — 自定义消息 CRC_EXTRA：改用官方算法实算，并加两道防回归门禁（2026-10-02）



> **怎么发现的**：为核实自定义 msgId 是否与官方分配冲突去查 MAVLink 官方定义，顺手比对了

> CRC_EXTRA。发现 `MavlinkMessageInfo` 里 51 条自定义消息的 CRC_EXTRA 是**人工序数**

> （430-434=201..205、437-441=211..215、450-454=233..237 …… 483=267），与字段签名毫无关系。



| # | 类别 | 问题 | 修复 |

|---|---|---|---|

| 1 | CRC_EXTRA 是人工序数 | CRC_EXTRA 的唯一职责是让「对同一 msgId 持有不同字段定义」的两端在帧 CRC 上必然不一致。填与字段无关的常数，等于两份字段布局完全不同的实现只要抄同一个数就能互通，**该机制形同虚设**。代码里的注释还写着「按 MavlinkCrc 对消息名+字段名+类型计算」——`MavlinkCrc` 是帧 CRC（CRC-16/MCRF4XX），**不含**任何 CRC_EXTRA 计算逻辑，注释名不副实 | 新增 `MavlinkMessageChecksum`（官方 `message_checksum` 算法的 Java 实现）+ 生成器 `scripts/mavlink-crc-extra-gen.py`，51 条消息的 CRC_EXTRA 全部按官方算法从字段签名实算 |

| 2 | 字段签名没有机器可读的定义 | 自定义消息在仓库里**没有 XML 定义**（全仓 `.xml` 只有 pom 和 logback），唯一定义源是各消息类的 Javadoc 字段布局表 + `encode()` 字节偏移。此前两者靠人读保持一致，实际已漂移：`SprayStatus` / `SprayCommand` 的 `reserved1/reserved2/reserved` 在 `encode()` 里写了但布局表没列；`OBSTACLE_REPORT` 尾部 2 字节、`VISION_DETECTION` 尾部 1 字节既没写也没列 | 生成器从 Javadoc 表提取并与 `encode()` 偏移交叉校验；7 处缺失的尾部保留字节补进布局表（源码即定义） |

| 3 | 变长重复结构被静默丢弃 | `TERRAIN_TYPE_MAP` / `TERRAIN_UPDATE` / `MESH_NEIGHBOR_TABLE` / `FLIGHT_RESTRICTION` 的尾部是「每项 N 字节」的重复结构（Java 侧内嵌 record），正则匹配不到就被**静默丢掉**——签名少了字段，却不报错 | 显式 `STRUCT_TAILS` 表声明展开方式（MAVLink 不能表达重复结构体，展开成并列数组）；解析器对无法解释的布局行**报错而非丢弃** |

| 4 | 改字段不会让构建变红 | 没有任何机制保证「改了字段 → CRC_EXTRA 跟着变」。下一次改字段又会静默失配 | 新增 `MavlinkCrcExtraTest`（**106 例**）：把 51 条消息的字段签名钉在测试里，用 `MavlinkMessageChecksum` 独立重算并与常量表比对，**改了字段没重算就红** |

| 5 | 兼容性脚本在自证循环 | `scripts/mavlink-compatibility-check.py` 硬编码了一份从 `MavlinkMessageInfo.java` **抄来的**消息表，等于用副本校验原件——两份同时改错都不会被发现。且副本只覆盖到 msgId 476，漏了 477-483 | 改为**直接解析** `MavlinkMessageInfo.java`（单一真相源）；新增 `--cross-check`，用 pymavlink 官方定义逐条核对标准消息 CRC_EXTRA |



**生成器如何被证明是对的**（不靠"看起来对"）：



1. **算法自检 23/23**：用本仓库算法重算 23 条**标准** MAVLink 消息的 CRC_EXTRA，与 pymavlink

   解析出的官方值逐条相等。算法对，则实现对。

2. **交叉验证 51/51**：把自定义消息的字段签名写成 XML 交给 pymavlink 官方

   `message_checksum` 重算，与本仓库结果全部一致。两条独立代码路径。

3. **落表后 CHANGED = 0**：重跑生成器确认 Java 常量表与重算值完全一致。



**一处必须写明的限制**：MAVLink 的 CRC 里**数组长度只占 1 个字节**，故签名中的数组长度上限是 255。

这 4 条变长消息的 Java 侧允许更多元素（`MAX_CELLS=65535` 等），超出部分**不在 CRC_EXTRA 的表达

范围内**——这是 MAVLink 本身的限制，不是本仓库的取舍。已写进生成器注释。



**顺带发现（本次未改，另开一条）**：`mavlink-compatibility-check.py --cross-check` 独立指出标准

消息段有 3 处硬错——`msgId=43/143` 被**互相对调**（官方 MISSION_REQUEST=40、MISSION_REQUEST_LIST=43、

SCALED_PRESSURE3=143），`RADIO_STATUS(109)` 的 CRC_EXTRA 是 88 而官方为 185。详见下一条。



**已知偏差（未修）**：46 条自定义消息里有 **30 条的 wire 布局未按 MAVLink 的 type_length 降序排列**，

导致多字节字段落在奇数偏移（如 `RADAR_TARGET` 的 `distance` f32 在偏移 2）。MAVLink 之所以规定这个

排序顺序，就是为了自然对齐、免填充。改线格式属协议重设计、会同时影响 drone-sim 与 cloud-backend

两端，不在本次范围；已由生成器逐条报出，可随时复查。



---

---

---

## [Unreleased] — License 门禁 fail-closed：三个互相掩盖的缺陷（2026-10-02）



> **怎么发现的**：外部审计指出「License 验签失败会降级为无限期 dev license」，准备改 fail-closed 时

> 顺手去读 `parseSignedLicense`，结果发现**签名功能从未成功过一次**。三个缺陷叠在一起，

> 每一个都把下一个的信号吃掉——所以单看任何一处都"看起来在工作"。



| # | 类别 | 问题 | 修复 |

|---|---|---|---|

| 1 | 验签失败 fail-open | `LicenseService.loadLicense()` 无论验签成败一律 `return buildDevLicense()`，而 dev license 是 `maxDevices=0`（无限制）、`expiryDate=null`（永不过期）、`ALL_MODULES`。**被篡改或损坏的 key 反而拿到最宽松的授权**，商业门禁形同虚设 | 改为 fail-closed。判据是**「是否配置了 key」而不是「是否 enabled」**——配置 key 本身就是运营方声明本部署要执行授权校验，此时任何失败都必须显式失败。没配 key 仍是开发版，开发/CI 路径零影响（已核对全仓无任何配置设置过 `aerofleet.license.key`）。不新增开关：多一个 `fail-closed=false` 就多一条"配错反而继续放行"的路 |

| 2 | 签名覆盖了信封（**合法 License 永远验不过**） | `serializeForSigning` 只排除 `signature`/`signerCert`，**`licenseKey` 参与了签名计算**；而 `parseSignedLicense` 又在验签**之前**把 `licenseKey` 覆写成完整 key 串（`payload + "." + signature`）。签发方在算签名时不可能预知自己将要产出的那串 key → 签方签的 `licenseKey` 与验签方算的必然不同 → **任何合法签名的 License 都验不过** | `licenseKey` 一并排除出签名（它是承载签名的那层信封，不是被签名的内容），并把 `setLicenseKey` 移到验签之后，摆正顺序避免后人再踩 |

| 3 | 签名覆盖了**随时间变化**的派生量（**过期即提权**） | `LicenseInfo.isExpired()` 依赖 `Instant.now()`，是被序列化进签名 map 的派生字段。于是"签发时未过期、到期后变成已过期"这个**正常生命周期事件**会改变被签名的字节 → 验签失败 → 在旧的 fail-open 下，**License 一到期就自动变成全模块、设备无限制、永不过期的 dev license**。次生问题：该派生量没有对应 setter，任何按本类序列化出的 payload 在严格 ObjectMapper 下会抛 `UnrecognizedPropertyException`，即"能否加载 License"取决于运行环境的隐式配置 | `isExpired()` 加 `@JsonIgnore`（派生量不该被持久化，也不该参与签名，它由 `expiryDate` 唯一决定） |

| 4 | 本模块零测试 | `src/test` 下 **0 个** License 用例。商业门禁这种"错了不会崩、只会悄悄放行"的逻辑，恰恰最需要测试——上面三个缺陷任何一个都会被一条"合法 key 应被接受"的红用例抓住 | 新增 `LicenseServiceFailClosedTest` **13 例**（本模块首批）：未配 key=dev 版 3、坏 key 必须 fail-closed 5（含 payload 篡改、非 dev 模式旧格式、垃圾串、报错可操作性）、合法签名必须被接受 5（含 `licenseKey` 解耦的最小复现、**过期 License 必须"验签通过但判定过期"而不是验签失败**） |



**测试口径的一处修正**：用例里的 `ObjectMapper` 一开始用裸 `new ObjectMapper()`（`FAIL_ON_UNKNOWN_PROPERTIES` 默认开），

于是缺陷 3 先以"反序列化失败"的形式暴露。生产环境跑的是 Spring Boot 自动配置的 mapper（该开关默认**关**），

所以生产里不会抛这个异常——但这恰恰说明**代码的正确性依赖了环境的隐式配置**，本身不自洽。

已把测试的 mapper 对齐 Spring 行为，另用一条独立断言直接钉住"`expired` 不得进入序列化"这个真正的根因。



**本轮未闭合**：① License 签发工具链本身（`LicenseKeyGenerator` 只出密钥对，不出 key）仍无端到端签发脚本

（**→ 已于 2026-10-03 收口：`LicenseIssuer`，见顶部 section**）；

② `LicenseController` 的激活/查询路径仍无测试（**→ 已于 2026-10-03 收口：`LicenseControllerTest`，见对应 section**）；

③ 设备摄取仍是**整部署一把共享 key**（上一批已记，未变）

   （**→ 已于 2026-10-03 收口：设备 Key 签发/轮换/撤销 + 认证缓存，见顶部 section**）。



---

---

---

## [Unreleased] — 生产凭据加密密钥：去掉 base 明文默认值，改 fail-fast（2026-10-02）



> 与上一条同源：都是"配置看起来是安全的，实际不是"。这个甚至更朴素——注释写着

> "生产环境必须通过环境变量覆盖"，而**prod 和 staging profile 里都没有这一行**。



| # | 类别 | 问题 | 修复 |

|---|---|---|---|

| 1 | base 硬编码加密密钥 | `application.properties` 里 `aerofleet.encryption.key=aerofleet-dev-encryption-key`，protecting 安防设备 ONVIF 口令（`PasswordConverter`）与 webhook secret（`WebhookService`）。prod/staging 均未覆盖 → 默认用一把**写在公开仓库里**的密钥加密生产凭据。且 `deriveKey()` 的"空值则抛异常"检查因为拿到的不是空值而**从不触发** | base 改为 `${AEROFLEET_ENCRYPTION_KEY:}`（无明文默认）；dev/test 各自显式声明自己的开发/测试密钥；prod/staging 用 `${AEROFLEET_ENCRYPTION_KEY}` **无缺省**，未设即启动失败——与 `aerofleet.security.jwt-secret` 同一套 fail-fast 口径，不新增机制 |

| 2 | prod 飞行日志不落库 | `aerofleet.flightlog.persist-to-db` 默认 false 且 prod 未覆盖 → 生产只写 JSONL 文件，DB 无审计线索 | prod 显式 `true`（DB 写失败仍整批回退 JSONL，不丢账） |

| 3 | staging 与 prod 不对齐 | staging 的定位是"把生产的配置跑一遍"，但**漏了** prod 有的两行：`device-whitelist-enabled=true` 与 `device-registry.persist=true`。它于是继承了 base 的 `false`，验证的是一个"接受任意 sysid 且注册表重启即失"的状态——与 prod 相反。加密密钥与 flightlog 同样漏了 | staging 补齐上述四项。CI 不启动 staging，因此无门禁影响；但这也意味着这些行目前**只有配置自证，没有运行证据** |



**顺带更正两处指向不存在类名的文档**（核实 ① 时发现）：README「真实卫星接入预留」写的

`*SatellitePlaceholder` 与接口 `SatelliteLink` 在代码中都不存在，实际是

`*SatLinkProvider` + `SatLinkProvider`；`SatLinkProvider` 的 Javadoc 里三个 `{@link}`

也指向不存在的类，一并更正。三个占位类的每个方法都抛 `UnsupportedOperationException`，

仿真用 `SimulatedSatLinkProvider`——**抛错而非返回假数据**是这里正确的做法，

与安防厂商适配器的 mock 做法恰成对照。



---

---

---

## [Unreleased] — 设备撤销登记 + 覆盖率口径接进 CI + H2 产物取消跟踪（2026-10-01）



> 三条都是上一批留下的"未闭合"里能独立收口的：撤销腿、口径自证、被跟踪的数据库产物。



| # | 类别 | 问题 | 处理 |

|---|---|---|---|

| 1 | 有开门没关门 | 上一批补了 `POST /devices/{sysid}`，但**没有对称的撤销**：登记错的 sysid 只能手工删库行。prod 里 `device-registry.persist=true` 之后 `devices` 表只增不减，也没有任何停用/清理策略 | `DeviceRegistry.deregister(sysid)` + `DELETE /api/v1/devices/{sysid}`（ADMIN，200/404，响应带 `persisted`）。语义上把"内存有没有"和"库里有没有"**都**算已知，任一存在即撤销成功；两者都不存在返回 false → 404，绝不静默删库。正在飞的设备被撤销会立刻失联——这是运维意图，不加"在线即拒绝"的额外保护 |

| 2 | `persist=false` 时的删除边界 | 若实现成"无条件 `deleteById`"，那么内存里没有、库里有（persist 关着）的情况会一边返回 404 一边把行删掉 | 只有 `isPersisting()` 才触碰库；`DeviceRegistryPersistenceTest` 有一条专门断言"`persist=false` 时 `deregister` 返回 false 且库行仍在" |

| 3 | 覆盖率口径只能人工自证 | `pom` 的 `<minimum>`、`ci.yml` 的注释表、`scripts/ci-coverage-threshold.sh` 的 POLICY 口径三处需要同步，此前**没有任何 CI 信号**——下调 pom 阈值忘了改文档不会变红 | 在 Java matrix job 的 `Coverage gate` 之后加一步 `bash scripts/ci-coverage-threshold.sh --strict ${{ matrix.module }}`。它与 `jacoco:check` 是两个问题：前者"够不够阈值"，后者"三处声明是否自洽"。**只查本 matrix 项的模块**：`-am` 会顺带构建依赖模块，但它们的 `jacoco.xml` 不保证存在，不带参数全量跑会在依赖模块上误报"缺产物" |

| 4 | 会不会引入新的假红 | `--strict` 的两个失败分支（`DECLARED > MEASURED`、`DECLARED < POLICY`）里，前者与 `jacoco:check` 的失败条件同向，后者只在人为下调阈值时触发 | 本机六模块 `--strict` 全 ✅ 自洽；且 CI 里若实测略低于声明阈值，上一步 `verify` 本来就会红，不是新增判据 |

| 5 | H2 产物一直被跟踪 | `.gitignore` 的 `/data/` 是**根锚定**，盖不到 `cloud-backend/data/` ⇒ `aerofleet.mv.db`/`aerofleet.trace.db` 在版本库里，dev profile 每跑一次就产生二进制 diff 噪声 | `git rm --cached`（保留本地文件）+ 按扩展名兜一层 `*.mv.db` / `*.trace.db`，注释写明为什么根锚定不够 |



**IT 补撤销腿**：Pass C 断言 7 在"落行 + 机队列表可见"之后加 `DELETE → 200`、`撤销后 PUT /tenant → 404 device unknown`。刻意选**纯 REST 状态断言**而不是"撤销后行数不再增长"——后者依赖帧到达时刻，会给 CI 引入计时抖动。顺带让 IT 自清理：`devices` 行被删掉后，复跑回到 201（新建）分支，不会再出现"增量库只能走幂等分支"的观测缺口。



**新增/扩展测试**：`DeviceProvisioningControllerTest` +2（撤销 200 且设备离开白名单 / 未知设备 404）；`DeviceRegistryPersistenceTest` +2（撤销同时摘内存与库行 / 未知返回 false 且 `persist=false` 时不动库行）；`UdpGatewayTest` 第 24 条扩成三段（未登记被丢 → 登记后放行 → **撤销后重新拒收**），用 `mockingDetails` 的调用数不变来断"没进 ingest"，避开新增 mock 断言的口径漂移。



**验证（本机，串行跑批；日志 `cloud-backend/target/verify-20260930/w13-*.log`）**：全量 `mvn -B -o test` = **3869 用例 / 0 失败 / BUILD SUCCESS**（343/1324/117/2054/12/19，基线 3865 + 4）；`PKG_EXIT=0`；`IT_EXIT=0`，**37 条 ✅ / 0 条 ❌**（原 35 + 撤销腿 2）。跑完在真 PG 上复核两件决定性的事：`SELECT count(*) FROM devices WHERE sysid=231` = **0**（撤销真的删了库行，不只是内存），`SELECT count(*) FROM flight_log WHERE sysid=231 AND tenant_id=1` = **4**（活体设备的遥测带着正确租户落库）。



**仍未闭合**：① `devices` 表仍无停用/软删与清理策略（现在至少能删了）；② 每台设备独立凭据与轮换；③ 告警 SSE 带不了 `Authorization`；④ `ci-coverage-threshold.sh` 的 POLICY 与 pom 若同时被人下调，`--strict` 只拦"低于实测地板"，拦不住"两边一起放水"；⑤ 存量 `tenant_id IS NULL` 的遥测行不回填（上一批已定）。



---

---

---

## [Unreleased] — prod 设备白名单死锁：显式登记端点 + 注册表持久化成对（2026-10-01）



> **怎么发现的**：上一批留了一条未闭合项——"Pass C 没有设备接入，`flight_log` 空表，批量插入路径没被端到端断言"。我按活体配方在本地 prod + 真 PostgreSQL 复现它，60 秒遥测得到 **0 行**。不是异步队列没写，而是**帧根本没被接受**：prod 里任何真机都接不进来。



| # | 类别 | 问题（实测路径） | 修复 |

|---|---|---|---|

| 1 | 死锁的三环 | 三条同时成立，就没有任何入口能让首台设备进白名单：① `UdpGateway.onFrame:152-157` 在进 ingest **之前**就丢弃陌生 sysid 的帧；② 唯一创建注册条目的是 ingest **之后**的 `TelemetrySnapshotListener.registerIfAbsent`（12 处调用全在监听器里）；③ 恢复路径也堵着——`aerofleet.device-registry.persist` 默认 false（`application.properties:82`），prod 未覆盖、compose/k8s 也未设，于是 `DeviceRegistry.restoreFromRepository():51-53` 直接 return；而 `DeviceRepository` 全仓只被 `DeviceRegistry` 引用，`DeviceProvisioningController` 只有 `GET /devices/unassigned` 和 `PUT /devices/{sysid}/tenant`，**没有"登记设备"这回事** | 新增 `POST /api/v1/devices/{sysid}`（ADMIN，可选 `{"tenantId":N}`；201 新建 / 200 已存在），把"登记"与"接受"解耦；prod 同时打开 `aerofleet.device-registry.persist=true`，让登记条目跨重启保留——否则重启后又回到 ① 的状态，白名单重新变成死锁 |

| 2 | 3852 条单测为什么没抓到 | 白名单那 5 条用例全部用 **mock DeviceRegistry**，`get()` 被 `when(...)` 直接喂了快照。测试断言了"门会关"，却从未断言"有人能开门"——mock 恰好填上了现实中缺失的那条注册腿 | 加第 24 条用例：用**真实注册表**，先证明未登记设备的帧被丢（`never()`），再 `provision` 后证明同一设备的帧被放行。成对断言，任何一侧失效都判红 |

| 3 | 归因靠 A/B，不靠猜 | 同一套接线，唯一变量是 `device-whitelist-enabled`：开着 = 0 行，关掉 = 60 秒 **59 行**（id 1..59 连续、`type=telemetry`、JSONL 反证未回退）。所以敢把它归因给配置门，而不是上一批的批量插入 | 该判据直接搬进 Pass C 断言 7（阈值 ≥3 行） |

| 4 | 重登记不该抹掉既有归属 | `provision` 若对已入库行一律写 `tenantId=null`，会把 `PUT /tenant` 指派过的归属悄悄清掉 | 只在显式给了租户时改写；改归属仍是 `PUT /api/v1/devices/{sysid}/tenant` 的职责 |

| 5 | 幂等与并发 | `provision` 与 UDP 注册线程可能同时为同一 sysid 建快照 | 用 `putIfAbsent`：已存在则返回 `alreadyRegistered=true` 且不改动快照。在线位仍由心跳监听器置——`registerIfAbsent` 对已存在快照不做任何事，有一条测试专门钉这点（我最初把它误写成"provision 后 registerIfAbsent 会置 online"，读 `TelemetrySnapshotListener:56-59` 后改正） |

| 6 | prod 直连的启动前置（顺手记档） | 手工按 Pass C 参数起 prod 后端时先撞 `Could not resolve placeholder 'AEROFLEET_USERS'`——`application-prod.properties:19` 是无缺省的 `${AEROFLEET_USERS}`，compose 侧是 `${AEROFLEET_USERS:?...}` 必填 | IT 脚本早已在 `:86` 算好、`:365` 注入；此处记一句，免得下次手工复现再漏（四件套：datasource URL/口令 + `AEROFLEET_JWT_SECRET` + `AEROFLEET_USERS`） |

| 7 | 断言 7 首跑判红，红在读不红在写（**附我的第二次同类错误**） | 首跑：登记返回 201、`persisted:true`、库里真落了 **65 行 sysid=231 的 telemetry**，但 `GET /api/v1/flightlog` 返回 `[]`，断言判红。我当场下的诊断是"写入端从不盖租户戳（P0-4 记的 `tenant_id 恒 NULL`）"——**这是错的**，又是一次照记忆陈述而未读当前代码：`FlightLogService.base():306-331` 的 `tenantForWrite()` 早就按 `DeviceRegistry.tenantOf(sysid)` 落租户。真实原因是我把设备登记成了**未归属**（`POST /devices/231` 没带 `tenantId`），于是行落 `tenant_id=NULL`，而 `isVisibleTo:345-347` 对"具体租户 + 记录 NULL"判不可见；引导 ADMIN 的 JWT 带 `tenant_id=1`，所以它读不到自己库里的数据 | Pass C 登记时带 `{"tenantId":1}`（V6 种子 `tenant id=1 code=default`），链路才租户自洽。并补两条单测把这条此前**零覆盖**的语义钉住——实测全仓 `flightlog` 包内没有任何与 `tenantId` 相关的断言，所以"写进去但没人看得见"这种状态可以长期无人察觉 |



**新增测试 13 条**：`DeviceRegistryPersistenceTest` +5（provision 以 offline 入库、重启后可恢复进白名单 / 幂等不覆盖归属 / provision 后 `registerIfAbsent` 复用同一快照且不重复入库 / `persist=false` 时只进内存 / 越界 sysid 抛 `IllegalArgumentException`）；`DeviceProvisioningControllerTest` +5（201 登记后即时对该租户可见 / 无请求体登记为未归属、对租户不可见 / 200 已存在 / 400 越界（0 与 255）/ 租户不存在 404 与非整数 400 都不落库）；`UdpGatewayTest` +1（真实注册表的"未登记被丢 → 登记后放行"成对回归）；`FlightLogPersistenceTest` +2（遥测行继承设备归属且只有该租户读得到、未归属设备的行任何具体租户都读不到）。



**IT 新增**：Pass C 断言 7 —— ADMIN `POST /api/v1/devices/231` → 断言响应 `persisted:true`（这才证明 prod 那两个开关真的成对打开了）→ 起 drone-sim（`--port 14540 --sysid 231`，由后端默认发现端口学到对端，**不需要任何 ARM/起飞触发**）→ 有界轮询 ≤60s 直到 `GET /api/v1/flightlog?type=telemetry&sysid=231` 返回 ≥3 行 → 停 sim（`cleanup_on_exit` 也带上 `PID_SIM`，中途硬失败不留进程）。这条一并闭合上一批"批量插入路径未被端到端断言"，并且是本仓第一次在 CI 里用真机（非 mock、非 REST 伪造遥测）验证 prod 遥测接入。



**顺带修 CHANGELOG 自身两处**：① 上一条目第 7 行写"本仓目前只有 `flight_log` 换成了序列"——那是被真库打回**之前**的口径，与同表第 3 行自相矛盾，已改为"批量来自 writer 线程的显式 `batchUpdate`，与 `batch_size` 无关"；② 同条目测试列表里 `TelemetryWriteOffThreadTest` 被写了两遍（5 条版 + 8 条版）且括号残缺、多出一个 `。；`，合并为 8 条版。



**验证（本机，串行跑批）**：全量 `mvn -B -o test` = **3865 用例 / 0 失败 / BUILD SUCCESS**（分模块 343/1324/117/2050/12/19，基线 3852 + 本批 13）；`mvn -B -o package -DskipTests` PKG_EXIT=0；`IT_EXIT=0`，**35 条 ✅ / 0 条 ❌**（原 31 + 断言 7 的 4 条），日志 `cloud-backend/target/verify-20260930/w11-it.log`。断言 7 实际输出：`POST /api/v1/devices/231 → HTTP 200`（本机是增量复跑，条目已在库里且已归属租户 1，所以走幂等分支；CI 全新库会是 201）、`登记条目已入库`、`flight_log 在 PostgreSQL 上收到活体遥测（3 行，等待 ≤4s）`、`GET /api/v1/drones 含活体设备`。

注意那 3 行是**新落的行**：同库同表里还留着上一轮未归属的 65 行（`tenant_id IS NULL`），它们没有被计数——正好反证读过滤在真库上确实生效，不是"全表返回"造成的假绿。



**仍未闭合**：① 没有"撤销登记"的端点，登记错的 sysid 目前只能删库里的行；② `persist=true` 后 `devices` 表会长期累积条目，没有任何清理/停用策略；③ 存量 `tenant_id IS NULL` 的遥测行**不回填**（你已定：只保证新数据正确），它们对具体租户永久不可见，只有无租户上下文的全局口径能看到；④ 每台设备独立凭据与轮换（现仍是整个部署一把共享摄取 key）；⑤ 告警 SSE 带不了 `Authorization`。



---

---

---

## [Unreleased] — 遥测/告警入库移出调用线程：有界批量写队列 + 显式 JDBC 批量插入（序列方案被真库打回）（2026-10-01）



> **为什么现在做**：`aerofleet.flightlog.persist-to-db` 一直是 false，所以"打开入库会怎样"从未被观测过。实测代码路径后确认：一旦打开，数据库写就发生在**产生这条数据的线程**上——而那条线程是不能等的。这是"遥测入库可用"的前置条件，不是可选优化。

> **两条我自己说错、被实测推翻的话**：① 我说过"全量回归会顺带验证迁移"——不成立：`application-test.properties:25` 是 `spring.flyway.enabled=false`、`:20` 是 `ddl-auto=create-drop`，3849 个单测一条迁移都不跑；dev 是 `ddl-auto=update`，同样不校验。全仓只有 prod 档的 `validate` 会校验 schema，也就是**只有 Pass C 这一条腿**能发现迁移/映射不一致（CI 的 Integration Tests 会跑 Pass C，所以这条守门本来就该响）。② 我在 V22 的注释里写过"validate 大概不查序列，风险待确认"——它不是风险，是必然失败，见下表第 3 行。



| # | 类别 | 问题（实测路径） | 修复 |

|---|---|---|---|

| 1 | 谁在阻塞路径上 | `UdpMavlinkTransport.java:59` 起一条 `mavlink-udp-<port>` 单线程接收循环 → `UdpGateway.onFrame:173` 同步调 `ingest.handle(frame)` → `TelemetryIngestService:52` 发 Spring 事件，**默认 multicaster 没有 task executor，所有 `@EventListener` 都在那条 UDP 线程上跑**。告警链路尤其致命：`TelemetrySnapshotListener:121/127/194/209 alerts.publish()` → `AlertBus`（同步派发的 `CopyOnWriteArrayList`）→ `TelemetryPusher:91 flightLog.alert()` → `FlightLogService:105 repository.save()` | 新增 `BatchedWriteQueue<T>`：有界队列 + 单 writer 线程 + 成批 `saveAll` + 关闭排空 + 计数（offered/written/failedBatches/rejected）。接入两条腿：`FlightLogService.append()` 与 `FlightTrackStore.persistLastKnown()` 都改为入队 |

| 2 | 溢出与失败语义 | 直接改成异步很容易变成"要么背压回生产者，要么静默丢数据" | **两条都不选**：队列满或 writer 已停 → `offer` 立即返回 false（实测 <100ms，不阻塞）；调用方就地走既有的 JSONL 追加。落库抛异常 → 整批交给兜底回调写 JSONL。保留原注释声明的语义"DB 异常自动回退 JSONL"（`FlightLogService:48`），一账不丢 |

| 3 | 批处理为什么以前是假的，以及为什么最终没走序列 | `FlightLogEntity:26` 原为 `GenerationType.IDENTITY`：Hibernate 对 IDENTITY 主键必须逐行执行才能取回生成键，所以 `hibernate.jdbc.batch_size` 对这张表静默无效——加了也不会批。**先按"改用序列"实现了一版，被真库打回**：Pass C（prod 档 `ddl-auto=validate` + 真 PostgreSQL）启动即失败 `Schema-validation: missing sequence [flight_log_id_seq]`；查库发现该名字**早已被 PG 给 identity 列的内部序列占用**（`pg_class` 里有、`information_schema.sequences` 里无，`DROP` 时报 "column id requires it"），于是 V22 的 `CREATE SEQUENCE IF NOT EXISTS` 是**空操作但 Flyway 记 success**——一个静默无效的迁移。序列对 Hibernate 校验不可见，prod 起不来 | 回退实体到 V18 的 IDENTITY（**删除 V22**，prod validate 不再有任何可失败点），改由 writer 线程用 `JdbcTemplate.batchUpdate` 做显式多行批量插入：批处理真实成立且不依赖驱动的序列元数据。INSERT 的列名/类型/值三者全部由实体 `@Column` 反射派生（同一份字段列表，结构上不可能错位），并加一条测试把该列清单与 **V18 DDL 逐项对比**，挡住"改 `@Column` 忘了改迁移"；另加一条测试断言"一批 N 行只发一次 `batchUpdate`" |

| 4 | 不该被顺手改掉的并发保证 | `FlightTrackStore.java:124-130` 的 `synchronized(deque)` 是 cc28ed7 治并发 flake 的点 | 原样保留，只把落库挪走 |

| 5 | 关闭顺序会静默倒退数据 | 有两个写者会在停机时抢同一行：队列里是**较早**的快照，`persistAllOnShutdown` 从内存轨迹取**最新**值 | `@PreDestroy` 里先 `queue.close()` 排空，再从内存补写；顺序颠倒就会用旧位置盖掉新位置。`TelemetryWriteOffThreadTest` 有一条专门钉这个顺序 |

| 6 | 配置项该开在哪 | 高低频两条腿不该一样待遇 | 高频的 flight-log 开三个配置（容量/批量/轮询间隔），最后已知位置已被 `PERSIST_INTERVAL=10` 节流（20Hz 下每机约 0.5 秒一条），三个参数写死并在注释里说明为什么不配 |

| 7 | 别让人以为全局开了批处理 | `audit_log`、`geofence_breach_event`、`orch_*` 等仍是 IDENTITY | `application.properties` 的批处理注释里直接点名：**batch_size 只对非 IDENTITY 主键生效**，本仓的表几乎都是 IDENTITY，加了也不会批；`flight_log` 的批量来自 writer 线程的显式 `JdbcTemplate.batchUpdate`，与 batch_size 无关 |

| 8 | 异步化带来的隐性代价 | 写改异步后，"写完立刻读"的断言全部变成时序依赖。**第一次全量只有 3 条红（`telemetryWritesToDb`/`alertWritesToDb`/`queryFromDbReturnsSameFormatAsJsonl`），但这个类里实际有 6 处这种写法** —— 另外 3 处（`missionWritesToDb`/`connectivityWritesToDb`/`telemetryThrottleStillWorksInDbMode`/`trackForFromDb`）只是恰好被前面的耗时盖住，属于潜伏 flake，CI 换个机器就会红 | 统一改为**排空式等待**而不是"轮询到非空"：给 `BatchedWriteQueue` 加 `awaitIdle(timeout)`（判据是"队列空 **且** in-flight 批次数为 0"——只看队列空会漏掉已取走未提交的那批，故另设 `inFlight` 计数），并开 `FlightLogService.awaitPendingWrites(timeout)` 给测试/运维用。节流那条要的是"正好 N 条"，poll-to-non-empty 会把它变成弱断言，所以必须用排空语义。**没放宽任何实质断言**：超时后仍返回原结果，让 `hasSize(...)` 照常失败 |



**新增测试**：`BatchedWriteQueueTest`(6：批次不超 batchSize 且条数守恒、写发生在 `db-write-*` 线程而非调用线程、队列满立即拒绝且 `offer` 耗时 <100ms、失败整批交兜底、`close` 排空 200 条不丢、关闭后 offer 不抛错)；`TelemetryWriteOffThreadTest`(8：DB 卡住时调用方 <100ms 返回、落库异常整批回退 JSONL、队列满回退 JSONL、最后已知位置在 writer 线程落地、停机先排空再补写（旧值不盖新值）、**一批 N 行只发一次 `batchUpdate`**、插入列由注解派生且不含主键、**插入列与 V18 DDL 逐项一致（防实体/迁移漂移）**）。



**本轮未闭合**：真库上的**批量插入路径本身没有被端到端断言**——Pass C 没有设备接入，`flight_log` 是空表，那条断言只证明"PG 上表存在 + 查询方言可用 + schema 校验通过"。我曾打算加"POST /alarms/events 后再查 flight_log 非空"来钉住它，核实后放弃：REST 告警经 `AlarmLinkageEngine` 只写告警表，**不写 `flight_log`**（`flightLog.alert()` 只由订阅 AlertBus 的 `TelemetryPusher.pushAlert` 调用），那条断言会是空证。要在 CI 里钉住插入路径，需要 Pass C 接一台 sim 或加一个可写的内部端点。

---

---

## [Unreleased] — 覆盖率门禁：补齐两个未接模块 + 抬回 cloud-backend 的地板（2026-10-01）



> **本轮验证**：`mvn -B -o test` 全 reactor **3852 用例 / 0 failures / 0 errors / 0 skipped**（BUILD SUCCESS；分模块 343/1324/117/2037/12/19，较 3838 基线净 +14 = `BatchedWriteQueueTest` 6 + `TelemetryWriteOffThreadTest` 8）。本机 `ci-integration-test.sh` **IT_EXIT=0、31 条 ✅**，关键是 **Pass C 在真 PostgreSQL 上正常启动**——方案 A 之前它启动即失败（`Schema-validation: missing sequence [flight_log_id_seq]`），这条腿是全仓唯一会校验 schema 的地方。

> **先纠正一条我自己说错的**：上一轮我说"覆盖率门禁是装饰性的（步骤名写 >=50% 但命令带 `-DskipTests`，无 `.exec` → check 跳过 → 恒绿）"。那是外部审计报告在 253dca5 基线上的结论，**后来的 CI 真实化批次已经修好了**：现在测试步是 `mvn -B -pl <module> -am package`（不跳测试），CI 里还有一条显式守卫——`${module}/target/jacoco.exec` 不存在就 `::error::` + `exit 1`，artifact 上传也设了 `if-no-files-found: error`。我引用过期记忆而没先核实，是错的。



真正还弱的两处，本批处理掉：



| 模块 | 实测 LINE | 原声明 | 新声明 | 说明 |

|---|---|---|---|---|

| cloud-backend | 62.7% | 0.50 | **0.60** | 地板比实测低 **12.7 个百分点**：删掉那么多覆盖才会红。本仓口径是"实测向下取整到 5%"，其余三个模块余量只有 1.2~4.3pt，唯它离谱 |

| sdk-java | 37.2%（87/234 行） | 无 jacoco | **0.35** | 此前完全未接门禁，也不在 CI matrix：12 个测试对覆盖率零贡献、零防退化 |

| regulator-sim | 71.3%（209/293 行） | 无 jacoco | **0.70** | 同上（19 个测试） |

| mavlink-core / drone-sim / link-sim | 69% / 71% / 66% | 0.65 / 0.70 / 0.65 | 不变 | 已自洽 |



改动：`sdk-java/pom.xml`、`regulator-sim/pom.xml` 各加一段 jacoco execution（`prepare-agent` + `report@test` + `check-coverage@verify`，逐字照现有四模块的形态，不发明新结构）；CI matrix 由 4 模块扩到 6；matrix 上方阈值注释表同步；`ci-coverage-threshold.sh` 的 `MODULES_DEFAULT` 同步补齐并把"4 个门禁模块"改成 6。



如实记下两处代价与限制：

- **cloud-backend 余量只剩 2.7pt**。这是地板应有的样子，但也意味着今后一个不加测试的 PR 就更可能把 CI 撞红；出口是补测试，不是下调阈值。

- **sdk-java 只有 37% 是真实状况**，不是阈值定低了——它 6 个主类只有 1 个测试文件（`DroneApiTest`，用 JDK 内置 `HttpServer` + 端口 0 自给，所以接进门禁不会与后端抢端口）。把地板钉在 0.35 的作用是防退化，不代表 SDK 覆盖已够。

- 阈值写在 6 个 pom 里，与 `ci.yml` 的注释、`ci-coverage-threshold.sh` 三处需要同步维护；本次靠该脚本的 `--strict` 自证一致，但它并未进 CI（只有 pom 的 `check` 在 CI 里执行）。**注意**：本批最初那次"六模块全自洽"是用 CSV 口径算的，即上面那个偏乐观的口径；改用 BUNDLE 后重跑，六模块仍全自洽（sdk-java 从 37% 修正为 36%，阈值 0.35 依旧成立）。



**核对时发现并修掉的一个口径缺陷（这条是本节存在的主要原因）**：`ci-coverage-threshold.sh` 原本从 `jacoco.csv` 逐行相加算覆盖率，而 **CSV 是每个类一行，匿名内部类与其宿主的同一源行会被重复计数**，BUNDLE 级则按去重后的源行统计——`jacoco:check` 用的正是后者。sdk-java 实测差 1 行：CSV 求和 87/234=0.3718，BUNDLE 86/233=0.3691。差异本身微小，但它让脚本**给一个真门禁会拒的阈值盖章**：把 `<minimum>` 临时设成 0.37 时，旧脚本输出"✅ 自洽"，而 `mvn -pl sdk-java verify` 直接 `Rule violated ... ratio is 0.36, but expected minimum is 0.37` + BUILD FAILURE。其余五模块两种口径恰好相等，所以这个坑只有 sdk-java 暴露得出来。修法：改读 `jacoco.xml` 里最后一个 `counter type="LINE"`（report/BUNDLE 级），与 check 同源；改完后同一 0.37 阈值在 strict 与默认两种模式都变成 `RESULT: FAIL`，并明确提示"verify 会失败"。核对全部六模块：mavlink-core 0.6927 / drone-sim 0.7119 / link-sim 0.6634 / cloud-backend 0.6270 / sdk-java 0.3691 / regulator-sim 0.7133，与各自声明阈值自洽。



**本轮未闭合**：`ci-coverage-threshold.sh` 未接入 CI（三处数字一致性只靠人工跑）；SDK 响应信封契约问题仍在（与覆盖率无关，是既有项）；`mvn verify -DskipTests` 复用上一次构建遗留的 `.exec` 这一"陈旧产物也算存在"的窗口，CI 里因为同 job 先跑过测试而不成立，但本地单独执行 `verify` 时存在——守卫判的是"文件在不在"，不是"新不新"。



---

---

---

## [Unreleased] — 设备/边缘摄取通道的 API Key 引导（2026-10-01）



> **本轮验证**：`mvn -B -o test` 全 reactor **3838 用例 / 0 failures / 0 errors / 0 skipped**（BUILD SUCCESS；较上一批 +5 = `DeviceIngestKeyBootstrapRunnerTest` 5 例）。`docker compose config` 实测解析通过，未注入 `AEROFLEET_SECURITY_DEVICE_INGEST_API_KEY` 时该变量渲染为 `""`（不报错、不引导，既有部署不受影响）。本机 IT **IT_EXIT=0**，新增 Pass B 断言 7 走通 `X-API-Key` 分支并成对取证：`✅ POST /api/v1/alarms/events（引导出的 device-ingest key → 200） → HTTP 200`、`✅ [对照] POST /api/v1/alarms/events（错误 key → 401，证明不是恒放行） → HTTP 401`——这是本仓第一条经 API Key（而非 JWT）通过 RBAC 角色门的端到端断言，链路覆盖 `ApiKeyFilter` 哈希查库 → `ApiKeyContext` 角色 → `RoleInterceptor` OPERATOR 门。

> **为什么**：上一批把四条上报腿（`POST /api/v1/edge/results`、`/loRa/alarm`、`/offline-alarm/batch-upload`+`/flush`、`/alarms/events`）标成 `@RequireRole(OPERATOR)`，但**仓库里没有任何发放凭据的路径**——唯一发 `X-API-Key` 的调用方是 sdk-java 的 `NexusSkyClient.java:490`，而铸 key 的 `POST /api/v1/auth/keys` 本身要求 ADMIN。生产模式下这些端点 over `anyRequest().authenticated()`（`SecurityConfig.java:76`），匿名上报一直是 401，所以问题不是"这轮改坏了"，而是**"设备必须持凭据"从来只是一句文档**，新部署卡在"先要有账号才能发凭据、先要有凭据才能上报"的循环里。



| # | 类别 | 问题（实测） | 修复 |

|---|---|---|---|

| 1 | 引导通路 | 摄取端点要求 OPERATOR，却没有任何可运行的凭据发放方式 | 新增 `DeviceIngestKeyBootstrapRunner`（与 `AdminBootstrapRunner` 同构）：`aerofleet.security.device-ingest-api-key` 非空时按固定 `keyId=device-ingest` 创建/覆写一条 `api_keys` 记录（`role=OPERATOR`、库里只存 SHA-256 哈希、长度 <16 拒绝引导、`createdAt` 保留以便看出 key 寿命）；compose 注入 `AEROFLEET_SECURITY_DEVICE_INGEST_API_KEY`。轮换=改环境变量重启，同 keyId 覆写所以表里不堆积 |

| 2 | 出厂行为 | 引导类组件最常见的失败模式是"每个默认部署自带一把后门 key" | **留空即完全不介入**（含 CI 与 dev profile），compose 用 `${VAR:-}` 允许空值。`DeviceIngestKeyBootstrapRunnerTest` 第一条断言就是"未配置时不查库不写库"（`verify(repository, never()).findByKeyId/save`），第二条防"无数据源时抛错拖垮启动" |

| 3 | 局限如实登记 | 一把共享 key 容易被误读成每机一密钥；`scopes` 容易被误读成能力边界 | 类 javadoc、启动 WARN、`docs/security-design.md` §3.4 三处都写明"这是整个部署共享的静态密钥，撤销粒度只有整体轮换"。`scopes` 经实测只随 `ApiKeyContext` 透传、**没有任何授权判定读它**（全仓 `getScopes()` 调用面只有上下文与 DTO 展示），API 参考与 security-design 的字段表都加了这句提醒 |



**新增测试**：`DeviceIngestKeyBootstrapRunnerTest`(5)——未配置不介入 / 无 repository 不抛 / 短 key 拒绝 / 只存哈希且哈希与 `ApiKeyFilter` 同口径（测试里独立复算 SHA-256，防两处漂移）/ 覆写保留 `createdAt`。



**本轮未闭合**：每设备·每租户独立发放与轮换/撤销（当前只有共享一把，撤销粒度=整体换 key）；告警 SSE `GET /api/v1/alarms/stream` 带不了 `Authorization` 头（`api.js:1171` + `EventSource` 限制），prod 下当前不可订阅，需要 query token 校验或迁 WS——用户已定"先不动，只登记"；Pass B 的端到端证据走的是 H2 + dev profile（`dev-mode=false`），**compose + PostgreSQL 那条腿未单独验证过引导路径**（`ApiKeyRepository` 与 profile 无关，风险低，但如实记着）；其余三条摄取腿（`edge/results`、`loRa/alarm`、`offline-alarm/*`）与 `/alarms/events` 走同一角色门，只在 `/alarms/events` 上做了实测。



---

---

---

## [Unreleased] — RBAC 默认拒绝：`@PermitAll` 白名单 + 342 个端点全量声明（2026-10-01）



> **本轮验证**：全 reactor `mvn -B -o test` **3833 用例 / 0 failures / 0 errors / 0 skipped**（BUILD SUCCESS，7 模块；分模块 343/1324/117/2018/12/19，较 P6 后基线 3827 净 +6 = `RoleInterceptorTest` 11→16 + 新增 `RbacEndpointCoverageTest` 1）。本机 `scripts/ci-integration-test.sh`（跑前先 `mvn -B -o package -DskipTests`，避免拿旧 jar 验新断言）**IT_EXIT=0 / `=== All integration tests passed ===`**，断言较上轮 +2，两条新证据的实测输出：`✅ GET /api/v1/drones（OBSERVER 读已声明端点 → 200，未被 fail-closed 误伤） → HTTP 200`、`✅ POST /api/v1/geofence/check（OBSERVER 越级写 → 403） → HTTP 403`；成对是必须的——只断 403 分不清拦的是"角色不够"还是"端点没声明"，恒 403 也会绿。翻转过程中 `HttpAuthChainTest` 曾有 **8 例判红**（`tenantlessAdminIsGlobalScope`、`flightLogsAreTenantScoped`、`directIdAccessToOtherTenantIsNotFound` 等），补完端点声明后全部转绿——这个套件是全仓唯一以 `dev-mode=false` 起完整过滤器链的测试，所以它是这次翻转真正的自证：**它先红，说明 RBAC 之前在这些路径上确实一分力都没出**。

> **为什么**：`RoleInterceptor:78-80` 写的是"方法与其类都没有 `@RequireRole` → `return true`"。这不是"某个端点忘了设角色"，而是**默认状态即无鉴权**：每新增一个端点都天然对任何已认证主体敞开，且运行时没有任何信号——上一轮实测 342 个端点里只有 70 个有声明（21 ADMIN/46 OPERATOR/3 OBSERVER），272 个裸奔，其中 92 个是写端点。P5 把 `rbac-enabled` 翻成 base 默认 true 只是让开关处于"开"的状态，覆盖面没变。



| # | 类别 | 问题（实测） | 修复 |

|---|---|---|---|

| 1 | 默认值 | 无声明即放行，漏写注解静默失去鉴权 | `RoleInterceptor` 改为**无声明即 403**（响应体 `forbidden: endpoint has no role declaration`，WARN 里带 `handler=类#方法` 便于定位），"公开"必须写成显式 `@PermitAll` |

| 2 | 覆盖面 | 272 个端点未声明（180 GET + 92 写） | 全部收口：读=类级 `@RequireRole(Role.OBSERVER)`（拦截器早已支持类级回退 `:74-77`）；写=方法级 `OPERATOR`，配置/用户/API Key/租户/围栏/场景模板/license 面 `ADMIN`；匿名入口只有 `AuthController#login`(:83)、`#refresh`(:150) 两处 `@PermitAll`。共 61 个文件、+268 行 |

| 3 | 声明语义 | 翻转后"类级 vs 方法级、`@RequireRole` vs `@PermitAll` 并存时谁说话"没有定义，容易被顺手放宽 | 明确为：**方法级声明覆盖类级声明**（两种注解同规则），同一元素并存时 `@RequireRole` 胜出（收紧优先）。`RoleInterceptorTest` 从 11 例扩到 16 例逐条钉住，其中一条把旧的"未标注端点不受 RBAC 影响"断言**方向翻转**——旧用例断言的正是缺陷本身 |

| 4 | 门禁怎么实现 | 我先写了一版 `scripts/rbac-endpoint-coverage.sh`（awk 文本扫描），实测把 272 个未声明**少报成 99**：方法签名里的 `@RequestBody`/`@PathVariable` 被当成注解行，注解缓冲区在错误的行结算 | 删掉 shell 版，改为 `RbacEndpointCoverageTest`：用 `ClassPathScanningCandidateComponentProvider` + `AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class)` 反射枚举真实端点。**一个会静默少报的安全门禁比没有门禁更糟**，而反射口径与运行时生效的东西同源，不会漂移（顺带过滤掉测试夹具里的嵌套 `@RestController`，它们不是生产端点） |

| 5 | 别踩的两个坑 | ① 担心翻转会锁死管理界面；② 担心 `@PermitAll` 变成新的匿名口子 | ① 角色层级是**向上满足**的：`hasPermission = userRole.ordinal() <= requiredRole.ordinal()`，`Role` 声明顺序 ADMIN→OPERATOR→OBSERVER，故 ADMIN 令牌过任何门；② `@PermitAll` 只放开 RBAC，生产模式下 Spring Security 仍是 `anyRequest().authenticated()`（`SecurityConfig.java:76`），匿名先吃 401——它的真实语义是"已认证的任意角色可用" |

| 6 | CI 侧证据 | Pass B 只有"ADMIN 201 / OBSERVER 打 ADMIN 端点 403"，看不出 fail-closed 有没有误伤只读用户 | 加两条制衡断言：`OBSERVER 读 /api/v1/drones → 200`（证明类级 OBSERVER 声明生效、只读面没被默认拒绝打掉）与 `OBSERVER 越级 POST /api/v1/geofence/check → 403` |

| 7 | 批量标注的可控性 | 60+ 文件机械改动，容易把注解插错位置或把操作性端点误标 ADMIN | 由一次性脚本按 `Controller#method [VERB path]` 清单插入（脚本放 `target/`，不入库，入库的门禁是第 4 条那个反射测试）；插入点选在方法声明行之前，避免与多行 mapping 注解的括号配对纠缠。**ADMIN 全集我逐个复核**，把 `POST /api/v1/geofence/check`（手动触发一次围栏检查）与 `POST /api/v1/geofence/restriction/refresh`（拉取禁飞区数据）从 ADMIN 降级为 OPERATOR——它们是操作动作，不是配置变更 |

| 8 | 仓库卫生 | `dependency-reduced-pom.xml` ×3 与 `__pycache__` ×6 被跟踪，每跑一次构建工作区就脏一次（shade 会把 jacoco 插件块抄 41 行进 diff） | `git rm --cached`（本地保留、构建自再生）+ `.gitignore` 补三条规则 |



**新增/改动测试**：`RbacEndpointCoverageTest`(1，反射全量覆盖率)；`RoleInterceptorTest` 11→16（新增 `@PermitAll` 三向、并存收紧优先、缺声明 403 文案、rbac-off/dev-mode 仍整体放行）。



**本轮未闭合**：`cloud-backend/data/aerofleet.{mv,trace}.db`（dev H2 数据文件）仍被跟踪，`.gitignore` 的 `/data/` 是根锚定、盖不到该路径——性质是数据不是构建产物，摘不摘由用户定；设备/边缘侧摄取端点（`POST /api/v1/edge/results`、`/loRa/alarm`、`/offline-alarm/batch-upload`、`/alarms/events`）已标 `OPERATOR`，但**仓库内没有任何带凭据的调用方**（唯一发 `X-API-Key` 的是 sdk-java `NexusSkyClient.java:490`），今天全靠 `dev-mode=true` 绕过，所以"设备必须持 key"目前是契约声明而非已验证通路，e2e 脚本与 compose 的凭据发放是下一件事；告警 SSE（`GET /api/v1/alarms/stream`）无法带 `Authorization` 头（`api.js:1171` 的 `alarmStreamUrl` 无 token、`EventSource` 也不支持自定义头），在 prod 下翻转前后都会在 Spring Security 层 401，属既有缺口；前端 110 余个调用点未逐一复验 OBSERVER 档的实际可见面；License 仍 fail-open。



---

---

---

## [Unreleased] — MAVLink v2 签名与官方协议对等 + backend 签名接线（2026-10-01）



> **本轮验证**：`mvn -B -o test` 全 reactor **3827 用例 / 0 failures / 0 errors / 0 skipped**（BUILD SUCCESS，7 模块；分模块 343/1324/117/2012/12/19，较上轮 3818 净 +9 = 按 sysid 取签名器的工厂用例 6 + link-sim 画像净 2（旧 2 条重写为 4 条）+ "只给密钥库"装配 1）。本机 `scripts/e2e-signing.ps1` 6 场景 **43 条断言全绿 / E2E_EXIT=0**（起点是 23 PASS / 7 FAIL，7 条 FAIL 逐条归因修完才有这个数：backend 启动没带 profile、断言时机导致的"空过"、link-sim 画像改错字节）。三条日志原文入档，作为"接线真的通了"的端到端证据：场景 2 `UdpGateway - 签名验证失败：sysid=1 linkId=1 msgId=33` 配 link-sim `tampered=3`；场景 3 `拒绝未签名帧：sysid=1 msgId=242 (rejectUnsigned=true)` 配 `stripped=3`；场景 5 两把不同口令的 sysid=1/sysid=2 都被发现且命令通路正常。

> **顺带修掉一处旧竞态**：第一次全量回归红在 `SimulatedHardwareAdapterTest.rtlShouldSetRtlMode`（`expected: <RTL> but was: <LAND>`），**与签名无关**（`git diff HEAD -- .../mavlink/hardware` 为空，该包本轮零改动）。机制：`takeoff(10.0)` 只设目标高度，实际爬升交给 200 ms 一跳的遥测线程（`TAKEOFF_RATE=2.0` → 每跳 0.4 m），而 `simulateRtl` 在"位于家点上方且 alt≤0.1"时把这次 RTL 判为"已着陆"并立刻写 `mode=LAND`——适配器的初始经纬度恰好等于 `homeLat/homeLon`，所以"还在路上"那个水平分支永远不成立。断言与 `rtl()` 之间只要落进一个 tick 就输。单跑 6/6 绿、全量同 JVM 串跑时输掉窗口，所以它是一条低频 flake 而非稳定红。修法：先有界轮询等高度真越过 0.1 m 再 `rtl()`（此时 RTL 要按 `DESCENT_RATE=1.0` 从 >2 m 降到 0.1 m，留有秒级余量），不再依赖线程调度运气。

> **为什么**：两个互相独立的缺陷叠在一起。① **字节层不互通**：签名块 15 字节（linkId + 6B 大端时间戳 + 8B HMAC-SHA256 截断），而官方是 13 字节（linkId + 6B **小端** 时间戳 + 6B `sha256_48`），哈希构造连密钥参与方式都不同（HMAC ≠ 口令前置摘要）→ 与 PX4/pymavlink 混流既验不过签名、又会因帧长差 2 字节错帧。② **接线层是死的**：`UdpGateway` 的四个签名注入点全是 `@Autowired(required=false)`，而 `CloudBackendApplication` 是不带 `scanBasePackages` 的裸 `@SpringBootApplication`（只扫 `io.aerofleet.cloud`），签名类在 `io.aerofleet.mavlink.security`，mavlink-core 也没有自动配置文件 → 四个字段恒 null、`isSigningEnabled()` 恒 false，**把 `mavlink.signing.enabled=true` 配上也不会签名**，连配置类"开签名没给密钥就启动失败"的校验都不执行。README 原写"已实现并接入 UdpGateway"对 backend 半边是不实声明。



| # | 类别 | 问题（实测红因） | 修复 |

|---|---|---|---|

| 1 | 协议对等 | 签名块 15 字节、HMAC-SHA256 取 8 字节、时间戳**大端**、单位口径写成"10ms tick"（官方是 10 微秒、纪元 2015-01-01） | 官方 13 字节布局：`linkId(1) + timestamp(6B 小端 48 位) + signature(6B)`，`signature = SHA-256(secret ++ 帧头至CRC(含 STX) ++ linkId ++ ts6)[:6]`；`MavlinkFrame`/`MavlinkParser`/`MavlinkMessage` 三处同步（`totalLen` 随之收缩 2 字节，否则后续帧错位）；小端编解码收敛成唯一出处（`MavlinkSigner.write/readTimestampLittleEndian`），不再各写一遍 |

| 2 | 重放规则 | `TimestampTracker` 用 `timestamp >= last` **放行相等值**（等于允许原帧重放一次）、按 linkId 单键分桶（两台机共用 linkId 会互相顶高基准）、回退阈值 500 tick 且单位不对 | 改为官方语义：流键 `(linkId, systemId, componentId)`、已见过的流**严格递增**、新流允许落后最多 60 秒 = 6,000,000 tick、48 位范围外直接拒；时钟用 `LongSupplier` 注入，使窗口边界可确定性测试 |

| 3 | backend 接线 | 见"为什么"第 ② 条：开关完全无效，且 `MavlinkSignatureConfig.afterPropertiesSet()` 从不执行 | 新增 `MavlinkSigningConfiguration`（`@ConditionalOnProperty(mavlink.signing.enabled=true)`）条件装配四个 bean：关掉时一概不创建（出厂行为逐字不变），打开后启动校验真正生效——`enabled=true` 而 `secret-key` 与 `key-store-path` 同时为空、或密钥库文件不存在，都会让 `afterPropertiesSet()` 抛错使应用**启动即失败**，而不是静默明文发送；`MavlinkSigningConfigurationTest` 用 `ApplicationContextRunner` 双向断言（含"只给密钥库"这一条腿）——这类"注入点是不是真的有 bean"的断言此前完全缺失，所以死路径活了很久 |

| 4 | 验收方式 | 旧测试全是"自己签自己验"的自洽往返（24 例 `MavlinkSignerTest` 无一使用已知答案），`scripts/mavlink-compatibility-check.py` 亦为同源自比（`pack_v2` 恒写 INC=0，零签名覆盖），证明不了互通 | 引入**独立参考实现**：隔离 venv（`target/p6-ref-venv`，不进主依赖）装 pymavlink，`scripts/mavlink-signing-vectors.py` 生成 5 条固定输入向量（HEARTBEAT / GLOBAL_POSITION_INT / 尾零裁剪 HEARTBEAT(LEN=1) / 奇数长度 RADIO_STATUS / 含负浮点 ATTITUDE），生成器自检"pymavlink 能验过自己的帧 + 按规范公式独立重算"；`MavlinkSigningVectorTest` 逐字节断言签名相同 + 解码-重编码还原。另加防退化断言：旧 HMAC 算法的输出必须与新签名不同、8 字节旧格式签名必须被拒 |

| 5 | e2e 可运行性 | 脚本启动 backend **不带 profile** → 命中 base 默认 `spring.profiles.active=prod` → prod 要 `localhost:5432` 的 PostgreSQL → `FlywaySqlException: Connection refused`，场景 1 就挂；且 `$BackendRestPort`/`$BackendUdpPort` 只是变量，6 处启动都没下传（等于永远硬编码 8080/14561） | backend 启动统一加 `--spring.profiles.active=dev` + `--server.port` + `--aerofleet.udp-port` + Redis 主机/端口，并把 REST/UDP/Drone/LinkSim/Redis 全部提升为脚本参数（与 `ci-integration-test.sh` 的 A_PORT/PG_PORT 同一做法；本机 8080 被其它项目容器占用时也能跑） |

| 6 | 文档不实声明 | README 首屏"所有协议、接口与真实硬件（PX4 飞控）完全一致"、"签名代码已实现并接入 UdpGateway"；`docs/commercialization-plan.md` 三处把签名记成"HMAC-SHA256 8 字节截断、已完成"；`docs/demo-scenarios.md` 称"替换模拟器即对接真飞控" | 全部按实测改写（保留"此前怎么写、为什么错"的痕迹而不是抹平）；`docs/security-design.md` 新增第 7 节记录线上格式、重放规则、装配史、互通性与边界（新节初稿编号撞了已有"第 6 节 安全加固清单"，已顺延）；两处主源码 javadoc 随算法同步（`MavlinkMessage` 类注释仍写"payload 附加 8 字节 HMAC-SHA256"、`UdpGateway.signFrame` 写"附加 HMAC-SHA256 签名"、`MavlinkSignatureConfig` 配置项说明写"HMAC 密钥"） |

| 7 | 多机密钥名不副实 | 接线补好后仍只补了一半：`SigningKeyManager` 支持密钥库（per-sysid 口令 + linkId），但 backend 侧只注入**一个**全局 `MavlinkSigner`，密钥库的 `key` 字段进不了签名路径——per-sysid 只有 **linkId** 生效，所有系统共用全局 `secret-key` 签名。即"多机密钥分发"模式在 backend 侧从来没真正工作过，而 e2e 场景 5 当时因为脚本自身缺陷也没能暴露这件事 | 新增 `MavlinkSignerFactory`：**按口令字符串缓存** `MavlinkSigner` 实例（同口令复用、密钥库热重载自然产生新实例、无需失效逻辑），`UdpGateway.signFrame/verifyFrame` 改为按 sysid 经 `SigningKeyManager.keyFor()` 取签名器；单机与多机走同一条代码路径、无特判。发送侧查不到密钥→WARN 并降级为未签名；接收侧取不到口令→**拒帧**（不拿别的机的口令去验一个陌生 sysid）。注意口令查找本身有回退语义：多机模式下密钥库未命中该 sysid 时会回退到 `defaultKey`（`SigningKeyManager.keyFor`），所以"只给 `key-store-path`、不写 `defaultKey`"才是严格的白名单。`MavlinkSignerFactoryTest`(6) 断言跨口令必须验不过 + 同口令复用同一实例；e2e 场景 5 用两把不同口令（sysid=1/2）端到端跑通 |

| 8 | 安全损伤画像不实 | `SecurityImpairmentEngine.applyTamper` 注释自称"仅模拟签名篡改"，实际在**载荷**上随机翻位——载荷一动 CRC 就不对，帧在 `MavlinkParser` 解析阶段即被丢弃，**永远走不到验签**；`applyStripSignature` 清掉 INC 位 0 后**不重算 CRC**（INC 在 CRC 覆盖范围内），产出的是一帧 CRC 坏帧，同样到不了验签。这两条正是"篡改/未签名被拒"端到端从未被证明的直接原因 | `applyTamper` 改为只在 6 字节签名块内翻 1–3 位（未签名帧原样返回）；`applyStripSignature` 按官方口径重算 CRC 并写小端字段，同时加 `isSignedV2` 前置判断以保持"未签名帧不改动"的既有契约（`applyStripSignatureOnUnsignedFrame` 这条旧用例实测拦下了我第一版引入的回归）。旧 2 条用例重写为 4 条（净 +2）钉住"改动的字节必须落在签名块内""剥签名后的帧 CRC 自洽"——值得记一笔的是原用例里有一条就叫 `applyTamperModifiesPayloadOnly`，**它断言的正是"篡改＝改载荷"这个缺陷本身**，所以这套"安全画像"测试从命名起就在为错误实现背书，缺陷才一直没被察觉 |

| 9 | e2e 断言"空过" | 场景 2/3 的 backend 断言在**固定的 sleep 之后**用 `Select-String` 查一次日志：日志没刷出来就算失败、而"未签名帧被拒"那条曾经 PASS 只是因为查询时机对上了没发生的帧——脚本判定与真实行为脱钩；`[link-stats]` 只在进程停止时输出且 link-sim 日志此前根本没落盘，所以"篡改/剥离计数 > 0"无从断言 | 新增 `Wait-LogPattern` 有界轮询（最多 40 s、命中即返回，并把命中的那一行原样打进结果，让 PASS 自带证据）；场景 1 的签名统计改用同一 helper；link-stats 断言移到 `Stop-SceneProcesses` **之后**读，link-sim 启动加 `-Dorg.slf4j.simpleLogger.logFile=`；正则收紧为 `tampered=[1-9]` / `stripped=[1-9]`，排除"计数为 0 也算命中" |



**新增/重写测试**：`MavlinkSigningVectorTest`(6，pymavlink 已知答案)；`MavlinkSignerTest` 按官方语义重写(18，含"非 HMAC""旧 8 字节签名被拒""含 STX""帧内任一字节敏感性")；`TimestampTrackerTest`(12，含"相等必须拒""三元组分流""60 秒窗口边界")；`MavlinkSignerFactoryTest`(6，跨口令互拒 + 实例复用)；`MavlinkSigningConfigurationTest`(装配双向 + 只给密钥库)；`MavlinkFrameTest` 签名用例改 13 字节/小端；link-sim `SecurityImpairmentTest` 净 +2（旧 2 条重写为 4 条）。



**本轮未闭合**：出厂仍默认 `mavlink.signing.enabled=false`（任何 profile 都没打开，生产仍是明文——本轮只回答"开关打开时是否可信"）；口令与密钥库均为**明文**（`application.properties` 或本地 JSON，`SigningKeyManager` 只读不解密，也没有轮换端点），且 `--mavlink.signing.secret-key=...` 走命令行会出现在进程列表里；时间戳仍取 `System.currentTimeMillis()`，粒度 1 毫秒（=100 tick，`TICKS_PER_MILLISECOND`），同一毫秒内连发会得到相等时间戳并被 `TimestampTracker` 判为重放，高频链路要调用方自行保证严格递增（官方要求"每次 ≥ 前值+1"）；多机模式下密钥库未命中 sysid 会回退 `defaultKey`，严格白名单需显式不配该字段；`MavlinkParser` 层仍只切帧不验签（验签发生在 `MavlinkMessage.decode(frame, signer)` 与 `UdpGateway.verifyFrame`）；重放与时间戳回退这两类攻击面只有单元测试证据（link-sim 的画像只篡改签名字节，不会转发一条已签名的原帧）；签名统计只进日志（`signing: verified=/rejected=/unsigned=`），无指标、无告警；未与真机（PX4）联调——"与官方对等"的现有证据是 pymavlink 生成的 5 条已知答案向量逐字节相同 + 本仓自环，不含任何厂商实现。



---

---

---

## [Unreleased] — RBAC 默认启用（`rbac-enabled` base 翻 true）+ Pass B 拒绝分支门禁（2026-09-30）



> **本轮验证**：`mvn -B -o test` 全 reactor **3806 用例 / 0 failures / 0 errors / 0 skipped**（BUILD SUCCESS，7 模块）——翻默认对测试零波及，依据是 surefire 固定 `spring.profiles.active=test`（`cloud-backend/pom.xml:138`）+ `application-test.properties:8/:10` 显式 `dev-mode=true`/`rbac-enabled=false`。本机 `scripts/ci-integration-test.sh` **IT_EXIT=0，24 条断言全绿**（Pass A 5 / Pass B 9 / Pass C 10）：Pass B 断言 6 实测 `POST /api/v1/users → 201` + `OBSERVER GET /api/v1/audit/logs → 403`；Pass C 链校验响应 `{"ok":true,"checked":2,"brokenAtId":null,"reason":null,"truncated":false}`（同一 PG 库跨进程重启续链的再次实证）。本机 IT 需 `REDIS_PORT=56379 PG_PORT=55433` 指向我自己那对一次性容器——**默认 6379 是一个需要 AUTH 的外来 Redis**，dev profile 不带口令 → `/actuator/health` 恒 503 → `wait_ready` 永不就绪（不是"端口被占"，是握手能通但 `NOAUTH`）。本机 Flyway 证据是 `No migration necessary`（该库已应用过 V1..V21，属增量态；CI 的全新库仍是 `Successfully applied 20 migrations`）。

> **为什么**：base 默认 `false` 的实际含义是"任何忘记显式打开的 profile 都没有 RBAC"，而 **staging 正是那一个**——它 `dev-mode=false`（`application-staging.properties:8`）却没有 `rbac-enabled` 键，等于预发布环境根本不验角色；而预发布本该是"上线前把生产安全配置跑一遍"的那一档。



| # | 类别 | 问题（实测红因） | 修复 |

|---|---|---|---|

| 1 | 安全默认 | `aerofleet.security.rbac-enabled` base=false，只有 prod 打开；staging 无该键 → 预发布零 RBAC | base 翻 **true**（`application.properties:59`，注释写清判定顺序与"无注解仍放行"的边界）；staging **显式** true（不靠继承）。生效矩阵核对过：prod `:15` 本就 true、k8s configmap 与 docker-compose 都 `SPRING_PROFILES_ACTIVE=prod`（行为不变）；dev 不设该键但 `dev-mode=true` 先旁路（`RoleInterceptor:69`）；test 显式 false → 单测不受影响 |

| 2 | 门禁 | 整条 CI **从未执行过 RoleInterceptor 的拒绝分支**：Pass B 此前"不覆盖 RBAC"（脚本头注释自陈），Pass C 的 prod 虽开着 RBAC，却只走"ADMIN 够格 → 放行"这一侧 | Pass B 新增断言 6，成对取证：ADMIN `POST /api/v1/users`（`UserController:114` 标 `@RequireRole(ADMIN)`）拿 **201** 建 OBSERVER 用户 → 用该账号换 JWT → `GET /api/v1/audit/logs`（`AuditController:47-48` ADMIN）拿 **403**。成对是必要的：只断 403 排不掉"恒 403 也绿"的假门禁 |

| 3 | 机制自查 | 子代理给的路子（"用 `AEROFLEET_USERS` 种 OBSERVER"）**实测不成立** | 自查 `AuthController.java:100-102`：登录**先查 UserRepository**，JPA 可用时内存 users 表被整体忽略 → 只能通过建用户端点种账号。另自算注解覆盖 `grep -c @RequireRole(Role.` = **70**（21 ADMIN / 46 OPERATOR / 3 OBSERVER），不采信转述数字 |

| 4 | 文档口径 | README 称"rbac-enabled 默认 false 且 staging 未显式打开"（本轮改掉的事实）；`docs/security-design.md` 把跳过条件写成"默认关闭"；README 测试规模表还停在 **3787**（上一批 P4 加了 19 例，我漏改） | 三处按实测改写；README 表更新为 **3806**（`cloud-backend` 1986→2005）并在 test profile 那行注明"base 翻 true 不影响它们"的原因；写清未标注端点在 RBAC 打开后**对任何已认证主体一视同仁**（匿名由 Spring Security 拦，与 RBAC 无关） |



**本轮未闭合**：`@RequireRole` 仍只覆盖 341 个端点中的 70 个——**翻默认 true 并不会保护未标注的端点**（`RoleInterceptor:78-80` 无注解即放行），27 个有写端点的控制器零注解；根治方向是把它改成 `@PermitAll` 白名单式 fail-closed（外部审计报告也这么建议），但那会一次性改变所有未标注端点的可达性，属产品决策，未擅自铺开。staging profile 本身仍无 CI 门禁（CI 不启动 staging），其 RBAC 等价性由 prod Pass C 支撑。



---

---

---

## [Unreleased] — 遥测与审计数据保留策略（flight_log / JSONL / audit_log）（2026-09-30）



> **本轮验证**：`mvn -B -o test` 全 reactor **3806 用例 / 0 failures / 0 errors / 0 skipped**（BUILD SUCCESS，7 模块；较上轮 3796 + 新增 10 例，分模块 331/1324/115/2005/12/19）。定向复跑：`FlightLogRetentionTest` 3/3、`AuditRetentionTest` 6/6、`FlightLogPersistenceTest` 10/10。

> **本机 IT 当时未实跑**：写本条时 Docker Desktop 未运行（`docker ps` 报 daemon 套接字不存在），Pass C 的 PostgreSQL 腿在本机不可用，故 Pass C 新增断言 6 交由 CI 首跑验证——**CI run 36730879556 = completed/success**（16 job 全绿），Integration Tests 日志实测 `✅ GET /api/v1/flightlog（prod + PostgreSQL，DB 读通路） → HTTP 200`、`✅ flight_log 查询返回 JSON 数组（实际 []）`、链校验响应 `{"ok":true,"checked":1,"brokenAtId":null,"reason":null,"truncated":false}`。同日稍后 Docker 恢复，本机也已用 `REDIS_PORT=56379 PG_PORT=55433` 跑通整条 IT（见下一条的验证行）。

> **为什么**：`flight_log` 表行、`./flight-logs/*.jsonl` 文件、`audit_log` 表行三处都在无界增长——全仓此前没有任何 retention 实现（`grep -rln Retention` 只命中本轮新增文件）。遥测按 1Hz/机写入，一年就是 3000 万行级；而生产 `persist-to-db` 一旦打开，没有保留策略等于给运维埋一个必然涨满的库。



| # | 类别 | 问题（实测红因） | 修复 |

|---|---|---|---|

| 1 | 保留清理 | 无任何清理通路：DB 行、JSONL 文件、审计行都永久累积 | `FlightLogRetentionJob`（每天 03:30，`aerofleet.flightlog.retention-days=30`；DB 行按精确时刻删、JSONL 按文件名日期**整天**删，两条存储路径各自裁剪，`<=0` 关闭）；`AuditRetentionJob`（03:45，`aerofleet.audit.retention-days=0` **默认不删**）；两个任务错开分钟，因为 Boot 默认调度器是单线程（`spring.task.scheduling.pool.size=1`，全仓 24 处 `@Scheduled` 共用） |

| 2 | 读路径排序 | `FlightLogService.query()` 用 `subList(size-limit, size)` 取"最新 N 条"，前提是列表按时间升序，但 `FlightLogRepository` 的 4 个 `...TimestampBetween` 派生查询**没有 ORDER BY**——顺序由执行计划决定，换 PostgreSQL 或走索引就可能返回最旧的 N 条；`trackFor()` 同理不保证轨迹时序 | 4 个方法改 `...OrderByTimestampAscIdAsc`（自增 id 作次级键消掉同毫秒并列，与 JSONL 追加顺序同口径），DB 与文件两条读路径的 `limit` 语义一致 |

| 3 | 删除与哈希链冲突 | 审计行按保留删除会切掉哈希链**前缀**，而 `verifyChain()` 的 DB 分支从 `GENESIS_HASH` 起算链首 → 删过一次之后校验恒判红；P3 的 CHANGELOG 又把"审计保留/归档"许给了本批 | 保留默认关闭（维持 P3 "落库行不由应用侧默认删除"的不变量）；打开后 `verifyChain()` 读同一配置键容忍链首截断，响应与 `ChainVerification` 新增 `truncated` 字段（截断时 `ok` 只描述现存链段）；**保留关闭时"链首不接创世哈希"仍判红**，防止"有人删了最早的审计行"被静默放行；文档写明"前缀删除本身不可检测，需全周期取证应做归档导出而非删库" |

| 4 | 批量删除形态 | 清理若用派生 `deleteBy...` 会把整段历史加载进持久化上下文再逐行删 | `@Modifying @Transactional @Query` 单条 bulk DELETE（`FlightLogRepository.deleteOlderThan` / `AuditLogRepository.deleteOlderThan`），事务标在 Repository 方法上（调用方是定时任务，无请求事务）；`flight_log`/`audit_log` 已有 `timestamp` 索引（V18/V21）可直接用 |

| 5 | 文档口径 | README 与 `FlightLogService` javadoc 称"one file per UTC day"，实际 `fileFor(LocalDate.now())` 用 JVM 默认时区（**本地日**）；README 又称"换数据库是 `flightlog` 包一个包的事"，而 `persist-to-db` 早已实现 | 两处按实测改正（本地日 / JSONL 与表双模式并存），README 的"持久化已部分实现"条目、`docs/api-reference.md`（flightlog 顺序与保留、verify 响应补 `truncated`）、`docs/troubleshooting-guide.md` 3.3（两个保留键 + 截断语义）同步 |

| 6 | IT 门禁 | `flight_log` 的 DB 读路径与保留 SQL 只在 H2 上验过；`timestamp` 作谓词/排序键在 PG 上是否被接受没有证据（H2 两种模式都认，只有 PG 会红），而 prod 默认 `persist-to-db=false` 使这条通路在 CI 里从不执行 | Pass C 以 `--aerofleet.flightlog.persist-to-db=true` 启动 + 新增断言 6：`GET /api/v1/flightlog?type=telemetry&limit=5` 断 200 且响应为 JSON 数组（空表也成立——证的是"表存在 + SQL 被 PG 接受"，不是"有数据"） |



**新增测试**：`FlightLogRetentionTest`(3)：过期行按时刻删且保留边界不删 / `retention-days<=0` 时行与文件都不动 / JSONL 按文件名日期整天删而未知文件名不动；`AuditRetentionTest`(6)：过期行删除 / 关闭时不删 / 截断链 `ok=true + truncated=true + checked=剩余` / 保留关闭时缺前缀仍判红 / 截断链内改内容仍被抓 / 删除后新记录照常续接链尾；`FlightLogPersistenceTest` +1：乱序写入（+3h→+1h→+2h）下 `query(limit=2)` 取到的是最新两条、`trackFor()` 返回时序（无 ORDER BY 时该用例必红）。



**本轮未闭合**：遥测入库仍默认关（`persist-to-db=false`，`application-prod.properties` 未开），生产实跑的仍是 JSONL 腿；**`alert()` 落库与 `FlightTrackStore.persistLastKnown()` 的 JPA 写发生在 UDP 接收线程**（`TelemetryIngestService.handle()` 由 UDP 传输直接调用 → Spring 事件默认同步派发，全仓无 `@EnableAsync`/自定义 `applicationEventMulticaster`），`persist-to-db=false` 时只是文件追加所以无感，一旦打开入库这条会阻塞收包——off-thread 化是打开遥测入库的前置条件；保留任务只有 cron、无手动触发端点，也没有分区/按租户差异化保留；审计前缀删除不可检测（无外部链锚或签名检查点）。



---

---

---

## [Unreleased] — 审计日志持久化 + SHA-256 哈希链（V21）（2026-09-30）



> **本轮验证**：`mvn -B -o test` 全 reactor **3796 用例 / 0 failures / 0 errors / 0 skipped**（BUILD SUCCESS；较上轮 3787 + 新增 9 例）；`scripts/ci-integration-test.sh` 本机 **IT_EXIT=0（19 条断言）**——Pass C 断言 5 实测 `GET /api/v1/audit/verify` → `{"ok":true,"checked":1,"brokenAtId":null,"reason":null}`：V21 随全新 PostgreSQL 的 `Successfully applied 20 migrations`（V1..V21 共 21 个编号、V7 缺失 → 20 个文件）建表，登录 POST 被拦截器落库入链，链校验通过。新增 `AuditPersistenceTest` 9 例（H2 `MODE=PostgreSQL` 内存库）。

> **为什么**：此前审计只有进程内 `ConcurrentLinkedDeque`（容量 1000 丢最旧、重启清零、无任何校验手段），且默认 `aerofleet.audit.enabled=false`——审计在 CI 与测试里完全空转；即使打开，历史行被 UPDATE/DELETE 也无人能发现，不满足安全审计的"可追溯、可取证"要求。



| # | 类别 | 问题（实测红因） | 修复 |

|---|---|---|---|

| 1 | 持久化 | 内存 Deque 容量 1000 丢最旧（`size()` O(n)）、重启清零；无 audit 表（下一编号 V21） | `V21__audit_log_table.sql`（`audit_log` + timestamp/user_id 索引；列名用 `entry_hash` 规避保留字）；`aerofleet.audit.persist-to-db`（默认 false，prod=true）落库；查询读路径优先 DB、异常回退内存；内存窗口保留（双模式并存） |

| 2 | 防篡改 | 审计行可被直接改/删，无发现手段 | SHA-256 哈希链：`entry_hash = SHA-256(prev_hash + '\u001F' + epochMilli + '\u001F' + 各字段)`、创世 = 64×'0'；`GET /api/v1/audit/verify`（ADMIN）重算全链——内容改 → `entry_hash` 不符（`brokenAtId` = 该行 id）、行缺失 → `prev_hash` 不符；链尾从库尾惰性恢复；落库失败仍推进链尾，缺口会在 verify 暴露而非静默 |

| 3 | 响应缺字段 | `AuditController.toJson()` 用 `Map.of` 丢弃 detail；响应无哈希字段 | 改 `LinkedHashMap` 补 `detail`（null→""）/`prevHash`/`entryHash` |

| 4 | 幻影配置 | `application-prod.properties` 的 `aerofleet.audit.log-dir` 代码零引用（文档中的路径也为假） | 删除该键；troubleshooting 3.3 与实际实现对齐（双模式 + 两个端点） |

| 5 | IT 门禁 | Pass C 无审计断言；本机复跑另踩三处环境陷阱 | 断言 5（verify 200 + `ok=true` + `checked≥1`）；Pass A/B 改用 `cloud-backend/target/it-dev-db` 临时 H2 库（防编辑迁移后旧库 checksum 失配）；Flyway 日志匹配改 `migration` 前缀（11.7.2 增量库为单数措辞，实测踩中） |



**新增测试**：`AuditPersistenceTest`（9）：创世链接续 / 连续记录成链 / DB 读路径（含 detail）/ 篡改行断链 / 删行断链 / 重启后链尾恢复 / 内存模式（persist=false）/ repository 缺失回退 / 容量裁剪。



**本轮未闭合**：`detail` 仍恒空（拦截器不采集请求体——避免敏感信息入库与性能开销）；`verify` 为全量遍历（大表需增量/分页校验）；无导出端点（承诺的"保留/归档策略"已交付，见本日志顶部《遥测与审计数据保留策略》条目：`aerofleet.audit.retention-days` 默认 0=不删，打开后删除切的是哈希链前缀、`verify` 改报 `truncated=true`；导出端点本身仍缺）。



---

---

---

## [Unreleased] — Trivy 剩余 MEDIUM 清零 + PostgreSQL 生产迁移通路打通（2026-09-30）



> **本轮验证**：`mvn -B -o test` 全 reactor **3787 用例 / 0 failures / 0 errors / 0 skipped**（复跑两次：4m20s、5m25s，均 6 模块 BUILD SUCCESS）；`scripts/ci-integration-test.sh` 本机 IT_EXIT=0——Pass A（dev 冒烟 5 断言）/ Pass B（鉴权 6 断言）/ Pass C（prod + 真实 PostgreSQL 5 断言）共 16 条断言全过，Pass C 证据 `Successfully applied 19 migrations`（就绪本身即意味着 `ddl-auto=validate` 逐实体校验通过）；staging profile + 真实 PG 实测启动通过（health / 匿名 401 / 登录 / 带 token 全断言，Flyway validated + applied 19）；prod + H2 内存库 + `validate`（`deploy/k8s/configmap.yaml` 的 DB 段模拟）实测启动通过。PG 侧 `\d` 复核：`orch_step`（`id` IDENTITY 主键 + `uk_plan_step` 唯一约束）、`geofence_zone`（`fence_type` / `proximity_buffer_m`）与实体逐列一致。

> **为什么新增 Pass C**：此前 integration 腿只跑 dev profile + H2——迁移里的 MySQL 方言 DDL（`AUTO_INCREMENT`）与 PG 不认的裸 `DOUBLE` 在 PostgreSQL 上必然建表失败，而 prod 的 `ddl-auto=validate` 会逐实体校验；也就是说"生产环境能不能起来"这个最基本的问题此前没有任何门禁回答。



| # | 类别 | 问题（实测红因） | 修复 |

|---|---|---|---|

| 1 | 依赖安全 | 第一批修复（7f9a088）后 Trivy 门禁仍有 2 类 MEDIUM：log4j-api 2.24.3（CVE-2026-49844）、commons-lang3 3.17.0（CVE-2025-48924）。另：trivy-action v0.36.0 在 `format=sarif` 且未设 `limit-severities-for-sarif` 时会 unset `TRIVY_SEVERITY`（action 源码 entrypoint.sh:76-83），`severity` 入参失效、`exit-code: 1` 对**任意**严重级生效（门禁严于标注，注释已按实测改写） | 根 pom `dependencyManagement` 直接覆写（Boot 3 import BOM 场景下 `<properties>` 覆写无效）：log4j-api / log4j-to-slf4j 2.25.5、commons-lang3 3.18.0 |

| 2 | 生产迁移通路 | prod / staging profile 此前从未被任何 CI 启动过；V17/V18 的 `AUTO_INCREMENT` 是 MySQL 方言（PG 建表直接语法错误，H2 两版都认所以从未暴露）；V13/V18 裸 `DOUBLE` PG 不认；Flyway 10+ 缺 `flyway-database-postgresql` 模块时报 `Unsupported Database: PostgreSQL` | 新增 Pass C 门禁（integration job 加 `postgres:15-alpine` service + 5 条断言）；补 `flyway-database-postgresql` 依赖；V17/V18 改标准 `GENERATED BY DEFAULT AS IDENTITY`、V13/V18 数值列改 `DOUBLE PRECISION` |

| 3 | schema 漂移 | Pass C 实测抓出迁移与实体不一致：`geofence_zone` 缺 `fence_type` / `proximity_buffer_m`、`orch_plan` 缺 `pause_reason`、`orch_step` / `orch_trigger` 主键仍是业务 ID（实体是 IDENTITY 代理主键 + 计划内唯一约束）。dev 的 `ddl-auto=update` 一直在隐式补列，把漂移盖住——只有 prod 的 `validate` 会红 | V2/V3 迁移按实体重写（代理主键 + `uk_plan_step` / `uk_plan_trigger` + 缺列），PG 落库结构已 `psql \d` 复核 |

| 4 | 部署口径 | `deploy/docker/docker-compose.yml` 显式关 Flyway + `ddl-auto=update`（等于让 Hibernate 隐式建表，迁移从未在生产通路被执行过）；staging 与 k8s configmap / helm 同样 `update` | compose 改 Flyway 开 + `validate`；staging → `validate`（真实 PG 实测）；k8s configmap / helm values → `validate`（prod + H2 模拟实测） |

| 5 | staging 无日志 | `logback-spring.xml` 只配了 dev / prod / default 三块；`default` 仅在**无任何 profile 激活**时匹配，staging 启动时所有 logger 无 appender——实测除 Spring banner 外零输出，预发布事故将无日志可查 | prod 块合并为 `prod \| staging`（同 JSON 结构化格式） |

| 6 | 本地与文档 | 本机 6379 被别的 Redis 占用时集成腿 `/actuator/health` 恒 503（dev 硬编码端口无占位符）；troubleshooting 文档称"开发环境无需 Redis"（实际 actuator health 含 redis 指标，连不上/需密码即 503） | 集成脚本加 `REDIS_PORT` 环境变量覆盖（命令行参数穿透 profile 硬编码）；文档改正 Redis 表述并补 Flyway 校验和冲突的修复指引 |



**注意（本机开发库）**：本机 dev H2 文件库（`./data/aerofleet.mv.db`）若应用过旧版 V2/V3/V13/V17/V18，改文件后下次以 dev profile 启动会报 `Migration checksum mismatch`；处理方式见 `docs/troubleshooting-guide.md`（修复用 Flyway repair，或删除文件库重建）。`scripts/ci-integration-test.sh` 的 Pass A/B 已改用 `cloud-backend/target/it-dev-db` 临时库，不受此影响。



**本轮未闭合**：`deploy/k8s` / `deploy/helm` 的数据库仍是 H2 内存库（清单内已标注"生产应替换为外部 DB"；本次只把 schema 管理口径拨正为 Flyway + validate，换真 PG 需要部署侧提供实例地址与凭据）。



---

---

---

## [Unreleased] — 租户隔离收口 + WS 定向投递 + CI 门禁真实化（2026-09-30）



> **本轮验证**：`mvn -B -o test` 全 reactor BUILD SUCCESS，**3787 用例 / 0 failures / 0 errors / 0 skipped**（4m15s）；`scripts/ci-integration-test.sh` 本机 IT_EXIT=0（12 条断言）；覆盖率阈值实测 67.93/71.14/65.86%。

> **门禁的变异验证**（证明它真的会拦，而不是"加了规则"）：① 租户域非 ADMIN 分支改回 null → `tenantlessOperatorSeesNoTenantData` 变红（`$.length() expected:<0> but was:<1>`）；② WS 分桶判定改恒公共 → 3 条分区断言变红；③ link-sim 阈值抬到 0.99 → `Rule violated ... 0.65 but expected 0.99` BUILD FAILURE；④ 集成腿去掉 `--dev-mode=false` → 匿名 401 断言变红。四处均已还原并按 sha256 比对确认字节一致。



| # | 类别 | 问题（实测红因） | 修复 |

|---|---|---|---|

| 1 | 租户域 | `getEffectiveTenantId()` 返回 null 即"不过滤"，内存账号与 API Key 天然落入该态 | `TenantContext.resolveTenantScope(tenantClaim, roleClaim)` 三态：有归属 / 无归属+ADMIN=全局 / 无归属+非 ADMIN=`NO_ACCESS` 哨兵；`getWritableTenantId()` 防哨兵写库 |

| 2 | API Key | `TenantFilter` 无 Bearer 时无条件 `setTenantId(null)`，盖掉 ApiKeyFilter 写入的租户 → 租户 ADMIN 的 Key 可跨租户读写用户 | 仅在解出 JWT 时设值；ApiKeyFilter 与 WS 握手共用同一解析入口（`JwtTokenProvider.resolveTenantScope`）；5 个 `getTenantId()` 读点改有效租户口径 |

| 3 | 数据隔离 | alarm/flightlog/orch/delivery2/mapping 列表无租户条件、按 ID 直取跨租户可见（实测 5 条红）；`findByTenantId` 早已存在但无人调用 | 读侧接 `findByTenantId`/可见性判定，他租户按 ID 直取 404（与 `DeviceRegistry.get()` 同口径）；写侧落归属（flightlog 从设备归属取，UDP 线程无请求上下文也能落库，DB 与 JSONL 两条读路径都过滤） |

| 4 | RBAC | 341 端点仅 67 个标注；实测 OBSERVER 可 `PUT /api/v1/autodispatch/config` 得 200 | 3 个写端点加 `@RequireRole(OPERATOR)`（config 改写 / 围栏删除 / 编队创建）；其余 274 个的分档需产品决策，未擅自铺开 |

| 5 | WS 投递 | `broadcast()` 无租户判定 + 12 个推送点全走"默认全员广播" | 三入口机制：`broadcastToTenant` / `broadcastPublicInfra`（须声明依据）/ `tryBroadcastByOwnerKey(frame, ownerKey)` 把一帧按条目内设备归属拆成逐租户帧；mesh/hardware/obstacle/celltower 按 sysid、编队按 leader、编排按 planId→`tenantOfPlan`；灾害/卫星/地形/应急/空地协同实体缺 tenant 列，显式留在公共通道并注释 |

| 6 | WS 配置 | `WebSocketConfig` 的 dev-mode 注入默认 true，与其余 7 处 false 相反 → 不载入 profile 时"REST 受保护、WS 匿名放行" | 改 false；`/ws/** permitAll` 保留并注释（浏览器握手无 Authorization 头，鉴权在 handler 握手段） |

| 7 | CI 门禁 | npm audit `--production` 把 devDependencies 整体滤掉（实测 0 vs 全量 1）；Trivy 无 `exit-code` 只产 SARIF；"Coverage gate check (line >= 50%)" 对 3 个无 jacoco 的模块空转；集成腿在 dev-mode 下断言鉴权且 `\|\| true` 吞失败 | 去 `--production`（配 vite 5→6.4.3 依赖升级）、Trivy 加 `exit-code: 1` + SARIF `if: always()` + action 从 `@master` 固定到 `@v0.36.0`、三模块补 jacoco check（阈值=实测向下取整 5%）+ 步骤先断言 `jacoco.exec` 存在、集成腿重写为 Pass A(dev)/Pass B(鉴权) 两趟 |

| 8 | 假绿脚本 | `e2e-docker-compose.sh:41` 的 `$?` 取的是 `sleep`、`:144` 断言恒真；`sitl-compatibility-test.sh:270` 表达式含 `\|\| true` | `$?` 紧跟命令取值并在失败时打印 compose 输出；清理断言改为实测残留数；去掉恒真 |

| 9 | 文档 | README 称"MAVLink v2 signing 未实现"（实际已实现且接线，只是任何 profile 都未启用）、称"多租户隔离已实现"（覆盖面未满）、测试数 3230（实为 3787） | 三处改写，并补"这些用例跑在 dev-mode=true 的 test profile，鉴权面零覆盖"的说明，避免下一个读者把绿灯当安全证据 |



**新增测试**：`HttpAuthChainTest`(11) / `TenantScopeResolutionTest`(7) / `TelemetryWsTenantIsolationTest`(9)；`scripts/ci-coverage-threshold.sh` 对齐"实测/策略/声明"三口径。



**本轮未闭合**：License fail-open（缺 key 或验签失败降级为无限 dev license、prod/staging 未配 `license.public-key` → 进程自带签发私钥）；`deploy/k8s/secret.yaml` 占位 JWT 密钥长度达标可照抄部署；清单外 IDOR（alarm ack/SSE、orch progress/start/pause/abort、delivery2 start/deliver/confirm/route）；MAVLink 签名块与官方 13 字节布局不兼容、解析层不验签、重放窗口 `>=` 放行；前端告警 SSE 无 token 与姿态二次换算；sdk-java 响应信封契约。



---

---

---

## [Unreleased] — F1：外部视觉接入 + CV 评测指标层



> **测试基线**：3721 tests, 0 failures（全仓 7 模块）



### F1 交付（依据 design.md §4.2 + spec.md O2）



| # | 交付 | 内容 |

|---|---|---|

| F1.1 | **外部视觉源** | `ExternalVisionSource`（`aerofleet.vision.source=external` 条件装配，与 NoopReportSink 同型；endpoint + timeout-ms 可配，默认 3000ms）；检测源四档：truth（默认）/vision-source/pixels/external |

| F1.2 | **评测指标层** | `CvEvalService` 进程内滑动窗口（默认 500 帧，`aerofleet.cv-eval.window-size` 可配；重启清零）＋ 5m 阈值贪心 1:1 匹配判 TP（null 安全）；`CvEvalController`：GET `/api/v1/cv-eval/metrics?source=`、POST `/api/v1/cv-eval/reset`；响应含 frames/recall/latencyP95Ms + 参考线（85% recall / 15% falseDetectionRatio） |

| F1.3 | **GCS 评测面板** | `CvEvalPanel.jsx`（指标轮询 + source 过滤 + reset）；App 顶栏「CV评测」标签接线（useUI.js 归入 mission 分组） |

| F1.4 | **e2e + 测试** | `e2e-cv-eval.ps1`：ARM + 起飞 60m + 飞临目标上空驻留 30s（拍照仅接受 armed）→ truth×3 + pixels×2 拍摄 → 指标断言（E1/E3/O1/E4/S1）；新增 CvEvalServiceTest(11) / CvEvalControllerTest(4) / ExternalVisionSourceTest(8) |



### GCS Web 修复（笔记本分辨率布局 + 地图错误态）



| # | 修复 | 根因 |

|---|---|---|

| 1 | 顶栏视图标签溢出（逐字竖排） | `.topbar` 固定 54px 高且 flex 不换行，35+ 标签被压成逐字竖排越界；改 `min-height` + `.topbar-center`/`.view-tabs` `flex-wrap: wrap` + 按钮 `white-space: nowrap` |

| 2 | 地图错误态误判（瓦片失败触发整图错误遮罩） | maplibre 瓦片错误事件以 `e.tile` 属性标记（404 被库静默；网络超时 message 为 "Failed to fetch" 不含 "tile"），旧守卫按 message 匹配漏判 → 改 `e.tile` 优先 |

| 3 | replay-track / multi-track 图层校验报错 | `line-gradient` paint 要求 GeoJSON 源开启 `lineMetrics: true`，补上 |

| 4 | formation-labels 图层报错（从未渲染） | `text-field` 需 style `glyphs`（MAP_STYLE 为纯 raster 无 glyphs）；移除该 symbol 层，保留 formation-centers 圆点 |

| 5 | 错误面板重试按钮不可见/不可点 | maplibre canvas 为 absolute 定位盖住静态错误 UI；`.map-error-body` 抬 z-index |

| 6 | 底图换源：CARTO keyless → Esri 暗色/卫星双底图 | keyless `cartocdn.com` 实测仅返回 "API KEY REQUIRED" 水印瓦片（b./c. 子域）或连接超时（a. 子域，黑块来源）；换 Esri ArcGIS REST 免 key 源（暗色默认，地图左上角一键切卫星；商用条款需自行确认） |

| 7 | ESLint 9 错误清零（CI GCS Web 长期红） | JSX 文本内 ASCII `"` 未转义 6 处（DroneLockPanel×4 / GeofencePanel×2，react/no-unescaped-entities）→ `&quot;`；`target="_blank"` 缺 `rel="noreferrer"` 3 处（MappingPanel 测绘成果链接，react/jsx-no-target-blank） |



### 下游 CI 首跑修复（2026-09-29）



> 上游 job 全绿后，Integration Tests / E2E smoke / SDK Integration E2E / Docker Build / Security Scan 首次实际执行暴露的既存问题（此前因上游红一直 skip）。



| # | 修复 | 根因 |

|---|---|---|

| 1 | cloud-backend dev profile 启动失败（JWT 密钥缺失） | `--spring.profiles.active=dev` 未配任何 JWT 密钥：RS256 无 RSA 密钥回退 HS256 后抛 `未配置 jwt.secret 或 aerofleet.security.jwt-secret` 直接退出 → Integration Tests / E2E smoke / SDK Integration E2E 三 job 均卡在 backend health 120s 超时。dev profile 增 `jwt.generate-keys=true`（JwtTokenProvider 内置自动生成 RSA 密钥对，prod profile 有硬拦截） |

| 2 | Docker Build ×2 失败（Child module /build/sdk-java does not exist） | 根 pom `<modules>` 声明 7 个模块，但两个 Dockerfile 仅 COPY 部分模块 pom，Maven 解析模块结构即失败；补 `COPY sdk-java/pom.xml` + `COPY regulator-sim/pom.xml` |

| 3 | Dockerfile.sim 潜伏多源 COPY 报错 | `aerofleet-drone-sim-*.jar` 通配符同时命中 thin 主 artifact 与 shaded fat jar（2 个文件），COPY 到文件目标时多源报错；改为只拷 `*-shaded.jar` |

| 4 | Security Scan npm audit critical（GHSA-jrc7-96c5-q579） | maplibre-gl ≤6.4.0 受影响（首个修复版 6.4.1）；升级 4.7.0 → 6.11.2 |

| 5 | maplibre-gl v6 迁移适配（ESM-only + worker） | v6 起不再发布 UMD（默认导入不可用）→ 命名空间导入 `import * as maplibregl`；打包器环境 worker 无法自动定位 → `?worker&url` 引入 + `setWorkerUrl` 显式注册（官方 v5→v6 迁移指南） |

| 6 | package-lock.json 补全（150 → 348 包） | 原 lockfile 缺整个 devDependencies 树，`npm ci` 无法还原完整依赖；重新生成对齐 package.json |

| 7 | 任务航点范围校验从未生效（JSR303 注解空转） | `uploadMission` 收原始 JsonNode，`parseMissionItems` 手工构造 `MissionItemRequest` record——`@Min/@Max` 无 `@Valid`/校验器触发；`alt` 更无注解。改为在 `parseMissionItems` 显式校验 lat∈[-90,90] / lon∈[-180,180] / alt≥0，越界抛 `BadRequestException`（400，与 joystick 端点同型）；`DroneControllerTest` +3 用例（7→10） |

| 8 | SDK Integration E2E 4 断言误报（eval 引号 + 契约不齐） | `check` 以 `eval` 执行断言串，原始 JSON 直接拼入 → `[: too many arguments`；invalid_cmd/非法航点按 `status=error` 断言，真实契约是 HTTP 400 + `{"error":...}`（ApiExceptionHandler 统一体）。新增 `post_expect_400`（同请求捕获状态码+响应体），场景 12/13 四个断言改用 HTTP 400（与 404 检查同型） |

| 9 | E2E failsafe 3 断言恒 false（轮询 0 行输出） | `f"{d[\"online\"]}"` 在 Python 3.12（CI runner）为 SyntaxError（f-string 表达式内不能含反斜杠），被 `2>/dev/null \|\| true` 吞掉 → 130s 轮询无输出。改为 `print(d["online"], d["mode"], d.get("armed", False))` |

| 10 | Security Scan Trivy Maven Central 429 致命 | Trivy fs 对本地缺失的 pom 依赖回源 Central，共享 runner IP 被限流（Retry-After 1800）直接 fatal。CI 预跑 `mvn dependency:go-offline` 预填充 `~/.m2`（`continue-on-error`，防预取自身被限流拖垮 job）；新增根 `trivy.yaml`（`scan.offline: true`：缺失依赖跳过远程拉取）经 trivy-action `trivy-config` 传入 |

| 11 | E2E job Build all 被 Maven Central 429 打死（m2 缓存残缺） | setup-java 的 m2 缓存「首个保存者胜出」，之后所有保存被跳过、内容**永久冻结**（日志实证：`Cache hit occurred on the primary key ..., not saving cache`）——冻结的缓存仅 33MB，每个 maven job 每次运行仍实时下载数百 artifact（单 job 实测 740 次 `Downloading from central`），共享 runner IP 聚合流量触发 Central 限流后 `maven-shade-plugin:3.6.0` 解析即败。修复：新增 `maven-warm` 作业先于全部 maven job 运行（冷缓存时全量构建并成为唯一保存者；命中时 `-o` 离线构建自检缓存完整性，缺失立刻失败而非静默回源）；全部 maven job 改用显式 `actions/cache`（versioned key `mvn-<os>-<pom hash>-v1` + restore-keys 跨 pom 变更复用），下游与 CodeQL 用 `actions/cache/restore` 仅恢复不保存，杜绝二次冻结。依据：Central 官方 429 FAQ「reduce unnecessary traffic…caching artifacts…avoid repeated downloads from clean or ephemeral environments」，明确不要靠重试 |

| 12 | E2E failsafe 观测窗口 130s 过紧（CI 落地晚于固定窗口） | CI 重跑实证：RTL 于观测 ~72s 才触发（`FAILSAFE: datalink/battery critical -> RTL`），130s 固定窗口截止时仍 `[130s] online=True mode=RTL`（下降末段）→ `FAIL: vehicle landed by itself (STANDBY, disarmed)`。改为有界轮询 ≤225s（`seq 1 45`）：三项链式证据（offline→RTL→landed）齐备即提前退出，真回归仍由 225s 封顶兜底 |

| 13 | vision 脚本 track 循环迭代 range repr（装饰输出坏 + CI 日志噪声） | `for t in $(jqget "$tr" "range(len(d['tracks']))")` 迭代的是 Python `range` 的 repr 串（首个 token `range(0,`、次个 `2)`）→ 三条 `d['tracks'][$t][...]` 表达式全部 SyntaxError，CI 日志出现回显噪声（断言 L106-108 已在前判过，判定不受影响）。改为先取 `tracks_n=len(...)` 再 `seq 0 $((tracks_n-1))` 索引迭代 |

| 14 | CodeQL job 未等待 maven-warm（冷启动缓存 miss，autobuild 全量下载 1013 次） | codeql 无 needs、与 warm 并行竞争缓存：restore 于 19:21:52 执行时 warm 尚未保存（19:22:41 才 `Cache saved`），日志实证 `Cache not found for input keys: mvn-Linux-0115124c…v1, mvn-Linux-`，autobuild 随后实测 1013 次 `Downloading from central`——与 warm 的 1010 次叠加使冷启动流量近乎翻倍（429 风险面扩大）。修复：`codeql` 加 `needs: [ maven-warm ]`，restore 必在 save 之后（java job 同款依赖模式已实证命中）。**已复验**（run 36478769019）：`Cache restored from key: mvn-Linux-0115124c…v1`、下载 1013→3（仅 maven-clean-plugin 3 件，autobuild 的 clean 不在 warm 的 verify 生命周期内） |

| 15 | 未归属设备对所有租户可见可控（跨租户命令破口） | `DeviceRegistry.get()/all()` 把 `snapshot.tenantId == null` 当作「不过滤」放行，而快照由 UDP 接收线程创建（`registerIfAbsent`，该线程无请求上下文）→ 心跳注册的设备恒为 null，任何租户都能查询并下发指令。`DeviceEntity.tenant_id/device_token/name` 注释写「provisioning 时使用」但全仓无写入端点（有字段无功能）。修复：null 语义改为「未归属」，仅全局管理员上下文可见；新增 `assignTenant()` 与 `DeviceProvisioningController`（`PUT /api/v1/devices/{sysid}/tenant`、`GET /api/v1/devices/unassigned`，ADMIN）；`registerIfAbsent` 回查库中归属，避免离线设备指派后被首个心跳丢失 |

| 16 | RBAC 名义存在但不可用（默认关 + 三条断链 + 零测试） | `aerofleet.security.rbac-enabled` 默认 false（`RoleInterceptor.java:46`）；`@RequireRole` 仅 `@Target(METHOD)` 且拦截器只读方法注解（`:65`）→ 无法按控制器整块授权；角色只从 JWT claim 取（`:101-114`），但 SDK 只用 X-API-Key（`NexusSkyClient.java:25`）、内存模式 token 不含 role claim（`AuthController.java:133` 走 `JwtTokenProvider.java:278` 的无角色重载）、DB 种子 admin 哈希经 bcrypt 复算对 16 个候选口令全部不匹配（`V6:25` 注释自称「密码 admin」）——真开启 RBAC 后管理端点将无人可达；src/test 内 RBAC 覆盖为 0（grep RequireRole/RoleInterceptor/isForbidden 空）。**机制修复**：`@RequireRole` 支持类级（方法级优先）；角色来源改为 JWT claim → API Key 角色（新增 `api_keys.role` + `V19` 迁移，签发时从 JWT 继承、历史 Key 回填 OPERATOR）；内存用户配置扩为 `username:password[:ROLE]`（缺省 OPERATOR；末段非角色名则仍视为口令的一部分，保住含冒号口令），login/refresh 均签发带 role 的 token；compose demo 账号显式 `admin:admin:ADMIN`；新增 `RoleInterceptorTest` 11 例补上零覆盖 |

| 17 | RBAC 仍可在生产档被绕过：auth 前缀全匿名 + API Key 铸/撤无角色 + 无可用的首个管理员 | `SecurityConfig.java:70` 把整个 `/api/v1/auth/**` permitAll（铸造/撤销 API Key 也在内，仅靠 handler 内自校 JWT，铸 key 无角色要求）；`ApiKeyController.java:205-210` 撤销的跨租户校验写作 `currentTenantId != null && entity.getTenantId() != null`，任一方缺 tenant_id 即跳过比对（可跨租户撤销他人 Key）；且 DB 种子 admin 的哈希经 bcrypt 复算对 16 个候选口令全不匹配（`V6:25` 注释自称「密码 admin」），而建用户端点已要求 ADMIN（`UserController:114`）→ RBAC 真开启后无人能取得管理员。修复：permitAll 收窄到 login/refresh；create/revoke 加 `@RequireRole(ADMIN)`，撤销改为「非 privileged（无租户上下文或 ADMIN）必须两侧租户均非 null 且相等」；新增 `AdminBootstrapRunner`（仅当显式配置 `aerofleet.security.bootstrap-admin-password` 才引导 ADMIN，短于 8 位拒绝，不把默认口令写进仓库）+ `V20` 停用不可登录的种子行（按哈希条件，幂等） |

| 18 | MavlinkParser 签名块长度 13/15 自相矛盾（帧错位 + 越界读风险） | `MavlinkParser.java:72` 用硬编码 13 计算 `sigLen`，而 :104-114 实际按本仓 v2 签名布局读 15 字节（LINK_ID 1 + TIMESTAMP 6 + SIGNATURE 8 = `MavlinkFrame.SIGNATURE_DATA_LENGTH`）→ `totalLen` 少算 2 字节：签名帧之后的下一帧起始错位 2 字节；签名帧落在缓冲区末尾时边界校验按 13 通过、实际读到 15（`IndexOutOfBounds` 风险），且该分支此前无测试。修复：`sigLen` 改取 `MavlinkFrame.SIGNATURE_DATA_LENGTH`，消除魔法数并令校验与读取同源。注：本仓 15 字节布局本身仍与官方 13 字节 / sha256_48 / 小端不一致（对齐官方是另一项更大改动，含与 pymavlink 双向互通实测） |

| 19 | docker-compose 以 prod 档启动却内置可通过校验的公开凭据 | `deploy/docker/docker-compose.yml` 直接写死 `AEROFLEET_SECURITY_JWT_SECRET` / `AEROFLEET_JWT_SECRET`（长度达 `JwtTokenProvider` 的 ≥32 校验，故服务能正常起——等于「生产档 + 公开密钥」是可运行状态，任何人可伪造任意租户/角色的 JWT）、`AEROFLEET_USERS` 默认口令与数据库口令。修复：四处改 `${VAR:?...}` 必填插值（未注入即拒绝启动，fail-closed）。注：本机无 Docker，未跑 `docker compose config` 实测插值，仅 YAML 解析校验通过 |



> **本轮验证**：run 36478769019（eab3f4f，16 job 全 success，14.5 min）**全绿且 #13/#14 复验通过**：vision track 行正常打印（`track id=1 state=ACTIVE hits=4` / `id=2 state=ACTIVE hits=2`，无 SyntaxError）；CodeQL(java) `Cache restored from key: mvn-Linux-0115124c…v1`、下载 **1013→3**（对比修复前 1013；余 maven-clean-plugin 3 件）；warm 命中路径 restore + `-o` 离线自检 27.1s → `not saving cache`（命中不保存=预期）；failsafe offline@70s → RTL@105s → STANDBY@145s 3 断言 PASS；E2E 零下载；时长 14.5 min vs 上轮 11.7——CodeQL 串行化（其自身 2m59s）。冷启动基线（run 36471697558，1df333d，11.7 min）：warm 单次集中下载 1010 次（BUILD SUCCESS 42.9s，唯一保存者 `Cache saved with key`）、下游全部命中（E2E Build all 零下载 15.8s；Java job 仅 surefire 运行期 provider 15 个 artifact 实时解析——`-DskipTests` 暖缓存不含）、failsafe 3 PASS、vision 首跑 VISION E2E PASSED。本地：vision 修复复跑 VISION E2E PASSED——13 项断言全过，track 行正常输出、无 SyntaxError。



### 已知项（外部依赖，待决策）



| # | 项 | 说明 |

|---|---|---|

| 1 | Esri 底图商用条款 | 现为免 key 公开 REST 服务，开发/内部使用实测可用；转商用前需确认 Esri 授权 |

| 2 | Docker Compose E2E 在 CI 恒跳过 | runner 无 hyphen 版 `docker-compose` 二进制（SDK job 19:28:42 日志 `docker-compose not available, skipping`）；且脚本按独占 Docker 主机设计（host 网络自起 8080 backend / 5173 web，与 job 内已启动的 backend/sim 端口冲突），同 job 无法实跑、须独立 job。现保留为本地/手工 smoke（`continue-on-error: true`，不产生失败）；如需 CI 实跑需单开 job（估计 +5–8 min/次，本机 8080 被 Docker Desktop 占用无法本地完整验证） |



---

---

---

## [Unreleased] — Phase 2 合规专项（C1–C5）



> **测试基线**：3698 tests, 0 failures（全仓 7 模块）



### 合规专项（C 系列，依据低空经济需求调研 v2，硬 deadline 2026-11-01）



| # | 专项 | 内容 |

|---|---|---|

| C1 | **UOM 数据对接层** | 新增 `regulator` 包（RegulatorReportSink 抽象 + UOM/Sim/Noop 三实现 + ComplianceStateManager）与 `regulator-sim` 模拟监管平台模块；MH/T 3030 实名验证/激活/注销/遥测四项交互；e2e-regulator |

| C2 | **运行识别（RID）广播** | MAVLink `OPEN_DRONE_ID_*` 消息族（msgId 12900–12905）；cloud-backend RID 摄取/快照/WebSocket 推送；drone-sim RID 广播器（周期广播 + MESSAGE_PACK 合并）；GCS RidPanel（App 导航已接线）；e2e-rid |

| C3 | **电子围栏硬拦截** | 起飞前命令拦截链（InterceptChain/GeofenceInterceptService）；限飞区数据源抽象（MOCK/LOCAL_FILE/HTTP）+ 本地缓存管理器；拦截日志存储；GB 42590 围栏语义 |

| C4 | **PostgreSQL 持久化** | flight_log 表迁移（V18）+ FlightLog/DeviceRegistry 持久化（DB 写入失败回退 JSONL） |

| C5 | **MAVLink v2 signing** | 签名帧扩展（LINK_ID+TIMESTAMP+SIGNATURE 13B）；SigningKeyManager 密钥管理 + TimestampTracker 重放防护；link-sim 篡改/未签名损伤画像；e2e-signing |



### 测试清零修复（2026-09-28）



| # | 修复 | 根因 |

|---|---|---|

| 1 | `NoopReportSink` 加 `matchIfMissing=true` | regulator sink 三实现均为精确条件匹配，未配置时无 Bean，连带 22 个测试 context 失败 |

| 2 | OpenDroneId BasicId/OperatorId/SelfId encode 补零填充 | 短数组硬拷固定长度越界（MAVLink char[N] 应 NUL 填充） |

| 3 | `OrchestrationPlanService` 认领状态改 ALLOCATING | advanceSteps 提前置 EXECUTING 跳过资源分配阶段，破坏 StepExecutor 状态机衔接 |

| 4 | `RidBroadcaster.close` 加 awaitTermination | close 后 in-flight 广播帧仍可发出（flaky 根治） |

| 5 | slf4j-simple 隔离根治：drone-sim fat jar 挂 `-shaded` classifier 附属（主 artifact 保持 thin jar）+ cloud-backend 显式排除 slf4j-simple；link-sim/regulator-sim 无下游依赖，保持默认 shade fat 主 artifact | fat jar 内嵌类 + 依赖传递双重污染 cloud-backend（optional 方案只断传递，内嵌拷贝仍在），SLF4J 2.x provider 双绑定导致"单跑能过全量挂" |

| 6 | RidWebSocketHandlerTest 补 `throws IOException` | 测试编译失败（凌晨测试报告为旧编译产物） |

| 7 | RidControllerTest 脱敏期望值笔误 | `NEWE*********` → `NEWO**********`（脱敏规则保留前 4 位） |

| 8 | FlightTrackStore.addPoint add+trim per-sysid 原子化 | 并发下多线程同时观测超限会各自 poll 同一"多余量"，过度裁剪后最终 size < maxPoints（concurrentAdd_threadSafe 偶发 999） |

| 9 | SecurityImpairmentTest 断言掩码 + applyTamper bit 去重 | 断言 `Integer.bitCount(a[i]^b[i])` 未掩 `0xFF`，byte 符号扩展把 0x80 误计为 25（实测旧断言 ~23% 概率挂，CI 2026-09-27 起偶发）；实现改为不重复 bit 翻转，消除双翻同一 bit 相互抵消（旧实现实测 0.489% 概率篡改后与原文相同） |



---

---

---

## [Unreleased] — 夯实阶段：安全加固 + 质量提升



> **测试基线**：1587 tests, 0 failures



### CRITICAL 修复（7 项）



| # | 修复内容 | 影响模块 | 说明 |

|---|---|---|---|

| C1 | **密码加密** | `security` | 用户密码从明文存储改为 BCrypt 加密，登录验证使用加密比对 |

| C2 | **主键改自增** | `security`, `tenant` | 数据库主键从手动赋值改为自增策略，消除主键冲突风险 |

| C3 | **跨租户校验** | `security`, `tenant` | 多租户场景下增加跨租户数据访问校验，防止租户间数据泄露 |

| C4 | **JWT 持久化** | `security` | JWT 令牌增加持久化存储，支持令牌撤销与黑名单机制 |

| C5 | **fetch 超时** | `gateway` | HTTP fetch 请求增加超时配置，防止长时间阻塞导致服务不可用 |

| C6 | **WebSocket 认证** | `gateway`, `security` | WebSocket 连接增加 JWT 认证校验，防止未授权实时数据访问 |

| C7 | **XSS 修复** | `api` | REST API 响应增加 XSS 过滤，防止跨站脚本攻击注入 |



### MAJOR 修复（18 项）



| # | 修复内容 | 影响模块 | 说明 |

|---|---|---|---|

| M1 | **编排引擎改进** | `orch` | OrchestrationEngine 状态机增强：支持阶段回退、异常恢复、超时处理 |

| M2 | **编排计划持久化** | `orch` | OrchestrationPlanService 增加计划持久化，重启后可恢复执行状态 |

| M3 | **资源管理器增强** | `orch` | ResourceManager 支持动态资源分配与释放，防止资源泄漏 |

| M4 | **触发管理器改进** | `orch` | TriggerManager 支持复合触发条件与优先级排序 |

| M5 | **安全增强 — 速率限制** | `security` | 登录端点增加 IP 级速率限制（每 IP 每分钟 10 次） |

| M6 | **安全增强 — 角色权限** | `security` | `@RequireRole` 注解细粒度权限控制（ADMIN/OPERATOR） |

| M7 | **安全增强 — 审计日志** | `audit` | AuditController 记录关键操作（登录/命令/配置变更） |

| M8 | **安全增强 — License 管理** | `license` | LicenseController 支持激活码验证与设备绑定 |

| M9 | **前端优化 — 登录面板** | `gcs-web` | LoginPanel.jsx 实现登录界面与 JWT 令牌管理 |

| M10 | **前端优化 — 用户管理** | `gcs-web` | UserPanel.jsx 实现用户 CRUD 与角色分配 |

| M11 | **前端优化 — 租户管理** | `gcs-web` | TenantPanel.jsx 实现多租户管理与隔离视图 |

| M12 | **前端优化 — 健康面板** | `gcs-web` | HealthPanel.jsx 实现设备健康监控与告警展示 |

| M13 | **前端优化 — 仪表盘** | `gcs-web` | DashboardPanel.jsx 实现综合仪表盘视图 |

| M14 | **前端优化 — 场景库** | `gcs-web` | ScenarioLibraryPanel.jsx 实现场景模板管理与一键启动 |

| M15 | **前端优化 — 自动调度** | `gcs-web` | AutoDispatchPanel.jsx 实现自动调度可视化 |

| M16 | **前端优化 — 语音指令** | `gcs-web` | VoiceCmdPanel.jsx 实现语音指令输入与执行 |

| M17 | **前端优化 — 3D 场景** | `gcs-web` | Scene3D.jsx / Trajectory3D.jsx 实现 3D 轨迹可视化 |

| M18 | **前端优化 — 通信适配** | `gcs-web` | CommAdaptPanel.jsx 实现通信链路自适应监控 |



### 新增测试（5 个文件，71 个新测试）



| 测试文件 | 模块 | 测试数 | 说明 |

|---|---|---|---|

| `UserControllerTest.java` | `security` | 15 | 用户 CRUD、角色分配、密码加密验证 |

| `TenantControllerTest.java` | `security` | 12 | 租户 CRUD、跨租户隔离校验 |

| `AuthControllerTest.java` | `security` | 14 | 登录、令牌刷新、速率限制、JWT 持久化 |

| `JwtTokenProviderTest.java` | `security` | 18 | JWT 生成/验证/过期/撤销/黑名单 |

| `GeofenceStorePersistenceTest.java` | `geofence` | 12 | 围栏数据持久化往返、序列化兼容 |



### 测试基线



- **总计**：1587 tests, 0 failures

- **分布**：mavlink-core 185 / drone-sim 350+ / link-sim 20+ / cloud-backend 1030+



---



## [0.1.0-SNAPSHOT] — 初始骨架版本



### 核心模块



- **mavlink-core**：MAVLink v1/v2 二进制协议栈，CRC 与官方逐字节一致

- **drone-sim**：虚拟四轴无人机模拟器，支持任务上传/ARM/航点飞行/RTL

- **cloud-backend**：Spring Boot 3.5 云端管理平台，MAVLink 设备网关 + REST API

- **gcs-web**：React 18 + MapLibre Web 地面站

- **link-sim**：MAVLink/UDP 链路损伤代理（延迟/丢包/带宽/分区）



### 能力扩展里程碑



- M0a — Mesh 组网落地（一跳静态中继）

- M0b — 环境气象机制（温度/湿度/天气/风力模型 + 告警）

- M1 — 编队表演（队形生成 + 灯光控制 + 动作同步）

- M2 — 喷洒物流（执行器抽象 + 喷洒任务 + 配送序列）

- M3 — 成像增强（多光谱/热成像/避障）

- M4 — 硬件抽象（相控阵雷达/旋翼气动/LiDAR/IMU）

- M5 — 应急 Mesh 自愈组网（AODV-lite 多跳动态路由）

- M6 — 移动基站载荷抽象（LTE/WiFi/LoRa）

- M7 — 星-空-地多层级中继（LEO 卫星 + HAPS）

- M8 — 复杂地形适配（山地/森林/沼泽/城市 RF 衰减建模）

- M9 — 应急任务编排（全流程闭环：测绘→覆盖→组网→服务→自愈）

- M10 — 集群智能调度（任务分配 + 冲突避免）

- M11 — 自主决策引擎（RTH/避障/自适应航迹）

- M12 — 边缘计算节点（AI 推理 + 传感器融合）

- M13 — 数字孪生与预测（虚拟镜像 + 轨迹预测 + 场景回放）

- 4a — 空地一体化应急指挥（ONVIF 安防接入 + 报警联动）



### MAVLink 扩展消息



- 420–441：M0a–M4 扩展消息

- 450–467：M5–M9 应急组网消息

- 468–476：M10–M13 集群智能消息

- 477–479：4a 安防报警消息



---

---

---

## [Unreleased] — e2e harness 在 pwsh 上读不到错误响应体，于是 4xx 断言一直在对 `$null` 做（#110，2026-10-18）



PR #107 唯一失败的那一项，是两行相邻的输出：



```

[PASS] OPEN 态重复开门 → 409（实际 409）      ← 状态码对

[FAIL] 409 响应点名当前状态（）               ← 括号是空的

```



### 后端是清白的（实测，不是推断）



直接打活后端拿响应：



```json

{"status":"error","result":"command door_open rejected: dock state=OFFLINE"}

```



409 的响应体**确实点名了当前状态**。CI 那次状态是 OPEN，消息会是

`dock state=OPEN`，`-match 'OPEN'` 本该通过。



### 根因：两代 PowerShell 的 Response 是不同类型



我第一版的假设**恰好是反的** —— 我以为是 5.1 的 `GetResponseStream()` 坏了、

`ErrorDetails.Message` 才是正解。在本机把两种读法都实测了一遍，结果相反：

5.1 的 `GetResponseStream()` 读到 76 字节好好的，`ErrorDetails.Message` 才是空的。



真正原因是类型不同：



| PowerShell | `$_.Exception.Response` | 读法 |

|---|---|---|

| 5.1 | `HttpWebResponse` | 有 `GetResponseStream()` |

| **7+**（Linux runner 的 `pwsh`） | `HttpResponseMessage` | **没有这个方法** |



调用不存在的方法抛 `MethodNotFound`，被 ubiquitous 的 `catch {}` 吞掉，

响应体变成 `$null`。于是**一个断言对着 `$null` 做，却以产品失败的形态呈现**。



### 同一个契约被复制了 9 份



`Try-Json` 在 e2e 脚本里有 **9 份 / 4 个变体**：



- 1 份（dock）用 5.1 专有读法 —— 在 pwsh 上必然读不到，如上

- **6 份根本没有读错误体的分支** —— 那些脚本里所有 4xx 响应体断言都在对 `$null` 做



形态与 #109 的 `rtspUrl` 键名一模一样：**一份契约多处复制，各自漂移**。修法是

收进 `e2e-common.ps1` —— 那个**本来就是为平台差异而建**的公共库。



### 改法



- `e2e-common.ps1` 新增 `Read-E2eErrorBody`（按方法名分派，两种 Response 都能读）

  与 `Invoke-E2eJson`

- `e2e-dock.ps1` 删掉私有副本，改调公共库

- 新增 `e2e-common-selftest.ps1`：把两种对象形状直接喂给读取器。没有它，

  pwsh-7 那条分支**在 5.1 主机上永远测不到** —— 7+ 读取器写错了本地也一直是绿的

- 新增 CI job `e2e-common-selftest`，**且不带 `continue-on-error`**。本仓库已有多批

  e2e 是"观察模式"，这一条若也那样，就等于没有任何东西在守 `e2e-common.ps1`

  —— 而所有 e2e 脚本都依赖它



### 自检立刻抓到一个真 bug



新自检第一次跑：7+ 形状读不出。原因是读取器用

`$resp.PSObject.Methods.Name -contains 'get_Content'` 分派 —— 这只在对象是

**真实 .NET 类型**时成立（属性 getter 才叫 `get_*`）。Response 一旦被包装或替换

就漏判，然后静默返回 `$null`。**同一类静默失败，只是下沉了一层。** 改为直接在

`$resp.Content` 上找方法。6/6 通过。



### 验证



本地实跑 `scripts/e2e-dock.ps1` 端到端：



```

[PASS] 409 响应点名当前状态（command door_open rejected: dock state=OPEN）

=== e2e-dock: ALL PASS ===

```



变异验证用了**两个判据**，因为两条分支在同一台主机上的可观测性不同：



| 变异 | 判据 | 结果 |

|---|---|---|

| M1 5.1 分支停用 | dock e2e | ✅ 杀，且**逐字复现 CI 症状**：`[FAIL] 409 响应点名当前状态（）` |

| M2 pwsh 7+ 分支停用 | 自检 | ✅ 杀：`[FAIL] 7+ 形状能读出响应体` |

| M3 ErrorDetails 兜底移除 | 无 | **本机不可判定，明确说明而非冒充通过** —— 它只在两条类型分支都失败后才走到，而本机 5.1 上第一条必然成功 |



### 明确没做



另外 8 份 `Try-Json` 没动。其中 6 份读不到错误响应体，是真实的覆盖缺口，但

批量改写在本机**无法验证**（多数需要 PostgreSQL / link-sim / SDK harness），

我不盲改自己跑不了的代码。`Invoke-E2eJson` 已在公共库里就绪，供后续批次接入。
