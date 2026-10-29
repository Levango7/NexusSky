#!/usr/bin/env bash
# 守卫：maven 本地仓库的缓存键必须在所有 workflow 里同源。
#
# 为什么需要它（2026-10-07 事故，原文保留）
# ----------------------------------------
# 缓存键出现在 ci.yml 的多个 job 里（maven-warm / java / gcs-e2e / e2e-smoke /
# e2e-capability / integration / sdk-integration / security-scan / codeql …）。
# actions/cache 的语义是「同一 key 首个保存者胜出，之后只读不覆盖」，所以只要抬代次时
# 漏了某几处，warm job 写进去的那一代就永远没人读得到（或反过来：各 job 各自冷启动去
# 公网重下，共享 runner IP 上直接撞 Maven Central 429）。2026-10-07 整仓被一个过期代次
# 冻红之后，"抬键尾 v 号"变成例行动作，这个漂移面就必须自己变红，而不是靠人记住。
#
# 2026-10-29：守卫自己失明（本次修复）
# ------------------------------------
# master push run 40794626496 的 `Java Level Gate (bytecode vs declaration)` 红在
#   Non-resolvable import POM: … spring-boot-dependencies:pom:3.5.16 (absent):
#   Could not transfer … HTTP Status: 429
# 而同一次运行里该 job 的缓存恢复步打印
#   Cache not found for input keys: Linux-m2-1c24aa8b…
# 根因：`java-level` 的恢复键写的是
#   ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
# 而 `maven-warm` 保存的是 `mvn-${os}-${hash}-v2` —— 两套键**永不可能命中**，
# 于是该 job 每次运行都从中央仓冷下载整个 reactor，撞 429，门禁"没跑成"被记成"失败"。
#
# 而本守卫**看不见它**：旧实现用 `key:\s+mvn-` 前缀匹配，`${{ runner.os }}-m2-` 不以
# `mvn-` 开头 ⇒ 18 处站点里只扫到 17 处，那 17 处全对 ⇒ 打印 "OK: 同源"。
# 与 COORDINATION.md 第五节同款：**一个"匹配不到就不管"的校验器不是门禁，是装饰。**
#
# 现在改成按**语义标记**识别站点：只要一个 `key:` 行的值里带 `hashFiles('**/pom.xml')`，
# 它就是在缓存 maven 本地仓库，必须等于规范值 —— 不管前缀长什么样。另加两道自证：
#   ① 站点数下限 MIN_SITES（删 job 导致覆盖面缩水时，必须显式下调，不能静默）；
#   ② `restore-keys` 的代次检查（键对了但 restore 指向上一个代次，同样会读到空缓存）。
#
# 用法：
#   bash scripts/ci-maven-cache-key.sh [workflows 目录]   （默认 .github/workflows）
#   bash scripts/ci-maven-cache-key.sh --self-test        （离线夹具，五情形）
# 退出码：0=通过，1=不同源 / 失明 / 覆盖面缩水 / 自测失败。
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
SELF="$SCRIPT_DIR/$(basename "$0")"

CANON="mvn-\${{ runner.os }}-\${{ hashFiles('**/pom.xml') }}-v2"
RESTORE="mvn-\${{ runner.os }}-"
# 站点数下限：2026-10-29 实测 18 处（ci.yml 的 18 个 job 各一处）。
# 这是一个**下限**不是目标值——新增 job 只加分；要减必须先想清楚减掉的那个 job 靠什么
# 拿 m2，然后显式下调本常量（有意识的动作，而不是静默缩水）。
MIN_SITES=18

# 在给定目录里扫全部 yml，输出 (sites, mismatch, restore_total, restore_bad)
check_dir() {
  local dir="$1" total=0 mismatch=0 rtotal=0 rbad=0 raw body value rel
  if [ ! -d "$dir" ]; then
    echo "  ::error::目录不存在：$dir"
    return 3
  fi

  # 注 1：trim 用**纯参数展开**而不是 `$(trim ...)`。本机实测（Windows）一次命令替换
  # ≈0.9s 的 fork 开销，18 站点 × 2 处 = 36 次 fork ⇒ 单次检查 38s、自测跑不完。
  # 参数展开零 fork。改回 `$(...)` 会让这个门禁在 CI 里也变成负担。
  # 注 2：两类站点**共用一次 grep**（两次进程替换 = 8 个 fork，本机 ~5s）。合并后单次检查
  # 从 38s 降到 ~5s，全部开销都在 Windows 的 fork 上，与 yml 规模无关。
  while IFS= read -r raw; do
    [ -z "$raw" ] && continue
    rel="${raw%%:*}"
    body="${raw#*:}"; body="${body#*:}"
    body="${body#"${body%%[![:space:]]*}"}"
    body="${body%"${body##*[![:space:]]}"}"
    case "$body" in '#'*) continue ;; esac

    case "$body" in
      restore-keys:* | mvn-*)
        # ── ② restore-keys 的代次：键对了但 restore 指向上一个代次，同样读到空缓存
        case "$body" in
          restore-keys:*) value="${body#*restore-keys: }" ;;
          *)              value="$body" ;;
        esac
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        case "$value" in
          mvn-*)
            rtotal=$((rtotal + 1))
            if [ "$value" != "$RESTORE" ]; then
              rbad=$((rbad + 1))
              printf '  ✗ %s\n      restore 实得: %s\n      restore 规范: %s\n' "$rel" "$value" "$RESTORE"
            fi
            ;;
        esac
        ;;
      *)
        # ── ① 站点识别靠**语义标记**：值里带 hashFiles('**/pom.xml') ⇒ 在缓存 maven 本地仓库。
        # 不认前缀——2026-10-29 漏掉的正是 `${{ runner.os }}-m2-…` 这种不以 mvn- 开头的写法。
        case "$body" in
          *"pom.xml"*) ;;
          *) continue ;;
        esac
        value="${body#*key: }"
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        total=$((total + 1))
        if [ "$value" != "$CANON" ]; then
          mismatch=$((mismatch + 1))
          printf '  ✗ %s\n      实得: %s\n      规范: %s\n' "$rel" "$value" "$CANON"
        fi
        ;;
    esac
  done < <(cd "$dir" && grep -rn --include='*.yml' --include='*.yaml' \
             -E '^[[:space:]]*(-[[:space:]]+)?(key|restore-keys):|^[[:space:]]+mvn-' . 2>/dev/null | tr -d '\r' || true)

  # 工具链只在**判红时**才留痕：`mvn -version` 会拉起一个 JVM（本机实测单次 ~22s），
  # 挂在每次成功路径上纯属浪费。诊断只需要在出事时具备。
  # 自测会把它关掉（AF_MVN_TOOLCHAIN=0）：五个情形里四个是"预期判红"，各拉一次 JVM
  # 就把自测拖过工具超时（实测 4×22s），而自测要验的是判定逻辑，不是 mvn 装没装。
  TOOLCHAIN="(略：仅在判红时留痕)"
  if [ "${AF_MVN_TOOLCHAIN:-1}" != "0" ] \
     && { [ "$total" -eq 0 ] || [ "$mismatch" -ne 0 ] || [ "$rbad" -ne 0 ] || [ "$total" -lt "$MIN_SITES" ]; }; then
    if command -v mvn >/dev/null 2>&1; then
      TOOLCHAIN="$(mvn -version 2>/dev/null | head -1)"
    else
      TOOLCHAIN="(本环境无 mvn，跳过工具链留痕)"
    fi
  fi
  echo "maven cache key dir=$dir sites=$total mismatch=$mismatch restore=$rtotal/$rbad min=$MIN_SITES toolchain=$TOOLCHAIN"

  if [ "$total" -eq 0 ]; then
    echo "::error::没找到任何 maven 缓存键（门禁自己失明 = 假绿）：要么 workflow 被改坏，要么本脚本的匹配式过期"
    return 1
  fi
  if [ "$mismatch" -ne 0 ]; then
    echo "::error::maven 缓存键不同源（$mismatch/$total 处偏离规范值）⇒ 抬代次必须所有站点一起抬；键不同源 = 该 job 永远读不到 warm 缓存，每次冷下载撞 429"
    return 1
  fi
  if [ "$rbad" -ne 0 ]; then
    echo "::error::maven restore-keys 代次不同源（$rbad/$rtotal 处）⇒ 键抬了但 restore 还指向上一个代次"
    return 1
  fi
  if [ "$total" -lt "$MIN_SITES" ]; then
    echo "::error::站点数 $total < 下限 $MIN_SITES ⇒ 有 job 的 m2 缓存被摘掉却没登记；确属有意请下调 MIN_SITES 并写明该 job 靠什么取件"
    return 1
  fi
  echo "OK: maven 缓存键 $total 处同源（restore $rtotal 处同源）"
  return 0
}

# ── 自测：离线夹具，覆盖「干净 / 前缀漂移 / 空目录 / 覆盖面缩水 / restore 代次漂移」
self_test() {
  local tmp rc fail=0 out
  tmp="$(mktemp -d)"
  export AF_MVN_TOOLCHAIN=0   # 夹具不拉 JVM（见 check_dir 里的说明）

  emit_job() { # $1=job 名 $2=key 值 $3=restore 值 $4=目标文件
    {
      printf '  %s:\n' "$1"
      printf '    steps:\n'
      printf '      - uses: actions/cache@v4\n'
      printf '        with:\n'
      printf '          path: ~/.m2/repository\n'
      printf '          key: %s\n' "$2"
      printf '          restore-keys: |\n'
      printf '            %s\n' "$3"
    } >>"$4"
  }
  gen() { # $1=目录 $2=站点数 [$3=覆盖第几个为漂移键] [$4=restore 值]
    local d="$1" n="$2" bad="${3:-0}" rst="${4:-$RESTORE}" i
    mkdir -p "$d"
    : >"$d/ci.yml"
    for ((i = 1; i <= n; i++)); do
      if [ "$i" -eq "$bad" ]; then
        emit_job "job$i" '${{ runner.os }}-m2-${{ hashFiles('"'"'**/pom.xml'"'"') }}' "$rst" "$d/ci.yml"
      else
        emit_job "job$i" "$CANON" "$rst" "$d/ci.yml"
      fi
    done
  }

  # A 干净：18 处全规范 ⇒ 0
  gen "$tmp/a" "$MIN_SITES"
  bash "$SELF" "$tmp/a" >/dev/null 2>&1; rc=$?
  if [ "$rc" -eq 0 ]; then echo "  ✓ A 干净（$MIN_SITES 处同源）"; else echo "  ✗ A 干净：期望 rc=0，实得 $rc"; fail=1; fi

  # B 前缀漂移（本次 2026-10-29 的真身：${{ runner.os }}-m2-…）⇒ 1，且必须点名
  gen "$tmp/b" "$MIN_SITES" 7
  out="$(bash "$SELF" "$tmp/b" 2>&1)"; rc=$?
  if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q 'm2-'; then
    echo "  ✓ B 前缀漂移被抓（旧实现会漏）"
  else
    echo "  ✗ B 前缀漂移：期望 rc=1 且点名 m2-，实得 rc=$rc"; fail=1
  fi

  # C 空目录（匹配式过期/被改坏）⇒ 1，且必须是"失明"而不是"通过"
  mkdir -p "$tmp/c"; : >"$tmp/c/ci.yml"
  out="$(bash "$SELF" "$tmp/c" 2>&1)"; rc=$?
  if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q '失明'; then
    echo "  ✓ C 空目录判失明"
  else
    echo "  ✗ C 空目录：期望 rc=1 且报失明，实得 rc=$rc"; fail=1
  fi

  # D 覆盖面缩水（少一处）⇒ 1
  gen "$tmp/d" "$((MIN_SITES - 1))"
  bash "$SELF" "$tmp/d" >/dev/null 2>&1; rc=$?
  if [ "$rc" -eq 1 ]; then echo "  ✓ D 覆盖面缩水被抓（$((MIN_SITES - 1)) < $MIN_SITES）"; else echo "  ✗ D 覆盖面缩水：期望 rc=1，实得 $rc"; fail=1; fi

  # E restore 代次漂移 ⇒ 1
  gen "$tmp/e" "$MIN_SITES" 0 "mvn-\${{ runner.os }}-v1"
  bash "$SELF" "$tmp/e" >/dev/null 2>&1; rc=$?
  if [ "$rc" -eq 1 ]; then echo "  ✓ E restore 代次漂移被抓"; else echo "  ✗ E restore 代次漂移：期望 rc=1，实得 $rc"; fail=1; fi

  # 清理：本机沙箱会拦 `rm`（[safe-delete] 报 "embedded drive prefix"）并把整个进程杀掉，
  # 所以 Windows 路径（带盘符冒号）下**不清理**，只打印位置；Linux/CI 正常清理。
  case "$tmp" in
    *:*)
      echo "  （Windows 路径，跳过清理；夹具留在 $tmp）"
      ;;
    *)
      rm -rf "$tmp" 2>/dev/null || true
      ;;
  esac
  if [ "$fail" -ne 0 ]; then
    echo "自测失败：守卫自身的判定不可信，别拿它的绿当证据"
    return 1
  fi
  echo "自测通过：5/5"
  return 0
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi

check_dir "${1:-$REPO_ROOT/.github/workflows}"
exit $?
