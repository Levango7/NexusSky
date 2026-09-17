#!/usr/bin/env python3
"""
NexusSky MAVLink 协议兼容性验证脚本（纯 socket，不依赖 pymavlink）。

验证内容：
  1. 标准 MAVLink 消息（HEARTBEAT, ATTITUDE, GLOBAL_POSITION_INT 等）编解码正确性
  2. NexusSky 扩展消息（msgId 420-476）编解码一致性
  3. MAVLink v1 vs v2 帧兼容性
  4. 与 cloud-backend（UDP 14550）的端到端往返验证

帧格式参考 mavlink-core MavlinkFrame.java：
  v2: STX(0xFD) | LEN | INC | COMPAT | SEQ | SID | CID | MSGID(3B LE) | PAYLOAD | CRC(2B LE)
  v1: STX(0xFE) | LEN | SEQ | SID | CID | MSGID(1B)  | PAYLOAD | CRC(2B LE)

CRC 算法参考 MavlinkCrc.java：CRC-16/X.25（init=0xFFFF, poly_reflected=0x8408）。
标准测试向量：ASCII "123456789" 的 CRC = 0x906E。

用法：
  python3 mavlink-compatibility-check.py --self-test        # 离线自检（编解码一致性，无需后端）
  python3 mavlink-compatibility-check.py --roundtrip         # 端到端往返（需 cloud-backend 运行）
  python3 mavlink-compatibility-check.py --all               # 全部
  python3 mavlink-compatibility-check.py --list-messages     # 列出所有已知消息

退出码：0=全部通过，1=有失败项。

来源：消息表提取自 mavlink-core/.../MavlinkMessageInfo.java（2026-09 版本）。
"""

import argparse
import socket
import struct
import sys
import time

# ───────────────────────────── 常量 ─────────────────────────────

STX_V2 = 0xFD
STX_V1 = 0xFE

# cloud-backend UdpGateway 默认配置
CLOUD_HOST = "127.0.0.1"
CLOUD_PORT = 14550          # aerofleet.udp-port
GCS_SYSID = 255
GCS_COMPID = 190            # MAV_COMP_ID_MISSIONPLANNER
DRONE_SYSID = 1
DRONE_COMPID = 1

# MAVLink 枚举（仅用到的）
MAV_TYPE_QUADROTOR = 2
MAV_AUTOPILOT_PX4 = 12
MAV_MODE_FLAG_SAFETY_ARMED = 128
MAV_STATE_ACTIVE = 4
MAV_STATE_STANDBY = 3

# ───────────────────────── 消息定义表 ─────────────────────────
# (msg_id, name, length, crc_extra)
# 提取自 mavlink-core MavlinkMessageInfo.java。length=-1 表示可变长度。

STANDARD_MESSAGES = [
    (0,   "HEARTBEAT",           9,   50),
    (1,   "SYS_STATUS",         43,  124),
    (2,   "SYSTEM_TIME",        12,  137),
    (24,  "GPS_RAW_INT",        52,   24),
    (30,  "ATTITUDE",           28,   39),
    (33,  "GLOBAL_POSITION_INT",28,  104),
    (42,  "MISSION_CURRENT",    18,   28),
    (43,  "MISSION_REQUEST",     5,  230),
    (44,  "MISSION_COUNT",       9,  221),
    (47,  "MISSION_ACK",         8,  153),
    (51,  "MISSION_REQUEST_INT", 5,  196),
    (69,  "MANUAL_CONTROL",     26,  243),
    (73,  "MISSION_ITEM_INT",   38,   38),
    (74,  "VFR_HUD",            20,   20),
    (76,  "COMMAND_LONG",       33,  152),
    (77,  "COMMAND_ACK",        10,  143),
    (109, "RADIO_STATUS",        9,   88),
    (143, "MISSION_REQUEST_LIST",4,  132),
    (242, "HOME_POSITION",      60,  104),
    (253, "STATUSTEXT",         54,   83),
]

EXTENSION_MESSAGES = [
    (420, "LED_CONTROL",              18,   233),
    (421, "ENVIRONMENT_ALERT",        46, 25705),
    (422, "ENVIRONMENT_STATUS",       13, 36086),
    (423, "SPRAY_STATUS",             12, 58864),
    (424, "SPRAY_COMMAND",             6, 52077),
    (425, "GRIPPER_COMMAND",           7, 46389),
    (426, "PAYLOAD_STATUS",           10,  9268),
    (430, "OBSTACLE_REPORT",          20,   201),
    (431, "MULTISPECTRAL_DATA",       24,   202),
    (432, "THERMAL_DATA",             24,   203),
    (433, "DEPTH_DATA",               20,   204),
    (434, "VISION_DETECTION",         20,   205),
    (437, "RADAR_SCAN",               20,   211),
    (438, "RADAR_TARGET",             28,   212),
    (439, "ROTOR_TELEMETRY",          24,   213),
    (440, "LIDAR_DATA",               20,   214),
    (441, "IMU_DATA",                 41,   215),
    (450, "MESH_HEARTBEAT",           24,   233),
    (451, "MESH_ROUTE_REQUEST",       12,   234),
    (452, "MESH_ROUTE_REPLY",         10,   235),
    (453, "MESH_ROUTE_ERROR",          4,   236),
    (454, "MESH_NEIGHBOR_TABLE",      -1,   237),   # 可变长度
    (455, "CELL_TOWER_STATUS",        15,   245),
    (456, "CELL_TOWER_CONFIG",         7,   246),
    (457, "CELL_HANDOVER",             5,   247),
    (458, "GROUND_TERMINAL_REGISTER", 12,   248),
    (459, "SAT_LINK_STATUS",          24,   238),
    (460, "SAT_PASS_SCHEDULE",        16,   239),
    (461, "HIERARCHICAL_ROUTE_DECISION",34, 240),
    (462, "TERRAIN_TYPE_MAP",         -1,   242),   # 可变长度
    (463, "TERRAIN_UPDATE",           -1,   243),   # 可变长度
    (464, "FLIGHT_RESTRICTION",       -1,   244),   # 可变长度
    (465, "EMERGENCY_MISSION_PLAN",   25,   249),
    (466, "COVERAGE_OPTIMIZATION",    24,   250),
    (467, "EMERGENCY_PRIORITY",       50,   251),
    (468, "TASK_ASSIGNMENT",          18,   252),
    (469, "CONFLICT_ALERT",           12,   253),
    (470, "TASK_STATUS",              11,   254),
    (471, "DECISION_EVENT",           15,   255),
    (472, "ADAPTIVE_PATH",            20,   256),
    (473, "EDGE_TASK_STATUS",         13,   257),
    (474, "SENSOR_FUSION_DATA",       24,   258),
    (475, "TWIN_STATE_SYNC",          28,   259),
    (476, "PREDICTION_RESULT",        20,   260),
]

ALL_MESSAGES = STANDARD_MESSAGES + EXTENSION_MESSAGES
MSG_MAP = {m[0]: m for m in ALL_MESSAGES}

# ───────────────────────── CRC-16/X.25 ─────────────────────────


class MavlinkCrc:
    """CRC-16/X.25（与 mavlink-core MavlinkCrc.java 逐字节等价）。"""

    POLY = 0x8408

    @staticmethod
    def init():
        return 0xFFFF

    @staticmethod
    def accumulate(crc, data_byte):
        c = crc ^ (data_byte & 0xFF)
        for _ in range(8):
            if c & 1:
                c = (c >> 1) ^ MavlinkCrc.POLY
            else:
                c >>= 1
        return c & 0xFFFF

    @staticmethod
    def compute(data: bytes) -> int:
        """对字节序列计算 CRC-16/X.25。"""
        crc = MavlinkCrc.init()
        for b in data:
            crc = MavlinkCrc.accumulate(crc, b)
        return crc


def _crc_known_vector_check():
    """
    标准测试向量：ASCII '123456789' -> 0x6F91。

    MAVLink 实际使用 CRC-16/MCRF4XX 变体（init=0xFFFF, poly=0x1021 反射为 0x8408,
    xorout=0x0000），与 mavlink-core MavlinkCrc.java 逐字节等价。
    注：CRC-16/X.25（xorout=0xFFFF）对同一输入给出 0x906E，但 MAVLink 不带最终异或。
    """
    return MavlinkCrc.compute(b"123456789") == 0x6F91


# ───────────────────────── 帧打包/解包 ─────────────────────────


def _crc_extra_for(msg_id: int) -> int:
    entry = MSG_MAP.get(msg_id)
    if entry is None:
        raise ValueError(f"未知 msgId={msg_id}，无 CRC_EXTRA")
    return entry[3]


def pack_v2(sys_id, comp_id, seq, msg_id, payload: bytes) -> bytes:
    """
    打包 MAVLink v2 帧。
    线上布局：STX | LEN | INC | COMPAT | SEQ | SID | CID | MSGID(3B LE) | PAYLOAD | CRC(2B LE)
    CRC 覆盖：LEN, INC, COMPAT, SEQ, SID, CID, MSGID(3B), PAYLOAD, CRC_EXTRA。
    """
    crc_extra = _crc_extra_for(msg_id)
    length = len(payload)

    # CRC 输入：头字段（不含 STX）+ payload + crc_extra
    crc = MavlinkCrc.init()
    crc = MavlinkCrc.accumulate(crc, length)
    crc = MavlinkCrc.accumulate(crc, 0)          # incompatibility flags
    crc = MavlinkCrc.accumulate(crc, 0)          # compatibility flags
    crc = MavlinkCrc.accumulate(crc, seq & 0xFF)
    crc = MavlinkCrc.accumulate(crc, sys_id & 0xFF)
    crc = MavlinkCrc.accumulate(crc, comp_id & 0xFF)
    crc = MavlinkCrc.accumulate(crc, msg_id & 0xFF)
    crc = MavlinkCrc.accumulate(crc, (msg_id >> 8) & 0xFF)
    crc = MavlinkCrc.accumulate(crc, (msg_id >> 16) & 0xFF)
    for b in payload:
        crc = MavlinkCrc.accumulate(crc, b)
    crc = MavlinkCrc.accumulate(crc, crc_extra & 0xFF)

    # 手动拼接：MSGID 用 3 字节小端，避免 struct 对齐问题
    frame = bytes([
        STX_V2, length, 0, 0, seq & 0xFF,
        sys_id & 0xFF, comp_id & 0xFF,
        msg_id & 0xFF, (msg_id >> 8) & 0xFF, (msg_id >> 16) & 0xFF,
    ]) + payload + struct.pack("<H", crc)
    return frame


def pack_v1(sys_id, comp_id, seq, msg_id, payload: bytes) -> bytes:
    """
    打包 MAVLink v1 帧。
    线上布局：STX(0xFE) | LEN | SEQ | SID | CID | MSGID(1B) | PAYLOAD | CRC(2B LE)
    v1 的 MSGID 仅 1 字节，msg_id > 255 的扩展消息无法用 v1 发送。
    CRC 覆盖：LEN, SEQ, SID, CID, MSGID(1B), PAYLOAD, CRC_EXTRA。
    """
    if msg_id > 255:
        raise ValueError(f"MAVLink v1 不支持 msgId={msg_id}（>255），扩展消息必须用 v2")
    crc_extra = _crc_extra_for(msg_id)
    length = len(payload)

    crc = MavlinkCrc.init()
    crc = MavlinkCrc.accumulate(crc, length)
    crc = MavlinkCrc.accumulate(crc, seq & 0xFF)
    crc = MavlinkCrc.accumulate(crc, sys_id & 0xFF)
    crc = MavlinkCrc.accumulate(crc, comp_id & 0xFF)
    crc = MavlinkCrc.accumulate(crc, msg_id & 0xFF)
    for b in payload:
        crc = MavlinkCrc.accumulate(crc, b)
    crc = MavlinkCrc.accumulate(crc, crc_extra & 0xFF)

    frame = bytes([
        STX_V1, length, seq & 0xFF,
        sys_id & 0xFF, comp_id & 0xFF, msg_id & 0xFF,
    ]) + payload + struct.pack("<H", crc)
    return frame


def unpack(frame: bytes):
    """
    解包 MAVLink 帧（自动识别 v1/v2）。
    返回 dict: {version, sys_id, comp_id, seq, msg_id, payload, crc, crc_ok}
    """
    if len(frame) < 8:
        raise ValueError(f"帧太短: {len(frame)} 字节")

    stx = frame[0]
    if stx == STX_V2:
        return _unpack_v2(frame)
    elif stx == STX_V1:
        return _unpack_v1(frame)
    else:
        raise ValueError(f"未知 STX=0x{stx:02X}")


def _unpack_v2(frame: bytes):
    length = frame[1]
    # inc = frame[2], compat = frame[3]
    seq = frame[4]
    sys_id = frame[5]
    comp_id = frame[6]
    msg_id = frame[7] | (frame[8] << 8) | (frame[9] << 16)
    payload = frame[10:10 + length]
    crc_recv = frame[10 + length] | (frame[11 + length] << 8)

    # 重算 CRC 校验
    crc = MavlinkCrc.init()
    crc = MavlinkCrc.accumulate(crc, length)
    crc = MavlinkCrc.accumulate(crc, frame[2])
    crc = MavlinkCrc.accumulate(crc, frame[3])
    crc = MavlinkCrc.accumulate(crc, seq)
    crc = MavlinkCrc.accumulate(crc, sys_id)
    crc = MavlinkCrc.accumulate(crc, comp_id)
    crc = MavlinkCrc.accumulate(crc, frame[7])
    crc = MavlinkCrc.accumulate(crc, frame[8])
    crc = MavlinkCrc.accumulate(crc, frame[9])
    for b in payload:
        crc = MavlinkCrc.accumulate(crc, b)
    crc_extra = _crc_extra_for(msg_id)
    crc = MavlinkCrc.accumulate(crc, crc_extra & 0xFF)

    return {
        "version": 2, "sys_id": sys_id, "comp_id": comp_id, "seq": seq,
        "msg_id": msg_id, "payload": payload,
        "crc": crc_recv, "crc_ok": crc == crc_recv,
    }


def _unpack_v1(frame: bytes):
    length = frame[1]
    seq = frame[2]
    sys_id = frame[3]
    comp_id = frame[4]
    msg_id = frame[5]
    payload = frame[6:6 + length]
    crc_recv = frame[6 + length] | (frame[7 + length] << 8)

    crc = MavlinkCrc.init()
    crc = MavlinkCrc.accumulate(crc, length)
    crc = MavlinkCrc.accumulate(crc, seq)
    crc = MavlinkCrc.accumulate(crc, sys_id)
    crc = MavlinkCrc.accumulate(crc, comp_id)
    crc = MavlinkCrc.accumulate(crc, msg_id)
    for b in payload:
        crc = MavlinkCrc.accumulate(crc, b)
    crc_extra = _crc_extra_for(msg_id)
    crc = MavlinkCrc.accumulate(crc, crc_extra & 0xFF)

    return {
        "version": 1, "sys_id": sys_id, "comp_id": comp_id, "seq": seq,
        "msg_id": msg_id, "payload": payload,
        "crc": crc_recv, "crc_ok": crc == crc_recv,
    }


# ───────────────────── 标准消息编解码 ─────────────────────


def encode_heartbeat(custom_mode=0, mav_type=MAV_TYPE_QUADROTOR,
                     autopilot=MAV_AUTOPILOT_PX4, base_mode=0,
                     system_status=MAV_STATE_STANDBY) -> bytes:
    """HEARTBEAT (msgId=0, LEN=9)：custom_mode(u32) + type(u8) + autopilot(u8) + base_mode(u8) + system_status(u8) + mavlink_version(u8=3)。"""
    return struct.pack("<IBBBBB", custom_mode, mav_type, autopilot,
                       base_mode, system_status, 3)


def decode_heartbeat(payload: bytes) -> dict:
    cm, typ, ap, bm, ss, mv = struct.unpack("<IBBBBB", payload[:9])
    return {"custom_mode": cm, "type": typ, "autopilot": ap,
            "base_mode": bm, "system_status": ss, "mavlink_version": mv}


def encode_attitude(time_boot_ms=0, roll=0.0, pitch=0.0,
                    yaw=0.0, rollspeed=0.0, pitchspeed=0.0,
                    yawspeed=0.0) -> bytes:
    """ATTITUDE (msgId=30, LEN=28)：time_boot_ms(u32) + roll(f32) + pitch(f32) + yaw(f32) + rollspeed(f32) + pitchspeed(f32) + yawspeed(f32)。"""
    return struct.pack("<Iffffff", time_boot_ms, roll, pitch, yaw,
                       rollspeed, pitchspeed, yawspeed)


def decode_attitude(payload: bytes) -> dict:
    t, r, p, y, rs, ps, ys = struct.unpack("<Iffffff", payload[:28])
    return {"time_boot_ms": t, "roll": r, "pitch": p, "yaw": y,
            "rollspeed": rs, "pitchspeed": ps, "yawspeed": ys}


def encode_global_position_int(time_boot_ms=0, lat=0, lon=0, alt=0,
                               relative_alt=0, vx=0, vy=0, vz=0,
                               hdg=0) -> bytes:
    """GLOBAL_POSITION_INT (msgId=33, LEN=28)：time_boot_ms(u32) + lat(i32) + lon(i32) + alt(i32) + relative_alt(i32) + vx(i16) + vy(i16) + vz(i16) + hdg(u16)。"""
    return struct.pack("<IiiiihhhH", time_boot_ms, lat, lon, alt,
                       relative_alt, vx, vy, vz, hdg)


def decode_global_position_int(payload: bytes) -> dict:
    t, lat, lon, alt, rel, vx, vy, vz, hdg = struct.unpack("<IiiiihhhH", payload[:28])
    return {"time_boot_ms": t, "lat": lat, "lon": lon, "alt": alt,
            "relative_alt": rel, "vx": vx, "vy": vy, "vz": vz, "hdg": hdg}


def encode_mission_item_int(seq=0, frame=0, command=16, current=0,
                            autocontinue=1, param1=0.0, param2=0.0,
                            param3=0.0, param4=0.0, x=0, y=0, z=0.0,
                            mission_type=0) -> bytes:
    """MISSION_ITEM_INT (msgId=73, LEN=36)。
    字段：seq(u16) + frame(u8) + command(u16) + current(u8) + autocontinue(u8)
         + param1(f32) + param2(f32) + param3(f32) + param4(f32)
         + x(i32) + y(i32) + z(f32) + mission_type(u8)
    """
    return struct.pack("<HBHBBffffiifB", seq, frame, command, current,
                       autocontinue, param1, param2, param3, param4,
                       x, y, z, mission_type)


def decode_mission_item_int(payload: bytes) -> dict:
    vals = struct.unpack("<HBHBBffffiifB", payload[:36])
    return {"seq": vals[0], "frame": vals[1], "command": vals[2],
            "current": vals[3], "autocontinue": vals[4],
            "param1": vals[5], "param2": vals[6], "param3": vals[7],
            "param4": vals[8], "x": vals[9], "y": vals[10],
            "z": vals[11], "mission_type": vals[12]}


# ───────────────────── 扩展消息编解码（示例） ─────────────────────


def encode_spray_status(enabled=1, rate=250, remaining=50000,
                        coverage=75, low_chem=0, drift=0,
                        flow_corr=0) -> bytes:
    """SPRAY_STATUS (msgId=423, LEN=12) —— NexusSky M2 喷洒物流扩展消息。
    字段：enabled(u8) + rate(u16) + remaining(u16) + coverage(u8) + low_chem(u8)
         + drift(i16) + flow_corr(u8) + reserved(u8) + reserved(u8)
    """
    return struct.pack("<BHHBBhBBB", enabled & 0xFF, rate & 0xFFFF,
                       remaining & 0xFFFF, coverage & 0xFF, low_chem & 0xFF,
                       drift, flow_corr & 0xFF, 0, 0)


def decode_spray_status(payload: bytes) -> dict:
    en, rate, rem, cov, low, drift, fc, _, _ = struct.unpack("<BHHBBhBBB", payload[:12])
    return {"enabled": en, "rate": rate, "remaining": rem,
            "coverage": cov, "low_chem": low, "drift": drift, "flow_corr": fc}


# ───────────────────────── 测试框架 ─────────────────────────

_passed = 0
_failed = 0


def ok(msg):
    global _passed
    _passed += 1
    print(f"  ✅ {msg}")


def fail(msg):
    global _failed
    _failed += 1
    print(f"  ❌ {msg}")


def step(title):
    print(f"\n== {title}")


def check(desc, condition, detail=""):
    if condition:
        ok(desc)
    else:
        fail(desc + (f" ({detail})" if detail else ""))


# ───────────────────────── 自检测试 ─────────────────────────


def test_crc_known_vector():
    """验证 CRC-16/X.25 标准测试向量。"""
    step("CRC-16/X.25 标准测试向量")
    check("CRC('123456789') == 0x6F91 (MAVLink 变体)", _crc_known_vector_check(),
          f"实际=0x{MavlinkCrc.compute(b'123456789'):04X}")


def test_standard_messages_codec():
    """标准消息编解码一致性：构造 payload -> 打包 v2 帧 -> 解包 -> 比对。"""
    step("标准消息编解码一致性（v2 帧往返）")

    # HEARTBEAT
    hb = encode_heartbeat(custom_mode=3, base_mode=MAV_MODE_FLAG_SAFETY_ARMED,
                          system_status=MAV_STATE_ACTIVE)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, 0, 0, hb)
    info = unpack(frame)
    check("HEARTBEAT 帧解包 msg_id=0", info["msg_id"] == 0)
    check("HEARTBEAT CRC 校验通过", info["crc_ok"])
    dec = decode_heartbeat(info["payload"])
    check("HEARTBEAT custom_mode 往返一致", dec["custom_mode"] == 3)
    check("HEARTBEAT base_mode 往返一致", dec["base_mode"] == MAV_MODE_FLAG_SAFETY_ARMED)
    check("HEARTBEAT mavlink_version=3", dec["mavlink_version"] == 3)

    # ATTITUDE
    att = encode_attitude(time_boot_ms=12345, roll=0.1, pitch=-0.05,
                          yaw=1.57, rollspeed=0.01, pitchspeed=0.02, yawspeed=0.0)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, 1, 30, att)
    info = unpack(frame)
    check("ATTITUDE 帧解包 msg_id=30", info["msg_id"] == 30)
    check("ATTITUDE CRC 校验通过", info["crc_ok"])
    dec = decode_attitude(info["payload"])
    check("ATTITUDE roll 往返一致", abs(dec["roll"] - 0.1) < 1e-6)
    check("ATTITUDE yaw 往返一致", abs(dec["yaw"] - 1.57) < 1e-6)

    # GLOBAL_POSITION_INT
    gpi = encode_global_position_int(time_boot_ms=99999, lat=225907000,
                                     lon=1139345000, alt=50000,
                                     relative_alt=30000, vx=100, vy=-50,
                                     vz=10, hdg=18000)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, 2, 33, gpi)
    info = unpack(frame)
    check("GLOBAL_POSITION_INT 帧解包 msg_id=33", info["msg_id"] == 33)
    check("GLOBAL_POSITION_INT CRC 校验通过", info["crc_ok"])
    dec = decode_global_position_int(info["payload"])
    check("GLOBAL_POSITION_INT lat 往返一致", dec["lat"] == 225907000)
    check("GLOBAL_POSITION_INT lon 往返一致", dec["lon"] == 1139345000)

    # MISSION_ITEM_INT
    mi = encode_mission_item_int(seq=0, command=16, x=225907000,
                                 y=1139345000, z=50.0, autocontinue=1)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, 3, 73, mi)
    info = unpack(frame)
    check("MISSION_ITEM_INT 帧解包 msg_id=73", info["msg_id"] == 73)
    check("MISSION_ITEM_INT CRC 校验通过", info["crc_ok"])
    dec = decode_mission_item_int(info["payload"])
    check("MISSION_ITEM_INT command 往返一致", dec["command"] == 16)
    check("MISSION_ITEM_INT x 往返一致", dec["x"] == 225907000)


def test_extension_messages_codec():
    """NexusSky 扩展消息（420-476）编解码一致性。"""
    step("NexusSky 扩展消息（420-476）编解码一致性")

    # SPRAY_STATUS（具体字段编解码）
    ss = encode_spray_status(enabled=1, rate=250, remaining=48000,
                             coverage=60, low_chem=0, drift=-150, flow_corr=5)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, 0, 423, ss)
    info = unpack(frame)
    check("SPRAY_STATUS 帧解包 msg_id=423", info["msg_id"] == 423)
    check("SPRAY_STATUS CRC 校验通过", info["crc_ok"])
    dec = decode_spray_status(info["payload"])
    check("SPRAY_STATUS rate 往返一致", dec["rate"] == 250)
    check("SPRAY_STATUS remaining 往返一致", dec["remaining"] == 48000)
    check("SPRAY_STATUS drift 往返一致", dec["drift"] == -150)

    # 所有扩展消息的帧层透传往返（含可变长度消息）
    step("扩展消息帧层透传往返（全部 420-476）")
    for msg_id, name, length, crc_extra in EXTENSION_MESSAGES:
        # 可变长度用 8 字节示例 payload；定长用声明长度
        plen = 8 if length < 0 else length
        payload = bytes(range(plen))  # 0,1,2,...,plen-1
        try:
            frame = pack_v2(DRONE_SYSID, DRONE_COMPID, 0, msg_id, payload)
            info = unpack(frame)
            check(f"{name}(id={msg_id}) CRC 校验通过", info["crc_ok"])
            check(f"{name}(id={msg_id}) payload 往返一致",
                  info["payload"] == payload)
        except Exception as e:
            fail(f"{name}(id={msg_id}) 异常: {e}")


def test_v1_v2_compatibility():
    """MAVLink v1 vs v2 兼容性验证。"""
    step("MAVLink v1 vs v2 兼容性")

    # v1 能发送 msgId <= 255 的标准消息
    hb = encode_heartbeat()
    frame_v1 = pack_v1(DRONE_SYSID, DRONE_COMPID, 0, 0, hb)
    info_v1 = unpack(frame_v1)
    check("v1 HEARTBEAT 识别为 version=1", info_v1["version"] == 1)
    check("v1 HEARTBEAT CRC 校验通过", info_v1["crc_ok"])
    check("v1 HEARTBEAT payload 往返一致", info_v1["payload"] == hb)

    # v2 发送同一消息，payload 应一致
    frame_v2 = pack_v2(DRONE_SYSID, DRONE_COMPID, 0, 0, hb)
    info_v2 = unpack(frame_v2)
    check("v2 HEARTBEAT 识别为 version=2", info_v2["version"] == 2)
    check("v1/v2 HEARTBEAT payload 一致", info_v1["payload"] == info_v2["payload"])

    # v1 帧比 v2 帧短 4 字节（v2 多 INC + COMPAT + MSGID 额外 2 字节）
    check("v1 帧比 v2 帧短 4 字节", len(frame_v1) == len(frame_v2) - 4,
          f"v1={len(frame_v1)}, v2={len(frame_v2)}")

    # v1 不能发送 msgId > 255 的扩展消息
    try:
        pack_v1(DRONE_SYSID, DRONE_COMPID, 0, 423, b"\x00" * 12)
        fail("v1 应拒绝 msgId=423（>255）")
    except ValueError:
        ok("v1 正确拒绝 msgId=423（扩展消息必须用 v2）")

    # v2 能发送扩展消息
    ss = encode_spray_status()
    frame_ext = pack_v2(DRONE_SYSID, DRONE_COMPID, 0, 423, ss)
    info_ext = unpack(frame_ext)
    check("v2 扩展消息 msg_id=423 CRC 校验通过", info_ext["crc_ok"])

    # 混合流：v1 和 v2 帧交替，解包器自动识别
    mixed = frame_v1 + frame_v2
    info_a = unpack(mixed[:len(frame_v1)])
    info_b = unpack(mixed[len(frame_v1):])
    check("混合流 v1 帧自动识别", info_a["version"] == 1)
    check("混合流 v2 帧自动识别", info_b["version"] == 2)


def test_msg_id_no_conflict():
    """验证扩展消息 ID 不与标准消息冲突。"""
    step("消息 ID 无冲突检查")
    std_ids = {m[0] for m in STANDARD_MESSAGES}
    ext_ids = {m[0] for m in EXTENSION_MESSAGES}
    overlap = std_ids & ext_ids
    check("标准消息与扩展消息 ID 无重叠", len(overlap) == 0,
          f"重叠={overlap}" if overlap else "")

    # 扩展消息 ID 应在 420-476 区间
    out_of_range = [mid for mid in ext_ids if mid < 420 or mid > 476]
    check("扩展消息 ID 均在 420-476 区间", len(out_of_range) == 0,
          f"越界={out_of_range}" if out_of_range else "")


def run_self_test():
    """离线自检：编解码一致性，无需 cloud-backend。"""
    print("=" * 60)
    print("NexusSky MAVLink 协议兼容性自检（离线）")
    print("=" * 60)

    test_crc_known_vector()
    test_msg_id_no_conflict()
    test_standard_messages_codec()
    test_extension_messages_codec()
    test_v1_v2_compatibility()

    print("\n" + "=" * 60)
    print(f"自检结果: {_passed} 通过, {_failed} 失败")
    print("=" * 60)
    return _failed == 0


# ───────────────────── 端到端往返测试 ─────────────────────


def send_frame(sock, frame: bytes, addr):
    """发送一帧到指定地址。"""
    sock.sendto(frame, addr)


def recv_frame(sock, timeout=3.0):
    """
    接收一帧（带超时）。
    返回 (frame_bytes, sender_addr) 或 None（超时）。
    """
    sock.settimeout(timeout)
    try:
        data, addr = sock.recvfrom(2048)
        return data, addr
    except socket.timeout:
        return None


def run_roundtrip_test(host=CLOUD_HOST, port=CLOUD_PORT):
    """
    端到端往返测试：向 cloud-backend 发送 MAVLink 帧，验证后端能接收并响应。

    前置：cloud-backend 已运行并监听 UDP 14550。
    """
    print("=" * 60)
    print(f"NexusSky MAVLink 端到端往返测试 -> {host}:{port}")
    print("=" * 60)

    addr = (host, port)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 0))  # 任意可用端口
    local_port = sock.getsockname()[1]
    print(f"  本地绑定端口: {local_port}")

    seq = 0

    # 1. 发送 HEARTBEAT（模拟无人机上线）
    step("发送 HEARTBEAT（模拟无人机 sysid=1 上线）")
    hb = encode_heartbeat(custom_mode=0, mav_type=MAV_TYPE_QUADROTOR,
                          autopilot=MAV_AUTOPILOT_PX4, base_mode=0,
                          system_status=MAV_STATE_STANDBY)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, seq, 0, hb)
    seq += 1
    send_frame(sock, frame, addr)
    ok(f"已发送 HEARTBEAT v2 帧 ({len(frame)} 字节)")

    # 等待 cloud-backend GCS 心跳响应
    resp = recv_frame(sock, timeout=5.0)
    if resp is not None:
        resp_data, resp_addr = resp
        try:
            resp_info = unpack(resp_data)
            check("收到 cloud-backend 响应帧", True)
            check(f"响应 msg_id={resp_info['msg_id']}",
                  resp_info["msg_id"] is not None)
            check("响应帧 CRC 校验通过", resp_info["crc_ok"],
                  f"recv=0x{resp_info['crc']:04X}")
            check("响应来自 GCS sysid=255", resp_info["sys_id"] == GCS_SYSID,
                  f"实际 sysid={resp_info['sys_id']}")
        except Exception as e:
            fail(f"响应帧解析失败: {e}")
    else:
        fail("未收到 cloud-backend 响应（5s 超时）—— 后端可能未运行或未发现本机")

    # 2. 发送 GLOBAL_POSITION_INT（模拟遥测）
    step("发送 GLOBAL_POSITION_INT（模拟遥测路由）")
    gpi = encode_global_position_int(time_boot_ms=int(time.time() * 1000) % 1000000,
                                     lat=225907000, lon=1139345000,
                                     alt=50000, relative_alt=30000,
                                     vx=100, vy=0, vz=0, hdg=9000)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, seq, 33, gpi)
    seq += 1
    send_frame(sock, frame, addr)
    ok(f"已发送 GLOBAL_POSITION_INT ({len(frame)} 字节)")

    # 3. 发送 ATTITUDE
    step("发送 ATTITUDE")
    att = encode_attitude(time_boot_ms=int(time.time() * 1000) % 1000000,
                          roll=0.05, pitch=0.02, yaw=0.0)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, seq, 30, att)
    seq += 1
    send_frame(sock, frame, addr)
    ok(f"已发送 ATTITUDE ({len(frame)} 字节)")

    # 4. 发送 MISSION_ITEM_INT（模拟任务上传）
    step("发送 MISSION_ITEM_INT（模拟任务管理）")
    mi = encode_mission_item_int(seq=0, command=16, x=225907000,
                                 y=1139345000, z=50.0, autocontinue=1)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, seq, 73, mi)
    seq += 1
    send_frame(sock, frame, addr)
    ok(f"已发送 MISSION_ITEM_INT ({len(frame)} 字节)")

    # 5. 发送扩展消息 SPRAY_STATUS
    step("发送 SPRAY_STATUS（NexusSky 扩展 msg_id=423）")
    ss = encode_spray_status(enabled=1, rate=250, remaining=50000, coverage=50)
    frame = pack_v2(DRONE_SYSID, DRONE_COMPID, seq, 423, ss)
    seq += 1
    send_frame(sock, frame, addr)
    ok(f"已发送 SPRAY_STATUS ({len(frame)} 字节)")

    # 6. 持续接收 2s，统计 cloud-backend 回包
    step("接收 cloud-backend 回包（2s 窗口）")
    recv_count = 0
    gcs_heartbeat_count = 0
    deadline = time.time() + 2.0
    while time.time() < deadline:
        resp = recv_frame(sock, timeout=0.5)
        if resp is None:
            continue
        recv_count += 1
        try:
            info = unpack(resp[0])
            if info["msg_id"] == 0 and info["sys_id"] == GCS_SYSID:
                gcs_heartbeat_count += 1
        except Exception:
            pass

    check(f"收到 {recv_count} 个回包", recv_count > 0)
    check(f"其中 GCS 心跳 {gcs_heartbeat_count} 个", gcs_heartbeat_count > 0,
          "cloud-backend 应周期性回 GCS 心跳以维持链路")

    sock.close()
    print("\n" + "=" * 60)
    print(f"往返测试结果: {_passed} 通过, {_failed} 失败")
    print("=" * 60)
    return _failed == 0


# ───────────────────── 消息列表打印 ─────────────────────


def list_messages():
    print(f"{'ID':>5}  {'Name':<30} {'Len':>5}  {'CRC_EXTRA':>10}  {'Type':<8}")
    print("-" * 65)
    for msg_id, name, length, crc_extra in STANDARD_MESSAGES:
        print(f"{msg_id:>5}  {name:<30} {length:>5}  {crc_extra:>10}  {'标准':<8}")
    for msg_id, name, length, crc_extra in EXTENSION_MESSAGES:
        ln = "变长" if length < 0 else str(length)
        print(f"{msg_id:>5}  {name:<30} {ln:>5}  {crc_extra:>10}  {'扩展':<8}")
    print(f"\n共 {len(ALL_MESSAGES)} 条消息"
          f"（标准 {len(STANDARD_MESSAGES)} + 扩展 {len(EXTENSION_MESSAGES)}）")


# ───────────────────────── 主入口 ─────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="NexusSky MAVLink 协议兼容性验证（纯 socket，无需 pymavlink）")
    parser.add_argument("--self-test", action="store_true",
                        help="离线自检：编解码一致性（无需后端）")
    parser.add_argument("--roundtrip", action="store_true",
                        help="端到端往返测试（需 cloud-backend 运行）")
    parser.add_argument("--all", action="store_true",
                        help="执行全部测试")
    parser.add_argument("--list-messages", action="store_true",
                        help="列出所有已知消息")
    parser.add_argument("--host", default=CLOUD_HOST,
                        help=f"cloud-backend 地址（默认 {CLOUD_HOST}）")
    parser.add_argument("--port", type=int, default=CLOUD_PORT,
                        help=f"cloud-backend UDP 端口（默认 {CLOUD_PORT}）")
    args = parser.parse_args()

    if args.list_messages:
        list_messages()
        return 0

    if args.all:
        args.self_test = True
        args.roundtrip = True

    if not (args.self_test or args.roundtrip):
        parser.print_help()
        return 1

    success = True

    if args.self_test:
        success = run_self_test() and success

    if args.roundtrip:
        success = run_roundtrip_test(args.host, args.port) and success

    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())