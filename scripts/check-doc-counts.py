#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：README 目录树里的「脚本数 / e2e 脚本数 / 文档篇数」必须等于实测值。

为什么需要
----------
2026-10-24 实测发现 README 里三处计数全部过期，且**没有任何门禁在看它们**：

    README:642   `scripts/` 下 28 个 `e2e-*` 脚本中，7 个进 CI 门禁
    README:716   50 个脚本（其中 28 个 e2e-*）
    README:718   32 篇文档

    实测（同日）：e2e-* 44 个（28 .ps1 / 11 .sh / 4 .py / 1 .cmd）、
                  scripts/ 89 项（含 2 个子目录）、docs/ 39 篇 .md

根因：#114 起大量 e2e 脚本跨平台适配（新增 .ps1 版本）、文档持续增加，
而这两个数字从 2026-10-06 写进 README 后没人再核过。
本仓库已有「端点数声称」「测试数声称」门禁，**目录计数这一类没有**，本脚本补上。

口径（刻意写死，避免"换个算法数出来不一样"）
-------------------------------------------
  1. 「N 个脚本」= `scripts/` 下的**条目数**（os.listdir，含子目录，不含 . / ..）。
  2. 「其中 M 个 e2e-*」= `scripts/e2e-*` 的条目数（任意扩展名）。
  3. 「N 篇文档」= `docs/*.md` 的文件数。

只校验这两种行形态，**不碰 README 里其他数字**（模块数、消息数、端点数各有其主，
混在一起校验会误报——与 check-claimed-endpoint-count.py 的教训一致：误报比漏检贵）。

用法：
    python scripts/check-doc-counts.py
    python scripts/check-doc-counts.py --self-test
退出码：0 一致；1 有不一致或锚点缺失。
"""
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "README.md")

# 注意：正则里直接写中文，**不要**用 r"...\uXXXX" —— raw string 不解释 \u 转义，
# 会静默匹配不到任何行（2026-10-24 首版就踩了这个坑，自测当场抓住）。
ANCHORS = (
    ("scripts/ 目录树的脚本总数",
     re.compile(r"^\s*├── scripts/\s+.*?/\s*(\d+) 个脚本（其中 (\d+) 个 e2e-\*"),
     "scripts"),
    ("docs/ 目录树的文档篇数",
     re.compile(r"^\s*├── docs/\s+(\d+) 篇文档"),
     "docs"),
)


# 计数时必须排除的噪声条目：它们只存在于本地工作区，不会出现在 CI 的 checkout 里。
# 2026-10-24 踩过：本地 scripts/ 有未跟踪的 __pycache__ ⇒ 本地数出 90、CI 数出 89，
# 门禁在 CI 上红，而本地怎么跑都绿（典型的"本地对、CI 错"环境差异）。
IGNORED = {"__pycache__", ".pytest_cache", ".mypy_cache", ".DS_Store", "node_modules"}


def measure(root):
    """返回 (scripts 条目数, e2e-* 条目数, docs/*.md 篇数)。已排除 IGNORED 噪声。"""
    sd = os.path.join(root, "scripts")
    dd = os.path.join(root, "docs")
    if not os.path.isdir(sd) or not os.path.isdir(dd):
        return None
    scripts = [e for e in os.listdir(sd) if e not in (".", "..") and e not in IGNORED]
    e2e = [e for e in scripts if e.startswith("e2e-")]
    docs = [e for e in os.listdir(dd)
            if e.endswith(".md") and e not in IGNORED
            and os.path.isfile(os.path.join(dd, e))]
    return len(scripts), len(e2e), len(docs)


def check(readme_path, root):
    """返回不一致列表 [(行号, 说明, 声称, 实测)]。"""
    if not os.path.isfile(readme_path):
        return [(0, "找不到 README.md", "-", "-")]
    actual = measure(root)
    if actual is None:
        return [(0, "scripts/ 或 docs/ 目录不存在", "-", "-")]
    n_scripts, n_e2e, n_docs = actual

    with io.open(readme_path, encoding="utf-8", newline="") as f:
        lines = f.read().replace("\r\n", "\n").split("\n")

    problems = []
    seen = {"scripts": 0, "docs": 0}
    for i, line in enumerate(lines, 1):
        for desc, pat, kind in ANCHORS:
            m = pat.search(line)
            if not m:
                continue
            seen[kind] += 1
            if kind == "scripts":
                cs, ce = int(m.group(1)), int(m.group(2))
                if cs != n_scripts:
                    problems.append((i, desc + "（脚本总数）", cs, n_scripts))
                if ce != n_e2e:
                    problems.append((i, desc + "（e2e-* 数）", ce, n_e2e))
            else:
                cd = int(m.group(1))
                if cd != n_docs:
                    problems.append((i, desc, cd, n_docs))

    # 锚点缺失 = 目录树被重构了，必须显式失败，不能假装"没问题"
    for kind, desc in (("scripts", "scripts/ 目录树行"), ("docs", "docs/ 目录树行")):
        if seen[kind] == 0:
            problems.append((0, desc + "未找到（行形式可能已变，请更新本门禁的锚点）", "-", "-"))
        elif seen[kind] > 1:
            problems.append((0, desc + "命中 %d 次（锚点不唯一）" % seen[kind], "-", "-"))
    return problems


def self_test():
    """合成夹具自证：一致必须过、不一致必须报、锚点缺失必须报。"""
    tmp = tempfile.mkdtemp(prefix="doc-counts-")
    fails = []
    try:
        os.makedirs(os.path.join(tmp, "scripts"))
        os.makedirs(os.path.join(tmp, "docs"))
        for n in ["a.sh", "b.sh", "e2e-x.sh", "e2e-y.sh", "c.py"]:
            io.open(os.path.join(tmp, "scripts", n), "w").write("")
        for i in range(3):
            io.open(os.path.join(tmp, "docs", "d%d.md" % i), "w").write("")

        good = ("├── scripts/             x / 5 个脚本（其中 2 个 e2e-*）\n"
                "├── docs/                3 篇文档\n")
        p = os.path.join(tmp, "README.md")

        io.open(p, "w", encoding="utf-8").write(good)
        if check(p, tmp):
            fails.append("一致时却报错")

        io.open(p, "w", encoding="utf-8").write(good.replace("5 个脚本", "9 个脚本"))
        if not any("脚本总数" in x[1] for x in check(p, tmp)):
            fails.append("脚本总数写错没报出来")

        io.open(p, "w", encoding="utf-8").write(good.replace("2 个 e2e-", "7 个 e2e-"))
        if not any("e2e-*" in x[1] for x in check(p, tmp)):
            fails.append("e2e-* 数写错没报出来")

        io.open(p, "w", encoding="utf-8").write(good.replace("3 篇文档", "8 篇文档"))
        if not any("文档篇数" in x[1] for x in check(p, tmp)):
            fails.append("文档篇数写错没报出来")

        io.open(p, "w", encoding="utf-8").write("没有目录树\n")
        if len(check(p, tmp)) < 2:
            fails.append("锚点缺失时至少两条都该报（不能伪装通过）")

        if fails:
            for f in fails:
                print("  [FAIL] " + f)
            return 1
        print("  [PASS] 一致时不报错")
        print("  [PASS] 脚本总数不一致被报出")
        print("  [PASS] e2e-* 数不一致被报出")
        print("  [PASS] 文档篇数不一致被报出")
        print("  [PASS] 锚点缺失时显式失败（不伪装通过）")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    problems = check(README, ROOT)
    if problems:
        print("目录计数门禁：%d 处与实测不一致" % len(problems))
        for line, desc, claimed, actual in problems:
            loc = "README.md:%d" % line if line else "README.md"
            print("  - %s  %s  声称 %s / 实测 %s" % (loc, desc, claimed, actual))
        print("改方法：把 README 目录树里的数字改成实测值（或自己数：`ls scripts/ | wc -l` 等）。")
        sys.exit(1)
    print("目录计数门禁：通过（README 声称与实测一致）")
    sys.exit(0)


if __name__ == "__main__":
    main()
