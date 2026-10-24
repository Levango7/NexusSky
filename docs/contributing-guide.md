# NexusSky 开发者贡献指南

## 1. 项目模块结构

NexusSky 是一个多模块 Maven 项目，根 POM 定义了 5 个子模块：

| 模块 | artifactId | 技术栈 | 职责 |
|---|---|---|---|
| `mavlink-core` | `aerofleet-mavlink-core` | 纯 Java 17 | MAVLink v1/v2 二进制协议栈（帧/CRC/消息编解码/UDP 传输，标准消息 + M0a–P2 扩展消息 30000–30063） |
| `drone-sim` | `aerofleet-drone-sim` | 纯 Java 17 | 虚拟四轴无人机模拟器：任务上传、ARM/起飞/航点飞行/RTL 状态机、遥测广播、故障注入、物理引擎 v2 |
| `link-sim` | `aerofleet-link-sim` | 纯 Java 17 | MAVLink/UDP 链路损伤代理：延迟、丢包（Gilbert-Elliot 模型）、带宽限制、分区模拟 |
| `cloud-backend` | `aerofleet-cloud-backend` | Spring Boot 3.5 | MAVLink 设备网关、REST API（410 端点）、WebSocket 推送、JWT 安全认证、多租户隔离 |
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
mvn -pl cloud-backend -am verify
:: 报告位于 cloud-backend/target/site/jacoco/index.html
```

> **为什么必须带 `-am`**（maven-incantations:ok 本行是解释而非可执行命令）：本仓是多模块 reactor。`mvn -pl cloud-backend` 不带
> `-am` 时，Maven 不会在 reactor 内重建 `mavlink-core`/`drone-sim`，而是去本地
> 仓库（`~/.m2/repository`）找。若那里的 SNAPSHOT jar 是旧的，**测试会拿旧字节码
> 跑并假失败**——你看到的失败不在你的改动里。本仓曾因此误判两轮（详见
> `CHANGELOG.md` 排查备注）。一律用 `-am`，或先跑 `mvn install -DskipTests`。
> 门禁 `scripts/check-maven-incantations.py` 会检查文档/脚本里的裸 `-pl`。

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

### 2.5 e2e 脚本台账（2026-10-20 按 CI 实测刷新）

**全部 PowerShell 脚本（18 个）都已接入 CI**，分五个 job：

| job | 脚本 | 断言（CI 实测） |
|---|---|---|
| `e2e-core-scripts` | `e2e-formation.ps1` / `e2e-mesh.ps1 -Mode simplified` / `e2e-rid.ps1` | 24 / 12 / 14 |
| `e2e-signing` | `e2e-signing.ps1`（6 场景） | 6 场景 |
| `e2e-b1-scripts` | `e2e-regulator.ps1` / `e2e-dock.ps1` / `e2e-route-templates.ps1` / `e2e-defect.ps1` | 37 / 23 / 16 / 18 |
| `e2e-b2-a-type` | `e2e-cv-eval.ps1` / `e2e-operations.ps1` / `e2e-rtk.ps1` / `e2e-sensing.ps1` / `e2e-uspace.ps1` / `e2e-roc.ps1` / `e2e-spray.ps1` | 12 / 9 / 3 / 10 / 13 / 14 / 30 |
| `e2e-b2-b-type` | `e2e-network.ps1` / `e2e-rf.ps1` / `e2e-vision-pixels.ps1` | 9 / 4 / 6 |
| `e2e-mjpeg` | `scripts/e2e-mjpeg-ci.sh` → `scripts/e2e-mjpeg-preview.py`（python） | 23 |

另有 bash 版：`e2e-smoke.sh`、`e2e-fault-linux.sh`、`e2e-failsafe-linux.sh`、
`e2e-vision-linux.sh`、`e2e-spray.sh`、`e2e-emergency.sh`、`e2e-hardware.sh`、
`e2e-sdk-integration.sh`、`e2e-docker-compose.sh`。

**`e2e-mjpeg` 为什么要单独立一个 job**（2026-10-21 新增，硬门禁）：
MJPEG 取流链路此前**从来没在 CI 里跑过** —— 它需要一台真摄像头，GitHub runner 没有。
而它里面有一条硬断言「匿名取流必须被 401 拒掉」，那条断言**只在非 dev 配置下成立**：

- `aerofleet.security.dev-mode=true` 时，Spring Security 链是 `anyRequest().permitAll()`，
  **且** `StreamTokenFilter` 直接跳过（见 `StreamTokenFilter` 第 82 行的 dev 分支）
  ⇒ 匿名访问 `/mjpeg` 会拿到 **200 和真实帧流**；
- 这是 dev 模式的**既定行为**（不是漏洞），但它让那条安全断言失去意义 ——
  仓库里启动后端的标准姿势（CI 的 `sdk-integration` job、多数 e2e 脚本）恰恰是 dev profile。

所以 `scripts/e2e-mjpeg-ci.sh` 把前置条件固化成可复现的配方：
**dev 起一次建 ADMIN 用户 → 优雅停（让 H2 落盘）→ 以 `--aerofleet.security.dev-mode=false`
重启 → 起合成帧源 → 跑链路**。合成帧源（`scripts/synthetic-frame-source.py`）吐的是
仓库里 3 张**真 JPEG** 夹具并轮换，所以「每帧都是真 JPEG」「帧内容在变」在 CI 里仍有意义。

> 登录那条路也有个坑：`aerofleet.security.users`（内存用户）**只在没有 UserRepository 时生效**，
> 有数据库时登录只查库、查不到直接 401 —— 所以必须先在库里建号。

**五个 e2e job 均为硬门禁**（2026-10-20 起）：2026-10-14 起它们先以
`continue-on-error`（观察模式）接入收集 flaky 数据，零红若干次后删掉该标记转正。
断言聚合步骤会在统计到任何 `[FAIL]` 时**直接判红**（不是只警告），
以防"脚本印了红字却忘了 exit 1"时 job 绿着通过。

**唯一未跑通**：`e2e-px4-sitl.ps1`——缺 PX4 SITL 环境（脚本现在会明确报"前置条件缺失"）。
装好后照 `docs/sitl-integration.md`。

**各脚本的跑法要点**（断言数以 CI 实测为准，本机跑法可能有差异）：

| 脚本 | 断言 | 跑法要点 |
|---|---|---|
| `e2e-cv-eval.ps1` | 12 | 自起后端 + drone-sim（真值 HTTP 18080） |
| `e2e-defect.ps1` | 18 | 自起后端 + drone-sim |
| `e2e-dock.ps1` | 23 | 自起后端 + drone-sim + 机巢 sim |
| `e2e-operations.ps1` | 9 | 自起后端 + drone-sim（报表对账） |
| `e2e-roc.ps1` | 14 | 自起后端 + **2 台** drone-sim（双机席位） |
| `e2e-route-templates.ps1` | 16 | 航线模板真飞 |
| `e2e-rtk.ps1` | 3 | 三机 RTK 三态 |
| `e2e-sensing.ps1` | 10 | 感知聚合 |
| `e2e-uspace.ps1` | 13 | U-space 四服务 |
| `e2e-network.ps1` | 9 | **需先起后端且 `--aerofleet.drone-port=14600`**，再 `AF_BACKEND_PORT=<口>` 跑脚本 |
| `e2e-rf.ps1` | 4 | 需先起后端（sim 在 14543，默认 extra-ports 覆盖） |
| `e2e-vision-pixels.ps1` | 6 | 需先起后端带 `--aerofleet.vision.source=pixels` + `--aerofleet.sim-truth-base=http://127.0.0.1:18082` |

**跨平台改造复用 `scripts/e2e-common.ps1`**（新脚本照抄，别再各改各的）：
`Get-E2eJar`（Linux 上反斜杠不是分隔符，`Join-Path $Root 'x\target\y.jar'` 会拼出
不存在的路径）、`Get-E2eJava`（Linux 叫 `java`）、`Stop-E2eStaleProcesses`（Windows
CIM / Linux 读 `/proc/*/cmdline`；**别用 `& ps -eo`**——`ps` 是 Get-Process 的别名，
`-eo` 直接报错）、`Get-E2eTempDir`（Linux 上 `$env:TEMP` 常未设置）。

**所有自起后端的脚本都已遵守的四条约定**（新脚本照抄，否则本机跑不起来）：

1. `AF_BACKEND_PORT` 覆盖 REST 端口——本机 8080 可能被其他服务（如 opsmesh 容器）长期占用；
2. 显式 `--spring.profiles.active=dev`——不指定会落 prod 连 localhost:5432，启动即挂；
3. 就绪判据用业务端点 `/api/v1/drones`（只读且恒 200）而非 `actuator/health`——本机 Redis
   6379 要 AUTH 或不通时，`RedisHealthContributor` 把 health 打成 503，会把早就起来的后端误杀；
4. 自带内存库 `--spring.datasource.url=jdbc:h2:mem:<脚本名>;DB_CLOSE_DELAY=-1`——dev profile
   默认落 `./data/aerofleet` 文件库，上一轮 e2e 留下的编队会被 `restoreFormations` 复活，
   旧编队的 Keeper 把同一批 sysid 的飞机往旧队形拽，新编队的到位判定永远失败。

跑之前先 `powershell -ExecutionPolicy Bypass -File scripts\kill-stale-e2e-jobs.ps1`
清残留模拟器（UDP 口被占会让后端 BindException 起不来）。

### 2.6 Docker 构建

```bash
mvn -DskipTests package
docker compose up -d
bash scripts/e2e-smoke.sh
```

注意：docker-compose 仅在 Linux 上可运行（MAVLink UDP 需要 host 网络模式）。

### 2.7 行尾（CRLF/LF）与本仓的现状

**结论：CI 不受影响，本地看到 CRLF 属正常。**

本仓**没有** `.gitattributes`，而多数 Windows 开发机的 `core.autocrlf=true`。其行为是：

| 阶段 | 效果 |
|---|---|
| 提交时 | CRLF → LF（**归一化进 git 索引**） |
| 检出时 | LF → CRLF（仅本地工作区） |

实测确认：`git show :scripts/ci-integration-test.sh` 等文件的**索引内容均为 LF**，
所以 Linux CI 检出后执行不会遇到 `bad interpreter: /bin/bash^M`。
本地 `ls` 看到 CRLF 只是检出形态。

> **为什么不在本仓加 `.gitattributes`**：那会一次性改写全仓 1,281 个文件的
> 行尾归一化行为，需要独立验证（尤其 `jitpack.yml` 历史上踩过 BOM+行尾坑，
> 见 commit `1e6095c`），不宜混在功能提交里做。若后续要做，应单独开一轮。

**注意事项**：
- 新增 `.sh` / `.py` 脚本时，**保持 LF**（编辑器设为 LF，不要 CRLF）。
  Python 对 CRLF 容忍度较高，但 shell 脚本在容器/Linux 里执行时 `\r` 会导致
  参数解析异常（如变量值末尾多一个 `\r`）。
- 若本机脚本莫名报 `$'\r': command not found`，用
  `git config --local core.autocrlf false` 后重新检出该文件，而不是手改行尾。

### 2.8 变异测试门禁（测"盯住了没有"，不只测"跑到过没有"）

常规覆盖率（jacoco，阈值 0.65）回答的是「**这段代码被跑到过没有**」，
回答不了「**这段代码的行为被钉住了没有**」——
一段被调用一百次、却只断言「没抛异常」的代码，覆盖率是满的，
但把它的行为改掉（返回 null、把 `>=` 改成 `>`、删掉一次加密调用），
**没有任何测试会红**。这就是"假绿测试"的一种形态。

**变异测试**换了个问法：把生产代码按规则改坏，看**有没有测试变红**。

```bash
# 跑变异测试（profile 默认不启用，只有显式 -Pmutation 才跑）
# 刻意**不加 -am**：profile 里的 targetClasses 指向 io.aerofleet.cloud.security.*，
# 带 -am 会让 PIT 在上游模块（mavlink-core 等）上各跑一遍，而那边没有匹配的类
# ⇒ 插件直接报"没找到变异体"把构建打断。
mvn -B -pl cloud-backend -Pmutation test-compile pitest:mutationCoverage  # maven-incantations:ok 只跑 cloud-backend 一个模块，理由见上一行

# 对基线判红（变异分数是棘轮，不许低于基线）
python scripts/check-mutation-baseline.py \
  --report cloud-backend/target/pit-reports/mutations.xml
```

CI 里对应 job `mutation-gate`（**硬门禁**）。基线在
`scripts/mutation-baseline.json`；报告缺失/损坏/一个变异体都没有 ⇒ **判红**
（跳过不等于通过）。

细节与 2026-10-21 那轮审计的发现见 [`docs/mutation-testing.md`](mutation-testing.md)。

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

> **"CI 没跑"先查 mergeability，别先怀疑 GitHub**：PR 一旦与目标分支冲突
> （`mergeable=CONFLICTING` / `mergeStateStatus=DIRTY`），GitHub 就**合成不出**
> `refs/pull/<N>/merge`，`pull_request` 流水线**根本不会被调度**——症状是零 run、
> 零 check suite、零失败记录（第三方 App 的 check suite 反而照常出现，最容易被误判成平台故障）。
> 判读四步与修复办法见 [`COORDINATION.md` 第六节](../COORDINATION.md#ci-no-schedule)。
> 合并方式被拒（`--merge` 不允许）时改试 `--squash`，但**被拒不等于已合并**，合并后要回查 master tip。

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