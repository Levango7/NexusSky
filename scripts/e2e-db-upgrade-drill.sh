#!/usr/bin/env bash
# 数据库升级演练：V1–V20 → V21–V29（在**真 PostgreSQL** 上）。
#
# 为什么需要它
# ------------
# CI 一直只验「全新安装」：H2 上把 28 个迁移从零跑一遍。
# 而客户真正走的那条路 —— **从老版本升上来** —— 从来没被演练过。
# 2026-10-21 手工演练了一次（prod profile + 真 PG 16），当场翻出一个用户看得见的缺陷：
# `GET /api/v1/flightlog/track` 的每个点 `ts` 永远是 0 ⇒ 前端按 ts 合并时
# 把当天历史轨迹去重成一个点（`trackMerge.js`）。那次是手工跑的、不可复现 ——
# 本脚本把它变成**每次 PR 都跑**的机械检查。
#
# 流程
# ----
#   1. 建一个空库；
#   2. 用 prod profile + `--spring.flyway.target=20` 起后端
#      （只应用 V1–V20，`ddl-auto=none` —— 中间态的 schema 缺后面迁移才建的表，
#       JPA 的 validate 必然报 missing table，这不是缺陷，是中间态的常态）；
#   3. 灌入代表性数据（**含亚秒精度的 flight_log 时间戳** —— V29 会改那一列的类型）；
#   4. 停掉；**不带 target** 重启（应用 V21–V29，`ddl-auto=validate` = 生产口径）；
#   5. 断言（每条都是硬断言，任何一条不成立即红）：
#      a. 迁移条数 = 28，版本连续到 V29；
#      b. 升级前灌的数据**一条不少**；
#      c. `flight_log.timestamp` 已是 `timestamptz`，且**亚秒精度分毫不差**；
#      d. 后面的迁移建的新表就位（docks / work_orders / audit_log / airspace_reservations…）；
#      e. 应用起得来（health 200）且供得出数（登录 + 读设备 + 读轨迹）；
#      f. **轨迹点的 ts 是真实时刻且互不相同**（前端靠它合并/排序）；
#   6. 回滚路径可跑通：pg_dump → 改坏 → pg_restore 回来。
#
# 依赖
# ----
#   - 一个可连的 PostgreSQL（CI 里用 service 容器；本机用 docker）；
#   - 一个可连的 Redis（prod profile 要）；
#   - cloud-backend 的 jar（`mvn -pl cloud-backend -am package -DskipTests`）。
#
# 用法
# ----
#   PG_URL=jdbc:postgresql://localhost:5432 PG_USER=nexus PG_PASSWORD=nexus123 \
#   REDIS_HOST=localhost REDIS_PORT=6379 bash scripts/e2e-db-upgrade-drill.sh
#
# 退出码：0 全过；1 有断言失败；2 前置不满足。
set -u -o pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PG_URL="${PG_URL:-jdbc:postgresql://localhost:5432/nexus}"
PG_USER="${PG_USER:-nexus}"
PG_PASSWORD="${PG_PASSWORD:-nexus123}"
PG_DB="${PG_DB:-nexussky_upgrade_drill}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
BACKEND_PORT="${BACKEND_PORT:-18095}"   # 阶段二（升到 HEAD）用的端口
OLD_BACKEND_PORT="${OLD_BACKEND_PORT:-18094}"  # 阶段一（只迁到 V20）用的端口
DRILL_PORT="${DRILL_PORT:-$BACKEND_PORT}"
JAR="cloud-backend/target/aerofleet-cloud-backend-0.1.0-SNAPSHOT.jar"
LOG_DIR="${LOG_DIR:-/tmp/nexussky-upgrade-drill}"
BOOTSTRAP_PASSWORD="${BOOTSTRAP_PASSWORD:-DrillAdmin-Passw0rd!}"
JWT_SECRET="${JWT_SECRET:-drill-only-jwt-secret-0123456789abcdefghi}"
ENC_KEY="${ENC_KEY:-drill-only-encryption-key-0123456789abcdef}"
OLD_VERSION="${OLD_VERSION:-20}"          # 演练的"老版本"停在哪一版
NEW_VERSION="${NEW_VERSION:-29}"          # 演练的"新版本"到哪一版

PASS=0
FAIL=0
BACKEND_PID=""

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); printf '  [PASS] %s\n' "$*"; }
bad()  { FAIL=$((FAIL+1)); printf '  [FAIL] %s\n' "$*"; }
step() { printf '\n--- %s ---\n' "$*"; }

cleanup() {
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# psql / pg_dump 走 PG* 形式的连接串
psql_q() { PGPASSWORD="$PG_PASSWORD" psql -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -U "$PG_USER" -d "$1" -t -A -c "$2"; }
psql_x() { PGPASSWORD="$PG_PASSWORD" psql -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -U "$PG_USER" -d "$1" -c "$2"; }

require() {
  command -v "$1" >/dev/null 2>&1 || { say "  [FAIL] 缺少命令：$1"; exit 2; }
}

launch_backend() {   # $1=额外参数  $2=端口；**只起进程不等就绪**
  local port="$2"
  java -Duser.timezone=UTC -jar "$JAR" \
    --spring.profiles.active=prod \
    --server.port="$port" \
    --spring.datasource.url="$PG_URL/$PG_DB?options=-c%20TimeZone%3DUTC" \
    --spring.datasource.username="$PG_USER" \
    --spring.datasource.password="$PG_PASSWORD" \
    --spring.data.redis.host="$REDIS_HOST" \
    --spring.data.redis.port="$REDIS_PORT" \
    --spring.data.redis.password="${REDIS_PASSWORD:-}" \
    --aerofleet.security.users=admin:admin \
    --aerofleet.security.bootstrap-admin-tenant-id="${BOOTSTRAP_TENANT_ID:-2}" \
    --aerofleet.security.jwt-secret="$JWT_SECRET" \
    --aerofleet.encryption.key="$ENC_KEY" \
    --aerofleet.udp-port=14570 \
    --aerofleet.drone-port=14571 \
    --aerofleet.drone-extra-ports=14572,14573,14574 \
    $1 > "$LOG_DIR/backend.log" 2>&1 &
  BACKEND_PID=$!
}

# 等端口空出来（上一个进程没退干净时，会把下一次的启动挤掉）
wait_port_free() {   # $1=端口
  local port="$1"
  local i=0
  while [ "$i" -lt 60 ]; do
    if ! (exec 3<>/dev/tcp/127.0.0.1/"$port") 2>/dev/null; then
      return 0
    fi
    exec 3<&- 2>/dev/null || true
    i=$((i+1)); sleep 1
  done
  return 1
}

start_backend() {   # $1=额外参数  $2=端口；等到 health 200 且**确认这个进程还活着**
  local port="$2"
  if ! wait_port_free "$port"; then
    say "  端口 $port 被别的进程占着"
    return 1
  fi
  launch_backend "$1" "$port"
  local i=0
  while [ "$i" -lt 90 ]; do
    if curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
      # 健康检查通了，但必须确认**这个 200 是我起的进程回的**：
      # 不确认就会把「旧进程的 200」当成「我起来了」（本演练首跑就被这个耳木正式上过当）。
      if kill -0 "$BACKEND_PID" 2>/dev/null; then
        return 0
      fi
      say "  健康检查有响应，但我起的进程已经死了（端口被别的进程回应的）"
      tail -15 "$LOG_DIR/backend.log" | sed 's/^/    /'
      return 1
    fi
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      say "  后端进程退出了，日志尾部："
      tail -25 "$LOG_DIR/backend.log" | sed 's/^/    /'
      return 1
    fi
    i=$((i+1)); sleep 2
  done
  say "  后端在 180s 内没就绪，日志尾部："
  tail -25 "$LOG_DIR/backend.log" | sed 's/^/    /'
  return 1
}

stop_backend() {
  [ -n "$BACKEND_PID" ] || return 0
  kill "$BACKEND_PID" 2>/dev/null || true
  local i=0
  while [ "$i" -lt 40 ] && kill -0 "$BACKEND_PID" 2>/dev/null; do i=$((i+1)); sleep 1; done
  kill -9 "$BACKEND_PID" 2>/dev/null || true
  wait "$BACKEND_PID" 2>/dev/null || true
  BACKEND_PID=""
  # 等端口释放：下一阶段启动的后端不能被旧进程抢住端口（否则会让后一阶段的判定失真）
  wait_port_free "$DRILL_PORT" || true
}

# ---------------------------------------------------------------- 前置
mkdir -p "$LOG_DIR"
# **时区钉死成 UTC**（两边都钉，不靠猜）：
#  - psql 会话：PGOPTIONS 给每个会话带 timezone=UTC；
#  - 应用的 JDBC 会话：连接串的 options=-c TimeZone=UTC（pgjdbc 会把会话时区设成 JVM 本地时区，
#    不看我们的环境变量）。
# 为什么要钉：V29 把历史 naive 值按**会话时区**解释，不钉就会出现
# 「整段历史平移一个时区偏移」（V29 的注释里写过这个操作约束）。
export PGOPTIONS="-c timezone=UTC"
require java
require curl
require psql
require pg_dump
[ -f "$JAR" ] || { say "  [FAIL] 找不到 $JAR（先 mvn -B -pl cloud-backend -am package -DskipTests）"; exit 2; }

step "0/7 前置"
psql_q postgres "DROP DATABASE IF EXISTS $PG_DB WITH (FORCE);" >/dev/null 2>&1
if ! psql_q postgres "CREATE DATABASE $PG_DB OWNER $PG_USER;" >/dev/null 2>&1; then
  say "  [FAIL] 建库失败（连不上 PostgreSQL：$PG_URL）"; exit 2
fi
ok "空库 $PG_DB 已建"
# **时区钉死成 UTC**（两边都钉）：
#  - DB 会话时区：迁移把历史 naive 值按**会话时区**解释；
#  - JVM 时区：应用写入的 wall clock 是 JVM 本地时区。
# 两边不钉死就会出现「整段历史平移一个时区偏移」（V29 的注释里写过这个操作约束）。
# 本演练把时区钉死在 UTC，使“增量后的墙钟值与升级前完全一致”可以被机械地断言。
psql_q postgres "ALTER DATABASE $PG_DB SET TimeZone TO 'UTC';" >/dev/null 2>&1 || true

# ---------------------------------------------------------------- 阶段一：老版本
step "1/7 只迁到 V$OLD_VERSION（模拟老版本部署）"
# 注意：这一阶段**允许应用起不来**。
# 当前代码在启动时会查 `docks` 表（V23 才建）⇒ 中间态 schema 下它必然失败，
# 这是**中间态的常态**，不是缺陷。演练要的只是"老版本 schema + 正确的 flyway 历史"，
# 而历史（含 checksum）必须由 **flyway 自己写** —— 手写历史表的 checksum 对不上，
# 下一阶段 flyway 会报 checksum mismatch 直接拒绝启动。
#
# 另：也不能用 psql 手工跑迁移来代替 —— 同上，checksum 得由 flyway 算。
launch_backend "--spring.flyway.target=$OLD_VERSION --spring.jpa.hibernate.ddl-auto=none" "$OLD_BACKEND_PORT"
i=0
while [ "$i" -lt 90 ]; do
  MIGRATED=$(psql_q "$PG_DB" "SELECT count(*) FROM flyway_schema_history;" 2>/dev/null || echo 0)
  [ "${MIGRATED:-0}" -ge "$OLD_VERSION" ] && break
  i=$((i+1)); sleep 2
done
stop_backend
MIGRATED=$(psql_q "$PG_DB" "SELECT count(*) FROM flyway_schema_history;")
# 版本列是 varchar：max() 是**字典序**（'9' > '20'），必须转成整数再比
MAXV=$(psql_q "$PG_DB" "SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history;")
# 期望条数**按迁移文件数算**（版本号不连续 —— V7 从来不存在，所以 V20 只有 19 个迁移）
EXPECT_OLD=$(ls "$ROOT/cloud-backend/src/main/resources/db/migration" 2>/dev/null \
  | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p' \
  | awk -v t="$OLD_VERSION" '$1 + 0 <= t + 0' | wc -l | tr -d ' ')
if [ "$MIGRATED" = "$EXPECT_OLD" ] && [ "$MAXV" = "$OLD_VERSION" ]; then
  ok "老版本 schema 就位（$MIGRATED 个迁移，最大版本 V$MAXV）"
else
  bad "老版本阶段不对：迁移 ${MIGRATED:-?} 条（期望 $EXPECT_OLD），最大版本 V${MAXV:-?}（期望 $OLD_VERSION）"
  exit 1
fi

# ---------------------------------------------------------------- 灌数据
step "2/7 灌入代表性数据（含亚秒精度时间戳）"
psql_x "$PG_DB" "
-- 注意：V6 自己就种了 id=1 的默认租户 ⇒ 演练租户用 id=2，别撞种子数据
INSERT INTO tenant (id, name, code) VALUES (2, 'drill-tenant', 'drill');
INSERT INTO app_user (id, username, password_hash, role, tenant_id) VALUES (2, 'drill-ops', 'x', 'OPERATOR', 2);
INSERT INTO devices (sysid, online, last_heartbeat_ms, name, tenant_id) VALUES (101, true, 1700000000000, 'drone-101', 2);
INSERT INTO devices (sysid, online, last_heartbeat_ms, name, tenant_id) VALUES (102, false, 1700000000001, 'drone-102', 2);
INSERT INTO flight_log (timestamp, type, sysid, lat, lon, relative_alt, battery, tenant_id)
  VALUES ('2026-10-01 10:00:00.123456', 'telemetry', 101, 39.901, 116.301, 120, 88, 2);
INSERT INTO flight_log (timestamp, type, sysid, lat, lon, tenant_id)
  VALUES ('2026-10-01 10:05:00.500000', 'telemetry', 101, 39.902, 116.302, 2);
INSERT INTO flight_log (timestamp, type, sysid, lat, lon, tenant_id)
  VALUES ('2026-10-01 10:09:59.999999', 'telemetry', 102, 39.903, 116.303, 2);
INSERT INTO api_keys (key_id, tenant_id, name, scopes, key_hash, role, masked_key)
  VALUES ('nsk_drill_0001', 2, 'drill-key', '[ingest]', 'abc123hash', 'OPERATOR', 'nsk_****0001');
INSERT INTO surveillance_device (id, name, vendor, ip, port, status, tenant_id)
  VALUES ('cam-drill-1', 'drill-cam', 'HIKVISION', '10.0.0.9', 554, 'ONLINE', 2);
" > "$LOG_DIR/seed.log" 2>&1
if [ "$(psql_q "$PG_DB" "SELECT count(*) FROM flight_log WHERE sysid IN (101,102);")" = "3" ]; then
  ok "数据已灌入（flight_log 3 条 / devices 2 条 / api_keys 1 条 / 租户 1 个 / 摄像头 1 台）"
else
  bad "灌数据失败（见 $LOG_DIR/seed.log）"
  tail -5 "$LOG_DIR/seed.log" | sed 's/^/    /'
  say "  灌数据失败就不能继续：带着脏数据继续跑，断言就在验残留而不是验这次演练。"
  exit 1
fi
# 升级前的基线快照（用于比对）
# 注意比较口径：V29 的转换按**会话时区**解释历史 naive 值，而那次会话是应用的 JDBC 连接
# （pgjdbc 默认把会话时区设成 **JVM 的时区** = 宿主 OS 时区）。DB 自己的会话时区是 UTC，
# 直接拿它比较会凭空差一个时区偏移 ⇒ 比较时必须显式用宿主时区换算。
psql_q "$PG_DB" "SELECT string_agg(to_char(timestamp, 'YYYY-MM-DD HH24:MI:SS.US'), '|' ORDER BY id) FROM flight_log;" > "$LOG_DIR/before-ts.txt"
if ! pg_dump -Fc -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -U "$PG_USER" "$PG_DB" > "$LOG_DIR/backup.dump" 2>"$LOG_DIR/dump.log"; then
  bad "备份失败（pg_dump 退出码非 0）："
  tail -5 "$LOG_DIR/dump.log" | sed 's/^/    /'
  exit 1
fi
if [ ! -s "$LOG_DIR/backup.dump" ]; then
  bad "备份产物为空（dump 失败）"
  exit 1
fi
ok "已备份（回滚路径的实物）：$LOG_DIR/backup.dump"

# ---------------------------------------------------------------- 阶段二：升到 HEAD
step "3/7 升到 V$NEW_VERSION（不带 target = 生产口径，ddl-auto=validate）"
if ! start_backend "--aerofleet.security.bootstrap-admin-password=$BOOTSTRAP_PASSWORD" "$DRILL_PORT"; then
  say "  [FAIL] 升级后起不来（迁移或 schema 校验没过）"; exit 1
fi
ok "应用带着升级后的 schema 起来了（JPA validate 过了）"

step "4/7 断言：迁移与 schema"
TOTAL=$(psql_q "$PG_DB" "SELECT count(*) FROM flyway_schema_history;")
MAXV=$(psql_q "$PG_DB" "SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history;")
EXPECT_ALL=$(ls "$ROOT/cloud-backend/src/main/resources/db/migration" 2>/dev/null \
  | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p' | wc -l | tr -d ' ')
[ "$TOTAL" = "$EXPECT_ALL" ] && ok "迁移条数 = $TOTAL（全部迁移都应用了）" \
  || bad "迁移条数 = $TOTAL（期望 $EXPECT_ALL）"
[ "$MAXV" = "$NEW_VERSION" ] && ok "最新版本 = V$MAXV" || bad "最新版本 = V${MAXV:-?}（期望 $NEW_VERSION）"
COLTYPE=$(psql_q "$PG_DB" "SELECT data_type FROM information_schema.columns WHERE table_name='flight_log' AND column_name='timestamp';")
[ "$COLTYPE" = "timestamp with time zone" ] && ok "flight_log.timestamp 已是 timestamptz" || bad "flight_log.timestamp 类型 = ${COLTYPE:-?}"
for t in docks work_orders audit_log airspace_reservations; do
  N=$(psql_q "$PG_DB" "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='$t';")
  [ "$N" = "1" ] && ok "新表就位：$t" || bad "新表缺失：$t"
done

step "5/7 断言：数据没丢、精度没丢"
# 只数"演练自己灌的那些行"—— 迁移种子里本来就有行（V6 的默认租户等），不能拿全表计数
for spec in \
  "devices:SELECT count(*) FROM devices WHERE name LIKE 'drone-%':2" \
  "flight_log:SELECT count(*) FROM flight_log WHERE sysid IN (101,102):3" \
  "api_keys:SELECT count(*) FROM api_keys WHERE key_id='nsk_drill_0001':1" \
  "tenant:SELECT count(*) FROM tenant WHERE code='drill':1" \
  "surveillance_device:SELECT count(*) FROM surveillance_device WHERE id='cam-drill-1':1"; do
  T="${spec%%:*}"; rest="${spec#*:}"; Q="${rest%%:*}"; WANT="${rest##*:}"
  GOT=$(psql_q "$PG_DB" "$Q;")
  [ "$GOT" = "$WANT" ] && ok "$T 的演练数据保住 $GOT 行" || bad "$T = $GOT（期望 $WANT）"
done
AFTER_TS=$(psql_q "$PG_DB" "SELECT string_agg(to_char(timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US'), '|' ORDER BY id) FROM flight_log;")
BEFORE_TS=$(cat "$LOG_DIR/before-ts.txt")
# 统一成同一精度再比（naive 列打印 .5 而不是 .500000）
if [ "$AFTER_TS" = "$BEFORE_TS" ]; then
  ok "亚秒精度分毫不差：$AFTER_TS"
else
  bad "时间戳被改了：升级前 [$BEFORE_TS] -> 升级后 [$AFTER_TS]"
fi

step "6/7 断言：应用供得出数，且轨迹点的 ts 是真实时刻"
TOKEN=$(curl -sf -X POST "http://localhost:$DRILL_PORT/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$BOOTSTRAP_PASSWORD\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then
  ok "登录拿到 token（引导链路可用）"
  N=$(curl -sf -H "Authorization: Bearer $TOKEN" "http://localhost:$DRILL_PORT/api/v1/drones" | grep -o '"sysid"' | wc -l | tr -d ' ')
  [ "$N" = "2" ] && ok "GET /api/v1/drones = $N 台" || bad "GET /api/v1/drones = $N（期望 2）"
  TRACK=$(curl -sf -H "Authorization: Bearer $TOKEN" "http://localhost:$DRILL_PORT/api/v1/flightlog/track?day=2026-10-01&sysid=101")
  echo "$TRACK" | grep -q '"ts":0' && bad "轨迹点的 ts 还是 0（前端会把它去重成一个点）" || ok "轨迹点的 ts 不再是 0"
  DISTINCT=$(echo "$TRACK" | grep -o '"ts":[0-9]*' | sort -u | wc -l | tr -d ' ')
  [ "$DISTINCT" -ge 2 ] && ok "轨迹点的 ts 互不相同（$DISTINCT 个不同值）" || bad "轨迹点的 ts 只有 $DISTINCT 个不同值"
else
  bad "登录失败（引导管理员没建起来）"
fi

step "7/7 断言：回滚（灾备）路径可跑通：备份恢复到一个干净库"
# 灾备的真实场景是「恢复到一个干净实例」，而不是「在跑着的库上拆掉重建」 ——
# 后者会被活连接/依赖拒径掉（CI 首跑就是这个）。
# 因此：先停掉后端（灾备时应用本来就是停的），再恢复到新建的空库里，
# 然后对比两边的演练数据。
stop_backend
RESTORE_DB="${PG_DB}_restore"
psql_q postgres "DROP DATABASE IF EXISTS $RESTORE_DB WITH (FORCE);" >/dev/null 2>&1
psql_q postgres "CREATE DATABASE $RESTORE_DB OWNER $PG_USER;" >/dev/null 2>&1
if ! PGPASSWORD="$PG_PASSWORD" pg_restore -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -U "$PG_USER" -d "$RESTORE_DB" < "$LOG_DIR/backup.dump" > "$LOG_DIR/restore.log" 2>&1; then
  bad "恢复失败（pg_restore 退出码非 0），日志："
  tail -10 "$LOG_DIR/restore.log" | sed 's/^/    /'
  psql_q postgres "DROP DATABASE IF EXISTS $RESTORE_DB WITH (FORCE);" >/dev/null 2>&1
  exit 1
fi
R_DEV=$(psql_q "$RESTORE_DB" "SELECT count(*) FROM devices WHERE name LIKE 'drone-%';")
R_TS=$(psql_q "$RESTORE_DB" "SELECT string_agg(to_char(timestamp, 'YYYY-MM-DD HH24:MI:SS.US'), '|' ORDER BY id) FROM flight_log;")
R_LOG=$(psql_q "$RESTORE_DB" "SELECT count(*) FROM flight_log;")
psql_q postgres "DROP DATABASE IF EXISTS $RESTORE_DB WITH (FORCE);" >/dev/null 2>&1
if [ "$R_DEV" = "2" ] && [ "$R_LOG" = "3" ] && [ "$R_TS" = "$BEFORE_TS" ]; then
  ok "回滚（灾备）路径可跑通：恢复到干净库后数据与亚秒精度都对得上"
else
  bad "恢复后对不上：devices=$R_DEV（期望 2）flight_log=$R_LOG（期望 3）时间戳=$R_TS"
  bad "  期望的时间戳 = $BEFORE_TS"
  tail -10 "$LOG_DIR/restore.log" | sed 's/^/    /'
fi

printf '\n================ 结论 ================\n'
printf '  PASS=%d  FAIL=%d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
