#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""仓库卫生门禁：把"别再犯"的规矩变成能自动拦的门禁。

为什么需要这个
--------------
这个仓库是多 agent 并行改的，同一份文件被不同会话各改一次再合并，很容易留下几类
**静默损坏**——它们不会让 CI 变红，但会把别的门禁变成假绿：

1. **merge 冲突标记进了仓库**：CHANGELOG / README 里若带着 `<<<<<<< HEAD`，
   `check-test-count-docs.py` 仍可能"核对了"（它按行号改数字，标记行不影响它读数），
   于是计数门禁看起来在工作，实际可能核的是错的行。
2. **CHANGELOG 自相矛盾**：正文小节数与头部说明、版本索引表声称的数字不一致。
   而且"写法"本身也要对 —— 曾经出现过「共有 159 158 个」这种（字符串手术插坏的），
   老的宽松正则会**整个跳过检查**，反而放行。
3. **疑似回退式改动**：把一大块已经修好的内容静撤回去（见
   `check-revert-looking-diff.py`）。这类改动不违反任何断言，门禁看不出来。

实测过的真实事故
----------------
* 2026-10-20：一次 stash pop 冲突后直接提交，CHANGELOG 里带了 3 处冲突标记，
  且正文 156 节 / 头部声称 154 —— 本门禁就是照着这两条写的。
* 2026-10-12：一份被 merge 冲突污染的 CHANGELOG 带着 42 处标记进了 master，
  当时只是靠人肉记得去 `git grep`。
* 2026-10-20：某个工作树把 CHANGELOG 撤掉 559 行、并把已修好的脚本退回成有 bug 的版本。
* 2026-10-20：同一天我自己的字符串手术把头部写成「共有 159 158 个」，
  宽松的门禁**没抓住** —— 于是把解析口径改成"整段必须是单个整数"。

检查项
------
1. 被跟踪的文件里不得有 merge 冲突标记行（`<<<<<<< ` / `>>>>>>> ` / `=======`）。
2. CHANGELOG 的 `[Unreleased]` 小节数必须与头部说明、版本索引表两处声称的一致，
   且两处的声称必须是**单个整数**（写法坏了也要报）。
3. 不得提交 `*.orig` / `*.rej` / `*.bak` 这类合并残留。
4. 不得提交构建产物 / 依赖目录 / 本地报告（`target/`、`node_modules/`、`dist/`、
   `vitest-report.json`、`data/`、`.env`）。
5. 不得有"疑似回退式改动"（删除远多于新增），除非确认过。

用法：
    python scripts/check-repo-hygiene.py              # 查本仓库
    python scripts/check-repo-hygiene.py --self-test  # 用合成夹具自证
退出码：0 干净；1 有问题（逐条打印）。
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# merge 冲突标记：行首的 <<<<<<< / >>>>>>> / =======
MARKER_RE = re.compile(r"^\s*(<<<<<<< |>>>>>>> |=======\s*$)")
# 合并残留文件
RESIDUE_EXT = (".orig", ".rej", ".bak")
# 不得入库的路径前缀 / 名字
FORBIDDEN_DIRS = ("target/", "node_modules/", "dist/", "build/", "data/", ".idea/", "__pycache__/")
FORBIDDEN_NAMES = ("vitest-report.json", ".env", "local.properties")


def git(*args, root=None):
    return subprocess.run(["git", "-C", root or ROOT] + list(args),
                          stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL).stdout.decode("utf-8", "replace")


def _single_int(s):
    """从一段文字里取"唯一的整数"；取不出或取出多个（如 '159 158'）就返回 None。"""
    s = s.strip()
    return int(s) if re.fullmatch(r"\d+", s) else None


def check_changelog_claims(text, sections):
    """核对 CHANGELOG 里两处小节数声称。返回问题列表。"""
    problems = []
    for line in text.replace("\r\n", "\n").split("\n"):
        # 头部那一行：行首是 ">"、含"共有"、含"个"、含 "[Unreleased]" 段。
        # 刻意**不**按"最新在最上方"这个精确前缀找 —— 换个措辞就整段跳过检查，
        # 等于把门禁自己关掉（2026-10-20 踩过）。
        if line.startswith(">") and "共有" in line and "个" in line and "[Unreleased]" in line:
            seg = line.split("共有", 1)[1]
            seg = seg.split("个", 1)[0]
            claimed = _single_int(seg)
            if claimed is None:
                problems.append("CHANGELOG 头部的小节数写法不合法（应为单个整数）：共有 %s 个"
                                % seg.strip()[:60])
            elif claimed != sections:
                problems.append("CHANGELOG 头部声称 %d 个小节，实际 %d 个" % (claimed, sections))
        if line.startswith("| **[Unreleased]**"):
            cells = line.split("|")
            # 形如 | **[Unreleased]** | 累积中 | — | N 个小节持续累积（…） | — |
            seg = cells[4] if len(cells) > 4 else ""
            claimed = _single_int(seg.split("个", 1)[0])
            if claimed is None:
                problems.append("CHANGELOG 索引表的小节数写法不合法（应为单个整数）：%s" % seg.strip()[:60])
            elif claimed != sections:
                problems.append("CHANGELOG 索引表声称 %d 个小节，实际 %d 个" % (claimed, sections))
    return problems


def check_repo(root=None):
    """对指定目录做检查，返回问题列表。"""
    problems = []
    base = root or ROOT

    # --- 1. 冲突标记 ---
    for rel in [f for f in git("ls-files", root=base).split("\n") if f.strip()]:
        p = os.path.join(base, rel.replace("/", os.sep))
        if not os.path.isfile(p):
            continue
        try:
            with open(p, "rb") as f:
                if b"\x00" in f.read(4096):
                    continue  # 二进制跳过
            with open(p, "rb") as f:
                data = f.read().decode("utf-8", "replace")
        except OSError:
            continue
        for i, line in enumerate(data.replace("\r\n", "\n").split("\n"), 1):
            if MARKER_RE.match(line):
                problems.append("冲突标记 %s:%d  %s" % (rel, i, line.strip()[:60]))

    # --- 2. CHANGELOG 自洽 ---
    cl = os.path.join(base, "CHANGELOG.md")
    if os.path.isfile(cl):
        with open(cl, encoding="utf-8", newline="") as f:
            text = f.read()
        sections = len(re.findall(r"(?m)^## \[Unreleased\]", text))
        problems += check_changelog_claims(text, sections)

    # --- 2.5 pom 可解析 ---
    # 一个 pom 写坏（比如 XML 注释里出现 "--"，XML 语法不允许），Maven 在 CI 里
    # 全线 "Non-parseable POM"：缓存预热、Java 各版本编译、E2E、compose 一起红，
    # 而且要到 2.5 小时的队列里才暴露（2026-10-23 实测踩到）。这里秒级拦住。
    for rel in [f for f in git("ls-files", root=base).split("\n") if f.strip()]:
        norm = rel.replace("\\", "/")
        if norm != "pom.xml" and not norm.endswith("/pom.xml"):
            continue
        p = os.path.join(base, rel.replace("/", os.sep))
        if not os.path.isfile(p):
            continue
        with open(p, encoding="utf-8") as f:
            ptext = f.read()
        for m in re.finditer(r"<!--(.*?)-->", ptext, re.S):
            if "--" in m.group(1):
                problems.append("%s: XML 注释里含连续连字符（XML 不允许，整个 POM 会解析失败）" % norm)
                break
        try:
            ET.parse(p)
        except ET.ParseError as e:
            problems.append("%s 不是合法 XML：%s" % (norm, str(e)[:90]))

    # --- 3/4. 合并残留 / 构建产物 ---
    for rel in [f for f in git("ls-files", root=base).split("\n") if f.strip()]:
        if rel.lower().endswith(RESIDUE_EXT):
            problems.append("合并残留文件入库: %s" % rel)
        norm = rel.replace("\\", "/")
        if any(norm.startswith(d) or ("/" + d) in ("/" + norm) for d in FORBIDDEN_DIRS):
            problems.append("构建产物/依赖目录入库: %s" % rel)
        if os.path.basename(norm) in FORBIDDEN_NAMES:
            problems.append("本地报告/配置入库: %s" % rel)
    return problems


def check_revert(base):
    """疑似回退式改动（内层脚本成功时静默，按退出码判定）。"""
    try:
        rr = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "check-revert-looking-diff.py"), base],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if rr.returncode != 0:
            return ("疑似回退式改动："
                    + rr.stdout.decode("utf-8", "replace").strip().replace("\n", " | ")[:240])
    except Exception:
        pass
    return None


def self_test():
    """合成夹具自证：真事故里的坏状态要能拦，干净状态不能误拦。"""
    tmp = tempfile.mkdtemp(prefix="hygiene-selftest-")
    try:
        # 夹具必须是 git 仓库：检查项 1/3/4 是按 `git ls-files` 取被跟踪文件的
        def run(*a):
            return subprocess.run(["git", "-C", tmp] + list(a), stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL).stdout.decode("utf-8", "replace")
        run("init", "-q")
        run("config", "user.email", "t@t")
        run("config", "user.name", "t")

        clean = ("## [Unreleased] — A\n\nbody\n\n"
                 "> 截至 2026-10-20 共有 1 个 `[Unreleased]` 小节\n\n"
                 "| **[Unreleased]** | 累积中 | — | 1 个小节持续累积 |\n")
        cl = os.path.join(tmp, "CHANGELOG.md")
        ok = {}

        with open(cl, "w", encoding="utf-8", newline="") as f:
            f.write(clean.replace("## [Unreleased] — A",
                                  "<<<<<<< HEAD\n## [Unreleased] — A\n=======\n>>>>>>> Stashed changes", 1))
        run("add", "-A")
        pr = check_repo(tmp)
        ok["冲突标记被拦"] = any("冲突标记" in x for x in pr)

        with open(cl, "w", encoding="utf-8", newline="") as f:
            f.write(clean.replace("共有 1 个", "共有 9 个", 1))
        run("add", "-A")
        pr = check_repo(tmp)
        ok["CHANGELOG 自相矛盾被拦"] = any("头部声称" in x for x in pr)

        # 写法坏了（数字被插成两个）—— 老的宽松正则会整个跳过检查，反而放行
        with open(cl, "w", encoding="utf-8", newline="") as f:
            f.write(clean.replace("共有 1 个", "共有 1 5 个", 1))
        run("add", "-A")
        pr = check_repo(tmp)
        ok["小节数写法不合法被拦"] = any("写法不合法" in x for x in pr)

        with open(cl, "w", encoding="utf-8", newline="") as f:
            f.write(clean)
        run("add", "-A")
        pr = check_repo(tmp)
        ok["干净不误拦"] = (pr == [])

        # pom 的 XML 注释里出现 "--"：2026-10-23 实测把 7 个 CI job 一起带红
        mod = os.path.join(tmp, "mod")
        os.makedirs(mod, exist_ok=True)
        pom = os.path.join(mod, "pom.xml")
        with open(pom, "w", encoding="utf-8", newline="") as f:
            f.write("<project><!-- 传 --strict 参数 --><x/></project>")
        run("add", "-A")
        pr = check_repo(tmp)
        ok["pom 注释里的连续连字符被拦"] = any("连续连字符" in x for x in pr)

        with open(pom, "w", encoding="utf-8", newline="") as f:
            f.write("<project><x>")
        run("add", "-A")
        pr = check_repo(tmp)
        ok["非法 XML 的 pom 被拦"] = any("不是合法 XML" in x for x in pr)

        os.remove(pom)
        run("add", "-A")
        pr = check_repo(tmp)
        ok["pom 修好后不误拦"] = (pr == [])

        for k, v in ok.items():
            print("  [%s] %s" % ("PASS" if v else "FAIL", k))
        return 0 if all(ok.values()) else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--self-test" in sys.argv:
        return self_test()
    problems = check_repo()
    rev = check_revert(os.environ.get("HYGIENE_BASE", "origin/master"))
    if rev:
        problems.append(rev)
    if problems:
        print("仓库卫生门禁：%d 项不合规" % len(problems))
        for p in problems[:40]:
            print("  - " + p)
        if len(problems) > 40:
            print("  ... 其余 %d 项省略" % (len(problems) - 40))
        return 1
    print("仓库卫生门禁：通过（无冲突标记、CHANGELOG 自洽、pom 可解析、无合并残留、无构建产物入库、无疑似回退式改动）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
