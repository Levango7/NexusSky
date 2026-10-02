#!/usr/bin/env python3
"""
门禁：文档里声称的单测数必须与 surefire 实测一致。

**为什么需要它**
---------------
本仓的文档里长期写着「3230 单测全绿」，而实测早已比它高出七百多。这个数字在某个
时点是对的，此后每批新增测试都没人回来改，最后销售材料、定价文档、技术白皮书、路线图
一起对外引用了一个偏小两成的数字。

根因不是"忘了改"，而是**没有任何信号会变红**。加测试不会让文档过期这件事变红，
所以它可以过期很久。本脚本补上这个信号：用 surefire XML 的实际用例数去核对所有
**当前口径**文档里的声称值，对不上就失败。

**范围：为什么排除 CHANGELOG.md**
-------------------------------
CHANGELOG 是**历史记录**，记的是"当时是什么状态"。改写历史条目等于篡改记录——
例如 CHANGELOG 里那条「测试数 3230（实为 3787）」本身就是在描述一次历史修复，
把它改成今天的数字会让那次修复的叙述失真。故 CHANGELOG 显式排除。

同理，文档里**追述旧值**的行也不校验：ROADMAP 里那句「此前此处写「6 轮 / 32 问题 /
3230 单测」」是引述一个已被本轮修正的错误值，把它改成今天的数字会让句子变成
"此前写的是 3994"——一句假话。见 ``RETROSPECTIVE``。

已知局限：追述判定是**逐行**的。若一个追述句跨行、数字落在续行上（该续行本身不含
追述标记），那行仍会被当当前口径校验。本仓当前 12 行命中已逐条人工核对，确认均为
真正的历史叙述；新增文档请把「追述 + 数字」写在同一行。

**用法**
------
    python scripts/check-test-count-docs.py                    # 用本地已跑出的 surefire 产物
    python scripts/check-test-count-docs.py --root F:/repo     # 指定仓库根
    python scripts/check-test-count-docs.py --print-actual     # 只打印实测数，不校验
    python scripts/check-test-count-docs.py --reports-root D   # 从 D/<module>/TEST-*.xml 汇总

``--reports-root`` 供 CI 使用：各模块 job 把 surefire 报告按模块名上传为 artifact，
汇总 job 下载后用本模式统计，从而**复用 java job 已经跑过的那次测试**，不必为门禁
再跑一遍全 reactor（否则同一批用例在 CI 里要跑两遍）。

退出码：0=一致，1=存在不一致或找不到产物。
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

# 当前口径文档：这些文件里的单测数是对外声称，必须与实测一致。
# 注意这里刻意不含 CHANGELOG.md——它是历史记录，改写等于篡改。
CURRENT_DOCS = [
    'README.md',
    'ROADMAP.md',
    'docs/whitepaper.md',
    'docs/sales-pitch-deck.md',
    'docs/pricing-strategy.md',
    'docs/demo-scenarios.md',
    'docs/customer-onboarding-guide.md',
    'docs/commercialization-plan.md',
    'docs/competitive-analysis.md',
    'docs/low-altitude-economy-demand-research.md',
]

# 参与统计的模块。与根 pom 的 <modules> 一致；漏一个会让总数偏小，
# 从而把文档里的正确数字判成错——比反过来更难排查，所以显式列出并在 CI 模式下断言齐全。
EXPECTED_MODULES = [
    'mavlink-core',
    'drone-sim',
    'cloud-backend',
    'link-sim',
    'sdk-java',
    'regulator-sim',
]

# 形如 "3230 单测" / "3869 个测试" / "共 3988 例" 的数字声称
CLAIM_PATTERNS = [
    re.compile(r'(\d{3,5})\s*(?:条|个)?\s*单测'),
    re.compile(r'(\d{3,5})\s*(?:条|个)?\s*(?:单元测试|测试用例)'),
    re.compile(r'(?:测试|单测)(?:用例)?\s*(?:共|计|总计)?\s*[:：]?\s*(\d{3,5})'),
]

# 表格行里表示"全仓合计"的首列写法
TOTAL_LABELS = {'总计', '合计', '共计', '总数'}

# 追述标记：命中即认为该行在引述一个已被取代的历史值，不参与校验。
RETROSPECTIVE = ('此前', '原先', '曾经', '曾写', '已过期', '旧值', '改前', '修正前')

# 表格单元格里剥掉 Markdown 强调/代码标记
_CELL_NOISE = re.compile(r'[*`\s]')

# 表头出现这些词，才认为这是一张"测试数"表。
# 必要：仓库里还有端口表（| 服务 | 端口 | 8080 |）与人天表（| **合计** | **31** |），
# 早先只按"首列是模块名 + 第二列是数字"判定，把 8080/14540/31 全当成了单测数误报。
_CELL_SEP = re.compile(r'^:?-{2,}:?$')
TEST_HEADER_HINT = ('单测', '测试数', '单元测试', '测试用例', '用例数')


def _read_suite(path):
    """解析一份 surefire XML，返回 (用例数, 失败, 错误, 跳过) 或 None。"""
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        return None
    return (int(suite.get('tests', 0)),
            int(suite.get('failures', 0)),
            int(suite.get('errors', 0)),
            int(suite.get('skipped', 0)))


def measure_actual(root, reports_root=None):
    """汇总 surefire 报告。

    返回 (总数, 失败, 错误, 跳过, 文件数, 每模块计数, 缺失模块)。

    - 默认模式：递归扫 ``*/target/surefire-reports/TEST-*.xml``，模块名由相对路径推出。
    - ``reports_root`` 模式：只扫 ``<reports_root>/<module>/TEST-*.xml``，模块名取目录名。
      这是 CI 汇总模式，artifact 下载到哪个模块目录就算哪个模块。
    """
    total = 0
    failures = errors = skipped = 0
    files = 0
    per_module = {}

    def absorb(module, path):
        nonlocal total, failures, errors, skipped, files
        got = _read_suite(path)
        if got is None:
            return
        n, f, e, s = got
        total += n
        failures += f
        errors += e
        skipped += s
        per_module[module] = per_module.get(module, 0) + n
        files += 1

    if reports_root:
        for module in sorted(os.listdir(reports_root)):
            mdir = os.path.join(reports_root, module)
            if not os.path.isdir(mdir):
                continue
            for fn in os.listdir(mdir):
                if fn.startswith('TEST-') and fn.endswith('.xml'):
                    absorb(module, os.path.join(mdir, fn))
    else:
        for dirpath, _dirnames, filenames in os.walk(root):
            if 'surefire-reports' not in dirpath.replace('\\', '/'):
                continue
            rel = os.path.relpath(dirpath, root).replace('\\', '/')
            module = rel.split('/target/')[0] if '/target/' in rel else os.path.basename(dirpath)
            for fn in filenames:
                if fn.startswith('TEST-') and fn.endswith('.xml'):
                    absorb(module, os.path.join(dirpath, fn))

    missing = [m for m in EXPECTED_MODULES if m not in per_module]
    return total, failures, errors, skipped, files, per_module, missing


def _is_retrospective(line):
    return any(marker in line for marker in RETROSPECTIVE)


def test_table_rows(lines):
    """找出所有「测试数」表的**数据行**行号集合。

    判定依据是表头含单测/测试数/用例数等词，而不是"看起来像数字"——
    后者会把端口表和人天表一起吞进来。表结构按 GFM：表头行 + 分隔行（`|---|---|`）
    + 数据行。
    返回的是 **1 基**行号，与 ``enumerate(lines, 1)`` 对齐——早先误用 0 基索引，
    整张表因此一条都没命中（表现为"README 主测试表漏检"）。
    """
    rows = set()
    i = 0
    n = len(lines)
    while i < n:
        if not lines[i].lstrip().startswith('|'):
            i += 1
            continue
        j = i
        while j < n and lines[j].lstrip().startswith('|'):
            j += 1
        block = list(range(i, j))
        if len(block) >= 3 and _is_separator(lines[block[1]]):
            if any(h in lines[block[0]] for h in TEST_HEADER_HINT):
                rows.update(k + 1 for k in block[2:])   # block[1] 是分隔行
        i = j
    return rows


def _is_separator(line):
    # 先整行 strip 掉换行符：否则 '|---|---|\\n' 切分后会多出一个空单元格
    # （'---'、'---'、''），空串过不了 _CELL_SEP，整张表被判为非表格。
    cells = [c.strip() for c in line.strip().strip('|').split('|') if c.strip()]
    return bool(cells) and all(_CELL_SEP.match(c) for c in cells)


def row_claim(line, per_module):
    """从「测试数」表的数据行取 (作用域, 声称值, 是否精确)。

    两种作用域：

    - ``| `mavlink-core` | 449 |``      首列是已知模块名 → 该模块
    - ``| **总计** | **3994** |``         首列是合计字样 → 全仓

    第二列必须整体是整数或 ``~`` 前缀的整数，否则不是测试数。
    以 README 第 27 行为例，第二列是「纯 Java 17」而非数字，会被正确忽略——
    那一行的数字由 CLAIM_PATTERNS 文本路径负责。

    ``~1823`` 这类近似值返回 exact=False：门禁无法核对它，也无法让它随测试增长
    自动过期，所以调用方应当判红并要求写准数，而不是默默放过。
    """
    stripped = line.lstrip()
    if not stripped.startswith('|'):
        return None
    cells = stripped.split('|')
    if len(cells) < 3:
        return None
    name = _CELL_NOISE.sub('', cells[1])
    raw = _CELL_NOISE.sub('', cells[2])
    exact = True
    if raw.startswith('~'):
        exact, raw = False, raw[1:]
    if not raw.isdigit():
        return None
    if name in per_module:
        return name, int(raw), exact
    if name in TOTAL_LABELS:
        return '__total__', int(raw), exact
    return None


def scope_of(line, per_module):
    """判断文本声称是「全仓总数」还是「某模块」。

    形如 ``| `mavlink-core` | ... | **449 个单测** |`` 的表格行，首列是模块名，
    其数字是该模块的用例数；其余按全仓总数核对。
    """
    if line.lstrip().startswith('|'):
        first_cell = line.split('|')[1] if '|' in line else ''
        name = _CELL_NOISE.sub('', first_cell)
        if name in per_module:
            return name
    return None


def main():
    ap = argparse.ArgumentParser(description='校验文档声称的单测数与 surefire 实测一致')
    ap.add_argument('--root', default=os.path.abspath(os.path.join(os.path.dirname(__file__), '..')),
                    help='仓库根目录（默认取脚本上级目录）')
    ap.add_argument('--reports-root', default=None,
                    help='从该目录下的 <module>/TEST-*.xml 汇总（CI 聚合模式）')
    ap.add_argument('--print-actual', action='store_true', help='只打印实测数')
    args = ap.parse_args()

    (actual, failures, errors, skipped, files,
     per_module, missing) = measure_actual(args.root, args.reports_root)

    if files == 0:
        if args.reports_root:
            print('目录下没有 TEST-*.xml：%s' % args.reports_root, file=sys.stderr)
        else:
            print('找不到 surefire 产物（*/target/surefire-reports/TEST-*.xml），'
                  '请先跑 mvn test', file=sys.stderr)
        return 1

    print('实测：%d 用例 / %d 失败 / %d 错误 / %d 跳过（来自 %d 个测试类）'
          % (actual, failures, errors, skipped, files))
    print('  分模块：' + '，'.join('%s=%d' % kv for kv in sorted(per_module.items())))

    if missing:
        # 缺模块意味着 total 偏小，此时任何校验结论都不可信，先红。
        print('缺少模块的 surefire 报告：%s。总数偏小，校验结果不可信，先补齐。'
              % '，'.join(missing), file=sys.stderr)
        return 1

    if args.print_actual:
        return 0

    if failures or errors:
        print('有测试未通过，先修测试再谈文档口径', file=sys.stderr)
        return 1

    print('\n校验当前口径文档：')
    bad = 0
    for rel in CURRENT_DOCS:
        path = os.path.join(args.root, rel)
        if not os.path.exists(path):
            print('  ?? %s 不存在，跳过' % rel)
            continue
        with open(path, encoding='utf-8') as fh:
            lines = fh.readlines()
        table_rows = test_table_rows(lines)
        hits = []
        approx = []
        skipped_retro = 0
        for lineno, line in enumerate(lines, 1):
            if _is_retrospective(line):
                skipped_retro += 1
                continue
            # 表格型模块/合计行：不要求本行出现"单测"字样（README 的测试规模表
            # 数据行是 "| `mavlink-core` | 449 |"，不带关键词——早先按关键词过滤
            # 会把整张表漏检，那正是最该被核对的地方）。
            if lineno in table_rows:
                claim = row_claim(line, per_module)
                if claim:
                    scope, claimed, exact = claim
                    expected = actual if scope == '__total__' else per_module[scope]
                    if exact:
                        hits.append((lineno, claimed, expected, scope, line.strip()))
                    else:
                        approx.append((lineno, scope, line.strip()))
                    continue
            if '单测' not in line and '测试用例' not in line and '单元测试' not in line:
                continue
            scope = scope_of(line, per_module)
            expected = per_module[scope] if scope else actual
            for pat in CLAIM_PATTERNS:
                for m in pat.finditer(line):
                    claimed = int(m.group(1))
                    if 100 <= claimed <= 200_000:      # 过滤明显不是测试数的巧合数字
                        hits.append((lineno, claimed, expected, scope, line.strip()))

        note = '，跳过 %d 行追述旧值' % skipped_retro if skipped_retro else ''
        if not hits and not approx:
            print('  ok %s（无单测数声称%s）' % (rel, note))
            continue
        bad += len(approx)
        for lineno, scope, text in approx:
            what = '全仓' if scope == '__total__' else '模块 %s' % scope
            print('  ✗ %s:%d [%s] 用了近似值 "~~N~~"，门禁无法核对也无法让它随测试增长过期，'
                  '请写准数' % (rel, lineno, what))
            print('      %s' % (text[:120] + ('…' if len(text) > 120 else '')))
        wrong = [h for h in hits if h[1] != h[2]]
        bad += len(wrong)
        for lineno, claimed, expected, scope, text in wrong:
            what = '全仓' if scope in (None, '__total__') else '模块 %s' % scope
            print('  ✗ %s:%d [%s] 声称 %d，实测 %d' % (rel, lineno, what, claimed, expected))
            print('      %s' % (text[:120] + ('…' if len(text) > 120 else '')))
        if not wrong and not approx:
            scope_desc = '/'.join(sorted({h[3] for h in hits if h[3] and h[3] != '__total__'})) or '全仓'
            print('  ok %s（%d 处，%s%s）' % (rel, len(hits), scope_desc, note))

    print()
    if bad:
        print('不一致 %d 处。新增测试后请同步更新上述文档，或用 '
              '`python scripts/check-test-count-docs.py --print-actual` 取实测数。' % bad,
              file=sys.stderr)
        return 1
    print('全部一致。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
