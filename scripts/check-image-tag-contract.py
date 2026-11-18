#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""镜像 tag 契约门禁：确保「发布产物」与「部署清单」说的是同一套标识。

为什么需要这个
--------------
2026-11-18 取证：

* CI 只产出 `latest`（master push）与 `sha-<short>`（`type=sha,format=short`）；
* Helm values 默认 `imageTag: "0.1.0"`、raw k8s 硬编码 `:0.1.0`、
  生产 compose 硬编码 `:1.0.0` —— **这三个 tag 没有任何工作流产出**；
* 仓库已有 v1.0.2–v1.0.7 六个附注 tag，CI 因 `tags: ['v*']` 为它们跑完整门禁，
  但 Login/Push 两步被 master-only 守卫挡住，tag 发布从未产出镜像。

回滚的前提是「能确定回滚到哪个不可变标识」。本门禁把契约机械化为六条：

1. CI metadata 同时产出 `sha-<short>`（技术部署标识）与 `semver`（发布别名）；
2. CI 的 Login / Push 两步都覆盖 `master push` 与 `v* tag push`（PR 仍不推）；
3. Helm values 默认 `imageTag` 为空，且模板有 fail-fast 守卫拒绝空/latest/0.1.0；
4. raw k8s 三个清单不含无 CI 产出的硬编码 tag，统一用 `${IMAGE_TAG}` 显式注入；
5. 生产 compose 不含无 CI 产出的硬编码 tag；
6. rollback runbook 写明 sha 是唯一部署标识、semver 是发布别名。

用法：python scripts/check-image-tag-contract.py [--self-test]
退出码：0 契约成立；1 存在断裂。
"""
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CI_REL = ".github/workflows/ci.yml"
VALUES_REL = "deploy/helm/nexussky/values.yaml"
TMPL_REL = "deploy/helm/nexussky/templates/deployment.yaml"
RAW_RELS = [
    "deploy/k8s/cloud-backend.yaml",
    "deploy/k8s/drone-sim.yaml",
    "deploy/k8s/gcs-web.yaml",
]
COMPOSE_REL = "deploy/docker/docker-compose.yml"
RUNBOOK_REL = "docs/rollback-runbook.md"

ALL_RELS = [CI_REL, VALUES_REL, TMPL_REL, COMPOSE_REL, RUNBOOK_REL] + RAW_RELS

TAG_GUARD = "startsWith(github.ref, 'refs/tags/v')"
BANNED_TAGS = (":0.1.0", ":1.0.0", ":latest")


def read(root, rel, problems):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        problems.append("缺文件：%s" % rel)
        return ""
    return io.open(path, encoding="utf-8", errors="replace").read()


def check(root):
    problems = []

    ci = read(root, CI_REL, problems)
    # 1) CI 必须同时产出两类不可变标识
    if "type=sha,format=short" not in ci:
        problems.append("CI 未产出 sha-<short>（缺 type=sha,format=short）")
    if not re.search(r"type=semver,\s*pattern=\{\{version\}\}", ci):
        problems.append("CI 未产出 semver 发布别名（缺 type=semver,pattern={{version}}）")

    # 2) Login 与 Push 两步**各自**都要放行 v* tag push（PR 仍被 event_name 挡住）
    #    不能只数全文出现次数：metadata 的 semver enable 也含同一表达式，
    #    只数总数会把「Push 步骤漏掉守卫」误判成合规。
    for step in ("Log in to GitHub Container Registry", "Push ${{ matrix.module }}"):
        idx = ci.find("name: " + step)
        if idx < 0:
            problems.append("CI 找不到步骤 %s" % step)
            continue
        tail = ci[idx:]
        nxt = tail.find("\n      - name:", 10)
        block = tail if nxt < 0 else tail[:nxt]
        if TAG_GUARD not in block:
            problems.append("CI 步骤 %s 未放行 v* tag push" % step)

    # 3) Helm：默认值为空 + 模板 fail-fast
    values = read(root, VALUES_REL, problems)
    m = re.search(r"^imageTag:\s*(.*)$", values, re.M)
    if not m:
        problems.append("values.yaml 缺 imageTag 键")
    else:
        raw = m.group(1).strip()
        if raw not in ("", '""', "''"):
            problems.append(
                "values.yaml 的 imageTag 默认值是 %s——应为空以强制显式注入" % raw
            )
    tmpl = read(root, TMPL_REL, problems)
    if "fail" not in tmpl or "imageTag" not in tmpl:
        problems.append("Helm 模板缺 imageTag 的 fail-fast 守卫")
    for banned in ('"latest"', '"0.1.0"'):
        if banned not in tmpl:
            problems.append("Helm 守卫未拒绝 %s" % banned)

    # 4) raw k8s：不得硬编码无 CI 产出的 tag，必须显式占位
    for rel in RAW_RELS:
        text = read(root, rel, problems)
        for bad in BANNED_TAGS:
            if bad in text:
                problems.append("%s 含无 CI 产出的硬编码 tag %s" % (rel, bad))
        if "${IMAGE_TAG}" not in text:
            problems.append("%s 未使用 ${IMAGE_TAG} 显式注入" % rel)

    # 5) 生产 compose
    compose = read(root, COMPOSE_REL, problems)
    for bad in BANNED_TAGS:
        if bad in compose:
            problems.append("%s 含无 CI 产出的硬编码 tag %s" % (COMPOSE_REL, bad))

    # 6) runbook 口径
    rb = read(root, RUNBOOK_REL, problems)
    if "sha-<short>" not in rb:
        problems.append("runbook 未写明 sha-<short> 是部署标识")
    if "semver" not in rb and "1.0.7" not in rb:
        problems.append("runbook 未写明 semver 发布别名")

    return problems


def self_test():
    """复制真实仓到临时目录，逐项破坏后确认门禁确实会红。"""
    cases = []

    def fresh(td):
        for rel in ALL_RELS:
            src = os.path.join(ROOT, rel)
            dst = os.path.join(td, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)

    def mutate(td, rel, old, new, replace_all=False):
        p = os.path.join(td, rel)
        s = io.open(p, encoding="utf-8", newline="").read()
        if s.count(old) < 1:
            raise AssertionError("自测探针在 %s 中找不到 %r" % (rel, old))
        count = -1 if replace_all else 1
        io.open(p, "w", encoding="utf-8", newline="").write(s.replace(old, new, count))

    with tempfile.TemporaryDirectory() as td:
        fresh(td)
        p = check(td)
        cases.append(("合规仓库应绿", not p, p))

        fresh(td)
        mutate(td, CI_REL, "type=semver,pattern={{version}}", "type=raw,value=legacy")
        p = check(td)
        cases.append(("删 semver 规则应红", any("semver" in x for x in p), p))

        fresh(td)
        mutate(td, CI_REL, TAG_GUARD, "false", replace_all=True)
        p = check(td)
        cases.append(("Login/Push 守卫不覆盖 tag 应红",
                      any("未放行 v* tag push" in x for x in p), p))

        fresh(td)
        mutate(td, VALUES_REL, 'imageTag: ""', 'imageTag: "0.1.0"')
        p = check(td)
        cases.append(("values 回填 0.1.0 应红", any("imageTag 默认值" in x for x in p), p))

        fresh(td)
        mutate(td, RAW_RELS[0], "image: nexussky/cloud-backend:${IMAGE_TAG}",
               "image: nexussky/cloud-backend:0.1.0")
        p = check(td)
        cases.append(("raw k8s 回填硬编码 tag 应红",
                      any("硬编码 tag" in x for x in p), p))

        fresh(td)
        mutate(td, COMPOSE_REL, "image: nexussky/gcs-web:${IMAGE_TAG:-local}",
               "image: nexussky/gcs-web:1.0.0")
        p = check(td)
        cases.append(("compose 回填硬编码 tag 应红",
                      any("硬编码 tag" in x for x in p), p))

        fresh(td)
        mutate(td, RUNBOOK_REL, "sha-<short>", "最近镜像", replace_all=True)
        p = check(td)
        cases.append(("runbook 删 sha 口径应红", any("sha-<short>" in x for x in p), p))

    failed = 0
    for name, ok, detail in cases:
        print(("PASS  " if ok else "FAIL  ") + name)
        if not ok:
            failed += 1
            for d in detail:
                print("        " + d)
    print("self-test: %d/%d passed" % (len(cases) - failed, len(cases)))
    return 1 if failed else 0


def main():
    if "--self-test" in sys.argv:
        return self_test()
    problems = check(ROOT)
    if problems:
        print("✗ 镜像 tag 契约断裂：")
        for p in problems:
            print("  - " + p)
        return 1
    print("✓ 镜像 tag 契约成立：sha / semver 双产物，部署清单无假 tag，回滚目标可确定")
    return 0


if __name__ == "__main__":
    sys.exit(main())
