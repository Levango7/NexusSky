-- V28__device_signing_state.sql: devices 补两列——"这台机到底有没有在签名"的地面可见证据
--
-- 背景：C5 第三步（prod 打开 `mavlink.signing.enabled` 并收紧 `reject-unsigned`）唯一的失败模式是
-- "某台设备没配密钥"，而它在 strict 下的表现是**那台机遥测静默消失**。此前地面只知道"帧被拒"，
-- 不知道"哪台机从来没有过签名帧"，所以翻牌只能靠人值守（见 docs/signing-prod-rollout.md §2、§6）。
--
-- 1) signing_verified_at：最近一次**收到该 sysid 的帧并通过验签**的时刻。这是"该机确实配好了密钥且
--    在飞"的唯一地面证据；收紧前的就绪判定（在线设备 100% 具备、且未过期）只看它。
-- 2) signing_key_fingerprint：地面为该 sysid 生成/轮换过的密钥**指纹**（不是明文密钥）。它的存在是为了
--    区分两种被混为一谈的状态——"我已经生成了密钥，等现场写进飞控" vs "系统真的看到了它的签名帧"。
--    前者由轮换/批量端点写，后者只由收帧验签路径写。
-- 3) 两列都可空且**不给默认值**：NULL = 无记录。给默认（如 CURRENT_TIMESTAMP）会把所有历史设备
--    伪装成"已验证"，那正好是本迁移要消灭的那类假象。
-- 4) 写频：signing_verified_at 由 DeviceRegistry 按节流落库（同一设备距上次写入 ≥ 阈值才写），
--    绝不在每帧路径上写库——遥测 5Hz × N 机下每帧写会直接把 ingestion 压垮。
--    指纹与心跳/飞行态同批写（心跳路径本来就在写 devices 行），不新增写放大。

ALTER TABLE devices ADD COLUMN signing_verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE devices ADD COLUMN signing_key_fingerprint VARCHAR(64);
