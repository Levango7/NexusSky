# 角色状态机 + 多目标任务分配（Batch F 方案，纯后端，不引 ROS2）

## 灵感来源
外部共享的"跨场景飞控"思路核心：**分层架构 + 动态调度**。其中对我们最有
性价比的两条：
1. **角色切换机制**：动态维护 Leader（协调者）/ Worker（执行者）/ Relay
   （中继）角色，切换触发 = 任务类型 / 网络拓扑 / 电量故障。
2. **多终端协同**：蜂群大脑负责任务分解与冲突消解，各终端经 Mesh 交换
   位置/状态/意图。

**为什么不做 ROS2/DDS**：骨架是模块化单体，引入 ROS2/DDS 是过度设计
（外部思路也承认"自研中间件"可行）。我们用现成的 Spring 后端 + REST +
UdpGateway 路由表就能承载同样的"角色状态机 + 动态调度"语义。

## 已验证的基础（无需新增，直接复用）
| 能力 | 现有组件 | 位置 |
|---|---|---|
| 感知（目标检测） | TargetTracker.ingest/tracksOf | vision/TargetTracker.java |
| 决策（定位/跟踪预测） | Track.predictLatLon | 同上 |
| 执行（任务/返航/环绕） | DroneCommandService.arm/startMission/rtl/command | mission/DroneCommandService.java |
| 拓扑（路由表） | RouteTable.prune() | gateway/RouteTable.java |
| 电池 | DroneSnapshot.battery | gateway/DroneSnapshot.java |
| 环绕闭环 | OrbitJobManager/OrbitService | vision/ |

## 新增设计（3 个文件 + 1 个端点 + 测试）

### F1. 角色状态机（SquadRoleService）
NEW `cloud-backend/.../mission/SquadRoleService.java`：
- 每台 drone 维护一个角色：`enum Role { WORKER, RELAY, LEADER }`
- 角色状态：`ConcurrentHashMap<Integer, Role>` + `version`（每次切换递增，
  供 GCS 轮询探测拓扑变化）
- **触发切换规则**（纯规则，不用训练，可解释）：
  - `RELAY`：当 `RouteTable` 显示某 drone 被多台其他 drone 作为转发目标
    时，自动提升为 RELAY（= 段子里的"节点既是终端也是中继"，但用现有
    路由表推断，不实现 BATMAN）
  - `LEADER`：任务调度时指定一台（默认 sysid 最小 + 在线）为 LEADER
  - 电量故障：`DroneSnapshot.battery < 20` → 该 drone 降级为 WORKER 且
    不承担 RELAY/LEADER（E4 电池模型正好提供了触发信号）
- 方法：`roleOf(sysid)`、`roles()`（快照）、`promoteToRelay/assignLeader`、
  `onBatteryCritical(sysid)`

### F2. 多目标任务分配（SquadDispatcher）
NEW `cloud-backend/.../mission/SquadDispatcher.java`：
- 输入：`TargetTracker` 的 ACTIVE tracks + `DeviceRegistry` 的在线机队
- 输出：任务分配（哪台去跟踪哪个目标），规则（可解释）：
  - **冲突消解**：同一目标被多台 drone 看到时，只分配最近的一台（避免
    重复环绕）；其余 drone 分配其他目标（或空闲待命）
  - **电量优先**：低电 drone 不分配长任务（RTL/短任务优先），高电承担
    环绕/跟踪
  - 返回 `assignment: { targetTrackId -> sysid }` + `unassigned`
- 复用现有 `OrbitJobManager`：分配后触发该 drone 的环绕任务（已是异步 job）
- 方法：`dispatch()`（基于当前 tracks + 机队状态计算分配）、`dispatchFor(sysid)`

### F3. 调度端点（SquadController）
`api/SquadController.java`（`@RequestMapping("/api/v1/squad")`）：
- `GET /api/v1/squad/roles` → `{ drones: [{sysid, role, battery, online}], version }`
- `POST /api/v1/squad/assign` → 触发 `SquadDispatcher.dispatch()`，返回分配
  结果 + 每个被分配 drone 的 orbit jobId
- `POST /api/v1/squad/leader/{sysid}` → 手动指定 LEADER（覆盖默认选举）

### F4. 测试（SquadRoleTest + SquadDispatchTest）
不起 Spring（直接构造 service + 注入 mock 的 tracker/registry/commands）：
- 角色切换：RELAY 提升规则、电量降级、LEADER 选举
- 任务分配：多目标不重复分配、低电不分配长任务、单目标最近机分配
- 冲突消解：同一目标只分配一台
- 生命周期：角色 version 单调递增、空机队不崩

## 集成点（不改现有模块）
- 复用 `DroneSnapshot`、`RouteTable`、`TargetTracker`、`OrbitJobManager`，
  **不修改它们的内部逻辑**（只读）
- `SquadDispatcher` 调用 `OrbitJobManager.submit()`（已是异步，天然不阻塞）
- GCS 侧前端不新增（本轮纯后端；roles 端点已够外部脚本/前端查询）

## 验收标准
- F1/F2/F3 编译 + 4+ 个新单测绿（不起 Spring）
- 现有 112 测试不回归
- 手动验证：`GET /squad/roles` 返回正确角色、`POST /squad/assign` 触发分配
  （在环境允许时用 e2e-squad.ps1 验证；环境受限时以单测为准，如实标注）

## 边界声明（诚实）
- **不是完整蜂群协议**：没有 SLA（服务等级）、没有动态拓扑发现、没有
  意图信息广播。是基于"角色状态机 + 距离/电量规则"的可解释调度。
- **RELAY 角色当前是"推断标记"**：我们不做真正的自组织 mesh 中继（E3 方案
  已声明一跳静态中继；多跳动态路由是未来）。角色状态机先把"该台是否应
  承担中继职责"的决策逻辑跑通，底层转发仍是 E3 的静态中继。
- **调度是"建议"不是"强制"**：`assign` 触发环绕 job，但每台 drone 的最终
  行为仍由现有任务/环绕服务决定，不引入新的飞行授权层。

## 与已有批次关系
- 依赖 E4 电池（降级触发）、D 多机路由（拓扑）、P3 跟踪器（感知）、
  D1 异步环绕（执行）。全部已提交并测试绿。
- 本批只在现有基础上**新增调度层**，不动底层任何模块。

## 文件清单
- NEW `mission/SquadRoleService.java`（~150 行）
- NEW `mission/SquadDispatcher.java`（~140 行）
- NEW `api/SquadController.java`（~60 行）
- NEW 测试 `SquadRoleTest.java` + `SquadDispatchTest.java`（~200 行）
- 可选 `scripts/e2e-squad.ps1`（环境允许时验证）