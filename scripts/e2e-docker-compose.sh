#!/usr/bin/env bash
# NexusSky Docker Compose 端到端验证（Bash 版，CI 兼容）
# 使用 docker-compose 启动全部服务并验证完整链路
# 前置：已执行 mvn -DskipTests package，jar 包已生成
set -u
BASE=http://localhost:8080/api/v1
FAIL=0
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose.yml"

step() { echo "== $1"; }
check() {
  if eval "$2"; then echo "   PASS: $1"; else echo "   FAIL: $1"; FAIL=1; fi
}

# ── 清理函数：确保容器不会泄漏 ──
cleanup_containers() {
  echo "== 清理容器..."
  docker-compose -f "$COMPOSE_FILE" down --remove-orphans 2>/dev/null || true
  docker rm -f nexussky-redis 2>/dev/null || true
}

# 注册退出时清理
trap cleanup_containers EXIT

# ═══════════════════════════════════════════════════
# 场景 1：启动全部服务
# ═══════════════════════════════════════════════════
step "启动 Docker Compose 全部服务"
# 先清理可能残留的容器
cleanup_containers
sleep 2

# 启动 redis（docker-compose.yml 未包含，需要单独启动）
docker run -d --name nexussky-redis --network host redis:7-alpine 2>/dev/null || true
sleep 2

# 启动 docker-compose 服务
# 原写法：docker-compose up -d 之后夹了一行 sleep 5，再用 "[ $? -eq 0 ]" 断言 ——
# $? 取的是 sleep 的退出码（恒 0），所以这条断言永远 PASS，compose 起不来也看不见。
# $? 必须紧跟被检查的命令取值。
COMPOSE_UP_LOG=$(mktemp)
docker-compose -f "$COMPOSE_FILE" up -d > "$COMPOSE_UP_LOG" 2>&1
COMPOSE_UP_STATUS=$?
sleep 5
check "docker-compose up -d 退出码为 0" "[ $COMPOSE_UP_STATUS -eq 0 ]"
if [ "$COMPOSE_UP_STATUS" -ne 0 ]; then
  echo "   --- docker-compose up 输出（末尾 30 行）---"
  tail -30 "$COMPOSE_UP_LOG"
fi
rm -f "$COMPOSE_UP_LOG"

# ═══════════════════════════════════════════════════
# 场景 2：等待服务就绪（健康检查轮询）
# ═══════════════════════════════════════════════════
step "等待 cloud-backend 就绪（/actuator/health 轮询）"
BACKEND_READY=0
for i in $(seq 1 60); do
  HEALTH=$(curl -sf -m 5 http://localhost:8080/actuator/health 2>/dev/null || echo "")
  if echo "$HEALTH" | grep -q '"status":"UP"'; then
    BACKEND_READY=1
    echo "   backend 就绪（第 $i 次探测）"
    break
  fi
  sleep 2
done
check "cloud-backend 健康检查返回 UP" "[ \"$BACKEND_READY\" = \"1\" ]"
if [ "$BACKEND_READY" != "1" ]; then
  echo "   backend 未就绪，尝试获取诊断信息..."
  curl -s -m 5 http://localhost:8080/actuator/health || echo "(no response)"
  exit 1
fi

# ═══════════════════════════════════════════════════
# 场景 3：验证 cloud-backend /actuator/health 返回 UP
# ═══════════════════════════════════════════════════
step "验证 /actuator/health 返回 UP"
HEALTH=$(curl -sf -m 5 http://localhost:8080/actuator/health)
echo "   $HEALTH"
check "/actuator/health status=UP" "echo '$HEALTH' | grep -q '\"status\":\"UP\"'"

# ═══════════════════════════════════════════════════
# 场景 4：验证 gcs-web 返回 HTML 页面
# ═══════════════════════════════════════════════════
step "验证 gcs-web 返回 HTML 页面"
WEB_OK=0
for i in $(seq 1 30); do
  WEB_RESP=$(curl -sf -m 5 http://localhost:5173 2>/dev/null || echo "")
  if echo "$WEB_RESP" | grep -q '<html'; then
    WEB_OK=1
    break
  fi
  sleep 2
done
check "gcs-web 返回 HTML 页面（200 + <html>）" "[ \"$WEB_OK\" = \"1\" ]"

# ═══════════════════════════════════════════════════
# 场景 5：验证 drone-sim 被 cloud-backend 发现
# ═══════════════════════════════════════════════════
step "验证 drone-sim 被 cloud-backend 发现（/api/v1/drones 非空）"
SYSID=""
for i in $(seq 1 30); do
  COUNT=$(curl -sf -m 5 "$BASE/drones" 2>/dev/null | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
  if [ "$COUNT" != "0" ] && [ -n "$COUNT" ]; then
    SYSID=$(curl -sf -m 5 "$BASE/drones" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['sysid'])" 2>/dev/null)
    break
  fi
  sleep 2
done
check "机队列表非空（drone-sim 已被发现）" "[ -n \"$SYSID\" ]"
echo "   sysid=$SYSID"
if [ -z "$SYSID" ]; then
  echo "   drone-sim 未被发现，终止测试"
  exit 1
fi

# ═══════════════════════════════════════════════════
# 场景 6：执行基本飞行操作（ARM → takeoff → RTL）
# ═══════════════════════════════════════════════════
step "执行基本飞行操作"

# ARM
R=$(curl -sf -m 20 -X POST -H 'Content-Type: application/json' -d '{"type":"arm"}' "$BASE/drones/$SYSID/commands")
echo "   ARM: $R"
check "ARM ok" "echo '$R' | grep -q '\"status\":\"ok\"' || echo '$R' | grep -q '\"status\": \"ok\"'"

# Takeoff
R=$(curl -sf -m 20 -X POST -H 'Content-Type: application/json' -d '{"type":"takeoff","alt":30}' "$BASE/drones/$SYSID/commands")
echo "   takeoff: $R"
check "takeoff ok" "echo '$R' | grep -q '\"status\":\"ok\"' || echo '$R' | grep -q '\"status\": \"ok\"'"

sleep 5

# RTL
R=$(curl -sf -m 20 -X POST -H 'Content-Type: application/json' -d '{"type":"rtl"}' "$BASE/drones/$SYSID/commands")
echo "   RTL: $R"
check "RTL ok" "echo '$R' | grep -q '\"status\":\"ok\"' || echo '$R' | grep -q '\"status\": \"ok\"'"

# ═══════════════════════════════════════════════════
# 场景 7：验证飞行日志记录（/api/v1/flightlog）
# ═══════════════════════════════════════════════════
step "验证飞行日志记录（/api/v1/flightlog）"
sleep 3
FLOG=$(curl -sf -m 5 "$BASE/flightlog")
FLOGN=$(echo "$FLOG" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
echo "   日志条目数: $FLOGN"
check "飞行日志非空" "[ \"$FLOGN\" -ge 1 ] 2>/dev/null"

# ═══════════════════════════════════════════════════
# 清理容器（trap 已注册，此处显式调用确保日志可见）
# ═══════════════════════════════════════════════════
step "清理容器"
cleanup_containers
# 原写法是 check "容器已清理" "true" —— 恒真假断言，任何清理失败都会打 PASS。
# cleanup_containers 内部用 `|| true` 吞掉 docker-compose down 的错误（teardown 要幂等，
# 不该把清理错误盖过真实测试结果），所以这里改为断言**清理后的真实状态**。
# 查询命令本身失败时不能当成"没有残留"，显式判失败。
COMPOSE_PS_OUT=$(docker-compose -f "$COMPOSE_FILE" ps -q 2>/dev/null)
COMPOSE_PS_STATUS=$?
if [ $COMPOSE_PS_STATUS -ne 0 ]; then
  echo "   ❌ docker-compose ps 查询失败（退出码 $COMPOSE_PS_STATUS），无法确认容器已清理"
  FAIL=1
else
  COMPOSE_LEFTOVER=$(printf '%s' "$COMPOSE_PS_OUT" | grep -c .)
  check "compose 项目内无残留运行容器（实测 $COMPOSE_LEFTOVER 个）" "[ \"$COMPOSE_LEFTOVER\" = \"0\" ]"
fi
REDIS_PS_OUT=$(docker ps -q -f name=nexussky-redis 2>/dev/null)
REDIS_PS_STATUS=$?
if [ $REDIS_PS_STATUS -ne 0 ]; then
  echo "   ❌ docker ps 查询失败（退出码 $REDIS_PS_STATUS），无法确认 redis 已清理"
  FAIL=1
else
  REDIS_LEFTOVER=$(printf '%s' "$REDIS_PS_OUT" | grep -c .)
  check "nexussky-redis 容器已移除（实测残留 $REDIS_LEFTOVER 个）" "[ \"$REDIS_LEFTOVER\" = \"0\" ]"
fi

# ═══════════════════════════════════════════════════
# 结果汇总
# ═══════════════════════════════════════════════════
if [ "$FAIL" = "0" ]; then
  echo "ALL DOCKER COMPOSE E2E TESTS PASSED"
  exit 0
else
  echo "DOCKER COMPOSE E2E TESTS FAILED"
  exit 1
fi