#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""report-only 清单：public getter 里"声明文件外零引用"的候选，**只提示、不判红**。

为什么不做成门禁（owner 裁决，审计 §12.3）
------------------------------------------
同一条判据（"这个 getter 在声明文件外没人调"）已经手工跑过两批：§10.3 收了
`CommAdaptConfig`/`DockProperties`，§11.3 全仓重扫得 224 个零外引用 getter，四类定性后
删 12 留 2。但**判据本身是过近似的**：Jackson 按 getter 决定 JSON 字段名，所以"代码里
没人调"不等于"没人消费"——§11.3 里 174 个"随响应出网"是刻意宽的估计，真要收紧需要
编译级调用图。文本级启发式在这里已经到能力边界（和 §6 里 `check-dead-wiring.py` 暂缓
是同一个原因）。

所以这里**故意不做成会红的门禁**：误报的代价是让九道仓门变得"经常红但没人理"，那比
没有这条更糟。本脚本恒退 0，只在 CI 里打印三档计数和最暗的一档名单，让"新写出来的
零引用访问器"至少**看得见**。将来若要收严，先把判据换成编译级，再谈 `--strict`。

三档口径（可复算，优先级从高到低）
----------------------------------
1. `契约候选`：属性名（去掉 get/is 前缀，camel 与 kebab 两种写法）出现在
   `gcs-web/src`、`docs/`、`deploy/` 里 ⇒ 前端或文档在消费它，**不许当死码删**。
2. `只有测试在用`：main 里零调用点，但 test 里有 ⇒ 是"测试在钉它"，
   删之前要先问是谁在钉（§10.2 那 6 组常量的教训）。
3. `零外引用候选`：main/test 都零调用点，且属性名没见于契约面 ⇒ 最暗的一档，
   但**含 Jackson 序列化的假阳性**，必须人工复核后才能动手。
   （复核时顺手把 javadoc/注释指向的"文档证据"当真证据的风险：§11.4 抓到过两例假阳性，
   一次是 alertmanager.yml 里的 `receiver:` 撞名，一次是 SVG 里的 `www.w3.org`。）

用法：
    python scripts/list-unreferenced-getters.py [--root .] [--top N] [--strict]
    python scripts/list-unreferenced-getters.py --self-test
退出码：**恒 0**（report-only）。`--strict` 只给本地实验用——命中第 3 档即退 1，
CI 不许用它。
"""
import io
import os
import re
import shutil
import sys
import argparse

CONTRACT_DIRS = ("gcs-web/src", "docs", "deploy")
JAVA_VALUE_READ = re.compile(r'"\$\{([A-Za-z0-9_.\-]+)(?::[^}]*)?\}"')
GETTER_DECL = re.compile(
    r'^\s*public\s+(?!class\b|interface\b|enum\b)(?:static\s+|final\s+|synchronized\s+)*'
    r'[\w<>,\[\].?\s]+?\s+((?:get|is)[A-Z]\w*)\s*\(\s*\)', re.M)
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'//[^\n]*')
STRING_LIT = re.compile(r'"(?:[^"\\\n]|\\.)*"')


def strip_noise(text):
    """剥掉块注释、行注释与字符串字面量——注释里的方法名不算调用点。"""
    text = BLOCK_COMMENT.sub(" ", text)
    text = LINE_COMMENT.sub(" ", text)
    return STRING_LIT.sub('""', text)


def java_files(root, sub):
    base = os.path.join(root, "")
    out = []
    for mod in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        d = os.path.join(base, mod, "src", sub, "java")
        if not os.path.isdir(d):
            continue
        for dp, _, fns in os.walk(d):
            for fn in fns:
                if fn.endswith(".java"):
                    out.append(os.path.join(dp, fn))
    return out


def contract_texts(root):
    """契约面：前端源码 + 文档 + 部署清单的全文（字符串匹配口径，与 §11.3 一致）。"""
    blobs = []
    for rel in CONTRACT_DIRS:
        d = os.path.join(root, rel)
        if not os.path.isdir(d):
            continue
        for dp, _, fns in os.walk(d):
            if os.sep + "node_modules" in dp:
                continue
            for fn in fns:
                if fn.endswith((".ts", ".tsx", ".js", ".jsx", ".md", ".yml", ".yaml",
                                ".properties", ".json")):
                    try:
                        blobs.append(io.open(os.path.join(dp, fn), encoding="utf-8",
                                             errors="replace").read())
                    except OSError:
                        pass
    return "\n".join(blobs)


def camel_to_kebab(name):
    return re.sub(r"(?<!^)(?=[A-Z])", "-", name).lower()


def scan(root):
    """返回 (三档计数 dict, 第 3 档名单 list[(模块, 文件, 方法)])."""
    main_files = java_files(root, "main")
    test_files = java_files(root, "test")
    main_clean = {}
    for p in main_files:
        main_clean[p] = strip_noise(io.open(p, encoding="utf-8", errors="replace").read())
    test_clean = {}
    for p in test_files:
        test_clean[p] = strip_noise(io.open(p, encoding="utf-8", errors="replace").read())
    # @Value 读取的键名也算契约面（profile/properties 里真的会设它）
    contract = contract_texts(root)
    value_keys = set()
    for text in main_clean.values():
        for mo in JAVA_VALUE_READ.finditer(text):
            value_keys.add(mo.group(1))
    counts = {"契约候选": 0, "只有测试在用": 0, "零外引用候选": 0, "有调用点": 0}
    dead = []
    for path, text in sorted(main_clean.items()):
        for mo in GETTER_DECL.finditer(text):
            name = mo.group(1)
            body = re.compile(r'\b%s\s*\(' % re.escape(name))
            if any(body.search(t) for q, t in main_clean.items() if q != path):
                counts["有调用点"] += 1
                continue
            prop = re.sub(r"^(get|is)", "", name)
            prop = prop[0].lower() + prop[1:] if prop else prop
            in_contract = (prop and (prop in contract or camel_to_kebab(prop) in contract)) \
                or any(k.endswith("." + prop) or k.endswith("." + camel_to_kebab(prop))
                       for k in value_keys)
            if in_contract:
                counts["契约候选"] += 1
                continue
            if any(body.search(t) for t in test_clean.values()):
                counts["只有测试在用"] += 1
                continue
            counts["零外引用候选"] += 1
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            dead.append(rel + "#" + name)
    return counts, dead


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--strict", action="store_true",
                    help="本地实验用：命中第 3 档即退 1。CI 不许用（本脚本的设计是不判红）。")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    counts, dead = scan(args.root)
    total_getters = sum(counts.values())
    print("[report-only] public getter 零外引用清单（不判红；判据是过近似，"
          "第 3 档含 Jackson 序列化假阳性——审计 §11.3/§12.3）")
    print("  扫描 %d 个 public 无参 getter：有调用点 %d / 契约候选 %d / 只有测试在用 %d / "
          "零外引用候选 %d" % (total_getters, counts["有调用点"], counts["契约候选"],
                               counts["只有测试在用"], counts["零外引用候选"]))
    if dead:
        print("  最暗一档（人工复核后才动手，前 %d 个）：" % args.top)
        for item in sorted(dead)[:args.top]:
            print("    - " + item)
        if len(dead) > args.top:
            print("    …另 %d 个（全量用 --top 999）" % (len(dead) - args.top))
    else:
        print("  最暗一档：0 个")
    if args.strict and dead:
        print("[--strict] 命中 %d 个候选 ⇒ 退 1（本地实验口径）" % len(dead))
        return 1
    return 0


def _write(path, text):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)


def self_test():
    """合成夹具：验证四档归类、注释不算调用点、以及"恒退 0"这条设计本身。"""
    import tempfile
    base = tempfile.mkdtemp(prefix="getters-")
    results = {}
    try:
        def fixture(extra_main="", extra_test="", extra_frontend="", extra_docs=""):
            root = os.path.join(base, "r%d" % len(os.listdir(base)))
            _write(os.path.join(root, "m", "src", "main", "java", "demo", "A.java"),
                   "package demo;\npublic class A {\n"
                   "  public String getUsedElsewhere() { return \"x\"; }\n"
                   "  public int getOrphan() { return 1; }\n"
                   "  public boolean isFlagged() { return false; }\n"
                   "  public String getTestOnly() { return \"t\"; }\n"
                   "  public String getContracted() { return \"c\"; }\n"
                   + extra_main + "}\n")
            _write(os.path.join(root, "m", "src", "main", "java", "demo", "B.java"),
                   "package demo;\npublic class B {\n"
                   "  void f(A a) { a.getUsedElsewhere(); }\n}\n")
            if extra_test:
                _write(os.path.join(root, "m", "src", "test", "java", "demo", "AT.java"),
                       "package demo;\npublic class AT {\n" + extra_test + "}\n")
            if extra_frontend:
                _write(os.path.join(root, "gcs-web", "src", "api.ts"), extra_frontend)
            if extra_docs:
                _write(os.path.join(root, "docs", "d.md"), extra_docs)
            return root

        r = fixture(extra_test="  void t() { A a = new A(); a.getTestOnly(); }\n",
                    extra_frontend="export const x = { contracted: 1 };\n")
        counts, dead = scan(r)
        names = set(d.split("#")[1] for d in dead)
        results["别处真有调用的 getter 不算候选"] = counts["有调用点"] == 1 and \
            "getUsedElsewhere" not in names
        results["零外引用候选恰好等于两个真孤儿"] = names == {"getOrphan", "isFlagged"}
        results["只有测试在用单独成档"] = counts["只有测试在用"] == 1 and \
            "getTestOnly" not in names
        results["前端契约名不算死码"] = counts["契约候选"] == 1 and \
            "getContracted" not in names
        # 注释里的方法名不能被当成调用点
        r2 = fixture(extra_main="  // getOrphan() 只在注释里出现\n"
                               "  /* 也提到 getOrphan() */\n"
                               '  String s = "getOrphan()";\n')
        _c, d2 = scan(r2)
        results["注释与字符串不算调用点"] = "getOrphan" in " ".join(d2)
        # 恒退 0：有候选也不红（这是本脚本存在的核心承诺）
        rc = main(["--root", r])
        results["有候选仍退 0（report-only）"] = rc == 0
        rc2 = main(["--root", r, "--strict"])
        results["--strict 才退 1"] = rc2 == 1
    finally:
        shutil.rmtree(base, ignore_errors=True)
    ok = 0
    for k, v in results.items():
        print(("  [%s] " % ("OK" if v else "FAIL")) + k)
        ok += 1 if v else 0
    print("list-unreferenced-getters 自测：%d/%d 通过" % (ok, len(results)))
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
