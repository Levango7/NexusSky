"""把文档里的测试计数同步为 surefire 实测值（行号从门禁输出解析，不猜）。

为什么单独一个脚本：每个给仓库加了测试的 PR 都要做这件事，而做法是固定的——跑
check-test-count-docs.py 拿到"声称 X，实测 Y"与行号，再按行号改。以前每轮都临时
重写一遍内联脚本，容易漏行（曾因 git add 范围漏一个文件让 Docs gate 红）。

用法：
    python scripts/sync-test-counts.py [vitest-report.json]

vitest-report.json 不给则只同步 Java 侧（前端口径不校验）。
改完必须再跑一次 check-test-count-docs.py 确认"全部一致"。
"""
import io
import re
import subprocess
import sys

FRONTEND_JSON = sys.argv[1] if len(sys.argv) > 1 else 'F:/tmp/vitest-report.json'
REPO = 'F:/Nexus/nexussky-b1'

out = subprocess.run(
    [sys.executable, 'scripts/check-test-count-docs.py', '--frontend-json', FRONTEND_JSON],
    capture_output=True, text=True, encoding='utf-8', cwd=REPO).stdout

pat = re.compile(r'\u2717\s+(\S+?):(\d+)\s+\[[^\]]+\]\s+声称\s+(\d+)，实测\s+(\d+)')
seen, targets = set(), []
for m in pat.finditer(out):
    key = (m.group(1), int(m.group(2)))
    if key in seen:
        continue
    seen.add(key)
    targets.append((m.group(1), int(m.group(2)), m.group(3), m.group(4)))

print('待订正:', len(targets))
for f, ln, old, new in targets:
    path = REPO + '/' + f
    lines = io.open(path, encoding='utf-8').read().split('\n')
    lines[ln - 1] = re.sub(r'(?<![\d])' + re.escape(old) + r'(?![\d])', new, lines[ln - 1])
    io.open(path, 'w', encoding='utf-8', newline='').write('\n'.join(lines))
    print('  %s:%d %s→%s' % (f, ln, old, new))
