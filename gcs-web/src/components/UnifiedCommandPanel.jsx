import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { PRIORITY_META, SEVERITY_META, normPriority, normSeverity } from '../utils/statusMeta'
import { battColorVar } from '../utils/battery'
import {
  api,
  emergencyOrch,
  disaster,
  listSurveillanceDevices,
  ptzControl,
  listSurveillanceEvents,
  acknowledgeAlarm,
  listAlarmRules,
  triggerEmergencyResponse,
  listLinkageLogs,
  alarmStreamUrl,
  fetchStreamToken,
  getAirGroundSituation,
  triggerReconFromAlarm,
  triggerPtzTracking,
} from '../api.js'

// 空地一体化应急指挥面板（UnifiedCommandPanel）
// 整合安防监控 + 应急编排 + 灾害通信 + 报警联动 + Mesh 拓扑为一个统一指挥视图
// 三栏布局：左栏（安防设备） + 中栏（空地协同地图） + 右栏（无人机/报警/联动）
// 底部：视频融合区 + 编排进度时间线 + 仪表盘 + 优先级任务队列
// 风格与现有面板一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 3s；SSE 接收实时报警事件

const POLL_MS = 3000

// 场景预设类型
// 场景类型兜底清单：只在 GET /scenarios 拉取失败时用。
// type 必须是**后端的 int**（0..3）—— POST /scenarios/{type}/start 的
// @PathVariable("type") 是 int，传字符串会被 Spring 判为类型不匹配直接 400。
// 正常路径下这个常量不参与：选择器由 scenarios（后端真值）驱动。
const SCENARIO_TYPE_FALLBACK = [
  { type: 0, name: '地震' },
  { type: 1, name: '泥石流' },
  { type: 2, name: '火灾' },
  { type: 3, name: '自定义' },
]

// 编排阶段（六阶段流程）
const PHASES = [
  { key: 'survey', label: '测绘' },
  { key: 'plan', label: '规划' },
  { key: 'deploy', label: '部署' },
  { key: 'service', label: '服务' },
  { key: 'selfheal', label: '自愈' },
  { key: 'verify', label: '验证' },
]

// 阶段状态 → 颜色
const PHASE_STATUS_COLOR = {
  done: 'var(--ok)',
  running: 'var(--cyan)',
  pending: 'var(--dim)',
  failed: 'var(--crit)',
  skipped: 'var(--dim)',
}

// 编排终态
const TERMINAL_STATUS = new Set([
  'COMPLETED', 'DONE', 'ABORTED', 'FAILED', 'ERROR',
])

// 无人机状态 → 颜色
const DRONE_STATUS_COLOR = {
  ONLINE: 'var(--ok)',
  IDLE: 'var(--cyan)',
  BUSY: 'var(--gold)',
  LOW_BATTERY: 'var(--warn)',
  OFFLINE: 'var(--dim)',
  DAMAGED: 'var(--crit)',
  LOST: 'var(--crit)',
}

// 优先级/严重度元数据与规范化：见 utils/statusMeta（与 EmergencyOrchPanel、AlarmPanel 共用单一实现）

// 安防设备在线状态 → 颜色
const ONLINE_COLOR = {
  online: 'var(--ok)',
  offline: 'var(--dim)',
  error: 'var(--crit)',
}

// PTZ 控制按钮
const PTZ_BUTTONS = [
  { cmd: 'up', label: '▲', title: '上' },
  { cmd: 'left', label: '◀', title: '左' },
  { cmd: 'stop', label: '■', title: '停止' },
  { cmd: 'right', label: '▶', title: '右' },
  { cmd: 'down', label: '▼', title: '下' },
]

const PTZ_ZOOM = [
  { cmd: 'zoomIn', label: '＋', title: '放大' },
  { cmd: 'zoomOut', label: '－', title: '缩小' },
]

// 圆环仪表盘组件
function Gauge({ value, label, color, size = 72 }) {
  const pct = Math.max(0, Math.min(100, Number(value) || 0))
  const r = size / 2 - 6
  const c = 2 * Math.PI * r
  const offset = c * (1 - pct / 100)
  const cx = size / 2
  return (
    <div style={{ textAlign: 'center', minWidth: size + 8 }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle cx={cx} cy={cx} r={r} fill="none" stroke="var(--line-2)" strokeWidth="4" />
        <circle
          cx={cx} cy={cx} r={r} fill="none" stroke={color} strokeWidth="4"
          strokeDasharray={c} strokeDashoffset={offset}
          transform={`rotate(-90 ${cx} ${cx})`} strokeLinecap="round"
          style={{ transition: 'stroke-dashoffset 0.4s ease' }}
        />
        <text
          x={cx} y={cx + 4} textAnchor="middle" fontSize="12" fontWeight="bold"
          fill="var(--text)" fontFamily="var(--mono)"
        >
          {pct.toFixed(0)}%
        </text>
      </svg>
      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 1 }}>{label}</div>
    </div>
  )
}

// 事件去重 key
function eventDedupKey(e) {
  if (e.id) return e.id
  const eventId = e.eventId || ''
  const ts = e.ts != null ? e.ts : (e.timestamp != null ? e.timestamp : '')
  if (eventId || ts) return eventId + '|' + ts
  const desc = e.description || e.message || e.title || ''
  const type = e.type || e.alarmType || ''
  const source = e.source || e.sourceType || ''
  const sev = e.severity || ''
  const composite = desc + '|' + type + '|' + source + '|' + sev
  if (composite === '|||') return null
  return 'cmp:' + composite
}

export default function UnifiedCommandPanel() {
  // ===== 安防设备状态 =====
  const [survDevices, setSurvDevices] = useState([])
  const [survDevicesLoading, setSurvDevicesLoading] = useState(true)
  const [selectedSurvDeviceId, setSelectedSurvDeviceId] = useState(null)
  // 【接线缺口 · 已修 #104】监控事件列表。键名此前读 data.events，而后端返回 {items}
  const [survEvents, setSurvEvents] = useState([])
  const [ptzBusy, setPtzBusy] = useState(false)

  // ===== 报警事件状态 =====
  const [alarmEvents, setAlarmEvents] = useState([])
  const [alarmRules, setAlarmRules] = useState([])
  const [linkageLogs, setLinkageLogs] = useState([])
  const [sseStatus, setSseStatus] = useState('connecting')
  const [selectedAlarmId, setSelectedAlarmId] = useState(null)
  const [responding, setResponding] = useState(false)
  const alarmEventsRef = useRef([])
  alarmEventsRef.current = alarmEvents
  const dedupCounterRef = useRef(0)

  // ===== 应急编排状态 =====
  // scenarios = 后端 GET /api/v1/emergency/scenarios 的真值，字段为
  // {type:int, name, description, defaults:{radius,droneCount,priority}}。
  // 【原先的缺陷】这里拉回来的列表没人读，选择器用的是另一份硬编码 SCENARIO_TYPES
  // （type 是字符串），而 POST /scenarios/{type}/start 的 @PathVariable 是 int
  // ⇒ 字符串直接 400，「一键应急响应」按钮从来没有成功过。
  // 现在选择器改由本列表驱动（拉取失败时才退到 SCENARIO_TYPE_FALLBACK）。
  const [scenarios, setScenarios] = useState([])
  // 起步为 null：后端的 type 是 int，在列表到位之前不该猜。到位后自动选中第一项。
  const [selectedScenarioType, setSelectedScenarioType] = useState(null)
  // centerLat/centerLon 是**度**（PTZ 追踪那边也用同一份，见 handleTriggerPtzTracking），
  // 启动编排时在调用点换算成后端契约要求的 1E7 度；radiusKm 是 km，后端要米。
  const [centerLat, setCenterLat] = useState(30.0)
  const [centerLon, setCenterLon] = useState(104.0)
  const [radiusKm, setRadiusKm] = useState(5)
  const [activePlan, setActivePlan] = useState(null)
  const [coverage, setCoverage] = useState(null)
  const [priorityQueue, setPriorityQueue] = useState(null)
  // 【接线缺口 · 已修 #104】编排事件流。原先的写入是真的，但去重逻辑按对象读
  // e.ts / e.message，而后端 events 是 List<String> ⇒ 键恒为 '|' ⇒ 所有事件并成一条
  const [orchEvents, setOrchEvents] = useState([])
  // 事件只追加，所以身份是位置；这个 ref 记录「已消费到第几条」以及属于哪个 planId，
  // 换计划时用它整体替换，避免拿旧计划的长度去切新计划。
  const orchEventsRef = useRef({ planId: null, count: 0 })
  const [starting, setStarting] = useState(false)
  const [aborting, setAborting] = useState(false)

  // ===== 灾害通信状态 =====
  const [disasterStatus, setDisasterStatus] = useState(null)
  // 【接线缺口 · 已修 #104】灾害聚簇。后端 getClusterTopology() 返回
  // { clusterCount, clusters:[{clusterId, clusterHead, members, memberCount, clusterRadius, timestamp}] }
  const [disasterClusters, setDisasterClusters] = useState(null)
  const [disasterLinks, setDisasterLinks] = useState(null)

  // ===== Mesh 拓扑状态 =====
  const [meshTopology, setMeshTopology] = useState(null)
  const [meshLinks, setMeshLinks] = useState(null)

  // ===== 无人机列表 =====
  const [drones, setDrones] = useState([])

  // ===== 空地态势融合数据 =====
  const [airGroundSituation, setAirGroundSituation] = useState(null)

  // ===== 通用错误 =====
  const [error, setError] = useState(null)

  // ===== SSE 实时订阅报警事件 =====
  useEffect(() => {
    const controller = new AbortController()
    let es = null
    let retryTimer = null
    let closed = false

    const subscribe = () => {
      if (controller.signal.aborted) return

      // EventSource 无法携带 Authorization 头：先取 60s 单次用流令牌再开流
      fetchStreamToken()
        .then((token) => {
          if (controller.signal.aborted || closed) return
          es = new EventSource(alarmStreamUrl(token))

          es.onopen = () => {
            if (controller.signal.aborted) { es.close(); return }
            setSseStatus('open')
          }

          es.onmessage = (ev) => {
            if (controller.signal.aborted) return
            let data
            try {
              data = JSON.parse(ev.data)
            } catch (e) {
              return
            }
            const incoming = Array.isArray(data) ? data : [data]
            const incomingKeys = incoming.map((e) => {
              const key = eventDedupKey(e)
              return key === null ? '__dedup_' + (dedupCounterRef.current++) : key
            })
            setAlarmEvents((prev) => {
              const seen = new Set()
              for (const e of prev) {
                const key = eventDedupKey(e)
                if (key !== null) seen.add(key)
              }
              const fresh = []
              for (let i = 0; i < incoming.length; i++) {
                const key = incomingKeys[i]
                if (seen.has(key)) continue
                seen.add(key)
                fresh.push(incoming[i])
              }
              return [...fresh, ...prev].slice(0, 100)
            })
          }

          es.onerror = () => {
            if (controller.signal.aborted) return
            setSseStatus('closed')
            if (es) es.close()
            // 断线 5s 自动重连（重连会取新流令牌）
            if (!closed) retryTimer = setTimeout(subscribe, 5000)
          }
        })
        .catch(() => {
          if (controller.signal.aborted || closed) return
          setSseStatus('closed')
          // 取令牌失败（过期 / 网络抖动）与断线同路：5s 后重试
          if (!closed) retryTimer = setTimeout(subscribe, 5000)
        })
    }

    subscribe()

    return () => {
      closed = true
      controller.abort()
      clearTimeout(retryTimer)
      if (es) es.close()
    }
  }, [])

  // ===== 轮询安防设备列表 =====
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await listSurveillanceDevices()
        if (cancelled) return
        const list = Array.isArray(data) ? data : (data && data.devices) || []
        setSurvDevices(list)
        setSurvDevicesLoading(false)
        if (list.length > 0 && !list.some((d) => d.id === selectedSurvDeviceId)) {
          setSelectedSurvDeviceId(list[0].id)
        }
      } catch (e) {
        if (cancelled) return
        setSurvDevicesLoading(false)
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ===== 轮询安防事件（#106：按当前选中设备过滤）=====
  // 后端 GET /api/v1/surveillance/events 现已委托给 AlarmEventStore，真正返回数据，
  // 且 deviceId 过滤**真正生效**（此前该参数声明了却恒返回空）。
  // 本面板按「当前选中设备」过滤，与右栏「报警事件」（全局 + 确认/应急/侦察操作）分工：
  // 这里回答「这台设备发生过什么」，那里回答「全局有哪些报警需要处置」。
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        // 参数名是 size 不是 limit —— 此前传 limit 被后端忽略，恒取默认 20。
        // URLSearchParams 会跳过 undefined，设备列表还没加载完时等于不过滤。
        const data = await listSurveillanceEvents({
          size: 20,
          ...(selectedSurvDeviceId ? { deviceId: selectedSurvDeviceId } : {}),
        })
        if (cancelled) return
        const list = Array.isArray(data)
          ? data
          : (data && (data.items || data.events)) || []
        setSurvEvents(list)
      } catch (e) {
        // 静默失败
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
    // 设备切换要重新拉取 —— 否则面板会一直显示上一台设备的事件
  }, [selectedSurvDeviceId])

  // ===== 轮询报警规则 + 联动日志 =====
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const [rulesData, logsData] = await Promise.all([
          listAlarmRules().catch(() => []),
          listLinkageLogs({ limit: 20 }).catch(() => []),
        ])
        if (cancelled) return
        setAlarmRules(Array.isArray(rulesData) ? rulesData : (rulesData && rulesData.rules) || [])
        setLinkageLogs(Array.isArray(logsData) ? logsData : (logsData && logsData.logs) || [])
      } catch (e) {
        // 静默失败
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ===== 轮询无人机列表 =====
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await api.listDrones()
        if (cancelled) return
        const list = Array.isArray(data) ? data : (data && data.drones) || []
        setDrones(list)
      } catch (e) {
        // 静默失败
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ===== 轮询 Mesh 拓扑 + 灾害通信 =====
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const [topo, lnks, dStatus, dClusters, dLinks] = await Promise.all([
          api.getMeshTopology().catch(() => null),
          api.getMeshLinks().catch(() => null),
          disaster.getStatus().catch(() => null),
          disaster.getClusters().catch(() => null),
          disaster.getLinks().catch(() => null),
        ])
        if (cancelled) return
        setMeshTopology(topo)
        setMeshLinks(lnks)
        setDisasterStatus(dStatus)
        setDisasterClusters(dClusters)
        setDisasterLinks(dLinks)
      } catch (e) {
        // 静默失败
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ===== 轮询空地态势融合数据 =====
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await getAirGroundSituation()
        if (cancelled) return
        setAirGroundSituation(data)
      } catch (e) {
        // 静默失败
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ===== 加载场景预设 =====
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await emergencyOrch.getScenarios()
        if (cancelled) return
        const list = Array.isArray(data) ? data : (data && data.scenarios) || []
        setScenarios(list)
      } catch (e) {
        // 静默失败
      }
    }
    load()
    return () => { cancelled = true }
  }, [])

  // ===== 轮询编排计划状态 =====
  const planId = activePlan && activePlan.planId
  useEffect(() => {
    if (!planId) return
    let cancelled = false
    let timer = null

    const poll = async () => {
      try {
        const [plan, cov, pq] = await Promise.all([
          emergencyOrch.getPlan(planId).catch(() => null),
          emergencyOrch.getCoverage(planId).catch(() => null),
          emergencyOrch.getPriorityQueue(planId).catch(() => null),
        ])
        if (cancelled) return
        if (plan) {
          setActivePlan((prev) => ({ ...(plan || {}), planId: plan.planId || (prev && prev.planId) }))
          const newEvents = Array.isArray(plan.events) ? plan.events
            : Array.isArray(plan.recentEvents) ? plan.recentEvents
            : Array.isArray(plan.eventLog) ? plan.eventLog : []
          const pid = plan.planId || planId
          const cur = orchEventsRef.current
          if (cur.planId !== pid) {
            // 换计划了：整体替换，别拿旧计划的长度去切新计划
            orchEventsRef.current = { planId: pid, count: newEvents.length }
            setOrchEvents(newEvents.slice(-100))
          } else if (newEvents.length > cur.count) {
            // 事件列表是**只追加**的（后端只在建计划时 plan.put("events", …) 一次，
            // 之后只有 addEvent 的 .add()），所以事件的身份是**位置**而不是内容。
            //
            // 此前的去重键是 (e.ts||e.timestamp) + '|' + (e.message||e.msg)，
            // 而后端 events 是 List<String>（EmergencyOrchService:572 直接
            // ((List<String>) plan.get("events")).add(event)）⇒ 每条都是字符串，
            // e.ts / e.message 全是 undefined ⇒ 键恒为 '|' ⇒ seen 集合最多一个元素
            // ⇒ 除第一条外全部被判为重复 ⇒ 所有事件并成一条。
            //
            // 也不能改用"内容=字符串"做键：事件串可以重复（"phase 0->1 status=RUNNING"、
            // "priority adjust: task=5 EMERGENCY->COMMAND"），那会把真事件吞掉。
            const fresh = newEvents.slice(cur.count)
            orchEventsRef.current = { planId: pid, count: newEvents.length }
            setOrchEvents((prev) => [...prev, ...fresh].slice(-100))
          }
          const status = String(plan.status || '').toUpperCase()
          if (TERMINAL_STATUS.has(status)) {
            if (timer) { clearInterval(timer); timer = null }
          }
        }
        if (cov) setCoverage(cov)
        if (pq) setPriorityQueue(pq)
      } catch (e) {
        // 静默失败
      }
    }

    poll()
    timer = setInterval(poll, POLL_MS)
    return () => {
      cancelled = true
      if (timer) clearInterval(timer)
    }
  }, [planId])

  // ===== 一键启动编排 =====
  const handleStartOrch = useCallback(async () => {
    setError(null)
    // 后端 type 是 int；列表还没到位时不猜，直接不让发。
    if (selectedScenarioType == null) {
      setError('请先选择应急场景')
      return
    }
    setStarting(true)
    try {
      // 后端契约（EmergencyOrchController.startScenario 与 EmergencyOrchService.start 的
      // Javadoc 明写）：centerLat/centerLon 是 **1E7 度**，radius 是 **米**。
      // 原先这里发的是 centerLat(度) / centerLon(度) / radiusKm(km)，两处都错：
      //   * `radiusKm` 这个键后端**根本没读**（它 numInt(body, "radius", 0)）
      //     ⇒ 半径被静默忽略，service 回退到预设默认值；
      //   * 单位也没换算 ⇒ 即使键名修对，5 也会被当成 5 米。
      // 状态本身保持「度 / km」不动，因为 handleTriggerPtzTracking 用的是同一份
      // centerLat/centerLon，换算只做在这一处调用点。
      const body = {
        centerLat: Math.round(Number(centerLat) * 1e7),
        centerLon: Math.round(Number(centerLon) * 1e7),
        radius: Math.round(Number(radiusKm) * 1000),
      }
      const res = await emergencyOrch.startScenario(selectedScenarioType, body)
      const newPlanId = res && res.planId
      if (!newPlanId) throw new Error('启动失败：后端未返回 planId')
      setActivePlan({
        planId: newPlanId,
        status: (res && res.status) || 'STARTING',
        phase: (res && res.phase) || 'survey',
        ...res,
      })
      setCoverage(null)
      setPriorityQueue(null)
      setOrchEvents([])
    } catch (e) {
      setError('启动编排失败：' + e.message)
    } finally {
      setStarting(false)
    }
  }, [selectedScenarioType, centerLat, centerLon, radiusKm])

  // ===== 紧急停止编排 =====
  const handleAbortOrch = useCallback(async () => {
    if (!activePlan || !activePlan.planId) return
    setAborting(true)
    try {
      await emergencyOrch.abort(activePlan.planId)
      setActivePlan((prev) => ({ ...(prev || {}), status: 'ABORTED' }))
    } catch (e) {
      setError('紧急停止失败：' + e.message)
    } finally {
      setAborting(false)
    }
  }, [activePlan && activePlan.planId])

  // ===== PTZ 控制 =====
  const handlePtz = useCallback(async (cmd) => {
    if (!selectedSurvDeviceId) return
    setPtzBusy(true)
    try {
      await ptzControl(selectedSurvDeviceId, cmd)
    } catch (e) {
      setError('PTZ 控制失败：' + e.message)
    } finally {
      setPtzBusy(false)
    }
  }, [selectedSurvDeviceId])

  // ===== 确认报警 =====
  const handleAckAlarm = useCallback(async (eventId) => {
    try {
      await acknowledgeAlarm(eventId)
      setAlarmEvents((prev) => prev.map((e) => ((e.id || e.eventId) === eventId ? { ...e, acknowledged: true } : e)))
    } catch (e) {
      setError('确认报警失败：' + e.message)
    }
  }, [])

  // ===== 一键应急响应 =====
  const handleEmergencyRespond = useCallback(async (eventId) => {
    setResponding(true)
    setError(null)
    try {
      await triggerEmergencyResponse(eventId)
      setAlarmEvents((prev) => prev.map((e) => ((e.id || e.eventId) === eventId ? { ...e, responded: true } : e)))
    } catch (e) {
      setError('应急响应触发失败：' + e.message)
    } finally {
      setResponding(false)
    }
  }, [])

  // ===== 报警触发无人机侦察 =====
  const handleTriggerRecon = useCallback(async (eventId) => {
    setResponding(true)
    setError(null)
    try {
      // 后端 POST /api/v1/air-ground/recon 要目标坐标（body 里的 lat/lon）。
      // 没有坐标就**不触发**：后端缺省 lat/lon=0 ⇒ 侦察计划会落到 (0,0)，
      // 无人机飞往几内亚湾（2026-10-21 实测口径，不是推测）。
      const ev = alarmEventsRef.current.find((e) => (e.id || e.eventId) === eventId)
      const lat = ev?.lat ?? ev?.latitude
      const lon = ev?.lon ?? ev?.longitude
      if (lat == null || lon == null) {
        setError('该告警没有坐标，无法定位侦察点')
        return
      }
      await triggerReconFromAlarm(eventId, {
        lat,
        lon,
        alt: ev?.alt ?? ev?.altitude ?? 0,
        sourceDeviceId: ev?.sourceDeviceId ?? ev?.deviceId ?? '',
        sourceDeviceName: ev?.sourceDeviceName ?? ev?.deviceName ?? '',
        eventType: ev?.eventType ?? ev?.type ?? 'CUSTOM',
        severity: ev?.severity ?? 'WARN',
        description: ev?.description ?? ev?.message ?? '',
      })
      setAlarmEvents((prev) => prev.map((e) => ((e.id || e.eventId) === eventId ? { ...e, reconTriggered: true } : e)))
    } catch (e) {
      setError('触发无人机侦察失败：' + e.message)
    } finally {
      setResponding(false)
    }
  }, [])

  // ===== PTZ 自动跟踪联动 =====
  const handleTriggerPtzTracking = useCallback(async (deviceId, targetLat, targetLon) => {
    setPtzBusy(true)
    setError(null)
    try {
      await triggerPtzTracking(deviceId, { targetLat, targetLon })
    } catch (e) {
      setError('PTZ 跟踪联动失败：' + e.message)
    } finally {
      setPtzBusy(false)
    }
  }, [])

  // ===== 派生数据 =====
  const selectedSurvDevice = useMemo(
    () => survDevices.find((d) => d.id === selectedSurvDeviceId),
    [survDevices, selectedSurvDeviceId]
  )

  const planStatus = String((activePlan && activePlan.status) || '').toUpperCase()
  const isRunning = !!planId && !TERMINAL_STATUS.has(planStatus)
  // 场景选项：后端真值优先，拉取失败才退到兜底清单。
  // label 兼容两种键名：后端是 name，旧硬编码是 label。
  const scenarioOptions = useMemo(() => {
    const list = scenarios.length > 0 ? scenarios : SCENARIO_TYPE_FALLBACK
    return list.map((s) => ({ type: s.type, label: s.name || s.label || '--', defaults: s.defaults || null }))
  }, [scenarios])
  const selectedScenario = useMemo(
    () => scenarioOptions.find((s) => s.type === selectedScenarioType) || null,
    [scenarioOptions, selectedScenarioType]
  )
  const scenarioDefaults = selectedScenario && selectedScenario.defaults
  // 列表到位后自动选中第一项，让按钮在数据到达后立刻可用；
  // 在此之前 selectedScenarioType 为 null ⇒ 按钮禁用，不猜后端的 int。
  useEffect(() => {
    if (selectedScenarioType == null && scenarioOptions.length > 0) {
      setSelectedScenarioType(scenarioOptions[0].type)
    }
  }, [scenarioOptions, selectedScenarioType])
  const phases = (activePlan && activePlan.phases) || (activePlan && activePlan.phaseProgress) || []
  const coveragePct = (coverage && (coverage.coveragePct ?? coverage.coverage)) ?? null
  const connectivityPct = (coverage && (coverage.connectivityPct ?? coverage.connectivity)) ?? null
  const queueTasks =
    (priorityQueue && priorityQueue.tasks) ||
    (priorityQueue && priorityQueue.queue) ||
    (Array.isArray(priorityQueue) ? priorityQueue : [])

  const getPhaseStatus = (phaseKey) => {
    if (Array.isArray(phases) && phases.length > 0) {
      const found = phases.find((p) => (p.key || p.phase || p.name) === phaseKey)
      if (found) return String(found.status || 'pending').toLowerCase()
    }
    const currentPhase = activePlan && activePlan.phase
    if (currentPhase) {
      const idx = PHASES.findIndex((p) => p.key === currentPhase)
      const targetIdx = PHASES.findIndex((p) => p.key === phaseKey)
      if (idx >= 0 && targetIdx >= 0) {
        if (targetIdx < idx) return 'done'
        if (targetIdx === idx) return planStatus === 'FAILED' ? 'failed' : 'running'
        return 'pending'
      }
    }
    return 'pending'
  }

  // 优先级分组
  const groupedTasks = useMemo(() => {
    const groups = {}
    for (const t of queueTasks) {
      const key = normPriority(t.priority)
      if (!groups[key]) groups[key] = []
      groups[key].push(t)
    }
    return Object.entries(groups).sort(
      (a, b) => ((PRIORITY_META[a[0]] && PRIORITY_META[a[0]].weight) || 99) - ((PRIORITY_META[b[0]] && PRIORITY_META[b[0]].weight) || 99)
    )
  }, [queueTasks])

  // 灾害模式是否激活
  const isDisasterActive = disasterStatus?.mode === 'active' || disasterStatus?.active === true
  const recoveryRate = disasterStatus?.recoveryRate ?? disasterStatus?.recovery ?? 0

  // 灾害聚簇列表（#104：此前 setDisasterClusters 有写入但全组件无人读）
  const disasterClusterList = useMemo(() => {
    if (!disasterClusters) return []
    return Array.isArray(disasterClusters) ? disasterClusters
      : (disasterClusters.clusters || [])
  }, [disasterClusters])

  // 编排事件文案（#104：后端 events 是 List<String>，但也兼容对象形态）
  const orchEventRows = useMemo(
    () => orchEvents.map((e) => (
      typeof e === 'string' || typeof e === 'number'
        ? String(e)
        : (e.message || e.msg || e.description || e.title || e.type || '--')
    )),
    [orchEvents]
  )

  // 安防事件面板的作用域（#106）：有选中设备就只看该设备，否则看全部。
  // 空态因此要分三种说法，不能一律写「暂无」——「还没选设备」和「这台设备确实
  // 没出过事」对使用者是完全不同的信息。
  const survEventScope = selectedSurvDeviceId
    ? (selectedSurvDevice && (selectedSurvDevice.name || selectedSurvDevice.id)) || selectedSurvDeviceId
    : null

  // #107：选中设备的能力三态。ptzSupport === false 表示设备**明确声明**不支持，
  // 这时才禁用；undefined/null 表示未声明（未知），保持可用 —— 与后端
  // SurveillanceController 的「不明确不支持即放行」口径一致。
  const ptzSupport = selectedSurvDevice ? selectedSurvDevice.ptzSupport : undefined
  // 声明的能力（已归一）拼成可读文本，用于"为什么按钮不可用"的说明
  const declaredCapsText = Array.isArray(selectedSurvDevice?.capabilities)
    ? selectedSurvDevice.capabilities.join(' / ')
    : ''

  // Mesh 节点列表
  const meshNodes = useMemo(() => {
    if (!meshTopology) return []
    return Array.isArray(meshTopology) ? meshTopology :
      (meshTopology?.nodes || meshTopology?.topology || [])
  }, [meshTopology])

  // Mesh 链路列表
  const meshLinkList = useMemo(() => {
    if (!meshLinks) return []
    return Array.isArray(meshLinks) ? meshLinks :
      (meshLinks?.links || [])
  }, [meshLinks])

  // 灾害通信链路列表
  const disasterLinkList = useMemo(() => {
    if (!disasterLinks) return []
    return Array.isArray(disasterLinks) ? disasterLinks :
      (disasterLinks?.links || disasterLinks?.bridges || [])
  }, [disasterLinks])

  // 灾区连通率
  const connectivityRate = useMemo(() => {
    const allLinks = [...meshLinkList, ...disasterLinkList]
    if (allLinks.length === 0) return 0
    const goodLinks = allLinks.filter((l) => {
      const q = l.quality || l.linkQuality
      return q === 'EXCELLENT' || q === 'GOOD'
    })
    return Math.round((goodLinks.length / allLinks.length) * 100)
  }, [meshLinkList, disasterLinkList])

  // 未确认报警数
  const unackAlarmCount = useMemo(() => alarmEvents.filter((e) => !e.acknowledged).length, [alarmEvents])

  // 在线安防设备数
  const onlineSurvCount = useMemo(() => survDevices.filter((d) => d.status === 'online').length, [survDevices])

  // 在线无人机数
  const onlineDroneCount = useMemo(() => drones.filter((d) => d.online).length, [drones])

  return (
    <div className="page" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {/* 标题栏 + 全局状态指示 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <h2 className="page-title">
          空地一体化应急指挥面板
        </h2>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          {/* SSE 状态 */}
          <span style={{
            fontSize: 9, padding: '2px 6px', borderRadius: 3,
            border: `1px solid ${sseStatus === 'open' ? 'var(--ok)' : 'var(--warn)'}`,
            color: sseStatus === 'open' ? 'var(--ok)' : 'var(--warn)',
          }}>
            ● SSE {sseStatus === 'open' ? '已连接' : sseStatus === 'connecting' ? '连接中' : '断线重连'}
          </span>
          {/* 灾害模式 */}
          <span style={{
            fontSize: 9, padding: '2px 6px', borderRadius: 3,
            border: `1px solid ${isDisasterActive ? 'var(--crit)' : 'var(--ok)'}`,
            color: isDisasterActive ? 'var(--crit)' : 'var(--ok)',
          }}>
            {isDisasterActive ? '● 灾害模式' : '○ 常规模式'}
          </span>
          {/* 设备统计 */}
          <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>
            安防 <b style={{ color: 'var(--ok)' }}>{onlineSurvCount}</b>/{survDevices.length}
            {' · '}无人机 <b style={{ color: 'var(--ok)' }}>{onlineDroneCount}</b>/{drones.length}
            {' · '}未确认报警 <b style={{ color: unackAlarmCount > 0 ? 'var(--warn)' : 'var(--ok)' }}>{unackAlarmCount}</b>
          </span>
        </div>
      </div>

      {/* 错误提示 */}
      {error && (
        <div style={{
          color: 'var(--crit)', fontSize: 11, padding: '4px 8px',
          background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)',
        }}>
          ⚠ {error}
        </div>
      )}

      {/* ===== 主三栏区域 ===== */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: '220px 1fr 260px',
        gap: 8,
        minHeight: 360,
      }}>
        {/* ===== 左栏：安防设备列表 ===== */}
        <div style={{ ...cardStyle, padding: 6, display: 'flex', flexDirection: 'column', gap: 4, overflow: 'hidden' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={labelStyle}>安防设备（{survDevices.length}）</span>
          </div>
          {survDevicesLoading ? (
            <div className="empty-hint">加载中…</div>
          ) : survDevices.length === 0 ? (
            <div className="empty-hint">暂无安防设备</div>
          ) : (
            <div style={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>
              {survDevices.map((d) => {
                const isSel = d.id === selectedSurvDeviceId
                const color = ONLINE_COLOR[d.status || 'offline'] || 'var(--dim)'
                return (
                  <div
                    key={d.id}
                    onClick={() => setSelectedSurvDeviceId(d.id)}
                    style={{
                      padding: '4px 6px', marginBottom: 2, borderRadius: 3, cursor: 'pointer',
                      background: isSel ? 'var(--bg-2)' : 'transparent',
                      border: `1px solid ${isSel ? 'var(--cyan)' : 'transparent'}`,
                      display: 'flex', alignItems: 'center', gap: 6,
                    }}
                  >
                    <span style={{ color, fontSize: 10 }}>●</span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{
                        fontSize: 11, color: isSel ? 'var(--cyan)' : 'var(--text)',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                      }}>
                        {d.name || d.id}
                      </div>
                      <div style={{
                        fontSize: 9, color: 'var(--dim-2)',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                      }}>
                        {d.vendor || '--'} · {d.ip || '--'}
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          )}

          {/* PTZ 控制（选中设备时显示） */}
          {selectedSurvDevice && (
            <div style={{ borderTop: '1px solid var(--line-2)', paddingTop: 6, marginTop: 4 }}>
              <div style={{ ...labelStyle, marginBottom: 4 }}>
                PTZ · {selectedSurvDevice.name || selectedSurvDevice.id}
              </div>
              {/* #107：设备明确声明「不支持 PTZ」时禁用按钮并说明原因，
                  而不是让运维点了之后收一个无信息量的 400。
                  ptzSupport 为 null = 设备未声明（未知）⇒ 保持可用 —— 
                  后端口径同样是「不明确不支持即放行」，两边一致。 */}
              {ptzSupport === false && (
                <div className="empty-hint" style={{ marginBottom: 4 }}>
                  该设备声明的能力里没有 PTZ（{declaredCapsText || '未声明任何能力'}），云台按钮不可用
                </div>
              )}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 28px)', gap: 3, justifyContent: 'center' }}>
                {PTZ_BUTTONS.map((b) => (
                  <button
                    key={b.cmd}
                    onClick={() => handlePtz(b.cmd)}
                    disabled={ptzBusy || ptzSupport === false}
                    title={ptzSupport === false ? '设备不支持云台控制' : b.title}
                    style={{
                      width: 28, height: 24, fontSize: 11,
                      cursor: (ptzBusy || ptzSupport === false) ? 'not-allowed' : 'pointer',
                      border: '1px solid var(--line-2)', background: 'var(--bg-3)',
                      color: 'var(--text)', borderRadius: 3,
                      opacity: (ptzBusy || ptzSupport === false) ? 0.5 : 1,
                    }}
                  >
                    {b.label}
                  </button>
                ))}
              </div>
              <div style={{ display: 'flex', gap: 3, justifyContent: 'center', marginTop: 3 }}>
                {PTZ_ZOOM.map((b) => (
                  <button
                    key={b.cmd}
                    onClick={() => handlePtz(b.cmd)}
                    disabled={ptzBusy || ptzSupport === false}
                    title={ptzSupport === false ? '设备不支持云台控制' : b.title}
                    style={{
                      width: 44, height: 22, fontSize: 11,
                      cursor: (ptzBusy || ptzSupport === false) ? 'not-allowed' : 'pointer',
                      border: '1px solid var(--line-2)', background: 'var(--bg-3)',
                      color: 'var(--text)', borderRadius: 3,
                      opacity: (ptzBusy || ptzSupport === false) ? 0.5 : 1,
                    }}
                  >
                    {b.label}
                  </button>
                ))}
              </div>
              {/* PTZ 自动跟踪联动按钮 */}
              <button
                onClick={() => handleTriggerPtzTracking(selectedSurvDeviceId, centerLat, centerLon)}
                disabled={ptzBusy}
                title="PTZ 自动跟踪检测目标"
                style={{
                  width: '100%', height: 22, fontSize: 9, marginTop: 4,
                  cursor: ptzBusy ? 'not-allowed' : 'pointer',
                  border: '1px solid var(--cyan)', background: 'var(--bg-3)',
                  color: ptzBusy ? 'var(--dim)' : 'var(--cyan)', borderRadius: 3,
                  opacity: ptzBusy ? 0.5 : 1,
                }}
              >
                PTZ 跟踪联动
              </button>
            </div>
          )}
        </div>

        {/* ===== 中栏：空地协同地图 ===== */}
        <div style={{ ...cardStyle, padding: 0, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
          <div style={{
            padding: '4px 8px', borderBottom: '1px solid var(--line-2)',
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          }}>
            <span style={labelStyle}>空地协同地图</span>
            <div style={{ display: 'flex', gap: 6, fontSize: 9 }}>
              <span style={{ color: 'var(--cyan)' }}>● 无人机</span>
              <span style={{ color: 'var(--ok)' }}>● 安防摄像头</span>
              <span style={{ color: '#9B59B6' }}>● 基站</span>
              <span style={{ color: 'var(--warn)' }}>━ Mesh链路</span>
              <span style={{ color: 'var(--crit)' }}>━ 灾区边界</span>
            </div>
          </div>
          <div style={{
            flex: 1, position: 'relative', background: '#0a0e1a',
            minHeight: 280, overflow: 'hidden',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            {/* 地图占位：实际部署时替换为 MapLibre 渲染 */}
            <div style={{
              position: 'absolute', inset: 0,
              backgroundImage: 'radial-gradient(circle at 30% 40%, rgba(0,212,255,0.06) 0%, transparent 50%), radial-gradient(circle at 70% 60%, rgba(46,204,113,0.04) 0%, transparent 50%)',
            }} />

            {/* SVG 叠加层：安防摄像头位置 + 覆盖范围 */}
            <svg width="100%" height="100%" viewBox="0 0 600 360" style={{ position: 'absolute', inset: 0 }}>
              {/* 安防摄像头位置 + 覆盖范围 */}
              {survDevices.filter((d) => d.status === 'online' && d.lat != null && d.lon != null).map((d, i) => {
                const x = 100 + (i % 4) * 120
                const y = 80 + Math.floor(i / 4) * 100
                return (
                  <g key={`cam-${d.id}`}>
                    <circle cx={x} cy={y} r="30" fill="rgba(46,204,113,0.08)" stroke="rgba(46,204,113,0.3)" strokeWidth="1" strokeDasharray="3 2" />
                    <circle cx={x} cy={y} r="5" fill="#2ECC71" stroke="var(--text)" strokeWidth="1" />
                    <text x={x + 8} y={y + 3} fontSize="8" fill="var(--dim)" fontFamily="var(--mono)">{d.name || d.id}</text>
                  </g>
                )
              })}

              {/* 无人机位置 + 航迹 */}
              {drones.filter((d) => d.online && d.lat != null && d.lon != null).map((d, i) => {
                const x = 200 + (i % 3) * 150
                const y = 120 + Math.floor(i / 3) * 80
                return (
                  <g key={`drone-${d.sysid || i}`}>
                    {/* 航迹线 */}
                    <path d={`M ${x - 40} ${y + 20} Q ${x - 20} ${y - 10} ${x} ${y}`} fill="none" stroke="rgba(0,212,255,0.4)" strokeWidth="1" strokeDasharray="4 2" />
                    <circle cx={x} cy={y} r="6" fill="#00d4ff" stroke="var(--text)" strokeWidth="1" />
                    <text x={x + 9} y={y + 3} fontSize="8" fill="var(--cyan)" fontFamily="var(--mono)">UAV-{d.sysid || i + 1}</text>
                  </g>
                )
              })}

              {/* Mesh 拓扑连线 */}
              {meshLinkList.slice(0, 8).map((l, i) => {
                const x1 = 80 + (i % 4) * 140
                const y1 = 60 + Math.floor(i / 4) * 120
                const x2 = x1 + 80 + (i % 2) * 40
                const y2 = y1 + 60
                const q = l.quality || l.linkQuality || 'GOOD'
                const color = q === 'EXCELLENT' ? '#2ECC71' : q === 'GOOD' ? '#3498DB' : q === 'FAIR' ? '#F39C12' : '#E74C3C'
                return (
                  <line key={`mesh-${i}`} x1={x1} y1={y1} x2={x2} y2={y2}
                    stroke={color} strokeWidth="1.5" opacity="0.6" strokeDasharray="5 3" />
                )
              })}

              {/* 基站覆盖区域 */}
              {meshNodes.filter((n) => n.role === 'base' || n.role === 'gateway').slice(0, 3).map((n, i) => {
                const x = 150 + i * 200
                const y = 200
                return (
                  <g key={`bs-${n.sysid || i}`}>
                    <circle cx={x} cy={y} r="50" fill="rgba(155,89,182,0.06)" stroke="rgba(155,89,182,0.3)" strokeWidth="1" />
                    <rect x={x - 5} y={y - 5} width="10" height="10" fill="#9B59B6" stroke="var(--text)" strokeWidth="1" />
                    <text x={x + 8} y={y + 3} fontSize="8" fill="#9B59B6" fontFamily="var(--mono)">基站</text>
                  </g>
                )
              })}

              {/* 灾区边界（编排激活时显示） */}
              {activePlan && centerLat != null && (
                <g key="disaster-zone">
                  <ellipse cx="300" cy="180" rx="120" ry="80" fill="none" stroke="var(--crit)" strokeWidth="2" strokeDasharray="8 4" opacity="0.6" />
                  <text x="300" y="170" fontSize="10" fill="var(--crit)" textAnchor="middle" fontFamily="var(--mono)">灾区边界</text>
                </g>
              )}
            </svg>

            {/* 地图中心提示 */}
            <div style={{
              position: 'relative', zIndex: 1, textAlign: 'center',
              color: 'var(--dim-2)', fontSize: 10, pointerEvents: 'none',
            }}>
              空地协同态势视图
              <div style={{ fontSize: 9, marginTop: 4 }}>
                {survDevices.filter((d) => d.status === 'online').length} 个在线摄像头 · {drones.filter((d) => d.online).length} 架在线无人机 · {meshNodes.length} 个 Mesh 节点
              </div>
            </div>
          </div>
        </div>

        {/* ===== 右栏：无人机列表 + 报警事件 + 联动规则 ===== */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, overflow: 'hidden' }}>
          {/* 无人机列表 */}
          <div style={{ ...cardStyle, padding: 6, flex: '0 0 auto' }}>
            <div style={labelStyle}>无人机列表（{drones.length}）</div>
            <div style={{ maxHeight: 120, overflowY: 'auto' }}>
              {drones.length === 0 ? (
                <div className="empty-hint">暂无无人机</div>
              ) : (
                drones.slice(0, 8).map((d, i) => {
                  const sysid = d.sysid || d.id || i
                  const status = String(d.status || d.state || (d.online ? 'ONLINE' : 'OFFLINE')).toUpperCase()
                  const statusColor = DRONE_STATUS_COLOR[status] || (d.online ? 'var(--ok)' : 'var(--dim)')
                  const batt = d.batteryPct != null ? d.batteryPct : (d.battery != null ? d.battery : null)
                  return (
                    <div key={sysid} style={{
                      padding: '3px 4px', display: 'flex', alignItems: 'center', gap: 4,
                      borderBottom: '1px solid var(--line-2)',
                    }}>
                      <span style={{ color: statusColor, fontSize: 9 }}>●</span>
                      <span style={{ fontSize: 10, color: 'var(--text)', fontFamily: 'var(--mono)', flex: 1 }}>
                        UAV-{sysid}
                      </span>
                      <span style={{ fontSize: 9, color: battColorVar(batt), fontFamily: 'var(--mono)' }}>
                        {batt != null ? `${Number(batt).toFixed(0)}%` : '--'}
                      </span>
                      <span style={{ fontSize: 9, color: statusColor }}>
                        {status}
                      </span>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 报警事件列表 */}
          <div style={{ ...cardStyle, padding: 6, flex: '1 1 auto', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={labelStyle}>报警事件（{alarmEvents.length}）</span>
              {unackAlarmCount > 0 && (
                <span style={{ fontSize: 9, color: 'var(--warn)' }}>{unackAlarmCount} 未确认</span>
              )}
            </div>
            <div style={{ flex: 1, overflowY: 'auto', minHeight: 0, marginTop: 4 }}>
              {alarmEvents.length === 0 ? (
                <div className="empty-hint">暂无报警事件</div>
              ) : (
                alarmEvents.slice(0, 30).map((e, i) => {
                  const eid = e.id || e.eventId || i
                  const sev = normSeverity(e.severity)
                  const meta = SEVERITY_META[sev] || SEVERITY_META.INFO
                  const isSel = eid === selectedAlarmId
                  const ts = e.ts != null ? e.ts : e.timestamp
                  const time = ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--'
                  return (
                    <div
                      key={eid}
                      onClick={() => setSelectedAlarmId(eid)}
                      style={{
                        padding: '4px 6px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-2)' : 'transparent',
                        borderLeft: `3px solid ${meta.color}`,
                        opacity: e.acknowledged ? 0.6 : 1,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 4 }}>
                        <div style={{ display: 'flex', gap: 4, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 8, color: meta.color, fontWeight: 'bold', flexShrink: 0 }}>[{meta.label}]</span>
                          <span style={{ fontSize: 10, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {e.message || e.description || e.title || e.type || '--'}
                          </span>
                        </div>
                        <span style={{ fontSize: 8, color: 'var(--dim-2)', flexShrink: 0, fontFamily: 'var(--mono)' }}>{time}</span>
                      </div>
                      {/* 选中事件的操作按钮 */}
                      {isSel && (
                        <div style={{ display: 'flex', gap: 4, marginTop: 4 }}>
                          {!e.acknowledged && (
                            <button
                              onClick={(ev) => { ev.stopPropagation(); handleAckAlarm(eid) }}
                              style={{ ...miniBtnStyle, fontSize: 9, padding: '1px 6px' }}
                            >
                              确认
                            </button>
                          )}
                          <button
                            onClick={(ev) => { ev.stopPropagation(); handleEmergencyRespond(eid) }}
                            disabled={responding}
                            style={{
                              ...miniBtnStyle, fontSize: 9, padding: '1px 6px',
                              border: '1px solid var(--crit)',
                              color: responding ? 'var(--dim)' : 'var(--crit)',
                              cursor: responding ? 'not-allowed' : 'pointer',
                              opacity: responding ? 0.5 : 1,
                            }}
                          >
                            应急响应
                          </button>
                          <button
                            onClick={(ev) => { ev.stopPropagation(); handleTriggerRecon(eid) }}
                            disabled={responding}
                            style={{
                              ...miniBtnStyle, fontSize: 9, padding: '1px 6px',
                              border: '1px solid var(--cyan)',
                              color: responding ? 'var(--dim)' : 'var(--cyan)',
                              cursor: responding ? 'not-allowed' : 'pointer',
                              opacity: responding ? 0.5 : 1,
                            }}
                          >
                            触发侦察
                          </button>
                        </div>
                      )}
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 联动规则概要 */}
          <div style={{ ...cardStyle, padding: 6, flex: '0 0 auto' }}>
            <div style={labelStyle}>联动规则（{alarmRules.length}）</div>
            <div style={{ maxHeight: 80, overflowY: 'auto' }}>
              {alarmRules.length === 0 ? (
                <div className="empty-hint">暂无联动规则</div>
              ) : (
                alarmRules.slice(0, 5).map((r, i) => (
                  <div key={r.id || i} style={{
                    padding: '2px 4px', fontSize: 9, color: 'var(--dim)',
                    borderBottom: '1px solid var(--line-2)',
                    display: 'flex', justifyContent: 'space-between',
                  }}>
                    <span>{r.name || r.alarmType || '--'}</span>
                    <span style={{ color: 'var(--cyan)' }}>{r.actionType || r.action || '--'}</span>
                  </div>
                ))
              )}
            </div>
          </div>
        {/* ===== #104 接通渲染 / #106 改为按选中设备过滤 ===== */}
          <div style={{ ...cardStyle, padding: 6, flex: '0 0 auto' }}>
            <div style={labelStyle}>
              安防事件（{survEventScope ? `${survEventScope} · ` : '全部 · '}{survEvents.length}）
            </div>
            <div style={{ maxHeight: 80, overflowY: 'auto' }}>
              {survEvents.length === 0 ? (
                <div className="empty-hint">
                  {!survEventScope
                    ? '未选中设备 —— 左侧点选一台设备即可只看它的安防事件'
                    : `设备 ${survEventScope} 暂无安防事件（不等于全局无事件，右栏「报警事件」是全局视图）`}
                </div>
              ) : (
                survEvents.slice(0, 20).map((e, i) => {
                  const ev = typeof e === 'string' ? { description: e } : (e || {})
                  const eid = ev.id ?? ev.eventId ?? i
                  const sev = normSeverity(ev.severity)
                  const meta = SEVERITY_META[sev] || SEVERITY_META.INFO
                  const ts = ev.timestampMs ?? ev.timestamp ?? ev.ts
                  const time = ts ? new Date(Number(ts)).toLocaleTimeString() : ''
                  const dev = ev.sourceDeviceName || ev.sourceDeviceId || ''
                  return (
                    <div key={eid} style={{
                      padding: '2px 4px', fontSize: 9, color: 'var(--dim)',
                      borderBottom: '1px solid var(--line-2)',
                      display: 'flex', justifyContent: 'space-between', gap: 4,
                    }}>
                      <span style={{
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      }}>
                        <span style={{ color: meta.color, fontWeight: 'bold' }}>
                          [{meta.label}]
                        </span>
                        {' '}
                        {ev.eventType ? `${ev.eventType} ` : ''}
                        {ev.description || ev.message || ev.title || '--'}
                        {dev ? ` @${dev}` : ''}
                      </span>
                      {time && (
                        <span style={{ color: 'var(--dim-2)', flexShrink: 0, fontFamily: 'var(--mono)' }}>
                          {time}
                        </span>
                      )}
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* ===== #104：灾害聚簇（此前 disasterClusters 无人读） ===== */}
          <div style={{ ...cardStyle, padding: 6, flex: '0 0 auto' }}>
            <div style={labelStyle}>
              灾害聚簇（{disasterClusters?.clusterCount ?? disasterClusterList.length}）
            </div>
            <div style={{ maxHeight: 80, overflowY: 'auto' }}>
              {disasterClusterList.length === 0 ? (
                <div className="empty-hint">暂无分簇（后端 clusterCount = 0）</div>
              ) : (
                disasterClusterList.slice(0, 8).map((c, i) => {
                  const cid = c.clusterId ?? i
                  const count = c.memberCount ?? (Array.isArray(c.members) ? c.members.length : null)
                  return (
                    <div key={cid} style={{
                      padding: '2px 4px', fontSize: 9, color: 'var(--dim)',
                      borderBottom: '1px solid var(--line-2)',
                      display: 'flex', justifyContent: 'space-between', gap: 4,
                    }}>
                      <span style={{ fontFamily: 'var(--mono)', color: 'var(--text)' }}>
                        簇{cid} · 头 {c.clusterHead ?? '--'}
                      </span>
                      <span>{count != null ? `${count} 节点` : '--'}</span>
                      <span style={{ color: 'var(--dim-2)' }}>
                        {c.clusterRadius != null ? `R=${c.clusterRadius}m` : ''}
                      </span>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* ===== #104：编排事件流（此前 orchEvents 无人读，且去重逻辑把事件并成一条） ===== */}
          <div style={{ ...cardStyle, padding: 6, flex: '0 0 auto' }}>
            <div style={labelStyle}>编排事件（{orchEvents.length}）</div>
            <div style={{ maxHeight: 80, overflowY: 'auto' }}>
              {orchEventRows.length === 0 ? (
                <div className="empty-hint">暂无编排事件</div>
              ) : (
                orchEventRows.slice(-20).reverse().map((text, i) => (
                  <div key={`${orchEventRows.length - i}`} style={{
                    padding: '2px 4px', fontSize: 9, color: 'var(--dim)',
                    borderBottom: '1px solid var(--line-2)',
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}>
                    {text}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>

      {/* ===== 一键应急响应区 ===== */}
      <div style={{ ...cardStyle, padding: 8, display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div style={labelStyle}>一键应急响应</div>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {scenarioOptions.map((s) => (
            <button
              key={s.type}
              onClick={() => setSelectedScenarioType(s.type)}
              style={{
                padding: '4px 10px', fontSize: 10, cursor: 'pointer', borderRadius: 3,
                border: `1px solid ${selectedScenarioType === s.type ? 'var(--cyan)' : 'var(--line-2)'}`,
                background: selectedScenarioType === s.type ? 'var(--bg-2)' : 'transparent',
                color: selectedScenarioType === s.type ? 'var(--cyan)' : 'var(--dim)',
              }}
            >
              {s.label}
            </button>
          ))}
        </div>
        {/* 选中场景的预设参数：这组 defaults 之前随 scenarios 一起被拉回来却没人读，
            用户看不到"这个场景默认带几架机、半径多大"。 */}
        {scenarioDefaults && (
          <span style={{ fontSize: 9, color: 'var(--dim-2)', alignSelf: 'center' }}>
            预设：半径 {scenarioDefaults.radius ?? '--'}m · 机数 {scenarioDefaults.droneCount ?? '--'} · 优先级 {scenarioDefaults.priority ?? '--'}
          </span>
        )}
        <label style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
          <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>中心纬度</span>
          <input
            type="number" step="0.0001" value={centerLat}
            onChange={(e) => setCenterLat(Number(e.target.value))}
            style={{ width: 80, padding: '3px 5px', fontSize: 10, fontFamily: 'var(--mono)', color: 'var(--text)', background: 'var(--bg-1)', border: '1px solid var(--line-2)', borderRadius: 3, outline: 'none' }}
          />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
          <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>中心经度</span>
          <input
            type="number" step="0.0001" value={centerLon}
            onChange={(e) => setCenterLon(Number(e.target.value))}
            style={{ width: 80, padding: '3px 5px', fontSize: 10, fontFamily: 'var(--mono)', color: 'var(--text)', background: 'var(--bg-1)', border: '1px solid var(--line-2)', borderRadius: 3, outline: 'none' }}
          />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
          <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>半径(km)</span>
          <input
            type="number" step="0.1" min="0" value={radiusKm}
            onChange={(e) => setRadiusKm(Number(e.target.value))}
            style={{ width: 60, padding: '3px 5px', fontSize: 10, fontFamily: 'var(--mono)', color: 'var(--text)', background: 'var(--bg-1)', border: '1px solid var(--line-2)', borderRadius: 3, outline: 'none' }}
          />
        </label>
        <button
          onClick={handleStartOrch}
          disabled={starting || isRunning || selectedScenarioType == null}
          style={{
            padding: '5px 16px', fontSize: 11, cursor: starting || isRunning ? 'not-allowed' : 'pointer',
            border: '1px solid var(--cyan)',
            background: starting ? 'var(--bg-2)' : 'transparent',
            color: starting || isRunning ? 'var(--dim)' : 'var(--cyan)',
            borderRadius: 4, fontWeight: 'bold',
            opacity: starting || isRunning ? 0.6 : 1,
          }}
        >
          {starting ? '启动中…' : isRunning ? '编排进行中' : '▶ 一键启动'}
        </button>
        {isRunning && (
          <button
            onClick={handleAbortOrch}
            disabled={aborting}
            style={{
              padding: '5px 14px', fontSize: 11, cursor: aborting ? 'not-allowed' : 'pointer',
              border: '1px solid var(--crit)',
              background: aborting ? 'var(--bg-2)' : 'transparent',
              color: aborting ? 'var(--dim)' : 'var(--crit)',
              borderRadius: 4, fontWeight: 'bold',
              opacity: aborting ? 0.6 : 1,
            }}
          >
            {aborting ? '停止中…' : '■ 紧急停止'}
          </button>
        )}
      </div>

      {/* ===== 编排进度时间线 ===== */}
      {activePlan && (
        <div style={{ ...cardStyle, padding: 8 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={labelStyle}>编排进度 · 计划 {activePlan.planId} · 状态 {activePlan.status || '--'}</span>
          </div>
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            marginTop: 6, position: 'relative',
          }}>
            <div style={{
              position: 'absolute', top: 12, left: '4%', right: '4%',
              height: 2, background: 'var(--line-2)', zIndex: 0,
            }} />
            {PHASES.map((phase, i) => {
              const status = getPhaseStatus(phase.key)
              const color = PHASE_STATUS_COLOR[status] || 'var(--dim)'
              return (
                <div key={phase.key} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', zIndex: 1, flex: 1 }}>
                  <div style={{
                    width: 24, height: 24, borderRadius: '50%',
                    border: `2px solid ${color}`,
                    background: status === 'done' ? color : 'var(--bg-1)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: 10, fontWeight: 'bold', color: status === 'done' ? 'var(--bg-1)' : color,
                    fontFamily: 'var(--mono)',
                    boxShadow: status === 'running' ? `0 0 6px ${color}` : 'none',
                  }}>
                    {status === 'done' ? '✓' : i + 1}
                  </div>
                  <div style={{ fontSize: 9, color, marginTop: 2, whiteSpace: 'nowrap' }}>
                    {phase.label}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* ===== 底部：仪表盘 + 优先级任务队列 + 视频融合区 ===== */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'auto 1fr 1fr',
        gap: 8,
      }}>
        {/* 仪表盘区 */}
        <div style={{ ...cardStyle, padding: 8, display: 'flex', gap: 12, alignItems: 'center', justifyContent: 'center' }}>
          <Gauge value={coveragePct} label="覆盖率" color="var(--ok)" size={64} />
          <Gauge value={connectivityPct ?? connectivityRate} label="连通率" color="var(--cyan)" size={64} />
          <Gauge value={recoveryRate} label="恢复率" color={recoveryRate >= 80 ? 'var(--ok)' : recoveryRate >= 50 ? 'var(--warn)' : 'var(--crit)'} size={64} />
        </div>

        {/* 优先级任务队列 */}
        <div style={{ ...cardStyle, padding: 6 }}>
          <div style={labelStyle}>优先级任务队列</div>
          {groupedTasks.length === 0 ? (
            <div className="empty-hint">暂无任务</div>
          ) : (
            <div style={{ maxHeight: 100, overflowY: 'auto' }}>
              {groupedTasks.map(([priority, tasks]) => {
                const meta = PRIORITY_META[priority] || PRIORITY_META.P3
                return (
                  <div key={priority} style={{ marginBottom: 4 }}>
                    <div style={{
                      fontSize: 9, color: meta.color, fontWeight: 'bold', marginBottom: 2,
                      display: 'flex', justifyContent: 'space-between',
                    }}>
                      <span>{meta.label}</span>
                      <span style={{ color: 'var(--dim-2)' }}>{tasks.length} 个任务</span>
                    </div>
                    {tasks.slice(0, 3).map((t, i) => (
                      <div key={t.id || t.taskId || i} style={{
                        fontSize: 9, color: 'var(--dim)', padding: '2px 4px',
                        borderBottom: '1px solid var(--line-2)',
                        display: 'flex', justifyContent: 'space-between',
                      }}>
                        <span>{t.name || t.description || t.type || '--'}</span>
                        <span style={{ color: 'var(--cyan)' }}>{t.status || t.state || '--'}</span>
                      </div>
                    ))}
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {/* 视频融合区 */}
        <div style={{ ...cardStyle, padding: 6 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={labelStyle}>视频融合区</span>
            <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>
              安防 4 分屏 + 无人机航拍 2 分屏
            </span>
          </div>
          <div style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr 1fr 1fr 1fr 1fr',
            gridTemplateRows: '1fr 1fr',
            gap: 2,
            marginTop: 4,
            minHeight: 80,
            background: '#000',
            borderRadius: 3,
            padding: 2,
          }}>
            {/* 安防视频 4 分屏（左侧 2x2） */}
            {survDevices.filter((d) => d.status === 'online').slice(0, 4).map((d, i) => (
              <div key={`v-surv-${d.id}`} style={{
                gridColumn: `${i % 2 + 1}`,
                gridRow: `${Math.floor(i / 2) + 1}`,
                background: '#0a0e1a',
                border: '1px solid var(--line-2)',
                borderRadius: 2,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 8, color: 'var(--dim-2)',
                overflow: 'hidden',
              }}>
                <span style={{ color: 'var(--ok)', fontSize: 8 }}>●</span>
                <span style={{ marginLeft: 2 }}>{d.name || d.id}</span>
              </div>
            ))}
            {/* 无人机航拍 2 分屏（右侧 1x2） */}
            {drones.filter((d) => d.online).slice(0, 2).map((d, i) => (
              <div key={`v-drone-${d.sysid || i}`} style={{
                gridColumn: `${5 + i}`,
                gridRow: '1 / span 2',
                background: '#0a0e1a',
                border: '1px solid var(--cyan)',
                borderRadius: 2,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 8, color: 'var(--cyan)',
                overflow: 'hidden',
              }}>
                <span>UAV-{d.sysid || i + 1}</span>
              </div>
            ))}
            {/* 空位填充 */}
            {survDevices.filter((d) => d.status === 'online').length < 4 &&
              Array.from({ length: 4 - survDevices.filter((d) => d.status === 'online').length }).map((_, i) => (
                <div key={`v-empty-surv-${i}`} style={{
                  gridColumn: `${(survDevices.filter((d) => d.status === 'online').length % 2) + i % 2 + 1}`,
                  gridRow: `${Math.floor((survDevices.filter((d) => d.status === 'online').length + i) / 2) + 1}`,
                  background: '#0a0e1a',
                  border: '1px solid var(--line-2)',
                  borderRadius: 2,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 8, color: 'var(--dim-2)',
                }}>
                  空位
                </div>
              ))
            }
            {drones.filter((d) => d.online).length < 2 &&
              Array.from({ length: 2 - drones.filter((d) => d.online).length }).map((_, i) => (
                <div key={`v-empty-drone-${i}`} style={{
                  gridColumn: `${5 + drones.filter((d) => d.online).length + i}`,
                  gridRow: '1 / span 2',
                  background: '#0a0e1a',
                  border: '1px solid var(--line-2)',
                  borderRadius: 2,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 8, color: 'var(--dim-2)',
                }}>
                  空位
                </div>
              ))
            }
          </div>
        </div>
      </div>

      {/* ===== 空地态势融合数据展示 ===== */}
      {airGroundSituation && (
        <div style={{ ...cardStyle, padding: 8 }}>
          <div style={labelStyle}>空地协同态势</div>
          <div style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr 1fr',
            gap: 8,
            marginTop: 4,
          }}>
            {/* 地面人员/设备位置 */}
            <div>
              <div style={{ fontSize: 9, color: 'var(--ok)', fontWeight: 'bold', marginBottom: 2 }}>地面单位</div>
              {(airGroundSituation.surveillanceDevices || airGroundSituation.groundUnits || airGroundSituation.groundPersonnel || []).length === 0 ? (
                <div className="empty-hint">暂无地面单位</div>
              ) : (
                (airGroundSituation.surveillanceDevices || airGroundSituation.groundUnits || airGroundSituation.groundPersonnel || []).slice(0, 5).map((u, i) => (
                  <div key={u.id || i} style={{
                    fontSize: 9, color: 'var(--dim)', padding: '2px 4px',
                    borderBottom: '1px solid var(--line-2)',
                    display: 'flex', justifyContent: 'space-between',
                  }}>
                    <span>{u.name || u.unitName || u.id || '--'}</span>
                    <span style={{ color: u.status === 'active' || u.online ? 'var(--ok)' : 'var(--dim-2)' }}>
                      {u.status || (u.online ? '在线' : '离线')}
                    </span>
                  </div>
                ))
              )}
            </div>

            {/* 空中无人机状态 */}
            <div>
              <div style={{ fontSize: 9, color: 'var(--cyan)', fontWeight: 'bold', marginBottom: 2 }}>空中单位</div>
              {(airGroundSituation.droneStatuses || airGroundSituation.aerialUnits || airGroundSituation.drones || []).length === 0 ? (
                <div className="empty-hint">暂无空中单位</div>
              ) : (
                (airGroundSituation.droneStatuses || airGroundSituation.aerialUnits || airGroundSituation.drones || []).slice(0, 5).map((u, i) => {
                  const sysid = u.sysid || u.id || i
                  const status = String(u.status || u.state || (u.online ? 'ONLINE' : 'OFFLINE')).toUpperCase()
                  const statusColor = DRONE_STATUS_COLOR[status] || (u.online ? 'var(--ok)' : 'var(--dim)')
                  return (
                    <div key={sysid} style={{
                      fontSize: 9, color: 'var(--dim)', padding: '2px 4px',
                      borderBottom: '1px solid var(--line-2)',
                      display: 'flex', justifyContent: 'space-between',
                    }}>
                      <span>UAV-{sysid}</span>
                      <span style={{ color: statusColor }}>{status}</span>
                    </div>
                  )
                })
              )}
            </div>

            {/* 协同任务列表 */}
            <div>
              <div style={{ fontSize: 9, color: 'var(--warn)', fontWeight: 'bold', marginBottom: 2 }}>协同任务</div>
              {(airGroundSituation.coordinationTasks || airGroundSituation.tasks || []).length === 0 ? (
                <div className="empty-hint">暂无协同任务</div>
              ) : (
                (airGroundSituation.coordinationTasks || airGroundSituation.tasks || []).slice(0, 5).map((t, i) => (
                  <div key={t.id || t.taskId || i} style={{
                    fontSize: 9, color: 'var(--dim)', padding: '2px 4px',
                    borderBottom: '1px solid var(--line-2)',
                    display: 'flex', justifyContent: 'space-between',
                  }}>
                    <span>{t.name || t.description || t.type || '--'}</span>
                    <span style={{ color: t.status === 'COMPLETED' || t.status === 'DONE' ? 'var(--ok)' : t.status === 'FAILED' ? 'var(--crit)' : 'var(--cyan)' }}>
                      {t.status || t.state || '--'}
                    </span>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      {/* ===== 联动日志（最新 5 条） ===== */}
      {linkageLogs.length > 0 && (
        <div style={{ ...cardStyle, padding: 6 }}>
          <div style={labelStyle}>联动日志（最新 5 条）</div>
          <div style={{ maxHeight: 80, overflowY: 'auto' }}>
            {linkageLogs.slice(0, 5).map((log, i) => (
              <div key={log.id || i} style={{
                fontSize: 9, color: 'var(--dim)', padding: '2px 4px',
                borderBottom: '1px solid var(--line-2)',
                display: 'flex', justifyContent: 'space-between', gap: 8,
              }}>
                <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {log.eventDescription || log.message || log.alarmType || '--'}
                </span>
                <span style={{ color: 'var(--cyan)', flexShrink: 0 }}>{log.action || log.actionType || '--'}</span>
                <span style={{ color: log.result === 'SUCCESS' || log.status === 'SUCCESS' ? 'var(--ok)' : log.result === 'FAILED' || log.status === 'FAILED' ? 'var(--crit)' : 'var(--warn)', flexShrink: 0 }}>
                  {log.result || log.status || '--'}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ===== 内联样式 =====
const cardStyle = {
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 4,
  padding: '6px 10px',
}

const labelStyle = {
  fontSize: 10,
  color: 'var(--dim-2)',
  marginBottom: 2,
}

const miniBtnStyle = {
  fontSize: 10,
  padding: '2px 8px',
  cursor: 'pointer',
  border: '1px solid var(--line-2)',
  background: 'transparent',
  color: 'var(--dim)',
  borderRadius: 3,
}
