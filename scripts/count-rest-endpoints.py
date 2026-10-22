# -*- coding: utf-8 -*-
"""精确测 REST 端点数，并给出可复现的测法（供门禁与文档共用）。

口径（刻意写死，避免"换个算法数出来不一样"）：
  1. 只数 `@RestController` 标注的类（`@Controller` 视图层不算 API）。
  2. 只数类里的**方法级** `@Get/Post/Put/Delete/PatchMapping`。
  3. 排除 actuator 的类（`/actuator/**` 不是业务 API）。
"""
import io
import os
import re

# 从脚本位置推导仓库根：硬编码绝对路径会让这个计数器**只在一个 worktree 里是对的**
# （换目录/CI 上 os.walk 扫不到任何文件 ⇒ 数出 0 个端点，比没有还误导）。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "cloud-backend", "src", "main", "java")

CLASS_ANNO = re.compile(r"@RestController\b")
MAPPING = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b")


def count():
    classes = 0
    endpoints = 0
    files = 0
    for dp, dn, fn in os.walk(SRC):
        for f in fn:
            if not f.endswith(".java"):
                continue
            t = io.open(os.path.join(dp, f), encoding="utf-8", errors="replace").read()
            if not CLASS_ANNO.search(t):
                continue
            if "actuator" in f.lower():
                continue
            classes += 1
            files += 1
            endpoints += len(MAPPING.findall(t))
    return classes, endpoints, files


if __name__ == "__main__":
    c, e, f = count()
    print("  @RestController 类（排除 actuator）= %d" % c)
    print("  方法级映射注解 = %d" % e)
    print("  端点数 = %d" % e)
