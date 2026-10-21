#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Python SDK 测试数门禁：文档里声称的用例数必须等于实际收集到的用例数。

为什么需要它
------------
2026-10-21 实测发现：`README.md` 的模块表里写着
``sdk-python` | Python 3 | Python 客户端 SDK（…），7 文件、**0 单测**``，
而**实际上 `sdk-python/tests/test_client.py` 有 27 个用例，CI 的 `sdk-python` job 一直在跑**。

这是一条典型的「声明≠行为」：
- 那份测试是 2026-10-13 补上的（测试文件的 docstring 里写着"此前这个 SDK …零测试"），
  README 的那一行**没跟着改**；
- Java 侧和前端侧都有「文档声称数 == 实测数」的门禁（`check-test-count-docs.py`），
  **Python 侧没有** ⇒ 这类腐烂没人拦。

本门禁把 Python 侧也纳入机械检查：
1. 数出实际收集到的用例数（`pytest --collect-only -q`）；
2. 扫文档里提到 `sdk-python` / Python SDK 的行，找出其中的测试数声称；
3. 不一致 ⇒ 判红。

**用例数口径**：用 pytest 收集到的**用例**数（不是 `def test_` 函数数 ——
有 `parametrize` 时两者不等，27 ≠ 22）。所以本门禁**必须在装了 pytest 的环境里跑**
（CI 的 `sdk-python` job 满足；本机没装 pytest 时退出码 2 = 前置不满足，不是"通过"）。

用法：
    python scripts/check-python-test-count.py
    python scripts/check-python-test-count.py --self-test

退出码：0 一致；1 声称与实测不符；2 前置不满足（没 pytest / 目录不存在）。
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 文档里可能声称 Python SDK 测试数的地方
DOC_FILES = ["README.md", "ROADMAP.md"]
DOC_DIR = "docs"

# 提到 Python SDK 的行才算数（限定词，避免把别处的数字抓过来比）
SDK_MENTIONS = re.compile(r"sdk-python|Python\s*SDK|python\s*SDK", re.I)
# 测试数声称：数字紧贴 单测 / 个用例 / 例
CLAIM = re.compile(r"(\d+)\s*(?:个)?\s*(?:单测|用例|例)")
# 追述旧值的行豁免（历史叙述，不是当前声称）
EXEMPT = re.compile(r"追述旧值|历史上|此前|曾经|当时")

PY_TEST_PATTERNS = ("pytest", "unittest")


def collect_cases(sdk_dir):
    """用 pytest 收集用例数。返回 (数量, 说明)。"""
    if not os.path.isdir(sdk_dir):
        return None, "sdk 目录不存在：%s" % sdk_dir
    try:
        import pytest  # noqa: F401
    except Exception:
        return None, "本机没装 pytest —— 收集不到用例数（请在装了 pytest 的环境里跑）"
    r = subprocess.run([sys.executable, "-m", "pytest", "tests/", "--collect-only", "-q"],
                       cwd=sdk_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = r.stdout.decode("utf-8", "replace")
    m = re.search(r"(\d+)\s+tests?\s+collected", out)
    if m:
        return int(m.group(1)), "pytest --collect-only"
    m2 = re.search(r"collected\s+(\d+)\s+items?", out)
    if m2:
        return int(m2.group(1)), "pytest --collect-only"
    return None, "pytest 输出里没找到用例数：%s" % out.strip()[-160:]


def scan_claims(paths):
    """扫文档，返回 [(文件, 行号, 声称数, 行文本)]。"""
    hits = []
    for p in paths:
        if not os.path.exists(p):
            continue
        for i, line in enumerate(io.open(p, encoding="utf-8", errors="replace").read().split("\n"), 1):
            if not SDK_MENTIONS.search(line):
                continue
            if EXEMPT.search(line):
                continue
            m = CLAIM.search(line)
            if m:
                hits.append((p, i, int(m.group(1)), line.strip()))
    return hits


def verify(sdk_dir, paths, collected=None):
    """返回 (是否通过, 问题列表, 实际数)。"""
    problems = []
    if collected is None:
        collected, why = collect_cases(sdk_dir)
        if collected is None:
            return None, [why], None
    claims = scan_claims(paths)
    if not claims:
        return True, ["文档里没有 Python SDK 的测试数声称（无需核对）"], collected
    for p, i, claimed, text in claims:
        if claimed != collected:
            problems.append("%s:%d 声称 %d 个用例，实测 %d" % (p, i, claimed, collected))
    return (not problems), problems, collected


def self_test():
    """用合成夹具自证：声称不符要能报出、相符不误报、没声称不误报。"""
    ok = True
    tmp = None
    try:
        import tempfile
        tmp = tempfile.mkdtemp(prefix="py-count-")
        doc = os.path.join(tmp, "README.md")
        io.open(doc, "w", encoding="utf-8").write(
            "| `sdk-python` | Python 3 | Python 客户端 SDK，7 文件、**27 个用例** |\n")
        bad = os.path.join(tmp, "BAD.md")
        io.open(bad, "w", encoding="utf-8").write(
            "| `sdk-python` | Python 3 | Python 客户端 SDK，7 文件、**0 单测** |\n")
        hist = os.path.join(tmp, "HIST.md")
        io.open(hist, "w", encoding="utf-8").write(
            "sdk-python 此前是 0 单测（追述旧值），现在 27 个用例\n")

        passed, problems, _ = verify("no-such-dir", [doc], collected=27)
        if passed:
            print("  [PASS] 声称相符不误报")
        else:
            print("  [FAIL] 声称相符被误报：%s" % problems)
            ok = False

        passed, problems, _ = verify("no-such-dir", [bad], collected=27)
        if not passed and any("0 个用例" in p or "0 单测" in p for p in problems):
            print("  [PASS] 声称 0 而实测 27 能报出")
        else:
            print("  [FAIL] 该报的没报：%s" % problems)
            ok = False

        passed, problems, _ = verify("no-such-dir", [hist], collected=27)
        if passed:
            print("  [PASS] 追述旧值的行被豁免")
        else:
            print("  [FAIL] 追述旧值被误判：%s" % problems)
            ok = False

        passed, problems, _ = verify("no-such-dir", [os.path.join(tmp, "NONE.md")], collected=27)
        if passed:
            print("  [PASS] 文档里没有声称时不误报")
        else:
            print("  [FAIL] 没有声称被误判：%s" % problems)
            ok = False

        # 收集不到用例数时必须"前置不满足"而不是"通过"
        passed, problems, _ = verify("no-such-dir", [doc], collected=None)
        if passed is None:
            print("  [PASS] 收集不到用例数时判前置不满足（不是静默通过）")
        else:
            print("  [FAIL] 收集不到用例数时给出了通过/失败结论")
            ok = False
    finally:
        if tmp:
            import shutil
            shutil.rmtree(tmp, ignore_errors=True)
    return 0 if ok else 1


def main():
    if "--self-test" in sys.argv:
        print("== Python SDK 测试数门禁自测 ==")
        return self_test()

    sdk_dir = os.path.join(ROOT, "sdk-python")
    paths = [os.path.join(ROOT, f) for f in DOC_FILES]
    docs_dir = os.path.join(ROOT, DOC_DIR)
    if os.path.isdir(docs_dir):
        paths += [os.path.join(docs_dir, f) for f in sorted(os.listdir(docs_dir))
                  if f.endswith(".md")]

    passed, problems, collected = verify(sdk_dir, paths)
    if passed is None:
        print("")
        print("  [FAIL] 前置不满足：%s" % problems[0])
        print("")
        print("  本门禁必须**在装了 pytest 的环境**里跑（CI 的 sdk-python job 满足）。")
        print("  它不接受'数不到就当通过'。")
        return 2
    if not passed:
        print("")
        print("  [FAIL] Python SDK 的测试数声称与实测不符：")
        for p in problems:
            print("    %s" % p)
        print("")
        print("  实测（pytest 收集到的用例数）= %d" % collected)
        print("  改文档，还是改测试 —— 但**两边必须对上**。")
        return 1
    print("  Python SDK 测试数门禁：通过（文档声称 = pytest 收集到的 %d 个用例）" % collected)
    return 0


if __name__ == "__main__":
    sys.exit(main())
