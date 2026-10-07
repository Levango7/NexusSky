import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * WS 帧消费完备性守卫。
 *
 * ## 为什么需要它
 *
 * 2026-10-06 之前，cloud-backend 的 `TelemetryWebSocketHandler.WS_TYPE_MAP` 已有 13 类转发帧，
 * 而 gcs-web 的 `useWebSocket.onmessage` 只对其中 7 类写了分支。剩下 6 类
 * （task-assignment / conflict-alert / task-status / alarm-trigger / alarm-ack /
 * surveillance-status）**帧照常到达、前端照常连接、无任何错误，然后被静默丢弃**。
 *
 * 这类缺陷的隐蔽之处在于：**没有任何信号会变红**。后端日志显示已广播，前端 WS 状态显示
 * `open`，面板不报错——只是数据从来没到过。M10（集群调度）的全部对外价值对操作员
 * 不可见，而这一状况可以无限期存在。
 *
 * ## 守卫做什么
 *
 * 逐条核对两份**手工维护的清单**是否互相覆盖，且清单与两侧源码都一致：
 *  ① `WS_TYPE_MAP` 的每一类都在前端有消费分支；
 *  ② 前端没有消费后端不产出的帧类型（否则是另一类漂移：前端等一条永远不来的消息）；
 *  ③ 后端新增帧类型而本清单未登记 → 判红（提示把新帧登记进清单并补消费）。
 *
 * ③ 是关键：它把"后端加了帧"变成一个**必须让人看见的动作**，与 drone-sim 的
 * `AiAutonomyWiringTest` 完备性守卫同一思路。
 *
 * ## 清单与源码漂移时的判定
 *
 * 两侧任一侧改动而另一侧未改，本测试即红并打印差异清单——
 * 而不是默默信任手写清单。
 */

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = join(HERE, '..', '..')

const HANDLER = readFileSync(
  join(REPO, 'cloud-backend', 'src', 'main', 'java', 'io', 'aerofleet', 'cloud', 'api', 'ws', 'TelemetryWebSocketHandler.java'),
  'utf-8',
)
const HOOK = readFileSync(join(HERE, '..', 'src', 'hooks', 'useWebSocket.js'), 'utf-8')

/** 去掉注释，避免注释里的类型名造成误判 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

const HANDLER_SRC = stripComments(HANDLER)
const HOOK_SRC = stripComments(HOOK)

/**
 * 权威清单：WS_TYPE_MAP 的 msgId → 帧类型，及该帧在前端是否有消费分支。
 * `consumed: true` 表示 useWebSocket.js 里存在 `msg.type === '<type>'` 分支。
 *
 * ⚠️ 这张表必须与后端 WS_TYPE_MAP 保持一致——测试第 ③ 条就是来强制这件事的。
 */
const WS_FRAMES = [
  { msgId: 30014, type: 'vision-detection', consumed: true },
  { msgId: 30048, type: 'task-assignment', consumed: true },
  { msgId: 30049, type: 'conflict-alert', consumed: true },
  { msgId: 30050, type: 'task-status', consumed: true },
  { msgId: 30051, type: 'decision-event', consumed: true },
  { msgId: 30052, type: 'adaptive-path', consumed: true },
  { msgId: 30053, type: 'edge-task-status', consumed: true },
  { msgId: 30054, type: 'sensor-fusion', consumed: true },
  { msgId: 30055, type: 'twin-state-sync', consumed: true },
  { msgId: 30056, type: 'prediction-result', consumed: true },
  { msgId: 30057, type: 'alarm-trigger', consumed: true },
  { msgId: 30058, type: 'alarm-ack', consumed: true },
  { msgId: 30059, type: 'surveillance-status', consumed: true },
]

/** 从后端源码解析 WS_TYPE_MAP：`Map.entry(Msg.ID, "type")` */
function backendFrameTypes() {
  const out = new Map()
  const re = /Map\.entry\(\s*[\w.]*?(\w+)\.ID\s*,\s*"([^"]+)"\s*\)/g
  let m
  while ((m = re.exec(HANDLER_SRC)) !== null) {
    out.set(m[2], m[1])
  }
  return out
}

/** 从前端 hook 解析消费分支：`msg.type === 'type'` */
function frontendConsumedTypes() {
  const out = new Set()
  const re = /msg\.type\s*===\s*'([^']+)'/g
  let m
  while ((m = re.exec(HOOK_SRC)) !== null) {
    out.add(m[1])
  }
  return out
}

const BACKEND = backendFrameTypes()
const FRONTEND = frontendConsumedTypes()

describe('WS 帧消费完备性（后端 WS_TYPE_MAP ↔ 前端 useWebSocket）', () => {
  it('后端 WS_TYPE_MAP 解析非空（防解析器本身失效导致假绿）', () => {
    expect(BACKEND.size).toBeGreaterThanOrEqual(13)
  })

  it('前端消费分支解析非空（同上）', () => {
    expect(FRONTEND.size).toBeGreaterThanOrEqual(13)
  })

  it('① 后端 WS_TYPE_MAP 的每一类在前端都有消费分支', () => {
    const orphans = [...BACKEND.keys()].filter((t) => !FRONTEND.has(t))
    expect(
      orphans,
      `以下 WS 帧后端在广播、前端无消费分支 —— 帧到达即被静默丢弃，且没有任何信号会变红：${orphans.join(', ')}\n` +
        '修复：在 gcs-web/src/hooks/useWebSocket.js 增加对应 msg.type 分支与 state，' +
        '并在 gcs-web/test/wsFrameConsumption.test.js 的 WS_FRAMES 里登记。',
    ).toEqual([])
  })

  it('② 前端不消费后端不产出的帧类型（否则是"等一条永远不来的消息"）', () => {
    // useWebSocket 还消费一批**非** WS_TYPE_MAP 的帧（telemetry/status/alert/formation/
    // mesh-topology/sat-link/terrain-*/celltower-topology），它们由专用 pusher 直接
    // broadcast，不经 MAVLink 事件总线。故只断言"前端消费的类型数 >= 后端转发数"，
    // 不能反过来要求前端集合是后端集合的子集。
    const extra = [...FRONTEND].filter((t) => !BACKEND.has(t))
    expect(extra.length).toBeGreaterThan(0) // 确有非 MAVLink 来源的帧，避免本条退化为空断言
    // 且这些"额外"类型必须是已知的那几个，不允许悄悄多出一批来路不明的
    const known = new Set([
      'telemetry', 'status', 'alert', 'formation',
      'mesh-topology', 'sat-link', 'terrain-update', 'terrain-restriction',
      'celltower-topology',
    ])
    expect(extra.filter((t) => !known.has(t))).toEqual([])
  })

  it('③ 权威清单与后端 WS_TYPE_MAP 完全一致（后端加帧必须被人看见）', () => {
    expect(
      [...BACKEND.keys()].sort(),
      '后端 WS_TYPE_MAP 与 gcs-web/test/wsFrameConsumption.test.js 的 WS_FRAMES 不一致 —— ' +
        '后端新增/删除/改名了转发帧类型。请同步清单，并确认新帧是否需要前端消费分支。',
    ).toEqual(WS_FRAMES.map((f) => f.type).sort())
  })

  it('③b 清单的 consumed 标记必须与前端真实分支一致（防止手滑标 true 却没写代码）', () => {
    const lying = WS_FRAMES.filter((f) => f.consumed !== FRONTEND.has(f.type))
    expect(
      lying,
      `清单里 consumed 与实际代码不符：${lying.map((f) => `${f.type}(清单=${f.consumed})`).join(', ')}`,
    ).toEqual([])
  })

  it('④ 六帧归一化函数全部存在且可调用（消费分支不得直接把原始单位画到 UI 上）', async () => {
    const mod = await import('../src/utils/schedulingFrames.js')
    const required = [
      'normalizeTaskAssignment',
      'normalizeConflictAlert',
      'normalizeTaskStatus',
      'normalizeAlarmTrigger',
      'normalizeAlarmAck',
      'normalizeSurveillanceStatus',
    ]
    for (const fn of required) {
      expect(typeof mod[fn], `缺少归一化函数 ${fn}`).toBe('function')
      expect(mod[fn]({}, 1)).not.toBeNull()
      expect(mod[fn](null, 1)).toBeNull()
    }
  })

  it('⑤ 六张码表形状统一为 { label, color }（混形状会让消费方 .label 拿到 undefined）', async () => {
    const mod = await import('../src/utils/schedulingFrames.js')
    const tables = [
      'TASK_TYPE_META', 'TASK_PRIORITY_META', 'TASK_STATUS_META',
      'CONFLICT_TYPE_META', 'CONFLICT_SEVERITY_META',
      'ALARM_TYPE_META', 'ALARM_SEVERITY_META', 'ALARM_ACK_META',
      'SURVEILLANCE_VENDOR_META', 'SURVEILLANCE_STATUS_META',
    ]
    const bad = []
    for (const name of tables) {
      const table = mod[name]
      if (table == null) { bad.push(`${name}（缺失）`); continue }
      for (const [code, v] of Object.entries(table)) {
        if (typeof v !== 'object' || v === null || typeof v.label !== 'string' || typeof v.color !== 'string') {
          bad.push(`${name}[${code}]=${JSON.stringify(v)}`)
        }
      }
    }
    expect(bad, `码表必须是 {label,color}；以下不是：${bad.join(', ')}`).toEqual([])
  })
})