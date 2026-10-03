# NexusSky 集成手册

> **版本**：v1.1 | **适用对象**：将 NexusSky 对接现有飞控系统的开发人员 | **日期**：2026-09-24

---

## 一、MAVLink 协议接入

### 1.1 UDP 连接

NexusSky 云后端通过 UDP 接收 MAVLink 帧：

```
无人机(PX4/ArduPilot) ──MAVLink/UDP──► cloud-backend:14550
```

**配置**（application.properties）：
```properties
aerofleet.udp-port=14550        # 云端监听端口
aerofleet.drone-port=14540      # 无人机端端口
aerofleet.heartbeat-timeout-seconds=10  # 心跳超时
```

### 1.2 扩展消息注册

NexusSky 已注册 51 条扩展消息（msgId 30000-30063，区间内含空号）。各区间分配如下：

| msgId 区间 | 里程碑 | 说明 |
|---|---|---|
| 30000-30021 | M0a-M4 | Mesh 中继、编队协同、喷洒物流、硬件抽象 |
| 30030-30047 | M5-M9 | Mesh 组网、移动基站、星地中继、地形适配、应急编排 |
| 30048-30056 | M10-M13 | 集群调度、自主决策、边缘计算、数字孪生 |
| 30057-30059 | 4a | 安防报警联动 |
| 30060-30063 | P2 | 灾害应急通讯组网（QoS 路由/分簇/灾害模式/蜂鸣器） |

如需新增：

1. 创建消息类（继承 MavlinkMessage）
2. 在 `MavlinkMessage.java` decode switch 添加 case
3. 在 `MavlinkMessageInfo.java` INFOS 数组添加元数据
4. 在 `TelemetryIngestService.java` 添加路由

### 1.3 兼容性验证

- PX4 SITL：`make px4_sitl jmavsim` → MAVLink 2.0 兼容
- ArduPilot SITL：`sim_vehicle.py -v ArduCopter` → MAVLink 2.0 兼容

---

## 二、REST API 集成

### 2.1 认证

```bash
# 获取 JWT 令牌
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

# 响应：{"token":"eyJhbG...","expiry":3600}
```

### 2.2 设备管理

```bash
# 查询所有设备
curl http://localhost:8080/api/v1/drones \
  -H "Authorization: Bearer <token>"

# 响应：[{"sysid":1,"online":true,"battery":85,"lat":39.9,"lon":116.4,...}]
```

### 2.3 任务下发

```bash
# 航点任务上传（items 数组，每项 cmd/lat/lon/alt/holdTime，上限 1000 项）
curl -X POST http://localhost:8080/api/v1/drones/1/mission \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"items":[{"cmd":"waypoint","lat":39.9,"lon":116.4,"alt":50,"holdTime":2},...]}'
```

### 2.4 集群调度

```bash
# 任务分配（taskType 取值 SURVEY/SPRAY/RELAY/RESCUE）
curl -X POST http://localhost:8080/api/v1/scheduling/tasks \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"taskId":"task-001","taskType":"SURVEY","priority":5,"targetLat":39.9,"targetLon":116.4}'
```

### 2.5 数字孪生

```bash
# 查询孪生状态
curl http://localhost:8080/api/v1/twin/state/1 \
  -H "Authorization: Bearer <token>"

# 轨迹预测（horizon 为预测秒数，默认 30）
curl http://localhost:8080/api/v1/twin/predict/1?horizon=30 \
  -H "Authorization: Bearer <token>"
```

---

## 三、WebSocket 实时接入

### 3.1 连接

```javascript
const ws = new WebSocket('ws://localhost:8080/ws/telemetry');
ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('遥测更新:', data);
    // {sysid:1, battery:85, lat:39.9, lon:116.4, ...}
};
```

### 3.2 消息类型

| 类型 | 说明 |
|---|---|
| telemetry | 实时遥测数据（位置/姿态/电量） |
| alert | 告警信息（环境/链路/电量） |
| formation | 编队状态更新 |
| scheduling | 调度结果通知 |
| decision | AI 决策事件 |

---

## 四、License 激活

### 4.1 查询 License

```bash
curl http://localhost:8080/api/v1/license/info
# {"tenantId":"dev","productName":"AeroFleet Cloud Dev Edition","devEdition":true}
```

### 4.2 激活

```bash
curl -X POST http://localhost:8080/api/v1/license/activate \
  -H "Content-Type: application/json" \
  -d '{"activationCode":"<code>","tenantId":"<tenant>","machineId":"<machine>"}'
```

### 4.3 配置 License

```properties
# application.properties
aerofleet.license.key=<Base64编码的License Key>
aerofleet.license.enabled=true
```

---

## 五、多租户使用

### 5.1 租户标识

租户归属由服务端从认证凭证解析（JWT 的 `tenant_id` claim 或 API Key 的租户绑定），客户端无法通过 header 自选租户：
```bash
curl http://localhost:8080/api/v1/drones \
  -H "Authorization: Bearer <token>"
```

### 5.2 限流配置

```properties
aerofleet.tenant.rate-limit=100  # 每限流 key（真实租户或客户端 IP）每分钟 API 调用上限
```

---

## 六、Mesh 网络配置

### 6.1 动态 MAX_HOPS（FR-18）

Mesh 网络的 MAX_HOPS（最大跳数）可根据网络节点数动态调整，避免小网络冗余转发、大网络覆盖不足。

**启用方式**（drone-sim CLI 参数）：

```bash
java -jar drone-sim.jar --mesh-dynamic-max-hops true
```

**调整规则（`DynamicMaxHops`）：**

| 网络规模 | 节点数 | MAX_HOPS |
|---|---|---|
| SMALL | ≤ 20 | 15 |
| MEDIUM | 21-50 | 20 |
| LARGE | > 50 | 25（绝对上限 30） |

未启用时（默认），MAX_HOPS 保持静态值 15（`--mesh-max-hops` 参数可覆盖默认值）。

**REST API 查询与调整：**

```bash
# 查询当前 MAX_HOPS
curl http://localhost:8080/api/v1/mesh/max-hops \
  -H "Authorization: Bearer <token>"

# 动态调整 MAX_HOPS（需 ADMIN 角色）
curl -X POST http://localhost:8080/api/v1/mesh/adjust-max-hops \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"nodeCount": 35}'
```

### 6.2 其他 Mesh CLI 参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--mesh-max-hops` | 15 | 静态 MAX_HOPS（动态模式启用后自动覆盖） |
| `--mesh-dynamic-max-hops` | false | 启用动态 MAX_HOPS 调整 |
| `--mesh-hello-ms` | 1000 | HELLO 广播间隔（ms） |
| `--mesh-neighbor-timeout-ms` | 5000 | 邻居超时（ms） |
| `--mesh-route-lifetime-ms` | 30000 | 路由有效期（ms） |
| `--mesh-transport` | UDP | 传输层类型（UDP/LoRa） |

---

## 七、卫星接入预留接口

NexusSky 为三类真实卫星体制预留了统一接入点，当前为占位实现（抛出 `UnsupportedOperationException`），仿真环境请使用 `SimulatedSatLinkProvider`。

### 7.1 卫星提供商

| 体制 | SatType | 波段 | 典型带宽 | 典型延迟 | 占位实现类 |
|---|---|---|---|---|---|
| 天通 | `TIANTONG` | S | 9.6 kbps | 500 ms | `TiantongSatLinkProvider` |
| 铱星 | `IRIDIUM` | L | 2.4 kbps | 1500 ms | `IridiumSatLinkProvider` |
| 星链 | `STARLINK` | Ku/Ka | 100 Mbps | 20 ms | `StarlinkSatLinkProvider` |

### 7.2 预留 REST 端点

```bash
# 列出可用卫星提供商
curl http://localhost:8080/api/v1/sat/providers \
  -H "Authorization: Bearer <token>"

# 连接卫星（当前返回 501 Not Implemented）
curl -X POST http://localhost:8080/api/v1/sat/providers/TIANTONG/connect \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"satId": "TIANTONG-1"}'
```

### 7.3 仿真卫星链路监控

仿真环境下，卫星链路状态监控使用独立的 `/api/v1/sat-link/*` 端点：

| 端点 | 说明 |
|---|---|
| `GET /api/v1/sat-link/status` | 获取所有卫星链路状态 |
| `GET /api/v1/sat-link/passes` | 获取所有过境计划 |
| `GET /api/v1/sat-link/routes` | 获取最近路由决策历史 |
| `GET /api/v1/sat-link/strategy` | 获取当前切换策略 |
| `PUT /api/v1/sat-link/strategy` | 设置切换策略（运行时热更新） |
| `GET /api/v1/sat-link/constellation` | 获取星座配置 |

---

*完整 API 文档：启动后访问 http://localhost:8080/swagger-ui.html*