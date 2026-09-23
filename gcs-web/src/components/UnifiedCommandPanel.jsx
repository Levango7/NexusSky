import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import {
  api,
  emergencyOrch,
  disaster,
  listSurveillanceDevices,
  getDeviceStream,
  ptzControl,
  listSurveillanceEvents,
  listAlarmEvents,
  acknowledgeAlarm,
  listAlarmRules,
  triggerEmergencyResponse,
  listLinkageLogs,
  alarmStreamUrl,
} from '../api.js'

// 空地一体化应急指挥面板（UnifiedCommandPanel）
// 整合安防监控 + 应急编排 + 灾害通信 + 报警联动 + Mesh 拓扑为一个统一指挥视图
// 三栏布局：左栏（安防设备） + 中栏（空地协同地图） + 右栏（无人机/报警/联动）
// 底部：视频融合区 + 编排进度时间线 + 仪表盘 + 优先级任务队列
// 风格与现有面板一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 3s；SSE 接收实时报警事件

const POLL_MS = 3000

// 场景预设类型
const SCENARIO_TYPES = [
  { type: 'earthquake', label: '地震', icon: '🌐' },
  { type: 'mudslide', label: '泥石流', icon: '⛰' },
  { type: 'fire', label: '火灾', icon: '🔥' },
  { type: 'custom', label: '自定义', icon: '⚙' },
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

// 优先级 → 颜色 + 排序权重
const PRIORITY_META = {
  P0: { label: 'P0 紧急', color: 'var(--crit)', weight: 0 },
  P1: { label: 'P1 高', color: 'var(--warn)', weight: 1 },
  P2: { label: 'P2 中', color: 'var(--gold)', weight: 2 },
  P3: { label: 'P3 低', color: 'var(--cyan)', weight: 3 },
  HIGH: { label: '高', color: 'var(--crit)', weight: 0 },
  MEDIUM: { label: '中', color: 'var(--warn)', weight: 1 },
  LOW: { label: '低', color: 'var(--cyan)', weight: 2 },
}

function normPriority(p) {
  if (!p) return 'P3'
  const u = String(p).toUpperCase()
  if (PRIORITY_META[u]) return u
  if (u.startsWith('P0')) return 'P0'
  if (u.startsWith('P1')) return 'P1'
  if (u.startsWith('P2')) return 'P2'
  if (u.startsWith('P3')) return 'P3'
  return 'P3'
}

// 安防设备在线状态 → 颜色
const ONLINE_COLOR = {
  online: 'var(--ok)',
  offline: 'var(--dim)',
  error: 'var(--crit)',
}

// 报警严重程度 → 颜色
const SEVERITY_META = {
  CRITICAL: { color: 'var(--crit)', label: '严重', weight: 0 },
  WARN: { color: 'var(--warn)', label: '警告', weight: 1 },
  WARNING: { color: 'var(--warn)', label: '警告', weight: 1 },
  INFO: { color: 'var(--cyan)', label: '信息', weight: 2 },
  ERROR: { color: 'var(--crit)', label: '错误', weight: 0 },
}

function normSeverity(s) {
  if (!s) return 'INFO'
  const u = String(s).toUpperCase()
  if (SEVERITY_META[u]) return u
  if (u.includes('CRIT')) return 'CRITICAL'
  if (u.includes('WARN')) return 'WARN'
  if (u.includes('ERR')) return 'ERROR'
  return 'INFO'
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

// 链路类型
const LINK_TYPES = {
  WiFi: { label: 'WiFi', color: '#3498DB' },
  LTE: { label: 'LTE', color: '#2ECC71' },
  LoRa: { label: 'LoRa', color: '#F39C12' },
  Sat: { label: '卫星', color: '#9B59B6' },
}

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
  const [scenarios, setScenarios] = useState([])
  const [selectedScenarioType, setSelectedScenarioType] = useState('earthquake')
  const [centerLat, setCenterLat] = useState(30.0)
  const [centerLon, setCenterLon] = useState(104.0)
  const [radiusKm, setRadiusKm] = useState(5)
  const [activePlan, setActivePlan] = useState(null)
  const [coverage, setCoverage] = useState(null)
  const [priorityQueue, setPriorityQueue] = useState(null)
  const [orchEvents, setOrchEvents] = useState([])
  const [starting, setStarting] = useState(false)
  const [aborting, setAborting] = useState(false)

  // ===== 灾害通信状态 =====
  const [disasterStatus, setDisasterStatus] = useState(null)
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
      es = new EventSource(alarmStreamUrl)

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
        if (!closed) retryTimer = setTimeout(subscribe, 5000)
      }
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

  // ===== 轮询安防事件 =====
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await listSurveillanceEvents({ limit: 20 })
        if (cancelled) return
        const list = Array.isArray(data) ? data : (data && data.events) || []
        setSurvEvents(list)
      } catch (e) {
        // 静默失败
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

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
          const newEvents = plan.events || plan.recentEvents || plan.eventLog || []
          if (Array.isArray(newEvents) && newEvents.length > 0) {
            setOrchEvents((prev) => {
              const seen = new Set(prev.map((e) => (e.ts || e.timestamp || '') + '|' + (e.message || e.msg || '')))
              const fresh = newEvents.filter((e) => {
                const key = (e.ts || e.timestamp || '') + '|' + (e.message || e.msg || '')
                if (seen.has(key)) return false
                seen.add(key)
                return true
              })
              return [...prev, ...fresh].slice(-100)
            })
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
    setStarting(true)
    try {
      const body = {
        centerLat: Number(centerLat),
        centerLon: Number(centerLon),
        radiusKm: Number(radiusKm),
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

  // ===== 派生数据 =====
  const selectedSurvDevice = useMemo(
    () => survDevices.find((d) => d.id === selectedSurvDeviceId),
    [survDevices, selectedSurvDeviceId]
  )

  const planStatus = String((activePlan && activePlan.status) || '').toUpperCase()
  const isRunning = !!planId && !TERMINAL_STATUS.has(planStatus)
  const phases = (activePlan && activePlan.phases) || (activePlan && activePlan.phaseProgress) || []
  const orchDrones = (activePlan && activePlan.drones) || (activePlan && activePlan.fleet) || []
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
    <div style={{ padding: 12, color: 'var(--text)', display: 'flex', flexDirection: 'column', gap: 8, height: '100%', overflow: 'auto' }}>
      {/* 标题栏 + 全局状态指示 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <h2 style={{ fontSize: 16, margin: 0, color: 'var(--text)' }}>
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
            <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 8 }}>加载中…</div>
          ) : survDevices.length === 0 ? (
            <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 8 }}>暂无安防设备</div>
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
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 28px)', gap: 3, justifyContent: 'center' }}>
                {PTZ_BUTTONS.map((b) => (
                  <button
                    key={b.cmd}
                    onClick={() => handlePtz(b.cmd)}
                    disabled={ptzBusy}
                    title={b.title}
                    style={{
                      width: 28, height: 24, fontSize: 11, cursor: ptzBusy ? 'not-allowed' : 'pointer',
                      border: '1px solid var(--line-2)', background: 'var(--bg-3)',
                      color: 'var(--text)', borderRadius: 3,
                      opacity: ptzBusy ? 0.5 : 1,
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
                    disabled={ptzBusy}
                    title={b.title}
                    style={{
                      width: 44, height: 22, fontSize: 11, cursor: ptzBusy ? 'not-allowed' : 'pointer',
                      border: '1px solid var(--line-2)', background: 'var(--bg-3)',
                      color: 'var(--text)', borderRadius: 3,
                      opacity: ptzBusy ? 0.5 : 1,
                    }}
                  >
                    {b.label}
                  </button>
                ))}
              </div>
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
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 4 }}>暂无无人机</div>
              ) : (
                drones.slice(0, 8).map((d, i) => {
                  const sysid = d.sysid || d.id || i
                  const status = String(d.status || d.state || (d.online ? 'ONLINE' : 'OFFLINE')).toUpperCase()
                  const statusColor = DRONE_STATUS_COLOR[status] || (d.online ? 'var(--ok)' : 'var(--dim)')
                  const batt = d.batteryPct != null ? d.batteryPct : (d.battery != null ? d.battery : null)
                  const battNum = Number(batt)
                  const battColor = batt == null ? 'var(--dim)' : battNum <= 15 ? 'var(--crit)' : battNum <= 30 ? 'var(--warn)' : 'var(--ok)'
                  return (
                    <div key={sysid} style={{
                      padding: '3px 4px', display: 'flex', alignItems: 'center', gap: 4,
                      borderBottom: '1px solid var(--line-2)',
                    }}>
                      <span style={{ color: statusColor, fontSize: 9 }}>●</span>
                      <span style={{ fontSize: 10, color: 'var(--text)', fontFamily: 'var(--mono)', flex: 1 }}>
                        UAV-{sysid}
                      </span>
                      <span style={{ fontSize: 9, color: battColor, fontFamily: 'var(--mono)' }}>
                        {batt != null ? `${battNum.toFixed(0)}%` : '--'}
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
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 8, textAlign: 'center' }}>暂无报警事件</div>
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
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 4 }}>暂无联动规则</div>
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
        </div>
      </div>

      {/* ===== 一键应急响应区 ===== */}
      <div style={{ ...cardStyle, padding: 8, display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div style={labelStyle}>一键应急响应</div>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {SCENARIO_TYPES.map((s) => (
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
              <span style={{ marginRight: 3 }}>{s.icon}</span>
              {s.label}
            </button>
          ))}
        </div>
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
            onChange={(e) => setRadiusKm(e.target.value)}
            style={{ width: 60, padding: '3px 5px', fontSize: 10, fontFamily: 'var(--mono)', color: 'var(--text)', background: 'var(--bg-1)', border: '1px solid var(--line-2)', borderRadius: 3, outline: 'none' }}
          />
        </label>
        <button
          onClick={handleStartOrch}
          disabled={starting || isRunning}
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
            <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 8, textAlign: 'center' }}>暂无任务</div>
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