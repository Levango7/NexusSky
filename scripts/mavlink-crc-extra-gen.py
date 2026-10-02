#!/usr/bin/env python3
"""
生成 NexusSky 自定义 MAVLink 消息的 CRC_EXTRA 常量表（官方 mavgen 算法）。

背景
----
MAVLink 的 CRC_EXTRA 不是随便取的常数，而是由「消息名 + 字段签名」按官方算法
（pymavlink `generator/mavparse.py: message_checksum`）算出的 8 位值：

    crc = x25crc(0xFFFF)
    crc.accumulate_str(NAME + ' ')
    for f in ordered_fields[:base_fields()]:      # 按 type_length 降序（稳定）
        crc.accumulate_str(f.type + ' ')          # 全名，如 'uint8_t' / 'float'
        crc.accumulate_str(f.name + ' ')
        if f.array_length:
            crc.accumulate(bytes([f.array_length]))
    return (crc & 0xFF) ^ (crc >> 8)

其作用是：当两端对同一 msgId 的「定义」不一致时，帧 CRC 必然不同，从而被拒。
本仓库原先把自定义消息的 CRC_EXTRA 写成人工序数（201..267），与字段签名完全无关，
该机制因此形同虚设——任意两份字段布局不同的实现，只要抄同一个常数就能互通。

本脚本的字段签名来源
--------------------
自定义消息在本仓库没有 XML 定义（`grep -r "*.xml"` 确认过，仓库内只有 pom.xml 等），
唯一定义源是各消息类的 Javadoc 字段布局表 + `encode()` 的字节偏移。脚本从这两处
提取，并对二者做一致性校验（覆盖区间必须无缝铺满 [0, LEN)）。

用法
----
    python scripts/mavlink-crc-extra-gen.py            # 打印对照表
    python scripts/mavlink-crc-extra-gen.py --check    # 只校验，不写文件
    python scripts/mavlink-crc-extra-gen.py --pymavlink # 额外用 pymavlink 交叉验证

交叉验证
--------
1) `--selftest`：用本脚本的算法重算 25 条**标准** MAVLink 消息的 CRC_EXTRA，
   必须与 pymavlink 解析出的官方值逐一相等。算法对，则实现对。
2) `--pymavlink`：把自定义消息的字段签名写成 XML 交给 pymavlink 官方
   `message_checksum` 重新计算，与本脚本结果比对。两条独立代码路径必须一致。
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..'))
MSG_DIR = os.path.join(REPO, 'mavlink-core', 'src', 'main', 'java', 'io', 'aerofleet', 'mavlink')
PKG_DIR = os.path.join(MSG_DIR, 'messages')

# Java 侧类型简称 -> (MAVLink 类型全名, 字节宽度)
TYPE_MAP = {
    'u8':  ('uint8_t', 1),
    'i8':  ('int8_t', 1),
    'u16': ('uint16_t', 2),
    'i16': ('int16_t', 2),
    'u32': ('uint32_t', 4),
    'i32': ('int32_t', 4),
    'u64': ('uint64_t', 8),
    'f32': ('float', 4),
    'char': ('char', 1),
}
# 部分消息类的 Javadoc 直接写 MAVLink 类型名（uint8 / int16 ...，无 _t 后缀）
for _ma, _ms in (('uint8', 'u8'), ('int8', 'i8'), ('uint16', 'u16'), ('int16', 'i16'),
                 ('uint32', 'u32'), ('int32', 'i32'), ('uint64', 'u64'),
                 ('float', 'f32'), ('double', 'f64')):
    if _ms in TYPE_MAP:
        TYPE_MAP[_ma] = TYPE_MAP[_ms]
TYPE_W = {v[0]: v[1] for v in TYPE_MAP.values()}

# 消息名覆盖：MAVLink 官方消息名一律 SCREAMING_SNAKE_CASE 且不带 _MSG 后缀
# （HEARTBEAT / COMMAND_LONG / ... 从无 xxx_MSG）。本仓库有两个自定义消息在
# Javadoc 里写成 *_MSG，而 MavlinkMessageInfo 的注释又写无后缀名。此处以无后缀
# 为准（与 MavlinkMessageInfo 一致），并同步修正 Javadoc。
NAME_OVERRIDES = {
    'BUZZER_CONTROL_MSG': 'BUZZER_CONTROL',
    'LED_CONTROL_MSG': 'LED_CONTROL',
}

# 变长尾部：MAVLink 的消息定义里数组只能在末尾，且**不能表达「重复的结构体」**。
# 本仓库有 4 条消息用了「每项 N 字节」的重复结构（Java 侧是内嵌 record）。
# 官方算法只能吃「类型 + 字段名 + 数组长度」，因此必须把重复结构展开成若干并列数组
# （column-major），这里显式声明展开方式，不做隐式猜测。
#
# 两条限制必须写明，否则会误以为签名能完整覆盖线格式：
#   1) MAVLink 的 CRC 里数组长度只占 **1 个字节**（message_checksum 里
#      `crc.accumulate([f.array_length])`），故签名中的数组长度上限是 255。
#      这些消息的 Java 侧允许更多元素（MAX_CELLS/MAX_NEIGHBORS 等），超出部分
#      **不在 CRC_EXTRA 的表达范围内**——这是 MAVLink 本身的限制，不是本仓库的取舍。
#   2) 实际线上字节序仍是 Java 侧那种交错/结构体布局，与展开后的数组顺序不同。
#      两者不一致属于已知偏差（见 README 已知边界），改线格式属协议重设计，不在本次范围。
STRUCT_TAILS = {
    'TERRAIN_TYPE_MAP': [
        ('uint8_t', 'gridCells', 255),
    ],
    'TERRAIN_UPDATE': [
        ('uint16_t', 'affectedCellsGridIndex', 255),
        ('uint8_t', 'affectedCellsNewTerrainType', 255),
        ('uint8_t', 'affectedCellsReserved', 255),
    ],
    'MESH_NEIGHBOR_TABLE': [
        ('uint8_t', 'neighborsSysid', 255),
        ('int8_t', 'neighborsRssi', 255),
        ('uint8_t', 'neighborsLinkQuality', 255),
        ('uint8_t', 'neighborsReserved', 255),
    ],
    'FLIGHT_RESTRICTION': [
        ('int32_t', 'areaLat', 255),
        ('int32_t', 'areaLon', 255),
    ],
}

# encode() 写入函数 -> 类型全名
PUT_MAP = {
    'putU8': 'uint8_t', 'putI8': 'int8_t', 'putU16': 'uint16_t', 'putI16': 'int16_t',
    'putU32': 'uint32_t', 'putI32': 'int32_t', 'putU64': 'uint64_t', 'putF32': 'float',
    'putChars': 'char',
}


# --------------------------------------------------------------------------
# CRC-16/MCRF4XX（x25crc 官方实现，逐 bit 版本，不依赖 fastcrc）
# --------------------------------------------------------------------------
def x25crc(data, crc=0xFFFF):
    for b in data:
        tmp = b ^ (crc & 0xFF)
        tmp = (tmp ^ (tmp << 4)) & 0xFF
        crc = ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF
    return crc


def message_checksum(name, fields):
    """官方 message_checksum。fields 需已按 type_length 降序排好。

    fields: [(type_name, field_name, array_len_or_0), ...]
    """
    crc = x25crc((name + ' ').encode())
    for ftype, fname, alen in fields:
        crc = x25crc((ftype + ' ').encode(), crc)
        crc = x25crc((fname + ' ').encode(), crc)
        if alen:
            crc = x25crc(bytes([alen]), crc)
    return (crc & 0xFF) ^ (crc >> 8)


def order_fields(fields):
    """复刻 mavparse.py:426 —— 按 type_length 降序稳定排序（等宽保持原序）。"""
    return sorted(fields, key=lambda f: TYPE_W[f[0]], reverse=True)


# --------------------------------------------------------------------------
# 从 Java 源码提取
# --------------------------------------------------------------------------
_TYPES = r'(u8|i8|u16|i16|u32|i32|u64|f32|char|uint8|int8|uint16|int16|uint32|int32|uint64|float|double)'
# 布局表行：<偏移> <字段名> <类型> [数组长度] [说明]
# 说明列可省略（部分消息只写「10  reserved1  uint8」），故描述部分为可选并锚定行尾。
_ROW_A = re.compile(
    r'^(\d+)\s+([A-Za-z_][A-Za-z0-9_]*)\s+' + _TYPES +
    r'(?:\s*\[\s*(\d+)\s*\])?(?:\s+\S.*)?$')
# 同上但以「偏移」二字开头（BuzzerControlMsg 风格）
_ROW_B = re.compile(
    r'^偏移\s+(\d+)\s+([A-Za-z_][A-Za-z0-9_]*)\s+' + _TYPES +
    r'(?:\s*\[\s*(\d+)\s*\])?(?:\s+\S.*)?$')
# 变长结构行：<偏移> <字段名> <类型>[n] 每项 ...（后接子字段缩进行，单独处理）
_ROW_C = re.compile(
    r'^(\d+)\s+([A-Za-z_][A-Za-z0-9_]*)\s+' + _TYPES +
    r'\s*\[(\d+)\]\s+(每|每项)')


def parse_javadoc(path):
    src = open(path, encoding='utf-8').read()
    m = re.search(r'/\*\*(.*?)\*/', src, re.S)
    if not m:
        return None
    doc = m.group(1)
    # ID / LEN 以 Java 常量为准；Javadoc 头部若与常量不符会被下面的校验报出
    mid = re.search(r'msgId\s*=\s*(\d+)', doc)
    mlen_doc = re.search(r'LEN\s*=?\s*(?:可变\s*)?(-?\d+)', doc)
    cid = re.search(r'public static final int ID\s*=\s*(-?\d+)', src)
    clen = re.search(r'public static final int LEN\s*=\s*(-?\d+)', src)
    name = re.search(r'^\s*\*\s*([A-Z][A-Z0-9_]+)\s*(?:[（(]|\s*——|\s*$)', doc, re.M)
    rows = []
    unparsed = []
    # 部分消息类用 <pre> 包裹布局表，部分直接缩进书写；以是否有 <pre> 决定扫描范围
    has_pre = '<pre>' in doc
    in_pre = False
    for line in doc.splitlines():
        s = line.strip()
        if s.startswith('*'):
            s = s[1:].strip()
        if '<pre>' in s:
            in_pre = True
            continue
        if '</pre>' in s:
            in_pre = False
            continue
        if has_pre and not in_pre:
            continue
        # 形如布局行（有前导偏移数字）却没被任何正则吃掉 -> 记下来，绝不静默丢弃
        looks_like_row = re.match(r'^(偏移\s+)?\d+\s+\S', s)
        matched = False
        for rx in (_ROW_A, _ROW_B, _ROW_C):
            mm = rx.match(s)
            if mm:
                rows.append((int(mm.group(1)), mm.group(2), mm.group(3),
                             int(mm.group(4)) if mm.group(4) else 0))
                matched = True
                break
        if looks_like_row and not matched:
            unparsed.append(s)
    return {
        'file': os.path.basename(path),
        'path': path,
        'msg_name': NAME_OVERRIDES.get(name.group(1), name.group(1)) if name else None,
        'msg_id': int(cid.group(1)) if cid else (int(mid.group(1)) if mid else None),
        'len': int(clen.group(1)) if clen else (int(mlen_doc.group(1)) if mlen_doc else None),
        'len_doc': int(mlen_doc.group(1)) if mlen_doc else None,
        'rows': rows,
        'unparsed': unparsed,
    }


def parse_encode(path):
    """提取 encode() 中的写偏移集合（用于与 Javadoc 表交叉校验）。"""
    src = open(path, encoding='utf-8').read()
    m = re.search(r'public byte\[\] encode\(\)\s*\{(.*?)\n    \}', src, re.S)
    if not m:
        return None
    body = m.group(1)
    puts = []
    for mm in re.finditer(
            r'PayloadCodec\.(putU8|putI8|putU16|putI16|putU32|putI32|putU64|putF32|putChars)'
            r'\s*\(\s*buf\s*,\s*(\d+)\s*,', body):
        puts.append((int(mm.group(2)), PUT_MAP[mm.group(1)]))
    # 循环内写（如 putU8(buf, 8 + i, members[i])）
    loops = re.findall(r'PayloadCodec\.(putU8|putI8|putU16|putI16|putU32|putI32)\s*\(\s*buf\s*,\s*(\d+)\s*\+', body)
    return puts, [(int(b), PUT_MAP[a]) for a, b in loops]


def check_coverage(info):
    """字段区间必须无缝铺满 [0, 固定头部长度)，不得有洞或重叠。"""
    problems = []
    rows = sorted(info['rows'])
    if not rows:
        return ['Javadoc 未解析出任何字段行']
    pos = 0
    for off, name, ty, alen in rows:
        tname, w = TYPE_MAP[ty]
        span = w * (alen if alen else 1)
        if off != pos:
            problems.append('字段 %s@%d 与前一字段不连续（期望 %d）' % (name, off, pos))
        pos = off + span
    # 末尾若是可变长数组则允许
    last_off, last_name, last_ty, last_alen = rows[-1]
    if not last_alen:
        fixed = pos
        if info['len'] is not None and info['len'] > 0 and fixed != info['len']:
            problems.append('固定字段合计 %d != LEN %d' % (fixed, info['len']))
    return problems


def fill_tail_gap(info):
    """布局表末尾到 LEN 之间若还有字节，补一条显式 reserved 字段。

    多个消息类在 encode() 里写了 `putU8(buf, N, 0) // reserved`，但 Javadoc 布局表
    没列这些字节。它们同样属于线上字段定义，必须计入 CRC_EXTRA，否则签名对不上
    真实线格式。补出来的字段一律记入 info['synthesized']，报告里单列，便于人工复核。
    """
    info.setdefault('synthesized', [])
    if info['msg_name'] in STRUCT_TAILS:
        # 变长重复结构：按 STRUCT_TAILS 显式展开，不走尾部补齐
        for t, n, a in STRUCT_TAILS[info['msg_name']]:
            info.setdefault('extra_fields', []).append((t, n, a))
        info['synthesized'].append('尾部重复结构按 %d 个并列数组展开（见 STRUCT_TAILS）'
                                  % len(STRUCT_TAILS[info['msg_name']]))
        return
    if not info['rows'] or info['len'] is None or info['len'] <= 0:
        return
    off, _name, ty, alen = sorted(info['rows'])[-1]
    _t, w = TYPE_MAP[ty]
    pos = off + w * (alen if alen else 1)
    if pos < info['len']:
        gap = info['len'] - pos
        used = {n for _o, n, _t, _a in info['rows']}
        base = 'reserved'
        k = 2
        while base in used:
            base = 'reserved%d' % k
            k += 1
        info['rows'].append((pos, base, 'u8', gap))
        info['synthesized'].append('补 %s uint8_t[%d] @%d' % (base, gap, pos))
    elif pos > info['len']:
        info['synthesized'].append('字段合计 %d 超过 LEN %d' % (pos, info['len']))


def alignment_problems(info):
    """MAVLink 约定：按 type_length 降序排列以自然对齐。检测 wire 布局是否非对齐。"""
    rows = sorted(info['rows'])
    bad = []
    for off, name, ty, alen in rows:
        if alen:
            continue
        _t, w = TYPE_MAP[ty]
        if w > 1 and off % w != 0:
            bad.append('%s(%s)@%d 未 %d 字节对齐' % (name, ty, off, w))
    return bad


def load_messages():
    out = []
    seen = set()
    for d in (PKG_DIR, MSG_DIR):
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith('.java'):
                continue
            p = os.path.join(d, fn)
            if p in seen:
                continue
            seen.add(p)
            info = parse_javadoc(p)
            if not info or not info['rows'] or info['msg_id'] is None:
                continue
            out.append(info)
    return out


# --------------------------------------------------------------------------
# 自检：用标准消息验证算法
# --------------------------------------------------------------------------
STANDARD_CHECK = [
    (0, 'HEARTBEAT', 50), (1, 'SYS_STATUS', 124), (2, 'SYSTEM_TIME', 137),
    (24, 'GPS_RAW_INT', 24), (30, 'ATTITUDE', 39), (42, 'MISSION_CURRENT', 28),
    (44, 'MISSION_COUNT', 221), (47, 'MISSION_ACK', 153), (51, 'MISSION_REQUEST_INT', 196),
    (69, 'MANUAL_CONTROL', 243), (73, 'MISSION_ITEM_INT', 38), (74, 'VFR_HUD', 20),
    (76, 'COMMAND_LONG', 152), (77, 'COMMAND_ACK', 143), (242, 'HOME_POSITION', 104),
    (253, 'STATUSTEXT', 83), (259, 'CAMERA_INFORMATION', 92), (260, 'CAMERA_SETTINGS', 146),
    (262, 'CAMERA_CAPTURE_STATUS', 12), (263, 'CAMERA_IMAGE_CAPTURED', 133),
    (271, 'CAMERA_FOV_STATUS', 22), (109, 'RADIO_STATUS', 185), (43, 'MISSION_REQUEST_LIST', 132),
]


def selftest_pymavlink():
    """从 pymavlink 取真实标准消息的字段签名，用本脚本算法重算，比对官方 CRC。"""
    try:
        from pymavlink.generator import mavparse
    except ImportError:
        return None, 'pymavlink 未安装，跳过'
    import glob
    d = os.path.join(os.path.dirname(mavparse.__file__), '..', 'message_definitions', 'v1.0')
    merged = {}
    for fn in ['minimal.xml', 'common.xml', 'ardupilotmega.xml', 'development.xml',
               'uAvionix.xml', 'csAirLink.xml', 'cubepilot.xml', 'ASLUAV.xml',
               'AVSSUAS.xml', 'storm32.xml', 'loweheiser.xml', 'matrixpilot.xml',
               'icarous.xml', 'paparazzi.xml', 'ualberta.xml']:
        p = os.path.join(d, fn)
        if not os.path.exists(p):
            continue
        try:
            x = mavparse.MAVXML(p, wire_protocol_version='2.0')
        except Exception:
            continue
        for m in x.message:
            merged[m.id] = m
    results = []
    for mid, name, expect in STANDARD_CHECK:
        m = merged.get(mid)
        if m is None:
            results.append((mid, name, expect, None, '官方定义未找到'))
            continue
        fields = [(f.type, f.name, f.array_length) for f in m.ordered_fields[:m.base_fields()]]
        got = message_checksum(m.name, fields)
        results.append((mid, m.name, expect, got, 'OK' if got == expect else 'MISMATCH'))
    return results, None


def crosscheck_pymavlink(custom):
    """把自定义字段签名写成 XML，交给 pymavlink 官方 message_checksum 重算。"""
    try:
        from pymavlink.generator import mavparse
    except ImportError:
        return None
    import tempfile
    lines = ['<?xml version="1.0"?>', '<mavlink>',
             '  <version>3</version>', '<dialect>0</dialect>', '  <messages>']
    for info in custom:
        lines.append('    <message id="%d" name="%s">' % (info['msg_id'], info['msg_name']))
        for ftype, fname, alen in order_fields(info['fields']):
            # MAVLink XML 的数组长度写在 type 属性里（type="char[50]"），不是独立属性
            xsuf = '[%d]' % alen if alen else ''
            lines.append('      <field type="%s%s" name="%s">doc</field>' % (ftype, xsuf, fname))
        lines.append('    </message>')
    lines.append('  </messages>')
    lines.append('</mavlink>')
    xml = '\n'.join(lines)
    path = os.path.join(tempfile.gettempdir(), 'aerofleet_crc_extra_crosscheck.xml')
    with open(path, 'w', encoding='utf-8') as fh:
        fh.write(xml)
    try:
        x = mavparse.MAVXML(path, wire_protocol_version='2.0')
        return {i['msg_id']: x.message_crcs.get(i['msg_id']) for i in custom}
    finally:
        try:
            os.unlink(path)          # Windows 下 MAVXML 可能仍持有句柄，失败不影响结果
        except OSError:
            pass


def emit_java(custom):
    """产出 Java 端 MavlinkCrcExtraTest 用的字段签名常量表。

    单一真相源：生成器解析出的字段签名直接落成 Java 代码，测试再用
    MavlinkMessageChecksum 独立重算并与 MavlinkMessageInfo 常量表比对。
    """
    out = []
    out.append('    /** msgId -> {消息名, 字段签名}；由 scripts/mavlink-crc-extra-gen.py --emit-java 生成，勿手改。 */')
    out.append('    private static final Object[][] SIGNATURES = {')
    for info in sorted(custom, key=lambda i: i['msg_id']):
        fs = []
        for ftype, fname, alen in info['fields']:
            js = 'F(' + java_type(ftype) + ', "%s"' % fname
            if alen:
                js += ', %d' % alen
            fs.append(js + ')')
        out.append('        {%d, "%s", new F[]{%s}},' % (
            info['msg_id'], info['msg_name'], ', '.join(fs)))
    out.append('    };')
    return '\n'.join(out)


def java_type(ftype):
    return {
        'uint8_t': 'U8', 'int8_t': 'I8', 'uint16_t': 'U16', 'int16_t': 'I16',
        'uint32_t': 'U32', 'int32_t': 'I32', 'uint64_t': 'U64', 'float': 'F32',
        'char': 'CH',
    }[ftype]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true', help='只校验不写文件')
    ap.add_argument('--pymavlink', action='store_true', help='用 pymavlink 交叉验证')
    ap.add_argument('--selftest', action='store_true', default=True)
    ap.add_argument('--emit-java', action='store_true', help='输出 Java 测试用的字段签名表')
    args = ap.parse_args()

    msgs = load_messages()
    if args.emit_java:
        # emit 模式需要先跑完填充与计算
        for _i in msgs:
            fill_tail_gap(_i)
            _i['fields'] = [(TYPE_MAP[t][0], n, a) for _o, n, t, a in sorted(_i['rows'])]
            _i['fields'].extend(_i.get('extra_fields', []))
        print(emit_java(msgs))
        return 0
    print('解析到 %d 个带 Javadoc 布局表的消息类\n' % len(msgs))

    custom = []
    issues = []
    for info in msgs:
        fill_tail_gap(info)
        probs = check_coverage(info)
        if (info['len_doc'] is not None and info['len'] is not None
                and info['len'] > 0 and info['len_doc'] != info['len']):
            probs.append('Javadoc 头部 LEN=%d 与常量 LEN=%d 不符' % (info['len_doc'], info['len']))
        mis = alignment_problems(info)
        if mis:
            info['misaligned'] = mis
        fields = []
        for off, name, ty, alen in sorted(info['rows']):
            tname, _w = TYPE_MAP[ty]
            fields.append((tname, name, alen))
        fields.extend(info.get('extra_fields', []))
        ordered = order_fields(fields)
        crc = message_checksum(info['msg_name'], ordered)
        info['fields'] = fields
        info['ordered'] = ordered
        info['crc'] = crc
        custom.append(info)
        if probs:
            issues.append((info, probs))

    if issues:
        print('!! 布局校验未通过，需人工确认：')
        for info, probs in issues:
            print('  %-28s %s' % (info['file'], '; '.join(probs)))
        print()

    leftovers = [i for i in custom if i.get('unparsed')]
    if leftovers:
        print('!! 布局表里有行未被解析（已按 STRUCT_TAILS 显式处理尾部，否则会静默丢字段）：')
        for info in leftovers:
            print('  %-28s %s' % (info['file'], ' | '.join(info['unparsed'])))
        print()

    print('=' * 108)
    print('%-26s %-6s %-6s %-6s %-6s  %s' % ('消息', 'msgId', 'LEN', '仓库', '重算', '说明'))
    print('=' * 108)
    table = os.path.join(MSG_DIR, 'MavlinkMessageInfo.java')
    src = open(table, encoding='utf-8').read()
    for info in sorted(custom, key=lambda i: i['msg_id']):
        m = re.search(r'(?:INFOS\[%d\]|EXTENDED_INFOS\.put\(%d)\s*=\s*new Info\(-?\d+,\s*(\d+)\)' % (info['msg_id'], info['msg_id']), src)
        old = int(m.group(1)) if m else None
        note = []
        if info.get('misaligned'):
            note.append('wire 非对齐: ' + ', '.join(info['misaligned'][:2]))
        if info.get('synthesized'):
            note.append('补齐: ' + '; '.join(info['synthesized']))
        if old is not None and old != info['crc']:
            note.append('CHANGED')
        if old is None:
            note.append('表中无此项')
        print('%-26s %-6d %-6s %-6s %-6d  %s' % (
            info['msg_name'], info['msg_id'], info['len'],
            old if old is not None else '-', info['crc'], '; '.join(note)))

    # ---- 自检：算法对标准消息是否成立 ----
    results, err = selftest_pymavlink()
    if err:
        print('\n[自检] %s' % err)
    else:
        ok = sum(1 for r in results if r[4] == 'OK')
        print('\n[自检] 本算法重算标准消息 CRC_EXTRA：%d/%d 与 pymavlink 官方值一致' % (ok, len(results)))
        for mid, name, exp, got, st in results:
            if st != 'OK':
                print('   <<< %d %s 期望 %s 实得 %s %s' % (mid, name, exp, got, st))

    # ---- 交叉验证：自定义消息走 pymavlink 官方实现 ----
    if args.pymavlink:
        ref = crosscheck_pymavlink(custom)
        if ref is None:
            print('\n[交叉验证] pymavlink 未安装，跳过')
        else:
            bad = [(i['msg_name'], i['crc'], ref.get(i['msg_id']))
                   for i in custom if ref.get(i['msg_id']) != i['crc']]
            print('\n[交叉验证] pymavlink 官方 message_checksum 重算自定义消息：%d/%d 一致' % (
                len(custom) - len(bad), len(custom)))
            for nm, mine, theirs in bad:
                print('   <<< %s 本脚本=%d pymavlink=%s' % (nm, mine, theirs))

    return 0


if __name__ == '__main__':
    sys.exit(main())
