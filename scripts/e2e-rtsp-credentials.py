#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RTSP URL 凭据编码的线上验证：设备实际收到什么密码。

为什么需要这个
--------------
厂商适配器按公开规范「推导」RTSP URL（`StreamUrlOrigin.VENDOR_STANDARD_FORMAT`，
代码从未向设备确认过）。格式对不对可以拿真 RTSP 服务器验，但**凭据有没有被拼坏**
是另一回事：`URLEncoder.encode` 实现的是 `application/x-www-form-urlencoded`
（HTML 表单）语义，不是 URL 语义，两者只在**空格**上分歧：

    form 语义：空格 → "+"      （接收端把 "+" 解回空格）
    URL  语义：空格 → "%20"    （"+" 是字面加号，不是空格）

用错了，密码 `"p w"` 会变成 `"p+w"` 发到设备上 —— 鉴权失败，而现象只是
"连不上"，排查时会一路怀疑摄像头、网络、账号，而不是那几行拼串。

本脚本的做法：起一个只做 Basic 挑战的极小 RTSP 服务端，读客户端**重试时**发出的
`Authorization` 头并 Base64 解码，于是拿到的是"设备会看到的那个密码"本身 ——
不是推理，也不是用我们自己的解析器自证。

用法
----
    python scripts/e2e-rtsp-credentials.py
    python scripts/e2e-rtsp-credentials.py --ffprobe "/path/to/ffprobe"

需要 ffprobe（用真实 RTSP 客户端发请求）。退出码：0 全过，1 有断言失败，2 前置不满足。
"""

import argparse
import base64
import os
import shutil
import socket
import subprocess
import sys
import threading
import time

HOST = "127.0.0.1"
PORT = 8559

ok = 0
fail = 0


def check(label, cond, detail=""):
    global ok, fail
    if cond:
        ok += 1
        print("  [PASS] %s %s" % (label, detail))
    else:
        fail += 1
        print("  [FAIL] %s %s" % (label, detail))
    return bool(cond)


class ChallengeServer(threading.Thread):
    """只做 Basic 挑战并把收到的凭据记下来的极小 RTSP 服务端。

    同连接内重试 —— 与真实 RTSP 鉴权一致（先 401，客户端带 Authorization 重发）。
    """

    daemon = True

    def __init__(self):
        super().__init__()
        self.raw_auth = None
        self.credential = None       # Base64 解出来的 user:pass
        self.requests = 0
        self.ready = False

    def run(self):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((HOST, PORT))
        s.listen(4)
        s.settimeout(20)
        self.ready = True
        end = time.time() + 18
        while time.time() < end and self.credential is None:
            try:
                conn, _ = s.accept()
            except socket.timeout:
                break
            conn.settimeout(4)
            try:
                while self.credential is None:
                    buf = b""
                    while b"\r\n\r\n" not in buf:
                        chunk = conn.recv(4096)
                        if not chunk:
                            break
                        buf += chunk
                    if not buf:
                        break
                    text = buf.decode("latin-1", "replace")
                    cseq = "1"
                    auth = ""
                    for line in text.split("\r\n"):
                        low = line.lower()
                        if low.startswith("cseq:"):
                            cseq = line.split(":", 1)[1].strip()
                        elif low.startswith("authorization:"):
                            auth = line.split(":", 1)[1].strip()
                    self.requests += 1
                    if auth:
                        self.raw_auth = auth
                        if auth.lower().startswith("basic "):
                            try:
                                self.credential = base64.b64decode(auth[6:]).decode(
                                    "utf-8", "replace")
                            except Exception as e:
                                self.credential = "<base64 解码失败: %s>" % e
                        conn.sendall(("RTSP/1.0 404 Not Found\r\nCSeq: %s\r\n\r\n"
                                      % cseq).encode())
                    else:
                        conn.sendall(("RTSP/1.0 401 Unauthorized\r\nCSeq: %s\r\n"
                                      'WWW-Authenticate: Basic realm="cam"\r\n\r\n'
                                      % cseq).encode())
            except (socket.timeout, ConnectionResetError, BrokenPipeError):
                pass
            finally:
                try:
                    conn.close()
                except OSError:
                    pass
        s.close()


def probe(ffprobe, url):
    srv = ChallengeServer()
    srv.start()
    while not srv.ready:
        time.sleep(0.05)
    try:
        subprocess.run([ffprobe, "-hide_banner", "-v", "quiet", "-rtsp_transport", "tcp",
                        "-timeout", "3000000", "-i", url],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
    except subprocess.TimeoutExpired:
        pass
    srv.join(timeout=20)
    return srv


# 用例矩阵：URL 里的凭据编码 -> 设备应当看到的 user:pass
CASES = [
    # （说明，URL 模板，期望设备看到）
    ("普通凭据", "rtsp://admin:Passw0rd@{h}:{p}/Streaming/Channels/1",
     "admin:Passw0rd"),
    ("密码含空格：URL 语义应编为 %20", "rtsp://admin:p%20w@{h}:{p}/Streaming/Channels/1",
     "admin:p w"),
    ("密码含空格：form 语义的 + 会原样送达（错误编码）",
     "rtsp://admin:p+w@{h}:{p}/Streaming/Channels/1", "admin:p+w"),
    ("结构性字符 @ / : ? # 经编码后不影响解析",
     "rtsp://a%40b:p%40ss%3Aw%2Frd%3Fx%23y@{h}:{p}/Streaming/Channels/1",
     "a@b:p@ss:w/rd?x#y"),
    ("用户名含空格", "rtsp://a%20b:pw@{h}:{p}/Streaming/Channels/1", "a b:pw"),
]


def main():
    ap = argparse.ArgumentParser(description="RTSP URL 凭据编码的线上验证")
    ap.add_argument("--ffprobe", default="",
                    help="ffprobe 路径；不给则从 PATH 与常见位置找")
    args = ap.parse_args()

    ffprobe = args.ffprobe or shutil.which("ffprobe")
    if not ffprobe:
        for cand in (
            r"C:\Users\winge\AppData\Local\Programs\icat\resources\bin\ffmpeg\ffprobe.exe",
            "/usr/bin/ffprobe", "/usr/local/bin/ffprobe",
        ):
            if os.path.exists(cand):
                ffprobe = cand
                break
    if not ffprobe:
        print("  找不到 ffprobe。用 --ffprobe 指定，或装一个（本项目 CI 不跑本脚本，"
              "它需要真实 RTSP 客户端）")
        return 2
    print("  使用 ffprobe: %s\n" % ffprobe)

    for label, tmpl, expect in CASES:
        url = tmpl.format(h=HOST, p=PORT)
        srv = probe(ffprobe, url)
        print("  %s" % label)
        print("     输入 URL      : %s" % url)
        print("     Authorization : %s" % (srv.raw_auth or "(未收到)"))
        print("     设备看到的凭据 : %r   期望 %r" % (srv.credential, expect))
        check("凭据按 URL 语义送达", srv.credential == expect,
              "实际 %r" % (srv.credential,))
        print()

    # 关键判定：两种编码必须**可区分**。若两者结果相同，说明本脚本没有鉴别力，
    # 那前面的 PASS 也不能作数。
    print("  --- 鉴别力自检 ---")
    a = probe(ffprobe, "rtsp://admin:p%%20w@%s:%d/x" % (HOST, PORT)).credential
    b = probe(ffprobe, "rtsp://admin:p+w@%s:%d/x" % (HOST, PORT)).credential
    check("两种编码的结果必须不同（否则脚本无鉴别力）", a != b,
          "%r vs %r" % (a, b))

    print("\n================ 结论 ================")
    print("  PASS=%d  FAIL=%d" % (ok, fail))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())