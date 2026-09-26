# NexusSky SDK API 参考

本文档为 NexusSky SDK 的统一 API 参考文档，涵盖 REST API 端点定义、Java SDK 方法映射、Python SDK 方法映射、响应格式和错误码。

## 目录

- [REST API 基础](#rest-api-基础)
- [认证方式](#认证方式)
- [设备管理 API](#设备管理-api)
- [飞行命令 API](#飞行命令-api)
- [任务管理 API](#任务管理-api)
- [飞行日志 API](#飞行日志-api)
- [编队管理 API](#编队管理-api)
- [响应格式](#响应格式)
- [错误码列表](#错误码列表)
- [Java SDK 方法映射表](#java-sdk-方法映射表)
- [Python SDK 方法映射表](#python-sdk-方法映射表)

---

## REST API 基础

- **基础路径**：`/api/v1/`
- **协议**：默认 HTTPS（开发环境可配置 HTTP）
- **数据格式**：JSON（`Content-Type: application/json`）
- **请求超时**：Java SDK 默认 30 秒；Python SDK 默认 30 秒（可配置）

## 认证方式

所有 API 请求通过 `X-API-Key` HTTP Header 进行认证：

```
X-API-Key: your-api-key
```

SDK 在内部自动添加此 Header，无需手动设置。

---

## 设备管理 API

### GET /api/v1/drones

获取所有无人机列表。

**响应数据**：无人机摘要列表，每个元素包含以下字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `sysid` | `int` | 无人机系统 ID |
| `online` | `bool` | 是否在线 |
| `armed` | `bool` | 是否已解锁 |
| `mode` | `string` | 飞行模式（如 STANDBY） |
| `battery` | `int` | 电池剩余百分比 |
| `voltage` | `int` | 电池电压（mV） |
| `lat` | `float` | 纬度 |
| `lon` | `float` | 经度 |
| `relativeAlt` | `float` | 相对高度（米） |
| `heading` | `int` | 航向角（度） |
| `satellites` | `int` | GPS 卫星数 |
| `gpsHealthy` | `bool` | GPS 是否健康 |

### GET /api/v1/drones/{sysid}

获取单个无人机的详细信息。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `sysid` | `int` | 无人机系统 ID |

**响应数据**：无人机详情，包含最新告警信息。

### GET /api/v1/drones/{sysid}/telemetry

获取无人机遥测数据快照。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `sysid` | `int` | 无人机系统 ID |

**响应数据**：当前遥测快照（位置、姿态、速度等）。

---

## 飞行命令 API

### POST /api/v1/drones/{sysid}/commands

向无人机发送飞行命令。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `sysid` | `int` | 无人机系统 ID |

**请求体**：

```json
{
  "type": "arm",
  "alt": 0
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `string` | 命令类型 |
| `alt` | `float` | 高度参数（仅 `takeoff` 命令使用，单位：米） |

**支持的命令类型**：

| 命令 | 说明 | alt 参数 |
|---|---|---|
| `arm` | 解锁无人机 | 不使用 |
| `disarm` | 锁定无人机 | 不使用 |
| `takeoff` | 起飞到指定高度 | 必填（目标高度，米） |
| `rtl` | 返航（Return To Launch） | 不使用 |
| `start_mission` | 开始执行航点任务 | 不使用 |

**响应数据**：命令执行结果，包含 `status` 和 `result` 字段。

---

## 任务管理 API

### POST /api/v1/drones/{sysid}/mission

上传航点任务到指定无人机。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `sysid` | `int` | 无人机系统 ID |

**请求体**：

```json
{
  "waypoints": [
    {"lat": 39.9042, "lon": 116.4074, "alt": 50},
    {"lat": 39.9050, "lon": 116.4080, "alt": 60}
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `waypoints` | `array` | 航点列表 |
| `waypoints[].lat` | `float` | 纬度 |
| `waypoints[].lon` | `float` | 经度 |
| `waypoints[].alt` | `float` | 高度（米） |

### GET /api/v1/drones/{sysid}/mission

下载指定无人机的航点任务。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `sysid` | `int` | 无人机系统 ID |

### POST /api/v1/drones/{sysid}/mission/clear

清除指定无人机的航点任务。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `sysid` | `int` | 无人机系统 ID |

---

## 飞行日志 API

### GET /api/v1/flightlog

查询飞行日志列表。

**响应数据**：飞行日志条目列表。

### GET /api/v1/flightlog/{logId}

获取指定飞行日志的详情。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `logId` | `string` | 日志 ID |

---

## 编队管理 API

### GET /api/v1/formation

获取所有编队列表。

**响应数据**：编队列表，每个元素包含 `formationId`、`state`、`shape` 等字段。

### POST /api/v1/formation

创建编队。

**请求体**：

```json
{
  "members": [1, 2, 3],
  "shape": "LINE",
  "spacing": 10,
  "heading": 0,
  "refLat": 39.9042,
  "refLon": 116.4074,
  "refAlt": 50
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `members` | `array<int>` | 成员无人机 sysid 列表 |
| `shape` | `string` | 队形名称（LINE / GRID / CIRCLE / VEE） |
| `spacing` | `float` | 间距（米） |
| `heading` | `float` | 航向角（度） |
| `refLat` | `float` | 参考点纬度 |
| `refLon` | `float` | 参考点经度 |
| `refAlt` | `float` | 参考点高度（米） |

**响应数据**：创建结果，包含 `formationId`、`assignments`、`state`、`leader`。

### GET /api/v1/formation/{id}

查询单个编队状态。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `id` | `int` | 编队 ID |

**响应数据**：编队详情，包含队形、成员、位置等信息。

### POST /api/v1/formation/{id}/command

向编队下发命令。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `id` | `int` | 编队 ID |

**请求体**：

```json
{
  "type": "TAKEOFF",
  "alt": 30
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `string` | 命令类型 |
| `alt` | `float` | 高度参数（仅 TAKEOFF 使用，单位：米） |

**支持的命令类型**：

| 命令 | 说明 |
|---|---|
| `TAKEOFF` | 编队起飞 |
| `TRANSITION` | 队形变换 |
| `LIGHTS` | 灯光控制 |
| `RTL` | 编队返航 |
| `DISSOLVE` | 解散编队 |

### POST /api/v1/formation/{id}/transition

队形平滑变换。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `id` | `int` | 编队 ID |

**请求体**：

```json
{
  "newShape": "CIRCLE",
  "steps": 5
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `newShape` | `string` | 新队形名称 |
| `steps` | `int` | 插值步数（>= 1） |

### POST /api/v1/formation/{id}/dissolve

解散编队。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `id` | `int` | 编队 ID |

---

## 响应格式

所有 API 响应采用统一的 `ApiResponse` 结构：

### 成功响应

```json
{
  "status": "ok",
  "data": { ... }
}
```

或列表数据：

```json
{
  "status": "ok",
  "data": [ ... ]
}
```

### 错误响应

```json
{
  "status": "error",
  "error": "错误描述信息"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` | `string` | `"ok"` 表示成功，`"error"` 表示失败 |
| `data` | `object / array` | 响应数据体（成功时存在） |
| `error` | `string` | 错误描述（失败时存在） |

---

## 错误码列表

| HTTP 状态码 | 错误类型 | 说明 | Python 异常类 |
|---|---|---|---|
| 400 | Bad Request | 请求参数错误（如航点列表为空、参数越界等） | `BadRequestError` |
| 401 | Unauthorized | API Key 无效或缺失 | `AuthenticationError` |
| 403 | Forbidden | API Key 权限不足 | `AuthenticationError` |
| 404 | Not Found | 资源不存在（如无人机未注册、编队不存在等） | `NotFoundError` |
| 500 | Internal Server Error | 服务器内部错误 | `ServerError` |
| 502 | Bad Gateway | 上游服务错误 | `ServerError` |
| 503 | Service Unavailable | 服务不可用 | `ServerError` |

> Java SDK 中所有错误统一抛出 `SdkException`，通过 `getStatusCode()` 获取 HTTP 状态码。
> Python SDK 中根据 HTTP 状态码抛出对应的子类异常。

---

## Java SDK 方法映射表

| REST 端点 | HTTP 方法 | NexusSkyClient 直接方法 | DroneApi / MissionApi / FlightLogApi |
|---|---|---|---|
| `/drones` | GET | `getDrones()` | `drones().list()` |
| `/drones/{sysid}` | GET | `getDrone(sysid)` | `drones().get(sysid)` |
| `/drones/{sysid}/telemetry` | GET | `getTelemetry(sysid)` | `drones().telemetry(sysid)` |
| `/drones/{sysid}/commands` | POST | `sendCommand(sysid, command)` / `sendCommand(sysid, command, alt)` | `drones().arm(sysid)` / `drones().takeoff(sysid, alt)` / `drones().rtl(sysid)` / `drones().startMission(sysid)` |
| `/drones/{sysid}/mission` | POST | - | `missions().upload(sysid, waypoints)` |
| `/drones/{sysid}/mission` | GET | - | `missions().download(sysid)` |
| `/drones/{sysid}/mission/clear` | POST | - | `missions().clear(sysid)` |
| `/flightlog` | GET | - | `flightLogs().list()` |
| `/flightlog/{logId}` | GET | - | `flightLogs().get(logId)` |
| `/formation` | GET | `getFormations()` | - |
| `/formation` | POST | `createFormation(...)` / `createFormation(payload)` | - |
| `/formation/{id}` | GET | `getFormation(formationId)` | - |
| `/formation/{id}/command` | POST | `commandFormation(formationId, type, alt)` | - |
| `/formation/{id}/transition` | POST | `transitionFormation(formationId, newShape, steps)` | - |
| `/formation/{id}/dissolve` | POST | `dissolveFormation(formationId)` | - |

---

## Python SDK 方法映射表

| REST 端点 | HTTP 方法 | NexusSkyClient 直接方法 | DroneApi / MissionApi / FlightLogApi |
|---|---|---|---|
| `/drones` | GET | `get_drones()` | `drones.list()` |
| `/drones/{sysid}` | GET | `get_drone(sysid)` | `drones.get(sysid)` |
| `/drones/{sysid}/telemetry` | GET | `get_telemetry(sysid)` | `drones.telemetry(sysid)` |
| `/drones/{sysid}/commands` | POST | `send_command(sysid, command, alt=0)` | `drones.arm(sysid)` / `drones.takeoff(sysid, alt)` / `drones.rtl(sysid)` / `drones.start_mission(sysid)` |
| `/drones/{sysid}/mission` | POST | - | `missions.upload(sysid, mission)` |
| `/drones/{sysid}/mission` | GET | - | `missions.download(sysid)` |
| `/drones/{sysid}/mission/clear` | POST | - | `missions.clear(sysid)` |
| `/flightlog` | GET | - | `flight_logs.list()` |
| `/flightlog/{logId}` | GET | - | `flight_logs.get(log_id)` |
| `/formation` | GET | `get_formations()` | - |
| `/formation` | POST | `create_formation(members, shape, ...)` | - |
| `/formation/{id}` | GET | `get_formation(formation_id)` | - |
| `/formation/{id}/command` | POST | `command_formation(formation_id, command_type, alt=0)` | - |
| `/formation/{id}/transition` | POST | `transition_formation(formation_id, new_shape, steps)` | - |
| `/formation/{id}/dissolve` | POST | `dissolve_formation(formation_id)` | - |