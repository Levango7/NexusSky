#!/usr/bin/env bash
# NexusSky 试用机一键部署/运维（在 VM 上、与本目录一起运行）
# 用法：
#   bash deploy.sh            # 首次：生成 .env → 拉镜像 → 起栈 → 健康检查 → 打印地址
#   bash deploy.sh logs       # 看全栈日志（tail -f）
#   bash deploy.sh status     # 容器状态 + 健康
#   bash deploy.sh down       # 停栈（保留数据卷）
#   bash deploy.sh purge      # 停栈并删除数据卷/网络（试用到期清理用）
set -euo pipefail
cd "$(dirname "$0")"

CMD="${1:-up}"

log()  { printf '\033[1;36m[deploy]\033[0m %s\n' "$*"; }
warn_log() { printf '\033[1;33m[deploy][warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[deploy][fail]\033[0m %s\n' "$*" >&2; exit 1; }

# compose 形态探测（v2 插件优先）
if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  die "未找到 docker compose（先跑 bootstrap-vm.sh）"
fi

COMPOSE_FILE=docker-compose.trial.yml
[ -f "$COMPOSE_FILE" ] || die "当前目录缺少 $COMPOSE_FILE"

# ---------------------------------------------------------------------------
# .env：缺则生成（随机密钥；权限 600）
# 每次 up 从 .env 读 WEB_PORT，脚本都在 compose 目录内执行，保证相对路径正确。
# ---------------------------------------------------------------------------
gen_env() {
  [ -f .env ] && return 0
  command -v openssl >/dev/null 2>&1 || die "缺少 openssl（apt-get install -y openssl）"
  local jwt enc pgpw adminpw
  jwt="$(openssl rand -hex 32)"
  enc="$(openssl rand -hex 16)"
  pgpw="$(openssl rand -hex 16)"
  adminpw="$(openssl rand -hex 8)"   # 16 位 hex，纯 URL 安全字符，>=8 满足引导要求
  # 管理员口令同时写两处（同一值）：AEROFLEET_USERS 供无库降级；bootstrap 供 DB 模式
  # （prod 登录的真实路径——库里种子 admin 已停用，见 compose 注释）
  sed -e "s/^AEROFLEET_SECURITY_JWT_SECRET=.*/AEROFLEET_SECURITY_JWT_SECRET=$jwt/" \
      -e "s/^AEROFLEET_JWT_SECRET=.*/AEROFLEET_JWT_SECRET=$jwt/" \
      -e "s/^AEROFLEET_ENCRYPTION_KEY=.*/AEROFLEET_ENCRYPTION_KEY=$enc/" \
      -e "s/^AEROFLEET_USERS=.*/AEROFLEET_USERS=admin:$adminpw:ADMIN/" \
      -e "s/^AEROFLEET_SECURITY_BOOTSTRAP_ADMIN_PASSWORD=.*/AEROFLEET_SECURITY_BOOTSTRAP_ADMIN_PASSWORD=$adminpw/" \
      -e "s/^SPRING_DATASOURCE_PASSWORD=.*/SPRING_DATASOURCE_PASSWORD=$pgpw/" \
      .env.example > .env
  chmod 600 .env
  log "已生成 .env（随机密钥）"
  echo
  printf '\033[1;32m  管理端账号：admin    密码：%s\033[0m\n' "$adminpw"
  printf '  ↑ 只显示这一次，已写入 %s/.env（请立即保存到你的密码管理器）\n' "$PWD"
  echo
}

public_ip() {
  # 腾讯云元数据服务（内网可达）；取不到则退回本机首个非环回地址
  curl -s -m 3 http://metadata.tencentyun.com/latest/meta-data/public-ipv4 2>/dev/null \
    || hostname -I 2>/dev/null | awk '{print $1}' \
    || echo "<你的公网IP>"
}

wait_healthy() {
  local port; port="$(grep -E '^WEB_PORT=' .env | cut -d= -f2)"; port="${port:-80}"
  log "等待健康检查 http://localhost:${port}/actuator/health ..."
  for i in $(seq 1 60); do
    if curl -fsS -m 3 "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
      log "健康检查通过（${i}×3s）"
      return 0
    fi
    sleep 3
  done
  warn_stack
  return 1
}

warn_stack() {
  log "未通过健康检查——以下是排障信息："
  "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env ps || true
  log "后端最近 60 行日志："
  "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env logs --tail 60 cloud-backend || true
}

# prod 口径开启了设备白名单（aerofleet.udp.device-whitelist-enabled=true）：
# 未登记 sysid 的帧在进 ingest 前就被丢（实测日志 "Rejected frame from unregistered
# sysid=1"）。登记 = POST /api/v1/devices/{sysid}（ADMIN，幂等：已登记回 200）。
# 绑定到默认租户 1，这样 Web 控制台（租户上下文）能看到 Drone-1。
# 失败不致命（比如想手动登记），只提示。
provision_sim() {
  local port users adminpw token code
  port="$(grep -E '^WEB_PORT=' .env | cut -d= -f2)"; port="${port:-80}"
  users="$(grep -E '^AEROFLEET_USERS=' .env | cut -d= -f2-)"
  adminpw="$(printf '%s' "$users" | cut -d: -f2 | tr -d '\r')"
  token="$(curl -s -m 8 -X POST "http://localhost:${port}/api/v1/auth/login" \
      -H 'Content-Type: application/json' \
      -d "{\"username\":\"admin\",\"password\":\"${adminpw}\"}" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)"
  if [ -z "$token" ]; then
    warn_log "自动登记 Drone-1 跳过（拿不到登录令牌）——可稍后手动：见 README「验证清单」"
    return 0
  fi
  code="$(curl -s -m 8 -o /dev/null -w '%{http_code}' -X POST "http://localhost:${port}/api/v1/devices/1" \
      -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
      -d '{"tenantId":1}')"
  case "$code" in
    200|201) log "已登记 Drone-1 进白名单（sysid=1 → 租户 1，HTTP ${code}）" ;;
    *)       warn_log "Drone-1 登记返回 HTTP ${code}——稍后可在控制台重试" ;;
  esac
}

case "$CMD" in
  up)
    gen_env
    log "拉取镜像（ghcr.io/levango7/nexussky/*:$(grep -E '^IMAGE_TAG=' .env | cut -d= -f2 | tr -d '\r')）..."
    "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env pull
    log "启动全栈（两阶段：后端冷启动要跑 Flyway + 全量上下文，"
    log "  ~1-3 分钟；gcs-web 等后端 healthy 才有意义，先起核心再补前端）..."
    "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env up -d || true
    if wait_healthy; then
      # 后端 healthy 后补起依赖它的服务（sim/web 在首轮 up 里可能因依赖门被跳过）
      "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env up -d >/dev/null
      provision_sim
      echo
      log "部署完成 ✅"
      printf '  Web 控制台：http://%s/（登录见上方账号）\n' "$(public_ip)"
      printf '  接口探活：  curl -s http://%s/actuator/health\n' "$(public_ip)"
      printf '  Drone-1：已登记并在线（STANDBY 待命，位置静止属正常）\n'
      printf '  想让它飞：README「演示飞行」一段 curl 直接跑（起飞→航点→自动返航降落）\n'
    else
      die "健康检查未通过（日志见上）"
    fi
    ;;
  status)
    "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env ps
    ;;
  logs)
    "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env logs -f --tail 100
    ;;
  down)
    "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env down
    log "已停栈（数据卷保留；重新 deploy.sh 即可恢复）"
    ;;
  purge)
    "${DC[@]}" -f "$COMPOSE_FILE" --env-file .env down -v --remove-orphans
    log "已停栈并删除数据卷。镜像仍在（想清磁盘：docker image prune -a）"
    ;;
  *)
    die "未知子命令：$CMD（可用：up / status / logs / down / purge）"
    ;;
esac
