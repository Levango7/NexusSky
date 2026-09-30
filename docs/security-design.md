# NexusSky 安全设计文档

## 1. JWT 认证架构

### 1.1 JwtTokenProvider 实现

JWT 令牌的生成与验证由 `JwtTokenProvider`（`cloud-backend/src/main/java/io/aerofleet/cloud/security/JwtTokenProvider.java`）负责。

**支持的签名算法**：

| 算法 | 配置 | 适用场景 | 密钥来源 |
|---|---|---|---|
| RS256（默认推荐） | `jwt.algorithm=RS256` | 多实例部署，私钥签发、公钥验证 | `jwt.private-key` + `jwt.public-key`（PEM Base64） |
| HS256（兼容回退） | `jwt.algorithm=HS256` | 单实例或兼容旧配置 | `jwt.secret` 或 `aerofleet.security.jwt-secret`（≥32 字节） |

**算法选择逻辑**（`JwtTokenProvider` Spring 注入构造函数）：

1. `RS256` + 已配置 RSA 密钥 → 使用非对称签名
2. `RS256` + `jwt.generate-keys=true` → 自动生成 RSA 密钥对（仅开发环境，生产环境抛 `IllegalStateException`）
3. `RS256` + 无 RSA 密钥 → 回退到 HS256（记录 WARN 日志）
4. `HS256` → 使用对称签名

**生产环境约束**：
- `jwt.generate-keys=true` 在 `prod` profile 下抛异常，防止重启后所有 JWT token 失效
- 生产环境必须配置 `jwt.private-key` / `jwt.public-key` 或使用 HS256 + `AEROFLEET_JWT_SECRET` 环境变量

### 1.2 JWT Claims 结构

```json
{
  "iss": "aerofleet",
  "sub": "<username>",
  "iat": <issuedAt>,
  "exp": <expiresAt>,
  "type": "access",
  "role": "ADMIN|OPERATOR|OBSERVER",
  "tenant_id": <integer|null>
}
```

- `sub`：用户名（主题）
- `role`：用户角色枚举名称（`Role.name()`）
- `tenant_id`：租户 ID（`null` 表示全局管理员）
- `type`：固定值 `"access"`

**Token 生成方法**：

```java
// 不含 role/tenant_id（向后兼容）
JwtTokenProvider.generateToken(String username, Duration expiry)

// 含 role 和 tenant_id
JwtTokenProvider.generateToken(String username, Role role, Integer tenantId, Duration expiry)
```

默认有效期：3600 秒（1 小时），通过 `aerofleet.security.jwt-expiry` 配置。

### 1.3 认证流程

**SecurityConfig**（`cloud-backend/src/main/java/io/aerofleet/cloud/security/SecurityConfig.java`）配置两种模式：

**开发模式**（`aerofleet.security.dev-mode=true`）：
- 禁用 CSRF
- 允许所有请求（`anyRequest().permitAll()`）
- 不强制 JWT 认证

**生产模式**（`aerofleet.security.dev-mode=false`）：
- 无状态会话（`SessionCreationPolicy.STATELESS`）
- `/api/v1/auth/**` 公开（登录注册端点）
- `/actuator/health*` 公开（健康检查）
- `/ws/**` 公开（WebSocket handshake 在 `TelemetryWebSocketHandler` 中认证）
- 其余 `/api/**` 需要认证
- 认证链顺序：`ApiKeyFilter` → `oauth2ResourceServer(JWT)` → `TenantFilter`

### 1.4 前端 Token 管理

前端 Token 管理在 `gcs-web/src/api.js` 中实现：

- Token 存储在 `sessionStorage`（key: `nexus_auth_token`），页面刷新后可恢复，关闭浏览器即清除
- `setAuthToken(token)` 验证 JWT 格式（三段 `header.payload.signature`）后才存储
- 每次请求自动添加 `Authorization: Bearer <token>` Header
- 401 响应自动清除 Token 并跳转登录页
- 请求超时通过 `AbortController` 实现（默认 15 秒）

## 2. 角色权限模型

### 2.1 Role 枚举

`Role`（`cloud-backend/src/main/java/io/aerofleet/cloud/security/Role.java`）定义三个角色：

| 角色 | ordinal | 权限范围 |
|---|---|---|
| `ADMIN` | 0 | 全部权限（用户管理、租户管理、配置、审计日志、任务、设备） |
| `OPERATOR` | 1 | 任务管理 + 设备控制（创建/取消任务、下发指令、编队） |
| `OBSERVER` | 2 | 只读（查询设备状态、遥测、任务列表） |

权限层级用 ordinal 表示：用户 ordinal <= 要求 ordinal 即有权限。ADMIN 可以访问所有标注了 `@RequireRole` 的端点。

### 2.2 @RequireRole 注解

`RequireRole`（`cloud-backend/src/main/java/io/aerofleet/cloud/security/RequireRole.java`）是方法级注解，声明接口方法所需的最小角色：

```java
@PostMapping("/tasks")
@RequireRole(Role.OPERATOR)
public Result createTask(@RequestBody TaskRequest req) { ... }
```

### 2.3 RoleInterceptor 拦截器

`RoleInterceptor`（`cloud-backend/src/main/java/io/aerofleet/cloud/security/RoleInterceptor.java`）在请求处理前拦截校验：

**跳过校验的条件**（任一满足即放行）：
- `aerofleet.security.dev-mode=true`（开发模式）
- `aerofleet.security.rbac-enabled=false`（**base 默认 true**；`application.properties:59`，
  dev profile 不设该键而靠 `dev-mode=true` 旁路，`application-test.properties:10` 显式 false，
  prod/staging 各自显式 true）
- 目标方法（或其所属类）显式标注了 `@PermitAll`
- 非控制器方法（静态资源等）

**默认拒绝（2026-10-01 翻转）**：方法与其类既没有 `@RequireRole` 也没有 `@PermitAll` 时返回
403，响应体 `{"error":"forbidden: endpoint has no role declaration"}`。
此前这里是"无注解即放行"（旧 `RoleInterceptor:78-80`），后果不是"某个端点忘了设角色"，
而是**每新增一个端点默认就没有鉴权、且运行时毫无信号**。翻转后：

| 项 | 数值（实测口径：反射枚举 `@RestController` 的映射方法） |
|---|---|
| 端点方法总数 | 342（190 GET / 126 POST / 13 PUT / 13 DELETE / 0 PATCH） |
| 翻转前已标注 | 70（21 ADMIN + 46 OPERATOR + 3 OBSERVER，全为方法级） |
| 翻转时未标注 | 272（180 GET + 92 写）——已全部收口 |
| 收口规则 | 读=类级 `@RequireRole(OBSERVER)`；写=方法级 `OPERATOR`，配置/用户/密钥/租户/围栏/模板/license 面=`ADMIN`；匿名入口只有 `AuthController#login`、`#refresh` 两处 `@PermitAll` |
| 声明优先级 | 方法级覆盖类级（两种注解同规则）；同一元素并存时 `@RequireRole` 胜出（收紧优先）。已由 `RoleInterceptorTest` 逐条钉住（16 例） |

两点容易被忽略的事实：
1. **角色层级是向上满足的**：`hasPermission = userRole.ordinal() <= requiredRole.ordinal()`
   且 `Role` 声明顺序为 ADMIN→OPERATOR→OBSERVER，所以 ADMIN 令牌能过任何门——翻转不会
   锁死管理员界面，受影响的是 OBSERVER/OPERATOR 的可达面。
2. **`@PermitAll` 只放开 RBAC，不放开认证**：生产模式下 Spring Security 仍是
   `anyRequest().authenticated()`（`SecurityConfig.java:76`），匿名请求先吃 401。
   所以 `@PermitAll` 的真实语义是"已认证的任意角色可用"。

覆盖率由 `RbacEndpointCoverageTest` 在单测里用反射逐个校验（有未声明端点即红并列出
`Controller#method [VERB path]`）。故意不用文本扫描：早前一版 awk 门禁会把方法签名里的
`@RequestBody` 当成注解行，把"未声明"从 272 少报成 99——**会静默少报的安全门禁比没有门禁更糟**。

**校验流程**：
1. 从 `Authorization: Bearer <token>` 提取 JWT
2. 解码 JWT，提取 `role` claim
3. 将 role 字符串解析为 `Role` 枚举（大小写不敏感）
4. 判断 `userRole.ordinal() <= requiredRole.ordinal()`
5. 校验失败返回 403：`{"error":"forbidden: requires role XXX"}`

### 2.4 角色分配决策树

基于 RBAC 注解批量回填经验（来源：2026-09-19-rbac-annotation-batch-backfill-role-decision-tree），角色分配遵循以下决策树：

| 操作语义 | 角色 | 示例端点 |
|---|---|---|
| 设备实时控制 | OPERATOR | `POST /api/v1/drones/{sysid}/commands`、`POST /api/v1/drones/{sysid}/joystick` |
| 任务管理 | OPERATOR | `POST /api/v1/spray/task`、`POST /api/v1/formation/create`、`POST /api/v1/delivery/...` |
| 应急操作 | OPERATOR | `POST /api/v1/emergency/...`、`POST /api/v1/autodispatch/trigger` |
| 系统配置 | ADMIN | `PUT /api/v1/sat-link/strategy`、`POST /api/v1/celltowers/{sysid}/config` |
| 用户/租户管理 | ADMIN | `POST /api/v1/auth/users`、`POST /api/v1/tenants` |
| 审计日志 | ADMIN | `GET /api/v1/audit/...` |
| 纯查询 GET | 不加注解 | `GET /api/v1/drones`、`GET /api/v1/telemetry/...` |

### 2.5 前端角色显示

前端根据 JWT 中的 `role` 字段控制 UI：

- `App.jsx` 中 `isAdmin = currentUser.role === 'ADMIN'`，ADMIN 可看到"租户管理"和"用户管理"面板
- 顶部导航栏显示角色中文名：ADMIN→管理员，OPERATOR→操作员，OBSERVER→观察者

## 3. API Key 管理机制

### 3.1 ApiKeyController

`ApiKeyController`（`cloud-backend/src/main/java/io/aerofleet/cloud/security/ApiKeyController.java`）提供三个端点：

| 端点 | 方法 | 功能 |
|---|---|---|
| `POST /api/v1/auth/api-key` | `createApiKey` | 生成 API Key（需 JWT 认证） |
| `DELETE /api/v1/auth/api-key/{keyId}` | `revokeApiKey` | 撤销 API Key（需 JWT 认证） |
| `GET /api/v1/auth/api-key` | `listApiKeys` | 列出当前用户的 API Key（脱敏显示） |

**API Key 格式**：`nsk_<32位随机hex>`（前缀 `nsk_` = NexusSky Key）

**安全设计**：
- 使用 `SecureRandom` 生成 32 字节随机数（64 hex 字符）
- 数据库中只存储 SHA-256 哈希（`keyHash`），不存储明文
- 明文 API Key 仅在创建时返回一次，后续不可查看
- 列表接口返回脱敏 Key（前4后4字符，中间 `****`）
- 默认有效期 365 天

### 3.2 ApiKeyFilter

`ApiKeyFilter`（`cloud-backend/src/main/java/io/aerofleet/cloud/security/ApiKeyFilter.java`）在 Spring Security 链中执行：

**认证流程**：
1. 从 `X-API-Key` Header 读取 API Key
2. 计算 SHA-256 哈希
3. 查询 `ApiKeyRepository.findByKeyHashAndRevokedFalse(keyHash)`
4. 检查是否过期
5. 设置 `SecurityContextHolder`（`ApiKeyAuthenticationToken`）
6. 设置 `TenantContext` 和 `ApiKeyContext`（ThreadLocal）
7. 异步更新 `lastUsedAt`

**行为规则**：
- 开发模式（`dev-mode=true`）：跳过
- 无 `X-API-Key` Header：跳过，交由 JWT 认证链处理
- API Key 无效：跳过，交由 JWT 认证链处理
- finally 块始终清理 `ApiKeyContext` 和 `TenantContext`，防止线程池复用导致上下文泄漏

### 3.3 ApiKeyEntity 数据模型

`ApiKeyEntity` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `keyId` | String | 展示标识（`nsk_<tenantId>_<8位hex>`） |
| `keyHash` | String | SHA-256 哈希（64 hex 字符） |
| `maskedKey` | String | 脱敏 Key（前4后4） |
| `tenantId` | Integer | 租户 ID |
| `userId` | Integer | 用户 ID |
| `name` | String | 用户自定义名称 |
| `scopes` | String | 权限范围（JSON 数组字符串） |
| `createdAt` | Instant | 创建时间 |
| `expiresAt` | Instant | 过期时间 |
| `lastUsedAt` | Instant | 最后使用时间 |
| `revoked` | boolean | 是否已撤销 |

## 4. 租户隔离机制

### 4.1 TenantContext

`TenantContext`（`cloud-backend/src/main/java/io/aerofleet/cloud/security/TenantContext.java`）基于 ThreadLocal 存储当前请求的租户 ID：

- `setTenantId(Integer tenantId)` — 设置租户 ID（null 表示全局管理员）
- `getTenantId()` — 获取租户 ID
- `getEffectiveTenantId()` — 获取有效租户 ID（优先 ApiKeyContext，降级 TenantContext）
- `clear()` — 清理 ThreadLocal（必须在请求结束时调用）

**有效租户 ID 查找顺序**：
1. `ApiKeyContext.getTenantId()` — API Key 认证时设置的租户 ID
2. `TenantContext.getTenantId()` — JWT 认证时由 TenantFilter 设置的租户 ID
3. 返回 null 表示全局管理员或未认证（不进行租户过滤）

### 4.2 TenantFilter

`TenantFilter` 在 Spring Security 链中 `UsernamePasswordAuthenticationFilter` 之后执行，从 JWT 提取 `tenant_id` claim 设置到 `TenantContext`。

### 4.3 TenantInterceptor

`TenantInterceptor`（`cloud-backend/src/main/java/io/aerofleet/cloud/tenant/TenantInterceptor.java`）实现多租户拦截和 API 限流：

**租户 ID 提取顺序**：
1. `X-Tenant-Id` Header
2. JWT subject（Authorization: Bearer token）
3. dev-mode 下返回 `"default"`
4. 其他情况返回 `"anonymous"`

**限流机制**：
- 优先使用 Redis 分布式限流（`RedisRateLimiter`，多实例共享计数）
- Redis 不可用时回退到内存滑动窗口限流（单机模式）
- 默认限制：每租户每分钟 100 次 API 调用（`aerofleet.tenant.rate-limit=100`）
- 超限返回 429：`{"code":429,"error":"Too Many Requests","message":"API 调用频率超限，请稍后重试"}`
- 内存限流窗口每 60 秒自动清理过期条目（`@Scheduled(fixedRate=60000)`）

### 4.4 租户隔离在业务层的应用

- **WebhookService**：注册时绑定当前租户 ID，查询和推送时仅访问当前租户的 webhook（`TenantContext.getEffectiveTenantId()`）
- **ApiKeyController**：列表查询时按 tenantId 或 userId 过滤
- **JPA 查询**：所有 Repository 的查询方法包含 `tenantId` 参数过滤

## 5. 加密方案

### 5.1 AES-GCM 加密（Webhook Secret）

`WebhookService`（`cloud-backend/src/main/java/io/aerofleet/cloud/webhook/WebhookService.java`）使用 AES-GCM 加密 Webhook 的 HMAC 签名密钥：

**加密配置**：
- 算法：`AES/GCM/NoPadding`
- GCM Tag 长度：128 位
- IV 长度：12 字节（随机生成）
- 密钥派生：从 `aerofleet.encryption.key` 配置值派生
- 存储格式：Base64(IV + ciphertext + GCM tag)

**加密流程**（`encryptSecret`）：
1. 随机生成 12 字节 IV
2. 使用派生密钥和 GCM 参数初始化 Cipher
3. 加密明文 secret
4. 将 IV 和密文拼接后 Base64 编码存储

**解密流程**（`decryptSecret`）：
1. Base64 解码，分离 IV（前 12 字节）和密文
2. 使用相同密钥和 IV 解密
3. 解密失败时返回原值（兼容旧明文数据）

### 5.2 AES-ECB 加密（安防设备密码）

`PasswordConverter`（`cloud-backend/src/main/java/io/aerofleet/cloud/surveillance/PasswordConverter.java`）是 JPA `AttributeConverter`，对 `SurveillanceDeviceEntity` 的 password 字段进行 AES 加密：

**加密配置**：
- 算法：`AES/ECB/PKCS5Padding`
- 密钥派生：SHA-256(配置密钥) 取前 16 字节（AES-128）
- 密钥来源：`aerofleet.encryption.key`（默认 `aerofleet-dev-encryption-key`）
- 存储格式：Base64(ciphertext)

**注意**：ECB 模式不推荐用于新开发（相同明文产生相同密文），但已用于安防设备密码的兼容性考虑。Webhook secret 使用更安全的 GCM 模式。

### 5.3 密钥配置

| 配置项 | 默认值 | 生产环境要求 |
|---|---|---|
| `aerofleet.encryption.key` | `aerofleet-dev-encryption-key` | 必须通过环境变量覆盖 |
| `aerofleet.security.jwt-secret` | 空 | HS256 模式下必须配置（≥32 字节） |
| `jwt.private-key` / `jwt.public-key` | 空 | RS256 生产模式必须配置 |
| `AEROFLEET_JWT_SECRET` | — | 生产环境环境变量 |

## 6. 安全加固清单

### 6.1 SSRF 防护

`WebhookService.validateWebhookUrl()` 实现 SSRF 防护：

- 必须以 `http://` 或 `https://` 开头
- 禁止 `localhost` 主机名
- 解析 host 为 IP 地址，检查是否为私有地址段：
  - `10.x.x.x`、`172.16-31.x.x`、`192.168.x.x`（siteLocalAddress）
  - `127.x.x.x`（loopbackAddress）
  - `169.254.x.x`（linkLocalAddress）
  - `0.0.0.0`（anyLocalAddress）

### 6.2 频率限制

| 维度 | 限制 | 配置 | 实现 |
|---|---|---|---|
| 租户 API 调用 | 100 次/分钟 | `aerofleet.tenant.rate-limit` | `TenantInterceptor` |
| 单 sysid MAVLink 帧数 | 100 帧/秒 | `aerofleet.udp.max-frame-rate-per-sysid` | `UdpGateway` |
| WebSocket 单 IP 连接数 | 10 | `aerofleet.ws.max-connections-per-ip` | `TelemetryWebSocketHandler` |
| WebSocket 单租户连接数 | 20 | `aerofleet.ws.max-connections-per-tenant` | `TelemetryWebSocketHandler` |

### 6.3 CORS 配置

| 环境 | 允许的源 | 配置 |
|---|---|---|
| 开发 | `http://localhost:5173,http://localhost:3000,http://localhost:8080` | `application-dev.properties` |
| 生产 | `https://aerofleet.io,https://gcs.aerofleet.io,https://admin.aerofleet.io` | `application-prod.properties` |

WebSocket CORS 通过 `WebSocketConfig.registerWebSocketHandlers()` 的 `setAllowedOrigins()` 配置。

### 6.4 UDP 网关安全

| 配置 | 开发环境 | 生产环境 | 说明 |
|---|---|---|---|
| `aerofleet.udp.bind-address` | `0.0.0.0` | 内网网卡 IP | 网卡绑定地址 |
| `aerofleet.udp.device-whitelist-enabled` | `false` | `true` | 只接受已注册 sysid 的帧 |
| `aerofleet.udp.max-frame-rate-per-sysid` | `100` | `100` | 单 sysid 每秒最大帧数 |

### 6.5 错误信息脱敏

`ApiExceptionHandler`（`cloud-backend/src/main/java/io/aerofleet/cloud/api/exception/ApiExceptionHandler.java`）：

- 生产模式（`dev-mode=false`）：500 错误只返回 `"internal server error"`，不泄露堆栈/类名
- 开发模式（`dev-mode=true`）：500 错误返回 `"internal error: <详细消息>"`，便于调试
- 完整异常堆栈始终记录到服务端日志

### 6.6 API Key 日志脱敏

`ApiKeyFilter.maskForLog()` 在日志中脱敏显示 API Key：仅保留前 8 和后 4 字符，中间用 `****` 替代。

### 6.7 RestTemplate 超时

`WebhookService` 的 RestTemplate 配置：
- 连接超时：5 秒（`factory.setConnectTimeout(5000)`）
- 读取超时：10 秒（`factory.setReadTimeout(10000)`）

防止目标 URL 响应缓慢时线程长时间阻塞。

### 6.8 HMAC 签名验证

Webhook 推送使用 HMAC-SHA256 签名：
- 签名密钥（secret）在存储前经过 AES-GCM 加密
- 推送时解密 secret，计算 HMAC-SHA256 签名
- 签名放入 `X-Webhook-Signature` Header
- 签名失败时跳过推送（不返回空签名），记录 WARN 日志

### 6.9 生产环境安全配置汇总

`application-prod.properties` 中的安全相关配置：

```properties
aerofleet.security.dev-mode=false          # 启用认证
aerofleet.security.rbac-enabled=true       # 启用 RBAC
aerofleet.security.jwt-secret=${AEROFLEET_JWT_SECRET}  # 环境变量注入
aerofleet.security.users=${AEROFLEET_USERS}            # 环境变量注入
aerofleet.license.enabled=true             # 启用 License 校验
aerofleet.audit.enabled=true               # 启用审计日志
aerofleet.udp.device-whitelist-enabled=true # 启用设备白名单
springdoc.swagger-ui.enabled=false         # 禁用 Swagger UI
management.endpoint.health.show-details=when-authorized  # 健康详情需认证
```

## 7. 链路签名（MAVLink v2 signing）

上行/下行 UDP 帧可附加官方 MAVLink v2 签名块，防止地面命令被注入或重放。

### 7.1 线上格式（与官方逐字节对等）

签名帧在 CRC 之后追加 **13 字节**：

| 偏移 | 字段 | 宽度 | 说明 |
|---|---|---|---|
| 0 | `link_id` | 1 B | 链路标识，多密钥库可按 sysid 配置 |
| 1 | `timestamp` | 6 B | **小端** 48 位，单位 10 微秒，纪元 2015-01-01T00:00:00Z |
| 7 | `signature` | 6 B | `SHA-256(secret_key ++ 帧头至CRC ++ link_id ++ timestamp)` 的前 6 字节（sha256_48） |

签名覆盖**含 STX 的整帧**（帧头至 CRC），口令以"前置后整体求摘要"的方式参与，
**不是 HMAC**——旧实现用 HMAC-SHA256 截 8 字节、时间戳按大端写入（签名块共 15 字节），
与 PX4/pymavlink 混流既验不过签名又会因帧长差 2 字节错帧。

### 7.2 重放规则（`TimestampTracker`）

- 流标识是三元组 `(link_id, system_id, component_id)`；只按 link_id 分桶会让共用同一
  linkId 的多台设备互相顶高基准，把正常流量误判为重放。
- 已见过的流：`timestamp` 必须**严格大于**上次值，相等即拒（旧实现 `>=` 放行相等值，
  等于允许原帧重放一次）。
- 新出现的流：允许落后本地时间最多 **60 秒** = 6,000,000 tick（旧实现阈值 500 tick 且
  时间戳单位口径也不对）。

### 7.3 开关与装配

`mavlink.signing.enabled`（默认 false）、`secret-key`、`key-store-path`、
`reject-unsigned`（默认 true，fail-closed）。

装配曾长期是断的：`UdpGateway` 的四个签名注入点都是 `@Autowired(required=false)`，
而 `CloudBackendApplication` 是不带 `scanBasePackages` 的裸 `@SpringBootApplication`
（只扫 `io.aerofleet.cloud`），签名类却在 `io.aerofleet.mavlink.security` 包里，
mavlink-core 也没有自动配置文件 → 四个字段恒为 null，开关打开也不签名，连配置类的
启动校验都不执行。现由 `cloud-backend` 的 `MavlinkSigningConfiguration`
（`@ConditionalOnProperty(mavlink.signing.enabled=true)`）按开关条件装配：关着时不创建
任何签名 bean（出厂行为不变），打开后缺口令会在启动阶段直接失败而不是静默明文。
`MavlinkSigningConfigurationTest` 用 `ApplicationContextRunner` 双向断言这两点。

### 7.4 互通性怎么证明的

不靠"自己签自己验"。`scripts/mavlink-signing-vectors.py` 用独立参考实现 **pymavlink**
打包并签名 5 条固定输入的消息（HEARTBEAT、GLOBAL_POSITION_INT、尾零裁剪的 HEARTBEAT、
奇数长度 payload 的 RADIO_STATUS、含负浮点的 ATTITUDE），把完整帧 hex 与期望签名作为
**已知答案向量**写进 `MavlinkSigningVectorTest`：Java 侧算出的 6 字节签名必须逐字节相同，
解码 pymavlink 帧再重编码必须逐字节还原。向量生成器自身还做了两道自检（pymavlink 能验过
自己的帧 + 按规范公式独立重算签名）。

字节层之外还有 `scripts/e2e-signing.ps1` 的 6 个场景（本机 43 条断言全绿，2026-10-01）：
正常签名通信、篡改检测、未签名拒绝、密钥不匹配、多机密钥分发、签名未启用兼容。
其中"篡改"与"未签名"这两条以前从未真正端到端成立过——link-sim 的损伤画像改的是载荷、
且剥签名后不重算 CRC，帧在解析阶段就因 CRC 失败被丢掉，压根到不了验签。现在断言用的是
backend 日志里真实出现过的那一行，例如
`拒绝未签名帧：sysid=1 msgId=242 (rejectUnsigned=true)` 与
`签名验证失败：sysid=1 linkId=1 msgId=33`，配对 link-sim 侧的 `stripped=3` / `tampered=3`。

### 7.5 已知边界

- 出厂仍是明文：任何 profile 都未设置 `mavlink.signing.enabled=true`。
- 口令与密钥库都是**明文**：`secret-key` 写在配置里（走命令行会进进程列表），
  `key-store-path` 指向的 JSON 不做加密，也没有轮换端点。
- 多机模式（`key-store-path`）现在确实按 sysid 取口令（`MavlinkSignerFactory` →
  `SigningKeyManager.keyFor()`），但**未命中该 sysid 时会回退 `defaultKey`**；
  要"只认密钥库里列出的机子"，必须显式不配 `defaultKey`，否则陌生 sysid 的签名帧
  会被拿去和 `defaultKey` 比对（比对失败仍拒，但这不是一次成员白名单检查）。
- `MavlinkParser` 层只切帧、不验签；验签发生在 `MavlinkMessage.decode(frame, signer)`
  与 `UdpGateway.verifyFrame`。
- 时间戳由毫秒时钟换算，粒度是 1 毫秒（100 tick）；同一毫秒内连发多帧需要调用方自行递增。
- 重放与时间戳回退只有单元测试证据：link-sim 的损伤画像会篡改签名字节、剥离签名块，
  但不会转发一条已签名的原帧来触发重放分支。
- 签名统计（`signing: verified=/rejected=/unsigned=`）只进日志，既无指标也无告警。
