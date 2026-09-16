# Mesh Relay 设计文档（Batch E3，方案已细化）

## 现状与缺口
骨架的 `UdpGateway` 是 `ConcurrentHashMap<sysid, SocketAddress>`：每台无人机
只有一条路由，没有多跳。`link-sim` 是点对点损伤代理（proxyPort↔dronePort），
没有中继转发功能。`RouteTable.prune()`（D3）只处理路由老化，不处理拓扑重建。

缺口已在本仓 12 维度审计中确认：
- **mesh 组网**：完全空白
- **移动基站中继接收（特定场景通过其他移动基站）**：LTE/Starlink 画像有
  统计特性（延迟/丢包/分区），但没有基站切换或中继节点路由重建

## 最小可解方案（本批可落地，不引入完整 mesh 协议栈）

### 一、Relay 节点（静态中继，先不做移动中继）
扩展 `link-sim` 增加 `--relay` 模式（或独立 `mesh-relay` 脚本）：
- 监听 `gcsPort`（接收后端命令）和 `relayPort`（接收远端飞机遥测）
- 双向转发：上行（GCS→飞机）通过 `uplink` 损伤引擎，下行（飞机→GCS）
  通过 `downlink` 损伤引擎，各自独立
- 学习地址：收到飞机包时记录飞机源地址为 `droneAddr`，收到 GCS 包时记录
  `gcsAddr`（与现有 `LinkSimMain.gcsAddr` 语义一致）
- 无环：一跳场景下，只有固定的 GCS↔中继↔飞机三角关系，不存在环路；
  跳数守卫（如 `hopCount` 字段或 TTL 限制）在方案中明确记为**已知边界**，
  本批不实现，只在 README 声明

### 二、协议层测试重点（价值）
- 远端飞机直连链路被 `link-loss`（黑洞/高丢包）杀死时，任务命令仍能经
  中继到达 → 验证 `DroneCommandService` 的多机命令路由在多跳下仍正确
- 遥测反向到达：远端飞机的 HEARTBEAT 经中继到达后端，`TelemetryIngestService`
  正确解析（sysid 正确，不被中继的地址替换混淆）
- 链路质量遥测：中继两段可分别设置不同 profile（如 LTE_EDGE + WIFI5），
  证明多段异构链路在同一任务中可共存（这是“移动基站切换”的基础）

### 三、文件与变更（方案细化，未执行代码写入上一步已完成的设计文档）
- `link-sim/src/main/java/io/aerofleet/linksim/LinkSimMain.java`：增加
  `--relay` 参数处理、第二个 `DatagramSocket`（relay→drone）、第二套
  `ImpairmentEngine`（可独立配置 profile）、转发逻辑
- `mesh-relay-design.md`（已写入）：本设计文档，含架构图、协议行为、边界声明
- `scripts/e2e-mesh.ps1`（已写入）：两机 + 一跳中继 + 后端的回归场景脚本，断言任务
  完成 + 遥测到达
- `README.md` 补充 mesh 边界节：一跳静态中继已实现；完整 mesh 路由协议
  （BATMAN/动态拓扑）明确不做

### 四、与现有模块的关系
- `RouteTable.prune()`（D3）：远端飞机的路由地址现在是中继节点的转发端口
  （`drone-facing` 端口），而不是飞机自己的源地址。这是正确的行为变化：
  后端向中继投递，命令再由中继投递给远端飞机
- `DroneViews`：遥测仍正确关联到远端飞机的 sysid（因为遥测帧的 `sysid`
  是远端飞机的，不是中继的）
- `e2e-network.ps1`（现有网络场景测试）：与 mesh 场景互补——前者测试点对点
  损伤，本批 mesh 测试多跳拓扑

## 边界声明（诚实）
- **不是完整 mesh 路由协议**：没有 RREQ/RREP，没有链路状态广播，没有
  动态拓扑发现。只是一个“透明 UDP 转发节点”验证协议在中继下的行为
- **没有移动中继自动发现**：中继节点地址是场景配置（脚本里固定），
  不是由飞机主动探测发现的。真实移动中继需要 GPS 位置共享 + 邻居发现，
  那是下一层
- **跳数守卫缺失**：MAVLink 帧无 TTL 字段，一跳场景无环；多跳场景需在
  应用层增加 `hopCount` 扩展，本批不做，只在 README 标记为已知边界

### 执行状态
- 代码方案已细化（本文件 + `mesh-relay-design.md` + `e2e-mesh.ps1`）
- 代码未写入：`LinkSimMain` 的 `--relay` 扩展、`mesh-relay` 进程脚本的深度实现
  （核心转发逻辑约 120 行，纯 UDP + 损伤引擎，风险可控）
- 单测已设计：`MeshRelayTest`（转发无丢失、双方向独立损伤、远端遥测到达）
- 本批执行完成标准：`e2e-mesh.ps1` 通过 → 任务命令经中继到达远端飞机、遥测反向到达、链路质量遥测正确报告两段独立 profile

下步是否执行 E3 mesh 执行？（条件：环境稳定，或接受 danger-full-access 重启后端）
