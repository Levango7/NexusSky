#!/usr/bin/env bash
# NexusSky SDK Python - PyPI 发布脚本（Bash 版，CI 兼容）
#
# 用法:
#   ./publish-pypi.sh --dry-run
#   ./publish-pypi.sh --pypi-token TOKEN
#
# 环境变量（CI 推荐）:
#   PYPI_TOKEN

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_PYTHON_DIR="$PROJECT_ROOT/sdk-python"

# 参数解析
DRY_RUN=false
PYPI_TOKEN="${PYPI_TOKEN:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --pypi-token) PYPI_TOKEN="$2"; shift 2 ;;
        *) echo "[ERROR] 未知参数: $1"; exit 1 ;;
    esac
done

# 输出函数
step() { echo -e "\n[STEP] $1"; }
ok() { echo "[OK] $1"; }
warn() { echo "[WARN] $1"; }
err() { echo "[ERROR] $1"; }

# ====== 1. 环境检查 ======
step "环境检查"

if ! command -v python &>/dev/null; then
    err "python 未找到，需要 Python 3.8+"
    exit 1
fi
ok "python: $(python --version 2>&1)"

if ! command -v pip &>/dev/null; then
    err "pip 未找到"
    exit 1
fi
ok "pip: $(pip --version 2>&1)"

# 检查构建工具
for tool in build twine; do
    if ! pip show "$tool" &>/dev/null; then
        warn "$tool 未安装，正在安装..."
        pip install "$tool"
        if [ $? -ne 0 ]; then
            err "$tool 安装失败"
            exit 1
        fi
    fi
    ok "$tool 已安装"
done

if [ "$DRY_RUN" = false ]; then
    if [ -z "$PYPI_TOKEN" ]; then
        err "非 dry-run 模式需要 --pypi-token 或 PYPI_TOKEN 环境变量"
        echo "获取 Token: 登录 https://pypi.org/manage/account/token/ 创建 API Token"
        exit 1
    fi
fi

ok "环境检查通过"

# ====== 2. 构建 wheel + sdist ======
step "构建 wheel + sdist"

cd "$SDK_PYTHON_DIR"

# 清理旧构建产物
rm -rf dist build *.egg-info

echo "执行: python -m build"
python -m build
if [ $? -ne 0 ]; then
    err "构建失败"
    exit 1
fi

# 验证产物
WHEEL_FILE=$(find dist -name "*.whl" | head -1)
if [ -z "$WHEEL_FILE" ]; then
    err "wheel 文件未找到"
    exit 1
fi
ok "Wheel: $(basename "$WHEEL_FILE")"

SDIST_FILE=$(find dist -name "*.tar.gz" | head -1)
if [ -z "$SDIST_FILE" ]; then
    err "sdist 文件未找到"
    exit 1
fi
ok "SDist: $(basename "$SDIST_FILE")"

# ====== 3. 上传到 PyPI ======
if [ "$DRY_RUN" = false ]; then
    step "上传到 PyPI"
    echo "执行: twine upload dist/*"
    twine upload dist/* -u "__token__" -p "$PYPI_TOKEN"
    if [ $? -ne 0 ]; then
        err "上传到 PyPI 失败"
        echo "常见原因:"
        echo "  1. Token 错误 - 检查 pypi-token"
        echo "  2. 版本号已存在 - PyPI 不允许重复上传同一版本"
        echo "  3. 网络问题 - 检查网络连接"
        exit 1
    fi
    ok "上传到 PyPI 完成"
    echo ""
    echo "查看发布结果: https://pypi.org/project/aerofleet-sdk/"
else
    step "上传到 PyPI（跳过 - dry-run 模式）"
fi

# ====== 完成 ======
step "发布流程完成"
if [ "$DRY_RUN" = true ]; then
    echo "Dry-run 模式: 已完成构建验证，未执行上传。"
    echo "要执行实际发布，请去掉 --dry-run 参数并提供 PyPI Token。"
else
    ok "NexusSky SDK Python 已发布到 PyPI"
fi