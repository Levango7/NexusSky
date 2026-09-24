# NexusSky SDK 参考

> **版本**：v1.1 | 完整 API 文档：启动后访问 http://localhost:8080/swagger-ui.html
>
> **注意**：本文档为快速参考，完整端点详情请见 [api-reference.md](api-reference.md)

---

## 一、认证 API

### POST /api/auth/login
获取 JWT 令牌。

**请求：**
```json
{"username": "admin", "password": "admin"}
```

**响应：**
```json
{"token": "eyJhbG...", "expiresIn": 3600, "username": "admin"}
```

### POST /api/auth/refresh
刷新 JWT 令牌（需携带当前有效令牌）。

---

## 二、设备管理 API

### GET /api/v1/drones
查询所有无人机状态。

**响应：**
```json
[
  {
    "sysid": 1,
    "online": true,
    "armed": false,
    "mode": "STANDBY",
    "battery": 85,
    "voltage": 12400,
    "lat": 39.9042,
    "lon": 116.4074,
    "relativeAlt": 0,
    "heading": 0,
    "satellites": 12,
    "gpsHealthy": true
  }
]
```

### GET /api/v1/drones/{sysid}
查询单个无人机详情。

### GET /api/v1/drones/{sysid}/telemetry
获取遥测数据快照。

### GET /api/v1/drones/{sysid}/track
获取飞行轨迹（最近 200 点）。

### GET /api/v1/drones/{sysid}/mission
下载当前机载任务（MAVLink mission download 协议）。

---

## 三、任务管理 API

### POST /api/v1/drones/{sysid}/mission
上传航点任务（完整 MAVLink mission 协议）。

**请求：**
```json
{
  "items": [
    {"cmd": "waypoint", "lat": 39.9042, "lon": 116.4074, "alt": 50, "holdTime": 2},
    {"cmd": "takeoff", "alt": 30},
    {"cmd": "rtl"}
  ]
}
```

### POST /api/v1/drones/{sysid}/commands
发送飞行命令。

**请求：**
```json
{"type": "arm"}
```

支持的命令类型：`arm` / `disarm` / `start_mission` / `rtl` / `takeoff` / `raw`

### POST /api/v1/drones/{sysid}/joystick
虚拟摇杆手动控制（MANUAL_CONTROL 透传）。

**请求：**
```json
{"x": 0, "y": 700, "z": 500, "r": 0}
```

---

## 四、集群调度 API

### POST /api/scheduling/tasks
创建调度任务（综合评分：能力匹配 40% + 电量 30% + 距离 20% + 优先级 10%）。

**请求：**
```json
{
  "taskId": "task-001",
  "taskType": "SURVEY",
  "priority": 5,
  "targetLat": 39.9042,
  "targetLon": 116.4074,
  "targetAlt": 50
}
```

**响应：**
```json
{
  "taskId": "task-001",
  "assignedSysid": 1,
  "score": 85.3,
  "success": true
}
```

### GET /api/scheduling/tasks
查询所有调度任务。

### DELETE /api/scheduling/tasks/{id}
取消调度任务。

### GET /api/v1/squad/roles
查询角色状态（Leader/Worker/Relay 状态机）。

### POST /api/v1/squad/assign
重计算并分派环绕任务。

---

## 五、编队 API

### POST /api/v1/formation
创建编队。

**请求：**
```json
{
  "name": "delta-team",
  "shape": "V_FORMATION",
  "spacing": 10,
  "members": [1, 2, 3]
}
```

### POST /api/v1/formation/{id}/lights
控制编队灯光。

**请求：**
```json
{"on": true, "pattern": "BLINK", "brightness": 80, "colorR": 255}
```

### POST /api/v1/formation/{id}/transition
队形变换。

### POST /api/v1/formation/{id}/command
下发编队命令（TAKEOFF/TRANSITION/LIGHTS/RTL/DISSOLVE）。

### POST /api/v1/formation/{id}/dissolve
解散编队。

---

## 六、数字孪生 API

### GET /api/twin/state/{sysid}
查询数字孪生状态。

### GET /api/twin/predict/{sysid}?horizon=30
轨迹预测（未来 30 秒）。

**响应：**
```json
{
  "sysid": 1,
  "predictions": [
    {"t": 0, "lat": 39.9042, "lon": 116.4074, "alt": 50},
    {"t": 5, "lat": 39.9045, "lon": 116.4077, "alt": 51}
  ]
}
```

### GET /api/twin/compare/{sysid}
虚实对比（实测态 vs 模型预测态）。

---

## 七、边缘计算 API

### POST /api/edge/results
提交边缘计算结果。

**请求：**
```json
{
  "sysid": 1,
  "taskId": "task-001",
  "type": "DETECTION",
  "result": "person"
}
```

### GET /api/edge/tasks
获取所有边缘任务。

### GET /api/edge/fusion/{sysid}
获取传感器融合数据。

---

## 八、应急编排 API

### POST /api/v1/emergency/orch/start
启动应急编排计划。

**请求：**
```json
{
  "scenarioType": 3,
  "centerLat": 225900000,
  "centerLon": 1139300000,
  "radius": 1000,
  "droneIds": [1, 2, 3]
}
```

### POST /api/emergency-command
创建应急指挥命令（接报阶段）。

### POST /api/emergency-command/{id}/one-click
一键应急响应（自动走完接报→研判→部署→执行）。

### GET /api/v1/emergency/scenarios
查询场景预设列表。

---

## 九、Mesh 网络 API

### GET /api/v1/mesh/topology
获取全网拓扑（节点数、邻居关系、链路质量）。

### GET /api/v1/mesh/topology/{sysid}
获取单节点拓扑；不存在返回 404。

### GET /api/v1/mesh/neighbors/{sysid}
获取单节点邻居表（含 RSSI 与质量分级）。

### GET /api/v1/mesh/links
获取所有链路及质量分级（A-B 与 B-A 合并）。

### POST /api/v1/mesh/adjust-max-hops
根据当前网络节点数动态调整 MAX_HOPS（FR-18）。

**请求：**
```json
{"nodeCount": 35}
```

**响应：**
```json
{
  "previousMaxHops": 15,
  "currentMaxHops": 20,
  "nodeCount": 35,
  "networkScale": "MEDIUM",
  "adjusted": true
}
```

**动态调整规则（`DynamicMaxHops`）：**

| 网络规模 | 节点数 | MAX_HOPS |
|---|---|---|
| SMALL | ≤ 20 | 15 |
| MEDIUM | 21-50 | 20 |
| LARGE | > 50 | 25（绝对上限 30） |

### GET /api/v1/mesh/max-hops
查询当前生效的 MAX_HOPS 值。

**响应：**
```json
{"currentMaxHops": 20, "nodeCount": 35, "networkScale": "MEDIUM"}
```

---

## 十、卫星接入 API（预留）

> **注意**：以下接口为真实卫星接入预留占位，当前实现抛出 `UnsupportedOperationException`。
> 仿真环境请使用 `SimulatedSatLinkProvider`，卫星链路监控请使用 `/api/v1/sat-link/*` 端点。

### GET /api/v1/sat/providers
列出可用卫星提供商及体制信息。

**响应：**
```json
[
  {"type": "TIANTONG", "label": "天通", "band": "S", "status": "PLACEHOLDER"},
  {"type": "IRIDIUM", "label": "铱星", "band": "L", "status": "PLACEHOLDER"},
  {"type": "STARLINK", "label": "星链", "band": "Ku/Ka", "status": "PLACEHOLDER"}
]
```

### POST /api/v1/sat/providers/{type}/connect
连接指定体制的卫星链路。`{type}` 取值：`TIANTONG` / `IRIDIUM` / `STARLINK`。

> **当前状态**：所有 provider 的 `connect()` 方法抛出 `UnsupportedOperationException`，
> 返回 HTTP 501（Not Implemented）。真实卫星接入待后续版本实现。

**请求：**
```json
{"satId": "TIANTONG-1"}
```

**响应（当前）：**
```json
{"error": "真实卫星接入尚未实现，请使用 SimulatedSatLinkProvider", "status": 501}
```

---

## 十一、License API

### GET /api/license/info
查询 License 信息。

### POST /api/license/activate
激活 License。

### GET /api/license/verify
验证 License 有效性。

---

## 十二、WebSocket 事件

连接：`ws://localhost:8080/ws/telemetry`

| 事件类型 | 触发条件 | 数据格式 |
|---|---|---|
| `telemetry` | 每秒推送 | `{sysid, battery, lat, lon, alt, ...}` |
| `alert` | 告警触发 | `{sysid, type, severity, message}` |
| `status` | 设备状态变更 | `{sysid, online, mode, ...}` |

---

## 十三、SSE 事件流

| 端点 | 事件名 | 心跳间隔 |
|---|---|---|
| GET /api/alarms/stream | `alarm-event` | 15 秒 |
| GET /api/surveillance/devices/{id}/events | `surveillance-event` | 15 秒 |
---

## 十四、MAVLink 扩展消息

NexusSky 已注册 **60 条扩展消息**（msgId 420-479），覆盖 M0a-M13 及 4a 安防报警等里程碑能力。

| msgId 区间 | 里程碑 | 说明 |
|---|---|---|
| 420-441 | M0a-M4 | Mesh 中继、编队协同、喷洒物流、硬件抽象 |
| 450-467 | M5-M9 | 应急 mesh、星地中继、数字孪生、边缘计算、应急编排 |
| 468-476 | M10-M13 | 集群调度、故障检测、视觉感知、链路韧性 |
| 477-479 | 4a | 安防报警联动 |

> 扩展消息注册与编解码细节请参见 [integration-guide.md](integration-guide.md#12-扩展消息注册)。