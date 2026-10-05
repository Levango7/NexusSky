# 依赖版本管理与升级机制

> **建立于 2026-10-05**，回应用户关切：「迭代太快了，是否需要考虑兼容……万一项目
> 各种突飞猛进又迭代了上百个版本，其中还有好几个大版本。」

本文档不是"当前版本快照"——快照会过期。它是**升级方法论 + 机器可校验的机制**。

---

## 1. 为什么不用"写死版本"求稳定

**判断（非事实）**：对"迭代太快"的本能反应是到处写死版本。这在无人机飞控领域
**适得其反**：

- 写死 → 安全补丁进不来。本仓 `pom.xml` 已有 20+ 项安全覆写（CVE-2026-65182 等），
  若当初写死 Spring Boot 内部依赖版本，这些补丁无法通过 BOM 升级获得。
- 写死 → 升级时改 33 处，漏一处就**版本分裂**（同一 jar 两个版本进 classpath）。
  症状是运行期 `NoSuchMethodError`，而编译期全绿——这类故障排查成本极高。

**因此本仓的策略是：版本集中 + 门禁防漂移 + 升级演练**，而非写死。

---

## 2. 三层版本管理模型

| 层级 | 谁管 | 本仓做法 |
|---|---|---|
| **BOM 层** | `spring-boot-dependencies` 3.5.16 + `junit-bom` 5.11.4 | import 方式引入，管住数百个传递依赖 |
| **安全覆写层** | 根 `pom.xml` 的 `<dependencyManagement>` | **故意写字面量**（见 §4） |
| **业务依赖层** | 根 `pom.xml` 的 `<properties>` | 统一 `${xxx.version}`，子模块只引用 |

### 2.1 属性清单（单一真相源）

全部第三方版本集中在根 `pom.xml` `<properties>`。**新增依赖必须在此声明版本**。

| 属性 | 当前值 | 用途 |
|---|---|---|
| `spring-boot.version` | 3.5.16 | Spring Boot BOM |
| `junit.version` | 5.11.4 | JUnit BOM |
| `slf4j.version` | 2.0.16 | 日志门面 |
| `springdoc.version` | 2.8.6 | OpenAPI 文档 |
| `mavlink.version` | 3.6.0 | MAVLink 库 |
| `jacoco.version` | 0.8.12 | 覆盖率插件 |
| `surefire.version` | 3.5.2 | 测试插件 |
| `compiler-plugin.version` | 3.13.0 | 编译插件 |
| `tomcat.version` | 10.1.59 | 安全覆写（Servlet 容器） |
| `netty.version` | 4.1.138.Final | 安全覆写（网络） |
| `postgresql.version` | 42.7.13 | 安全覆写（数据库驱动） |
| `jackson.version` | 2.21.7 | 安全覆写（JSON） |
| `log4j.version` | 2.25.5 | 安全覆写（日志） |
| `commons-lang3.version` | 3.18.0 | 安全覆写（工具库） |

> 上述"安全覆写"类的属性**仅用于台账记录与脚本引用**，根 pom 的
> `dependencyManagement` 中仍写字面量——原因见 §4，这是经**探针实测**的设计，
> 不是疏漏。**不要"优化"成属性引用。**

---

## 3. 升级演练机制（核心）

### 3.1 门禁：`scripts/check-dependency-versions.py`

CI 中执行，三查：

1. **硬编码版本** — 除白名单外，任何 `<version>1.2.3</version>` 字面量报警
2. **版本分裂** — 同一 `artifactId` 解析出多个版本报警（会解析 `${xxx}` 后比较）
3. **台账同步** — 本文档记录的版本与 pom 实际解析值比对

```bash
python scripts/check-dependency-versions.py              # 静态检查
python scripts/check-dependency-versions.py --print-table # 打印版本台账
```

### 3.2 升级前的标准流程

当需要升级某个依赖（尤其是大版本）时：

```bash
# 1. 看清当前真实依赖树（不要靠 pom 字面量猜）
mvn -B dependency:tree -Dincludes=<groupId> > /tmp/before.txt

# 2. 改版本 → 全量构建（必须 -am，否则用 m2 陈旧 jar 会假失败）
mvn -B clean install -DskipTests

# 3. 全量测试（这是唯一能发现 API 断裂的手段）
mvn -B test

# 4. 依赖树对比：确认没有意外引入新版本
mvn -B dependency:tree -Dincludes=<groupId> > /tmp/after.txt
diff /tmp/before.txt /tmp/after.txt

# 5. 门禁
python scripts/check-dependency-versions.py
python scripts/check-test-count-docs.py

# 6. 安全扫描（Trivy 门禁是升级的主要触发因素）
```

### 3.3 大版本升级的风险分级

**事实依据**：本仓当前依赖中，以下组件的大版本升级**已知有破坏性**：

| 组件 | 当前 | 风险点 | 应对 |
|---|---|---|---|
| Spring Boot | 3.5.16 | 3.x → 4.x 会改 Jakarta 命名空间之外的配置属性；`spring.config` 校验更严 | 先在 `application-test.properties` 起，再推 prod |
| Spring Security | (BOM 管) | 7.x 移除 `WebSecurityConfigurerAdapter` 时代的 API、`authorizeRequests` 废弃 | 本仓用 `SecurityFilterChain` Bean，影响面集中在 `SecurityConfig.java` |
| Jackson | 2.21.7 | 3.x 改包名为 `tools.jackson`（**不是** `com.fasterxml`） | 升级需全仓 import 替换，**不建议在项目中期做** |
| Netty | 4.1.138 | 4.2/5.x 有 API 移除 | 本仓未直接用 Netty API，由 Spring 管，风险低 |
| PostgreSQL JDBC | 42.7.13 | 42.x 内小版本安全，43.x 未发布 | 低风险 |
| Java 语言级别 | 17（SDK 为 11） | 21 的虚拟线程会与现有线程池模型冲突 | 见 §5 |

---

## 4. 为什么安全覆写保留字面量（重要，勿"优化"）

根 `pom.xml` 的 `dependencyManagement` 中，安全覆写条目**故意**写 `10.1.59`
而非 `${tomcat.version}`。这有实测依据：

> **探针记录**（2026-09-30，见 pom.xml 内注释）：
> 在 `import` 方式引入 BOM 的场景下，用 `<properties>` 覆写属性**实测无效**——
> 探针把 `netty.version` 改为目标值后，Maven 仍解析出 `4.1.135.Final`。
> 因此必须写直接条目。

**推论**：如果有人把 `10.1.59` 改成 `${tomcat.version}`，安全覆写会**静默失效**
（解析回 BOM 默认值，即存在漏洞的版本），而构建、测试**全部照过**。
这是本仓最隐蔽的一类回归——所以门禁把这类条目列入 `PINNED_ALLOWLIST`，
**主动允许**字面量，防止被误改。

---

## 5. 已知版本策略分歧（已解决）

**原事实**：`sdk-java/pom.xml` 用 `maven.compiler.source/target = 11`，
而根 pom 及其余模块用 `maven.compiler.release = 17`。

**原判断（错误，已推翻）**：曾认为"用 17 编译 target 11 只能保证字节码版本"。
2026-10-06 实测发现更严重：当时 `sdk-java` 的 `source/target=11` **完全未生效**——
父 pom 的 `maven.compiler.release=17` 属性被 compiler-plugin 当作 `<release>` 读取，
而 **release 压制 source/target**，故产物字节码是 **major 61（Java 17）**，
与文档声称的"兼容 Java 11"不符。

**已实施整改**（2026-10-06）：

```xml
<properties>
    <maven.compiler.release>11</maven.compiler.release>   <!-- 关键：显式 release -->
    <maven.compiler.source>11</maven.compiler.source>     <!-- 兜底提示，非决定项 -->
    <maven.compiler.target>11</maven.compiler.target>
</properties>
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <release>${maven.compiler.release}</release>
    </configuration>
</plugin>
```

**实证**：整改后 `javap -v` 实测产物 `major version: 55`（Java 11），
且构建日志输出 `javac [debug parameters release 11]`（整改前为 `release 17`）。

**机制要点**（写在这里防复发）：

| 层级 | 优先级 | 说明 |
|---|---|---|
| compiler-plugin `<release>` | 最高 | 本模块显式配置 |
| `maven.compiler.release` 属性 | 高 | **含从父 pom 继承的值**，同样压制 source/target |
| `maven.compiler.source/target` 属性 | 低 | 只要上面任一存在即被忽略 |

→ **`release` 一旦存在就压制 source/target，且来源（本模块/父 pom）不影响此规则。**

- 该模块**不得**使用 Java 12+ 语法（`switch` 表达式、`record`、文本块）——由
  `--release 11` 在编译期强制（不只是"注意"，而是构建会失败）。

**门禁**：`scripts/check-java-level.py` + CI `java-level` job 每次构建核对字节码；
另有 `java-upward-compat` job 在 JDK 26 上跑 compile 作为未来兼容预警。

**详见**：`docs/jdk-compatibility.md`（含 5 组阴性对照实证记录）。

---

## 6. 当前版本台账（机器核对点）

以下条目由 `check-dependency-versions.py` 核对。**修改依赖版本后必须同步本表**，
否则门禁红。

| artifactId | 版本 | 来源 |
|---|---|---|
| `spring-boot-dependencies` | `3.5.16` | 根 pom BOM import |
| `junit-bom` | `5.11.4` | 根 pom BOM import |
| `springdoc-openapi-starter-webmvc-ui` | `2.8.6` | `${springdoc.version}` |
| `tomcat-embed-core` | `10.1.59` | 安全覆写（字面量） |
| `tomcat-embed-el` | `10.1.59` | 安全覆写（字面量） |
| `tomcat-embed-websocket` | `10.1.59` | 安全覆写（字面量） |
| `netty-common` | `4.1.138.Final` | 安全覆写（字面量） |
| `netty-buffer` | `4.1.138.Final` | 安全覆写（字面量） |
| `netty-transport` | `4.1.138.Final` | 安全覆写（字面量） |
| `netty-codec` | `4.1.138.Final` | 安全覆写（字面量） |
| `netty-handler` | `4.1.138.Final` | 安全覆写（字面量） |
| `netty-resolver` | `4.1.138.Final` | 安全覆写（字面量） |
| `netty-transport-native-unix-common` | `4.1.138.Final` | 安全覆写（字面量） |
| `postgresql` | `42.7.13` | 安全覆写（字面量） |
| `jackson-core` | `2.21.7` | 安全覆写（字面量） |
| `jackson-databind` | `2.21.7` | 安全覆写 + `${jackson.version}` |
| `log4j-api` | `2.25.5` | 安全覆写（字面量） |
| `log4j-to-slf4j` | `2.25.5` | 安全覆写（字面量） |
| `commons-lang3` | `3.18.0` | 安全覆写（字面量） |
| `jacoco-maven-plugin` | `0.8.12` | `${jacoco.version}` |
| `maven-surefire-plugin` | `3.5.2` | `${surefire.version}` |
| `maven-compiler-plugin` | `3.13.0` | `${compiler-plugin.version}` |

---

## 7. 升级触发时机（何时该动）

| 信号 | 动作 | 紧急度 |
|---|---|---|
| **Trivy / Semgrep CI 变红** | 按 §3.2 流程升级对应组件 | 高（阻塞发布） |
| **CVE 公告命中当前版本** | 查到修复版本后升级 | 高 |
| **上游发布大版本 + 距上次升级 > 6 个月** | 评估，排期 | 中 |
| **客户环境 JDK 版本变化** | 同步 `maven.compiler.release` | 中 |
| **无以上信号** | **不动** | — |

> **反面原则**：不要在"因为担心不够新"时升级。本仓 33 项依赖中多数由 BOM 管住，
> 真正需要关注的只有 §3.3 表中的 6 个组件。

---

## 8. 与 CI 的集成

已在 `.github/workflows/ci.yml` 的 **`dep-gate` job** 中接入（2026-10-05）：

```yaml
dep-gate:
  name: Dependency Version Gate
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-python@v5
      with: { python-version: '3.12' }
    - run: python3 scripts/check-dependency-versions.py
    - run: python3 scripts/check-maven-incantations.py
```

**为什么单列一个 job**：纯静态检查、秒级完成、不依赖 Java 编译产物，能**早于**
耗时的 maven job 给出反馈。

> 教训：`scripts/check-test-count-docs.py` 早年在仓库里躺了很久却**未接入 CI**，
> 结果文档数字漂移两年无人发现（脚本自己在 docstring 里记了这段）。**有脚本 ≠ 有门禁**，
> 必须接入 CI 才算数。

