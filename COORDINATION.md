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