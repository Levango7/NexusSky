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

# 标记块的边界（必须在脚本头部定义：自测函数与 count_outside 都要用它，
# 定义在主流程里就会让函数里取不到（set -u 下直接报 unbound variable）。
BEGIN='<!-- coord-status:begin 由 scripts/coord-status.sh 生成，别手改这一段 -->'
END='<!-- coord-status:end -->'

# --- 「我是谁」：本脚本自己的绝对路径 -----------------------------------------
# 必须在**任何 cd 之前**取：$0 是相对路径时，脚本后面会 cd 到仓库根，
# 那个相对路径就指向别的文件了。
# 为什么需要它：自测的默认目标原来写死成相对路径 `scripts/coord-status.sh`，
# 而自测跑之前会 `cd $selftest_root` ⇒ **从别的仓库目录用绝对路径调本脚本**时，
# 自测测的是「那个仓库的」同名脚本，不是你调用的这一份。
# 2026-11-02 实测事故：我从共享主树目录里调我分支的脚本，自测报「T7/T8 失败」，
# 实际失败的是主树那份 405 行的老版本（它没有多副本守卫、用 git status 计数）——
# 结论差点整个反了（我差点去"修"一个并不存在于我分支上的缺陷）。
SELF_INVOKED=""
if _self_dir="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"; then
  SELF_INVOKED="${_self_dir}/$(basename "$0")"
else
  SELF_INVOKED="$0"
fi

# 自测目标的**唯一**解析入口。--self-test-target 与 self_test 都走它：
# 两处各写一份的话迟早走偏，而走偏的后果是「自测测了别的文件却看不出来」。
# COORD_STATUS_SELF 仍是显式覆盖点（变异测试、复制副本时用）。
resolve_selftest_target() {
  if [ -n "${COORD_STATUS_SELF:-}" ]; then printf '%s' "$COORD_STATUS_SELF"; return 0; fi
  printf '%s' "$SELF_INVOKED"
}

# 文件内容哈希（sha256sum 在 Linux/WSL 有，shasum 是 macOS 的等价物）。
hash_of() {
  { sha256sum "$1" 2>/dev/null || shasum -a 256 "$1" 2>/dev/null; } | awk '{print $1}'
}

MODE="print"
case "${1:-}" in
  --write) MODE="write" ;;
  --json)  MODE="json" ;;
  --self-test) MODE="selftest" ;;
  --self-test-target) MODE="selftest-target" ;;
  "")      ;;
  *) echo "用法: bash scripts/coord-status.sh [--write|--json|--self-test|--self-test-target]" >&2; exit 64 ;;
esac

# --self-test-target：只回答「自测会去测哪一个文件」（路径 + 内容哈希），不跑自测。
# 用途：让「自测测的是不是我以为的那份」可被外部观测 —— T10 断言就靠它，
# 人排查时也能直接问一句，而不是靠猜。
if [ "$MODE" = "selftest-target" ]; then
  _tgt="$(resolve_selftest_target)"
  printf '%s\t%s\n' "$_tgt" "$(hash_of "$_tgt")"
  exit 0
fi

warn() { echo "$*" >&2; }

# --- 工作区路径的可移植解析 --------------------------------------------------
# Windows 建的 worktree，在 .git/worktrees/<name>/gitdir 里记的是 `F:/Nexus/...` 形式。
# 本脚本通常在 WSL 下跑（`bash scripts/coord-status.sh`），而 WSL 的 git 解析不了
# `F:/...`（它要 /mnt/f/...）⇒ 这些 worktree 全部退化成 `(DETACHED)` @ `?`，
# 分支名取不到 ⇒ 「在途与占用」表里它们的分支列全是问号，
# 而「谁的分支还没写开工声明」正是本板用来避撞车的判据（失真 = 撞车）。
# 实测（2026-11-01）：8 个 worktree 里 5 个显示成 DETACHED/未知。
# 解法：路径解析不了就换算成 <mnt-root>/<盘符小写>/... 再试；
# **展示仍用原路径**——agents 认的是 Windows 路径，写成 /mnt/f/... 他们反而找不到树。
# COORD_WSL_MNT_ROOT 是测试注入点（CI 在 Linux 上没有 /mnt，要能自证）。
resolve_wt() {
  _p="$1"
  if [ -d "$_p" ]; then printf '%s' "$_p"; return 0; fi
  case "$_p" in
    [A-Za-z]:/*)
      _drive="$(printf '%s' "$_p" | cut -c1 | tr 'A-Z' 'a-z')"
      _rest="$(printf '%s' "$_p" | cut -c3-)"
      _alt="${COORD_WSL_MNT_ROOT:-/mnt}/${_drive}${_rest}"
      if [ -d "$_alt" ]; then printf '%s' "$_alt"; return 0; fi
      ;;
  esac
  printf '%s' "$_p"
}

# --- worktree 列表：直接解析 `git worktree list --porcelain` ------------------------
# 输出每个工作区一行：<path>\t<HEAD sha>\t<branch>
# porcelain 格式（实测）：
#   worktree <path>
#   HEAD <40位 sha>
#   branch refs/heads/<name>      ← 或 "detached"
#   prunable gitdir file points to non-existent location   ← Windows 建的工作区在 WSL 下会带这条
# ⚠️ **不要只取路径、其余靠 `git -C` 现查**：Windows 建的工作区，其 .git 里记的
#    gitdir 是 Windows 路径（`F:/...`），WSL 的 git 解析不了 ⇒ 现查全部失败
#    ⇒ 分支与 sha 变成 (DETACHED) @ ?，而"这个分支有没有写开工声明"
#    正是本板避撞车的判据（失真 = 撞车）。实测 2026-11-01：9 个工作区里 5 个中招。
worktree_records() {
  git worktree list --porcelain 2>/dev/null | awk '
    /^worktree /{ if (p != "") print p "\t" h "\t" b; p = substr($0, 10); h = ""; b = ""; next }
    /^HEAD /{ h = substr($0, 6); next }
    /^branch /{ b = substr($0, 8); sub("^refs/heads/", "", b); next }
    /^detached/{ b = "(DETACHED)"; next }
    END{ if (p != "") print p "\t" h "\t" b }
  '
}

# --- 「未提交面」的内容级计数 ---------------------------------------------------
# 口径（2026-11-01 实测定案，**别再改回 `git status`**）：
#   `git status --porcelain` 判定「工作区已改」走的是 **stat 尺寸比对**
#   （index 里缓存的旧尺寸 vs 当前文件尺寸），**内容相等与否是另一条路**。
#   裸仓夹具实测（属性已声明 text eol=lf、工作区是 CRLF、blob 是 LF）：
#     git status --porcelain → " M a.txt"    ← 仍报已改
#     git diff --name-only   → 空             ← 内容其实相等
#     git hash-object a.txt  → == index blob  ← 内容逐字节相等
#   ⇒ 行尾归一后内容等价的文件，status 照样报"已改"。拿它计数，板面就会把
#   假改动当真改动 ⇒ 每个 agent 都以为有人在改主树而互相让路、不敢提交
#   （docs/agent-constraints.md 第 2 条就是这么写的）。
#
#   内容级口径 = 已跟踪文件的**真实内容差异**（staged + unstaged 都算）
#              + 未跟踪文件数。
#   ⚠️ stderr 必须丢掉：git 会把 "warning: in the working copy of X, CRLF will be
#      replaced by LF the next time Git touches it" 打到 stderr，混进来会被当文件名。
count_dirty() {
  local w="$1" n_tracked n_untracked
  # 连不上这个工作区 ⇒ **必须报「无法判定」，不能报 0**。
  # 报 0 是假绿：读板的人会把"git 根本没打开这个目录"读成"这个工作区很干净"，
  # 于是以为没人在这儿干活。实测（2026-11-01）：5 个 Windows 建的 worktree 在 WSL 下
  # 因 .git 里的 gitdir 是 Windows 路径而连不上 ⇒ 若报 0，板面就在说假话。
  if ! git -C "$w" rev-parse --git-dir >/dev/null 2>&1; then
    printf '?'
    return 0
  fi
  n_tracked="$(
    {
      if git -C "$w" rev-parse --verify -q HEAD >/dev/null 2>&1; then
        git -C "$w" diff --name-only HEAD 2>/dev/null
      else
        # 没有 HEAD（空仓）时退化成"暂存区 vs 工作区"+"HEAD vs 暂存区"
        git -C "$w" diff --name-only 2>/dev/null
        git -C "$w" diff --cached --name-only 2>/dev/null
      fi
    } | sort -u | grep -c . || true
  )"
  n_untracked="$(git -C "$w" ls-files --others --exclude-standard 2>/dev/null | grep -c . || true)"
  echo $(( ${n_tracked:-0} + ${n_untracked:-0} ))
}

# =============================================================================
# 自测：证明「取不到」不会被冒充成「没问题」，且 --write 只动标记块、不重复插。
# 全部在临时目录里跑，绝不碰真实的 .coord/STATUS.md。
# =============================================================================
self_test() {
  local fails="" tmp status out rc marks n1 n2 heads self pr_fix pr_fix2 pr_fix3
  local self_abs dup eolrepo bare realrepo eol_out d1 d2 d3 decoy _t10
  local _mk_repo _dirty_via_script
  SELF="$(resolve_selftest_target)"
  [ -f "$SELF" ] || { echo "无法判定：自测目标不存在（$SELF）——绝不测别的文件冒充通过。" >&2; return 1; }
  # **先**打印被测目标（路径 + 内容哈希）：**失败时也必须知道测的是哪一份**。
  # 2026-11-02 实测事故：默认目标解析到 CWD 下同名脚本时，自测测了另一个文件，
  # 而失败输出里根本没说测的是谁 —— 没有证据的失败，等于没有结论。
  echo "自测目标=$SELF（sha256=$(hash_of "$SELF")）"
  # 绝对路径副本：T8 要 cd 进临时仓库跑子调用，相对路径会找不到脚本
  self_abs="$(cd "$(dirname "$SELF")" && pwd)/$(basename "$SELF")"
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

  # T2/T3/T4/T5 也一律走 COORD_PR_FIXTURE 注入 —— 这几条断言的是"写回逻辑"（插入/幂等/
  # 并存拒绝/合法变短），不该被 GitHub 当场算出的 open PR 列表污染。
  # 上一版就是在这里翻车的：T2 那次写进去的是"UNVERIFIED 一行"，T3 第二次写进去的是
  # "若干 PR 若干行"，于是幂等断言报「22→23（不幂等，正文残留）」——脚本一行没改，
  # master 却连红四笔。**同一个原理 T1/T6 的注释里早就写了，只是没贯彻到 --write 那几条。**
  # 下面还加了一条"夹具确实被消费"的断言：谁把注入拿掉，这里就红，不会退回实时数据。
  pr_det="$tmp/prs-deterministic.tsv"
  printf '142\tfeat/x\tCONFLICTING\tDIRTY\t标题甲\n143\tfeat/y\tMERGEABLE\tCLEAN\t标题乙\n' > "$pr_det"

  # T1 gh 不可用 ⇒ 必须 UNVERIFIED 且 exit 2（绝不能报成「无在途 PR」）
  #    用 COORD_NO_GH 注入，不依赖网络/鉴权是否恰好坏——上一版拿真实 gh 调用做断言，结果
  #    同一份代码两次跑出不同结论，那叫自测自己在 flaky。
  out="$(COORD_NO_GH=1 bash "$SELF" 2>/dev/null)"; rc=$?
  printf '%s' "$out" | grep -q "UNVERIFIED" || fails="${fails}T1:降级没走 UNVERIFIED "
  printf '%s' "$out" | grep -q "当前无在途 PR" && fails="${fails}T1:取不到却伪装成「无在途 PR」 "
  [ "$rc" = "2" ] || fails="${fails}T1:期望退出 2 实得 $rc "

  # T2 首次 --write：插入标记块，且他人段落都在
  COORD_PR_FIXTURE="$pr_det" COORD_STATUS_FILE="$status" bash "$SELF" --write >/dev/null 2>&1
  grep -qF 'coord-status:begin' "$status" || fails="${fails}T2:没写入标记块 "
  for a in "开工声明" "规则速记" "C 系列仅剩的三条"; do
    grep -qF "$a" "$status" || fails="${fails}T2:丢了段落$a "
  done
  # 夹具必须真的被消费：否则说明又退回读实时数据，幂等断言就只是在测网络
  grep -qF "#142" "$status" || fails="${fails}T2:注入的 PR 夹具没进生成段（疑似仍在读实时数据） "
  grep -qF "#143" "$status" || fails="${fails}T2:注入的第二条 PR 夹具没进生成段 "

  # T3 重写必须**幂等**：begin 恰一份、标题段一份、有效行数不变
  #    （上一版只查了 begin 计数，结果 awk 丢了 inside 追踪、把旧块正文留在原地，
  #      自测照样绿——就是这种"绿着的失效"要盯住。）
  n1="$(grep -c . "$status")"
  t3_diff=""
  cp "$status" "$tmp/STATUS.t1"   # 留住第一次写回的正文，供不一致时当场 diff
  COORD_PR_FIXTURE="$pr_det" COORD_STATUS_FILE="$status" bash "$SELF" --write >/dev/null 2>&1; rc=$?
  # 重写必须**成功**（exit 0）且幂等。只断言"文件没变"会被"被守卫拒绝所以没变"蒙混过去——
  # 变异测试就是这么暴露的：正文重复的老 bug 触发了并存守卫，退出码 1，而 T3 仍判"通过"。
  [ "$rc" = "0" ] || fails="${fails}T3:重写应成功(exit0) 实得 $rc（多半是生成段自己撞出了重复） "
  marks="$(grep -c 'coord-status:begin' "$status")"
  heads="$(grep -c '^## 在途与占用' "$status")"
  n2="$(grep -c . "$status")"
  [ "$marks" = "1" ] || fails="${fails}T3:begin 出现 $marks 次（应 1） "
  [ "$heads" = "1" ] || fails="${fails}T3:在途与占用 段有 $heads 份（应 1） "
  if [ "$n1" != "$n2" ]; then
    fails="${fails}T3:重写后有效行数 $n1→$n2（两次写回的正文不一致） "
    # 只报「行数变了」等于把已经算出来的归因扔掉。原先这句写成「不幂等，正文残留」，
    # 这四个字是本脚本自己的措辞，它把人推向「写回逻辑坏了」这个假目标；实跑四笔红
    # 全是同一句 22→23，而 coord-status.sh 在那四笔里一行没动。⇒ 现在当场把差异打出来。
    t3_diff="$(diff "$tmp/STATUS.t1" "$status" | head -30)"
  fi

  # T4 已有手写「在途与占用」段且无标记块 ⇒ 拒绝写入（不能让两份并存）
  status2="$tmp/STATUS2.md"
  printf '# 板\n## 在途与占用（手写的旧段）\n旧内容\n## 开工声明\n- x\n## 规则速记\n- y\n' > "$status2"
  before="$(grep -c . "$status2")"
  COORD_PR_FIXTURE="$pr_det" COORD_STATUS_FILE="$status2" bash "$SELF" --write >/dev/null 2>&1; rc=$?
  [ "$rc" = "1" ] || fails="${fails}T4:并存场景期望 1 实得 $rc "

    # T5 旧块比新块长（worktree 变少）时，写回必须**成功**——合法变短不是「写坏」。
    # 这条是回归：守卫原先比整份文件的行数，块一变短就拒写，状态板自 2026-10-12 起
    # 一直写不进去（各 agent 的声明发不出去），而自测当时没覆盖到「变短」这一情形。
    status3="$tmp/STATUS3.md"
    {
      printf '# 板\n## 工程现状\n略\n'
      printf '%s\n' "$BEGIN"
      printf '%s\n' "$END"
      printf '## 开工声明\n- x\n## 规则速记\n- y\n'
    } > "$status3"
    # 先塞一段很长的旧块进去（模拟「上一版内容更长」）
    awk -v b="$BEGIN" '
      index($0,b){print; for(i=0;i<40;i++) print "| 旧块多出来的第 " i " 行"; next}
      {print}
    ' "$status3" > "$status3.tmp" && mv "$status3.tmp" "$status3"
    COORD_PR_FIXTURE="$pr_det" COORD_STATUS_FILE="$status3" bash "$SELF" --write >/dev/null 2>&1; rc=$?
    [ "$rc" = "0" ] || fails="${fails}T5:旧块更长时应仍写成功(exit0) 实得 $rc "
    grep -qF 'coord-status:begin' "$status3" || fails="${fails}T5:写不进去标记块 "
    grep -qF '规则速记' "$status3" || fails="${fails}T5:丢了手写段 "
  after="$(grep -c . "$status2")"
  [ "$before" = "$after" ] || fails="${fails}T4:拒绝写入却改了文件 "
  grep -qF 'coord-status:begin' "$status2" && fails="${fails}T4:被拒绝却仍插入了标记块 "

  # T5 目标文件不存在 ⇒ exit 2 且绝不凭空创建
  rm -f "$status"
  COORD_PR_FIXTURE="$pr_det" COORD_STATUS_FILE="$status" bash "$SELF" --write >/dev/null 2>&1; rc=$?
  [ "$rc" = "2" ] || fails="${fails}T5:缺文件时期望 2 实得 $rc "
  [ ! -e "$status" ] || fails="${fails}T5:不该凭空造状态板 "

  # T6 不可合并的 PR ⇒ 看板必须标出「Actions 不会被调度」。
  #    这条提示的价值全在"出现"与"不出现"都对，所以正反两面都要断言：
  #    ① CONFLICTING ⇒ 必须出现，且必须指向那条合成不出来的 refs/pull/<N>/merge；
  #    ② MERGEABLE ⇒ **不得**出现（别把正常的 PR 也标一遍）；
  #    ③ mergeable=UNKNOWN 是 GitHub 还没算完的暂态，不是冲突 ⇒ **不得**出现；
  #    ④ 夹具缺失 ⇒ 必须 UNVERIFIED 且退出 2，绝不能伪装成「没有在途 PR」。
  #    走 COORD_PR_FIXTURE 注入，不依赖 GitHub 当时算出的 mergeability。
  pr_fix="$tmp/prs-conflict.tsv"
  printf '142\tfeat/x\tCONFLICTING\tDIRTY\t标题\n' > "$pr_fix"
  out="$(COORD_PR_FIXTURE="$pr_fix" bash "$SELF" 2>/dev/null)"
  printf '%s' "$out" | grep -q "Actions 不会调度" || fails="${fails}T6:不可合并的 PR 没标出 Actions 不会调度 "
  printf '%s' "$out" | grep -q "refs/pull/142/merge" || fails="${fails}T6:提示没指出 refs/pull/142/merge "
  pr_fix2="$tmp/prs-ok.tsv"
  printf '143\tfeat/y\tMERGEABLE\tBLOCKED\t标题\n' > "$pr_fix2"
  out="$(COORD_PR_FIXTURE="$pr_fix2" bash "$SELF" 2>/dev/null)"
  printf '%s' "$out" | grep -q "Actions 不会调度" && fails="${fails}T6:可合并的 PR 被误标成 Actions 不会调度 "
  pr_fix3="$tmp/prs-unknown.tsv"
  printf '144\tfeat/z\tUNKNOWN\tUNKNOWN\t标题\n' > "$pr_fix3"
  out="$(COORD_PR_FIXTURE="$pr_fix3" bash "$SELF" 2>/dev/null)"
  printf '%s' "$out" | grep -q "Actions 不会调度" && fails="${fails}T6:mergeable=UNKNOWN 被误标成 Actions 不会调度 "
  out="$(COORD_PR_FIXTURE="$tmp/prs-missing.tsv" bash "$SELF" 2>/dev/null)"; rc=$?
  printf '%s' "$out" | grep -q "UNVERIFIED" || fails="${fails}T6:夹具缺失没走 UNVERIFIED "
  printf '%s' "$out" | grep -q "当前无在途 PR" && fails="${fails}T6:夹具缺失却伪装成「无在途 PR」 "
  [ "$rc" = "2" ] || fails="${fails}T6:夹具缺失时期望退出 2 实得 $rc "

  # T7 状态板有**多份完整副本** ⇒ 必须拒写（exit 1）且文件一字未动。
  #    2026-11-01 实测事故：本文件一度堆成 32 份完整副本（7.3 MB / 71067 行），
  #    而本脚本的 awk 只刷新**第一份**标记块（emitted 守卫），其余各份的
  #    「在途与占用」段会被**清空**——读板的人看到一段空的「在途与占用」，
  #    会误读成「当前没有在途工作」（真实情况是那段在第 68610 行）。
  #    所以这是必须拦下的情形。正反两面都断言：拒写 + 文件未动 + 说清原因。
  #
  #    ⚠️ 夹具必须**真的复现**被测条件：副本的标题行必须是真实状态板用的那一行
  #    （`# Agent 状态板（各 agent 开工前 / 提交前读一次）`）——守卫按这行计数。
  #    第一版夹具写的是 `# 板`，于是守卫数到 0 份、压根没触发，自测却报
  #    「期望拒写(exit1) 实得 0」——这正是"夹具不具判别性"的典型（agent-constraints 第 3 条）。
  #    夹具自建：T5 已经把 $status 删掉了（它断言"不凭空造板"），不能复用。
  dup="$tmp/STATUS-dup.md"
  single="$tmp/STATUS-single.md"
  cat > "$single" <<'EOF'
# Agent 状态板（各 agent 开工前 / 提交前读一次）

## 各 agent 现状

## 工程现状

## 开工声明
- 某人：声明

## 规则速记
- 某规则
EOF
  cat "$single" > "$dup"; printf '\n' >> "$dup"; cat "$single" >> "$dup"
  cp "$dup" "$tmp/STATUS-dup.before"
  dup_out="$(COORD_PR_FIXTURE="$pr_det" COORD_STATUS_FILE="$dup" bash "$SELF" --write 2>&1)"; rc=$?
  [ "$rc" = "1" ] || fails="${fails}T7:多副本状态板期望拒写(exit1) 实得 $rc "
  cmp -s "$dup" "$tmp/STATUS-dup.before" || fails="${fails}T7:多副本拒写却改了文件 "
  printf '%s' "$dup_out" | grep -qF "完整副本" || fails="${fails}T7:拒写时没说清原因 "
  # 反向：单份必须仍能写（守卫不能把正常路径也堵死）。判「标记块写进去了」而不是判退出码——
  # gh 不可用时脚本会走 UNVERIFIED 降级并 exit 2，那是**证据不全**的诚实退出，
  # 与「写不进去」是两回事，混在一起断言会让本自测在没网的环境里假红。
  COORD_PR_FIXTURE="$pr_det" COORD_STATUS_FILE="$single" bash "$SELF" --write >/dev/null 2>&1
  grep -qF 'coord-status:begin' "$single" || fails="${fails}T7:单份状态板反而写不进去 "

  # T8 「未提交面」必须按**内容**判定，不能按 `git status` 的 stat 判定。
  #
  # 2026-11-01 的实测链条（过程中推翻过自己两次，结论已定案）：
  #   现象：主工作区明明干净，WSL 下的 git 却报「未提交 89 个文件」。
  #   根因①（内容层）：**没被 .gitattributes 声明的类型，git 不套 clean 过滤器**
  #        ⇒ Windows 的 core.autocrlf=true 检出成 CRLF、blob 却是 LF，
  #          同一份检出在 Windows git 与 WSL git 两侧判定相反。
  #        实测（主树，WSL git，**缓存无关**的逐文件哈希比对）：
  #        真实内容不一致 89 个；挂上补齐后的 .gitattributes → **1 个**
  #        （剩的 1 个是根 pom.xml 的混合行尾，已单独归一）。治本靠仓库层。
  #        ⚠️ 别用 `git diff`/`git status` 的数字当"内容真值"：共享 index 的 stat 缓存
  #        是跨 git 配置共享的，谁最后刷新谁说了算（缓存说"没改"就直接跳过内容比较）。
  #   根因②（stat 层）：`git status` 判定「工作区已改」走 **stat 尺寸比对**，
  #        内容相等与否是另一条路。裸仓夹具实测（属性已声明 text eol=lf）：
  #          git status --porcelain → " M a.txt"   ← 仍报已改
  #          git diff --name-only   → 空            ← 内容其实相等
  #          git hash-object a.txt  → == index blob ← 内容逐字节相等
  #        ⇒ 光靠属性覆盖修不了 status 的 stat 判定 ⇒ 脚本层必须按内容比。
  #
  # 本条断言**脚本层**的性质，用三个夹具把"真差异"与"等价差异"分开：
  #   ① 属性已覆盖 + 工作区 CRLF ⇒ 内容等价 ⇒ 计数必须 0
  #   ② 属性未覆盖 + 工作区 CRLF ⇒ 字节真差异 ⇒ 计数必须 1（判别性：夹具不能空转）
  #   ③ 属性已覆盖 + 内容真改了   ⇒ 计数必须 1（反"永远 0"的假绿）
  eolrepo="$tmp/eolrepo"; bare="$tmp/bare"; realrepo="$tmp/realrepo"
  mkdir -p "$eolrepo" "$bare" "$realrepo"
  # 建仓：init + 提交 + 造 origin/master 引用（脚本靠它取 origin/master，取不到就 exit 2）
  _mk_repo() {
    if [ -n "${2:-}" ]; then printf '%s' "$2" > "$1/.gitattributes"; fi
    ( cd "$1" \
      && git init -q . \
      && git config user.email t@t && git config user.name t \
      && printf 'line1\nline2\n' > a.txt \
      && git add -A && git commit -q -m init \
      && git update-ref refs/remotes/origin/master HEAD ) >/dev/null 2>&1
  }
  # 读脚本自己算出来的 dirty 数：走 --json 拿机器可读字段。
  # （板面正文里的「未提交 N 个文件」只在 N≠0 时才出现，拿它断言 0 是**结构上读不到**的。）
  _dirty_via_script() {
    ( cd "$1" && COORD_NO_GH=1 COORD_STATUS_FILE="$tmp/status-out.md" \
        bash "$self_abs" --json 2>/dev/null ) \
      | grep -o '"dirty":"[0-9]*"' | head -1 | grep -o '[0-9]*' || true
  }
  printf '# Agent 状态板\n' > "$tmp/status-out.md"
  # ① 属性已覆盖 + CRLF 工作区 ⇒ 内容等价 ⇒ 0
  _mk_repo "$eolrepo" '*.txt text eol=lf
'
  printf 'line1\r\nline2\r\n' > "$eolrepo/a.txt"
  d1="$(_dirty_via_script "$eolrepo")"
  [ "$d1" = "0" ] || fails="${fails}T8:内容等价的 CRLF 差异被算成 ${d1:-<空>} 个（应 0） "
  # ② 属性未覆盖 + CRLF 工作区 ⇒ 字节真差异 ⇒ 1（判别性）
  _mk_repo "$bare" ''
  printf 'line1\r\nline2\r\n' > "$bare/a.txt"
  d2="$(_dirty_via_script "$bare")"
  [ "$d2" = "1" ] || fails="${fails}T8:夹具不具判别性（无属性覆盖的字节差异应算 1 个，实得 ${d2:-<空>}） "
  # ③ 属性已覆盖 + 真改动 ⇒ 1（反假绿）
  _mk_repo "$realrepo" '*.txt text eol=lf
'
  printf 'line1\nline2-CHANGED\n' > "$realrepo/a.txt"
  d3="$(_dirty_via_script "$realrepo")"
  [ "$d3" = "1" ] || fails="${fails}T8:真实改动没被算出来（应 1 个，实得 ${d3:-<空>}） "
  # ④ 端到端：真实改动的数字必须真的出现在板面正文里（不能只在 JSON 里对）
  #    注意：本脚本**没有 --print 这个模式**（无参即 print 模式），
  #    传 --print 会走「用法错误」分支 exit 64、输出为空 ⇒ 断言必然假红。
  eol_out="$( cd "$realrepo" && COORD_NO_GH=1 COORD_STATUS_FILE="$tmp/status-out.md" \
              bash "$self_abs" 2>/dev/null )"
  case "$eol_out" in
    *"未提交 1 个文件"*) : ;;
    *) fails="${fails}T8:板面正文里没有「未提交 1 个文件」" ;;
  esac
  # ⑤ **连不上 ⇒ 报「无法判定」，不许报 0**（0 是假绿：会被读成"这个工作区很干净"）。
  #    实测背景：Windows 建的 worktree，其 .git 里记的 gitdir 是 Windows 路径，
  #    WSL 的 git 解析不了 ⇒ 5 个工作区在板面上是 (DETACHED) @ ?。
  d_unknown="$(count_dirty "/nonexistent-worktree-path-for-selftest")"
  [ "$d_unknown" = "?" ] || fails="${fails}T8:连不上的工作区应报「无法判定」，实得 '${d_unknown:-<空>}' "

  # T9 工作区路径必须**可移植解析**：Windows 建的 worktree 记的是 `F:/...`，
  #    WSL 的 git 要 `/mnt/f/...`。解析不了 ⇒ 分支名取不到 ⇒ 板面把该 worktree
  #    显示成 `(DETACHED)` @ `?` ⇒ 「谁的分支还没写开工声明」这个防撞车判据失真。
  #    2026-11-01 实测：8 个 worktree 里 5 个是这样。
  #    夹具用 COORD_WSL_MNT_ROOT 注入挂载根，CI（Linux，无 /mnt）也能自证。
  mkdir -p "$tmp/mnt/x/wt"
  p1="$(COORD_WSL_MNT_ROOT="$tmp/mnt" resolve_wt "X:/wt")"
  [ "$p1" = "$tmp/mnt/x/wt" ] || fails="${fails}T9:盘符路径没换算成 mnt 形式（实得 '$p1'） "
  p2="$(resolve_wt "$tmp/mnt")"
  [ "$p2" = "$tmp/mnt" ] || fails="${fails}T9:本来就存在的路径被改写了（实得 '$p2'） "
  p3="$(COORD_WSL_MNT_ROOT="$tmp/mnt" resolve_wt "Z:/nonexistent")"
  [ "$p3" = "Z:/nonexistent" ] || fails="${fails}T9:换算不成立时应原样返回（实得 '$p3'） "

  # T10 自测目标必须就是**被调用的那一份**，不能是 CWD 下的同名文件。
  #    2026-11-02 实测事故：从共享主树目录用绝对路径调我分支的脚本，
  #    自测默认目标解析到主树的 scripts/coord-status.sh（405 行的老版本：
  #    没有多副本守卫、用 git status 计数）⇒ 我看到的是「我的脚本 T7/T8 失败」，
  #    实际失败的是老脚本的缺陷，结论差点整个反了。
  #    诱饵夹具：造一个仓库，里面放一份**同名但内容不同**的脚本；
  #    在该仓库里调用被测脚本 ⇒ 解析到的目标若指向诱饵，T10 转红。
  #    ⚠️ 必须 env -u COORD_STATUS_SELF：父进程若带着这个注入点，
  #    子进程就会沿用它 ⇒ 诱饵根本不会被选中，T10 变成永远绿的空断言。
  decoy="$tmp/decoy"
  mkdir -p "$decoy/scripts"
  ( cd "$decoy" && git init -q . && git config user.email t@t && git config user.name t ) >/dev/null 2>&1
  printf '#!/usr/bin/env bash\necho DECOY\n' > "$decoy/scripts/coord-status.sh"
  _t10="$( cd "$decoy" && env -u COORD_STATUS_SELF bash "$self_abs" --self-test-target 2>/dev/null | head -1 | cut -f1 )"
  if [ "$_t10" != "$self_abs" ]; then
    fails="${fails}T10:自测目标解析到了别的文件（期望 '$self_abs'，实得 '${_t10:-<空>}'） "
  fi

  rm -rf "$tmp"
  if [ -n "$fails" ]; then
    echo "自测失败：$fails" >&2
    if [ -n "$t3_diff" ]; then
      echo "---- T3 两次写回的正文差异（< 第一次 / > 第二次）----" >&2
      printf '%s\n' "$t3_diff" >&2
    fi
    return 1
  fi
  echo "自测通过：假 slug→UNVERIFIED/exit2、首写插入且他人段落齐全、重写幂等（begin/标题各 1 份、行数不变）、"
  echo "手写段并存→拒绝写入且文件未被改动、缺文件→exit2 且不凭空造板、"
  echo "不可合并的 PR→标出 Actions 不会调度（可合并与 UNKNOWN 都不标、夹具缺失→UNVERIFIED）、"
  echo "多份副本→拒写且文件未动（单份仍可写）、未提交面按内容判定→"
  echo "内容等价的 CRLF 差异算 0 个、字节真差异算 1 个（判别性对照）、真实改动算 1 个（反假绿）、"
  echo "数字能落到板面正文里、"
  echo "Windows 盘符路径→可换算成 mnt 形式解析（原有路径不动、换算不成立时原样返回）、"
  echo "自测目标恒为**被调用的那一份**（同名诱饵副本不被劫持）。"
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

# gh 的可执行文件名要**两个都试**：Windows 上装的是 `gh.exe`，
# 而 WSL 侧 PATH 里通常只有 `gh.exe`（没有无后缀的 `gh`）⇒
# 只判 `command -v gh` 会让整条「在途 PR」永远走 UNVERIFIED 降级，
# 于是板面把"查不到"写成了"没有在途 PR"——**证据丢失**。
# 实测（2026-11-01）：本机 gh 在 F:\AppData\gh\bin\gh.exe，WSL 里挂上
# /mnt/f/AppData/gh/bin 后可用（Windows 的 gh 配置在 %APPDATA%，WSL 侧同样读得到）。
GH_BIN=""
if command -v gh >/dev/null 2>&1; then GH_BIN="gh"
elif command -v gh.exe >/dev/null 2>&1; then GH_BIN="gh.exe"
fi

# --- 在途 PR：用真实调用判定可用性，别拿假探测当证据 ----------------------------
# 上一版用 `gh api repos` 探测，它本来就该 404 ⇒ gh 明明能用却被报成 UNVERIFIED。
# 现在的判据是「真调用的退出码」：0 且空列表＝确实没有在途 PR；非 0＝UNVERIFIED。
PR_BLOCK=""
PR_CI_WARN=0
PR_TSV_FILE=""
PR_TSV_TMP=""
# COORD_NO_GH=1 是测试注入点：让降级分支可被确定性验证（不依赖网络/鉴权是否恰好坏）
# COORD_PR_FIXTURE 是另一个注入点：给它一个 TSV 文件（列与 gh --jq 输出同构：
#   number \t branch \t mergeable \t mergeStateStatus \t title），就用它代替真实调用——
#   这样「不可合并 ⇒ Actions 不会被调度」这条提示的**出现与不出现**都能被确定性断言，
#   不必等 GitHub 什么时候算出 mergeability（它常常是 UNKNOWN 的暂态）。
if [ "${COORD_NO_GH:-0}" = "1" ]; then
  DEGRADED=1
  PR_BLOCK="UNVERIFIED：gh 取不到（此处为注入的降级场景），**不要**把这条读成『没有在途 PR』。"
elif [ -n "${COORD_PR_FIXTURE:-}" ]; then
  if [ ! -s "${COORD_PR_FIXTURE}" ]; then
    DEGRADED=1
    PR_BLOCK="UNVERIFIED：注入的 PR 夹具 \`${COORD_PR_FIXTURE}\` 不存在或是空文件。**不要**把这条读成『没有在途 PR』。"
  else
    PR_TSV_FILE="${COORD_PR_FIXTURE}"
  fi
elif [ -n "$REPO_SLUG" ] && [ -n "$GH_BIN" ]; then
  PR_TSV_FILE="$(mktemp)"
  PR_TSV_TMP="$PR_TSV_FILE"
  # ⚠️ Windows 上跑的 gh.exe 输出是 CRLF ⇒ 不去掉 \r，最后一列（title）会带上回车
  if "$GH_BIN" pr list --repo "$REPO_SLUG" --state open \
       --json number,title,headRefName,mergeable,mergeStateStatus \
       --jq '.[] | [.number, .headRefName, .mergeable, .mergeStateStatus, .title] | @tsv' \
       2>/dev/null | tr -d '\r' > "$PR_TSV_FILE"; then
    :
  else
    DEGRADED=1
    PR_BLOCK="UNVERIFIED：\`gh pr list --repo $REPO_SLUG\` 调用失败（鉴权/网络/限流）。**不要**把这条读成『没有在途 PR』。"
    rm -f "$PR_TSV_FILE"
    PR_TSV_FILE=""
  fi
else
  DEGRADED=1
  PR_BLOCK="UNVERIFIED：gh 不可用或 origin 无法解析出 owner/repo。**不要**把这条读成『没有在途 PR』。"
fi

if [ -n "$PR_TSV_FILE" ]; then
  if [ -z "$PR_BLOCK" ]; then
    if [ ! -s "$PR_TSV_FILE" ]; then
      PR_BLOCK="（当前无在途 PR）"
    else
      while IFS=$'\t' read -r num br mr ms title; do
        [ -z "$num" ] && continue
        mark=""
        # 不可合并 ⇒ GitHub 合成不出 refs/pull/<N>/merge ⇒ pull_request workflow 不会被调度
        # （零 run / 零 Actions check suite / 零失败记录）。这条不写出来，看板就会让人
        # 去查「GitHub 是不是挂了」。mergeable=UNKNOWN 是 GitHub 还没算完的暂态，不是冲突。
        if [ "$mr" = "CONFLICTING" ] || [ "$ms" = "DIRTY" ]; then
          mark=" ⚠不可合并⇒Actions 不会调度（GitHub 合成不出 refs/pull/${num}/merge）"
          PR_CI_WARN=1
        fi
        PR_BLOCK="${PR_BLOCK}#${num} \`[${br}]\` merge=${mr}/${ms}${mark} :: ${title}"$'\n'
      done < "$PR_TSV_FILE"
    fi
  fi
  [ -n "$PR_TSV_TMP" ] && rm -f "$PR_TSV_TMP"
fi

if [ "$PR_CI_WARN" = "1" ]; then
  PR_BLOCK="${PR_BLOCK}"$'\n'"**上面标 ⚠ 的 PR：GitHub 合成不出 \`refs/pull/<N>/merge\` ⇒ \`pull_request\` workflow **不会被调度**（零 run / 零 Actions check suite / 零失败记录；第三方 App 的 check suite 照常出现）。⇒ 「CI 没跑」多半是这个，不是 GitHub 故障——先解冲突。"$'\n'
fi

# --- worktree 逐个体检 ---------------------------------------------------------
# `git worktree list --porcelain` **本身就给了每个工作区的 HEAD sha 与分支名**
# （"HEAD <sha>" / "branch refs/heads/<x>" / "detached"）。
# 2026-11-01 实测：之前这里只取了路径，分支与 sha 全靠 `git -C <worktree>` 现查；
# 而 Windows 建的 worktree，其 .git 里记的 gitdir 是 Windows 路径（`F:/...`），
# WSL 的 git 解析不了 ⇒ **9 个工作区里 5 个的分支与 sha 变成 (DETACHED) @ ?**，
# 而"这个分支有没有写开工声明"正是本板避撞车的判据（失真 = 撞车）。
# 修法：**直接解析 porcelain 输出**，不进工作区也拿得到分支与 sha；
# 只有「未提交面」必须进工作区，连不上就报「无法判定」（绝不报 0，那是假绿）。
WT_ROWS=""
WT_JSON=""
while IFS=$'\t' read -r wt wt_head wt_branch; do
  [ -z "$wt" ] && continue
  # 展示用原路径（agents 认 Windows 路径），取 git 信息用可解析的那个
  wt_git="$(resolve_wt "$wt")"
  br="$wt_branch"
  [ -z "$br" ] && br="(DETACHED)"
  sha="$(printf '%s' "$wt_head" | cut -c1-7)"
  [ -z "$sha" ] && sha="?"
  # ahead/behind 用主仓里的 origin/master 与**记录下来的 sha**算，不必进工作区
  ahead="$(git rev-list --count "origin/master..${wt_head:-HEAD}" 2>/dev/null || echo '?')"
  behind="$(git rev-list --count "${wt_head:-HEAD}..origin/master" 2>/dev/null || echo '?')"
  # 「未提交面」用**内容级**口径（count_dirty），**不用** `git status --porcelain`。
  #
  # 2026-11-01 实测踩过的坑（先说为什么不用 status）：当时怀疑是行尾问题，
  # 想用 `git -c core.autocrlf=input status --porcelain` 归一后统计。在真实仓里
  # 它确实把 89 降到 0，但在**裸仓库**夹具里它**一点用都没有**（1→1，false/input/
  # true 全试过）⇒ 真正起作用的是 .gitattributes 覆盖，那个开关只是"看起来修好了"。
  #
  # 两条根因、两条修法（都已实测）：
  #   ① 内容层：**没被 .gitattributes 声明的类型，git 不套 clean 过滤器**，
  #      Windows 的 core.autocrlf=true 检出成 CRLF、blob 却是 LF ⇒ 同一份检出
  #      在 Windows git 与 WSL git 两侧判定相反（主树实测：WSL 侧内容级差异 89 个；
  #      挂上补齐后的 .gitattributes 后 → **0 个**，autocrlf=false/input/true 三种
  #      口径都是 0）。修法在仓库层：.gitattributes 覆盖全部文本类型（治本）。
  #   ② stat 层：`git status` 判定「工作区已改」走 **stat 尺寸比对**，
  #      内容相等与否是另一条路 ⇒ 属性覆盖到位后 status 仍可能报 M
  #      （裸仓夹具实测：" M a.txt" / diff 空 / hash-object 等于 index blob）。
  #      修法在脚本层：按内容比（count_dirty）。
  dirty="$(count_dirty "$wt_git")"
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
  if [ "$dirty" = "?" ]; then
    # 「无法判定」要**明写**，不能落到"无"里——"无"会被读成"这个工作区没人动"。
    note="${note:+$note; }**未提交面无法判定**（git 连不上这个工作区）"
  elif [ "${dirty:-0}" != "0" ]; then
    note="${note:+$note; }未提交 ${dirty} 个文件"
  fi
  WT_ROWS="${WT_ROWS}| \`${wt}\` | \`${br}\` @ \`${sha}\` | ahead=${ahead} | ${note:-无} | ${declared} |"$'\n'
  WT_JSON="${WT_JSON}{\"path\":\"${wt}\",\"branch\":\"${br}\",\"sha\":\"${sha}\",\"ahead\":\"${ahead}\",\"behind\":\"${behind}\",\"dirty\":\"${dirty}\",\"declared\":\"${declared}\"},"
done < <(worktree_records)

# --- 未合入的远端分支（在途或弃稿，都该被看见） --------------------------------
UNMERGED="$(git branch -r --no-merged "origin/master" 2>/dev/null | sed 's#^ *##' | grep -v 'HEAD ->' | sed 's#origin/##' | paste -sd ', ' -)"
[ -z "$UNMERGED" ] && UNMERGED="（无）"

if [ "$MODE" = "json" ]; then
  printf '{"master":"%s","worktrees":[%s],"pr":"%s","unmerged_remote_branches":"%s","degraded":%s}\n' \
    "$MSHA" "${WT_JSON%,}" "$(printf '%s' "$PR_BLOCK" | tr '\n' ' ')" "$UNMERGED" "$DEGRADED"
  [ "$DEGRADED" = "1" ] && exit 2 || exit 0
fi

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

# 数「标记块之外」的非空行数。守卫要比的是这一部分：标记块内的内容由本脚本自己
# 生成，长度本就该随实测变化（worktree 少了、PR 没了都会变短）——拿整份文件的行数
# 去比，会把「合法变短」误判成「写坏」，于是 --write 被拒，状态板从此冻住。
count_outside() {
  awk -v mk_b="$BEGIN" -v mk_e="$END" '
    index($0, mk_b) { inside=1; next }
    index($0, mk_e) { inside=0; next }
    !inside && NF { c++ }
    END { print c+0 }
  ' "$1"
}

# --- --write：只替换标记块内的内容；没有标记块就插在「开工声明」之前 ------------
if [ ! -f "$STATUS_FILE" ]; then
  warn "无法判定：$STATUS_FILE 不存在，不凭空造状态板。"
  exit 2
fi

# --- 多副本守卫：状态板必须只有一份完整副本 --------------------------------
# 2026-11-01 实测事故：本文件一度堆成 **32 份**完整副本（7.3 MB / 71067 行）——
# 多个会话每次刷新都把整块板追加到文件末尾，而不是就地改。后果：
#   ① 30 份是 10-25 ~ 10-28 的陈旧快照，读板的人打开哪份取决于翻到哪；
#   ② 头部与尾部两份「较新」的副本互相矛盾（名册各不相同），无从判断哪份权威；
#   ③ **本脚本的 awk 只刷新第一份标记块**（emitted 守卫），其余各份的
#      「在途与占用」段会被**清空**——读板的人看到一段空的「在途与占用」，
#      会误读成「当前没有在途工作」，而真实情况是这段在第 68610 行。
# 所以这里必须**拒写**：先收敛成一份（保留最新那份，其余归档到
# .coord/STATUS-HISTORY.md），再重跑。门禁见 scripts/check-coord-board.py。
_ncopies="$(grep -c '^# Agent 状态板' "$STATUS_FILE" 2>/dev/null || true)"
case "${_ncopies:-0}" in ''|*[!0-9]*) _ncopies=0 ;; esac
if [ "$_ncopies" -gt 1 ]; then
  warn "失败：状态板里有 ${_ncopies} 份完整副本（标题行 '# Agent 状态板' 出现 ${_ncopies} 次）。"
  warn "为什么必须拒写：本脚本只刷新**第一份**标记块，其余各份的「在途与占用」段会被**清空**——"
  warn "  读板的人看到一段空的「在途与占用」会误读成「没有在途工作」。"
  warn "处理：先收敛成一份（保留最新那份，其余原样归档到 .coord/STATUS-HISTORY.md），再重跑。"
  exit 1
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
  # 只比标记块之外的内容（手写段必须一字不少）。比整份文件行数是错的：
  # 自动段本就该随实测变长变短，一变短就把合法写入判成「写坏」——2026-10-20 实测
  # 状态板自 2026-10-12 起就是这样冻住的（工作树减少后 --write 一直被拒）。
  _outside_before="$(count_outside "$STATUS_FILE")"
  _outside_after="$(count_outside "$TMP_STATUS")"
  if [ "$_outside_after" -lt "$_outside_before" ]; then
    warn "失败：标记块之外的内容变少了（$_outside_before -> $_outside_after 行），疑似写坏。放弃，原文件未动。"
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
