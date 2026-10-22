#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：前端 api.js 调用的每个端点，后端都必须真的有（方法 + 路径都算）。

为什么需要
----------
2026-10-21 实测：`gcs-web/src/api.js` 里有 12 条被调用的路径，**后端压根没有**：

    POST /api/v1/formation/create        （后端是 POST /api/v1/formation）
    POST /api/v1/spray/task              （后端是 POST /api/v1/spray）
    GET  /api/v1/spray/status/{sysid}    （后端只按**任务 id** 查，前端手里没有任务 id）
    POST /api/v1/spray/gripper           （后端的夹爪命令挂在**配送序列**的控制上）
    GET  /api/v1/delivery/sequence/{sysid}
    POST /api/v1/hardware/physics-model  （后端无此能力）
    GET  /api/v1/vision/thermal/sources  （后端只有 thermal 任务，没有"源"的概念）
    GET  /api/v1/env/status              （后端只有 POST 覆盖 + /env-alerts）
    GET  /api/v1/env/alerts              （后端是 /api/v1/env-alerts）
    GET  /api/v1/link-sim/profiles|status + POST /api/v1/link-sim/damage

危害不是"报错"这么轻：前端的轮询普遍写成 `.catch(() => null)`，
**404 被静默吞掉**，面板永远空 —— 界面上看不出任何异常，只有对着代码才知道功能是假的。
这正是本仓库主线一直在清的「声明≠行为」，只是这次声明在前端。

口径
----
  1. 后端端点 = `@RestController` 类里的**方法级** `@Get/Post/Put/Delete/PatchMapping`，
     路径 = 类级 `@RequestMapping` + 方法级映射（与 count-rest-endpoints.py 同源口径）。
  2. 前端路径 = `gcs-web/src/api.js` 里 `jsonFetch(...)` 的**实参**：
     模板字符串里的 `${BASE}` 等 base 常量按 api.js 里的定义展开，
     `${xxx}` 展开成路径参数 `{xxx}`，查询串去掉，HTTP 方法取同一处调用里的
     `method: 'POST'`（没有就是 GET）。
  3. 路径按段比对：任一侧是路径参数就算匹配；方法必须相同。
  4. **已知缺口**登记在 `KNOWN_GAPS` 里：这些前端调用后端确实还没有，逐条写明原因。
     缺口是**债务登记**，不是豁免：
       - 新增的未登记缺口 ⇒ 门禁红（这才是它拦得住漂移的原因）
       - 已登记的缺口被修好 ⇒ 门禁也红（提醒你把登记删掉，别让债务条目烂在清单里）

用法：
    python scripts/check-frontend-endpoints.py
    python scripts/check-frontend-endpoints.py --self-test
退出码：0 通过；1 有未登记缺口、登记已过期，或自测失败。
"""
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_SRC = os.path.join(ROOT, "cloud-backend", "src", "main", "java")
API_JS = os.path.join(ROOT, "gcs-web", "src", "api.js")

METHOD_ANNO = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b")
CLASS_ANNO = re.compile(r"@RestController\b")
QUOTED = re.compile(r"[\"']([^\"']*)[\"']")

# 已知缺口登记：path -> 原因。修好一条就删一条（门禁会检查登记是否过期）。
KNOWN_GAPS = {
    "/api/v1/env/status": "气象图层要机队级环境状态读口径；后端只有 POST /env/{sysid}/wind|weather|thresholds 覆盖写 + GET /api/v1/env-alerts",
    "/api/v1/link-sim/profiles": "链路质量面板；后端口径在 /api/v1/comm-adapt/quality/*，返回形状不同（不是纯路径替换，要改面板取数逻辑）",
    "/api/v1/link-sim/status": "同上",
    "/api/v1/link-sim/damage": "同上（链路损伤注入，后端无对应能力）",
    "/api/v1/vision/thermal/sources": "热成像源列表；后端只有 /api/v1/thermal/tasks（任务），没有「源」的概念",
    "/api/v1/hardware/physics-model": "物理模型切换；概念只存在于模拟器 VirtualDrone.physicsModel，没有 MAVLink 下发通道，后端无法真正生效",
}


def norm(p):
    """路径归一：去掉重复斜杠与末尾斜杠。"""
    p = re.sub(r"/{2,}", "/", p)
    if len(p) > 1 and p.endswith("/"):
        p = p[:-1]
    return p


def split_template(raw):
    """把模板字符串拆成 (字面量片段, 表达式) 序列：${...} 视为原子。"""
    parts = []
    i = 0
    buf = []
    while i < len(raw):
        if raw[i] == "$" and i + 1 < len(raw) and raw[i + 1] == "{":
            depth = 1
            j = i + 2
            while j < len(raw) and depth:
                if raw[j] == "{":
                    depth += 1
                elif raw[j] == "}":
                    depth -= 1
                j += 1
            if buf:
                parts.append(("lit", "".join(buf)))
                buf = []
            parts.append(("expr", raw[i + 2:j - 1]))
            i = j
            continue
        buf.append(raw[i])
        i += 1
    if buf:
        parts.append(("lit", "".join(buf)))
    return parts


def resolve_template(raw, consts):
    """展开模板：base 常量按定义替换，路径参数记成 {p}，查询片段丢弃。

    返回 (路径, 是否可静态核对)。
    - 含 `?` 的表达式（如 `${qs ? '?' + qs : ''}`）是查询片段 ⇒ 整段丢掉；
    - 其余 `${...}` 是路径参数 ⇒ 记成 {p}；
    - 展开后**首段不是字面量**（如 `${url}/x`，url 是运行时变量）⇒ 不可静态核对，
      门禁会显式报出来，不静默放过。
    """
    parts = split_template(raw)
    out = []
    for kind, val in parts:
        if kind == "lit":
            out.append(val)
            continue
        name = val.strip()
        if name in consts:
            out.append(consts[name])
        elif "?" in val:
            # 查询片段（`${qs ? '?' + qs : ''}`）⇒ 丢掉
            out.append("")
        else:
            out.append("{p}")
    # 去掉剩下的查询串
    s = "".join(out)
    depth = 0
    cut = len(s)
    for i, ch in enumerate(s):
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        elif ch == "?" and depth == 0:
            cut = i
            break
    path = norm(s[:cut])
    segs = [x for x in path.split("/") if x]
    resolvable = bool(segs) and not (segs[0].startswith("{") and segs[0].endswith("}"))
    return path, resolvable


def parse_consts(text):
    """api.js 里形如 `const X = '/api/v1/...'` 的路径常量。"""
    consts = {}
    for m in re.finditer(r"^(?:export\s+)?const\s+([A-Z_][A-Z0-9_]*)\s*=\s*(['\"])(/[^'\"]*)\2",
                         text, re.M):
        consts[m.group(1)] = m.group(3)
    return consts


def call_args(text, start):
    """从 `(` 开始扫描出该调用的实参区间（含括号内全部内容）。"""
    depth = 0
    i = start
    quote = None
    while i < len(text):
        ch = text[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in "\"'`":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return ""


def first_arg(args):
    """取调用的**第一个实参**（URL）。不能用"第一个反引号串"——
    jsonFetch(url, { headers: { Authorization: `Bearer ${token}` } }) 里
    第一个反引号串是 header，不是 URL（2026-10-21 实测踩过）。"""
    s = args.lstrip()
    if s.startswith("`"):
        end = s.find("`", 1)
        return s[1:end] if end > 0 else None
    if s[:1] in ("'", '"'):
        q = s[0]
        end = s.find(q, 1)
        return s[1:end] if end > 0 else None
    return None


def extract_frontend(api=API_JS):
    """返回 [(方法, 路径, 可静态核对?)]，口径见模块 docstring。"""
    text = io.open(api, encoding="utf-8", errors="replace").read()
    consts = parse_consts(text)
    out = []
    for m in re.finditer(r"jsonFetch\s*\(", text):
        args = call_args(text, m.end() - 1)
        raw = first_arg(args)
        if raw is None:
            continue
        mm = re.search(r"method:\s*['\"](\w+)['\"]", args)
        method = mm.group(1).upper() if mm else "GET"
        path, ok = resolve_template(raw, consts)
        out.append((method, path, ok))
    return out


def extract_controller(path):
    """单个控制器文件里的 [(方法, 路径)]。"""
    text = io.open(path, encoding="utf-8", errors="replace").read()
    if not CLASS_ANNO.search(text):
        return []
    lines = text.split("\n")
    cls_idx = len(lines)
    for i, l in enumerate(lines):
        if re.search(r"\bclass\s+\w+", l):
            cls_idx = i
            break
    base = ""
    for i in range(cls_idx):
        if "@RequestMapping" in lines[i]:
            q = QUOTED.search(lines[i])
            if q:
                base = q.group(1)
    eps = []
    for l in lines:
        m = METHOD_ANNO.search(l)
        if not m:
            continue
        subs = []
        if "(" in l:
            inner = l[l.find("("):]
            # 只取**路径**实参：`produces = "application/yaml"` 这类不是路径
            # （OpenApiExportController 就是这么写的，取错会把端点数数多 1）
            mv = re.search(r"value\s*=\s*[\"']([^\"']*)[\"']", inner)
            if mv:
                subs = [mv.group(1)]
            else:
                mb = re.match(r"\(\s*[\"']([^\"']*)[\"']", inner)
                if mb:
                    subs = [mb.group(1)]
                elif re.match(r"\(\s*\{", inner):
                    # `@GetMapping({"/a", "/b"})` ⇒ 两个路径
                    for q in QUOTED.finditer(inner):
                        subs.append(q.group(1))
        if not subs:
            subs = [""]
        for sub in subs:
            eps.append((m.group(1).upper(), norm(base + sub)))
    return eps


def extract_backend(src=JAVA_SRC):
    """返回 [(方法, 路径)]，口径见模块 docstring。"""
    eps = []
    for dp, dn, fn in os.walk(src):
        for f in fn:
            if not f.endswith(".java"):
                continue
            p = os.path.join(dp, f)
            if "actuator" in f.lower():
                continue
            eps.extend(extract_controller(p))
    return eps


def seg_match(fp, bp):
    """按段比对路径；任一侧是路径参数就算匹配。"""
    fs = [s for s in fp.split("/") if s != ""]
    bs = [s for s in bp.split("/") if s != ""]
    if len(fs) != len(bs):
        return False
    for a, b in zip(fs, bs):
        if a == b:
            continue
        if a.startswith("{") and a.endswith("}"):
            continue
        if b.startswith("{") and b.endswith("}"):
            continue
        return False
    return True


def path_variants(fp):
    """路径的可能形态。

    前端常把查询串**不带 `?`** 直接拼在最后一段后面
    （`${BASE}/tracking/${sysid}/track${qs}`），展开后是 `.../track{p}` ——
    端点其实还是 `.../track`。所以匹配时除了原路径，还要试"剥掉末尾贴在
    最后一段上的参数"的形态。

    注意只剥**贴在段尾**的参数；独立成段的路径参数（`.../drones/{p}`）不能剥，
    否则 `/drones/{p}` 会去匹配后端的 `/drones`，把方法/路径错配放过去。
    """
    out = [fp]
    if fp.endswith("}"):
        cut = fp.rfind("{")
        if cut > 0 and fp[cut - 1] != "/":
            out.append(fp[:cut])
    return out


def check(backend, frontend, known_gaps=None):
    """返回 (未登记缺口, 已登记但后端已修好的过期登记)。

    frontend: [(方法, 路径)] 或 [(方法, 路径, 可静态核对?)]
    """
    known_gaps = KNOWN_GAPS if known_gaps is None else known_gaps
    new_gaps, stale = [], []
    for item in frontend:
        method, fp = item[0], item[1]
        resolvable = item[2] if len(item) > 2 else True
        if not resolvable:
            new_gaps.append((method, fp, "路径含运行时变量，无法静态核对（需人工确认或改成常量前缀）"))
            continue
        variants = path_variants(fp)
        hit = any(bm == method and any(seg_match(v, bp) for v in variants) for bm, bp in backend)
        if hit:
            continue
        path_only = any(any(seg_match(v, bp) for v in variants) for _bm, bp in backend)
        if path_only:
            new_gaps.append((method, fp, "后端有这条路径，但方法对不上（前端 %s）" % method))
        elif fp in known_gaps:
            continue
        else:
            new_gaps.append((method, fp, "后端没有这条端点"))
    for g in known_gaps:
        if any(seg_match(g, bp) for _bm, bp in backend):
            stale.append(g)
    return new_gaps, stale


def self_test():
    """合成夹具自证：未登记缺口要拦、登记要放行、登记过期要拦、匹配要认。"""
    tmp = tempfile.mkdtemp(prefix="frontend-ep-")
    try:
        be = [("GET", "/api/v1/drones"), ("GET", "/api/v1/drones/{sysid}"),
              ("POST", "/api/v1/formation"), ("POST", "/api/v1/spray"),
              ("GET", "/api/v1/env-alerts")]

        # ① 全部匹配时不报
        g1, s1 = check(be, [("GET", "/api/v1/drones"), ("GET", "/api/v1/drones/{p}")], {})
        ok1 = (g1 == [] and s1 == [])
        print("  [%s] 前后端一致时不误报" % ("PASS" if ok1 else "FAIL"))

        # ② 后端没有的路径要报出来
        g2, s2 = check(be, [("GET", "/api/v1/nope")], {})
        ok2 = (len(g2) == 1 and g2[0][1] == "/api/v1/nope")
        print("  [%s] 后端没有的端点被报出" % ("PASS" if ok2 else "FAIL"))

        # ③ 登记在案的缺口放行
        g3, s3 = check(be, [("GET", "/api/v1/nope")], {"/api/v1/nope": "x"})
        ok3 = (g3 == [] and s3 == [])
        print("  [%s] 已登记的缺口放行" % ("PASS" if ok3 else "FAIL"))

        # ④ 登记过期（后端已修好）要报
        be_fixed = be + [("GET", "/api/v1/nope")]
        g4, s4 = check(be_fixed, [("GET", "/api/v1/nope")], {"/api/v1/nope": "x"})
        ok4 = (g4 == [] and s4 == ["/api/v1/nope"])
        print("  [%s] 已修好的缺口登记被报出（提醒删登记）" % ("PASS" if ok4 else "FAIL"))

        # ⑤ 方法对不上要报
        g5, s5 = check(be, [("POST", "/api/v1/drones")], {})
        ok5 = (len(g5) == 1 and "方法" in g5[0][2])
        print("  [%s] 方法对不上被报出" % ("PASS" if ok5 else "FAIL"))

        # ⑥ 后端路径拼接口径
        os.makedirs(os.path.join(tmp, "java", "demo"))
        io.open(os.path.join(tmp, "java", "demo", "DemoController.java"), "w", encoding="utf-8").write(
            "@RestController\n@RequestMapping(\"/api/v1/demo\")\nclass DemoController {\n"
            "  @GetMapping(\"/a\") void a() {}\n"
            "  @GetMapping void b() {}\n"
            "}\n")
        be2 = extract_backend(os.path.join(tmp, "java"))
        ok6 = (("GET", "/api/v1/demo/a") in be2 and ("GET", "/api/v1/demo") in be2)
        print("  [%s] 后端路径拼接口径正确" % ("PASS" if ok6 else "FAIL"))

        # ⑦ 前端路径展开口径：base 常量、路径参数、查询串、方法都要对
        io.open(os.path.join(tmp, "api.js"), "w", encoding="utf-8").write(
            "const BASE = '/api/v1'\n"
            "const SUB_BASE = '/api/v1/sub'\n"
            "jsonFetch(`${BASE}/demo/a`)\n"
            "jsonFetch(`${BASE}/demo/${id}`)\n"
            "jsonFetch(`${BASE}/demo?x=1`)\n"
            "jsonFetch(`${BASE}/demo`, { method: 'POST' })\n"
            "jsonFetch(`${SUB_BASE}/z`)\n"
            "jsonFetch(`${BASE}/q${qs ? '?' + qs : ''}`)\n")
        fe2 = extract_frontend(os.path.join(tmp, "api.js"))
        paths2 = [(m, p) for m, p, ok in fe2]
        ok7 = (("GET", "/api/v1/demo/a") in paths2 and ("GET", "/api/v1/demo/{p}") in paths2
               and ("GET", "/api/v1/demo") in paths2 and ("POST", "/api/v1/demo") in paths2
               and ("GET", "/api/v1/sub/z") in paths2 and ("GET", "/api/v1/q") in paths2)
        print("  [%s] 前端路径展开口径正确" % ("PASS" if ok7 else "FAIL"))

        # ⑧ 运行时变量的路径要标成不可解析（不能静默放过）
        io.open(os.path.join(tmp, "api2.js"), "w", encoding="utf-8").write(
            "jsonFetch(`${someUrl}/x`)\n")
        fe3 = extract_frontend(os.path.join(tmp, "api2.js"))
        ok8 = (len(fe3) == 1 and fe3[0][2] is False)
        print("  [%s] 运行时变量路径被标出（不被静默放过）" % ("PASS" if ok8 else "FAIL"))

        # ⑨ 查询串**不带 ?** 拼在末尾时，端点应按"去掉末尾路径参数"匹配
        g9, s9 = check([("GET", "/api/v1/tracking/{sysid}/track")],
                       [("GET", "/api/v1/tracking/{p}/track{p}")], {})
        ok9 = (g9 == [] and s9 == [])
        print("  [%s] 不带 ? 的查询串能匹配到端点" % ("PASS" if ok9 else "FAIL"))

        return 0 if all([ok1, ok2, ok3, ok4, ok5, ok6, ok7, ok8, ok9]) else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--self-test" in sys.argv:
        return self_test()
    backend = extract_backend()
    frontend = extract_frontend()
    gaps, stale = check(backend, frontend)
    print("后端端点 %d 条，前端调用 %d 处" % (len(backend), len(frontend)))
    if gaps:
        print("前端端点契约门禁：%d 处对不上" % len(gaps))
        for method, path, why in gaps:
            print("  - %s %s  （%s）" % (method, path, why))
        print("")
        print("  这些是前端在调、但后端没有的端点 —— 界面上通常表现为「面板一直空」")
        print("  （404 被 .catch(() => null) 静默吞掉）。要么改前端指向真实端点，")
        print("  要么在后端补上这个能力，要么登记进 KNOWN_GAPS 并写明原因。")
        return 1
    if stale:
        print("前端端点契约门禁：%d 条缺口登记已过期（后端已修好）" % len(stale))
        for g in stale:
            print("  - %s" % g)
        print("")
        print("  这些缺口已经被修掉了，请把 KNOWN_GAPS 里的对应条目删掉（别让债务烂在清单里）。")
        return 1
    print("前端端点契约门禁：通过（前端 %d 处调用在后端都有对应端点；"
          "已知缺口登记 %d 条）" % (len(frontend), len(KNOWN_GAPS)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
