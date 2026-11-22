# 回滚运行手册（Rollback Runbook）

> **适用对象**：NexusSky 部署（Helm chart `deploy/helm/nexussky`，默认 namespace `nexussky`）。
> **用途**：出事时不用现想——按决策树走，按命令执行，按验证清单收尾。
> **状态**：L1（文档层）已交付。自动回滚（L2）与迁移安全门禁（L3）见 issue #323。
> **维护约定**：本文件里每一条命令都必须是**实际执行过**的；没跑过的写"待演练"，不许写理论值。

---

## 0. 三条硬约束（先读这三条，它们决定了回滚能做到什么）

**① 应用回滚 ≠ schema 回滚。** 把 Pod 退回上一版本，数据库结构**不会**跟着退。
Flyway 迁移在应用启动时**只前进**，`flyway_schema_history` 里已落地的版本不会因为换镜像而消失。

**② Flyway 社区版没有 `undo`。** 本仓用的是 `flyway-core` + `flyway-database-postgresql`
（`cloud-backend/pom.xml:109-120`，版本由 Spring Boot BOM 管理，11.7.2）。
社区版**不提供** `flyway undo`，`clean` 也不是降级手段（它会删掉整个 schema）。
所以"执行 down 脚本"这条路在本仓**不存在**——只有两条真实路径，见 §4。

**③ 回滚目标必须是不可变标识。** 用 `latest` 回滚等于没回滚（今天和昨天的 `latest` 是两个镜像）。
CI 的产出是 `sha-<short>`（`ci.yml` 的 `type=sha,format=short`）与 `latest`（仅 master push 产生）。
**回滚请指定 `sha-<short>`，并在工单里记下 digest。**

> **这个缺口的解决（2026-11-20，实测）**：`ghcr.io/levango7/nexussky/<module>` 存在 `latest`、不存在 `0.1.0`；
> 改 `deploy/helm/nexussky/values.yaml` 默认：`imageRegistry: "" → "ghcr.io/levango7/"`、`imageTag: "0.1.0" → "latest"`
> —— 默认 `helm install`（不带 --set）即可拉到实际存在的镜像。
> 回滚仍用 `sha-<short>`：需要可回滚时部署前 `--set imageTag=sha-<short>`（0.1.0 时期未实测的 RTO 依然说不清，见 §8「已交付，未验证」）。
> 历史记录（原有已知缺口）：`deploy/helm/nexussky/values.yaml:17` 的默认 `imageTag` 曾是 `0.1.0`，
> 而仓库内没有任何工作流产出 `0.1.0` 这个 tag——若发布不依赖手工 `docker push`，默认 values 不可直接部署。
> 详见 issue #324 的「阻塞项」一节。

---

## 1. 先分类：三种回滚，三条路

| 类型 | 典型症状 | 手段 | 可逆性 |
|---|---|---|---|
| **应用回滚** | 新版本启动即崩、5xx 飙升、接口行为回归、CPU/内存异常 | 镜像/配置退回上一 revision | ✅ 可逆，分钟级 |
| **schema 回滚** | 迁移执行成功但结构不对、新列导致旧代码报错 | 前向修复迁移 或 备份恢复到新库 | ⚠️ 只能"再往前修"，不能真退 |
| **数据回滚** | 批量写坏、误删、迁移写错数据 | 备份恢复 / 定点修正 SQL | ⚠️ 取决于备份窗口，RTO 未实测 |

**最容易搞错的一点**：症状看起来像"应用问题"，但根因是迁移——此时回滚应用**不会**好，
反而可能因为旧代码不认识新结构而更糟。先按 §2 判断。

---

## 2. 决策树

```
出事了
├─ Q1 最近 30 分钟内是否执行过数据库迁移（新版本发布）？
│   ├─ 是 ─→ Q2 迁移里是否含破坏性语句（DROP/TRUNCATE）？
│   │         ├─ 是 ─→ 【先别回滚应用】按 §4.2 处理（旧代码可能读不到被删的列）
│   │         └─ 否 ─→ 可以回滚应用（§3），schema 保持新版本（向后兼容迁移的前提下）
│   └─ 否 ─→ Q3 是数据写坏了吗（表里内容不对）？
│             ├─ 是 ─→ §5 数据回滚
│             └─ 否 ─→ §3 应用回滚
└─ 任何情况下：回滚完成后必须走 §7 验证清单，否则不算结束
```

判断 Q2 的机械方法（不用读 40 个迁移文件）：

```bash
# 列出含破坏性语句的迁移（当前仓内命中 1 个：V22）
grep -lE 'DROP TABLE|DROP COLUMN|TRUNCATE' cloud-backend/src/main/resources/db/migration/*.sql
```

结果必须与 §6 的不可回滚清单一致——不一致就是门禁该红的时候（见 `scripts/check-destructive-migration.py`）。

---

## 3. 应用回滚

### 3.1 Helm（推荐路径）

```bash
# 1) 看历史，确认要退回的 revision 与它对应的镜像
helm -n nexussky history nexussky

# 2) 回滚（--wait 会等 Pod ready；失败时 helm 自己报错，不会假装成功）
helm -n nexussky rollback nexussky <REVISION> --wait --timeout 10m

# 3) 确认 ReplicaSet 已切回
kubectl -n nexussky rollout status deploy/nexussky-cloud-backend
kubectl -n nexussky get pods -l app.kubernetes.io/component=cloud-backend -o wide
```

`helm history` 只记录 Helm 侧改动。**如果镜像 tag 没变**（例如两次发布都用 `latest`），
`helm rollback` 会"成功"但拉到的还是同一个镜像——这正是 §0③ 要求用 `sha-<short>` 的原因。

### 3.2 裸 kubectl（`deploy/k8s/` 清单，不经 Helm）

```bash
kubectl -n nexussky rollout undo deploy/nexussky-cloud-backend
kubectl -n nexussky rollout status deploy/nexussky-cloud-backend
```

`rollout undo` 依赖 Deployment 保留的历史 ReplicaSet（默认保留 10 个）。
**注意**：`deploy/k8s/` 与 `deploy/helm/` 是两套并行清单，改了一边不改另一边就会漂移。

### 3.3 docker compose（单机/试用环境）

```bash
# 回到上一个镜像 tag（前提：镜像 tag 不可变，且 compose 文件里写的是具体 tag）
git -C <repo> checkout <上一个发布的 commit> -- docker-compose.yml
docker compose pull
docker compose up -d
```

单机环境没有滚动能力，`up -d` 期间**服务会短暂中断**。这不是缺陷，是单副本的物理事实。

### 3.4 当前部署形态的两个真实限制（不要指望它们不存在）

- `cloudBackend.replicaCount` 默认 **1**（`values.yaml:32`）。`maxSurge: 1 / maxUnavailable: 0`
  能保证"新 Pod ready 后才杀旧 Pod"，但**没有并行容量**——新镜像起不来时，整个服务就停在那里等超时。
- `cloudBackend.pdb.enabled` 默认 **false**（`values.yaml:59-61`）。单副本时 PDB 会阻塞节点 drain，
  所以默认关掉——代价是**节点维护期间没有可用性保护**。

---

## 4. schema 回滚（没有 undo，只有两条路）

### 4.1 路径 A：前向修复（首选）

新增一个迁移把结构改回可用状态，**不删历史迁移**（删了会让已部署环境的 checksum 校验失败）：

```bash
# 例：V22 删掉了 devices.device_token，若必须恢复该列
cat > cloud-backend/src/main/resources/db/migration/V42__restore_device_token.sql <<'SQL'
ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_token VARCHAR(255);
SQL
```

**代价必须说清楚**：`ADD COLUMN` 只能恢复**结构**，列里的**数据已经没了**。
若该列曾被用于鉴权，恢复后所有设备都需要重新下发 token。

### 4.2 路径 B：备份恢复到新库后切流量

```bash
# 1) 确认备份可用（备份策略见 §5）
# 2) 恢复到新库（示例，实际以你们的备份工具为准）
createdb -h <host> -U <user> nexussky_restore
pg_restore -h <host> -U <user> -d nexussky_restore <backup_file>

# 3) 校验版本（应等于备份时点，而不是当前最新）
psql -h <host> -U <user> -d nexussky_restore -c \
  "select version, description, success from flyway_schema_history order by installed_rank desc limit 5;"

# 4) 切换连接串（改 Secret 后滚动重启）
kubectl -n nexussky rollout restart deploy/nexussky-cloud-backend
```

切换前必须确认：新库的 `flyway_schema_history` 末条版本 **≤ 即将运行的镜像所包含的最高迁移版本**，
否则应用启动时会自动把新库再往前推一次。

### 4.3 绝对不能做的事

- ❌ 手工 `DELETE FROM flyway_schema_history WHERE version='22'`——下次启动会重跑 V22，
  而 V22 里的 `DROP COLUMN IF EXISTS` 不会报错，**你会以为修好了**。
- ❌ `flyway clean`——社区版可用，但它会删掉**整个 schema**。
- ❌ 直接改已发布迁移文件的内容——checksum 校验会让所有已部署环境启动失败。

---

## 5. 数据回滚

| 手段 | 适用 | 前提 | 实测 RTO |
|---|---|---|---|
| 备份全量恢复 | 大面积写坏、误删表 | 有可用的近期备份 | **待演练** |
| 定点修正 SQL | 已知范围的错误写入 | 能定位到行（时间/ID 区间） | **待演练** |
| 从副本重放 | 有 binlog/WAL 归档 | 具备 PITR 能力 | 本仓**未配置** |

**当前状态（事实）**：本仓没有配置 PITR，也没有在文档中记录备份保留窗口。
`docs/RELEASE.md` 全文不含回滚/备份章节。因此上表后两列的"待演练"是**真实缺口**，
不是待补的排版——演练完成前，任何"RTO 5 分钟"的说法都是编的。

---

## 6. 不可回滚清单（机器校验）

下面这段由 `scripts/check-destructive-migration.py` 解析：
**每个含破坏性语句的迁移文件都必须出现在本节表格里**，否则门禁判红。
表格里的文件若已不存在、或该文件其实没有破坏性语句，同样判红（防止清单腐烂）。

<!-- destructive-migrations:begin -->
| 迁移脚本 | 破坏性语句 | 为什么不可逆 | 出事后的处理 |
|---|---|---|---|
| `V22__api_keys_sysid.sql` | `ALTER TABLE devices DROP COLUMN IF EXISTS device_token` | 列被删除后，**回退到 V22 之前的应用版本会因缺列而查询失败**；且 `IF EXISTS` 让重跑不报错，掩盖问题 | 走 §4.1 前向修复补回列（数据不可恢复），或 §4.2 恢复到备份时点 |
<!-- destructive-migrations:end -->

---

## 7. 回滚后验证（三步，缺一不可）

```bash
# 先取 app Pod 名：kubectl exec 不接受 deploy/... 或 rollout/... 对象；
# CD3/CD4 落地后该工作负载是 Rollout（deploy/argo-rollouts/），不是 Deployment。
POD=$(kubectl -n nexussky get pod -l app.kubernetes.io/component=cloud-backend \
  -o jsonpath='{.items[0].metadata.name}')

# ① 就绪状态：readinessState + db 必须全 UP（路径见 values.yaml:46-47）
kubectl -n nexussky exec "$POD" -- \
  curl -s localhost:8080/actuator/health/readiness

# ② 版本确认：镜像必须是你要回滚到的那个 sha，不是 latest
kubectl -n nexussky get pod "$POD" \
  -o jsonpath='{.spec.containers[0].image}{"\n"}'

# ③ schema 版本确认：末条版本必须与预期一致
kubectl -n nexussky exec "$POD" -- \
  sh -c 'echo "select version, description, success from flyway_schema_history order by installed_rank desc limit 3;" | psql "$SPRING_DATASOURCE_URL"'
```

第 ③ 步的 `psql` 依赖镜像内是否带客户端——若镜像里没有（当前 `readOnlyRootFilesystem: true`，
装不了），改用集群内一次性 Pod 或从外部连库执行同一条 SQL。**不要因为命令跑不通就跳过这一步。**

业务侧还要看两个指标（Prometheus，抓取配置见 `deploy/monitoring/servicemonitor.yaml`）：
错误率是否回到基线、`ws_connections` 是否恢复。

---

## 8. 验证状态与演练记录

**本手册的验证状态（如实标注，不四舍五入；2026-11-21 更新）**：

| 项 | 状态 | 依据 |
|---|---|---|
| `helm lint deploy/helm/nexussky` | ✅ 已执行 | helm 3.14.0，`1 chart(s) linted, 0 chart(s) failed` |
| `helm template` 渲染出的资源名与镜像引用 | ✅ 已执行 | 渲染得 `Deployment nexussky-cloud-backend`，镜像 `nexussky/cloud-backend:sha-abc1234`——§3.1 与 §0③ 的命令与命名不是猜的 |
| `kubectl` 系列命令 | ✅ 已在 kind 执行 | 2026-11-21，context `kind-fs-k4-e2e`：Argo Rollouts controller 2/2 Ready + 5 个 CRD；apply `deploy/argo-rollouts/` 脚手架后 app Pod 1/1 Running，`kubectl wait --for=condition=Ready` 通过 |
| Argo Rollouts L1/L2 演练 | ✅ 已在本地 kind 完成 | 见下方演练记录；**是本地 kind，不是生产环境** |
| §7 ①就绪 / ②镜像版本 | ✅ 已实测 | readiness 返回 `{"status":"UP"}`；镜像 `ghcr.io/levango7/nexussky/cloud-backend:latest` |
| §7 ③ `flyway_schema_history` 查询 | ✅ 已实测（2026-11-22） | app 镜像内无 `psql`，改从 host PG 容器执行同一条 SQL：`docker exec opsmesh-p0a3-pg psql -U aerofleet -d aerofleet_prod -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 3;"` → 返回 `37 / emergency plan / t`、`36 / delivery task phase / t`、`35 / sequence counter / t` |
| Helm revision 回滚 | ✅ 已在 kind 演练 | rev1 → rev2 → rollback 回 rev1，见 §8 |
| 跨 V22 回滚（含前向修复） | ✅ 已在 kind 演练 | 旧镜像缺列失败 → 前向修复后启动成功，见 §8 |
| 备份恢复 RTO | ⚠️ **生产体量未实测** | 小体量机制演练已通过（71.9K / 3.09s），不能据此承诺生产 RTO |

**因此本文件当前状态是「Rollout 灰度/自动回滚、Helm revision 回滚、跨 V22 与备份恢复机制
已在本地 kind / host PG 实测；生产体量 RTO、真机链路仍未验证」**，不是「所有回滚能力已就绪」。
不要对外扩大口径。

| 日期 | 场景 | 执行人 | 结果 | 实测耗时 | 备注 |
|---|---|---|---|---|---|
| 2026-11-21 | Argo Rollout spec 滚动（cpu 250m→350m） | AI agent @ `kind-fs-k4-e2e` | ✅ 通过 | 未精确计时 | 控制器建新 RS `nexussky-cloud-backend-695b89bfbc`（revision 2）+ 新 Pod——spec 变化确实触发滚动 |
| 2026-11-21 | L1 回滚（spec revert 回 250m） | 同上 | ✅ 通过 | 未精确计时 | apply 回前序 revision 后 `kubectl wait` 通过，app Pod 1/1 Running，stable RS 恢复 `79fc98cf6d` |
| 2026-11-21 | L2 自动回滚（Analysis Error → abort → stable） | 同上 | ✅ 通过 | 未精确计时 | AnalysisRun `cloud-backend-5xx-error-rate-rehearsal` = Error（`consecutiveErrors (5) > consecutiveErrorLimit (4)`）；控制器 abort，canary RS 回 0、stable 回 1，app 1/1 Running |
| 2026-11-21 | L2 happy path（Analysis 成功 → canary 推进到 100%） | 同上 | ✅ 通过 | 未精确计时 | 集群内无 Prometheus，用临时 web-provider 模板探 app readiness（返回 `{"status":"UP"}`）；背景 AnalysisRun `nexussky-cloud-backend-7f576d67c4-4` = Successful，Rollout `phase=Healthy`、`currentStepIndex=3`、`stableRS=7f576d67c4`、`Completed=True`。演练对象已清理，Rollout 已按脚手架重建 |
| 2026-11-22 | L2 happy path（**真实 Prometheus** 5xx 查询） | 同上 | ✅ 通过 | 未精确计时 | quay 镜像 `prometheus:v2.55.0` + Bearer JWT 抓 `/actuator/prometheus`，target `up`；原查询因「无 5xx → 空向量」AnalysisRun Error（`no data returned from the metric provider`），改为 `or vector(0)` + `clamp_min` 后 **AnalysisRun Successful（count=4）**。同时发现 K8s 下 `prometheusAddress` 必须 FQDN（短名 DNS 失败），已写回脚手架。另修正 `args` 位置：CRD 要求 `analysis.args` 与 `templates` 同级，放在 template ref 内会被 strict decoding 拒绝——该错误由重建集群后 apply 脚手架实测暴露 |
| 2026-11-22 | L2 自动回滚（**真实 Prometheus** 查询失败 → abort） | 同上 | ✅ 通过 | 未精确计时 | 集群重建后用同样查询但临时把 successCondition 设为不可能值（`result[0] <= -1`，failureLimit=1）触发 canary：Rollout `phase=Degraded`，message = `RolloutAborted: Rollout aborted update to revision 2: Background analysis phase error/failed: Metric "http-5xx-error-rate" assessed Failed due to failed (2) > failureLimit (1)`；stable RS 保持 `79fc98cf6d`、app 1/1 Running。还原脚手架后 Rollout 回 `Healthy`。演练模板/AnalysisRun 已清理。 |
| 2026-11-23 | L2 5xx 指标（**真实 5xx 流量**） | AI agent @ `kind-fs-k4-e2e` | ✅ 通过 | 未精确计时 | 新增默认关闭的受控故障端点 `/api/v1/test/fault/500`（`@ConditionalOnProperty`，prod 默认 404；须在 `LicenseModuleMap` 登记前缀并加 `@RequireRole`），用带该端点的镜像起独立 Deployment 并让 Prometheus 抓它：60 次请求全 500，真实 5xx 错误率 `0.83`（阈值 5%）；用**生产 AnalysisTemplate** 跑 AnalysisRun → `Failed`：`Metric "http-5xx-error-rate" assessed Failed due to failed (4) > failureLimit (3)`。**此前用不可能阈值的演练是替代验证，本条是真流量证据**。演练后删 Deployment/Service/AnalysisRun、Prometheus 恢复抓稳定 app。另发现 token 1h 过期会让 target 401（指标停更），生产需 token 轮换 |
| 2026-11-22 | Helm 回滚到上一 revision | AI agent @ `kind-fs-k4-e2e` | ✅ 通过 | 未精确计时 | release `drill`（ns `nexussky-helm`，资源落在 values 指定的 `nexussky`）：rev1 install（cpu `250m`）→ rev2 upgrade（`--set cloudBackend.resources.requests.cpu=350m`，实测 `350m`）→ `helm rollback drill 1` → rev4 `deployed`，cpu 回 `250m`、镜像 `ghcr.io/levango7/nexussky/cloud-backend:latest`、cloud-backend Pod 1/1 Running。备注：① chart 修了 3 个真实缺陷才装得上（前导 `---` 产生空文档、`secret.yaml` 模板裁剪把注释与 `apiVersion` 粘连、hpa/pdb 条件块后的 `---` 产生空文档）；② 当时 gcs-web 因 chart 只读根文件系统 CrashLoop（entrypoint 的 envsubst 写不了 `/etc/nginx/conf.d/default.conf`），`--wait` 会误把成功 rollback 标 failed，演练用不带 `--wait` 的 rollback 拿到干净 rev4；该缺口随后已修（chart 与 `deploy/k8s/gcs-web.yaml` 均补 `nginx-conf` emptyDir），docker `--read-only` A/B 实测：修复前 `can't create /etc/nginx/conf.d/default.conf`，修复后 envsubst 成功生成配置；重建集群后用 chart 安装 release `verify`，两个 gcs-web Pod 均 **1/1 Running** |
| 2026-11-22 | 回滚跨 V22（缺列） | AI agent @ `kind-fs-k4-e2e` | ✅ 通过 | 未精确计时 | 从 V22 引入提交 `21b28bc7^` 构建旧版 app 镜像（`nexussky/cloud-backend:pre-v22`，用当前镜像作底座替换旧 jar），部署到已过 V22 的 DB（Flyway 版本 37）：旧镜像 **CrashLoopBackOff**，日志 `SchemaManagementException: Schema-validation: missing column [device_token] in table [devices]`——验证了「跨 V22 应用回滚直接失败」不是纸面推断。再按 §4.1 路径 A 前向修复（`alter table devices add column device_token varchar(255)`）后重建 Pod，旧镜像 **1/1 Running**（`Started CloudBackendApplication`）。演练后已 drop 该列、删 Deployment，当前 app 仍 1/1 |
| 2026-11-22 | 备份恢复切库 | AI agent @ host PG | ✅ 通过（小体量） | 3.09 s（pg_restore 实测） | `pg_dump -Fc` 71.9K → 新库 `aerofleet_restore_drill` → `pg_restore` → 校验 `flyway_schema_history` 末三条 `37/36/35 success=t` 与原库一致、`app_user` 行数 1 一致。**只是机制演练，不是接近生产体量的 RTO 实测**，不能据此承诺生产 RTO |

演练完成的判据：上表填了真实数字，且 §7 三步的**实际输出**贴进了 PR 或工单。
§7 ③ 尚未执行，因此**不能对外说成"全部回滚能力已就绪"**。

---

## 9. 相关文档与议题

- `docs/signing-prod-rollout.md` §4——MAVLink 签名功能的专用回滚（permissive→strict 两步走），与本手册正交。
- `docs/devops-enhancement-plan.md:161`——CD4 原始缺口描述（🔴 无自动回滚）。
- issue #323——本手册所属议题（L2 自动回滚 / L3 迁移门禁）。
- issue #324——Canary 灰度发布；灰度期间新旧版本共享同一 schema，**任何破坏性迁移都会让本手册失效**。
