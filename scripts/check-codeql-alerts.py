#!/usr/bin/env python3
"""CodeQL 开放告警数守卫。

为什么需要它：2026-10-11 发现仓上有 4 条 open 告警，全部停留在 2026-09-25 创建后
从未更新，而逐条对照代码后确认**代码处置都已存在**（见 docs/codeql-alert-disposition.md）。
这类"面板登记滞后于代码"不会让任何测试转红，于是永远留在那里——代价是将来真出现
新告警时，会被这 4 条历史噪音淹没。

设计取向：**默认全关、例外显式**。
  - `--max-open N`：允许 open 数上限，默认 0
  - 若将来确需保留某条（例如等上游修），必须显式把阈值提上去，并在
    docs/codeql-alert-disposition.md 登记理由——不允许悄悄堆积。

Token 获取顺序（与仓内 CI 一致）：
  1. $CODEQL_ALERTS_JSON —— 直接喂 JSON（离线自检/CI 缓存用）
  2. `gh api repos/<owner>/<repo>/code-scanning/alerts?state=open`
  3. 都没有时：**无法判定 ⇒ 退出码 2**，并在 stderr 说明为什么不是 0
     （静默跳过会让"没查到"伪装成"0 条 open"，那正是本守卫要消灭的失效面）
"""
import argparse
import json
import os
import subprocess
import sys


def gh_open_count(repo):
    o = subprocess.run(
        ("gh", "api", "repos/%s/code-scanning/alerts?state=open&per_page=100" % repo),
        capture_output=True)
    if o.returncode != 0:
        raise RuntimeError(o.stderr.decode("utf-8", "replace")[:200])
    data = json.loads(o.stdout.decode("utf-8"))
    if isinstance(data, dict):
        raise RuntimeError("GitHub 返回了对象而非数组：%s" % list(data)[:5])
    return data


def parse_inline(payload):
    """解析内联告警 JSON。

    **致命细节**：GitHub 在限流/出错时返回的是**对象**（{"message": "API rate
    limit exceeded"}），不是数组。若把"对象"兜底成空数组，"限流"就会被当成
    "0 条 open" 而放行 —— 那正是本守卫要消灭的失效面（实现这一步时我自己
    踩过，由负向验证抓到）。因此对象一律视为取数失败。
    """
    data = json.loads(payload)
    if isinstance(data, dict):
        # 唯一合法对象形态：显式包了 alerts 数组
        if "alerts" in data and isinstance(data["alerts"], list):
            return data["alerts"]
        raise ValueError(
            "期望告警数组，收到对象（%s）；GitHub 限流/出错时就是这样，"
            "不能当作 0 条处理" % (list(data)[:3],))
    if not isinstance(data, list):
        raise ValueError("JSON 既不是数组也不是带 alerts 的对象：%s" % type(data).__name__)
    return data


def main():
    ap = argparse.ArgumentParser(description="CodeQL open alert count guard")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""),
                    help="owner/repo；缺省取 $GITHUB_REPOSITORY")
    ap.add_argument("--max-open", type=int, default=0,
                    help="允许的 open 告警数上限（默认 0）")
    ap.add_argument("--allow", type=int, action="append", default=[],
                    help="已登记的告警编号（可重复）。这些编号不计入 open 数，"
                         "但必须在 docs/codeql-alert-disposition.md 写明处置依据")
    ap.add_argument("--json", dest="inline_json", default=os.environ.get(
        "CODEQL_ALERTS_JSON", ""),
        help="直接喂告警 JSON（离线自检/CI 缓存用），优先级高于 gh api")
    ap.add_argument("--self-test", action="store_true",
                    help="离线自检：用内置夹具证明守卫真的会红，且输入不可判定时"
                        "不会假装通过")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    alerts = None
    source = None
    err = None
    try:
        if args.inline_json:
            alerts = parse_inline(args.inline_json)
            source = "CODEQL_ALERTS_JSON"
        elif args.repo:
            alerts = gh_open_count(args.repo)
            source = "gh api %s" % args.repo
    except Exception as e:  # noqa: BLE001 - 要把任何取数失败变成显式非零
        err = e

    if alerts is None:
        print("无法取得 CodeQL open 告警数（%s）⇒ **不能**当作 0 条处理。" % err,
              file=sys.stderr)
        print("请提供 CODEQL_ALERTS_JSON，或确保 gh 已认证且能访问 code-scanning API。",
              file=sys.stderr)
        return 2

    n = len(alerts)
    allowed = set(args.allow)
    unexpected = [a for a in alerts if a.get("number") not in allowed]
    printed = len(unexpected)
    print("数据源: %s" % source)
    print("open 告警: %d（其中已登记 %d，未登记 %d；阈值 %d）"
          % (n, n - len(unexpected), len(unexpected), args.max_open))

    if unexpected and (printed > args.max_open):
        print("\n❌ 未登记的 open 告警 %d 条，超过阈值 %d。"
              % (printed, args.max_open), file=sys.stderr)
        by_rule = {}
        for a in unexpected:
            r = a.get("rule", {}).get("id", "?")
            by_rule.setdefault(r, []).append(a.get("number"))
        for r, nums in sorted(by_rule.items()):
            print("  %-42s %s" % (r, nums), file=sys.stderr)
        print("\n两种处置方式："
              "\n  1) 确认是误报/已修 → 去面板 dismiss，并在 "
              "docs/codeql-alert-disposition.md 记录依据"
              "\n  2) 确认需保留 → 在 workflow 里显式 --allow <编号>，"
              "并在同一文档登记理由"
              "\n不允许既不 dismiss 也不登记地堆积。",
              file=sys.stderr)
        return 1

    if n:
        print("（阈值内仍有 %d 条，按登记口径允许）" % n)
    print("✓ 在阈值内")
    return 0


def self_test():
    """五处夹具：超阈值必红、阈值内必绿、allow 可豁免、默认阈值 0 必红、取数失败必非零。"""
    ok = True

    # 1) 超阈值必红
    payload = json.dumps({"alerts": [
        {"number": 1, "rule": {"id": "js/request-forgery"}},
        {"number": 2, "rule": {"id": "java/tainted-numeric-cast"}}]})
    rc = _run_with(["--json", payload, "--max-open", "1"])
    print("超阈值(未登记 2 > 1) => rc=%s %s"
          % (rc, "OK" if rc == 1 else "**不符合预期**"))
    ok &= rc == 1

    # 2) 阈值内必绿
    rc = _run_with(["--json", payload, "--max-open", "2"])
    print("阈值内(未登记 2 <= 2) => rc=%s %s"
          % (rc, "OK" if rc == 0 else "**不符合预期**"))
    ok &= rc == 0

    # 3) --allow 豁免：同样 2 条，全部登记后应转绿
    rc = _run_with(["--json", payload, "--allow", "1", "--allow", "2"])
    print("全部 --allow 后(未登记 0) => rc=%s %s"
          % (rc, "OK" if rc == 0 else "**不符合预期**"))
    ok &= rc == 0

    # 4) --allow 只豁免了一条：剩下 1 条仍应红
    rc = _run_with(["--json", payload, "--allow", "1"])
    print("部分 --allow(未登记 1 > 0) => rc=%s %s"
          % (rc, "OK" if rc == 1 else "**不符合预期**"))
    ok &= rc == 1

    # 5) 取数失败必须非零，绝不能"当作 0 条"放行
    rc = _run_with(["--repo", "nonexistent/definitely-not-here-xyz"])
    print("取数失败 => rc=%s %s" % (rc, "OK" if rc == 2 else "**不符合预期**"))
    ok &= rc == 2

    # 6) 限流返回的对象形态绝不能兜底成空数组
    rc = _run_with(["--json", json.dumps({"message": "API rate limit exceeded"})])
    print("限流对象 => rc=%s %s" % (rc, "OK" if rc == 2 else "**不符合预期**"))
    ok &= rc == 2

    print("\n自检%s" % ("通过" if ok else "未通过"))
    return 0 if ok else 1


def _run_with(argv):
    """以子进程跑本脚本，避免 main() 内的 stdout 污染自检输出。"""
    o = subprocess.run([sys.executable, os.path.abspath(__file__)] + argv,
                       capture_output=True)
    return o.returncode


if __name__ == "__main__":
    raise SystemExit(main())
