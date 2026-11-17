#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""迁移安全门禁：含破坏性语句的迁移，必须在回滚手册里登记。

为什么需要这个
--------------
Flyway 社区版**没有 undo**（`flyway-core` 11.7.2，Boot BOM 管理，见
`cloud-backend/pom.xml:109-120`）。这意味着一旦某个迁移删了列/表，回滚应用版本
不会把结构退回去——旧代码读一个不存在的列会直接 500，而 CI 对此**一无所知**。

本仓现在有 40 个迁移，其中 1 个是破坏性的：

    V22__api_keys_sysid.sql:11  ALTER TABLE devices DROP COLUMN IF EXISTS device_token;

问题不在这一处存量（它有正当理由），而在**新增一处时没人会注意到**：
写迁移的人只想着"这个列没用了"，不会想到"半年后有人要回滚到 V21"。
更阴险的是 `IF EXISTS` / `IF NOT EXISTS` 让重跑不报错，掩盖了结构已被改坏的事实。

一刀切禁止破坏性迁移不现实（真该删的列还是要删），所以这里用**登记制**：

* 扫描 `cloud-backend/src/main/resources/db/migration/*.sql`，命中
  `DROP TABLE` / `DROP COLUMN` / `TRUNCATE` / `DROP SCHEMA` 即视为破坏性；
* 每个命中的文件必须在 `docs/rollback-runbook.md` 的
  `<!-- destructive-migrations:begin -->` … `<!-- destructive-migrations:end -->`
  区间内被登记（写成 `` `V22__xxx.sql` ``）；
* 登记了但文件已不存在、或该文件其实不含破坏性语句，同样判红——防止清单腐烂成摆设。

为什么要解析 markdown 而不是另开一份 JSON：登记内容需要"为什么不可逆 / 出事怎么办"，
这是给人看的，必须和命令写在同一个文件里；再维护一份 JSON 必然漂移。

口径（刻意写死）
----------------
1. 只看 `db/migration/*.sql`，不看 `target/`。
2. 匹配前先掩掉 `--` 行注释、`/* */` 块注释、单引号字符串——注释里写
   `-- 不要 DROP TABLE` 不该被当成破坏性迁移。
3. 不做大小写敏感匹配（SQL 关键字大小写混写很常见）。
4. 登记名以文件名精确匹配（`V22__api_keys_sysid.sql`），不用版本号前缀模糊匹配。

已知局限
--------
`DO $$ ... $$` 块里的动态 SQL 无法静态判定：若其中用字符串拼出 `DROP TABLE`，
本脚本会因字符串掩码而漏检。这类迁移请在评审时人工确认并登记。

用法：
    python scripts/check-destructive-migration.py
    python scripts/check-destructive-migration.py --self-test
退出码：0 干净；1 有问题（逐条打印）。
"""
import argparse
import io
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_MIGRATIONS = os.path.join(ROOT, "cloud-backend", "src", "main", "resources", "db", "migration")
DEFAULT_RUNBOOK = os.path.join(ROOT, "docs", "rollback-runbook.md")

BEGIN_MARK = "<!-- destructive-migrations:begin -->"
END_MARK = "<!-- destructive-migrations:end -->"

DESTRUCTIVE_RE = re.compile(
    r"\b(DROP\s+TABLE|DROP\s+COLUMN|TRUNCATE(?:\s+TABLE)?|DROP\s+SCHEMA)\b",
    re.IGNORECASE,
)
REGISTERED_RE = re.compile(r"`(V[0-9][A-Za-z0-9_.-]*\.sql)`")


def _mask_sql(src):
    """把注释与单引号字符串替换成空格，保留换行与字符位置。

    掩码后长度不变，所以行列号仍可回查原文。
    """
    out = list(src)
    i, n, state = 0, len(src), None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state is None:
            if c == "-" and nxt == "-":
                state = "line"
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block"
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c == "'":
                state = "str"
                out[i] = " "
                i += 1
                continue
        elif state == "line":
            if c == "\n":
                state = None
            else:
                out[i] = " "
        elif state == "block":
            if c == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                i += 2
                state = None
                continue
            if c != "\n":
                out[i] = " "
        elif state == "str":
            if c == "'":
                # 处理 '' 转义
                if nxt == "'":
                    out[i] = out[i + 1] = " "
                    i += 2
                    continue
                state = None
            if c != "\n":
                out[i] = " "
        i += 1
    return "".join(out)


def _first_hit(text):
    """返回 (行号, 匹配到的关键字) 或 None。"""
    masked = _mask_sql(text)
    m = DESTRUCTIVE_RE.search(masked)
    if not m:
        return None
    line = masked.count("\n", 0, m.start()) + 1
    return line, " ".join(m.group(1).split()).upper()


def _destructive_migrations(migrations_dir):
    """{文件名: (行号, 关键字)}"""
    found = {}
    if not os.path.isdir(migrations_dir):
        return found
    for fn in sorted(os.listdir(migrations_dir)):
        if not fn.lower().endswith(".sql"):
            continue
        path = os.path.join(migrations_dir, fn)
        if not os.path.isfile(path):
            continue
        with io.open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
        hit = _first_hit(text)
        if hit:
            found[fn] = hit
    return found


def _registered(runbook_path):
    """返回 (登记名单列表, 问题列表)。"""
    problems = []
    try:
        with io.open(runbook_path, encoding="utf-8") as f:
            text = f.read()
    except OSError as e:
        return [], ["无法读取回滚手册 %s：%s" % (runbook_path, e)]

    if BEGIN_MARK not in text or END_MARK not in text:
        return [], ["回滚手册缺少登记区间标记（%s … %s）" % (BEGIN_MARK, END_MARK)]

    start = text.index(BEGIN_MARK) + len(BEGIN_MARK)
    end = text.index(END_MARK)
    if end < start:
        return [], ["回滚手册登记区间标记顺序颠倒（end 在 begin 之前）"]

    section = text[start:end]
    names = REGISTERED_RE.findall(section)
    # 区间为空不在此处判红：仓库里若确实没有破坏性迁移，空表是正确状态。
    # 「有破坏性迁移但没登记」由 check() 逐文件报，定位更准。

    seen = {}
    for nm in names:
        seen[nm] = seen.get(nm, 0) + 1
    for nm, cnt in sorted(seen.items()):
        if cnt > 1:
            problems.append("回滚手册重复登记同一迁移 %s（%d 次）" % (nm, cnt))
    return sorted(seen), problems


def check(migrations_dir, runbook_path):
    """返回问题列表。"""
    destructive = _destructive_migrations(migrations_dir)
    names, problems = _registered(runbook_path)
    registered = set(names)

    for fn, (line, kw) in sorted(destructive.items()):
        if fn not in registered:
            problems.append(
                "破坏性迁移未登记：%s:%d（%s）——请写进 docs/rollback-runbook.md 的不可回滚清单，"
                "说明为什么不可逆、出事怎么办" % (fn, line, kw))

    for nm in names:
        path = os.path.join(migrations_dir, nm)
        if not os.path.isfile(path):
            problems.append("登记失效：回滚手册列了 %s，但迁移目录里没有这个文件" % nm)
            continue
        if nm not in destructive:
            problems.append(
                "登记失效：回滚手册列了 %s，但该文件不含破坏性语句（清单腐烂，请删除该行）" % nm)

    return problems, len(destructive), len(names)


def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def self_test():
    """用合成夹具证明：未登记/失效/缺标记会红，合法登记与注释内关键字不误拦。"""
    table = (
        "<!-- destructive-migrations:begin -->\n"
        "| 迁移 | 说明 |\n|---|---|\n%s\n"
        "<!-- destructive-migrations:end -->\n"
    )

    def run_case(name, sql_map, registered_lines, expect_clean, omit_marks=False):
        with tempfile.TemporaryDirectory() as tmp:
            mig = os.path.join(tmp, "migration")
            os.makedirs(mig)
            for fn, body in sql_map.items():
                _write(os.path.join(mig, fn), body)
            if omit_marks:
                runbook = "| 迁移 |\n|---|\n" + "\n".join(registered_lines)
            else:
                runbook = "# 回滚手册\n\n" + (table % "\n".join(registered_lines))
            rb = os.path.join(tmp, "rollback-runbook.md")
            _write(rb, runbook)
            problems, _, _ = check(mig, rb)
            ok = (not problems) == expect_clean
            detail = "" if ok else ("problems=%r" % (problems,))
            print("  [%s] %s %s" % ("PASS" if ok else "FAIL", name, detail))
            return ok

    cases = []

    cases.append(run_case(
        "破坏性且未登记 → 红",
        {"V1__a.sql": "ALTER TABLE t DROP COLUMN c;"},
        ["| `V0__none.sql` |"], False))

    cases.append(run_case(
        "破坏性且已登记 → 绿",
        {"V1__a.sql": "ALTER TABLE t DROP COLUMN c;"},
        ["| `V1__a.sql` | 删列 |"], True))

    cases.append(run_case(
        "非破坏性 → 绿",
        {"V1__a.sql": "CREATE TABLE t (id int);"},
        [], True))

    cases.append(run_case(
        "行注释里的 DROP TABLE 不误判 → 绿",
        {"V1__a.sql": "-- 这里不要写 DROP TABLE\nCREATE TABLE t (id int);"},
        [], True))

    cases.append(run_case(
        "块注释与字符串里的 TRUNCATE 不误判 → 绿",
        {"V1__a.sql": "/* TRUNCATE t; */\nINSERT INTO logs VALUES ('TRUNCATE t');"},
        [], True))

    cases.append(run_case(
        "大小写混写仍命中 → 红",
        {"V1__a.sql": "truncate table t;"},
        [], False))

    cases.append(run_case(
        "登记了但文件不存在 → 红",
        {"V1__a.sql": "CREATE TABLE t (id int);"},
        ["| `V9__gone.sql` |"], False))

    cases.append(run_case(
        "登记了但文件不含破坏性语句 → 红（清单腐烂）",
        {"V1__a.sql": "CREATE TABLE t (id int);"},
        ["| `V1__a.sql` |"], False))

    cases.append(run_case(
        "重复登记 → 红",
        {"V1__a.sql": "DROP TABLE t;"},
        ["| `V1__a.sql` | a |", "| `V1__a.sql` | b |"], False))

    cases.append(run_case(
        "缺登记区间标记 → 红",
        {"V1__a.sql": "DROP TABLE t;"},
        ["| `V1__a.sql` |"], False, omit_marks=True))

    failed = [c for c in cases if not c]
    print("自测：%d 例，%d 失败" % (len(cases), len(failed)))
    return 0 if not failed else 1


def main(argv):
    ap = argparse.ArgumentParser(description="迁移安全门禁：破坏性迁移必须在回滚手册登记")
    ap.add_argument("--self-test", action="store_true", help="用合成夹具自证")
    ap.add_argument("--migrations", default=DEFAULT_MIGRATIONS, help="迁移目录")
    ap.add_argument("--runbook", default=DEFAULT_RUNBOOK, help="回滚手册路径")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    problems, n_destructive, n_registered = check(args.migrations, args.runbook)
    if problems:
        print("迁移安全门禁：发现 %d 个问题" % len(problems))
        for p in problems:
            print("  ✗ %s" % p)
        return 1
    print("迁移安全门禁：通过（破坏性迁移 %d 个，均已登记；登记条目 %d 条）"
          % (n_destructive, n_registered))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
