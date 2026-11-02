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

五档口径（可复算，优先级从高到低）
----------------------------------
0a. `端点/框架方法`：方法上方紧邻 `@GetMapping`/`@RequestMapping`/`@Bean`/`@Query`/
    `@PrePersist`/`@Override`/`@Scheduled` 等注解——**它根本不是访问器**。
    2026-11-02 实测：`geofence/RestrictionController#getRestrictionStatus` 带着 `@GetMapping`
    被旧版列进"最暗一档"，照着清单动手会**删掉一个对外端点**。这一档存在本身就是教训。
0b. `响应暴露面`：声明类从 `@RestController`/`@Controller` 出发、沿"谁引用了谁"走
    `RESPONSE_HOPS` 跳可达 ⇒ 它会随响应序列化，Jackson 按 getter 定 JSON 字段名。
    这一档把上面那段"过近似"从**嘴上登记**变成**机器判据**。
1. `契约候选`：属性名（去掉 get/is 前缀，camel 与 kebab 两种写法）出现在
   `gcs-web/src`、`docs/`、`deploy/` 里 ⇒ 前端或文档在消费它，**不许当死码删**。
2. `只有测试在用`：main 里零调用点，但 test 里有 ⇒ 是"测试在钉它"，
   删之前要先问是谁在钉（§10.2 那 6 组常量的教训）。
3. `零外引用候选`：以上都不命中 ⇒ 最暗的一档。加了 0a/0b 之后它**仍然不是零假阳性**：
   可达性用文本 token 近似（不是编译级调用图），跨模块反射/`Map` 手工装配仍可能漏判，
   所以必须人工复核后才动手。
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
# 名为 getXxx()/isXxx() 但**不是访问器**的方法：路由处理器、Bean 工厂、Spring Data 查询、
# JPA 生命周期回调、覆写契约。2026-11-02 实测踩过——`RestrictionController#getRestrictionStatus`
# 带着 @GetMapping 被列进"最暗一档"，照着删就是删一个端点。
NON_ACCESSOR_ANN = re.compile(
    r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping|'
    r'Bean|Query|Modifying|PrePersist|PreUpdate|PostLoad|Override|Scheduled|ExceptionHandler|'
    r'Operation|ApiResponse)\b')
CONTROLLER_ANN = re.compile(r'@(RestController|Controller)\b')
TYPE_TOKEN = re.compile(r'\b[A-Z]\w*\b')
RESPONSE_HOPS = 3


def strip_noise(text):
    """剥掉块注释、行注释与字符串字面量——注释里的方法名不算调用点。"""
    text = BLOCK_COMMENT.sub(" ", text)
    text = LINE_COMMENT.sub(" ", text)
    return STRING_LIT.sub('""', text)


def annotations_above(text, match_start):
    """方法声明之前紧邻的注解行（允许中间夹注释/空行）。"""
    lines = text[:match_start].split("\n")
    out = []
    for ln in reversed(lines):
        s = ln.strip()
        if s.startswith("@"):
            out.append(s)
        elif s == "" or s.startswith(("*", "/*", "//")):
            continue
        else:
            break
    return "\n".join(out)


def declared_type(path):
    return os.path.splitext(os.path.basename(path))[0]


def response_exposed_types(main_clean):
    """从 @RestController/@Controller 出发，沿"谁引用了谁"走 RESPONSE_HOPS 跳，
    收集**可能随响应出网**的类型名。Jackson 按 getter 决定 JSON 字段名，
    所以这些类里的 getter 即使全仓零调用点也不能算死码——这一档把 §12.3 的
    "过近似"从嘴上登记变成机器判据。

    2026-11-02 收紧（§14）：边只认**仓内真实声明过的类型名**。
    旧版把"任何大写 token"都当边 ⇒ 泛型参数 `T`、常量名、外部类名都会成为假边（过宽），
    而 `TYPE_TOKEN` 原要求 ≥2 字符 ⇒ **单字母类名反而漏判**（过窄）。两个方向一起修：
    先取大写 token，再与仓内声明过的类型名取交集——交集既去掉了噪音，也把 `A` 这类
    真实但短的名字放了进来。"""
    decl = {declared_type(p) for p in main_clean}
    # 同一简单类名可能在多个包里各声明一次（实测：cloud-backend 的
    # mission.FormationService 与 show.FormationService）。旧版按名字建单值表，
    # 后写覆盖先写 ⇒ 被覆盖那个文件的边整片丢失（2026-11-02 字节码对账抓到 6 个类型）。
    # 文本图只能拿到简单名，无法区分包，故对同名文件取**并集**（过近似，安全方向）。
    by_type = {}
    for p in main_clean:
        by_type.setdefault(declared_type(p), []).append(p)

    def edges(text):
        return {t for t in TYPE_TOKEN.findall(text) if t in decl}

    ctrl_files = [p for p, t in main_clean.items() if CONTROLLER_ANN.search(t)]
    reachable = set()
    frontier = set()
    for p in ctrl_files:
        frontier.add(declared_type(p))
        frontier.update(edges(main_clean[p]))
    for _ in range(RESPONSE_HOPS):
        reachable |= frontier
        nxt = set()
        for t in frontier:
            for p in by_type.get(t, ()):
                if p not in ctrl_files:
                    nxt.update(edges(main_clean[p]))
        frontier = nxt - reachable
        if not frontier:
            break
    return reachable, len(ctrl_files)


def bytecode_edges(root):
    """解析 `*/target/classes/**/*.class` 的常量池，取类型引用边（只取简单名，内类折叠到外类）。
    返回 {类名: {引用的类型名}}；没有任何 class 产物时返回空 dict（调用方必须显式报"不可用"，
    不许把"没产物"当成"没有引用"）。

    边有两个来源：`CONSTANT_Class` 项，以及 Utf8 常量里的描述符（`Lpkg/Foo;`）。**只取前者会
    严重低估**——字段/参数/返回值/上界/注解的类型大多只出现在描述符里，不会各自生成 Class 常量。
    2026-11-02 实测：只取 Class 常量时闭包 205 个类，补上描述符后 584 个（源码图 601 个）。"""
    out = {}
    base = root.rstrip("/\\") + os.sep
    for mod in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        classes = os.path.join(base, mod, "target", "classes")
        if not os.path.isdir(classes):
            continue
        for dp, _, fns in os.walk(classes):
            for fn in fns:
                if not fn.endswith(".class"):
                    continue
                try:
                    blob = open(os.path.join(dp, fn), "rb").read()
                except OSError:
                    continue
                refs = _constant_pool_refs(blob)
                key = os.path.relpath(os.path.join(dp, fn), classes)[:-6].replace(os.sep, ".")
                leaf = key.split(".")[-1].split("$")[0]
                out.setdefault(leaf, set()).update(refs)
    return out


DESCRIPTOR_REF = re.compile(r'L([\w$/]+);')


def _constant_pool_refs(blob):
    """粗解析 class 文件常量池，收集类型引用（取简单名、内类折叠到外类）。
    来源：`CONSTANT_Class`(tag 7) + Utf8 里的 `L…;` 描述符。只读常量池，不解析方法体。"""
    names = set()
    try:
        if blob[:4] != b"\xca\xfe\xba\xbe":
            return names
        count = int.from_bytes(blob[8:10], "big")
        i = 10
        utf8 = {}
        class_refs = []
        idx = 1
        while idx < count:
            tag = blob[i]
            if tag == 1:                                  # Utf8
                ln = int.from_bytes(blob[i + 1:i + 3], "big")
                utf8[idx] = blob[i + 3:i + 3 + ln].decode("utf-8", "replace")
                i += 3 + ln
            elif tag == 7:                               # Class → name_index
                class_refs.append(int.from_bytes(blob[i + 1:i + 3], "big"))
                i += 3
            elif tag in (15,):                           # MethodHandle
                i += 4
            elif tag in (8, 16, 19, 20):                 # String/Module/Package/Record → 2B
                                                         # （tag 8 漏了会遇字符串字面量即 break）
                i += 3
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):   # 4B（Integer/Float/Fieldref/…）
                i += 5
            elif tag in (5, 6):                          # Long/Double 占两个槽
                i += 9
                idx += 1
            else:
                break
            idx += 1
        for ni in class_refs:
            raw = utf8.get(ni, "")
            if raw:
                leaf = raw.split(".")[-1].split("/")[-1].split("$")[0]
                if leaf and not leaf.startswith("["):
                    names.add(leaf.replace("[", ""))
        for s in utf8.values():
            for mo in DESCRIPTOR_REF.finditer(s):
                leaf = mo.group(1).split("/")[-1].split("$")[0]
                if leaf:
                    names.add(leaf)
    except (IndexError, ValueError):
        return names
    return names


def _closure(seed, graph, hops):
    reach, frontier = set(), set(seed)
    for _ in range(hops):
        reach |= frontier
        nxt = set()
        for t in frontier:
            nxt.update(graph.get(t, set()))
        frontier = nxt - reach
        if not frontier:
            break
    return reach




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


def scan(root, recon_out=None):
    """返回 (六档计数 dict, 第 3 档名单 list[str])。recon_out 传 dict 时写入字节码对账结果。"""
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
    counts = {"端点/框架方法": 0, "响应暴露面": 0, "契约候选": 0,
              "只有测试在用": 0, "零外引用候选": 0, "有调用点": 0}
    exposed, n_ctrl = response_exposed_types(main_clean)
    if recon_out is not None:
        bc = bytecode_edges(root)
        recon_out["classes"] = len(bc)
        if bc:
            src_names = {declared_type(q) for q in main_clean}
            # 只统计字节码里**也出现过**的仓内类型；外部类（String/Map/…）不参与对账
            graph = {t: {r for r in refs if r in src_names} for t, refs in bc.items()}
            # 与源码图同口径：以控制器的直接引用为种子，再走 RESPONSE_HOPS 跳
            seed = set()
            for q, text in main_clean.items():
                if CONTROLLER_ANN.search(text):
                    seed |= graph.get(declared_type(q), set())
            bc_reach = _closure(seed, graph, RESPONSE_HOPS)
            recon_out["classes"] = len(graph)
            recon_out["source"] = len(exposed)
            recon_out["bytecode"] = len(bc_reach)
            recon_out["bc_only"] = sorted(bc_reach - exposed)
            recon_out["src_only"] = sorted(exposed - bc_reach)

    dead = []
    for path, text in sorted(main_clean.items()):
        for mo in GETTER_DECL.finditer(text):
            name = mo.group(1)
            if NON_ACCESSOR_ANN.search(annotations_above(text, mo.start())):
                counts["端点/框架方法"] += 1
                continue
            body = re.compile(r'\b%s\s*\(' % re.escape(name))
            if any(body.search(t) for q, t in main_clean.items() if q != path):
                counts["有调用点"] += 1
                continue
            if declared_type(path) in exposed:
                counts["响应暴露面"] += 1
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
    ap.add_argument("--with-bytecode", action="store_true",
                    help="本地用：解析 */target/classes 的常量池，与源码可达性图对账（CI 无 JDK，不跑）")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    recon = {}
    counts, dead = scan(args.root, recon_out=recon if args.with_bytecode else None)
    total_getters = sum(counts.values())
    print("[report-only] public getter 零外引用清单（不判红；第 3 档仍是文本级近似，"
          "可达性不是编译级调用图——审计 §11.3/§12.3/§13）")
    print("  扫描 %d 个 public 无参 getter：有调用点 %d / 端点·框架方法 %d / 响应暴露面 %d / "
          "契约候选 %d / 只有测试在用 %d / 零外引用候选 %d"
          % (total_getters, counts["有调用点"], counts["端点/框架方法"], counts["响应暴露面"],
             counts["契约候选"], counts["只有测试在用"], counts["零外引用候选"]))
    if dead:
        print("  最暗一档（人工复核后才动手，前 %d 个）：" % args.top)
        for item in sorted(dead)[:args.top]:
            print("    - " + item)
        if len(dead) > args.top:
            print("    …另 %d 个（全量用 --top 999）" % (len(dead) - args.top))
    else:
        print("  最暗一档：0 个")
    if args.with_bytecode:
        if recon.get("classes"):
            print("  字节码对账：仓内类型 %d 个有 class 产物；暴露面 源码图 %d / 字节码闭包 %d；"
                  "字节码看到而源码图漏掉 %d 个%s；源码多出 %d 个（多为未使用 import 等文本假边）"
                  % (recon["classes"], recon["source"], recon["bytecode"],
                     len(recon["bc_only"]),
                     ("（%s…）" % ", ".join(recon["bc_only"][:6])) if recon["bc_only"] else "",
                     len(recon["src_only"])))
        else:
            print("  字节码对账：**UNVERIFIED——没找到 */target/classes，本轮只有源码图**"
                  "（不把「没产物」写成「没有暴露面」；CI 的 repo-gates job 没有 JDK，故默认不开此开关）")
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
        def fixture(extra_main="", extra_test="", extra_frontend="", extra_docs="",
                    extra_ctrl=""):
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
            if extra_ctrl:
                _write(os.path.join(root, "m", "src", "main", "java", "demo", "Ctrl.java"),
                       "package demo;\n@RestController\npublic class Ctrl {\n"
                       + extra_ctrl + "}\n")
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
        # 0a/0b 两档：端点处理器不是访问器；响应面上的类不进最暗档
        # （2026-11-02 实测：RestrictionController#getRestrictionStatus 被旧版列进第 3 档）
        r3 = fixture(extra_ctrl="  @GetMapping(\"/status\")\n"
                                "  public DemoDto getRestrictionStatus() { return new DemoDto(); }\n")
        _write(os.path.join(r3, "m", "src", "main", "java", "demo", "DemoDto.java"),
               "package demo;\npublic class DemoDto {\n"
               "  public int getOnlyOnWire() { return 7; }\n}\n")
        c3, d3 = scan(r3)
        n3 = set(x.split("#")[1] for x in d3)
        results["端点处理器不算访问器（第 0a 档）"] = c3["端点/框架方法"] == 1 and \
            "getRestrictionStatus" not in n3
        results["随响应出网的类不进最暗档（第 0b 档）"] = c3["响应暴露面"] == 1 and \
            "getOnlyOnWire" not in n3
        # 反证：控制器不该把全世界吞进暴露面——另一个与控制器无关的类仍是孤儿
        _write(os.path.join(r3, "m", "src", "main", "java", "demo", "Z.java"),
               "package demo;\npublic class Z {\n  public int getStillOrphan() { return 0; }\n}\n")
        _c4, d4 = scan(r3)
        results["暴露面没有把无关类一起吞掉"] = any("getStillOrphan" in x for x in d4)
        # 恒退 0：有候选也不红（这是本脚本存在的核心承诺）
        rc = main(["--root", r])
        # §14：旧版 TYPE_TOKEN 要求 ≥2 字符 ⇒ 单字母类名漏判；现在按"仓内声明过的类型名"取交集
        ctrl_body = '  @GetMapping("/api/x")\n  public A getWireOnly() { return null; }\n'
        r5 = fixture(extra_ctrl=ctrl_body)
        c5, d5 = scan(r5)
        n5 = set(x.split("#")[1] for x in d5)
        results["单字母类名不再被漏判（A 出现在控制器里 ⇒ 其 getter 归响应暴露面）"] = \
            c5["响应暴露面"] >= 3 and "getOrphan" not in n5 and "isFlagged" not in n5
        # --with-bytecode 在无 class 产物时必须显式 UNVERIFIED，不许静默当成"没有暴露面"
        import contextlib
        from io import StringIO
        buf = StringIO()
        with contextlib.redirect_stdout(buf):
            rc_b = main(["--root", r, "--with-bytecode"])
        out = buf.getvalue()
        results["无 class 产物时报 UNVERIFIED 而非静默"] = rc_b == 0 and "UNVERIFIED" in out
        # 描述符里的类型引用也算边：字段/签名类型常只出现在 Utf8 描述符里，不算会低估闭包。
        # 特意把 CONSTANT_String(tag 8) 放在前面：解析器漏掉 tag 8 会在这里 break，描述符就丢了。
        blob = (b"\xca\xfe\xba\xbe" + b"\x00\x00\x00\x34" + (3).to_bytes(2, "big")
                + b"\x08" + (2).to_bytes(2, "big")
                + b"\x01" + (10).to_bytes(2, "big") + b"Ldemo/Foo;")
        refs = _constant_pool_refs(blob)
        results["描述符里的类型引用也算字节码边（含 tag 8 不提前 break）"] = refs == {"Foo"}
        # 同名类（不同包）：旧版单值表后写覆盖先写 ⇒ 其中一个文件的边整片丢失。
        # 两个 DTO 各只被一个同名类引用，取并集才能让两条 getter 都归暴露面（与先后顺序无关）。
        r6 = fixture()
        for pkg, dto, decl_line in (("demo", "DtoOne", "getOnlyViaOne"),
                                    ("demo/sub", "DtoTwo", "getOnlyViaTwo")):
            _write(os.path.join(r6, "m", "src", "main", "java", *pkg.split("/"), "Same.java"),
                   "package %s;\npublic class Same {\n  %s d;\n}\n" % (pkg.replace("/", "."), dto))
            _write(os.path.join(r6, "m", "src", "main", "java", *pkg.split("/"), dto + ".java"),
                   "package %s;\npublic class %s {\n  public int %s() { return 1; }\n}\n"
                   % (pkg.replace("/", "."), dto, decl_line))
        _write(os.path.join(r6, "m", "src", "main", "java", "demo", "C2.java"),
               "package demo;\n@RestController\npublic class C2 {\n"
               "  @GetMapping(\"/same\")\n  public Same getSame() { return null; }\n}\n")
        _c6, d6 = scan(r6)
        n6 = set(x.split("#")[1] for x in d6)
        results["同名类（不同包）的边取并集，不因覆盖丢失"] = \
            "getOnlyViaOne" not in n6 and "getOnlyViaTwo" not in n6
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
