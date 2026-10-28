# UOM 契约差异比对（P3 先行件：拿到规范前先把机械部分建好）

> **来源**：`docs/decisions-2026-10-26.md` 决策二（真实 UOM 联调就绪清单 #3：
> 「`regulator-sim` 与真 UOM 的协议差异比对」——**仓内可先行、不依赖外部**）。
> **定位**：本页 + `scripts/uom-contract-diff.py` 把"比对"这件事的机械部分建好：等
> #1（对接主体，用户侧行动项）拿到 API 文档与测试环境后，**填两个输入、跑一条命令**，
> 差异表自动出来——不临场手抄、不靠记忆对字段。
>
> **本工具不做什么**：不判业务语义（"这个字段该不该有"要人读规范）、不替代真实联调、
> 不发明规范里没有的字段。它只回答一个问题：**实捕获与规范，逐字段/逐状态码差在哪。**

## 两个输入

### 1）`spec.json`——规范形状（拿到 API 文档后人工填）

```json
{
  "endpoints": [
    {
      "id": "verify",
      "method": "GET",
      "path": "/api/verify",
      "request_in": "query",
      "request": {"serialNo": "string", "certNo": "string"},
      "response": {"status": "string", "owner": "string|null", "registerDate": "string|null"},
      "errors": {"400": "serialNo 缺失"}
    }
  ]
}
```

- 类型词表：`string` / `number` / `boolean` / `object` / `array` / `null`；
  联合类型写 `string|null`（工具按集合判）。
- `request_in` 只用于文档可读性（`query` / `body`），比对本身对 request 与 response 同口径。

### 2）`captures/`——实捕获目录（每文件一次交换）

```json
{"endpoint": "verify", "status": 200, "request": {"serialNo": "T-1", "certNo": "C-1"},
 "response": {"status": "VERIFIED", "owner": "张*", "registerDate": "2026-01-15"}}
```

- 捕获来源二选一：真实 UOM 测试环境（最优）或 `regulator-sim`（`MockUomServer`，**sim 侧基线**）。
- 文件名随意（`*.json` 之外忽略）。

## 判定与退出码

差异类别（逐条可复算）：`missing_in_capture`（规范有、捕获无）/ `extra_in_capture`
（捕获有、规范无）/ `type_mismatch` / `status_unexpected`（不在 2xx 期望、也不在 errors 清单）
/ `endpoint_missing_capture`（规范端点一份捕获都没有）/ `unknown_endpoint_capture`。

退出码：**0** = 无差异；**1** = 有差异（逐条列出）；**2** = 无法判定
（spec 空 / captures 空 / 锚点读不到——"没得比"不许冒充"没问题"）。

```bash
# 直接用 sim 做基线（现在就能跑，只要先有 spec）：
python3 scripts/uom-contract-diff.py --spec docs/uom-spec.json --captures docs/uom-captures

# 拿到真实环境后：把真实响应落成 captures/real-*.json 再跑一遍，两轮差异对照即"规范↔sim↔真端"三点
python3 scripts/uom-contract-diff.py --spec docs/uom-spec.json --captures docs/uom-captures-real \
    --out docs/uom-contract-diff-real.md

# 工具自测（9 情形，CI 每轮都跑）：
python3 scripts/uom-contract-diff.py --self-test
```

## 当前状态（2026-10-28）

- **规范侧**：⬜ 待填（等 #1 拿到 UOM API 文档）。
- **sim 侧**：✅ **已被两处钉住**，本页不重复抄字段（避免出现第二份会漂移的手抄）：
  - `cloud-backend/src/main/java/io/aerofleet/cloud/regulator/UomReportSink.java`
    ——真实现的请求构造（字段名/签名头/退避重试）；
  - `cloud-backend/src/test/java/io/aerofleet/cloud/regulator/UomSinkVsRegulatorSimContractTest.java`
    ——真实现对 `MockUomServer` 的 5 例契约（含 4xx 不重试的请求计数断言）。
- **工具**：✅ `scripts/uom-contract-diff.py`（9 情形离线自测进 CI）。

### 六端点清单（sim 侧，锚点 `regulator-sim/.../MockUomServer.java`）

| # | 端点 | 语义（sim 状态机） |
|---|---|---|
| 1 | `GET /api/verify?serialNo=&certNo=` | 实名查询：VERIFIED / UNVERIFIED / NOT_FOUND 三态 |
| 2 | `POST /api/activate` | 激活：**未实名不可激活**、幂等（重试返回同一 ID） |
| 3 | `POST /api/cancel` | 注销：幂等；注销后拒绝新上报 |
| 4 | `POST /api/telemetry` | 遥测：**需有效激活**（未激活 403 NOT_ACTIVATED、已注销 403 CANCELLED） |
| 5 | `GET /api/records?type=` | 记录查询（e2e 断言落账用，非 UOM 契约面） |
| 6 | `GET /api/stats`、`POST /api/reset` | 计数与清态（测试隔离用，非 UOM 契约面） |

- 与 UOM 契约相关的**上报面**是 1–4；5/6 是它为了 e2e 可验证性额外开的观测口。

## 拿到规范后怎么做（runbook）

1. 按 API 文档把每条端点填进 `spec.json`（字段名/类型/错误码；拿不准的**不要猜**——
   标记 `"待确认"` 并在 PR 里列出来，比猜一个像样的字段名强）。
2. 捕获 sim 侧响应（`regulator-sim` 起在本地，按 `e2e-regulator.ps1` 的请求形状打一遍；
   已有 `UomSinkVsRegulatorSimContractTest` 可作为请求形状的权威来源）。
3. 跑工具 → 差异表进 `docs/uom-contract-diff.md` 增补节（谁后填谁负责解释每条差异的风险）。
4. 拿到真实测试环境后重复 2–3，产出"规范↔sim↔真端"三点对照；据此收敛 sim 与真实现的偏差。
