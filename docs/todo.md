# 待办清单：按"证据 + 验收标准"排，不按愿望排

> **这份清单的规矩**：每一条都必须有**可核对的证据**（文件:行 / 门禁输出 / 变异体数），
> 以及**能验收的标准**（做完之后哪个门禁或哪个用例会变）。没有证据的条目不许进这里。
>
> 维护方式：做完一条就在下面划掉并写明"哪次 PR 做的"；**不要删条目**——
> 删掉就等于"没人知道它曾经存在"，而这正是本仓库主线一直在清的那个病。
>
> 最后更新：2026-10-26（P2 #15/#18 的扫描结果回填；本批落实见
> `docs/persistence-and-dead-surface-audit.md` §9）

---

## P0 —— 今天审计直接查出的，证据最硬

**当前无未处理条目**：3 条已全部清掉 —— #1 由本轮（PR #139）清；
#2/#3 由原 #140（已并入 #139）清（`SurveillanceStreamAndPruneContractTest` 3 例 + `scripts/mutate-verify.py` 改坏⇒红、还原⇒绿）。

> 这三条的共同形状：**删掉生产代码里的一个动作，没有任何测试会红**。
> 这类"行为等价 vs 真缺口"的判定必须**手动复现**（`docs/agent-constraints.md` 第 9 条），
> 复现过才能写进清单。

## P1 —— 变异审计的剩余存活变异体（不是"没测到"，是"测了没盯住"）

**当前无未处理条目**：#7 security 包 102 个存活变异体已于 2026-10-24 全部清账
（见下方已清零表；两个包的分诊至此全部完成，surveillance 22 条 / security 27 条
均为逐条定性的等价项）。

## P1 —— 前端端点契约门禁里登记的已知缺口

**当前无未处理条目**：6 条 KNOWN_GAPS 已于 2026-10-24 全部清账（见下方已清零表；
`KNOWN_GAPS` 现为空，门禁 233 处前端调用全部有后端对应端点）。
后续若新增缺口，登记会回到 `scripts/check-frontend-endpoints.py`。

## P2 —— 产品级缺口（量级大，先把口径钉住再动）

| # | 条目 | 现状 | 备注 |
|---|---|---|---|
| 14 | **厂商 SDK 全是桩** | 海康/大华/宇视/GB28181 适配器都是模拟实现，媒体面未实现 | ONVIF **协议级**优先（`gb28181-invite://` 这类描述性标识不能当可播流） |
| 15 | **10+ 个模块只存内存** | 业务模块的产出没落库，重启即失 | 逐个模块列出"落库缺什么" ⇒ **2026-10-26 审计成稿**：`docs/persistence-and-dead-surface-audit.md` §2 实测 **必须落库 9 / 需产品定 10 / 可接受 11**（"10+"这个粗数被校正），每条带 `file:line` 与"重启丢什么"；§5 列出 8 个必须产品口径才能定的问题。**未开工的就是这 9 条落库本身** |
| 16 | **UOM `sink-type` 未落地** | 默认 Noop | 机巢/监管上报的真实去向 |
| 17 | **机巢只有 sim** | 机巢 MQTT 未接 | 硬件到位再动 |
| 18 | **死枚举 / 空端点** | 审计期发现的历史遗留 | 需要一次全仓扫描确认清单 ⇒ **2026-10-26 扫描完成**（同上文档 §3/§4）：139 个枚举声明 + 109 个配置键双向对账，**恒空返回 32 处**中确认假面 1 处（空地协同"空中单位"，本批已修）、死配置键 6 个（本批处理 3 个）、确认死枚举/getter 若干**尚未删**（6 组是"只有测试在用"，删前先确认谁在钉它）。反向缺口也记了：2 个键被代码读取但 properties 里根本没有 |
| 19 | **热成像链路未接线** | ~~MAVLink 30012 在 cloud-backend 零引用~~ **2026-10-25 接线落 master**（ThermalIngestListener.java / thermal/status）；~~逐点热源经纬度~~ **条件③ 2026-10-26 收官**：drone-sim 同帧下发 THERMAL_HOTSPOT(30064, CRC=111) 逐热点像素 (u,v,峰值温度×10)，云端 `ThermalIngestListener` 用既有 `GeolocationSolver` 反投影写 `DroneSnapshot.thermalHotspots`，`/thermal/status` 增 hotspots 段（lat/lon=null 表示当时缺位姿无法解算），前端逐点显示。残留口径（已写进注释与面板）：位姿取自"最新已知"遥测而非拍摄时刻值，云台按正下视假设（30064 不带位姿是遥测设计固有限制） | 无（条件①②③均已实测：mvn 5222+ / vitest 570+ / 端点 414 / A/B 变异各一） |
| 20 | **link-sim 无运行时控制通道** | ~~进程内无控制入口、云端无从下发~~ **2026-10-24/25 已接线落 master**（十元组损伤参数，LinkSimControl + 控制服务器 + 云端转发 + 前端滑块）；**2026-10-26 收官**：安全画像（tamper/unsigned）接控制通道——`SecurityControl` 只暴露真实存在的 `tamperRate`（签名剥离是动作全命中，刻意不给滑块），`LinkSimControlServer` 双构造器 + instanceof 分派，对外 HTTP 契约同形，前端按 mode="security" 只渲染一个真滑块；中继模式混合画像时按语义层选控制面（不再 exit 1，也不再给安全链路挂十元组假旋钮） | 无（A/B：applyTamperRate 空操作 / 夹带字段放行 两个变异体均被杀且还原后全绿） |

---

## 已清零（留档：别重复劳动）

| 条目 | 清在哪 | 证据 |
|---|---|---|
| **前端 7 处 404**（前端在调后端没有的端点，404 被 `.catch(() => null)` 静默吞掉） | PR #137 | `scripts/check-frontend-endpoints.py`：238 处调用全部对得上后端 |
| **端点数门禁自己的文法盲区**（9 处「358 个端点」活过了它，而它报的是「通过」） | PR #135 | 门禁自测 6 条，含表格行文法与限定词写法 |
| **演练脚本的假 PASS**（`pg_dump` 没带密码却照样打印"已备份"） | PR #134 | 备份步骤三道检查：退出码 / 产物非空 / `pg_restore --list` 里真有表 |
| **SPI 9 个开关接线** | 已被别的会话清掉 | `scripts/check-spi-coverage.py`：接口 13 / 实现 35 / **未验 0 / 豁免 0** |
| **限飞区不入命令拦截链**（平台能指挥无人机飞进民航禁飞区） | PR #136 | `GeofenceInterceptRestrictionTest` 7 例 + 变异验证 |
| **轨迹 `ts` 永远是 0**（当天历史轨迹被前端去重成一个点） | PR #134 | `FlightLogPersistenceTest` + 变异验证 |
| **security 包 4 处安全行为没人盯**（限流阈值 / XFF 信任 / 密钥掩码 / 口令哈希） | PR #130 | `scripts/check-mutation-baseline.py` 棘轮门禁 |
| **`surveillance.offline.*` 子包零测试**（121 个变异体全是 NO_COVERAGE，占全仓未覆盖的 43%） | PR #139 | 36 例：`EdgeAiTriggerTest` 11 / `OfflineAlarmCacheTest` 13 / `OfflineAlarmControllerTest` 12；PIT 复测 杀掉 427→535、分数 0.8057→0.8269、未覆盖 279→162（offline：112 杀 / 3 存活已定性 / 4 未覆盖） |
| **`MjpegStreamService.pump` 的 `flush()` 没人盯**（删掉照样全绿） | PR #140 | `SurveillanceStreamAndPruneContractTest` 3 例 + `scripts/mutate-verify.py` 改坏⇒红、还原⇒绿 |
| **`SurveillanceDeviceRegistry.pruneStaleDevices` 的 `persistStaleStatus` 没人盯** | PR #140 | 同上（同一组动作级契约） |
| **surveillance 包 97 个存活变异体逐条分诊**（P1 #4/#5/#6 全清：前缀形状、Registry 谓词与排序、Onvif 校验链、SSE 生命周期、MJPEG 泵、mask 全等、快速部署尾段与计数日志） | 本分支（第四轮，见 `docs/mutation-testing.md`） | 6 个新契约文件 + 8 处扩展共 49 个新用例；A/B 批量 `tools/mutate-verify.py` 等价项复现；PIT 复测 杀掉 546→**636**、存活 97→**22**（全部是已定性清单）、未覆盖 159→144、分数 0.8400→**0.9564**、强度 0.8491→**0.9666**；22 条定性无害已留档 |
| **前端端点契约 6 条 KNOWN_GAPS 清账**（P1 #8~#13：env/status 补读端点；链路质量面板改读 comm-adapt 真实口径；损伤滑块/热成像面板/物理模型三处假控件按诚实性原则移除或禁用并写明） | 本分支（见 `CHANGELOG.md` 2026-10-24 节） | 后端新增 `GET /api/v1/env/status` + `EnvStatusReadContractTest` 5 例（圆周风向均值/NaN 不进 JSON/众数并列取小）；`KNOWN_GAPS` 清空，端点门禁 233 处调用全匹配 + self-test；vitest 382 例 + vite build 全绿；#12 真实现选项登记 P2 #19、#11 登记 P2 #20 |
| **P2 #19 热成像接线**（ingest + 机队读端点 + 面板启用；P1 #12 的真实现第一步） | 本分支（见 `CHANGELOG.md` 2026-10-24 P2 #19 节） | sim 侧补发送环（完整版装配 SimulatedThermalSource + 1Hz 挂钩，此前全仓零发送）；`ThermalIngestListener` ingest + `GET /api/v1/thermal/status` + 面板真实聚合表；新用例 11 个（sim 真实 UDP 往返 2 + ingest 契约 5 + 读口径 4）；**A/B 4/4 KILLED**（挂钩删除/装配删除/收账删除/离线过滤取反）；剩余条件③（逐点经纬度=位姿解算）留本表 #19 |
| **P2 #20 link-sim 运行时控制通道**（P1 #11 假滑块的真实现恢复条件；三端各一环接通） | 本分支（见 `CHANGELOG.md` 2026-10-24 P2 #20 节） | link-sim：`ImpairmentSpec` + `LinkSimControl` + `LinkSimControlServer`（`--control-port` 默认 0=关闭、只绑 127.0.0.1）+ `ImpairmentEngine.apply/spec/statsSnapshot`；cloud：`GET/PATCH /api/v1/link-sim/control`（OBSERVER/OPERATOR，503/504/400 透传/502）+ LicenseModuleMap 登记 network；前端：`api.js` 两个函数 + `LinkQualityPanel` 恢复滑块（初值读当前值、失败可见）；新用例 19 个；**A/B 3/3 KILLED**（apply 删除/PATCH→GET/去掉 OPERATOR 注解）；link-sim 133 / cloud 3050 / 全仓 5169 全绿；端点 411→413 |
| **security 包 102 个存活变异体逐条分诊**（P1 #7 全清：租户守卫三角、缓存边界、追踪器后台写者、限流定时器、RBAC 直调断言、Bean 工厂哨兵、租户过滤器、签名审计归属） | 本分支（第五轮，见 `docs/mutation-testing.md`） | 4 个新契约文件 + 16 处扩展净增 52 个新用例；A/B 批量 42/42「改坏⇒红、还原⇒绿」；PIT 复测 杀掉 566→**649**、存活 102→**27**（全部是已定性清单）、未覆盖 97→89、分数 0.8448→**0.9572**、强度 0.8473→**0.9601**；27 条定性无害已留档（含坑 8/坑 9 两个新坑） |

---

## 怎么用这份清单

1. **先看 P0**：证据最硬、影响最直接；
2. 每条按"**改坏生产代码 ⇒ 对应用例必须红**"的方式验收（变异验证），
   否则新写的用例可能又是一个摆设；
3. 做完一条：划掉 + 写 PR 号 + 把证据补进对应门禁（让门禁以后能拦住回退）。
