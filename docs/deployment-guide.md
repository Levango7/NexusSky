# NexusSky 部署运维手册

> **版本**：v1.0 | **适用对象**：运维人员/DevOps 工程师

---

## 一、Docker Compose 部署（单机）

### 1.1 前置条件
- Docker 24+
- Docker Compose v2+

### 1.2 启动

```bash
cd deploy/docker
docker-compose up -d
```

### 1.3 服务端口

| 服务 | 端口 | 说明 |
|---|---|---|
| cloud-backend | 8080 | REST API + WebSocket |
| drone-sim | 14540 | MAVLink UDP |
| gcs-web | 5173 | 地面站前端 |
| link-sim | 14600 | 链路代理 |

### 1.4 停止

```bash
docker-compose down
```

---

## 二、Kubernetes 部署（Helm）

### 2.1 前置条件
- K8s 1.28+
- Helm 3.12+

### 2.2 部署

```bash
cd deploy/helm/nexussky
helm install nexussky . -f values.yaml
```

### 2.3 配置项（values.yaml）

```yaml
cloudBackend:
  replicas: 2
  image: nexussky/cloud-backend:latest
  resources:
    limits:
      cpu: 2000m
      memory: 2Gi

droneSim:
  replicas: 1
  image: nexussky/drone-sim:latest

gcsWeb:
  replicas: 2
  image: nexussky/gcs-web:latest

ingress:
  enabled: true
  host: nexussky.example.com
```

### 2.4 升级

```bash
helm upgrade nexussky . -f values.yaml
```

### 2.5 卸载

```bash
helm uninstall nexussky
```

---

## 三、配置说明

### 3.1 核心配置（application.properties）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `server.port` | 8080 | REST API 端口 |
| `aerofleet.udp-port` | 14550 | MAVLink UDP 端口 |
| `aerofleet.security.dev-mode` | true | 开发模式（true=免认证） |
| `aerofleet.security.jwt-secret` | (dev) | JWT 签名密钥（生产必换） |
| `aerofleet.license.enabled` | false | License 校验开关 |
| `aerofleet.license.key` | (空) | License Key |
| `aerofleet.tenant.rate-limit` | 100 | 每租户每分钟 API 上限 |

### 3.2 生产环境配置

```properties
# 关闭开发模式
aerofleet.security.dev-mode=false
# 设置强密钥
aerofleet.security.jwt-secret=<至少32字符的随机字符串>
# 启用 License
aerofleet.license.enabled=true
aerofleet.license.key=<Base64编码的License Key>
```

---

## 四、监控

### 4.1 健康检查

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"},...}}
```

### 4.2 Prometheus 指标

```bash
curl http://localhost:8080/actuator/prometheus
```

关键指标：
- `aerofleet_drones_online` — 在线无人机数
- `aerofleet_mavlink_frames_received_total` — 接收帧总数
- `aerofleet_api_requests_total` — API 请求总数
- `aerofleet_ws_connections` — WebSocket 连接数

### 4.3 日志

日志输出到 stdout（容器化）+ 文件（logback-spring.xml）：
- INFO 级别：正常操作
- WARN 级别：License/限流/链路异常
- ERROR 级别：服务错误

---

## 五、故障排查

| 现象 | 可能原因 | 解决方案 |
|---|---|---|
| 无人机不在线 | UDP 端口不通 | 检查防火墙 14550 端口 |
| API 403 | License 无效 | 检查 aerofleet.license.key 配置 |
| API 429 | 限流触发 | 调高 aerofleet.tenant.rate-limit |
| WebSocket 断开 | 网络问题 | 检查 /ws/** 路径代理配置 |
| 启动失败 | JWT 密钥太短 | 确保 jwt-secret 至少 32 字符 |
| 设备不注册 | MAVLink 版本不匹配 | 确认无人机使用 MAVLink 2.0 |

---

## 六、备份与恢复

### 6.1 飞行日志

日志存储在 `./flight-logs/` 目录，按 UTC 日期分文件：
```
flight-logs/2026-09-17.jsonl
```

### 6.2 数据库

H2 内存模式无需备份。生产环境使用外部数据库时，按数据库标准备份流程操作。