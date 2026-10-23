# 变异测试：把"测试盯得够不够紧"变成可测量的数

> 这份文档回答一个问题：**我们的测试到底是在"跑到"代码，还是在"盯住"行为？**
> 答案不是覆盖率能给的。

## 为什么需要它

常规覆盖率（jacoco，CI 门禁，阈值 0.65）回答的是：

> 这段代码**被跑到过**没有？

它回答不了：

> 这段代码的**行为被钉住**了没有？

一段被调用了一百次、却只断言了「没抛异常」的代码，覆盖率是满的，
但把它的行为改掉（返回 null、把 `>=` 改成 `>`、删掉一次加密调用），
**没有任何测试会红**。这就是"假绿测试"的一种形态 —— 测例存在、CI 全绿、
但它对回归毫无抵抗力。

**变异测试**换了个问法：把生产代码按规则改坏（变异），看**有没有测试变红**。
杀得掉的变异越多，测试越"盯得住"。

- **变异分数** = 杀掉 / (总数 − 未覆盖) —— 在看得见的变异里，盯住了多少
- **测试强度** = (杀掉 + 未覆盖) / 总数 —— 有多少行为压根没被看见

## 2026-10-21 的审计结果（cloud-backend security 包）

用 PIT 1.15.0 跑 `io.aerofleet.cloud.security.*`（767 个变异体）。
A/B/C 三档**同口径**（各自重新编译、依次单跑、互不干扰）：

| 指标 | 改前(A) | 补测试后(D) | 变化 |
|---|---|---|---|
| 变异体总数 | 767 | 767 | — |
| 杀掉 | 538 | **566** | **+28** |
| 存活（改了没人发现） | 116 | **102** | **-14** |
| 未覆盖（压根没测到） | 111 | **97** | **-14** |
| **变异分数** | **0.8201** | **0.8448** | **+2.5 pp** |
| 测试强度 | 0.8462 | 0.8644 | +1.8 pp |

逐类存活数（A → D）：

| 类 | 存活 | 说明 |
|---|---|---|
| `ApiKeyController` | 21 → 17 | 密钥掩码 / 随机源那一组 |
| `AuthController` | 16 → 12 | 限流器 / 客户端 IP 那一组 |
| `UserController` | 7 → 3 | 建号落库那一组 |
| `ApiKeyEntity` | 3 → 2 | — |

存活变异体里，**四处是真安全缺口**（不是"覆盖率不好看"，是"行为没人盯"）：

| 位置 | 变异 | 意味着 |
|---|---|---|
| `AuthController.isRateLimited` | 阈值 `>=` 改 `>` 存活；"恒不限流"存活 | **登录限流器没有任何测试碰过** —— 限流阈值改成 5 次还是 100 次，现有测试一律全绿 |
| `AuthController.extractClientIp` | `if (trustForwardedFor)` 取反存活 | **是否信任 X-Forwarded-For 没被钉住** —— 开关被取反，攻击者就能靠伪造转发头绕开限流（每换一个头就是一个新桶） |
| `ApiKeyController.maskKey` | 返回 null 存活 | **密钥掩码的结果没人验证** —— 哪天掩码退化成返回明文密钥，库里就留着明文，而测试全绿 |
| `UserController.createUser` | 删掉 `passwordEncoder.encode` 存活 | **口令哈希落库没人验证** —— 哪天那行被误删，明文口令直接进 users 表，测试全绿 |

### 三类要分开记账（这是本轮最值钱的一条）

PIT 报的"存活"里其实混着三种东西，**混着报就会报出不存在的安全缺口**：

| 类别 | 判定 | 本轮实例 |
|---|---|---|
| **真缺口** | 手动把源码改坏 → **测试变红** | 上面那四处 |
| **行为等价** | 手动改坏 → 测试仍绿（因为改坏没改变行为） | `isRateLimited` 的 `BooleanTrue/BooleanFalseReturnValsMutator`：mutator 把 `true` 换成 `true`、`false` 换成 `false` ⇒ 行为没变 |
| **覆盖缺口** | `NO_COVERAGE` | 压根没有测试跑到这段代码（**不是**假绿，是没测） |

本轮对每个存变异体都**手动改坏源码复现过一遍**（`return true`→`false`、
`return false`→`true`、`>=`→`>`、`trustForwardedFor` 取反）—— 新测试全部变红 ⇒
行为确实被钉住了；那两个"存活"是**工具的记账问题**，显式声明无害。

另外两处**属"良性变异"（不改行为）**，显式登记而不是假装是缺口：

- `ApiKeyController.maskKey` 的 `key == null || key.length() <= 8` 分支 ——
  该方法是私有的，唯一调用点传进来的 key 必然很长 ⇒ 那条分支**不可达**，
  变异它等价于没变异。（顺带说明这段是**死代码**。）
- `ApiKeyController.extractClaimAsInteger` 的 `return null` ——
  在当前调用路径上等价。

## 怎么跑

```bash
# 1) 跑变异测试（profile 默认不启用，只有显式 -Pmutation 才跑）
# 刻意**不加 -am**：profile 的 targetClasses 指向 io.aerofleet.cloud.security.*，
# 带 -am 会让 PIT 在上游模块（mavlink-core 等）上各跑一遍，而那边没有匹配的类
# ⇒ 插件报"没找到变异体"把构建打断。
mvn -B -pl cloud-backend -Pmutation test-compile pitest:mutationCoverage  # maven-incantations:ok 只跑 cloud-backend 一个模块，理由见上一行

# 2) 对基线判红（变异分数是棘轮，不许低于基线）
python scripts/check-mutation-baseline.py \
  --report cloud-backend/target/pit-reports/mutations.xml

# 3) 第二个包（surveillance）：**独立 profile、独立基线、独立报告目录**
mvn -B -pl cloud-backend -Pmutation-surveillance test-compile pitest:mutationCoverage  # maven-incantations:ok 只跑 cloud-backend 一个模块，理由见第 2 条
python scripts/check-mutation-baseline.py \
  --report cloud-backend/target/pit-reports-surveillance/mutations.xml \
  --baseline scripts/mutation-baseline-surveillance.json
```

CI 里对应 job `mutation-gate`（**硬门禁**，两个包各跑各的、各判各的；
报告目录分开，否则第二次 PIT 会覆盖第一次 ⇒ 基线张冠李戴）。

## 2026-10-22 的第二轮审计（cloud-backend surveillance 包）

security 包审完之后，同样的方法论搬到**安防包**（`io.aerofleet.cloud.surveillance.*`，
34 个主类 / 14 个测试类）：

| 指标 | security 包（2026-10-21） | surveillance 包（2026-10-22） |
|---|---|---|
| 变异体总数 | 767 | 809 |
| 杀掉 | 566 | **427** |
| 存活 | 102 | **98** |
| 未覆盖 | 97 | **279** |
| 变异分数 | 0.84 | **0.8057** |
| 测试强度 | 0.8473 | **0.8133** |

> **2026-10-23 订正**：这一行的"测试强度"原先按 `(KILLED+NO_COVERAGE)/total` 算（表里当时写的
> 0.86 / 0.8727），与 PIT 自己打印的 `Test strength` **不是一个口径**，而且把"没测到"算成了强度。
> 现按 PIT 口径 `KILLED/(KILLED+SURVIVED)` 重算为 0.8473 / 0.8133（旧值保留在这句话里，便于对照）。
> 口径本身也是门禁的一个 bug，详见下面"坑 5"。

⇒ 这个包的**未覆盖面大得多**（279 个，多半集中在 `surveillance.offline.*` 子包 ——
`EdgeAiTrigger` / `OfflineAlarmCache` / `OfflineAlarmController` 三个类**一个测试都没有**），
这是**覆盖缺口**（压根没测到），与"测了但没盯住"是另一件事（见上文"三类要分开记账"）。

**已确认的真缺口**（逐条手动复现过：把生产代码改坏 ⇒ 现有测试**照样全绿** ⇒
没人盯 ⇒ 才算缺口；行为等价的存活变异体显式声明无害）：

1. **四个厂商适配器的 `validateDevice` 调用被删掉，没有任何测试会红**
   （Hik / Uniview / Dahua / Gb28181 的 `getStreamUrl` / `ptzControl` / `subscribeEvents` /
   `getCapabilities`，合计 14 个存活变异体）⇒ 传 null 设备时从"抛
   `IllegalArgumentException`"退化成 `NullPointerException`；传空 IP 时直接拿空地址去拼流 URL。
2. **`RapidDeployService.extractLastOctet`（设备 ID 里的 IP 末段）没被钉住**
   （11 个存活变异体）⇒ 设备 ID 会变成 `cam-0-...`，同网段设备 ID 撞车、认不出设备。
3. **GB28181 适配器的"只收 GB28181 设备"这条约束没被钉住** ⇒ 别的厂商的设备能被它接走。

⇒ 修法：新增 `SurveillanceValidationContractTest`（6 个用例）把上面三条钉死，
**变异验证**（改坏生产代码 ⇒ 对应用例必须红）：

| 改坏的地方 | 变红的用例 |
|---|---|
| 删掉 `HikAdapter.getCapabilities` 的 `validateDevice` 调用 | `adaptersRejectNullDevice` |
| 删掉 `Gb28181Adapter.getCapabilities` 的 `validateDevice` 调用 | `adaptersRejectNullDevice`、`gb28181AdapterRejectsForeignVendor` |
| 把 `extractLastOctet` 改成恒返回 `"0"` | `generatedDeviceIdCarriesIpLastOctet` |

⇒ 实测效果（同一把尺子、同一个包、只多了这一个测试类）：
**杀掉 409 → 427，存活 116 → 98，变异分数 0.7717 → 0.8057（+3.4pp）**。

⇒ 顺带修正两条我自己的诊断错误：

- `RapidDeployService.validateSubnet` 的调用被删掉（2 个存活变异体）——
  **手动复现后发现是行为等价的**：`OnvifClient.discoverDevices` 里**也**校验空子网，
  所以删掉一处校验，异常照样抛（只是抛的地方变了）⇒ **不是真缺口**，显式声明无害。
- 四个适配器的"空 IP"分支看似不可达（`SurveillanceDevice` 构造器就拒空 IP）——
  但 `SurveillanceDevice.ip` 是**公开可变字段**，构造之后可被改坏 ⇒ 该分支**可达**，
  用例按"构造后把 ip 置 null"来测。

## 2026-10-23 的第三轮：补齐 offline 子包（121 个未覆盖）

第二轮留下的 279 个 NO_COVERAGE 里，**121 个（43%）集中在 `surveillance.offline.*`**：
`EdgeAiTrigger` 35 / `OfflineAlarmCache` 38 / `OfflineAlarmController` 48，三个类**一个测试都没有**。
这一轮补 36 个用例（11 + 13 + 12），**生产代码零改动**。下表是本分支**连同 #140 的 3 例动作契约**合并后的全包实测（那 3 例顺带多杀了 11 个变异体）。

| 指标 | 补测试前 | 补测试后 |
|---|---|---|
| 变异体总数 | 809 | 809 |
| 杀掉 | 427 | **546** |
| 存活 | 98 | **97** |
| 未覆盖 | 279 | **159** |
| 变异分数 | 0.8057 | **0.8400** |
| 测试强度（PIT 口径） | 0.8133 | **0.8491** |

offline 子包本身：121 个变异体里 **117 个被跑到、杀掉 112**，剩 3 个手动复现后定性为无害：

| 存活变异体 | 复现结果 | 定性 |
|---|---|---|
| `getCacheStats` 的 `<` → `<=` | 36 例仍全绿 | **行为等价**：时间戳相等时赋的是同一个值，观测不到差异 |
| `getCacheStats` 的 `>` → `>=` | 同上 | 同上 |
| `cacheAlarm` 的 `evicted != null` → `== null` | 同上 | **不可达**：`while (queue.size() > max)` 之后 `poll()` 必非空；变异只会在并发抽干队列时制造 NPE，测试无法稳定触发 |

还有 4 个变异体仍未覆盖（日志/防御分支），没有硬凑用例；另有 2 个超时。
更值钱的是：这一轮撞出了门禁自己的一个口径 bug（见下面"坑 5"）。

## 2026-10-23 的第四轮：surveillance 收口——97 个存货逐条分诊

第三轮结束时 surveillance 包还挂 **97 个存活**（分数 0.8400 的主要拖累）。
这一轮不再扩覆盖面，只做一件事：把 97 条逐个定性——**真缺口的补测试杀掉；
确实观测不到差异的，逐条给出"为什么测不到"的证据**，在册不再有"未定性"。
改动仍然全部落在测试，生产代码零改动。

新增 6 个契约测试文件 + 1 个测试工具（`LogWiretap`：try-with-resources 挂在
logback root 上拦日志——"跳过必须告警/全好不许告警"这类可观测面以前从没被断言过），
扩展 8 个既有测试类，共 **49 个新用例**：

| 测试 | 例数 | 钉住的契约 |
|---|---:|---|
| `VendorAdapterPrefixContractTest` | 6 | 四适配器 + `OnvifClient` 的 CIDR / 不带 `/` / 无点 三个形状逐位精确；`StreamUrlOrigin` 双值语义 |
| `SurveillanceRegistryTenantContractTest` | 7 | global/租户两套读口径的**精确成员资格**与按 id 排序；prune(0) 合法、hb=0 恒新鲜、fresh 不被 prune 顺带删 |
| `OnvifVendorAdapterContractTest` | 6 | 适配器层的 device 校验不许被删（null/blank 均抛指名道姓的 IllegalArgumentException，而不是往下传成 NPE）；PTZ 委托逐字全等 |
| `SurveillanceDeviceIdentityContractTest` | 3 | 端口 [1,65535] 边界；equals 按 id 对称；capabilities 永不为 null |
| `SurveillanceControllerSseContractTest` | 3 | 未知设备流立即错误终止；`AsyncContext.complete()` 必须触发厂商注销；断开重订阅不遗留 |
| `VideoFusionRecordingServiceTest` | 4 | start 的拒绝条件；stop 的时长=结束-开始（拿真实时间戳字段算，不是发明值） |
| `SurveillanceControllerTest`（扩展） | +4 | /mjpeg 错误体定长 + no-store + Pragma；端口/通道/在线通道三处边界；注册时真抓能力；size=1000 上界 |
| `MjpegStreamServiceTest`（扩展） | +4 | fps=0 走兜底不炸；SOI 三字节判定；fps=1 第二帧节流下限 ≥400ms；第 3 帧照常递增 + 每段 flush + 每帧尾 CRLF；中断后返回且**保留线程中断标记** |
| `Gb28181ShapeTest`（扩展） | +4 | 目录跳过必须 warn、全好不许 warn；PTZ 别名箭头与直连命令的差别；订阅句柄形状；manufacturer 显式/缺省 |
| `OnvifClientTest`（扩展） | +2 | 订阅前参数校验不许被删；端口边界（0 拒、65535 收） |
| `VendorAdapterRegistryTest`（扩展） | +2 | hasAdapter 未注册/null 必须 false（不是恒真）；null 句柄温和忽略不 NPE |
| `SurveillanceStatusPusherTest`（扩展） | +1 | 新鲜设备 uptimeSec 精确为 0（除→乘 / 减→加 两种变异都会给出天文数字） |
| `RapidDeployServiceTest`（扩展） | +2 | 尾段畸形（`.8`/`x.`/`1.2.3.100`）；mock 掉客户端也必须自校验子网；完成日志计数逐项钉死（计数断言并入混合场景用例） |
| `VideoFusionControllerTest`（扩展） | +1 | userinfo 脱敏输出**全等**（@ 后必须从主机位起）；scheme 空边界照常脱敏 |

装配口径与 #139 相同：真实适配器 + 真实客户端；仅当"必须把下游干扰掉才能证明
本层责任"时用 mock（快速部署的子网自校验、SSE 契约对厂商客户端的 spy）。

### 实测

- 定点点名 4 批共 331 例全绿（214 + 10 + 40 + 67；首跑 4 处失败如实修复，见"坑 6"）。
- A/B 批量实验（`tools/mutate-verify.py` 顺序跑 7 组，退出码存档
  `tools/ab_batch_surv_results.json`）：**X1 排序 key 突变为空——被杀**
  （按 id 排序确实差出顺序，`listDevicesByVendor_sortedById` 的有序断言抓到）；
  K1（prune 的 compute 返回 null ⇒ 整个条目被删）**被杀**；
  E1-E5（sleep 边界 / 两处前缀边界 / Capability null 分支 / onError 注销）**全部按预期存活**
  ——存活的留作等价性证据（行为分叉在测试观测面之外）。
- 变异复测（同包同尺，XML 精确对账）：杀掉 **546 → 636**、存活 **97 → 22**、
  未覆盖 **159 → 144**（总 809、超时 7、运行错误 0 均不变）；分数 **0.8400 → 0.9564**、
  强度 **0.8491 → 0.9666**。注意 PIT 汇总行把 TIMED_OUT 计进 "Killed"
  （控制台打 643 = 636 + 7），**解析以 XML 为准**（见"坑 7"）。
- 增量账目对得严丝合缝：**+90 = 计划杀 75（含 X1）+ 新覆盖顺带被杀 11 + 4 个"新面孔"补杀**。
  4 个新面孔是本轮新用例把原本 NO_COVERAGE 的 15 个变异体跑活后浮出水面的
  （pump 的中断重置、writePart 的尾 CRLF、setOnlineCameras 的 n==totalCameras 边界、
  hashCode 恒 0）——发现即补杀再复测，**存活恰好收敛回 22 条已定性清单**。
  基线地板：分数 0.95、强度 0.96。

### 定性为无害的 22 条（每条都有"为什么测不到"的证据）

| 存活变异体 | 复现 | 定性 |
|---|---|---|
| 四路前缀提取的 `lastDot > 0` → `>= 0`（Dahua/Hik/Uniview/`OnvifClient` 各 1） | A/B E2/E3：变异跑下测试仍全绿 | **行为等价（值域论证）**：只可能在 addr 以 `.` 开头（空主机名）时分叉；discover 的入参由租户表与设备表约束，不存在该形状的真实输入 |
| `MjpegStreamService.pump` 的 `sleep > 0` → `>= 0` | A/B E1：变异跑下 12 例全绿 | **行为等价**：sleep==0 时 `sleep(0)` 与"重对表"只差纳秒级；帧节奏的机器可读面（fps=1 的 400ms 下限）已被钉住 |
| `DeviceCapability.normalize` 的 raw==null 分支返回值 → `emptySet` | A/B E4：变异跑下全绿 | **行为等价**：该分支入口事件下 out 恒为空集；`unmodifiableSet(空)` 与 `emptySet` 都不可变、`add` 同抛 UOE |
| SSE 的 `onTimeout` 注册与 `onError` 注册/注销 | A/B E5：变异跑下全绿 | **依赖容器不可达**：MockAsyncContext 无法 fire error/timeout（只有真容器断连/超时才触发）；清理体与 onComplete 同形且 onComplete 路径已被杀掉 |
| SSE 建立连接时立即发送的 `stream-established` 注释行 | mock 容器下头部提交时机不可观测 | **不可观测**（注释行的语义已由 asyncStarted 路径间接钉住） |
| 心跳线程工厂的 `setDaemon` 与 `return null`（×2） | 测试窗口 <15s，心跳任务不 fire，工厂不执行 | **不可达**（守护属性只影响进程退出行为；NPE 只在建线时发生） |
| `VideoFusionController.maskRtspCredentials` 的三个 `at`/`slash` 边界（L245 ×3） | 变异跑下 13 例全绿 | **值域不可达**：分叉需要 `at == 0`（'@' 在 URL 第一位）、`slash == 0`（'/' 开头）或 `at == slash`（'@' 与 '/' 是同一字符），合法 `scheme://userinfo@host` 里三者都不会出现 |
| `SurveillanceController` 自带 username 掩码的 null 护栏取反（L830） | /stream 的 url 恒非空（适配器先验 IP 非空 + 模板拼装） | **不可达**（与 VideoFusion 版同形，后者的行为已被全等用例钉住） |
| `SurveillanceDeviceEntity.fromDevice` 的 capability null 判断取反 | 设备侧 capabilities 恒非 null（identity 契约钉住） | **不可达** |
| `Registry.pruneStaleDevices` 年龄比较 `>` → `>=` | 需要 age == timeoutMs 精确相等才分叉（毫秒级竞态） | **不可稳定测**（边界点两侧皆合理语义；命中概率 1/timeout） |
| `Registry.pruneStaleDevices` 日志 ago 参数 减→加 | 日志诊断字段；机器可读面（返回值+落库状态）已被 persist 用例钉住 | **等价-日志参数** |
| `Registry.listDevicesByVendor(null)` 返回 `new ArrayList` → `emptyList` | 调用方全部只读（快照语义），可变性差异无人观察 | **行为等价**（仅可变性） |
| `OfflineAlarmCache` ×3 | 第三轮已复现（转引上表） | **行为等价 / 不可达** |

13 行摘要覆盖 22 个变异体：4（前缀边界）+ 1（sleep）+ 1（Capability null）+ 2（SSE 注册）
+ 1（established 行）+ 2（心跳工厂）+ 3（mask L245）+ 1（L830）+ 1（Entity）
+ 1（prune 边界）+ 1（prune 日志）+ 1（emptyList）+ 3（OfflineAlarmCache）。

## 三条容易踩的坑（都踩过）

1. **PIT 找不到 JUnit 5 测试**：只打一句
   `JUnit 5 is on the classpath but the pitest junit 5 plugin is not installed`，
   然后跑出一堆"未覆盖"。必须在 `pitest-maven` 的 `<dependencies>` 里加
   `org.pitest:pitest-junit5-plugin`（根 pom 的 `mutation` profile 里已经加了）。
2. **A/B 对照不干净**：第一份基线是用**陈旧的 test-classes** 测的，
   重新 `mvn test-compile` 之后再测，分数变化里混着"产物新旧"的因素。
   凡是"改完 X 再测一次"的对照，两边必须**各自重新编译**。
3. **并发跑两个测量会互相污染**：两个 PIT（一个 standalone、一个 Maven 插件版）
   都写 `cloud-backend/target/`，Maven 那次 `test-compile` 会把另一次
   **正在读**的 test-classes 冲掉 ⇒ 261 个变异体变成 `RUN_ERROR`
   （"一个测试都没跑到"）。我差点把"变异分数 83% → 49%"读成
   "**我的新测试把分数弄崩了**" —— 其实是我自己污染了数据。
   同理：**读报告要等它写完** —— PIT 是逐个 `<mutation>` 追加写的，
   **半截文件有时也能解析成功**，于是你会拿到一个"看起来完整、其实只有一部分"的结果。
   门禁脚本因此把 `RUN_ERROR` 单独列出来，数量异常时会提示"先查测量环境"。
   光靠提示不够：**分数是 `killed/(total−noCoverage)`，分母缩水时分数反而更容易变高**——
   只覆盖了一小部分类、把那几个全杀掉，就能得到接近 1.0 的分数。
   所以门禁还会校验**变异体总数不得低于基线的 95%**（`total` 字段），
   缩水超 5% 直接判红，并提示先查 `targetClasses` / 构建日志 / 是否测量中途改了源码。
   只有确认是**代码合法删减**，才去更新基线里的 `total`。
4. **两个包共用一个报告目录 ⇒ 基线张冠李戴**：PIT 默认都写
   `target/pit-reports/mutations.xml`，连着跑两个 profile 时**第二个会覆盖第一个的**。
   ⇒ 两个 profile 各自配了 `reportsDirectory`（`pit-reports` / `pit-reports-surveillance`）。
5. **指标名与工具同名输出不同口径 ⇒ 门禁把"没测到"算成"强度高"**（2026-10-23 撞到）：
   门禁里的 `testStrength` 一直算 `(KILLED+NO_COVERAGE)/total`，而同一次 PIT 汇总行打印的是
   `Mutations with no coverage 165. Test strength 83%`（= `KILLED/(KILLED+SURVIVED)`）。
   后果不是"数字略有出入"，而是**方向反了**：给零覆盖子包补测试时，杀掉 119 个、未覆盖少 120 个、
   变异分数 +3.4pp，这个指标却从 0.8727 掉到 0.8714 ⇒ 门禁判红，奖励"不测"。
   ⇒ 改成 PIT 口径；`--self-test` 加了一条**能区分两种口径**的夹具
   （50 killed / 15 survived / 35 no-coverage：旧口径 0.85 假绿，新口径 0.769 必须红）。
   教训：自测夹具如果两种实现都能通过，它只证明"代码能跑"，不证明"口径对"。
6. **未初始化的 emitter 上 `complete()` 是 no-op（2026-10-23 撞到）**：给 SSE 回调
   （onCompletion 里注销厂商订阅）写直调测试时，`controller.subscribeEvents(id)`
   返回的 emitter 还没进 MVC 初始化，`emitter.complete()` 只把内部 complete 标志
   置位，**回调不会同步触发**——测试里 verify 注销就永远落空，而且看起来像
   "回调根本没注册"。要真实触发得走 MockMvc 的 async 路径：先
   `perform(...)` 等 `request().asyncStarted()`（此时返回值处理器已挂上 emitter），
   再 `result.getRequest().getAsyncContext().complete()`——mock 容器把这次完成
   同步派发给回调链。onError/onTimeout 在 mock 容器里没法 fire（坑在"定性 4"里），
   但 onComplete 的注销语义这样就能钉死了。
7. **PIT 的 XML 是"最后一刻才写盘"的（2026-10-23 撞到）**：报告 XML 在覆盖阶段
   一结束就先被**清空占位**（0 字节文件，mtime 是覆盖完成时刻），
   完整内容要等到 BUILD SUCCESS 那一瞬才落盘。中途读它的下场：拿到 0 字节、
   或者更糟——拿到**上一轮残留的旧清单**，做出"变异体没被杀"的假结论
   （本轮就把 632/26 的旧账当过新结果，追了半天"新面孔怎么又复活了"的幽灵）。
   另有一处口径差异：**PIT 汇总行把 TIMED_OUT 也算进 "Killed"**——控制台打
   `Killed 643`，XML 里 KILLED=636、TIMED_OUT=7，643 = 636 + 7，不是玄学。
   规矩：**永远等 BUILD SUCCESS，解析永远以 XML 为准**。

## 基线

- `scripts/mutation-baseline.json` —— security 包（由门禁读）。
- `scripts/mutation-baseline-surveillance.json` —— surveillance 包。

两份都是**棘轮**：分数只能涨，不能跌。要跌就得显式改基线，并在 PR 里说明为什么这次可以接受。
**两份分开记**——一个包的涨分不能掩盖另一个包的退步。
基线里的 `total`（变异体总数）同时充当**报告完整性锚点**：实测总数低于它 95% 判红。
`--write-baseline` 只允许**抬高**分数；降低分数必须手改文件（改动会进 PR diff 被看见）。

**数值向下取整到 2 位小数**（分数与强度都是），留 ~0.3pp 的运行间抖动余量。
2026-10-22 的 surveillance 基线曾按 4 位小数（0.8057）写，CI 实测 0.8000
（同一份代码、本地 0.8057）⇒ 0.57pp 的抖动被当成"回退"判红。抖动不是退步，别用精度去惩罚它。

### 基线里的计数不是判据，但必须对得上账（2026-10-24 补）

判据只有两条分数（`mutationScore` / `testStrength`）加 `total` 完整性锚点；
`killed` / `survived` / `noCoverage` / `timedOut` / `runError` 只是留档。**留档也要自洽**：
这五列之和必须等于 `total`，且**只要写了其中一列就必须五列齐全**——否则"删掉一列"
就等于"悄悄关掉这条判据"。`--self-test` 除合成夹具外，还直接读仓里那两份**真**基线
核对这条不变式（合成夹具拦不住"真文件本身写错"），而 `--self-test` 是每轮 CI 必跑、
不需要 PIT 报告就能判红的一步。

surveillance 那份挂过一处差 1：主块记 **636/22**（本地 XML 快照），CI 在 run `40002936758`
（head `293bf74`）打的是 **635/23**，门禁于是每轮都印一句
`存活变异体 23 个（基线登记 22 个）`。查清的结论是**不是抄错数**：两侧各自都能加回 809
（636+22+144+7+0 = 635+23+144+7+0），PIT 控制台行也同步差 1（本地 643 = 636+7 超时、
CI 642 = 635+7）⇒ 是同一个变异体在两次运行里从"被杀掉"翻成"存活"，与上面那条抖动余量同源。

处置**不是**把 636 改成 635（那会让文件声称一个本地从没测出的数，下次本地跑又"对不上"），
而是把 CI 那次真值单独记进基线的 `ciObserved`（连 `runId` / `headSha` / 来源 job，可复核），
门禁打印存活数时改与 `ciObserved` 对账——"22↔23"于是从一条每次都出现的假噪音，
变成**只有真漂移才会亮**的账（仍不判红，但明写"别长期挂着"）。
