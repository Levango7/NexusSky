#!/usr/bin/env bash
# NexusSky SDK Java - Maven Central 发布脚本（Bash 版，CI 兼容）
#
# 用法:
#   ./publish-maven.sh --dry-run
#   ./publish-maven.sh --sonatype-username USER --sonatype-password PASS --gpg-key-id KEYID
#
# 环境变量（CI 推荐）:
#   SONATYPE_USERNAME, SONATYPE_PASSWORD, GPG_KEY_ID

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_JAVA_DIR="$PROJECT_ROOT/sdk-java"

# 参数解析
DRY_RUN=false
SONATYPE_USERNAME="${SONATYPE_USERNAME:-}"
SONATYPE_PASSWORD="${SONATYPE_PASSWORD:-}"
GPG_KEY_ID="${GPG_KEY_ID:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --sonatype-username) SONATYPE_USERNAME="$2"; shift 2 ;;
        --sonatype-password) SONATYPE_PASSWORD="$2"; shift 2 ;;
        --gpg-key-id) GPG_KEY_ID="$2"; shift 2 ;;
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

if ! command -v java &>/dev/null; then
    err "java 未找到，需要 JDK 17+"
    exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | head -1)
ok "java: $JAVA_VERSION"

if ! command -v mvn &>/dev/null; then
    err "mvn 未找到，需要 Maven 3.6+"
    exit 1
fi
MVN_VERSION=$(mvn --version 2>&1 | head -1)
ok "mvn: $MVN_VERSION"

if [ "$DRY_RUN" = false ]; then
    if ! command -v gpg &>/dev/null; then
        err "gpg 未找到，非 dry-run 模式需要 GPG 签名"
        exit 1
    fi
    ok "gpg: $(gpg --version 2>&1 | head -1)"

    if [ -z "$SONATYPE_USERNAME" ]; then
        err "非 dry-run 模式需要 --sonatype-username 或 SONATYPE_USERNAME 环境变量"
        exit 1
    fi
    if [ -z "$SONATYPE_PASSWORD" ]; then
        err "非 dry-run 模式需要 --sonatype-password 或 SONATYPE_PASSWORD 环境变量"
        exit 1
    fi
    if [ -z "$GPG_KEY_ID" ]; then
        err "非 dry-run 模式需要 --gpg-key-id 或 GPG_KEY_ID 环境变量"
        exit 1
    fi
fi

ok "环境检查通过"

# ====== 2. 构建 JAR + Sources JAR + Javadoc JAR ======
step "构建 JAR + Sources JAR + Javadoc JAR"

cd "$SDK_JAVA_DIR"
echo "执行: mvn clean package -DskipTests"
mvn clean package -DskipTests

# 验证产物
JAR_FILE=$(find target -name "nexussky-sdk-java-*.jar" ! -name "*-sources*" ! -name "*-javadoc*" | head -1)
if [ -z "$JAR_FILE" ]; then
    err "主 JAR 文件未找到"
    exit 1
fi
ok "主 JAR: $(basename "$JAR_FILE")"

SOURCES_JAR=$(find target -name "*-sources.jar" | head -1)
if [ -n "$SOURCES_JAR" ]; then
    ok "Sources JAR: $(basename "$SOURCES_JAR")"
else
    warn "Sources JAR 未找到，尝试单独构建..."
    mvn source:jar -DskipTests
    SOURCES_JAR=$(find target -name "*-sources.jar" | head -1)
    if [ -n "$SOURCES_JAR" ]; then
        ok "Sources JAR: $(basename "$SOURCES_JAR")"
    else
        warn "Sources JAR 仍未找到"
    fi
fi

JAVADOC_JAR=$(find target -name "*-javadoc.jar" | head -1)
if [ -n "$JAVADOC_JAR" ]; then
    ok "Javadoc JAR: $(basename "$JAVADOC_JAR")"
else
    warn "Javadoc JAR 未找到，尝试单独构建..."
    mvn javadoc:jar -DskipTests
    JAVADOC_JAR=$(find target -name "*-javadoc.jar" | head -1)
    if [ -n "$JAVADOC_JAR" ]; then
        ok "Javadoc JAR: $(basename "$JAVADOC_JAR")"
    else
        warn "Javadoc JAR 仍未找到"
    fi
fi

# ====== 3. GPG 签名 ======
if [ "$DRY_RUN" = false ]; then
    step "GPG 签名"
    echo "执行: mvn gpg:sign -Dgpg.keyId=$GPG_KEY_ID -DskipTests"
    mvn gpg:sign -Dgpg.keyId="$GPG_KEY_ID" -DskipTests
    if [ $? -ne 0 ]; then
        err "GPG 签名失败。请确认密钥 ID 正确且 GPG agent 可用。"
        echo "提示: 如果 GPG 提示输入密码，设置 GPG_TTY 环境变量:"
        echo "  export GPG_TTY=\$(tty)"
        exit 1
    fi
    ok "GPG 签名完成"
else
    step "GPG 签名（跳过 - dry-run 模式）"
fi

# ====== 4. 上传到 Sonatype OSSRH ======
if [ "$DRY_RUN" = false ]; then
    step "上传到 Sonatype OSSRH"
    echo "执行: mvn deploy -DskipTests -Dsonatype.username=*** -Dsonatype.password=*** -P release"
    mvn deploy -DskipTests \
        -Dsonatype.username="$SONATYPE_USERNAME" \
        -Dsonatype.password="$SONATYPE_PASSWORD" \
        -P release
    if [ $? -ne 0 ]; then
        err "上传到 Sonatype OSSRH 失败"
        echo "常见原因:"
        echo "  1. 凭证错误 - 检查 sonatype-username/password"
        echo "  2. GPG 签名未通过 - 检查密钥是否已上传到 keyserver"
        echo "  3. POM 配置问题 - 确认 distributionManagement 已启用"
        exit 1
    fi
    ok "上传到 Sonatype OSSRH 完成"
    echo ""
    echo "下一步: 登录 https://s01.oss.sonatype.org/ 检查 staging 仓库"
    echo "确认无误后点击 Release 完成发布"
else
    step "上传到 Sonatype OSSRH（跳过 - dry-run 模式）"
fi

# ====== 完成 ======
step "发布流程完成"
if [ "$DRY_RUN" = true ]; then
    echo "Dry-run 模式: 已完成构建验证，未执行签名和上传。"
    echo "要执行实际发布，请去掉 --dry-run 参数并提供凭证。"
else
    ok "NexusSky SDK Java 已发布到 Maven Central（staging）"
fi