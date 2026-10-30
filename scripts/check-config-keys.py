#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""门禁：`application*.properties` 里声明的每个 `aerofleet.*` 键，代码必须真的读它。

为什么需要它
-----------
2026-10-30 实测（审计 §11.2）：`application.properties` 声明了 8 个
`aerofleet.plugins.{category}.{type}.enabled`，唯一的读者是 `spi/PluginConfig`，
而 **PluginConfig 在全仓零注入点**（main/test 都没有引用它的文件），四个插件接口
`PayloadPlugin` / `TransportPlugin` / `RoutePlannerPlugin` / `DecisionStrategyPlugin`
**没有任何实现类**，`PluginRegistry` 构造时拿到空列表。

⇒ 运维把 `aerofleet.plugins.payload.spray.enabled=false` 改掉，**没有任何东西会红**，
但他以为自己关掉了一个模块。这正是审计 §4.2 里 `aerofleet.geofence.intercept.enabled`
那条"假开关"的同一形状，只是这次藏在"动态前缀"的幌子下面。
（后续：`spi/` 那 13 个文件的框架本体已按 owner 裁决删除，接口形状留在
`docs/architecture-deepening-plan.md` §2.3 —— 见审计 §12。上面这段是当初的发现过程，
留着是为了说明这条门禁在防的是哪个形状，不是描述现在的代码。）

`check-doc-config-keys.py` 管的是**反方向**（文档承诺的键代码认不认）。两条合起来
才是双向对账：文档写了代码要有，代码不读的就别在配置里躺着。

口径（刻意写死，避免"换个算法数出来不一样"）
-------------------------------------------
1. **只看 `src/main/resources`** 下的 `application*.properties` 与 `*.yml`（含 profile 覆盖文件）——
   `src/test/resources` 里的键是测试夹具，不该被当成对外可调面。
2. 一个键算"被代码读取"，必须命中下面之一（与 `check-doc-config-keys.py` 同一套形态）：
   a. `application*.properties` 里同文件之外另有定义？不算——声明不是读取；
   b. `@Value("${键…}")`；
   c. `@ConditionalOnProperty(name = "键")` 或 `(prefix = "p", name = "n")` 拼出的全名；
   d. `environment.getProperty("键")` / `containsProperty("键")`；
   e. 落在某个 `@ConfigurationProperties(prefix = "p")` 类里同名字段（Spring 宽松绑定：
      字段 `sinkType` ⇔ 键 `p.sink-type`）；
   f. **动态前缀**：某个类里有 `getPropertyNamesByPrefix("p.")` /
      `"aerofleet.plugins."` 这类前缀常量 ⇒ 该前缀下的键算被读取，
      **但前提是那个读者自己可达**（见下）。
3. **动态前缀的读者必须可达**：声明该前缀常量的类，除自身文件外在 main/test 里至少有
   一处引用；否则前缀不成立，该前缀下的键全部按"没人读"判红。
   这条是本门禁存在的直接原因——旧白名单写的是"PluginConfig 会动态枚举"，
   而 PluginConfig 从来没被注入过，白名单的前提没人核。
4. 键名比对大小写不敏感、`-`/`_`/`.` 归一（Spring relaxed binding 的口径），
   所以 `sink-type` 与 `sinkType` 视为同一个键。
5. `EXEMPT` 是豁免账：每条必须带**理由 + 到期日**；到期即红（棘轮只准拧紧）。
   当前账：**空**——8 个插件假开关已随本批删除，原位留了注释指向审计 §11.2；
   同批之后 `spi/` 框架本体（13 个文件）也按 owner 裁决删除，见审计 §12。

用法：
    python scripts/check-config-keys.py
    python scripts/check-config-keys.py --self-test
退出码：0 全部有读者；1 有"声明但没人读"的键或豁免到期；2 找不到任何配置文件（不许
把"没查到"冒充"没问题"）。
"""
import io
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = ["mavlink-core", "drone-sim", "link-sim", "cloud-backend", "regulator-sim", "sdk-java"]

# 豁免账：键（归一后） -> (理由, 到期日 YYYY-MM-DD)。棘轮只准拧紧，到期即红。
EXEMPT = {}

# 动态前缀不再维护静态表：声称"按前缀枚举"的类由代码现场扫出（见 collect_readers），
# 可达性也现算。原先这里写死 {"aerofleet.plugins.": "PluginConfig"}，而 PluginConfig 与
# 整个 spi/ 框架已在 2026-10-30 的 owner 裁决中删除（审计 §12）——静态表本身就是又一处
# "文档说有人读、代码里没人读"的漂移面，删掉它改由代码说话。

PROP_KEY = re.compile(r"^\s*([A-Za-z0-9_.\-]+)\s*[:=]")
VALUE_READ = re.compile(r'"\$\{([A-Za-z0-9_.\-]+)(?::[^}]*)?\}"')
COND_BLOCK = re.compile(r"@ConditionalOnProperty\(([^)]*)\)", re.S)
COND_NAME = re.compile(r'(?:name|value)\s*=\s*\{?\s*"([^"]+)"')
COND_PREFIX = re.compile(r'prefix\s*=\s*"([^"]+)"')
CP_BLOCK = re.compile(r'@ConfigurationProperties\(\s*(?:prefix\s*=\s*)?"([^"]+)"')
ENV_READ = re.compile(r'(?:getProperty|containsProperty)\(\s*"([^"]+)"')
PRIVATE_FIELD = re.compile(r'^\s*private\s+(?!static\b)(?!final\b)[\w<>,.\s]+?\s+(\w+)\s*[;=]', re.M)


def norm(k):
    """Spring relaxed binding 口径的键名归一。"""
    return k.strip().lower().replace("_", "-")


def collapse(k):
    """比对用：再去掉 `-`（`sink-type` 与 `sinkType` 归一后同为 sinktype）。"""
    return norm(k).replace("-", "")


def camel_to_kebab(name):
    return re.sub(r"(?<!^)(?=[A-Z])", "-", name).lower()


def java_files(mod, sub):
    d = os.path.join(ROOT, mod, "src", sub, "java")
    if not os.path.isdir(d):
        return
    for dp, _, fns in os.walk(d):
        for fn in fns:
            if fn.endswith(".java"):
                yield os.path.join(dp, fn)


def read_configs():
    """返回 [(rel_path, key)]，只含 src/main/resources 下声明的 aerofleet.* 键。"""
    out = []
    found_any = False
    for mod in MODULES:
        d = os.path.join(ROOT, mod, "src", "main", "resources")
        if not os.path.isdir(d):
            continue
        for dp, _, fns in os.walk(d):
            for fn in sorted(fns):
                if not fn.endswith((".properties", ".yml", ".yaml")):
                    continue
                p = os.path.join(dp, fn)
                found_any = True
                text = io.open(p, encoding="utf-8", errors="replace").read()
                rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
                if fn.endswith(".properties"):
                    for line in text.split("\n"):
                        if line.lstrip().startswith("#"):
                            continue
                        mo = PROP_KEY.match(line)
                        if mo and mo.group(1).startswith("aerofleet"):
                            out.append((rel, mo.group(1)))
                else:
                    stack = []
                    for line in text.split("\n"):
                        if not line.strip() or line.lstrip().startswith("#"):
                            continue
                        mo = re.match(r"^(\s*)([\w.\-]+)\s*:", line)
                        if not mo:
                            continue
                        ind = len(mo.group(1))
                        key = mo.group(2)
                        while stack and stack[-1][0] >= ind:
                            stack.pop()
                        stack.append((ind, key))
                        full = ".".join([s[1] for s in stack])
                        if full.startswith("aerofleet"):
                            out.append((rel, full))
    return out, found_any


def collect_readers():
    """返回 (精确读取的归一键集合, 前缀绑定展开的键, 前缀集合, 可达的动态前缀,
    诊断信息, 全部被声称的动态前缀)。"""
    exact = set()
    prefixes = set()
    bound = set()          # @ConfigurationProperties 前缀（该前缀下任意键都算被绑定读取）
    field_keys = set()     # 前缀 + 字段名展开出的具体键
    claims = {}            # 前缀 -> 声称的读者类
    diag = []

    for mod in MODULES:
        for sub in ("main", "test"):
            for p in java_files(mod, sub):
                text = io.open(p, encoding="utf-8", errors="replace").read()
                cls = os.path.basename(p)[:-5]
                for mo in VALUE_READ.finditer(text):
                    if mo.group(1).startswith("aerofleet"):
                        exact.add(collapse(mo.group(1)))
                for mo in ENV_READ.finditer(text):
                    if mo.group(1).startswith("aerofleet"):
                        exact.add(collapse(mo.group(1)))
                for mo in COND_BLOCK.finditer(text):
                    body = mo.group(1)
                    pre = COND_PREFIX.search(body)
                    for nm in COND_NAME.findall(body):
                        full = (pre.group(1) + "." + nm) if pre and "." not in nm else nm
                        if full.startswith("aerofleet"):
                            exact.add(collapse(full))
                for mo in CP_BLOCK.finditer(text):
                    pre = mo.group(1)
                    if not pre.startswith("aerofleet"):
                        continue
                    prefixes.add(pre)
                    for fm in PRIVATE_FIELD.finditer(text):
                        field_keys.add(collapse(pre + "." + camel_to_kebab(fm.group(1))))
                # 动态前缀的"声称"：文件里出现以 aerofleet 开头且以 . 结尾的字符串常量
                for mo in re.finditer(r'"(aerofleet[\w.\-]*\.)"', text):
                    claims.setdefault(mo.group(1), set()).add(cls)

    # 动态前缀成立与否，取决于声称的读者类是否可达（除自身文件外有引用）
    live_dyn = set()
    for pre, classes in claims.items():
        reachable = False
        for c in classes:
            for mod in MODULES:
                for sub in ("main", "test"):
                    for p in java_files(mod, sub):
                        if os.path.basename(p)[:-5] == c:
                            continue
                        t = io.open(p, encoding="utf-8", errors="replace").read()
                        if re.search(r"\b" + re.escape(c) + r"\b", t):
                            reachable = True
                            break
                    if reachable:
                        break
                if reachable:
                    break
            if reachable:
                break
        if reachable:
            live_dyn.add(pre)
        else:
            diag.append("动态前缀 %s 的读者 %s 在全仓不可达 ⇒ 该前缀不按『被读取』算"
                        % (pre, "/".join(sorted(classes))))
    return exact, field_keys, prefixes, live_dyn, diag, set(claims)


def is_read(key, exact, field_keys, prefixes, live_dyn):
    ck = collapse(key)
    if ck in exact or ck in field_keys:
        return True
    for pre in live_dyn:
        if key.startswith(pre):
            return True
    return False


def check(root=None):
    """返回 (问题列表, 统计 dict, 提示列表)。"""
    global ROOT
    if root:
        ROOT = root
    declared, found_any = read_configs()
    if not found_any:
        return (["找不到任何 src/main/resources 下的配置文件（不许把『没查到』冒充『没问题』）"], {}, [])
    exact, field_keys, prefixes, live_dyn, diag, claimed_dyn = collect_readers()
    problems = []
    notices = []
    unread = []
    seen = set()
    today = __import__("datetime").date.today().isoformat()
    for rel, key in declared:
        if key in seen:
            continue
        seen.add(key)
        ck = collapse(key)
        if ck in EXEMPT:
            reason, until = EXEMPT[ck]
            if until < today:
                problems.append("豁免到期：%s（理由：%s，到期 %s）——请接线或删除该键"
                                % (key, reason, until))
            continue
        if not is_read(key, exact, field_keys, prefixes, live_dyn):
            unread.append((rel, key))
    for rel, key in sorted(unread, key=lambda x: x[1]):
        problems.append("properties 声明了但代码从不读取（假开关）：%s  <- %s" % (key, rel))
    # 动态前缀的读者不可达：只在**该前缀下真的声明了键**时才算违规（否则只是提示）。
    # 真事：本批删掉 8 个 aerofleet.plugins.*.enabled 之后，PluginConfig 仍不可达——
    # 那是"框架没接线"的事实，但配置面已经不再骗人，所以这里只在还有人往该前缀下写键时
    # 才判红，不让门禁冒充它管得着的事。声称的前缀从代码现算，不用静态表。
    dyn_declared = [k for k in seen if any(k.startswith(pre) for pre in claimed_dyn)]
    for note in diag:
        if dyn_declared:
            problems.append(note)
        else:
            notices.append(note)
    stats = {"declared": len(seen), "unread": len(unread),
             "readers_exact": len(exact), "readers_bound": len(field_keys),
             "dyn_prefix_live": len(live_dyn)}
    return problems, stats, notices


def main():
    args = sys.argv[1:]
    if "--self-test" in args:
        return self_test()
    problems, stats, notices = check()
    for x in notices:
        print("  [提示] " + x)
    if problems:
        print("配置键对账门禁：发现 %d 个问题" % len(problems))
        for x in problems:
            print("  - " + x)
        return 1
    print("配置键对账门禁：通过（properties 声明的 %d 个 aerofleet.* 键全部有真实读者；"
          "读者形态：@Value/Environment %d 个 + 前缀绑定字段 %d 个）"
          % (stats["declared"], stats["readers_exact"], stats["readers_bound"]))
    return 0


def _write(path, text):
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def self_test():
    """合成夹具：不碰真仓库，逐条验证五类判据与到期棘轮。"""
    results = {}
    base = tempfile.mkdtemp(prefix="cfgkeys-")
    try:
        def fixture(extra_props="", extra_java="", res_body=""):
            root = os.path.join(base, "r%d" % len(os.listdir(base)))
            _write(os.path.join(root, "cloud-backend", "src", "main", "resources",
                                "application.properties"),
                   "aerofleet.used.thing=1\naerofleet.orphan.thing=2\n" + extra_props)
            _write(os.path.join(root, "cloud-backend", "src", "main", "java", "demo", "Cfg.java"),
                   "package demo;\n"
                   "import org.springframework.beans.factory.annotation.Value;\n"
                   "public class Cfg {\n"
                   '  public Cfg(@Value("${aerofleet.used.thing:1}") int x) {}\n'
                   + extra_java + "}\n")
            _write(os.path.join(root, "cloud-backend", "src", "main", "java", "demo",
                                "Bound.java"),
                   "package demo;\n"
                   "import org.springframework.boot.context.properties.ConfigurationProperties;\n"
                   '@ConfigurationProperties(prefix = "aerofleet.bound")\n'
                   "public class Bound {\n"
                   "  private long sinkType;\n"
                   "}\n")
            _write(os.path.join(root, "cloud-backend", "src", "main", "java", "demo",
                                "Cond.java"),
                   "package demo;\n"
                   "import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;\n"
                   '@ConditionalOnProperty(prefix = "aerofleet.cond", name = "enabled")\n'
                   "public class Cond {}\n")
            _write(os.path.join(root, "cloud-backend", "src", "main", "resources",
                                "application-prod.yml"),
                   "aerofleet:\n  cond:\n    enabled: true\n" + res_body)
            return root

        # 1. 孤儿键必红，且点名
        r = fixture()
        probs, _st, _nt = check(r)
        results["孤儿键判红"] = any("aerofleet.orphan.thing" in x for x in probs)
        # 2. @Value 读取的键放行
        results["@Value 认作读者"] = not any("aerofleet.used.thing" in x for x in probs)
        # 3. @ConfigurationProperties 字段（sinkType ⇔ sink-type）认作读者
        r = fixture(extra_props="aerofleet.bound.sink-type=9\n")
        probs, _st, _nt = check(r)
        results["宽松绑定字段认作读者"] = not any("aerofleet.bound.sink-type" in x for x in probs)
        # 4. @ConditionalOnProperty(prefix,name) 拼出的全名认作读者（含 yml 声明）
        r = fixture(extra_props="aerofleet.cond.enabled=true\n")
        probs, _st, _nt = check(r)
        results["条件装配拼全名认作读者"] = not any("aerofleet.cond.enabled" in x for x in probs)
        # 5. 动态前缀：读者不可达 ⇒ 前缀下的键仍判红（本批抓到的真实形状）
        _write(os.path.join(base, "deadreach", "cloud-backend", "src", "main", "java", "demo",
                            "Enum.java"),
               "package demo;\npublic class Enum {\n"
               '  private static final String P = "aerofleet.plugins.";\n}\n')
        r2 = os.path.join(base, "deadreach")
        _write(os.path.join(r2, "cloud-backend", "src", "main", "resources",
                            "application.properties"),
               'aerofleet.plugins.payload.spray.enabled=true\n')
        global ROOT
        saved = ROOT
        ROOT = r2
        probs2, _st, _nt = check()
        ROOT = saved
        results["动态前缀读者不可达仍判红"] = any("aerofleet.plugins.payload.spray.enabled" in x
                                            for x in probs2)
        results["不可达读者给出诊断"] = any("不可达" in x for x in probs2)
        # 6. 读者可达时，动态前缀下的键放行
        _write(os.path.join(r2, "cloud-backend", "src", "main", "java", "demo",
                            "Consumer.java"),
               "package demo;\npublic class Consumer { Enum e; }\n")
        ROOT = r2
        probs3, _st, _nt = check()
        ROOT = saved
        results["动态前缀读者可达则放行"] = not any("aerofleet.plugins.payload.spray.enabled" in x
                                              for x in probs3)
        # 7. 豁免到期即红
        r = fixture()
        saved_exempt = dict(EXEMPT)
        EXEMPT.clear()
        EXEMPT["aerofleet.orphan.thing"] = ("测试理由", "2000-01-01")
        probs4, _st, _nt = check(r)
        results["豁免到期判红"] = any("豁免到期" in x for x in probs4)
        EXEMPT.clear()
        EXEMPT["aerofleet.orphan.thing"] = ("测试理由", "2999-01-01")
        probs5, _st, _nt = check(r)
        results["未到期豁免放行"] = not any("aerofleet.orphan.thing" in x for x in probs5)
        EXEMPT.clear()
        EXEMPT.update(saved_exempt)
        # 8. 找不到配置 ⇒ exit 1 而不是"通过"
        r = os.path.join(base, "empty")
        _write(os.path.join(r, "README.md"), "x\n")
        probs6, _st, _nt = check(r)
        results["无配置时报错而非假绿"] = bool(probs6)
    finally:
        shutil.rmtree(base, ignore_errors=True)
    ok = 0
    for k, v in results.items():
        print(("  [%s] " % ("OK" if v else "FAIL")) + k)
        ok += 1 if v else 0
    print("check-config-keys 自测：%d/%d 通过" % (ok, len(results)))
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
