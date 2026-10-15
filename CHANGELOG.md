# NexusSky 变更日志

> 本文件记录 NexusSky（天枢）项目的重大变更，按版本倒序排列。

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

## [Unreleased] — 外部评估报告 P1 续整改：WS 两个并发/计数缺陷 + 租户隔离两处漏滤（2026-10-12）

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

## [Unreleased] — CI 巩固：UI 视觉审计入 CI + CodeQL 告警登记清零（2026-10-11）

> 两条线：把“本地判据”升级成 CI 门禁；把“面板噪音”清零。

| # | 交付 | 内容 |
|---|---|---|
| — | **UI Audit job（新）** | `gcs-web/scripts/ui-audit.mjs` 挂进 ci.yml：npm build + vite preview + **无后端审计**（`UI_AUDIT_OFFLINE`）+ **系统 Chrome**（`channel:'chrome'`，不下载 Playwright 浏览器）。preview 与审计**同一步**（GH Actions 每 step 独立 shell，`&` 起的进程会被回收——2026-10-04 的坑）。视图取覆盖四类布局的子集（桌面 8 + 移动端 4，约 2 分钟）；全 45 视图本地跑（不设 `UI_AUDIT_VIEWS` 即全量） |
| — | **ui-audit.mjs 新开关** | `UI_AUDIT_OFFLINE`（无后端跳过“云端已连接”等待——404 下面板空/错状态照样审：浅色控件残留/JS 错误/顶栏高度，空状态本身就在被审面内）+ `UI_AUDIT_BROWSER=chromium` 兜底 |
| — | **CodeQL 告警清零** | 4 条全部 dismiss（#1 false positive；#2/#5/#6 won't fix——理由与证据见 `docs/codeql-alert-disposition.md`），仓上 open=0。CI 守卫的 `--allow` 登记随之清空，回到“默认全关：再出现 open 即红”。守卫方向本地双向验证：喂空数组→过；喂一条未登记 open→红 |

**验证**：本地 offline 子集审计 8 视图次 0 问题、exit 0；守卫 `CODEQL_ALERTS_JSON` 两个方向实测；YAML 解析 18 jobs。

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

## [Unreleased] — 预约运营面补全：主动释放端点 + 归属租户列（V27）（2026-10-11）

> B5 预约持久化时登记的边界："主动释放单个预约未做（按过期清理已覆盖主要场景）；
> 预约的租户归属未建模"。两条一并闭合。

| # | 交付 | 内容 |
|---|---|---|
| — | **释放端点** | `DELETE /api/v1/scheduling/reservations/{sysid}`（OPERATOR）——释放该机全部预约（清内存+删库+返回条数）。运营场景：任务提前完成/撤防，时空块不必等窗口过期。repo 的 `deleteByOwnerSysid` 自 B5 持久化起已备，本批接线 |
| — | **归属租户列（V27）** | `airspace_reservations.tenant_id`（可空，`ALTER TABLE ADD COLUMN`）；落库时从 `TenantContext` 取。**语义是审计记录，不参与冲突判定**——空域是物理的，跨租户同样冲突，检测始终全局 |

**测试**：ConflictAvoidanceServiceTest +2（释放按机主清内存+删库+别机不动+无预约 0；落库带租户）；SchedulingControllerTest +1（释放端点全链：释放→快照→无预约宽容）；cloud-backend 全量 **2602/0**；全 reactor **4628/0**；H2+`ddl-auto=validate` 演练通过（V27 与实体一致）。

## [Unreleased] — GB28181 事件订阅路由（B1 的 events 收尾）（2026-10-11）

> B1（PTZ/RTSP 按厂商路由）时登记："事件订阅端点仍直连 OnvifClient——涉及订阅 handle
> 的生命周期语义统一，留独立评估"。评估结论：**VendorAdapter 的 subscribe/unsubscribe
> 契约早已齐备**（五个适配器全部实现 unsubscribeEvents），只差 Controller 接线。

| # | 交付 | 内容 |
|---|---|---|
| — | **订阅路由** | `/devices/{id}/events`（SSE）：订阅与三处退订（结束/超时/出错）均经 `getAdapter(device)`——此前直连 OnvifClient，GB28181 设备的事件源语义错置（进 ONVIF 模拟回调表，国标信令永远不触发） |
| — | **测试** | +2：GB28181 设备经国标信令层建立 SSE 订阅（asyncStarted）；设备不存在 → 200 + 流内错误终止（SSE 语义勘误：响应头已提交，错误只能流内表达，非 5xx） |

**验证**：cloud-backend 全量 **2599/0**；全 reactor 全绿；文档计数门禁 22 处同步后"全部一致"。

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

## [Unreleased] — 批次 4 flaky 测试治理：一处放宽、一处复核结论入档（2026-10-10）

> 清单上的两个「已知 flaky」逐条实测处置（不照抄旧记录）。

| # | 处置对象 | 结论 |
|---|---|---|
| C3.1 | `assignTasksGaCompletesWithin100ms` | **真修**：硬阈值 100ms → **1s 护栏**。全量并发实测曾被压到 ~180ms（机器忙），硬 100ms 会把"忙"和"算法退化"混为一谈；1s 仍能抓 GA 崩坏级回归（正常量级个位数 ms）。阈值理由写进测试注释 |
| C3.2 | `RestrictionCacheManagerTest.testRefreshSuccess` | **复核：旧记录已过期**——手工 `new` 的实例上 `@Scheduled` 不生效（无 Spring 调度代理），init/refresh 全为同步调用、无时序窗口；本机连跑 5 次全绿。复核结论与排查指引（"若再红先查是否引入后台刷新/共享状态"）写入类注释 |

**测试**：cloud-backend 全量 **2581/0**（计数不变——本批只改阈值与注释，未动用例）。

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
| 5 | e2e 可运行性 | 脚本启动 backend **不带 profile** → 命中 base 默认 `spring.profiles.active=prod` → prod 要 `localhost:5432` 的 PostgreSQL → `FlywaySqlException: Connection refused`，场景 1 就挂；且 `$BackendRestPort`/`$BackendUdpPort` 只是变量，6 处启动都没下传（等于永远硬编码 8080/14561） | backend 启动统一加 `--spring.profiles.active=dev` + `--server.port` + `--aerofleet.udp-port` + Redis 主机/端口，并把 REST/UDP/Drone/LinkSim/Redis 全部提升为脚本参数（与 `ci-integration-test.sh` 的 A_PORT/PG_PORT 同一做法；本机 8080 被其它项目容器占用时也能跑） |
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
