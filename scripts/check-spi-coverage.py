#!/usr/bin/env python3
"""SPI 实现的可验证性守卫：每个"通往外部世界的适配器"必须有测试引用它。

**为什么需要它**
----------------
2026-10-12 我人肉数了一遍：13 个 SPI 接口下共 35 个生产实现，其中 **9 个在所有测试源码里
一次都没出现过**（`UomReportSink`、`MavlinkDeviceGateway`、`UdpTransportAdapter`、
`RealEnvSource`、5 个 `ModuleAdapter` 实现）。这类代码不会让任何测试转红——它编译、它启动、
它甚至在日志里"看起来在工作"，只是**没有任何断言钉过它**。外部适配器的缺陷（报文格式、重试、
超时、鉴权头）恰好是最贵的一类：真机/真平台才暴露。

这个脚本把"新增了没人验的适配器"变成 CI 红灯。

**口径与它的局限（别把它当覆盖率）**
--------------------------------
判据是「类名是否出现在 `*/src/test/**/*.java` 里」。因此：
  - 通过**接口名**或 Spring 上下文间接经过的，**不算**已验——`ModuleAdapter` 接口在
    `StepExecutorTest` 里出现 4 次，但那 5 个具体适配器一次都没被点名，所以仍判未验；
  - 类名被引用也**不等于**行为被断言（可能只是 import 或 mock 声明）；
  - 它管的是"有没有被测试触及"，不是"测试够不够"。
要更严的判据（行覆盖/分支覆盖）请走 `scripts/ci-coverage-threshold.sh` 那条腿。

**设计取向：默认全关、例外显式、到期自动回红**
------------------------------------------
`EXEMPT` 里每条都必须带 `reason` 与 `expires`（YYYY-MM-DD）。四种情况一律判红：
  1. 出现未登记豁免的未验实现        —— 新增适配器必须带测试
  2. 豁免条目对应的类已不存在          —— 改名/删除后账要清
  3. 豁免条目其实已经被测试引用        —— 收紧，把账删掉
  4. 豁免已到期                        —— 拖延必须变红，不能悄悄续期
退出码：0 通过；1 违规；**2 无法判定**（没扫到任何实现＝扫描器失明，绝不伪装成 0）。

用法：
    python3 scripts/check-spi-coverage.py              # CI 判定
    python3 scripts/check-spi-coverage.py --print      # 输出当前未验清单（刷新豁免账用，不手抄）
    python3 scripts/check-spi-coverage.py --self-test  # 离线自证：五种情形都要按预期变红/变绿
"""

import argparse
import datetime
import os
import re
import sys
import tempfile

# 参与判定的 SPI 接口。加一条是深思熟虑的动作：它意味着"这个接口的每个实现都必须被测试点名"。
SPI_INTERFACES = [
    "RegulatorReportSink",   # 监管上报对端（UOM / sim / noop）
    "DeviceGateway",         # F5 多品牌双栈网关
    "DockGateway",           # F2 机巢命令下行通道
    "MavlinkTransport",      # 无人机侧 MAVLink 传输
    "VendorAdapter",         # 安防相机厂商适配（GB28181/ONVIF/海康/大华/宇视）
    "SatLinkProvider",       # 卫星链路提供
    "ModuleAdapter",         # 编排任务模块适配（行业任务形态）
    "HardwareAdapter",       # 硬件/载荷适配
    "VisionSource",
    "ThermalSource",
    "DepthSource",
    "EnvironmentSource",
    "RestrictionDataSource",
]

# 豁免账。每条都要能回答"为什么现在没人验、什么时候补"。
# 生成未验清单用 --print，别手抄类名。
EXEMPT = {
    "MavlinkDeviceGateway": {
        "reason": "F5 只交付了协议标记+路由骨架；CommandGatewayTest 用的是接口替身，"
                  "本类从未被点名。待补：把替身换成真实例、断言 dji-cloud 命令被明确拒绝",
        "expires": "2026-10-26",
    },
    "UdpTransportAdapter": {
        "reason": "drone-sim 传输腿，测试里零引用。待补：回环端口上的收发闭环 + 关闭后不再收包",
        "expires": "2026-11-02",
    },
    "RealEnvSource": {
        "reason": "drone-sim 环境源的真实实现（读外部数据），测试里零引用。"
                  "待补：注入桩数据源断言取值路径；真环境部分标为集成测试",
        "expires": "2026-11-02",
    },
}

SKIP_DIRS = {".git", "target", "node_modules", "dist", "build", ".m2", ".idea"}

CLASS_DECL_RE = re.compile(
    r"\b(?:class|enum|record)\s+([A-Za-z0-9_]+)[^{;]*?\bimplements\b([^{;]*)", re.S
)


def iter_java(root, which):
    """产出 (path, text)。which 取 'main' 或 'test'，按目录段匹配，避免漏掉多模块。"""
    seg = "/src/%s/java/" % which
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dirpath, fn).replace(os.sep, "/")
            if seg not in p:
                continue
            try:
                with open(p, encoding="utf-8", errors="replace") as fh:
                    yield p, fh.read()
            except OSError:
                continue


def find_implementations(root):
    """{interface: [(class, path), ...]}"""
    found = {}
    for p, txt in iter_java(root, "main"):
        for m in CLASS_DECL_RE.finditer(txt):
            cls, ifaces = m.group(1), m.group(2)
            for spi in SPI_INTERFACES:
                if re.search(r"\b" + re.escape(spi) + r"\b", ifaces):
                    found.setdefault(spi, []).append((cls, p))
    return found


def test_blob(root):
    return "\n".join(txt for _, txt in iter_java(root, "test"))


def audit(root, today=None):
    """返回 (exit_code, lines)。root 是仓库根（含各模块）。"""
    today = today or datetime.date.today()
    impls = find_implementations(root)
    all_impls = [(c, p) for lst in impls.values() for c, p in lst]
    out = []

    if not impls or not all_impls:
        out.append("无法判定：一个 SPI 实现都没扫到。")
        out.append("  要么扫描器瞎了（模块结构变了？root 传错了？），要么接口清单与代码脱节。")
        out.append("  判 2，不判 0——『没查到』不能冒充『没问题』。")
        out.append("  root=%s  registered=%d" % (root, len(SPI_INTERFACES)))
        return 2, out

    blob = test_blob(root)
    if not blob.strip():
        out.append("无法判定：测试源码树为空，任何实现都会被判成未验。")
        return 2, out

    unverified = [(c, p) for c, p in all_impls if not re.search(r"\b" + re.escape(c) + r"\b", blob)]
    unverified_names = {c for c, _ in unverified}
    impl_names = {c for c, _ in all_impls}

    violations = []
    for cls, path in sorted(unverified):
        ex = EXEMPT.get(cls)
        if ex is None:
            violations.append(
                "未验且无豁免：%s（%s）" % (cls, path)
            )
            continue
        expires = datetime.date.fromisoformat(ex["expires"])
        if expires < today:
            violations.append(
                "豁免已到期：%s（到期 %s，判据＝测试源码里出现类名）——请补测试或显式改期，别悄悄续"
                % (cls, expires)
            )

    for cls in sorted(EXEMPT):
        if cls not in impl_names:
            violations.append("豁免账有僵尸条目：%s 已不是任何 SPI 实现（改名/删除后请从 EXEMPT 删掉）" % cls)
        elif cls not in unverified_names:
            violations.append("豁免可以撤销：%s 现在已被测试引用，请从 EXEMPT 删除（棘轮只准拧紧）" % cls)

    out.append(
        "SPI 可验证性：接口 %d 个 / 生产实现 %d 个 / 未验 %d 个 / 已豁免 %d 个"
        % (len(SPI_INTERFACES), len(all_impls), len(unverified), len(EXEMPT))
    )
    for cls, path in sorted(unverified):
        mark = "豁免" if cls in EXEMPT else "违规"
        out.append("  [%s] %s  <- %s" % (mark, cls, path))

    if violations:
        out.append("")
        out.append("违规 %d 条：" % len(violations))
        out.extend("  - " + v for v in violations)
        return 1, out
    return 0, out


def do_print(root):
    impls = find_implementations(root)
    blob = test_blob(root)
    for spi in sorted(impls):
        for cls, path in sorted(impls[spi]):
            hit = bool(re.search(r"\b" + re.escape(cls) + r"\b", blob))
            print("%-24s %-28s %-9s %s" % (spi, cls, "referenced" if hit else "UNVERIFIED", path))
    return 0


# ---------------------------------------------------------------- 自测
def write_fixture(tmp, files):
    for rel, content in files.items():
        p = os.path.join(tmp, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(content)


def self_test():
    """六种情形必须各自给出预期结果。任一不符 ⇒ 自测失败（exit 1）。"""
    fails = []

    base_files = {
        "m/src/main/java/p/RegulatorReportSink.java": "package p;\npublic interface RegulatorReportSink {}\n",
        "m/src/main/java/p/SimSink.java": "package p;\npublic class SimSink implements RegulatorReportSink {}\n",
        "m/src/main/java/p/UomSink.java": "package p;\npublic class UomSink implements RegulatorReportSink {}\n",
    }

    def files_with_test(test_body):
        d = dict(base_files)
        d["m/src/test/java/p/SimSinkTest.java"] = "package p;\n" + test_body
        return d

    # 1) 一个实现被点名、另一个未验且无豁免 ⇒ exit 1，且报出未验类名
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, files_with_test("class SimSinkTest { } // SimSink"))
        global EXEMPT
        saved = EXEMPT
        EXEMPT = {}
        code, lines = audit(tmp, today=datetime.date(2026, 10, 12))
        EXEMPT = saved
        if code != 1:
            fails.append("情形1 期望 1 实得 %d" % code)
        if not any("UomSink" in ln and "未验且无豁免" in ln for ln in lines):
            fails.append("情形1 未报出 UomSink 的违规行")

    # 2) 同上但 UomSink 有未到期豁免 ⇒ exit 0
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, files_with_test("class SimSinkTest { } // SimSink"))
        saved = EXEMPT
        EXEMPT = {"UomSink": {"reason": "fixture", "expires": "2099-01-01"}}
        code, lines = audit(tmp, today=datetime.date(2026, 10, 12))
        EXEMPT = saved
        if code != 0:
            fails.append("情形2 期望 0 实得 %d :: %s" % (code, lines))

    # 3) 豁免到期 ⇒ exit 1 且文案点名到期
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, files_with_test("class SimSinkTest { } // SimSink"))
        saved = EXEMPT
        EXEMPT = {"UomSink": {"reason": "fixture", "expires": "2020-01-01"}}
        code, lines = audit(tmp, today=datetime.date(2026, 10, 12))
        EXEMPT = saved
        if code != 1:
            fails.append("情形3 期望 1 实得 %d" % code)
        if not any("豁免已到期" in ln for ln in lines):
            fails.append("情形3 未报出到期文案")

    # 4) 豁免已可撤销（实现被引用了）⇒ 棘轮必须红
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, files_with_test("class SimSinkTest { } // SimSink UomSink"))
        saved = EXEMPT
        EXEMPT = {"UomSink": {"reason": "fixture", "expires": "2099-01-01"}}
        code, lines = audit(tmp, today=datetime.date(2026, 10, 12))
        EXEMPT = saved
        if code != 1:
            fails.append("情形4 期望 1 实得 %d" % code)
        if not any("豁免可以撤销" in ln for ln in lines):
            fails.append("情形4 未报出撤销提示")

    # 5) 扫描器失明（没有任何实现）⇒ exit 2，绝不 0
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, {"m/src/main/java/p/Thing.java": "package p;\npublic class Thing {}\n",
                            "m/src/test/java/p/ThingTest.java": "package p;\nclass ThingTest {}\n"})
        code, lines = audit(tmp, today=datetime.date(2026, 10, 12))
        if code != 2:
            fails.append("情形5 期望 2 实得 %d" % code)
        if not any("无法判定" in ln for ln in lines):
            fails.append("情形5 未报出无法判定")

    # 6) 僵尸豁免条目（类不存在）⇒ exit 1
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, files_with_test("class SimSinkTest { } // SimSink UomSink"))
        saved = EXEMPT
        EXEMPT = {"GhostSink": {"reason": "fixture", "expires": "2099-01-01"}}
        code, lines = audit(tmp, today=datetime.date(2026, 10, 12))
        EXEMPT = saved
        if code != 1:
            fails.append("情形6 期望 1 实得 %d" % code)
        if not any("僵尸条目" in ln for ln in lines):
            fails.append("情形6 未报出僵尸条目")

    if fails:
        print("自测失败 %d 项：" % len(fails))
        for f in fails:
            print("  - " + f)
        return 1
    print("自测通过：6 种情形（未验无豁免→1 / 豁免未到期→0 / 豁免到期→1 / 可撤销→1 / 无实现→2 / 僵尸豁免→1）"
          "均按预期，且断言的是具体错误文案而非仅退出码。")
    return 0


def main():
    ap = argparse.ArgumentParser(description="SPI 实现可验证性守卫")
    ap.add_argument("--root", default=".", help="仓库根（默认当前目录）")
    ap.add_argument("--print", dest="do_print", action="store_true", help="输出清单（刷新豁免账用）")
    ap.add_argument("--self-test", dest="self_test", action="store_true", help="离线自证判据有效")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if args.do_print:
        return do_print(args.root)

    code, lines = audit(args.root)
    for ln in lines:
        print(ln)
    return code


if __name__ == "__main__":
    sys.exit(main())
