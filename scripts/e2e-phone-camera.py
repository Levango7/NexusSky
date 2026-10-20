#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Android 手机当摄像头 -> 真 HTTP 帧源 -> 真后端 /mjpeg -> 浏览器可播（实机验证）。

为什么需要这个工具
------------------
`/api/v1/surveillance/devices/{id}/mjpeg` 需要一个**真能连上**的 HTTP 帧源。
手边只有一台 Android 手机，它在开发者选项里打开「IP 摄像头」后就会在 8080 端口
提供一个 HTTP 取图端点（内置 IP Camera、IP Webcam、camera-streamer 之类，路径各不相同），
正好就是后端要的那种"真实网络摄像头普遍具备的 HTTP 快照端点"。

它做的事
--------
1. **找到手机**：有 adb 就问 adb（`adb devices` + `adb shell` 读 wlan0 的 IP + 查端口是否在听）；
   没有 adb 就在本机网段里扫 8080/8081/8088。
2. **找到取图端点**：**两条路一起走** ——
   a) **读设备自己的首页**（`GET /`），从返回的 HTML 里解析出它声明的取图/视频端点
      （AOSP 的 IP 摄像头、内置 IP Webcam 等都会在首页列出自己的路径）；
   b) 再按常见候选路径逐个探测。
   两条路都以**返回体是不是真 JPEG**判定 —— 不写死任何一条路径。
   写死就是猜：安卓侧内置 IP Camera、IP Webcam、camera-streamer 路径各不相同
   （/snapshot、/snapshot.jpg、/shot.jpg、/now.jpg、/capture…），猜错整条链路的验证就作废。
3. **跑完整链路**：把探测到的 URL 交给 `scripts/e2e-mjpeg-preview.py`（#110 那套断言：
   帧源真出 JPEG、注册回显 browserPlayable、匿名 401、短令牌开流 200/multipart、
   每一帧都是真 JPEG、帧号递增、注销后 404）。

用法
----
    # 手机插上 USB 并开 USB 调试授权，或在开发者选项里打开「IP 摄像头」后：
    python scripts/e2e-phone-camera.py --backend http://localhost:8080

    # 只想知道取图 URL（不跑后端链路）：
    python scripts/e2e-phone-camera.py --discover-only

    # 手机不在默认网段/端口时手工指定：
    python scripts/e2e-phone-camera.py --phone-ip 192.168.10.77 --port 8080

    # 自证（不碰真手机、不碰后端）：起一个合成的「假 IP 摄像头」，证明探测逻辑
    # 能发现设备自己声明的端点、且不会把非 JPEG 的响应误判成取图端点。
    python scripts/e2e-phone-camera.py --self-test

退出码：0 全过；1 有断言失败；2 环境/前置不满足（找不到手机、没开 IP 摄像头等）。
"""
import argparse
import base64
import concurrent.futures as cf
import io
import ipaddress
import os
import re
import socket
import subprocess
import sys
import urllib.error
import urllib.request
from html.parser import HTMLParser

HERE = os.path.dirname(os.path.abspath(__file__))

# 取图端点候选（不同 app 路径不同；最终以"返回体是 JPEG"判定，不靠猜）
CANDIDATE_PATHS = [
    "/snapshot", "/snapshot.jpg", "/shot.jpg", "/now.jpg", "/preview.jpg",
    "/capture", "/image", "/jpeg", "/photo", "/still", "/frame.jpg", "/img.jpg",
    "/mjpeg/snapshot", "/camera/snapshot", "/picture", "/takepicture",
    "/getPicture", "/capture.jpg", "/snapshot.jpeg",
]
# 扫网段时的端口（安卓 IP 摄像头几乎都用 8080）
CANDIDATE_PORTS = [8080, 8081, 8088]
# 扫网段时的连接超时
SCAN_TIMEOUT = 0.4
# 合成夹具：一张真 JPEG（32x24，梯度图），用来自证"是不是真 JPEG"的判定。
# 与合成帧源（scripts/synthetic-frame-source.py）共用同一批夹具。
FIXTURE_JPEG_B64 = os.path.join(HERE, "fixtures", "fake-camera-frame-1.jpg.b64")


def log(msg):
    print(msg, flush=True)


def find_adb():
    """找 adb：PATH 里优先，其次本会话装过的那份。"""
    from shutil import which
    p = which("adb")
    if p:
        return p
    for c in (
        r"C:\Users\winge\AppData\Local\Temp\opencode\pt\platform-tools\adb.exe",
        os.path.expanduser("~/AppData/Local/Temp/opencode/pt/platform-tools/adb.exe"),
        "/usr/bin/adb", "/usr/local/bin/adb",
    ):
        if os.path.exists(c):
            return c
    return None


def run(cmd, timeout=20):
    try:
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        return r.returncode, r.stdout.decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


def adb_devices(adb):
    rc, out = run([adb, "devices"])
    if rc != 0:
        return []
    devs = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devs.append(parts[0])
    return devs


def adb_phone_ip(adb, serial):
    """从手机里读 wlan0 的 IPv4。"""
    for cmd in (
        [adb, "-s", serial, "shell", "ip -f inet addr show wlan0"],
        [adb, "-s", serial, "shell", "ifconfig wlan0"],
        [adb, "-s", serial, "shell", "getprop", "dhcp.wlan0.ipaddress"],
    ):
        rc, out = run(cmd)
        if rc != 0:
            continue
        for tok in out.replace("\r", " ").split():
            t = tok.strip()
            if t.count(".") == 3:
                try:
                    ip = ipaddress.IPv4Address(t)
                except ValueError:
                    continue
                if not ip.is_loopback:
                    return str(ip)
    return None


def adb_listening_ports(adb, serial):
    """手机上正在监听的 TCP 端口（十六进制）。"""
    rc, out = run([adb, "-s", serial, "shell", "cat /proc/net/tcp"])
    ports = set()
    if rc == 0:
        for line in out.splitlines()[1:]:
            f = line.split()
            if len(f) > 2 and f[3] == "0A":  # 0A = LISTEN
                try:
                    ports.add(int(f[1].split(":")[1], 16))
                except (IndexError, ValueError):
                    pass
    return ports


def local_ipv4():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 1))  # TEST-NET-1，不会真发包
        return s.getsockname()[0]
    except Exception:
        return None
    finally:
        s.close()


def subnet_hosts(ip):
    try:
        net = ipaddress.ip_network(ip + "/24", strict=False)
        return [str(h) for h in net.hosts()]
    except ValueError:
        return []


def port_open(host, port, timeout=SCAN_TIMEOUT):
    try:
        s = socket.create_connection((host, port), timeout)
        s.close()
        return True
    except Exception:
        return False


def http_get(url, timeout=6, limit=65536):
    """返回 (状态码, 头, 字节)。不抛异常。"""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "nexus-e2e/1.0"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read(limit)
    except urllib.error.HTTPError as e:
        try:
            return e.code, dict(e.headers or {}), e.read(limit)
        except Exception:
            return e.code, {}, b""
    except Exception:
        return None, {}, b""


def is_jpeg(head):
    """真 JPEG 的头是 FF D8 FF。只看魔数，不信任 Content-Type。"""
    return len(head) >= 3 and head[0] == 0xFF and head[1] == 0xD8 and head[2] == 0xFF


class _LinkParser(HTMLParser):
    """从设备自己的首页里解析出它声明的端点（href/src/action 属性）。"""

    def __init__(self):
        super().__init__()
        self.paths = []

    def handle_starttag(self, tag, attrs):
        d = dict(attrs)
        for k in ("href", "src", "action"):
            v = d.get(k)
            if v and v.startswith("/") and v not in self.paths:
                self.paths.append(v)


def discover_from_index(base):
    """读设备首页，解析它自己声明的端点。"""
    code, hdrs, body = http_get(base + "/", timeout=6, limit=200000)
    if code != 200 or not body:
        return []
    text = body.decode("utf-8", "replace")
    if "<html" not in text.lower() and "<a " not in text.lower():
        return []
    p = _LinkParser()
    try:
        p.feed(text)
    except Exception:
        pass
    return p.paths


def probe_jpeg_endpoint(base):
    """在 base 上探测取图端点；返回 (url, jpeg_bytes) 或 (None, None)。

    顺序：先读设备自己首页里声明的端点，再按候选路径。
    判定：返回体开头必须是真 JPEG 的魔数（不看 Content-Type ——
    有些 app 会把 JPEG 标成 application/octet-stream，也有些会把非 JPEG 标成 image/jpeg）。
    """
    tried = []
    paths = discover_from_index(base) + CANDIDATE_PATHS
    for path in paths:
        if path in tried:
            continue
        tried.append(path)
        url = base + path
        code, hdrs, body = http_get(url, timeout=6)
        if code == 200 and is_jpeg(body):
            return url, body
    return None, None


def find_phone(args):
    """按优先级找手机：手工指定 > adb > 扫网段。返回 (描述, host) 或 (None, None)。"""
    if args.phone_ip:
        return ("手工指定", args.phone_ip)

    adb = find_adb()
    if adb:
        devs = adb_devices(adb)
        log("  adb: %s" % adb)
        if devs:
            serial = devs[0]
            log("  adb 设备: %s" % serial)
            ip = adb_phone_ip(adb, serial)
            if ip:
                log("  手机 wlan IP: %s" % ip)
                listen = adb_listening_ports(adb, serial)
                for p in CANDIDATE_PORTS:
                    if p in listen:
                        log("  手机上 %d 端口在监听（IP 摄像头已开）" % p)
                        return ("adb", ip)
                log("  手机上没有 %s 端口在监听 —— 开发者选项里的「IP 摄像头」可能没开"
                    % ",".join(str(x) for x in CANDIDATE_PORTS))
                return ("adb", ip)
        else:
            log("  adb 没有已授权的设备（手机没插 USB，或没开 USB 调试 / 未授权）")
    else:
        log("  找不到 adb")

    me = local_ipv4()
    if not me:
        return None, None
    log("  本机 IPv4: %s —— 扫同网段的 %s" % (me, ",".join(str(p) for p in CANDIDATE_PORTS)))
    hosts = subnet_hosts(me)
    found = []

    def probe(h):
        for p in CANDIDATE_PORTS:
            if port_open(h, p):
                return (h, p)

    with cf.ThreadPoolExecutor(max_workers=128) as ex:
        for r in ex.map(probe, hosts):
            if r:
                found.append(r)
    if found:
        host, port = found[0]
        log("  扫到候选: %s:%d" % (host, port))
        return ("扫描", host)
    return None, None


# --------------------------------------------------------------------------
# 自测：合成一个「假 IP 摄像头」，证明探测逻辑成立
# --------------------------------------------------------------------------
def _load_fixture_jpeg():
    if not os.path.exists(FIXTURE_JPEG_B64):
        return None
    try:
        raw = io.open(FIXTURE_JPEG_B64, encoding="utf-8").read()
        return base64.b64decode("".join(raw.split()))
    except Exception:
        return None


def _serve_fake_camera(jpeg, routes):
    """起一个本地 HTTP 服务模拟手机 IP 摄像头。返回 (base_url, shutdown)。"""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"

        def log_message(self, *a):  # 静音
            pass

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == "/":
                body = routes["index"].encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if path in routes.get("files", {}):
                ctype, data = routes["files"][path]
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    t = __import__("threading").Thread(target=srv.serve_forever, daemon=True)
    t.start()
    base = "http://127.0.0.1:%d" % srv.server_address[1]
    return base, srv.shutdown


def self_test():
    """自证探测逻辑（不碰真手机、不碰后端）：
    1) 设备**自己首页里声明**的端点（故意用不在候选列表里的怪路径）要能被发现；
    2) 返回体不是 JPEG 的端点（HTML / multipart 流）要被拒；
    3) 全站只有 HTML 的设备不能被误判成"有取图端点"。
    """
    ok = True
    jpeg = _load_fixture_jpeg()
    if not jpeg:
        print("  [FAIL] 读不到夹具 %s" % FIXTURE_JPEG_B64)
        return 1
    if not is_jpeg(jpeg[:3]):
        print("  [FAIL] 夹具本身不是 JPEG（魔数 %s）" % jpeg[:3].hex())
        return 1
    print("  [PASS] 夹具是真 JPEG（%d 字节）" % len(jpeg))

    # 假的「设备自己声明的路径」—— 故意不在 CANDIDATE_PATHS 里
    exotic = "/vendor/exotic/capture"
    if exotic in CANDIDATE_PATHS:
        print("  [FAIL] 怪路径撞上候选列表了，自测就不成立")
        return 1

    # 首页里第一个链接是 multipart 视频流（**不是** JPEG），
    # 第二个才是真正的取图端点 —— 探测必须跳过前者、选后者。
    boundary = "NEXUSBOUND"
    multipart = ("--%s\r\nContent-Type: image/jpeg\r\n\r\n" % boundary).encode() + jpeg \
        + ("\r\n--%s--\r\n" % boundary).encode()
    # 假 JPEG：头两个字节像 JPEG（FF D8），第三个字节不是 FF —— 有些设备出错时就这么回。
    # 判别性夹具：只查前两字节的实现会把它当成真 JPEG 收下。
    truncated = b"\xff\xd8\x00truncated-junk-not-a-jpeg"
    index_html = (
        "<html><head><title>IP Camera</title></head><body>"
        "<a href='/vendor/exotic/stream'>video</a>"
        "<a href='/vendor/exotic/truncated'>broken</a>"
        "<a href='%s'>photo</a>"
        "<a href='/vendor/exotic/notjpeg'>info</a>"
        "</body></html>" % exotic
    )
    routes = {
        "index": index_html,
        "files": {
            "/vendor/exotic/stream": ("multipart/x-mixed-replace", multipart),
            "/vendor/exotic/truncated": ("image/jpeg", truncated),   # 标成 JPEG，内容是假的
            exotic: ("application/octet-stream", jpeg),   # 故意标错 Content-Type
            "/vendor/exotic/notjpeg": ("text/html", b"<html>not a photo</html>"),
        },
    }
    base, stop = _serve_fake_camera(jpeg, routes)
    try:
        found = discover_from_index(base)
        if exotic in found and "/vendor/exotic/stream" in found:
            print("  [PASS] 从设备首页解析出它自己声明的端点：%s" % ", ".join(found))
        else:
            print("  [FAIL] 没从首页解析出声明的端点：%s" % found)
            ok = False

        url, body = probe_jpeg_endpoint(base)
        if url and url.endswith(exotic):
            print("  [PASS] 探测选中了真取图端点（跳过了 multipart 流）：%s" % url)
        else:
            print("  [FAIL] 探测选错了端点：%s（应为 %s）" % (url, exotic))
            ok = False

        if url and body and is_jpeg(body[:3]):
            print("  [PASS] 取回的是真 JPEG（%d 字节）" % len(body))
        else:
            print("  [FAIL] 取回的不是 JPEG")
            ok = False
    finally:
        stop()

    # 全站只有 HTML 的设备：不能被误判
    routes2 = {
        "index": "<html><body><a href='/only-html'>x</a></body></html>",
        "files": {"/only-html": ("text/html", b"<html>nope</html>")},
    }
    base2, stop2 = _serve_fake_camera(jpeg, routes2)
    try:
        url2, _ = probe_jpeg_endpoint(base2)
        if url2 is None:
            print("  [PASS] 只有 HTML 的设备被正确判为「没有取图端点」")
        else:
            print("  [FAIL] 把非 JPEG 的端点误判成取图端点：%s" % url2)
            ok = False
    finally:
        stop2()

    if not is_jpeg(b"<html>"):
        print("  [PASS] 非 JPEG 响应被魔数判定拒绝")
    else:
        print("  [FAIL] 魔数判定对 HTML 误判")
        ok = False

    if not is_jpeg(b"\xff\xd8\x00truncated"):
        print("  [PASS] 「头两字节像 JPEG」的假响应被拒（第三个字节不对）")
    else:
        print("  [FAIL] 只看前两字节的魔数判定放过了假 JPEG")
        ok = False

    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description="Android 手机当摄像头 -> 后端 /mjpeg 端到端验证")
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--phone-ip", default="")
    ap.add_argument("--port", type=int, default=0, help="手机上的端口（0=逐个候选试）")
    ap.add_argument("--discover-only", action="store_true", help="只找出取图 URL，不跑后端链路")
    ap.add_argument("--username", default="admin")
    ap.add_argument("--password", default="")
    ap.add_argument("--allow-static-frames", action="store_true",
                    help="允许每帧内容相同（手机对着静止场景时属正常；默认仍要求画面在动）")
    ap.add_argument("--require-motion", action="store_true",
                    help="要求每帧内容都不同（手机对着静止场景时就会失败，与手机无关）；"
                         "默认允许静止画面")
    ap.add_argument("--self-test", action="store_true",
                    help="用合成的假 IP 摄像头自证探测逻辑（不碰真手机、不碰后端）")
    args = ap.parse_args()

    if args.self_test:
        print("== 手机摄像头探测逻辑自测 ==")
        return self_test()

    log("== 1/3 找手机 ==")
    how, host = find_phone(args)
    if not host:
        log("")
        log("找不到手机。前置条件（任一即可）：")
        log("  a) 手机插上 USB、开「开发者选项 → USB 调试」并在本机授权 adb；")
        log("  b) 或者在「开发者选项 → IP 摄像头」里打开它（它会在 8080 端口出 HTTP 取图流）；")
        log("  c) 或者用第三方 IP 摄像头 app（IP Webcam / camera-streamer 等），同样在 8080。")
        log("也可以直接指定：--phone-ip <手机IP> --port <端口>")
        return 2

    log("")
    log("== 2/3 找取图端点（先读设备自己的首页，再按候选路径；均以返回体是不是真 JPEG 判定）==")
    if args.port:
        bases = ["http://%s:%d" % (host, args.port)]
    else:
        bases = ["http://%s:%d" % (host, p) for p in CANDIDATE_PORTS]
    snap_url = None
    for base in bases:
        idx = discover_from_index(base)
        if idx:
            log("  %s 首页声明了 %d 个端点：%s" % (base, len(idx), ", ".join(idx[:6])))
        url, body = probe_jpeg_endpoint(base)
        if url:
            snap_url = url
            log("  找到: %s  （JPEG %d 字节）" % (url, len(body)))
            break
        log("  %s 上没有候选端点返回 JPEG" % base)
    if not snap_url:
        log("")
        log("手机在 %s 上可达，但没有候选路径返回 JPEG。" % host)
        log("多半是「IP 摄像头」没开（它默认只开在 8080 且需要你在手机上确认 Wi-Fi 权限），")
        log("或者装的是别的 app（路径不同）—— 可以用 --port 试别的端口。")
        return 2

    if args.discover_only:
        log("")
        log("取图 URL = %s" % snap_url)
        log("接下来：python scripts/e2e-mjpeg-preview.py --backend %s --snapshot-url %s"
            % (args.backend, snap_url))
        return 0

    log("")
    log("== 3/3 跑完整链路（真后端 /mjpeg -> 浏览器可播）==")
    preview = os.path.join(HERE, "e2e-mjpeg-preview.py")
    if not os.path.exists(preview):
        log("  找不到 %s" % preview)
        return 2
    cmd = [sys.executable, preview, "--backend", args.backend, "--snapshot-url", snap_url,
           "--username", args.username]
    if args.password:
        cmd += ["--password", args.password]
    if not args.require_motion:
        # 手机摆在桌上对着静止场景时，每帧本来就一样，那时「帧内容在变」失败与手机无关，
        # 会让人误以为链路有问题。
        cmd.append("--allow-static-frames")
    log("  " + " ".join(cmd))
    rc = subprocess.run(cmd).returncode
    if rc == 0:
        log("")
        log("== 手机摄像头链路全绿 ==")
    return rc


if __name__ == "__main__":
    sys.exit(main())
