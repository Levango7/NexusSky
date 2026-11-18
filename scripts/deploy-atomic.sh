#!/usr/bin/env bash
# 部署时自动回滚（#323 L2 的可验证退路实现）
#
# 用法：
#   IMAGE_TAG=sha-a1b2c3d bash scripts/deploy-atomic.sh upgrade
#   bash scripts/deploy-atomic.sh --self-test
#
# 做什么：校验不可变 tag → helm upgrade --wait --timeout --atomic → 打印切换前后 revision。
# 边界（不要当成完整回滚能力）：
#   ✅ 覆盖「启动即失败」：readiness 永不 ready、镜像拉取失败、CrashLoop。
#   ❌ 不覆盖「起来后指标恶化」：Pod ready 时 --atomic 认为成功，5xx/P99 劣化需人工按
#      docs/rollback-runbook.md §2 决策树处理；真正的自动判定在 #324（Argo Rollouts analysis）。
#   ❌ 不碰 schema：Flyway 社区版无 undo，见 runbook §4。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART="$ROOT/deploy/helm/nexussky"
RELEASE="${RELEASE:-nexussky}"
NAMESPACE="${NAMESPACE:-nexussky}"
TIMEOUT="${TIMEOUT:-10m}"
IMAGE_REGISTRY="${IMAGE_REGISTRY:-ghcr.io/levango7/}"

usage() {
  cat <<'EOF'
用法：
  IMAGE_TAG=sha-a1b2c3d bash scripts/deploy-atomic.sh upgrade
  bash scripts/deploy-atomic.sh --self-test

IMAGE_TAG 只接受 sha-<short>（7-12 位十六进制）或 x.y.z 语义版本；
latest / 0.1.0 / 1.0.0 是无 CI 产出的漂移标识，一律拒绝。
EOF
}

validate_tag() {
  local tag="${1:-}"
  if [[ -z "$tag" ]]; then
    echo "✗ IMAGE_TAG 未设置：请用 sha-<short> 或 semver 版本（见 docs/rollback-runbook.md §0③）" >&2
    return 1
  fi
  case "$tag" in
    latest|0.1.0|1.0.0)
      echo "✗ IMAGE_TAG=$tag 是禁止的标识：latest 会漂移，0.1.0/1.0.0 无 CI 产出" >&2
      return 1
      ;;
  esac
  if [[ ! "$tag" =~ ^(sha-[0-9a-fA-F]{7,12}|[0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
    echo "✗ IMAGE_TAG=$tag 不符合 sha-<short> 或 x.y.z 格式" >&2
    return 1
  fi
}

current_revision() {
  helm -n "$NAMESPACE" history "$RELEASE" 2>/dev/null | tail -1 | awk '{print $1}'
}

do_upgrade() {
  validate_tag "${IMAGE_TAG:-}" || return 1

  local before after
  before="$(current_revision || true)"
  echo "→ 切换前：release=$RELEASE ns=$NAMESPACE revision=${before:-none} tag=$IMAGE_TAG"

  # --atomic：任一资源失败时 helm 自动回到上一 revision（L2 的核心）。
  # --wait：等 readiness 探针通过才算成功（因此能捕获「启动即失败」）。
  helm upgrade --install "$RELEASE" "$CHART" -n "$NAMESPACE" \
    --set imageRegistry="$IMAGE_REGISTRY" \
    --set imageTag="$IMAGE_TAG" \
    --wait --timeout "$TIMEOUT" --atomic

  after="$(current_revision || true)"
  echo "→ 切换后：revision=${after:-unknown} tag=$IMAGE_TAG"
  echo "AUDIT release=$RELEASE ns=$NAMESPACE before=${before:-none} after=${after:-unknown} tag=$IMAGE_TAG image=${IMAGE_REGISTRY}nexussky/cloud-backend:${IMAGE_TAG}"
}

self_test() {
  local td ok=0 fail=0
  td="$(mktemp -d)"
  # Git Bash 的 mktemp 返回 Windows 路径（C:\Users\...），直接放进 POSIX PATH
  # 会被按 ':' 拆成多个条目而失效，导致调用到真 helm。转成 /c/... 形式。
  td="$(cd "$td" && pwd)"

  # mock helm：记录参数；history 返回固定 revision；可配置为升级失败。
  cat > "$td/helm" <<'MOCK'
#!/usr/bin/env bash
echo "$*" >> "${HELM_ARG_LOG:-/dev/null}"
if [[ "${1:-}" == "history" ]]; then
  printf 'REVISION\tUPDATED\tSTATUS\tCHART\n'
  printf '7\t2026-11-18\tdeployed\tnexussky-0.1.0\n'
  exit 0
fi
if [[ "${MOCK_HELM_FAIL:-0}" == "1" ]]; then
  echo "mock: upgrade failed (simulated startup failure)" >&2
  exit 1
fi
exit 0
MOCK
  chmod +x "$td/helm"

  run_case() {
    local name="$1" want="$2" tag="$3" mockfail="$4"
    : > "$td/args.log"
    set +e
    (
      export PATH="$td:$PATH"
      export HELM_ARG_LOG="$td/args.log"
      export MOCK_HELM_FAIL="$mockfail"
      export IMAGE_TAG="$tag"
      bash "$0" upgrade
    ) > "$td/out.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" == "$want" ]]; then
      echo "PASS  $name"
      ok=$((ok + 1))
    else
      echo "FAIL  $name (rc=$rc, want=$want)"
      cat "$td/out.log"
      fail=$((fail + 1))
    fi
  }

  run_case "未设置 IMAGE_TAG 应拒绝" 1 "" 0
  run_case "latest 应拒绝" 1 "latest" 0
  run_case "0.1.0 应拒绝" 1 "0.1.0" 0
  run_case "非 sha/semver 应拒绝" 1 "v1.0.7" 0

  run_case "sha tag 应执行" 0 "sha-a1b2c3d" 0
  if grep -q -- '--atomic' "$td/args.log" \
     && grep -q -- '--wait' "$td/args.log" \
     && grep -q -- '--timeout 10m' "$td/args.log" \
     && grep -q -- '--set imageTag=sha-a1b2c3d' "$td/args.log"; then
    echo "PASS  --atomic/--wait/--timeout/--set imageTag 均传入 helm"
    ok=$((ok + 1))
  else
    echo "FAIL  helm 参数不完整"
    cat "$td/args.log"
    fail=$((fail + 1))
  fi

  run_case "semver 应执行" 0 "1.0.7" 0
  run_case "helm 升级失败应透传非 0" 1 "sha-a1b2c3d" 1

  echo "self-test: $ok/$((ok + fail)) passed"
  [[ "$fail" -eq 0 ]]
}

case "${1:-}" in
  upgrade)   do_upgrade ;;
  --self-test) self_test ;;
  *)         usage; exit 2 ;;
esac
