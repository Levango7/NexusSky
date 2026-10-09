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

**前端用例数（2026-10-06 补）**
--------------------------
此前本脚本只读 surefire XML，即**只管 Java**。前端从 2026-10-01 起有 vitest 用例后，
它的用例数完全没有约束力——实测 README 的「168 例」已过期到 177 例，长期无人发现，
原因与上面 Java 侧同一个：加前端测试不会让文档过期这件事变红。

故新增 ``--frontend-json``：读 vitest 的 JSON 报告（``numTotalTests``），
核对文档里的前端用例数声称。与 Java 侧共用 ``CURRENT_DOCS`` 与"追述旧值"豁免，
但用**独立的、更窄的**匹配文法（``FRONTEND_CLAIM_PATTERNS``）——不能复用 Java 侧的
宽泛正则：README「与后端 3869 例形成断层」这一行同时含「前端」与「例」，会被宽泛文法
当成前端声称而误报。前端文法要求限定词紧贴数字（`前端测试覆盖 177 例` /
`前端已有 168 例 vitest` / `177 例 vitest`），宁可漏检也不误报。

**为什么不用静态数 ``it(``**
---------------------------
静态计数给出 156（实测 177）：``test/flightCommands.test.jsx`` 与
``test/singleSourceOfTruth.test.js`` 里有用例在循环里生成（5 / 12 / 7 三处），
静态文本数与运行时数天然不等。用 156 当基准会把**正确的**文档判成错——
比漏检更糟，所以取 vitest 自己的报告。

**用法**
------
    python scripts/check-test-count-docs.py                    # 用本地已跑出的 surefire 产物
    python scripts/check-test-count-docs.py --root F:/repo     # 指定仓库根
    python scripts/check-test-count-docs.py --print-actual     # 只打印实测数，不校验
    python scripts/check-test-count-docs.py --reports-root D   # 从 D/<module>/TEST-*.xml 汇总
    python scripts/check-test-count-docs.py --frontend-json gcs-web/vitest-report.json

``--reports-root`` 供 CI 使用：各模块 job 把 surefire 报告按模块名上传为 artifact，
汇总 job 下载后用本模式统计，从而**复用 java job 已经跑过的那次测试**，不必为门禁
再跑一遍全 reactor（否则同一批用例在 CI 里要跑两遍）。

``--frontend-json`` 同理复用 frontend job 已经跑过的那次 vitest（``npm run test:ci``）。

退出码：0=一致，1=存在不一致或找不到产物。
"""
import argparse
import io
import json
import os
import re
import shutil
import sys
import tempfile
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
    # 2026-10-05 补入：一页纸产品介绍是对外第一手材料，此前游离在门禁之外，
    # 其中的「4,239 个后端单测」既不被核对、也无信号会因测试增长而变红。
    'docs/product-brief.md',
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

# 形如 "3230 单测" / "3869 个测试" / "共 3988 例" 的数字声称。
# 数字部分统一用 ``_NUM`` 片段容忍千分位逗号：``4,239`` 与 ``4239`` 必须等价，
# 否则 \d{3,5} 只会截到 ``239`` 并当成合法声称值，产生**误报**（2026-10-05 修）。
_NUM = r'(\d{1,3}(?:[,\uFF0C]\d{3})+|\d{3,5})'
# 「数量词 + 可选限定词 + 单测」：容忍 "4,239 个后端单测" / "4239 个单测" 两种写法。
# 早先写成 ``_NUM\s*(?:条|个)?\s*单测``，要求「个」与「单测」相邻，于是
# product-brief.md 的「4,239 个后端单测」整句逃检（2026-10-05 修）。
CLAIM_PATTERNS = [
    re.compile(_NUM + r'\s*(?:条|个)?\s*(?:[\u4e00-\u9fa5]{0,6})?单测'),
    re.compile(_NUM + r'\s*(?:条|个)?\s*(?:单元测试|测试用例)'),
    re.compile(r'(?:测试|单测)(?:用例)?\s*(?:共|计|总计)?\s*[:：]?\s*' + _NUM),
    # 英文口径，形如 "4234 tests" / "4239 unit tests"。
    # 必要：demo-scenarios.md 与 sales-pitch-deck.md 曾用英文写总测数，
    # 因不含「单测/测试用例」中文关键词而被整段漏检（2026-10-05 修复）。
    re.compile(_NUM + r'\s*(?:unit\s+)?tests?\b', re.IGNORECASE),
]

# 前端用例数声称（2026-10-06）。**刻意比 Java 侧窄得多**。
#
# 为什么不复用 CLAIM_PATTERNS：README「与后端 3869 例形成断层」这一行同时含
# 「前端」与「例」，按 Java 侧的宽泛文法会被当成前端声称，而实测是 177 →
# 把一句正确的表述判成错。误报比漏检更贵（门禁一旦有噪声就会被整体忽略）。
#
# 因此要求限定词与数字**相邻**：
#   前端测试覆盖 177 例 / 前端已有 168 例 vitest / 177 例 vitest
# 宁可漏检（新增文档请照此写法），不可误报。
FRONTEND_CLAIM_PATTERNS = [
    re.compile(r'前端\s*(?:测试)?\s*(?:覆盖|已有|共|计|共计)?\s*[:：]?\s*' + _NUM + r'\s*例'),
    re.compile(_NUM + r'\s*例\s*(?:的\s*)?vitest', re.IGNORECASE),
    re.compile(_NUM + r'\s*front-?end\s+tests?', re.IGNORECASE),
]


def _parse_count(text):
    """把 ``4,239`` / ``4239`` / ``4，239`` 统一解析成整数。"""
    return int(re.sub(r'[,\uFF0C]', '', text))

# 表格行里表示"全仓合计"的首列写法
TOTAL_LABELS = {'总计', '合计', '共计', '总数'}

# 章节级豁免：这些章节标题下的全部内容视为**历史台账**，与 CHANGELOG 同性质，
# 其中的测数是"当时那一刻"的快照，不参与当前口径校验。
#
# 为什么需要它：commercialization-plan.md 的「六、代码审查修复记录」逐 commit 记录
# 每一轮修复后的测数（1692 → 1934 → 3266），这些值在当时都是真的。若强行改成今天
# 的数字，那张「更新前 → 更新后」的对比表就变成了假话。逐行追述标记覆盖不到这种
# 形态（"测试验证"行本身不含 commit 字样，commit 在上一行），故按章节整体豁免。
RETROSPECTIVE_SECTIONS = {
    'docs/commercialization-plan.md': ('六、代码审查修复记录',),
}

# Markdown 章节标题（## / ### ...），用于划定章节级豁免范围
_HEADING = re.compile(r'^#{2,6}\s+(.*\S)\s*$')

# 追述标记：命中即认为该行在引述一个已被取代的历史值，不参与校验。
RETROSPECTIVE = ('此前', '原先', '曾经', '曾写', '已过期', '旧值', '改前', '修正前',
                 # 「更新前 / 更新后」对比表列头：整表记的是历史演进，不是当前口径。
                 '更新前', '更新后',
                 # 「测试验证：... (commit abc1234)」型历史记录。
                 # commercialization-plan.md 的章节按 commit 记录每一轮修复后的测数，
                 # 那些数字**在当时是对的**，改成今天的值是篡改历史（2026-10-05 修）。
                 'commit ')

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


def _suite_class(fn):
    """从 ``TEST-<fqcn>.xml`` 取测试类全名；``$Nested`` 归到宿主类（surefire 为每个
    嵌套类各写一份报告，但源文件只有宿主类那一份）。"""
    name = fn[len('TEST-'):-len('.xml')]
    return name.split('$')[0]


def _class_has_source(root, module, fqcn):
    """该测试类在本模块 ``src/test`` 下是否还有源文件。

    为什么必须查：**surefire 报告目录不会被清理**。`mvn test`（不带 clean）只覆盖
    本轮跑过的类，早已改名/删除的类留下的旧 XML 会一直被算进总数。实测踩坑：
    2026-10-06 某轮临时探针用例 `io.aerofleet.sim.PortReleaseProbeTest` 验后删除，
    类不在了、XML 还在，本地量到 drone-sim=1392 而 CI（干净 checkout）是 1391——
    门禁本身不会因此变红，反而是"把带病的数字写进对外文档"这条路径被打开了。
    """
    rel = fqcn.replace('.', os.sep) + '.java'
    expected = os.path.join(root, module, 'src', 'test', 'java', rel)
    if os.path.isfile(expected):
        return True
    # 兜底：类可能写在同名之外的文件里（Java 允许包级私有类文件名不同）
    probe = fqcn.split('.')[-1]
    test_dir = os.path.join(root, module, 'src', 'test', 'java')
    if not os.path.isdir(test_dir):
        return True  # 拿不到源码树时不判陈旧——宁可少报，不可误杀
    for dirpath, _dirnames, filenames in os.walk(test_dir):
        for f in filenames:
            if f.endswith('.java'):
                try:
                    with open(os.path.join(dirpath, f), 'r', encoding='utf-8',
                              errors='ignore') as fh:
                        if probe in fh.read():
                            return True
                except OSError:
                    continue
    return False


def measure_actual(root, reports_root=None):
    """汇总 surefire 报告。

    返回 (总数, 失败, 错误, 跳过, 文件数, 每模块计数, 缺失模块, 陈旧报告列表)。

    - 默认模式：递归扫 ``*/target/surefire-reports/TEST-*.xml``，模块名由相对路径推出。
    - ``reports_root`` 模式：只扫 ``<reports_root>/<module>/TEST-*.xml``，模块名取目录名。
      这是 CI 汇总模式，artifact 下载到哪个模块目录就算哪个模块。
    - 两种模式都剔除「类已不存在于 src/test」的陈旧报告（见 ``_class_has_source``），
      并把剔除项回传给调用方打印——剔除必须可见，静默剔除等于换了个方式的错数。
    """
    total = 0
    failures = errors = skipped = 0
    files = 0
    per_module = {}
    stale = []

    def absorb(module, path):
        nonlocal total, failures, errors, skipped, files
        if not _class_has_source(root, module, _suite_class(os.path.basename(path))):
            stale.append('%s: %s' % (module, os.path.basename(path)))
            return
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
    return total, failures, errors, skipped, files, per_module, missing, stale


def _is_retrospective(line):
    return any(marker in line for marker in RETROSPECTIVE)


def retrospective_section_lines(lines, rel):
    """返回属于「历史台账章节」的行号集合（1 基），见 RETROSPECTIVE_SECTIONS。

    规则：命中标题前缀后，直到**同级或更高级**的标题为止，整段豁免。
    例：豁免「## 六、...」后，其下所有 ### / #### 子节一并豁免，
    直到下一个「## 」为止。
    """
    prefixes = RETROSPECTIVE_SECTIONS.get(rel)
    if not prefixes:
        return set()
    exempt = set()
    active_level = None
    for lineno, line in enumerate(lines, 1):
        m = _HEADING.match(line)
        if m:
            level = len(line) - len(line.lstrip('#'))
            title = m.group(1)
            if active_level is not None and level <= active_level:
                active_level = None          # 同级或更高级标题 → 豁免段结束
            if any(title.startswith(p) or p in title for p in prefixes):
                active_level = level
        if active_level is not None:
            exempt.add(lineno)
    return exempt


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

    第二列必须**以整数开头**（允许后跟「全绿/PASS/个/例」等说明文字），否则不是测试数。
    以 README 第 27 行为例，第二列是「纯 Java 17」而非数字，会被正确忽略——
    那一行的数字由 CLAIM_PATTERNS 文本路径负责。

    历史坑（2026-10-05 修）：早先要求第二列 ``strip`` 后**完全是数字**，于是
    ``| **单元测试** | 4234 全绿 |`` 这类「数字 + 说明」的写法整行逃检，
    销售材料里的 4234 因此长期未被门禁抓到。现改为「正则抓取前导整数」。

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
    if not name:
        return None
    exact = True
    if raw.startswith('~'):
        exact, raw = False, raw[1:]
    # 抓取前导整数（容忍千分位逗号与其后紧跟的说明文字「全绿」「PASS」「个」等）
    m = re.match(r'(\d{1,3}(?:[,\uFF0C]\d{3})+|\d+)', raw)
    if not m:
        return None
    value = _parse_count(m.group(1))
    # 常识过滤：单测数不会小到 2 位数；防把「第 3 阶段」之类误当测试数
    if value < 100:
        return None
    if name in per_module:
        return name, value, exact
    if name in TOTAL_LABELS:
        return '__total__', value, exact
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


def measure_frontend(report_path):
    """从 vitest JSON 报告取实测前端用例数。

    返回 ``(总数, 通过数, 失败数)``；报告不存在或字段缺失时抛 ``FileNotFoundError``
    / ``ValueError``，由调用方决定是红还是跳过。

    刻意**不**接受"没给 --frontend-json 就当 0"：那等于把「没跑前端测试」与
    「前端零用例」混为一谈，而后者不可能（test/ 与 src/ 下都有测试文件）。
    """
    if not os.path.exists(report_path):
        raise FileNotFoundError(report_path)
    with open(report_path, encoding='utf-8') as fh:
        data = json.load(fh)
    for key in ('numTotalTests', 'numPassedTests', 'numFailedTests'):
        if key not in data:
            raise ValueError('vitest 报告缺少字段 %s（不是 vitest json reporter 的产物？）' % key)
    return data['numTotalTests'], data['numPassedTests'], data['numFailedTests']


def find_conflict_marks(root, docs):
    """返回 [(rel, [行号...])]，命中的文档带有未解决的 merge 冲突标记。"""
    hits = []
    for rel in docs:
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            continue
        with open(path, encoding='utf-8') as fh:
            lines = fh.readlines()
        marks = [i + 1 for i, l in enumerate(lines)
                 if l.startswith('<<<<<<< ') or l.startswith('>>>>>>> ')]
        if marks:
            hits.append((rel, marks))
    return hits


def self_test():
    """离线自检：证明「冲突标记检出」这道防线真的会红，且不会误伤干净文档。

    没有自检的门禁 = 又一个"看起来在检查"的东西。2026-10-08 那次静默失效
    正是因为这类防护从未被证明有效过。

    夹具全部落在临时目录，不碰真实仓库。
    """
    # 用真实存在的测试类构造 surefire XML，避免自检依赖是否跑过 mvn。
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(here)
    docs = ['README.md']
    ok = True

    def build(case_doc):
        tmp = tempfile.mkdtemp(prefix='countgate-selftest-')
        reports = os.path.join(tmp, 'reports')
        # 每个模块各 1 例：满足"缺模块即红"的前置条件，
        # 这样自检走的是与 CI 完全同一条代码路径，而不是旁路。
        for m in EXPECTED_MODULES:
            pkg = os.path.join(reports, m)
            os.makedirs(pkg)
            xml = (
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<testsuite name="%s" tests="1" failures="0" errors="0" skipped="0">\n'
                '  <testcase name="ok" classname="%s.DemoTest"/>\n'
                '</testsuite>\n'
            ) % (m, m)
            with open(os.path.join(pkg, 'TEST-%s.DemoTest.xml' % m),
                      'w', encoding='utf-8') as fh:
                fh.write(xml)
        with open(os.path.join(tmp, 'README.md'), 'w', encoding='utf-8') as fh:
            fh.write(case_doc)
        return tmp, reports

    def run(tmp, reports):
        # 夹具**故意**造红，stderr 会刷屏。静默掉，让自检结论一眼可读；
        # 只留每个用例自己的 ok/失败说明。
        saved_out, saved_err = sys.stdout, sys.stderr
        sys.stdout, sys.stderr = io.StringIO(), io.StringIO()
        try:
            rc = _run(tmp, reports)
        finally:
            sys.stdout, sys.stderr = saved_out, saved_err
        return rc

    def clean_table(rows_total):
        body = ['# Demo', '', '| 模块 | 单测数 |', '|---|---|']
        for m in EXPECTED_MODULES:
            body.append('| `%s` | 1 |' % m)
        body.append('| **总计** | **%d** |' % rows_total)
        return '\n'.join(body) + '\n'

    # 干净文档：不得误伤（总计 = 模块数）
    tmp, reports = build(clean_table(len(EXPECTED_MODULES)))
    try:
        rc = run(tmp, reports)
        if rc != 0:
            print('自检失败：干净文档被判红（rc=%s）——新检查存在误伤' % rc)
            ok = False
        else:
            print('  ✓ 干净文档不被误伤')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    # 数字真错时也必须判红（防止"冲突检查"把数字校验顶掉）
    tmp, reports = build(clean_table(999))
    try:
        rc = run(tmp, reports)
        if rc == 0:
            print('自检失败：总计数字写错却判绿——数字校验被架空')
            ok = False
        else:
            print('  ✓ 数字漂移仍被判红（未被新检查顶掉）')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    # 带冲突标记、且冲突块正好吞掉计数表：必须判红
    # 这一条就是 2026-10-08 事故的复现——表在冲突块里，旧版门禁会"全部一致"。
    dirty = ('# Demo\n\n<<<<<<< HEAD\n'
             + clean_table(999).replace('# Demo\n\n', '')
             + '=======\n'
             + clean_table(888).replace('# Demo\n\n', '')
             + '>>>>>>> origin/master\n')
    tmp, reports = build(dirty)
    try:
        rc = run(tmp, reports)
        if rc == 0:
            print('自检失败：带冲突标记的文档被判绿——'
                  '这正是 2026-10-08 事故的形态，门禁又变哑了')
            ok = False
        else:
            print('  ✓ 冲突标记（吞掉计数表）被判红')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print('自检%s' % ('通过' if ok else '未通过'))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description='校验文档声称的单测数与 surefire 实测一致')
    ap.add_argument('--root', default=os.path.abspath(os.path.join(os.path.dirname(__file__), '..')),
                    help='仓库根目录（默认取脚本上级目录）')
    ap.add_argument('--reports-root', default=None,
                    help='从该目录下的 <module>/TEST-*.xml 汇总（CI 聚合模式）')
    ap.add_argument('--print-actual', action='store_true', help='只打印实测数')
    ap.add_argument('--frontend-json', default=None,
                     help='vitest json 报告路径（gcs-web/vitest-report.json）。'
                          '给了才校验前端用例数；不给则只校验 Java 侧')
    ap.add_argument('--self-test', action='store_true',
                    help='离线自检：用临时夹具证明「冲突标记检出」真的会红，'
                         '且干净文档不会被误伤。无需任何测试产物。')
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    return _run(args.root, args.reports_root, args.frontend_json, args.print_actual)


def _run(root, reports_root=None, frontend_json=None, print_actual=False):
    (actual, failures, errors, skipped, files,
     per_module, missing, stale) = measure_actual(root, reports_root)

    if files == 0:
        if reports_root:
            print('目录下没有 TEST-*.xml：%s' % reports_root, file=sys.stderr)
        else:
            print('找不到 surefire 产物（*/target/surefire-reports/TEST-*.xml），'
                  '请先跑 mvn test', file=sys.stderr)
        return 1

    print('实测：%d 用例 / %d 失败 / %d 错误 / %d 跳过（来自 %d 个测试类）'
          % (actual, failures, errors, skipped, files))
    print('  分模块：' + '，'.join('%s=%d' % kv for kv in sorted(per_module.items())))

    if stale:
        # 剔除必须说出来：静默剔除只是把「虚高的数」换成「看不见的剔除」。
        print('  剔除 %d 份陈旧报告（对应测试类已不在 src/test，surefire 目录不会自清）：'
              % len(stale))
        for s in stale[:10]:
            print('    - %s' % s)
        print('  （要彻底干净请跑 `mvn clean test`）')

    if missing:
        # 缺模块意味着 total 偏小，此时任何校验结论都不可信，先红。
        print('缺少模块的 surefire 报告：%s。总数偏小，校验结果不可信，先补齐。'
              % '，'.join(missing), file=sys.stderr)
        return 1

    if print_actual:
        return 0

    if failures or errors:
        print('有测试未通过，先修测试再谈文档口径', file=sys.stderr)
        return 1

    # 前端实测值（--frontend-json 才启用）。取不到就红：静默跳过等于把
    # "没跑前端测试"当成"前端用例数无需核对"，那正是本门禁要消灭的失效面。
    fe_total = fe_passed = fe_failed = None
    if frontend_json:
        try:
            fe_total, fe_passed, fe_failed = measure_frontend(frontend_json)
        except FileNotFoundError:
            print('找不到 vitest json 报告：%s（先跑 `npm run test:ci`）'
                  % frontend_json, file=sys.stderr)
            return 1
        except ValueError as e:
            print('vitest 报告无法解析：%s' % e, file=sys.stderr)
            return 1
        print('实测（前端）：%d 用例 / %d 通过 / %d 失败（来自 %s）'
              % (fe_total, fe_passed, fe_failed, frontend_json))
        if fe_failed:
            print('前端有用例未通过，先修测试再谈文档口径', file=sys.stderr)
            return 1

    print('\n校验当前口径文档：')
    bad = 0

    # ---- 结构完整性前置检查 ----
    # 2026-10-08 实测事故：master 上 README.md / ROADMAP.md 带着**误提交的 merge
    # 冲突标记**（<<<<<<< HEAD … ======= … >>>>>>> origin/master）。两份文件的
    # 测试规模表恰好都落在冲突块里，于是 test_table_rows() 认不出那些行、
    # 逐格核对被**静默跳过**，门禁输出"全部一致"——而真实情况是表已损坏、
    # 该核对的数字一个都没核。
    #
    # 这比"数字写错"更坏：它让门禁在最需要的时候变成哑的。
    # 一个只会"匹配不到就不管"的校验器不是门禁，是装饰。
    # 故先扫冲突标记：命中即红，且**明确说清后果**（哪些行没被核对）。
    conflict_docs = []
    for rel in CURRENT_DOCS:
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            continue
        with open(path, encoding='utf-8') as fh:
            doc_lines = fh.readlines()
        marks = [i + 1 for i, l in enumerate(doc_lines)
                 if l.startswith('<<<<<<< ') or l.startswith('>>>>>>> ')]
        if marks:
            conflict_docs.append((rel, marks))
    if conflict_docs:
        print('\n❌ 文档里存在 merge 冲突标记，门禁已停：', file=sys.stderr)
        for rel, marks in conflict_docs:
            print('  %s：第 %s 行' % (rel, '、'.join(str(m) for m in marks[:10])),
                  file=sys.stderr)
        print('  后果：冲突块内的表格/数字无法被本门禁逐格核对，'
              '"全部一致"在此情况下毫无意义（2026-10-08 实际发生过）。'
              '\n  请先解决冲突再跑本门禁；不要用"看起来一致"当结论。', file=sys.stderr)
        return 1

    for rel in CURRENT_DOCS:
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            print('  ?? %s 不存在，跳过' % rel)
            continue
        with open(path, encoding='utf-8') as fh:
            lines = fh.readlines()
        table_rows = test_table_rows(lines)
        exempt_rows = retrospective_section_lines(lines, rel)
        hits = []
        approx = []
        fe_hits = []
        skipped_retro = 0
        for lineno, line in enumerate(lines, 1):
            if lineno in exempt_rows or _is_retrospective(line):
                skipped_retro += 1
                continue
            # 前端声称（--frontend-json 启用时）。**在 Java 侧之前判、且 continue**：
            # 同一行若同时含前后端两个数，Java 的宽泛文法会把前端的数也抓走，
            # 拿 177 去比 4301，判红理由还是错的。
            if fe_total is not None:
                fe_matched = False
                for pat in FRONTEND_CLAIM_PATTERNS:
                    for m in pat.finditer(line):
                        claimed = _parse_count(m.group(1))
                        if 1 <= claimed <= 200_000:
                            fe_hits.append((lineno, claimed, line.strip()))
                            fe_matched = True
                if fe_matched:
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
            # 散文化声称（非表格行）：需含「单测 / 测试用例 / 单元测试」或英文 tests。
            # 英文关键词是 2026-10-05 补的：demo-scenarios.md 曾写
            # "4234 tests，0 failures"，不含任何中文关键词，整行静默逃检。
            _low = line.lower()
            if ('单测' not in line and '测试用例' not in line
                    and '单元测试' not in line and 'tests' not in _low):
                continue
            scope = scope_of(line, per_module)
            expected = per_module[scope] if scope else actual
            for pat in CLAIM_PATTERNS:
                for m in pat.finditer(line):
                    claimed = _parse_count(m.group(1))
                    if 100 <= claimed <= 200_000:      # 过滤明显不是测试数的巧合数字
                        hits.append((lineno, claimed, expected, scope, line.strip()))

        note = '，跳过 %d 行追述旧值' % skipped_retro if skipped_retro else ''
        if not hits and not approx and not fe_hits:
            print('  ok %s（无单测数声称%s）' % (rel, note))
            continue
        fe_wrong = [h for h in fe_hits if h[1] != fe_total]
        bad += len(fe_wrong)
        for lineno, claimed, text in fe_wrong:
            print('  ✗ %s:%d [前端] 声称 %d，实测 %d' % (rel, lineno, claimed, fe_total))
            print('      %s' % (text[:120] + ('…' if len(text) > 120 else '')))
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
        if not wrong and not approx and not fe_wrong:
            bits = []
            if hits:
                scope_desc = '/'.join(sorted({h[3] for h in hits if h[3] and h[3] != '__total__'})) or '全仓'
                bits.append('%d 处，%s' % (len(hits), scope_desc))
            if fe_hits:
                bits.append('前端 %d 处' % len(fe_hits))
            print('  ok %s（%s%s）' % (rel, '，'.join(bits), note))

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
