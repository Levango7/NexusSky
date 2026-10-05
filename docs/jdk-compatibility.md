# JDK 兼容策略与升级机制

> **建立于 2026-10-06**，回应用户需求：「保证整体的兼容性、未来开发的可持续性、
> 风险的可控性、收益的有效性」。
>
> 本机环境：`E:\dev-tools\jdk17.0.20_8`（Corretto 17.0.20 LTS）、
> `E:\dev-tools\jdk26`（Temurin 26.0.2.1）、`E:\dev-tools\jdk8-64-oracle`（8u503）。

---

## 0. 结论摘要

| 问题 | 结论 |
|---|---|
| 当前能在 JDK 26 上构建吗？ | **能**。2026-10-05 实测 `mvn clean compile` 在 JDK 26 下 7 模块全部 SUCCESS |
| 需要为此改什么？ | **几乎不用改代码**——真正要修的是 `sdk-java` 的 **release 配置缺陷**（见 §2） |
| 应该升到 26 吗？ | **不**。基线保持在 **17（LTS）**；26 作为**兼容性验证目标**，不是生产基线 |
| 未来 JDK 27/28 出来怎么办？ | 靠 §4 的机制自检，不需要改代码 |

---

## 1. 四性目标如何落地

| 目标 | 落地手段 |
|---|---|
| **兼容性** | 基线 17（LTS）+ release 机制保证字节码下限；CI 增加 JDK 26 编译腿验证向上兼容 |
| **可持续性** | 用 `--release` 而非 `source/target`；JDK 升级不改代码，只改一个属性值 |
| **可控性** | `scripts/check-java-level.py` 门禁 + §5 升级清单 + 回退路径 |
| **收益有效性** | 不为"支持更多 JDK"而支持——26 只做编译验证，不引入 26 独有 API |

---

## 2. 核心缺陷修复：`sdk-java` 的 release 覆盖问题

### 2.1 问题【事实】

`sdk-java/pom.xml` 声明 `maven.compiler.source/target = 11`，
文档声称 SDK 兼容 **Java 11**，但**实测产物字节码 major=61（Java 17）**：

```
$ javap -v sdk-java/target/classes/.../SomeClass.class | grep major
major version: 61        # = Java 17，不是声明的 11
```

**根因【已复现确认】**：父 pom 定义 `maven.compiler.release=17` 属性。
Maven Compiler Plugin 把该属性当作 `<release>` 默认值读取，而

```
<release>（插件配置 / maven.compiler.release 属性）
    >  <source>/<target>（maven.compiler.source/target 属性）
```

**`release` 一旦存在就压制 source/target**，无论它来自本模块还是父 pom 继承。

2026-10-06 用 git HEAD 原始 pom 精确复现：

```
$ git show HEAD:sdk-java/pom.xml > sdk-java/pom.xml   # 只有 source/target=11
$ mvn -B -pl sdk-java clean compile
[INFO] Compiling 6 source files with javac [debug release 17] to target\classes
                                          ^^^^^^^^^^^^ 生效的是 release 17
$ javap -v sdk-java/target/classes/.../ApiResponse.class | grep major
major version: 61        # = Java 17，不是声明的 11
```

注意两点：
1. 构建**不打印任何警告**——Maven 只说 `release 17`，完全不提 source/target=11 被忽略。
2. `mvn help:effective-pom` 里 source/target 仍显示为 11，**看起来是对的**。
   这是"配置层看起来对、产物层实际错"的典型，任何只读配置的检查都抓不到。

**后果**：客户按文档在 JDK 11 上集成，编译期即
`UnsupportedClassVersionError: class file has wrong version 61.0, should be 55.0`。

### 2.2 修复【已实施】

`sdk-java/pom.xml` 改为：

```xml
<properties>
    <maven.compiler.release>11</maven.compiler.release>
    <!-- source/target 保留，作为读取工具（IDE/旧插件）的兜底提示 -->
    <maven.compiler.source>11</maven.compiler.source>
    <maven.compiler.target>11</maven.compiler.target>
</properties>

<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>${compiler-plugin.version}</version>
    <configuration>
        <release>${maven.compiler.release}</release>   <!-- 用 release，不用 source/target -->
    </configuration>
</plugin>
```

### 2.3 为什么 `release` 才是对的机制

| 机制 | 约束语言级别 | 约束可用 API | 能保证向下兼容 |
|---|---|---|---|
| `<source>/<target>` | ✅ | ❌ | **否**——可调高版本 API 而编译通过 |
| `<release>` | ✅ | ✅（`ct.sym`） | **是** |

> 例：`source/target=11` 下，代码里写 `List.of()`（Java 9+）没问题，
> 但写 `Stream.toList()`（Java 16+）**也能编译通过**，然后在 JDK 11 上
> `NoSuchMethodError`。`--release 11` 会在编译期就报"该 API 在 11 不可用"。

#### 2.3.1 阳性对照：验证 `release 11` 真能拦住高版本 API【实测】

2026-10-06 向 `NexusSkyClient.java` 注入一个调用 Java 16+ API 的探针方法：

```java
static java.util.List<String> __probe() {
    return java.util.stream.Stream.of("x").toList();   // Stream.toList 是 Java 16+
}
```

编译结果：

```
[INFO] Compiling 6 source files with javac [debug parameters release 11] to target\classes
[ERROR] NexusSkyClient.java:[37,85] 找不到符号
  符号:   方法 toList()
  位置:  接口 java.util.stream.Stream<java.lang.String>
[INFO] BUILD FAILURE
```

**结论**：`release 11` 确实在编译期阻断了 Java 16+ API。这不只是"字节码版本号对了"，
而是**真的不能调用 11 以上的 API**——这正是声明"兼容 Java 11"所必须的保证。

（对照：若用 `source/target=11`，这段代码**会编译通过**，产出的 jar 在 JDK 11 上
运行时报 `NoSuchMethodError`。这是 `source/target` 的致命盲区。）

> 探针已在验证后移除，源码恢复原状（`git diff` 确认无残留）。

---

## 3. 模块 Java 级别矩阵

| 模块 | release | 理由 |
|---|---|---|
| `mavlink-core` | **17** | 协议栈，内部服务，无外部客户 |
| `drone-sim` | **17** | 模拟器，内部 |
| `link-sim` | **17** | 模拟器，内部 |
| `cloud-backend` | **17** | Spring Boot 3.x 要求 17+ |
| `regulator-sim` | **17** | 模拟器，内部 |
| `sdk-java` | **11** | **对外交付 SDK**，需覆盖仍用 JDK 11 的政企环境 |

> **判断**：SDK 落后基线是**有意设计**，不是疏漏。政府/国企客户大量环境仍在
> JDK 8/11，SDK 若能覆盖 11 就显著扩大可交付面。代价是该模块不得使用
> 12+ 语言特性（`record`、文本块、`switch` 表达式等）——由 `--release 11` 强制。

---

## 4. 未来 JDK 升级机制（可持续性）

### 4.1 升级路径（只改一处）

当需要把基线从 17 升到 21/25/… 时：

```bash
# 1. 改根 pom 一个属性
#    <maven.compiler.release>17</maven.compiler.release>  →  21

# 2. 全量构建 + 测试
mvn -B clean install

# 3. 门禁自检（会核对字节码版本是否真的变了）
python scripts/check-java-level.py

# 4. 若 sdk-java 要跟着升，单独改它的 release（它有自己的属性）
```

**改一处属性即可**——这是 §1 "可持续性"的具体体现。

### 4.2 门禁：`scripts/check-java-level.py`

CI 中执行，四查：

1. **字节码 vs 声明一致性**：读各模块 `target/classes` 首个 class 的 `major version`，
   与 pom 声明的**有效级别**（release 优先，其次 source/target）比对。不一致即红。
2. **产物新鲜度**：class 文件若早于 `pom.xml` 修改时间即报错——否则"一致性"
   结论建立在陈旧产物上，是假绿。
3. **release/source 混用告警**：本模块/父 pom 的 release 若与本模块的
   source/target 不一致，报警——source/target 是无效死配置。
4. **级别清单打印**：全仓 Java 级别一览，并标注每项来源（本模块显式 / 继承根 pom）。

```bash
python scripts/check-java-level.py          # 完整检查（需先编译）
python scripts/check-java-level.py --list   # 只列声明级别
```

#### 4.2.1 门禁有效性验证（阴性对照）

**门禁的价值不在"全绿时通过"，而在"真错时变红"。** 2026-10-06 用 5 组对照实测：

| # | 输入 | 期望 | 实测 | 说明 |
|---|---|---|---|---|
| 1 | 修复态（release 11） | pass | ✅ pass | 基线正确 |
| 2 | 插件改 source/target，但**属性层 release=11 仍在** | — | pass | 揭示：属性层 release 才是决定项 |
| 3 | 误以为的"缺陷态"（实为 #2） | fail | pass | **暴露我的对照设计错误** |
| 4 | **git HEAD 原始 pom**（无 release 属性） | fail | ✅ **fail + 精确定位** | 门禁对真实缺陷有效 |
| 5 | 陈旧产物（pom 改动后未重编译） | fail | ✅ fail | 新鲜度检查有效 |

对照 4 的门禁输出：

```
sdk-java  17  maven.compiler.release（继承根 pom） ⚠ 并存死配置: maven.compiler.source=11, plugin <source>=11
...
!  sdk-java  同时存在 release 与 source/target 且不一致 —— release 会静默覆盖后者
```

> **教训记录**：对照 2/3 的失败源于我最初的错误假设——以为"移除插件的
> `<release>` 就复现缺陷"。实际上 `sdk-java` 属性层的
> `maven.compiler.release=11` 优先级同样高于 source/target，所以必须**连属性一起删**
> 才是原始态。这个误判本身证明了门禁的必要性：**配置层的改动与产物层的表现
> 之间隔着一层插件解析逻辑，肉眼核对不可靠**。

### 4.3 CI 矩阵策略

| 腿 | JDK | 目的 |
|---|---|---|
| **主构建**（现有全部 job） | **17** | 生产基线，必须全绿 |
| **向上兼容验证**（新增） | **26** | 只跑编译；验证未来 JDK 不会破坏构建 |

> **为什么不把 26 设为主基线**：26 是**非 LTS**，2027-03 即 EOL。
> 生产依赖非 LTS 是风险，不是收益。26 的价值在于**提前发现**"未来 JDK 会
> 移除某个 API"——它是预警器，不是运行目标。

---

## 5. 升级风险清单（可控性）

### 5.1 已知的 JDK 17 → 21+ 破坏性变更（对本仓有影响的）

| 变更 | 影响本仓？ | 依据 |
|---|---|---|
| `SecurityManager` 移除（JDK 24 起 JDK 17 `@Deprecated(forRemoval=true)`） | 需核查 | 本仓未见使用，待 `jdeps` 确认 |
| 强封装 JDK 内部 API（JEP 403，17 起） | 否 | 构建已通过，无 `--add-opens` |
| 字符串模板移除（21 预览 → 23 撤回） | 否 | 本仓未用 |
| `sun.misc.Unsafe` 内存访问方法废弃（23+） | 需核查 | 传递依赖（Netty/Spring）可能用，由上游版本决定 |

### 5.2 依赖对高版本 JDK 的兼容性【实测】

2026-10-05 在 **JDK 26** 下 `mvn clean compile` → **7 模块全部 SUCCESS**，
证明当前依赖链（Maven 3.9.12 / Spring Boot 3.5.16 / Jackson 2.21.7 /
Jacoco 0.8.12）在 JDK 26 上**可编译**。

**未验证**：JDK 26 下**运行**（测试/启动）。编译通过 ≠ 运行通过，
字节码增强类工具（JaCoCo agent、Mockito inline mock）在 JDK 26 上可能失败。
故 §4.3 的 26 腿**只跑 compile，不跑 test**，这是刻意的最小验证集。

---

## 6. 本机多 JDK 使用方式

本机有三个 JDK，按需切换（不要改系统 `JAVA_HOME` 去"统一"）：

```bash
# Java 17（默认基线）
export JAVA_HOME=/e/dev-tools/jdk17.0.20_8

# Java 26（向上兼容验证）
export JAVA_HOME=/e/dev-tools/jdk26

# Java 8（旧客户环境验证，需另装 Maven 或用 -Dmaven.compiler.* 覆盖）
export JAVA_HOME=/e/dev-tools/jdk8-64-oracle
```

> **注意**：Spring Boot 3.x **要求 JDK 17+**，故 `cloud-backend` 在 JDK 8 上
> **无法构建**。JDK 8 只能用于验证 `sdk-java` 的向下兼容（这正是 `release 11`
> 的用途——若客户环境是 8，则 release 需降到 8，`sdk-java` 代码须进一步降级）。

### 6.1 建议：用 `.mvn/jvm.config` 固化基线（避免"我本机跑得好）

**待办**（未实施，因会影响所有开发者）：在仓库加 `.mvn/maven.config`：

```
# 强制基线 JDK，防止有人用 JDK 8 构建 cloud-backend 得到误导性失败
```

> **判断**：本项**不实施**。原因：`.mvn/maven.config` 不能校验 JDK 版本
> （只能传 Maven 参数），而强行指定会在多 JDK 切换场景下造成困扰。
> 更合适的做法是 §4.2 的门禁在 CI 里兜底，本地靠文档说明。

---

## 7. 验收标准（收益有效性）

| 项 | 达成标志 | 实证状态（2026-10-06） |
|---|---|---|
| 兼容性 | JDK 17 全量构建+测试绿；JDK 26 编译绿 | ✅ JDK 26 编译 7 模块 SUCCESS；JDK 17 全量测试见 §7.1 |
| 可持续性 | 升 JDK 只改 1 个属性；门禁自动核对字节码 | ✅ 门禁就位（`check-java-level.py` + CI `java-level` job） |
| 可控性 | 每次构建都有门禁核对；升级风险清单有据可查 | ✅ 5 组阴性对照证明门禁真能抓错（§4.2.1） |
| 收益有效性 | SDK 真正兼容 11（字节码 major=55 实测） | ✅ `javap -v` 实测 major=55，构建日志 `release 11` |

### 7.1 实测数据汇总

| 验证项 | 命令 | 结果 |
|---|---|---|
| JDK 26 向上兼容 | `JAVA_HOME=jdk26 mvn -B clean compile -DskipTests` | 7 模块 BUILD SUCCESS |
| sdk-java 字节码 | `javap -v sdk-java/target/classes/.../ApiResponse.class` | `major version: 55`（Java 11）|
| 构建日志 | `mvn -pl sdk-java clean compile` | `javac [debug parameters release 11]` |
| 门禁正常态 | `python scripts/check-java-level.py` | 6 模块全 ok，退出码 0 |
| 门禁缺陷态（git HEAD pom） | 同上 | sdk-java 报"并存死配置"，退出码 1 |
| License 档位测试 | `mvn -pl cloud-backend -am test -Dtest=LicenseTierTest` | 20 用例 0 失败 |

> **未验证项（诚实标注）**：JDK 26 下的**运行**（测试/启动）未验证。
> 编译通过 ≠ 运行通过；JaCoCo agent、Mockito inline mock 等字节码增强工具
> 在新 JDK 上可能失败。故 CI 的 26 腿只跑 compile，这是刻意的最小验证集。
