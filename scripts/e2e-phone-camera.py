#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Android 手机当摄像头 -> 真 HTTP 帧源 -> 真后端 /mjpeg -> 浏览器可播（实机验证）。

为什么需要这个工具
------------------
`/api/v1/surveillance/devices/{id}/mjpeg` 需要一个**真能连上**的 HTTP 帧源。
本机手边只有一台 Android 手机，它在开发者选项里打开「IP 摄像头」后就会在 8080 端口
提供一个 HTTP 取图端点（内置 IP Camera、IP Webcam、camera-streamer 之类的 app 路径各不相同），
正好就是后端要的那种"真实网络摄像头普遍具备的 HTTP 快照端点"。

它做的事
--------
1. **找到手机**：有 adb 就问 adb（`adb devices` + `adb shell` 读 wlan0 的 IP + 查 8080 是否在听）；
   没有 adb 就在本机网段里扫 8080。
2. **找到取图端点**：对候选路径逐个探测，**以返回体是不是真 JPEG（SOI=ffd8ff）为准**，
   不写死任何一条路径 —— 不同 app 的路径差别很大，写死就是"猜"。
3. **跑完整链路**：把探测到的 URL 交给 `scripts/e2e-mjpeg-preview.py`（#110 那套断言：
   帧源真出 JPEG、注册回显 browserPlayable、匿名 401、短令牌开流 200/multipart、
   每一帧都是真 JPEG、帧内容在变、注销后 404）。

用法
----
    # 手机插上 USB 并开 USB 调试授权，或在开发者选项里打开「IP 摄像头」后：
    python scripts/e2e-phone-camera.py --backend http://localhost:8080

    # 只想知道取图 URL（不跑后端链路）：
    python scripts/e2e-phone-camera.py --discover-only

    # 手机不在默认网段/端口时手工指定：
    python scripts/e2e-phone-camera.py --phone-ip 192.168.10.77 --port 8080

退出码：0 全过；1 有断言失败；2 环境/前置不满足（找不到手机、没开 IP 摄像头等）。
"""
import argparse
import concurrent.futures as cf
import ipaddress
import os
import socket
import struct
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# 取图端点候选（不同 app 路径不同；最终以"返回体是 JPEG"判定，不靠猜）
CANDIDATE_PATHS = [
    "/snapshot", "/snapshot.jpg", "/shot.jpg", "/now.jpg", "/preview.jpg",
    "/capture", "/image", "/jpeg", "/photo", "/still", "/frame.jpg", "/img.jpg",
    "/mjpeg/snapshot", "/camera/snapshot",
]

# 扫网段时的端口（安卓 IP 摄像头几乎都用 8080）
CANDIDATE_PORTS = [8080, 8081, 8088]


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
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=timeout)
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


def port_open(host, port, timeout=0.4):
    try:
        s = socket.create_connection((host, port), timeout)
        s.close()
        return True
    except Exception:
        return False


def http_get(url, timeout=6):
    """返回 (状态码, 头, 前若干字节) —— 不抛异常。"""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "nexus-e2e/1.0"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read(4096)
    except urllib.error.HTTPError as e:
        try:
            return e.code, dict(e.headers or {}), e.read(4096)
        except Exception:
            return e.code, {}, b""
    except Exception:
        return None, {}, b""


def is_jpeg(head):
    return len(head) >= 3 and head[0] == 0xFF and head[1] == 0xD8 and head[2] == 0xFF


def probe_jpeg_endpoint(base, timeout=6):
    """在 base 上探测取图端点；返回 (url, jpeg_bytes) 或 (None, None)。"""
    for path in CANDIDATE_PATHS:
        url = base + path
        code, hdrs, body = http_get(url, timeout)
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
                # 端口没在听也先报 IP，探测阶段再判
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


def main():
    ap = argparse.ArgumentParser(description="Android 手机当摄像头 -> 后端 /mjpeg 端到端验证")
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--phone-ip", default="")
    ap.add_argument("--port", type=int, default=0, help="手机上的端口（0=逐个候选试）")
    ap.add_argument("--discover-only", action="store_true", help="只找出取图 URL，不跑后端链路")
    ap.add_argument("--require-motion", action="store_true",
                    help="要求每帧内容都不同（手机对着静止场景时就会失败，"
                         "与手机无关）；默认允许静止画面")
    ap.add_argument("--username", default="admin")
    ap.add_argument("--password", default="")
    args = ap.parse_args()

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
    log("== 2/3 找取图端点（以返回体是不是真 JPEG 判定，不写死路径）==")
    bases = []
    if args.port:
        bases.append("http://%s:%d" % (host, args.port))
    else:
        bases = ["http://%s:%d" % (host, p) for p in CANDIDATE_PORTS]
    snap_url = None
    for base in bases:
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
    if not args.require_motion:
        # 手机摆在桌上对着静止场景时，每帧本来就一样，那时
        # 「帧内容在变」失败与手机无关，会让人误以为链路有问题。
        cmd.append("--allow-static-frames")
    if args.password:
        cmd += ["--password", args.password]
    log("  " + " ".join(cmd))
    rc = subprocess.run(cmd).returncode
    if rc == 0:
        log("")
        log("== 手机摄像头链路全绿 ==")
    return rc


if __name__ == "__main__":
    sys.exit(main())
