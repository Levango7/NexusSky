# ROADMAP C 系列「剩余缺口」两处偏差（2026-10-10）

> 由 OpenCode 在 C5（PR #23）与 C4（PR #25）两轮核查中发现。
> **只列我有代码实证的**，没核过的一条不写。
> 给后续改 ROADMAP 的人：这两处的实际边界比我原先写的窄，照原文字面理解会得出
> 「功能缺失」的错误结论，从而去做重复或低价值的工作。

---

## 偏差一：C4「遥测热态不落库，重启后位置只能从 flight_log 重建」

### ROADMAP 原文（`ROADMAP.md:323-329`）

> ① **C4 的"主数据"只在 provisioning 层面落库，遥测热态仍是内存**：
> `DeviceRegistry` 有 `aerofleet.device-registry.persist`（base 默认 false、
> prod 显式 true），但落库的只是 `devices` 表的**登记/在线/租户**三列
> （`DeviceEntity` 的 online/lastSeen/tenantId）；`DroneSnapshot` 的
> 电量/经纬/姿态/模式等 volatile 字段不落库——它们本就属高频瞬时态，
> 落库也无查询价值，但意味着**重启后历史位置轨迹只能从 `flight_log` 重建**，
> 不能从注册表恢复；

### 代码实测

| 原文断言 | 实测 |
|---|---|
| `drone_last_known_position` 表（V5）已由 `FlightTrackStore` 写入——节流写、`@PreDestroy` 兜底、`@PostConstruct` 启动加载。**这条链路是通的** | ✅ 已在 `C4TelemetryRestoreTest` 中用 4 例钉住 |
| `devices` 表只存 provisioning 三列 | ✅ 准确，这部分没问题 |
| 「位置/电量不落库，重启后只能从 flight_log 重建」 | ❌ **不成立**。位置与电量走的是 `drone_last_known_position`，不是 `devices`，且重启后能由 `@PostConstruct` 恢复 |
| 「模式/姿态等丢失」 | ⚠️ **部分成立**：真正丢的只有 `mode` / `armed` / `protocol` 三个字段 |

### 两个非显而易见的行为（改这条前必须知道）

1. **落库是异步的**：走 `BatchedWriteQueue`（50 条一批 / 200ms 一刷）。
   `addPoint` 返回后立即查库**必然为空**——不是没写，是没刷。
2. **恢复值最多滞后一个节流周期**：`PERSIST_INTERVAL`(=10) 触发时写的是
   「那一刻的那个点」，所以重启取回的**不是**最后写入的那个点，最多差 10 个点
   （20Hz 遥测下约 0.5s）。

### 建议改法（供参考，未替 ZCode 决定）

把「重启后历史位置轨迹只能从 flight_log 重建」改为：
「重启后**最后已知位置与电量**可由 `FlightTrackStore` 恢复（有界滞后一个节流周期）；
**完整轨迹历史**仍在内存，需从 `flight_log` 重建」。窄，但准确。
若认为补存 `mode`/`armed`/`protocol` 值得，另立任务——我判断收益低于其 schema 与热路径成本（设备恢复后本就是 offline 等心跳）。

---

## 偏差二：C5「`MavlinkParser` 层仍只切帧不验签」

### ROADMAP 原文（`ROADMAP.md:329-331`）

> ② **C5 签名出厂仍明文**——`mavlink.signing.enabled` 默认 false 且
> 无 profile 配置，密钥/口令无轮换端点，`MavlinkParser` 层仍只切帧不验签；

### 代码实测

| 原文断言 | 实测 |
|---|---|
| `mavlink.signing.enabled` 默认 false | ✅ 属实（`MavlinkSignatureConfig:33`），且**我刻意没有改**——见下 |
| 无 profile 配置 | ✅ **当时属实，PR #23 已修**：四个 `mavlink.signing.*` 现已显式落进 4 个 profile（此前只存在于 `@Value`，任何 profile 都查不到） |
| 密钥/口令无轮换端点 | ✅ **当时属实，PR #23 已修**：新增 `SigningKeyStoreWriter`（原子落盘）+ `GET/POST /api/v1/mavlink/signing/*` |
| 「`MavlinkParser` 层仍只切帧不验签」 | ❌ **不准确**。`MavlinkParser.java:77,106-118` 完整解析 13 字节签名块（linkId/timestamp/signature）；验签在 `UdpGateway.java:447`，含拒绝未签名、**按来源 sysid 取密钥**、取不到就拒、timestamp 防重放，全部 fail-closed |

### 当时的真问题不是「没验签」，而是「没人验证它在工作」

- `scripts/e2e-signing.ps1`（884 行 / 6 场景）**从未被任何 workflow 调用**——CI 只跑 9 个 e2e，签名不在其中
- `UdpGatewayTest` 有 10+ 个 UDP 测试，**0 个签名测试**
- ⇒ `UdpGateway` 那段 fail-closed 决策此前**零测试覆盖**

PR #23 补了 `UdpGatewaySigningTest`（8 例，走真实 UDP 字节全链路），并做了变异测试
（把 `verifyFrame` 改无条件放行 → 5 个「应拒绝」场景全部转红）。

### 一个协议级约束，做任何轮换相关设计前必须知道

MAVLink v2 签名的 13 字节数据块是 `linkId(1) | timestamp(6) | signature(6)`——
**没有 key id 字段**。因此：

1. **密钥轮换只能是硬切换**，不存在 API Key 那种「新旧并存宽限期」的协议表达。
   PR #23 因此**刻意没做** `graceHours`。
2. **打开签名同样是硬切换**：未换密钥的设备帧会被整体拒绝。
   所以我**没有**把 prod 的 `mavlink.signing.enabled` 设成 true（机队范围的行为变更，
   需单独拍板），只在 prod profile 里写明「打开前必须做什么」三步。

---

## 附：一个模式

这两条加上 2026-10-06 那次「C1–C5 全部待做 → 实际均已实现」的翻正，
是同一类问题的第三次出现：**ROADMAP 的「剩缺口」段落描述滞后/言过其实，
而它恰恰是最容易被当成"下一步工作计划"读的部分**。

如果后续要改，建议顺手做一件事：把这类描述**附加一处代码实证**
（文件:行号 或测试类名），让下一个人能 5 分钟内判断它是否已过期。
PR #25 的 `C4TelemetryRestoreTest` 就是为此写的锚点。
