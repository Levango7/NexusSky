#!/usr/bin/env python3
"""生成 MAVLink v2 签名的**已知答案向量**（known-answer vectors）。

为什么需要它：本仓此前的签名测试全是"自己的代码生成 → 自己的代码验证"的自洽往返，
证明不了与官方实现互通。这个脚本用独立参考实现 **pymavlink** 打包并签名真实帧，
把结果以十六进制向量形式写进 Java 测试（`MavlinkSigningVectorTest`），于是 Java 侧
"我们算出的 6 字节签名"与"pymavlink 算出的 6 字节签名"逐字节比对——这是真正的互通证据。

用法（需要 pymavlink，仓库内隔离 venv 即可，不进主依赖）：
    python -m venv target/p6-ref-venv
    target/p6-ref-venv/Scripts/python.exe -m pip install pymavlink
    target/p6-ref-venv/Scripts/python.exe scripts/mavlink-signing-vectors.py

向量本身是手工挑选的固定输入（口令/link_id/时间戳/消息字段全写死），因此输出可复现；
把脚本打印的 JSON 粘进 Java 测试常量即可。脚本还会做两件自检：
  ① 用 pymavlink 自己的 check_signature 验一遍它刚生成的帧（证明向量内部一致）；
  ② 打印官方布局的逐段字节（link_id / 6B 小端时间戳 / 6B 签名），供人工核对。
"""

import hashlib
import json
import struct
import sys

from pymavlink.dialects.v20 import common as mavlink2

# 固定输入：绝不使用当前时间，否则向量不可复现
SECRET = b"nexussky-p6-known-answer-secret!"          # 32 字节口令
LINK_ID = 0x07
# 10 微秒单位、自 2015-01-01 起的时间戳（ pymavlink 的口径：int((t-1420070400)*100*1000)）
TIMESTAMP_10US = 370_000_000_000_000 // 10            # 任意固定值，须 < 2**48

SYSID = 1
COMPID = 1
SEQ = 42


def decode_ts48(six: bytes) -> int:
    """48 位小端时间戳解码，与 pymavlink 的 struct.Struct('<IH') 同一口径。"""
    low, high = struct.unpack("<IH", six)
    return low + (high << 32)


def make_mav(secret_key: bytes):
    """构造一个开启签发的 MAVLink 打包器（不绑定文件句柄，只用来 pack）。"""
    mav = mavlink2.MAVLink(None, srcSystem=SYSID, srcComponent=COMPID)
    mav.signing.secret_key = secret_key
    mav.signing.link_id = LINK_ID
    mav.signing.timestamp = TIMESTAMP_10US
    mav.signing.sign_outgoing = True
    return mav


def frame_vectors():
    msgs = {
        # msgId 0：HEARTBEAT，payload 8 字节，含枚举/位域，最常见的首帧
        "HEARTBEAT": lambda m: m.heartbeat_encode(
            custom_mode=684353, type=16, autopilot=9, base_mode=81,
            system_status=4, mavlink_version=3),
        # msgId 33：GLOBAL_POSITION_INT，int32 大数 + 有符号负值，测小端/补码
        "GLOBAL_POSITION_INT": lambda m: m.global_position_int_encode(
            time_boot_ms=1234567, lat=399042000, lon=1164074000, alt=512000,
            relative_alt=100500, vx=-12345, vy=23456, vz=-789, hdg=27000),
        # msgId 0 之外的截断用例：payload 末尾全为 0，官方要求裁掉尾部零字节再补 CRC
        "HEARTBEAT_TRAILING_ZEROS": lambda m: m.heartbeat_encode(
            custom_mode=0, type=0, autopilot=0, base_mode=0,
            system_status=0, mavlink_version=0),
        # 奇数长度 payload：RADIO_STATUS 有 7 个 uint8 字段，官方要求补一个 0 使总长为偶数
        "RADIO_STATUS_ODD_PADDING": lambda m: m.radio_status_encode(
            rssi=200, remrssi=180, txbuf=5, noise=-30 & 0xFF, remnoise=10,
            rxerrors=7, fixed=1),
        # 有符号浮点负值：确认小端补码与截断规则一致
        "ATTITUDE": lambda m: m.attitude_encode(
            time_boot_ms=1000, roll=-0.25, pitch=0.5, yaw=-1.75,
            rollspeed=0.0, pitchspeed=0.0, yawspeed=0.0),
    }
    out = []
    for name, builder in msgs.items():
        mav = make_mav(SECRET)
        before = mav.signing.timestamp
        msg = builder(mav)
        buf = msg.pack(mav)                      # 13 字节签名块已附加
        raw = bytes(bytearray(buf))
        # 校验：pymavlink 自己必须验得过（证明向量自洽、且我方后续按同一规则实现可复现）
        verify_mav = make_mav(SECRET)
        verify_mav.signing.timestamp = before + 10  # 给它一个更新的本地时间戳
        accepted = verify_mav.check_signature(bytearray(raw), SYSID, COMPID)
        sig_off = len(raw) - 13
        out.append({
            "name": name,
            "msgId": msg.get_msgId(),
            "payloadLen": raw[1],                     # v2 header：[0]=STX [1]=LEN [2]=INV [3]=COMPAT
            "signedFrameHex": raw.hex(),
            "frameLen": len(raw),
            "linkId": raw[sig_off],
            "timestampBytesHex": raw[sig_off + 1:sig_off + 7].hex(),
            "timestampDecodedLE": decode_ts48(raw[sig_off + 1:sig_off + 7]),
            "signatureHex": raw[sig_off + 7:].hex(),
            "pymavlinkAcceptedOwnFrame": bool(accepted),
            # 独立复算一遍签名，确认布局与哈希输入的解读一致（secret + 帧头..payload..CRC..linkId..ts6）
            "recomputedSigHex": hashlib.sha256(SECRET + raw[:sig_off + 7]).digest()[:6].hex(),
            "hashInputUpToTsHex": raw[:sig_off + 7].hex(),
        })
    return out


def main() -> int:
    vectors = frame_vectors()
    print(json.dumps({
        "reference": "pymavlink " + getattr(mavlink2, "__version__", "unknown"),
        "secretKeyHex": SECRET.hex(),
        "linkId": LINK_ID,
        "timestamp10usSince2015": TIMESTAMP_10US,
        "vectors": vectors,
    }, indent=2))
    bad = [v["name"] for v in vectors
           if not v["pymavlinkAcceptedOwnFrame"] or v["signatureHex"] != v["recomputedSigHex"]]
    if bad:
        print("SELF-CHECK FAILED: " + ", ".join(bad), file=sys.stderr)
        return 1
    print(f"self-check OK: {len(vectors)} vectors", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
