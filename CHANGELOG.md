# NexusSky 变更日志

> 本文件记录 NexusSky（天枢）项目的重大变更，按版本倒序排列。

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
| 5 | e2e 可运行性 | 脚本启动 backend **不带 profile** → 命中 base 默认 `spring.profiles.active=prod` → prod 要 `localhost:5432` 的 PostgreSQL → `FlywaySqlException: Connection refused`，场景 1 就挂；且 `$BackendRestPort`/`$BackendUdpPort` 只是变量，6 处启动都没下传（等于永远硬编码 8080/14550） | backend 启动统一加 `--spring.profiles.active=dev` + `--server.port` + `--aerofleet.udp-port` + Redis 主机/端口，并把 REST/UDP/Drone/LinkSim/Redis 全部提升为脚本参数（与 `ci-integration-test.sh` 的 A_PORT/PG_PORT 同一做法；本机 8080 被其它项目容器占用时也能跑） |
| 6 | 文档不实声明 | README 首屏"所有协议、接口与真实硬件（PX4 飞控）完全一致"、"签名代码已实现并接入 UdpGateway"；`docs/commercialization-plan.md` 三处把签名记成"HMAC-SHA256 8 字节截断、已完成"；`docs/demo-scenarios.md` 称"替换模拟器即对接真飞控" | 全部按实测改写（保留"此前怎么写、为什么错"的痕迹而不是抹平）；`docs/security-design.md` 新增第 7 节记录线上格式、重放规则、装配史、互通性与边界（新节初稿编号撞了已有"第 6 节 安全加固清单"，已顺延）；两处主源码 javadoc 随算法同步（`MavlinkMessage` 类注释仍写"payload 附加 8 字节 HMAC-SHA256"、`UdpGateway.signFrame` 写"附加 HMAC-SHA256 签名"、`MavlinkSignatureConfig` 配置项说明写"HMAC 密钥"） |
| 7 | 多机密钥名不副实 | 接线补好后仍只补了一半：`SigningKeyManager` 支持密钥库（per-sysid 口令 + linkId），但 backend 侧只注入**一个**全局 `MavlinkSigner`，密钥库的 `key` 字段进不了签名路径——per-sysid 只有 **linkId** 生效，所有系统共用全局 `secret-key` 签名。即"多机密钥分发"模式在 backend 侧从来没真正工作过，而 e2e 场景 5 当时因为脚本自身缺陷也没能暴露这件事 | 新增 `MavlinkSignerFactory`：**按口令字符串缓存** `MavlinkSigner` 实例（同口令复用、密钥库热重载自然产生新实例、无需失效逻辑），`UdpGateway.signFrame/verifyFrame` 改为按 sysid 经 `SigningKeyManager.keyFor()` 取签名器；单机与多机走同一条代码路径、无特判。发送侧查不到密钥→WARN 并降级为未签名；接收侧取不到口令→**拒帧**（不拿别的机的口令去验一个陌生 sysid）。注意口令查找本身有回退语义：多机模式下密钥库未命中该 sysid 时会回退到 `defaultKey`（`SigningKeyManager.keyFor`），所以"只给 `key-store-path`、不写 `defaultKey`"才是严格的白名单。`MavlinkSignerFactoryTest`(6) 断言跨口令必须验不过 + 同口令复用同一实例；e2e 场景 5 用两把不同口令（sysid=1/2）端到端跑通 |
| 8 | 安全损伤画像不实 | `SecurityImpairmentEngine.applyTamper` 注释自称"仅模拟签名篡改"，实际在**载荷**上随机翻位——载荷一动 CRC 就不对，帧在 `MavlinkParser` 解析阶段即被丢弃，**永远走不到验签**；`applyStripSignature` 清掉 INC 位 0 后**不重算 CRC**（INC 在 CRC 覆盖范围内），产出的是一帧 CRC 坏帧，同样到不了验签。这两条正是"篡改/未签名被拒"端到端从未被证明的直接原因 | `applyTamper` 改为只在 6 字节签名块内翻 1–3 位（未签名帧原样返回）；`applyStripSignature` 按官方口径重算 CRC 并写小端字段，同时加 `isSignedV2` 前置判断以保持"未签名帧不改动"的既有契约（`applyStripSignatureOnUnsignedFrame` 这条旧用例实测拦下了我第一版引入的回归）。旧 2 条用例重写为 4 条（净 +2）钉住"改动的字节必须落在签名块内""剥签名后的帧 CRC 自洽"——值得记一笔的是原用例里有一条就叫 `applyTamperModifiesPayloadOnly`，**它断言的正是"篡改＝改载荷"这个缺陷本身**，所以这套"安全画像"测试从命名起就在为错误实现背书，缺陷才一直没被察觉 |
| 9 | e2e 断言"空过" | 场景 2/3 的 backend 断言在**固定的 sleep 之后**用 `Select-String` 查一次日志：日志没刷出来就算失败、而"未签名帧被拒"那条曾经 PASS 只是因为查询时机对上了没发生的帧——脚本判定与真实行为脱钩；`[link-stats]` 只在进程停止时输出且 link-sim 日志此前根本没落盘，所以"篡改/剥离计数 > 0"无从断言 | 新增 `Wait-LogPattern` 有界轮询（最多 40 s、命中即返回，并把命中的那一行原样打进结果，让 PASS 自带证据）；场景 1 的签名统计改用同一 helper；link-stats 断言移到 `Stop-SceneProcesses` **之后**读，link-sim 启动加 `-Dorg.slf4j.simpleLogger.logFile=`；正则收紧为 `tampered=[1-9]` / `stripped=[1-9]`，排除"计数为 0 也算命中" |

**新增/重写测试**：`MavlinkSigningVectorTest`(6，pymavlink 已知答案)；`MavlinkSignerTest` 按官方语义重写(18，含"非 HMAC""旧 8 字节签名被拒""含 STX""帧内任一字节敏感性")；`TimestampTrackerTest`(12，含"相等必须拒""三元组分流""60 秒窗口边界")；`MavlinkSignerFactoryTest`(6，跨口令互拒 + 实例复用)；`MavlinkSigningConfigurationTest`(装配双向 + 只给密钥库)；`MavlinkFrameTest` 签名用例改 13 字节/小端；link-sim `SecurityImpairmentTest` 净 +2（旧 2 条重写为 4 条）。

**本轮未闭合**：出厂仍默认 `mavlink.signing.enabled=false`（任何 profile 都没打开，生产仍是明文——本轮只回答"开关打开时是否可信"）；口令与密钥库均为**明文**（`application.properties` 或本地 JSON，`SigningKeyManager` 只读不解密，也没有轮换端点），且 `--mavlink.signing.secret-key=...` 走命令行会出现在进程列表里；时间戳仍取 `System.currentTimeMillis()`，粒度 1 毫秒（=100 tick，`TICKS_PER_MILLISECOND`），同一毫秒内连发会得到相等时间戳并被 `TimestampTracker` 判为重放，高频链路要调用方自行保证严格递增（官方要求"每次 ≥ 前值+1"）；多机模式下密钥库未命中 sysid 会回退 `defaultKey`，严格白名单需显式不配该字段；`MavlinkParser` 层仍只切帧不验签（验签发生在 `MavlinkMessage.decode(frame, signer)` 与 `UdpGateway.verifyFrame`）；重放与时间戳回退这两类攻击面只有单元测试证据（link-sim 的画像只篡改签名字节，不会转发一条已签名的原帧）；签名统计只进日志（`signing: verified=/rejected=/unsigned=`），无指标、无告警；未与真机（PX4）联调——"与官方对等"的现有证据是 pymavlink 生成的 5 条已知答案向量逐字节相同 + 本仓自环，不含任何厂商实现。

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