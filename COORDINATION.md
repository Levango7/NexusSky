# NexusSky 多 agent 协作说明

> 本仓库近期可能由多个 AI agent 并行操作（ZCode CLI、DevEco Code CLI 等）。
> 这份文档是它们之间的**共享事实源**，目的是避免两方在同一个仓库上互相覆盖。

## 一、分工现状（2026-10-04）

| Agent | 工作目录 | 任务范围 | 与本仓库的文件关系 |
|---|---|---|---|
| **ZCode CLI**（本会话所属） | `F:\Nexus\NexusSky` | 后端/前端/CI 的工程收口：e2e-cv-eval 验证、前端测试分支合并、Playwright E2E（CI7）、WS 握手 NPE 修复 | 直接在仓库内读写（正常开发） |
| **DevEco Code CLI** | `F:\Agent\deveco\workspace\MyApp` | 鸿蒙（HarmonyOS）可视化界面 + 设备模拟器联调 | **不碰本仓库**；其 DevEco 授权路径仅 `F:/Agent`，对本仓库只有只读评估行为 |

结论：**当前两方无文件级重叠，不存在冲突面。**

## 二、硬性隔离规则

1. **工作目录隔离**：DevEco 系的一切产出落在 `F:\Agent\deveco\workspace\`；本仓库的代码改动只在 `F:\Nexus\NexusSky\`。任何一方需要跨目录写文件前，先在下方「沟通记录」里登记。
2. **禁止 git 操作冲突**：
   - 提交前先 `git status` 确认工作区里**没有别人的未提交改动**；若发现他人改动，不要 `git add -A`，只 `git add` 自己碰过的文件。
   - 推 `master` 前先 `git fetch origin`，若 `origin/master` 已前进（他人已推），先合并再推，不要强推。
3. **分支隔离**：并行任务各自开特性分支（如 `feat/<topic>`），完成后再合 master，避免两个 agent 同时在 master 上提交。
4. **端口隔离**：本机 8080 已被常驻 Docker 容器（opsmesh-controlplane）占用，**不是本仓库的服务，不要去 kill 它**。本项目 e2e 用 18099（后端）/ 14542（MAVLink）/ 4273（GCS preview）。
   - 另注意 **UDP 14550**：多个 worktree 同时跑 `mvn clean test` 时，设了固定端口的 Spring 上下文会互相撞（`BindException: Address already in use`，表现为**十几个测试连环报错**）。这不是代码缺陷，是并发环境噪声；跑不动时先看看是不是有别的 agent 在构建，而不是去改测试。仓内 `SprayTaskPersistenceTest:15` 已注释说明该坑与规避方式（`aerofleet.udp-port=0`）。
5. **禁止把 merge 冲突标记提交进仓库**（详见第五节）：提交前必须确认 `<<<<<<<` / `>>>>>>>` 一个都不剩。

## 三、沟通机制

两方 agent 无法直接发消息（各自 CLI 独立），用**共享文件**互告，约定：

- 文件位置：`F:\Nexus\NexusSky\.coord\`（该目录已 gitignore，不进版本库）
- 投递方式：把消息追加到 `.coord\INBOX-<agent名>.md`，并在 `.coord\STATUS.md` 更新自己的状态行
- 读取方式：开工前与提交前各读一次 `.coord\STATUS.md`；有需要对方知晓的事就投递 INBOX
- 消息格式：一段话讲清「我动了什么 / 我占了哪些文件或端口 / 我接下来要动什么」

### 当前状态（STATUS.md 内容应与此处一致）

- ZCode：空闲，master 已同步（`53a1bfd`），下一步候选 ROADMAP F2/F4
- DevEco：鸿蒙界面 + 双模拟器（P90 Pro Max / Mate X5）联调中，不触碰本仓库

## 四、历史踩坑（给后来者）

- 多个 e2e 脚本硬编码 8080，在本机会撞常驻容器；用环境变量覆盖（`AF_BACKEND_PORT` / `AF_GCS_PORT` 等）。
- JDK17 需显式指定（`AF_JAVA` 或 `JAVA_HOME`），系统默认是 JDK8，跑 jar 会报 `class file 52.0`。
- GitHub 推送走 SSH 443：`git push ssh://git@ssh.github.com:443/Levango7/NexusSky.git master`（origin 被全局 insteadOf 重写到只读 gh-proxy）。

## 五、不要把 merge 冲突标记提交进仓库

**为什么单列一节**：这个错误的后果不是"文档难看"，而是让 CI 的计数门禁**静默失效**——
你以为它在核对，其实它一个数字都没核。

### 事故形态（2026-10-08 实际发生）

多份口径文档（`README.md` / `ROADMAP.md` / `docs/*.md`）带着未解决的
`<<<<<<< HEAD … ======= … >>>>>>> origin/master`。这些标记的来源是历次合并时
**JSX 冲突被修掉了、markdown 冲突被漏掉**，然后被后续提交一路继承：

| 提交 | 冲突标记数 |
|---|---|
| `ddd444a` | 42 处 |
| `a36c55c` | 42 处 |
| `ec4ddb5` | 42 处 |
| `e3304eb` | 42 处 |

**每次合入都重新出现，所以这是流程问题**——只清理一次治不了本，必须有门禁兜底。

### 后果链条

冲突块恰好吞掉各文档的测试规模表 → `test_table_rows()` 认不出表格行 →
逐格核对被**静默跳过** → 门禁打印"全部一致"。

而在 `ddd444a` 上实测：未加固的门禁报"全部一致"，加固后的门禁当场拒绝并说清后果。
**一个"匹配不到就不管"的校验器不是门禁，是装饰。**

### 硬规则

1. **提交前必查冲突标记**（一条命令，别靠肉眼）：
   ```bash
   git grep -nE '^(<<<<<<< |>>>>>>> )' -- . | head
   ```
   有输出就别提交。
2. **`git commit -a` / `git add -A` 前先想清楚**：冲突文件往往会连带别人的改动一起进暂存区。
3. **解决 markdown 冲突时两侧都要看**：相同侧去重即可；
   **两侧不同通常是两条不同内容，都要保留**——例如 README 里 F4「缺陷闭环」与
   F2「机巢管控」是两条独立 bullet，丢掉任一边就是丢交付说明。
4. **门禁自己也要被证明有效**：`python scripts/check-test-count-docs.py --self-test`
   用离线夹具验证「干净文档不误伤 / 数字漂移仍红 / 冲突标记必红」。
   改动门禁后务必跑一次，别让防线自己也退化成装饰。

### 已经装好的防线（2026-10-09 起生效）

- `scripts/check-test-count-docs.py`：命中冲突标记即返回非零，并说明**哪些行没被核对**
- CI `Docs test-count gate` 在下载产物**之前**先跑 `--self-test`，坏了最早暴露
- 维修方：改文档时顺手清掉历史标记（相同侧去重、不同侧全留），并跑一次 `--self-test`