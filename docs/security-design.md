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
- `aerofleet.security.rbac-enabled=false`（默认关闭）
- 目标方法未标注 `@RequireRole`
- 非控制器方法（静态资源等）

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