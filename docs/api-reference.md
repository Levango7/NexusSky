# NexusSky API 参考

> 自动生成于 2026-09-26，共 60 个 `@RestController`、3 个 `@Service` 辅助类、318 个 REST API 端点
>
> 基础设施：Spring Boot + MAVLink 协议 + JWT 认证 + OpenAPI 3.0: 注解
>
> 认证方式：除 `/api/auth/login` 外，所有端点要求 `Authorization: Bearer <JWT>` 请求头；部分端点额外要求 `ADMIN` 或 `OPERATOR` 角色（通过 `@RequireRole` 注解声明）。API Key 认证通过 `X-API-Key` 请求头，与 JWT 等效。

## 目录

- [无人机控制](#无人机控制)
- [飞行追踪](#飞行追踪)
- [电子围栏](#电子围栏)
- [远程锁机](#远程锁机)
- [安防监控](#安防监控)
- [报警联动](#报警联动)
- [应急指挥](#应急指挥)
- [编队表演](#编队表演)
- [喷洒物流](#喷洒物流)
- [视觉感知](#视觉感知)
- [硬件抽象](#硬件抽象)
- [通信组网](#通信组网)
- [集群调度](#集群调度)
- [AI 决策](#ai-决策)
- [边缘计算](#边缘计算)
- [数字孪生](#数字孪生)
- [环境气象](#环境气象)
- [安全认证](#安全认证)
- [审计日志](#审计日志)
- [许可证](#许可证)
- [自动出警](#自动出警)
- [语音指挥](#语音指挥)
- [空地协同](#空地协同)
- [通信自适应](#通信自适应)
- [灾害通信](#灾害通信)
- [健康管理](#健康管理)
- [智能巡检](#智能巡检)
- [航拍测绘](#航拍测绘)
- [编排管理](#编排管理)
- [场景管理](#场景管理)
- [编队表演-灯光秀](#编队表演-灯光秀)
- [物流配送2](#物流配送2)
- [数字孪生-城市](#数字孪生-城市)
- [离线自治](#离线自治)
- [LoRa 回传](#lora-回传)
- [用户管理](#用户管理)
- [租户管理](#租户管理)
- [API Key 管理](#api-key-管理)
- [Webhook 管理](#webhook-管理)
- [OpenAPI 导出](#openapi-导出)

---

## 无人机控制

### 基础路径 `/api/v1/drones`

**Controller**: `api/DroneController` | **Tag**: 无（Fleet REST API）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 无人机列表（在线/离线、电量、模式、位置、最后心跳） | - | 200 List<DroneSummary> |
| GET | `/{sysid}` | 单无人机详情（含最近 20 条告警） | - | 200 DroneDetail / 404 |
| GET | `/{sysid}/telemetry` | 获取遥测数据快照 | - | 200 Telemetry / 404 |
| GET | `/{sysid}/track` | 飞行轨迹（最近 200 点，时间升序） | - | 200 List<TrackPoint> / 404 |
| GET | `/{sysid}/mission` | 下载当前任务（MAVLink mission download） | - | 200 {status,count,items} |
| POST | `/{sysid}/joystick` | 虚拟摇杆（MANUAL_CONTROL 透传，轴值 -1000..1000） | {x,y,z,r} | 200 {status:"ok"} |
| POST | `/{sysid}/mission` | 上传航点任务（完整 MAVLink mission 协议） | {items:[{cmd,lat,lon,alt,holdTime}]} | 200 {status,uploaded} |
| POST | `/{sysid}/commands` | 发送飞行命令（arm/disarm/start_mission/rtl/takeoff/raw） | {type,alt?,cmd?,p1..p7?} | 200 {status,result} |

#### 端点详情

**GET /api/v1/drones**
- 响应: 200 - 无人机摘要列表（含 sysid、online、battery、mode、position、lastHeartbeat）

**GET /api/v1/drones/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 无人机详情；404 - 无人机未注册

**GET /api/v1/drones/{sysid}/telemetry**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 完整遥测 JSON；404 - 无人机未注册

**GET /api/v1/drones/{sysid}/track**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `[{lat,lon,alt,ts}, ...]`，最旧在前，最近 200 点；404 - 无人机未注册

**GET /api/v1/drones/{sysid}/mission**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{status:"ok", count, items:[{seq,command,lat,lon,alt,holdTime,frame}]}`

**POST /api/v1/drones/{sysid}/joystick** （需 OPERATOR 角色）
- 路径参数: `sysid` (int) - 无人机系统 ID
- 请求体: `{x:int, y:int, z:int, r:int}`（x/y/r ∈ [-1000,1000]，z ∈ [0,1000]）
- 响应: 200 - `{status:"ok"}`；400 - 轴值越界；404 - 无人机未注册

**POST /api/v1/drones/{sysid}/mission** （需 OPERATOR 角色）
- 路径参数: `sysid` (int) - 无人机系统 ID
- 请求体: `{items:[{cmd:String, lat:double, lon:double, alt:double, holdTime:double}, ...]}`（最多 1000 个航点）
- 响应: 200 - `{status, uploaded, error?}`；400 - items 为空或超过 1000；404 - 无人机未注册

**POST /api/v1/drones/{sysid}/commands** （需 OPERATOR 角色）
- 路径参数: `sysid` (int) - 无人机系统 ID
- 请求体: `{type:"arm"|"disarm"|"start_mission"|"rtl"|"takeoff"|"raw", alt?:double, cmd?:int, p1..p7?:double}`
- 响应: 200 - `{status:"ok", result:"ACCEPTED"|"MAV_RESULT_*"}`；400 - 不支持的命令类型；404 - 无人机未注册

**curl 示例**:
```bash
# 获取无人机列表
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/drones

# 上传航点任务
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"items":[{"cmd":"waypoint","lat":22.59,"lon":113.93,"alt":50,"holdTime":2}]}' \
  http://localhost:8080/api/v1/drones/1/mission

# 解锁起飞
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"type":"arm"}' http://localhost:8080/api/v1/drones/1/commands
```

### 基础路径 `/api/v1/flightlog`

**Controller**: `api/FlightLogController` | 飞行日志查询 API

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 查询飞行日志（支持 day/type/sysid/limit 过滤） | - | 200 List<LogEntry> |
| GET | `/track` | 查询指定无人机某日轨迹 | - | 200 List<{lat,lon,alt,ts}> |

#### 端点详情

**GET /api/v1/flightlog**
- 查询参数: `day` (String, 可选, YYYY-MM-DD, 默认今天), `type` (String, 可选, telemetry|alert|mission|connectivity), `sysid` (int, 可选), `limit` (int, 默认 200, 上限 5000)
- 响应: 200 - 日志条目列表

**GET /api/v1/flightlog/track**
- 查询参数: `day` (String, 可选, 默认今天), `sysid` (int, 必填)
- 响应: 200 - `[{lat,lon,alt,ts}, ...]`

**curl 示例**:
```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/flightlog?day=2026-09-20&type=alert&limit=100"
```

---

## 飞行追踪

### 基础路径 `/api/tracking`

**Controller**: `tracking/TrackingController` | **Tag**: Tracking - 无人机追踪 REST API：飞行轨迹查询、历史轨迹回放、遗失辅助查找

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/{sysid}/track` | 获取飞行轨迹（按时间升序，最新在末尾） | - | 200 List<TrackPoint> |
| GET | `/{sysid}/replay` | 历史轨迹回放（按时间范围查询） | - | 200 List<TrackPoint> |
| GET | `/{sysid}/last-known` | 获取最后已知位置 | - | 200 TrackPoint / 200 null |
| GET | `/lost` | 获取失联无人机列表 | - | 200 List<LostAlert> |
| GET | `/{sysid}/search-guide` | 获取辅助查找信息（最后位置+轨迹方向+电量+预计坠落范围） | - | 200 SearchGuide / 404 |
| GET | `/scan` | 触发一次失联检测扫描（管理端点） | - | 200 {newlyLost,totalLost} |

#### 端点详情

**GET /api/tracking/{sysid}/track**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 查询参数: `limit` (Integer, 可选) - 最多返回 N 条，<=0 表示不限制
- 响应: 200 - 轨迹点列表；404 - 无人机未注册

**GET /api/tracking/{sysid}/replay**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 查询参数: `from` (long, 默认 0, epoch ms, 0=不限起始), `to` (long, 默认 0, epoch ms, 0=不限结束), `limit` (int, 默认 1000)
- 响应: 200 - 轨迹点列表（按时间升序）；404 - 无人机未注册

**GET /api/tracking/{sysid}/last-known**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 最新轨迹点（有注册但无轨迹时返回 null body）；404 - 无人机未注册

**GET /api/tracking/lost**
- 响应: 200 - 失联无人机列表（含告警时间、最后位置、电量等）

**GET /api/tracking/{sysid}/search-guide**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 辅助查找信息；404 - 无人机未注册或无查找信息

**GET /api/tracking/scan**
- 响应: 200 - `{newlyLost:List<int>, totalLost:int}`（本次扫描新失联的 sysid 列表）

**curl 示例**:
```bash
# 历史轨迹回放
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/tracking/1/replay?from=1695600000000&to=1695603600000&limit=500"

# 获取失联无人机列表
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/tracking/lost
```

---

## 电子围栏

### 基础路径 `/api/geofence`

**Controller**: `geofence/GeofenceController` | **Tag**: Geofence - 电子围栏 REST API：围栏区域 CRUD、越界历史查询、手动检查

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/zones` | 创建围栏区域（支持 CIRCLE 和 POLYGON） | Zone | 200 Zone / 400 |
| GET | `/zones` | 列出所有围栏区域 | - | 200 {items,total} |
| GET | `/zones/{id}` | 获取单个围栏区域 | - | 200 Zone / 404 |
| PUT | `/zones/{id}` | 更新围栏区域 | Zone | 200 Zone / 404 / 400 |
| DELETE | `/zones/{id}` | 删除围栏区域 | - | 200 {deleted,id} / 404 |
| GET | `/breaches` | 获取越界历史（支持 sysid/zoneId 过滤） | - | 200 {items,total} |
| POST | `/check` | 手动触发一次全量围栏检查 | - | 200 {newEvents,count,timestamp} |

#### 端点详情

**POST /api/geofence/zones**
- 请求体（圆形）: `{id:int, name:String, type:"CIRCLE", centerLat:double, centerLon:double, radiusM:double, action:"WARN"|"LOCK_RTH", enabled?:boolean}`
- 请求体（多边形）: `{id:int, name:String, type:"POLYGON", points:[{lat:double,lon:double}, ...], action:"WARN"|"LOCK_RTH", enabled?:boolean}`（至少 3 个点）
- 响应: 200 - 围栏详情；400 - 请求体格式错误

**GET /api/geofence/zones**
- 响应: 200 - `{items:[Zone], total:int}`

**GET /api/geofence/zones/{id}**
- 路径参数: `id` (int) - 围栏 ID
- 响应: 200 - 围栏详情；404 - 围栏不存在

**PUT /api/geofence/zones/{id}**
- 路径参数: `id` (int) - 围栏 ID
- 请求体: 同 POST（body 中的 id 会被强制设为路径 id）
- 响应: 200 - 更新后的围栏；404 - 围栏不存在；400 - 请求体格式错误

**DELETE /api/geofence/zones/{id}**
- 路径参数: `id` (int) - 围栏 ID
- 响应: 200 - `{deleted:true, id:int}`；404 - 围栏不存在

**GET /api/geofence/breaches**
- 查询参数: `sysid` (Integer, 可选) - 按无人机过滤, `zoneId` (Integer, 可选) - 按围栏过滤
- 响应: 200 - `{items:[BreachEvent], total:int}`

**POST /api/geofence/check**
- 响应: 200 - `{newEvents:[BreachEvent], count:int, timestamp:long}`（检查所有在线无人机位置是否越界）

**curl 示例**:
```bash
# 创建圆形围栏
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"id":1,"name":"base","type":"CIRCLE","centerLat":22.5,"centerLon":113.9,"radiusM":500,"action":"WARN"}' \
  http://localhost:8080/api/geofence/zones

# 手动触发全量检查
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/geofence/check
```

---

## 远程锁机

### 基础路径 `/api/drone-lock`

**Controller**: `drone/DroneLockController` | **Tag**: Drone Lock - 无人机远程锁定/解锁

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/{sysid}/lock` | 锁定无人机（禁止起飞/强制降落/返航锁定） | LockRequest | 200 LockState / 404 / 400 |
| POST | `/{sysid}/unlock` | 解锁无人机 | LockRequest | 200 LockState / 404 / 400 |
| GET | `/{sysid}` | 获取锁定状态 | - | 200 LockState / 404 |
| GET | `/locked` | 获取所有已锁定无人机 | - | 200 List<LockState> |
| GET | `/all` | 获取所有无人机锁定状态 | - | 200 List<LockState> |
| DELETE | `/{sysid}` | 清除锁定状态记录（管理端点） | - | 200 {status,sysid} / 404 |

#### 端点详情

**POST /api/drone-lock/{sysid}/lock**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 请求体: `{reason:String, lockedBy:String, action:"DISARM"|"FORCE_LAND"|"RETURN_TO_LAUNCH"}`
- 响应: 200 - 锁定状态（幂等，已锁定返回当前状态）；404 - 无人机未注册；400 - lockedBy 缺失或 action 非法

**POST /api/drone-lock/{sysid}/unlock**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 请求体: `{unlockedBy:String}`
- 响应: 200 - 解锁后的状态；404 - 无人机未注册；400 - 未锁定或 unlockedBy 缺失

**GET /api/drone-lock/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 锁定状态；404 - 无人机未注册

**GET /api/drone-lock/locked**
- 响应: 200 - 当前处于锁定状态的无人机列表

**GET /api/drone-lock/all**
- 响应: 200 - 所有有锁定记录的无人机状态

**DELETE /api/drone-lock/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{status:"ok", sysid:int}`；404 - 无人机未注册

**curl 示例**:
```bash
# 锁定无人机（强制降落）
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"reason":"违规飞行","lockedBy":"admin","action":"FORCE_LAND"}' \
  http://localhost:8080/api/drone-lock/1/lock

# 查询所有已锁定无人机
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/drone-lock/locked
```

---

## 安防监控

### 基础路径 `/api/surveillance`

**Controller**: `surveillance/SurveillanceController` | **Tag**: Surveillance - 安防设备 REST API：ONVIF 设备全生命周期管理、RTSP 流、PTZ 控制、事件订阅

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/devices` | 注册安防设备 | DeviceRegister | 200 Device / 400 |
| GET | `/devices` | 列出所有安防设备 | - | 200 {count,devices} |
| GET | `/devices/{id}` | 获取设备详情 | - | 200 Device / 404 |
| DELETE | `/devices/{id}` | 注销设备 | - | 200 {status,id} / 404 |
| GET | `/devices/{id}/stream` | 获取 RTSP 流 URL | - | 200 {deviceId,channel,rtspUrl} / 400 / 404 / 502 |
| POST | `/devices/{id}/ptz` | PTZ 控制 | {cmd} | 200 {status,cmd,result} / 400 / 404 / 502 |
| POST | `/discover` | 发现子网内设备 | {subnet} | 200 {subnet,count,devices} / 400 / 502 |
| POST | `/rapid-deploy` | 一键扫描子网并自动注册布控球设备 | {subnet,username?,password?} | 200 {subnet,count,results} / 400 / 502 |
| POST | `/scan` | 仅扫描子网发现设备（不注册） | {subnet} | 200 {subnet,count,results} / 400 / 502 |
| GET | `/devices/{id}/events` | 订阅设备事件（SSE，每 15 秒心跳） | - | 200 text/event-stream / 404 |
| GET | `/events` | 查询全局安防事件列表（分页） | - | 200 {items,total,page,size} / 400 |

#### 端点详情

**POST /api/surveillance/devices**
- 请求体: `{id:String, name:String, vendor:"HIKVISION"|"DAHUA"|"UNIVIEW", ip:String, port:int, username:String, password:String}`
- 响应: 200 - 设备详情（含能力信息）；400 - 参数错误

**GET /api/surveillance/devices/{id}/stream**
- 路径参数: `id` (String) - 设备 ID
- 查询参数: `channel` (int, 默认 1) - 通道号，必须 >= 1
- 响应: 200 - `{deviceId, channel, rtspUrl}`（URL 中凭据脱敏）；404 - 设备不存在；502 - 获取流 URL 失败

**POST /api/surveillance/devices/{id}/ptz**
- 路径参数: `id` (String) - 设备 ID
- 请求体: `{cmd:"up"|"down"|"left"|"right"|"zoomIn"|"zoomOut"|"stop"}`
- 响应: 200 - `{status:"ok", cmd, result}`；400 - cmd 非法；404 - 设备不存在；502 - PTZ 控制失败

**POST /api/surveillance/discover**
- 请求体: `{subnet:String}`（CIDR 格式，如 "192.168.1.0/24"）
- 响应: 200 - `{subnet, count, devices:[Device]}`；400 - subnet 缺失；502 - 发现失败

**POST /api/surveillance/rapid-deploy**
- 请求体: `{subnet:String, username:String?, password:String?}`（默认 admin/admin123）
- 响应: 200 - `{subnet, count, results:[{deviceId,ip,vendor,status,message,rtspUrl}]}`

**GET /api/surveillance/devices/{id}/events** （SSE）
- 路径参数: `id` (String) - 设备 ID
- 响应: 200 - `text/event-stream`，事件名 `surveillance-event`，每 15 秒心跳注释；404 - 设备不存在

**GET /api/surveillance/events**
- 查询参数: `page` (int, 默认 0), `size` (int, 默认 20, 范围 [1,1000]), `deviceId` (String, 可选)
- 响应: 200 - `{items, total, page, size, deviceId?}`

**curl 示例**:
```bash
# 注册安防设备
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"id":"cam-001","name":"前门摄像头","vendor":"HIKVISION","ip":"192.168.1.100","port":80,"username":"admin","password":"admin123"}' \
  http://localhost:8080/api/surveillance/devices

# PTZ 控制
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"cmd":"left"}' http://localhost:8080/api/surveillance/devices/cam-001/ptz
```

---

## 报警联动

### 基础路径 `/api/alarms`

**Controller**: `alarm/AlarmController` | **Tag**: Alarm - 报警联动 REST API：报警事件接收/查询/确认、联动规则 CRUD、SSE 实时推送、一键应急响应

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/events` | 接收报警事件（存储+匹配规则+执行联动） | AlarmEvent | 200 {eventId,matchedCount,executions} |
| GET | `/events` | 查询报警事件列表（分页/筛选） | - | 200 {items,total,page,size} |
| GET | `/events/{id}` | 获取报警事件详情 | - | 200 Event / 404 |
| POST | `/events/{id}/ack` | 确认报警（需 OPERATOR） | - | 200 {eventId,acknowledged} / 404 |
| POST | `/events/ack-batch` | 批量确认报警（需 OPERATOR） | {eventIds} | 200 {totalRequested,successCount,failedIds} / 400 |
| POST | `/events/{id}/respond` | 一键应急响应（需 OPERATOR） | - | 200 {commandId,eventId,status,message} / 404 |
| GET | `/stream` | 报警事件 SSE 实时推送（每 2 秒轮询，每 15 秒心跳） | - | 200 text/event-stream |
| GET | `/linkage-logs` | 查询联动执行日志 | - | 200 {items,total,limit} |
| GET | `/rules` | 列出联动规则 | - | 200 {items,total} |
| POST | `/rules` | 创建联动规则（需 OPERATOR） | Rule | 200 {ruleId,created} / 400 |
| PUT | `/rules/{id}` | 更新联动规则（需 OPERATOR） | Rule | 200 {ruleId,updated} / 404 / 400 |
| DELETE | `/rules/{id}` | 删除联动规则（需 OPERATOR） | - | 200 {ruleId,deleted} / 404 |
| POST | `/rules/{id}/test` | 测试联动规则（模拟触发，需 OPERATOR） | {lat?,lon?,deviceId?} | 200 {ruleId,testEventId,matched,matchedCount,executions} / 404 |

#### 端点详情

**POST /api/alarms/events**
- 请求体: `{id?:String, sourceDeviceId:String, sourceDeviceName:String, eventType:"MOTION"|"INTRUSION"|"FIRE"|"DOOR"|"CUSTOM", severity:"INFO"|"WARN"|"CRITICAL", description:String, lat:double, lon:double, alt:double, timestampMs?:long}`
- 响应: 200 - `{eventId, matchedCount:int, executions:[{ruleId,actionType,status,planId,taskTemplate?,error?}], timestamp}`

**GET /api/alarms/events**
- 查询参数: `page` (int, 默认 0), `size` (int, 默认 20), `severity` (String, 可选, INFO/WARN/CRITICAL), `type` (String, 可选, MOTION/INTRUSION/FIRE/DOOR/CUSTOM)
- 响应: 200 - `{items:[Event], total, page, size}`

**POST /api/alarms/events/ack-batch** （需 OPERATOR 角色）
- 请求体: `{eventIds:[String]}`
- 响应: 200 - `{totalRequested, successCount, failedIds:[String], timestamp}`；400 - eventIds 缺失或为空

**POST /api/alarms/events/{id}/respond** （需 OPERATOR 角色）
- 路径参数: `id` (String) - 报警事件 ID
- 响应: 200 - `{commandId, eventId, status:"EXECUTING"|"FAILED", message, timestamp}`；404 - 报警事件不存在

**GET /api/alarms/stream** （SSE）
- 响应: 200 - `text/event-stream`，事件名 `alarm-event`，每 2 秒轮询新事件推送，每 15 秒心跳

**GET /api/alarms/linkage-logs**
- 查询参数: `limit` (int, 默认 100) - 最多返回条数
- 响应: 200 - `{items:[{eventId,ruleId,actionType,status,planId,error?,timestampMs}], total, limit}`

**POST /api/alarms/rules** （需 OPERATOR 角色）
- 请求体: `{id?:String, name:String, enabled?:boolean, matchEventType?:String, matchSeverity:"INFO"|"WARN"|"CRITICAL", matchDeviceIds:[String], actionType:"DEPLOY_DRONE"|..., droneCount:int, targetLat?:double, targetLon?:double, targetRadiusM:double, altitudeM:double, taskTemplate?:String}`
- 响应: 200 - `{ruleId, created:true}`；400 - 规则参数非法

**curl 示例**:
```bash
# 接收报警事件
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"sourceDeviceId":"cam-001","sourceDeviceName":"前门摄像头","eventType":"INTRUSION","severity":"CRITICAL","description":"检测到入侵","lat":22.59,"lon":113.93,"alt":0}' \
  http://localhost:8080/api/alarms/events

# SSE 订阅报警事件
curl -N -H "Authorization: Bearer <token>" http://localhost:8080/api/alarms/stream
```

---

## 应急指挥

### 基础路径 `/api/emergency-command`

**Controller**: `mission/EmergencyCommandController` | **Tag**: EmergencyCommand - 应急指挥工作流 REST API：接报→研判→部署→执行→评估→总结全生命周期管理

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `` | 创建指挥命令（接报，需 OPERATOR） | CommandCreate | 200 Command / 400 |
| GET | `` | 列出指挥命令（支持阶段筛选） | - | 200 {commands,total} |
| GET | `/{id}` | 获取命令详情 | - | 200 Command / 404 |
| POST | `/{id}/assess` | 研判（需 OPERATOR） | {assessmentResult,operator} | 200 Command / 400 |
| POST | `/{id}/deploy` | 部署（需 OPERATOR） | {planName,strategy,estimatedDurationMin,communicationRelay,operator} | 200 Command / 400 |
| POST | `/{id}/execute` | 开始执行（需 OPERATOR） | {operator} | 200 Command / 400 |
| POST | `/{id}/evaluate` | 评估（需 OPERATOR） | {evaluationResult,operator} | 200 Command / 400 |
| POST | `/{id}/close` | 总结关闭（需 OPERATOR） | {summary,operator} | 200 Command / 400 |
| POST | `/{id}/one-click` | 一键应急响应（自动走完接报→研判→部署→执行，需 OPERATOR） | - | 200 Command / 400 |
| GET | `/{id}/history` | 获取阶段转移历史 | - | 200 {commandId,currentPhase,history} / 404 |

#### 端点详情

**POST /api/emergency-command** （需 OPERATOR 角色）
- 请求体: `{incidentType:String, severity:"INFO"|"WARN"|"CRITICAL", lat:double, lon:double, alt:double, description:String, reporterName:String, reporterContact:String}`
- 约束: lat ∈ [-90,90]，lon ∈ [-180,180]
- 响应: 200 - 指挥命令详情；400 - 参数非法

**GET /api/emergency-command**
- 查询参数: `phase` (String, 可选) - 按阶段过滤（RECEIVED/ASSESSING/DEPLOYING/EXECUTING/EVALUATING/CLOSED）
- 响应: 200 - `{commands:[Command], total}`

**POST /api/emergency-command/{id}/deploy** （需 OPERATOR 角色）
- 请求体: `{planName:String, strategy:String, estimatedDurationMin:int, communicationRelay:String, operator:String}`
- 约束: estimatedDurationMin > 0
- 响应: 200 - 部署后的命令；400 - 命令不存在或参数非法

### 基础路径 `/api/v1/emergency`

**Controller**: `api/EmergencyOrchController` | 应急任务编排 REST 端点（M9 应急任务编排，FR-30）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/orch/start` | 启动编排计划（自定义参数，需 OPERATOR） | {scenarioType,centerLat,centerLon,radius,droneIds} | 200 {planId,status,timestamp} |
| POST | `/orch/{planId}/abort` | 中止编排计划（需 OPERATOR） | - | 200 {planId,status,timestamp} / 404 |
| GET | `/orch/{planId}` | 查询计划状态 | - | 200 Plan / 404 |
| GET | `/orch/{planId}/progress` | 查询阶段进度 | - | 200 Progress / 404 |
| GET | `/orch/{planId}/coverage` | 查询覆盖信息 | - | 200 Coverage / 404 |
| POST | `/orch/{planId}/replan` | 重规划（需 OPERATOR） | {reason} | 200 {newPlanId,status} / 404 |
| POST | `/orch/{planId}/priority` | 调整任务优先级（需 OPERATOR） | {taskId,priority,reason} | 200 Result / 404 |
| GET | `/orch/{planId}/priority/queue` | 查询优先级队列 | - | 200 Queue / 404 |
| GET | `/scenarios` | 查询场景预设列表 | - | 200 {scenarios} |
| POST | `/scenarios/{type}/start` | 加载预设并启动（需 OPERATOR） | {centerLat,centerLon,radius,droneIds} | 200 {planId,status} / 404 |

#### 端点详情

**POST /api/v1/emergency/orch/start** （需 OPERATOR 角色）
- 请求体: `{scenarioType:int, centerLat:int, centerLon:int, radius:int, droneIds:[int]}`
- 响应: 200 - `{planId:long, status:"RUNNING", timestamp:long}`

**POST /api/v1/emergency/scenarios/{type}/start** （需 OPERATOR 角色）
- 路径参数: `type` (int) - 场景类型
- 请求体: `{centerLat:int, centerLon:int, radius:int, droneIds:[int]}`（缺失参数用预设默认值填充）
- 响应: 200 - `{planId, status:"RUNNING"}`；404 - 场景类型不存在

**curl 示例**:
```bash
# 创建应急指挥命令
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"incidentType":"FIRE","severity":"CRITICAL","lat":22.59,"lon":113.93,"alt":0,"description":"仓库火灾","reporterName":"张三","reporterContact":"13800138000"}' \
  http://localhost:8080/api/emergency-command

# 一键应急响应
curl -X POST -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/emergency-command/cmd-001/one-click

# 启动编排计划
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"scenarioType":3,"centerLat":225900000,"centerLon":1139300000,"radius":1000,"droneIds":[1,2,3]}' \
  http://localhost:8080/api/v1/emergency/orch/start
```

---

## 编队表演

### 基础路径 `/api/v1/formation`

**Controller**: `mission/FormationController` | 编队 REST 端点（FR-04/FR-11/FR-13~FR-17）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `` | 创建编队（FR-14） | FormationCreateRequest | 200 {formationId,assignments,state,leader} |
| GET | `/{id}` | 查询编队状态（FR-13） | - | 200 FormationView / 404 |
| POST | `/{id}/command` | 下发编队命令（FR-15） | CommandRequest | 200 {formationId,results} |
| POST | `/{id}/transition` | 队形变换（FR-04） | TransitionRequest | 200 {formationId,newShape,waypoints} / 400 |
| POST | `/{id}/lights` | 灯光控制（FR-11） | LedControlCommand | 200 {formationId,results} |
| GET | `/{id}/lights` | 查询灯光状态（FR-13） | - | 200 {formationId,on,pattern,...} / 404 |
| DELETE | `/{id}/members/{sysid}` | 单机脱离（FR-17） | - | 200 {formationId,removedSysid,state,members} |
| POST | `/{id}/dissolve` | 解散编队（FR-16） | - | 200 {formationId,results} |

#### 端点详情

**POST /api/v1/formation**
- 请求体: FormationCreateRequest（含编队几何、成员、间距、航向等）
- 响应: 200 - `{formationId, assignments, state, leaderSysid}`

**GET /api/v1/formation/{id}**
- 路径参数: `id` (int) - 编队 ID
- 响应: 200 - `{formationId, state, shape, spacing, heading, leader, version, members:[{sysid,online,targetLat,targetLon,targetAlt}]}`；404 - 编队不存在

**POST /api/v1/formation/{id}/command**
- 请求体: `{type:"TAKEOFF"|"TRANSITION"|"LIGHTS"|"RTL"|"DISSOLVE", alt?:double, newShape?:Shape, steps?:int, ledCommand?:LedControlCommand}`
- 响应: 200 - `{formationId, results:Map<sysid,AckResult>}`

**POST /api/v1/formation/{id}/transition**
- 请求体: `{newShape:Shape, steps:int}`（steps >= 1）
- 响应: 200 - `{formationId, newShape, waypoints:Map<sysid,List<GeoPos>>}`；400 - steps < 1

**POST /api/v1/formation/{id}/lights**
- 请求体: LedControlCommand（含 on, pattern, brightness, freq, sync, colorR/G/B）
- 响应: 200 - `{formationId, results:Map<sysid,AckResult>}`

**DELETE /api/v1/formation/{id}/members/{sysid}**
- 路径参数: `id` (int) - 编队 ID, `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{formationId, removedSysid, state, members}`

**curl 示例**:
```bash
# 查询编队状态
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/formation/1

# 队形变换
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"newShape":"DIAMOND","steps":10}' \
  http://localhost:8080/api/v1/formation/1/transition

# 解散编队
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/formation/1/dissolve
```

---

## 喷洒物流

### 基础路径 `/api/v1/spray`

**Controller**: `mission/SprayController` | 喷洒 REST 端点（FR-32）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `` | 创建喷洒任务（FR-12，需 OPERATOR） | SprayTaskRequest | 200 {taskId,segments,state,totalArea} / 400 |
| GET | `/{id}` | 查询喷洒任务状态（FR-15） | - | 200 {taskId,state,coveredArea,coverageRate,...} / 404 |
| POST | `/{id}/control` | 控制喷洒任务（FR-14，需 OPERATOR） | {action} | 200 {taskId,results} / 409 |

#### 端点详情

**POST /api/v1/spray** （需 OPERATOR 角色）
- 请求体: SprayTaskRequest（含 waypoints，至少 2 个点）
- 响应: 200 - `{taskId, segments, state, totalArea}`；400 - waypoints 少于 2 个

**GET /api/v1/spray/{id}**
- 路径参数: `id` (int) - 任务 ID
- 响应: 200 - `{taskId, state, coveredArea, coverageRate, remainingChemical, currentSegment, segmentCount}`；404 - 任务不存在

**POST /api/v1/spray/{id}/control** （需 OPERATOR 角色）
- 请求体: `{action:"START"|"PAUSE"|"STOP"|"EMERGENCY_STOP"}`
- 响应: 200 - `{taskId, results:Map<sysid,AckResult>}`；409 - 无人机离线

### 基础路径 `/api/v1/delivery`

**Controller**: `mission/DeliveryController` | 配送/物流 REST 端点（FR-33/FR-34）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `` | 创建配送任务（FR-23，需 OPERATOR） | DeliveryRequest | 200 {deliveryId,sites,state} / 400 |
| GET | `/{id}` | 查询配送任务状态（FR-25） | - | 200 DeliveryView / 404 |
| POST | `/{id}/control` | 控制配送任务（推进/跳过站点，需 OPERATOR） | {action} | 200 {deliveryId,results} / 409 |
| GET | `/{id}/payload` | 查询当前负载清单（FR-34） | - | 200 {deliveryId,payloads,totalWeight,totalVolume,combinedCenterOfGravity} / 404 |

#### 端点详情

**POST /api/v1/delivery** （需 OPERATOR 角色）
- 请求体: DeliveryRequest（含 sites 列表，不能为空）
- 响应: 200 - `{deliveryId, sites, state}`；400 - sites 为空

**GET /api/v1/delivery/{id}**
- 路径参数: `id` (int) - 配送 ID
- 响应: 200 - `{deliveryId, state, progress, currentIndex, remainingSites, sites:[{index,lat,lon,alt,payloadId,payloadWeightKg,payloadVolumeL,dropAccuracyM,state,arriveTimeMs,dropTimeMs}]}`；404 - 配送不存在

**POST /api/v1/delivery/{id}/control** （需 OPERATOR 角色）
- 请求体: `{action:"START"|"DROP"|"SKIP"|"RESET"|"FINISH"}`
- 响应: 200 - `{deliveryId, results}`；409 - 无人机离线

**curl 示例**:
```bash
# 控制喷洒任务
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"action":"START"}' http://localhost:8080/api/v1/spray/1/control

# 查询配送负载
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/delivery/1/payload
```

---

## 视觉感知

### 基础路径 `/api/v1/vision`

**Controller**: `api/VisionController` | 智能成像端点：拍照→地理定位→真值评分；环绕→逐照片跟踪；实时跟踪查询

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/drones/{sysid}/capture` | 拍照并地理定位（需 OPERATOR） | - | 200 CaptureResult / 500 |
| POST | `/drones/{sysid}/orbit` | 启动环绕飞行（异步，需 OPERATOR） | {lat,lon,radiusM,altM,photos} | 202 {jobId,state,photosRequested,sysid} / 400 / 409 |
| GET | `/jobs/{jobId}` | 查询环绕任务进度 | - | 200 JobView / 404 |
| GET | `/drones/{sysid}/tracks` | 查询跟踪目标列表 | - | 200 {sysid,tracks} |

#### 端点详情

**POST /api/v1/vision/drones/{sysid}/capture** （需 OPERATOR 角色）
- 路径参数: `sysid` (int) - 无人机系统 ID
- 查询参数: `source` (String, 可选) - 图片源，`pixels` 运行 E2 像素管线
- 响应: 200 - 拍照+地理定位+真值评分结果

**POST /api/v1/vision/drones/{sysid}/orbit** （需 OPERATOR 角色）
- 路径参数: `sysid` (int) - 无人机系统 ID
- 请求体: `{lat:double, lon:double, radiusM:double(默认25), altM:double(默认60), photos:int(默认4)}`
- 响应: 202 - `{jobId, state, photosRequested, sysid}`（异步启动，2 分钟飞行）；400 - 参数错误；409 - 已有在飞任务

**GET /api/v1/vision/jobs/{jobId}**
- 路径参数: `jobId` (String) - 任务 ID
- 响应: 200 - 任务状态+逐站点进度+最终结果；404 - 任务不存在

**GET /api/v1/vision/drones/{sysid}/tracks**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{sysid, tracks:[{trackId, state, hits, kind, lastSeen:{lat,lon}, predicted:{lat,lon}}]}`

### 基础路径 `/api/v1/thermal`

**Controller**: `api/ThermalController` | 热成像 REST 端点（FR-28）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/tasks` | 创建热成像任务（FR-28，需 OPERATOR） | {sysid,region,hotspotThreshold} | 200 TaskView / 400 |
| GET | `/tasks/{taskId}` | 查询任务状态+温度场结果（FR-28） | - | 200 TaskView / 404 |

### 基础路径 `/api/v1/multispectral`

**Controller**: `api/MultispectralController` | 多光谱 REST 端点（FR-27）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/tasks` | 创建多光谱任务（FR-27，需 OPERATOR） | {sysid,bands,region} | 200 TaskView / 400 |
| GET | `/tasks/{taskId}` | 查询任务状态+NDVI 结果（FR-27） | - | 200 TaskView / 404 |

### 基础路径 `/api/v1/obstacle`

**Controller**: `api/ObstacleController` | 避障 REST 端点（FR-25/FR-26/FR-29）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/config` | 配置避障（FR-25，需 ADMIN） | {sysid,safetyDistanceM,emergencyHoverM,mode,enabled,maxSpeedMs} | 200 ConfigView / 400 |
| GET | `/config/{sysid}` | 查询避障配置（FR-25） | - | 200 ConfigView / 404 |
| GET | `/status/{sysid}` | 避障状态查询（FR-26） | - | 200 StatusView |
| POST | `/{sysid}/release` | 紧急悬停解除（FR-29，需 OPERATOR） | - | 200 {sysid,released} |

#### 端点详情

**POST /api/v1/thermal/tasks** （需 OPERATOR 角色）
- 请求体: `{sysid:int, region:Map, hotspotThreshold:double(默认50.0)}`
- 约束: hotspotThreshold ∈ [-273.15, 1000]
- 响应: 200 - 任务详情+温度场结果；400 - sysid 缺失或阈值越界

**POST /api/v1/multispectral/tasks** （需 OPERATOR 角色）
- 请求体: `{sysid:int, bands:[String](默认["NIR","RED"]), region:Map}`
- 响应: 200 - 任务详情+NDVI 结果；400 - sysid 缺失

**POST /api/v1/obstacle/config** （需 ADMIN 角色）
- 请求体: `{sysid:int, safetyDistanceM:double, emergencyHoverM:double, mode:"WAYPOINT_OFFSET"|"SPEED_LIMIT"|"EMERGENCY_HOVER"|"DISABLED", enabled:boolean, maxSpeedMs:double}`
- 响应: 200 - `{sysid, safetyDistanceM, emergencyHoverM, mode, enabled, maxSpeedMs}`

**GET /api/v1/obstacle/status/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{sysid, currentThreat, nearestDistance, nearestDirectionDeg, inEmergencyHover, lastReportTime, lastCommandTime}`

**curl 示例**:
```bash
# 启动环绕飞行
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"lat":22.5912,"lon":113.935,"radiusM":25,"altM":60,"photos":4}' \
  http://localhost:8080/api/v1/vision/drones/1/orbit

# 创建热成像任务
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"sysid":1,"region":{"lat":22.59,"lon":113.93,"radius":100},"hotspotThreshold":60}' \
  http://localhost:8080/api/v1/thermal/tasks

# 紧急悬停解除
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/obstacle/1/release
```

---

## 硬件抽象

### 基础路径 `/api/v1`（HardwareDataController，聚合雷达/动力/LiDAR/IMU 端点）

**Controller**: `api/HardwareDataController` | 硬件数据 REST 端点（M4 硬件抽象，FR-24~FR-28）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/radar/config` | 配置雷达扫描（FR-24，需 ADMIN） | RadarConfig | 200 ConfigView / 400 |
| GET | `/radar/config/{sysid}` | 查询雷达配置（FR-24） | - | 200 ConfigView / 404 |
| GET | `/radar/status/{sysid}` | 查询扫描状态（FR-24） | - | 200 StatusView |
| GET | `/radar/targets/{sysid}` | 查询目标列表（FR-25） | - | 200 {sysid,count,targets} |
| POST | `/rotor/config` | 配置气动参数（FR-26，需 ADMIN） | RotorConfig | 200 ConfigView / 400 |
| GET | `/rotor/telemetry/{sysid}` | 查询气动遥测（FR-26） | - | 200 TelemetryView |
| GET | `/lidar/data/{sysid}` | 查询 LiDAR 数据（FR-27） | - | 200 {sysid,nearestDistance,pointCount,density,avgIntensity} / 404 |
| GET | `/imu/data/{sysid}` | 查询 IMU 数据（FR-28） | - | 200 {sysid,accelX/Y/Z,gyroX/Y/Z,magX/Y/Z,tempC} / 404 |

#### 端点详情

**POST /api/v1/radar/config** （需 ADMIN 角色）
- 请求体: `{sysid:int(1-255), mode:"SECTOR_SCAN"|"FULL_SCAN"|..., azimCenter:double(0-359), azimWidth:double(1-360), elevCenter:double(-90~90), beamWidth:double(1-30), range:double(10-10000), scanPeriodMs:int(100-10000), enabled:boolean}`
- 响应: 200 - 雷达配置详情；400 - 参数越界

**GET /api/v1/radar/targets/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{sysid, count, targets:[{targetId, distance, azimDeg, elevDeg, radialVelocity, rcs, trackState, timestamp}]}`

**POST /api/v1/rotor/config** （需 ADMIN 角色）
- 请求体: `{sysid:int(1-254), rotorCount:int(1-12), diameter:double(>0), pitch:double(0-30), maxRpm:double(0-20000), airDensity:double(>0)}`
- 响应: 200 - `{sysid, rotorCount, diameter, pitch, maxRpm, airDensity}`

**GET /api/v1/rotor/telemetry/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{sysid, rotorIndex, rpm, thrust, power, totalThrust, totalPower, lastUpdateTime}`

**curl 示例**:
```bash
# 配置雷达扫描
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"sysid":1,"mode":"SECTOR_SCAN","azimCenter":0,"azimWidth":90,"elevCenter":0,"beamWidth":5,"range":500,"scanPeriodMs":1000,"enabled":true}' \
  http://localhost:8080/api/v1/radar/config

# 查询 IMU 数据
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/imu/data/1
```

> **注**: `vision/RadarController`、`vision/RotorController`、`vision/ObstacleAvoidanceController` 为 `@Service` 内部组件，不直接暴露 REST 端点，其能力通过 `HardwareDataController` 和 `ObstacleController` 对外提供。

---

## 通信组网

### 基础路径 `/api/v1/mesh`

**Controller**: `api/MeshController` | Mesh 拓扑 REST 端点（M5 应急 mesh，FR-28，全部只读）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/topology` | 获取全网拓扑 | - | 200 {version,nodeCount,nodes} |
| GET | `/topology/{sysid}` | 获取单节点拓扑 | - | 200 NodeView / 404 |
| GET | `/routes/{sysid}` | 获取单节点路由表（暂未实现，返回空） | - | 200 {sysid,routes:[]} / 404 |
| GET | `/neighbors/{sysid}` | 获取单节点邻居表 | - | 200 {sysid,neighborCount,neighbors} / 404 |
| GET | `/links` | 获取所有链路及质量分级（A-B 与 B-A 合并） | - | 200 {linkCount,links} |

### 基础路径 `/api/v1/celltowers`

**Controller**: `api/CellTowerController` | 基站拓扑 REST 端点（M6 移动基站载荷抽象）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 获取全网基站拓扑 | - | 200 {version,towerCount,terminalCount,towers} |
| GET | `/{sysid}` | 获取单基站状态 | - | 200 TowerView / 404 |
| PUT | `/{sysid}/config` | 下发基站配置（FR-CT-05，需 ADMIN） | ConfigRequest | 200 ConfigView / 400 / 502 |
| GET | `/{sysid}/terminals` | 获取基站接入终端列表 | - | 200 {sysid,terminalCount,terminals} |
| POST | `/{sysid}/handover` | 触发漫游切换（FR-HO-03，需 OPERATOR） | HandoverRequest | 200 {terminalId,fromSysid,toSysid,reason,status} / 400 / 502 |
| GET | `/handovers` | 获取漫游切换历史 | - | 200 {count,handovers} |

### 基础路径 `/api/v1/sat-link`

**Controller**: `api/SatLinkController` | 卫星链路 REST 端点（M7 星-空-地多层级中继）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/status` | 获取所有卫星链路状态 | - | 200 {version,satCount,links} |
| GET | `/status/{satId}` | 获取单星链路状态 | - | 200 LinkView / 404 |
| GET | `/passes` | 获取所有过境计划 | - | 200 {passCount,passes} |
| GET | `/passes/{satId}` | 获取单星过境计划 | - | 200 {satId,passCount,passes} / 404 |
| GET | `/routes` | 获取最近路由决策历史 | - | 200 {routeCount,routes} |
| GET | `/strategy` | 获取当前切换策略 | - | 200 {strategy,validStrategies} |
| PUT | `/strategy` | 设置切换策略（运行时热更新，需 ADMIN） | {strategy} | 200 {strategy,message} / 400 |
| GET | `/constellation` | 获取星座配置（FR-4.4.3） | - | 200 {simulated,note,linkCount} |

### 基础路径 `/api/v1/terrain`

**Controller**: `api/TerrainController` | 地形 REST 端点（M8 复杂地形适配，FR-31）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/map` | 获取当前地形分区图 | - | 200 {available,version,mapWidth,...} |
| GET | `/restrictions` | 获取飞行限制区列表 | - | 200 {count,restrictions} |
| GET | `/changes` | 获取地形变更历史（分页） | - | 200 {offset,limit,count,changes} |
| POST | `/build` | 触发地形建图（需 OPERATOR） | BuildRequest | 202 {status,originLat,...} |

#### 端点详情

**PUT /api/v1/celltowers/{sysid}/config** （需 ADMIN 角色）
- 路径参数: `sysid` (int, 1-255) - 基站系统 ID
- 请求体: `{cellType:int(0=LTE,1=WIFI,2=LORA), txPowerDbm:int(-10~30), maxTerminals:int(1-1000), frequencyChannel:int(0-1000)}`
- 响应: 200 - `{sysid, cellType, txPowerDbm, maxTerminals, frequencyChannel, status:"sent"}`

**POST /api/v1/celltowers/{sysid}/handover** （需 OPERATOR 角色）
- 请求体: `{terminalId:int, toSysid:int, reason:int(0=SIGNAL_WEAK,1=LOAD_BALANCE,2=CELL_SHUTDOWN)}`
- 响应: 200 - `{terminalId, fromSysid, toSysid, reason, status:"sent"}`

**PUT /api/v1/sat-link/strategy** （需 ADMIN 角色）
- 请求体: `{strategy:"NEAR_FIRST"|"DELAY_OPTIMAL"|"BANDWIDTH_OPTIMAL"|"RELIABILITY_OPTIMAL"}`
- 响应: 200 - `{strategy, message:"strategy updated successfully"}`；400 - 策略非法

**POST /api/v1/terrain/build** （需 OPERATOR 角色）
- 请求体: `{originLat:double, originLon:double, widthM:double, heightM:double, gridResolution:double}`
- 响应: 202 - `{status:"accepted", originLat, originLon, widthM, heightM, gridResolution, message}`（异步建图）

**curl 示例**:
```bash
# 获取 Mesh 全网拓扑
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/mesh/topology

# 下发基站配置
curl -X PUT -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"cellType":0,"txPowerDbm":20,"maxTerminals":50,"frequencyChannel":100}' \
  http://localhost:8080/api/v1/celltowers/1/config

# 设置卫星切换策略
curl -X PUT -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"strategy":"DELAY_OPTIMAL"}' http://localhost:8080/api/v1/sat-link/strategy
```

---

## 集群调度

### 基础路径 `/api/scheduling`

**Controller**: `scheduling/SchedulingController` | M10 集群调度 REST API

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/tasks` | 创建调度任务（需 OPERATOR） | TaskRequest | 200 AssignmentResult |
| GET | `/tasks` | 列出所有调度任务 | - | 200 Map<taskId,AssignmentResult> |
| DELETE | `/tasks/{id}` | 取消调度任务（需 OPERATOR） | - | 200 {taskId,cancelled} |
| POST | `/conflicts/check` | 检查冲突 | {lat1,lon1,alt1,v1,h1,lat2,lon2,alt2,v2,h2} | 200 ConflictResult / 400 |
| GET | `/status` | 调度状态 | - | 200 {totalTasks,status} |

#### 端点详情

**POST /api/scheduling/tasks** （需 OPERATOR 角色）
- 请求体: TaskRequest（含 taskId, taskType, priority 等）
- 响应: 200 - 任务分配结果

**POST /api/scheduling/conflicts/check**
- 请求体: `{lat1:double, lon1:double, alt1:double, v1:double, h1:double, lat2:double, lon2:double, alt2:double, v2:double, h2:double}`（两架无人机的位置/速度/航向）
- 响应: 200 - 冲突检测结果；400 - 缺少必填字段

### 基础路径 `/api/v1/squad`

**Controller**: `api/SquadController` | Squad/编队端点：角色状态机 + 多目标分派

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/roles` | 当前角色状态 | - | 200 {version,leader,drones} |
| POST | `/assign` | 重计算并分派环绕任务（需 OPERATOR） | - | 200 {assignments,...} |
| POST | `/leader/{sysid}` | 手动设置长机（需 OPERATOR） | - | 200 RolesView |

#### 端点详情

**GET /api/v1/squad/roles**
- 响应: 200 - `{version, leader, drones:[{sysid, role, battery, online}]}`

**POST /api/v1/squad/assign** （需 OPERATOR 角色）
- 响应: 200 - 分派结果（含 assignments 详情）

**POST /api/v1/squad/leader/{sysid}`** （需 OPERATOR 角色）
- 路径参数: `sysid` (int) - 新长机系统 ID
- 响应: 200 - 更新后的角色视图

**curl 示例**:
```bash
# 创建调度任务
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"taskId":"task-001","taskType":"SURVEY","priority":1}' \
  http://localhost:8080/api/scheduling/tasks

# 编队分派
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/squad/assign
```

---

## AI 决策

### 基础路径 `/api/ai`

**Controller**: `ai/DecisionMonitorController` | M11 决策监控 REST API

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/decisions` | 获取所有无人机决策 | - | 200 Map<sysid,List<Decision>> |
| GET | `/decisions/{sysid}` | 获取单机决策列表 | - | 200 List<Decision> |

#### 端点详情

**GET /api/ai/decisions**
- 响应: 200 - 所有无人机决策列表（按 sysid 分组）

**GET /api/ai/decisions/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 指定无人机的决策列表

**curl 示例**:
```bash
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/ai/decisions
```

---

## 边缘计算

### 基础路径 `/api/edge`

**Controller**: `edge/EdgeCoordinationController` | M12 边缘协同 REST API

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/results` | 提交边缘计算结果 | {sysid,taskId,type,...} | 200 {status:"OK"} |
| GET | `/tasks` | 获取所有边缘任务 | - | 200 Map<sysid,List<Result>> |
| GET | `/fusion/{sysid}` | 获取融合数据 | - | 200 List<Result> |

#### 端点详情

**POST /api/edge/results**
- 请求体: `{sysid:int, taskId:String, type:String, ...}`（含任务结果数据）
- 响应: 200 - `{status:"OK"}`

**GET /api/edge/fusion/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 指定无人机的融合数据列表

**curl 示例**:
```bash
# 提交边缘计算结果
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"sysid":1,"taskId":"task-001","type":"DETECTION","result":"person"}' \
  http://localhost:8080/api/edge/results
```

---

## 数字孪生

### 基础路径 `/api/twin`

**Controller**: `twin/TwinController` | M13 数字孪生 REST API

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/state` | 获取所有数字孪生状态 | - | 200 Collection<TwinState> |
| GET | `/state/{sysid}` | 获取单机数字孪生状态 | - | 200 TwinState |
| GET | `/predict/{sysid}` | 轨迹预测 | - | 200 PredictionResult |
| GET | `/compare/{sysid}` | 虚实对比（实测态 vs 模型预测态） | - | 200 ComparisonResult |

#### 端点详情

**GET /api/twin/state**
- 响应: 200 - 所有数字孪生状态集合

**GET /api/twin/state/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 指定无人机的数字孪生状态（含 lat/lon/alt/heading/velocity/battery/syncTimestamp）

**GET /api/twin/predict/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 查询参数: `horizon` (int, 默认 30) - 预测时间范围（秒）
- 响应: 200 - 预测结果（含轨迹点列表）

**GET /api/twin/compare/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - 虚实对比结果（含位置/速度等偏差指标）

**curl 示例**:
```bash
# 轨迹预测（60 秒）
curl -H "Authorization: Bearer <token>" "http://localhost:8080/api/twin/predict/1?horizon=60"

# 虚实对比
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/twin/compare/1
```

---

## 环境气象

### 基础路径 `/api/v1/env-alerts`

**Controller**: `telemetry/EnvAlertController` | 环境告警查询 API（M0b，FR-23 后端接入）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 查询环境告警历史（含环境告警 + STATUSTEXT 映射告警，按时间倒序） | - | 200 List<AlertEntry> |

#### 端点详情

**GET /api/v1/env-alerts**
- 查询参数: `day` (String, 可选, YYYY-MM-DD, 默认今天), `sysid` (Integer, 可选, 过滤特定无人机), `limit` (int, 默认 100, 上限 5000)
- 响应: 200 - 告警列表，每条含 `{t, type, sysid, severity, text}`

**curl 示例**:
```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/env-alerts?day=2026-09-20&sysid=1&limit=100"
```

---

## 安全认证

### 基础路径 `/api/auth`

**Controller**: `security/AuthController` | 认证端点：登录与令牌刷新

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/login` | 用户登录，返回 JWT 令牌 | {username,password} | 200 {token,expiresIn,username} / 400 / 401 / 429 |
| POST | `/refresh` | 刷新令牌 | - | 200 {token,expiresIn} / 401 |

#### 端点详情

**POST /api/auth/login**
- 请求体: `{username:String, password:String}`
- 频率限制: 每 IP 每分钟最多 10 次尝试，超限返回 429
- 响应: 200 - `{token:String, expiresIn:long, username:String}`；400 - 缺少用户名/密码；401 - 凭据无效；429 - 频率超限

**POST /api/auth/refresh**
- 请求头: `Authorization: Bearer <token>`
- 响应: 200 - `{token:String, expiresIn:long}`；401 - 令牌缺失/无效/过期

**curl 示例**:
```bash
# 登录
curl -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' \
  http://localhost:8080/api/auth/login

# 刷新令牌
curl -X POST -H "Authorization: Bearer <old-token>" \
  http://localhost:8080/api/auth/refresh
```

---

## 审计日志

### 基础路径 `/api/audit`

**Controller**: `audit/AuditController` | 审计日志查询端点

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/logs` | 查询审计日志（需 ADMIN） | - | 200 List<{timestamp,userId,action,target,ip}> |

#### 端点详情

**GET /api/audit/logs** （需 ADMIN 角色）
- 查询参数: `limit` (Integer, 可选) - 最大返回条数，默认全部
- 响应: 200 - 审计日志列表，每条含 `{timestamp, userId, action, target, ip}`

**curl 示例**:
```bash
curl -H "Authorization: Bearer <token>" "http://localhost:8080/api/audit/logs?limit=100"
```

---

## 许可证

### 基础路径 `/api/license`

**Controller**: `license/LicenseController` | License 管理 REST 端点

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/info` | 查询当前 License 信息 | - | 200 {tenantId,productName,maxDevices,...} |
| POST | `/activate` | 激活 License | {activationCode,tenantId,machineId} | 200 {success,message} |
| GET | `/verify` | 验证 License 有效性 | - | 200 {valid,expired,active,devEdition} |

#### 端点详情

**GET /api/license/info**
- 响应: 200 - `{tenantId, productName, maxDevices, expiryDate, issuedAt, issuedTo, active, expired, devEdition}`

**POST /api/license/activate**
- 请求体: `{activationCode:String, tenantId:String, machineId:String}`
- 响应: 200 - `{success:boolean, message:String}`

**GET /api/license/verify**
- 响应: 200 - `{valid:boolean, expired:boolean, active:boolean, devEdition:boolean}`

**curl 示例**:
```bash
# 查询 License 信息
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/license/info

# 激活 License
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"activationCode":"XXXX-XXXX-XXXX","tenantId":"tenant-001","machineId":"mac-001"}' \
  http://localhost:8080/api/license/activate
```

---

## 自动出警

### 基础路径 `/api/v1/autodispatch`

**Controller**: `autodispatch/AutoDispatchController` | **Tag**: AutoDispatch - 自动出警 REST API：报警触发无人机自动派遣、出警历史/活跃任务查询、配置管理

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/trigger` | 手动触发自动出警 | {lat,lon,alarmId?,droneCount} | 200 {dispatchId,status,dispatchedDrones,message,timestamp} |
| GET | `/history` | 查询出警历史 | - | 200 {items,total} |
| GET | `/active` | 查询进行中的出警任务 | - | 200 {items,total} |
| POST | `/{dispatchId}/abort` | 中止出警任务 | - | 200 {dispatchId,status,abortTime} / 404 |
| GET | `/config` | 获取自动出警配置 | - | 200 {enabled,minBatteryPct,maxDispatchDistanceM,defaultDroneCount,hoverAltitudeM,hoverDurationSec} |
| PUT | `/config` | 更新自动出警配置（字段级合并） | {enabled?,minBatteryPct?,maxDispatchDistanceM?,defaultDroneCount?,hoverAltitudeM?,hoverDurationSec?} | 200 Config |

#### 端点详情

**POST /api/v1/autodispatch/trigger**
- 请求体: `{lat:double, lon:double, alarmId:String?, droneCount:int}`（alarmId 缺失时自动生成 `manual-<timestamp>`）
- 响应: 200 - `{dispatchId, status:"DISPATCHED"|"FAILED", dispatchedDrones:[{sysid,taskAssigned,estimatedArrivalSec}], message, timestamp}`；400 - 请求体格式错误

**GET /api/v1/autodispatch/history**
- 查询参数: `limit` (int, 默认 100) - 最多返回条数
- 响应: 200 - `{items:[{dispatchId,alarmId,triggerTime,lat,lon,status,dispatchedDrones,abortTime,completeTime}], total}`

**POST /api/v1/autodispatch/{dispatchId}/abort**
- 路径参数: `dispatchId` (String) - 派遣 ID
- 响应: 200 - `{dispatchId, status, abortTime}`；404 - 出警任务不存在

**PUT /api/v1/autodispatch/config**
- 请求体: `{enabled?:boolean, minBatteryPct?:int, maxDispatchDistanceM?:int, defaultDroneCount?:int, hoverAltitudeM?:int, hoverDurationSec?:int}`（未提供的字段保留原值）
- 响应: 200 - 更新后的完整配置

**curl 示例**:
```bash
# 手动触发自动出警
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"lat":39.9,"lon":116.3,"alarmId":"alarm-001","droneCount":2}' \
  http://localhost:8080/api/v1/autodispatch/trigger

# 查询进行中的出警任务
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/autodispatch/active
```

### 基础路径 `/api/v1/voice-intercom`

**Controller**: `autodispatch/VoiceIntercomController` | **Tag**: VoiceIntercom - 语音对讲 REST API：双向对讲启停、状态查询、广播喊话

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/{sysid}/start` | 启动双向语音对讲 | - | 200 {sysid,status,startTimeMs,stopTimeMs} / 404 |
| POST | `/{sysid}/stop` | 停止语音对讲 | - | 200 {sysid,status,startTimeMs,stopTimeMs} |
| GET | `/{sysid}/status` | 语音对讲状态查询 | - | 200 {sysid,status,startTimeMs,stopTimeMs} |
| POST | `/{sysid}/broadcast` | 广播喊话（文本转语音） | {text,volume?} | 200 {sysid,status,message,volume,timestamp} / 400 |

#### 端点详情

**POST /api/v1/voice-intercom/{sysid}/start**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{sysid, status:"ACTIVE"|"INACTIVE", startTimeMs, stopTimeMs}`；404 - 无人机未注册

ECHO `POST /api/v1/voice-intercom/{sysid}/broadcast`
- 请求体: `{text:String, volume:int?}`（volume 默认 0，使用设备默认值）
- 响应: 200 - `{sysid, status:"BROADCASTING"|"FAILED", message, volume, timestamp}`；400 - 文本为空或过长

### 基础路径 `/api/v1/video-stream`

**Controller**: `autodispatch/VideoStreamController` | **Tag**: VideoStream - 视频流回传 REST API：无人机 RTSP 流 URL、启停控制、状态查询

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/{sysid}/url` | 获取无人机视频流 URL（RTSP） | - | 200 {sysid,url,protocol:"RTSP"} |
| POST | `/{sysid}/start` | 启动视频流推送 | - | 200 {sysid,url,status,startTimeMs,stopTimeMs} / 404 |
|7 POST | `/{sysid}/stop` | 停止视频流 | - | 200 {sysid,url,status,startTimeMs,stopTimeMs} |
| GET | `/{sysid}/status` | 视频流状态查询 | - | 200 {sysid,url,status,startTimeMs,stopTimeMs} |
| GET | `/active` | 查询所有活跃视频流 | - | 200 {items,total} |

**curl 示例**:
```bash
# 获取视频流 URL
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/video-stream/1/url

# 启动视频流推送
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/video-stream/1/start
```

---

## 语音指挥

### 基础路径 `/api/v1/voice-cmd`

**Controller**: `voicecmd/VoiceCommandController` | **Tag**: Voice Command - 语音/自然语言指挥 REST API：语音解析、指令执行、语音播报

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/parse` | 解析语音文本为结构化指令 | {text} | 200 ParsedCommand / 400 |
| POST | `/execute` | 执行解析后的指令 | ParsedCommand | 200 ExecutionResult / 404 |
| POST | `/confirm/{pendingId}` | 确认待确认的高优先级指令 | - | 200 ExecutionResult / 404 |
| POST | `/broadcast/{sysid}` | 发送语音播报到操作员 | {text} | 200 BroadcastResult / 404 |
| GET | `/broadcast/{sysid}/status` | 获取状态播报文本 | - | 200 {text} / 404 |
| GET | `/broadcast/{sysid}/alert` | 获取告警播报文本 | - | 200 {text,alertType} / 404 |
| GET | `/history` | 查询指令历史 | - | 200 List<ExecutionResult> |
| GET | `/pending` | 查询待确认指令 | - | 200 List<{pendingId,command}> |

#### 端点详情

**POST /api/v1/voice-cmd/parse**
- 请求体: `{text:String}`（非空）
- �,响应: 200 - ParsedCommand（含 action、sysid、params 等）；400 - 缺少 text 字段

**POST /api/v1/voice-cmd/execute**
- 请求体: ParsedCommand（从 /parse 获取的结构化指令）
- 响应: 200 - ExecutionResult（含 status、message）；404 - 无人机未注册
- 注: 高优先级指令（如 arm、takeoff）需二次确认，返回 status=PENDING

**POST /api/v1/voice-cmd/confirm/{pendingId}**
- 路径参数: `pendingId` (String) - 待确认指令 ID
- 响应: 200 - 确认后的执行结果；404 - 待确认指令不存在

**curl 示例**:
```bash
# 解析语音文本
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"text":"1号机起飞"}' http://localhost:8080/api/v1/voice-cmd/parse

# 发送语音播报
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"text":"请立即离开限制区域"}' http://localhost:8080/api/v1/voice-cmd/broadcast/1
```

---

## 空地协同

### 基础路径 `/api/v1/air-ground`

**Controller**: `mission/emergency/AirGroundCoordinationController` | **Tag**: AirGroundCoordination - 空地协同指挥 REST API：态势融合、报警触发侦察、PTZ 联动追踪、六阶段指挥流程

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/situation` | 获取空地协同态势融合视图（需 OBSERVER） | - | 200 SituationView |
| POST | `/recon` | 从安防告警触发无人机自动侦察（需 OPERATOR） | AlarmEvent | 200 {planId,status,eventId,timestamp} / 400 / 503 |
| POST | `/ptz-track` | 无人机目标触发安防 PTZ 联动追踪（需 OPERATOR） | {lat,lon,alt,targetType,confidence,sourceSysid} | 200 {result,targetType,confidence,sourceSysid,timestamp} / 400 |
| POST | `/start` | 启动空地协同指挥六阶段流程（需 OPERATOR） | {alarmEventId} | 200 {status,coordinationId,cmdId,planId,phase} / 400 / 404 |
| GET | `/evaluate/{coordinationId}` | 执行评估阶段并获取评估结果（需 OBSERVER） | - | 200 EvaluationResult / 404 |
| GET | `/report/{coordinationId}` | 执行总结阶段并获取指挥报告（需 OBSERVER） | - | 200 CoordinationReport / 404 |

#### 端点详情

**POST /api/v1/air-ground/recon** （需 OPERATOR 角色）
- 请求体: `{eventType:"MOTION"|"INTRUSION"|"FIRE"|"DOOR"|"CUSTOM", severity:"INFO"|"WARN"|"CRITICAL", sourceDeviceId:String, sourceDeviceName:String, description:String, lat:double, lon:double, alt:double, timestampMs?:long}`
- 响应: 200 - `{planId:long, status:"RUNNING"|"FAILED", eventId, timestamp}`；400 - 参数非法；503 - 编排服务不可用

**POST /api/v1/air-ground/ptz-track** （需 OPERATOR 角色）
- 请求体: `{lat:double, lon:double, alt:double, targetType:"PERSON"|"VEHICLE"|"STRUCTURE"|"FIRE_SOURCE"|"UNKNOWN", confidence:double(0.0~1.0), sourceSysid:int}`
- 响应: 200 - `{result:"TRACKING"|"NO_DEVICE"|"LOW_CONFIDENCE"|"FAILED", targetType, confidence, sourceSysid, timestamp}`

**POST /api/v1/air-ground/start** （需 OPERATOR 角色）
- 请求体: `{alarmEventId:String}`
- 响应: 200 - `{status:"RUNNING", coordinationId, cmdId, planId, phase, alarmEventId, timestamp}`；404 - 报警事件不存在

**GET /api/v1/air-ground/evaluate/{coordinationId}** （需 OBSERVER 角色）
- 响应: 200 - `{coordinationId, coverageRate, deviceCoverageRate, connectivityRate, timing:{receiveToAssessSec,assessToDeploySec,deployToExecuteSec,totalResponseSec}, consumption:{droneCount,deviceCount,avgBatteryPct,meshNodes}}`

**curl 示例**:
```bash
# 触发无人机侦察
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"eventType":"FIRE","severity":"CRITICAL","sourceDeviceId":"cam-001","description":"火灾报警","lat":30.5,"lon":!114.3,"alt":0}' \
  http://localhost:8080/api/v1/air-ground/recon

# 启动空地协同指挥流程
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"alarmEventId":"evt-uuid-001"}' http://localhost:8080/api/v1/air-ground/start
```

---

## 通信自适应

### 基础路径 `/api/v1/comm-adapt`

**Controller**: `commadapt/CommSituationController` | **Tag**: CommAdapt - 多模态通信自适应 REST API：通信质量监控、链路切换建议、故障切换管理、拓扑可视化

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/quality/{sysid}` | 获取单机通信质量评分 | - | 200 {sysid,overallScore,grade,bestLinkType,details} / 404 |
| GET | `/quality/fleet` | 获取机队通信质量总览 | - | 200 {items,total} |
| GET | `/recommendations` | 获取链路切换建议 | - | 200 {items,total} |
| POST | `/switch/{sysid}` | 手动切换链路 | {targetLink} | 200 {sysid,fromLink,toLink,status,timestamp,message} / 400 / 404 |
| GET | `/failover/history/{sysid}` | 获取故障切换历史 | - | 200 {items,total} / 404 |
| GET | `/topology` | 获取通信拓扑（三种链路状态） | - | 200 {MESH,SATELLITE,CELLULAR,failedDrones,failedCount} |
| GET | `/config` | 获取自适应配置 | - | 200 {switchThreshold,failoverThreshold,detectionIntervalMs,autoSwitchEnabled,minStableTimeMs} |
| PUT | `/config` | 更新自适应配置（字段级合并） | {switchThreshold?,failoverThreshold?,detectionIntervalMs?,autoSwitchEnabled?,minStableTimeMs?} | 200 Config |

#### 端点详情

**GET /api/v1/comm-adapt/quality/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - `{sysid, overallScore:int, grade:"EXCELLENT"|"GOOD"|"FAIR"|"POOR"|"CRITICAL", bestLinkType:"MESH"|"SATELLITE"|"CELLULAR", details:[{linkType,latencyMs,bandwidthKbps,rssiDbm,packetLossPct,jitterMs,timestamp,sysid,score}]}`；404 - 无人机未注册或无质量数据

**POST /api/v1/comm-adapt/switch/{sysid}**
- 请求体: `{targetLink:"MESH"|"SATELLITE"|"CELLULAR"}`
- 响应: 200 - `{sysid, fromLink, toLink, status:"SUCCESS"|"FAILED", timestamp, message}`；400 - targetLink 缺失或非法；404 - 无人机未注册

**curl 示例**:
```bash
# 获取单机通信质量
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/comm-adapt/quality/1

# 手动切换链路到卫星
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"targetLink":"SATELLITE"}' http://localhost:8080/api/v1/comm-adapt/switch/1
```

---

## 灾害通信

### 基础路径 `/api/v1/disaster`

**Controller**: `api/controller/DisasterCommController` | 灾害通信监控 REST API（P2 灾害应急通讯组网扩展）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/status` | 获取灾害模式状态 | - | 200 {mode,triggerReason,timestamp,...} |
| POST | `/activate` | 手动激活灾害模式 | - | 200 {mode,triggerReason,timestamp,message} |
| POST | `/deactivate` | 手动退出灾害模式 | - | 200 {mode,triggerReason,timestamp,message} |
| GET | `/clusters` | 获取分簇拓扑 | - | 200 {clusters,...} |
| GET | `/qos` | 获取 QoS 优先级队列状态 | - | 200 {qos,...} |
| GET | `/links` | 获取异构链路桥接状态 | - | 200 {links,...} |

**curl 示例**:
```bash
# 获取灾害模式状态
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/disaster/status

# 手动激活灾害模式
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/disaster/activate
```

---

## 健康管理

### 基础路径 `/api/v1/health`

**Controller**: `health/HealthController` | **Tag**: Health - 无人机健康管理 REST API：健康评分查询、机队总览、历史趋势、部件详情、告警

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/{sysid}` | 获取单机健康评分 | - | 200 HealthScore / 404 |
| GET | `/fleet` | 获取机队健康总览 | - | 200 List<HealthScore> |
| GET | `/{sysid}/history` | 获取健康评分历史（按时间升序） | - | 200 List<HealthScore> / 404 |
| GET | `/{sysid}/components/{component}` | 获取单部件详情 | - | 200 ComponentScore / 404 |
| GET | `/warnings` | 获取所有健康告警（WARNING/CRITICAL） | - | 200 List<ComponentScore> |

#### 端点详情

**GET /api/v1/health/{sysid}**
- 路径参数: `sysid` (int) - 无人机系统 ID
- 响应: 200 - HealthScore（含总体分数、等级、各部件评分）；404 - 无人机未注册或尚无评分

**GET /api/v1/health/{sysid}/components/{component}**
- 路径参数: `sysid` (int), `component` (String) - 部件类型：BATTERY/MOTOR/VIBRATION/TEMPERATURE/COMMUNICATION/IMU/GPS
- 响应: 200 - ComponentScore（含评分、状态、原始指标、维护建议）；404 - 无人机未注册或部件无评分

### 基础路径 `/api/v1/maintenance`

**Controller**: `health/MaintenanceController` | **Tag**: Maintenance - 维护管理 REST API：维护记录 CRUD、预测性维护建议、维护计划

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/records` | 查询维护记录（支持 sysid/status 筛选） | - | 200 List<MaintenanceRecord> |
| POST | `/records` | 创建维护记录 | MaintenanceRecord | 200 MaintenanceRecord / 400 |
| PUT | `/records/{id}` | 更新维护记录（部分更新） | MaintenanceRecord | 200 MaintenanceRecord / 404 |
| GET | `/predictions` | 获取所有预测性维护建议 | - | 200 List<MaintenancePrediction> |
| GET | `/predictions/{sysid}` | 获取单机预测性维护建议 | - | 200 List<MaintenancePrediction> |
| GET | `/schedule` | 获取维护计划（SCHEDULED+IN_PROGRESS，按时间升序） | - | 200 List<MaintenanceRecord> |

#### 端点详情

**POST /api/v1/maintenance/records**
- 请求体: `{sysid:int(>0), componentType:String, maintenanceType:String, status?:"SCHEDULED"|"IN_PROGRESS"|"COMPLETED"|"CANCELLED", scheduledDate?:LocalDate, technician?:String, notes?:String, cost?:double}`
- 响应: 200 - 创建后的维护记录（id 自动生成）；400 - sysid/componentType/maintenanceType 缺失

**curl 示例**:
```bash
# 获取单机健康评分
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/health/1

# 创建维护记录
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"sysid":1,"componentType":"MOTOR","maintenanceType":"REPLACE","status":"SCHEDULED"}' \
  http://localhost:8080/api/v1/maintenance/records
```

---

## 智能巡检

### 基础路径 `/api/v1/inspection`

**Controller**: `inspection/InspectionController` | **Tag**: Inspection - 无人机集群智能巡检 REST API：任务管理、航线规划、异常检测、报告生成

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/tasks` | 创建巡检任务（基于模板，自动规划航线） | {templateId,sysid,startLat,startLon,area?} | 200 TaskSummary / 400 |
| GET | `/tasks` | 列出巡检任务（支持状态筛选） | - | 200 List<TaskSummary> |
| GET | `/tasks/{id}` | 获取任务详情（含航点列表） | - | 200 TaskDetail / 404 |
| POST | `/tasks/{id}/start` | 启动巡检任务 | - | 200 TaskSummary |
| POST | `/tasks/{id}/abort` | 中止巡检任务 | - | 200 TaskSummary |
| GET | `/tasks/{id}/progress` | 查询任务进度 | - | 200 ProgressMap |
| GET | `/templates` | 列出巡检模板预设 | - | 200 List<TemplateSummary> |

#### 端点详情

**POST /api/v1/inspection/tasks**
- 请求体: `{templateId:String, sysid:int, startLat:double, startLon:double, area?:{type:"polygon"|"circle", points?:[{lat,lon}], centerLat?, centerLon?, radiusM?}}`
- 响应: 200 - `{id, templateId, status:"PENDING", assignedSysid, progressPct, waypointCount, photosCaptured, anomaliesFound}`；400 - 模板不存在或参数非法

### 基础路径 `/api/v1/inspection/reports`

**Controller**: `inspection/InspectionReportController` | **Tag**: InspectionReport - 巡检报告 REST API：报告查询、异常清单、照片列表、导出

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/{taskId}` | 获取巡检报告 | - | 200 ReportView / 404 |
| GET | `/{taskId}/anomalies` | 获取异常清单 | - | 200 List<AnomalyView> |
| GET | `/{taskId}/photos` | 获取照片列表（带 GPS 标注） | - | 200 List<PhotoView> |
| POST | `/{taskId}/export` | 导出报告（JSON/CSV） | {format} | 200 String (JSON/CSV) |

**curl 示例**:
```bash
# 创建巡检任务
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"templateId":"power-line","sysid":1,"startLat":22.59,"startLon":113.93}' \
  http://localhost:8080/api/v1/inspection/tasks

# 导出巡检报告（CSV）
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"format":"csv"}' http://localhost:8080/api/v1/inspection/reports/task-001/export
```

---

## 航拍测绘

### 基础路径 `/api/v1/mapping`

**Controller**: `mapping/MappingController` | **Tag**: Mapping - 无人机航拍测绘 REST API：灾害区域快速测绘，生成正射影像/三维模型/DEM

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/tasks` | 创建测绘任务（自动规划航线） | CreateTaskRequest | 200 {id,name,type,status,assignedSysid,altitudeM,overlapPct,sidelapPct,gsdCm,waypointCount} / 400 |
| GET | `/tasks` | 列出测绘任务（可选状态筛选） | - | 200 List<TaskSummary> |
| GET | `/tasks/{id}` | 获取任务详情（含区域和航点） | - | 200 TaskDetail / 404 |
| POST | `/tasks/{id}/start` | 启动测绘任务 | - | 200 {id,status,startTime} / 404 / 400 |
| POST | `/tasks/{id}/abort` | 中止测绘任务 | - | 200 {id,status,endTime} / 404 / 400 |
| GET | `/tasks/{id}/waypoints` | 获取航线规划航点列表 | - | 200 List<MappingWaypoint> / 404 |
| GET | `/tasks/{id}/photos` | 获取任务采集的照片列表 | - | 200 List<CapturedPhoto> / 404 |
| GET | `/tasks/{id}/result` | 获取任务的测绘成果 | - | 200 List<MappingResult> / 404 |
| POST | `/tasks/{id}/process` | 触发测绘成果生成 | - | 200 {id,status,photosProcessed,resultsCount} / 404 / 400 |
| GET | `/results` | 列出所有测绘成果 | - | 200 List<MappingResult> |
| GET | `/results/{id}/download` | 下载测绘成果（返回下载链接） | - | 200 {id,type,status,downloadUrl,fileSizeMB} / 404 |

#### 端点详情

**POST /api/v1/mapping/tasks**
- 请求体: `{name:String, type:"ORTHO_PHOTO"|"DEM"|"THREE_D_MODEL"|"MIXED", sysid?:Integer, altitudeM?:Double(默认100), overlapPct?:Double(默认80), sidelapPct?:Double(默认60), cameraAngleDeg?:Double(默认0), area?:{type:"polygon"|"circle", points?:[{lat,lon}], centerLat?, centerLon?, radiusM?}}`
- 响应: 200 - 任务创建结果（含 gsdCm 地面分辨率、waypointCount 航点数）；400 - name/type 缺失

**POST /api/v1/mapping/tasks/{id}/process**
- 响应: 200 - `{id, status:"COMPLETED", photosProcessed, resultsCount}`；400 - 任务不在 IN_PROGRESS 状态

**curl 示例**:
```bash
# 创建正射影像测绘任务
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"灾区测绘","type":"ORTHO_PHOTO","sysid":1,"altitudeM":80,"area":{"type":"circle","centerLat":22.59,"centerLon":113.93,"radiusM":500}}' \
  http://localhost:8080/api/v1/mapping/tasks

# 触发成果生成
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/mapping/tasks/<id>/process
```

---

0
---

## 编排管理

### 基础路径 `/api/v1/orch`

**Controller**: `orch/OrchestrationController` | 编排计划 REST 端点：创建、查询、生命周期管理和进度查询

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/plans` | 创建编排计划 | CreatePlanRequest | 200 {planId,status:"DRAFT"} |
| GET | `/plans` | 列出所有编排计划 | - | 200 List<OrchestrationPlanEntity> |
| GET | `/plans/{planId}` | 查询指定计划详情 | - | 200 OrchestrationPlanEntity / 404 |
| POST | `/plans/{planId}/start` | 启动计划 | - | 200 {planId,status:"RUNNING"} |
| POST | `/plans/{planId}/pause` | 暂停计划 | - | 200 {planId,status:"PAUSED"} |
| POST | `/plans/{planId}/resume` | 恢复计划 | - | 200 {planId,status:"RUNNING"} |
| POST | `/plans/{planId}/abort` | 中止计划 | - | 200 {planId,status:"ABORTED"} |
| GET | `/plans/{planId}/progress` | 查询步骤进度 | - | 200 List<TaskStepEntity> |

#### 端点详情

**POST /api/v1/orch/plans**
- 请求体: `{name:String, resourcePool:[int], steps:[{stepId, module:"DRONE"|"FORMATION"|"SPRAY"|..., action:"TAKEOFF"|"LAND"|..., params?:String, requiredResources?:String, dependsOn?:String, continueOnFailure?:Boolean, timeoutMs?:Long}], triggers?:[{triggerId, type:"CONDITION"|"TIMER", condition?:String, action:"START"|"ABORT"|"PAUSE"|"RESUME", targetPlanId?:Long}]}`
- 响应: 200 - `{planId:long, status:"DRAFT"}`

**curl 示例**:
```bash
# 创建编排计划
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"应急侦察编排","resourcePool":[1,2,3],"steps":[{"stepId":"s1","module":"DRONE","action":"TAKEOFF"}]}' \
  http://localhost:8080/api/v1/orch/plans

# 启动计划
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/orch/plans/1/start
```

---

## 场景管理

### 基础路径 `/api/v1/scenarios/templates`

**Controller**: `scenario/ScenarioTemplateController` | **Tag**: ScenarioTemplate - 应急救援场景模板管理：CRUD 与按灾害类型筛选

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 列出所有场景模板（预设+自定义） | - | 200 List<ScenarioTemplate> |
| GET | `/{id}` | 获取模板详情 | - | 200 ScenarioTemplate / 404 |
| POST | `` | 创建自定义模板 | ScenarioTemplate | 200 ScenarioTemplate / 400 |
| PUT | `/{id}` | 更新模板 | ScenarioTemplate | 200 ScenarioTemplate / 404 |
| DELETE | `/{id}` | 删除模板（预设不可删除） | - | 200 {deleted,id} / 404 |
| GET | `/by-type/{disasterType}` | 按灾害类型筛选 | - | 200 List<ScenarioTemplate> |

#### 端点详情

**POST /api/v1/scenarios/templates**
- 请求体: ScenarioTemplate（含 name, disasterType:"FIRE"|"FLOOD"|"EARTHQUAKE"|"MUDSLIDE"|"CHEMICAL_LEAK"|"MASS_EVENT", severityLevel, description, droneCount, radiusKm, hoverAltitudeM, durationMin, collaborationStrategy, communicationMode）
- 响应: 200 - 创建后的模板（id 自动生成 `custom-<seq>`，含 createdAt/updatedAt）；400 - name 为空

**GET /api/v1/scenarios/templates/by-type/{disasterType}**
- 路径参数: `disasterType` - FIRE/FLOOD/EARTHQUAKE/MUDSLIDE/CHEMICAL_LEAK/MASS_EVENT

### 基础路径 `/api/v1/scenarios/launch`

**Controller**: `scenario/ScenarioLaunchController` | **Tag**: ScenarioLaunch - 应急救援场景一键启动、状态查询与中止

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/{templateId}` | 一键启动场景 | {lat,lon,overrides?} | 200 LaunchResult / 404 |
| GET | `/active` | 查询进行中的场景 | - | 200 List<LaunchRecord> |
| GET | `/history` | 查询历史启动记录 | - | 200 List<LaunchRecord> |
| POST | `/{launchId}/abort` | 中止场景执行 | - | 200 {launchId,status:"ABORTED"} / 404 |
| GET | `/{launchId}/status` | 查询场景执行状态 | - | 200 LaunchRecord / 404 |

#### 端点详情

**POST /api/v1/scenarios/launch/{templateId}**
- 路径参数: `templateId` (String) - 模板 ID
- 请求体: `{lat:double, lon:double, overrides?:{droneCount?,radiusKm?,hoverAltitudeM?,durationMin?}}`
- 响应: 200 - LaunchResult（含 launchId, planId, templateId, templateName, centerLat, centerLon, assignedDrones, roleAssignments, launchStatus, status, startTime, estimatedCoveragePct, message）；404 - 模板不存在

### 基础路径 `/api/v1/scenarios/drill`

**Controller**: `scenario/ScenarioDrillController` | **Tag**: ScenarioDrill - 应急救援场景演练：模拟执行与评估报告

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/{templateId}` | 启动演练（模拟执行，不实际起飞） | - | 200 DrillResult / 404 |
| GET | `/{drillId}/result` | 获取演练评估报告 | - | 200 DrillResult / 404 |
| GET | `/history` | 演练历史 | - | 200 List<DrillResult> |

#### 端点详情

**POST /api/v1/scenarios/drill/{templateId}**
- 路径参数: `templateId` (String) - 模板 ID
- 响应: 200 - DrillResult（含 drillId, templateId, passed:[], failed:[], score:double, recommendations:[]）；404 - 模板不存在
- 注: 演练以模拟方式执行，检查模板配置合理性（参数校验、协同策略匹配、通信模式适配等）

**curl 示例**:
```bash
# 一键启动场景
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"lat":30.5,"lon":114.3}' http://localhost:8080/api/v1/scenarios/launch/fire-large-001

# 启动演练
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/scenarios/drill/fire-large-001
```

---

## 编队表演-灯光秀

### 基础路径 `/api/v1/show`

**Controller**: `show/ShowController` | **Tag**: Show - 无人机编队表演 REST API：队形管理、表演任务、动作序列、音乐同步

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/formations` | 创建队形定义 | {name,type,droneCount,spacingM,parameters?} | 200 FormationSummary / 400 |
| GET | `/formations` | 列出所有队形定义 | - | 200 List<FormationSummary> |
| GET | `/formations/{id}` | 获取队形详情 | - | 200 FormationSummary / 404 |
| POST | `/formations/{id}/positions` | 计算队形位置（输入 droneCount） | {droneCount} | 200 {formationId,formationType,droneCount,spacingM,positions} / 404 |
| POST | `/tasks` | 创建表演任务 | {name,formationId,droneSysids,durationSec,altitudeM,centerLat,centerLon} | 200 TaskSummary / 400 / 404 |
| GET | `/tasks` | 列出所有表演任务（可选状态筛选） | - | 200 List<TaskSummary> |
| GET | `/tasks/{id}` | 获取任务详情 | - | 200 TaskDetail / 404 |
| POST | `/tasks/{id}/start` | 启动表演任务 | - | 200 TaskSummary / 404 |
| POST | `/tasks/{id}/abort` | 中止表演任务 | - | 200 TaskSummary / 404 |
| GET | `/tasks/{id}/actions` | 获取动作序列（如未生成则自动编排） | - | 200 List<ActionSummary> / 404 |
| POST | `/tasks/{id}/music-sync` | 配置音乐同步（BPM、起始偏移） | {musicUrl,bpm,startTimeOffsetSec} | 200 {taskId,musicUrl,bpm,startTimeOffsetSec,beatDurationSec,syncedBeatTimes} / 400 / 404 |

#### 端点详情

**POST /api/v1/show/formations**
- 请求体: `{name:String, type:"GRID"|"CIRCLE"|"HEART"|"STAR"|"SPIRAL"|"DIAMOND"|"CUSTOM", droneCount:int(1-100), spacingM:double(>0,<=500), parameters?:Map<String,Double>}`
- 响应: 200 - `{id, name, type, droneCount, spacingM, parameters}`；400 - 参数非法

**POST /api/v1/show/tasks**
- 请求体: `{name:String, formationId:String, droneSysids:[int], durationSec:int, altitudeM:double, centerLat:double, centerLon:double}`
- 响应: 200 - `{id, name, formationId, status:"PENDING", droneSysids, durationSec, altitudeM, centerLat, centerLon}`；400 - name/formationId 缺失；404 - 队形不存在

**curl 示例**:
```bash
# 创建队形定义
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"心形","type":"HEART","droneCount":20,"spacingM":2.0}' \
  http://localhost:8080/api/v1/show/formations

# 配置音乐同步
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"musicUrl":"https://example.com/music.mp3","bpm":120,"startTimeOffsetSec":0.5}' \
  http://localhost:8080/api/v1/show/tasks/task-001/music-sync
```

---

## 物流配送2

### 基础路径 `/api/v1/delivery2`

**Controller**: `delivery2/DeliveryController2` | **Tag**: Delivery2 - 无人机物流配送 REST API：应急物资空投、医疗样本运输、偏远地区配送

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/tasks` | 创建配送任务 | DeliveryTask2 | 200 DeliveryTask2 / 400 |
| GET | `/tasks` | 列出配送任务 | - | 200 List<DeliveryTask2> |
| GET | `/tasks/{id}` | 获取任务详情 | - | 200 DeliveryTask2 / 404 |
| POST | `/tasks/{id}/start` | 启动配送 | - | 200 DeliveryTask2 / 400 / 404 |
| POST | `/tasks/{id}/abort` | 中止配送 | - | 200 DeliveryTask2 / 400 / 404 |
| GET | `/tasks/{id}/route` | 获取优化路线 | - | 200 OptimizedRoute / 404 |
| POST | `/tasks/{id}/deliver` | 执行投放 | {method} | 200 {taskId,method,status,landingSite?} / 400 / 404 |
| GET | `/tasks/{id}/status` | 配送状态追踪 | - | 200 DeliveryStatus / 404 |
| POST | `/tasks/{id}/confirm` | 确认签收 | - | 200 {taskId,confirmed,status} / 400 / 404 |
| GET | `/landing-sites` | 搜索降落点 | - | 200 List<LandingSite> |

#### 端点详情

**POST /api/v1/delivery2/tasks**
- 请求体: `{type:"EMERGENCY_SUPPLY"|"MEDICAL_SAMPLE"|"REMOTE_DELIVERY", senderLat:double, senderLon:double, receiverLat:double, receiverLon:double, payload:{weightKg:double(>0), description?:String}, priority?:int}`
- 响应: 200 - 创建后的配送任务（id 自动生成 `DT-<timestamp>-<seq>`）；400 - type/senderLat/senderLon/receiverLat/receiverLon/payload 缺失

**POST /api/v1/delivery2/tasks/{id}/deliver**
- 请求体: `{method:"AIR_DROP"|"LAND_DELIVER"|"ROPE_LOWER"}`
- 响应: 200 - `{taskId, method, status:"DELIVERED", landingSite?}`；400 - method 缺失或任务不在 IN_PROGRESS 状态；LAND_DELIVER 需要降落点

**GET /api/v1/delivery2/landing-sites**
- 查询参数: `lat` (double, 必填), `lon` (double, 必填), `radius` (double, 默认 5.0) - 搜索半径 km
- 响应: 200 - 降落点列表

**curl 示例**:
```bash
# 创建配送任务
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"type":"EMERGENCY_SUPPLY","senderLat":30.5,"senderLon":114.3,"receiverLat":30.6,"receiverLon":114.4,"payload":{"weightKg":5.0,"description":"急救药品"}}' \
  http://localhost:8080/api/v1/delivery2/tasks

# 执行投放
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"method":"AIR_DROP"}' http://localhost:8080/api/v1/delivery2/tasks/DT-001/deliver
```

---

## 数字孪生-城市

### 基础路径 `/api/v1/city-twin/simulation`

**Controller**: `citytwin/SimulationController` | **Tag**: CityTwin-Simulation - 灾害模拟推演：洪水、火灾、地震、疏散模拟及结果查询

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/flood` | 洪水模拟 | - (查询参数) | 200 DisasterSimulation / 400 |
| POST | `/fire` | 火灾模拟 | - (查询参数) | 200 DisasterSimulation / 400 |
| POST | `/earthquake` | 地震模拟 | - (查询参数) | 200 DisasterSimulation / 400 |
| POST | `/evacuation` | 疏散模拟 | - (查询参数) | 200 DisasterSimulation / 400 |
| GET | `/{id}` | 获取模拟结果 | - | 200 DisasterSimulation |
| GET | `/history` | 模拟历史 | - | 200 List<DisasterSimulation> |

#### 端点详情

**POST /api/v1.0/city-twin/simulation/flood**
- 查询参数: `centerLat` (double, 必填), `centerLon` (double, 必填), `radiusKm` (double, 必填, >0), `depthM` (double, 必填, >0), `durationMin` (int, 必填, >0)
- 响应: 200 - DisasterSimulation；400 - 参数越界

**POST /api/v1/city-twin/simulation/fire**
- 查询参数: `centerLat`, `centerLon`, `radiusKm`(>0), `windSpeed`(>=0), `durationMin`(>0)

**POST /api/v1/city-twin/simulation/earthquake**
- 查询参数: `centerLat`, `centerLon`, `magnitude`(6(>0), `durationMin`(>0)

**POST /api/v1/city-twin/simulation/evacuation**
- 查询参数: `centerLat`, `centerLon`, `radiusKm`(>0)

### 基础路径 `/api/v1/city-twin/situation`

**Controller**: `citytwin/SituationController` | **Tag**: CityTwin-Situation - 实时态势叠加：当前态势、历史态势、无人机位置、告警标记

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/current` | 获取当前态势快照 | - | 200 RealtimeSituation |
| GET | `/history` | 获取历史态势（按时间范围） | - | 200 List<RealtimeSituation> |
| GET | `/drones` | 获取所有无人机位置 | - | 200 List<DronePosition> |
| GET | `/alerts` | 获取所有告警标记 | - | 200 List<AlertMarker> |

**GET /api/v1/city-twin/situation/history**
- 查询参数: `from` (long, 默认 0, epoch ms), `to` (long, 默认 0=当前时间)

### 基础路径 `/api/v1/city-twin/playback`

**Controller**: `citytwin/PlaybackController` | **Tag**: CityTwin-Playback - 历史回放：无人机轨迹回放、告警事件回放、综合态势回放

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/drones/{sysid}` | 无人机轨迹回放 | - | 200 List<DronePosition> |
| GET | `/alerts` | 告警事件回放 | - | 200 List<AlertMarker> |
| GET | `/situation` | 综合态势回放 | - | 200 List<RealtimeSituation> |

**GET /api/v1/city-twin/playback/drones/{sysid}**
- 查询参数: `from` (long, 默认 0), `to` (long, 默认 0=当前时间)

### 基础路径 `/api/v1/city-twin/markers`

**Controller**: `citytwin/MarkerController` | **Tag**: CityTwin-Markers - 态势标绘：在地图上创建、更新、删除点/线/面/圆/文本标记

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 列出所有标绘 | - | 200 List<SituationMarker> |
| POST | `` | 创建标绘 | SituationMarker | 200 SituationMarker |
| DELETE | `/{id}` | 删除标绘 | - | 200 (void) |
| PUT | `/{id}` | 更新标绘 | SituationMarker | 200 SituationMarker |

### 基础路径 `/api/v1/city-twin/models`

**Controller**: `citytwin/CityModelController` | **Tag**: CityTwin-Models - 城市三维模型管理：模型注册、查询、删除、刷新

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 列出所有城市模型 | - | 200 List<CityModel> |
| GET | `/{id}` | 获取模型详情 | - | 200 CityModel |
| POST | `` | 上传/注册新模型 | CityModel | 200 CityModel |
| DELETE | `/{id}` | 删除模型 | - | 200 (void) |
| PUT | `/{id}/refresh` | 刷新模型数据（更新时间戳） | - | 200 CityModel |

**curl 示例**:
```bash
# 洪水模拟
curl -X POST -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/city-twin/simulation/flood?centerLat=30.5&centerLon=114.3&radiusKm=2.0&depthM=1.5&durationMin=60"

# 获取当前态势
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/city-twin/situation/current
```

---

## 离线自治

### 基础路径 `/api/v1/offline-alarm`

**Controller**: `surveillance/offline/OfflineAlarmController` | **Tag**: OfflineAlarm - 安防设备离线自治 REST API

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/batch-upload` | 批量上传离线缓存的报警事件 | JsonArray | 200 {status,accepted,rejected,pendingCount} / 400 / 503 |
| GET | `/pending` | 获取待上传的离线报警列表 | - | 200 {count,alarms} / 503 |
| GET | `/cache-stats` | 获取缓存统计信息 | - | 200 Stats / 503 |
| POST | `/flush` | 手动触发批量上传到 AlarmEventStore | - | 200 {uploaded,remaining,status} / 503 |
| POST | `/edge-ai/trigger` | 模拟触发一次边缘 AI 检测 | {deviceId,deviceName,detectType,lat,lon,description} | 200 {status,deviceId,detectType,message} / 400 / 503 |
| GET | `/edge-ai/stats` | 获取边缘 AI 检测统计 | - | 200 Stats / 503 |
| POST | `/edge-ai/configure` | 配置设备的启用检测类型 | {deviceId,enabledTypes:[...]} | 200 {status,deviceId,enabledTypes} / 400 / 503 |

#### 端点详情

**POST /api/v1/offline-alarm/batch-upload**
- 请求体: JSON 数组，每个元素包含 `{deviceId, deviceName?, eventType?, severity?, description?, lat?, lon?, alt?, timestampMs?}`
- 响应: 200 - `{status:"ok", accepted:int, rejected:int, pendingCount:int}`；400 - body 不是 JSON 数组；503 - 离线报警缓存服务不可用

**POST /api/v1/offline-alarm/edge-ai/trigger**
- 请求体: `{deviceId:String, deviceName:String, detectType:"PERSON"|"VEHICLE"|"FIRE"|"ANIMAL", lat:double, lon:double, description:String}`
- 响应: 200 - `{status:"TRIGGERED"|"SKIPPED", deviceId, detectType, message}`

---

## LoRa 回传

### 基础路径 `/api/v1/loRa`

**Controller**: `api/controller/LoRaRelayController` | **Tag**: LoRaRelay - LoRa 回传报警 REST API：接收布控球经无人机 mesh 路由的回传告警

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `/alarm` | 接收 LoRa 回传告警 | LoRaAlarmDto | 200 {eventId,status,latencyMs,...} / 503 |
| GET | `/stats` | 获取 LoRa 回传通道统计信息 | - | 200 Stats / 503 |

**curl 示例**:
```bash
# 接收 LoRa 回传告警
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"deviceId":"cam-001","eventType":"INTRUSION","severity":"CRITICAL","lat":30.5,"lon":114.3}' \
  http://localhost:8080/api/v1/loRa/alarm
```

---

## 用户管理

### 基础路径 `/api/v1/users`

**Controller**: `security/UserController` | 用户管理 CRUD API，ADMIN 可管理本租户用户

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 列出当前租户的用户（全局管理员列出所有用户）（需 ADMIN） | - | 200 List<UserResponse> |
| GET | `/{id}` | 获取指定用户详情（需 ADMIN） | - | 200 UserResponse / 404 |
| POST | `` | 创建用户（username 必须唯一，需 ADMIN） | {username,password,role,tenantId?,enabled?} | 201 UserResponse / 400 |
| PUT | `/{id}` | 更新用户（不允许修改 username，需 ADMIN） | {role?,enabled?,tenantId?,password?} | 200 UserResponse / 404 |
| DELETE | `/{id}` | 删除用户（不允许删除自己，需 ADMIN） | - | 204 / 400 / 404 |

#### 端点详情

**POST /api/v1/users** （需 ADMIN 角色）
- 请求体: `{username:String, password:String, role:"ADMIN"|"OPERATOR"|"OBSERVER", tenantId?:Integer, enabled?:Boolean(默认true)}`
- 响应: 201 - `{id, username, role, tenantId, enabled, createdAt}`（不含 passwordHash）；400 - username 已存在或 role 非法

**PUT /api/v1/users/{id}** （需 ADMIN 角色）
- 请求体: `{role?:String, enabled?:Boolean, tenantId?:Integer, password?:String}`（不允许修改 username）
- 响应: 200 - 更新后的用户（不含 passwordHash）；404 - 用户不存在

**DELETE /api/v1/users/{id}** （需 ADMIN 角色）
- 响应: 204 - 删除成功；400 - 不允许删除自己；404 - 用户不存在
- 注: 跨租户访问控制：非全局管理员只能访问本租户用户

---

## 租户管理

### 基础路径 `/api/v1/tenants`

**Controller**: `security/TenantController` | 租户管理 CRUD API，仅 ADMIN 可操作

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `` | 列出所有租户（需 ADMIN） | - | 200 List<TenantResponse> |
| GET | `/{id}` | 获取指定租户详情（需 ADMIN） | - | 200 TenantResponse / 404 |
| POST | `` | 创建租户（code 必须唯一，需 ADMIN） | {name,code,enabled?} | 201 TenantResponse / 400 |
| PUT | `/{id}` | 更新租户（需 ADMIN） | {name,code,enabled?} | 200 TenantResponse / 404 / 409 |
| DELETE | `/{id}` | 删除租户（不允许删除有用户的租户或自己所属租户，需 ADMIN） | - | 204 / 400 / 404 |

#### 端点详情

**POST /api/v1/tenants** （需 ADMIN 角色）
- 请求体: `{name:String, code:String, enabled?:Boolean(默认true)}`
- 响应: 201 - `{id, name, code, enabled, createdAt}`；400 - code 已存在

**DELETE /api/v1/tenants/{id}** （需 ADMIN 角色）
- 响应: 204 - 删除成功；400 - 不允许删除有用户的租户或自己所属租户；404 - 租户不存在

**curl 示例**:
```bash
# 创建用户
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"username":"operator1","password":"pass123","role":"OPERATOR","tenantId":1}' \
  http://localhost:8080/api/v1/users

# 创建租户
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"租户A","code":"TENANT_A","enabled":true}' \
  http://localhost:8080/api/v1/tenants
```

---

## API Key 管理

### 基础路径 `/api/v1/auth/api-key`

**Controller**: `security/ApiKeyController` | API Key 管理端点（所有端点需 JWT 认证，不能用 API Key 创建/管理 API Key）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `` | 生成 API Key（明文仅返回一次） | {name,scopes?,expiresAt?} | 201 {keyId,apiKey,maskedKey,name,scopes,createdAt,expiresAt,warning} / 400 |
| DELETE | `/{keyId}` | 撤销 API Key | - | 200 {keyId,revoked:true} / 403 / 404 |
| GET | `` | 列出当前用户的 API Key（脱敏显示） | - | 200 List<{keyId,maskedKey,name,scopes,createdAt,expiresAt,lastUsedAt,revoked}> |

#### 端点详情

**POST /api/v1/auth/api-key**
- 请求体: `{name:String, scopes?:[String], expiresAt?:String(ISO-8601, 默认365天)}`
- 响应: 201 - `{keyId, apiKey:"nsk_<64hex>", maskedKey:"nsk_****<last4>", name, scopes, createdAt, expiresAt, warning:"This is the only time the full API Key will be shown."}`
- 安全设计: 数据库只存储 SHA-256 哈希，明文 API Key 仅在创建时返回一次

**DELETE /api/v1/auth/api-key/{keyId}**
- 路径参数: `keyId` (String) - API Key 标识（非完整 Key）
- 响应: 200 - `{keyId, revoked:true}`；403 - 跨租户撤销被拒绝；404 - Key 不存在

**curl 示例**:
```bash
# 生成 API Key
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"SDK集成Key","scopes":["drone:read","mission:write"]}' \
  http://localhost:8080/api/v1/auth/api-key

# 列出 API Key
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/auth/api-key
```

---

## Webhook 管理

### 基础路径 `/api/v1/webhooks`

**Controller**: `webhook/WebhookController` | Webhook 管理端点（租户隔离）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| POST | `` | 注册 webhook | {url,secret?,events} | 201 {id,url,events,enabled,createdAt} / 400 / 503 |
| GET | `` | 列出当前租户的 webhook（不含 secret） | - | 200 List<{id,url,events,enabled,createdAt,updatedAt}> / 503 |
| DELETE | `/{id}` | 注销 webhook | - | 200 {id,deleted:true} / 404 / 503 |

#### 端点详情

**POST /api/v1/webhooks**
- 请求体: `{url:String(http://或https://), secret?:String, events:[String](非空)}`
- 响应: 201 - `{id, url, events, enabled:true, createdAt}`；400 - url 缺失/格式错误/events 为空（SSRF 防护：URL 格式校验）

---

## OpenAPI 导出

### 基础路径 `/api/v1/openapi`

**Controller**: `api/OpenApiExportController` | OpenAPI Spec 导出端点（需认证，所有环境可访问）

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/json` | 导出 OpenAPI JSON spec | - | 200 application/json / 503 |
| GET | `/yaml` | 导出 OpenAPI YAML spec | - | 200 application/yaml / 503 |

**curl 示例**:
```bash
# 导出 OpenAPI JSON
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/openapi/json

# 导出 OpenAPI YAML
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/openapi/yaml
```

---

## 附录

### 错误响应格式

所有错误响应统一使用以下 JSON 格式：

```json
{
  "error": "错误描述信息",
  "timestamp": 1695600000000
}
```

常见 HTTP 状态码：

| 状态码 | 含义 | 触发场景 |
|--------|------|----------|
| 200 | 成功 | 正常请求 |
| 202 | 已接受（异步处理） | 环绕飞行、地形建图 |
| 400 | 请求参数错误 | 参数缺失/格式错误/越界 |
| 401 | 未认证 | 缺少/无效 JWT 令牌 |
| 403 | 禁止访问 | 角色权限不足 |
| 404 | 资源不存在 | 无人机/设备/任务未找到 |
| 409 | 冲突 | 无人机离线/已有在飞任务 |
| 429 | 频率超限 | 登录频率限制 |
| 502 | 网关错误 | ONVIF/MAVLink 通信失败 |

### 角色权限说明

| 角色 | 说明 | 典型端点 |
|------|------|----------|
| ADMIN | 管理员 | 雷达配置、基站配置、卫星策略、审计日志 |
| OPERATOR | 操作员 | 飞行命令、任务创建、报警确认、应急响应 |
| （无注解） | 登录用户即可 | 查询类端点（遥测、轨迹、状态） |

### SSE 事件流

支持 SSE（Server-Sent Events）的端点：

| 端点 | 事件名 | 心跳间隔 | 超时时间 |
|------|--------|----------|----------|
| GET /api/alarms/stream | `alarm-event` | 15 秒 | 30 分钟 |
| GET /api/surveillance/devices/{id}/events | `surveillance-event` | 15 秒 | 30 分钟 |

SSE 客户端示例（JavaScript）：
```javascript
const es = new EventSource('http://localhost:8080/api/alarms/stream', {
  withCredentials: true
});
es.addEventListener('alarm-event', (e) => {
  const event = JSON.parse(e.data);
  console.log('报警事件:', event);
});
es.onerror = (e) => {
  console.error('SSE 连接错误:', e);
  es.close();
};
```

### 模块与 Controller 映射

| 模块 | Controller | 基础路径 | 端点数 |
|------|-----------|----------|--------|
| 无人机控制 | DroneController | /api/v1/drones | 8 |
| 无人机控制 | FlightLogController | /api/v1/flightlog | 2 |
| 飞行追踪 | TrackingController | /api/tracking | 6 |
| 电子围栏 | GeofenceController | /api/geofence | 7 |
| 远程锁机 | DroneLockController | /api/drone-lock | 6 |
| 安防监控 | SurveillanceController | /api/surveillance | 11 |
| 报警联动 | AlarmController | /api/alarms | 13 |
| 应急指挥 | EmergencyCommandController | /api/emergency-command | 10 |
| 应急指挥 | EmergencyOrchController | /api/v1/emergency | 10 |
| 编队表演 | FormationController | /api/v1/formation | 8 |
| 喷洒物流 | SprayController | /api/v1/spray | 3 |
| 喷洒物流 | DeliveryController | /api/v1/delivery | 4 |
| 视觉感知 | VisionController | /api/v1/vision | 4 |
| 视觉感知 | ThermalController | /api/v1/thermal | 2 |
| 视觉感知 | MultispectralController | /api/v1/multispectral | 2 |
| 视觉感知 | ObstacleController | /api/v1/obstacle | 4 |
| 硬件抽象 | HardwareDataController | /api/v1 | 8 |
| 通信组网 | MeshController | /api/v1/mesh | 5 |
| 通信组网 | CellTowerController | /api/v1/celltowers | 6 |
| 通信组网 | SatLinkController | /api/v1/sat-link | 8 |
| 通信组网 | TerrainController | /api/v1/terrain | 4 |
| 集群调度 | SchedulingController | /api/v1/scheduling | 5 |
| 集群调度 | SquadController | /api/v1/squad | 3 |
| AI 决策 | DecisionMonitorController | /api/v1/ai | 2 |
| 边缘计算 | EdgeCoordinationController | /api/v1/edge | 3 |
| 数字孪生 | TwinController | /api/v1/twin | 4 |
| 环境气象 | EnvAlertController | /api/v1/env-alerts | 1 |
| 安全认证 | AuthController | /api/auth | 2 |
| 审计日志 | AuditController | /api/audit | 1 |
| 许可证 | LicenseController | /api/license | 3 |
| 自动出警 | AutoDispatchController | /api/v1/autodispatch | 6 |
| 自动出警 | VoiceIntercomController | /api/v1/voice-intercom | 4 |
| 自动出警 | VideoStreamController | /api/v1/video-stream | 5 |
| 语音指挥 | VoiceCommandController | /api/v1/voice-cmd | 8 |
| 空地协同 | AirGroundCoordinationController | /api/v1/air-ground | 6 |
| 通信自适应 | CommSituationController | /api/v1/comm-adapt | 8 |
| 灾害通信 | DisasterCommController | /api/v1/disaster | 6 |
| 健康管理 | HealthController | /api/v1/health | 5 |
| 健康管理 | MaintenanceController | /api/v1/maintenance | 6 |
| 智能巡检 | InspectionController | /api/v1/inspection | 7 |
| 智能巡检 | InspectionReportController | /api/v1/inspection/reports | 4 |
| 航拍测绘 | MappingController | /api/v1/mapping | 11 |
| 编排管理 | OrchestrationController | /api/v1/orch | 8 |
| 场景管理 | ScenarioTemplateController | /api/v1/scenarios/templates | 6 |
| 场景管理 | ScenarioLaunchController | /api/v1/scenarios/launch | 5 |
| 场景管理 | ScenarioDrillController | /api/v1/scenarios/drill | 3 |
| 编队表演-灯光秀 | ShowController | /api/v1/show | 11 |
| 物流配送2 | DeliveryController2 | /api/v1/delivery2 | 10 |
| 数字孪生-城市 | SimulationController | /api/v1/city-twin/simulation | 6 |
| 数字孪生-城市 | SituationController | /api/v1/city-twin/situation | 4 |
| 数字孪生-城市 | PlaybackController | /api/v1/city-twin/playback | 3 |
| 数字孪生-城市 | MarkerController | /api/v1/city-twin/markers | 4 |
| 数字孪生-城市 | CityModelController | /api/v1/city-twin/models | 5 |
| 离线自治 | OfflineAlarmController | /api/v1/offline-alarm | 7 |
| LoRa 回传 | LoRaRelayController | /api/v1/loRa | 2 |
| 用户管理 | UserController | /api/v1/users | 5 |
| 租户管理 | TenantController | /api/v1/tenants | 5 |
| API Key 管理 | ApiKeyController | /api/v1/auth/api-key | 3 |
| Webhook 管理 | WebhookController | /api/v1/webhooks | 3 |
| OpenAPI 导出 | OpenApiExportController | /api/v1/openapi | 2 |
| **合计** | **60 个 @RestController** | | **318** |

> **注**: 项目共 63 个 `*Controller.java` 文件，其中 `vision/RadarController`、`vision/RotorController`、`vision/ObstacleAvoidanceController` 为 `@Service` 内部组件（不暴露 REST 端点），其能力通过 `HardwareDataController` 和 `ObstacleController` 对外提供。`ApiExceptionHandler` 为 `@RestControllerAdvice`（全局异常处理，非端点 Controller）。已文档化的 60 个 `@RestController` 共 318 个端点。