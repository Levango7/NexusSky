#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：文档「配置表」里列出的 `aerofleet.*` 键，必须真的被代码读取或配置定义过。

为什么需要它
-----------
2026-10-29 发现 `docs/customer-onboarding-guide.md` §5.6 让客户"按合同选型配置模块启用/禁用"，
列了 8 个键：

    aerofleet.modules.mesh.enabled / emergency / scheduling / relay / twin /
    surveillance / formation / spray

**全部不存在**：全仓 grep 无读取点，`application.properties` 也没有定义。客户照着配不会生效，
却会以为"已按合同关掉某模块"——而真正的模块裁剪机制是 License 的模块清单
（`LicenseModuleMap` + `LicenseInterceptor`）。

这类失真比"路径写错"更隐蔽：路径错了立刻 404，配置键错了**静默无效**，没人会红。

口径（刻意写死）
---------------
1. 只认**表格行**形态：`| `aerofleet.x.y` | … |`——这是文档在"承诺一个配置项"的明确信号；
   正文里顺口提到的键（如度量名 `aerofleet.signing.unsigned`）不扫，避免误报。
2. 键算"存在"的四种情形：
   a. 在 `application*.properties` / `*.yml` 里定义；
   b. 被 `@Value("${k…}")` 读取；
   c. 被 `@ConditionalOnProperty(name=k)` / `(prefix=p, name=n)` 读取（拼成全名）；
   d. 落在某个 `@ConfigurationProperties(prefix=p)` 的前缀下（字段由绑定生效）；
   e. 命中动态前缀白名单（见 DYNAMIC_PREFIXES，附理由）。
3. 历史/规划文档不扫（CHANGELOG 里的是度量名与历史记录，不是当前承诺）。
4. 键名大小写与 `-`/`_` 不敏感（Spring 宽松绑定），比对时统一小写并把 `_` 归一成 `-`。

用法：
    python scripts/check-doc-config-keys.py
    python scripts/check-doc-config-keys.py --self-test
退出码：0 全部可解析；1 有文档承诺但代码不认的键。
"""
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 只扫「当前口径」文档（历史/规划文档允许写当时的键）
SCAN_DOCS = (
    "README.md",
    "docs/customer-onboarding-guide.md",
    "docs/deployment-guide.md",
    "docs/security-design.md",
    "docs/api-reference.md",
    "docs/integration-guide.md",
    "docs/frontend-dev-guide.md",
    "docs/PRODUCT-POSITIONING.md",
)

# 动态读取的键前缀：代码用 Environment 按前缀枚举，静态解析看不到。
# 每条都要写清为什么，否则就是"把门禁自己关掉"。
DYNAMIC_PREFIXES = (
    ("aerofleet.plugins.", "PluginConfig 按 aerofleet.plugins.{category}.{type}.enabled 动态枚举"),
)

ROW = re.compile(r"^\|\s*`(aerofleet\.[A-Za-z0-9_.\-]+)`\s*\|")
VALUE = re.compile(r'"\$\{([^}:]+)(?::[^}]*)?\}"')
COND = re.compile(r"@ConditionalOnProperty\(([^)]*)\)")
CP = re.compile(r"@ConfigurationProperties\(\s*(?:prefix\s*=\s*)?" + '"' + r'([^"]+)' + '"')


def _norm(k):
    return k.strip().lower().replace("_", "-")


def collect_code(src_dir):
    """返回 (直接被读取的键集合, @ConfigurationProperties 前缀集合)。"""
    keys, prefixes = set(), set()
    for dirpath, _, files in os.walk(src_dir):
        for name in files:
            if not name.endswith(".java"):
                continue
            for line in io.open(os.path.join(dirpath, name), encoding="utf-8", errors="replace"):
                for m in VALUE.finditer(line):
                    keys.add(_norm(m.group(1)))
                for m in COND.finditer(line):
                    args = m.group(1)
                    pre = re.search(r'prefix\s*=\s*"([^"]+)"', args)
                    nm = re.search(r'name\s*=\s*"([^"]+)"', args) or re.search(
                        r'value\s*=\s*"([^"]+)"', args)
                    if nm:
                        full = (pre.group(1) + "." + nm.group(1)) if pre else nm.group(1)
                        keys.add(_norm(full))
                for m in CP.finditer(line):
                    prefixes.add(_norm(m.group(1)))
    return keys, prefixes


def collect_props(res_dir):
    defined = set()
    if not os.path.isdir(res_dir):
        return defined
    for name in os.listdir(res_dir):
        if not name.endswith((".properties", ".yml", ".yaml")):
            continue
        for line in io.open(os.path.join(res_dir, name), encoding="utf-8", errors="replace"):
            s = line.strip()
            if not s or s.startswith(("#", "!")):
                continue
            m = re.match(r"([A-Za-z0-9_.\-]+)\s*[:=]", s)
            if m:
                defined.add(_norm(m.group(1)))
    return defined


def scan_doc_keys(repo, docs):
    found = {}
    for rel in docs:
        full = os.path.join(repo, rel)
        if not os.path.exists(full):
            continue
        for i, line in enumerate(io.open(full, encoding="utf-8", errors="replace"), 1):
            m = ROW.match(line)
            if m:
                found.setdefault(_norm(m.group(1)), []).append((rel, i))
    return found


def check(repo):
    keys, prefixes = collect_code(os.path.join(repo, "cloud-backend", "src", "main", "java"))
    # drone-sim 也有自己的 @Value / System.getProperty（如 aerofleet.sim.rtcm-fresh-window-ms）
    sim_keys, sim_prefixes = collect_code(os.path.join(repo, "drone-sim", "src", "main", "java"))
    keys |= sim_keys
    prefixes |= sim_prefixes
    defined = collect_props(os.path.join(repo, "cloud-backend", "src", "main", "resources"))

    problems = []
    for key, locs in sorted(scan_doc_keys(repo, SCAN_DOCS).items()):
        if key in keys or key in defined:
            continue
        if any(key == p or key.startswith(p + ".") for p in prefixes):
            continue
        if any(key.startswith(pre) for pre, _ in DYNAMIC_PREFIXES):
            continue
        problems.append((key, locs))
    return len(keys) + len(defined), problems


def self_test():
    tmp = tempfile.mkdtemp(prefix="doc-config-gate-")
    try:
        java = os.path.join(tmp, "cloud-backend", "src", "main", "java", "demo")
        res = os.path.join(tmp, "cloud-backend", "src", "main", "resources")
        os.makedirs(java)
        os.makedirs(res)
        io.open(os.path.join(java, "Props.java"), "w", encoding="utf-8").write(
            'package demo;\n'
            '@ConfigurationProperties(prefix = "aerofleet.demo")\n'
            'public class Props {\n'
            '    @Value("${aerofleet.port:8080}") int port;\n'
            '    @ConditionalOnProperty(prefix = "aerofleet.demo", name = "enabled")\n'
            '    void x() {}\n'
            '}\n')
        io.open(os.path.join(res, "application.properties"), "w", encoding="utf-8").write(
            "aerofleet.defined-key=true\n")

        docs = os.path.join(tmp, "docs")
        os.makedirs(docs)
        good = ("| `aerofleet.port` | 8080 | 端口 |\n"
                "| `aerofleet.defined-key` | true | 已定义 |\n"
                "| `aerofleet.demo.field` | x | 前缀覆盖 |\n"
                "| `aerofleet.demo.enabled` | true | 条件装配 |\n")
        io.open(os.path.join(docs, "deployment-guide.md"), "w", encoding="utf-8").write(good)
        io.open(os.path.join(tmp, "README.md"), "w", encoding="utf-8").write(good)

        _, problems = check(tmp)
        if problems:
            print("自测失败：正确夹具被判违规 %s" % problems)
            return 1

        io.open(os.path.join(docs, "deployment-guide.md"), "w", encoding="utf-8").write(
            good + "| `aerofleet.modules.ghost.enabled` | true | 并不存在 |\n")
        _, problems = check(tmp)
        if not problems or problems[0][0] != "aerofleet.modules.ghost.enabled":
            print("自测失败：不存在的配置键没被判红（%s）" % problems)
            return 1

        # 正文提到（非表格行）不算承诺：不应判红
        io.open(os.path.join(docs, "deployment-guide.md"), "w", encoding="utf-8").write(
            good + "指标 `aerofleet.signing.unsigned` 用于统计未签名帧。\n")
        _, problems = check(tmp)
        if problems:
            print("自测失败：非表格行的键被误判（%s）" % problems)
            return 1

        print("自测通过（4 情形：@Value/已定义/前缀/条件→绿；伪键→红；正文提及→豁免）")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv):
    if "--self-test" in argv:
        return self_test()
    total, problems = check(ROOT)
    print("可解析的配置键 = %d 个；扫描「当前口径」文档 %d 篇" % (total, len(SCAN_DOCS)))
    if not problems:
        print("文档配置键门禁：通过（配置表里的 aerofleet.* 键都能被代码读取或配置定义）")
        return 0
    print("文档配置键门禁：%d 个键文档里有、代码不认" % len(problems))
    for key, locs in problems:
        print("  ✗ %s" % key)
        for rel, lineno in locs[:4]:
            print("      %s:%d" % (rel, lineno))
    print("\n修法：要么在代码里真的读这个键（@Value / @ConditionalOnProperty / @ConfigurationProperties），"
          "\n      要么把它从配置表里删掉、改写真实机制；动态前缀请登记到 DYNAMIC_PREFIXES 并写明理由。")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
