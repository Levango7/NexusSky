# quarantine-storm — 污染轮隔离（2026-11-11）

以下轮次 recon 虽闭合（sentFrames == decoded + kernelDropEstimate），但测量期 CPU 轨迹
显示环境被外部负载污染（早期为 gbwire 线 pitest 变异风暴，后期为多 Qoder 会话 CPU 占用），
**不入主判定**，仅留档供复核。判据＝轨迹形态学：持续 ≥90% 或密集尖刺＝风暴；孤立单尖刺＋零损失＝干净。

## pitest 风暴轮（attempt5/6，旧门控口径）

| 文件 | 轮次 | 污染判据 |
|---|---|---|
| 20261111-095011-d254-hz25-c10-ip10.json | attempt5 cold1 | 旧门控（单次瞬时 ≤20% 即开火；该轮前 gate 序列 28%→15.1% 仅 2 次采样），测量期风暴在场 |
| 20261111-095301-d254-hz25-c10-ip10-w60.json | attempt5 warm1 | 同上（同 attempt） |
| 20261111-102147-d254-hz25-c10-ip10.json | attempt6 cold1 | 新门控（3×≤25%+storm check）过门，但测量期轨迹 12 样本 mean=44.2% max=84.6% |
| 20261111-102423-d254-hz25-c10-ip10-w60.json | attempt6 warm1 | 同上（同 attempt） |

## 多会话 CPU 风暴轮（p 轴两次 attempt；storm_check 只查 pitest，对他 Qoder 会话盲）

| 文件 | 轮次 | 损失 | 轨迹 |
|---|---|---|---|
| 20261111-113454-d254-hz25-c10-ip10.json | attempt1 R3p2 | 扇出 drop=200 | mean 21.8，末段 75.9/54.4/39.7 三连高 |
| 20261111-113809-d254-hz25-c10-ip10.json | attempt1 R3p4 | kd=746 | mean 36.9 max 83.2 |
| 20261111-122408-d254-hz25-c10-ip10.json | R3p2 | kd=17226、drop=85279、p95=992 | 100% 持续＋采样器饿死（qPk=0 为读数 artifact，轨迹有 64s 空档） |
| 20261111-122734-d254-hz25-c10-ip10.json | R3p4 | kd=816、drop=1763、p95=237 | mean 50.1 max 80.2 |
| 20261111-123249-d254-hz25-c10-ip10.json | R4p2 | kd=3816 | mean 41.5 max 87.5 |

剂量-响应：kd 随轨迹**峰值**单调（80.2%→816、87.5%→3816、100% 持续→17226），随均值不单调
⇒ 机制是瞬时 CPU 竞争饿死 UDP 收包路径，非应用层队列饱和。终报见 `docs/capacity/bistable-paxis-20261111.md`。

对应 CPU 轨迹：早期轮在 /f/tmp/capacity/quarantine-storm/cpu-*.log；p 轴轮在 /f/tmp/capacity/cpu-*.log。
加严门控（连续 3 次 ≤25% + pitest 不在场 + 每轮轨迹落盘事后打标）见 /f/tmp/nex-bistable-run.sh。

## 错位轮（harness 仓路径写死，2026-11-12 07:24 本地）

| 文件 | 轮次 | 问题 |
|---|---|---|
| 20261112-072455-d254-hz25-c10-ip10.json | 对照 attempt2 R1 cold-p2 | harness L48 仓路径写死 ⇒ 实跑 r5（#283 后代码）而非 pre283；测量期轨迹 79.1/49.5/79.4/86.4 风暴、drop=1270/kd≈1396/p95=310；JSON 误落 r5/docs/capacity/，已移回本目录；非对照数据、不入任何判定 |
