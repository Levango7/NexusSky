#!/usr/bin/env bash
# MJPEG 取流链路 e2e：**在非 dev 配置下**跑通整条链路（CI 与本机通用）。
#
# 为什么要有这个脚本
# ------------------
# `scripts/e2e-mjpeg-preview.py` 里有一条硬断言：「匿名取流必须被 401 拒掉」。
# 这条断言**只在非 dev 后端上成立** —— dev 模式下 Spring Security 链是
# `anyRequest().permitAll()`，且 `StreamTokenFilter` 直接跳过，匿名能拿到真实帧流。
# 而仓库里启动后端的"标准姿势"（CI 的 sdk-integration job 也是）就是 dev profile，
# 于是：这条断言要么没人跑（从前如此），要么红在错的原因上。
#
# 本脚本把前置条件固化成可复现的配方：
#   1. 先用 dev 模式起一次后端（H2 文件库），通过 API 建一个可登录的 ADMIN 用户
#      —— 登录走数据库，`aerofleet.security.users` 内存配置在有库时会被忽略；
#   2. **优雅**停掉它（SIGTERM，让 H2 正常落盘，否则建好的用户会丢）；
#   3. 用同一份 H2 库、以 `--aerofleet.security.dev-mode=false` 起"生产同款"后端
#      （鉴权链与流令牌过滤器都真实生效，匿名 401 这条断言才有意义）；
#   4. 起一个合成帧源（CI 没有摄像头；见 scripts/synthetic-frame-source.py）；
#   5. 跑 e2e-mjpeg-preview.py，任何断言失败即整体失败。
#
# 用法：
#   bash scripts/e2e-mjpeg-ci.sh                     # 需要 cloud-backend 的 jar 已编好
#   bash scripts/e2e-mjpeg-ci.sh --build             # 先 mvn 编一遍
#   bash scripts/e2e-mjpeg-ci.sh --backend-port 18099 --frame-port 18077
#
# 退出码：0 全过；1 有断言失败或前置不满足。
set -u -o pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

BACKEND_PORT="${BACKEND_PORT:-18099}"
FRAME_PORT="${FRAME_PORT:-18077}"
JAR="cloud-backend/target/aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar"
E2E_USER="${E2E_USER:-e2e-mjpeg-admin}"
E2E_PASSWORD="${E2E_PASSWORD:-e2e-mjpeg-Passw0rd!}"
JWT_SECRET="${JWT_SECRET:-e2e-mjpeg-local-only-jwt-secret-0123456789}"
LOG_DIR="${LOG_DIR:-/tmp/nexussky-mjpeg-e2e}"

BUILD=0
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --backend-port=*) BACKEND_PORT="${arg#*=}" ;;
    --frame-port=*) FRAME_PORT="${arg#*=}" ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

PY="${PY:-python3}"
command -v "$PY" >/dev/null 2>&1 || PY=python
command -v "$PY" >/dev/null 2>&1 || {
  echo "找不到 python（CI 上是 python3；本机可以用 PY=/path/to/python 指定）" >&2
  exit 2
}
command -v curl >/dev/null 2>&1 || { echo "找不到 curl" >&2; exit 2; }

mkdir -p "$LOG_DIR"
BACKEND_LOG="$LOG_DIR/backend.log"
FRAME_LOG="$LOG_DIR/frame-source.log"
BACKEND_PID=""
FRAME_PID=""

cleanup() {
  if [ -n "$FRAME_PID" ]; then kill "$FRAME_PID" 2>/dev/null || true; fi
  if [ -n "$BACKEND_PID" ]; then kill "$BACKEND_PID" 2>/dev/null || true; fi
  sleep 1
}
trap cleanup EXIT

wait_health() {  # $1=port  $2=最大秒数
  # 判据用**状态码 200**，不看 body 里的 "status":"UP"：
  # 本机 Redis 不通时 RedisHealthContributor 会把 health 打成 503，
  # 而后端其实早就起来了 —— 那会把早就起来的后端误杀（docs/contributing-guide.md §2.5 的约定）。
  # dev profile 关掉了 redis 健康检查（management.health.redis.enabled=false），
  # 但这里不赌这一点，只认「端点能应答」。
  local port="$1" limit="$2" i=0 code=""
  while [ "$i" -lt "$limit" ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$port/actuator/health" 2>/dev/null || echo 000)
    if [ "$code" = "200" ]; then
      return 0
    fi
    if [ -n "$BACKEND_PID" ] && ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      echo "后端进程已经死了，日志尾部：" >&2
      tail -30 "$BACKEND_LOG" >&2 || true
      return 1
    fi
    i=$((i + 1))
    sleep 2
  done
  echo "后端在 ${limit}s 内没就绪（最后状态码 ${code:-none}），日志尾部：" >&2
  tail -30 "$BACKEND_LOG" >&2 || true
  return 1
}

stop_backend_gracefully() {
  [ -n "$BACKEND_PID" ] || return 0
  kill "$BACKEND_PID" 2>/dev/null || true
  local i=0
  while [ "$i" -lt 30 ] && kill -0 "$BACKEND_PID" 2>/dev/null; do
    i=$((i + 1)); sleep 1
  done
  if kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "后端 30s 内没退出，强制结束（注意：H2 可能没落盘）" >&2
    kill -9 "$BACKEND_PID" 2>/dev/null || true
  fi
  wait "$BACKEND_PID" 2>/dev/null || true
  BACKEND_PID=""
}

if [ "$BUILD" = "1" ]; then
  echo "== 0/5 编 jar =="
  mvn -B -pl cloud-backend -am package -DskipTests || { echo "编译失败" >&2; exit 1; }
fi
[ -f "$JAR" ] || { echo "找不到 $JAR（先 --build 或先跑 mvn package）" >&2; exit 2; }

# 干净起步：删掉旧的 H2 文件库，免得用上一轮的残留状态（用户是上一轮建的）
rm -rf ./data/aerofleet.mv.db ./data/aerofleet.trace.db 2>/dev/null || true

echo "== 1/5 先用 dev 模式起后端，建一个可登录的 ADMIN 用户 =="
java -jar "$JAR" \
  --spring.profiles.active=dev \
  --server.port="$BACKEND_PORT" \
  --aerofleet.udp-port=14551 \
  --aerofleet.drone-port=14544 \
  --aerofleet.drone-extra-ports=14545,14546,14547 \
  > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
wait_health "$BACKEND_PORT" 120 || exit 1

CREATE_BODY=$(printf '{"username":"%s","password":"%s","role":"ADMIN"}' "$E2E_USER" "$E2E_PASSWORD")
CREATE_CODE=$(curl -s -o "$LOG_DIR/create-user.json" -w '%{http_code}' \
  -X POST "http://localhost:$BACKEND_PORT/api/v1/users" \
  -H 'Content-Type: application/json' -d "$CREATE_BODY" || echo 000)
echo "   建号 HTTP $CREATE_CODE （200=新建；409/400=已存在，都可接受）"
if [ "$CREATE_CODE" = "000" ]; then
  echo "建号请求没发出去" >&2; cat "$LOG_DIR/create-user.json" 2>/dev/null >&2 || true; exit 1
fi

echo "== 2/5 优雅停掉 dev 实例（让 H2 落盘）=="
stop_backend_gracefully

echo "== 3/5 以生产同款配置重启（dev-mode=false => 鉴权链与流令牌过滤器都生效）=="
java -jar "$JAR" \
  --spring.profiles.active=dev \
  --server.port="$BACKEND_PORT" \
  --aerofleet.security.dev-mode=false \
  --aerofleet.security.jwt-secret="$JWT_SECRET" \
  --aerofleet.udp-port=14551 \
  --aerofleet.drone-port=14544 \
  --aerofleet.drone-extra-ports=14545,14546,14547 \
  > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
wait_health "$BACKEND_PORT" 120 || exit 1

echo "== 4/5 起合成帧源（CI 没有摄像头）=="
"$PY" scripts/synthetic-frame-source.py --port "$FRAME_PORT" > "$FRAME_LOG" 2>&1 &
FRAME_PID=$!
i=0
while [ "$i" -lt 20 ]; do
  if curl -sf "http://127.0.0.1:$FRAME_PORT/healthz" >/dev/null 2>&1; then break; fi
  i=$((i + 1)); sleep 1
done
curl -sf "http://127.0.0.1:$FRAME_PORT/healthz" >/dev/null 2>&1 || {
  echo "帧源没起来：" >&2; cat "$FRAME_LOG" >&2 || true; exit 1; }

echo "== 5/5 跑 MJPEG 链路 e2e =="
set +e
"$PY" scripts/e2e-mjpeg-preview.py \
  --backend "http://localhost:$BACKEND_PORT" \
  --snapshot-url "http://127.0.0.1:$FRAME_PORT/snapshot" \
  --username "$E2E_USER" \
  --password "$E2E_PASSWORD" \
  --frames 4
RC=$?
set -e
if [ "$RC" -ne 0 ]; then
  echo "" >&2
  echo "MJPEG 链路 e2e 失败（exit=$RC）。后端日志尾部：" >&2
  tail -40 "$BACKEND_LOG" >&2 || true
fi
exit $RC
