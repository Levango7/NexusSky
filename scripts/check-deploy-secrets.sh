#!/usr/bin/env bash
# 校验：仓库里的占位凭据不可能被当成真凭据上岗（helm 侧）。
#
# 为什么要有这个脚本：values.yaml / deploy/k8s/secret.yaml 里的
# REPLACE_WITH_* 占位串**长度达标**，能原样通过后端既有的「jwt secret >= 32 字节」
# 强度校验。忘替换的那次部署不是报错，而是安静地把一个公开字符串当 HMAC-SHA256
# 密钥上岗——任何读过本仓库的人都能自造任意租户/ADMIN 的 JWT。
# 后端 DeploymentSecretsGuard 在启动时拒启（见 DeploymentSecretsGuardTest），
# 本脚本管的是更早的那一层：helm 在 install/template 阶段就拒绝。
#
# 三个方向都断言，缺一个就是名义门禁：
#   A 默认 values 必须渲染失败，且失败原因点名占位串；
#   B 真实 values 必须渲染成功；
#   C 渲染结果里不得残留 REPLACE_WITH。
# 任何一步不成立都 exit 1；helm 不存在也 exit 1（跳过不等于通过）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART="$ROOT/deploy/helm/nexussky"
PLACEHOLDER_PREFIX="REPLACE_WITH"
# 长度达标即可通过后端强度校验；这里只要求「不是占位串」。
REAL_JWT="check-script-real-jwt-secret-0123456789abcdef"
REAL_ENC="check-script-real-encryption-key-0123456789"
REAL_DB="check-script-real-db-password-0123456789"

FAIL=0
say() { printf '%s\n' "$*"; }
ok() { say "  ✓ $*"; }
bad() { say "  ✗ $*"; FAIL=1; }

if ! command -v helm >/dev/null 2>&1; then
    say "FAIL: 本机/CI 上没有 helm，无法验证占位凭据门禁（跳过不等于通过）"
    exit 1
fi
say "helm: $(helm version --short 2>/dev/null)"

# ---- helm lint ----
if helm lint "$CHART" >/tmp/nx-lint.log 2>&1; then
    ok "helm lint 通过"
else
    bad "helm lint 失败：$(tail -3 /tmp/nx-lint.log | tr '\n' ' ')"
fi

# ---- A：默认 values 必须被拒 ----
DEFAULT_ERR="$(helm template placeholder-probe "$CHART" 2>&1 >/dev/null)"
A_EXIT=$?
if [ "$A_EXIT" -eq 0 ]; then
    bad "A 默认 values 竟然渲染成功——占位凭据门禁被删掉了（这就是本脚本要拦的事）"
elif printf '%s' "$DEFAULT_ERR" | grep -q "$PLACEHOLDER_PREFIX"; then
    ok "A 默认 values 被拒，且错误点名占位串"
else
    bad "A 默认 values 渲染失败，但失败原因不含占位串（可能改坏了别的）：$(printf '%s' "$DEFAULT_ERR" | head -2 | tr '\n' ' ')"
fi

# ---- B + C：真实 values 必须渲染成功且无残留 ----
RENDERED="/tmp/nx-rendered-$$.yaml"
if ! helm template real-secrets "$CHART" \
        --set "security.jwtSecret=$REAL_JWT" \
        --set "security.encryptionKey=$REAL_ENC" \
        --set "database.password=$REAL_DB" > "$RENDERED" 2>/tmp/nx-real.err; then
    bad "B 真实 values 渲染失败：$(head -2 /tmp/nx-real.err | tr '\n' ' ')"
else
    ok "B 真实 values 渲染成功（$(grep -c '^kind:' "$RENDERED") 个对象）"
    if grep -q "$PLACEHOLDER_PREFIX" "$RENDERED"; then
        bad "C 渲染结果仍残留占位串：$(grep -n "$PLACEHOLDER_PREFIX" "$RENDERED" | head -3 | tr '\n' ' ')"
    else
        ok "C 渲染结果无占位串残留"
    fi
fi
rm -f "$RENDERED"

# ---- 附：原始 k8s 清单里的占位串必须都带前缀（防止有人把真密钥提交进示例清单）----
RAW="$ROOT/deploy/k8s/secret.yaml"
if [ -f "$RAW" ]; then
    SUSPECT="$(grep -nE '^[[:space:]]*AEROFLEET_[A-Z_]+:[[:space:]]+"[^"]{20,}"' "$RAW" \
        | grep -v "$PLACEHOLDER_PREFIX" || true)"
    if [ -n "$SUSPECT" ]; then
        bad "示例清单 deploy/k8s/secret.yaml 出现不像占位串的长密钥（疑似真凭据入库）：$SUSPECT"
    else
        ok "示例清单 deploy/k8s/secret.yaml 的长值全是占位串"
    fi
fi

say ""
if [ "$FAIL" -eq 0 ]; then
    say "PASS: 占位凭据门禁有效（helm 拒默认 / 收真实 / 渲染无残留）"
    exit 0
fi
say "FAIL: 占位凭据门禁存在问题（见上面 ✗ 行）"
exit 1
