# NexusSky SDK 参考

> **版本**：v1.0 | 完整 API 文档：启动后访问 http://localhost:8080/swagger-ui.html

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
{"token": "eyJhbG...", "expiry": 3600}
```

---

## 二、设备管理 API

### GET /api/drones
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

### GET /api/drones/{sysid}
查询单个无人机详情。

---

## 三、任务管理 API

### POST /api/mission/upload
上传航点任务。

**请求：**
```json
{
  "sysid": 1,
  "waypoints": [
    {"seq": 0, "lat": 39.9042, "lon": 116.4074, "alt": 50, "command": 16},
    {"seq": 1, "lat": 39.9050, "lon": 116.4080, "alt": 50, "command": 16}
  ]
}
```

### POST /api/mission/start
启动任务。`{"sysid": 1}`

### POST /api/mission/rtl
返航。`{"sysid": 1}`

---

## 四、集群调度 API

### POST /api/scheduling/assign
分配任务到最优无人机。

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
  "detail": "sysid=1 score=85.3",
  "success": true
}
```

### GET /api/scheduling/assignments
查询所有分配结果。

### DELETE /api/scheduling/cancel/{taskId}
取消任务分配。

---

## 五、编队 API

### POST /api/formation/create
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

### POST /api/formation/{id}/led
控制编队灯光。

**请求：**
```json
{"pattern": "BLINK", "color": "RED", "frequency": 2}
```

---

## 六、数字孪生 API

### GET /api/twin/state/{sysid}
查询数字孪生状态。

### GET /api/twin/predict/{sysid}?horizonSeconds=30
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

### POST /api/twin/replay
场景回放。

**请求：**
```json
{"startTime": "2026-09-17T00:00:00Z", "endTime": "2026-09-17T01:00:00Z"}
```

---

## 七、边缘计算 API

### POST /api/edge/task
下发边缘任务。

**请求：**
```json
{
  "taskType": "VIDEO_ANALYSIS",
  "sysid": 1,
  "params": {"model": "yolov8", "confidence": 0.5}
}
```

### GET /api/edge/tasks
查询边缘任务状态。

---

## 八、应急编排 API

### POST /api/v1/emergency/orchestrate
启动应急编排。

**请求：**
```json
{
  "scenario": "EARTHQUAKE",
  "disasterArea": {"centerLat": 30.0, "centerLon": 103.0, "radiusKm": 10},
  "availableDrones": [1, 2, 3, 4, 5]
}
```

---

## 九、License API

### GET /api/license/info
查询 License 信息。

### POST /api/license/activate
激活 License。

### GET /api/license/verify
验证 License 有效性。

---

## 十、WebSocket 事件

连接：`ws://localhost:8080/ws/telemetry`

| 事件类型 | 触发条件 | 数据格式 |
|---|---|---|
| `telemetry` | 每秒推送 | `{sysid, battery, lat, lon, alt, ...}` |
| `alert` | 告警触发 | `{sysid, type, severity, message}` |
| `formation` | 编队状态变更 | `{formationId, state, members[]}` |
| `scheduling` | 调度结果 | `{taskId, sysid, score}` |
| `decision` | AI 决策 | `{sysid, decisionType, reason}` |