# NexusSky 代码架构深化调研报告

> **版本**：v1.0 | **日期**：2026-09-22 | **作者**：架构调研 Agent

---

## 目录

1. [模块解耦分析](#一模块解耦分析)
2. [插件化机制设计](#二插件化机制设计)
3. [性能优化机会](#三性能优化机会)
4. [安全加固评估](#四安全加固评估)
5. [实施路线图](#五实施路线图)

---

## 一、模块解耦分析

### 1.1 项目结构概览

cloud-backend 包含 **31 个包**、**341 个 Java 文件**，各包规模分布：

| 包名 | 文件数 | 角色 |
|---|---|---|
| `api` | 31 | REST 控制器 + WebSocket + 推送器 |
| `mission` | 31 | 任务管理（编队/喷洒/配送/应急） |
| `orch` | 32 | 编排引擎（计划/步骤/适配器/事件） |
| `security` | 19 | 认证/授权/多租户/RBAC |
| `vision` | 19 | 视觉感知/避障/雷达/热成像 |
| `gateway` | 12 | UDP 网关/遥测接入/设备注册 |
| `scheduling` | 5 | 集群调度/任务分配/冲突避免 |
| `twin` | 9 | 数字孪生/预测/比对 |
| `geofence` | 6 | 地理围栏 |
| `surveillance` | 5 | 安防监控设备 |
| `delivery2` | 4 | 物流配送 |
| `mapping` | 5 | 测绘 |
| `show` | 4 | 表演 |
| `alarm` | 3 | 报警事件 |
| `telemetry` | 3 | 环境告警/待确认ACK |
| `tracking` | 3 | 飞行轨迹 |
| `ai` | 2 | 决策监控 |
| `edge` | 2 | 边缘协同 |
| `config` | 4 | WebSocket/WebConfig/OpenAPI |
| 其他 | ~20 | drone/license/audit/flightlog/inspection 等 |

### 1.2 高耦合模块识别

#### 🔴 **TelemetryIngestService — 上帝类（最高耦合）**

`TelemetryIngestService` 是整个系统的**消息分发中枢**，直接依赖 **14 个跨包组件**：

```
gateway.TelemetryIngestService 依赖：
├── gateway.DeviceRegistry          (同包)
├── telemetry.PendingAcks           (同包)
├── telemetry.AlertBus              (同包)
├── tracking.FlightTrackStore       (跨包)
├── api.TelemetryWebSocketHandler   (跨包)
├── api.CellTowerTopologyService    (跨包)
├── api.EmergencyOrchService        (跨包)
├── api.HardwareDataController      (跨包)
├── api.MeshTopologyService         (跨包)
├── api.SatLinkMonitorService       (跨包)
├── api.TerrainMapService           (跨包)
├── vision.ObstacleAvoidanceController (跨包)
├── vision.RadarController          (跨包)
├── vision.RotorController          (跨包)
└── com.fasterxml.jackson.databind.ObjectMapper
```

**问题**：
- 497 行代码，`switch(frame.getMessageId())` 中有 **40+ case 分支**
- 每新增一个 MAVLink 消息类型，必须修改此类 → 违反开闭原则
- 12 个跨包依赖通过 `@Lazy` 注入打破循环依赖 → 架构异味
- 单个类承担消息解码 + 路由 + 业务处理三重职责

**耦合度评分**：🔴 极高（14 个依赖，40+ case 分支）

#### 🟡 **api 包 — 胖包问题**

`api` 包包含 31 个文件，混合了多种职责：
- REST 控制器（DroneController, VisionController, MeshController 等）
- WebSocket 处理器（TelemetryWebSocketHandler）
- 推送器（TelemetryPusher, HardwarePusher, ObstaclePusher 等 6 个 Pusher）
- 服务（EmergencyOrchService, MeshTopologyService, SatLinkMonitorService 等）
- DTO（CellTowerSnapshot, MeshNodeSnapshot, DroneViews）
- 异常处理（ApiExceptionHandler）

**问题**：控制器、推送器、服务、DTO 混杂在同一包中，边界模糊。

#### 🟡 **mission 包 — 职责膨胀**

31 个文件涵盖编队、喷洒、配送、应急指挥、中队调度等多种业务，应拆分为独立子包。

### 1.3 循环依赖分析

已识别的循环依赖路径（通过 `@Lazy` 打破）：

```
循环1：TelemetryIngestService ↔ TelemetryWebSocketHandler
  TelemetryIngestService → @Lazy TelemetryWebSocketHandler (WebSocketConfig 创建)
  WebSocketConfig → JwtTokenProvider → (无循环)

循环2：TelemetryIngestService ↔ vision.ObstacleAvoidanceController
  TelemetryIngestService → @Lazy ObstacleAvoidanceController
  ObstacleAvoidanceController → (可能间接依赖 gateway 中的服务)

循环3：TelemetryIngestService ↔ api.EmergencyOrchService
  TelemetryIngestService → @Lazy EmergencyOrchService
  EmergencyOrchService → (可能间接依赖 gateway)
```

**根因**：`TelemetryIngestService` 作为中心枢纽，既需要调用各业务模块处理消息，各业务模块又可能通过 DeviceRegistry 等共享组件间接依赖回 gateway 包。

### 1.4 模块边界清晰度评估

| 包 | 边界清晰度 | 说明 |
|---|---|---|
| `orch` | ✅ 良好 | 通过 `ModuleAdapter` 接口隔离业务模块，事件驱动推进 |
| `security` | ✅ 良好 | 认证/授权/租户隔离职责单一 |
| `gateway` | ⚠️ 中等 | TelemetryIngestService 职责过重 |
| `api` | ⚠️ 中等 | 控制器/服务/推送器/DTO 混杂 |
| `mission` | ⚠️ 中等 | 多种业务类型混杂 |
| `vision` | ✅ 良好 | VisionSource 接口抽象良好 |
| `scheduling` | ✅ 良好 | 独立的调度算法封装 |
| `twin` | ✅ 良好 | 纯数据模型，无外部依赖 |
| `ai/edge` | ✅ 良好 | 简单的监控/记录服务 |

### 1.5 解耦方案

#### 方案 A：事件驱动消息总线（推荐）

**目标**：将 `TelemetryIngestService` 从"上帝类"重构为"瘦路由器"。

```
改造前：
  UdpGateway → TelemetryIngestService.handle(frame)
      → switch(40+ case) → 直接调用各业务模块

改造后：
  UdpGateway → TelemetryIngestService.handle(frame)
      → decode → publish(MavlinkMessageEvent)
      
  各业务模块 @EventListener(MavlinkMessageEvent) → 自行过滤处理
```

**具体步骤**：

1. **定义消息事件**：
   ```java
   public class MavlinkMessageEvent {
       private final int sysid;
       private final int msgId;
       private final MavlinkMessage message;
       private final long timestamp;
   }
   ```

2. **TelemetryIngestService 瘦身**：只保留 decode + publish，删除所有 `onXxx()` 方法和跨包依赖

3. **各业务模块自行监听**：
   ```java
   @Component
   public class RadarMessageListener {
       @EventListener(condition = "#event.msgId == 437")
       public void onRadarScan(MavlinkMessageEvent event) { ... }
   }
   ```

4. **消除 `@Lazy`**：所有循环依赖通过事件解耦自然消除

**收益**：
- TelemetryIngestService 从 497 行 → ~80 行
- 14 个跨包依赖 → 2 个（EventPublisher + DeviceRegistry）
- 新增消息类型只需新增 Listener，不改 TelemetryIngestService

#### 方案 B：api 包拆分

```
api/ → 拆分为：
├── api/controller/    (REST 控制器)
├── api/ws/             (WebSocket 处理器)
├── api/pusher/         (推送器)
├── api/service/        (跨模块服务)
├── api/dto/            (数据传输对象)
├── api/exception/      (异常处理)
```

#### 方案 C：mission 包拆分

```
mission/ → 拆分为：
├── mission/formation/  (编队)
├── mission/spray/      (喷洒)
├── mission/delivery/   (配送)
├── mission/emergency/  (应急指挥)
├── mission/squad/      (中队调度)
├── mission/common/     (共享模型)
```

---

## 二、插件化机制设计

### 2.1 现有 Strategy/Adapter 模式评估

项目已有两个良好的抽象层：

#### ModuleAdapter（orch 包）— 已实现的插件化雏形

```java
public interface ModuleAdapter {
    ModuleResult createTask(Map<String, Object> params);
    ModuleResult startTask(String taskId);
    ModuleResult abortTask(String taskId);
    ModuleResult getTaskStatus(String taskId);
    String getModuleType();
}
```

**现有实现**：5 个适配器
- `FormationAdapter` → FormationService
- `DeliveryAdapter` → DeliveryService
- `MappingAdapter` → MappingTaskService
- `ShowAdapter` → ShowTaskService
- `EmergencyAdapter` → EmergencyOrchService

**评估**：✅ 设计良好，`StepExecutor` 通过 `@PostConstruct` 自动发现所有 `ModuleAdapter` Bean，按 `getModuleType()` 建立映射。这是事实上的 SPI 机制。

#### VisionSource（vision 包）— 策略模式

```java
public interface VisionSource {
    List<VisionDetection> detect(CameraShot shot, CameraPose cameraPose);
}
```

**现有实现**：
- `ProjectionVisionSource`（投影简化）
- `SimulatedVisionSource`（模拟检测）

**评估**：✅ 接口简洁，实现可替换，未来接入真实 CV 模型只需新增实现类。

#### DecisionEngine（drone-sim 包）— 多策略融合

```java
public class DecisionEngine {
    private final ReturnToHomeStrategy rtlStrategy;
    private final ObstacleAvoidanceStrategy avoidStrategy;
    private final AdaptivePathStrategy adaptStrategy;
}
```

**评估**：⚠️ 策略类硬编码在构造函数中，不支持运行时动态加载新策略。

### 2.2 适合插件化的功能域

| 功能域 | 当前状态 | 插件化价值 | 优先级 |
|---|---|---|---|
| **载荷类型** | 硬编码（Spray/Gripper/Payload msgId 423-426） | 🔴 高 — 不同行业需要不同载荷 | P0 |
| **通信协议** | 硬编码（UDP MAVLink only） | 🔴 高 — 需支持 MQTT/HTTP/gRPC | P0 |
| **AI 决策策略** | 硬编码 3 策略 | 🟡 中 — 新场景需新策略 | P1 |
| **传感器类型** | 硬编码（Radar/LiDAR/IMU/Thermal） | 🟡 中 — 新传感器接入频繁 | P1 |
| **航线规划算法** | 硬编码（A*/RRT） | 🟡 中 — 不同场景需不同算法 | P1 |
| **任务模块** | ✅ 已有 ModuleAdapter | 🟢 低 — 已插件化 | P2 |
| **视觉检测源** | ✅ 已有 VisionSource | 🟢 低 — 已插件化 | P2 |
| **调度算法** | 硬编码（评分/GA） | 🟡 中 — 可插拔不同调度策略 | P1 |

### 2.3 插件接口规范设计

#### 2.3.1 载荷插件 SPI

```java
public interface PayloadPlugin {
    /** 载荷类型标识（如 "spray", "gripper", "camera"） */
    String getPayloadType();
    
    /** 处理上行遥测消息 */
    void onTelemetry(int sysid, MavlinkMessage msg);
    
    /** 构建下行控制命令 */
    MavlinkMessage buildCommand(int sysid, Map<String, Object> params);
    
    /** 插件元数据（名称/版本/作者/兼容性） */
    PluginMetadata getMetadata();
}
```

#### 2.3.2 通信协议插件 SPI

```java
public interface TransportPlugin {
    /** 协议标识（如 "mavlink-udp", "mqtt", "http-rest"） */
    String getProtocolType();
    
    /** 启动传输层监听 */
    void start(TransportConfig config, MessageHandler handler);
    
    /** 停止传输层 */
    void stop();
    
    /** 发送消息到设备 */
    void send(int sysid, Object message) throws IOException;
}
```

#### 2.3.3 AI 决策策略插件 SPI

```java
public interface DecisionStrategyPlugin {
    /** 策略标识（如 "rtl", "avoid", "adapt-path", "emergency-land"） */
    String getStrategyType();
    
    /** 评估当前态势，返回决策结果 */
    DecisionResult evaluate(DecisionContext context);
    
    /** 策略优先级（用于融合排序） */
    int getPriority();
    
    /** 是否适用于当前上下文 */
    boolean isApplicable(DecisionContext context);
}
```

#### 2.3.4 航线规划算法插件 SPI

```java
public interface RoutePlannerPlugin {
    /** 算法标识（如 "astar", "rrt", "dijkstra", "voronoi"） */
    String getAlgorithmType();
    
    /** 规划路径 */
    List<Waypoint> plan(RoutePlanRequest request);
    
    /** 算法适用场景描述 */
    String getApplicableScenario();
}
```

### 2.4 插件加载与生命周期管理

#### 加载机制：Spring Bean 自动发现（推荐）

利用 Spring 的 `@Component` + `List<PluginInterface>` 注入，与现有 `StepExecutor` 的 `List<ModuleAdapter>` 模式一致：

```java
@Component
public class PluginRegistry {
    private final Map<String, PayloadPlugin> payloadPlugins = new ConcurrentHashMap<>();
    
    public PluginRegistry(List<PayloadPlugin> plugins) {
        for (PayloadPlugin p : plugins) {
            payloadPlugins.put(p.getPayloadType(), p);
        }
    }
    
    public PayloadPlugin get(String type) {
        return payloadPlugins.get(type);
    }
}
```

**优势**：
- 与现有 `StepExecutor.init()` 模式完全一致
- 零配置：插件只需标注 `@Component` 即可被发现
- 支持 JAR 热部署（Spring Boot DevTools）

#### 生命周期管理

```java
public interface PluginLifecycle {
    /** 插件初始化（注册后、启用前） */
    void initialize(PluginContext context);
    
    /** 插件启用 */
    void enable();
    
    /** 插件禁用（不卸载，可重新启用） */
    void disable();
    
    /** 插件销毁（卸载前清理资源） */
    void destroy();
}
```

#### 配置驱动启用/禁用

```properties
# application.properties
aerofleet.plugins.payload.spray.enabled=true
aerofleet.plugins.payload.gripper.enabled=true
aerofleet.plugins.transport.mqtt.enabled=false
aerofleet.plugins.ai.weather-route.enabled=true
```

---

## 三、性能优化机会

### 3.1 热点代码路径识别

#### 🔴 热点 1：TelemetryIngestService.handle() — 消息分发瓶颈

**场景**：每架无人机 10-50Hz 发送 MAVLink 帧，多机集群下每秒数百到上千帧。

**当前实现**：
- `handle()` 在 UDP 接收线程上同步执行所有 case 分支
- `onPosition()` 中同步写入 `flightTrackStore.addPoint()`（I/O 操作）
- `forwardToWs()` 中同步 JSON 序列化 + WebSocket 广播

**瓶颈**：
- 单线程处理所有消息 → 多机场景下 CPU 成为瓶颈
- I/O 操作（flightTrackStore 写入）阻塞接收线程 → 可能导致 UDP 丢包
- JSON 序列化在接收线程上执行 → CPU 密集操作阻塞消息处理

**优化方案**：
1. **消息队列 + 异步处理**：`handle()` 只做 decode + 入队，后台线程池消费
2. **批量写入**：flightTrackStore 改为批量写入（攒批 100 条或 1 秒刷盘）
3. **异步 WebSocket 推送**：JSON 序列化 + broadcast 移到独立线程

#### 🔴 热点 2：TelemetryPusher.pushOnce() — 1Hz 全量推送

**当前实现**：
```java
@Scheduled(fixedDelay = 1000)
public void pushOnce() {
    for (DroneSnapshot s : registry.all()) {  // 遍历所有无人机
        flightLog.telemetry(s);                // 每台都写日志
        handler.broadcast(frame("telemetry", s.sysid, ...), mapper);  // 每台都序列化+广播
        handler.broadcast(frame("status", s.sysid, ...), mapper);     // 再一次
    }
}
```

**瓶颈**：
- N 台无人机 → 2N 次 JSON 序列化 + 2N 次 WebSocket 广播
- 每次广播遍历所有 WS session，`synchronized(session)` 串行发送
- `flightLog.telemetry(s)` 在推送线程上同步写文件

**优化方案**：
1. **增量推送**：只推送状态变化的字段（位置/电量/模式），而非全量快照
2. **批量 JSON 序列化**：将所有无人机数据组装为单个 JSON 数组，一次序列化一次广播
3. **异步日志写入**：flightLog 写入移到独立线程
4. **WebSocket 并行发送**：使用 `session.sendMessageAsync()` 替代同步 `sendMessage()`

#### 🟡 热点 3：TaskAssignmentService.assignTask() — synchronized 阻塞

**当前实现**：`assignTask()` 和 `assignTasks()` 都用 `synchronized` 修饰。

**瓶颈**：集群规模扩大时，并发任务分配请求会串行等待。

**优化方案**：
1. 使用 `ReadWriteLock` 替代 `synchronized`（读多写少场景）
2. 或使用 CAS + 乐观重试机制

#### 🟡 热点 4：VirtualDrone.tickOnce() — 20Hz 物理引擎

**当前实现**：每 50ms 执行一次物理计算 + 遥测编码 + UDP 发送。

**瓶颈**：单线程 scheduler，多机模拟时所有无人机共享一个调度线程。

**优化方案**：
1. 多机场景下使用线程池并行 tick（每 N 台无人机一个线程）
2. 物理计算与遥测编码分离（物理 20Hz，遥测可降频到 10Hz）

### 3.2 已有优化评估

| 优化项 | 状态 | 效果评估 |
|---|---|---|
| `@Lazy` 注入打破循环依赖 | ✅ 已实现 | 解决了启动问题，但掩盖了架构问题 |
| `BoundedHistory` 有界历史 | ✅ 已实现 | 防止内存泄漏，O(1) 添加 |
| `ConcurrentHashMap` 并发快照 | ✅ 已实现 | 读无锁，适合读多写少 |
| `RouteTable` 路由老化 | ✅ 已实现 | 防止 stale route 吃命令 |
| TelemetryPusher 1Hz 降频 | ✅ 已实现 | 从 10-50Hz 降到 1Hz，减少 WS 压力 |
| GA 调度算法（4+任务时启用） | ✅ 已实现 | 小规模用贪心，大规模用 GA |
| `forwardToWs` 无连接时跳过 | ✅ 已实现 | 无 GCS 客户端时不做序列化 |
| `DroneSnapshot` 离线保留 | ✅ 已实现 | 机队列表持续显示，无重建开销 |

### 3.3 新优化机会

| 优化项 | 优先级 | 预期收益 | 实现复杂度 |
|---|---|---|---|
| **TelemetryIngestService 异步化** | P0 | 消除 UDP 丢包风险 | 中 |
| **批量 WebSocket 推送** | P0 | 减少 2N→1 次序列化 | 低 |
| **flightTrackStore 批量写入** | P1 | 减少 I/O 阻塞 | 中 |
| **TaskAssignment 锁优化** | P1 | 提高并发分配吞吐 | 低 |
| **VirtualDrone 并行 tick** | P1 | 支持大规模集群模拟 | 中 |
| **DroneSnapshot 增量推送** | P2 | 减少 WS 带宽 50%+ | 高 |
| **MAVLink 消息池化** | P2 | 减少 GC 压力 | 中 |
| **连接池（H2/JPA）** | P2 | 已有默认连接池，需调优参数 | 低 |

---

## 四、安全加固评估

### 4.1 现有安全机制审查

| 安全层 | 实现 | 评估 |
|---|---|---|
| **JWT 认证** | `JwtTokenProvider` (HMAC-SHA256) | ✅ 使用 Spring Security Nimbus，密钥 ≥32 字节校验 |
| **Spring Security** | `SecurityConfig` (dev/prod 双模式) | ✅ 生产模式 STATELESS + oauth2ResourceServer |
| **RBAC** | `RoleInterceptor` + `@RequireRole` 注解 | ✅ ADMIN/OPERATOR/OBSERVER 三级角色 |
| **多租户隔离** | `TenantFilter` + `TenantContext` | ✅ JWT 提取 tenant_id，ThreadLocal 隔离 |
| **CORS** | `WebSocketConfig` allowed-origins | ✅ 生产环境限制为特定域名 |
| **输入验证** | `ValidationConfig` (JSR303 Bean Validation) | ✅ MethodValidationPostProcessor |
| **异常脱敏** | `ApiExceptionHandler` | ✅ 生产模式不泄露内部错误细节 |
| **WebSocket 认证** | `TelemetryWebSocketHandler` JWT 校验 | ✅ handshake 时验证 token |
| **密码加密** | `BCryptPasswordEncoder` | ✅ 用户密码 BCrypt 存储 |
| **AES 加密** | `PasswordConverter` (surveillance) | ✅ 监控设备密码 AES 加密 |
| **API 限流** | `aerofleet.tenant.rate-limit=100` | ⚠️ 配置存在，实现待确认 |
| **审计日志** | `aerofleet.audit.enabled` | ⚠️ 默认关闭，生产环境启用 |
| **License 校验** | `LicenseController` + License 拦截器 | ✅ 生产环境启用 |

### 4.2 安全盲点识别

#### 🔴 **盲点 1：WebSocket 无限流**

`TelemetryWebSocketHandler` 在 handshake 时验证 JWT，但连接建立后**无任何限流**。恶意客户端可建立大量连接或高频发送消息。

**建议**：
- 限制单 IP 最大 WS 连接数（如 10）
- 限制单 tenant 最大 WS 连接数
- 对客户端上行消息做频率限制（当前只读通道，但注释提到未来可能支持下行命令）

#### 🔴 **盲点 2：UDP 网关无认证**

`UdpGateway` 监听 14550 端口，任何能访问该端口的设备都可以发送 MAVLink 帧。无 sysid 认证、无消息频率限制。

**建议**：
- 增加设备白名单（已知 sysid 才接受帧）
- 增加单 sysid 消息频率限制（防止恶意泛洪）
- 生产环境绑定到内网网卡，不暴露公网

#### 🟡 **盲点 3：JWT 无刷新机制**

当前 JWT 有效期 3600 秒（1 小时），无 refresh token 机制。Token 过期后需重新登录。

**建议**：
- 实现 refresh token 机制
- 或使用 sliding expiration（每次请求自动续期）

#### 🟡 **盲点 4：SQL 注入风险（低但存在）**

项目使用 JPA + H2/PostgreSQL，Hibernate 参数化查询默认防 SQL 注入。但自定义查询（如 `@Query`）需审查。

**建议**：
- 审查所有 `@Query` 注解，确保使用参数化查询
- 禁止字符串拼接 SQL

#### 🟡 **盲点 5：日志脱敏不足**

`Statustext` 消息内容直接写入日志和 AlertBus，可能包含敏感信息（如设备配置、位置数据）。

**建议**：
- 对 STATUSTEXT 内容做敏感词过滤
- 生产环境日志级别 WARN+，减少 INFO 级别遥测日志

#### 🟡 **盲点 6：dev-mode 默认值不一致**

- `application.properties` 中 `aerofleet.security.dev-mode` 未显式设置（SecurityConfig 默认 `false`）
- `application-dev.properties` 应显式设置 `dev-mode=true`
- `WebSocketConfig` 中 `devMode` 默认 `true`（与 SecurityConfig 默认 `false` 不一致）

**建议**：统一 dev-mode 默认值，在通用 `application.properties` 中显式声明。

#### 🟢 **盲点 7：Swagger UI 生产环境暴露**

已在 `application-prod.properties` 中禁用 Swagger UI ✅。但 `application-staging.properties` 需确认。

### 4.3 OWASP Top 10 覆盖度

| OWASP 类别 | 覆盖状态 | 说明 |
|---|---|---|
| A01 - Broken Access Control | ✅ 良好 | RBAC + 多租户隔离 + JWT |
| A02 - Cryptographic Failures | ✅ 良好 | BCrypt 密码 + HMAC-SHA256 JWT + AES 设备密码 |
| A03 - Injection | ✅ 良好 | JPA 参数化查询 + Bean Validation |
| A04 - Insecure Design | ⚠️ 中等 | TelemetryIngestService 上帝类，单点故障风险 |
| A05 - Security Misconfiguration | ⚠️ 中等 | dev-mode 默认值不一致 |
| A06 - Vulnerable Components | ⚠️ 待审查 | 需定期扫描依赖漏洞 |
| A07 - Auth Failures | ✅ 良好 | JWT + BCrypt + 限流配置 |
| A08 - Data Integrity Failures | ✅ 良好 | MAVLink CRC 校验 + JWT 签名验证 |
| A09 - Logging/Monitoring | ⚠️ 中等 | 审计日志默认关闭，日志脱敏不足 |
| A10 - SSRF | ✅ 低风险 | 无外部 URL 请求场景 |

---

## 五、实施路线图

### Phase 1（P0 — 架构关键路径）

| 任务 | 预估工时 | 依赖 |
|---|---|---|
| TelemetryIngestService 事件驱动重构 | 3-5 天 | 无 |
| 批量 WebSocket 推送优化 | 1-2 天 | 无 |
| UDP 网关设备白名单 + 频率限制 | 2 天 | 无 |
| WebSocket 连接数限制 | 1 天 | 无 |

### Phase 2（P1 — 插件化扩展）

| 任务 | 预估工时 | 依赖 |
|---|---|---|
| 载荷插件 SPI 定义 + 现有载荷迁移 | 3 天 | Phase 1 完成 |
| 通信协议插件 SPI 定义 | 2 天 | 无 |
| AI 决策策略插件 SPI 定义 | 2 天 | 无 |
| 航线规划插件 SPI 定义 | 1 天 | 无 |
| api 包拆分 | 1 天 | 无 |
| mission 包拆分 | 1 天 | 无 |

### Phase 3（P2 — 性能深化）

| 任务 | 预估工时 | 依赖 |
|---|---|---|
| flightTrackStore 批量写入 | 2 天 | Phase 1 完成 |
| DroneSnapshot 增量推送 | 3 天 | Phase 1 完成 |
| TaskAssignment 锁优化 | 1 天 | 无 |
| VirtualDrone 并行 tick | 2 天 | 无 |
| JWT refresh token 机制 | 1 天 | 无 |
| 日志脱敏 | 1 天 | 无 |

### Phase 4（P3 — 安全加固）

| 任务 | 预估工时 | 依赖 |
|---|---|---|
| dev-mode 默认值统一 | 0.5 天 | 无 |
| SQL 注入审查 | 1 天 | 无 |
| 依赖漏洞扫描 | 1 天 | 无 |
| 审计日志完善 | 2 天 | 无 |

---

## 附录：关键文件索引

| 文件 | 行数 | 关键角色 |
|---|---|---|
| `gateway/TelemetryIngestService.java` | 497 | 消息分发中枢（重构目标） |
| `gateway/UdpGateway.java` | 169 | UDP 传输层 |
| `gateway/DeviceRegistry.java` | 101 | 设备注册表 |
| `api/TelemetryWebSocketHandler.java` | 143 | WebSocket 处理器 |
| `api/TelemetryPusher.java` | 89 | 1Hz 推送器 |
| `api/ApiExceptionHandler.java` | 110 | 统一异常处理 |
| `orch/OrchestrationPlanService.java` | 434+ | 编排计划服务 |
| `orch/StepExecutor.java` | 376 | 步骤执行引擎 |
| `orch/adapter/ModuleAdapter.java` | 51 | 模块适配器接口（插件化雏形） |
| `security/SecurityConfig.java` | 80 | Spring Security 配置 |
| `security/JwtTokenProvider.java` | 153 | JWT 令牌工具 |
| `security/RoleInterceptor.java` | 141 | RBAC 拦截器 |
| `security/TenantFilter.java` | 101 | 多租户过滤器 |
| `scheduling/TaskAssignmentService.java` | 401+ | 集群调度（含 GA 算法） |
| `vision/VisionSource.java` | 31 | 视觉数据源接口（策略模式） |
| `drone-sim/ai/DecisionEngine.java` | 230 | AI 决策引擎 |
| `drone-sim/ai/PathPlanner.java` | 365+ | A*/RRT 路径规划 |
| `drone-sim/VirtualDrone.java` | 2313 | 虚拟无人机核心 |

---

> **结论**：NexusSky 架构整体设计合理，`ModuleAdapter` 和 `VisionSource` 已展现出良好的插件化思维。最关键的架构债务是 `TelemetryIngestService` 的上帝类问题——通过事件驱动重构可同时解决耦合度、循环依赖和性能三个问题，应作为 Phase 1 的首要任务。