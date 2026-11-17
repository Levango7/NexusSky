#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""数据分拣收益估算器（纯计算，无依赖、无硬件）。

回答一个具体问题：无人机把画面回传，到底"传原始视频"还是"只传机载 AI 分拣后的
结构化结果 + 关键帧"，回传带宽差多少？这正是 docs/swarm-comms-architecture.md
里"AI 最值钱处 = 数据分拣（Mbps→Kbps）"那句话的**可复现数字来源**。

重要边界（诚实声明）：
  - 这是**量级估算**，不是实测。输入参数（帧率/JPEG 大小/检测框字节数）由调用方给定，
    输出 = 这些输入下的确定性算术结果；不代表任何真实设备的吞吐量。
  - 链路容量参照 link-sim 的公开经验值（数量级），只用于判断"该档位是否撑得住"，
    不是运营商 SLA。
  - 不做图像解码、不读真实帧、不连网络。要实测请走真机/真链路。

用法：
  python3 scripts/data-triage-bandwidth.py
  python3 scripts/data-triage-bandwidth.py --drones 30 --fps 10 --jpeg-kb 40 \
      --det-per-frame 2 --det-bytes 48 --keyframe-every 50
  python3 scripts/data-triage-bandwidth.py --json
  python3 scripts/data-triage-bandwidth.py --self-test       # 已知答案断言
"""
import argparse
import json
import sys

# 链路容量（比特/秒）：量级取自 link-sim LinkType/LinkProfile 的字节/秒 × 8。
# 注意单位必须是 bps——bps() 返回的是比特/秒，两者直接比较，差 8 倍会误判。
LINK_CAPACITY_BPS = {
    "WIFI": 400_000 * 8,       # WiFi 400_000 B/s = 3.2 Mbps
    "LTE": 80_000 * 8,         # 4G 蜂窝 = 640 Kbps
    "LORA": 5_760 * 8,         # SiK 数传 57600bps
    "SATELLITE": 600_000 * 8,  # LEO 宽带 = 4.8 Mbps（北斗短报文远低于此，另计）
}


def bps(drones, fps, bytes_per_frame):
    """回传比特率 = 机数 × 帧率 × 每帧字节 × 8。"""
    return drones * fps * bytes_per_frame * 8


def estimate(drones, fps, jpeg_kb, det_per_frame, det_bytes, keyframe_every):
    """给定输入，返回四种回传模式的比特率与链路适配判断。"""
    if drones <= 0 or fps <= 0 or jpeg_kb <= 0 or det_per_frame < 0 or det_bytes < 0:
        raise ValueError("drones/fps/jpeg-kb 必须为正，det 参数非负")
    if keyframe_every <= 0:
        raise ValueError("keyframe-every 必须为正整数")

    jpeg_bytes = int(jpeg_kb * 1024)
    raw = bps(drones, fps, jpeg_bytes)                                   # 原始视频全传
    structured = bps(drones, fps, det_per_frame * det_bytes)             # 只传检测框
    keyframes = bps(drones, fps / keyframe_every, jpeg_bytes)            # 只传关键帧
    hybrid = structured + keyframes                                       # 结构化 + 关键帧

    def fits(rate):
        return {name: (rate <= cap) for name, cap in LINK_CAPACITY_BPS.items()}

    saved = 0.0 if raw == 0 else (1 - hybrid / raw) * 100
    return {
        "input": {
            "drones": drones, "fps": fps, "jpeg_kb": jpeg_kb,
            "det_per_frame": det_per_frame, "det_bytes": det_bytes,
            "keyframe_every": keyframe_every,
        },
        "rates_bps": {
            "raw_video": raw,
            "structured_only": structured,
            "keyframes_only": keyframes,
            "hybrid": hybrid,
        },
        "savings_pct": round(saved, 2),
        "link_fits_hybrid": fits(hybrid),
        "link_fits_raw": fits(raw),
    }


def human(result):
    r = result
    inp = r["input"]
    rates = r["rates_bps"]
    def mb(v):
        return "%10.1f Kbps" % (v / 1000) if v < 1_000_000 else "%10.2f Mbps" % (v / 1_000_000)
    out = []
    out.append("数据分拣收益估算（量级估算，非实测）")
    out.append("  输入: %d 架 × %d fps × %s KB/帧, 每帧 %d 框 × %d B/框, 关键帧每 %d 帧"
               % (inp["drones"], inp["fps"], inp["jpeg_kb"], inp["det_per_frame"],
                  inp["det_bytes"], inp["keyframe_every"]))
    out.append("")
    out.append("  回传模式                 带宽")
    out.append("  " + "-" * 42)
    out.append("  原始视频（全传）       %s" % mb(rates["raw_video"]))
    out.append("  仅结构化检测结果       %s" % mb(rates["structured_only"]))
    out.append("  仅关键帧               %s" % mb(rates["keyframes_only"]))
    out.append("  结构化 + 关键帧        %s" % mb(rates["hybrid"]))
    out.append("")
    out.append("  分拣节省（混合 vs 原始）: %.2f%%" % r["savings_pct"])
    out.append("")
    out.append("  链路容量适配（混合模式）:")
    for name, ok in r["link_fits_hybrid"].items():
        out.append("    %-10s %s" % (name, "撑得住" if ok else "撑不住（需降级/降帧/只发事件）"))
    out.append("  链路容量适配（原始视频）:")
    for name, ok in r["link_fits_raw"].items():
        out.append("    %-10s %s" % (name, "撑得住" if ok else "撑不住"))
    return "\n".join(out)


def self_test():
    """已知答案断言：改坏算术/阈值即红。"""
    # 1 架 10fps 40KB/帧 原始视频 = 1*10*40960*8 = 3,276,800 bps
    e = estimate(1, 10, 40, 2, 48, 50)
    assert e["rates_bps"]["raw_video"] == 3_276_800, e["rates_bps"]["raw_video"]
    # 结构化 = 1*10*2*48*8 = 7,680 bps
    assert e["rates_bps"]["structured_only"] == 7_680, e["rates_bps"]["structured_only"]
    # 关键帧 = 1*(10/50)*40960*8 = 65,536 bps
    assert e["rates_bps"]["keyframes_only"] == 65_536, e["rates_bps"]["keyframes_only"]
    # 混合 = 7,680 + 65,536 = 73,216
    assert e["rates_bps"]["hybrid"] == 73_216, e["rates_bps"]["hybrid"]
    # 节省 = (1 - 73216/3276800)*100 ≈ 97.77%
    assert abs(e["savings_pct"] - 97.77) < 0.01, e["savings_pct"]
    # 混合 73kbps < LoRa 57600bps? 不：73216 > 57600 ⇒ LoRa 撑不住
    assert e["link_fits_hybrid"]["LORA"] is False
    assert e["link_fits_hybrid"]["LTE"] is True
    # 原始视频 3.27Mbps > WiFi 3.2Mbps ⇒ 撑不住
    assert e["link_fits_raw"]["WIFI"] is False
    # 单位回归：20 架的混合模式 1.46Mbps，在 WiFi(3.2Mbps)/卫星(4.8Mbps) 应撑得住。
    # 若链路容量误用 link-sim 的字节/秒（400000/600000）与 bps 直接比，这两条会变 False ⇒ 红。
    e20 = estimate(20, 10, 40, 2, 48, 50)
    assert e20["rates_bps"]["hybrid"] == 1_464_320, e20["rates_bps"]["hybrid"]
    assert e20["link_fits_hybrid"]["WIFI"] is True, "WiFi 容量单位错（字节/秒 vs bps）"
    assert e20["link_fits_hybrid"]["SATELLITE"] is True, "卫星容量单位错（字节/秒 vs bps）"
    # 参数校验
    for bad in [(0, 10, 40, 2, 48, 50), (1, 10, 40, 2, 48, 0)]:
        try:
            estimate(*bad)
            raise AssertionError("expected ValueError for %r" % (bad,))
        except ValueError:
            pass
    print("self-test ok")
    return 0


def main():
    p = argparse.ArgumentParser(description="数据分拣收益估算（量级，非实测）")
    p.add_argument("--drones", type=int, default=20)
    p.add_argument("--fps", type=int, default=10)
    p.add_argument("--jpeg-kb", type=float, default=40.0)
    p.add_argument("--det-per-frame", type=int, default=2)
    p.add_argument("--det-bytes", type=int, default=48)
    p.add_argument("--keyframe-every", type=int, default=50)
    p.add_argument("--json", action="store_true", help="输出 JSON")
    p.add_argument("--self-test", action="store_true", help="已知答案断言")
    a = p.parse_args()
    if a.self_test:
        return self_test()
    try:
        r = estimate(a.drones, a.fps, a.jpeg_kb, a.det_per_frame, a.det_bytes, a.keyframe_every)
    except ValueError as e:
        print("参数错误: %s" % e, file=sys.stderr)
        return 2
    print(json.dumps(r, ensure_ascii=False, indent=2) if a.json else human(r))
    return 0


if __name__ == "__main__":
    sys.exit(main())
