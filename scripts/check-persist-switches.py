#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""persist 开关 profile 对账：`@Value` 里 `:false` 默认的 `*.persist*` 开关，prod 与 staging 都必须显式覆盖。

**为什么需要它**
----------------
2026-10-26 审计 §5.1 抓到的真事：空域预约的持久化路径（表 V26/V27 + 往返测试）**早就建成了**，
`restorePersistedReservations()` 也一直在，但五个 profile **没有一个**把
`aerofleet.scheduling.reservation.persist` 打开——"未完成的上半截"在代码里躺了很久，
没有任何东西会红。2026-10-27 同类第二例：审计链的 staging 覆盖行同样缺席（#185 才补上）。

这类洞的形状是固定的：**开关有默认值 false、生产 profile 没覆盖 ⇒ 功能永远不会开**，
而编译/启动/测试全绿。审计 §6 第三条建议门禁把"表建了但开关没人开"变成 CI 红灯，本脚本就是它。

**判据（每条都可复算）**
------------------------
1. 扫 `cloud-backend/src/main/java/**/*.java` 的 `@Value("${key:false}")`，取 `key` 含 `persist` 的开关；
2. `application-prod.properties` 与 `application-staging.properties` **都**必须有该键的显式行；
3. 显式行的值必须是 `true`；要显式 `false`（例如"保持关闭"是拍板结论）必须进 `EXEMPT`（理由 + 到期日）；
4. 豁免到期 / 豁免已被满足 / 僵尸豁免 ⇒ 红（照 SPI 门禁的同款棘轮）。
退出码：0 通过；1 违规；**2 无法判定**（一个 persist 开关都没扫到＝扫描器失明，绝不伪装成 0）。

**口径与局限**
--------------
- 只盯 `:false` 默认且键名含 `persist` 的开关；其它类型的开关（如 `intercept-enabled`）不在判据内。
- "prod/staging 都有覆盖行"不检查 test/dev profile——本门禁管的是**出厂姿势**。
- 值是文本级解析（properties 行），不是 Spring 实际解析结果；特殊写法（占位符拼接）会失配并报红。

用法：
    python3 scripts/check-persist-switches.py              # CI 判定
    python3 scripts/check-persist-switches.py --print      # 输出当前账目（刷新豁免用）
    python3 scripts/check-persist-switches.py --self-test  # 离线自证：八种情形各自预期红/绿/无法判定
"""

import argparse
import datetime
import glob
import io
import os
import re
import sys
import tempfile

VALUE_RE = re.compile(r'@Value\(\s*"\$\{([^}:]+)(?::([^}]*))?\}"\s*\)')

PROD = "cloud-backend/src/main/resources/application-prod.properties"
STAGING = "cloud-backend/src/main/resources/application-staging.properties"

# 豁免账：显式保持关闭的开关必须登记"为什么关、什么时候重审"。
# 生成账目用 --print，别手抄。
EXEMPT = {}


def find_switches(root):
    """返回 {key: 定义处相对路径}，key 含 persist 且 `:false` 默认。"""
    found = {}
    pattern = os.path.join(root, "cloud-backend", "src", "main", "java", "**", "*.java")
    for p in sorted(glob.glob(pattern, recursive=True)):
        try:
            txt = io.open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        for m in VALUE_RE.finditer(txt):
            key, default = m.group(1), (m.group(2) or "").strip().lower()
            if "persist" in key.lower() and default == "false":
                found.setdefault(key, os.path.relpath(p, root).replace(os.sep, "/"))
    return found


def read_prop(root, rel):
    """读一个 properties 文件的键值（后出现者胜，与 Spring 同）。缺文件返回 None。"""
    p = os.path.join(root, rel.replace("/", os.sep))
    if not os.path.isfile(p):
        return None
    out = {}
    for raw in io.open(p, encoding="utf-8", errors="replace").read().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def audit(root, switches=None, exempt=None, today=None):
    """返回 (exit_code, lines)。"""
    switches = find_switches(root) if switches is None else switches
    exempt = EXEMPT if exempt is None else exempt
    today = today or datetime.date.today()
    out, violations = [], []

    prod = read_prop(root, PROD)
    staging = read_prop(root, STAGING)
    if prod is None or staging is None:
        out.append("无法判定：prod/staging properties 文件读不到（prod=%s, staging=%s）"
                   % (prod is not None, staging is not None))
        out.append("  判 2，不判 0——文件结构变了的时候不许报绿。")
        return 2, out
    if not switches:
        out.append("无法判定：一个 `:false` 默认的 persist 开关都没扫到（root=%s）。" % root)
        out.append("  要么扫描器瞎了（@Value 写法变了？模块结构变了？），要么开关全被删了。")
        return 2, out

    covered = []
    for key in sorted(switches):
        row = {"key": key, "def": switches[key],
               "prod": prod.get(key), "staging": staging.get(key)}
        ex = exempt.get(key)
        problems = []
        if row["prod"] is None:
            problems.append("prod 未覆盖（出厂姿势=默认 false，持久化永远不会开）")
        elif row["prod"].lower() != "true":
            problems.append("prod 显式 %s" % row["prod"])
        if row["staging"] is None:
            problems.append("staging 未覆盖（预发从未验证过这条持久化通路）")
        elif row["staging"].lower() != "true":
            problems.append("staging 显式 %s" % row["staging"])

        if problems:
            if ex is None:
                violations.append("%s（%s）：%s" % (key, row["def"], "；".join(problems)))
            else:
                try:
                    expires = datetime.date.fromisoformat(ex["expires"])
                except (KeyError, ValueError):
                    violations.append("%s：豁免条目缺 expires 或格式不是 YYYY-MM-DD" % key)
                    continue
                if expires < today:
                    violations.append(
                        "豁免已到期：%s（到期 %s）——请打开开关或显式改期，别悄悄续" % (key, expires))
                else:
                    row["exempt"] = ex
                    covered.append(row)
        else:
            if ex is not None:
                violations.append(
                    "豁免可以撤销：%s 已在 prod/staging 都显式 true，请从 EXEMPT 删除（棘轮只准拧紧）" % key)
            else:
                covered.append(row)

    for key in sorted(exempt):
        if key not in switches:
            violations.append("豁免账有僵尸条目：%s 已不是 `:false` 默认的 persist 开关（删掉）" % key)

    out.append("persist 开关对账：开关 %d 个（已对齐 %d / 豁免 %d / 违规 %d）"
               % (len(switches), sum(1 for r in covered if "exempt" not in r),
                  sum(1 for r in covered if "exempt" in r), len(violations)))
    for row in covered:
        mark = "豁免" if "exempt" in row else "对齐"
        out.append("  [%s] %s = prod:%s / staging:%s  <- %s"
                   % (mark, row["key"], row["prod"], row["staging"], row["def"]))
    if violations:
        out.append("")
        out.append("违规 %d 条：" % len(violations))
        out.extend("  - " + v for v in violations)
        return 1, out
    return 0, out


def do_print(root):
    switches = find_switches(root)
    prod = read_prop(root, PROD) or {}
    staging = read_prop(root, STAGING) or {}
    for key in sorted(switches):
        print("%-52s prod=%-5s staging=%-5s <- %s"
              % (key, prod.get(key), staging.get(key), switches[key]))
    return 0


# ---------------------------------------------------------------- 自测
def write_fixture(tmp, files):
    for rel, content in files.items():
        p = os.path.join(tmp, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with io.open(p, "w", encoding="utf-8", newline="") as fh:
            fh.write(content)


def self_test():
    """八种情形必须各自给出预期结果。任一不符 ⇒ 自测失败（exit 1）。"""
    fails = []
    today = datetime.date(2026, 10, 28)
    java = ("cloud-backend/src/main/java/p/Foo.java",
            'class Foo { @Value("${aerofleet.foo.persist:false}") boolean p; }\n')

    def root_with(prod, staging):
        return {
            java[0]: java[1],
            "cloud-backend/src/main/resources/application-prod.properties": prod,
            "cloud-backend/src/main/resources/application-staging.properties": staging,
        }

    def run(files, exempt=None):
        with tempfile.TemporaryDirectory() as tmp:
            write_fixture(tmp, files)
            return audit(tmp, exempt=exempt or {}, today=today)

    def expect(label, code, lines, want_code, needle=None):
        if code != want_code:
            fails.append("%s 期望 %d 实得 %d :: %s" % (label, want_code, code, lines))
        if needle and not any(needle in ln for ln in lines):
            fails.append("%s 未报出期待文案 %r :: %s" % (label, needle, lines))

    # 1) 两处都 true，无豁免 ⇒ 0
    code, lines = run(root_with("aerofleet.foo.persist=true\n", "aerofleet.foo.persist=true\n"))
    expect("情形1", code, lines, 0)

    # 2) prod 没覆盖 ⇒ 1，点名 prod
    code, lines = run(root_with("", "aerofleet.foo.persist=true\n"))
    expect("情形2", code, lines, 1, "prod 未覆盖")

    # 3) staging 没覆盖 ⇒ 1，点名 staging
    code, lines = run(root_with("aerofleet.foo.persist=true\n", ""))
    expect("情形3", code, lines, 1, "staging 未覆盖")

    # 4) prod 显式 false、无豁免 ⇒ 1，点名显式关闭
    code, lines = run(root_with("aerofleet.foo.persist=false\n", "aerofleet.foo.persist=true\n"))
    expect("情形4", code, lines, 1, "prod 显式 false")

    # 5) prod 显式 false、挂有效豁免 ⇒ 0
    code, lines = run(root_with("aerofleet.foo.persist=false\n", "aerofleet.foo.persist=true\n"),
                      exempt={"aerofleet.foo.persist": {"reason": "fixture", "expires": "2099-01-01"}})
    expect("情形5", code, lines, 0)

    # 6) 豁免到期 ⇒ 1
    code, lines = run(root_with("aerofleet.foo.persist=false\n", "aerofleet.foo.persist=true\n"),
                      exempt={"aerofleet.foo.persist": {"reason": "fixture", "expires": "2020-01-01"}})
    expect("情形6", code, lines, 1, "豁免已到期")

    # 7) 两处都 true 却还挂豁免 ⇒ 1，点名可撤销
    code, lines = run(root_with("aerofleet.foo.persist=true\n", "aerofleet.foo.persist=true\n"),
                      exempt={"aerofleet.foo.persist": {"reason": "fixture", "expires": "2099-01-01"}})
    expect("情形7", code, lines, 1, "豁免可以撤销")

    # 8) 一个开关都没扫到 ⇒ 2（扫描器失明不许报绿）
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, {
            "cloud-backend/src/main/java/p/Foo.java": "class Foo {}\n",
            "cloud-backend/src/main/resources/application-prod.properties": "",
            "cloud-backend/src/main/resources/application-staging.properties": "",
        })
        code, lines = audit(tmp, today=today)
        expect("情形8", code, lines, 2, "无法判定")

    if fails:
        print("[FAIL] persist 开关门禁自测：%d 条不符" % len(fails))
        for f in fails:
            print("  - " + f)
        return 1
    print("[PASS] persist 开关门禁自测：8 种情形全部按预期（红/绿/无法判定）")
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--print", dest="do_print", action="store_true")
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.do_print:
        return do_print(args.root)
    code, lines = audit(args.root)
    print("\n".join(lines))
    return code


if __name__ == "__main__":
    sys.exit(main())
