#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本机 UVC 摄像头 -> 真实运动事件源。

为什么需要这个工具
------------------
后端的厂商适配器（海康 ISAPI / 大华 DHSDK / 宇视 SDK / GB28181 / ONVIF）目前
**全是桩**：它们把回调塞进 map 就返回，从不触发。`AlarmEventStore` 的唯一生产者
是 `POST /api/v1/alarms/events`，也就是只能靠人手工发 JSON。

于是"安防事件"这条链路长期没有任何**真实**事件源。这个脚本把本机的 UVC 摄像头
接上，做真正的背景建模 + 运动判定，产出**带实测数值**的事件（运动像素数、占比、
峰值、前景框），让整条 `运动 -> AlarmEventStore -> /api/v1/surveillance/events
-> 面板` 链路能在没有网络摄像头的情况下被真实验证。

它**不能**替代厂商适配器：本机摄像头是 UVC 设备，不说 ISAPI/DHSDK/ONVIEW，
`registerDevice` 也不认它（枚举里没有 UVC），所以它不会出现在设备列表里。

用法
----
    python scripts/camera-motion-source.py --duration 90
    python scripts/camera-motion-source.py --threshold 6 --cooldown 8
    python scripts/camera-motion-source.py --dry-run     # 只检测，不发 HTTP

产物
----
每触发一次事件，保存一张证据帧到 --evidence-dir，文件名带事件 id，
可与数据库里的事件一一对应核对。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

import cv2
import numpy as np

DEFAULT_URL = 'http://localhost:8080/api/v1/alarms/events'


def post_json(url, payload, timeout=10):
    """POST JSON。

    显式 utf-8 编码：Windows 控制台/默认编码会把中文 description 变成 "??"，
    那样存进库的事件描述就是坏的，事后没法核对（这个坑踩过）。
    """
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(
        url, data=data,
        headers={'Content-Type': 'application/json; charset=utf-8'})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        return 0, 'ERR %s' % e


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--camera', type=int, default=0, help='摄像头序号（Windows 一般是 0）')
    ap.add_argument('--backend', default='http://localhost:8080')
    ap.add_argument('--device-id', default='uvc-0')
    ap.add_argument('--device-name', default='本机摄像头 HD Webcam')
    ap.add_argument('--threshold', type=float, default=0.010,
                    help='运动像素占比阈值，超过即触发（默认 1%%）')
    ap.add_argument('--warmup', type=float, default=1.5,
                    help='预热秒数：这段时间只建背景、不判运动（默认 1.5s）。'
                         '去掉它会让**自动曝光收敛**被当成运动——实测首帧起就能报出'
                         '73%% 全画面变化，那不是运动。')
    ap.add_argument('--min-peak', type=int, default=55,
                    help='前景峰值差下限。运动会在物体边缘制造高对比；光照变化虽然'
                         '覆盖全画面但峰值低（实测：真运动峰值 89，光照变化峰值 40）。')
    ap.add_argument('--max-bbox-ratio', type=float, default=0.70,
                    help='前景包围盒占全画面的最大比例。超过说明是**整体**变化'
                         '（曝光/白平衡）而非某个物体在动。')
    ap.add_argument('--illum-tolerance', type=float, default=0.06,
                    help='平均亮度相对背景的突变幅度，超过判为光照变化并重建背景。')
    ap.add_argument('--cooldown', type=float, default=6.0, help='两次事件的最小间隔秒')
    ap.add_argument('--duration', type=float, default=60.0, help='运行秒数，<=0 表示不限')
    ap.add_argument('--evidence-dir',
                    default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                         '..', 'target', 'camera-motion-evidence'))
    ap.add_argument('--dry-run', action='store_true', help='只检测并发打印，不发 HTTP')
    args = ap.parse_args()

    ev_dir = os.path.abspath(args.evidence_dir)
    os.makedirs(ev_dir, exist_ok=True)
    url = args.backend.rstrip('/') + '/api/v1/alarms/events'

    cap = cv2.VideoCapture(args.camera, cv2.CAP_DSHOW)
    if not cap.isOpened():
        cap.release()
        cap = cv2.VideoCapture(args.camera)
    if not cap.isOpened():
        print('[FAIL] 打不开摄像头 %d' % args.camera)
        return 2

    print('[ok] 摄像头已打开  deviceId=%s  url=%s%s'
          % (args.device_id, url, '  (dry-run)' if args.dry_run else ''))
    print('[cfg] 阈值=%.3f%%  预热=%.1fs  冷却=%.1fs  峰值下限=%d  包围盒上限=%.0f%%'
          % (args.threshold * 100, args.warmup, args.cooldown,
             args.min_peak, args.max_bbox_ratio * 100))
    print('       证据目录=%s' % ev_dir)
    print('       提示：请在摄像头前活动一下，否则可能一直不触发（静止场景本来就没有事件）')
    print()

    # 背景用指数滑动平均（EMA）。比相邻帧差更稳：相邻帧差会被传感器噪声顶起来，
    # 也检测不到"人进来后停住不动"。
    bg = None
    ALPHA = 0.02          # 背景更新速率，太快会吃掉运动本身
    SCALE = 0.25          # 降采样，既降噪又快

    t0 = time.time()
    warmup_until = t0 + args.warmup
    last_event = -1e9
    events = 0
    frame_no = 0
    skipped_illum = 0
    skipped_global = 0
    skipped_peak = 0
    ratios = []

    try:
        while True:
            if args.duration > 0 and time.time() - t0 > args.duration:
                break
            ok, frame = cap.read()
            if not ok or frame is None:
                print('[warn] 读帧失败，停止')
                break
            frame_no += 1

            small = cv2.resize(frame, None, fx=SCALE, fy=SCALE, interpolation=cv2.INTER_AREA)
            gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
            gray = cv2.GaussianBlur(gray, (5, 5), 0)

            if bg is None:
                bg = gray.astype(np.float32)
                continue

            # --- 预热期：只学背景，不判运动 ---
            if time.time() < warmup_until:
                bg = cv2.addWeighted(bg, 1 - ALPHA,
                                    gray.astype(np.float32), ALPHA, 0)
                if frame_no % 30 == 0:
                    print('    ... 预热中 frame=%d' % frame_no)
                continue

            # --- 光照突变：整体亮度对不上背景，是曝光/白平衡在动，不是场景在动 ---
            bg_u8 = bg.astype(np.uint8)
            mean_now, mean_bg = float(gray.mean()), float(bg_u8.mean())
            illum_shift = abs(mean_now - mean_bg) / max(1.0, mean_bg)
            if illum_shift > args.illum_tolerance:
                skipped_illum += 1
                # 让背景快速追上新光照，否则接下来几十帧都会一直被判"在动"
                bg = cv2.addWeighted(bg, 0.5, gray.astype(np.float32), 0.5, 0)
                print('    ... 光照突变（平均亮度偏移 %.1f%%），重建背景，不算运动'
                      % (illum_shift * 100))
                continue

            diff = cv2.absdiff(gray, bg_u8)
            _, mask = cv2.threshold(diff, 25, 255, cv2.THRESH_BINARY)
            # 形态学开运算去掉孤立噪点
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))

            total = mask.size
            changed = int(np.count_nonzero(mask))
            ratio = changed / float(total)
            ratios.append(ratio)

            if ratio > args.threshold:
                ys, xs = np.nonzero(mask)
                if len(xs) == 0:
                    continue
                x0, x1 = int(xs.min()), int(xs.max())
                y0, y1 = int(ys.min()), int(ys.max())
                box = '%dx%d@(x%d..%d,y%d..%d) in %dx%d' % (
                    x1 - x0, y1 - y0, x0, x1, y0, y1,
                    mask.shape[1], mask.shape[0])
                box_ratio = float((x1 - x0 + 1) * (y1 - y0 + 1)) / float(total)
                peak = int(diff.max())

                # --- 全画面变化 = 光照，不是运动 ---
                if box_ratio > args.max_bbox_ratio:
                    skipped_global += 1
                    bg = cv2.addWeighted(bg, 0.3,
                                        gray.astype(np.float32), 0.7, 0)
                    print('    ... 前景包围盒占 %.0f%%（全画面）判为光照变化，不算运动'
                          % (box_ratio * 100))
                    continue

                # --- 峰值过低 = 整体微调，不是有边缘的物体运动 ---
                if peak < args.min_peak:
                    skipped_peak += 1
                    bg = cv2.addWeighted(bg, 1 - ALPHA,
                                        gray.astype(np.float32), ALPHA, 0)
                    continue

                now = time.time()
                if now - last_event >= args.cooldown:
                    conf = min(1.0, ratio / 0.05)          # 5% 运动视为满置信
                    severity = ('CRITICAL' if ratio >= 0.05 else
                                'WARN' if ratio >= 0.02 else 'INFO')
                    desc = ('运动检出 %d/%d px (%.2f%%) 峰值差 %d 前景框 %s 占画面 %.0f%% 置信 %.2f'
                            % (changed, total, ratio * 100, peak, box,
                               box_ratio * 100, conf))

                    ev_id = 'uvcm-%d-%d' % (int(t0), events + 1)
                    ev_path = os.path.join(ev_dir, 'motion_%s.jpg' % ev_id)
                    cv2.imwrite(ev_path, frame)

                    payload = {
                        'id': ev_id,
                        'sourceDeviceId': args.device_id,
                        'sourceDeviceName': args.device_name,
                        'eventType': 'MOTION',
                        'severity': severity,
                        'description': desc,
                        'lat': 0.0, 'lon': 0.0, 'alt': 0.0,
                        'timestampMs': int(time.time() * 1000),
                    }
                    if args.dry_run:
                        print('[motion #%d] frame=%d %s  (dry-run, 未发送)'
                              % (events + 1, frame_no, desc))
                    else:
                        code, body = post_json(url, payload)
                        print('[motion #%d] frame=%d %s' % (events + 1, frame_no, desc))
                        print('            -> HTTP %s %s' % (code, body.strip()[:110]))
                        print('            evidence: %s' % ev_path)
                    events += 1
                    last_event = now
                    # 触发后短暂冻结背景更新，让事件画面留在前景里
                    for _ in range(5):
                        ok2, f2 = cap.read()
                        if not ok2:
                            break
                    continue
            bg = cv2.addWeighted(bg, 1 - ALPHA,
                                gray.astype(np.float32), ALPHA, 0)

            if frame_no % 60 == 0:
                print('    ... frame=%d  当前运动占比=%.3f%%  已发事件=%d'
                      % (frame_no, ratio * 100, events))
    except KeyboardInterrupt:
        print('\n[stop] 用户中断')
    finally:
        cap.release()

    print()
    if ratios:
        arr = np.array(ratios)
        print('[stat] 判定帧数=%d  运动占比 mean=%.4f%%  p95=%.4f%%  max=%.4f%%'
              % (len(ratios), arr.mean() * 100,
                 np.percentile(arr, 95) * 100, arr.max() * 100))
    print('[stat] 触发事件数=%d  抑制: 光照突变 %d / 全画面 %d / 峰值不足 %d'
          % (events, skipped_illum, skipped_global, skipped_peak))
    if not args.dry_run and events:
        print('[next] 去验证：')
        print('       curl "http://localhost:8080/api/v1/surveillance/events?deviceId=%s"'
              % args.device_id)
    return 0 if events else 1


if __name__ == '__main__':
    sys.exit(main())