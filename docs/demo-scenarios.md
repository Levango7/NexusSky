# NexusSky 端到端演示场景设计文档

> **文档版本**：v1.1 | **日期**：2026-09-24 | **作者**：演示场景调研组
>
> **定位**：本文档为客户演示与自助体验提供完整的场景设计、操作流程与技术支撑。
> 所有场景基于 NexusSky PoC 阶段已实现的 e2e 脚本与模拟能力，如实标注边界。

---

## 目录

1. [现有 e2e 脚本审计](#1-现有-e2e-脚本审计)
2. [演示场景设计](#2-演示场景设计)
3. [客户自助演示操作流程](#3-客户自助演示操作流程)
4. [需要自动化的手动步骤](#4-需要自动化的手动步骤)

---

## 1. 现有 e2e 脚本审计

### 1.1 脚本清单与完整性评估

| 脚本 | 里程碑 | 覆盖能力 | 完整性 | 可操作性 | 评估 |
|---|---|---|---|---|---|
| `e2e-smoke.ps1` / `.sh` | 基础 | 设备发现→任务上传→ARM→任务推进→RTL→轨迹→飞行日志→手动控制 | ★★★★★ | ★★★★★ | **核心演示脚本**，覆盖单机操控全链路，PS1/SH 双版本，前置依赖清晰 |
| `e2e-formation.ps1` | M1 T16 | 3机编队→起飞→队形变换(LINE→CIRCLE)→灯光同步→解散 | ★★★★★ | ★★★★☆ | **编队演示核心**，完整状态机验证，可选 `-IncludeRelay` 中继复用；需构建多个 jar |
| `e2e-mesh.ps1` | M0a Phase G | 中继转发→命令到达→遥测反向→透明性→异构链路→对端唯一性 | ★★★★★ | ★★★★☆ | **组网演示核心**，支持 auto/simplified/full 三模式，简化版零外部依赖；完整版需多 jar |
| `e2e-emergency.sh` | 4a + M9 | 布控球发现→报警事件→联动规则→应急指挥工作流(一键响应) | ★★★★★ | ★★★★★ | **应急演示核心**，9步完整流程，自带演示总结输出，最贴近客户场景 |
| `e2e-spray.sh` | M2 | 创建喷洒任务→查询状态→控制夹爪→查询物流配送→取消任务 | ★★★☆☆ | ★★★☆☆ | **喷洒演示基础**，依赖外部后端运行，无自启动逻辑，需配合其他脚本启动环境 |
| `e2e-hardware.sh` | M4 | 雷达配置→雷达状态→雷达目标→旋翼遥测→LiDAR→IMU→物理模型切换 | ★★★☆☆ | ★★★☆☆ | **硬件演示基础**，依赖外部后端运行，覆盖传感器查询但缺少操控闭环 |
| `e2e-failsafe.ps1` | P0 | 断链→自动RTL→链路恢复→自主落地 | ★★★★★ | ★★★★☆ | **安全演示核心**，验证 PX4 兼容自主保护，220s 观察窗，全程零人工命令 |
| `e2e-vision.ps1` | A5 | 相机会话→单拍定位→环绕orbit→航迹查询 | ★★★★★ | ★★★★☆ | **视觉演示核心**，异步 job 轮询，4站环绕拍照，定位误差<3m |
| `e2e-vision-pixels.ps1` | E2 | pixels模式单拍→blob检测→地理解算→JPEG字节验证 | ★★★★☆ | ★★★☆☆ | **像素域验证**，需后端以 `AEROFLEET_VISION_SOURCE=pixels` 启动，配置门槛较高 |
| `e2e-network.ps1` | 网络回归 | LTE损伤链路任务流→Starlink周期黑洞存活 | ★★★★★ | ★★★★☆ | **链路韧性演示**，7种链路画像，验证窄带/高丢包/周期黑洞下的系统韧性 |
| `e2e-rf.ps1` | E1 | 近场RSSI→飞远衰减→REST透传 | ★★★★☆ | ★★★☆☆ | **RF信号演示**，验证距离衰减建模，需 truth HTTP 通道配合 |
| `e2e-fault.ps1` | 故障回归 | GPS丢失→电池故障→链路中断 | ★★★★★ | ★★★★☆ | **故障演示核心**，三故障自动断言，sysid=7 故障模拟器自启停 |
| `ci-integration-test.sh` | CI | 构建→启动后端→健康检查→API文档→Swagger→Prometheus→认证 | ★★☆☆☆ | ★★★☆☆ | **CI基础验证**，仅基础设施检查，无业务场景覆盖 |

### 1.2 关键发现

**已具备完整演示能力的场景**：
- ✅ 单机操控（e2e-smoke）—— 起飞→航点→返航全链路
- ✅ 多机编队（e2e-formation）—— 3机编队完整闭环
- ✅ 应急指挥（e2e-emergency）—— 空地一体化9步流程
- ✅ 安全保障（e2e-failsafe + e2e-fault）—— 故障检测与自主保护
- ✅ 网络韧性（e2e-network）—— 多链路画像下的任务韧性

**缺失或需补强的演示场景**：
- ⚠️ 喷洒物流演示 —— e2e-spray.sh 缺少环境自启动，需配合 start-all + drone-sim 才能运行
- ⚠️ 安防监控演示 —— e2e-emergency.sh 覆盖了安防联动，但缺少独立的"监控→告警→追踪→处置"闭环演示
- ⚠️ 硬件抽象演示 —— e2e-hardware.sh 仅查询传感器数据，缺少操控闭环（如雷达扫描控制→目标追踪）
- ❌ 应急编排完整演示 —— M9 应急编排（灾区测绘→覆盖规划→组网部署→自愈重构）无独立 e2e 脚本
- ❌ 多机协同调度演示 —— M10 集群调度无 e2e 脚本
- ❌ 星地中继演示 —— M7 层级路由无独立 e2e 脚本
- ⚠️ P3 动态 MAX_HOPS —— 已实现（`DynamicMaxHops` + `--mesh-dynamic-max-hops` CLI 参数 + REST API），但无独立 e2e 脚本验证动态跳数调整过程
- ⚠️ P3 真实卫星接入预留 —— 已实现占位接口（`TiantongSatLinkProvider` / `IridiumSatLinkProvider` / `StarlinkSatLinkProvider`，均抛出 `UnsupportedOperationException`），但无独立 e2e 脚本验证占位行为

### 1.3 脚本运行依赖矩阵

| 脚本 | 需构建的 jar | 需预运行的服务 | Java 版本 | 平台 |
|---|---|---|---|---|
| e2e-smoke.ps1 | drone-sim, cloud-backend | 无（脚本不自启） | ≥17 | Windows |
| e2e-smoke.sh | drone-sim, cloud-backend | 无（脚本不自启） | ≥17 | Linux |
| e2e-formation.ps1 | drone-sim, cloud-backend, (link-sim) | 无（脚本自启全部） | ≥17 | Windows |
| e2e-mesh.ps1 | link-sim, (cloud-backend, drone-sim) | 无（简化版零依赖） | ≥17 | Windows |
| e2e-emergency.sh | cloud-backend | cloud-backend 需运行 | — | Linux |
| e2e-spray.sh | cloud-backend, drone-sim | cloud-backend + drone-sim 需运行 | — | Linux |
| e2e-hardware.sh | cloud-backend, drone-sim | cloud-backend + drone-sim 需运行 | — | Linux |
| e2e-failsafe.ps1 | drone-sim, cloud-backend | cloud-backend 需运行 | ≥17 | Windows |
| e2e-vision.ps1 | drone-sim, cloud-backend | cloud-backend 需运行 | ≥17 | Windows |
| e2e-network.ps1 | link-sim, drone-sim, cloud-backend | cloud-backend 需运行 | ≥17 | Windows |
| e2e-fault.ps1 | drone-sim, cloud-backend | cloud-backend 需运行 | ≥17 | Windows |
| ci-integration-test.sh | 全部模块 | 无（脚本自构建自启动） | ≥17 | Linux |

### 1.4 测试基线

当前项目测试基线：**3230 tests，0 failures**（全部通过）。

| 模块 | 测试数 | 说明 |
|---|---|---|
| mavlink-core | 185 | MAVLink 协议编解码、CRC 一致性 |
| drone-sim | 1222 | Mesh 路由、卫星链路、视觉感知、故障模拟 |
| cloud-backend | ~1823 | REST API、调度引擎、应急编排、安防联动 |
| **总计** | **3230** | **全部通过，0 failures** |

> 测试基线随里程碑推进持续增长，每个里程碑必须保持回归基线不退化。

### 1.5 P3 能力实现状态

P3 优先级功能已全部实现，但尚无独立 e2e 脚本覆盖：

| P3 功能 | 实现位置 | 实现状态 | e2e 覆盖 |
|---|---|---|---|
| 动态 MAX_HOPS | `DynamicMaxHops` + `MeshRouter.adjustMaxHops()` + `--mesh-dynamic-max-hops` CLI | ✅ 已实现（含单测） | ❌ 无独立 e2e 脚本 |
| 真实卫星接入预留 | `TiantongSatLinkProvider` / `IridiumSatLinkProvider` / `StarlinkSatLinkProvider` | ✅ 占位实现（抛 `UnsupportedOperationException`） | ❌ 无独立 e2e 脚本 |

**建议新增 e2e 脚本**：
- `e2e-dynamic-max-hops.ps1` —— 验证节点数变化时 MAX_HOPS 动态调整（SMALL→MEDIUM→LARGE 跨阈值场景）
- `e2e-sat-providers.ps1` —— 验证卫星提供商列表查询及占位连接的 501 响应

---

## 2. 演示场景设计

### 场景 A：单机操控演示（起飞→航点→返航）

> **对应脚本**：`e2e-smoke.ps1` / `e2e-smoke.sh`
> **演示时长**：约 3 分钟
> **目标客户**：二线整机厂商、飞控厂商

#### 前置条件

| 条件 | 要求 | 验证方式 |
|---|---|---|
| JDK | ≥ 17 | `java -version` |
| 构建产物 | drone-sim jar + cloud-backend jar | `mvn -pl drone-sim,cloud-backend -am package -DskipTests` |
| 端口 | 8080(REST)、14540(MAVLink)、14550(GCS) 可用 | 无其他 Java 进程占用 |
| 前端（可选） | gcs-web dev server (5173) | `cd gcs-web && npm install && npm run dev` |

#### 操作步骤

| 步骤 | 操作 | API/命令 | 预期结果 |
|---|---|---|---|
| 1 | 启动后端 + 模拟器 | `scripts\start-all.cmd` | 后端 8080 可达，drone-sim 14540 上线 |
| 2 | 确认设备上线 | `GET /api/v1/drones` | 返回列表含 sysid=1，online=true |
| 3 | 上传方形航线任务 | `POST /api/v1/drones/1/mission` | status=ok，5 个航点（takeoff→3×waypoint→rtl） |
| 4 | 解锁飞机 | `POST /api/v1/drones/1/commands {"type":"arm"}` | status=ok，armed=true |
| 5 | 开始任务 | `POST /api/v1/drones/1/commands {"type":"start_mission"}` | status=ok，mode=MISSION |
| 6 | 观察飞行 | `GET /api/v1/drones/1/telemetry` | missionSeq 递增，relativeAlt > 5m |
| 7 | 返航 | `POST /api/v1/drones/1/commands {"type":"rtl"}` | status=ok，mode=RTL |
| 8 | 查看轨迹 | `GET /api/v1/drones/1/track` | 轨迹点数 > 1 |
| 9 | 飞行日志 | `GET /api/v1/flightlog?sysid=1&limit=100` | 含 telemetry/mission/command 类型事件 |
| 10 | 手动控制体验 | `POST /api/v1/drones/1/joystick {"x":0,"y":500,"z":500,"r":0}` | status=ok，飞机响应摇杆 |

#### 预期结果

- 设备从发现到上线 ≤ 2s
- 任务上传后 missionSeq 从 0 开始递增
- 起飞高度达到 30m 后航点飞行高度 50m
- RTL 后飞机自主降落，轨迹完整记录
- 飞行日志落盘（`flight-logs/flight-YYYY-MM-DD.jsonl`）

#### 关键卖点

1. **MAVLink 协议兼容性**：与 PX4 真机完全一致的协议栈，替换模拟器即对接真飞控
2. **任务规划闭环**：航点编辑→上传→执行→返航→轨迹回放→日志审计全链路
3. **实时遥测**：1-5Hz 遥测推送，地图轨迹实时更新，姿态仪表 HUD
4. **手动控制**：MANUAL_CONTROL 直通，虚拟摇杆体验，松手自动 position hold

---

### 场景 B：多机编队演示（组队→变换→灯光→解散）

> **对应脚本**：`e2e-formation.ps1`
> **演示时长**：约 5 分钟
> **目标客户**：行业解决方案商（表演/活动）、二线整机厂商

#### 前置条件

| 条件 | 要求 |
|---|---|
| JDK | ≥ 17 |
| 构建产物 | drone-sim jar + cloud-backend jar（+ link-sim jar 如用 `-IncludeRelay`） |
| 端口 | 8080, 14550-14553 可用 |
| 参考点 | 深圳 (22.5431, 113.9578) |

#### 操作步骤

| 步骤 | 操作 | API/命令 | 预期结果 |
|---|---|---|---|
| 1 | 启动编队演示 | `scripts\e2e-formation.ps1` | 后端 + 3台 drone-sim(sysid=1/2/3) 自启动 |
| 2 | 等待3机上线 | `GET /api/v1/drones` | 3 架飞机均 online=true |
| 3 | 创建编队 | `POST /api/v1/formation` (members=[1,2,3], shape=LINE, spacing=5m) | 200 + formationId + state=FORMING |
| 4 | 起飞到位 | `POST /api/v1/formation/{id}/takeoff` (alt=10m) | 3机起飞，state→STABLE |
| 5 | 队形变换 | `POST /api/v1/formation/{id}/transition` (shape=CIRCLE) | state=TRANSITIONING→STABLE |
| 6 | 灯光同步 | `POST /api/v1/formation/{id}/lights` (pattern=RAINBOW) | 3机灯光状态更新 |
| 7 | 查看灯光 | `GET /api/v1/formation/{id}/lights` | 反映 RAINBOW 状态 |
| 8 | 解散编队 | `POST /api/v1/formation/{id}/dissolve` | state=DISSOLVED |

#### 预期结果

- 3 机编队创建后 state 从 FORMING→STABLE
- 队形变换 LINE→CIRCLE 经历 TRANSITIONING 中间态
- 灯光 RAINBOW 模式 3 机同步生效
- 解散后 state=DISSOLVED，3 机各自独立

#### 关键卖点

1. **多机协同调度**：3 机编队创建、起飞、变换、灯光、解散完整闭环
2. **状态机可观测**：每一步状态转移可追踪（FORMING→STABLE→TRANSITIONING→STABLE→DISSOLVED）
3. **中继复用**：`-IncludeRelay` 开关启用 M0a 中继，验证异构链路下的编队控制
4. **队形平滑变换**：LINE→CIRCLE 插值航点过渡，非瞬时跳变

---

### 场景 C：应急编排演示（灾情→部署→覆盖→重构）

> **对应脚本**：`e2e-emergency.sh`（空地一体化应急指挥）
> **演示时长**：约 5 分钟
> **目标客户**：行业解决方案商（应急救援）、应急管理部门

#### 前置条件

| 条件 | 要求 |
|---|---|
| 后端 | cloud-backend 8080 运行中（surveillance/alarm/mission 模块已启用） |
| 模拟器 | 至少 1 台 drone-sim 在线 |
| 网络 | 布控球模拟子网 192.168.1.0/24 |

#### 操作步骤

| 步骤 | 操作 | API/命令 | 预期结果 |
|---|---|---|---|
| 1 | 布控球自动发现 | `POST /api/surveillance/rapid-deploy` (subnet=192.168.1.0/24) | 发现并注册 ≥1 个布控球（海康/大华/宇视） |
| 2 | 查看已注册设备 | `GET /api/surveillance/devices` | 设备列表含厂商、型号信息 |
| 3 | 创建联动规则 | `POST /api/alarms/rules` (FIRE+CRITICAL→DEPLOY_DRONE) | ruleId 非空 |
| 4 | 模拟报警事件 | `POST /api/alarms/events` (FIRE, CRITICAL, lat/lon) | eventId + matchedCount≥1（联动触发） |
| 5 | 查看报警列表 | `GET /api/alarms/events` | 事件总数 ≥1 |
| 6 | 确认报警 | `POST /api/alarms/events/{id}/ack` | acknowledged=true |
| 7 | 创建应急指挥 | `POST /api/emergency-command` (incidentType=火灾) | commandId + currentPhase |
| 8 | 一键应急响应 | `POST /api/emergency-command/{id}/one-click` | 阶段流转：接报→研判→部署→执行→评估→总结 |
| 9 | 查看指挥历史 | `GET /api/emergency-command` | 历史命令数 ≥1 |

#### 预期结果

- 布控球自动发现并一键注册，识别厂商品牌
- 报警事件触发联动规则，自动匹配 DEPLOY_DRONE 动作
- 应急指挥一键响应，6 阶段自动流转（接报→研判→部署→执行→评估→总结）
- 最终阶段为 CLOSED/SUMMARY，阶段转移历史 ≥4 步

#### 关键卖点

1. **空地一体化**：布控球（地面安防）→ 报警联动 → 无人机侦察 → 应急指挥闭环
2. **ONVIF 多厂商兼容**：海康/大华/宇视三大安防硬件供应商协议兼容
3. **一键应急响应**：6 阶段工作流自动走完，从报警到总结零人工干预
4. **报警联动编排**：规则引擎自动匹配事件类型+严重程度→触发无人机部署

---

### 场景 D：喷洒物流演示（航线规划→执行→交付）

> **对应脚本**：`e2e-spray.sh`
> **演示时长**：约 3 分钟
> **目标客户**：行业解决方案商（农业）、农业大户

#### 前置条件

| 条件 | 要求 |
|---|---|
| 后端 | cloud-backend 8080 运行中 |
| 模拟器 | drone-sim sysid=1 在线 |
| 环境 | 需先运行 `start-all.cmd` 或等效启动 |

#### 操作步骤

| 步骤 | 操作 | API/命令 | 预期结果 |
|---|---|---|---|
| 1 | 确认后端 + 飞机在线 | `GET /api/v1/drones` | sysid=1 online=true |
| 2 | 创建喷洒任务 | `POST /api/v1/spray/task` (rateLpm=2.5, totalLiters=50, 2段航线) | taskId 非空 |
| 3 | 查询喷洒状态 | `GET /api/v1/spray/status/1` | pump 字段有值 |
| 4 | 打开夹爪 | `POST /api/v1/spray/gripper` (open=true) | 返回成功 |
| 5 | 关闭夹爪 | `POST /api/v1/spray/gripper` (open=false) | 返回成功 |
| 6 | 查询物流配送序列 | `GET /api/v1/delivery/sequence/1` | 站点列表非空 |
| 7 | 取消喷洒任务 | `POST /api/v1/spray/cancel` (taskId) | 返回成功 |

#### 预期结果

- 喷洒任务创建返回 taskId
- 喷洒状态包含 pump 运行状态
- 夹爪开/关控制成功
- 物流配送序列包含站点列表
- 任务取消成功

#### 关键卖点

1. **精准农业**：喷洒速率+总量控制，分段航线规划
2. **物流配送**：夹爪控制 + 配送序列查询
3. **任务生命周期**：创建→执行→查询→取消完整管理
4. **模块化可选**：喷洒/物流作为可选模块，按需启用

---

### 场景 E：安防监控演示（监控→告警→追踪→处置）

> **对应脚本**：`e2e-emergency.sh`（安防部分） + `e2e-fault.ps1`（故障检测） + `e2e-failsafe.ps1`（自主保护）
> **演示时长**：约 8 分钟
> **目标客户**：行业解决方案商（安防）、安防甲方

#### 前置条件

| 条件 | 要求 |
|---|---|
| 后端 | cloud-backend 8080 运行中（surveillance/alarm 模块） |
| 模拟器 | drone-sim 在线 |
| JDK | ≥ 17 |

#### 操作步骤

| 步骤 | 操作 | API/命令 | 预期结果 |
|---|---|---|---|
| **监控阶段** | | | |
| 1 | 布控球自动发现 | `POST /api/surveillance/rapid-deploy` | 注册 ≥1 设备 |
| 2 | 查看设备列表 | `GET /api/surveillance/devices` | 含厂商/型号/状态 |
| **告警阶段** | | | |
| 3 | 创建联动规则 | `POST /api/alarms/rules` (FIRE+CRITICAL→DEPLOY_DRONE) | ruleId 非空 |
| 4 | 模拟报警事件 | `POST /api/alarms/events` (FIRE, CRITICAL) | eventId + matchedCount≥1 |
| 5 | 确认报警 | `POST /api/alarms/events/{id}/ack` | acknowledged=true |
| **追踪阶段** | | | |
| 6 | 查看报警事件列表 | `GET /api/alarms/events` | 事件总数≥1 |
| 7 | 查看应急指挥历史 | `GET /api/emergency-command` | 命令历史 |
| **处置阶段** | | | |
| 8 | 一键应急响应 | `POST /api/emergency-command/{id}/one-click` | 6阶段自动流转→CLOSED |
| 9 | 故障检测演示 | `scripts\e2e-fault.ps1` | GPS丢失/电池故障/链路中断自动检测 |
| 10 | Failsafe演示 | `scripts\e2e-failsafe.ps1` | 断链→自主RTL→自主落地 |

#### 预期结果

- 布控球自动发现并注册到监控面板
- 报警事件触发联动规则，自动部署无人机侦察
- 应急指挥一键响应，6 阶段自动流转
- 故障检测：GPS 丢失/电池故障/链路中断均被云端检测
- Failsafe：断链 15s 后飞机自主 RTL，链路恢复后云端看到已安全落地

#### 关键卖点

1. **空地协同安防**：布控球监控 → 报警联动 → 无人机侦察 → 应急处置完整闭环
2. **故障检测能力**：GPS/电池/链路三类故障实时检测 + 告警
3. **自主保护（Failsafe）**：PX4 兼容的断链自主返航，全程零人工命令
4. **多厂商安防接入**：ONVIF 协议兼容海康/大华/宇视

---

## 3. 客户自助演示操作流程

### 3.1 环境准备

#### 3.1.1 基础环境

```cmd
:: 1. 确认 JDK 17+
java -version

:: 2. 构建所有模块
mvn -B -DskipTests package

:: 3. 一键启动后端 + 模拟器
scripts\start-all.cmd

:: 4.（可选）启动前端地面站
cd gcs-web
npm install
npm run dev
:: 打开 http://localhost:5173
```

#### 3.1.2 验证环境就绪

| 检查项 | 命令 | 预期 |
|---|---|---|
| 后端健康 | `curl http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| 设备在线 | `curl http://localhost:8080/api/v1/drones` | 列表非空，online=true |
| API 文档 | `curl http://localhost:8080/v3/api-docs` | OpenAPI JSON |
| Swagger UI | 浏览器打开 `http://localhost:8080/swagger-ui.html` | 可交互 API 文档 |

### 3.2 演示启动顺序

```
┌─────────────────────────────────────────────────────────┐
│                    演示启动顺序                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. start-all.cmd ──→ 后端 + 模拟器启动                 │
│       │                                                 │
│       ├──→ 场景A：单机操控（e2e-smoke）                 │
│       │     └──→ 起飞→航点→返航→轨迹→日志              │
│       │                                                 │
│       ├──→ 场景B：多机编队（e2e-formation）             │
│       │     └──→ 3机编队→变换→灯光→解散                │
│       │                                                 │
│       ├──→ 场景C：应急编排（e2e-emergency）             │
│       │     └──→ 布控球→报警→联动→一键应急             │
│       │                                                 │
│       ├──→ 场景D：喷洒物流（e2e-spray）                 │
│       │     └──→ 喷洒任务→夹爪→配送序列                │
│       │                                                 │
│       └──→ 场景E：安防监控（e2e-emergency + fault）     │
│             └──→ 监控→告警→追踪→处置+Failsafe          │
│                                                         │
│  2. gcs-web (可选) ──→ 前端可视化                       │
│       └──→ 地图轨迹 / 仪表 HUD / 告警面板              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3.3 各场景操作指南

#### 场景 A：单机操控 — 快速体验

```cmd
:: 方式1：自动脚本（推荐）
powershell -ExecutionPolicy Bypass -File scripts\e2e-smoke.ps1

:: 方式2：手动逐步操作（配合 GCS 前端）
:: 1. 打开 http://localhost:5173
:: 2. 左侧"任务规划" → 选择"测绘模板" → 上传任务
:: 3. 右侧点击"解锁" → "开始任务"
:: 4. 观察地图轨迹、姿态仪
:: 5. 点击"返航 RTL"
```

#### 场景 B：多机编队 — 一键演示

```cmd
:: 自动脚本（推荐，自启全部依赖）
powershell -ExecutionPolicy Bypass -File scripts\e2e-formation.ps1

:: 含中继复用（展示异构链路下的编队控制）
powershell -ExecutionPolicy Bypass -File scripts\e2e-formation.ps1 -IncludeRelay
```

#### 场景 C：应急编排 — 一键演示

```bash
# Linux/Mac（推荐，脚本最完整）
bash scripts/e2e-emergency.sh

# 自定义后端地址
BASE_URL=http://1.2.3.4:8080 bash scripts/e2e-emergency.sh
```

#### 场景 D：喷洒物流 — 手动操作

```bash
# 前置：后端 + drone-sim 已运行
# 1. 确认飞机在线
curl http://localhost:8080/api/v1/drones

# 2. 运行喷洒演示
bash scripts/e2e-spray.sh
```

#### 场景 E：安防监控 — 组合演示

```cmd
:: Step 1: 安防联动（Linux 脚本，Windows 下可用 WSL）
bash scripts/e2e-emergency.sh

:: Step 2: 故障检测（PowerShell）
powershell -ExecutionPolicy Bypass -File scripts\e2e-fault.ps1

:: Step 3: Failsafe 自主保护（PowerShell）
powershell -ExecutionPolicy Bypass -File scripts\e2e-failsafe.ps1
```

### 3.4 演示时长建议

| 场景 | 脚本自动运行 | 手动操作+讲解 | 建议总时长 |
|---|---|---|---|
| A 单机操控 | 30s | 2min | 3min |
| B 多机编队 | 3min | 2min | 5min |
| C 应急编排 | 1min | 4min | 5min |
| D 喷洒物流 | 30s | 2min | 3min |
| E 安防监控 | 5min | 3min | 8min |
| **全场景** | **10min** | **13min** | **25min** |

---

## 4. 需要自动化的手动步骤

### 4.1 当前需手动操作的步骤

| 手动步骤 | 当前方式 | 自动化建议 | 优先级 |
|---|---|---|---|
| 构建所有 jar | `mvn -DskipTests package` | 在 start-all.cmd 中自动检测 jar 是否存在，缺失则自动构建 | 高 |
| 启动后端 + 模拟器 | `start-all.cmd` | 已自动化 | — |
| 启动前端 | `cd gcs-web && npm run dev` | 在 start-all.cmd 中增加前端启动选项 | 中 |
| 喷洒演示环境准备 | 手动启动后端+drone-sim | 创建 `e2e-spray.ps1` 自启版本（参照 e2e-formation 模式） | 高 |
| 硬件演示环境准备 | 手动启动后端+drone-sim | 创建 `e2e-hardware.ps1` 自启版本 | 中 |
| 安防演示跨平台 | e2e-emergency.sh 仅 Linux | 创建 PowerShell 版本 `e2e-emergency.ps1` | 高 |
| 场景切换 | 手动停止/启动不同脚本 | 创建 `demo-all.ps1` 一键串联所有场景 | 高 |
| 前端演示操作 | 手动点击 GCS 界面 | 录制演示视频或创建前端自动化脚本 | 低 |

### 4.2 建议新增的演示脚本

| 脚本名 | 功能 | 优先级 | 依赖 |
|---|---|---|---|
| `demo-all.ps1` | 一键串联5个演示场景，带暂停/讲解提示 | 高 | 所有 e2e 脚本 |
| `e2e-emergency.ps1` | 应急编排 PowerShell 版（当前仅 .sh） | 高 | cloud-backend |
| `e2e-spray.ps1` | 喷洒物流自启版（当前 .sh 依赖外部环境） | 高 | drone-sim, cloud-backend |
| `e2e-orchestration.ps1` | M9 应急编排完整演示（灾区测绘→覆盖规划→组网部署→自愈重构） | 中 | drone-sim, cloud-backend |
| `e2e-security.ps1` | 安防监控独立演示（监控→告警→追踪→处置闭环） | 中 | cloud-backend |
| `e2e-dynamic-max-hops.ps1` | P3 动态 MAX_HOPS 验证（节点数跨阈值时跳数自动调整） | 中 | drone-sim, cloud-backend |
| `e2e-sat-providers.ps1` | P3 卫星接入预留验证（提供商列表 + 占位连接 501 响应） | 低 | cloud-backend |

### 4.3 演示环境一键启动建议

```cmd
:: 建议的 demo-all.cmd 结构
@echo off
echo === NexusSky 演示环境启动 ===

:: 1. 检查并构建 jar
if not exist drone-sim\target\*.jar (
    echo [1/4] 构建 jar...
    call mvn -B -DskipTests package
)

:: 2. 启动后端 + 模拟器
echo [2/4] 启动后端 + 模拟器...
call scripts\start-all.cmd

:: 3. 等待就绪
echo [3/4] 等待服务就绪...
timeout /t 5

:: 4. 启动前端（可选）
echo [4/4] 启动前端地面站...
start cmd /k "cd gcs-web && npm run dev"

echo.
echo === 演示环境就绪 ===
echo 后端: http://localhost:8080
echo 前端: http://localhost:5173
echo Swagger: http://localhost:8080/swagger-ui.html
echo.
echo 可运行演示:
echo   场景A: powershell -File scripts\e2e-smoke.ps1
echo   场景B: powershell -File scripts\e2e-formation.ps1
echo   场景C: bash scripts/e2e-emergency.sh
echo   场景D: bash scripts/e2e-spray.sh
echo   场景E: powershell -File scripts\e2e-fault.ps1
```

---

## 附录：演示场景与产品定位映射

| 演示场景 | 对应产品能力 | 目标客户 | 核心卖点 |
|---|---|---|---|
| A 单机操控 | MAVLink 协议栈 + 任务规划 + 实时遥测 | 二线整机厂商、飞控厂商 | 协议兼容性、任务闭环 |
| B 多机编队 | M1 编队协同 + 状态机 + 灯光同步 | 行业解决方案商（表演/活动） | 多机协同调度 |
| C 应急编排 | 4a 空地一体化 + M9 应急编排 + ONVIF | 行业解决方案商（应急救援） | 应急指挥闭环 |
| D 喷洒物流 | M2 喷洒 + 物流配送 | 行业解决方案商（农业） | 精准农业模块 |
| E 安防监控 | 4a 安防联动 + 故障检测 + Failsafe | 行业解决方案商（安防） | 空地协同安防 |

> **产品定位参考**：NexusSky 是兼容多厂商飞控协议的多机协同调度云平台中间件。
> 演示场景设计围绕"中间件"定位——展示协同调度、应急指挥、安防联动等中间层能力，
> 不与客户在飞控或整机制造层竞争。（来源：[PRODUCT-POSITIONING.md](PRODUCT-POSITIONING.md)）