#!/usr/bin/env python3
"""
门禁：各模块的 Java 编译级别必须与实际产物字节码一致，且不得无故低于/高于声明。

**为什么需要它**（2026-10-05 建立）
----------------------------------
本仓发生了一个**静默的兼容性欺诈**：
  `sdk-java/pom.xml` 声明 `maven.compiler.source/target = 11`，
  文档（`sdk-java/README.md`、`docs/PUBLISHING.md`）声称 SDK 兼容 **Java 11**，
  但**实际产出的 jar 字节码是 major 61（Java 17）**。
  客户按文档用 JDK 11 集成 → 编译期 `UnsupportedClassVersionError`。

根因：父 pom 定义了 `maven.compiler.release=17`。该属性**优先级高于**
子模块 compiler-plugin 的 `<source>/<target>`，静默覆盖之。

这类问题的可怕之处在于**没有任何信号会变红**：构建成功、测试全绿、
文档也"写对了"，只有客户在 JDK 11 上才炸——那时已经在客户现场。

本脚本让它在 CI 就红。

**准确机制**（2026-10-05 复测确认，修正初版归因）
--------------------------------------------------
Maven Compiler Plugin 解析编译级别的优先级是：

    <release>（插件配置 / maven.compiler.release 属性）
        > <source>/<target>（maven.compiler.source/target 属性）

**`release` 一旦存在就压制 source/target**，且不管它来自本模块还是父 pom 继承。
原缺陷态：根 pom 有 `maven.compiler.release=17` 属性（被插件当默认值读取），
sdk-java 有 `maven.compiler.source/target=11` —— 三者共存，生效的是 release=17。
所以 sdk-java 的 source/target=11 是**死配置**，构建日志甚至不会提示被忽略。

修法：把 sdk-java 的 release **显式**设为 11（盖过继承值），并删掉死的 source/target。

**检查什么**
------------
1. **字节码版本 vs 声明级别**：对每个模块，按其 pom 声明的**有效级别**
   （release 优先，其次 source/target），用 `javap` 读 `target/classes` 下
   首个 class 的 `major version`，断言二者一致。
2. **产物新鲜度**：若 class 文件早于 pom.xml 修改时间，报警——说明字节码是
   上一次构建的陈旧产物，"一致性"结论不成立（这是本脚本第一版的假绿漏洞）。
3. **release vs source/target 混用**：本模块/父 pom 的 release 若与
   本模块的 source/target 不一致，报警——source/target 是无效死配置。
4. **模块级别清单**：打印全仓 Java 级别一览，便于人工核对是否有意外漂移。

**用法**
--------
    python scripts/check-java-level.py                # 完整检查（需已编译）
    python scripts/check-java-level.py --list         # 只列出各模块声明级别（不需编译）
    python scripts/check-java-level.py --root F:/repo

退出码：0=通过，1=不一致或找不到字节码。
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# Java 字节码 major version → Java 版本
MAJOR_TO_JAVA = {
    52: '8', 53: '9', 54: '10', 55: '11', 56: '12', 57: '13', 58: '14',
    59: '15', 60: '16', 61: '17', 62: '18', 63: '19', 64: '20', 65: '21',
    66: '22', 67: '23', 68: '24', 69: '25', 70: '26', 71: '27',
}

MODULES = ['mavlink-core', 'drone-sim', 'link-sim', 'cloud-backend',
           'sdk-java', 'regulator-sim']

# javap 从哪个 JDK 取：优先用环境变量 JAVA_HOME，其次 PATH
def javap_cmd():
    jh = os.environ.get('JAVA_HOME')
    if jh:
        exe = os.path.join(jh, 'bin', 'javap.exe' if os.name == 'nt' else 'javap')
        if os.path.exists(exe):
            return exe
    return 'javap'


def _tag(e):
    return e.tag.rsplit('}', 1)[-1] if '}' in e.tag else e.tag


def _children(e, n):
    return [c for c in e if _tag(c) == n]


def _text(e, n):
    g = _children(e, n)
    return g[0].text.strip() if g and g[0].text else None


def declared_level(root, root_props):
    """返回 {module: (级别, 来源说明)}。级别为字符串如 '17' / '11'。

    有效级别解析顺序（与 compiler-plugin 实际行为一致）：
      本模块 release > 继承 release > 本模块 source > 继承 source
    即：release 只要存在就压制 source/target，无论来自本模块还是父 pom。
    """
    out = {}
    for m in MODULES:
        pom = os.path.join(root, m, 'pom.xml')
        if not os.path.exists(pom):
            continue
        try:
            tree = ET.parse(pom)
        except ET.ParseError:
            out[m] = (None, 'POM 解析失败')
            continue
        props = {}
        pn = _children(tree.getroot(), 'properties')
        if pn:
            for p in _children(pn[0], '__any__') or list(pn[0]):
                if p.text:
                    props[_tag(p)] = p.text.strip()

        def resolve(v):
            if v is None:
                return None
            mref = re.fullmatch(r'\$\{([^}]+)\}', v)
            if mref:
                key = mref.group(1)
                return props.get(key) or root_props.get(key)
            return v

        # 本模块 vs 继承：分开记录，来源标注才能准确
        own_rel = resolve(props.get('maven.compiler.release'))
        inh_rel = None if own_rel else root_props.get('maven.compiler.release')
        own_src = resolve(props.get('maven.compiler.source'))
        inh_src = None if own_src else root_props.get('maven.compiler.source')

        # compiler-plugin 的 <release> / <source> 配置优先级最高
        plugin_rel = plugin_src = None
        for pl in tree.getroot().iter():
            if _tag(pl) == 'plugin':
                aid = _text(pl, 'artifactId')
                if aid == 'maven-compiler-plugin':
                    cfg = _children(pl, 'configuration')
                    if cfg:
                        plugin_rel = resolve(_text(cfg[0], 'release')) or plugin_rel
                        plugin_src = resolve(_text(cfg[0], 'source')) or plugin_src

        if plugin_rel:
            out[m] = (plugin_rel, 'compiler-plugin <release>（本模块显式）')
        elif own_rel:
            out[m] = (own_rel, 'maven.compiler.release（本模块）')
        elif inh_rel:
            out[m] = (inh_rel, 'maven.compiler.release（继承根 pom）')
        elif plugin_src:
            out[m] = (plugin_src, 'compiler-plugin <source>（注意：不校验 API！）')
        elif own_src:
            out[m] = (own_src, 'maven.compiler.source（本模块，注意：不校验 API！）')
        elif inh_src:
            out[m] = (inh_src, 'maven.compiler.source（继承，注意：不校验 API！）')
        else:
            out[m] = (None, '未声明，将从父 pom 继承')

        # 混用检测：只要 release 生效（不管来自哪层）且本模块另有 source/target，
        # 或本模块 release 与继承 release 冲突 → 后者是死配置
        effective_rel = plugin_rel or own_rel or inh_rel
        stale = []
        if effective_rel and own_src and own_src != effective_rel:
            stale.append('maven.compiler.source=%s' % own_src)
        if effective_rel and plugin_src and plugin_src != effective_rel:
            stale.append('plugin <source>=%s' % plugin_src)
        if own_rel and inh_rel and own_rel != inh_rel:
            stale.append('继承 release=%s' % inh_rel)
        if stale:
            out[m] = (out[m][0], out[m][1] + ' ⚠ 并存死配置: ' + ', '.join(stale))
    return out


def first_class(target_classes):
    """返回 target/classes 下第一个 .class 的路径。"""
    for dirpath, _dirs, files in os.walk(target_classes):
        for f in sorted(files):
            if f.endswith('.class'):
                return os.path.join(dirpath, f)
    return None


def bytecode_major(classfile):
    """用 javap -v 读 major version。"""
    try:
        r = subprocess.run([javap_cmd(), '-v', classfile],
                           capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError) as e:
        return None, str(e)
    m = re.search(r'major version:\s*(\d+)', r.stdout)
    if not m:
        return None, 'javap 输出里没有 major version'
    return int(m.group(1)), r.stdout


def main():
    ap = argparse.ArgumentParser(description='检查 Java 编译级别与产物字节码一致性')
    ap.add_argument('--root', default=os.path.abspath(
        os.path.join(os.path.dirname(__file__), '..')))
    ap.add_argument('--list', action='store_true', help='只列声明级别')
    args = ap.parse_args()

    # 读根 pom 的 properties 作为继承默认值
    root_props = {}
    root_pom = os.path.join(args.root, 'pom.xml')
    if os.path.exists(root_pom):
        try:
            t = ET.parse(root_pom)
            pn = _children(t.getroot(), 'properties')
            if pn:
                for p in _children(pn[0], '__any__') or list(pn[0]):
                    if p.text:
                        root_props[_tag(p)] = p.text.strip()
        except ET.ParseError:
            pass

    levels = declared_level(args.root, root_props)

    print('模块 Java 级别声明一览（根 pom release=%s）:' % root_props.get('maven.compiler.release', '?'))
    for m in MODULES:
        if m not in levels:
            continue
        lvl, src = levels[m]
        print('  %-16s %-6s   %s' % (m, lvl or '?', src))

    if args.list:
        return 0

    exit_code = 0
    print('\n字节码一致性检查:')
    for m in MODULES:
        if m not in levels:
            continue
        lvl, src = levels[m]
        if lvl is None:
            print('  ? %-16s 未声明级别，跳过' % m)
            continue
        module_dir = os.path.join(args.root, m)
        classes = os.path.join(module_dir, 'target', 'classes')
        if not os.path.isdir(classes):
            print('  ? %-16s 无 target/classes（未编译），跳过' % m)
            continue
        cf = first_class(classes)
        if cf is None:
            print('  ? %-16s target/classes 下无 .class，跳过' % m)
            continue

        # 新鲜度：class 必须不早于 pom.xml，否则是陈旧产物，一致性无从谈起
        pom_path = os.path.join(module_dir, 'pom.xml')
        if os.path.exists(pom_path):
            try:
                if os.path.getmtime(cf) < os.path.getmtime(pom_path):
                    print('  x  %-16s 产物字节码早于 pom.xml —— 陈旧产物！'
                          '先重新构建再看一致性。' % m)
                    print('       本检查读的是磁盘上的 .class，不是"当前 pom 会产出什么"。'
                          '跳过新鲜度校验就会拿旧产物报假绿。')
                    exit_code = 1
                    continue
            except OSError:
                pass

        major, detail = bytecode_major(cf)
        if major is None:
            print('  ? %-16s 读字节码失败：%s' % (m, detail))
            exit_code = 1
            continue
        actual = MAJOR_TO_JAVA.get(major, 'major%s' % major)
        want = lvl.strip()
        if actual == want:
            print('  ok %-16s 声明 %-3s 实际 major=%d(%s)' % (m, want, major, actual))
        else:
            print('  x  %-16s 声明 %-3s 但产物字节码 major=%d —— 即 Java %s！'
                  % (m, want, major, actual))
            print('       该模块对外声称兼容 Java %s，实际交付的是 Java %s' % (want, actual))
            print('       客户在 JDK %s 上会 UnsupportedClassVersionError。' % want)
            print('       机制提醒：release 压制 source/target。若本模块只有 source/target，'
                  '而父 pom 有 maven.compiler.release，生效的是父 pom 的值。')
            print('       修法：在本模块 compiler-plugin 里显式写 <release>%s</release>。' % want)
            exit_code = 1

    # release/source 混用提示
    for m in MODULES:
        if m in levels and '⚠' in levels[m][1]:
            print('  !  %-16s 同时存在 release 与 source/target 且不一致 —— '
                  'release 会静默覆盖后者，后者是无效死配置（易误导）。' % m)
            exit_code = 1

    print()
    if exit_code:
        print('检查未通过。', file=sys.stderr)
    else:
        print('检查通过。')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
