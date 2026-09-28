# NexusSky 变更日志

> 本文件记录 NexusSky（天枢）项目的重大变更，按版本倒序排列。

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

### 已知项（外部依赖，待决策）

| # | 项 | 说明 |
|---|---|---|
| 1 | Esri 底图商用条款 | 现为免 key 公开 REST 服务，开发/内部使用实测可用；转商用前需确认 Esri 授权 |

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