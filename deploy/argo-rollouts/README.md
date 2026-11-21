# Argo Rollouts — 灰度发布 + 自动回滚（CD3/CD4，issue #323）

> 本落地 = #323（L2 自动回滚）+ plan §2.4（0.2.0 路线：Argo Rollouts Canary + Prometheus 自动回滚）的落地骨架。
> 当前机器上：`github.com` 不可达（实测 helm install / curl 与 ~3000 字处理均确认连接失败，`curl:000`），
> 因此控制器安装命令在本机暂时执行不了——在有 github 的环境里一键跑完：
>
> ```bash
> helm repo add argo https://argoproj.github.io/argo-helm
> helm repo update
> helm install argo-rollouts argo/argo-rollouts -n argo-rollouts --create-namespace
> kubectl get crd | rg argoproj    # 应出现 rollouts.argoproj.io 等
> ```

## 上线（Rollout 替代 Deployment `deploy/k8s/cloud-backend.yaml`）

```bash
# 1) 模板先于 Rollout（Rollout 的后台 analysis 引用它）
kubectl apply -n nexussky -f deploy/argo-rollouts/analysis/cloud-backend-5xx-error-rate.yaml
# 2) 替代旧 Deployment
kubectl delete -n nexussky deploy nexussky-cloud-backend
# 3) 应用 Rollout
kubectl apply -n nexussky -f deploy/argo-rollouts/cloud-backend-rollout.yaml
# 4) 查看
kubectl argo rollouts status nexussky-cloud-backend -n nexussky
```

Service（既有 `cloud-backend` Service 的 selectors）不需改；Rollout 的 pods 与原 Deployment 同口径
（`app.kubernetes.io/name: nexussky` + `app.kubernetes.io/component: cloud-backend`）。

## 灰度工作流（CD3）

`cloud-backend-rollout.yaml` 的 steps：

```yaml
steps:
- setWeight: 50
- pause: {duration: 5m}
- setWeight: 100
```

- 监视：`kubectl argo rollouts status nexussky-cloud-backend -n nexussky`（显示当前 step、canary 比例）。
- 人工推进/中断：`kubectl argo rollouts promote nexussky-cloud-backend -n nexussky` / `kubectl argo rollouts abort nexussky-cloud-backend -n nexussky`（abort 后会缩回 stable）。
- 回滚：`kubectl argo rollouts undo nexussky-cloud-backend -n nexussky`（上一 revision）。
- 安全须知（replicas=1 是尺度型灰度）：无流量路由时比例是 best-effort，真实精度级灰度需
  （a）增加 `spec.replicas`（如 ≥4，搭配自动预约），或
  （b）接流量路由插件（Istio/NGINX/SMI 等，见 docs/devops-enhancement-plan.md §2.4 推荐）——
  这些是 plan 推荐 0.2.0/长期的方向。

## 自动回滚（CD4 / #323 L2）

`analysis/cloud-backend-5xx-error-rate.yaml` — Rollout 的后台 Analysis（`strategy.canary.analysis`）：
- 实测阈值：error_rate（5xx/总请求，`http_server_requests_seconds_count{status=~"5.."}`）> 5% 持续超过 3 次（`failureLimit: 3`）→ AnalysisRun 失败 → Rollout 自动 abort，canary 缩为 0，流量回稳在 stable。
- Prometheus 地址参数 `prometheusAddress`（默认 `http://prometheus:9090`）：
  - compose 模式：`deploy/monitoring/docker-compose-monitoring.yml` 的服务名是 `prometheus`；
  - K8s 模式：改 template 的 `value` 为你的 Prometheus 地址。
- 注意：查询在暂无流量（0/0 → NaN）时 AnalysisRun 不共解（inconclusive），Rollout 停在当前步——正常时不发生。

## 迁移安全门禁（CD4 / #323 L3）

```bash
python scripts/check-destructive-migration.py   # 实测通过（RC=0，破坏性迁移登记齐）
```

`docs/rollback-runbook.md`（L1 手动回滚）已交付；L2 之后它可通过 Analysis abort 替代部分冷路径——#323 跟进。

## 状态说明

| 线 | 现状 |
|---|---|
| CD3 灰度（0.1.0 应用级 RollingUpdate） | `deploy/k8s/*.yaml` + `deploy/helm/nexussky/templates/deployment.yaml` 已有（见 docs/devops-enhancement-plan.md §2.4 建议） |
| CD3 灰度（Canary/流量级） | 本目录 Rollout（落地中）；路线 = #324（Istio/Flagger）或 0.2.0 Argo Rollouts |
| CD4 自动回滚 | 本目录 Analysis（#323 L2）+ 门禁（green）+ L1 runbook（docs/rollback-runbook.md） |

## 相关文档

- `docs/devops-enhancement-plan.md` §2.3/§2.4/§4.1 — CD3/CD4 计划口径
- `docs/rollback-runbook.md` — L1 回滚手册（Helm/kubectl/compose）
- `scripts/check-destructive-migration.py` — L3 迁移安全门禁（已实测 green）
