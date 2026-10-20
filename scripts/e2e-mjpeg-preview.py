#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MJPEG 浏览器可播出口的端到端实机验证（#110）。

验的是什么
----------
"安防视频看不了"这件事此前是**结构性**的：后端只产 `rtsp://`，浏览器不能原生播，
而平台没有 RTSP 解码器。本脚本验证补上的出口是真的通的：

    真实摄像头 -> 真实 HTTP 快照源 -> 真后端 /mjpeg -> 真 multipart 帧流

断言全部落在**实测字节**上，不用"应该能工作"糊过去：

  S1  帧源真能出 JPEG（SOI=ffd8ff），且两次取帧内容不同（不是缓存的静止画面）
  S2  注册时显式给 snapshotUrl，设备视图回显 browserPlayable=true + 原地址
  S3  匿名访问预览流 -> 401，且响应里一帧都没漏
  S4  换 60s 单次短令牌后开流 -> 200 / multipart / no-store
  S5  收到的每一帧都是真 JPEG，声明长度与实际字节一致，帧号递增
  S6  帧内容在变（同一张图重复发不算"能看"）
  S7  注销后预览明确 404 + JSON，而不是留一个挂着的空格子

为什么 S3 要单独断言
--------------------
`SecurityConfig` 里这个路径走 `?streamToken=` 短令牌（`StreamTokenFilter`），
链上是 `anyRequest().authenticated()`。如果哪天有人图省事把它加进 permitAll，
"放行"就变成了"不鉴权"—— 监控画面公开广播，而 S4-S6 照样全绿。
所以匿名拒绝必须是一条独立断言，不能只验"带令牌能看"。

本脚本**不进 CI**：CI runner 没有摄像头。它是本机手动验证脚本，
与 `camera-motion-source.py` 同类（都依赖真实硬件）。

用法
----
    # 终端 1：起帧源（若已有别的 HTTP 快照端点，可跳过，用 --snapshot-url 指定）
    python scripts/camera-snapshot-source.py --port 8099

    # 终端 2：起后端（prod profile，fail-fast 项见 application-prod.properties）

    # 终端 3：跑验证
    python scripts/e2e-mjpeg-preview.py --backend http://localhost:8080

    # 也可让它自己拉起帧源
    python scripts/e2e-mjpeg-preview.py --spawn-frame-source

退出码：0 全过，1 有断言失败，2 环境/前置不满足（连不上后端等）。
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

ok = 0
fail = 0
notes = []


def step(title):
    print("\n--- %s ---" % title)


def check(label, cond, detail=""):
    global ok, fail
    if cond:
        ok += 1
        print("  [PASS] %s %s" % (label, detail))
    else:
        fail += 1
        print("  [FAIL] %s %s" % (label, detail))
    return bool(cond)


def note(text):
    notes.append(text)
    print("  [NOTE] %s" % text)


def probe_auth_posture(base):
    """探后端的鉴权姿态：匿名访问受保护端点会怎样。

    为什么必须先探：`aerofleet.security.dev-mode=true` 时，Spring Security 链是
    `anyRequest().permitAll()`，**且** `StreamTokenFilter` 会直接跳过 ——
    于是匿名访问 /mjpeg 会拿到 200 和真实帧流。这不是漏洞，是 dev 模式的既定行为；
    但它意味着 S3「匿名必须被拒」这条断言**在 dev 后端上不可能成立**。

    不先探的后果有两个，都是实测踩到的：
      1) 红在「匿名 -> 401」上，看不出真正原因是后端配置不对（红在错的原因上）；
      2) 匿名那一探会把整段流读进内存（实测 7MB），又慢又可能撑爆内存。
    返回 "strict"（匿名被拒）/ "open"（匿名放行）。
    """
    st, _h, _b = http("GET", base + "/api/v1/surveillance/devices", raw=True, timeout=15)
    if st == 200:
        return "open"
    if st in (401, 403):
        return "strict"
    return "unknown(%s)" % st


def http(method, url, body=None, token=None, raw=False, timeout=20):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read()
            if raw:
                return r.status, dict(r.headers), payload
            return r.status, (json.loads(payload.decode()) if payload else {})
    except urllib.error.HTTPError as e:
        payload = e.read()
        if raw:
            return e.code, dict(e.headers), payload
        try:
            return e.code, json.loads(payload.decode())
        except Exception:
            return e.code, {"raw": payload[:200].decode("latin-1")}


def split_multipart(blob, boundary):
    """拆 multipart/x-mixed-replace。标注每帧是否完整 —— 读被截断时最后一帧不完整，
    那是读取方造成的，不能算成"流声明长度与实际不符"。"""
    sep = ("--" + boundary).encode()
    parts = []
    idx = blob.find(sep)
    while idx >= 0:
        head_end = blob.find(b"\r\n\r\n", idx)
        if head_end < 0:
            break
        head = blob[idx:head_end].decode("latin-1")
        seq = clen = None
        for line in head.split("\r\n"):
            low = line.lower()
            if low.startswith("x-frame-seq:"):
                seq = int(line.split(":", 1)[1].strip())
            elif low.startswith("content-length:"):
                clen = int(line.split(":", 1)[1].strip())
        start = head_end + 4
        data = blob[start:start + clen] if clen else b""
        complete = blob[start + clen:start + clen + 2] == b"\r\n" or blob.find(sep, start) >= 0
        parts.append({"seq": seq, "declared": clen, "data": data, "complete": complete,
                      "is_jpeg": data[:3] == b"\xff\xd8\xff"})
        nxt = blob.find(sep, start)
        if nxt < 0:
            break
        idx = nxt
    return parts


def wait_http(url, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        try:
            with urllib.request.urlopen(url, timeout=3):
                return True
        except Exception:
            time.sleep(1)
    return False


def main():
    ap = argparse.ArgumentParser(description="MJPEG 浏览器可播出口 E2E 实机验证")
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--snapshot-url", default="http://127.0.0.1:8099/snapshot")
    ap.add_argument("--username", default="admin")
    ap.add_argument("--password", default="")
    ap.add_argument("--device-id", default="e2e-mjpeg-cam")
    ap.add_argument("--fps", type=int, default=8)
    ap.add_argument("--frames", type=int, default=4,
                    help="至少收满几帧才算通过")
    ap.add_argument("--allow-static-frames", action="store_true",
                    help="允许每帧内容相同（手机对着静止场景时属正常；"
                         "默认仍要求画面在动）")
    ap.add_argument("--spawn-frame-source", action="store_true",
                    help="自己拉起 scripts/camera-snapshot-source.py")
    ap.add_argument("--allow-open-backend", action="store_true",
                    help="允许对着不鉴权的后端跑（dev 模式）。此时 S3「匿名必须被拒」"
                         "会按记录处理而不是断言失败 —— 只在只想验取流链路本身时用")
    args = ap.parse_args()

    snap = args.snapshot_url
    child = None
    if args.spawn_frame_source:
        port = snap.rsplit(":", 1)[1].split("/")[0]
        child = subprocess.Popen(
            [sys.executable, os.path.join(HERE, "camera-snapshot-source.py"),
             "--port", port],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if not wait_http(snap.replace("/snapshot", "/healthz")):
            print("  帧源没起来，先读它的输出：")
            if child.stdout:
                print(child.stdout.read(600).decode("latin-1"))
            return 2
        print("  已拉起帧源 %s (pid=%d)" % (snap, child.pid))

    try:
        run_checks(args, snap)
    finally:
        if child is not None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except Exception:
                child.kill()

    print("\n================ 结论 ================")
    for n in notes:
        print("  记录：%s" % n)
    print("  PASS=%d  FAIL=%d" % (ok, fail))
    return 1 if fail else 0


def run_checks(args, snap):
    base = args.backend.rstrip("/")
    snap_hz = snap.replace("/snapshot", "/healthz")

    step("0. 前置")
    try:
        st, _ = http("GET", base + "/actuator/health", timeout=8)
    except Exception:
        print("  [FAIL] 连不上后端 %s —— 先起后端" % base)
        raise SystemExit(2)
    check("后端可达", st == 200, "HTTP %d" % st)

    if not args.password:
        env = os.environ.get("AF_E2E_PASSWORD") or os.environ.get("AEROFLEET_PASSWORD")
        args.password = env or ""
    if not args.password:
        print("  [FAIL] 没给密码（--password / AF_E2E_PASSWORD）")
        raise SystemExit(2)

    # 鉴权姿态先探：dev 模式下 S3 那条「匿名必须被拒」**不可能成立**，
    # 与其红在错的原因上（而且顺手把整段流读进内存），不如在这里说清白。
    step("0.5 后端鉴权姿态")
    posture = probe_auth_posture(base)
    if posture != "strict" and not args.allow_open_backend:
        print("  [FAIL] 匿名访问受保护端点返回的不是 401/403（探测结果：%s）" % posture)
        print("")
        print("  这通常意味着后端跑在 dev 模式：")
        print("    aerofleet.security.dev-mode=true 时，Spring Security 链是 anyRequest().permitAll()，")
        print("    且 StreamTokenFilter 直接跳过 —— 匿名取流会拿到 200 和真实帧流。")
        print("    这是 dev 模式的既定行为（不是漏洞），但它让本脚本的 S3 安全断言失去意义。")
        print("")
        print("  要跑本脚本，请用非 dev 配置启动后端：")
        print("    --aerofleet.security.dev-mode=false --aerofleet.security.jwt-secret=<>=32 字节>")
        print("  并确保库里有一个可登录用户（登录走数据库，内存用户配置在有库时会被忽略）。")
        print("  确实要对着一个不鉴权的后端跑（只验取流链路本身），加 --allow-open-backend。")
        raise SystemExit(2)
    if posture != "strict":
        note("后端不鉴权（%s）—— S3 匿名断言已按 --allow-open-backend 跳过" % posture)
    else:
        check("后端鉴权姿态：匿名被拒", True, "S3 断言有意义")

    step("1. 登录拿长效 JWT")
    st, body = http("POST", base + "/api/v1/auth/login",
                    {"username": args.username, "password": args.password})
    if not check("登录成功", st == 200 and "token" in body, "HTTP %d" % st):
        raise SystemExit(2)
    jwt = body["token"]
    print("  token %s... (%d 字符)" % (jwt[:20], len(jwt)))

    step("2. S1 帧源真能出 JPEG，且内容在变")
    try:
        st1, h1, f1 = http("GET", snap, raw=True, timeout=12)
        st2, h2, f2 = http("GET", snap, raw=True, timeout=12)
    except Exception as e:
        print("  [FAIL] 帧源不可达 %s: %s" % (snap, e))
        raise SystemExit(2)
    check("帧源 200", st1 == 200, "HTTP %d" % st1)
    check("字节是 JPEG（SOI=ffd8ff）", f1[:3] == b"\xff\xd8\xff", f1[:3].hex())
    # Content-Type 只作记录，不作断言：真实网络摄像头常把它标错
    # （有的把 JPEG 标成 application/octet-stream，有的把非 JPEG 标成 image/jpeg）。
    # 我们和后端一样**按字节判定**成帧，所以帧源的头标得对不对，不影响这条链路是否成立。
    if h1.get("Content-Type") != "image/jpeg":
        note("帧源 Content-Type 是 %r（不是 image/jpeg）—— 按字节判定成帧，不影响链路"
             % h1.get("Content-Type"))
    if args.allow_static_frames:
        note("两次取帧内容相同（已开 --allow-static-frames：静态场景下属正常）: %d vs %d bytes"
             % (len(f1), len(f2)))
    else:
        check("两次取帧内容不同（不是缓存的静止画面）", f1 != f2,
              "%d vs %d bytes" % (len(f1), len(f2)))

    step("3. S2 注册设备（显式帧源），设备视图如实回显")
    # 两处登记缺口，如实记录而非掩盖：
    #   * 厂商枚举没有 GENERIC/OTHER，非厂商设备只能填 ONVIF（枚举自述的通用 fallback）
    #   * registerDevice 强制 username 非空，而本地非厂商设备本就无凭据
    # 两者都与 /mjpeg 路径无关（帧源由 snapshotUrl 决定）。
    note("厂商枚举无 GENERIC/OTHER，非厂商设备只能登记为 ONVIF")
    note("registerDevice 强制 username 非空，非厂商设备只能用占位凭据")
    dev = {"id": args.device_id, "name": "MJPEG E2E 帧源",
           "vendor": "ONVIF", "ip": "127.0.0.1", "port": 80,
           "username": "e2e", "password": "n/a", "channels": 1,
           "snapshotUrl": snap}
    http("DELETE", base + "/api/v1/surveillance/devices/" + args.device_id, token=jwt)
    st, body = http("POST", base + "/api/v1/surveillance/devices", dev, token=jwt)
    if not check("注册成功", st == 200, "HTTP %d %s" % (st, str(body)[:70])):
        raise SystemExit(2)
    st, body = http("GET", base + "/api/v1/surveillance/devices", token=jwt)
    mine = [d for d in body.get("devices", []) if d.get("id") == args.device_id]
    if check("设备在列表里", len(mine) == 1, "共 %d 台" % len(body.get("devices", []))):
        check("browserPlayable=true（由真帧源决定，非推导）",
              mine[0].get("browserPlayable") is True, repr(mine[0].get("browserPlayable")))
        check("snapshotUrl 原样回显", mine[0].get("snapshotUrl") == snap,
              str(mine[0].get("snapshotUrl")))

    step("4. S3 匿名必须被拒（链上 permitAll ≠ 不鉴权）")
    if posture != "strict":
        # 后端不鉴权时，这一探会把整段流读进内存（实测 7MB）且注定失败 ——
        # 上面 0.5 已经把原因说清楚了，这里不重复制造一次红。
        note("后端不鉴权（%s），跳过匿名探测（无意义且会读进整段流）" % posture)
    else:
        st, h, blob = http("GET", "%s/api/v1/surveillance/devices/%s/mjpeg?fps=%d"
                           % (base, args.device_id, args.fps), raw=True, timeout=15)
        check("匿名 -> 401", st == 401, "HTTP %d" % st)
        check("匿名响应一帧都没漏", b"--aerofleetframe" not in blob, "%d bytes" % len(blob))

    step("5. S4 换短令牌后开流")
    st, body = http("POST", base + "/api/v1/auth/stream-token", {}, token=jwt)
    stok = body.get("token", "")
    if not check("拿到 stream-token", st == 200 and bool(stok), "len=%d" % len(stok)):
        raise SystemExit(2)
    url = "%s/api/v1/surveillance/devices/%s/mjpeg?streamToken=%s&fps=%d" % (
        base, args.device_id, stok, args.fps)
    # 不要带空的 Authorization 头：StreamTokenFilter 视"头凭证在场"为歧义凭证，
    # 会跳过流令牌判定，于是 401。浏览器 <img> 本来也不发这个头。
    try:
        resp = urllib.request.urlopen(urllib.request.Request(url), timeout=30)
    except urllib.error.HTTPError as e:
        check("开流 200", False, "HTTP %d %s" % (e.code, e.read()[:120].decode("latin-1")))
        return
    ct = resp.headers.get("Content-Type", "")
    check("Content-Type 是 multipart/x-mixed-replace",
          ct.startswith("multipart/x-mixed-replace"), ct)
    check("Cache-Control 含 no-store（防止缓存住一张死图）",
          "no-store" in (resp.headers.get("Cache-Control") or ""),
          resp.headers.get("Cache-Control", ""))

    step("6. S5/S6 逐帧校验")
    boundary = ct.split("boundary=", 1)[1] if "boundary=" in ct else "aerofleetframe"
    blob = b""
    end = time.time() + 20
    while time.time() < end and len(blob) < 200000:
        chunk = resp.read(4096)
        if not chunk:
            break
        blob += chunk
        if blob.count(b"--" + boundary.encode()) >= args.frames + 1:
            break
    resp.close()

    parts = split_multipart(blob, boundary)
    check("至少收到 %d 帧" % args.frames, len(parts) >= args.frames,
          "实收 %d 帧 / %d bytes" % (len(parts), len(blob)))
    if not parts:
        return
    full = [p for p in parts if p["complete"]]
    check("完整帧声明长度与实际字节一致",
          all(p["declared"] == len(p["data"]) for p in full),
          "完整 %d 帧" % len(full))
    check("每帧都是真 JPEG（SOI）", all(p["is_jpeg"] for p in parts),
          "%d/%d" % (sum(1 for p in parts if p["is_jpeg"]), len(parts)))
    seqs = [p["seq"] for p in parts if p["seq"] is not None]
    check("帧号递增", seqs == sorted(seqs) and len(set(seqs)) == len(seqs),
          str(seqs[:6]))
    sizes = [p["declared"] for p in parts]
    print("  前 6 帧字节数: %s" % sizes[:6])
    if args.allow_static_frames:
        note("帧内容未变（已开 --allow-static-frames：静态场景下属正常）: distinct=%d"
             % len(set(sizes)))
    else:
        check("帧内容在变（同一张图重复发不算能看）", len(set(sizes)) > 1,
              "distinct=%d" % len(set(sizes)))

    step("7. S7 注销后明确拒绝")
    st, _ = http("DELETE", base + "/api/v1/surveillance/devices/" + args.device_id, token=jwt)
    check("注销成功", st in (200, 204), "HTTP %d" % st)
    st, body = http("POST", base + "/api/v1/auth/stream-token", {}, token=jwt)
    stok2 = body.get("token", "")
    st, h, blob = http("GET", "%s/api/v1/surveillance/devices/%s/mjpeg?streamToken=%s"
                       % (base, args.device_id, stok2), raw=True, timeout=15)
    check("已注销设备 -> 404", st == 404, "HTTP %d" % st)
    check("404 是 JSON 而非帧流",
          b"--aerofleetframe" not in blob and b"error" in blob,
          blob[:70].decode("latin-1"))


if __name__ == "__main__":
    sys.exit(main())