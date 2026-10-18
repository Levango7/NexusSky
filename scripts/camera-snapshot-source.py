#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本机 UVC 摄像头 -> 真实 HTTP JPEG 快照源（MJPEG 帧源的实机验证端）。

为什么需要这个工具
------------------
`/api/v1/surveillance/devices/{id}/mjpeg` 需要一个**真能连上**的 HTTP 帧源。
本机没有网络摄像头，而这个平台也没有 RTSP 解码器（不引入 ffmpeg / javacv），
所以"把安防视频变成浏览器能看"这条路在本机唯一的真实素材就是 UVC 摄像头。

本脚本起一个极小的 HTTP 服务：`GET /snapshot` 返回**当前这一帧**编码出的
JPEG。它模拟的是真实网络摄像头普遍具备的 HTTP 快照端点（海康
`/ISAPI/Streaming/channels/1/picture`、大华 `/cgi-bin/snapshot.jpg`、ONVIF
`GetSnapshotUri` 背后的 HTTP 取图），而不是自造一个"只有本平台认得"的协议 ——
后者会把"帧源可用"这件事验证成了自说自话。

它**不能**替代厂商适配器：UVC 设备不说 ISAPI/DHSDK/ONVIF，`registerDevice`
也不认它。它只提供一个真实的、可被后端真连上的帧源。

用法
----
    python scripts/camera-snapshot-source.py --port 8099
    # 另开一个终端：
    #   curl -s -o frame.jpg http://127.0.0.1:8099/snapshot

    python scripts/camera-snapshot-source.py --serve-seconds 60 --jpeg-quality 85

每请求一次抓一帧（不缓存旧帧），否则后端轮询取到的会是同一张静止画面，
"能播"就退化成了"能显示一张图"，验证不到实时性。
"""

import argparse
import io
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2


def open_camera(index, width, height):
    cap = cv2.VideoCapture(index)
    if not cap.isOpened():
        raise SystemExit("摄像头 %d 打不开（设备不存在或被占用）" % index)
    if width > 0:
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
    if height > 0:
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    return cap


class SnapshotServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def make_handler(cap, quality, lock, stats):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            # 静音：默认实现往 stderr 写，后端每秒轮询 5 次会淹掉终端
            pass

        def _send(self, code, ctype, body):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            # 后端的 HttpClient 会保持连接；不给明确长度它会等
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                # 客户端读一半就走了（后端探帧失败/超时），不是错误
                pass

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == "/snapshot":
                with lock:
                    ok, frame = cap.read()
                if not ok or frame is None:
                    stats["failed"] += 1
                    self._send(503, "text/plain; charset=utf-8", b"camera read failed")
                    return
                ok, buf = cv2.imencode(".jpg", frame,
                                       [int(cv2.IMWRITE_JPEG_QUALITY), quality])
                if not ok:
                    stats["failed"] += 1
                    self._send(500, "text/plain; charset=utf-8", b"jpeg encode failed")
                    return
                data = buf.tobytes()
                stats["served"] += 1
                self._send(200, "image/jpeg", data)
                return
            if path == "/stats":
                body = ('{"served":%d,"failed":%d,"frames":%d}'
                        % (stats["served"], stats["failed"], stats["frames"])).encode()
                self._send(200, "application/json; charset=utf-8", body)
                return
            if path == "/healthz":
                self._send(200, "text/plain; charset=utf-8", b"ok")
                return
            self._send(404, "text/plain; charset=utf-8", b"not found")

    return Handler


def main():
    ap = argparse.ArgumentParser(description="UVC 摄像头 -> HTTP JPEG 快照源")
    ap.add_argument("--camera", type=int, default=0, help="摄像头序号")
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--width", type=int, default=640)
    ap.add_argument("--height", type=int, default=360)
    ap.add_argument("--jpeg-quality", type=int, default=80)
    ap.add_argument("--serve-seconds", type=float, default=0,
                    help=">0 则跑够这么多秒后自动退出（0=一直跑）")
    args = ap.parse_args()

    cap = open_camera(args.camera, args.width, args.height)
    lock = threading.Lock()
    stats = {"served": 0, "failed": 0, "frames": 0}

    # 先抓一帧确认真的能出图，别起完服务才发现摄像头不产出
    with lock:
        ok, frame = cap.read()
    if not ok or frame is None:
        cap.release()
        raise SystemExit("摄像头 %d 打不开画面（已打开但读不出帧）" % args.camera)
    ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), args.jpeg_quality])
    if not ok:
        cap.release()
        raise SystemExit("JPEG 编码失败（本机 cv2 缺 JPEG 编码器？）")

    srv = SnapshotServer((args.host, args.port), make_handler(cap, args.jpeg_quality, lock, stats))
    url = "http://%s:%d/snapshot" % (args.host, args.port)
    sys.stdout.write("snapshotUrl = %s\n" % url)
    sys.stdout.write("首帧自检：%dx%d -> JPEG %d bytes\n"
                     % (frame.shape[1], frame.shape[0], len(buf)))
    sys.stdout.write("stats: %s/stats\n" % ("http://%s:%d" % (args.host, args.port)))
    sys.stdout.flush()

    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        if args.serve_seconds > 0:
            time.sleep(args.serve_seconds)
        else:
            while True:
                time.sleep(3600)
    except KeyboardInterrupt:
        pass
    finally:
        srv.shutdown()
        cap.release()
        sys.stdout.write("\n共供帧 %d，失败 %d\n" % (stats["served"], stats["failed"]))


if __name__ == "__main__":
    main()