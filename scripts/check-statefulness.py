#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""持久化状态性守卫：审计 §2.1「必须落库」的九个域，必须要么真有持久化证据、要么在豁免账里写明理由与到期日。

**为什么需要它**
----------------
`docs/persistence-and-dead-surface-audit.md` §2.1 实测了九个"对外承诺了持久性、或丢了会破坏合规
叙事"的业务域。它们在审计时全是内存态，重启即失；2026-10-27 起分批落库（V30~V35）。
这类债的特点是**回退不响**：某个域哪天被改回纯内存（或迁移表被删、水合调用被摘），
编译照过、启动照跑、测试可能照绿——只有重启后数据没了才知道。

审计 §6 给的验收姿势就是本脚本：**照 `check-spi-coverage.py` 的四元账形状**——
每个域要么"证据齐全"，要么"豁免登记（理由 + 到期日）"。

**判据（每条都可复算）**
------------------------
`DOMAINS` 里每个域有一组锚点 `(相对路径 glob, 正则)`，**全部命中**才算"证据齐全"。
四类情况一律判红：
  1. 证据不齐且无豁免            —— 新增/回退的内存态域必须登记
  2. 豁免条目对应的域已有完整证据 —— 收紧，把账删掉（棘轮只准拧紧）
  3. 豁免已到期                  —— 拖延必须变红，不能悄悄续期
  4. 锚点文件一个都匹配不到      —— 改名/移动/删除后账要跟着改，不许静默失明
退出码：0 通过；1 违规；**2 无法判定**（一个域的锚点都没解析到＝扫描器失明，绝不伪装成 0）。

**口径与局限（别把它当"全仓内存态覆盖"）**
------------------------------------------
- 它盯的是审计已定性的这九个域；**新增业务域需要手动登记**（登记本身就是一次声明）。
- "证据齐全"只证明持久化通路存在（实体/仓库/迁移表/水合方法），**不证明行为正确**——
  行为由各域自己的"重启恢复"测试钉（第一批三条约 13 例是模板）。
- 判据锚点是文本级（正则命中），不是编译级；改包名/改方法名后锚点失配会红，跟着修即可。

用法：
    python3 scripts/check-statefulness.py              # CI 判定
    python3 scripts/check-statefulness.py --print      # 输出当前账目（刷新豁免用，不手抄）
    python3 scripts/check-statefulness.py --self-test  # 离线自证：八种情形各自预期红/绿/无法判定
"""

import argparse
import datetime
import glob
import io
import os
import re
import sys
import tempfile

# 审计 §2.1 的九个域。加一条是深思熟虑的动作：它意味着"这个域必须有持久化证据或豁免"。
# 锚点写法：("相对仓库根的 glob", "正则")；glob 用 / 分隔，Windows 侧会转成系统分隔符。
DOMAINS = [
    {
        "name": "1 空域预约 ConflictAvoidanceService",
        "checks": [
            ("cloud-backend/src/main/java/io/aerofleet/cloud/scheduling/ConflictAvoidanceService.java",
             r"restorePersistedReservations"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql",
             r"(?i)create table\s+(if not exists\s+)?airspace_reservations\b"),
        ],
    },
    {
        "name": "2 维护记录 MaintenanceController",
        "checks": [
            ("cloud-backend/src/main/java/io/aerofleet/cloud/health/MaintenanceRecordEntity.java",
             r"@Entity"),
            ("cloud-backend/src/main/java/io/aerofleet/cloud/health/MaintenanceRecordRepository.java",
             r"interface\s+MaintenanceRecordRepository"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql",
             r"(?i)create table\s+(if not exists\s+)?maintenance_records\b"),
        ],
    },
    {
        "name": "3 出警历史 AutoDispatchService",
        "checks": [
            ("cloud-backend/src/main/java/io/aerofleet/cloud/autodispatch/DispatchHistoryRepository.java",
             r"interface\s+DispatchHistoryRepository"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql",
             r"(?i)create table\s+(if not exists\s+)?dispatch_history\b"),
        ],
    },
    {
        "name": "4 测绘航点 MappingController",
        "checks": [
            ("cloud-backend/src/main/java/io/aerofleet/cloud/mapping/MappingWaypointCodec.java",
             r"class\s+MappingWaypointCodec"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql", r"(?i)mapping_task"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql", r"(?i)waypoint"),
        ],
    },
    {
        "name": "5 配送六阶段 DeliveryStatusTracker",
        "checks": [
            # 锚点是"写穿到 delivery_task2 的 phase 列"这条通路（PR #186 的实现形状）：
            # tracker 持有 DeliveryTask2Repository + V36 给表加了 phase 列。
            ("cloud-backend/src/main/java/io/aerofleet/cloud/delivery2/DeliveryStatusTracker.java",
             r"DeliveryTask2Repository"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql",
             r"(?i)alter table\s+delivery_task2[\s\S]{0,200}?add column\s+phase\b"),
        ],
    },
    {
        "name": "6 空地协同 AirGroundCoordinationService",
        "checks": [
            ("cloud-backend/src/main/java/io/aerofleet/cloud/mission/emergency/AirGroundCoordinationService.java",
             r"CoordinationRecordRepository"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql",
             r"(?i)create table\s+(if not exists\s+)?coordination_records\b"),
        ],
    },
    {
        "name": "7 合规状态 ComplianceStateManager",
        "checks": [
            ("cloud-backend/src/main/java/io/aerofleet/cloud/regulator/ComplianceStateEntity.java",
             r"@Entity"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql",
             r"(?i)create table\s+(if not exists\s+)?compliance_state\b"),
        ],
    },
    {
        "name": "8 应急编排 EmergencyOrchService",
        "checks": [
            # 锚点是"新表独立落库"这条通路（PR #186 的实现形状）：服务持仓库 + V37 建 emergency_plan。
            ("cloud-backend/src/main/java/io/aerofleet/cloud/api/service/EmergencyOrchService.java",
             r"EmergencyPlanRepository"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql",
             r"(?i)create table\s+(if not exists\s+)?emergency_plan\b"),
        ],
    },
    {
        "name": "9 审计哈希链 AuditService",
        "checks": [
            ("cloud-backend/src/main/java/io/aerofleet/cloud/audit/AuditLogRepository.java",
             r"interface\s+AuditLogRepository"),
            ("cloud-backend/src/main/java/io/aerofleet/cloud/audit/AuditService.java",
             r"aerofleet\.audit\.persist-to-db"),
            ("cloud-backend/src/main/resources/db/migration/V*.sql",
             r"(?i)create table\s+(if not exists\s+)?audit_log\b"),
        ],
    },
]

# 豁免账。每条都要能回答"为什么现在没有证据、什么时候补"。
# 生成账目用 --print，别手抄。
#
# 2026-10-28：最后两条（配送六阶段 / 应急编排）随 #186 的 V36/V37 落库，本门禁在
# rebase 后当场把它们判成「豁免可以撤销」——按棘轮删账，EXEMPT 清零。此后新增的
# 内存态域必须带证据或重新登记（格式照旧：reason + expires）。
EXEMPT = {}


def resolve(root, pattern):
    """把 (相对路径 glob) 解析成实际文件列表（排序，稳定输出）。"""
    return sorted(glob.glob(os.path.join(root, pattern.replace("/", os.sep))))


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def domain_status(root, domain):
    """返回 (has_evidence, anchors_alive, anchor_dead, evidence_miss)。

    anchor_dead：某个锚点 glob 一个文件都匹配不到（改名/移动/删除）——永远算违规；
    evidence_miss：锚点在、但正则不命中——"还没落库"的域会命中这一类，豁免批的就是它。
    """
    anchors_alive = False
    anchor_dead, evidence_miss = [], []
    for pattern, rx in domain["checks"]:
        files = resolve(root, pattern)
        if not files:
            anchor_dead.append("锚点不存在：%s（改名/移动/删除后请更新 DOMAINS）" % pattern)
            continue
        anchors_alive = True
        if not any(re.search(rx, read(f)) for f in files):
            evidence_miss.append("证据未命中：%s 里找不到 /%s/" % (pattern, rx))
    return (not anchor_dead and not evidence_miss), anchors_alive, anchor_dead, evidence_miss


def audit(root, domains=None, exempt=None, today=None):
    """返回 (exit_code, lines)。"""
    domains = DOMAINS if domains is None else domains
    exempt = EXEMPT if exempt is None else exempt
    today = today or datetime.date.today()
    out = []
    violations = []
    persisted, pending = [], []

    if not domains:
        out.append("无法判定：域清单为空——要么扫描器配置丢了，要么 DOMAINS 被清空。")
        out.append("  判 2，不判 0——'没查到'不能冒充'没问题'。")
        return 2, out

    any_anchor_alive = False
    for d in domains:
        name = d["name"]
        has_evidence, anchors_alive, anchor_dead, evidence_miss = domain_status(root, d)
        any_anchor_alive = any_anchor_alive or anchors_alive
        # 锚点失明永远红，哪怕域挂了豁免——豁免批的是"还没落库"，不是"文件可以消失"
        violations.extend("%s：%s" % (name, p) for p in anchor_dead)
        ex = exempt.get(name)
        if has_evidence and ex:
            violations.append(
                "豁免可以撤销：%s 已有完整持久化证据，请从 EXEMPT 删除（棘轮只准拧紧）" % name)
        elif has_evidence:
            persisted.append(name)
        elif ex is not None:
            try:
                expires = datetime.date.fromisoformat(ex["expires"])
            except (KeyError, ValueError):
                violations.append("%s：豁免条目缺 expires 或格式不是 YYYY-MM-DD" % name)
                continue
            if expires < today:
                violations.append(
                    "豁免已到期：%s（到期 %s）——请补落库或显式改期，别悄悄续" % (name, expires))
            else:
                pending.append((name, ex))
        else:
            # 有豁免时"证据未命中"不出违规（豁免批的就是它）；此处只报真缺口
            for p in evidence_miss:
                violations.append("%s：%s" % (name, p))
            violations.append("缺证据且无豁免：%s（该类域必须落库；确不能落库的走 EXEMPT 登记）" % name)

    for name in sorted(exempt):
        if name not in {d["name"] for d in domains}:
            violations.append("豁免账有僵尸条目：%s 不在 DOMAINS 里（改名/删除后请从 EXEMPT 删掉）" % name)

    if not any_anchor_alive and not persisted and not pending:
        out.append("无法判定：一个域的锚点都没解析到（root=%s）。" % root)
        out.append("  判 2，不判 0——扫描器失明时不许报绿。")
        return 2, out

    out.append(
        "持久化状态性：域 %d 个 / 证据齐全 %d / 豁免在册 %d / 违规 %d"
        % (len(domains), len(persisted), len(pending), len(violations)))
    for name in persisted:
        out.append("  [证据] %s" % name)
    for name, ex in pending:
        out.append("  [豁免] %s（到期 %s：%s）" % (name, ex["expires"], ex["reason"]))

    if violations:
        out.append("")
        out.append("违规 %d 条：" % len(violations))
        out.extend("  - " + v for v in violations)
        return 1, out
    return 0, out


def do_print(root):
    for d in DOMAINS:
        has_evidence, problems = domain_status(root, d)
        if has_evidence:
            status = "EVIDENCE"
        elif d["name"] in EXEMPT:
            status = "EXEMPT"
        else:
            status = "UNREGISTERED"
        print("%-12s %s" % (status, d["name"]))
        for p in problems:
            print("             %s" % p)
    return 0


# ---------------------------------------------------------------- 自测
def write_fixture(tmp, files):
    for rel, content in files.items():
        p = os.path.join(tmp, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with io.open(p, "w", encoding="utf-8", newline="") as fh:
            fh.write(content)


def self_test():
    """八种情形必须各自给出预期结果。任一不符 ⇒ 自测失败（exit 1）。"""
    fails = []
    today = datetime.date(2026, 10, 28)

    def dom(checks):
        return [{"name": "X 域", "checks": checks}]

    def expect(label, code, lines, want_code, needle=None):
        if code != want_code:
            fails.append("%s 期望 %d 实得 %d :: %s" % (label, want_code, code, lines))
        if needle and not any(needle in ln for ln in lines):
            fails.append("%s 未报出期待文案 %r :: %s" % (label, needle, lines))

    # 1) 证据齐全、无豁免 ⇒ 0
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, {"m/a/Foo.java": "class Foo { void restoreAll() {} }\n"})
        code, lines = audit(tmp, dom([("m/a/Foo.java", r"restoreAll")]), {}, today)
        expect("情形1", code, lines, 0)

    # 2) 证据不齐、无豁免 ⇒ 1，点名"缺证据且无豁免"
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, {"m/a/Foo.java": "class Foo {}\n"})
        code, lines = audit(tmp, dom([("m/a/Foo.java", r"restoreAll")]), {}, today)
        expect("情形2", code, lines, 1, "缺证据且无豁免")

    # 3) 证据不齐、未到期豁免 ⇒ 0
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, {"m/a/Foo.java": "class Foo {}\n"})
        code, lines = audit(tmp, dom([("m/a/Foo.java", r"restoreAll")]),
                            {"X 域": {"reason": "fixture", "expires": "2099-01-01"}}, today)
        expect("情形3", code, lines, 0)

    # 4) 证据不齐、豁免到期 ⇒ 1，点名到期
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, {"m/a/Foo.java": "class Foo {}\n"})
        code, lines = audit(tmp, dom([("m/a/Foo.java", r"restoreAll")]),
                            {"X 域": {"reason": "fixture", "expires": "2020-01-01"}}, today)
        expect("情形4", code, lines, 1, "豁免已到期")

    # 5) 证据已在、还挂豁免 ⇒ 1，点名可撤销
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, {"m/a/Foo.java": "class Foo { void restoreAll() {} }\n"})
        code, lines = audit(tmp, dom([("m/a/Foo.java", r"restoreAll")]),
                            {"X 域": {"reason": "fixture", "expires": "2099-01-01"}}, today)
        expect("情形5", code, lines, 1, "豁免可以撤销")

    # 6) 锚点文件不存在（改名/删除）⇒ 1，且即使有豁免也红
    with tempfile.TemporaryDirectory() as tmp:
        code, lines = audit(tmp, dom([("m/a/Gone.java", r"whatever")]),
                            {"X 域": {"reason": "fixture", "expires": "2099-01-01"}}, today)
        expect("情形6", code, lines, 1, "锚点不存在")

    # 7) 僵尸豁免（域清单里没有这个名字）⇒ 1
    with tempfile.TemporaryDirectory() as tmp:
        write_fixture(tmp, {"m/a/Foo.java": "class Foo { void restoreAll() {} }\n"})
        code, lines = audit(tmp, dom([("m/a/Foo.java", r"restoreAll")]),
                            {"Y 域": {"reason": "fixture", "expires": "2099-01-01"}}, today)
        expect("情形7", code, lines, 1, "僵尸条目")

    # 8) 域清单为空 ⇒ 2（扫描器失明不许报绿）
    with tempfile.TemporaryDirectory() as tmp:
        code, lines = audit(tmp, [], {}, today)
        expect("情形8", code, lines, 2, "无法判定")

    if fails:
        print("[FAIL] 状态性门禁自测：%d 条不符" % len(fails))
        for f in fails:
            print("  - " + f)
        return 1
    print("[PASS] 状态性门禁自测：8 种情形全部按预期（红/绿/无法判定）")
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--print", dest="do_print", action="store_true")
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.do_print:
        return do_print(args.root)
    code, lines = audit(args.root)
    print("\n".join(lines))
    return code


if __name__ == "__main__":
    sys.exit(main())
