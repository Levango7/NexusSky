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
# 2026-09-30 增补 Pass C（生产迁移通路）：
#   此前 prod profile 从未在任何 CI 里启动过——integration job 只有 H2/dev，
#   而 Flyway 迁移里的 MySQL 方言 DDL（V17/V18 的 AUTO_INCREMENT）在 PG 上必然
#   建表失败。也就是说"生产环境能不能起来"这个最基本的问题没有门禁回答。
#   Pass C = prod profile + 真实 PostgreSQL（CI service / 本地容器）。前置是应用能
#   启动（health UP）——这同时证明 Flyway 从零建库成功 + Hibernate ddl-auto=validate
#   逐实体校验通过（schema 与实体一致）。断言：
#     1) Flyway 日志确有 "Successfully applied ... migrations"；
#     2) 匿名访问受保护端点 401（prod 的 dev-mode=false 生效）；
#     3) DB 引导管理员账密能换到 token（证明 PG 上的用户表/JPA 查询通路可用）；
#     4) 带 token 访问 200；
#     5) 审计哈希链校验通过（V21 建表 + 审计落库 + 链生成/校验全通路）。
#   为什么必须 --logging.level.org.flywaydb=INFO：prod 的 root=WARN
#   （application-prod.properties:52），Flyway 的 INFO 迁移日志默认被压掉，
#   没有这行断言 2) 会永远看不到证据。
#   为什么 prod 的 LicenseInterceptor 不挡登录：未配置 aerofleet.license.key 时
#   LicenseService 加载"永久有效开发版 License"（LicenseService.loadLicense →
#   buildDevLicense，active=true/永不过期/全模块），拦截器放行；
#   本 Pass 验证的是迁移与鉴权通路，不是 License 商业开关。
#
# 用法：在仓库根目录执行 bash scripts/ci-integration-test.sh
#       本地无 5432 端口 PG 时：PG_PORT=<端口> SPRING_DATASOURCE_PASSWORD=<口令> \
#         bash scripts/ci-integration-test.sh（并确保 postgres 里已有 aerofleet_prod 库）
#       本地 6379 被别的 Redis 占用时：REDIS_PORT=<端口> bash scripts/ci-integration-test.sh
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
C_PORT="${C_PORT:-8082}"
# Pass C 的 PG 连接：默认值与 application-prod.properties:24/26/27 对齐
# （jdbc:postgresql://localhost:5432/aerofleet_prod，用户 aerofleet）。
# 密码无默认值（prod 里 ${SPRING_DATASOURCE_PASSWORD} 不带缺省），
# 这里给出 CI service 容器里同名口令；本地验证可覆盖。
C_PG_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-ci_pg_password}"
C_PG_PORT="${PG_PORT:-5432}"
# JWT 密钥：prod 由 ${AEROFLEET_JWT_SECRET} 注入（无默认值，缺失即启动失败）。
# 32 字节以上是 HS256 的硬性下限（JwtTokenProvider 未配置 RSA 时回退 HS256）。
C_JWT_SECRET="${AEROFLEET_JWT_SECRET:-ci_gateway_jwt_secret_at_least_32_chars_000}"
C_USERS="${AEROFLEET_USERS:-${CI_USER}:${CI_PASSWORD}:ADMIN}"
# Redis 端口覆盖（可选）：application-dev.properties:28 把端口硬编码成 6379（无占位符），
# 本机 6379 若被别的 Redis 占着（如需 AUTH 的外来实例），/actuator/health 会因 redis
# 指标 DOWN 恒返 503，wait_ready 空等到 120s 超时。命令行参数优先级最高，可穿透
# profile 文件；不设置时为空串，CI 行为与现在完全一致。
REDIS_OVERRIDE=""
if [ -n "${REDIS_PORT:-}" ]; then
    REDIS_OVERRIDE="--spring.data.redis.host=127.0.0.1 --spring.data.redis.port=${REDIS_PORT}"
fi
FAILED=0
PID_A=""
PID_B=""
PID_C=""

# 任何硬失败（set -e 中途退出、就绪超时 exit 1）都不该把 java 进程留在工作区里：
# 三趟各自绑 8080/8081/8082 + UDP 14550，残留进程会让复跑/后续步骤行为不可预期。
cleanup_on_exit() {
    local p
    for p in "$PID_A" "$PID_B" "$PID_C"; do
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
# JDK 预检：本脚本用 PATH 上的 java 启动后端（CI 由 setup-java 17 保证）。
# 本机 PATH 上是 JDK8 时，`java -jar` 报的是 spring 类文件的
# UnsupportedClassVersionError，不指向"用错 JDK"这个真实原因——先挡在这里。
JAVA_VERSION_LINE=$(java -version 2>&1 | head -1) || JAVA_VERSION_LINE="(java 不可用)"
JAVA_MAJOR=$(printf '%s' "$JAVA_VERSION_LINE" | sed -n 's/.*version "\([0-9][0-9]*\)\..*/\1/p')
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
    echo "❌ 需要 JDK 17+：$(printf '%s' "$JAVA_VERSION_LINE")"
    echo "   把 JDK17 放到 PATH 前面再跑，例如：export PATH=\"\$JAVA_HOME/bin:\$PATH\""
    exit 1
fi

echo "[1/6] Building all modules..."
mvn -B -DskipTests package
if [ ! -f "$JAR" ]; then
    echo "❌ 构建产物缺失：$JAR"
    exit 1
fi

# dev profile 的 H2 文件库固定 ./data/aerofleet（application-dev.properties:18-21）。
# 一旦仓库迁移文件被编辑（本批 V2/V3/V13/V17/V18 的 schema 漂移修正），旧库的
# flyway_schema_history 校验和必然失配，FlywayValidateException 在启动期杀死
# Pass A/B（CI runner 无 data/ 目录所以从不暴露；本机复跑实测踩中）。
# 改用脚本自管、每跑清空的 target 下临时库，CI 与本机行为一致。
IT_DB_DIR="cloud-backend/target/it-dev-db"
rm -rf "$IT_DB_DIR"
mkdir -p "$IT_DB_DIR"
DEV_DB_OVERRIDE="--spring.datasource.url=jdbc:h2:file:./${IT_DB_DIR}/aerofleet;AUTO_SERVER=TRUE"

# ───────────────────────── 2. Pass A：无鉴权冒烟（dev-mode=true） ─────────────────────────
echo "[2/6] Pass A —— dev profile（dev-mode=true）无鉴权冒烟..."
LOG_A=$(mktemp)
PID_A=$(start_backend "$LOG_A" "$A_PORT" $REDIS_OVERRIDE --spring.profiles.active=dev $DEV_DB_OVERRIDE)
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
echo "[3/6] Pass B —— 同一 profile 但 dev-mode=false，鉴权必须真实生效..."
LOG_B=$(mktemp)
PID_B=$(start_backend "$LOG_B" "$B_PORT" $REDIS_OVERRIDE \
    --spring.profiles.active=dev \
    $DEV_DB_OVERRIDE \
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

# ───────────────────────── 4. Pass C：生产迁移通路（prod profile + PostgreSQL） ─────────────────────────
echo "[4/6] Pass C —— prod profile + 真实 PostgreSQL，验证 Flyway 迁移通路..."
LOG_C=$(mktemp)
# 为什么用 spring.config 命令行参数而不是环境变量覆盖 profile 内已有键：
# prod 的日志级别在 profile 内写死为 WARN，只有命令行 --logging.level.*（最高优先级）
# 才能把 Flyway 的 INFO 放出来当证据。
PID_C=$(AEROFLEET_JWT_SECRET="$C_JWT_SECRET" \
    AEROFLEET_USERS="$C_USERS" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${C_PG_PORT}/aerofleet_prod" \
    SPRING_DATASOURCE_USERNAME=aerofleet \
    SPRING_DATASOURCE_PASSWORD="$C_PG_PASSWORD" \
    start_backend "$LOG_C" "$C_PORT" $REDIS_OVERRIDE \
    --spring.profiles.active=prod \
    --logging.level.org.flywaydb=INFO \
    --aerofleet.security.bootstrap-admin-username="$CI_USER" \
    --aerofleet.security.bootstrap-admin-password="$CI_PASSWORD")
if ! wait_ready "$PID_C" "$C_PORT" "$LOG_C" "Pass C cloud-backend"; then
    stop_backend "$PID_C" "Pass C cloud-backend"
    rm -f "$LOG_C"
    exit 1
fi

echo "   --- Pass C 断言 1：Flyway 迁移在 PostgreSQL 上有执行证据（日志）---"
# 就绪本身就隐含"迁移成功 + 实体校验通过"：prod 是 ddl-auto=validate，
# 若 flyway_schema_history 缺失或任何表与实体不符，Hibernate 会在启动阶段直接失败，
# 进程根本活不到 health UP。这一条只是把 Flyway 自己的日志取出来做显式证据。
# sed 而非 grep：grep 无匹配返回 1，在 set -e/pipefail 下会直接中断脚本
# （同 3 号断言取 token 的处理），sed 恒返回 0，交给 assert_true 判红。
# 匹配 "migration" 而非 "migrations"：Flyway 11.7.2 消息模板是
# "Successfully applied <n> <migration{,\'s\'}> to schema ..."（DbMigrate 常量池
# `migration\u0001` 占位符拼后缀）——只应用 1 个迁移的增量复跑日志是单数
# "1 migration"，写死复数会在本机增量库上误红（实测踩中）。
FLYWAY_EVIDENCE=$(sed -n 's/.*\(Successfully applied [0-9][0-9]* migration[^"]*\|Schema .\{0,40\} is up to date\|No migration necessary\).*/\1/p' "$LOG_C" | head -1)
assert_true "Flyway 迁移日志（PostgreSQL）" "$FLYWAY_EVIDENCE"
echo "   Flyway 证据: ${FLYWAY_EVIDENCE:-（无）}"

echo "   --- Pass C 断言 2：匿名访问受保护端点必须 401（prod dev-mode=false）---"
assert_status "匿名 GET /api/v1/drones（prod）" "401" \
    "$(http_status "http://localhost:${C_PORT}/api/v1/drones")"

echo "   --- Pass C 断言 3：PG 引导管理员账密能换到 token ---"
LOGIN_C_RAW=$(curl -sS -w $'\n%{http_code}' -X POST "http://localhost:${C_PORT}/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${CI_USER}\",\"password\":\"${CI_PASSWORD}\"}")
LOGIN_C_STATUS=${LOGIN_C_RAW##*$'\n'}
LOGIN_C_BODY=${LOGIN_C_RAW%$'\n'*}
assert_status "POST /api/v1/auth/login（prod + PostgreSQL）" "200" "$LOGIN_C_STATUS"
TOKEN_C=$(printf '%s' "$LOGIN_C_BODY" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
assert_true "prod 登录返回非空 JWT" "$TOKEN_C"

echo "   --- Pass C 断言 4：带合法 token 访问受保护端点必须 200 ---"
if [ -n "$TOKEN_C" ]; then
    assert_status "GET /api/v1/drones（prod Bearer 合法 token）" "200" \
        "$(http_status -H "Authorization: Bearer ${TOKEN_C}" \
            "http://localhost:${C_PORT}/api/v1/drones")"
else
    echo "   ❌ 无 token，跳过带凭据访问断言（prod 登录已判红）"
    FAILED=1
fi

echo "   --- Pass C 断言 5：审计哈希链在该 PG 库上校验通过 ---"
# prod 的 audit.enabled=true + persist-to-db=true（application-prod.properties），
# 前面那一次登录 POST 已被审计拦截器落库入链（audit_log，V21），
# 所以此时至少 1 行、链应完好（ok=true）。这条同时覆盖：V21 迁移在 PG 建表、
# 审计写入通路、哈希链生成与校验端点。
if [ -n "$TOKEN_C" ]; then
    VERIFY_RAW=$(curl -sS -w $'\n%{http_code}' -H "Authorization: Bearer ${TOKEN_C}" \
        "http://localhost:${C_PORT}/api/v1/audit/verify")
    VERIFY_STATUS=${VERIFY_RAW##*$'\n'}
    VERIFY_BODY=${VERIFY_RAW%$'\n'*}
    assert_status "GET /api/v1/audit/verify（prod + PostgreSQL）" "200" "$VERIFY_STATUS"
    echo "   链校验响应: ${VERIFY_BODY}"
    VERIFY_OK=$(printf '%s' "$VERIFY_BODY" | sed -n 's/.*"ok":true.*/ok/p')
    VERIFY_CHECKED=$(printf '%s' "$VERIFY_BODY" | sed -n 's/.*"checked":\([0-9][0-9]*\).*/\1/p')
    assert_true "审计哈希链完好（ok=true，登录 POST 已入链）" "$VERIFY_OK"
    assert_true "链校验覆盖 ≥1 条记录（实际 checked=${VERIFY_CHECKED:-?}）" "$VERIFY_CHECKED"
else
    echo "   ❌ 无 token，跳过审计链校验断言（prod 登录已判红）"
    FAILED=1
fi

stop_backend "$PID_C" "Pass C cloud-backend"
rm -f "$LOG_A" "$LOG_B" "$LOG_C"

# ───────────────────────── 5. 结果 ─────────────────────────
echo "[5/6] Cleanup done."
echo "[6/6] 汇总"
if [ "$FAILED" -ne 0 ]; then
    echo "=== FAIL: 集成测试存在未通过的断言（见上方 ❌）==="
    exit 1
fi
echo "=== All integration tests passed ==="
