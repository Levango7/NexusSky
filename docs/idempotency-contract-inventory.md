# 关键写接口幂等/重复提交 · 风险清单（2026-11-26，master `21506d96`）

> 本文档是 #315（应急编排 abort 幂等）之后的**现状注记**，不是终局/完整审计。所有"已覆盖"均须以
> 仓库测试/代码为证；没有证据的一律列入"缺口/待裁决"。机器可复核：`rg -n "幂等|dedupe|去重|idempot|409|客" cloud-backend/src/test`。
> 2026-11-26（#149）：把设备激活/注销、遥测上报、命令投递四族重复场景补成显式契约矩阵
> （`Idempotency*ContractTest`），并按实测语义把激活/注销挪入表一、上报/投递留在表二。

## 一、已有幂等契约测试的写接口（证据定位）

| 写接口/动作 | 幂等语义 | 证据（测试类 / 方法） |
|---|---|---|
| `POST /lock` 重复锁定 | 返回当前状态、不覆盖 | `DroneLockServiceTest#testDoubleLock`、`DroneLockControllerTest#lockDoubleLock_idempotent` |
| `DeviceRegistry.provision` 重复登记 | `provision` 返回 false，不覆盖既有归属 | `DeviceRegistryPersistenceTest`（provision 幂等） |
| `POST /api/v1/devices/{sysid}` 重复激活 | 同请求体重复登记 → 200 `alreadyRegistered=true`，有效归属不变（改归属是 `PUT /{sysid}/tenant` 的职责）；并发重复同一 sysid → 恰好一个 201、其余 200、注册表仅一条目（`provision` 的 `putIfAbsent` 保证）；注销后再登记 → 重新走 201 | `IdempotencyActivationContractTest`（repeatActivationSameBodyIsIdempotent / repeatActivationDifferentTenantDoesNotRebind / concurrentDuplicateActivationExactlyOneCreated / reactivationAfterDeregistrationCreatesAgain） |
| `DELETE /api/v1/devices/{sysid}` 重复注销 | 吸收性操作：首次 200 `deregistered=true`，第二次起 404 `device unknown`（**拒绝**而非静默 NO-OP，符合立约原则 3）；并发重复注销 → 恰好一个 200、其余 404（sysid 条带锁串行化） | `IdempotencyDeregistrationContractTest`（repeatDeregistrationReturnsNotFound / concurrentDuplicateDeregistrationExactlyOneSucceeds / reProvisionAfterDeregistrationCreatesAgain） |
| `VoiceIntercomService.startIntercom/stopIntercom` | 已 ACTIVE 再启动保持状态 | `VoiceIntercomServiceTest`（start/stop 幂等） |
| `VideoStreamService.startStream/stopStream` | 已 STREAMING 再启动保持状态 | `VideoStreamServiceTest`（start/stop 幂等） |
| `VideoFusionController` 重复 start | 同 id 重登记返回原记录、不重置起点 | `VideoFusionControllerTest`、`VideoFusionRecordingServiceTest` |
| `DefectService.logDefect` 同 kind ≤10m 复现 | 只更新、不重复立案；已消缺不折叠 | `DefectServiceTest#dedupByDistance`、`…#resolvedNotDedupFold` |
| `WorkOrderService` 建单 | RECTIFIED 拒绝（不重复派单）；未终态工单占用同缺陷时拒绝；终态允许再派 | `WorkOrderServiceTest`（创建工单：RECTIFIED / 占用守卫 / 终态允许） |
| `EmergencyOrchService.abort` | 已 ABORTED 再次 abort 返回 true 且**不重复副作用**（事件/持久化/推送/EmergencyEndEvent） | #315（`EmergencyOrchServiceTest#abortTwiceIsIdempotent`、`#repeatedAbortPublishesEmergencyEndEventOnlyOnce`） |
| `UomReportSink` activate/cancel | 同 ID 重复激活/注销返回同一 ID | `UomSinkVsRegulatorSimContractTest#幂等：重复激活 / 重复注销` |
| `VideoFusionRecordingService` 同 id | 重复登记不告警、不重置 | `VideoFusionRecordingServiceTest` |
| `TerrainRestrictionsBound` 重复上报 | 列表不增长 | `TerrainRestrictionsBoundTest#duplicateReportsDoNotGrowTheList` |
| `DockService.register` 重复 SN | 重复 SN 抛 `IllegalStateException("dock sn already registered")`（失败语义：拒绝、不覆盖归属/位置）；`DockController` 映射 409；`DockServiceTest#registerDuplicateSnRejected`（2026-11-16 补：第二次 register 抛 IllegalStateException 且 `save` 未再被调用） |
| `WorkOrderService.verify` 终态再次复检 | `machine.isTerminal` 终态直接拒绝（失败语义：409 / `IllegalWorkOrderTransitionException`），且 `captureAndLocate` 不被再调用；`WorkOrderServiceTest#verifyAfterTerminalRejected`（VERIFIED 后再次 verify 抛 IllegalWorkOrderTransitionException 且 capture 只调用 1 次） |
| `GeofenceStore.addZone` 同 id 重复创建 | **覆盖语义（last-write-wins）**：同 `zoneId` 再 `addZone` 覆盖并返回旧值；`GeofenceStorePersistenceTest#memoryMode_addZone_overwriteReturnsPrevious`（2026-11-16 复核：已有测试钉住，原「缺口」为误记） |
| `AlarmLinkageEngine.addRule` 同 id 重复创建 | **覆盖语义（last-write-wins）**：同 `ruleId` 再 `addRule` 覆盖旧规则；`AlarmLinkageEngineTest#addRuleOverwritesSameId`（2026-11-16 复核：已有测试钉住，原「缺口」为误记） |

## 二、**有写路径但未形成幂等契约/缺口**（诚实列出，不臆测已解决）

| 写接口/动作 | 现状 | 缺口/风险 | 建议方向（非本文档决策） |
|---|---|---|---|
| `POST /scheduling/assign` 重复提交同一 `taskId` | `TaskAssignmentService.assignTask` 无按 taskId 去重的 `guard`；覆盖内存映射 `assignments` 并再 `taskQueue.offer()` | 需求未明："同一 taskId 重复提交应被拒绝/合并/重分配"涉及产品语义；现状下可能重复下发任务帧、重复 occupy 无人机队列 | 需要产品/架构裁决后再定契约；在此之前**只可观察、不得改行为** |
| `POST /api/v1/orch/start` 连续两次 | `EmergencyOrchServiceTest#startReturnsIncrementingPlanIds` 已钉住：`start` 多次返回**不同 planId**（递增）——**按设计非幂等** | 符合产品语义（两次应急 = 两次编排）；客户端若希望"去重"必须在外层做幂等键 | 不在仓库侧发明"去重 start"；若有必要，由调用方携幂等键并产品定语义 |
| `POST /regulator/report` 遥测批量重复上报 | `TelemetryReportServiceTest` 已覆盖上报频率/序列；UOM 幂等在 `UomSinkVsRegulatorSimContractTest` 覆盖 | 业务侧"重传同意义报文"的去重在 UOM 侧已验 | 保持现状即可；不要在此重复造轮子 |
| `POST /docks/{id}/command` 重复 `OPEN_DOOR` | 状态机会拒绝非法迁移（11-16 前已落地，`DockStateMachine` + `DockService` + `DockServiceTest`） | 非法状态二次命令是**拒绝**而非"成功 NO-OP"；客户端须按 409 走重试/对账逻辑，而不是把 409 当故障 | 保持拒绝语义；在接口文档已有 409 语义 |
| UDP 遥测帧 ingest 重复帧（`TelemetryIngestService.handle`） | **无序列号/内容去重**：重复帧（同 MAVLink seq 同内容）被各发布一次事件，下游（事件总线/监管上报/决策监控）按 at-least-once 消费；`MavlinkFrame.sequence` 仅用于日志，不参与去重 | 重复帧会无差别转发；是否为预期（at-least-once + 消费方幂等）需 owner 裁决。`IdempotencyTelemetryReportContractTest` 已钉住当前 at-least-once 保证，使未来引入 seq 去重必须作为显式契约变更 | 裁决后再定：引入 seq 去重窗口，或明文接受 at-least-once 并在消费侧幂等 |
| `POST /api/v1/drones/{sysid}/commands` 重复提交同一命令 | **无客户端去重键 / 请求 ID**（全仓无 Idempotency-Key），也不按 MAV_CMD 内容去重：每次提交各发一条线上命令；单次提交超时重试恰好 2 次发送（`ACK_RETRIES=2`）后失败 | 对效果幂等命令（ARM/TAKEOFF）重复下发通常无害，但对一次性动作（任务启动/返航/相机触发）可能被执行多次。`IdempotencyCommandDeliveryContractTest` 已钉住当前行为，使未来引入去重键必须是显式契约变更 | 裁决后再定：引入请求 ID + 服务端短期去重窗口，或改为 exactly-once 语义 |

## 三、立约原则（后续新写接口必填，缺一不可）

1. **语义先行**：对外动作在设计阶段必须回答"同一实体同一请求重复提交 = ?"，从 {接受并幂等返回、4xx 拒绝、按新实体处理} 三选一，并写进本清单。
2. **测试锁死**：选定答案后必须有对应契约测试（至少 1 例"重复提交"路径），且为该语义的 A/B 测试（改坏→红、还原→绿），而不是"顺手把全绿的路径跑一遍"。
3. **绝不静默成功**：重复提交绝不返回成功假象；要么真 NO-OP、要么明确 4xx/5xx，绝不准吞掉错误后返回 `true/ok`。
4. **端点口径**：写该接口的幂等行为时，同时检查 RBAC 与审计哈希链是否仍满足 `SecurityConfigContractTest` 等门禁。

## 四、本清单的局限（诚实声明）

- 本清单按 2026-11-16 主干快照人工核对，**不是 exhaustive**：174 个 `@Post/Put/PatchMapping` 锚点**并非**每条都逐一跑过；
  "已覆盖"只指表一中列出且测试确实断言对应语义的条目。
- "缺口"≠"确实有 bug"：很多条目只是"现状未定契约"，需要产品/架构或客户侧重放语义才能收敛——不能把"新功能"塞进修复清单。
- 若发现现存的"静默成功"重复提交，应按 11-16 修复 abort 的模板：**先判语义正确性，再补测试→A/B→PR→CI 绿→squash merge**。

## 五、建议节奏

P2（每个迭代的能做）：每次新增/改造写接口时**同额补 1 例重复提交契约测试**；
本清单季度过一遍（或每个里程碑一次），把已补的从"缺口"挪到"已覆盖"。