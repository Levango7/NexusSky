#!/usr/bin/env python3
"""
Reference external vision inference server for NexusSky.

Protocol (POST image/jpeg -> JSON array):
  Request:  POST /infer (or --path), Content-Type: image/jpeg, body = JPEG bytes
  Response: 200 application/json
            [{"kind":"person","confidence":0.93,"u":312.4,"v":148.0}, ...]

Modes:
  - mock (default): deterministic/seeded contract-valid detections for
    integration and pipeline checks. No ML dependencies required.
  - replay: returns detections from --detections-file (JSON array, array-of-frames,
    or JSONL one-frame-per-line) in order, cycling; useful for deterministic
    regression without a model.
  - onnx: optional hook for users who have onnxruntime + a compatible model;
    this reference implementation does NOT bundle a model. Provide --model and
    implement/adapt preprocessing+postprocessing for your model's I/O tensors.

Use --self-test to validate contract helpers without starting the server.
This script is an optional tool outside the main Maven/Java dependency graph.
"""

import argparse
import json
import random
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def parse_args():
    p = argparse.ArgumentParser(description="NexusSky external vision inference reference server")
    p.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    p.add_argument("--port", type=int, default=9001, help="Bind port (default: 9001)")
    p.add_argument("--path", default="/infer", help="Inference endpoint path (default: /infer)")
    p.add_argument("--mode", choices=["mock", "onnx", "replay"], default="mock", help="Inference mode")
    p.add_argument("--model", default="", help="Path to .onnx model (required for --mode onnx)")
    p.add_argument("--detections-file", default="", help="JSON array or JSONL file for --mode replay")
    p.add_argument("--kinds", default="person,vehicle,insulator", help="Comma-separated labels for mock mode")
    p.add_argument("--max-detections", type=int, default=3, help="Max mock detections per frame")
    p.add_argument("--width", type=int, default=640, help="Reference image width for mock/replay coords")
    p.add_argument("--height", type=int, default=360, help="Reference image height for mock/replay coords")
    p.add_argument("--seed", type=int, default=7, help="RNG seed for mock mode")
    p.add_argument("--min-confidence", type=float, default=0.55, help="Min mock confidence")
    p.add_argument("--drop-rate", type=float, default=0.0, help="Mock frame drop rate [0,1] returning empty list")
    p.add_argument("--self-test", action="store_true", help="Run contract self-test and exit")
    return p.parse_args()


class OnnxRunner:
    def __init__(self, model_path: str):
        try:
            import onnxruntime as ort  # type: ignore
        except Exception as e:
            raise RuntimeError(
                "onnxruntime is required for --mode onnx. Install it in your environment "
                "(e.g. pip install onnxruntime) and provide a compatible --model."
            ) from e
        if not model_path:
            raise RuntimeError("--model is required for --mode onnx")
        self.ort = ort
        self.session = ort.InferenceSession(model_path, providers=ort.get_available_providers())
        self.inputs = self.session.get_inputs()
        self.outputs = self.session.get_outputs()

    def infer(self, jpeg_bytes: bytes, width: int, height: int):
        """
        Placeholder adapter: decode/preprocess/postprocess depend on the specific model.
        Implement mapping to NexusSky contract: [{kind, confidence, u, v}, ...]
        Coordinates u/v must be in original image pixel space (width x height).
        """
        raise RuntimeError(
            "ONNX adapter hook reached, but model-specific preprocessing/postprocessing "
            "is not implemented in this reference script. Adapt this method for your "
            "model's input tensor layout and output format (boxes/scores/classes), then "
            "return contract-valid detections."
        )


def _normalize_detection(d):
    if not isinstance(d, dict):
        raise ValueError("detection must be object")
    kind = d.get("kind")
    if not isinstance(kind, str) or not kind.strip():
        raise ValueError("kind must be non-empty string")
    try:
        confidence = float(d.get("confidence"))
        u = float(d.get("u"))
        v = float(d.get("v"))
    except Exception as e:
        raise ValueError("u/v/confidence must be numeric") from e
    if confidence < 0.0 or confidence > 1.0:
        raise ValueError("confidence out of range")
    return {"kind": kind, "confidence": confidence, "u": u, "v": v}


def validate_detections(payload):
    if not isinstance(payload, list):
        raise ValueError("response must be a JSON array")
    return [_normalize_detection(x) for x in payload]


def load_replay(path: str):
    text = Path(path).read_text(encoding="utf-8").strip()
    if not text:
        return [[]]
    # JSONL: one frame per line
    if "\n" in text and not text.lstrip().startswith("["):
        frames = []
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            frames.append(validate_detections(json.loads(line)))
        return frames or [[]]
    obj = json.loads(text)
    # either a single frame (array of detections) or array of frames
    if isinstance(obj, list) and obj and all(isinstance(x, list) for x in obj):
        return [validate_detections(frame) for frame in obj]
    return [validate_detections(obj)]


def replay_detections(frames, frame_no: int):
    if not frames:
        return []
    return frames[(frame_no - 1) % len(frames)]


def mock_detections(args, frame_no: int):
    rng = random.Random(args.seed + frame_no * 101)
    if args.drop_rate > 0 and rng.random() < args.drop_rate:
        return []
    kinds = [k.strip() for k in args.kinds.split(",") if k.strip()] or ["object"]
    n = rng.randint(0, max(0, args.max_detections))
    out = []
    for _ in range(n):
        conf = max(0.0, min(1.0, rng.uniform(args.min_confidence, 0.99)))
        out.append({
            "kind": rng.choice(kinds),
            "confidence": round(conf, 4),
            "u": round(rng.uniform(0, max(1, args.width - 1)), 2),
            "v": round(rng.uniform(0, max(1, args.height - 1)), 2),
        })
    return out


def self_test():
    ok = validate_detections([{"kind": "tower", "confidence": 0.9, "u": 1, "v": 2}])
    assert ok[0]["kind"] == "tower"
    try:
        validate_detections([{"kind": "", "confidence": 0.5, "u": 1, "v": 2}])
        raise AssertionError("expected failure")
    except ValueError:
        pass
    frames = load_replay_text('[{"kind":"a","confidence":1,"u":0,"v":0}]')
    assert frames[0][0]["kind"] == "a"
    print("self-test ok")


def load_replay_text(text: str):
    text = text.strip()
    if not text:
        return [[]]
    if "\n" in text and not text.lstrip().startswith("["):
        frames = []
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            frames.append(validate_detections(json.loads(line)))
        return frames or [[]]
    obj = json.loads(text)
    if isinstance(obj, list) and obj and all(isinstance(x, list) for x in obj):
        return [validate_detections(frame) for frame in obj]
    return [validate_detections(obj)]


def main():
    args = parse_args()
    if args.self_test:
        self_test()
        return
    runner = None
    replay_frames = None
    if args.mode == "onnx":
        runner = OnnxRunner(args.model)
    elif args.mode == "replay":
        if not args.detections_file:
            raise SystemExit("--detections-file is required for --mode replay")
        replay_frames = load_replay(args.detections_file)

    state = {"frame": 0}

    class Handler(BaseHTTPRequestHandler):
        server_version = "NexusSkyVisionRef/1.0"

        def log_message(self, fmt, *a):
            sys.stdout.write("%s - %s\n" % (self.address_string(), fmt % a))

        def _send_json(self, code, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path in ("/health", "/healthz"):
                self._send_json(200, {"ok": True, "mode": args.mode})
                return
            self._send_json(404, {"error": "not found"})

        def do_POST(self):
            if self.path != args.path:
                self._send_json(404, {"error": "not found"})
                return
            ctype = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            length = int(self.headers.get("Content-Length") or "0")
            body = self.rfile.read(length) if length > 0 else b""
            if ctype and ctype != "image/jpeg":
                self._send_json(415, {"error": "expected Content-Type image/jpeg"})
                return
            if not body:
                self._send_json(400, {"error": "empty body"})
                return
            state["frame"] += 1
            try:
                if args.mode == "onnx":
                    detections = runner.infer(body, args.width, args.height)
                elif args.mode == "replay":
                    detections = replay_detections(replay_frames, state["frame"])
                else:
                    detections = mock_detections(args, state["frame"])
                self._send_json(200, detections)
            except Exception as e:
                self._send_json(500, {"error": str(e)})

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    extra = f" detections_file={args.detections_file}" if args.mode == "replay" else ""
    print(f"vision-inference-server listening on http://{args.host}:{args.port}{args.path} mode={args.mode}{extra}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
