# Agent 作业硬约束（把踩过的坑变成规则）

> 这份清单来自 2026-10-18 ~ 10-20 一次连续作业里**我自己**踩的坑。每条写清：
> **错在哪 → 造成什么后果 → 硬约束（可执行的句子）→ 机械化在哪（门禁/脚本）**。
>
> 原则：**光写下来没用，能让机器拦住的才算数。** 标「未机械化」的条目，
> 任何人接手都可以把它变成门禁。

## 1. 提交了带 merge 冲突标记的文件

**错在哪**：一次 `git stash pop` 撞了冲突，我没有看 `git status` 就 `git add` + commit，
CHANGELOG 里带进 3 处 `<<<<<<< HEAD` / `=======` / `>>>>>>> Stashed changes`。

**后果**：这种损坏**不会让 CI 变红**。计数门禁按行号读数字，标记行不影响它读数 ——
于是「门禁看起来在工作，其实可能核的是错的行」。仓库历史上还发生过一次：
带 42 处标记的 CHANGELOG 进了 master。

**硬约束**：
1. 提交前**必须**跑 `git status`（看有没有别人的未提交改动）。
2. 提交前**必须**跑 `git grep -nE '^(<<<<<<< |>>>>>>> )'` 全仓验证。
3. 看到别人的未提交改动 → **不要 `git add -A`**，只 add 自己碰过的文件。

**机械化**：`scripts/check-repo-hygiene.py`（CI job `repo-gates`）。
本门禁**已用真事故反证过**：带标记的 CHANGELOG → 拦下；干净文件 → 不误拦。

## 2. 同一份文件里两处数字自相矛盾就提交了

**错在哪**：上面那次冲突提交里，CHANGELOG 正文有 156 个 `[Unreleased]` 小节，
而头部说明与版本索引表还写着 154。我直到下一轮才发现。

**后果**：读者按头部数字理解规模、按正文数实际条目，对不上。
「结构说明」这种**自述性数字**一旦漂移，就与整个项目「声明≠行为」的主线自相矛盾。

**硬约束**：改 CHANGELOG 后，正文小节数、头部说明、版本索引表**三处**必须一致。

**机械化**：同一个 `scripts/check-repo-hygiene.py`（已反证：把头部数字改成 154 → 拦下）。

## 3. 结论建立在没自证过的工具上

**错在哪**：同一轮里我的"量具"错了三次，每次都差点让我去"修"一段好代码：

| 错法 | 现象 | 真相 |
|---|---|---|
| `bash -c "… echo RC=$?"` | 退出码恒为 0 | `$?` 被 PowerShell 插值成 `True`（转义用**反引号**不是反斜杠） |
| 在 WSL 里跑 `coord-status.sh --self-test` | 自测假红 | WSL 里只有 `gh.exe`，`command -v gh` 找不到 → 走了 UNVERIFIED 降级分支 |
| 用正则切 `it(...)` 块数断言 | 报出 289 个"零断言" | 标题里的括号 `[0,360)` 被当成了 `it(` 的结束 |

**后果**：每次都差点把「好代码」判成「坏代码」并去改它。第三次差点给一个**有两条断言**
的用例加重复测试。

**硬约束**：
1. 任何用来**得出结论**的工具（解析器、探针、变异 harness、门禁脚本），
   必须先用一个**已知答案的用例**自证：**正例要能过、反例要能拦**，两个都要。
2. 自证没过 → **不许引用它的输出**。
3. 报告结论时，要说清这个结论是"工具说的"还是"我看的"。
4. **断言型自测还要再走一层变异**：把被测实现改坏，自测必须变红。
   抓不住 = 夹具缺判别性样本 = **这个自测本身是假绿的**，不许拿它当证据。

**机械化**：
- `scripts/check-repo-hygiene.py`、`scripts/check-selftest-coverage.py` 都有
  正例/反例/干净态三个情形的自证（写在各自的模块 docstring 里）。
- **所有门禁脚本一律提供 `--self-test`**；且
  `scripts/check-selftest-coverage.py`（CI job `repo-gates`）会检查
  **凡提供 `--self-test` 的脚本，都必须有 CI job 真的跑它** ——
  没有 CI 覆盖的自测，等于没有自测。
  真实教训：`scripts/coord-status.sh` 的自测就在仓库里，却从没被任何 CI job 跑过，
  它的写回守卫坏了 8 天（把「自动段合法变短」误判成「写坏」，
  `.coord/STATUS.md` 的「在途与占用」段自 2026-10-12 起冻住，
  各 agent 的开工声明发不出去，读板的人只看到 8 天前的过期事实）。
- **自测的假绿也要被抓**：2026-10-21 给 `scripts/e2e-phone-camera.py` 写 `--self-test` 时，
  三个变异体里有一个**没被抓住** —— 夹具里没有任何「像 JPEG 开头但不是 JPEG」的响应，
  于是「魔数只看前两字节」和「看三字节」两种实现表现完全一样。
  补上 `FF D8 00` 这个判别性夹具后才抓住。
  判据：变异体若与原实现**行为等价**，要么补夹具把它区分开，
  要么**显式声明它无害**（并说明为什么无害）—— 不许默默放过。

## 4. 把外部系统的路径写死

**错在哪**：我一度准备把 Android「IP 摄像头」的取图路径写死。

**后果**：安卓侧的内置 IP Camera、IP Webcam、camera-streamer 之类路径各不相同
（`/snapshot`、`/snapshot.jpg`、`/shot.jpg`、`/now.jpg`、`/capture`…），
写死就是猜 —— 猜错了整条链路的验证就作废。

**硬约束**：对接**不由本项目控制**的系统（外部设备、第三方 app、不同厂商的实现）时，
**不写死路径/字段/行为**；要么探测，要么按"响应特征"判定（本次用「返回体是不是真 JPEG」判定）。

**机械化**：`scripts/e2e-phone-camera.py` 的探测逻辑（候选路径 + 按响应体特征判定），
以及 `scripts/e2e-phone-camera.py --phone-ip <ip> --port <port> --discover-only` 可单跑探测。

## 5. 归因也要有证据，不能凭印象

**错在哪**：CI 排队 40 分钟，我第一反应是「仓库没配 `concurrency`」，
于是去加 —— 结果仓库**早就有** `concurrency: cancel-in-progress`（在 `ci.yml` 里），
真正原因是 **runner 容量饱和**（我和另一个会话的 run 撞在一起）。
我加的那段是**重复键**，YAML 里后者覆盖前者，等于白加。

**后果**：多一次无谓改动；而真正的原因（容量）并没有被解决。

**硬约束**：提"缺 X"之前，先 `grep` 现状确认 X 真的缺；提"因为 Y"之前，先找证据。

**机械化**：暂无（软约束）。写在这里就是为了下次先查再改。

## 6. 合并后要验证的东西

**错在哪**：合并冲突解完，我改了头部说明和索引表，却**没核对结构说明的计数**，
导致"声明的数字"与"实际的小节数"不一致（见第 2 条）。

**硬约束**：
1. 合并冲突解完后，除 `git grep` 查标记外，还要核对**该文件自己的自述数字**。
2. 提交前跑一次本仓库的全部门禁（计数门禁 + 卫生门禁 + 相关自测）。

**机械化**：
- `python scripts/check-test-count-docs.py --reports-root <聚合目录> --frontend-json <报告>`（计数）
- `python scripts/check-repo-hygiene.py`（卫生 + CHANGELOG 自洽）
- `python scripts/check-selftest-coverage.py`（自测覆盖）
- `python scripts/check-spi-coverage.py --self-test`（SPI 可验证性）

## 附：一次作业的收尾清单

```bash
git status                                    # 有没有别人的未提交改动
git grep -nE '^(<<<<<<< |>>>>>>> )'            # 冲突标记
python scripts/check-repo-hygiene.py           # 卫生 + CHANGELOG 自洽
python scripts/check-selftest-coverage.py      # 自测是否都在 CI 里跑
python scripts/check-test-count-docs.py --reports-root ... --frontend-json ...   # 计数
```

## 附：本清单的"未机械化"剩余项

- 第 5 条（归因要有证据）目前只能靠自觉。
- 「只 add 自己碰过的文件」目前是规则；可考虑做成 `scripts/precommit-check.sh`
  （对比暂存区与工作区，提示与本次主题无关的删除），但误报率尚可评估。
