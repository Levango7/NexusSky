#!/usr/bin/env python3
"""
门禁：本地/文档中的 Maven 命令若用了 -pl 却漏 -am，会静默使用 m2 陈旧制品。

**为什么需要它**（根因分析，2026-10-05）
---------------------------------------
本仓是多模块 reactor。``mvn -pl cloud-backend test`` 不带 ``-am`` 时，Maven **不**
在 reactor 内重建 ``mavlink-core`` / ``drone-sim``，而是去本地仓库
(``~/.m2/repository``，本机实测被重定向到 ``E:\\dev-data\\maven\\repository``) 找。

若本地仓库里的 ``aerofleet-drone-sim-0.1.0-SNAPSHOT.jar`` 是几天前的，
**依赖它的测试会拿旧字节码跑**，结果是**假失败**——测试断言新行为，跑的是旧实现。

实测事故（本仓真实发生）：
  ``EnvOverrideE2ETest`` 3/3 失败、``DeliveryPayloadQueryE2ETest`` 1/1 失败。
  反查：m2 里的 drone-sim jar 停在 Oct 2 00:13，其 ``VirtualDrone`` 命令表最大
  常量 420，而当前源码已迁移到 30080-30087（私有方言段）。删陈旧 jar 后
  ``mvn -pl cloud-backend -am test`` → **4/4 全绿**。
  完整取证见 ``.coord/GAPS.md`` 与 MEMORY。

**为什么单靠"-am"不够**：CI 里的命令都对（都带 -am），但**文档与脚本**里教给
开发者/用户的是错的——``docs/contributing-guide.md`` 与 ``scripts/e2e-*.ps1``
都写了裸 ``-pl``。新人照着做，得到与我完全相同的假失败，然后去改**本来正确的代码**。
本门禁就是让这种传染无法再通过 review。

**检查范围**：``docs/*.md``、``scripts/*``、``README.md``、``ROOT 下 *.md``。
**豁免**：CI 工作流（已人工核验全部带 -am）；本脚本自身；注释里说明为何不带的行。

用法：
    python scripts/check-maven-incantations.py
    python scripts/check-maven-incantations.py --root F:/repo

退出码：0=通过，1=发现裸 -pl。
"""
import argparse
import os
import re
import sys

# 匹配 `mvn ... -pl <modules>`，捕获 -pl 之后到行尾的部分
MVN_PL = re.compile(r'\bmvn\b[^\n]*?\s-pl\s+(?P<after>[^\n]*)')
# 在 -pl 的参数段内找 -am / --also-make
HAS_AM = re.compile(r'(^|\s)(-am|--also-make)(\s|$)')

# 扫描的文件：文档与脚本。CHANGELOG 也扫（它是给人看的命令记录）。
SCAN_GLOBS = ('*.md',)
SCAN_DIRS = ('docs', 'scripts', '.coord')
SKIP_FILES = {
    'scripts/check-maven-incantations.py',      # 本脚本
    # CHANGELOG 是历史记录，且其中 65/68 行**正是**在讲"不带 -am 会怎样"的反面教材
    # ——把它改掉等于删掉这段教训。README/contributing 才是面向未来的指令，必须干净。
    'CHANGELOG.md',
}
# 行内豁免标记：写明理由的行不再报警
EXEMPT_MARKERS = ('maven-incantations:ok', 'noqa: mvn-pl')

# 叶子模块：无内部 reactor 依赖。对它们裸用 -pl 是安全的（m2 里只有它自己）。
# 只需一条 `install` 过就不再有陈旧问题。据各模块 pom 的 aerofleet-* 依赖实测定表
# （2026-10-05）。新模块若无内部依赖，应加进此集合。
LEAF_MODULES = {'mavlink-core', 'sdk-java', 'regulator-sim'}


def iter_targets(root):
    """产出待扫描文件（相对路径）。"""
    out = []
    for name in os.listdir(root):
        if name.endswith('.md'):
            out.append(name)
    for d in SCAN_DIRS:
        base = os.path.join(root, d)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [x for x in dirnames if x not in ('__pycache__', 'node_modules')]
            for fn in filenames:
                if fn.endswith(('.md', '.sh', '.ps1', '.cmd', '.py', '.txt')):
                    out.append(os.path.relpath(os.path.join(dirpath, fn), root).replace('\\', '/'))
    return sorted(set(out))


# 已知的 Maven 子命令/阶段：出现在 -pl 之后即表示模块列表结束。
# 不加这个，「mvn -pl mavlink-core test」里的 `test` 会被当成第二个模块名，
# 从而让叶子模块判定失败（2026-10-05 修）。
MVN_GOALS = {
    'test', 'verify', 'package', 'install', 'clean', 'compile', 'validate',
    'deploy', 'site', 'spring-boot:run', 'dependency:tree', 'dependency:list',
    'help:evaluate', 'dependency:go-offline',
}


def check_file(path, rel):
    """返回违例行 [(lineno, text)]。"""
    bad = []
    with open(path, encoding='utf-8', errors='replace') as fh:
        for lineno, line in enumerate(fh, 1):
            if any(m in line for m in EXEMPT_MARKERS):
                continue
            for m in MVN_PL.finditer(line):
                after = m.group('after')
                # -pl 的参数以空格分隔，遇到下一个 - 开头的选项即止
                # 但简单起见：-am 只要出现在同行 mvn 命令里就算
                if HAS_AM.search(after) or ' -am' in line:
                    continue
                # 取 -pl 后的模块名：剥 Markdown 标记 → 截到下一个 - 选项 →
                # 取形如 [a-z][a-z0-9-]* 且不是子命令的 token。
                cleaned = after.replace('`', '').replace('"', '').replace("'", '')
                mods_part = re.split(r'\s+-{1,2}\w', cleaned)[0]
                mods = []
                for x in re.split(r'[,\s]+', mods_part):
                    if not re.fullmatch(r'[a-z][a-z0-9-]*', x):
                        break               # 遇到中文/标点 → 命令结束
                    if x in MVN_GOALS:
                        break               # 遇到子命令 → 模块列表结束
                    mods.append(x)
                if not mods:
                    continue
                # 全部是叶子模块 → 裸 -pl 安全（m2 里只有它们自己，不会引用陈旧上游）
                if all(mm in LEAF_MODULES for mm in mods):
                    continue
                bad.append((lineno, line.strip()))
    return bad


def main():
    ap = argparse.ArgumentParser(description='检查 Maven -pl 命令是否漏 -am')
    ap.add_argument('--root', default=os.path.abspath(
        os.path.join(os.path.dirname(__file__), '..')))
    args = ap.parse_args()

    total_bad = 0
    checked = 0
    for rel in iter_targets(args.root):
        if rel in SKIP_FILES:
            continue
        path = os.path.join(args.root, rel)
        if not os.path.isfile(path):
            continue
        checked += 1
        bad = check_file(path, rel)
        if bad:
            print('  x %s' % rel)
            for lineno, text in bad:
                print('      :%d  %s' % (lineno, text[:130]))
            total_bad += len(bad)

    print('\n扫描 %d 个文件。' % checked)
    if total_bad:
        print('发现 %d 处 `mvn -pl` 漏 `-am` —— 会静默使用 m2 陈旧制品，导致假失败。'
              % total_bad, file=sys.stderr)
        print('修法：在 -pl 后追加 -am（在 reactor 内重建依赖）。'
              '确需裸 -pl 的（如只想跑单模块且已 install 过），'
              '在行尾加注释 `maven-incantations:ok` 并写明理由。', file=sys.stderr)
        return 1
    print('通过：未发现漏 -am 的 -pl 命令。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
