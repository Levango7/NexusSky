# NexusSky 开发者贡献指南

## 1. 项目模块结构

NexusSky 是一个多模块 Maven 项目，根 POM 定义了 5 个子模块：

| 模块 | artifactId | 技术栈 | 职责 |
|---|---|---|---|
| `mavlink-core` | `aerofleet-mavlink-core` | 纯 Java 17 | MAVLink v1/v2 二进制协议栈（帧/CRC/消息编解码/UDP 传输，标准消息 + M0a–M9 扩展消息 420–479） |
| `drone-sim` | `aerofleet-drone-sim` | 纯 Java 17 | 虚拟四轴无人机模拟器：任务上传、ARM/起飞/航点飞行/RTL 状态机、遥测广播、故障注入、物理引擎 v2 |
| `link-sim` | `aerofleet-link-sim` | 纯 Java 17 | MAVLink/UDP 链路损伤代理：延迟、丢包（Gilbert-Elliot 模型）、带宽限制、分区模拟 |
| `cloud-backend` | `aerofleet-cloud-backend` | Spring Boot 3.5 | MAVLink 设备网关、REST API（155+ 端点）、WebSocket 推送、JWT 安全认证、多租户隔离 |
| `sdk-java` | `aerofleet-sdk-java` | 纯 Java 17 | Java SDK 客户端：封装 REST API 调用，提供 DroneApi/MissionApi/FlightLogApi |

前端模块 `gcs-web` 独立于 Maven 构建，使用 npm/Vite：

| 模块 | 技术栈 | 职责 |
|---|---|---|
| `gcs-web` | React 18 + MapLibre + Three.js | Web 地面站：实时地图轨迹、飞行仪表 HUD、任务规划、39 个功能面板 |

### 模块依赖关系

```
mavlink-core ← drone-sim ← cloud-backend
mavlink-core ← cloud-backend
drone-sim ← cloud-backend (复用 TrajectoryPredictor)
mavlink-core ← sdk-java
```

## 2. 构建和测试命令

### 2.1 Java 后端构建

```cmd
:: 全量构建（含测试）
mvn clean package

:: 跳过测试快速构建
mvn -DskipTests package

:: 仅构建特定模块
mvn -pl cloud-backend -am package

:: 运行单元测试
mvn test

:: 运行特定模块的测试
mvn -pl mavlink-core test

:: 生成测试覆盖率报告（JaCoCo）
mvn -pl cloud-backend verify
:: 报告位于 cloud-backend/target/site/jacoco/index.html
```

覆盖率要求：`cloud-backend` 行覆盖率最低 50%（`jacoco-maven-plugin` 配置）。

### 2.2 前端构建

```cmd
cd gcs-web

:: 安装依赖
npm install

:: 开发模式启动（Vite dev server，端口 5173）
npm run dev

:: 生产构建
npm run build

:: 预览生产构建
npm run preview
```

前端语法检查（沙箱环境无法运行 vite build 时）：

```cmd
node gcs-web\scripts\check-frontend.cjs
```

该脚本使用 `@babel/parser` 进行全量语法检查和 import 图校验。

### 2.3 一键启动

```cmd
:: Windows：构建并启动模拟器 + 后端
scripts\start-all.cmd

:: 前端（新窗口）
cd gcs-web && npm run dev
```

### 2.4 冒烟测试

```powershell
:: 完整链路冒烟测试
powershell -ExecutionPolicy Bypass -File scripts\e2e-smoke.ps1

:: 网络仿真回归
powershell -ExecutionPolicy Bypass -File scripts\e2e-network.ps1

:: 故障注入回归
powershell -ExecutionPolicy Bypass -File scripts\e2e-fault.ps1

:: Failsafe 回归
powershell -ExecutionPolicy Bypass -File scripts\e2e-failsafe.ps1
```

Linux 版本：`scripts/e2e-smoke.sh`、`scripts/e2e-fault-linux.sh` 等。

### 2.5 Docker 构建

```bash
mvn -DskipTests package
docker compose up -d
bash scripts/e2e-smoke.sh
```

注意：docker-compose 仅在 Linux 上可运行（MAVLink UDP 需要 host 网络模式）。

## 3. 代码提交规范

### 3.1 Conventional Commits 格式

所有提交消息必须遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**type 取值**：

| type | 含义 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat(spray): 新增喷洒任务创建端点` |
| `fix` | Bug 修复 | `fix(websocket): 修复断线重连后遥测不更新` |
| `refactor` | 重构（不改行为） | `refactor(api): 提取 jsonFetch 公共函数` |
| `docs` | 文档变更 | `docs: 更新故障排查指南` |
| `test` | 测试相关 | `test(mavlink): 新增 M9 扩展消息 CRC 测试` |
| `chore` | 构建/工具变更 | `chore: 升级 Spring Boot 到 3.5.16` |
| `perf` | 性能优化 | `perf(telemetry): 遥测推送频率从 2Hz 优化为 1Hz` |
| `ci` | CI 配置 | `ci: 添加 GitHub Actions 工作流` |

**scope 取值**（对应模块或功能领域）：

`mavlink`、`drone-sim`、`link-sim`、`cloud-backend`、`sdk-java`、`gcs-web`、`security`、`tenant`、`webhook`、`spray`、`formation`、`delivery`、`surveillance`、`emergency`、`vision`、`terrain`、`mesh`、`satlink`、`celltower`、`api`、`websocket`

### 3.2 提交示例

```
feat(security): 实现 API Key 认证过滤器

- ApiKeyFilter 从 X-API-Key Header 读取 API Key
- SHA-256 哈希后查询数据库验证有效性
- 设置 SecurityContext + TenantContext + ApiKeyContext
- finally 块清理 ThreadLocal 防止上下文泄漏

Closes #123
```

## 4. 分支策略

### 4.1 分支模型

| 分支 | 用途 | 命名规则 | 保护规则 |
|---|---|---|---|
| `main` | 生产发布分支 | — | 禁止直接推送，仅通过 PR 合入 |
| `develop` | 开发集成分支 | — | 允许开发者推送，PR 合入前需通过 CI |
| `feature/*` | 功能开发分支 | `feature/<简短描述>` | 从 `develop` 拉出，完成后 PR 到 `develop` |
| `hotfix/*` | 紧急修复分支 | `hotfix/<简短描述>` | 从 `main` 拉出，修复后 PR 到 `main` 和 `develop` |
| `release/*` | 发布准备分支 | `release/<版本号>` | 从 `develop` 拉出，仅做 bug 修复，合入 `main` |

### 4.2 分支操作示例

```bash
:: 创建功能分支
git checkout develop
git pull origin develop
git checkout -b feature/spray-task-persistence

:: 开发完成后提交
git add .
git commit -m "feat(spray): 实现喷洒任务持久化到 JPA"

:: 推送并创建 PR
git push origin feature/spray-task-persistence
:: 然后在 GitHub/GitLab 上创建 PR 到 develop
```

## 5. PR 流程和模板

### 5.1 PR 创建流程

1. 从 `develop` 拉出功能分支
2. 开发并确保本地测试通过
3. 推送分支并创建 PR 到 `develop`
4. PR 标题遵循 Conventional Commits 格式
5. 等待 CI 通过和代码审查
6. 审查通过后合入

### 5.2 PR 模板

```markdown
## 变更描述

[简要描述本 PR 做了什么以及为什么]

## 变更类型

- [ ] 新功能 (feat)
- [ ] Bug 修复 (fix)
- [ ] 重构 (refactor)
- [ ] 文档 (docs)
- [ ] 测试 (test)
- [ ] 构建/工具 (chore)

## 影响范围

- [ ] mavlink-core
- [ ] drone-sim
- [ ] link-sim
- [ ] cloud-backend
- [ ] sdk-java
- [ ] gcs-web

## 测试

- [ ] 单元测试通过 (`mvn test`)
- [ ] 冒烟测试通过 (`scripts/e2e-smoke.ps1`)
- [ ] 手动验证：[描述验证步骤]

## 检查清单

- [ ] 代码遵循项目风格（无注释除非必要）
- [ ] 无硬编码密钥/密码
- [ ] 新增 API 端点有 `@RequireRole` 注解（如适用）
- [ ] 新增配置项有默认值
- [ ] 日志级别合理（DEBUG 用于开发，INFO/WARN 用于生产）
```

## 6. 代码审查清单

### 6.1 安全审查

- [ ] 新增 API 端点是否需要 `@RequireRole` 注解？参考角色决策树：设备控制→OPERATOR，系统配置→ADMIN，纯查询→不加注解
- [ ] 是否引入硬编码密钥、密码或 token？
- [ ] SQL 查询是否使用参数化（防注入）？
- [ ] Webhook URL 是否通过 SSRF 校验？
- [ ] 新增 ThreadLocal 使用是否在 finally 块中清理？
- [ ] 敏感数据（密码、secret）是否加密存储？

### 6.2 功能审查

- [ ] 新增功能是否与现有模块职责一致？
- [ ] 是否破坏向后兼容性？
- [ ] 错误处理是否使用 `ApiExceptionHandler` 统一格式？
- [ ] 新增配置项是否在 `application.properties` 中有默认值？
- [ ] 新增端点是否在 `api.js` 中添加对应调用？

### 6.3 前端审查

- [ ] 组件命名是否遵循 PascalCase（如 `SprayPanel.jsx`）？
- [ ] 新增面板是否在 `VIEW_PANEL_MAP` 中注册？
- [ ] WebSocket 消息类型是否在 `useWebSocket.js` 中处理？
- [ ] API 调用是否通过 `api.js` 的 `jsonFetch` 函数？
- [ ] 是否正确处理 401 自动跳转登录？
- [ ] useEffect cleanup 是否完整（防止内存泄漏）？

### 6.4 测试审查

- [ ] 新增功能是否有对应单元测试？
- [ ] 测试是否使用 `spring.profiles.active=test`？
- [ ] 覆盖率是否满足 50% 最低要求？
- [ ] 是否测试了异常路径和边界条件？

## 7. 开发环境配置

### 7.1 JDK 要求

- JDK 17（`maven.compiler.release=17`）
- 环境变量 `JAVA_HOME` 指向 JDK17 安装目录

### 7.2 Maven 要求

- Maven 3.9+（项目使用 `spring-boot-dependencies` BOM 管理）

### 7.3 Node.js 要求

- Node.js 20+（docker-compose 使用 `node:20-alpine`）

### 7.4 IDE 配置

- 编译器参数名保留：`<parameters>true</parameters>`（Spring 6 反射需要）
- 文件编码：UTF-8（`project.build.sourceEncoding=UTF-8`）

### 7.5 开发模式启动

开发模式（`aerofleet.security.dev-mode=true`）下：
- 禁用 JWT 认证，所有请求放行
- 禁用 RBAC 角色校验
- 使用 H2 内存数据库
- 禁用 Redis，使用 Simple 缓存
- 启用 Swagger UI（`http://localhost:8080/swagger-ui.html`）
- 启用 H2 控制台（`http://localhost:8080/h2-console`）
- 日志级别 DEBUG

```cmd
:: 开发模式启动后端
java -jar cloud-backend/target/aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

## 8. 项目约定

### 8.1 代码风格

- **禁止添加注释**，除非用户明确要求
- 包名前缀：`io.aerofleet`
- 类名 PascalCase，方法名 camelCase
- 常量 UPPER_SNAKE_CASE
- 日志使用 SLF4J（`org.slf4j.Logger`）

### 8.2 API 设计约定

- REST API 路径前缀：`/api/v1/`
- 错误响应统一格式：`{"error": "..."}`
- 分页参数：`offset` + `limit`
- 时间格式：ISO-8601（`Instant.toString()`）

### 8.3 前端约定

- 组件文件：`gcs-web/src/components/<Name>Panel.jsx`
- Hook 文件：`gcs-web/src/hooks/use<Name>.js`
- 工具函数：`gcs-web/src/utils/<name>Utils.js`
- API 调用统一通过 `gcs-web/src/api.js` 的 `api` 对象
- Token 存储在 `sessionStorage`（关闭浏览器即清除）

### 8.4 数据库迁移

- 使用 Flyway 管理数据库 schema
- 迁移脚本位于 `cloud-backend/src/main/resources/db/migration/`
- 命名规则：`V<序号>__<描述>.sql`（如 `V17__webhook.sql`）
- 已有 schema 建立基线：`spring.flyway.baseline-on-migrate=true`