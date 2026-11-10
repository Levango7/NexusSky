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
| link-sim 控制面 | 14601 | 运行时伤害控制（`--control-port`，默认关闭） |

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

> **凭据必须先替换，否则装不上。** `values.yaml` 里的 `security.jwtSecret`、
> `security.encryptionKey`、`database.password` 是 `REPLACE_WITH_*` 占位串，仓库里公开可见。
> 它们长度达标，会原样通过后端既有的「JWT secret ≥32 字节」强度校验——所以从本版本起
> chart 在 `helm install/template` 阶段直接 `fail`，后端 `DeploymentSecretsGuard` 启动时
> 也会拒绝，两级都不给「安静地用公开字符串当密钥」留机会。

```bash
cd deploy/helm/nexussky

# 用一份不进版本库的私有 values 覆盖占位值
helm install nexussky . -f my-values.yaml
# my-values.yaml 至少包含：
#   security:
#     jwtSecret: "<≥32 字符随机串>"
#     encryptionKey: "<≥32 字符随机串>"
#   database:
#     password: "<真实库口令>"

# 或临时用 --set（会留在 shell history 里，仅适合本地验证）
helm install nexussky . \
  --set security.jwtSecret="$JWT_SECRET" \
  --set security.encryptionKey="$ENC_KEY" \
  --set database.password="$DB_PASSWORD"
```

自查（本机或 CI 同一条脚本）：

```bash
bash scripts/check-deploy-secrets.sh
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
helm upgrade nexussky . -f my-values.yaml
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

### 3.3 部署相关键（换环境就要改的那一组）

下面 11 个键**不是内部调参**，而是"这套部署连的是谁 / 在哪"。出厂值是单机/仿真的姿势，
生产用 profile 或环境变量覆盖即可（键名不变，Spring 照常读取）。改完用**启动日志自证**，
每一行都写明去哪看——别靠猜。

| 配置项 | 出厂值 | 含义 | 改完在哪里看见 |
|---|---|---|---|
| `aerofleet.drone-host` | `127.0.0.1` | UDP 网关主动发现的目标主机（真机/仿真的地址） | 启动日志 `MAVLink UDP gateway listening …, discovery -> <地址>` |
| `aerofleet.drone-sysid` | `1` | 请求未显式带 sysid 时的默认目标机 | 启动日志 `指令下行默认目标 sysid=…` |
| `aerofleet.gb28181.sip-host` | `127.0.0.1:5060` | GB28181 SIP 信令对端 | 启动日志 `GB28181 接入配置: sip-domain=…, sip-host=…` |
| `aerofleet.gb28181.sip-domain` | `00000000000000000001` | SIP 域（GB28181 标准为 20 位域编码） | 同上 |
| `aerofleet.sensing.base-lat` | `22.5907` | 反制雷达站址纬度（模拟源的航迹基点） | 启动日志 `CounterDroneRadarSource (模拟) 就绪: base=(lat, lon)` |
| `aerofleet.sensing.base-lon` | `113.9345` | 同上，经度 | 同上 |
| `aerofleet.instance-id` | `unknown` | 指标公共标签 `instance`（多实例部署必改，否则 Prometheus 里聚成一个） | `/actuator/prometheus` 里的 `instance="…"` 标签 |
| `aerofleet.dock.transport` | `sim` | 机巢命令通道：`sim`=HTTP 回环到 drone-sim；`mqtt`=DJI Cloud API over EMQX（二者条件装配、互斥） | `mqtt` 模式启动日志 `dock mqtt gateway: broker=…, clientId=…` |
| `aerofleet.dock.mqtt-broker-url` | （空） | MQTT Broker 地址（如 `tcp://host:1883`）；`transport=mqtt` 且为空 ⇒ **启动失败** | 启动失败文本 `aerofleet.dock.mqtt-broker-url is required when aerofleet.dock.transport=mqtt`；成功见上行 `broker=…` |
| `aerofleet.dock.mqtt-username` | （空） | Broker 用户名；空=匿名连接 | Broker 侧会话/ACL（进程日志不打印用户名） |
| `aerofleet.dock.mqtt-password` | （空） | Broker 密码；仅当用户名非空时随 CONNECT 下发 | 同上（口令不落日志） |

> ⚠️ 前 7 个键**此前只写在代码默认值里**，配置文件里搜不到（审计 §12 / §12.5）。
> 声明进 `application.properties` 不会改变行为（值等于代码默认），只是让运维能在配置里看见。
> 后 4 个机巢 MQTT 键已声明在 `application.properties`：`transport=mqtt` 时才装配，
> `mqtt-broker-url` 为空会让进程**启动失败**（fail-fast），不是"配了不响"。
> 其中 `gb28181.sip-domain` 必须 20 位数字串；写错不会启动失败，只会连不上对端——
> 所以以启动日志那一行为准。

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
- `aerofleet_messages_received_total` — 接入帧总数（按 `msgId` 标签；位于验签/白名单/限速之前，可与注入量对账）
- `aerofleet_messages_decoded_total` — 成功解码帧数
- `aerofleet_messages_errors_total` — 解析失败帧数（CRC 校验失败等）
- `aerofleet_api_requests_total` — API 请求总数
- `aerofleet_ws_sessions` — 当前遥测 WebSocket 会话数
- `aerofleet_ws_fanout_queue_depth` — 遥测扇出待发队列积压（持续贴近 4096 即意味着有客户端消费不过来）
- `aerofleet_ws_fanout_dropped_total` — 因扇出队列饱和被丢弃的帧数
- `aerofleet_udp_ingest_datagrams_total` — 收到的 UDP 数据报数
- `aerofleet_udp_ingest_noise_bytes_total` — 解析时跳过的非 STX 噪声**字节数**（不是帧数）
- `aerofleet_udp_ingest_dropped_total{reason=...}` — 摄入侧丢弃，按原因分片

> 名字按 Micrometer → Prometheus 的转换规则写：点号变下划线，Counter 追加 `_total`。
> 上面列出的都在代码里有对应的注册点（`DroneMetrics` / `ApiMetricsFilter` / `WsFanoutMetrics`）。
> 此前本节写的 `aerofleet_mavlink_frames_received_total` 与
> `aerofleet_ws_connections` **在代码里从未存在过**（前者实际叫 `aerofleet_messages_received_total`，
> 后者直到 2026-11-07 才以 `aerofleet_ws_sessions` 落地），照抄会得到永远查不到数据的告警规则。

> **2026-11-08 更正：「有注册点」不等于「有数据」**。`aerofleet_messages_received_total`
> 对应的 `DroneMetrics.recordMessageReceived/Decoded` 此前**全仓零调用**，指标虽已注册却恒为 0，
> 容量基线因此无法区分「注入器少发」与「UDP 摄入丢包」。现已在 `UdpGateway.onFrame` 入口接入
> `received`（按 `msgId` 标签）与 `decoded`，位置在验签 / 白名单 / 限速等丢弃分支之前，
> 故可与注入量直接对账；被策略丢弃的帧仍计入接入侧。
>
> **2026-11-10 已完成 `messages.errors` 桥接**：该指标不再恒为 0。`mavlink-core` 新增
> `UdpIngestObserver` 接口（依赖倒置——core 不依赖 Micrometer / Spring / cloud-backend），
> `UdpMavlinkTransport` 在每个 UDP 包解析结束后回调一次，`UdpGateway` 注入实现写入 Micrometer。
> 计数取解析前后 `MavlinkParser.crcErrors` 的差值，**按包聚合而非逐帧回调**：
> 回调在收包线程内同步执行，实现必须 O(1)、不阻塞、不做 IO、不抛异常。

#### 摄入侧丢包怎么定位

四类口径互不重叠，**不要相加**：

| 现象 | 指标 | 单位 |
|---|---|---|
| 内核 UDP 队列溢出 | 无直接指标（见下） | — |
| 数据报被截断 | `udp.ingest.dropped{reason="datagram-truncated"}` | 数据报 |
| 帧解析失败（CRC 等） | `udp.ingest.dropped{reason="frame-crc-error"}` | 帧 |
| 非 STX 噪声 | `udp.ingest.noise.bytes` | 字节 |
| 策略丢弃 | `{reason="frame-signature-rejected" / "frame-whitelist-rejected" / "frame-rate-limited"}` | 帧 |
| 监听器抛异常 | `{reason="frame-listener-error"}` | 帧 |
| 收包 IO 异常 | `{reason="datagram-receive-error"}` | 次 |

**内核溢出只能间接对账**：Java `DatagramSocket` 没有跨平台的「内核丢包」计数，
只能用「注入端送出包数 − `udp.ingest.datagrams`」估算。这个差值是**量级参考，不是精确值**：
注入器日志每 10 秒打印一次累计值，与 Prometheus 采集时刻最多差 10 秒，
误差量级 = 10s × 注入速率。

⚠️ 不要用 `messages.errors` 单独判断丢包：它只覆盖 CRC / 解析失败，
内核溢出和策略丢弃都不会进入该计数。

**建议告警规则**（扇出背压）：`rate(aerofleet_ws_fanout_dropped_total[5m]) > 0`
——非零即表示已有客户端跟不上，遥测开始丢最旧帧。处置顺序：先看
`aerofleet_ws_sessions` 是否突增，再查是否有客户端连上不读。

**建议告警规则**（摄入侧丢弃）：`rate(aerofleet_udp_ingest_dropped_total[5m]) > 0`，
按 `reason` 分流处置——`frame-rate-limited` 是产品限速（先确认
`aerofleet.udp.max-frame-rate-per-sysid` 是否被调得过小，属预期行为）；
`frame-crc-error` 指向链路质量或对端实现不一致；`datagram-truncated` 说明帧长超过接收缓冲区。

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