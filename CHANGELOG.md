# NexusSky 变更日志

> 本文件记录 NexusSky（天枢）项目的重大变更，按版本倒序排列。

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
- **MAVLink 回包被挡死**：cloud-backend UDP 网关从 14550 端口探测 sim
  （GCS HEARTBEAT），sim 回包/遥测发回 backend:14550/UDP——只放行
  8080/TCP 时回包全丢，飞行链路静默失效。修复：同命名空间规则追加
  14550/UDP（helm + 原生两份）；
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