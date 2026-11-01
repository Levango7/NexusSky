#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段事实门禁：让定位白皮书的"自我声明"与仓库里的**代码事实**保持一致。

为什么需要这个门禁（2026-11-01 实测）：
  `docs/PRODUCT-POSITIONING.md` 是对外的第一份文档，它自己声明"哪些已完成、
  哪些还差"。实测发现**同一份文档里同一件事有两处不同说法**：
    - §1.4（阶段诚实声明）说"商用落地需要补齐鉴权、持久化与真机集成"；
    - §7.2（商用落地必补项）却把鉴权、持久化、MAVLink 签名标成 ✅ 已完成。
  还有 §7.1（标注"同步自 README，2026-10-02"）落后两个月，
  其中"自主决策只建议不执行""M12 边缘库无生产调用方"两条**已被代码反证**
  （`--autonomy-exec` 执行级已接线；`EdgeInferenceRunner` 已在生产链路里
  实例化 `SensorFusionEngine`/`VideoStreamAnalyzer`，且 `AiAutonomyWiringTest`
  有反回归断言钉住）。

**光写下来没用，能让机器拦住的才算数**（docs/agent-constraints.md 开头的话）。
所以把"阶段事实"抽成机器可读的单一事实源 `docs/stage-facts.json`，本门禁守住：
  1. 每条事实的**代码级证据**仍然成立（文档说有、代码没了 ⇒ 报出）；
  2. §7.2 的表格与事实源一致（该划掉的没划 / 不该划的划了 ⇒ 报出）；
  3. §1.4 声明的"待补齐项数"与事实源里的待补项数一致（§1.4 与 §7.2 打架 ⇒ 报出）；
  4. 事实的"反证短语"（如「只建议不执行」）不出现在文档里（过期声明残留 ⇒ 报出）。

退出码：
  0  一致
  1  发现不一致（逐条打印）
  2  证据不全（文件缺失/无法解析）——刻意不返回 0：「查不到」不能冒充「没问题」
"""
from __future__ import annotations

import argparse
import glob
import io
import json
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_FACTS = os.path.join(REPO_ROOT, "docs", "stage-facts.json")
DEFAULT_DOC = os.path.join(REPO_ROOT, "docs", "PRODUCT-POSITIONING.md")

DONE_STATUSES = ("done", "wired")
PENDING_STATUSES = ("pending", "external")
KNOWN_STATUSES = DONE_STATUSES + PENDING_STATUSES

STAGE_HEADING = "### 1.4"
GAP_HEADING = "### 7.2"
COUNT_RE = re.compile(r"§7\.2\s*为准（当前\s*(\d+)\s*项待补齐）")


# --------------------------------------------------------------------------- #
# 事实源自身的形状
# --------------------------------------------------------------------------- #
def check_facts_shape(facts):
    """事实源本身的结构问题（缺字段、状态不认识、id 重复…）。"""
    problems = []
    if not isinstance(facts, dict):
        return ["事实源根节点必须是对象"]
    items = facts.get("facts")
    if not isinstance(items, list) or not items:
        return ["事实源里没有 facts 数组，或它是空的"]
    seen = set()
    for i, f in enumerate(items):
        where = f"facts[{i}]"
        if not isinstance(f, dict):
            problems.append(f"{where} 不是对象")
            continue
        for key in ("id", "label", "status"):
            if not f.get(key):
                problems.append(f"{where} 缺字段 {key}")
        fid = f.get("id")
        if fid in seen:
            problems.append(f"{where} 的 id 重复：{fid}")
        seen.add(fid)
        st = f.get("status")
        if st and st not in KNOWN_STATUSES:
            problems.append(f"事实 {fid} 的 status 未知：{st}（允许：{', '.join(KNOWN_STATUSES)}）")
        if f.get("in_gap_table") and not f.get("doc_key"):
            problems.append(f"事实 {fid} 声明进 §7.2 表，却没给 doc_key（门禁找不到对应的行）")
        if "evidence" in f and not isinstance(f["evidence"], list):
            problems.append(f"事实 {fid} 的 evidence 必须是数组")
    return problems


# --------------------------------------------------------------------------- #
# 代码级证据
# --------------------------------------------------------------------------- #
def _read(path):
    with io.open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def check_evidence(facts, root):
    """每条事实的代码级证据是否仍然成立。"""
    problems = []
    for f in facts.get("facts", []):
        fid = f.get("id", "?")
        for ev in f.get("evidence", []) or []:
            kind = ev.get("kind")
            if kind == "file_exists":
                p = os.path.join(root, ev["path"])
                if not os.path.isfile(p):
                    problems.append(f"事实 {fid} 的证据不成立：文件不存在 {ev['path']}")
            elif kind == "file_contains":
                p = os.path.join(root, ev["path"])
                if not os.path.isfile(p):
                    problems.append(f"事实 {fid} 的证据不成立：文件不存在 {ev['path']}")
                    continue
                if ev["text"] not in _read(p):
                    problems.append(
                        f"事实 {fid} 的证据不成立：{ev['path']} 里已找不到 {ev['text']!r}")
            elif kind == "file_count_at_least":
                d = os.path.join(root, ev["path"])
                if not os.path.isdir(d):
                    problems.append(f"事实 {fid} 的证据不成立：目录不存在 {ev['path']}")
                    continue
                n = len(glob.glob(os.path.join(d, ev.get("glob", "*"))))
                if n < int(ev.get("min_count", 1)):
                    problems.append(
                        f"事实 {fid} 的证据不成立：{ev['path']} 下匹配 {ev.get('glob')} 的只有 "
                        f"{n} 个，少于要求的 {ev.get('min_count')} 个")
            elif kind == "no_file_matching":
                hits = glob.glob(os.path.join(root, ev["glob"]), recursive=True)
                hits = [h for h in hits if os.path.isfile(h)]
                if hits:
                    rel = [os.path.relpath(h, root) for h in hits[:3]]
                    problems.append(
                        f"事实 {fid} 的证据不成立：应当**不存在**的文件出现了 {rel}"
                        f"（若这项事实已经完成，请改事实源 + 文档，而不是让门禁红着）")
            else:
                problems.append(f"事实 {fid} 用了未知的证据类型：{kind!r}")
    return problems


# --------------------------------------------------------------------------- #
# 文档与事实源的一致性
# --------------------------------------------------------------------------- #
def _section(text, heading):
    """取以 heading 开头的那一节（到下一个同级标题为止）。"""
    start = text.find(heading)
    if start < 0:
        return None
    rest = text[start + len(heading):]
    m = re.search(r"\n#{2,3} ", rest)
    return rest[:m.start()] if m else rest


def check_doc(facts, doc_text):
    """§1.4 / §7.2 与事实源是否一致。"""
    problems = []
    gap = _section(doc_text, GAP_HEADING)
    stage = _section(doc_text, STAGE_HEADING)
    if gap is None:
        problems.append(f"文档里找不到 {GAP_HEADING}（商用落地必补项）一节")
    if stage is None:
        problems.append(f"文档里找不到 {STAGE_HEADING}（阶段诚实声明）一节")

    # 1) §7.2 的行与事实状态一致
    if gap is not None:
        rows = [ln.strip() for ln in gap.splitlines() if ln.strip().startswith("|")]
        for f in facts.get("facts", []):
            if not f.get("in_gap_table"):
                # 不变量：待补的事实在 §7.2 里必须**可见**——它不在表里，
                # 读者就看不到它还差什么，门禁也就没法守住它。
                if f.get("status") in PENDING_STATUSES:
                    problems.append(
                        f"事实 {f.get('id')} 是待补项，却没有出现在 {GAP_HEADING} 表里"
                        f"（in_gap_table=false）——读者看不到它还差什么")
                continue
            fid, key, st = f.get("id"), f.get("doc_key"), f.get("status")
            hit = [r for r in rows if key in r]
            if not hit:
                problems.append(
                    f"事实 {fid}（doc_key={key!r}）在 {GAP_HEADING} 表里找不到对应行"
                    f"——文档与事实源对不上")
                continue
            struck = any("~~" in r for r in hit)
            if st in DONE_STATUSES and not struck:
                problems.append(
                    f"事实 {fid} 状态是 {st}（已完成），但 {GAP_HEADING} 表里对应行没划掉"
                    f"（应写成 ~~项~~）：{hit[0][:60]}")
            if st in PENDING_STATUSES and struck:
                problems.append(
                    f"事实 {fid} 状态是 {st}（待补），但 {GAP_HEADING} 表里对应行被划掉了"
                    f"（会被读成已完成）：{hit[0][:60]}")

    # 2) §1.4 声明的待补项数 == 事实源里的待补项数
    n_pending = len([f for f in facts.get("facts", []) if f.get("status") in PENDING_STATUSES])
    if stage is not None:
        m = COUNT_RE.search(stage)
        if not m:
            problems.append(
                f"{STAGE_HEADING} 里没有「§7.2 为准（当前 N 项待补齐）」这样的计数声明"
                f"（应为 N={n_pending}）——不写计数，§1.4 与 §7.2 就会各说各话")
        elif int(m.group(1)) != n_pending:
            problems.append(
                f"{STAGE_HEADING} 说待补 {m.group(1)} 项，事实源里是 {n_pending} 项"
                f"——两处对不上")
    else:
        problems.append(f"{STAGE_HEADING} 一节缺失，无法核对待补项数")

    # 3) 反证短语不得出现在文档里（过期声明残留）
    for f in facts.get("facts", []):
        for phrase in f.get("contradicting_phrases", []) or []:
            if phrase in doc_text:
                problems.append(
                    f"事实 {f.get('id')} 的反证短语「{phrase}」仍出现在文档里"
                    f"——它与事实源冲突（事实源说这条已完成/已接线）")
    return problems


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #
def run(facts_path, doc_path, root=REPO_ROOT):
    problems = []
    if not os.path.isfile(facts_path):
        return ["事实源不存在：" + facts_path], True
    if not os.path.isfile(doc_path):
        return ["文档不存在：" + doc_path], True
    try:
        facts = json.loads(_read(facts_path))
    except (ValueError, UnicodeDecodeError) as exc:
        return ["事实源解析失败：%s" % exc], True
    problems += check_facts_shape(facts)
    if problems:
        return problems, False
    problems += check_evidence(facts, root)
    problems += check_doc(facts, _read(doc_path))
    return problems, False


def main(argv=None):
    ap = argparse.ArgumentParser(description="阶段事实门禁（文档声明 vs 代码事实）")
    ap.add_argument("--facts", default=DEFAULT_FACTS, help="事实源 JSON 路径")
    ap.add_argument("--doc", default=DEFAULT_DOC, help="定位白皮书路径")
    ap.add_argument("--self-test", action="store_true", help="跑自测（正例/反例/干净态）")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    problems, incomplete = run(args.facts, args.doc)
    if incomplete:
        print("[UNVERIFIED] 证据不全，无法判定：")
        for p in problems:
            print("  - %s" % p)
        return 2
    if problems:
        print("阶段事实门禁：不一致（%d 条）" % len(problems))
        for p in problems:
            print("  - %s" % p)
        return 1
    print("阶段事实门禁：通过（文档声明与代码事实一致）")
    return 0


# --------------------------------------------------------------------------- #
# 自测：证明它**抓得住**，且不误拦
# --------------------------------------------------------------------------- #
def _fixture(root):
    """造一套最小夹具：1 个已完成事实（证据=某文件内容）+ 1 个待补事实。"""
    os.makedirs(os.path.join(root, "code"), exist_ok=True)
    with io.open(os.path.join(root, "code", "Thing.java"), "w", encoding="utf-8") as fh:
        fh.write("// marker-xyz\n")
    with io.open(os.path.join(root, "code", "Present.java"), "w", encoding="utf-8") as fh:
        fh.write("// present\n")
    facts = {
        "schema": 1,
        "facts": [
            {
                "id": "done_one",
                "label": "已完成的一件事",
                "status": "done",
                "in_gap_table": True,
                "doc_key": "已完成的一件事",
                "evidence": [
                    {"kind": "file_contains", "path": "code/Thing.java", "text": "marker-xyz"}
                ],
                "contradicting_phrases": ["过时的说法"],
            },
            {
                "id": "pending_one",
                "label": "待补的一件事",
                "status": "pending",
                "in_gap_table": True,
                "doc_key": "待补的一件事",
                "evidence": [],
                "contradicting_phrases": [],
            },
            {
                "id": "exists_one",
                "label": "靠「文件存在」证明的一条",
                "status": "done",
                "in_gap_table": False,
                "evidence": [{"kind": "file_exists", "path": "code/Present.java"}],
                "contradicting_phrases": [],
            },
            {
                "id": "absence_one",
                "label": "靠「不存在」证明的一条",
                "status": "pending",
                "in_gap_table": True,
                "doc_key": "靠「不存在」证明的一条",
                # 证据是「某个文件不存在」——这类证据最容易被写成"我没查到"，所以
                # 必须有一条反例证明它**会**在文件出现时报出来（否则它是永远绿的摆设）。
                "evidence": [{"kind": "no_file_matching", "glob": "code/NeverThere*.java"}],
                "contradicting_phrases": [],
            },
        ],
    }
    doc = (
        "# 标题\n\n"
        + STAGE_HEADING + " 阶段诚实声明\n\n"
        + "商用落地尚需补齐的项以 **§7.2 为准（当前 2 项待补齐）**。\n\n"
        + "## 2. 别的\n\n"
        + GAP_HEADING + " 商用落地必补项\n\n"
        + "| 项 | 优先级 |\n|---|---|\n"
        + "| ~~已完成的一件事~~ | — |\n"
        + "| 待补的一件事 | P0 |\n"
        + "| 靠「不存在」证明的一条 | P1 |\n\n"
        + "## 8. 收尾\n"
    )
    return facts, doc


def self_test():
    import shutil
    import tempfile

    failures = []
    tmp = tempfile.mkdtemp(prefix="posfacts-")
    try:
        facts, doc = _fixture(tmp)
        facts_path = os.path.join(tmp, "facts.json")
        doc_path = os.path.join(tmp, "doc.md")
        with io.open(facts_path, "w", encoding="utf-8") as fh:
            fh.write(json.dumps(facts, ensure_ascii=False, indent=2))
        with io.open(doc_path, "w", encoding="utf-8") as fh:
            fh.write(doc)

        # ① 干净态 ⇒ 必须通过
        problems, incomplete = run(facts_path, doc_path, root=tmp)
        if problems:
            failures.append("干净态被误拦：%s" % problems)
        else:
            print("  [ok] 干净态不误拦")

        # ② 证据失效 ⇒ 必须报出
        with io.open(os.path.join(tmp, "code", "Thing.java"), "w", encoding="utf-8") as fh:
            fh.write("// 证据被删了\n")
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("证据不成立" in p for p in problems):
            failures.append("证据失效没被抓到（夹具缺判别性）")
        else:
            print("  [ok] 证据失效被报出")
        with io.open(os.path.join(tmp, "code", "Thing.java"), "w", encoding="utf-8") as fh:
            fh.write("// marker-xyz\n")

        # ③ §7.2 把待补项划掉 ⇒ 必须报出
        with io.open(doc_path, "w", encoding="utf-8") as fh:
            fh.write(doc.replace("| 待补的一件事 | P0 |", "| ~~待补的一件事~~ | — |"))
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("被划掉了" in p for p in problems):
            failures.append("§7.2 把待补项划掉没被抓到")
        else:
            print("  [ok] §7.2 把待补项划掉被报出")

        # ④ §1.4 计数与事实源不符 ⇒ 必须报出
        with io.open(doc_path, "w", encoding="utf-8") as fh:
            fh.write(doc.replace("当前 2 项待补齐", "当前 3 项待补齐"))
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("待补" in p for p in problems):
            failures.append("§1.4 计数不符没被抓到")
        else:
            print("  [ok] §1.4 计数与事实源不符被报出")

        # ⑤ 反证短语出现 ⇒ 必须报出
        with io.open(doc_path, "w", encoding="utf-8") as fh:
            fh.write(doc.replace("## 2. 别的", "## 2. 别的\n\n这里写着过时的说法。"))
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("反证短语" in p for p in problems):
            failures.append("反证短语残留没被抓到")
        else:
            print("  [ok] 反证短语残留被报出")

        # ⑥ 事实源形状不对 ⇒ 必须报出
        with io.open(facts_path, "w", encoding="utf-8") as fh:
            fh.write(json.dumps({"facts": [{"id": "x", "status": "胡说"}]}, ensure_ascii=False))
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("status 未知" in p for p in problems):
            failures.append("事实源形状错误没被抓到")
        else:
            print("  [ok] 事实源形状错误被报出")

        # ⑦ 「不存在」类证据：文件一旦出现必须报出（否则它是永远绿的摆设）
        with io.open(facts_path, "w", encoding="utf-8") as fh:
            fh.write(json.dumps(facts, ensure_ascii=False, indent=2))
        with io.open(doc_path, "w", encoding="utf-8") as fh:
            fh.write(doc)
        with io.open(os.path.join(tmp, "code", "NeverThere.java"), "w", encoding="utf-8") as fh:
            fh.write("// 本该不存在\n")
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("应当**不存在**的文件出现了" in p for p in problems):
            failures.append("「不存在」类证据在文件出现时没被抓到")
        else:
            print("  [ok] 「不存在」类证据在文件出现时被报出")
        os.remove(os.path.join(tmp, "code", "NeverThere.java"))

        # ⑧ 「文件存在」类证据：文件被删必须报出（变异验证实测过：这条缺负例时，
        #    去掉该检查的变异体**存活**——自测抓不住，等于没测）
        os.remove(os.path.join(tmp, "code", "Present.java"))
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("证据不成立" in p for p in problems):
            failures.append("「文件存在」类证据在文件消失时没被抓到")
        else:
            print("  [ok] 「文件存在」类证据在文件消失时被报出")

        # ⑨ 已完成的事实却没在 §7.2 里划掉 ⇒ 必须报出
        #    （变异验证实测过：这条缺负例时，去掉该检查的变异体**存活**）
        with io.open(facts_path, "w", encoding="utf-8") as fh:
            fh.write(json.dumps(facts, ensure_ascii=False, indent=2))
        with io.open(doc_path, "w", encoding="utf-8") as fh:
            fh.write(doc.replace("| ~~已完成的一件事~~ | — |", "| 已完成的一件事 | — |"))
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("没划掉" in p for p in problems):
            failures.append("已完成项没划掉没被抓到")
        else:
            print("  [ok] 已完成项没划掉被报出")

        # ⑩ 待补事实却不在 §7.2 表里（读者看不到它还差什么）⇒ 必须报出
        broken = json.loads(json.dumps(facts))
        for f in broken["facts"]:
            if f["id"] == "pending_one":
                f["in_gap_table"] = False
        with io.open(facts_path, "w", encoding="utf-8") as fh:
            fh.write(json.dumps(broken, ensure_ascii=False, indent=2))
        with io.open(doc_path, "w", encoding="utf-8") as fh:
            fh.write(doc)
        problems, _ = run(facts_path, doc_path, root=tmp)
        if not any("没有出现在" in p for p in problems):
            failures.append("待补项不在 §7.2 表里没被抓到")
        else:
            print("  [ok] 待补项不在 §7.2 表里被报出")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print("自测失败：")
        for f in failures:
            print("  - %s" % f)
        return 1
    print("自测通过（干净态 + 9 个反例：证据失效 / 待补项被划掉 / 已完成项没划掉 / "
          "待补项不在 §7.2 表里 / §1.4 计数不符 / 反证短语残留 / 事实源形状错误 / "
          "「不存在」类证据失效 / 「文件存在」类证据失效）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
