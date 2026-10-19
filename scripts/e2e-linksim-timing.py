#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""link-sim 逐画像实测：声明的延迟区间 vs 真实投递延迟。

为什么需要这个脚本
------------------
`LinkProfile` 里每个画像声明 `delay = delayMs + uniform(0, jitterMs)`，
`ImpairmentEngine.verdict()` 也确实按这个返回值。但**投递延迟**由
`LinkSimMain.wirePump` 用 `Thread.sleep` 实现，而它的精度上限是操作系统的
线程唤醒粒度 —— 不是引擎的判决。

本机实测（Windows，2026-10-19，**多轮复测**）：

    画像        声明区间        空载 p50（三轮）     有负载时 p50     落在声明区间内
    lan         [0, 0]          1 / 1 / 1            1               本底
    wifi5       [2, 6]          5 / 5 / 6            12              空载 86%，有负载 15%
    starlink    [35, 55]        47 / 46 / 47         53              空载 81~85%，有负载 62%
    lte         [45, 80]        —                    72              78%
    lte-edge    [120, 210]      —                    176             93%

结论要说得准 —— 这里踩过一次：
 * 第一版只跑了一轮（有负载那次），看到 wifi5 p50=12ms，就写成"多出恒定的约 +10ms"。
   复测三轮后发现空载时 wifi5 p50 只有 5~6ms。**+10ms 不是恒定值，是那一次的负载产物。**
 * 真实机制：投递用 Thread.sleep，它的下限是操作系统的线程唤醒粒度（本机 tick
   约 15.6ms）。实际超出量 = 到下一个 tick 边界的距离，取决于调用时刻的相位，
   所以**跑与跑之间天然不同**，负载重时更明显。
 * 因此可说的只有：声明区间**不是保证值**，实际会超出，超出多少随负载与相位变化。
   对低延迟画像影响最大 —— wifi5 声明 [2,6]ms，有负载时实测 p50 12ms，
   此时"协议在 2ms 链路上的表现"这个结论说的其实是"一条约 12ms 的链路"。

同一份代码在 Linux（CI 跑 e2e 的地方）上粒度小得多，所以**同一个画像在开发机与
CI 上不是同一条链路**。这正是本脚本要钉住的事：结论的适用范围。

判据说明
--------
不放任何模型。从 GCS 侧发带序号与发送时刻的包，穿过 link-sim，在飞机侧
数收到的包。低速（5pps）发以排除令牌桶排队。`lan` 画像作对照组：它声明
零延迟，实测值即本机测量装置的本底；若对照本身就偏得厉害，其余数字不作数。

用法
----
    python scripts/e2e-linksim-timing.py
    python scripts/e2e-linksim-timing.py --samples 200 --profiles wifi5,starlink
    python scripts/e2e-linksim-timing.py --json out.json

需要 link-sim 已打包：`mvn -o -q -pl link-sim -am -DskipTests package`
不进 CI（依赖本机调度粒度，且各平台结果本就不同）。
退出码：0 正常产出，1 对照组异常（本底过大），2 前置不满足。
"""

import argparse
import json
import os
import socket
import subprocess
import sys
import threading
import time

HOST = "127.0.0.1"
PROXY_PORT = 14600
DRONE_PORT = 14540

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "link-sim", "target",
                   "aerofleet-link-sim-0.1.0-SNAPSHOT-shaded.jar")

# (profile, delayMs, jitterMs) —— 与 LinkProfile.java 一致；本脚本会核对
DECLARED = [
    ("lan", 0, 0),
    ("wifi5", 2, 4),
    ("lte", 45, 35),
    ("lte-edge", 120, 90),
    ("starlink", 35, 20),
]


def find_java():
    jh = os.environ.get("JAVA_HOME")
    if jh:
        cand = os.path.join(jh, "bin", "java.exe" if os.name == "nt" else "java")
        if os.path.exists(cand):
            return cand
    return "java"


class Drone(threading.Thread):
    """飞机侧：只收，记录每包的单向延迟。"""

    daemon = True

    def __init__(self):
        super().__init__()
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((HOST, DRONE_PORT))
        self.sock.settimeout(0.5)
        self.delays = {}
        self.go = True

    def run(self):
        while self.go:
            try:
                data, _ = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            if len(data) >= 12:
                seq = int.from_bytes(data[:4], "big")
                sent_ms = int.from_bytes(data[4:12], "big")
                self.delays[seq] = time.time() * 1000 - sent_ms
        self.sock.close()


def measure(java, profile, samples, interval):
    d = Drone()
    d.start()
    sim = subprocess.Popen(
        [java, "-jar", JAR, "--profile", profile, "--port", str(PROXY_PORT),
         "--drone-ip", HOST, "--drone-port", str(DRONE_PORT)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    time.sleep(2.0)
    gcs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sent = 0
    try:
        for seq in range(samples):
            ms = int(time.time() * 1000)
            gcs.sendto(seq.to_bytes(4, "big") + ms.to_bytes(8, "big") + b"x" * 52,
                       (HOST, PROXY_PORT))
            sent += 1
            time.sleep(interval)
        time.sleep(6.0)          # 给延迟队列排空（含 starlink 的 2.5s 黑洞）
    finally:
        gcs.close()
        d.go = False
        sim.terminate()
        try:
            sim.wait(timeout=5)
        except subprocess.TimeoutExpired:
            sim.kill()
        d.join(timeout=3)
    return sorted(d.delays.values()), sent


def q(v, p):
    return v[min(len(v) - 1, int(len(v) * p))] if v else float("nan")


def main():
    ap = argparse.ArgumentParser(description="link-sim 逐画像延迟实测")
    ap.add_argument("--samples", type=int, default=120)
    ap.add_argument("--interval", type=float, default=0.2, help="发送间隔秒（默认 5pps）")
    ap.add_argument("--profiles", default="",
                    help="逗号分隔；默认全部声明了延迟的画像")
    ap.add_argument("--json", default="", help="把结果写成 JSON")
    args = ap.parse_args()

    if not os.path.exists(JAR):
        print("  找不到 %s\n  先跑：mvn -o -q -pl link-sim -am -DskipTests package" % JAR)
        return 2
    java = find_java()

    wanted = [p for p in args.profiles.split(",") if p.strip()]
    cases = [c for c in DECLARED if not wanted or c[0] in wanted]

    print("  使用 java: %s" % java)
    print("  %d 个画像，每个 %d 包，间隔 %.2fs\n" % (len(cases), args.samples, args.interval))

    results = []
    baseline = None
    for profile, delay_ms, jitter_ms in cases:
        v, sent = measure(java, profile, args.samples, args.interval)
        if not v:
            print("  %-10s 一包未收到（%d 发出）" % (profile, sent))
            results.append({"profile": profile, "declared": [delay_ms, delay_ms + jitter_ms],
                            "received": 0, "sent": sent})
            continue
        lo, hi = float(delay_ms), float(delay_ms + jitter_ms)
        inside = sum(1 for x in v if lo - 1 <= x <= hi + 1) / len(v)
        rec = {"profile": profile, "declared": [lo, hi], "sent": sent, "received": len(v),
               "min": v[0], "p50": q(v, 0.5), "p90": q(v, 0.9), "max": v[-1],
               "inside_ratio": inside, "loss": 1 - len(v) / sent}
        results.append(rec)
        if profile == "lan":
            baseline = rec
        print("  %-10s 声明 [%.0f, %.0f]  实测 min=%.0f p50=%.0f p90=%.0f max=%.0f"
              % (profile, lo, hi, rec["min"], rec["p50"], rec["p90"], rec["max"]))
        print("  %-10s 落在声明区间内 %.0f%%   丢包 %.2f%%" % ("", inside * 100, rec["loss"] * 100))

    print("\n  --- 判据 ---")
    rc = 0
    if baseline is None:
        print("  未跑 lan 对照，无法判断测量装置本底")
    elif baseline["p50"] > 5:
        print("  [FAIL] 对照组 lan 的 p50=%.0fms 偏大，测量装置本身有问题，"
              "其余数字不作数" % baseline["p50"])
        rc = 1
    else:
        print("  [PASS] 对照组 lan p50=%.0fms（本底），测量装置可信" % baseline["p50"])
        worst = max((r for r in results if "inside_ratio" in r and r["profile"] != "lan"),
                    key=lambda r: 1 - r["inside_ratio"], default=None)
        if worst:
            print("  偏差最大：%s —— 声明 [%.0f, %.0f]，p50 实测 %.0f（%.0f%% 样本在区间内）"
                  % (worst["profile"], worst["declared"][0], worst["declared"][1],
                     worst["p50"], worst["inside_ratio"] * 100))
            print("  注：这是本机线程唤醒粒度的产物，Linux（CI）上粒度更小，结果会不同 ——")
            print("      结论的适用范围是'本机实测'，别当成画像的固有属性。")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"results": results, "baseline_p50": baseline and baseline["p50"]},
                      f, ensure_ascii=False, indent=2)
        print("  已写入 %s" % args.json)
    return rc


if __name__ == "__main__":
    sys.exit(main())