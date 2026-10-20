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
mvn -B -pl cloud-backend -Pmutation test-compile pitest:mutationCoverage

# 2) 对基线判红（变异分数是棘轮，不许低于基线）
python scripts/check-mutation-baseline.py \
  --report cloud-backend/target/pit-reports/mutations.xml
```

CI 里对应 job `mutation-gate`（**硬门禁**）。

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

## 基线

`scripts/mutation-baseline.json`（由门禁读）。它是**棘轮**：
分数只能涨，不能跌。要跌就得显式改基线，并在 PR 里说明为什么这次可以接受。
