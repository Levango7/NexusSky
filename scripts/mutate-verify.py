#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""变异验证：把生产代码改坏 ⇒ 指定用例必须红；还原 ⇒ 必须绿。

为什么需要这个脚本（2026-10-22 踩的坑）：
  手工用 `Copy-Item` 还原源文件时，文件系统的 **LastWriteTime 会跟着还原成旧值**，
  Maven 增量编译看到"源码比 class 旧"就跳过重编 —— 于是你以为在跑还原后的代码，
  实际跑的还是变异后的 .class。表现是"还原后用例仍然红"，很容易被误判成
  "用例本身不稳定"，进而去改一个本来没问题的测试。

本脚本的机械保证：
  1) 改坏前把原文读进内存（不依赖备份文件）；
  2) 还原时写回原文并 **os.utime 刷新时间戳**（强制重编）；
  3) 还原后必须再跑一遍，**绿了才算验证完成**；没绿就直接报错，不让人误判；
  4) 任何一步失败都会把源码还原（try/finally），不留半截状态。

用法：
  python scripts/mutate-verify.py \
      --file cloud-backend/src/main/java/.../Foo.java \
      --old '        foo();' --new '        // MUTATION' \
      --test 'FooTest#barMustFail' [--module cloud-backend] [--self-test]

退出码：
  0 = 变异被杀掉 且 还原后全绿（验证成立）
  1 = 变异没被杀掉（用例没守住这段代码）
  2 = 还原后仍然是红的（环境/计数问题，先查再结论）
"""
import argparse
import os
import subprocess
import sys

MAVEN = "mvn"


def _read(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    # 关键：刷新时间戳，否则 Maven 增量编译会继续用旧 class
    os.utime(path, None)


def run_test(module, test_selector, quiet=True):
    cmd = [MAVEN, "-B", "-o", "-pl", module, "test",
           "-Dtest=" + test_selector, "-DfailIfNoTests=false", "-Djacoco.skip=true"]
    if quiet:
        cmd.append("-q")
    print("  $ " + " ".join(cmd))
    proc = subprocess.run(cmd, shell=(os.name == "nt"), capture_output=True,
                          text=True, encoding="utf-8", errors="replace")
    tail = [l for l in (proc.stdout or "").splitlines()
            if "Tests run:" in l or "FAILURE!" in l or "ERROR]" in l][-4:]
    for l in tail:
        print("    " + l.strip()[:140])
    return proc.returncode


def mutate_verify(path, old, new, module, test_selector):
    original = _read(path)
    count = original.count(old)
    if count != 1:
        print("  [ABORT] 待替换片段出现 %d 次（必须恰好 1 次）；改用更长的上下文" % count)
        return 2
    if old == new:
        print("  [ABORT] --old 与 --new 相同，没有变异可做")
        return 2

    mutated = original.replace(old, new)
    try:
        _write(path, mutated)
        print("  [1/2] 已改坏：%s" % path)
        rc_bad = run_test(module, test_selector)
        if rc_bad == 0:
            print("  [FAIL] 变异体存活：改坏生产代码后用例仍然全绿 ⇒ 这段行为没人盯")
            return 1
        print("  [OK] 变异体被杀掉（退出码 %d）" % rc_bad)
    finally:
        _write(path, original)
        print("  [2/2] 已还原并刷新时间戳：%s" % path)

    rc_good = run_test(module, test_selector)
    if rc_good != 0:
        print("  [FAIL] 还原后仍为红（退出码 %d）—— 先查测量环境，别急着改用例" % rc_good)
        return 2
    print("  [OK] 还原后全绿，验证成立")
    return 0


def self_test():
    """自证：替换/还原/时间戳 三件事在临时文件上真的成立。"""
    import tempfile
    import time

    fd, p = tempfile.mkstemp(suffix=".java")
    os.close(fd)
    try:
        original = "a();\n    b();\n"
        _write(p, original)
        before = os.stat(p).st_mtime_ns
        time.sleep(0.01)
        _write(p, original.replace("b();", "// MUTATION"))
        mutated_ok = "// MUTATION" in _read(p)
        _write(p, original)
        restored_ok = _read(p) == original
        touched_ok = os.stat(p).st_mtime_ns > before
        checks = [("替换生效", mutated_ok), ("还原字节一致", restored_ok), ("时间戳已刷新", touched_ok)]
        for name, ok in checks:
            print("  [%s] %s" % ("OK" if ok else "FAIL", name))
        return 0 if all(ok for _, ok in checks) else 1
    finally:
        os.unlink(p)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file")
    ap.add_argument("--old")
    ap.add_argument("--new")
    ap.add_argument("--test", dest="test_selector")
    ap.add_argument("--module", default="cloud-backend")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        print("  mutate-verify 自测：")
        sys.exit(self_test())
    if not all([args.file, args.old is not None, args.new is not None, args.test_selector]):
        ap.error("需要 --file / --old / --new / --test（或用 --self-test）")
    print("  变异验证：%s" % args.file)
    sys.exit(mutate_verify(args.file, args.old, args.new, args.module, args.test_selector))


if __name__ == "__main__":
    main()
