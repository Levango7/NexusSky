# NexusSky 变更日志

> 本文件记录 NexusSky（天枢）项目的重大变更，按版本倒序排列。

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