#!/usr/bin/env bash
# =============================================================================
# 状态板「在途与占用」段的实测生成器
#
# 为什么需要它：2026-10-12 发现 .coord/STATUS.md 停在 10-10，而 master 在这两天里
# 合了 12 笔 PR、多了两个在写的 worktree。手写的状态板一旦没人记得刷，就会变成
# 一张"看起来很确定"的假图——下一个照它干活的人会按过期 sha 和过期计数行动。
# 本脚本只报能从 git/gh 当场取到的事实，取不到就明说取不到。
#
# 用法：
#   bash scripts/coord-status.sh              # 打印 markdown 段（只读，不动文件）
#   bash "$SELF" --write      # 用标记块替换 .coord/STATUS.md 里的同一段
#   bash scripts/coord-status.sh --json       # 机器可读输出（给别的脚本用）
#   COORD_NO_GH=1 / COORD_REPO_SLUG=… / COORD_STATUS_FILE=… 是测试注入点
#
# 退出码：
#   0  证据齐全
#   2  证据不全（找不到主 worktree / gh 不可用 / 网络失败）
#      —— 刻意不返回 0：「没查到」不能冒充「没问题」，静默的 0 比报错更危险。
#
# 边界：它只证明"谁站在哪个 sha、有无未提交面、PR 是否可合"，
#       **不证明** 那个人是谁（GitHub 上 PR 都挂同一个账号）。归属仍需各 agent 自己在
#       「开工声明」里签名；本脚本会标出哪些在途工作没有声明。
# =============================================================================
set -uo pipefail

MODE="print"
case "${1:-}" in
  --write) MODE="write" ;;
  --json)  MODE="json" ;;
  --self-test) MODE="selftest" ;;
  "")      ;;
  *) echo "用法: bash scripts/coord-status.sh [--write|--json|--self-test]" >&2; exit 64 ;;
esac

warn() { echo "$*" >&2; }

# =============================================================================
# 自测：证明「取不到」不会被冒充成「没问题」，且 --write 只动标记块、不重复插。
# 全部在临时目录里跑，绝不碰真实的 .coord/STATUS.md。
# =============================================================================
self_test() {
  local fails="" tmp status out rc marks n1 n2 heads self
  SELF="${COORD_STATUS_SELF:-scripts/coord-status.sh}"
  [ -f "$SELF" ] || { echo "无法判定：自测目标不存在（$SELF）——绝不测别的文件冒充通过。" >&2; return 1; }
  tmp="$(mktemp -d)"
  status="$tmp/STATUS.md"
  # 夹具一：没有标记块、也没有手写「在途与占用」段 ⇒ 首写应插入
  cat > "$status" <<'EOF'
# 板
## 工程现状
略
## 开工声明（新声明加在最上面）
- 某人：声明
## 规则速记
- 某规则
**C 系列仅剩的三条**：见下
EOF

  # T1 gh 不可用 ⇒ 必须 UNVERIFIED 且 exit 2（绝不能报成「无在途 PR」）
  #    用 COORD_NO_GH 注入，不依赖网络/鉴权是否恰好坏——上一版拿真实 gh 调用做断言，结果
  #    同一份代码两次跑出不同结论，那叫自测自己在 flaky。
  out="$(COORD_NO_GH=1 bash "$SELF" 2>/dev/null)"; rc=$?
  printf '%s' "$out" | grep -q "UNVERIFIED" || fails="${fails}T1:降级没走 UNVERIFIED "
  printf '%s' "$out" | grep -q "当前无在途 PR" && fails="${fails}T1:取不到却伪装成「无在途 PR」 "
  [ "$rc" = "2" ] || fails="${fails}T1:期望退出 2 实得 $rc "

  # T2 首次 --write：插入标记块，且他人段落都在
  COORD_STATUS_FILE="$status" bash "$SELF" --write >/dev/null 2>&1
  grep -qF 'coord-status:begin' "$status" || fails="${fails}T2:没写入标记块 "
  for a in "开工声明" "规则速记" "C 系列仅剩的三条"; do
    grep -qF "$a" "$status" || fails="${fails}T2:丢了段落$a "
  done

  # T3 重写必须**幂等**：begin 恰一份、标题段一份、有效行数不变
  #    （上一版只查了 begin 计数，结果 awk 丢了 inside 追踪、把旧块正文留在原地，
  #      自测照样绿——就是这种"绿着的失效"要盯住。）
  n1="$(grep -c . "$status")"
  COORD_STATUS_FILE="$status" bash "$SELF" --write >/dev/null 2>&1; rc=$?
  # 重写必须**成功**（exit 0）且幂等。只断言"文件没变"会被"被守卫拒绝所以没变"蒙混过去——
  # 变异测试就是这么暴露的：正文重复的老 bug 触发了并存守卫，退出码 1，而 T3 仍判"通过"。
  [ "$rc" = "0" ] || fails="${fails}T3:重写应成功(exit0) 实得 $rc（多半是生成段自己撞出了重复） "
  marks="$(grep -c 'coord-status:begin' "$status")"
  heads="$(grep -c '^## 在途与占用' "$status")"
  n2="$(grep -c . "$status")"
  [ "$marks" = "1" ] || fails="${fails}T3:begin 出现 $marks 次（应 1） "
  [ "$heads" = "1" ] || fails="${fails}T3:在途与占用 段有 $heads 份（应 1） "
  [ "$n1" = "$n2" ] || fails="${fails}T3:重写后有效行数 $n1→$n2（不幂等，正文残留） "

  # T4 已有手写「在途与占用」段且无标记块 ⇒ 拒绝写入（不能让两份并存）
  status2="$tmp/STATUS2.md"
  printf '# 板\n## 在途与占用（手写的旧段）\n旧内容\n## 开工声明\n- x\n## 规则速记\n- y\n' > "$status2"
  before="$(grep -c . "$status2")"
  COORD_STATUS_FILE="$status2" bash "$SELF" --write >/dev/null 2>&1; rc=$?
  [ "$rc" = "1" ] || fails="${fails}T4:并存场景期望 1 实得 $rc "
  after="$(grep -c . "$status2")"
  [ "$before" = "$after" ] || fails="${fails}T4:拒绝写入却改了文件 "
  grep -qF 'coord-status:begin' "$status2" && fails="${fails}T4:被拒绝却仍插入了标记块 "

  # T5 目标文件不存在 ⇒ exit 2 且绝不凭空创建
  rm -f "$status"
  COORD_STATUS_FILE="$status" bash "$SELF" --write >/dev/null 2>&1; rc=$?
  [ "$rc" = "2" ] || fails="${fails}T5:缺文件时期望 2 实得 $rc "
  [ ! -e "$status" ] || fails="${fails}T5:不该凭空造状态板 "

  rm -rf "$tmp"
  if [ -n "$fails" ]; then
    echo "自测失败：$fails" >&2
    return 1
  fi
  echo "自测目标=$SELF（用 COORD_STATUS_SELF 注入，防副本测到原文件）"
  echo "自测通过：假 slug→UNVERIFIED/exit2、首写插入且他人段落齐全、重写幂等（begin/标题各 1 份、行数不变）、"
  echo "手写段并存→拒绝写入且文件未被改动、缺文件→exit2 且不凭空造板。"
  return 0
}

if [ "$MODE" = "selftest" ]; then
  # 用 git 定根，不用 dirname $0/..：后者在被复制成别的路径时（变异测试、别人挪了脚本）
  # 会 cd 到非仓库目录，于是子调用全部以「无法判定」失败——看起来抓到了 bug，其实抓的是自己。
  selftest_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
  [ -n "$selftest_root" ] || { echo "无法判定：自测必须在仓库内运行。" >&2; exit 2; }
  cd "$selftest_root" || { echo "无法判定：进不去仓库根。" >&2; exit 2; }
  self_test
  exit $?
fi

# --- 取主 worktree（.coord 是 gitignored，只存在于主检出里） -------------------
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$ROOT" ]; then
  warn "无法判定：当前目录不在 git 仓库里。"
  exit 2
fi
MAIN="$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0,10); exit}')"
if [ -z "$MAIN" ]; then
  warn "无法判定：git worktree list 没返回主工作树。"
  exit 2
fi
STATUS_FILE="${COORD_STATUS_FILE:-$MAIN/.coord/STATUS.md}"

git -C "$MAIN" fetch --quiet origin 2>/dev/null || warn "注意：fetch 失败，下面的 origin/master 可能是旧值。"
MSHA="$(git -C "$MAIN" rev-parse --short origin/master 2>/dev/null || echo "")"
if [ -z "$MSHA" ]; then
  warn "无法判定：取不到 origin/master。"
  exit 2
fi

DEGRADED=0

# 仓库标识从 origin 推，别把 owner/repo 写死在脚本里（origin 换镜像就失效）
# COORD_REPO_SLUG 仅供自测注入（用一个不存在的 slug 证明「取不到」会走 UNVERIFIED 而不是「无在途 PR」）
ORIGIN_URL="$(git -C "$MAIN" remote get-url origin 2>/dev/null || true)"
DERIVED_SLUG="$(printf '%s' "$ORIGIN_URL" | sed -E 's#.*[/:]([^/]+/[^/.]+)(\.git)?$#\1#')"
REPO_SLUG="${COORD_REPO_SLUG:-$DERIVED_SLUG}"
if ! printf '%s' "$REPO_SLUG" | grep -qE '^[^/]+/[^/]+$'; then
  DEGRADED=1
  REPO_SLUG=""
fi

# --- 在途 PR：用真实调用判定可用性，别拿假探测当证据 ----------------------------
# 上一版用 `gh api repos` 探测，它本来就该 404 ⇒ gh 明明能用却被报成 UNVERIFIED。
# 现在的判据是「真调用的退出码」：0 且空列表＝确实没有在途 PR；非 0＝UNVERIFIED。
PR_BLOCK=""
# COORD_NO_GH=1 是测试注入点：让降级分支可被确定性验证（不依赖网络/鉴权是否恰好坏）
if [ "${COORD_NO_GH:-0}" = "1" ]; then
  DEGRADED=1
  PR_BLOCK="UNVERIFIED：gh 取不到（此处为注入的降级场景），**不要**把这条读成『没有在途 PR』。"
elif [ -n "$REPO_SLUG" ] && command -v gh >/dev/null 2>&1; then
  PR_TSV_FILE="$(mktemp)"
  if gh pr list --repo "$REPO_SLUG" --state open \
       --json number,title,headRefName,mergeable,mergeStateStatus \
       --jq '.[] | [.number, .headRefName, .mergeable, .mergeStateStatus, .title] | @tsv' \
       > "$PR_TSV_FILE" 2>/dev/null; then
    if [ ! -s "$PR_TSV_FILE" ]; then
      PR_BLOCK="（当前无在途 PR）"
    else
      while IFS=$'\t' read -r num br mr ms title; do
        [ -z "$num" ] && continue
        PR_BLOCK="${PR_BLOCK}#${num} \`[${br}]\` merge=${mr}/${ms} :: ${title}"$'\n'
      done < "$PR_TSV_FILE"
    fi
  else
    DEGRADED=1
    PR_BLOCK="UNVERIFIED：\`gh pr list --repo $REPO_SLUG\` 调用失败（鉴权/网络/限流）。**不要**把这条读成『没有在途 PR』。"
  fi
  rm -f "$PR_TSV_FILE"
else
  DEGRADED=1
  PR_BLOCK="UNVERIFIED：gh 不可用或 origin 无法解析出 owner/repo。**不要**把这条读成『没有在途 PR』。"
fi

# --- worktree 逐个体检 ---------------------------------------------------------
WT_ROWS=""
WT_JSON=""
while read -r wt; do
  [ -z "$wt" ] && continue
  br="$(git -C "$wt" branch --show-current 2>/dev/null)"
  [ -z "$br" ] && br="(DETACHED)"
  sha="$(git -C "$wt" rev-parse --short HEAD 2>/dev/null || echo '?')"
  ahead="$(git -C "$wt" rev-list --count "origin/master..HEAD" 2>/dev/null || echo '?')"
  behind="$(git -C "$wt" rev-list --count "HEAD..origin/master" 2>/dev/null || echo '?')"
  dirty="$(git -C "$wt" status --porcelain 2>/dev/null | grep -c . || true)"
  # 声明检查：分支名是否出现在 STATUS.md 里（.coord 是 gitignored，所以只看主检出）
  declared="未知"
  if [ -f "$STATUS_FILE" ]; then
    if grep -qF "$br" "$STATUS_FILE" 2>/dev/null; then declared="有"; else declared="**无声明**"; fi
  else
    DEGRADED=1
    declared="STATUS.md 不存在"
  fi
  note=""
  [ "${behind:-0}" != "0" ] && [ "$behind" != "?" ] && note="落后 master ${behind} 笔"
  [ "${dirty:-0}" != "0" ] && note="${note:+$note; }未提交 ${dirty} 个文件"
  WT_ROWS="${WT_ROWS}| \`${wt}\` | \`${br}\` @ \`${sha}\` | ahead=${ahead} | ${note:-无} | ${declared} |"$'\n'
  WT_JSON="${WT_JSON}{\"path\":\"${wt}\",\"branch\":\"${br}\",\"sha\":\"${sha}\",\"ahead\":\"${ahead}\",\"behind\":\"${behind}\",\"dirty\":\"${dirty}\",\"declared\":\"${declared}\"},"
done < <(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0,10)}')

# --- 未合入的远端分支（在途或弃稿，都该被看见） --------------------------------
UNMERGED="$(git branch -r --no-merged "origin/master" 2>/dev/null | sed 's#^ *##' | grep -v 'HEAD ->' | sed 's#origin/##' | paste -sd ', ' -)"
[ -z "$UNMERGED" ] && UNMERGED="（无）"

if [ "$MODE" = "json" ]; then
  printf '{"master":"%s","worktrees":[%s],"pr":"%s","unmerged_remote_branches":"%s","degraded":%s}\n' \
    "$MSHA" "${WT_JSON%,}" "$(printf '%s' "$PR_BLOCK" | tr '\n' ' ')" "$UNMERGED" "$DEGRADED"
  [ "$DEGRADED" = "1" ] && exit 2 || exit 0
fi

BEGIN='<!-- coord-status:begin 由 scripts/coord-status.sh 生成，别手改这一段 -->'
END='<!-- coord-status:end -->'
BLOCK="${BEGIN}"$'\n'
BLOCK="${BLOCK}## 在途与占用（$(date '+%Y-%m-%d %H:%M') 实测，origin/master = \`${MSHA}\`）"$'\n\n'
BLOCK="${BLOCK}| worktree | 分支 @ sha | 相对 master | 未提交面 | 开工声明 |"$'\n'
BLOCK="${BLOCK}|---|---|---|---|---|"$'\n'
BLOCK="${BLOCK}${WT_ROWS}"
BLOCK="${BLOCK}"$'\n'"**在途 PR**"$'\n\n'"${PR_BLOCK}"$'\n'
BLOCK="${BLOCK}"$'\n'"**未合入 master 的远端分支**：${UNMERGED}"$'\n'
BLOCK="${BLOCK}${END}"

if [ "$MODE" = "print" ]; then
  printf '%s\n' "$BLOCK"
  [ "$DEGRADED" = "1" ] && { warn "证据不全（见上），退出码 2。"; exit 2; }
  exit 0
fi

# --- --write：只替换标记块内的内容；没有标记块就插在「开工声明」之前 ------------
if [ ! -f "$STATUS_FILE" ]; then
  warn "无法判定：$STATUS_FILE 不存在，不凭空造状态板。"
  exit 2
fi
# 用文件传块，不用 awk -v：-v 会解释反斜杠转义，markdown 里迟早踩雷
BLK_FILE="$(mktemp)"
printf '%s\n' "$BLOCK" > "$BLK_FILE"
TMP_STATUS="${STATUS_FILE}.coord-status.tmp.$$"
if grep -qF "$BEGIN" "$STATUS_FILE" && grep -qF "$END" "$STATUS_FILE"; then
  awk -v b="$BEGIN" -v e="$END" -v blkf="$BLK_FILE" '
    function loadblk(  line, s) { while ((getline line < blkf) > 0) s = s line "\n"; close(blkf); return s }
    index($0, b) { inside=1; if (!emitted) { printf "%s", loadblk(); emitted=1 } next }
    index($0, e) { inside=0; next }
    !inside { print }
  ' "$STATUS_FILE" > "$TMP_STATUS"
else
  awk -v blkf="$BLK_FILE" '
    function loadblk(  line, s) { while ((getline line < blkf) > 0) s = s line "\n"; close(blkf); return s }
    !done && /^## 开工声明/ { printf "%s\n\n", loadblk(); done=1 }
    { print }
  ' "$STATUS_FILE" > "$TMP_STATUS"
  grep -qF "$BEGIN" "$TMP_STATUS" || warn "注意：状态板里没有标记块，本次是插入新段（此后每次刷新只动标记块内的内容）。"
fi
rm -f "$BLK_FILE"
if [ ! -s "$TMP_STATUS" ]; then
  warn "失败：生成的内容为空，保留原文件不动。"
  rm -f "$TMP_STATUS"
  exit 1
fi
# 断言一：他人手写段落必须还在（原文里有的才强求）
LOST=""
for anchor in "开工声明" "规则速记" "C 系列仅剩的三条"; do
  grep -qF "$anchor" "$STATUS_FILE" || continue
  grep -qF "$anchor" "$TMP_STATUS" || LOST="$LOST $anchor"
done
if [ -n "$LOST" ]; then
  warn "失败：新内容丢了既有段落：$LOST —— 放弃写入，原文件未动。"
  rm -f "$TMP_STATUS"
  exit 1
fi
# 断言二：新块在位，且没把文件写短（防 awk 写半截）
grep -qF "$BEGIN" "$TMP_STATUS" || { warn "失败：标记块没写进去。"; rm -f "$TMP_STATUS"; exit 1; }
if [ "$(grep -c . "$TMP_STATUS")" -lt "$(grep -c . "$STATUS_FILE")" ]; then
  warn "失败：新文件有效行数比原来少，疑似写坏。放弃，原文件未动。"
  rm -f "$TMP_STATUS"
  exit 1
fi
# 断言三：不许出现两个「在途与占用」段——手写段和生成段并存时，读板的人会挑到过期那份。
# 触发条件：本文件还没有标记块，但已存在手写同名段。此时必须拒绝写入，让人先迁移。
if [ "$(grep -c '^## 在途与占用' "$TMP_STATUS")" -gt 1 ] && ! grep -qF "$BEGIN" "$STATUS_FILE"; then
  warn "失败：状态板里已有一个手写的「## 在途与占用」段，生成段会与之并存，读的人挑不准哪份是新的。"
  warn "处理：把手写那段删掉（或改成别的标题），再跑 --write；此后本脚本只维护标记块内的内容。"
  rm -f "$TMP_STATUS"
  exit 1
fi
if [ "$(grep -c '^## 在途与占用' "$TMP_STATUS")" -ne 1 ]; then
  warn "失败：生成后「在途与占用」段数不是 1，放弃写入。"
  rm -f "$TMP_STATUS"
  exit 1
fi
mv "$TMP_STATUS" "$STATUS_FILE"
echo "已刷新 $STATUS_FILE 的「在途与占用」段（其余段落未动）。"
[ "$DEGRADED" = "1" ] && { warn "注意：本次证据不全（gh 或网络），PR 一栏是 UNVERIFIED。"; exit 2; }
exit 0
