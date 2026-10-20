#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""合成 HTTP 帧源：轮换返回仓库里的真 JPEG（CI 里没有摄像头时的替身）。

为什么需要它
------------
`/api/v1/surveillance/devices/{id}/mjpeg` 的整条链路（注册设备 -> 短令牌 -> 拉 HTTP
帧源 -> 吐 multipart MJPEG -> 注销后 404）在 CI 里**从来没有被验证过** ——
因为它需要一台真摄像头，而 GitHub runner 没有。

于是有两条路：要么这条链路永远靠人肉验，要么给它一个**合成的帧源**。
本脚本就是那个合成帧源：它吐的是**真 JPEG**（仓库里 3 张夹具，轮换），
不是随手拼的字节 —— 所以「每一帧都是真 JPEG」「帧内容在变」「帧号递增」
这几条断言在 CI 里**仍然有意义**，而不是在测一个假帧源。

它不替代真硬件验证：真摄像头跑通的证据见 docs/contributing-guide.md 的 e2e 台账。
它补的是「CI 每次 PR 都守住这条链路」。

用法
----
    python scripts/synthetic-frame-source.py --port 18077
    python scripts/synthetic-frame-source.py --self-test

退出码：0 通过；1 断言失败。
"""
import argparse
import base64
import glob
import io
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE_DIR = os.path.join(HERE, "fixtures")
# 帧源约定的取图路径（与 camera-snapshot-source.py 一致）
SNAPSHOT_PATH = "/snapshot"


def load_frames(fixture_dir=FIXTURE_DIR):
    """读出所有真 JPEG 夹具（按文件名排序）。返回 (字节列表, 描述列表)。"""
    paths = sorted(glob.glob(os.path.join(fixture_dir, "fake-camera-frame-*.jpg.b64")))
    frames, descs = [], []
    for p in paths:
        raw = io.open(p, encoding="utf-8").read()
        data = base64.b64decode("".join(raw.split()))
        if data[:3] != b"\xff\xd8\xff":
            raise ValueError("夹具不是 JPEG：%s" % p)
        frames.append(data)
        descs.append("%s(%d 字节)" % (os.path.basename(p), len(data)))
    return frames, descs


def make_handler(frames, stats, lock):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"

        def log_message(self, *a):
            pass

        def _send(self, code, ctype, body):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == SNAPSHOT_PATH:
                with lock:
                    idx = stats["seq"] % len(frames)
                    stats["seq"] += 1
                    stats["served"] += 1
                self._send(200, "image/jpeg", frames[idx])
                return
            if path == "/healthz":
                self._send(200, "text/plain; charset=utf-8", b"ok")
                return
            if path == "/stats":
                with lock:
                    body = json.dumps({"served": stats["served"], "seq": stats["seq"],
                                       "frames": len(frames)}).encode()
                self._send(200, "application/json; charset=utf-8", body)
                return
            self._send(404, "text/plain; charset=utf-8", b"not found")

    return Handler


def serve(port, host, frames, stats, lock):
    srv = ThreadingHTTPServer((host, port), make_handler(frames, stats, lock))
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv


def self_test():
    """自证：帧源吐的是真 JPEG、并且会轮换（否则「帧内容在变」那条断言在 CI 里就没意义）。"""
    import urllib.request

    ok = True
    frames, descs = load_frames()
    if len(frames) < 2:
        print("  [FAIL] 夹具至少要 2 张（轮换才有意义），实际 %d 张" % len(frames))
        return 1
    print("  [PASS] 夹具 %d 张：%s" % (len(frames), ", ".join(descs)))

    stats = {"served": 0, "seq": 0}
    lock = threading.Lock()
    srv = serve(0, "127.0.0.1", frames, stats, lock)
    base = "http://127.0.0.1:%d" % srv.server_address[1]
    try:
        with urllib.request.urlopen(base + "/healthz", timeout=5) as r:
            body = r.read()
        if r.status == 200 and b"ok" in body:
            print("  [PASS] /healthz 正常")
        else:
            print("  [FAIL] /healthz 不正常")
            ok = False

        got = []
        for _ in range(len(frames) + 1):
            with urllib.request.urlopen(base + SNAPSHOT_PATH, timeout=5) as r:
                data = r.read()
                ctype = r.headers.get("Content-Type", "")
            if r.status != 200:
                print("  [FAIL] /snapshot 状态 %d" % r.status)
                ok = False
                break
            if data[:3] != b"\xff\xd8\xff":
                print("  [FAIL] /snapshot 不是 JPEG（%s）" % data[:3].hex())
                ok = False
                break
            got.append(data)
        if ok:
            print("  [PASS] /snapshot 每次都返回真 JPEG（SOI=ffd8ff）")
            distinct = len(set(got))
            if distinct == len(frames):
                print("  [PASS] 轮换生效：连续 %d 次拿到 %d 张不同的帧" % (len(got), distinct))
            else:
                print("  [FAIL] 轮换没生效：%d 次请求只拿到 %d 张不同的帧" % (len(got), distinct))
                ok = False
            if got[-1] == got[0]:
                print("  [PASS] 轮完一轮回到第一张（循环而不是耗尽）")
            else:
                print("  [FAIL] 轮完一轮没有回到第一张")
                ok = False
            if ctype == "image/jpeg":
                print("  [PASS] Content-Type 是 image/jpeg")
            else:
                print("  [NOTE] Content-Type 是 %r（后端按字节判定，不影响）" % ctype)
        with urllib.request.urlopen(base + "/stats", timeout=5) as r:
            st = json.loads(r.read().decode())
        if st.get("served") == len(got) and st.get("frames") == len(frames):
            print("  [PASS] /stats 计数对得上：%s" % st)
        else:
            print("  [FAIL] /stats 计数对不上：%s" % st)
            ok = False
    finally:
        srv.shutdown()
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description="合成 HTTP 帧源（CI 用，轮换真 JPEG 夹具）")
    ap.add_argument("--port", type=int, default=18077)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--serve-seconds", type=int, default=0, help=">0 则跑够这么久后自动退出")
    ap.add_argument("--self-test", action="store_true",
                    help="自证：吐的是真 JPEG 且会轮换（不依赖任何外部进程）")
    args = ap.parse_args()

    if args.self_test:
        print("== 合成帧源自测 ==")
        return self_test()

    frames, descs = load_frames()
    if not frames:
        print("找不到夹具（%s/fake-camera-frame-*.jpg.b64）" % FIXTURE_DIR)
        return 2
    stats = {"served": 0, "seq": 0}
    lock = threading.Lock()
    srv = serve(args.port, args.host, frames, stats, lock)
    print("合成帧源已启动 http://%s:%d%s  （%d 张夹具轮换）" %
          (args.host, args.port, SNAPSHOT_PATH, len(frames)))
    for d in descs:
        print("  - %s" % d)
    try:
        if args.serve_seconds > 0:
            end = time.time() + args.serve_seconds
            while time.time() < end:
                time.sleep(0.5)
        else:
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        srv.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
