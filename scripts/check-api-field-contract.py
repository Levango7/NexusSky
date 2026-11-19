#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import io, json, os, re, shutil, sys, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SNAPSHOT_REL = os.path.join("docs", "api-field-contract.json")

VIEWS = {
    "drones.summary": ("cloud-backend/src/main/java/io/aerofleet/cloud/api/dto/DroneViews.java",
                       "summary", [], "GET /api/v1/drones row"),
    "drones.telemetry": ("cloud-backend/src/main/java/io/aerofleet/cloud/api/dto/DroneViews.java",
                         "telemetry", [], "GET /api/v1/drones/{sysid}/telemetry"),
    "drones.detail": ("cloud-backend/src/main/java/io/aerofleet/cloud/api/dto/DroneViews.java",
                      "detail", ["drones.telemetry"], "detail = telemetry + alerts"),
    "drones.status": ("cloud-backend/src/main/java/io/aerofleet/cloud/api/dto/DroneViews.java",
                      "status", [], "WS status frame"),
    "docks.view": ("cloud-backend/src/main/java/io/aerofleet/cloud/dock/DockController.java",
                   "view", [], "dock REST view"),
    "docks.scheduleView": ("cloud-backend/src/main/java/io/aerofleet/cloud/dock/DockController.java",
                          "scheduleView", [], "dock schedule view"),
    "docks.runView": ("cloud-backend/src/main/java/io/aerofleet/cloud/dock/DockController.java",
                     "runView", [], "dock run log view"),
}

PUT_RE = re.compile(r'\.put\(\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*,')
def read(p):
    if not os.path.exists(p):
        raise FileNotFoundError(p)
    return io.open(p, encoding="utf-8", errors="replace").read()


def method_body(text, name):
    m = re.search(r"\b" + re.escape(name) + r"\s*\([^)]*\)\s*\{", text)
    if not m:
        return None
    start = m.end() - 1
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return None


def extract_keys(text, name):
    body = method_body(text, name)
    if body is None:
        return None
    return sorted(set(PUT_RE.findall(body)))

def resolve(root, name, seen=None):
    seen = set(seen or ())
    if name in seen:
        raise ValueError("inherits cycle: %s" % name)
    seen.add(name)
    path, method, parents, _desc = VIEWS[name]
    keys = extract_keys(read(os.path.join(root, path)), method)
    if keys is None:
        raise LookupError("%s: method %s() not found" % (path, method))
    if not keys:
        raise LookupError("%s#%s(): extracted 0 keys, refusing false green" % (path, method))
    for parent in parents:
        keys = sorted(set(keys) | set(resolve(root, parent, seen)))
    return keys


def build(root):
    out = {}
    for name in VIEWS:
        path, method, _parents, desc = VIEWS[name]
        out[name] = {"desc": desc, "source": path + "#" + method,
                     "keys": resolve(root, name)}
    return out

def load_snapshot(path):
    if not os.path.exists(path):
        return None, "snapshot missing: %s" % os.path.relpath(path, ROOT)
    try:
        with io.open(path, encoding="utf-8") as f:
            return json.load(f), None
    except Exception as e:
        return None, "snapshot parse failed: %s" % e


def main():
    if "--self-test" in sys.argv:
        return self_test()
    snapshot_path = os.path.join(ROOT, SNAPSHOT_REL)
    try:
        actual = build(ROOT)
    except FileNotFoundError as e:
        print("precondition failed: missing source %s" % e, file=sys.stderr)
        return 2
    except LookupError as e:
        print("precondition failed: %s" % e, file=sys.stderr)
        return 2

    if "--update" in sys.argv:
        os.makedirs(os.path.dirname(snapshot_path), exist_ok=True)
        io.open(snapshot_path, "w", encoding="utf-8", newline="\n").write(
            json.dumps({"views": actual}, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        print("snapshot updated: %s (%d views)"
              % (os.path.relpath(snapshot_path, ROOT), len(actual)))
        return 0

    snap, err = load_snapshot(snapshot_path)
    if err:
        print("precondition failed: %s" % err, file=sys.stderr)
        return 2
    old_views = (snap or {}).get("views") or {}
    problems, notes = [], []
    for name in sorted(actual):
        now = set(actual[name]["keys"])
        old = set((old_views.get(name) or {}).get("keys") or [])
        if not old:
            notes.append("%s newly registered, run --update" % name)
            continue
        removed = sorted(old - now)
        added = sorted(now - old)
        if removed:
            problems.append("%s (%s) removed/renamed: %s"
                            % (name, actual[name]["source"], ", ".join(removed)))
        if added:
            notes.append("%s added keys (compatible): %s" % (name, ", ".join(added)))
    for name in sorted(set(old_views) - set(actual)):
        problems.append("snapshot view %s no longer exists" % name)

    for n in notes:
        print("  [info] %s" % n)
    if problems:
        print("")
        print("field contract gate: %d breaking change(s)" % len(problems))
        for p in problems:
            print("  - %s" % p)
        print("")
        print("  If intentional: update frontend + migration note, then --update.")
        return 1
    print("field contract gate: OK (%d views, no removals)" % len(actual))
    return 0

def self_test():
    global ROOT, VIEWS
    saved_root, saved_views = ROOT, VIEWS
    tmp = tempfile.mkdtemp(prefix="api-field-contract-")
    fails = []
    dto = os.path.join("cloud-backend", "src", "main", "java", "io", "aerofleet", "cloud", "api", "dto")
    dock = os.path.join("cloud-backend", "src", "main", "java", "io", "aerofleet", "cloud", "dock")
    try:
        os.makedirs(os.path.join(tmp, dto))
        os.makedirs(os.path.join(tmp, dock))
        io.open(os.path.join(tmp, dto, "DroneViews.java"), "w", encoding="utf-8", newline="\n").write(
            "class DroneViews {\n"
            "  static java.util.Map<String,Object> summary(Object s) {\n"
            "    java.util.Map<String,Object> m = new java.util.HashMap<>();\n"
            "    m.put(\"sysid\", 1);\n"
            "    m.put(\"rtkStatus\", \"RTK_FIXED\");\n"
            "    return m;\n"
            "  }\n"
            "  static java.util.Map<String,Object> telemetry(Object s) {\n"
            "    java.util.Map<String,Object> m = new java.util.HashMap<>();\n"
            "    m.put(\"battery\", 1);\n"
            "    return m;\n"
            "  }\n"
            "  static java.util.Map<String,Object> detail(Object s) {\n"
            "    java.util.Map<String,Object> m = telemetry(s);\n"
            "    m.put(\"callsign\", \"x\");\n"
            "    return m;\n"
            "  }\n"
            "  static java.util.Map<String,Object> status(Object s) {\n"
            "    java.util.Map<String,Object> m = new java.util.HashMap<>();\n"
            "    m.put(\"online\", true);\n"
            "    return m;\n"
            "  }\n"
            "}\n")
        io.open(os.path.join(tmp, dock, "DockController.java"), "w", encoding="utf-8", newline="\n").write(
            "class DockController {\n"
            "  static java.util.Map<String,Object> view(Object d) {\n"
            "    java.util.Map<String,Object> m = new java.util.LinkedHashMap<>();\n"
            "    m.put(\"id\", 1);\n"
            "    return m;\n"
            "  }\n"
            "}\n")
        ROOT = tmp
        if extract_keys(read(os.path.join(tmp, dto, "DroneViews.java")), "summary") != ["rtkStatus", "sysid"]:
            fails.append("key extraction")
        if resolve(tmp, "drones.detail") != ["battery", "callsign"]:
            fails.append("inherits: %r" % (resolve(tmp, "drones.detail"),))
        if resolve(tmp, "docks.view") != ["id"]:
            fails.append("docks.view")
        try:
            resolve(tmp, "drones.missing")
            fails.append("unknown view not rejected")
        except KeyError:
            pass
        empty = re.sub(PUT_RE, "noop(", read(os.path.join(tmp, dto, "DroneViews.java")))
        io.open(os.path.join(tmp, dto, "DroneViews.java"), "w", encoding="utf-8", newline="\n").write(empty)
        try:
            resolve(tmp, "drones.summary")
            fails.append("zero-key extraction not rejected (false green)")
        except LookupError:
            pass
    finally:
        ROOT, VIEWS = saved_root, saved_views
        shutil.rmtree(tmp, ignore_errors=True)
    if fails:
        for x in fails:
            print("  [FAIL] %s" % x)
        return 3
    print("  [PASS] extraction")
    print("  [PASS] inherits")
    print("  [PASS] unknown view / zero-key rejected")
    return 0


if __name__ == "__main__":
    sys.exit(main())
