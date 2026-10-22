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
