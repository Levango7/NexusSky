#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""空 catch 棘轮门禁：新增的「吞异常」必须显式登记理由。

为什么需要这个
--------------
`catch (Exception e) {}` 不报错、不崩溃、不写日志，是最安静的故障形态：
请求线程看起来正常返回，调用方拿到默认值，线上却少了一段数据/一次重试。
本仓库已有 19 处**故意**的空 catch（关闭失败不掩盖原始异常、监听器异常不拖垮
收包循环、坏行跳过继续读……），它们都有注释说明。问题不在这些存量，而在
**新增**一处时没人会注意到——代码评审也很难逐条盯住。

一刀切禁止会逼人写假日志（`log.debug("ignore")` 只是把空 catch 换个形状），
所以这里用棘轮：

* 生产代码（`*/src/main/java/`）里每个 catch 块，去掉注释后若没有任何语句，
  必须命中 `scripts/empty-catch-exemptions.json` 的一条登记；
* 登记键是「文件 + 理由注释」，**不含行号**：代码上下移动不会误报，但改掉
  理由或新增同名 catch 都会红，强制重新评审；
* 豁免清单整体有到期日；到期即红，逼着下一轮重新确认这些吞异常仍然合理；
* 登记了但代码里找不到（catch 被删/注释被改）也红，防止清单腐烂成摆设。

裸 `catch (...) {}`（连理由注释都没有）永远不能豁免——先写清楚为什么。

用法：
    python scripts/check-empty-catch.py              # 查本仓库
    python scripts/check-empty-catch.py --self-test  # 用合成夹具自证
退出码：0 干净；1 有问题（逐条打印）。
"""
import argparse
import datetime
import io
import json
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_EXEMPTIONS = os.path.join(HERE, "empty-catch-exemptions.json")
SKIP_DIRS = {".git", "target", "node_modules", "dist", "build", "data",
             ".idea", "__pycache__", ".venv", "venv"}
CATCH_RE = re.compile(r"\bcatch\s*\(")


def _mask_java(src):
    """把注释与字符串字面量替换成空格，保留换行与字符位置。

    catch 解析必须只看**代码**：注释里写 `catch (X) {}`、字符串里带大括号
    都会让朴素正则错位。掩码后长度不变，所以行列号仍可回查原文。
    """
    out = list(src)
    i, n, state = 0, len(src), None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state is None:
            if c == "/" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = "line"
                i += 2
            elif c == "/" and nxt == "*":
                out[i] = out[i + 1] = " "
                state = "block"
                i += 2
            elif src.startswith('"""', i):
                out[i] = out[i + 1] = out[i + 2] = " "
                state = "textblock"
                i += 3
            elif c == '"':
                out[i] = " "
                state = "string"
                i += 1
            elif c == "'":
                out[i] = " "
                state = "char"
                i += 1
            else:
                i += 1
        elif state == "line":
            if c == "\n":
                state = None
            else:
                out[i] = " "
            i += 1
        elif state == "block":
            if c == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = None
                i += 2
            else:
                if c != "\n":
                    out[i] = " "
                i += 1
        elif state == "textblock":
            if src.startswith('"""', i):
                out[i] = out[i + 1] = out[i + 2] = " "
                state = None
                i += 3
            else:
                if c != "\n":
                    out[i] = " "
                i += 1
        else:  # string / char
            quote = '"' if state == "string" else "'"
            if c == "\\":
                if c != "\n":
                    out[i] = " "
                if i + 1 < n:
                    if src[i + 1] != "\n":
                        out[i + 1] = " "
                    i += 2
                else:
                    i += 1
            elif c == quote:
                out[i] = " "
                state = None
                i += 1
            else:
                if c != "\n":
                    out[i] = " "
                i += 1
    return "".join(out)


def _normalize(text):
    return re.sub(r"\s+", " ", text).strip()


def _comment_text(body):
    """取出 catch 块里的注释文字，作为豁免键（不含行号）。"""
    parts = []
    for m in re.finditer(r"//([^\n]*)", body):
        parts.append(m.group(1))
    for m in re.finditer(r"/\*(.*?)\*/", body, re.S):
        parts.append(m.group(1))
    return _normalize(" ".join(parts))


def iter_empty_catches(src):
    """产出 (行号, 理由注释)，只针对「去掉注释后没有任何语句」的 catch 块。"""
    masked = _mask_java(src)
    for m in CATCH_RE.finditer(masked):
        i = m.end() - 1  # 指向 '('
        depth = 0
        while i < len(masked):
            if masked[i] == "(":
                depth += 1
            elif masked[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        if i >= len(masked):
            continue
        j = i + 1
        while j < len(masked) and masked[j].isspace():
            j += 1
        if j >= len(masked) or masked[j] != "{":
            continue
        depth = 0
        k = j
        while k < len(masked):
            if masked[k] == "{":
                depth += 1
            elif masked[k] == "}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        if k >= len(masked):
            continue
        body = src[j + 1:k]
        if _mask_java(body).strip():
            continue
        line = src.count("\n", 0, m.start()) + 1
        yield line, _comment_text(body)


def _main_java_files(root):
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if d not in SKIP_DIRS]
        for fn in fns:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dp, fn)
            rel = os.path.relpath(p, root).replace(os.sep, "/")
            if "/src/main/java/" in rel:
                yield p, rel


def _load_exemptions(path, today):
    problems = []
    try:
        with io.open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        return {}, [], ["无法读取豁免清单 %s：%s" % (path, e)]
    expires_raw = data.get("expires")
    try:
        expires = datetime.date.fromisoformat(expires_raw)
    except (TypeError, ValueError):
        problems.append("豁免清单缺少合法 expires（YYYY-MM-DD）：%r" % (expires_raw,))
        expires = None
    if expires is not None and expires < today:
        problems.append("空 catch 豁免清单已于 %s 过期——请逐条复核后刷新" % expires)
    entries = {}
    for item in data.get("entries", []):
        file_ = item.get("file")
        comment = _normalize(item.get("comment", ""))
        reason = _normalize(item.get("reason", ""))
        if not file_ or not comment or not reason:
            problems.append("豁免条目必须同时有 file / comment / reason：%r" % (item,))
            continue
        entries.setdefault((file_, comment), []).append(reason)
    return entries, data.get("entries", []), problems


def check(root, exemptions_path, today=None):
    """返回 (问题列表, 空 catch 数, 登记条目数)。"""
    today = today or datetime.date.today()
    entries, raw_entries, problems = _load_exemptions(exemptions_path, today)
    found = {}
    sources = {}
    for path, rel in _main_java_files(root):
        with io.open(path, encoding="utf-8", errors="replace") as f:
            src = f.read()
        sources[rel] = src
        for line, comment in iter_empty_catches(src):
            found.setdefault((rel, comment), []).append(line)

    for (rel, comment), lines in sorted(found.items()):
        if not comment:
            for line in lines:
                problems.append("%s:%d 裸空 catch（连理由注释都没有）" % (rel, line))
            continue
        allowed = len(entries.get((rel, comment), []))
        for line in lines[allowed:]:
            problems.append("%s:%d 空 catch 未登记：%s" % (rel, line, comment))

    for (rel, comment), reasons in sorted(entries.items()):
        allowed = len(reasons)
        actual = len(found.get((rel, comment), []))
        if actual < allowed:
            problems.append(
                "豁免未命中（catch 被删或理由注释被改）：%s | %s（登记 %d，实际 %d）"
                % (rel, comment, allowed, actual))
    return problems, sum(len(v) for v in found.values()), len(raw_entries)


def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def self_test():
    """用合成夹具证明：裸空/未登记/过期/失效会红，合法登记与非空 catch 不误拦。"""
    today = datetime.date(2026, 10, 31)
    file_rel = "mod/src/main/java/demo/Demo.java"

    def run_case(name, source, entries, expires, expect_clean):
        with tempfile.TemporaryDirectory() as tmp:
            root = os.path.join(tmp, "repo")
            _write(os.path.join(root, *file_rel.split("/")), source)
            ex = os.path.join(tmp, "exemptions.json")
            _write(ex, json.dumps({"expires": expires, "entries": entries},
                                  ensure_ascii=False))
            problems, catches, _ = check(root, ex, today=today)
            clean = not problems
            ok = clean == expect_clean
            detail = "" if ok else ("problems=%r catches=%d" % (problems, catches))
            print("  [%s] %s %s" % ("PASS" if ok else "FAIL", name, detail))
            return ok

    cases = [
        ("裸空 catch 必须红",
         'class Demo { void f() { try { g(); } catch (Exception e) {} } }',
         [], "2027-06-30", False),
        ("仅注释但未登记必须红",
         'class Demo { void f() { try { g(); } catch (Exception e) { // best effort\n } } }',
         [], "2027-06-30", False),
        ("仅注释且已登记应绿",
         'class Demo { void f() { try { g(); } catch (Exception e) { // best effort\n } } }',
         [{"file": file_rel, "comment": "best effort", "reason": "断开即可"}],
         "2027-06-30", True),
        ("非空 catch 不拦",
         'class Demo { void f() { try { g(); } catch (Exception e) { log.warn("x", e); } } }',
         [], "2027-06-30", True),
        ("评论/字符串里的 catch 不误判",
         'class Demo { // catch (Exception e) {}\n String s = "catch (Exception e) {}"; }',
         [], "2027-06-30", True),
        ("登记失效（catch 已删）必须红",
         'class Demo { void f() { g(); } }',
         [{"file": file_rel, "comment": "best effort", "reason": "断开即可"}],
         "2027-06-30", False),
        ("豁免清单过期必须红",
         'class Demo { void f() { try { g(); } catch (Exception e) { // best effort\n } } }',
         [{"file": file_rel, "comment": "best effort", "reason": "断开即可"}],
         "2020-01-01", False),
        ("同名 catch 两处但只登记一条必须红",
         'class Demo { void f() { try { g(); } catch (Exception e) { // same\n } '
         'try { g(); } catch (Exception e) { // same\n } } }',
         [{"file": file_rel, "comment": "same", "reason": "一次"}],
         "2027-06-30", False),
    ]
    print("空 catch 门禁自测：")
    results = [run_case(*c) for c in cases]
    if all(results):
        print("自测通过：%d 个夹具全部符合预期" % len(results))
        return 0
    print("自测失败：%d/%d 个夹具不符" % (results.count(False), len(results)))
    return 1


def main():
    ap = argparse.ArgumentParser(description="空 catch 棘轮门禁")
    ap.add_argument("--root", default=ROOT, help="仓库根目录（默认：本脚本上一级）")
    ap.add_argument("--exemptions", default=DEFAULT_EXEMPTIONS, help="豁免清单 JSON")
    ap.add_argument("--self-test", action="store_true", help="用合成夹具自证")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    problems, catches, entries = check(args.root, args.exemptions)
    if problems:
        print("空 catch 门禁未通过（%d 个问题）：" % len(problems))
        for p in problems:
            print("  - " + p)
        return 1
    with io.open(args.exemptions, encoding="utf-8") as f:
        expires = json.load(f).get("expires")
    print("空 catch 门禁：通过（生产代码 %d 处空 catch 均有登记理由；清单 %s 到期）"
          % (catches, expires))
    return 0


if __name__ == "__main__":
    sys.exit(main())
