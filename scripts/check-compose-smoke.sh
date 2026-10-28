#!/usr/bin/env bash
# docker-compose 根编排 smoke（GAPS §二「docker-compose 完整性验证」的收口）。
#
# 用途：在 Linux + Docker 环境里如实 `docker compose up` 根编排（host 网络 +
# 挂载 jar），断言四件事：
#   1. 后端 HTTP 栈应答（/actuator/health 200，或安全面 401/403——只要不是 000）；
#   2. drone-sim 打出 "listening on UDP"（进程真起来了，不是 compose Started 回显）；
#   3. 五个容器在超时窗内保持 Running（崩溃重启不算过）；
#   4. DB 模式真能登录（bootstrap admin）：POST /api/v1/auth/login 返回 200 且带 token。
#      此项针对 2026-10-28 实测缺口：本编排带 postgres ⇒ 登录走 UserRepository，
#      库内种子 admin 已被 V20 停用，只配 AEROFLEET_USERS 不配
#      AEROFLEET_SECURITY_BOOTSTRAP_ADMIN_PASSWORD 时 admin 登录恒 401。
#      此前 smoke 只探 /actuator/health，这类缺口从未暴露（PR #195 修配置，本脚本补门禁）。
#
# 前置：先 mvn -pl drone-sim,cloud-backend -am package -DskipTests（jar 挂载进容器）。
# 退出码：0=全过；1=有断言失败（失败时自动 dump 各容器日志尾部）。
#
# CI 里由 compose-smoke job 调用；本机复跑：bash scripts/check-compose-smoke.sh [超时秒]
set -u
cd "$(dirname "$0")/.."

TIMEOUT=${1:-420}
FAILED=0

# 登录断言的凭据：与 docker-compose.yml 的 bootstrap 值对齐；CI/换密时用环境变量覆盖，
# 不必改脚本（默认值只用于本仓的 compose 开发编排，生产部署不读这里）。
LOGIN_USER=${COMPOSE_SMOKE_ADMIN_USER:-admin}
LOGIN_PASSWORD=${COMPOSE_SMOKE_ADMIN_PASSWORD:-compose-dev-password}

note() { echo "  [compose] $*"; }
fail() { echo "  [FAIL] $*"; FAILED=1; }

cleanup() {
  docker compose logs --tail 60 > /tmp/compose-smoke-logs.txt 2>&1 || true
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

note "compose config 校验"
docker compose -f docker-compose.yml config -q || { fail "compose config 解析失败"; exit 1; }

note "compose up（host 网络；postgres + redis + drone-sim + cloud-backend + gcs-web）"
docker compose up -d || { fail "compose up 失败"; exit 1; }

# ---- 1. 后端 HTTP 栈 ----
# /actuator/health 200 最理想；安全面 401/403 同样证明 HTTP 栈活着（比"连不上"强得多）。
note "等待后端 HTTP 应答（Flyway 迁移 + Spring 启动，预算 ${TIMEOUT}s）"
backend_code=""
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  backend_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://localhost:8080/actuator/health 2>/dev/null)
  if [ "$backend_code" = "200" ]; then break; fi
  sleep 3; elapsed=$((elapsed + 3))
done
if [ "$backend_code" = "200" ]; then
  note "后端 /actuator/health = 200"
elif [ "$backend_code" = "401" ] || [ "$backend_code" = "403" ]; then
  fail "后端 HTTP 应答 $backend_code（health 不应要求认证——暴露面口径变了？）"
else
  fail "后端 ${TIMEOUT}s 内无 HTTP 应答（最后状态码 $backend_code）"
fi

# ---- 2. drone-sim 监听 UDP ----
note "断言 drone-sim 已监听 UDP 14540"
sim_up=0
elapsed=0
while [ "$elapsed" -lt 60 ]; do
  if docker compose logs drone-sim 2>/dev/null | grep -q "listening on UDP"; then
    sim_up=1; break
  fi
  sleep 3; elapsed=$((elapsed + 3))
done
[ "$sim_up" = "1" ] && note "drone-sim listening on UDP" || fail "drone-sim 60s 内未打出 listening on UDP"

# ---- 3. 容器存活（含崩溃重启检测） ----
note "断言容器存活（RestartCount=0 且 Running）"
for svc in postgres redis drone-sim cloud-backend gcs-web; do
  state=$(docker inspect --format '{{.State.Running}} {{.RestartCount}}' \
      "$(docker compose ps -q "$svc" 2>/dev/null)" 2>/dev/null || echo "missing")
  if [ "$state" = "true 0" ]; then
    note "$svc Running"
  else
    fail "$svc 容器状态异常：$state（期望 'true 0'）"
  fi
done

# ---- 4. DB 模式登录（bootstrap admin） ----
# 一次调用即可：健康检查已 200，说明 Spring 上下文就绪、AdminBootstrapRunner 已跑完。
# 只打一发也避免撞 AuthController 的每 IP 10 次/分钟登录限流。
note "断言 DB 模式登录可用（bootstrap admin）"
payload=$(printf '{"username":"%s","password":"%s"}' "$LOGIN_USER" "$LOGIN_PASSWORD")
resp=$(curl -s -w '\n%{http_code}' --max-time 8 -X POST \
  -H 'Content-Type: application/json' --data "$payload" \
  http://localhost:8080/api/v1/auth/login 2>/dev/null)
login_code=$(printf '%s' "$resp" | tail -n1)
login_body=$(printf '%s' "$resp" | sed '$d')
if [ "$login_code" = "200" ] && printf '%s' "$login_body" | grep -q '"token"'; then
  note "admin 登录 200 且返回 token"
elif [ "$login_code" = "401" ]; then
  fail "admin 登录 401（AEROFLEET_SECURITY_BOOTSTRAP_ADMIN_PASSWORD 未生效？或库内用户被停用）"
else
  fail "admin 登录异常：HTTP $login_code（响应：$(printf '%s' "$login_body" | head -c 200)）"
fi

if [ "$FAILED" = "0" ]; then
  echo "=== compose smoke: ALL PASS ==="
else
  echo "=== compose smoke: FAILED（日志尾部见 /tmp/compose-smoke-logs.txt 与上方 dump） ==="
  docker compose logs --tail 30 2>&1 | tail -60
fi
exit "$FAILED"
