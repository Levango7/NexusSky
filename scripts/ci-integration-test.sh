#!/bin/bash
# =============================================================================
# NexusSky CI 集成测试（两组 profile，各跑各的断言）
#
# 2026-09-29 重写要点：
#   旧版只在 dev profile（aerofleet.security.dev-mode=true → SecurityConfig 走
#   anyRequest().permitAll()）下跑一遍，然后把"登录拿到 token"这条鉴权断言写成
#   `TOKEN=$(...) || true` + 失败只 echo ⚠️ —— 也就是说**整个 job 永远不会因为
#   鉴权坏掉而变红**：permitAll 下登录接口本来就可有可无。这是名义门禁。
#   现在拆成两趟真实断言：
#     Pass A（dev profile，dev-mode=true）  ：无鉴权冒烟 —— 文档/指标类端点可用；
#     Pass B（dev profile + dev-mode=false）：鉴权生效 —— 匿名 401 / 错误口令 401 /
#                                             坏 token 401 / 合法 token 200。
#   任何一条断言不匹配即 FAILED=1，脚本末尾 exit 1；不再有任何 || true / echo ⚠️ 继续。
#
# 为什么 Pass B 不直接换 profile：
#   application-prod.properties / application-staging.properties 需要 PostgreSQL、
#   AEROFLEET_JWT_SECRET、AEROFLEET_USERS 等外部资源，CI 里没有这些服务；
#   而 dev profile 提供 H2 + Flyway + springdoc + jwt.generate-keys=true
#   （RS256 密钥对自动生成，Pass B 的 decoder 才能验签）。
#   所以 Pass B = dev profile 打底 + 命令行把 dev-mode 关掉，
#   只翻转"鉴权"这一个维度，其余环境等价。
#   （RBAC 授权层不在本脚本覆盖范围：aerofleet.security.rbac-enabled 保持 dev 默认
#   false，链级 RBAC/租户断言由 cloud-backend 的 HttpAuthChainTest 负责。）
#
# 用法：在仓库根目录执行 bash scripts/ci-integration-test.sh
# 退出码：0=全部断言通过，1=有断言失败或后端未就绪。
# =============================================================================
set -euo pipefail

echo "=== NexusSky CI Integration Test ==="

JAR="cloud-backend/target/aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar"
# Pass B 的凭据来源：DB 引导账号（AdminBootstrapRunner）。
# 为什么不用 aerofleet.security.users 的内存用户：AuthController.login 里
#   if (userRepository != null) { 查 DB；查不到就 401，**不会回退内存用户** }
# 而 dev profile 有 H2 + JPA ⇒ userRepository 恒非空 ⇒ 内存用户分支是死路
# （实测：--aerofleet.security.users=ci_gateway_user:xxx 登录返回 401 invalid credentials）。
# AdminBootstrapRunner 只在显式配置 bootstrap-admin-password 时介入（长度 ≥8，
# 落库 role=ADMIN/enabled=true），这正好给 CI 一个可复现、不污染仓库内种子账号的凭据。
# 用户名用 CI 专用名，避免覆盖仓库 H2 库里已有的 admin 记录。
CI_USER="ci_gateway_admin"
CI_PASSWORD="CiGateBootPassw0rd"
A_PORT="${A_PORT:-8080}"
B_PORT="${B_PORT:-8081}"
FAILED=0
PID_A=""
PID_B=""

# 任何硬失败（set -e 中途退出、就绪超时 exit 1）都不该把 java 进程留在工作区里：
# 两趟各自绑 8080/8081 + UDP 14550，残留进程会让复跑/后续步骤行为不可预期。
cleanup_on_exit() {
    local p
    for p in "$PID_A" "$PID_B"; do
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
            kill "$p"
        fi
    done
}
trap cleanup_on_exit EXIT

# ───────────────────────── 工具函数 ─────────────────────────
# 只取 HTTP 状态码；连接层失败时 curl 自身输出 000 → 断言按"不等于期望值"判红，
# 不存在"取不到就当通过"的分支。
http_status() {
    local out
    out=$(curl -s -o /dev/null -w '%{http_code}' "$@" 2>/dev/null) || out="000"
    printf '%s' "$out"
}

assert_status() { # <desc> <expected-code> <actual-code>
    if [ "$2" = "$3" ]; then
        echo "   ✅ $1 → HTTP $3"
    else
        echo "   ❌ $1：期望 HTTP $2，实际 $3"
        FAILED=1
    fi
}

assert_true() { # <desc> <0/1 之类的事实>
    if [ -n "$2" ]; then
        echo "   ✅ $1"
    else
        echo "   ❌ $1（实际为空）"
        FAILED=1
    fi
}

start_backend() { # <日志文件> <端口> <Spring 参数...> —— 打印 PID 到 stdout
    local log="$1"; shift
    local port="$1"; shift
    java -jar "$JAR" --server.port="$port" "$@" > "$log" 2>&1 &
    printf '%s' "$!"
}

wait_ready() { # <pid> <端口> <日志> <名称> —— 就绪返回 0，超时或进程退出返回 1
    local pid="$1" port="$2" log="$3" name="$4"
    for i in $(seq 1 60); do
        if curl -sf "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
            echo "   ✅ ${name} 就绪（第 ${i} 次探测，约 $((i * 2))s）"
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "   ❌ ${name} 进程（PID $pid）已退出 —— 启动失败"
            echo "   --- ${log} 末尾 40 行 ---"
            tail -40 "$log"
            return 1
        fi
        sleep 2
    done
    echo "   ❌ ${name} 在 120s 内未就绪"
    echo "   --- ${log} 末尾 40 行 ---"
    tail -40 "$log"
    return 1
}

stop_backend() { # <pid> <名称> —— 必须确认进程真退出，否则下一趟会撞 TCP 8080/UDP 14550
    local pid="$1" name="$2"
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "   ℹ️ ${name}（PID $pid）已不在运行"
        return 0
    fi
    kill "$pid"
    for i in $(seq 1 30); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "   ${name} 已停止（${i}s）"
            return 0
        fi
        sleep 1
    done
    echo "   ❌ ${name}（PID $pid）SIGTERM 后 30s 未退出，强制 SIGKILL"
    kill -9 "$pid"
    FAILED=1
    return 0
}

# ───────────────────────── 1. 构建 ─────────────────────────
echo "[1/5] Building all modules..."
mvn -B -DskipTests package
if [ ! -f "$JAR" ]; then
    echo "❌ 构建产物缺失：$JAR"
    exit 1
fi

# ───────────────────────── 2. Pass A：无鉴权冒烟（dev-mode=true） ─────────────────────────
echo "[2/5] Pass A —— dev profile（dev-mode=true）无鉴权冒烟..."
LOG_A=$(mktemp)
PID_A=$(start_backend "$LOG_A" "$A_PORT" --spring.profiles.active=dev)
if ! wait_ready "$PID_A" "$A_PORT" "$LOG_A" "Pass A cloud-backend"; then
    stop_backend "$PID_A" "Pass A cloud-backend"
    rm -f "$LOG_A"
    exit 1
fi

echo "   --- Pass A 断言（profile: dev-mode=true → SecurityConfig 全放行）---"
assert_status "/actuator/health" "200" "$(http_status "http://localhost:${A_PORT}/actuator/health")"
assert_status "/v3/api-docs" "200" "$(http_status "http://localhost:${A_PORT}/v3/api-docs")"
assert_status "/swagger-ui.html" "200" "$(http_status -L "http://localhost:${A_PORT}/swagger-ui.html")"
assert_status "/actuator/prometheus" "200" "$(http_status "http://localhost:${A_PORT}/actuator/prometheus")"
# 对照组：同一端点在 dev-mode=true 下匿名可访问。Pass B 必须给出 401，
# 两趟对比才证明 Pass B 的 401 来自鉴权开关而不是"服务根本没起来"。
assert_status "[对照] 匿名 GET /api/v1/drones（dev-mode=true）" "200" \
    "$(http_status "http://localhost:${A_PORT}/api/v1/drones")"

stop_backend "$PID_A" "Pass A cloud-backend"

# ───────────────────────── 3. Pass B：鉴权生效（dev-mode=false） ─────────────────────────
echo "[3/5] Pass B —— 同一 profile 但 dev-mode=false，鉴权必须真实生效..."
LOG_B=$(mktemp)
PID_B=$(start_backend "$LOG_B" "$B_PORT" \
    --spring.profiles.active=dev \
    --aerofleet.security.dev-mode=false \
    --aerofleet.security.bootstrap-admin-username="$CI_USER" \
    --aerofleet.security.bootstrap-admin-password="$CI_PASSWORD")
if ! wait_ready "$PID_B" "$B_PORT" "$LOG_B" "Pass B cloud-backend"; then
    stop_backend "$PID_B" "Pass B cloud-backend"
    rm -f "$LOG_B"
    exit 1
fi

echo "   --- Pass B 断言 1：匿名访问受保护端点必须 401 ---"
assert_status "匿名 GET /api/v1/drones" "401" \
    "$(http_status "http://localhost:${B_PORT}/api/v1/drones")"

echo "   --- Pass B 断言 2：错误口令必须 401（证明 401 不是恒 401 的假绿）---"
assert_status "POST /api/v1/auth/login（错误口令）" "401" \
    "$(http_status -X POST -H 'Content-Type: application/json' \
        -d "{\"username\":\"${CI_USER}\",\"password\":\"wrong-password\"}" \
        "http://localhost:${B_PORT}/api/v1/auth/login")"

echo "   --- Pass B 断言 3：伪造 token 必须 401 ---"
assert_status "GET /api/v1/drones（Bearer 不存在的签名）" "401" \
    "$(http_status -H 'Authorization: Bearer not.a.valid.jwt' \
        "http://localhost:${B_PORT}/api/v1/drones")"

echo "   --- Pass B 断言 4：合法凭据能换到 token ---"
# 一次请求同时拿状态码与响应体（末尾追加一行 %{http_code} 再拆出来），
# 避免为了取状态码多发一次登录撞登录限流。
LOGIN_RAW=$(curl -sS -w $'\n%{http_code}' -X POST "http://localhost:${B_PORT}/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${CI_USER}\",\"password\":\"${CI_PASSWORD}\"}")
LOGIN_STATUS=${LOGIN_RAW##*$'\n'}
LOGIN_BODY=${LOGIN_RAW%$'\n'*}
assert_status "POST /api/v1/auth/login（正确口令）" "200" "$LOGIN_STATUS"
echo "   登录响应: ${LOGIN_BODY}"
# sed -n .../p 在"没有 token 字段"时返回 0 且输出空 → 交给 assert_true 判红；
# 用 grep -o 的话 pipefail 会让脚本在这里直接中断，跳过后续断言。
TOKEN=$(printf '%s' "$LOGIN_BODY" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
assert_true "/api/v1/auth/login 返回非空 JWT" "$TOKEN"

echo "   --- Pass B 断言 5：带合法 token 访问受保护端点必须 200 ---"
if [ -n "$TOKEN" ]; then
    assert_status "GET /api/v1/drones（Bearer 合法 token）" "200" \
        "$(http_status -H "Authorization: Bearer ${TOKEN}" \
            "http://localhost:${B_PORT}/api/v1/drones")"
else
    echo "   ❌ 无 token，跳过带凭据访问断言（登录已判红）"
    FAILED=1
fi

stop_backend "$PID_B" "Pass B cloud-backend"
rm -f "$LOG_A" "$LOG_B"

# ───────────────────────── 4. 结果 ─────────────────────────
echo "[4/5] Cleanup done."
echo "[5/5] 汇总"
if [ "$FAILED" -ne 0 ]; then
    echo "=== FAIL: 集成测试存在未通过的断言（见上方 ❌）==="
    exit 1
fi
echo "=== All integration tests passed ==="
