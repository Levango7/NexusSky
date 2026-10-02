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
#   （RBAC 授权层已被覆盖：base 的 aerofleet.security.rbac-enabled 默认 true，且 dev
#   profile 不设该键，所以 Pass B 在 dev-mode=false 下会真正执行 RoleInterceptor——
#   断言 6 用"ADMIN 建用户 201 + OBSERVER 打 /api/v1/audit/logs 403"成对取证。
#   链级角色/租户的细粒度断言仍由 cloud-backend 的 HttpAuthChainTest 负责。）
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
#     5) 审计哈希链校验通过（V21 建表 + 审计落库 + 链生成/校验全通路）；
#     6) flight_log 在 PG 上可查（Pass C 用 flag 打开 persist-to-db，覆盖未加引号的
#        timestamp 作 WHERE 谓词/ORDER BY 键这一 PG 方言风险——H2 两版语法都认）；
#     7) 活体设备：ADMIN 经 POST /api/v1/devices/{sysid} 登记 → drone-sim 真发遥测 →
#        flight_log 在 PG 上真落行。这条同时钉住两件事：设备白名单与注册表持久化必须成对
#        （prod 死锁回归腿），以及异步批量写路径在真实 PostgreSQL 上端到端可用
#        （断言 6 只证明读通路与方言，行数是 7 带来的）。
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
# Pass C 断言 7 用的活体设备模拟器（shaded = 可执行 fat jar，见 drone-sim/pom.xml 的 shade 配置）。
SIM_JAR="drone-sim/target/aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar"
# Pass C 里那台 sim 的 sysid：避开仓库内既有 sim/e2e 惯用的 1/7/9 等号段，便于日志里定位。
C_SIM_SYSID="${C_SIM_SYSID:-231}"
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
# 设备/边缘摄取通道的共享 API Key（DeviceIngestKeyBootstrapRunner 在 Pass B 启动时引导）。
# >=16 位是引导器的硬门槛，短于此会被拒绝并留 WARN。
CI_DEVICE_KEY="ci-device-ingest-key-0123456789"
A_PORT="${A_PORT:-8080}"
B_PORT="${B_PORT:-8081}"
C_PORT="${C_PORT:-8082}"
# Pass C 的 PG 连接：默认值与 application-prod.properties:24/26/27 对齐
# （jdbc:postgresql://localhost:5432/aerofleet_prod，用户 aerofleet）。
# 密码无默认值（prod 里 ${SPRING_DATASOURCE_PASSWORD} 不带缺省），
# 这里给出 CI service 容器里同名口令；本地验证可覆盖。
C_PG_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-ci_pg_password}"
C_PG_PORT="${PG_PORT:-5432}"
# JWT 密钥：Pass B 与 Pass C 共用。Pass B 必须显式注入——LicenseService 构造器在
# dev-mode=false 时对"未配置/内置开发默认值"的 jwt-secret fail-closed 拒启动
# （守卫是产品行为，不因测试放宽）；Pass C（prod）由 ${AEROFLEET_JWT_SECRET} 占位符
# 注入（无默认值，缺失即启动失败）。32 字符以上是 HS256 的硬性下限
# （JwtTokenProvider 未配置 RSA 时回退 HS256），也是 license 激活码 HMAC 的密钥门槛。
CI_JWT_SECRET="${AEROFLEET_JWT_SECRET:-ci_gateway_jwt_secret_at_least_32_chars_000}"
# 字段加密密钥（webhook 凭据、surveillance 摄像头密码等 AES-GCM 列加密）：
# prod 的 ${AEROFLEET_ENCRYPTION_KEY} 无默认值——2026-10-01 删除了明文默认
# aerofleet-dev-encryption-key，缺失即启动失败（Pass B 走 dev profile，base 的
# 空默认值可启动，故只有 Pass C 需要注入）。两个消费方（WebhookService、
# surveillance.PasswordConverter）都以 SHA-256(密钥串) 派生 AES-128 密钥，
# 任意非空字符串皆可；此为 CI 专用值，真实部署必须注入独立密钥。
CI_ENCRYPTION_KEY="${AEROFLEET_ENCRYPTION_KEY:-ci_field_encryption_key_0123456789abcdef}"
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
PID_SIM=""

# 任何硬失败（set -e 中途退出、就绪超时 exit 1）都不该把 java 进程留在工作区里：
# 三趟各自绑 8080/8081/8082 + UDP 14550，残留进程会让复跑/后续步骤行为不可预期。
cleanup_on_exit() {
    local p
    for p in "$PID_A" "$PID_B" "$PID_C" "$PID_SIM"; do
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
# dev-mode=false 触发 LicenseService 的 jwt-secret fail-closed 守卫：空值或内置
# 开发默认值一律拒启动。Pass B 要像真实部署一样显式给密钥，而不是放宽守卫。
PID_B=$(start_backend "$LOG_B" "$B_PORT" $REDIS_OVERRIDE \
    --spring.profiles.active=dev \
    $DEV_DB_OVERRIDE \
    --aerofleet.security.dev-mode=false \
    --aerofleet.security.jwt-secret="$CI_JWT_SECRET" \
    --aerofleet.security.bootstrap-admin-username="$CI_USER" \
    --aerofleet.security.bootstrap-admin-password="$CI_PASSWORD" \
    --aerofleet.security.device-ingest-api-key="$CI_DEVICE_KEY")
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

echo "   --- Pass B 断言 6：RBAC 真实生效（成对证据：ADMIN 过 / OBSERVER 拒）---"
# base 的 aerofleet.security.rbac-enabled 默认已是 true，而 dev profile 不设该键、
# 本趟又显式 --aerofleet.security.dev-mode=false（RoleInterceptor.preHandle 的旁路
# 条件不再成立），所以 Pass B 是整条 CI 里唯一走 RoleInterceptor **拒绝分支**的腿。
# （Pass C 的 prod 同样开着 RBAC，但它只证明"ADMIN 够格 → 放行"这一侧。）
# 为什么必须成对：只断 OBSERVER 得 403 不足以证明拦截器接上了——恒 403 也会绿
# （上一轮变异验证用过的口径）。先让 ADMIN 建用户拿到 201（证明"够格就放行"），
# 再用该 OBSERVER 账号换 token 打 ADMIN-only 的 /api/v1/audit/logs 拿 403。
if [ -n "$TOKEN" ]; then
    OBS_USER="ci-observer"
    OBS_PASS="ci-observer-pw"
    CREATE_RAW=$(curl -sS -w $'\n%{http_code}' -X POST "http://localhost:${B_PORT}/api/v1/users" \
        -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
        -d "{\"username\":\"${OBS_USER}\",\"password\":\"${OBS_PASS}\",\"role\":\"OBSERVER\"}")
    CREATE_STATUS=${CREATE_RAW##*$'\n'}
    assert_status "POST /api/v1/users（ADMIN token 建 OBSERVER 用户 → 201 放行）" "201" "$CREATE_STATUS"
    OBS_LOGIN=$(curl -sS -X POST "http://localhost:${B_PORT}/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"${OBS_USER}\",\"password\":\"${OBS_PASS}\"}")
    OBS_TOKEN=$(printf '%s' "$OBS_LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
    assert_true "OBSERVER 账号能换到 JWT（role claim 来自用户表）" "$OBS_TOKEN"
    if [ -n "$OBS_TOKEN" ]; then
        assert_status "GET /api/v1/audit/logs（OBSERVER 应被 RBAC 拒 403）" "403" \
            "$(http_status -H "Authorization: Bearer ${OBS_TOKEN}" \
                "http://localhost:${B_PORT}/api/v1/audit/logs")"

        # --- fail-closed 翻转后的成对证据（2026-10-01）---
        # RoleInterceptor 已从"无注解即放行"翻成"无注解即 403"。光看上面的 403 分不清
        # 拦的是"角色不够"还是"端点没声明"，所以这里补两条互相制衡的断言：
        #   读侧 200 证明只读用户没被默认拒绝误伤（GeofenceController 等已类级 OBSERVER）；
        #   写侧 403 证明 OPERATOR 门槛真的在拦 OBSERVER。
        # 覆盖率门禁（每个端点都要有 @RequireRole 或 @PermitAll）由单元测试
        # RbacEndpointCoverageTest 用反射逐端点校验，比文本扫描可靠，故不在此重复。
        assert_status "GET /api/v1/drones（OBSERVER 读已声明端点 → 200，未被 fail-closed 误伤）" "200" \
            "$(http_status -H "Authorization: Bearer ${OBS_TOKEN}" \
                "http://localhost:${B_PORT}/api/v1/drones")"
        assert_status "POST /api/v1/geofence/check（OBSERVER 越级写 → 403）" "403" \
            "$(http_status -X POST -H "Authorization: Bearer ${OBS_TOKEN}" \
                "http://localhost:${B_PORT}/api/v1/geofence/check")"
    else
        echo "   ❌ 无 OBSERVER token，跳过 RBAC 拒绝断言"
        FAILED=1
    fi
else
    echo "   ❌ 无 token，跳过 RBAC 生效断言（登录已判红）"
    FAILED=1
fi

echo "   --- Pass B 断言 7：设备摄取走 API Key 的真实通路（成对：对的 key 200 / 错的 key 401）---"
# 上面几条断言都经 JWT。这条专门验证 X-API-Key 分支：启动时用
# --aerofleet.security.device-ingest-api-key 引导 keyId=device-ingest（库里只存哈希），
# 再用该 key 打 OPERATOR 档的 POST /api/v1/alarms/events。
# 为什么必须配一条反向：只断 200 排不掉"拦截器压根没跑、谁都放行"这种假绿；
# 错的 key 应在 Spring Security 层就 401（ApiKeyFilter 认不出来 → 仍是匿名 → anyRequest 需认证）。
EVENTS_BODY='{"sourceDeviceId":"ci-edge-1","sourceDeviceName":"CI 边缘节点","eventType":"CUSTOM","severity":"WARN","description":"api-key-path proof"}'
assert_status "POST /api/v1/alarms/events（引导出的 device-ingest key → 200）" "200" \
    "$(http_status -X POST -H "X-API-Key: ${CI_DEVICE_KEY}" -H 'Content-Type: application/json' \
        -d "$EVENTS_BODY" "http://localhost:${B_PORT}/api/v1/alarms/events")"
assert_status "[对照] POST /api/v1/alarms/events（错误 key → 401，证明不是恒放行）" "401" \
    "$(http_status -X POST -H "X-API-Key: nsk_this_key_does_not_exist_000000" -H 'Content-Type: application/json' \
        -d "$EVENTS_BODY" "http://localhost:${B_PORT}/api/v1/alarms/events")"

stop_backend "$PID_B" "Pass B cloud-backend"

# ───────────────────────── 4. Pass C：生产迁移通路（prod profile + PostgreSQL） ─────────────────────────
echo "[4/6] Pass C —— prod profile + 真实 PostgreSQL，验证 Flyway 迁移通路..."
LOG_C=$(mktemp)
# 为什么用 spring.config 命令行参数而不是环境变量覆盖 profile 内已有键：
# prod 的日志级别在 profile 内写死为 WARN，只有命令行 --logging.level.*（最高优先级）
# 才能把 Flyway 的 INFO 放出来当证据。
PID_C=$(AEROFLEET_JWT_SECRET="$CI_JWT_SECRET" \
    AEROFLEET_USERS="$C_USERS" \
    AEROFLEET_ENCRYPTION_KEY="$CI_ENCRYPTION_KEY" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${C_PG_PORT}/aerofleet_prod" \
    SPRING_DATASOURCE_USERNAME=aerofleet \
    SPRING_DATASOURCE_PASSWORD="$C_PG_PASSWORD" \
    start_backend "$LOG_C" "$C_PORT" $REDIS_OVERRIDE \
    --spring.profiles.active=prod \
    --logging.level.org.flywaydb=INFO \
    --aerofleet.flightlog.persist-to-db=true \
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

echo "   --- Pass C 断言 6：flight_log 在 PostgreSQL 上可查（DB 读通路 + PG 方言）---"
# prod 默认 aerofleet.flightlog.persist-to-db=false（遥测只落 JSONL），这里用命令行 flag
# 临时打开 DB 读通路。这条断言要证明的是三件在 H2 上永远看不到的事：V18 建的表在 PG 里存在、
# Hibernate 生成的 "where type=? and sysid=? and timestamp between ? and ? order by timestamp asc, id asc"
# 被 PostgreSQL 接受（未加引号的 timestamp 作谓词/排序键，H2 两种模式都认，只有 PG 会红）、
# 保留清理作用的那张表在 prod schema 下与实体一致（validate 已隐含，这里补可查证据）。
# 注意：这一条跑在断言 7 之前，此刻表还是空的（sim 尚未接入），断言的是"查询通路 + 方言"，
# 不断言行数——行数由断言 7 用活体设备真落真查。
if [ -n "$TOKEN_C" ]; then
    FLIGHTLOG_RAW=$(curl -sS -w $'\n%{http_code}' -H "Authorization: Bearer ${TOKEN_C}" \
        "http://localhost:${C_PORT}/api/v1/flightlog?type=telemetry&limit=5")
    FLIGHTLOG_STATUS=${FLIGHTLOG_RAW##*$'\n'}
    FLIGHTLOG_BODY=${FLIGHTLOG_RAW%$'\n'*}
    assert_status "GET /api/v1/flightlog（prod + PostgreSQL，DB 读通路）" "200" "$FLIGHTLOG_STATUS"
    FLIGHTLOG_IS_ARRAY=$(printf '%s' "$FLIGHTLOG_BODY" | sed -n 's/^\[.*/array/p')
    assert_true "flight_log 查询返回 JSON 数组（实际 ${FLIGHTLOG_BODY:0:60}）" "$FLIGHTLOG_IS_ARRAY"
else
    echo "   ❌ 无 token，跳过 flight_log PG 读通路断言"
    FAILED=1
fi

echo "   --- Pass C 断言 7：活体设备经 ADMIN 登记穿过白名单，遥测在 PG 真落行 ---"
# 这一条是设备注册死锁（#46）的回归腿。prod 同时开 aerofleet.udp.device-whitelist-enabled=true
# 与 aerofleet.device-registry.persist=true：陌生 sysid 的帧在进 ingest 之前就被 UdpGateway 丢弃，
# 而注册条目过去只由被放行的帧创建 ⇒ 首台设备永远登记不上。修法补的是显式登记入口
# POST /api/v1/devices/{sysid}（ADMIN）。这里必须用真 UDP 设备走完整链路，因为单测里
# 白名单那几条一直用 mock DeviceRegistry（get() 恒给快照），恰好掩盖了"没人能填这个表"。
# 接线：后端默认发现 127.0.0.1:14540（aerofleet.drone-port），sim 绑 14540 即被学到对端，
# 之后自行持续推 GLOBAL_POSITION_INT —— 不需要任何 ARM/起飞触发命令（实测 1 行/秒/机）。
if [ -n "$TOKEN_C" ]; then
    # 必须带 tenantId：flight_log 的行按"设备归属"盖租户戳（FlightLogService.tenantForWrite →
    # DeviceRegistry.tenantOf），而未归属设备的行任何具体租户都读不到。Pass C 的引导 ADMIN 的
    # JWT 里带 tenant_id=1（AdminBootstrapRunner 默认租户，V6 种子 tenant id=1 code=default），
    # 所以登记成租户 1，整条链路才是租户自洽的 —— 只登记不带归属会得到"写进去了但谁都看不见"。
    PROVISION_RAW=$(curl -sS -w $'\n%{http_code}' -X POST \
        -H "Authorization: Bearer ${TOKEN_C}" \
        -H "Content-Type: application/json" -d '{"tenantId":1}' \
        "http://localhost:${C_PORT}/api/v1/devices/${C_SIM_SYSID}")
    PROVISION_STATUS=${PROVISION_RAW##*$'\n'}
    PROVISION_BODY=${PROVISION_RAW%$'\n'*}
    # 201=本次新建；200=已存在（本机增量复跑会走这支，因条目已入库）
    if [ "$PROVISION_STATUS" = "201" ] || [ "$PROVISION_STATUS" = "200" ]; then
        echo "   ✅ POST /api/v1/devices/${C_SIM_SYSID}（ADMIN 登记白名单）→ HTTP $PROVISION_STATUS"
    else
        echo "   ❌ POST /api/v1/devices/${C_SIM_SYSID}：期望 200/201，实际 $PROVISION_STATUS（响应 ${PROVISION_BODY:0:120}）"
        FAILED=1
    fi
    # persisted=true 才是 prod 那对开关真的成对打开了（内存态条目重启即失，白名单会重新变死锁）
    PERSISTED=$(printf '%s' "$PROVISION_BODY" | sed -n 's/.*"persisted":true.*/yes/p')
    assert_true "登记条目已入库（device-registry.persist 在 prod 生效）" "$PERSISTED"

    if [ -f "$SIM_JAR" ]; then
        LOG_SIM=$(mktemp)
        java -jar "$SIM_JAR" --port 14540 --sysid "$C_SIM_SYSID" > "$LOG_SIM" 2>&1 &
        PID_SIM=$!
        # 有界轮询：写队列是 200ms 批量刷 + 1 行/秒/机节流，放宽"多久能看到"，不放宽"必须看到"。
        # curl/grep 都要吞失败：pipefail 下无匹配的 grep 返回 1 会直接中断脚本（见文件头取证注释）。
        SIM_ROWS=0
        for i in $(seq 1 30); do
            sleep 2
            SIM_ROWS=$( { curl -sS -H "Authorization: Bearer ${TOKEN_C}" \
                "http://localhost:${C_PORT}/api/v1/flightlog?type=telemetry&sysid=${C_SIM_SYSID}&limit=200" \
                || true; } | { grep -o '"sysid"' || true; } | wc -l )
            [ "$SIM_ROWS" -ge 3 ] && break
        done
        if [ "$SIM_ROWS" -ge 3 ]; then
            echo "   ✅ flight_log 在 PostgreSQL 上收到活体遥测（sysid=${C_SIM_SYSID}，${SIM_ROWS} 行，等待 ≤$((i * 2))s）"
            # 同一 token 也应能在机队列表里看到这台设备（登记 + 归属 + 心跳三件事都成立才可能）
            DRONE_SEEN=$( { curl -sS -H "Authorization: Bearer ${TOKEN_C}" \
                "http://localhost:${C_PORT}/api/v1/drones" || true; } \
                | { grep -o "\"sysid\":${C_SIM_SYSID}" || true; } | wc -l )
            # assert_true 判的是"非空"，所以必须把 0 换算成空串——直接喂 wc -l 的 "0" 会被当成真。
            assert_true "GET /api/v1/drones 含活体设备 sysid=${C_SIM_SYSID}（白名单确已放行）" \
                "$([ "$DRONE_SEEN" -gt 0 ] && echo yes)"
            # 撤销腿（纯 REST 状态断言，不依赖帧到达时刻，避免计时抖动）：DELETE 成功后
            # 注册表里内存与库行都没了，此时给同一 sysid 指派租户应当 404 device unknown。
            DELETE_STATUS=$(http_status -X DELETE -H "Authorization: Bearer ${TOKEN_C}" \
                "http://localhost:${C_PORT}/api/v1/devices/${C_SIM_SYSID}")
            assert_status "DELETE /api/v1/devices/${C_SIM_SYSID}（撤销登记）" "200" "$DELETE_STATUS"
            assert_status "撤销后 PUT /tenant 应判设备未知（证明条目真没了）" "404" \
                "$(http_status -X PUT -H "Authorization: Bearer ${TOKEN_C}" \
                    -H "Content-Type: application/json" -d '{"tenantId":1}' \
                    "http://localhost:${C_PORT}/api/v1/devices/${C_SIM_SYSID}/tenant")"
        else
            echo "   ❌ 60s 内 flight_log 只见到 ${SIM_ROWS} 行 sysid=${C_SIM_SYSID} 的遥测（期望 ≥3）"
            echo "   --- sim 日志末尾 15 行 ---"
            tail -15 "$LOG_SIM"
            FAILED=1
        fi
        kill "$PID_SIM" 2>/dev/null || true
        PID_SIM=""
        rm -f "$LOG_SIM"
    else
        echo "   ❌ 找不到 sim 制品 $SIM_JAR，活体设备断言无法执行"
        FAILED=1
    fi
else
    echo "   ❌ 无 token，跳过活体设备断言（prod 登录已判红）"
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
