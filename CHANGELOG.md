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

> **本轮验证**：run 36478769019（eab3f4f，16 job 全 success，14.5 min）**全绿且 #13/#14 复验通过**：vision track 行正常打印（`track id=1 state=ACTIVE hits=4` / `id=2 state=ACTIVE hits=2`，无 SyntaxError）；CodeQL(java) `Cache restored from key: mvn-Linux-0115124c…v1`、下载 **1013→3**（对比修复前 1013；余 maven-clean-plugin 3 件）；warm 命中路径 restore + `-o` 离线自检 27.1s → `not saving cache`（命中不保存=预期）；failsafe offline@70s → RTL@105s → STANDBY@145s 3 断言 PASS；E2E 零下载；时长 14.5 min vs 上轮 11.7——CodeQL 串行化（其自身 2m59s）。冷启动基线（run 36471697558，1df333d，11.7 min）：warm 单次集中下载 1010 次（BUILD SUCCESS 42.9s，唯一保存者 `Cache saved with key`）、下游全部命中（E2E Build all 零下载 15.8s；Java job 仅 surefire 运行期 provider 15 个 artifact 实时解析——`-DskipTests` 暖缓存不含）、failsafe 3 PASS、vision 首跑 VISION E2E PASSED。本地：vision 修复复跑 VISION E2E PASSED——13 项断言全过，track 行正常输出、无 SyntaxError。

### 已知项（外部依赖，待决策）

| # | 项 | 说明 |
|---|---|---|
| 1 | Esri 底图商用条款 | 现为免 key 公开 REST 服务，开发/内部使用实测可用；转商用前需确认 Esri 授权 |
| 2 | Docker Compose E2E 在 CI 恒跳过 | runner 无 hyphen 版 `docker-compose` 二进制（SDK job 19:28:42 日志 `docker-compose not available, skipping`）；且脚本按独占 Docker 主机设计（host 网络自起 8080 backend / 5173 web，与 job 内已启动的 backend/sim 端口冲突），同 job 无法实跑、须独立 job。现保留为本地/手工 smoke（`continue-on-error: true`，不产生失败）；如需 CI 实跑需单开 job（估计 +5–8 min/次，本机 8080 被 Docker Desktop 占用无法本地完整验证） |

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