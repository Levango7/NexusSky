#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查"疑似回退式改动"：删除行数远多于新增的文件。

为什么需要
----------
2026-10-20 观察到一次真实案例：某个工作树把 CHANGELOG 一次性撤掉 559 行，
并把一个**已经修好**的脚本（`e2e-rtk.ps1` 的 key 归一化 + 诊断输出）退回成有 bug 的版本。

这类改动**不会让 CI 变红** —— 撤掉的内容往往"本来就该撤"，门禁看不出问题；
但它逐行看 diff 很费眼，而在统计上非常明显：**删除远多于新增**。

所以按"numstat 的增删比"抓它。阈值取得很宽松（删 ≥ 200 行且新增 ≤ 删除的 15%），
只抓明显的回退，不误伤正常重构。

**2026-10-30 补一条排除**：**整文件删除**不算"疑似回退"（新增 0 行且 HEAD 里已无该路径）。
理由：本门禁抓的是文件**内部**被静悄悄撤掉大半的形状；整文件删除在 diff 里是显眼的 `D`，
删完还会在编译/引用面上裸奔，不需要靠增删比发现。不加这条排除，"按裁决删掉一个空壳"
（如 cloud-backend 的 `spi/` 包）会被判成回退，门禁就开始惩罚正确行为。
自测里第三种情形专门钉这条：整文件删除不误拦。

用法：
    python scripts/check-revert-looking-diff.py [base]     # base 默认 origin/master
    python scripts/check-revert-looking-diff.py --self-test
退出码：0 无疑似回退；1 有（打印明细）。
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 删除行数下限；新增 / 删除 的比例上限
MIN_DELETED = 200
MAX_ADD_RATIO = 0.15


def git(*args, cwd=None):
    return subprocess.run(["git", "-C", cwd or ROOT] + list(args),
                          stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL).stdout.decode("utf-8", "replace")


def _path_in_head(path, cwd=None):
    """该路径在 HEAD 里还在不在。整文件删除与"文件内部被撤"是两种形状，见 check()。"""
    r = subprocess.run(["git", "-C", cwd or ROOT, "cat-file", "-e", "HEAD:%s" % path],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return r.returncode == 0


def check(base, cwd=None):
    """返回命中的 (path, add, del) 列表。"""
    if not git("rev-parse", "--verify", base, cwd=cwd).strip():
        return []
    out = git("diff", "--numstat", "%s...HEAD" % base, cwd=cwd)
    hits = []
    for line in out.strip().split("\n"):
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        add, dele, path = parts
        if not add.isdigit() or not dele.isdigit():
            continue
        add, dele = int(add), int(dele)
        # 整文件删除（新增 0 行且 HEAD 里已没有该路径）不算"疑似回退"：
        # 本门禁抓的是**文件内部**被静悄悄撤掉大半的改动（2026-10-20 那两例都是文件还在、
        # 内容被换回旧版）。整文件删除在 diff 里是一个显眼的 `D`，且删完就编译/引用面裸奔，
        # 不需要靠增删比来发现；用同一条阈值去拦它只会把"按裁决删掉一个空壳"判成回退。
        if add == 0 and not _path_in_head(path, cwd):
            continue
        if dele >= MIN_DELETED and add <= dele * MAX_ADD_RATIO:
            hits.append((path, add, dele))
    return hits


def self_test():
    """合成夹具自证：大量删除要能拦，正常改动不能误拦。"""
    tmp = tempfile.mkdtemp(prefix="revert-check-")
    try:
        def run(*a):
            return subprocess.run(["git", "-C", tmp] + list(a), stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL).stdout.decode("utf-8", "replace")
        run("init", "-q")
        run("config", "user.email", "t@t")
        run("config", "user.name", "t")
        big = os.path.join(tmp, "big.txt")
        with open(big, "w", encoding="utf-8") as f:
            f.write("\n".join("line %d" % i for i in range(400)))
        run("add", "-A")
        run("commit", "-qm", "base")
        base_sha = run("rev-parse", "HEAD").strip()

        # 情形 1：大量删除、几乎不新增 -> 应被判为疑似回退
        with open(big, "w", encoding="utf-8") as f:
            f.write("\n".join("keep %d" % i for i in range(5)))
        run("add", "-A")
        run("commit", "-qm", "revert")
        caught = len(check(base_sha, cwd=tmp)) > 0
        print("  [PASS] 大量删除被拦下" if caught else "  [FAIL] 大量删除漏过了")

        # 情形 2：正常追加 -> 不应被判为回退
        run("checkout", "-q", base_sha, "--", "big.txt")
        with open(big, "a", encoding="utf-8") as f:
            f.write("appended line\n")
        run("add", "-A")
        run("commit", "-qm", "normal")
        not_caught = len(check(base_sha, cwd=tmp)) == 0
        print("  [PASS] 正常追加不误拦" if not_caught else "  [FAIL] 正常追加被误拦")

        # 情形 3：整文件删除（≠ 文件内部被撤）-> 不应判回退
        run("checkout", "-q", base_sha, "--", "big.txt")
        os.remove(os.path.join(tmp, "big.txt"))
        run("add", "-A")
        run("commit", "-qm", "delete whole file")
        del_ok = len(check(base_sha, cwd=tmp)) == 0
        print("  [PASS] 整文件删除不误拦" if del_ok else "  [FAIL] 整文件删除被误拦")
        return 0 if (caught and not_caught and del_ok) else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--self-test" in sys.argv:
        return self_test()
    base = sys.argv[1] if len(sys.argv) > 1 else "origin/master"
    hits = check(base)
    if not hits:
        return 0
    print("下列文件的删除远多于新增，疑似回退式改动（删 ≥ %d 行且新增 ≤ 删除的 %d%%）："
          % (MIN_DELETED, int(MAX_ADD_RATIO * 100)))
    for path, add, dele in hits:
        print("  - %s  +%d/-%d" % (path, add, dele))
    print("请当面确认：是把已修好的内容静撤回去了，还是确实该删这么多。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
