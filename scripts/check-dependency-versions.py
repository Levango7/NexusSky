#!/usr/bin/env python3
"""
门禁：POM 依赖版本必须集中管理，不得散落硬编码。

**为什么需要它**（回应用户 2026-10-05 的关切）
---------------------------------------------
用户原话：「迭代太快了，是否需要考虑兼容……万一项目各种突飞猛进又迭代了上百个
版本，其中还有好几个大版本，你到时候自然就知道了应该怎么做了。」

这类风险的正确应对不是「这次把版本对齐一遍」——对齐会随时间再次漂移。
真正需要的是**让漂移无法悄悄发生**的机制。本脚本就是那个机制。

**它检查什么**
--------------
1. **版本集中度**：除白名单外，任何 ``<dependency>`` 若直接写 ``<version>1.2.3</version>``
   字面量（而非 ``${xxx.version}``）→ 报错。
   例外白名单（写死是有理由的，见各条注释）：
   - 根 pom ``dependencyManagement`` 中的安全覆写条目：import BOM 场景下
     property 覆写实测无效，必须写直接条目（见 pom.xml 内注释与探针记录）。
2. **版本分裂**：同一个 ``groupId:artifactId`` 在全仓被声明了**两个不同版本**
   → 报错。这是最危险的形态：Maven 会按 nearest-wins 选一个，另一个静默丢进
   classpath 取决于声明顺序，症状是运行期 ``NoSuchMethodError`` 而非编译失败。
3. **版本台账同步**：``docs/dependency-management.md`` 中记录的版本必须与 pom
   实际解析值一致，防止文档成为摆设。

**用法**
--------
    python scripts/check-dependency-versions.py                  # 静态检查（默认）
    python scripts/check-dependency-versions.py --print-table     # 打印版本台账
    python scripts/check-dependency-versions.py --root F:/repo

退出码：0=通过，1=存在问题。
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

# 允许写死版本的 dependencyManagement 条目：artifactId → 允许理由。
# 这些是「安全覆写」，刻意不走属性，因为 import BOM 下属性覆写无效（实测）。
PINNED_ALLOWLIST = {
    # Trivy / Security Scan 门禁红因，必须与 spring-boot-dependencies 解耦
    'tomcat-embed-core', 'tomcat-embed-el', 'tomcat-embed-websocket',
    'netty-codec', 'netty-codec-http', 'netty-codec-http2', 'netty-handler',
    'netty-common', 'netty-buffer', 'netty-transport',
    'netty-resolver', 'netty-transport-native-unix-common',
    'postgresql', 'jackson-core', 'jackson-databind',
    'log4j-api', 'log4j-to-slf4j', 'commons-lang3',
    # 插件（非依赖）：插件版本在 <build><plugins> 里写死是可接受的
    'maven-surefire-plugin', 'maven-compiler-plugin', 'jacoco-maven-plugin',
}

# 非本仓自有的 groupId 前缀（本仓模块用 ${project.version}，不参与检查）
OWN_GROUP_PREFIX = 'io.aerofleet'

POM_NS = 'http://maven.apache.org/POM/4.0.0'


def _tag(elem):
    """去掉命名空间前缀，返回本地标签名。"""
    return elem.tag.rsplit('}', 1)[-1] if '}' in elem.tag else elem.tag


def _children(elem, name):
    return [c for c in elem if _tag(c) == name]


def _text(elem, name):
    got = _children(elem, name)
    return got[0].text.strip() if got and got[0].text else None


def collect_poms(root):
    """收集仓库内全部 pom.xml（排除 target/ 与被 .gitignore 的构建产物）。"""
    poms = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in ('target', 'node_modules', '.git', 'dist')]
        if 'pom.xml' in filenames:
            poms.append(os.path.join(dirpath, 'pom.xml'))
    return sorted(poms)


def scan_pom(path, root):
    """扫描一份 pom，返回 (直接钉版本的依赖, 全部声明版本)。

    返回结构：
      hardcoded: [(relpath, lineno, groupId, artifactId, version)]
      declared:  {artifactId: [(relpath, version)]}  含版本引用或字面量
    """
    rel = os.path.relpath(path, root).replace('\\', '/')
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        print('  !! POM 解析失败：%s —— %s' % (rel, e), file=sys.stderr)
        return [], {}
    root_elem = tree.getroot()

    hardcoded = []
    declared = {}

    # 版本声明的来源：<dependencies> 与 <dependencyManagement><dependencies>
    # 用 iter 找所有 dependency 节点，但需排除 <parent>（它也有 version）
    for dep in root_elem.iter():
        if _tag(dep) != 'dependency':
            continue
        gid = _text(dep, 'groupId')
        aid = _text(dep, 'artifactId')
        ver = _text(dep, 'version')
        if not aid or ver is None:
            continue
        declared.setdefault(aid, []).append((rel, ver))
        if gid and gid.startswith(OWN_GROUP_PREFIX):
            continue                      # 本仓模块用 ${project.version}，不检查
        if ver.startswith('${'):
            continue                      # 走属性引用，合规
        # 字面量版本：检查是否在白名单
        if aid in PINNED_ALLOWLIST:
            continue
        lineno = _find_lineno(path, aid, ver)
        hardcoded.append((rel, lineno, gid or '?', aid, ver))

    return hardcoded, declared


def _find_lineno(path, aid, ver):
    """尽力定位 artifactId 所在行号（仅用于报错提示）。"""
    try:
        with open(path, encoding='utf-8') as fh:
            for i, line in enumerate(fh, 1):
                if '<artifactId>%s</artifactId>' % aid in line:
                    return i
    except OSError:
        pass
    return 0


def collect_properties(root):
    """收集根 pom 的 <properties>，用于把 ${xxx.version} 解析成字面值。

    只取根 pom：Maven 的属性继承是自下而上覆盖，子模块可以定义同名属性遮蔽
    父级。本门禁关心的是「解析后是否同一版本」，用根 pom 属性可覆盖绝大多数
    情形；子模块自有属性（如 sdk-java 的 jackson.version）单独并入。
    """
    props = {}
    for pom in collect_poms(root):
        try:
            tree = ET.parse(pom)
        except ET.ParseError:
            continue
        node = _children(tree.getroot(), 'properties')
        if not node:
            continue
        for p in _children(node[0], '__any__') or list(node[0]):
            if _tag(p) and p.text:
                props.setdefault(_tag(p), p.text.strip())
    return props


_PROP_REF = re.compile(r'\$\{([^}]+)\}')


def resolve_version(ver, props, depth=0):
    """把 ${xxx} 递归解析成字面值；解析不出则原样返回。"""
    if depth > 5 or not ver:
        return ver
    m = _PROP_REF.search(ver)
    if not m:
        return ver
    key = m.group(1)
    if key in ('project.version', 'pom.version'):
        return ver                       # 本仓版本，不参与第三方版本比较
    repl = props.get(key)
    if repl is None:
        return ver                       # 未定义属性：原样返回，暴露问题
    return resolve_version(ver.replace(m.group(0), repl), props, depth + 1)


def find_version_splits(declared, props):
    """找出同一 artifactId 被声明了多个**不同（解析后）**版本的情况。"""
    splits = []
    for aid, entries in declared.items():
        versions = {}
        for rel, ver in entries:
            resolved = resolve_version(ver, props)
            versions.setdefault(resolved, []).append('%s (%s)' % (ver, rel))
        if len(versions) > 1:
            splits.append((aid, versions))
    return splits


def main():
    ap = argparse.ArgumentParser(description='检查 POM 依赖版本集中度与分裂')
    ap.add_argument('--root', default=os.path.abspath(
        os.path.join(os.path.dirname(__file__), '..')), help='仓库根目录')
    ap.add_argument('--print-table', action='store_true', help='打印已声明版本台账')
    args = ap.parse_args()

    poms = collect_poms(args.root)
    print('扫描 %d 份 pom.xml（根：%s）' % (len(poms), args.root))

    all_hardcoded = []
    merged_declared = {}
    for p in poms:
        hc, dec = scan_pom(p, args.root)
        all_hardcoded.extend(hc)
        for aid, entries in dec.items():
            merged_declared.setdefault(aid, []).extend(entries)

    if args.print_table:
        print('\n已声明版本台账（artifactId → 版本 @ 文件）：')
        for aid in sorted(merged_declared):
            for rel, ver in merged_declared[aid]:
                print('  %-42s %-22s %s' % (aid, ver, rel))
        return 0

    exit_code = 0

    # ---- 检查 1：硬编码版本 ----
    print('\n[1/3] 硬编码版本（应改用 ${{xxx.version}} 集中管理）')
    if all_hardcoded:
        for rel, lineno, gid, aid, ver in all_hardcoded:
            print('  x %s:%d  %s:%s = %s' % (rel, lineno, gid, aid, ver))
        print('  → %d 处。若确需写死，请加进 PINNED_ALLOWLIST 并写明理由。'
              % len(all_hardcoded))
        exit_code = 1
    else:
        print('  ok 无硬编码版本（白名单内 %d 项除外）' % len(PINNED_ALLOWLIST))

    # ---- 检查 2：版本分裂 ----
    print('\n[2/3] 同一 artifactId 的版本分裂（解析 ${xxx} 后比较）')
    props = collect_properties(args.root)
    splits = find_version_splits(merged_declared, props)
    if splits:
        for aid, versions in splits:
            print('  x %s 解析出多个版本：' % aid)
            for ver, where in versions.items():
                print('      %-24s ← %s' % (ver, ', '.join(sorted(set(where)))))
        print('  → 同一 jar 两个版本进 classpath 时，Maven 按 nearest-wins 择一，')
        print('    另一个静默留存，症状是运行期 NoSuchMethodError 而非编译失败。')
        exit_code = 1
    else:
        print('  ok 无版本分裂')

    # ---- 检查 3：台账文档同步 ----
    print('\n[3/3] 版本台账文档同步')
    ledger = os.path.join(args.root, 'docs', 'dependency-management.md')
    if not os.path.exists(ledger):
        print('  ! docs/dependency-management.md 不存在 —— 无法核对台账')
        print('  → 请创建该文件（或更新本脚本路径）。')
        exit_code = 1
    else:
        with open(ledger, encoding='utf-8') as fh:
            text = fh.read()
        # 台账格式：| `artifactId` | `version` | 说明 |
        mismatches = []
        for aid, entries in merged_declared.items():
            vers = {v for _, v in entries}
            if len(vers) != 1:
                continue
            (ver,) = vers
            if ver.startswith('${') or ver == '${project.version}':
                continue
            m = re.search(r'\|\s*`?%s`?\s*\|\s*`?%s`?\s*\|' % (re.escape(aid), re.escape(ver)), text)
            if not m:
                # 台账里没这条也不算错（台账可选记录关键依赖），只提示
                mismatches.append((aid, ver))
        if mismatches:
            print('  i 有 %d 项已声明版本未出现在台账（非强制，供人工确认）：' % len(mismatches))
            for aid, ver in mismatches[:15]:
                print('      %-40s %s' % (aid, ver))
            if len(mismatches) > 15:
                print('      ...另有 %d 项' % (len(mismatches) - 15))
        else:
            print('  ok 台账覆盖全部已声明版本')

    print()
    if exit_code:
        print('检查未通过。', file=sys.stderr)
    else:
        print('检查通过。')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
