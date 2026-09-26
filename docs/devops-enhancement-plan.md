# NexusSky 部署运维强化调研报告

> **版本**：v1.0 | **日期**：2026-09-22 | **作者**：DevOps 架构师调研  
> **范围**：Docker/K8s 配置审计 + CI/CD 现状分析 + 监控告警方案  
> **性质**：只读分析，不修改代码

---

## 一、Docker/K8s 配置审计

### 1.1 Dockerfile 审计

| Dockerfile | 多阶段构建 | HEALTHCHECK | JVM 参数 | 非 Root 用户 | 评级 |
|---|---|---|---|---|---|
| `Dockerfile.cloud` | ✅ JDK→JRE | ✅ `/actuator/health` | ✅ G1GC + MaxRAMPercentage=75 | ❌ | **B+** |
| `Dockerfile.sim` | ✅ JDK→JRE | ❌ 无（UDP 服务无 HTTP 端点） | ✅ G1GC + MaxRAMPercentage=75 | ❌ | **B-** |
| `Dockerfile.web` | ✅ Node→Nginx | ✅ `wget localhost` | N/A | ❌ | **B** |

**已具备的良好实践**：
- 三个 Dockerfile 均采用多阶段构建，构建产物与运行时分离
- 利用层缓存（先拷 pom/lockfile，再拷源码）
- BuildKit 缓存挂载（`--mount=type=cache,target=/root/.m2`）
- cloud-backend 有 HEALTHCHECK 指令，与 K8s probe 端点统一
- `.dockerignore` 配置完善，排除敏感文件和无关目录

**缺失项与风险**：

| # | 缺失项 | 风险等级 | 说明 |
|---|---|---|---|
| D1 | **非 Root 用户** | 🔴 高 | 所有镜像以 root 运行，违反容器安全最佳实践。应添加 `RUN useradd -r appuser && USER appuser` |
| D2 | **drone-sim 无 HEALTHCHECK** | 🟡 中 | UDP 服务无 HTTP 端点，但可通过 TCP 探活或自定义脚本检查进程存活 |
| D3 | **link-sim 无 Dockerfile** | 🟡 中 | link-sim 模块在 CI 矩阵中构建，但无容器化配置，无法独立部署 |
| D4 | **镜像无标签固定** | 🟡 中 | 基础镜像 `eclipse-temurin:17-jre` 未使用 digest 锁定（`@sha256:...`），存在供应链风险 |
| D5 | **无镜像签名验证** | 🟡 中 | 缺少 Cosign/Notary 签名，镜像完整性无法验证 |
| D6 | **curl 残留在运行时镜像** | 🟢 低 | Dockerfile.cloud 在运行时镜像安装 curl 仅用于 healthcheck，增加攻击面。可改用 `wget`（alpine 自带）或 Java-based healthcheck |

### 1.2 docker-compose 审计

**两套 compose 文件对比**：

| 维度 | 根 `docker-compose.yml`（开发） | `deploy/docker/docker-compose.yml`（生产） |
|---|---|---|
| 网络 | host 网络 | 桥接网络（默认） |
| 镜像 | 基础镜像 + 挂载 jar | 多阶段构建镜像 |
| 健康检查 | ❌ 无 | ✅ cloud-backend 有 |
| restart 策略 | ❌ 无 | ✅ `unless-stopped` |
| 资源限制 | ❌ 无 | ❌ 无 |
| 日志驱动 | ❌ 默认 | ❌ 默认 |
| 依赖编排 | ✅ depends_on | ✅ depends_on + condition: service_healthy |

**缺失项**：

| # | 缺失项 | 风险等级 | 说明 |
|---|---|---|---|
| C1 | **资源限制** | 🔴 高 | 生产 compose 未设置 `deploy.resources.limits`，单容器可耗尽宿主机资源 |
| C2 | **日志驱动配置** | 🟡 中 | 未配置 `logging.driver: json-file` + `max-size/max-file`，日志可撑满磁盘 |
| C3 | **自定义网络** | 🟡 中 | 未定义命名网络，使用默认 bridge，隔离性不足 |
| C4 | **开发 compose 无健康检查** | 🟢 低 | 开发用 compose 可接受，但影响开发体验（无法 `depends_on: condition: service_healthy`） |

### 1.3 Kubernetes 配置审计

**清单完整性**：

| 文件 | 资源类型 | 评级 | 说明 |
|---|---|---|---|
| `namespace.yaml` | Namespace | ✅ | 带标准 labels |
| `configmap.yaml` | ConfigMap | ⚠️ | H2 内存库不适合生产；注释提示需替换外部 DB |
| `secret.yaml` | Secret | ⚠️ | 使用 `stringData` 明文，注释提示改用 SealedSecret/Vault |
| `cloud-backend.yaml` | Deployment + Service | ✅ | 三种 probe 齐全，资源限制合理，RollingUpdate 策略 |
| `drone-sim.yaml` | Deployment + Service | ⚠️ | 无 probe（UDP），无 RollingUpdate 策略 |
| `gcs-web.yaml` | Deployment + Service | ✅ | readiness + liveness probe，NodePort |
| `ingress.yaml` | Ingress | ✅ | WebSocket 支持，路径路由完整 |

**缺失的 K8s 资源**：

| # | 缺失资源 | 风险等级 | 说明 |
|---|---|---|---|
| K1 | **HPA (HorizontalPodAutoscaler)** | 🔴 高 | cloud-backend 无自动扩缩容，流量突增时无法弹性应对 |
| K2 | **PDB (PodDisruptionBudget)** | 🔴 高 | 无 PDB 保护，节点维护时可导致全部 Pod 同时驱逐 |
| K3 | **NetworkPolicy** | 🔴 高 | 无网络隔离，任何 Pod 可访问 cloud-backend 的 actuator/prometheus 端点 |
| K4 | **ServiceMonitor (Prometheus Operator)** | 🟡 中 | 无 Prometheus Operator CRD，需手动配置 scrape |
| K5 | **PV/PVC (flight-logs 持久化)** | 🔴 高 | flight-logs 存在容器本地，Pod 重启后丢失 |
| K6 | **link-sim K8s 配置** | 🟡 中 | link-sim 模块无 K8s Deployment/Service |
| K7 | **PodSecurityPolicy / SecurityContext** | 🟡 中 | 未设置 `runAsNonRoot: true`、`readOnlyRootFilesystem: true` |
| K8 | **Topology Spread Constraints** | 🟢 低 | 单副本阶段不紧急，多副本时需跨节点分散 |
| K9 | **PriorityClass** | 🟢 低 | 无优先级保障，资源紧张时 cloud-backend 可能被驱逐 |

### 1.4 Helm Chart 审计

**结构完整性**：✅ Chart.yaml + values.yaml + 6 个模板文件

**模板参数化评估**：

| 参数 | 可配置 | 说明 |
|---|---|---|
| imageRegistry | ✅ | 支持私有仓库前缀 |
| imageTag | ✅ | 支持版本覆盖 |
| replicaCount | ✅ | 各组件独立配置 |
| resources | ✅ | requests/limits 完整 |
| healthPath | ✅ | cloud-backend 健康路径 |
| ingress | ✅ | enabled + className + host |
| security | ✅ | jwtSecret/devMode/allowedOrigins |
| database | ✅ | url/driver/username/password |

**缺失项**：

| # | 缺失项 | 风险等级 | 说明 |
|---|---|---|---|
| H1 | **无 HPA 模板** | 🔴 高 | Helm chart 未包含 HPA 模板 |
| H2 | **无 PDB 模板** | 🔴 高 | Helm chart 未包含 PDB 模板 |
| H3 | **无 ServiceMonitor 模板** | 🟡 中 | 未集成 Prometheus Operator |
| H4 | **无 values.schema.json** | 🟡 中 | 缺少参数类型校验，`helm install` 传入错误类型不会报错 |
| H5 | **无 PV/PVC 模板** | 🔴 高 | flight-logs 数据无持久化方案 |
| H6 | **无 NetworkPolicy 模板** | 🟡 中 | 网络隔离缺失 |
| H7 | **NOTES.txt 无监控说明** | 🟢 低 | 安装后提示缺少监控接入指引 |

---

## 二、CI/CD 现状分析

### 2.1 CI 流水线现状（`.github/workflows/ci.yml`）

**流水线结构**：

```
push/PR → [java (矩阵) + frontend (并行)] → e2e-smoke (串行) → integration (串行)
```

| Job | 内容 | 评级 | 说明 |
|---|---|---|---|
| `java` | 矩阵构建 4 模块 + JaCoCo 覆盖率 | ✅ | 矩阵并行加速，覆盖率仅 cloud-backend |
| `frontend` | npm ci + npm run build | ✅ | 基本前端构建 |
| `e2e-smoke` | sim→mission→RTL + fault + failsafe + vision | ✅ | 回归覆盖面广 |
| `integration` | API smoke 测试 | ⚠️ | 仅基本端点验证，深度不足 |

**覆盖率门槛问题**：
- `continue-on-error: true` 使覆盖率检查不阻断流水线，50% 门槛形同虚设
- 仅 cloud-backend 有覆盖率报告，其他 3 个 Java 模块无覆盖率收集

### 2.2 缺失的 CI 环节

| # | 缺失环节 | 风险等级 | 说明 |
|---|---|---|---|
| CI1 | **安全扫描 (SAST)** | 🔴 高 | 无 CodeQL/SonarQube 静态安全分析 |
| CI2 | **依赖漏洞扫描** | 🔴 高 | 无 Dependabot/Trivy/OWASP Dependency-Check |
| CI3 | **容器镜像扫描** | 🔴 高 | 无 Trivy/Grype 镜像漏洞扫描 |
| CI4 | **Docker 镜像构建+推送** | 🔴 高 | CI 不构建 Docker 镜像，无法实现 CD |
| CI5 | **Helm chart 验证** | 🟡 中 | 无 `helm lint` / `helm template` 验证 |
| CI6 | **K8s manifest 验证** | 🟡 中 | 无 kubeconform/kubeval 校验 |
| CI7 | **前端测试** | 🟡 中 | 无 vitest/jest 单元测试，无 Playwright E2E |
| CI8 | **覆盖率强制门槛** | 🟡 中 | `continue-on-error: true` 使门槛失效 |
| CI9 | **代码风格检查** | 🟢 低 | 无 checkstyle/spotless 强制检查 |

### 2.3 缺失的 CD 环节

| # | 缺失环节 | 风险等级 | 说明 |
|---|---|---|---|
| CD1 | **持续部署流水线** | 🔴 高 | 完全没有 CD 配置，无自动部署到任何环境 |
| CD2 | **环境晋升流程** | 🔴 高 | 无 dev→staging→prod 的分阶段晋升机制 |
| CD3 | **灰度发布 (Canary)** | 🔴 高 | 无 Canary/Blue-Green 部署策略 |
| CD4 | **回滚机制** | 🔴 高 | 无自动回滚（健康检查失败时自动 revert） |
| CD5 | **镜像签名** | 🟡 中 | 无 Cosign/Sigstore 签名验证 |
| CD6 | **GitOps** | 🟡 中 | 无 ArgoCD/Flux GitOps 模式 |
| CD7 | **部署审批门禁** | 🟡 中 | 无生产部署人工审批环节 |

### 2.4 灰度发布能力评估

**当前能力**：❌ 无灰度发布能力

**K8s 层面的灰度基础**：
- cloud-backend 和 gcs-web 已配置 RollingUpdate（maxSurge=1, maxUnavailable=0），具备基本的滚动更新能力
- 但缺少 Canary/Blue-Green 所需的额外资源（如 Istio VirtualService、Argo Rollouts、Flagger）

**推荐灰度方案**：

| 方案 | 复杂度 | 适用场景 | 说明 |
|---|---|---|---|
| **K8s 原生 RollingUpdate** | 低 | 小规模 | 当前已有，适合 0.1.0 阶段 |
| **Argo Rollouts** | 中 | 中规模 | Canary + AnalysisTemplate，集成 Prometheus 自动回滚 |
| **Istio + Flagger** | 高 | 大规模 | 流量级 Canary，精确百分比控制 |

**建议**：0.1.0 阶段使用 K8s 原生 RollingUpdate + 手动验证；0.2.0 引入 Argo Rollouts 实现 Canary + Prometheus 自动回滚。

---

## 三、监控告警方案

### 3.1 现有可观测性配置审查

**已具备的能力**：

| 维度 | 配置 | 评级 | 说明 |
|---|---|---|---|
| **Actuator 端点** | health/info/metrics/prometheus | ✅ | 端点暴露完整 |
| **Micrometer + Prometheus** | micrometer-registry-prometheus | ✅ | 依赖已添加，指标导出已启用 |
| **指标标签** | `management.metrics.tags.application=aerofleet-cloud` | ✅ | 通用前缀便于聚合 |
| **业务指标** | drones_online, mavlink_frames_received_total, api_requests_total, ws_connections | ✅ | 关键业务指标已定义 |
| **健康详情控制** | dev: always / prod: when-authorized | ✅ | 生产环境安全控制 |
| **日志格式** | dev: 人类可读 / prod: JSON | ✅ | 生产 JSON 便于采集 |
| **Profile 隔离** | dev/staging/prod 三套配置 | ✅ | 环境隔离完善 |

**缺失的可观测性配置**：

| # | 缺失项 | 风险等级 | 说明 |
|---|---|---|---|
| M1 | **Prometheus scrape 配置** | 🔴 高 | 无 `prometheus.yml`，Prometheus 不知道如何抓取指标 |
| M2 | **ServiceMonitor CRD** | 🔴 高 | 无 Prometheus Operator 集成，K8s 环境下标准做法 |
| M3 | **Grafana Dashboard** | 🔴 高 | 无预置仪表盘，运维无法可视化监控 |
| M4 | **Alertmanager 规则** | 🔴 高 | 无告警规则，异常无法主动通知 |
| M5 | **日志聚合方案** | 🔴 高 | 无 ELK/Loki/Fluentd 配置，日志仅输出到 stdout |
| M6 | **分布式追踪** | 🟡 中 | 无 OpenTelemetry/Zipkin/Jaeger，跨服务调用链不可见 |
| M7 | **错误追踪** | 🟡 中 | 无 Sentry/类似工具，异常堆栈无法聚合分析 |
| M8 | **logback JSON 格式不严格** | 🟡 中 | 使用 PatternLayoutEncoder 模拟 JSON，`%msg` 中双引号/换行未转义，破坏 JSON 结构 |

### 3.2 监控覆盖度评估

| 监控维度 | 覆盖情况 | 评级 | 缺失指标 |
|---|---|---|---|
| **API 监控** | api_requests_total | ⚠️ | 缺少延迟分布（P50/P95/P99）、错误率（4xx/5xx 分离） |
| **业务监控** | drones_online, ws_connections | ✅ | 缺少任务成功率、飞行时长分布 |
| **JVM 监控** | Micrometer 默认 JVM 指标 | ✅ | 堆内存、GC、线程数等自动暴露 |
| **MAVLink 监控** | mavlink_frames_received_total | ⚠️ | 缺少帧丢失率、延迟、协议错误计数 |
| **基础设施监控** | ❌ | 🔴 | 无 Node Exporter、cAdvisor 指标 |
| **数据库监控** | ❌ | 🔴 | 无 H2/PostgreSQL 连接池指标 |
| **WebSocket 监控** | ws_connections | ⚠️ | 缺少消息吞吐量、断连率 |

### 3.3 告警规则设计建议

**推荐 Alertmanager 规则集**：

| 告警名称 | 触发条件 | 严重度 | 通知渠道 |
|---|---|---|---|
| `CloudBackendDown` | `up{job="cloud-backend"} == 0` 持续 1m | 🔴 Critical | PagerDuty + Slack |
| `CloudBackendHighLatency` | `http_server_requests_seconds{quantile=0.95} > 2` 持续 5m | 🟡 Warning | Slack |
| `CloudBackendErrorRate` | `rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m]) > 0.05` 持续 5m | 🔴 Critical | PagerDuty + Slack |
| `JVMHeapHighUsage` | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85` 持续 10m | 🟡 Warning | Slack |
| `JVMGCPauseLong` | `rate(jvm_gc_pause_seconds_sum[5m]) / rate(jvm_gc_pause_seconds_count[5m]) > 0.5` | 🟡 Warning | Slack |
| `DronesAllOffline` | `aerofleet_drones_online == 0` 持续 2m | 🔴 Critical | PagerDuty + Slack |
| `MAVLinkFrameLossHigh` | `rate(aerofleet_mavlink_frames_received_total[5m]) < 1` 持续 5m | 🟡 Warning | Slack |
| `WSConnectionsDropped` | `derivative(aerofleet_ws_connections[5m]) < -5` | 🟡 Warning | Slack |
| `PodCrashLooping` | `rate(kube_pod_container_status_restarts_total[15m]) > 0` | 🔴 Critical | PagerDuty + Slack |
| `DiskSpaceLow` | `node_filesystem_avail_bytes / node_filesystem_size_bytes < 0.15` | 🟡 Warning | Slack |
| `CertExpiringSoon` | `probe_ssl_earliest_cert_expiry - time() < 7*24*3600` | 🟡 Warning | Email + Slack |

**通知渠道建议**：

| 渠道 | 用途 | 集成方式 |
|---|---|---|
| **Slack** | 日常告警、Warning 级别 | Alertmanager webhook |
| **PagerDuty** | Critical 级别、需立即响应 | Alertmanager webhook |
| **Email** | 证书过期、容量预警 | Alertmanager SMTP |
| **Webhook (自定义)** | 自动创建 Jira 工单 | Alertmanager webhook |

### 3.4 日志聚合方案评估

**当前状态**：日志仅输出到 stdout，prod profile 使用 JSON 格式，但 JSON 格式不严格（`%msg` 未转义）。

**推荐方案对比**：

| 方案 | 优点 | 缺点 | 推荐度 |
|---|---|---|---|
| **Loki + Promtail** | 轻量、与 Grafana 统一、成本低 | 查询能力不如 ES | ⭐⭐⭐⭐⭐ |
| **ELK (Elasticsearch + Logstash + Kibana)** | 搜索强大、生态成熟 | 资源消耗大、运维复杂 | ⭐⭐⭐ |
| **Fluentd + Elasticsearch + Kibana** | 采集灵活 | 同 ELK 资源问题 | ⭐⭐⭐ |
| **CloudWatch Logs** | AWS 原生、零运维 | 锁定 AWS、查询受限 | ⭐⭐⭐（AWS 部署时） |

**建议**：采用 **Loki + Promtail** 方案，与 Prometheus + Grafana 统一技术栈，资源消耗低，适合当前项目规模。

**logback-spring.xml 改进建议**：
- 引入 `net.logstash.logback:logstash-logback-encoder` 依赖，使用 `LogstashEncoder` 替代 PatternLayoutEncoder 模拟 JSON
- 或使用 Logback 的 `<encoder class="net.logstash.logback.encoder.LogstashEncoder">` 确保严格 JSON 输出

### 3.5 推荐监控架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     NexusSky 监控架构                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────────────┐  │
│  │ cloud-   │───▶│   Prometheus  │───▶│     Grafana          │  │
│  │ backend  │    │   (scrape)    │    │   (dashboards)       │  │
│  │ /actuator│    └──────┬───────┘    └──────────────────────┘  │
│  │ /prometheus│          │                                       │
│  └──────────┘           ▼                                        │
│                    ┌──────────────┐    ┌──────────────────────┐ │
│  ┌──────────┐      │  Alertmanager│───▶│  Slack/PagerDuty     │ │
│  │  K8s     │      │  (rules)     │    │  (notifications)     │ │
│  │  cluster │      └──────────────┘    └──────────────────────┘ │
│  │ metrics  │                                                       │
│  └──────────┘           ┌──────────────┐    ┌──────────────────┐ │
│                         │    Loki      │───▶│   Grafana        │ │
│  ┌──────────┐           │  (logs)      │    │   (log queries)  │ │
│  │ Promtail │──────────▶│              │    └──────────────────┘ │
│  │ (collect)│           └──────────────┘                         │
│  └──────────┘                                                     │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │  ServiceMonitor (Prometheus Operator CRD)                │    │
│  │  → 自动发现 cloud-backend Pod 并配置 scrape              │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 四、综合优先级排序与实施建议

### 4.1 按优先级排序的改进项

| 优先级 | 编号 | 改进项 | 工作量 | 影响范围 |
|---|---|---|---|---|
| **P0 (立即)** | D1 | Dockerfile 添加非 Root 用户 | 小 | 所有镜像 |
| **P0 (立即)** | K5/H5 | flight-logs PV/PVC 持久化 | 中 | K8s + Helm |
| **P0 (立即)** | CI4 | CI 构建 Docker 镜像并推送 | 中 | CI 流水线 |
| **P0 (立即)** | M1/M2 | Prometheus scrape 配置 + ServiceMonitor | 中 | 监控 |
| **P0 (立即)** | CD1 | 基础 CD 流水线（镜像→K8s） | 大 | CI/CD |
| **P1 (短期)** | K1/H1 | HPA 自动扩缩容 | 中 | K8s + Helm |
| **P1 (短期)** | K2/H2 | PDB 防驱逐保护 | 小 | K8s + Helm |
| **P1 (短期)** | K3/H6 | NetworkPolicy 网络隔离 | 中 | K8s + Helm |
| **P1 (短期)** | CI1/CI2 | SAST + 依赖漏洞扫描 | 中 | CI 流水线 |
| **P1 (短期)** | M3/M4 | Grafana Dashboard + Alertmanager 规则 | 中 | 监控 |
| **P1 (短期)** | M5 | 日志聚合 (Loki + Promtail) | 中 | 监控 |
| **P1 (短期)** | C1 | docker-compose 资源限制 | 小 | Docker |
| **P2 (中期)** | CI3 | 容器镜像扫描 (Trivy) | 小 | CI 流水线 |
| **P2 (中期)** | CI5/CI6 | Helm lint + K8s manifest 校验 | 小 | CI 流水线 |
| **P2 (中期)** | CD3 | Argo Rollouts Canary 灰度 | 大 | CD |
| **P2 (中期)** | M6 | OpenTelemetry 分布式追踪 | 大 | 监控 |
| **P2 (中期)** | M8 | logback 严格 JSON (LogstashEncoder) | 小 | 日志 |
| **P2 (中期)** | H4 | values.schema.json 参数校验 | 小 | Helm |
| **P3 (长期)** | CD6 | GitOps (ArgoCD/Flux) | 大 | CD |
| **P3 (长期)** | D4/D5 | 镜像 digest 锁定 + Cosign 签名 | 中 | 供应链安全 |
| **P3 (长期)** | M7 | Sentry 错误追踪 | 中 | 监控 |

### 4.2 分阶段实施路线图

**Phase 1 (0.1.0 → 0.2.0)：基础安全与可观测性**
- Dockerfile 非 Root 用户
- flight-logs PV/PVC
- CI 镜像构建推送
- Prometheus + ServiceMonitor + Grafana Dashboard
- Alertmanager 告警规则
- Loki 日志聚合
- docker-compose 资源限制
- HPA + PDB

**Phase 2 (0.2.0 → 0.3.0)：CI/CD 强化**
- SAST + 依赖扫描 + 镜像扫描
- Helm lint + K8s manifest 校验
- CD 流水线（镜像→staging→prod）
- Argo Rollouts Canary 灰度
- NetworkPolicy
- logback LogstashEncoder

**Phase 3 (0.3.0+)：高级运维**
- GitOps (ArgoCD)
- OpenTelemetry 分布式追踪
- Cosign 镜像签名
- Sentry 错误追踪
- values.schema.json

---

## 五、附录：配置文件清单

| 文件路径 | 类型 | 说明 |
|---|---|---|
| `docker-compose.yml` | 开发 compose | host 网络 + 挂载 jar |
| `deploy/docker/docker-compose.yml` | 生产 compose | 多阶段构建 + 桥接网络 |
| `deploy/docker/Dockerfile.cloud` | Dockerfile | cloud-backend 多阶段构建 |
| `deploy/docker/Dockerfile.sim` | Dockerfile | drone-sim 多阶段构建 |
| `deploy/docker/Dockerfile.web` | Dockerfile | gcs-web Node→Nginx |
| `deploy/docker/nginx.conf` | Nginx 配置 | 静态 + 反代 + WS + SPA |
| `deploy/docker/.dockerignore` | Docker 忽略 | 排除敏感/无关文件 |
| `deploy/k8s/namespace.yaml` | K8s | Namespace |
| `deploy/k8s/configmap.yaml` | K8s | 非敏感配置 |
| `deploy/k8s/secret.yaml` | K8s | 敏感配置（明文 stringData） |
| `deploy/k8s/cloud-backend.yaml` | K8s | Deployment + Service |
| `deploy/k8s/drone-sim.yaml` | K8s | Deployment + Service |
| `deploy/k8s/gcs-web.yaml` | K8s | Deployment + Service (NodePort) |
| `deploy/k8s/ingress.yaml` | K8s | Ingress (WebSocket 支持) |
| `deploy/helm/nexussky/Chart.yaml` | Helm | Chart 元数据 |
| `deploy/helm/nexussky/values.yaml` | Helm | 可配置参数 |
| `deploy/helm/nexussky/templates/*.yaml` | Helm | 6 个模板文件 |
| `.github/workflows/ci.yml` | CI | GitHub Actions 流水线 |
| `cloud-backend/src/main/resources/application.properties` | Spring | 通用配置 |
| `cloud-backend/src/main/resources/application-dev.properties` | Spring | 开发 profile |
| `cloud-backend/src/main/resources/application-staging.properties` | Spring | 预发布 profile |
| `cloud-backend/src/main/resources/application-prod.properties` | Spring | 生产 profile |
| `cloud-backend/src/main/resources/logback-spring.xml` | Logback | 日志配置 (dev/prod JSON) |

---

*报告结束*