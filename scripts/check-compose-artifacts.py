#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""docker-compose 里写死的 jar 名 vs pom 真实产物 —— 静态一致性校验。

为什么需要这个脚本
------------------
`docker-compose.yml` 里把 jar 文件名**硬编码**在 command 里：

    command: java -jar aerofleet-drone-sim-0.1.0-SNAPSHOT-shaded.jar

改 pom 的 `<version>`、`<finalName>` 或 shade 配置时，compose 不会跟着变。
后果不是"构建失败"，而是**容器秒退**（`Unable to access jarfile`）——
而 CI 对此**一直是绿的**：`Docker Compose E2E` 那一步在 ubuntu runner 上
`command -v docker-compose` 恒为假（runner 只有 v2 的 `docker compose`），
每轮都打印 "not available, skipping"，185 行的 e2e 脚本从来没在 CI 跑过。

本脚本**不启动任何容器**：只读 docker-compose.yml 与各模块 pom.xml，
推导"实际会产出什么 jar"，再与 compose 里写死的名字比对。
纯静态、秒级、零 flaky —— 补上"配置与产物脱节"这一格。

推导规则（与 Maven 默认行为一致）：
    <artifactId>-<version>.jar                 普通 jar
    <artifactId>-<version>-shaded.jar          声明了 maven-shade-plugin
version 缺失时继承父 pom（本仓库各模块都这样）。

用法：
    python scripts/check-compose-artifacts.py
    python scripts/check-compose-artifacts.py --self-test

退出码：0 一致；1 不一致 / 解析失败。
"""
import io
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_COMPOSE = os.path.join(ROOT, "docker-compose.yml")
NS = "{http://maven.apache.org/POM/4.0.0}"


def _text(el, tag):
    if el is None:
        return None
    child = el.find(NS + tag)
    if child is None:
        child = el.find(tag)
    return (child.text or "").strip() if child is not None else None


def read_pom(module_dir):
    """读模块 pom，返回 {artifactId, version, shaded}（version 可继承父 pom）。"""
    pom = os.path.join(module_dir, "pom.xml")
    if not os.path.exists(pom):
        raise ValueError("模块缺 pom.xml：%s" % pom)
    root = ET.parse(pom).getroot()
    artifact = _text(root, "artifactId")
    version = _text(root, "version")
    parent = root.find(NS + "parent") if root.find(NS + "parent") is not None else root.find("parent")
    parent_artifact = _text(parent, "artifactId")
    if not version and parent is not None:
        version = _text(parent, "version")
        # 父 pom 就在仓库里时，优先用父 pom 自己声明的 version（避免 parent 段写旧值）
        parent_rel = _text(parent, "relativePath")
        parent_pom = os.path.join(module_dir, parent_rel or "../pom.xml")
        if os.path.exists(parent_pom):
            pv = _text(ET.parse(parent_pom).getroot(), "version")
            if pv:
                version = pv
    if not artifact or not version:
        raise ValueError("pom 缺 artifactId/version：%s" % pom)

    raw = io.open(pom, encoding="utf-8").read()
    shaded = "maven-shade-plugin" in raw
    final_name = _text(root, "build")
    # finalName 需要单独取（build 子节点）
    build = root.find(NS + "build") if root.find(NS + "build") is not None else root.find("build")
    final_name = _text(build, "finalName") if build is not None else None
    return {"artifact": artifact, "version": version, "shaded": shaded,
            "finalName": final_name, "parent": parent_artifact}


def expected_jar(info):
    if info["finalName"]:
        return "%s.jar" % info["finalName"]
    base = "%s-%s" % (info["artifact"], info["version"])
    return "%s-shaded.jar" % base if info["shaded"] else "%s.jar" % base


def parse_compose(path):
    """返回 [{service, module_dir, jar}]，只挑 java -jar 的服务。"""
    if not os.path.exists(path):
        raise ValueError("找不到 compose 文件：%s" % path)
    text = io.open(path, encoding="utf-8").read()
    services = []
    cur = None
    for ln in text.split("\n"):
        m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", ln)
        if m:
            if cur:
                services.append(cur)
            cur = {"service": m.group(1), "volumes": [], "command": ""}
            continue
        if cur is None:
            continue
        mv = re.search(r"^\s+-\s+\./([^:]+)/target:/app", ln)
        if mv:
            cur["volumes"].append(mv.group(1))
        mc = re.search(r"command:\s*(.+)$", ln)
        if mc:
            cur["command"] = mc.group(1).strip()
    if cur:
        services.append(cur)

    out = []
    for s in services:
        mj = re.search(r"java\s+-jar\s+([^\s\"']+\.jar)", s["command"])
        if not mj:
            continue
        if not s["volumes"]:
            raise ValueError("服务 %s 用 java -jar 但没有 ./<模块>/target 挂载，无法确定产物来源"
                             % s["service"])
        out.append({"service": s["service"], "module_dir": s["volumes"][0], "jar": mj.group(1)})
    return out


def check(compose_path, root_dir):
    """返回 (问题列表, 检查条目列表)。"""
    problems = []
    rows = []
    for item in parse_compose(compose_path):
        module = os.path.join(root_dir, item["module_dir"])
        try:
            info = read_pom(module)
        except (ValueError, ET.ParseError) as e:
            problems.append("%s: %s" % (item["service"], e))
            continue
        want = expected_jar(info)
        got = item["jar"]
        ok = (want == got)
        rows.append((item["service"], item["module_dir"], got, want, ok))
        if not ok:
            problems.append("%s: compose 写的是 %s，pom 实际产出 %s"
                            "（改版本号/finalName/shade 配置后忘了同步 compose？）"
                            % (item["service"], got, want))
    if not rows and not problems:
        problems.append("compose 里没有找到任何 java -jar 服务 —— 解析规则可能已失效")
    return problems, rows


def self_test():
    """合成夹具自证：一致放行、版本/后缀/缺目录三种偏差都要报出。"""
    ok = True
    tmp = tempfile.mkdtemp(prefix="compose-artifacts-")

    def make_module(name, artifact, version=None, shaded=False, parent_version="1.0.0"):
        d = os.path.join(tmp, name)
        os.makedirs(os.path.join(d, "target"), exist_ok=True)
        ver = "<version>%s</version>" % version if version else ""
        shade = ("<build><plugins><plugin><artifactId>maven-shade-plugin</artifactId>"
                 "</plugin></plugins></build>" if shaded else "")
        io.open(os.path.join(d, "pom.xml"), "w", encoding="utf-8").write(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<parent><artifactId>p</artifactId><version>%s</version>'
            '<relativePath>../pom.xml</relativePath></parent>'
            '<artifactId>%s</artifactId>%s%s</project>'
            % (parent_version, artifact, ver, shade))
        return d

    io.open(os.path.join(tmp, "pom.xml"), "w", encoding="utf-8").write(
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        '<artifactId>p</artifactId><version>1.0.0</version></project>')

    make_module("mod-a", "demo-a", shaded=True)          # 继承 1.0.0 + shade
    make_module("mod-b", "demo-b", version="2.0.0")      # 显式 2.0.0

    compose = os.path.join(tmp, "docker-compose.yml")

    def write_compose(jar_a, jar_b, module_b="mod-b"):
        io.open(compose, "w", encoding="utf-8").write(
            "services:\n"
            "  a:\n"
            "    image: x\n"
            "    volumes:\n"
            "      - ./mod-a/target:/app\n"
            '    command: java -jar %s\n'
            "  b:\n"
            "    image: x\n"
            "    volumes:\n"
            "      - ./%s/target:/app\n"
            '    command: java -jar %s\n'
            "  web:\n"
            "    image: x\n"
            '    command: sh -c "npm run dev"\n' % (jar_a, module_b, jar_b))

    # ① 全部一致 => 无问题
    write_compose("demo-a-1.0.0-shaded.jar", "demo-b-2.0.0.jar")
    problems, rows = check(compose, tmp)
    if not problems and len(rows) == 2:
        print("  [PASS] 一致时放行（2 个服务，跳过非 java 服务）")
    else:
        print("  [FAIL] 一致时误报：%s" % problems)
        ok = False

    # ② 版本没同步（compose 写 0.1.0，pom 是 2.0.0）=> 必须报出
    write_compose("demo-a-1.0.0-shaded.jar", "demo-b-0.1.0.jar")
    problems, _ = check(compose, tmp)
    if any("demo-b" in p and "0.1.0" in p for p in problems):
        print("  [PASS] 版本漂移被报出")
    else:
        print("  [FAIL] 版本漂移没报出：%s" % problems)
        ok = False

    # ③ 漏了 -shaded 后缀 => 必须报出
    write_compose("demo-a-1.0.0.jar", "demo-b-2.0.0.jar")
    problems, _ = check(compose, tmp)
    if any("shaded" in p for p in problems):
        print("  [PASS] shade 后缀漏写被报出")
    else:
        print("  [FAIL] shade 后缀漏写没报出：%s" % problems)
        ok = False

    # ④ 模块目录不存在 => 必须报出
    write_compose("demo-a-1.0.0-shaded.jar", "demo-b-2.0.0.jar", module_b="mod-missing")
    problems, _ = check(compose, tmp)
    if any("mod-missing" in p or "pom.xml" in p for p in problems):
        print("  [PASS] 模块缺失被报出")
    else:
        print("  [FAIL] 模块缺失没报出：%s" % problems)
        ok = False

    import shutil
    shutil.rmtree(tmp, ignore_errors=True)
    return 0 if ok else 1


def main():
    if "--self-test" in sys.argv:
        print("== compose 产物一致性自测 ==")
        return self_test()

    compose = DEFAULT_COMPOSE
    root_dir = ROOT
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--compose" and i + 1 < len(args):
            compose = args[i + 1]; i += 2
        elif args[i] == "--root" and i + 1 < len(args):
            root_dir = args[i + 1]; i += 2
        else:
            i += 1

    try:
        problems, rows = check(compose, root_dir)
    except ValueError as e:
        print("")
        print("  [FAIL] 无法校验：%s" % e)
        return 1

    print("  compose：%s" % os.path.relpath(compose, root_dir))
    for service, module, got, want, ok in rows:
        mark = "OK  " if ok else "FAIL"
        print("    [%s] %-14s %s" % (mark, service, got))
        if not ok:
            print("           期望 %s（来自 %s/pom.xml）" % (want, module))

    if problems:
        print("")
        for p in problems:
            print("  [FAIL] %s" % p)
        print("")
        print("  compose 里写死的 jar 名与 pom 产物不一致 —— 容器会秒退")
        print("  （Unable to access jarfile），而 CI 此前对这种脱节是绿的。")
        return 1

    print("  compose 产物一致性：通过（%d 个 java 服务）" % len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
