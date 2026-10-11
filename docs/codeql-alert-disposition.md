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

### 当前登记清单

CI workflow 里 `--allow` 的编号与此表一一对应：

| 编号 | 规则 | 等级 | 登记理由 | 该消失的条件 |
|---|---|---|---|---|
| 1 | js/request-forgery | critical | `resolveApiUrl()` 已强制同源 + `/api/v1/` 前缀；`apiUrlGuard.test.js` 13 处覆盖 | 有人去面板 dismiss |
| 2 | java/tainted-numeric-cast | critical | MAVLink COMMAND_LONG param 是 4 字节 float，窄化是协议必需 | 有人去面板 dismiss |
| 5 | java/spring-disabled-csrf-protection | high | STATELESS + 无 Cookie；前提由 `HttpAuthChainTest.csrfPremise_noAmbientCookieCredential` 机器复核 | 有人去面板 dismiss |
| 6 | java/spring-disabled-csrf-protection | high | 同上（第二个 `csrf.disable()` 调用点） | 有人去面板 dismiss |

**维护规则**：每 dismiss 一条，就同时从 `.github/workflows/ci.yml` 的 `--allow`
里删掉该编号，并把上表那行划掉。清单应该只减不增；若确实要新增登记，
必须在此文档补一行"为什么保留"——不允许既不 dismiss 也不登记地堆积。
