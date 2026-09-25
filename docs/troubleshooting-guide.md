# NexusSky 故障排查指南

## 1. 常见错误码对照表

### 1.1 HTTP 状态码与错误消息

| HTTP 状态码 | 错误消息 | 含义 | 触发位置 |
|---|---|---|---|
| 400 | `malformed request: ...` | 请求体格式错误（JSON 解析失败、参数类型不匹配、缺少必填参数） | `ApiExceptionHandler.malformed()` |
| 400 | `validation failed: ...` | Bean Validation 校验失败（字段约束不满足） | `ApiExceptionHandler.validationFailed()` |
| 400 | `bad request: ...` | 业务层拒绝（不支持的 mission cmd、缺失字段） | `ApiExceptionHandler.BadRequestException` |
| 401 | `认证已过期，请重新登录` | JWT token 过期或无效，前端自动清除 token 并跳转登录 | `api.js:jsonFetch()` 401 处理 |
| 403 | `forbidden: requires role XXX` | RBAC 角色不足，JWT 中 `role` claim 不满足 `@RequireRole` 要求 | `RoleInterceptor.preHandle()` |
| 404 | `no such endpoint` | 请求路径不存在 | `ApiExceptionHandler.noRoute()` |
| 404 | `not found: ...` | 资源不存在（无人机、任务等） | `ApiExceptionHandler.NotFoundException` |
| 429 | `Too Many Requests` | 租户 API 调用频率超限（默认 100 次/分钟） | `TenantInterceptor.preHandle()` |
| 500 | `internal server error` | 未捕获的服务端异常（生产模式不泄露细节） | `ApiExceptionHandler.internal()` |
| 500 | `internal error: ...` | 未捕获的服务端异常（dev 模式返回详细消息） | `ApiExceptionHandler.internal()` |

### 1.2 MAVLink 命令执行错误

在 `drone-sim` 的 `VirtualDrone` 中，命令执行结果包含 `errorCode` 字段：

| errorCode | 含义 | 排查方向 |
|---|---|---|
| `SUCCESS` | 命令执行成功 | — |
| `BUSY` | 飞机正在执行其他任务 | 检查当前飞行模式，等待任务完成或发送 RTL |
| `REJECTED` | 命令被拒绝（如未 ARM 就发起飞指令） | 检查飞行状态机：STANDBY → ARM → TAKEOFF |
| `FAILED` | 命令执行失败 | 查看 drone-sim 控制台日志获取详细原因 |

### 1.3 基站接入错误（AccessResult.errorCode）

| errorCode | 含义 | 排查方向 |
|---|---|---|
| `CONGESTION` | 基站拥塞，拒绝新接入 | 等待其他终端断开后重试 |
| `OUT_OF_RANGE` | 终端超出基站覆盖范围 | 检查无人机位置与基站覆盖半径 |
| `HANDOVER_FAILED` | 漫游切换失败 | 检查目标基站状态是否正常 |

## 2. 服务启动失败诊断流程

### 2.1 启动顺序与端口分配

```
drone-sim (UDP 14540) → cloud-backend (HTTP 8080, UDP 14550) → gcs-web (HTTP 5173)
```

使用 `scripts/start-all.cmd` 一键启动模拟器和后端：

```cmd
scripts\start-all.cmd
```

该脚本先执行 `mvn -q -DskipTests package` 构建 Java 模块，然后启动两个进程。

### 2.2 端口占用诊断

**症状**：启动报 `Address already in use` 或 `Port 8080 was already in use`

```cmd
:: 检查 8080 端口占用
netstat -ano | findstr :8080

:: 检查 14540 端口占用（drone-sim UDP）
netstat -ano | findstr :14540

:: 检查 14550 端口占用（cloud-backend UDP gateway）
netstat -ano | findstr :14550

:: 检查 5173 端口占用（Vite dev server）
netstat -ano | findstr :5173
```

找到占用进程的 PID 后：

```cmd
taskkill /PID <PID> /F
```

### 2.3 数据库连接诊断

**开发环境（H2 文件库）**：
- 连接地址：`jdbc:h2:file:./data/aerofleet;AUTO_SERVER=TRUE`
- H2 控制台：`http://localhost:8080/h2-console`（dev profile 启用）
- 常见问题：H2 文件被其他进程锁定 → 删除 `./data/aerofleet.mv.db` 后重启

**生产环境（PostgreSQL）**：
- 连接地址通过环境变量 `SPRING_DATASOURCE_URL` 配置
- 默认：`jdbc:postgresql://localhost:5432/aerofleet_prod`
- 诊断命令：

```bash
:: 测试 PostgreSQL 连通性
psql -h localhost -p 5432 -U aerofleet -d aerofleet_prod -c "SELECT 1"

:: 检查 PostgreSQL 服务状态（Linux）
systemctl status postgresql
```

- Flyway 迁移失败时检查 `flyway_schema_history` 表，必要时手动修复：

```sql
DELETE FROM flyway_schema_history WHERE success = false;
```

### 2.4 Redis 连接诊断

**开发环境**：Redis 禁用（`spring.cache.type=simple`），无需 Redis

**生产环境**：
- 连接地址通过 `SPRING_REDIS_HOST` / `SPRING_REDIS_PORT` 配置
- 诊断命令：

```bash
redis-cli -h localhost -p 6379 ping
:: 期望返回 PONG
```

- Redis 不可用时，限流自动回退到内存滑动窗口（`TenantInterceptor.checkRateLimitInMemory()`），但多实例部署下限流不准确

### 2.5 JWT 密钥配置诊断

**生产环境启动失败**：`jwt.generate-keys=true is not allowed in production profile`

原因：生产环境禁止自动生成 RSA 密钥对（防止重启后所有 JWT token 失效）。

解决方案：
1. 配置 `jwt.private-key` 和 `jwt.public-key`（PEM 格式 Base64 编码）
2. 或切换到 HS256：设置 `jwt.algorithm=HS256` 和 `jwt.secret`（至少 32 字节）
3. 或设置环境变量 `AEROFLEET_JWT_SECRET`

## 3. 日志位置与分析方法

### 3.1 日志配置

日志框架：Logback（`cloud-backend/src/main/resources/logback-spring.xml`）

| Profile | 格式 | 输出目标 | 日志级别 |
|---|---|---|---|
| dev / default | 人类可读：`HH:mm:ss.SSS [thread] LEVEL logger - msg` | 控制台 | `io.aerofleet=DEBUG`, `org.springframework.web=DEBUG` |
| prod | JSON 结构化：`{"timestamp":"...","level":"...","thread":"...","logger":"...","msg":"..."}` | 控制台（供 ELK/Loki 采集） | `root=WARN`, `io.aerofleet=INFO` |

### 3.2 飞行日志

飞行日志持久化到 `./flight-logs/flight-YYYY-MM-DD.jsonl`（JSON Lines 格式）：

- 遥测数据 1Hz 节流写入
- 告警/任务/上下线事件即时写入
- 不依赖 GCS 页面是否打开

查询接口：

```
GET /api/v1/flightlog?day=2026-09-13&type=alert&sysid=1&limit=100
GET /api/v1/flightlog/track?sysid=1
```

手动查看：

```bash
:: 查看某天的所有告警
cat flight-logs/flight-2026-09-13.jsonl | jq 'select(.type=="alert")'

:: 查看某架机的轨迹
cat flight-logs/flight-2026-09-13.jsonl | jq 'select(.sysid==1 and .type=="telemetry")'
```

### 3.3 审计日志

生产环境启用审计日志（`aerofleet.audit.enabled=true`），输出到 `./audit-logs/` 目录。

### 3.4 日志分析技巧

**查找 RBAC 拒绝记录**：

```bash
grep "RBAC 拒绝" cloud-backend.log
```

**查找 Webhook 推送失败**：

```bash
grep "Webhook 推送失败" cloud-backend.log
```

**查找 API Key 认证问题**：

```bash
grep "API Key" cloud-backend.log
```

**查找租户限流触发**：

```bash
grep "API 调用超限" cloud-backend.log
```

## 4. 网络连通性检查步骤

### 4.1 MAVLink UDP 端口

系统使用 UDP 进行 MAVLink 通信：

| 端口 | 用途 | 进程 |
|---|---|---|
| 14540 | drone-sim 监听（飞机侧） | drone-sim |
| 14550 | cloud-backend 监听（GCS 侧） | cloud-backend |
| 14541-14543 | 额外模拟无人机 | drone-sim (extra rigs) |
| 14600 | link-sim 代理端口 | link-sim |

**诊断步骤**：

```cmd
:: 1. 确认 drone-sim 已启动并在监听 14540
netstat -ano | findstr :14540

:: 2. 确认 cloud-backend 已启动并在监听 14550
netstat -ano | findstr :14550

:: 3. 后端必须与模拟器同机启动（骨架阶段 UDP 走 127.0.0.1）
:: 模拟器像 PX4 真机一样等待 GCS 先发心跳才开始通信
```

如果机队列表为空，检查后端日志中是否有 UDP discovery 心跳发送记录。

### 4.2 WebSocket 连接

WebSocket 端点：`/ws/telemetry`

```cmd
:: 使用 wscat 测试 WebSocket 连接
npm install -g wscat
wscat -c ws://localhost:8080/ws/telemetry
```

前端 WebSocket 自动重连机制（`useWebSocket.js`）：
- 断线后 3 秒自动重连
- 连接状态显示在顶部导航栏（`LIVE` / `RECONNECTING`）

WebSocket 连接数限制：
- 单 IP 最大连接数：`aerofleet.ws.max-connections-per-ip=10`
- 单租户最大连接数：`aerofleet.ws.max-connections-per-tenant=20`

### 4.3 REST API 连通性

```cmd
:: 测试基础 API
curl http://localhost:8080/api/v1/drones

:: 测试健康检查
curl http://localhost:8080/actuator/health

:: 测试带认证的 API（生产模式）
curl -H "Authorization: Bearer <JWT_TOKEN>" http://localhost:8080/api/v1/drones

:: 测试 API Key 认证
curl -H "X-API-Key: nsk_xxxxxxxx" http://localhost:8080/api/v1/drones
```

### 4.4 Vite Dev Server 代理

前端开发服务器（Vite）代理 `/api` 和 `/ws` 到后端 8080 端口。如果前端页面显示"云端离线"：

1. 确认后端正在运行：`curl http://localhost:8080/actuator/health`
2. 确认 Vite 代理配置正确（`vite.config.js` 中 proxy 设置）
3. 检查浏览器控制台是否有 CORS 错误

## 5. 常见问题 FAQ

### Q1: 无人机无法连接，机队列表为空

**排查步骤**：
1. 确认 drone-sim 已启动：检查控制台窗口是否有 `VirtualDrone started` 日志
2. 确认 cloud-backend 已启动：`curl http://localhost:8080/actuator/health`
3. 确认两者在同一台机器上运行（UDP 走 127.0.0.1）
4. 检查后端日志是否有 `UdpGateway` 心跳发送记录
5. 确认端口未被占用：`netstat -ano | findstr :14540`
6. 如果使用 link-sim，确认 `aerofleet.drone-port=14600` 配置正确

### Q2: 模拟器不响应命令

**排查步骤**：
1. 检查无人机是否已 ARM：`GET /api/v1/drones/{sysid}/telemetry` 查看 `armed` 字段
2. 检查飞行模式：某些命令仅在特定模式下有效（如 MISSION 模式下不能发 MANUAL_CONTROL）
3. 查看 drone-sim 控制台日志，确认命令是否被接收
4. 如果使用故障注入（`--scenario`），检查是否注入了 `link-loss` 导致双向黑洞
5. 检查心跳是否正常：后端 10 秒无心跳则标记 offline，offline 设备不接受命令

### Q3: 链路模拟不生效

**排查步骤**：
1. 确认 link-sim 已启动并监听 14600 端口
2. 确认 drone-sim 使用 `--bind-ip 127.0.0.2` 绑定到不同网段身份
3. 确认后端配置 `aerofleet.drone-port=14600` 指向链路代理
4. 检查 link-sim 控制台是否显示正确的 profile（`lan`/`wifi5`/`lte` 等）
5. 使用 `scripts/e2e-network.ps1` 运行网络回归测试验证

### Q4: WebSocket 连接频繁断开

**排查步骤**：
1. 检查网络稳定性，确认后端进程未重启
2. 检查是否触发连接数限制（单 IP 10 个、单租户 20 个）
3. 查看后端日志是否有 WebSocket 相关异常
4. 前端有 3 秒自动重连机制，短暂断开属正常行为
5. 如果使用 docker-compose，确认 host 网络模式配置正确

### Q5: JWT 认证失败，API 返回 401

**排查步骤**：
1. 确认 token 格式正确：JWT 应为 `header.payload.signature` 三段结构
2. 检查 token 是否过期：默认有效期 3600 秒（1 小时）
3. 确认 JWT 签名密钥配置一致：重启后端后如果使用 `jwt.generate-keys=true`，旧 token 会失效
4. 生产环境确认 `jwt.private-key` / `jwt.public-key` 配置正确
5. 前端 token 存储在 `sessionStorage`，关闭浏览器后需重新登录

### Q6: RBAC 角色权限不足，API 返回 403

**排查步骤**：
1. 确认 `aerofleet.security.rbac-enabled=true`（生产环境默认启用）
2. 检查 JWT 中的 `role` claim 是否与 `Role` 枚举匹配（ADMIN/OPERATOR/OBSERVER）
3. 角色层级：ADMIN(0) > OPERATOR(1) > OBSERVER(2)，用户 ordinal <= 要求 ordinal 才有权限
4. 查看后端日志中的 `RBAC 拒绝` 记录，确认被拒绝的路径和要求角色

### Q7: 租户数据隔离失效

**排查步骤**：
1. 确认 `TenantContext.getEffectiveTenantId()` 返回值正确
2. 检查 `TenantFilter` 是否正确从 JWT 提取 `tenant_id` claim
3. API Key 认证时检查 `ApiKeyContext.getTenantId()` 是否正确设置
4. 确认 `TenantInterceptor.afterCompletion()` 中 `TenantContext.clear()` 被调用（防止线程池复用泄漏）

### Q8: Webhook 推送失败

**排查步骤**：
1. 检查 webhook URL 是否通过 SSRF 校验（禁止私有 IP、localhost）
2. 确认 RestTemplate 超时设置：连接 5 秒、读取 10 秒
3. 检查 HMAC 签名是否正确：签名失败时跳过推送并记录 WARN 日志
4. 确认 webhook secret 已正确加密存储（AES-GCM）
5. 推送失败不阻塞主流程，仅记录日志

### Q9: 前端面板不可见

**排查步骤**：
1. 检查是否切换了"丐版"预算模式（顶部导航栏下拉框）
2. 丐版模式下部分面板被隐藏，参考 `VIEW_PANEL_MAP` 映射关系
3. 确认用户角色：ADMIN 可看到租户管理和用户管理面板
4. 检查 `isPanelAvailable()` 函数是否正确过滤

### Q10: 3D 视图加载失败

**排查步骤**：
1. Three.js 通过 CDN 加载（`https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js`），确认网络可访问
2. 检查浏览器控制台是否有 CDN 加载错误
3. WebGL 支持检查：确认浏览器支持 WebGL 渲染
4. 组件卸载时会清理 renderer 和事件监听器，反复切换视图不应导致内存泄漏