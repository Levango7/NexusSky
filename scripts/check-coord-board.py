#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
门禁：协作状态板（.coord/STATUS.md）必须只有**一份**完整副本。

**为什么需要它**
---------------
`.coord/STATUS.md` 是多 agent 协作的留言板（开工声明 + 在途占用）。2026-11-01 实测，
它被多个会话**整块追加**成 **32 份**完整副本（7.3 MB / 71067 行）：

- 30 份是 2026-10-25 ~ 10-28 的陈旧快照，读者打开哪份取决于翻到哪；
- 头部与尾部两份「较新」的副本**互相矛盾**（名册各不相同），无从判断哪份权威；
- `scripts/coord-status.sh --write` 的 awk 只刷新**第一份**标记块（`emitted` 守卫），
  其余各份的「在途与占用」段会被**清空** —— 读板的人看到一段空的「在途与占用」，
  会误读成「当前没有在途工作」，而真实内容在第 68610 行。

这正是本仓主线一直在清的病（「声明 ≠ 行为」）长在**协作机制自己身上**。

**范围**：`.coord/` 是 gitignored 的，仓库里没有这个文件，所以 CI 上本门禁只跑
`--self-test`（证明它能拦住多副本、且不误拦干净态）；真检查在**本机**跑。

**用法**
------
    python scripts/check-coord-board.py                # 检查本机 .coord/STATUS.md
    python scripts/check-coord-board.py --self-test    # 自证（正例/反例/干净态）
    python scripts/check-coord-board.py --root F:/repo # 指定仓库根

退出码：0=通过（或文件不存在），1=有问题。
"""
import argparse
import io
import os
import sys

BOARD_TITLE = "# Agent 状态板"
BEGIN_MARK = "<!-- coord-status:begin"
OCCUPANCY_HEADING = "## 在途与占用"

# 一份板大约 3000 行；给 2 倍余量。超过即说明又开始整块复制了。
MAX_LINES = 6000


def read_text(path):
    with io.open(path, encoding="utf-8", newline="") as fh:
        return fh.read()


def check_board(text, label="STATUS.md"):
    """返回问题列表。"""
    problems = []
    lines = text.replace("\r\n", "\n").split("\n")

    copies = sum(1 for l in lines if l.startswith(BOARD_TITLE))
    if copies != 1:
        problems.append(
            "%s 里有 %d 份完整状态板副本（标题行 %r 出现 %d 次，应为 1）。"
            % (label, copies, BOARD_TITLE, copies))
        if copies > 1:
            problems.append(
                "  后果：coord-status.sh --write 只刷新第一份标记块，其余各份的"
                "「在途与占用」段会被清空；读板的人看到空段会误读成「没有在途工作」。")
            problems.append(
                "  处理：收敛成一份（保留最新那份，其余原样归档到 .coord/STATUS-HISTORY.md）。")

    begins = sum(1 for l in lines if BEGIN_MARK in l)
    if begins != 1:
        problems.append("%s 里有 %d 个 coord-status 标记块（应为 1）" % (label, begins))

    occ = sum(1 for l in lines if l.startswith(OCCUPANCY_HEADING))
    if occ != 1:
        problems.append("%s 里有 %d 个「%s」段（应为 1）"
                        % (label, occ, OCCUPANCY_HEADING))

    if len(lines) > MAX_LINES:
        problems.append("%s 有 %d 行（上限 %d）—— 疑似又开始整块复制状态板"
                        % (label, len(lines), MAX_LINES))
    return problems


def self_test():
    """正例 / 反例 / 干净态 三种情形都要有——只会说"通过"的门禁不是门禁。"""
    ok = {}

    clean = ("# Agent 状态板（各 agent 开工前 / 提交前读一次）\n\n"
             "## 各 agent 现状\n\n"
             "## 在途与占用（2026-11-01 22:20 实测）\n\n"
             "<!-- coord-status:begin 由 scripts/coord-status.sh 生成，别手改这一段 -->\n"
             "内容\n"
             "<!-- coord-status:end -->\n\n"
             "## 开工声明\n- x\n")
    ok["干净板不误拦"] = check_board(clean) == []

    dup = clean + "\n" + clean
    probs = check_board(dup)
    ok["多份副本被拦"] = any("副本" in p for p in probs)

    two_occ = ("# Agent 状态板（各 agent 开工前 / 提交前读一次）\n\n"
               "## 在途与占用（手写）\n\n"
               "<!-- coord-status:begin 由 scripts/coord-status.sh 生成，别手改这一段 -->\n"
               "内容\n"
               "<!-- coord-status:end -->\n\n"
               "## 在途与占用（生成）\n\n"
               "## 开工声明\n- x\n")
    probs = check_board(two_occ)
    ok["两个「在途与占用」被拦"] = any("在途与占用" in p for p in probs)

    no_mark = ("# Agent 状态板（各 agent 开工前 / 提交前读一次）\n\n"
               "## 开工声明\n- x\n")
    probs = check_board(no_mark)
    ok["缺标记块被拦"] = any("标记块" in p for p in probs)

    huge = clean + ("\n" + "x\n" * (MAX_LINES + 10))
    probs = check_board(huge)
    ok["超长被拦"] = any("上限" in p for p in probs)

    failed = [k for k, v in ok.items() if not v]
    for k, v in ok.items():
        print("  [%s] %s" % ("ok" if v else "FAIL", k))
    if failed:
        print("自测失败：%s" % "、".join(failed), file=sys.stderr)
        return 1
    print("自测通过（正例/反例/干净态共 %d 情形）" % len(ok))
    return 0


def main():
    ap = argparse.ArgumentParser(description="校验协作状态板只有一份完整副本")
    ap.add_argument('--root', default=os.path.abspath(
        os.path.join(os.path.dirname(__file__), '..')), help='仓库根目录')
    ap.add_argument('--self-test', action='store_true', help='只跑自测')
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    board = os.path.join(args.root, '.coord', 'STATUS.md')
    if not os.path.isfile(board):
        print("本机没有 %s（.coord 是 gitignored 的，CI 上没有是正常的）—— 跳过。" % board)
        return 0

    problems = check_board(read_text(board))
    if problems:
        print("状态板结构门禁：%d 项问题" % len(problems), file=sys.stderr)
        for p in problems:
            print("  - %s" % p, file=sys.stderr)
        return 1
    n = len(read_text(board).replace("\r\n", "\n").split("\n"))
    print("状态板结构门禁：通过（1 份副本 / 1 个标记块 / 1 个「在途与占用」段 / %d 行）" % n)
    return 0


if __name__ == '__main__':
    sys.exit(main())
