import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { emergencyOrch } from '../api.js'

// M9 应急任务编排面板
// 场景预设 → 一键启动 → 编排进度时间线 → 覆盖率/连通率仪表盘
// 无人机列表 / 优先级任务列表 / 事件日志 / 紧急停止
// 风格与 MeshTopologyPanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 2s；组件卸载或编排到达终态时自动停止轮询

const POLL_MS = 2000

// 场景预设类型（与后端 scenario type 对应）
const SCENARIO_TYPES = [
  { type: 'earthquake', label: '地震', icon: '🌐' },
  { type: 'mudslide', label: '泥石流', icon: '⛰' },
  { type: 'fire', label: '火灾', icon: '🔥' },
  { type: 'custom', label: '自定义', icon: '⚙' },
]

// 编排 5 阶段（测绘→规划→部署→服务→自愈）
const PHASES = [
  { key: 'survey', label: '测绘' },
  { key: 'plan', label: '规划' },
  { key: 'deploy', label: '部署' },
  { key: 'service', label: '服务' },
  { key: 'selfheal', label: '自愈' },
]

// 阶段状态 → 颜色
const PHASE_STATUS_COLOR = {
  done: 'var(--ok)',
  running: 'var(--cyan)',
  pending: 'var(--dim)',
  failed: 'var(--crit)',
  skipped: 'var(--dim)',
}

// 编排计划终态（到达后停止轮询）
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

// 规范化优先级键
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

// 圆环仪表盘（覆盖率 / 连通率）
function Gauge({ value, label, color }) {
  const pct = Math.max(0, Math.min(100, Number(value) || 0))
  const r = 34
  const c = 2 * Math.PI * r
  const offset = c * (1 - pct / 100)
  return (
    <div style={{ textAlign: 'center', minWidth: 96 }}>
      <svg width="86" height="86" viewBox="0 0 86 86">
        <circle cx="43" cy="43" r={r} fill="none" stroke="var(--line-2)" strokeWidth="6" />
        <circle
          cx="43" cy="43" r={r} fill="none" stroke={color} strokeWidth="6"
          strokeDasharray={c} strokeDashoffset={offset}
          transform="rotate(-90 43 43)" strokeLinecap="round"
          style={{ transition: 'stroke-dashoffset 0.4s ease' }}
        />
        <text
          x="43" y="47" textAnchor="middle" fontSize="15" fontWeight="bold"
          fill="var(--text)" fontFamily="var(--mono)"
        >
          {pct.toFixed(0)}%
        </text>
      </svg>
      <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 2 }}>{label}</div>
    </div>
  )
}

export default function EmergencyOrchPanel() {
  // ---- 状态 ----
  const [scenarios, setScenarios] = useState([])
  const [scenariosLoading, setScenariosLoading] = useState(true)
  const [selectedType, setSelectedType] = useState('earthquake')
  const [centerLat, setCenterLat] = useState(30.0)
  const [centerLon, setCenterLon] = useState(104.0)
  const [radiusKm, setRadiusKm] = useState(5)
  const [activePlan, setActivePlan] = useState(null)       // 当前编排计划
  const [coverage, setCoverage] = useState(null)           // 覆盖信息
  const [priorityQueue, setPriorityQueue] = useState(null) // 优先级队列
  const [events, setEvents] = useState([])                 // 事件日志
  const [starting, setStarting] = useState(false)
  const [aborting, setAborting] = useState(false)
  const [error, setError] = useState(null)
  const [pollError, setPollError] = useState(null)

  const eventsEndRef = useRef(null)

  // ---- 加载场景预设列表 ----
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await emergencyOrch.getScenarios()
        if (cancelled) return
        // 兼容 { scenarios: [...] } 或 [...] 两种返回
        const list = Array.isArray(data) ? data : (data && data.scenarios) || []
        setScenarios(list)
        setScenariosLoading(false)
      } catch (e) {
        if (cancelled) return
        setError('场景预设加载失败：' + e.message)
        setScenariosLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [])

  // ---- 轮询编排计划状态（planId 不变时只启动一次）----
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
        setPollError(null)
        if (plan) {
          // 保留 planId（后端可能不回传）
          setActivePlan((prev) => ({ ...(plan || {}), planId: plan.planId || (prev && prev.planId) }))
          // 合并事件日志（去重 + 截断 200 条）
          const newEvents = plan.events || plan.recentEvents || plan.eventLog || []
          if (Array.isArray(newEvents) && newEvents.length > 0) {
            setEvents((prev) => {
              const seen = new Set(prev.map((e) => (e.ts || e.timestamp || '') + '|' + (e.message || e.msg || '')))
              const fresh = newEvents.filter((e) => {
                const key = (e.ts || e.timestamp || '') + '|' + (e.message || e.msg || '')
                if (seen.has(key)) return false
                seen.add(key)
                return true
              })
              return [...prev, ...fresh].slice(-200)
            })
          }
          // 终态停止轮询
          const status = String(plan.status || '').toUpperCase()
          if (TERMINAL_STATUS.has(status)) {
            if (timer) { clearInterval(timer); timer = null }
          }
        }
        if (cov) setCoverage(cov)
        if (pq) setPriorityQueue(pq)
      } catch (e) {
        if (cancelled) return
        setPollError(e.message)
      }
    }

    poll()
    timer = setInterval(poll, POLL_MS)
    return () => {
      cancelled = true
      if (timer) clearInterval(timer)
    }
  }, [planId])

  // ---- 事件日志自动滚动到底部 ----
  useEffect(() => {
    if (eventsEndRef.current) {
      eventsEndRef.current.scrollIntoView({ behavior: 'smooth', block: 'end' })
    }
  }, [events])

  // ---- 一键启动编排 ----
  const handleStart = useCallback(async () => {
    setError(null)
    setStarting(true)
    try {
      const body = {
        centerLat: Number(centerLat),
        centerLon: Number(centerLon),
        radiusKm: Number(radiusKm),
      }
      const res = await emergencyOrch.startScenario(selectedType, body)
      const newPlanId = res && res.planId
      if (!newPlanId) throw new Error('启动失败：后端未返回 planId')
      // 初始化计划对象，后续由轮询填充
      setActivePlan({
        planId: newPlanId,
        status: (res && res.status) || 'STARTING',
        phase: (res && res.phase) || 'survey',
        ...res,
      })
      setCoverage(null)
      setPriorityQueue(null)
      setEvents([])
      setPollError(null)
    } catch (e) {
      setError('启动编排失败：' + e.message)
    } finally {
      setStarting(false)
    }
  }, [selectedType, centerLat, centerLon, radiusKm])

  // ---- 紧急停止 ----
  const handleAbort = useCallback(async () => {
    if (!activePlan || !activePlan.planId) return
    setAborting(true)
    try {
      await emergencyOrch.abort(activePlan.planId)
      setActivePlan((prev) => ({ ...(prev || {}), status: 'ABORTED' }))
      setEvents((prev) => [
        ...prev,
        { ts: Date.now(), message: '编排已紧急停止', level: 'warn' },
      ])
    } catch (e) {
      setError('紧急停止失败：' + e.message)
    } finally {
      setAborting(false)
    }
  }, [activePlan && activePlan.planId])

  // ---- 优先级调整 ----
  const handleAdjustPriority = useCallback(async (taskId, newPriority) => {
    if (!activePlan || !activePlan.planId) return
    try {
      await emergencyOrch.adjustPriority(activePlan.planId, { taskId, priority: newPriority })
      // 轮询会自动刷新队列，2s 内可见更新
    } catch (e) {
      setError('优先级调整失败：' + e.message)
    }
  }, [activePlan && activePlan.planId])

  // ===== 派生数据 =====
  const planStatus = String((activePlan && activePlan.status) || '').toUpperCase()
  const isRunning = !!planId && !TERMINAL_STATUS.has(planStatus)
  const phases = (activePlan && activePlan.phases) || (activePlan && activePlan.phaseProgress) || []
  const drones = (activePlan && activePlan.drones) || (activePlan && activePlan.fleet) || []
  const coveragePct = (coverage && (coverage.coveragePct ?? coverage.coverage)) ?? null
  const connectivityPct = (coverage && (coverage.connectivityPct ?? coverage.connectivity)) ?? null
  const queueTasks =
    (priorityQueue && priorityQueue.tasks) ||
    (priorityQueue && priorityQueue.queue) ||
    (Array.isArray(priorityQueue) ? priorityQueue : [])

  // 阶段状态查找
  const getPhaseStatus = (phaseKey) => {
    if (Array.isArray(phases) && phases.length > 0) {
      const found = phases.find((p) => (p.key || p.phase || p.name) === phaseKey)
      if (found) return String(found.status || 'pending').toLowerCase()
    }
    // 退化：用当前 phase 推断
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

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        应急任务编排
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}
      {pollError && (
        <div style={{ color: 'var(--warn)', fontSize: 11, marginBottom: 8 }}>
          轮询异常：{pollError}
        </div>
      )}

      {/* 1. 场景预设选择器 */}
      <div style={{ marginBottom: 12 }}>
        <div style={labelStyle}>场景预设</div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {SCENARIO_TYPES.map((s) => (
            <button
              key={s.type}
              onClick={() => setSelectedType(s.type)}
              style={{
                padding: '6px 14px',
                fontSize: 11,
                cursor: 'pointer',
                border: `1px solid ${selectedType === s.type ? 'var(--cyan)' : 'var(--line-2)'}`,
                background: selectedType === s.type ? 'var(--bg-2)' : 'transparent',
                color: selectedType === s.type ? 'var(--cyan)' : 'var(--dim)',
                borderRadius: 4,
                transition: 'all 0.15s',
              }}
            >
              <span style={{ marginRight: 4 }}>{s.icon}</span>
              {s.label}
            </button>
          ))}
        </div>
        {/* 后端返回的场景详情（如有）*/}
        {scenarios.length > 0 && (
          <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 4 }}>
            可用预设：{scenarios.map((s) => s.type || s.name || s.id).filter(Boolean).join(' / ')}
          </div>
        )}
      </div>

      {/* 2. 灾区输入 + 3. 一键启动 */}
      <div style={{ ...cardStyle, marginBottom: 12, padding: 10 }}>
        <div style={labelStyle}>灾区参数</div>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <label style={inputLabelStyle}>
            <span style={inputCaptionStyle}>中心纬度</span>
            <input
              type="number" step="0.0001" value={centerLat}
              onChange={(e) => setCenterLat(e.target.value)}
              style={inputStyle}
            />
          </label>
          <label style={inputLabelStyle}>
            <span style={inputCaptionStyle}>中心经度</span>
            <input
              type="number" step="0.0001" value={centerLon}
              onChange={(e) => setCenterLon(e.target.value)}
              style={inputStyle}
            />
          </label>
          <label style={inputLabelStyle}>
            <span style={inputCaptionStyle}>半径</span>
            <input
              type="number" step="0.1" min="0" value={radiusKm}
              onChange={(e) => setRadiusKm(e.target.value)}
              style={inputStyle}
            />
          </label>
          <button
            onClick={handleStart}
            disabled={starting || isRunning}
            style={{
              padding: '6px 18px',
              fontSize: 11,
              cursor: starting || isRunning ? 'not-allowed' : 'pointer',
              border: '1px solid var(--cyan)',
              background: starting ? 'var(--bg-2)' : 'transparent',
              color: starting || isRunning ? 'var(--dim)' : 'var(--cyan)',
              borderRadius: 4,
              fontWeight: 'bold',
              opacity: starting || isRunning ? 0.6 : 1,
            }}
          >
            {starting ? '启动中…' : isRunning ? '编排进行中' : '▶ 一键启动'}
          </button>
        </div>
      </div>

      {/* 编排状态概览 */}
      {activePlan && (
        <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
          <div style={cardStyle}>
            <div style={labelStyle}>计划 ID</div>
            <div style={{ ...valueStyle, fontSize: 12 }}>{activePlan.planId}</div>
          </div>
          <div style={cardStyle}>
            <div style={labelStyle}>状态</div>
            <div style={{ ...valueStyle, color: planStatus === 'ABORTED' ? 'var(--crit)' : planStatus === 'COMPLETED' || planStatus === 'DONE' ? 'var(--ok)' : 'var(--cyan)' }}>
              {activePlan.status || '--'}
            </div>
          </div>
          <div style={cardStyle}>
            <div style={labelStyle}>当前阶段</div>
            <div style={valueStyle}>{activePlan.phase || '--'}</div>
          </div>
          <div style={cardStyle}>
            <div style={labelStyle}>无人机数</div>
            <div style={valueStyle}>{drones.length || '--'}</div>
          </div>
        </div>
      )}

      {/* 4. 编排进度时间线 */}
      {activePlan && (
        <div style={{ ...cardStyle, marginBottom: 12, padding: 12 }}>
          <div style={labelStyle}>编排进度</div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 8, position: 'relative' }}>
            {/* 连线背景 */}
            <div style={{
              position: 'absolute', top: 14, left: '6%', right: '6%',
              height: 2, background: 'var(--line-2)', zIndex: 0,
            }} />
            {PHASES.map((phase, i) => {
              const status = getPhaseStatus(phase.key)
              const color = PHASE_STATUS_COLOR[status] || 'var(--dim)'
              return (
                <div key={phase.key} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', zIndex: 1, flex: 1 }}>
                  <div style={{
                    width: 28, height: 28, borderRadius: '50%',
                    border: `2px solid ${color}`,
                    background: status === 'done' ? color : 'var(--bg-1)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: 11, fontWeight: 'bold', color: status === 'done' ? 'var(--bg-1)' : color,
                    fontFamily: 'var(--mono)',
                    boxShadow: status === 'running' ? `0 0 8px ${color}` : 'none',
                  }}>
                    {status === 'done' ? '✓' : i + 1}
                  </div>
                  <div style={{ fontSize: 10, color, marginTop: 4, whiteSpace: 'nowrap' }}>
                    {phase.label}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* 5. 覆盖率/连通率仪表盘 */}
      {activePlan && (
        <div style={{ ...cardStyle, marginBottom: 12, padding: 12, display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'center' }}>
          <Gauge value={coveragePct} label="覆盖率" color="var(--ok)" />
          <Gauge value={connectivityPct} label="连通率" color="var(--cyan)" />
          <div style={{ flex: 1, minWidth: 200 }}>
            <div style={labelStyle}>覆盖详情</div>
            {coverage ? (
              <div style={{ fontSize: 10, fontFamily: 'var(--mono)', color: 'var(--dim)', lineHeight: 1.6 }}>
                {coverage.coveredAreaKm2 != null && <div>已覆盖面积：{Number(coverage.coveredAreaKm2).toFixed(2)} km²</div>}
                {coverage.totalAreaKm2 != null && <div>灾区总面积：{Number(coverage.totalAreaKm2).toFixed(2)} km²</div>}
                {coverage.connectedNodes != null && <div>连通节点：{coverage.connectedNodes}{coverage.totalNodes != null ? ` / ${coverage.totalNodes}` : ''}</div>}
                {coverage.gapCount != null && <div>覆盖盲区：{coverage.gapCount} 处</div>}
              </div>
            ) : (
              <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>等待覆盖数据…</div>
            )}
          </div>
        </div>
      )}

      {/* 6. 无人机列表 */}
      {activePlan && drones.length > 0 && (
        <div style={{ ...cardStyle, marginBottom: 12, padding: 12 }}>
          <div style={labelStyle}>无人机列表（{drones.length}）</div>
          <div style={{ overflowX: 'auto' }}>
            <table style={tableStyle}>
              <thead>
                <tr>
                  <th style={thStyle}>ID</th>
                  <th style={thStyle}>位置</th>
                  <th style={thStyle}>电量</th>
                  <th style={thStyle}>角色</th>
                  <th style={thStyle}>状态</th>
                </tr>
              </thead>
              <tbody>
                {drones.map((d, i) => {
                  const id = d.id != null ? d.id : (d.sysid != null ? d.sysid : `#${i}`)
                  const lat = d.lat != null ? d.lat : (d.latitude != null ? d.latitude : (d.position ? d.position.lat : null))
                  const lon = d.lon != null ? d.lon : (d.longitude != null ? d.longitude : (d.position ? d.position.lon : null))
                  const batt = d.batteryPct != null ? d.batteryPct : (d.battery != null ? d.battery : d.batteryLevel)
                  const role = d.role || d.taskRole || '--'
                  const status = String(d.status || d.state || 'ONLINE').toUpperCase()
                  const statusColor = DRONE_STATUS_COLOR[status] || 'var(--dim)'
                  const battNum = Number(batt)
                  const battColor = batt == null ? 'var(--dim)' : battNum <= 15 ? 'var(--crit)' : battNum <= 30 ? 'var(--warn)' : 'var(--ok)'
                  return (
                    <tr key={id || i}>
                      <td style={tdStyle}>{id}</td>
                      <td style={{ ...tdStyle, color: 'var(--dim)' }}>
                        {lat != null && lon != null ? `${Number(lat).toFixed(4)}, ${Number(lon).toFixed(4)}` : '--'}
                      </td>
                      <td style={{ ...tdStyle, color: battColor }}>
                        {batt != null ? `${battNum.toFixed(0)}%` : '--'}
                      </td>
                      <td style={tdStyle}>{role}</td>
                      <td style={{ ...tdStyle, color: statusColor }}>{status}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 7. 优先级任务列表 */}
      {activePlan && (
        <div style={{ ...cardStyle, marginBottom: 12, padding: 12 }}>
          <div style={labelStyle}>优先级任务列表（{queueTasks.length}）</div>
          {groupedTasks.length > 0 ? (
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 6 }}>
              {groupedTasks.map(([prio, tasks]) => {
                const meta = PRIORITY_META[prio] || { label: prio, color: 'var(--dim)' }
                return (
                  <div key={prio} style={{
                    flex: '1 1 220px', minWidth: 220,
                    border: `1px solid ${meta.color}`, borderRadius: 4, padding: 8,
                    background: 'var(--bg-1)',
                  }}>
                    <div style={{ fontSize: 11, fontWeight: 'bold', color: meta.color, marginBottom: 6, borderBottom: `1px solid ${meta.color}`, paddingBottom: 3 }}>
                      {meta.label}（{tasks.length}）
                    </div>
                    {tasks.map((t, i) => {
                      const tid = t.id != null ? t.id : (t.taskId != null ? t.taskId : i)
                      const desc = t.description || t.title || t.name || t.task || '--'
                      const tStatus = String(t.status || t.state || '').toUpperCase()
                      return (
                        <div key={tid} style={{
                          fontSize: 10, padding: '3px 0', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8,
                          color: tStatus === 'DONE' || tStatus === 'COMPLETED' ? 'var(--dim)' : 'var(--text)',
                        }}>
                          <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {desc}
                          </span>
                          {tStatus && (
                            <span style={{ fontSize: 9, color: tStatus === 'DONE' || tStatus === 'COMPLETED' ? 'var(--ok)' : tStatus === 'FAILED' ? 'var(--crit)' : 'var(--dim-2)' }}>
                              {tStatus}
                            </span>
                          )}
                          <button
                            onClick={() => {
                              const next = prio === 'P0' ? 'P1' : prio === 'P1' ? 'P2' : 'P0'
                              handleAdjustPriority(tid, next)
                            }}
                            style={{
                              fontSize: 9, padding: '1px 6px', cursor: 'pointer',
                              border: '1px solid var(--line-2)', background: 'transparent',
                              color: 'var(--dim)', borderRadius: 3,
                            }}
                            title="切换优先级"
                          >
                            ⇅
                          </button>
                        </div>
                      )
                    })}
                  </div>
                )
              })}
            </div>
          ) : (
            <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 4 }}>暂无优先级任务</div>
          )}
        </div>
      )}

      {/* 8. 事件日志 */}
      {activePlan && (
        <div style={{ ...cardStyle, marginBottom: 12, padding: 12 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={labelStyle}>事件日志（{events.length}）</div>
            <button
              onClick={() => setEvents([])}
              style={{ fontSize: 9, padding: '1px 8px', cursor: 'pointer', border: '1px solid var(--line-2)', background: 'transparent', color: 'var(--dim)', borderRadius: 3 }}
            >
              清空
            </button>
          </div>
          <div style={{
            maxHeight: 180, overflowY: 'auto', marginTop: 6, fontSize: 10, fontFamily: 'var(--mono)',
            background: 'var(--bg-1)', borderRadius: 4, padding: 6, border: '1px solid var(--line-2)',
          }}>
            {events.length > 0 ? (
              events.map((e, i) => {
                const ts = e.ts != null ? e.ts : e.timestamp
                const msg = e.message || e.msg || JSON.stringify(e)
                const level = String(e.level || e.severity || 'info').toLowerCase()
                const color = level === 'error' || level === 'crit' ? 'var(--crit)' : level === 'warn' ? 'var(--warn)' : level === 'ok' ? 'var(--ok)' : 'var(--dim)'
                const time = ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--'
                return (
                  <div key={i} style={{ display: 'flex', gap: 8, padding: '1px 0', color: 'var(--text)' }}>
                    <span style={{ color: 'var(--dim-2)', flexShrink: 0 }}>{time}</span>
                    <span style={{ color, flexShrink: 0 }}>[{level.toUpperCase()}]</span>
                    <span style={{ color: 'var(--text)', wordBreak: 'break-all' }}>{msg}</span>
                  </div>
                )
              })
            ) : (
              <div style={{ color: 'var(--dim-2)', textAlign: 'center', padding: 12 }}>暂无事件</div>
            )}
            <div ref={eventsEndRef} />
          </div>
        </div>
      )}

      {/* 9. 紧急停止 */}
      {activePlan && isRunning && (
        <div style={{ marginTop: 4, textAlign: 'center' }}>
          <button
            onClick={handleAbort}
            disabled={aborting}
            style={{
              padding: '8px 32px',
              fontSize: 12,
              cursor: aborting ? 'not-allowed' : 'pointer',
              border: '1px solid var(--crit)',
              background: aborting ? 'var(--bg-2)' : 'transparent',
              color: aborting ? 'var(--dim)' : 'var(--crit)',
              borderRadius: 4,
              fontWeight: 'bold',
              opacity: aborting ? 0.6 : 1,
            }}
          >
            {aborting ? '停止中…' : '⏹ 紧急停止'}
          </button>
        </div>
      )}

      {/* 空状态：未启动编排 */}
      {!activePlan && !scenariosLoading && (
        <div style={{
          background: 'var(--bg-2)', borderRadius: 6, padding: 24, marginBottom: 12,
          border: '1px solid var(--line-2)', textAlign: 'center', color: 'var(--dim-2)', fontSize: 11,
        }}>
          选择场景预设与灾区参数后，点击「一键启动」开始应急编排
        </div>
      )}
      {scenariosLoading && (
        <div style={{
          background: 'var(--bg-2)', borderRadius: 6, padding: 24, marginBottom: 12,
          border: '1px solid var(--line-2)', textAlign: 'center', color: 'var(--dim-2)', fontSize: 11,
        }}>
          正在加载场景预设…
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

const valueStyle = {
  fontSize: 16,
  fontWeight: 'bold',
  color: 'var(--text)',
  fontFamily: 'var(--mono)',
}

const tableStyle = {
  width: '100%',
  borderCollapse: 'collapse',
  fontSize: 10,
  fontFamily: 'var(--mono)',
}

const thStyle = {
  textAlign: 'left',
  padding: '3px 6px',
  borderBottom: '1px solid var(--line-2)',
  color: 'var(--dim-2)',
  fontWeight: 'normal',
}

const tdStyle = {
  padding: '3px 6px',
  borderBottom: '1px solid var(--line-2)',
  color: 'var(--text)',
}

const inputLabelStyle = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
}

const inputCaptionStyle = {
  fontSize: 10,
  color: 'var(--dim-2)',
}

const inputStyle = {
  width: 90,
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-1)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
}
