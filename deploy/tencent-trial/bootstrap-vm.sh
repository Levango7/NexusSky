#!/usr/bin/env bash
# NexusSky 试用机宿主准备（腾讯云 CVM 2核2G 等小内存 Linux）
# 支持：Ubuntu 22.04/24.04（apt）/ TencentOS / OpenCloudOS（dnf/yum）
# 幂等：重复执行只补缺项，不覆盖已有配置。
# 用法（root）：sudo bash bootstrap-vm.sh
set -euo pipefail

log()  { printf '\033[1;36m[bootstrap]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[bootstrap][warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[bootstrap][fail]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "请用 root 运行：sudo bash $0"

# ---------------------------------------------------------------------------
# 1) 2G swap（2G 物理机跑 5 容器 + JVM 的兜底；内存峰值溢出时不至于 OOM 连锁）
# ---------------------------------------------------------------------------
if swapon --show 2>/dev/null | grep -q .; then
  log "已有 swap，跳过创建：$(swapon --show --noheadings | head -1)"
else
  log "创建 2G swapfile ..."
  fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  log "swap 已创建并写入 fstab（重启自动挂载）"
fi

# 小内存机内核参数：swap 只在真吃紧时用；redis 需要 overcommit
cat > /etc/sysctl.d/99-nexussky-trial.conf <<'EOF'
vm.swappiness = 20
vm.overcommit_memory = 1
EOF
sysctl --system >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# 2) Docker（含 compose v2 插件）
# ---------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1; then
  log "docker 已安装：$(docker --version)"
else
  log "安装 docker ..."
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    # Ubuntu jammy/noble 的 universe 源里 docker.io + docker-compose-v2
    apt-get install -y -qq docker.io docker-compose-v2 || apt-get install -y -qq docker.io
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y docker docker-compose || dnf install -y docker
  elif command -v yum >/dev/null 2>&1; then
    yum install -y docker docker-compose || yum install -y docker
  else
    die "未知发行版：请手动安装 Docker 后再跑 deploy.sh"
  fi
fi
systemctl enable --now docker

# compose 形态探测（v2 插件 / v1 独立二进制二者其一即可，deploy.sh 也会探测）
if docker compose version >/dev/null 2>&1; then
  log "compose：$(docker compose version --short 2>/dev/null || echo 'docker compose 可用')"
elif command -v docker-compose >/dev/null 2>&1; then
  log "compose：$(docker-compose --version)（v1，deploy.sh 兼容）"
else
  warn "未找到 docker compose —— Ubuntu 可 apt-get install docker-compose-v2；"
  warn "TencentOS/OpenCloudOS 可 dnf install docker-compose；装好后重跑本脚本。"
fi

# ---------------------------------------------------------------------------
# 3) 镜像加速（腾讯云内网镜像源，仅腾讯云机器可达）+ 默认日志轮转
# ---------------------------------------------------------------------------
DAEMON_JSON=/etc/docker/daemon.json
if [ -f "$DAEMON_JSON" ]; then
  warn "已存在 $DAEMON_JSON，未覆盖——请自查 registry-mirrors / log-opts 是否符合预期："
  cat "$DAEMON_JSON"
else
  log "写入 $DAEMON_JSON（腾讯云内网镜像源 https://mirror.ccs.tencentyun.com）"
  mkdir -p /etc/docker
  cat > "$DAEMON_JSON" <<'EOF'
{
  "registry-mirrors": ["https://mirror.ccs.tencentyun.com"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
  systemctl restart docker
  log "docker 已重启使 daemon.json 生效"
fi

log "宿主准备完成。下一步：bash deploy.sh"
log "提示：腾讯云控制台安全组放行 TCP 22（SSH）与 TCP ${WEB_PORT:-80}（Web），其余全部不要开。"
