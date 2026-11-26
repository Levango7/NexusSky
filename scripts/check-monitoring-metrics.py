#!/usr/bin/env python3
"""校验监控配置里引用的 Prometheus 指标名是否真实存在。

背景：grafana-dashboard.json 与 alertmanager-rules.yaml 里的 PromQL 直接书写
指标名字符串，而指标名由 Java 代码（Micrometer）注册。两侧没有任何编译期或
运行期约束，改名/拼写错误不会让任何构建变红——只会在运维打开面板或告警
永远不触发时才暴露。本仓已实测三次同类问题：drones_online、ws_connections、
mavlink_frames_total 都是被 dashboard 引用但代码从未注册的名字。

本脚本把「代码实际注册的指标名」与「监控配置引用的指标名」对齐：
  1. 扫描 cloud-backend 主源码里的 aerofleet.* 指标字面量，转成 Prometheus
     命名（点→下划线；Counter/FunctionCounter 追加 _total）。
  2. 提取 dashboard 与 alertmanager 里所有 expr 的 PromQL 指标选择器。
  3. 每个引用必须落在「代码注册集合 ∪ actuator/Prometheus 白名单」内，
     否则判为无效引用，脚本以非零码退出。

设计取舍：有效集合是**超集**（含 @ConfigurationProperties 的 aerofleet.*
前缀，且对每个名字都接受 _total 形态），宁可放过、绝不误报——避免把真实
指标误判为无效而挡构建。

只用 Python 标准库，无需安装依赖，CI 可直接运行。
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import re
import sys
import tempfile
from pathlib import Path

# PromQL 关键字（非指标名）。聚合/函数名靠「后面紧跟 (」识别，不在此列。
PROMQL_KEYWORDS = {
    "by", "without", "on", "ignoring", "group_left", "group_right",
    "bool", "offset", "and", "or", "unless", "start", "end",
}

# 非 aerofleet 的有效指标：Spring Boot actuator 与 Prometheus 自身暴露的序列。
# 前缀匹配；「up」单独精确匹配。
ACTUATOR_PREFIXES = (
    "jvm_", "http_server_requests_", "process_", "system_", "tomcat_",
    "hikaricp_", "logback_", "disk_", "executor_", "jdbc_", "kafka_",
    "spring_", "http_", "jetty_", "undertow_", "cache_", "data_",
)


def collect_registered_prometheus_names(src_root: Path) -> set[str]:
    """扫描主源码，收集 aerofleet.* 指标字面量的 Prometheus 命名形态。

    同时接受裸名（Gauge）与追加 _total（Counter/FunctionCounter）两种形态。
    """
    forms: set[str] = set()
    pattern = re.compile(r'"(aerofleet\.[a-z0-9_.]+)"')
    for path in src_root.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in pattern.finditer(text):
            base = match.group(1).replace(".", "_")
            forms.add(base)
            forms.add(base + "_total")
    return forms


def iter_dashboard_exprs(path: Path) -> list[str]:
    """递归遍历 dashboard JSON，取出所有 targets[].expr 字符串。"""
    data = json.loads(path.read_text(encoding="utf-8"))
    exprs: list[str] = []

    def walk(node: object) -> None:
        if isinstance(node, dict):
            expr = node.get("expr")
            if isinstance(expr, str):
                exprs.append(expr)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(data)
    return exprs


def iter_yaml_exprs(path: Path) -> list[str]:
    """逐行提取 YAML 里的 expr 值，兼容单行与 expr: | 多行块（零依赖）。"""
    lines = path.read_text(encoding="utf-8").splitlines()
    exprs: list[str] = []
    i = 0
    while i < len(lines):
        match = re.match(r"^(\s*)expr:\s*(.*)$", lines[i])
        if not match:
            i += 1
            continue
        indent = len(match.group(1))
        rest = match.group(2).strip()
        if rest in ("|", "|-", "|+", ">", ">-", ">+"):
            block: list[str] = []
            i += 1
            while i < len(lines):
                line = lines[i]
                if line.strip() == "":
                    block.append(line)
                    i += 1
                    continue
                current = len(line) - len(line.lstrip())
                if current > indent:
                    block.append(line)
                    i += 1
                else:
                    break
            exprs.append(" ".join(part.strip() for part in block))
            continue
        # 单行 expr：去掉可能的行尾注释
        exprs.append(rest.split(" #")[0].strip())
        i += 1
    return exprs


def metric_candidates(expr: str) -> list[str]:
    """从一条 PromQL 里提取候选指标选择器标识符。

    依次剥离 {} 选择器、时间范围 [...]、by/without/on 等的标签列表、引号
    字符串；随后逐个标识符排除「紧跟 ( 的函数名」、关键字与数字。
    """
    text = re.sub(r"\{[^}]*\}", " ", expr)
    # 剥离时间范围选择器 [1m]/[5m]/[1h30m]，否则 duration 里的 m 会被误当指标名
    text = re.sub(r"\[[^\]]*\]", " ", text)
    text = re.sub(
        r"\b(by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)",
        " ", text,
    )
    text = re.sub(r'"[^"]*"', " ", text)
    text = re.sub(r"'[^']*'", " ", text)

    candidates: list[str] = []
    for match in re.finditer(r"[a-zA-Z_:][a-zA-Z0-9_:]*", text):
        name = match.group(0)
        # 跳过紧跟 ( 的——那是函数调用，不是指标名
        j = match.end()
        while j < len(text) and text[j] == " ":
            j += 1
        if j < len(text) and text[j] == "(":
            continue
        if name in PROMQL_KEYWORDS:
            continue
        candidates.append(name)
    return candidates


def is_valid(name: str, registered: set[str]) -> bool:
    if name.startswith("aerofleet_"):
        return name in registered
    if name == "up":
        return True
    return any(name.startswith(prefix) for prefix in ACTUATOR_PREFIXES)


def run(repo: Path, src: Path, dashboard: Path, alert_rules: Path) -> int:
    """核心校验：收集注册名 → 提取引用 → 逐一核对。返回进程退出码。"""
    registered = collect_registered_prometheus_names(src)
    if not registered:
        print(f"错误：在 {src} 下没有扫描到任何 aerofleet.* 指标，疑似路径不对", file=sys.stderr)
        return 2

    def display(path: Path) -> str:
        # 配置文件可能位于 repo 之外（如自检的临时副本），relative_to 需容错
        try:
            return str(path.resolve().relative_to(repo))
        except ValueError:
            return str(path)

    sources: list[tuple[str, list[str]]] = []
    if dashboard.exists():
        sources.append((display(dashboard), iter_dashboard_exprs(dashboard)))
    if alert_rules.exists():
        sources.append((display(alert_rules), iter_yaml_exprs(alert_rules)))

    invalid: list[tuple[str, str, str]] = []
    checked = 0
    for source, exprs in sources:
        for expr in exprs:
            for name in metric_candidates(expr):
                checked += 1
                if not is_valid(name, registered):
                    invalid.append((source, name, expr.strip()))

    if invalid:
        print("发现监控配置引用了代码中不存在的指标名：")
        for source, name, expr in invalid:
            print(f"  [{source}] 未注册指标 {name!r}\n      expr: {expr}")
        print(f"\n共 {len(invalid)} 处无效引用（已核对 {checked} 个指标选择器）。")
        print("修法：把 expr 改成代码里真实注册的指标名，或先在代码里注册该指标。")
        return 1

    print(f"通过：{checked} 个指标选择器全部命中代码注册集合或 actuator 白名单"
          f"（代码注册 {len(registered)} 个 Prometheus 名形态）。")
    return 0


def self_test() -> int:
    """离线自检：证明这道门禁真的会红，且不误伤干净配置。

    夹具全部落在临时目录，不碰真实仓库；端到端用例与 CI 走同一条 run() 路径。
    """
    ok = True

    def check(label: str, cond: bool) -> None:
        nonlocal ok
        print(("PASS " if cond else "FAIL ") + label)
        ok = ok and cond

    def run_quiet(*args) -> int:
        # 夹具**故意**造红，run() 会刷屏。静默掉，让自检结论一眼可读。
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            return run(*args)

    with tempfile.TemporaryDirectory(prefix="monmetric-selftest-") as tmp:
        base = Path(tmp)
        src = base / "src"
        src.mkdir()
        (src / "Metrics.java").write_text(
            'Counter.builder("aerofleet.messages.received").register(r);\n'
            'Gauge.builder("aerofleet.drones.online", () -> 1).register(r);\n'
            'static final String S = "aerofleet.ws.sessions";\n',
            encoding="utf-8")
        registered = collect_registered_prometheus_names(src)
        check("收集裸名 aerofleet_drones_online", "aerofleet_drones_online" in registered)
        check("收集 _total 形态", "aerofleet_messages_received_total" in registered)
        check("常量形态 aerofleet_ws_sessions", "aerofleet_ws_sessions" in registered)

        # 单元级：候选提取不误报 duration / 函数 / by 标签 / 白名单
        cands = metric_candidates("sum(rate(aerofleet_messages_received_total[1m])) by (msgId)")
        check("duration m 不误报", "m" not in cands)
        check("函数名不入选", "sum" not in cands and "rate" not in cands)
        check("by 标签不入选", "msgId" not in cands)
        check("指标名入选", "aerofleet_messages_received_total" in cands)

        # is_valid：actuator 白名单与 up
        check("jvm_ 白名单", is_valid("jvm_memory_used_bytes", registered))
        check("up 白名单", is_valid("up", registered))
        check("无效 aerofleet 名", not is_valid("aerofleet_nope", registered))
        check("无效非 aerofleet 名", not is_valid("ws_connections", registered))

        absent_json = base / "absent.json"
        absent_yaml = base / "absent.yaml"

        # 端到端：干净 dashboard 退 0（含 duration/actuator/up）
        clean = base / "clean.json"
        clean.write_text(json.dumps({"panels": [{"targets": [
            {"expr": "aerofleet_drones_online"},
            {"expr": "sum(rate(aerofleet_messages_received_total[1m]))"},
            {"expr": 'jvm_memory_used_bytes{area="heap"}'},
            {"expr": 'up{job="cloud-backend"} == 0'},
        ]}]}), encoding="utf-8")
        check("干净 dashboard 退 0", run_quiet(base, src, clean, absent_yaml) == 0)

        # 端到端：无效 aerofleet 名退 1
        bad_aero = base / "bad-aero.json"
        bad_aero.write_text(json.dumps({"panels": [{"targets": [
            {"expr": "aerofleet_drones_onlineX"}]}]}), encoding="utf-8")
        check("无效 aerofleet 名退 1", run_quiet(base, src, bad_aero, absent_yaml) == 1)

        # 端到端：无效非 aerofleet 名退 1（ws_connections / mavlink_frames_total 形态）
        bad_plain = base / "bad-plain.json"
        bad_plain.write_text(json.dumps({"panels": [{"targets": [
            {"expr": "ws_connections"},
            {"expr": "rate(mavlink_frames_total[1m])"}]}]}), encoding="utf-8")
        check("无效非 aerofleet 名退 1", run_quiet(base, src, bad_plain, absent_yaml) == 1)

        # 端到端：repo 外的 dashboard 路径不崩（display 容错）
        outside = Path(tempfile.mkdtemp(prefix="monmetric-outside-")) / "out.json"
        outside.write_text(json.dumps({"panels": [{"targets": [
            {"expr": "aerofleet_drones_online"}]}]}), encoding="utf-8")
        check("repo 外路径不崩且退 0", run_quiet(base, src, outside, absent_yaml) == 0)

        # 端到端：YAML 多行/单行 expr 提取且全有效
        rules = base / "rules.yaml"
        rules.write_text(
            "groups:\n  - name: g\n    rules:\n"
            "      - alert: A\n        expr: |\n"
            "          histogram_quantile(0.95,\n"
            "            sum(rate(http_server_requests_seconds_bucket[5m])) by (le)\n"
            "          ) > 2\n"
            "      - alert: B\n        expr: aerofleet_drones_online == 0\n",
            encoding="utf-8")
        check("YAML 多行/单行 expr 全有效退 0", run_quiet(base, src, absent_json, rules) == 0)

    print("self-test:", "全部通过" if ok else "有失败")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="校验监控配置引用的 Prometheus 指标名真实存在")
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="仓库根目录（默认取脚本上一级）")
    parser.add_argument("--src", type=Path, default=None,
                        help="Java 主源码目录（默认 <repo>/cloud-backend/src/main/java）")
    parser.add_argument("--dashboard", type=Path, default=None,
                        help="grafana dashboard JSON（默认 deploy/monitoring/grafana-dashboard.json）")
    parser.add_argument("--alert-rules", type=Path, default=None,
                        help="alertmanager 规则 YAML（默认 deploy/monitoring/alertmanager-rules.yaml）")
    parser.add_argument("--self-test", action="store_true",
                        help="离线自检：用合成夹具证明门禁会红且不误伤干净态")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    repo = args.repo_root.resolve()
    src = args.src or (repo / "cloud-backend/src/main/java")
    dashboard = args.dashboard or (repo / "deploy/monitoring/grafana-dashboard.json")
    alert_rules = args.alert_rules or (repo / "deploy/monitoring/alertmanager-rules.yaml")
    return run(repo, src, dashboard, alert_rules)


if __name__ == "__main__":
    sys.exit(main())
