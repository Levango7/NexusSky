#!/usr/bin/env bash
# M2 喷洒物流端到端测试（Linux/CI 版，bash）
# 前置：cloud-backend(8080) 已运行，且已有一架在线无人机（sysid=1）。
# 断言链：创建喷洒任务 -> 查询喷洒状态 -> 控制夹爪 -> 查询物流配送序列 -> 清理取消任务
# 便携性：JSON 解析用 python3（CI 自带），不依赖 jq；字符串校验用 grep。
set -euo pipefail
BASE='http://localhost:8080/api/v1'
FAIL=0
SYSID=1

step()  { echo "== $1"; }
check() { # check <desc> <expr>
  if eval "$2"; then echo "   ✅ $1"; else echo "   ❌ $1"; FAIL=1; fi
}
# 便携 JSON 取值（不依赖 jq）：jqget <json> <python-expr on d>
jqget() { python3 -c "import sys,json; d=json.loads(sys.argv[1]); print($2)" "$1" 2>/dev/null || echo ''; }

# ---- 0. 前置：后端可达 + sysid=1 在线 ----
step '前置：后端可达'
if curl -s --max-time 5 "$BASE/drones" >/dev/null 2>&1; then
  echo '   ✅ 后端可达'
else
  echo '   ❌ 后端未运行，中止'; exit 1
fi

step "等待无人机 sysid=$SYSID 上线"
online=1
for _ in $(seq 1 20); do
  if curl -s "$BASE/drones" 2>/dev/null | python3 -c "import sys,json; sys.exit(0 if any(d['sysid']==$SYSID and d.get('online') for d in json.load(sys.stdin)) else 1)" 2>/dev/null; then
    online=0; break
  fi
  sleep 1
done
check "无人机 sysid=$SYSID 已上线" "[ '$online' = '0' ]"
if [ "$online" = '1' ]; then echo '❌ M2 喷洒物流 e2e 失败：无人机未上线'; exit 1; fi

# ---- 1. 创建喷洒任务 ----
step '创建喷洒任务 (rateLpm=2.5, totalLiters=50, 2 段)'
TASK='{"sysid":1,"rateLpm":2.5,"totalLiters":50,"segments":[{"id":1,"startLat":22.5907,"startLon":113.9345,"endLat":22.5917,"endLon":113.9345},{"id":2,"startLat":22.5917,"startLon":113.9345,"endLat":22.5917,"endLon":113.9355}]}'
RESP=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d "$TASK" "$BASE/spray/task" 2>/dev/null || echo '{}')
echo "   响应: $RESP"
TASK_ID=$(jqget "$RESP" 'd.get("taskId") or d.get("task_id") or d.get("id")')
check "返回 taskId 非空" "[ -n '$TASK_ID' ] && [ '$TASK_ID' != 'None' ]"

# ---- 2. 查询喷洒状态 ----
step '查询喷洒状态'
sleep 1
ST=$(curl -s --max-time 10 "$BASE/spray/status/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $ST"
PUMP=$(jqget "$ST" "d.get('pump',{}).get('state') if isinstance(d.get('pump'),dict) else d.get('pump')")
check "状态包含 pump 字段" "[ -n '$PUMP' ] && [ '$PUMP' != 'None' ]"

# ---- 3. 控制夹爪 ----
step '控制夹爪 (open=true)'
GR=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"sysid":1,"open":true}' "$BASE/spray/gripper" 2>/dev/null || echo '{}')
echo "   响应: $GR"
check "夹爪打开返回成功" "echo '$GR' | grep -qiE 'ok|success|true'"

step '控制夹爪 (open=false)'
GR2=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"sysid":1,"open":false}' "$BASE/spray/gripper" 2>/dev/null || echo '{}')
echo "   响应: $GR2"
check "夹爪关闭返回成功" "echo '$GR2' | grep -qiE 'ok|success|true'"

# ---- 4. 查询物流配送序列 ----
step '查询物流配送序列'
SEQ=$(curl -s --max-time 10 "$BASE/delivery/sequence/$SYSID" 2>/dev/null || echo '{}')
echo "   响应: $SEQ"
SITES=$(jqget "$SEQ" "len(d.get('sites', d.get('stations', d.get('segments', [])))))")
check "配送序列包含站点列表" "[ -n '$SITES' ] && [ '$SITES' != 'None' ]"

# ---- 5. 清理：取消喷洒任务 ----
step '清理：取消喷洒任务'
if [ -n "$TASK_ID" ] && [ "$TASK_ID" != 'None' ]; then
  CANCEL=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d "{\"sysid\":1,\"taskId\":\"$TASK_ID\"}" "$BASE/spray/cancel" 2>/dev/null || echo '{}')
else
  CANCEL=$(curl -s --max-time 10 -X POST -H 'Content-Type: application/json' -d '{"sysid":1}' "$BASE/spray/cancel" 2>/dev/null || echo '{}')
fi
echo "   响应: $CANCEL"
check "取消任务返回成功" "echo '$CANCEL' | grep -qiE 'ok|success|true|cancel'"

# ---- 收尾 ----
if [ "$FAIL" = '0' ]; then
  echo '✅ M2 喷洒物流 e2e 全部通过'
  exit 0
else
  echo '❌ M2 喷洒物流 e2e 失败'
  exit 1
fi