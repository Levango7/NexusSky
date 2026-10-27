#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UOM 契约差异比对器（P3 先行件）：把「规范形状」与「实捕获」逐字段对照，差异出表。

**为什么需要它**
----------------
`docs/decisions-2026-10-26.md` 决策二把「真实 UOM 联调」立项为外部依赖主线，其中
#3「`regulator-sim` 与真 UOM 的协议差异比对」被点名为**仓内可先行、不依赖外部**的活：
先把比对的机械部分建好，等 #1（用户侧找对接主体）拿到 API 文档/测试环境，
把规范填成 `spec.json`、把真实响应落成 captures，**一条命令出差异表**，不用临场手抄。

本仓的 sim 侧形状已经被两处钉住（不在这里重复描述，避免第二份手抄）：
  - `cloud-backend/.../regulator/UomReportSink.java`（真实现的请求构造）
  - `UomSinkVsRegulatorSimContractTest`（真实现对 MockUomServer 的 5 例契约）
  - `regulator-sim/.../MockUomServer.java`（六端点与状态机语义）

**两个输入**
------------
1. `--spec spec.json`：规范形状（拿到 UOM API 文档后人工填，schema 见 docs/uom-contract-diff.md）：
   ```json
   {"endpoints": [{"id": "verify", "method": "GET", "path": "/api/verify",
                   "request": {"serialNo": "string", "certNo": "string"},
                   "request_in": "query",           // query | body（缺省 body）
                   "response": {"status": "string", "owner": "string|null"},
                   "errors": {"400": "serialNo 缺失"}}]}
   ```
2. `--captures DIR`：实捕获目录，每文件一次真实/仿真交换：
   ```json
   {"endpoint": "verify", "status": 200, "request": {...}, "response": {...}}
   ```

**判据（差异类别，逐条可复算）**
--------------------------------
- `missing_in_capture`：规范里有、捕获里没有的字段（可能字段改名/未发送）
- `extra_in_capture`：捕获里有、规范里没有的字段（可能是规范外扩展，或我方多发了）
- `type_mismatch`：类型不符（string/number/boolean/object/array/null 归类比较；
  `string|null` 这类联合类型按集合判）
- `status_unexpected`：捕获的状态码不在规范 2xx 期望里、也不在 `errors` 清单里
- `endpoint_missing_capture`：规范里的端点一份捕获都没有（无法比对，不许静默略过）
- `unknown_endpoint_capture`：捕获引用了规范里没有的端点（文档漏项或打错端点）

退出码：0 = 无差异；1 = 有差异（逐条列出）；**2 = 无法判定**（spec 空 / captures 空 /
锚点读不到——"没得比"不许冒充"没问题"）。

用法：
    python3 scripts/uom-contract-diff.py --spec docs/uom-spec.json --captures docs/uom-captures
    python3 scripts/uom-contract-diff.py --spec ... --captures ... --out docs/uom-contract-diff.md
    python3 scripts/uom-contract-diff.py --self-test
"""

import argparse
import io
import json
import os
import sys
import tempfile


# ---------------------------------------------------------------- 纯逻辑

TYPES = {"string", "number", "boolean", "object", "array", "null"}


def json_type(value):
    """把 JSON 值归一成类型名；与 spec 里的类型词表同口径。"""
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, dict):
        return "object"
    return "array"


def spec_types(raw):
    """规范字段的允许类型集合：'string|null' / 'string' 都支持。"""
    return {t.strip() for t in str(raw).split("|") if t.strip()}


def diff_endpoint(spec_ep, captures):
    """返回 (rows, issues)。rows 是给表格用的 (field, spec, capture, verdict, note)。"""
    rows, issues = [], []
    spec_req = spec_ep.get("request") or {}
    spec_resp = spec_ep.get("response") or {}
    errors = {str(k) for k in (spec_ep.get("errors") or {})}

    if not captures:
        issues.append(("endpoint_missing_capture", spec_ep.get("id"),
                       "规范端点在 captures 里一份都没有——先捕获再比对"))
        return rows, issues

    for cap in captures:
        cap_req = cap.get("request") or {}
        cap_resp = cap.get("response") or {}

        for label, spec_fields, cap_fields in (
                ("request", spec_req, cap_req), ("response", spec_resp, cap_resp)):
            for field, raw_type in spec_fields.items():
                if field not in cap_fields:
                    rows.append((f"{label}.{field}", str(raw_type), "（缺失）",
                                 "missing_in_capture", "字段可能改名/未发送"))
                    issues.append(("missing_in_capture", f"{spec_ep.get('id')}.{field}",
                                   "规范有、捕获无"))
                else:
                    allowed = spec_types(raw_type)
                    actual = json_type(cap_fields[field])
                    ok = actual in allowed
                    rows.append((f"{label}.{field}", str(raw_type), actual,
                                 "ok" if ok else "type_mismatch",
                                 "" if ok else "类型不符"))
                    if not ok:
                        issues.append(("type_mismatch", f"{spec_ep.get('id')}.{field}",
                                       f"规范 {raw_type} / 捕获 {actual}"))
            for field in cap_fields:
                if field not in spec_fields:
                    rows.append((f"{label}.{field}", "（规范无）",
                                 json_type(cap_fields[field]), "extra_in_capture",
                                 "可能是规范外扩展，或规范漏记"))
                    issues.append(("extra_in_capture", f"{spec_ep.get('id')}.{field}",
                                   "捕获有、规范无"))

        status = str(cap.get("status"))
        if not (status.startswith("2") or status in errors):
            issues.append(("status_unexpected", spec_ep.get("id"),
                           f"捕获状态码 {status} 不在规范 2xx 期望、也不在 errors 清单"))
    return rows, issues


def audit(spec, captures_by_endpoint):
    """返回 (exit_code, lines)。"""
    out, issues_all = [], []
    endpoints = spec.get("endpoints") or []
    if not endpoints:
        out.append("无法判定：spec 里没有 endpoints（还没拿到 UOM API 文档？先填 spec.json）。")
        out.append("  判 2，不判 0——'没得比'不能冒充'没问题'。")
        return 2, out
    if not captures_by_endpoint:
        out.append("无法判定：captures 目录里没有可解析的捕获文件。")
        out.append("  判 2，不判 0。")
        return 2, out

    known_ids = {ep.get("id") for ep in endpoints}
    for cid in sorted(captures_by_endpoint):
        if cid not in known_ids:
            issues_all.append(("unknown_endpoint_capture", cid,
                               "捕获引用了规范里没有的端点（规范漏项或打错 id）"))

    out.append("# UOM 契约差异比对（spec vs captures）")
    out.append("")
    for ep in endpoints:
        caps = captures_by_endpoint.get(ep.get("id"), [])
        rows, issues = diff_endpoint(ep, caps)
        issues_all.extend(issues)
        out.append(f"## {ep.get('id')} · {ep.get('method')} {ep.get('path')}"
                   f"（捕获 {len(caps)} 份）")
        out.append("")
        if rows:
            out.append("| 字段 | 规范 | 捕获 | 判定 | 备注 |")
            out.append("|---|---|---|---|---|")
            for r in rows:
                out.append("| `%s` | %s | %s | %s | %s |" % r)
            out.append("")
        if issues:
            for kind, where, why in issues:
                out.append(f"- **[{kind}]** {where}：{why}")
            out.append("")

    if issues_all:
        out.append("差异 %d 条：" % len(issues_all))
        for kind, where, why in issues_all:
            out.append("  - [%s] %s：%s" % (kind, where, why))
        return 1, out
    out.append("无差异：全部字段与状态码都与规范一致。")
    return 0, out


# ---------------------------------------------------------------- IO

def load_spec(path):
    with io.open(path, encoding="utf-8") as fh:
        return json.load(fh)


def load_captures(root):
    """{endpoint_id: [capture, ...]}；非法文件直接报错（不静默丢）。"""
    out = {}
    for name in sorted(os.listdir(root)):
        if not name.endswith(".json"):
            continue
        p = os.path.join(root, name)
        with io.open(p, encoding="utf-8") as fh:
            cap = json.load(fh)
        eid = cap.get("endpoint")
        if not eid:
            raise ValueError("capture %s 缺少 endpoint 字段" % name)
        out.setdefault(eid, []).append(cap)
    return out


# ---------------------------------------------------------------- 自测

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(json.dumps(obj, ensure_ascii=False, indent=2))


def self_test():
    """八种情形各自预期（0/1/2）。任一不符 ⇒ exit 1。"""
    fails = []

    spec = {"endpoints": [{
        "id": "verify", "method": "GET", "path": "/api/verify",
        "request": {"serialNo": "string", "certNo": "string"},
        "response": {"status": "string", "owner": "string|null", "registerDate": "string|null"},
        "errors": {"400": "serialNo 缺失"},
    }]}

    def cap(status=200, req=None, resp=None):
        return {"endpoint": "verify", "status": status,
                "request": req if req is not None else {"serialNo": "T-1", "certNo": "C-1"},
                "response": resp if resp is not None else
                {"status": "VERIFIED", "owner": "张*", "registerDate": "2026-01-15"}}

    def expect(label, code, lines, want, needle=None):
        if code != want:
            fails.append("%s 期望 %d 实得 %d :: %s" % (label, want, code, lines[-3:]))
        if needle and not any(needle in ln for ln in lines):
            fails.append("%s 未报出 %r" % (label, needle))

    # 1) 完全一致 ⇒ 0
    code, lines = audit(spec, {"verify": [cap()]})
    expect("情形1", code, lines, 0)

    # 2) 缺字段 ⇒ 1
    code, lines = audit(spec, {"verify": [cap(resp={"status": "VERIFIED"})]})
    expect("情形2", code, lines, 1, "missing_in_capture")

    # 3) 多字段 ⇒ 1
    code, lines = audit(spec, {"verify": [cap(resp={"status": "VERIFIED", "owner": "张*",
                                                    "registerDate": "2026-01-15",
                                                    "extra": 1})]})
    expect("情形3", code, lines, 1, "extra_in_capture")

    # 4) 类型不符 ⇒ 1
    code, lines = audit(spec, {"verify": [cap(resp={"status": 200, "owner": None,
                                                    "registerDate": None})]})
    expect("情形4", code, lines, 1, "type_mismatch")

    # 5) 状态码不在 2xx/errors ⇒ 1
    code, lines = audit(spec, {"verify": [cap(status=418)]})
    expect("情形5", code, lines, 1, "status_unexpected")

    # 6) 规范端点在 captures 里没有 ⇒ 1
    code, lines = audit(spec, {"other": [{"endpoint": "other"}]})
    expect("情形6", code, lines, 1, "endpoint_missing_capture")

    # 7) 捕获引用未知端点 ⇒ 1
    code, lines = audit(spec, {"verify": [cap()], "ghost": [{"endpoint": "ghost"}]})
    expect("情形7", code, lines, 1, "unknown_endpoint_capture")

    # 8) spec 空 / captures 空 ⇒ 2
    code, lines = audit({"endpoints": []}, {"verify": [cap()]})
    expect("情形8a", code, lines, 2, "无法判定")
    code, lines = audit(spec, {})
    expect("情形8b", code, lines, 2, "无法判定")

    # 9) 磁盘往返：--captures 目录读取（含非 .json 忽略）
    with tempfile.TemporaryDirectory() as tmp:
        write(os.path.join(tmp, "verify-1.json"), cap())
        with io.open(os.path.join(tmp, "README.txt"), "w", encoding="utf-8") as fh:
            fh.write("not json")
        caps = load_captures(tmp)
        code, lines = audit(spec, caps)
        expect("情形9", code, lines, 0)

    if fails:
        print("[FAIL] UOM 差异比对器自测：%d 条不符" % len(fails))
        for f in fails:
            print("  - " + f)
        return 1
    print("[PASS] UOM 差异比对器自测：9 种情形全部按预期（0/1/2）")
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--spec")
    ap.add_argument("--captures")
    ap.add_argument("--out")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.spec or not args.captures:
        print("用法：--spec spec.json --captures DIR [--out FILE] 或 --self-test")
        return 64
    try:
        spec = load_spec(args.spec)
        caps = load_captures(args.captures)
    except (OSError, ValueError, json.JSONDecodeError) as e:
        print("无法判定：%s" % e)
        return 2
    code, lines = audit(spec, caps)
    text = "\n".join(lines)
    if args.out:
        with io.open(args.out, "w", encoding="utf-8", newline="") as fh:
            fh.write(text + "\n")
        print("已写入 %s（判定 rc=%d）" % (args.out, code))
    else:
        print(text)
    return code


if __name__ == "__main__":
    sys.exit(main())
