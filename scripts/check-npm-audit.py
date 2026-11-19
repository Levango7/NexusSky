#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""npm audit 精确豁免门禁：只豁免一个已确认无补丁的 advisory。

背景（2026-11-18 取证）
----------------------
GHSA-fc5q-39xc-vjpf（picomatch ReDoS，CVSS 8.7）于 2026-11-17 才被 GitHub Review，
影响 picomatch <= 4.0.7。而 picomatch 的最新版本就是 4.0.7，advisory 明列
`Patched versions: None` —— 没有可升级的安全版本。

vitest 5 要求 vite ^6.4，vite 经 fdir -> tinyglobby -> picomatch 拉入该漏洞；
把 vitest 降到 3.x 想绕开，结果 vitest 3 自己也经 tinyglobby -> fdir 拉同一版本。
`npm audit fix --force` 实测把 6 high 变成 11 个漏洞（含 3 critical 的 tinypool RCE）。

因此这里**只豁免这一个 advisory**，且仅在某条漏洞的 via 链全部最终指向它时才忽略。
任何其他 high / critical（包括同一包上的第二个 advisory）仍然判红。

到期条件（任一成立即删除本豁免）：
  - picomatch 发布 > 4.0.7；
  - GHSA-fc5q-39xc-vjpf 被撤回或降级。

用法：
  npm audit --json > npm-audit.json || true
  python3 scripts/check-npm-audit.py npm-audit.json
  python3 scripts/check-npm-audit.py --self-test
退出码：0 无未豁免的 high/critical；1 有；2 用法错误。
"""
import io
import json
import sys

EXEMPT_GHSA = "GHSA-fc5q-39xc-vjpf"
BLOCK_LEVELS = {"high", "critical"}
MARKER = "advisories/GHSA-"


def via_advisory_ids(item):
    """via 中的对象元素 -> GHSA 集合；字符串元素 -> None（表示需递归查另一个包）。"""
    if isinstance(item, str):
        return None
    if not isinstance(item, dict):
        return set()
    url = item.get("url") or ""
    if MARKER in url:
        tail = url.split(MARKER, 1)[1]
        return {"GHSA-" + tail.split("/")[0].split("?")[0].split("#")[0]}
    # 没有可识别的 GHSA URL：不能证明属于豁免项，保守不豁免
    return set()


def only_exempt(name, vulns, chain=()):
    """返回 (via 链是否全部豁免, 是否找到实际 advisory)。

    环形引用（vitest <-> coverage-v8）本身不阻断，但也不算「找到豁免依据」；
    整条链必须至少落到一个实际 advisory，否则不能靠构造环来绕过判定。
    """
    if name in chain:
        return True, False
    v = vulns.get(name)
    if not isinstance(v, dict):
        return False, False
    via = v.get("via") or []
    if not via:
        return False, False
    found = False
    for item in via:
        ids = via_advisory_ids(item)
        if ids is None:
            ok, sub_found = only_exempt(item, vulns, chain + (name,))
            if not ok:
                return False, found
            found = found or sub_found
        else:
            if not ids or not ids.issubset({EXEMPT_GHSA}):
                return False, found
            found = True
    return True, found


def evaluate(report):
    vulns = report.get("vulnerabilities") or {}
    red, exempted = [], []
    for name in sorted(vulns):
        v = vulns[name]
        sev = str(v.get("severity", "")).lower()
        if sev not in BLOCK_LEVELS:
            continue
        ok, found = only_exempt(name, vulns)
        if ok and found:
            exempted.append("%s(%s)" % (name, sev))
            continue
        sources = []
        for item in v.get("via") or []:
            if isinstance(item, str):
                sources.append(item)
            else:
                sources.append((item.get("url") or item.get("title") or "?").split("/")[-1])
        red.append("%s: %s via %s" % (name, sev, ", ".join(sources) or "(unknown)"))
    return red, exempted


def self_test():
    def adv(ghsa, sev="high"):
        return {
            "source": 1, "name": "picomatch", "title": "ReDoS", "severity": sev,
            "range": "*", "url": "https://github.com/advisories/" + ghsa,
        }

    cases = []

    # 1) 全链豁免：picomatch 本体 + 两个 via 字符串引用的下游
    clean = {"vulnerabilities": {
        "picomatch": {"severity": "high", "via": [adv(EXEMPT_GHSA)]},
        "fdir": {"severity": "high", "via": ["picomatch"]},
        "vite": {"severity": "high", "via": ["fdir", "picomatch"]},
    }}
    r, e = evaluate(clean)
    cases.append(("全链豁免应绿", not r and len(e) == 3, r, e))

    # 2) 同一包上混入第二个 advisory -> 必须红
    mixed = {"vulnerabilities": {
        "picomatch": {"severity": "high", "via": [adv(EXEMPT_GHSA)]},
        "vite": {"severity": "high", "via": ["picomatch", adv("GHSA-OTHER-0001")]},
    }}
    r, e = evaluate(mixed)
    cases.append(("混入第二个 advisory 应红", any(x.startswith("vite:") for x in r), r, e))

    # 3) 非豁免 critical 必须红
    crit = {"vulnerabilities": {
        "tinypool": {"severity": "critical", "via": [adv("GHSA-CRIT-0002", "critical")]},
    }}
    r, e = evaluate(crit)
    cases.append(("非豁免 critical 应红", any(x.startswith("tinypool:") for x in r), r, e))

    # 4) moderate 不在 audit-level=high 范围内，不应阻断
    mod = {"vulnerabilities": {
        "glob": {"severity": "moderate", "via": [adv("GHSA-MOD-0003", "moderate")]},
    }}
    r, e = evaluate(mod)
    cases.append(("moderate 不应阻断", not r, r, e))

    # 5) via 环形引用保守判红（不允许靠构造环绕过豁免）
    ring = {"vulnerabilities": {
        "a": {"severity": "high", "via": ["b"]},
        "b": {"severity": "high", "via": ["a"]},
    }}
    r, e = evaluate(ring)
    cases.append(("环形 via 应保守判红", len(r) == 2, r, e))

    # 5b) 环形引用但链上确有豁免源（真实 vitest <-> coverage-v8 结构）-> 应绿
    ring_exempt = {"vulnerabilities": {
        "picomatch": {"severity": "high", "via": [adv(EXEMPT_GHSA)]},
        "coverage-v8": {"severity": "high", "via": ["vitest"]},
        "vitest": {"severity": "high", "via": ["coverage-v8", "picomatch"]},
    }}
    r, e = evaluate(ring_exempt)
    cases.append(("环形但有豁免源应绿", not r and len(e) == 3, r, e))

    # 6) 无 via 的高危不得被误豁免
    novia = {"vulnerabilities": {"x": {"severity": "high", "via": []}}}
    r, e = evaluate(novia)
    cases.append(("无 via 的高危应红", any(x.startswith("x:") for x in r), r, e))

    failed = 0
    for name, ok, r, e in cases:
        print(("PASS  " if ok else "FAIL  ") + name)
        if not ok:
            failed += 1
            print("        red=%s exempted=%s" % (r, e))
    print("self-test: %d/%d passed" % (len(cases) - failed, len(cases)))
    return 1 if failed else 0


def main(argv):
    if "--self-test" in argv:
        return self_test()
    if len(argv) != 1:
        print("用法: python3 scripts/check-npm-audit.py <npm-audit.json|-> [--self-test]")
        return 2
    try:
        if argv[0] == "-":
            report = json.load(sys.stdin)
        else:
            report = json.load(io.open(argv[0], encoding="utf-8"))
    except Exception as exc:
        print("✗ 无法读取 npm audit JSON：%s" % exc)
        return 1

    red, exempted = evaluate(report)
    if exempted:
        print("豁免（via 链全部指向 %s）：%s" % (EXEMPT_GHSA, ", ".join(exempted)))
    if red:
        print("✗ 仍有非豁免的 high/critical：")
        for item in red:
            print("  - " + item)
        return 1
    print("✓ npm audit 通过：无未豁免的 high/critical")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
