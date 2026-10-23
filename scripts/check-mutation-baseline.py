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
4. 报告缺失 / 损坏 / 一个变异体都没有 / **变异体数量比基线缩水 5% 以上**
   ⇒ **判红**（跳过不等于通过；分母缩水时分数会虚高）。

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
import datetime
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
    counts = {"KILLED": 0, "SURVIVED": 0, "NO_COVERAGE": 0, "TIMED_OUT": 0,
              "RUN_ERROR": 0, "MEMORY_ERROR": 0}
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
    # 必须与 PIT 自己的 "Test strength" 同口径：killed/(killed+survived)。
    # PIT 汇总行会同时打印两者（"Mutations with no coverage 165. Test strength 83%"）。
    # 旧实现是 (KILLED+NO_COVERAGE)/total —— 等于**把没测到的变异体算成测试强度**，
    # 结果是"给零覆盖子包补测试"反而被判红（2026-10-23 实测踩到：杀掉 +119、
    # 未覆盖 -120，分数 0.8057→0.8400，旧口径的"强度"却从 0.8727 掉到 0.8714）。
    tested = counts["KILLED"] + counts["SURVIVED"]
    strength = (counts["KILLED"] / tested) if tested else 0.0
    return {"total": total, "counts": counts, "mutationScore": score,
            "testStrength": strength, "survivors": survivors}


ACCOUNT_KEYS = ("killed", "survived", "noCoverage", "timedOut", "runError")


def check_accounting(block, label):
    """基线里那几列计数必须加得回 total。返回问题列表（空 = 自洽）。

    为什么单独判：**这几列不参与分数判定**（判据只有 mutationScore / testStrength 加
    total 完整性锚点），所以它们写错了不会有任何东西变红 —— 而记账字段一旦不可信，
    下一个读它的人只能重跑一次 PIT 才知道。surveillance 基线就挂着过
    「本地 636/22 vs CI 635/23」的差 1 好几位数（真相是运行间抖动，见
    scripts/mutation-baseline-surveillance.json 的 note 与 ciObserved）。
    缺键就跳过：老基线与自测夹具都只带 total 与两条分数。
    """
    if not isinstance(block, dict) or "total" not in block:
        return []
    present = [k for k in ACCOUNT_KEYS if k in block]
    if not present:
        return []
    missing = [k for k in ACCOUNT_KEYS if k not in block]
    if missing:
        # 只写了几列中的几列 ⇒ 必须拦住。否则"删掉一列"就等于"关掉这条判据"，
        # 门禁会在账目明明残缺的情况下安静地继续打分。
        return ["基线记账字段残缺：%s 缺 %s —— 要么补齐五列，要么一列都别写"
                % (label, "+".join(missing))]
    s = sum(int(block[k]) for k in ACCOUNT_KEYS)
    if s != int(block["total"]):
        return ["基线记账不平：%s 的 %s 之和 = %d ≠ total = %d —— "
                "这几个计数不参与判红，但错了就是账目，别挂着"
                % (label, "+".join(ACCOUNT_KEYS), s, int(block["total"]))]
    return []


def compare(measured, baseline):
    """返回 (是否通过, 问题列表)。

    比较前把实测值**四舍五入到 4 位**再比：基线文件里存的就是 4 位小数，
    直接拿全精度去比会把"持平"判成"回退"（0.82965... < 0.8297）。
    """
    problems = []
    score = round(measured["mutationScore"], 4)
    strength = round(measured["testStrength"], 4)

    # 基线自身的记账（含 ciObserved 那一块）先对一遍账，再比分数。
    problems.extend(check_accounting(baseline, "基线"))
    problems.extend(check_accounting(baseline.get("ciObserved"), "基线 ciObserved"))

    # 报告完整性：变异体数量不该大幅缩水。
    # 分数是 killed/(total-noCoverage)，**分母变小时分数反而更容易变高** ——
    # 若 PIT 只覆盖了部分类（targetClasses 配错、类编译失败被跳过、提前中断），
    # 剩下那一小撮全杀掉就能得到 1.0，门禁假绿。这正是"跳过不等于通过"的另一面。
    # 阈值 95%：允许正常代码删减，但缩水超过 5% 必须显式更新基线（改动进 diff）。
    base_total = baseline.get("total")
    if base_total:
        floor = int(base_total * 0.95)
        if measured["total"] < floor:
            problems.append(
                "报告不完整：变异体总数 %d < 基线 %d 的 95%%（%d）—— "
                "多半是 PIT 只覆盖了部分类或提前中断，此时的分数不可信"
                % (measured["total"], base_total, floor))

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
        baseline = {"scope": "x", "total": 100, "mutationScore": 0.80, "testStrength": 0.80}

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

        # ① 分数回退（60/100 killed = 0.60；基线 0.80）
        p_bad = write_report("bad.xml", 60, 40, 0)
        m = parse_report(p_bad)
        passed, problems = compare(m, baseline)
        if not passed and any("变异分数回退" in x for x in problems):
            print("  [PASS] 分数回退能被报出")
        else:
            print("  [FAIL] 分数回退没被报出")
            ok = False

        # ② 与基线持平 => 绿
        p_same = write_report("same.xml", 80, 20, 0)
        passed, problems = compare(parse_report(p_same), baseline)
        if passed:
            print("  [PASS] 与基线持平不误报")
        else:
            print("  [FAIL] 与基线持平被误报：%s" % problems)
            ok = False

        # ③ 分数更高 => 绿
        p_better = write_report("better.xml", 90, 10, 0)
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

        # ⑦ 报告不完整（只覆盖一小部分类）=> 必须判红。
        # 分数是 killed/(total-noCoverage)：分母缩水时，剩下几个全杀掉就能拿 1.0。
        # 只比分数不看总数的话，PIT 漏跑一半类反而更容易"通过"。
        p_partial = write_report("partial.xml", 5, 0, 0)
        passed, problems = compare(parse_report(p_partial), baseline)
        if not passed and any("报告不完整" in x for x in problems):
            print("  [PASS] 残缺报告被拦（分母缩水不能算进步）")
        else:
            print("  [FAIL] 残缺报告竟然通过了：%s" % problems)
            ok = False

        # ⑧ 未覆盖**不能**算进测试强度。
        # 50 killed / 15 survived / 35 no-coverage：
        #   旧口径 (50+35)/100 = 0.85 ⇒ 假绿（"没测到"被当成"强度高"）
        #   PIT 口径  50/(50+15) = 0.769 ⇒ 必须判红
        # 基线里的变异分数压到 0.60，保证红的理由只可能是"测试强度"。
        b_nocov = {"scope": "x", "total": 100, "mutationScore": 0.60, "testStrength": 0.80}
        p_nocov = write_report("nocov.xml", 50, 15, 35)
        passed, problems = compare(parse_report(p_nocov), b_nocov)
        if not passed and any("测试强度回退" in x for x in problems):
            print("  [PASS] 未覆盖不算测试强度（旧口径会假绿）")
        else:
            print("  [FAIL] 未覆盖被算进了测试强度：%s" % problems)
            ok = False
        # ⑨ 基线记账不平 => 必须判红。
        # 夹具刻意让分数与基线**持平**（60+... 与 p_same 的 0.80 无关，分数由报告决定）：
        # p_same = 80 killed / 20 survived ⇒ 0.80，两条分数都过 ⇒ 判红的理由只可能是记账。
        b_unbalanced = {"scope": "x", "total": 100, "mutationScore": 0.80, "testStrength": 0.80,
                        "killed": 60, "survived": 30, "noCoverage": 5, "timedOut": 0, "runError": 0}
        passed, problems = compare(parse_report(p_same), b_unbalanced)
        if not passed and any("基线记账不平" in x for x in problems):
            print("  [PASS] 基线记账不平被拦（60+30+5+0+0 = 95 ≠ 100）")
        else:
            print("  [FAIL] 基线记账不平竟然没被拦：%s" % problems)
            ok = False

        # ⑩ 记账自洽（含 ciObserved 块）的基线不许被这条新判据误报。
        b_balanced = {"scope": "x", "total": 100, "mutationScore": 0.80, "testStrength": 0.80,
                      "killed": 80, "survived": 20, "noCoverage": 0, "timedOut": 0, "runError": 0,
                      "ciObserved": {"total": 100, "killed": 79, "survived": 21,
                                     "noCoverage": 0, "timedOut": 0, "runError": 0}}
        passed, problems = compare(parse_report(p_same), b_balanced)
        if passed:
            print("  [PASS] 记账自洽（含 ciObserved）不误报")
        else:
            print("  [FAIL] 记账自洽被误报：%s" % problems)
            ok = False

        # ⑪ ciObserved 自己不平也要拦住（它是"异源那一次"的留档，错了同样误导人）。
        b_ci_unbalanced = {"scope": "x", "total": 100, "mutationScore": 0.80, "testStrength": 0.80,
                           "killed": 80, "survived": 20, "noCoverage": 0, "timedOut": 0,
                           "runError": 0,
                           "ciObserved": {"total": 100, "killed": 70, "survived": 20,
                                          "noCoverage": 0, "timedOut": 0, "runError": 0}}
        passed, problems = compare(parse_report(p_same), b_ci_unbalanced)
        if not passed and any("ciObserved" in x for x in problems):
            print("  [PASS] ciObserved 记账不平被拦")
        else:
            print("  [FAIL] ciObserved 记账不平没被拦：%s" % problems)
            ok = False

        # ⑫ 记账字段**残缺**也要拦住：否则"删掉一列"就等于"关掉这条判据"。
        # （反过来，一列都没有的老基线/夹具仍放行 —— 上面②③⑦⑧就是它的负向对照。）
        b_partial = {"scope": "x", "total": 100, "mutationScore": 0.80, "testStrength": 0.80,
                     "killed": 80, "survived": 20, "noCoverage": 0}
        passed, problems = compare(parse_report(p_same), b_partial)
        if not passed and any("记账字段残缺" in x for x in problems):
            print("  [PASS] 记账字段残缺被拦（缺 timedOut+runError 不算'没这回事'）")
        else:
            print("  [FAIL] 记账字段残缺没被拦：%s" % problems)
            ok = False

        # ⑬ 仓里那两份**真**基线的记账必须自洽。
        # 上面都是合成夹具；"真文件本身写错"只有直接读真文件才拦得住 ——
        # 而 --self-test 是每轮 CI 必跑的一步，不需要 PIT 报告就能判红。
        for name in ("mutation-baseline.json", "mutation-baseline-surveillance.json"):
            real = os.path.join(ROOT, "scripts", name)
            if not os.path.exists(real):
                print("  [FAIL] 找不到真基线文件：scripts/%s" % name)
                ok = False
                continue
            data = json.load(io.open(real, encoding="utf-8"))
            probs = check_accounting(data, name) + check_accounting(data.get("ciObserved"), name + ".ciObserved")
            if probs:
                print("  [FAIL] %s 账目不对：%s" % (name, probs[0]))
                ok = False
            else:
                print("  [PASS] %s 记账自洽（含 ciObserved）" % name)
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
        print("    mvn -B -pl cloud-backend -Pmutation test-compile pitest:mutationCoverage  # maven-incantations:ok")
        print("    （刻意不加 -am：profile 的 targetClasses 指向 security 包，")
        print("      带 -am 会让 PIT 在上游模块上各跑一遍，那里没有匹配的类 ⇒ 构建被打断）")
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
            # 写成当天日期：这个脚本现在也用于 surveillance 包的基线，
            # 写死日期会让那份基线的"测量时间"永远停在 security 包那次。
            "measuredAt": datetime.date.today().isoformat(),
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
        if any("报告不完整" in p for p in problems):
            print("  报告不完整 ≠ 分数退步，别急着补断言。先查：")
            print("    ① PIT 的 targetClasses 是否匹配到了全部类（看报告里的类清单）")
            print("    ② 构建日志里有没有类被跳过 / 编译失败 ③ 测量中途有没有改源码")
            print("  只有确认是**代码合法删减**导致变异体变少，才更新基线里的 total。")
        else:
            print("  变异分数是**棘轮**：要么把新写的代码测得更狠（补断言），")
            print("  要么显式更新基线（并在 PR 里说明为什么这次可以接受）。")
        return 1

    print("  变异基线门禁：通过（分数未回退）")
    if measured["survivors"]:
        # 存活数对账：优先与"最近一次 CI 实测"（ciObserved）比。基线主块是本地 XML 快照，
        # 与 CI 之间本就允许一个变异体的抖动 —— 拿它对账每次都会打印一个假"差 N"，
        # 于是那条真差（22↔23）也一起被当成噪音略过了。
        ci = baseline.get("ciObserved") or {}
        ref = ci.get("survived")
        ref_label = "上次 CI 实测" if ref is not None else "基线登记"
        if ref is None:
            ref = baseline.get("survived", "?")
        n = len(measured["survivors"])
        print("    存活变异体 %d 个（%s %s 个）—— 详情见报告" % (n, ref_label, ref))
        if isinstance(ref, int) and ref != n:
            print("    [账目差] 与%s差 %+d 个：分数没回退所以不判红，"
                  "但要么补断言、要么更新基线留档，别长期挂着" % (ref_label, n - ref))
    return 0


if __name__ == "__main__":
    sys.exit(main())
