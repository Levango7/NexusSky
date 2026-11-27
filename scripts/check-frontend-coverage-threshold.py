#!/usr/bin/env python3
"""
前端覆盖率阈值「实测计算 + 一致性核对」门禁。

为什么需要它：gcs-web 此前**没有任何覆盖率门禁**（vitest.config.js
的 coverage.thresholds 刻意为空，注释说明基线为零、先建测试再卡阈值）。
「加了阈值」和「阈值真的在拦」是两件事——本脚本把两件事都量化：

  1. 从 vitest 的 coverage-summary.json 取实测四指标（lines/statements/
     functions/branches）；
  2. 与 gcs-web/coverage-threshold.json 里声明的阈值比对：
     - 实测 < 声明 ⇒ FAIL（门禁会拦，构建必须红）；
     - 声明 > floor(实测/5)*5 ⇒ FAIL（人为把门槛抬到实测之上，会
       挡住正常构建）；
     - 声明 < floor(实测/5)*5 ⇒ WARN（覆盖率上涨后阈值没跟上，
       与 Java 侧 ci-coverage-threshold.sh 的非 --strict 行为一致：
         覆盖率自然上涨不该让构建变红，但必须有人看见）。

口径与 Java 侧一致：阈值 = 实测向下取整到 5%（覆盖率上涨时阈值
同步抬高，防止「实测涨、门槛不动」让门禁慢慢失效）。

用法：
    python3 scripts/check-frontend-coverage-threshold.py

退出码：0=自洽；1=有 FAIL。
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THRESHOLD = os.path.join(ROOT, 'gcs-web', 'coverage-threshold.json')
SUMMARY = os.path.join(ROOT, 'gcs-web', 'coverage', 'coverage-summary.json')
METRICS = ('lines', 'statements', 'functions', 'branches')


def floor5(v):
    return int(v // 5 * 5)


def main():
    if not os.path.isfile(THRESHOLD):
        print('❌ 缺 %s —— 前端覆盖率阈值未声明' % THRESHOLD)
        return 1
    if not os.path.isfile(SUMMARY):
        print('❌ 缺 %s —— 先跑 `npm run test:coverage` 产出报告' % SUMMARY)
        return 1

    with open(THRESHOLD, encoding='utf-8') as fh:
        declared = json.load(fh)
    with open(SUMMARY, encoding='utf-8') as fh:
        summary = json.load(fh)

    total = summary.get('total', {})
    measured = {}
    for m in METRICS:
        measured[m] = total.get(m, {}).get('pct')

    fail = 0
    warn = 0
    print('%-12s %10s %10s %10s  %s' %
          ('METRIC', 'MEASURED', 'DECLARED', 'POLICY', 'VERDICT'))
    for m in METRICS:
        meas = measured[m]
        decl = declared.get(m)
        if meas is None or decl is None:
            print('%-12s %10s %10s %10s  ❌ 缺指标' % (m, meas, decl, ''))
            fail += 1
            continue
        policy = floor5(meas)
        if meas < decl:
            verdict = '❌ 实测低于声明（门禁会拦）'
            fail += 1
        elif decl > policy:
            verdict = '❌ 声明高于策略地板（人为抬门槛）'
            fail += 1
        elif decl < policy:
            verdict = '⚠️  覆盖率上涨，阈值未跟上（应抬高）'
            warn += 1
        else:
            verdict = 'ok'
        print('%-12s %10.2f %10d %10d  %s' % (m, meas, decl, policy, verdict))

    print()
    if fail:
        print('FAIL %d 处。' % fail, file=sys.stderr)
        return 1
    if warn:
        print('WARN %d 处：覆盖率上涨后阈值未抬高（非阻塞，但应在本批或下批抬高）。' % warn)
    else:
        print('全部一致。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
