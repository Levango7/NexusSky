#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：文档里写出的 `/api/v1/...` 路径，必须在 Controller 里真实存在。

为什么需要它
-----------
2026-10-29 排查发现**文档公开了根本不存在的端点**，而且不止一处：

    docs/sdk-reference.md、docs/integration-guide.md   /api/v1/sat/providers*（从未实现，调用 404）
    README.md、docs/PRODUCT-POSITIONING.md              /api/v1/satlink/*（真实是 sat-link）
    docs/demo-scenarios.md、security-design.md          /api/v1/formation/create、/{id}/takeoff（真实是 POST /formation、/{id}/command）
    docs/sitl-integration.md                            /api/v1/missions（真实是 /drones/{sysid}/mission）
    README.md                                            /api/v1/env/alerts（真实是 env-alerts）

本仓已有「端点数声称」门禁（check-claimed-endpoint-count.py）与「前端调用契约」门禁
（check-frontend-endpoints.py），但**没有任何门禁看文档自己写出来的路径**——
于是文档可以长期承诺一个客户调不通的接口，且错误码还是 404 而不是文档说的 501。
本脚本补上这道信号。

口径（刻意写死，避免"换个算法数出来不一样"）
-------------------------------------------
1. 真实端点 = `@RestController` 类的方法级映射；类级 base 取**类声明前最后一个**
   `@RequestMapping`；方法级既吃 `@GetMapping("x")` / `value="/x"`，
   也吃**裸注解** `@GetMapping`（路径全在类级，如 EnvAlertController）。
2. 只扫「当前口径」文档（见 SCAN_DOCS）。**规划/历史文档不扫**（见 SKIP_DOCS）：
   它们本来就允许写"将来要有"的接口，扫了只会逼人写假注释。
3. 跳过**否定/规划语境**行：同一行含「未实现 / 不存在 / 没有 / 从未 / 待实现 / 规划 /
   建议新增 / 计划」时不算声称（否则解释"这个端点不存在"的句子反而会被判违规）。
4. 路径归一：`{var}`、纯数字、UUID、`XXX-123` 型实参统一成 `*`；
   文档只写到父级（`/a/b` 而代码是 `/a/b/{id}`）或省略写法（`/a/...`）都算存在。

用法：
    python scripts/check-doc-endpoints.py
    python scripts/check-doc-endpoints.py --self-test
退出码：0 全部存在；1 有文档路径在代码里找不到。
"""
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

REST = re.compile(r"@RestController\b")
CLASS_DECL = re.compile(r"public\s+(?:final\s+)?class\s+\w+")
BASE = re.compile(r"@RequestMapping\b\s*(?:\(([^)]*)\))?")
MAP = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b\s*(?:\(([^)]*)\))?")
PATH_IN_DOC = re.compile(r"(/api/v1/[A-Za-z0-9_\-{}/.]*)")

# 只扫「当前口径」文档：这些文档里的路径是**给客户照着调的**，写错=说谎。
SCAN_DOCS = (
    "README.md",
    "docs/api-reference.md",
    "docs/api-quick-reference.md",
    "docs/sdk-reference.md",
    "docs/sdk-api-reference.md",
    "docs/integration-guide.md",
    "docs/demo-scenarios.md",
    "docs/security-design.md",
    "docs/sitl-integration.md",
    "docs/PRODUCT-POSITIONING.md",
    "docs/frontend-dev-guide.md",
    "docs/customer-onboarding-guide.md",
)

# 规划/历史文档：允许写"将来要有"的接口，不参与本门禁。
SKIP_DOCS = (
    "ROADMAP.md",
    "CHANGELOG.md",
    "docs/todo.md",
    "docs/commercialization-plan.md",
    "docs/persistence-and-dead-surface-audit.md",
    "docs/decisions-2026-10-26.md",
    "docs/real-integration-program.md",
)

# 否定/规划语境：同一行（或上下 2 行的邻域）出现这些词时，该路径不算"声称存在"。
# 邻域是必要的：本仓的诚实注释常写成三行引用块，路径在中间行、结论（"均不存在"）在下一行。
NEGATIVE = ("未实现", "不存在", "没有", "从未", "待实现", "规划", "建议新增", "计划", "预留")
NEGATIVE_WINDOW = 2


def _paths_in(args):
    if not args:
        return [""]
    found = re.findall(r'"([^"]*)"', args)
    return found or [""]


def collect_real_endpoints(src_dir):
    """返回 {(verb, path)} 与 {归一 path}。"""
    real = set()
    for dirpath, _, filenames in os.walk(src_dir):
        for name in filenames:
            if not name.endswith(".java") or "actuator" in name.lower():
                continue
            full = os.path.join(dirpath, name)
            text = io.open(full, encoding="utf-8", errors="replace").read()
            if not REST.search(text):
                continue
            decl = CLASS_DECL.search(text)
            cls_at = decl.start() if decl else 0
            bases = list(BASE.finditer(text, 0, cls_at))
            base = ""
            if bases:
                got = _paths_in(bases[-1].group(1))
                base = got[0] if got else ""
            for m in MAP.finditer(text, cls_at):
                verb = m.group(1).upper()
                for sub in _paths_in(m.group(2)):
                    if sub and not sub.startswith("/"):
                        sub = "/" + sub
                    real.add((verb, (base.rstrip("/") + sub) if base else sub))
    return real


def _compile(path):
    """把代码路径编成正则：{var} → [^/]+ ；其余按字面量。"""
    parts = []
    for seg in path.strip("/").split("/"):
        if seg.startswith("{") and seg.endswith("}"):
            parts.append("[^/]+")
        else:
            parts.append(re.escape(seg))
    return re.compile("^/" + "/".join(parts) + "$")


def _doc_path(raw):
    raw = raw.split("?")[0].split("#")[0]
    return re.sub(r"/+$", "", raw)


def scan_docs(repo, docs):
    """返回 [(文档原文路径, [(文件, 行号, 原文), ...])]，保持文档里的字面写法。"""
    found = {}
    for rel in docs:
        full = os.path.join(repo, rel)
        if not os.path.exists(full):
            continue
        lines = io.open(full, encoding="utf-8", errors="replace").read().split("\n")
        for idx, line in enumerate(lines):
            # 否定语境按「行 ± NEGATIVE_WINDOW」判：诚实注释常把路径与"不存在"分写两行
            lo = max(0, idx - NEGATIVE_WINDOW)
            hi = min(len(lines), idx + NEGATIVE_WINDOW + 1)
            window = "\n".join(lines[lo:hi])
            if any(w in window for w in NEGATIVE):
                continue
            for m in PATH_IN_DOC.finditer(line):
                raw = _doc_path(m.group(1))
                if raw == "/api/v1":
                    continue
                found.setdefault(raw, []).append((rel, idx + 1, raw))
    return sorted(found.items())


def check(repo):
    real = collect_real_endpoints(os.path.join(repo, "cloud-backend", "src", "main", "java"))
    code_paths = sorted({p for _, p in real})
    patterns = [(_compile(p), p) for p in code_paths]

    problems = []
    for raw, locs in scan_docs(repo, SCAN_DOCS):
        base = raw.rstrip(".").rstrip("/")
        if raw.endswith("..."):                       # 文档省略写法：父级存在即可
            base = raw[:-3].rstrip("/")
            if any(p.startswith(base) for p in code_paths):
                continue
            problems.append((raw, locs))
            continue
        if any(pat.match(base) for pat, _ in patterns):
            continue
        if any(p.startswith(base + "/") for p in code_paths):   # 只写到父级
            continue
        problems.append((raw, locs))
    return len(real), problems


def _report(problems):
    if not problems:
        print("文档端点门禁：通过（文档里写出的 /api/v1 路径在代码里都存在）")
        return
    print("文档端点门禁：%d 个路径在代码里找不到" % len(problems))
    for path, locs in problems:
        print("  ✗ %s" % path)
        for rel, lineno, raw in locs[:4]:
            print("      %s:%d  (%s)" % (rel, lineno, raw))
        if len(locs) > 4:
            print("      …共 %d 处" % len(locs))
    print("\n修法：把文档改成真实路径；若该能力确实还没做，删除该节或在该行写明「未实现」"
          "（本门禁对含「未实现/不存在/预留」等否定语境的行不判违规）。")


def self_test():
    """离线夹具：造一个迷你仓库，验证三种情形都被正确处理。"""
    tmp = tempfile.mkdtemp(prefix="doc-endpoint-gate-")
    try:
        java = os.path.join(tmp, "cloud-backend", "src", "main", "java", "demo")
        os.makedirs(java)
        io.open(os.path.join(java, "DemoController.java"), "w", encoding="utf-8").write(
            "package demo;\n"
            "@RestController\n"
            "@RequestMapping(\"/api/v1/demo\")\n"
            "public class DemoController {\n"
            "    @GetMapping\n"                       # 裸注解：路径=base
            "    public Object list() { return null; }\n"
            "    @GetMapping(\"/{id}/detail\")\n"      # 带路径变量
            "    public Object detail() { return null; }\n"
            "    @PostMapping(value = \"/do\")\n"      # value= 写法
            "    public Object act() { return null; }\n"
            "}\n")

        docs = os.path.join(tmp, "docs")
        os.makedirs(docs)
        good = ("GET /api/v1/demo\n"
                "GET /api/v1/demo/42/detail\n"
                "POST /api/v1/demo/do\n")
        io.open(os.path.join(tmp, "README.md"), "w", encoding="utf-8").write(good)
        io.open(os.path.join(docs, "api-reference.md"), "w", encoding="utf-8").write(good)

        _, problems = check(tmp)
        if problems:
            print("自测失败：正确夹具被判违规 %s" % problems)
            return 1

        # 情形 2：写了一个不存在的端点 → 必须判红
        io.open(os.path.join(docs, "api-reference.md"), "w", encoding="utf-8").write(
            good + "GET /api/v1/demo/ghost\n")
        _, problems = check(tmp)
        if not problems or problems[0][0] != "/api/v1/demo/ghost":
            print("自测失败：不存在的端点没被判红（%s）" % problems)
            return 1

        # 情形 3：同一路径但行内写明「未实现」→ 不算违规
        io.open(os.path.join(docs, "api-reference.md"), "w", encoding="utf-8").write(
            good + "（注：/api/v1/demo/ghost 尚未实现，调用会 404）\n")
        _, problems = check(tmp)
        if problems:
            print("自测失败：否定语境行被误判（%s）" % problems)
            return 1

        print("自测通过（3 情形：存在→绿 / 不存在→红 / 否定语境→豁免）")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv):
    if "--self-test" in argv:
        return self_test()
    total, problems = check(ROOT)
    print("代码端点 = %d 条；扫描「当前口径」文档 %d 篇" % (total, len(SCAN_DOCS)))
    _report(problems)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
