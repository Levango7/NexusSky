# MAVLink 签名 prod 收紧：上线、观测与回滚（C5 第三步）

> 状态：**待拍板**（本文档不改变任何默认值；prod 仍是 `mavlink.signing.enabled=false`）。
>
> 这份只补一件事——**按什么顺序打开、每一步看什么数字、怎么退回去**。
> 协议语义、组件装配、指标定义与告警表达式在 `docs/security-design.md` **§7 链路签名（MAVLink v2 signing）**
> 里已经写全，本文一律引用、不重述。`ROADMAP.md` 的 C5 条目口径见 `docs/roadmap-c-drift.md`。

---

## 1. 要拍板的到底是什么

不是"要不要签名"（代码已实现、CI 每次都在验），而是这两个开关在 prod 的组合：

| 姿态 | `enabled` | `reject-unsigned` | 未配密钥的设备会怎样 | 现成测试锚点 |
|---|---|---|---|---|
| 出厂（现状） | `false` | 任意（无意义） | 照常通信 | `MavlinkSigningConfigurationTest`「未配置…四个签名 bean 都不创建（出厂行为不变）」 |
| **permissive**（#49 在 staging 用的第一步） | `true` | `false` | **不断线**：未签名帧放行，但记 `aerofleet.signing.unsigned` | `UdpGatewaySigningPermissiveTest` 全 6 例（含"签错密钥仍被拒"、"篡改仍被拒"） |
| **strict**（终态） | `true` | `true` | **帧被整体丢弃 = 该机遥测/告警全断** | `UdpGatewaySigningTest`「3. 未签名帧 + rejectUnsigned=true（fail-closed 默认）：拒绝」 |

**协议硬事实（决定了没有"平滑过渡"这回事）**：MAVLink v2 签名块是 `linkId|timestamp|signature`，
**没有 key id 字段** ⇒ 无法在同一设备上并存新旧两把密钥，因此**没有宽限期**。
代码把这个约束写在了两处：`SigningKeyStoreWriter` 类注释（"密钥轮换只能是**硬切换**：不存在 API Key 那种
'新旧并存宽限期'的协议能力……graceHours 之类参数——给了也做不到"）与
`MavlinkSigningKeyController`（`POST /api/v1/mavlink/signing/keys/{sysid}/rotate` 的请求体只有 `{"key": ...}`，
注明"**没有 graceHours**"）。
⚠️ **别把 API Key 的心智模型搬过来**：`/auth/api-key/{keyId}/rotate` 确实有 `graceHours`（§认证那套），
但那是 HTTP API Key，与链路签名无关。

## 2. 打开前必须满足的前置条件

逐条打勾才能进第 3 节。任何一条不满足，**结果不是"更安全"，而是机队部分失联**。

1. **每台在网设备都配好同一套密钥**（含中继/边缘端）。这是 strict 姿态唯一的风险来源。
2. **密钥注入路径确定**：prod 目前是 `mavlink.signing.secret-key=${MAVLINK_SIGNING_SECRET_KEY:}`
   （**空串缺省**），而 staging 是 `${MAVLINK_SIGNING_SECRET_KEY}`（无默认值）。
   装配层已保证 fail-closed：`MavlinkSigningConfigurationTest`「enabled=true 但既无口令也无密钥库：启动失败
   （校验真的被执行了）」「key-store-path 指向不存在的文件：启动失败而不是静默退回单密钥模式」
   ⇒ **不会出现"以为开了其实没开"**。
3. **多机分密钥能力已验证**：`UdpGatewaySigningTest`「5. 多机密钥库：各 sysid 用自己的口令都能通过」
   「6. 未知 sysid：密钥库无其口令且无 defaultKey → 拒绝（不顶替放行）」。
   运营侧要回答：新设备入网时密钥怎么发（端点见 `MavlinkSigningKeyController`）。
   **工具侧 2026-10-24 已补齐**：`POST /keys/{sysid}/rotate` 成功后会把新密钥的**指纹**记进
   `devices.signing_key_fingerprint`（`persist=false` 的默认部署记在内存镜像里，重启后由
   `restoreFromRepository()` 从库回填），响应里的 `provisioningLedger` 如实说明这一步落到哪一层
   （`PERSISTED` / `MEMORY_ONLY` / `UNAVAILABLE`）；逐机状态查
   `GET /api/v1/mavlink/signing/provisioning`（ADMIN）——它把"地面配过"（ISSUED_UNVERIFIED）与
   "真见过这台的签名帧"（VERIFIED）分开，另给 `fingerprintMismatch` 用来暴露
   "以为配过了、其实 keystore 里已经是另一把"。这张表就是手册里一直缺的那张**发放配置单**；
   它不含密钥材料，也不替你判"能不能翻 strict"（那仍看 `GET /posture`）。
4. **面板与告警先接好再翻开关**（不是反过来）：计数器与 PromQL 在 `docs/security-design.md` §签名统计；
   至少要有 `aerofleet_signing_rejected_total{reason=...}` 的看板 + 告警静默期。
   注意该文已提醒：收紧成 `reject-unsigned=true` 后 `unsigned` 计数**恒为 0**（未签名直接算 rejected），
   所以"unsigned 归零"在 strict 下不是达成条件的证据，**只有 permissive 阶段它是证据**。
5. **有测试钉着 prod 现状**：`MavlinkSigningConfigurationTest`「prod profile 仍是 enabled=false（第三步需单独
   拍板，不在本轮范围）」。⇒ 真要翻 prod，这条测试**必须一起改**，改它的人就是留下决策记录的人。

## 3. 建议的上线顺序（两步走，每步都有退回点）

```
第 0 步（已完成）  staging = enabled=true + reject-unsigned=true   ← PR #49
第 1 步（本次要拍）prod  = enabled=true + reject-unsigned=false    ← permissive：先"只签不砍"
                   观察判据：aerofleet.signing.unsigned 在 24h 内归零（意味着全部设备都已配密钥）
                   退回点：任一设备异常 → 翻回 enabled=false（见第 4 节），无数据迁移
第 2 步            prod  = enabled=true + reject-unsigned=true     ← strict
                   进入条件：第 1 步的 unsigned 连续 7 天为 0，且新增/维修设备均已入密钥库
                   观察判据：rejected{reason=unsigned|no-key} 保持 0；bad-signature / timestamp-replay
                              任何增长都要按安全事件处理（不是噪声）
```

**为什么第 1 步不可跳**：跳过 permissive 直接 strict，唯一失败模式是"某台设备没配密钥"，
而它的表现是**那台机遥测静默消失**——在无人机场景里这属于最坏的一类故障（现场以为还在飞）。

## 4. 回滚

- 改回 `mavlink.signing.enabled=false`（环境变量/配置即可），重启后**四个签名 bean 一概不创建**，
  注入点回到 null，行为与开启前逐字节一致（装配测试已钉）。
- 无需回滚数据：签名只影响链路层，不落库、不影响 `flight_log` 既有记录。
- 已下发的密钥**不需要**回收（旧姿态不校验它），但如果怀疑泄露，走 `SigningKeyStoreWriter` 轮换而不是回滚开关。

## 5. 本次为这份文档实际测到了什么 / 没测到什么

**测到了（本机，2026-10-12 17:2x）**

```
mvn -B -o test -pl cloud-backend -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='UdpGatewaySigningTest,UdpGatewaySigningPermissiveTest,UdpGatewaySigningMetricsTest,MavlinkSigningConfigurationTest'
→ Tests run: 32, Failures: 0, Errors: 0, Skipped: 0  BUILD SUCCESS
```

**测到了（CI，权威环境）**：master run `38221403959` 的 job `E2E MAVLink signing (6 scenarios)` = **success**
——六个场景（正常签名通信 / 篡改检测 / 未签名拒绝 / 密钥不匹配 / 多机密钥分发 / 未启用兼容）在带 Redis
service 容器的环境里全链路通过。

**没测到（本机，且不打算糊过去）**：我试图在本机直接跑 `scripts/e2e-signing.ps1`
（用 `-BackendRestPort 18080 -BackendUdpPort 15550 -DronePort 15540 -DronePort2 15541 -LinkSimPort 15600`
避开并行 agent 的构建端口），**场景 1 就失败**：cloud-backend 健康检查 503。
根因不是签名，而是本机 6379 上那个 Redis **要求认证**：
`io.lettuce.core.RedisConnectionException … NOAUTH HELLO must be called with the client authenticated itself`。
脚本只提供 `RedisHost/RedisPort` 参数、没有口令入口，本机也没有可自起的免密 redis ⇒
**这条 e2e 的可复现环境是 CI（`ci.yml` 的 `E2E MAVLink signing` job 自带 redis service），
或本地起 `deploy/docker/docker-compose.yml`（其中 `redis: redis:7-alpine` 是服务之一）**，不是我这台机器。
我不会把"CI 有别人跑绿"改写成"我在 prod 前置环境上验证过"。

**边界**：以上全部基于 `drone-sim`/`link-sim` 的 MAVLink 模拟。**它证明协议层行为正确，
不证明现网兼容性**——真机固件是否都支持 v2 签名块、以及逐机发密钥的运营流程，必须在第 1 步
（permissive）用真实机队跑一遍才有数。

## 6. 还需要人回答的三件事

1. **谁负责给每台在网设备配密钥**，以及维修/新增设备的密钥发放动作挂在哪个流程上。
   ~~现在只有端点，没有流程~~ ⇒ 2026-10-24 起**端点与台账都有了**（见 §2 第 3 条：轮换自动记指纹、
   `GET /provisioning` 出配置单），**缺的仍是"流程归谁"**：谁按下 rotate、密钥怎么送到现场、
   维修后重配算不算新设备、发放节奏跟不跟得上翻 strict 的窗口——这四条不是代码能替运营定的。
2. **能不能接受第 1 步那段"开着签名但不拒未签名"的窗口**（几周内安全性只增不减，但不是终态）。
3. **时间窗**：第 2 步（strict）翻牌时若某机失联，是否有人在现场可以立刻回滚——没有人值守就不要翻。
