# CodeQL 告警逐条处置（为什么"长期 open"不等于"没修"）

> 登记时间：2026-10-11。数据源：`gh api repos/Levango7/NexusSky/code-scanning/alerts?state=open`。
>
> **背景**：4 条告警全部停留在 **2026-09-25** 创建后从未更新。逐条对照代码后确认
> **4 条的代码处置都已存在**，缺的只是有人去关掉面板上的条目。
>
> 这类"登记滞后于代码"在本仓已出现第三次（前两次：ROADMAP C4/C5 的描述、
> C1-C5 的"待做/已完成"翻正）。共同点是：**滞后发生在"状态登记处"，而不在代码**。
> 对告警面板而言，代价比文档更直接——永远 open 的高危条目会让"还有 4 个没修"
> 变成长期噪音，将来出现**真**新告警时会被淹没在这 4 条里。

## 2026-11-08 新增一条（#264 大华 ICC 对接引入，**协议约束下的安全例外**）

### `java/rsa-without-oaep`（CodeQL: "RSA/ECB/PKCS1Padding without OAEP padding"）— `DahuaIccClient#rsaEncrypt`

**是什么**：`Cipher.getInstance("RSA/ECB/PKCS1Padding")`——PKCS#1 v1.5 填充，
理论上面临 padding oracle 类攻击。

**为什么不能改成 OAEP**（改 = 让功能坏掉，不是修缺陷）：

1. **大华官方文档《RSA密码加密》给的 demo 就是 `Cipher.getInstance("RSA")`**
   （Java 的 `"RSA"` 变换即 PKCS#1 v1.5）；同文档的 Python 示例用的是
   `Crypto.Cipher.PKCS1_v1_5`。换成 OAEP 后平台侧解不开 ⇒ 登录必然失败。
2. **该公钥是平台主动下发的一次性会话公钥**：文档明确「公钥有效期 10 分钟，
   认证成功后立即失效」「为了防止请求重放，所有使用秘钥对，请求对应业务功能成功后，
   会自动释放掉」。这不是长期复用的密钥体系，v1.5 的弱点在这个语境下不构成
   可重放通道。
3. 平台侧持私钥；padding oracle 的风险落在**厂商解密实现**上，不在本客户端可控范围。

**什么时候才允许改**：大华开放平台文档给出 OAEP 示例，或在其联调环境上实测
OAEP 登录通过。在那之前改成 OAEP 是把"更安全但连不上"当成"更安全"，是负优化。

**代码里怎么记的**：`DahuaIccClient#rsaEncrypt` 方法上带
`@SuppressWarnings("java/rsa-without-oaep")`，方法 javadoc 里写全三条理由与放行条件，
与本文档互相引用。

**这条是怎么关掉的（2026-11-11 实测结论）**：**注释形式的抑制对本条无效**——
按 CodeQL 通用文档试了两种位置（紧邻上一行的 `// codeql[java/rsa-without-oaep]`、
同一行行尾的 `// codeql[...]`），CI 的 CodeQL 检查**两次都仍报同一条告警**
（annotation 指向 `DahuaIccClient.java` 里 `Cipher.getInstance` 那一行）；
换成方法上的 `@SuppressWarnings("java/rsa-without-oaep")` 注解后**仍然报**
（注解在本仓只对已在 alerts 库里的历史告警有效，本条是 PR 分析新引入的，
PR 分析的告警不落库 ⇒ 没有可 dismiss 的对象）。

**最终采用的是路径级 query 过滤**：`.github/codeql/codeql-config.yml` 里

```yaml
query-filters:
  - exclude:
      id: java/rsa-without-oaep
      paths:
        - "**/io/aerofleet/cloud/surveillance/DahuaIccClient.java"
```

> **规则 id 写错 = 过滤没生效，且症状一模一样**：第一次按 `java/insecure-crypto`
> 写过滤，CI 仍报同一条告警，一度被误判成"路径级过滤机制对本条不管用"。
> 实际原因只是**告警的真实 rule id 是 `java/rsa-without-oaep`**
> （CodeQL 2.27.4，description "Use of RSA algorithm without OAEP"）。
> 告警的 message 文本（"This specification is used to initialize an RSA cipher
> without OAEP padding"）**不包含 rule id**，只看 message 会一直找错规则。
> 查 rule id 的正确入口：`gh api repos/<repo>/code-scanning/alerts?ref=refs/pull/<n>/merge`
> （注意 PR 告警挂在 **merge ref** 上，`refs/pull/<n>/head` 查不到）。

并在 `ci.yml` 的 `codeql-action/init` 步骤用 `config-file:` 引入。
**为什么用它而不是 `--max-open 1`**：`--max-open` 是"全仓允许 N 条 open 告警"，
N=1 意味着**任何地方**多出一条新告警都会被放过——它拦不住新问题；
路径级过滤只放过"这一个 query 在这一个文件上"，同文件的其它 query、其它文件的
同 query 全部照常生效。两者不是一回事。

代码里同时保留 `@SuppressWarnings("java/rsa-without-oaep")` 与完整理由注释
（与本仓另两条例外的写法一致，也是 IDE 侧可见的标记）。

**为什么这条只能人工关一次、不能在 API 里 dismiss**：PR 分析产生的告警不落
code-scanning alerts 库（`gh api repos/.../code-scanning/alerts?state=open` 对
`refs/heads/feat/dahua-icc-contract` 与 `refs/pull/264/head` 都查不到这条），
所以面板上没有可点的对象；`ci.yml` 的守卫按 alerts 库计数，本条靠**代码侧抑制**
不进库、不占 `--max-open` 配额。

**连带的诚实说明**：本仓 `README`/协调板的真机联调项**不变**——此条不推进真机打通，
只是把"公开文档契约层 + 一次性命中公钥"的安全边界写明。

## 四条逐条说明

### #1 `js/request-forgery`（critical）— `gcs-web/src/api.js:103`

**为什么不是缺陷**：`jsonFetch` 会自动附带 `Authorization: Bearer`，若 `url` 可被
外部值带出本前端，等于把令牌送到别的宿主。

**已修的代码**：`resolveApiUrl()`（`gcs-web/src/api.js:72-86`）：

- 必须先通过 `new URL(url, ORIGIN_PROBE)` 解析，origin 不等于哨兵值即拒
  （挡住绝对 URL 与协议相对 `//host`）
- 再要求 `pathname` 以 `/api/v1/` 开头（挡住靠 `..` 段爬出本站）
- 返回值只含 `pathname + search`，host 部分被彻底丢弃

**靠什么守着**：`gcs-web/test/apiUrlGuard.test.js`（13 处命中，覆盖跨源拒绝、
非 API 路径拒绝、非法类型、`..` 爬出等）。

### #5 / #6 `java/spring-disabled-csrf-protection`（high x2）— `SecurityConfig.java:67 / :87`

**为什么不是缺陷**：全站 `SessionCreationPolicy.STATELESS`，**既不下发也不读取
Cookie**。浏览器不会自动附带凭据，跨站请求因此拿不到身份——CSRF 的利用前提
（ambient credential）在本仓不成立。

**前提由机器复核，而非口头声明**：
`HttpAuthChainTest.csrfPremise_noAmbientCookieCredential`
（`cloud-backend/.../security/chain/HttpAuthChainTest.java:146`）
伪造 `JSESSIONID` 的写请求仍 401，且响应不下发 `Set-Cookie`。
两处 `csrf.disable()` 均带 `@SuppressWarnings("java/spring-disabled-csrf-protection")`
并在注释里写明"将来任何一处改成 cookie/会话型凭据，那条测试先红，这里就必须改回开启"。

**这条的正确关闭方式是 `Won't fix`，并注明上述测试名**——
它不是一个"待修缺陷"，而是一个"由测试守护的设计决策"。

### #2 `java/tainted-numeric-cast`（critical）— `DroneCommandService.java:162`

**为什么不是缺陷**：MAVLink `COMMAND_LONG` 的 param 在线路上就是 **4 字节 float**。
`takeoff(int, double alt)` 里的 `(float) alt` 是协议要求的窄化，不是精度疏漏。

代码注释原文：「double→float 不是疏漏：MAVLink COMMAND_LONG 的 param 在线路上
就是 4 字节 float，窄化是协议要求的（CodeQL java/tainted-numeric-cast #2 在此是
必然而非缺陷）」，并带 `@SuppressWarnings("java/tainted-numeric-cast")`。

**靠什么守着**：`DroneCommandServiceTest` 的 takeoff 用例（未知 sysid 抛
`CommandException`）确保该方法行为仍被回归覆盖；协议形状由
`MavlinkMessageInfo.crcExtraOf` 与签名向量等价性测试间接钉住。

## 如何关闭（人工一次，需 repo:admin 或 write:security-events）

```bash
gh api -X PATCH repos/Levango7/NexusSky/code-scanning/alerts/<n> \
  -f state=dismissed -f dismissed_reason="false positive" \
  -f dismissed_comment="处置依据见 docs/codeql-alert-disposition.md"
```

| # | 规则 | 建议 dismissed_reason | comment 里应点名的证据 |
|---|---|---|---|
| 1 | js/request-forgery | `false positive` | `resolveApiUrl` + `apiUrlGuard.test.js` |
| 5, 6 | spring-disabled-csrf-protection | `won't fix` | `HttpAuthChainTest.csrfPremise_noAmbientCookieCredential` |
| 2 | tainted-numeric-cast | `won't fix` | MAVLink COMMAND_LONG 4 字节 float 协议要求 |

## 防止再变成噪音：守卫

`scripts/check-codeql-alerts.py` 在 CodeQL job 末尾检查 open 告警数。

设计取向：**"默认全关、例外显式登记"**，不是"一刀切阈值 0"。

- `--allow <编号>`：登记某条不计入 open 数。**登记 ≠ 已修**，只是"已知且有据"。
- 未登记且超出 `--max-open`（默认 0）即红。
- 取数失败（无 token、gh 报错、**GitHub 限流返回对象**）返回 **2**，
  绝不兜底成"0 条"——不让"没查到"伪装成"没问题"。（这一条是实现时真实踩到的：
  把限流对象当空数组就会静默放行，由负向验证抓到。）

### 当前登记清单（2026-10-11 已清零）

4 条已全部去 GitHub 面板 dismiss（#1 false positive；#2/#5/#6 won't fix，
理由与证据见上）。CI workflow 的 `--allow` 登记随之清空，守卫回到
"默认全关"：**再出现 open 即红**，将来确需保留某条必须显式提
`--max-open` 并在本表登记理由——不允许悄悄堆积。

| 编号 | 规则 | 等级 | 关闭方式 | dismiss 理由（写在面板上的） |
|---|---|---|---|---|
| 1 | js/request-forgery | critical | false positive | `resolveApiUrl()` 强制同源 + `/api/v1/` 前缀；`apiUrlGuard.test.js` 13 处覆盖 |
| 2 | java/tainted-numeric-cast | critical | won't fix | MAVLink COMMAND_LONG param 在线路是 4 字节 float，窄化协议必需 |
| 5 | java/spring-disabled-csrf-protection | high | won't fix | STATELESS + 无 Cookie；前提由 `HttpAuthChainTest.csrfPremise_noAmbientCookieCredential` 机器复核 |
| 6 | java/spring-disabled-csrf-protection | high | won't fix | 同上（第二个 `csrf.disable()` 调用点） |

**登记的失效条件反过来也成立**：若将来有人把这些代码改坏（例如
`resolveApiUrl` 被绕过、认证改成 cookie 会话型），CodeQL 会产生**新**
alert，守卫立刻红——登记的是历史 disposition，不是永久免罪。

**维护规则**：每 dismiss 一条，就同时从 `.github/workflows/ci.yml` 的 `--allow`
里删掉该编号，并把上表那行划掉。清单应该只减不增；若确实要新增登记，
必须在此文档补一行"为什么保留"——不允许既不 dismiss 也不登记地堆积。

## 2026-11-12 追加：#93/#94 复现、面板处置与"源内抑制结构性惰性"根因

> 数据源：`gh api repos/Levango7/NexusSky/code-scanning/alerts?state=open`（2026-11-12
> 读回：open=0；#93/#94 均 state=dismissed，理由与 comment 已读回核实）。

### 同一对告警在新编号下复现

行号随 2026-10-11 之后的提交漂移（:103→:138、:162→:217），规则与代码语义未变，
已按上表同理由在面板 dismiss：

| 编号 | 规则 | 等级 | 位置（现） | dismissed_reason | 依据 |
|---|---|---|---|---|---|
| #93 | js/request-forgery | critical | `gcs-web/src/api.js:138` | false positive | 同 #1：`resolveApiUrl()` 同源强制 + `/api/v1/` 前缀；`apiUrlGuard.test.js` 13 处覆盖 |
| #94 | java/tainted-numeric-cast | critical | `cloud-backend/src/main/java/io/aerofleet/cloud/mission/common/DroneCommandService.java:217` | won't fix | 同 #2：MAVLink COMMAND_LONG param 在线路是 4 字节 float，窄化协议必需 |

### 为什么仓内的源内抑制一直是"惰性"的

两处抑制写法本身都符合 CodeQL 规则（`// codeql[js/request-forgery]` 独占一行、贴告警行
正上方；`@SuppressWarnings("codeql[java/tainted-numeric-cast]")` 注解整个方法），但
**默认查询集根本不跑抑制查询**：

1. 默认配置 = `javascript-code-scanning.qls` / `java-code-scanning.qls`（上游 github/codeql
   仓原文），内容只有 `- queries: .` + `- apply: code-scanning-selectors.yml`；
2. `misc/suite-helpers/code-scanning-selectors.yml` 的 include 规则只收 kind ∈
   {problem, path-problem, alert, path-alert} 等，**`alert-suppression` 不匹配任何规则**；
3. 实测（读 CodeQL job 日志，2026-11-12）：js 轮评估 89 条、java 轮 80 条查询，零条
   alert-suppression；仓内无自定义配置（augmented user-config 为 `{}`）；近 12 个
   codeql-cli-binaries 版本无相关变更 ⇒ 不是版本回归。
4. 结论：抑制查询从未执行，写法对错不再是变量。这是**默认管线的结构性缺口**。

完整证据链（含上游原文逐字引用）见 `.coord/STATUS.md` 2026-11-12T07:44 条目。

### 修复（本 PR，分支 fix/codeql-alert-suppression-queries）

`.github/workflows/ci.yml` 的 codeql job：`init` 的 `packs` 输入按矩阵语言叠加
`codeql/java-queries:AlertSuppression.ql, codeql/java-queries:AlertSuppressionAnnotations.ql` /
`codeql/javascript-queries:AlertSuppression.ql`（逗号分隔；路径为包内相对路径——
`qlpack.yml` 就在 `java/ql/src/`，`AlertSuppression.ql` 位于包根；语义经 codeql-action
v3 源码 `parsePacksFromInput` 核实：单语言矩阵、逗号分隔、无 `+` 前缀时只覆盖配置文件的
`packs` 键，本仓该键为空 ⇒ 默认查询套件照常跑，抑制查询纯增量）。
与 #264 引入的 `config-file`（query-filters 排除）正交：前者加查询、后者减排除，互不影响。
本 PR 不动守卫脚本（`--require-data` 是另一笔）。

**为什么不是 `queries: +java/alert-suppression,...`**：`queries` 输入里的单斜杠裸规则 id
会被 CodeQL CLI 当外部仓库 specifier 拒绝——`A fatal error occurred: Specifier for
external repository is invalid: java/alert-suppression`，init 退出码 2（run 43137211970
实证，2026-11-12）。`packs` 输入是 codeql-action 为此提供的通道，真实生产配置实证：
crate/crate-python（`packs: "codeql/${{ matrix.language }}-queries:AlertSuppression.ql"`）
与 Dispatcharr/Plugins（配置文件路由，含 java/javascript 双语）。

合并后下一轮扫描即实证：抑制查询进入评估集，且复测
`code-scanning/alerts?state=open` 不得出现 #93/#94 的新编号。注意当前守卫是
`--max-open 1`（#264 的大华例外，见 2026-11-08 节），单条重现不会立刻红——
以面板 open 列表复测为准，不以守卫绿红为准。面板 dismiss 记录不受影响
（dismiss 永久保留）——本修复让**将来**的源内抑制真正生效。
