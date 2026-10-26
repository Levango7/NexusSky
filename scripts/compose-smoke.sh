#!/usr/bin/env bash
# Compose smoke：起 deploy/docker 全套（redis/postgres/cloud-backend/drone-sim/gcs-web）
# → 等 cloud-backend healthy → 探 /actuator/health 与 gcs-web SPA/反代 → 拆栈。
#
# 为什么需要这个脚本（GAPS §二「docker-compose 完整性验证」）：
#   该编排三服务用 host 网络，本机 Windows Docker Desktop 的 host 网络不完整，
#   `compose up` 无法如实运行；Linux CI runner 是唯一可复现的运行环境。
#   此前只有 `docker compose config -q` 的配置层审查，没有运行层证据。
#
# 设计要点：
#   - 用独立 project 名（-p）隔离，避免与同 runner 上其他 compose 冲突；
#   - 失败时打印 compose ps + logs，再无条件 down -v --remove-orphans（不留脏容器）；
#   - 环境变量用**内联导出**而不是 .env 文件：仓库 hygiene 门禁禁止提交 .env。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/deploy/docker/docker-compose.yml"
PROJECT="nexussky-smoke-$$"
HEALTH_URL="http://127.0.0.1:28080/actuator/health"
WEB_URL="http://127.0.0.1:23000/"

# --- CI 专用测试凭据（非生产；只在本次 smoke 的容器生命周期内存在）---
# JWT secret 必须 >=32 字节（JwtTokenProvider.MIN_SECRET_BYTES）；
# 加密密钥任意非空即可（内部 SHA-256 派生 AES-128）；users 格式 username:password[:ROLE]。
export AEROFLEET_SECURITY_JWT_SECRET="smoke-test-jwt-secret-0123456789abcdef0123456789"
export AEROFLEET_JWT_SECRET="$AEROFLEET_SECURITY_JWT_SECRET"
export AEROFLEET_USERS="admin:smoke-test-password:ADMIN"
export AEROFLEET_ENCRYPTION_KEY="smoke-test-encryption-key-0123456789abcdef"
# 必须与 compose 里 postgres 的 POSTGRES_PASSWORD 一致
export SPRING_DATASOURCE_PASSWORD="aerofleet_dev_password"

compose() { docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"; }

cleanup() {
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "::group::compose ps (failure diagnostics)"
    compose ps || true
    echo "::endgroup::"
    echo "::group::compose logs --tail=200 (failure diagnostics)"
    compose logs --no-color --tail=200 || true
    echo "::endgroup::"
  fi
  echo "=== tear down (down -v --remove-orphans) ==="
  compose down -v --remove-orphans || true
  exit "$rc"
}
trap cleanup EXIT

echo "=== compose config validation ==="
compose config -q

echo "=== build + up -d ==="
compose up -d --build

echo "=== wait for cloud-backend /actuator/health (max 180s) ==="
ok=0
for i in $(seq 1 60); do
  if body=$(curl -fsS "$HEALTH_URL" 2>/dev/null); then
    echo "[$i] $body"
    if printf '%s' "$body" | grep -q '"status":"UP"'; then
      ok=1
      break
    fi
  else
    echo "[$i] not ready"
  fi
  sleep 3
done
if [ "$ok" -ne 1 ]; then
  echo "::error::cloud-backend 未在 180s 内 healthy（$HEALTH_URL）" >&2
  exit 1
fi

echo "=== gcs-web SPA root ==="
code=$(curl -fsS -o /dev/null -w '%{http_code}' "$WEB_URL")
echo "GET $WEB_URL -> $code"
[ "$code" = "200" ] || { echo "::error::gcs-web SPA 返回 $code" >&2; exit 1; }

echo "=== gcs-web reverse proxy -> backend /actuator/health ==="
code=$(curl -fsS -o /dev/null -w '%{http_code}' "http://127.0.0.1:23000/actuator/health")
echo "GET :23000/actuator/health -> $code"
[ "$code" = "200" ] || { echo "::error::gcs-web 反代 /actuator/health 返回 $code" >&2; exit 1; }

echo "=== drone-sim container running ==="
compose ps --status running --services | grep -qx 'drone-sim' \
  || { echo "::error::drone-sim 未处于 running" >&2; exit 1; }

echo "SMOKE OK"
