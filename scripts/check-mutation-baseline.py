#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""变异测试基线门禁：把"测试盯得够不够紧"变成可回归的机械检查。

为什么需要它
------------
常规覆盖率（jacoco）回答的是"**这段代码被跑到过没有**"。
它**回答不了**"这段代码的行为被钉住了没有" —— 一段被调用了一百次、却只断言了
"没抛异常"的代码，覆盖率是满的，行为却完全没被钉住。

2026-10-21 用 PIT 审 security 包时就是这么翻车的：
`ApiKeyController` 的**密钥掩码函数**（`maskKey`）被变异成"返回 null"之后**存活** ——
也就是"哪天掩码退化成返回明文密钥，现有测试一条都不会红"。
同一批里还翻出：登录限流阈值、X-Forwarded-For 信任开关、口令哈希落库，三处真缺口。

本门禁把这些结论变成**可回归的检查**：
1. 跑 PIT（`mvn -Pmutation pitest:mutationCoverage`）拿到变异报告；
2. 算两个数（口径与 PIT 一致）：
   - **变异分数** = killed / (总数 - 未覆盖)  ← 盯住了多少
   - **测试强度** = (killed + 未覆盖) / 总数  ← 有多少行为压根没被看见
3. 与基线（`scripts/mutation-baseline.json`）比：**分数不许低于基线**（棘轮）。
4. 报告缺失 / 损坏 / 一个变异体都没有 ⇒ **判红**（跳过不等于通过）。

用法：
    python scripts/check-mutation-baseline.py --report cloud-backend/target/pit-reports/mutations.xml
    python scripts/check-mutation-baseline.py --self-test

退出码：0 通过；1 分数回退 / 报告不可用。
"""
import io
import json
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_BASELINE = os.path.join(ROOT, "scripts", "mutation-baseline.json")
DEFAULT_REPORT = os.path.join(ROOT, "cloud-backend", "target", "pit-reports", "mutations.xml")


def load_baseline(path):
    if not os.path.exists(path):
        raise SystemExit("找不到基线文件：%s" % path)
    data = json.load(io.open(path, encoding="utf-8"))
    for k in ("mutationScore", "testStrength"):
        if k not in data:
            raise SystemExit("基线文件缺字段：%s" % k)
    return data


def parse_report(path):
    """读 PIT 的 mutations.xml，算出变异分数与测试强度。"""
    if not os.path.exists(path):
        raise ValueError("报告不存在：%s（跑 mvn -Pmutation pitest:mutationCoverage 生成）" % path)
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        raise ValueError("报告不是合法 XML：%s" % e)
    counts = {"KILLED": 0, "SURVIVED": 0, "NO_COVERAGE": 0, "TIMED_OUT": 0, "RUN_ERROR": 0}
    survivors = []
    for m in root.findall("mutation"):
        st = m.get("status", "?")

        def g(tag, default="?"):
            e = m.find(tag)
            return (e.text or default) if e is not None else default
        if st in counts:
            counts[st] += 1
        if st == "SURVIVED":
            survivors.append("%s.%s:%s" % (g("mutatedClass", "?").split(".")[-1],
                                          g("mutatedMethod", "?"), g("mutator", "?")))
    total = sum(counts.values())
    if total == 0:
        raise ValueError("报告里一个变异体都没有 —— 多半是 targetClasses 没匹配到东西，"
                         "或者测试一个都没跑起来（别当成'通过'）")
    covered = total - counts["NO_COVERAGE"]
    score = (counts["KILLED"] / covered) if covered else 0.0
    strength = (counts["KILLED"] + counts["NO_COVERAGE"]) / total
    return {"total": total, "counts": counts, "mutationScore": score,
            "testStrength": strength, "survivors": survivors}


def compare(measured, baseline):
    """返回 (是否通过, 问题列表)。

    比较前把实测值**四舍五入到 4 位**再比：基线文件里存的就是 4 位小数，
    直接拿全精度去比会把"持平"判成"回退"（0.82965... < 0.8297）。
    """
    problems = []
    score = round(measured["mutationScore"], 4)
    strength = round(measured["testStrength"], 4)
    if score < round(baseline["mutationScore"], 4):
        problems.append("变异分数回退：实测 %.4f < 基线 %.4f（掉了 %.1f 个百分点）"
                        % (score, baseline["mutationScore"],
                           100 * (baseline["mutationScore"] - score)))
    if strength < round(baseline["testStrength"], 4):
        problems.append("测试强度回退：实测 %.4f < 基线 %.4f"
                        % (strength, baseline["testStrength"]))
    return (not problems), problems


def rel(path):
    """跨盘符时 relpath 会抛异常 —— 这里退化成绝对路径。"""
    try:
        return os.path.relpath(path, ROOT)
    except ValueError:
        return path


def self_test():
    """用合成夹具自证：回退要能报出、持平/进步不误报、报告不可用要能拦。"""
    ok = True
    tmp = tempfile.mkdtemp(prefix="mut-baseline-")
    try:
        baseline = {"scope": "x", "mutationScore": 0.80, "testStrength": 0.80}

        def write_report(name, killed, survived, nocover, timedout=0):
            p = os.path.join(tmp, name)
            root = ET.Element("mutations")
            for st, n in (("KILLED", killed), ("SURVIVED", survived),
                          ("NO_COVERAGE", nocover), ("TIMED_OUT", timedout)):
                for i in range(n):
                    m = ET.SubElement(root, "mutation", {"status": st})
                    ET.SubElement(m, "mutatedClass").text = "com.example.Foo"
                    ET.SubElement(m, "mutatedMethod").text = "bar"
                    ET.SubElement(m, "mutator").text = "NegateConditionalsMutator"
            ET.ElementTree(root).write(p, encoding="utf-8", xml_declaration=True)
            return p

        # ① 分数回退（8/10 killed over 10 covered = 0.8；回退档 6/10 = 0.6）
        p_bad = write_report("bad.xml", 6, 4, 0)
        m = parse_report(p_bad)
        passed, problems = compare(m, baseline)
        if not passed and any("变异分数回退" in x for x in problems):
            print("  [PASS] 分数回退能被报出")
        else:
            print("  [FAIL] 分数回退没被报出")
            ok = False

        # ② 与基线持平 => 绿
        p_same = write_report("same.xml", 8, 2, 0)
        passed, problems = compare(parse_report(p_same), baseline)
        if passed:
            print("  [PASS] 与基线持平不误报")
        else:
            print("  [FAIL] 与基线持平被误报：%s" % problems)
            ok = False

        # ③ 分数更高 => 绿
        p_better = write_report("better.xml", 9, 1, 0)
        passed, problems = compare(parse_report(p_better), baseline)
        if passed:
            print("  [PASS] 分数变好不误报")
        else:
            print("  [FAIL] 分数变好被误报：%s" % problems)
            ok = False

        # ④ 报告缺失 => 必须判红（跳过不等于通过）
        try:
            parse_report(os.path.join(tmp, "not-there.xml"))
            print("  [FAIL] 报告缺失竟然没报错")
            ok = False
        except ValueError:
            print("  [PASS] 报告缺失被拦（不静默通过）")

        # ⑤ 报告损坏 => 必须判红
        p_bad_xml = os.path.join(tmp, "broken.xml")
        io.open(p_bad_xml, "w", encoding="utf-8").write("<mutations><mutation>")
        try:
            parse_report(p_bad_xml)
            print("  [FAIL] 报告损坏竟然没报错")
            ok = False
        except ValueError:
            print("  [PASS] 报告损坏被拦")

        # ⑥ 一个变异体都没有 => 必须判红
        p_empty = write_report("empty.xml", 0, 0, 0)
        try:
            parse_report(p_empty)
            print("  [FAIL] 空报告竟然没报错")
            ok = False
        except ValueError:
            print("  [PASS] 空报告被拦（别把'没跑到东西'当成'通过'）")
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)
    return 0 if ok else 1


def main():
    if "--self-test" in sys.argv:
        print("== 变异基线门禁自测 ==")
        return self_test()

    report = DEFAULT_REPORT
    baseline_path = DEFAULT_BASELINE
    write_baseline = False
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--report" and i + 1 < len(args):
            report = args[i + 1]; i += 2
        elif args[i] == "--baseline" and i + 1 < len(args):
            baseline_path = args[i + 1]; i += 2
        elif args[i] == "--write-baseline":
            write_baseline = True; i += 1
        else:
            i += 1

    baseline = load_baseline(baseline_path)
    try:
        measured = parse_report(report)
    except ValueError as e:
        print("")
        print("  [FAIL] 变异报告不可用：%s" % e)
        print("")
        print("  这个门禁**不接受**'没跑变异测试就算过'。要跑：")
        print("    mvn -B -pl cloud-backend -Pmutation test-compile pitest:mutationCoverage")
        return 1

    c = measured["counts"]
    print("  变异报告：%s" % rel(report))
    print("    变异体总数 = %d | 杀掉 = %d | 存活 = %d | 未覆盖 = %d | 超时 = %d | 运行错误 = %d"
          % (measured["total"], c["KILLED"], c["SURVIVED"], c["NO_COVERAGE"],
             c["TIMED_OUT"], c["RUN_ERROR"]))
    print("    变异分数 = %.4f（基线 %.4f）   测试强度 = %.4f（基线 %.4f）"
          % (measured["mutationScore"], baseline["mutationScore"],
             measured["testStrength"], baseline["testStrength"]))
    if c["RUN_ERROR"] > 0:
        # RUN_ERROR = 变异体跑起来"抛了异常"而不是"断言失败"。数量异常时，
        # **先怀疑测量环境**（并发跑两个测量、产物被冲掉、源码改了一半），
        # 再怀疑被测代码 —— 分数"退步"有太多种假原因。
        print("")
        print("  [注意] 报告里有 %d 个 RUN_ERROR（跑挂了，不是断言失败）。" % c["RUN_ERROR"])
        print("         先查测量环境：① 别在同一个 target/ 上并发跑两个测量")
        print("         ② 读报告前确认文件写完了 ③ 别在测量中途改源码")
        print("         确认环境无误，再看是不是被测代码真的有问题。")

    if write_baseline:
        # 显式更新基线：会把改动写进文件 → 进 PR diff → 评审的人看得见。
        # 这里只允许**抬高**分数；想降低分数必须手改文件（那才会被 diff 看见）。
        new = dict(baseline)
        new.update({
            "measuredAt": "2026-10-21",
            "total": measured["total"],
            "killed": c["KILLED"],
            "survived": c["SURVIVED"],
            "noCoverage": c["NO_COVERAGE"],
            "timedOut": c["TIMED_OUT"],
            "mutationScore": round(measured["mutationScore"], 4),
            "testStrength": round(measured["testStrength"], 4),
        })
        if new["mutationScore"] < baseline["mutationScore"]:
            print("  [FAIL] 拒绝用 --write-baseline 降低变异分数（%.4f -> %.4f）。"
                  " 降低基线必须手改文件并在 PR 里说明理由。"
                  % (baseline["mutationScore"], new["mutationScore"]))
            return 1
        io.open(baseline_path, "w", encoding="utf-8", newline="\n").write(
            json.dumps(new, ensure_ascii=False, indent=2) + "\n")
        print("  已更新基线：%s" % os.path.relpath(baseline_path, ROOT))
        return 0

    passed, problems = compare(measured, baseline)
    if not passed:
        print("")
        for p in problems:
            print("  [FAIL] %s" % p)
        print("")
        print("  变异分数是**棘轮**：要么把新写的代码测得更狠（补断言），")
        print("  要么显式更新基线（并在 PR 里说明为什么这次可以接受）。")
        return 1

    print("  变异基线门禁：通过（分数未回退）")
    if measured["survivors"]:
        print("    存活变异体 %d 个（基线登记 %s 个）—— 详情见报告"
              % (len(measured["survivors"]), baseline.get("survived", "?")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
