#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：文档里声称的 REST 端点数必须等于实测值。

为什么需要
----------
2026-10-20 实测发现：同一件事在文档里有**三个不同的数**——

    README.md            REST API（358 端点）
    sales-pitch-deck.md  REST API 358 端点
    contributing-guide.md / customer-onboarding-guide.md / pricing-strategy.md   345 端点

而**实测是 406**（78 个 @RestController 类里的方法级映射注解，排除 actuator）。
三个声称值没有一个对得上，而且彼此还不一样 —— 这正是本仓库主线一直在清的
「声明≠行为」，只是这次声明散在对外文档里，没被任何门禁覆盖。

口径（刻意写死，避免"换个算法数出来不一样"）：
  1. 只数 `@RestController` 标注的类（`@Controller` 视图层不算 API）。
  2. 只数类里的**方法级** `@Get/Post/Put/Delete/PatchMapping`。
  3. 排除 actuator 的类（`/actuator/**` 不是业务 API）。

改端点数时，文档里的声称值要跟着改（这个门禁会拦住你忘改）。

用法：
    python scripts/check-claimed-endpoint-count.py
    python scripts/check-claimed-endpoint-count.py --self-test
退出码：0 一致；1 有不一致。
"""
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "cloud-backend", "src", "main", "java")

CLASS_ANNO = re.compile(r"@RestController\b")
MAPPING = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b")
# 「N 端点」这种窄口径 —— 误报比漏检贵（门禁一旦有噪声就会被整体忽略）
CLAIM = re.compile(r"(\d+)\s*端点")
# 会被核对端点数的文档
DOCS = ("README.md",
        "docs/contributing-guide.md",
        "docs/customer-onboarding-guide.md",
        "docs/pricing-strategy.md",
        "docs/sales-pitch-deck.md")


def measure(src=SRC):
    """返回 (端点数, 类数)。口径见模块 docstring。"""
    endpoints = 0
    classes = 0
    for dp, dn, fn in os.walk(src):
        for f in fn:
            if not f.endswith(".java"):
                continue
            t = io.open(os.path.join(dp, f), encoding="utf-8", errors="replace").read()
            if not CLASS_ANNO.search(t) or "actuator" in f.lower():
                continue
            classes += 1
            endpoints += len(MAPPING.findall(t))
    return endpoints, classes


def check_claims(docs, actual, root=None):
    """返回不一致列表 [(文件:行号, 声称, 实测)]。"""
    problems = []
    for rel in docs:
        p = os.path.join(root or ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(p):
            continue
        with open(p, encoding="utf-8", newline="") as f:
            lines = f.read().replace("\r\n", "\n").split("\n")
        for i, line in enumerate(lines, 1):
            for m in CLAIM.finditer(line):
                claimed = int(m.group(1))
                if claimed != actual:
                    problems.append((rel, i, claimed, actual))
    return problems


def self_test():
    """合成夹具自证：声称与实测不一致要能报出来，一致时不能误报。"""
    tmp = tempfile.mkdtemp(prefix="endpoint-claim-")
    try:
        src = os.path.join(tmp, "src")
        os.makedirs(src)
        with open(os.path.join(src, "DemoController.java"), "w", encoding="utf-8") as f:
            f.write("@RestController\nclass DemoController {\n"
                    "  @GetMapping(\"/a\") void a() {}\n"
                    "  @PostMapping(\"/b\") void b() {}\n"
                    "}\n")
        actual, classes = measure(src)
        doc = os.path.join(tmp, "README.md")
        with open(doc, "w", encoding="utf-8") as f:
            f.write("REST API（%d 端点）\n" % actual)
        ok = (actual == 2 and classes == 1)
        print("  [%s] 端点实测口径正确（2 个端点 / 1 个类）" % ("PASS" if ok else "FAIL"))

        with open(doc, "w", encoding="utf-8") as f:
            f.write("REST API（358 端点）\n")
        pr = check_claims(("README.md",), actual, root=tmp)
        caught = (len(pr) == 1 and pr[0][2] == 358)
        print("  [%s] 声称与实测不一致时被报出" % ("PASS" if caught else "FAIL"))

        with open(doc, "w", encoding="utf-8") as f:
            f.write("REST API（%d 端点）\n" % actual)
        pr = check_claims(("README.md",), actual, root=tmp)
        not_caught = (len(pr) == 0)
        print("  [%s] 一致时不误报" % ("PASS" if not_caught else "FAIL"))
        return 0 if (ok and caught and not_caught) else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--self-test" in sys.argv:
        return self_test()
    actual, classes = measure()
    problems = check_claims(DOCS, actual)
    print("实测端点数 = %d（%d 个 @RestController 类）" % (actual, classes))
    if problems:
        print("端点数声称门禁：%d 处与实测不一致" % len(problems))
        for rel, line, claimed, act in problems:
            print("  - %s:%d  声称 %d，实测 %d" % (rel, line, claimed, act))
        print("")
        print("  这些是文档里对外声称的数，与代码里的实际端点数对不上。")
        print("  改端点时文档要跟着改；本门禁用 scripts/count-rest-endpoints.py 的口径。")
        return 1
    print("端点数声称门禁：通过（文档声称 %d = 实测 %d）" % (actual, actual))
    return 0


if __name__ == "__main__":
    sys.exit(main())
