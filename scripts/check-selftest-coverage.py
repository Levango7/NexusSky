#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自测覆盖门禁：提供了 `--self-test` 的脚本，必须有 CI job 真的跑它。

为什么需要这个
--------------
2026-10-12 起的"观察模式"e2e 批次都是"先以 `continue-on-error` 接入收集 flaky 数据，
若干次 run 全绿后删掉标记转硬门禁"。这套流程本身依赖**每个脚本的断言统计是可信的**。

而 `scripts/coord-status.sh` 自己带着 `--self-test`，**却从来没有被任何 CI job 跑过** ——
于是它的写回守卫坏了 8 天（把"自动段合法变短"误判成"写坏"，状态板自 2026-10-12 起冻住，
各 agent 的开工声明发不出去），期间没有任何门禁会红。

这个门禁把那条教训变成机械检查：
**任何提供了 `--self-test` 的脚本，如果没有被 CI 引用，门禁变红。**

检查项
------
1. 扫 `scripts/` 下支持 `--self-test` 的脚本（按文件内容里出现 `--self-test` 判定）。
2. 扫 `.github/workflows/*.yml` 的所有 `run:` 文本。
3. 每个自测脚本的**文件名**必须出现在某个 CI `run:` 里 —— 否则报缺失。

用法：python scripts/check-selftest-coverage.py [--self-test]
退出码：0 全覆盖；1 有脚本的自测没被 CI 跑。
"""
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS = os.path.join(ROOT, "scripts")
WORKFLOWS = os.path.join(ROOT, ".github", "workflows")

# 这个门禁自己提到了 "--self-test"（在文档里），不算"提供自测的脚本"
SELF = {"check-selftest-coverage.py"}


def scan(scripts_dir, workflows_dir):
    """返回 (candidates, covered, uncovered)。"""
    ci_text = []
    if os.path.isdir(workflows_dir):
        for wf in sorted(os.listdir(workflows_dir)):
            if wf.endswith((".yml", ".yaml")):
                ci_text.append(io.open(os.path.join(workflows_dir, wf),
                                       encoding="utf-8", errors="replace").read())
    all_ci = "\n".join(ci_text)

    candidates = []
    for name in sorted(os.listdir(scripts_dir)):
        if not (name.endswith(".sh") or name.endswith(".py")):
            continue
        if name in SELF:
            continue
        p = os.path.join(scripts_dir, name)
        try:
            text = io.open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        if "--self-test" in text:
            candidates.append(name)

    covered, uncovered = [], []
    for name in candidates:
        (covered if name in all_ci else uncovered).append(name)
    return candidates, covered, uncovered


def self_test():
    """用合成夹具自证：坏情形要能拦、好情形不能误拦。"""
    tmp = tempfile.mkdtemp(prefix="selftest-cov-")
    try:
        sd = os.path.join(tmp, "scripts")
        wd = os.path.join(tmp, ".github", "workflows")
        os.makedirs(sd)
        os.makedirs(wd)
        # 一个提供 --self-test 的脚本
        io.open(os.path.join(sd, "fake-gate.py"), "w", encoding="utf-8").write(
            "import sys\nif '--self-test' in sys.argv:\n    print('ok')\n")
        # 一个不提供自测的脚本（不该被算进去）
        io.open(os.path.join(sd, "plain.py"), "w", encoding="utf-8").write("print('hi')\n")
        # CI 里**没有**引用 fake-gate
        io.open(os.path.join(wd, "ci.yml"), "w", encoding="utf-8").write(
            "jobs:\n  x:\n    steps:\n      - run: python3 scripts/other.py\n")
        cands, cov, unc = scan(sd, wd)
        ok1 = (cands == ["fake-gate.py"] and unc == ["fake-gate.py"] and cov == [])
        # CI 里引用了它
        io.open(os.path.join(wd, "ci.yml"), "w", encoding="utf-8").write(
            "jobs:\n  x:\n    steps:\n      - run: python3 scripts/fake-gate.py --self-test\n")
        cands2, cov2, unc2 = scan(sd, wd)
        ok2 = (cands2 == ["fake-gate.py"] and cov2 == ["fake-gate.py"] and unc2 == [])
        print("  [PASS] 没被 CI 引用时能报出" if ok1 else "  [FAIL] 没被 CI 引用时没报出")
        print("  [PASS] 被 CI 引用后不再报" if ok2 else "  [FAIL] 被 CI 引用后仍误报")
        return 0 if (ok1 and ok2) else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--self-test" in sys.argv:
        return self_test()
    candidates, covered, uncovered = scan(SCRIPTS, WORKFLOWS)
    print("提供 --self-test 的脚本 = %d，被 CI 引用 = %d" % (len(candidates), len(covered)))
    for name in covered:
        print("  已覆盖: scripts/%s" % name)
    if uncovered:
        print("")
        print("自测覆盖门禁：%d 个脚本的自测没人在 CI 里跑" % len(uncovered))
        for name in uncovered:
            print("  - scripts/%s" % name)
        print("")
        print("  这些脚本的自测必须进 CI，否则它们坏了不会有任何人知道（真事：coord-status.sh 的")
        print("  守卫坏了 8 天，自测就在仓库里，却没人跑）。")
        return 1
    print("自测覆盖门禁：通过（每个提供 --self-test 的脚本都有 CI job 跑它）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
