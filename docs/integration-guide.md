# NexusSky 集成手册

> **版本**：v1.0 | **适用对象**：将 NexusSky 对接现有飞控系统的开发人员

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

NexusSky 已注册 57 条扩展消息（msgId 420-476）。如需新增：

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
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

# 响应：{"token":"eyJhbG...","expiry":3600}
```

### 2.2 设备管理

```bash
# 查询所有设备
curl http://localhost:8080/api/drones \
  -H "Authorization: Bearer <token>"

# 响应：[{"sysid":1,"online":true,"battery":85,"lat":39.9,"lon":116.4,...}]
```

### 2.3 任务下发

```bash
# 航点任务上传
curl -X POST http://localhost:8080/api/mission/upload \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"sysid":1,"waypoints":[{"lat":39.9,"lon":116.4,"alt":50},...]}'
```

### 2.4 集群调度

```bash
# 任务分配
curl -X POST http://localhost:8080/api/scheduling/assign \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"taskId":"task-001","priority":5,"targetLat":39.9,"targetLon":116.4}'
```

### 2.5 数字孪生

```bash
# 查询孪生状态
curl http://localhost:8080/api/twin/state/1 \
  -H "Authorization: Bearer <token>"

# 轨迹预测
curl http://localhost:8080/api/twin/predict/1?horizonSeconds=30 \
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
curl http://localhost:8080/api/license/info
# {"tenantId":"dev","productName":"AeroFleet Cloud Dev Edition","devEdition":true}
```

### 4.2 激活

```bash
curl -X POST http://localhost:8080/api/license/activate \
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

通过 HTTP Header 传递租户标识：
```bash
curl http://localhost:8080/api/drones \
  -H "X-Tenant-Id: my-company" \
  -H "Authorization: Bearer <token>"
```

### 5.2 限流配置

```properties
aerofleet.tenant.rate-limit=100  # 每租户每分钟 API 调用上限
```

---

*完整 API 文档：启动后访问 http://localhost:8080/swagger-ui.html*