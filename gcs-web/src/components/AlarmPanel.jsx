import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import {
  listAlarmEvents,
  acknowledgeAlarm,
  acknowledgeAlarms,
  listAlarmRules,
  createAlarmRule,
  updateAlarmRule,
  deleteAlarmRule,
  testAlarmRule,
  triggerEmergencyResponse,
  listLinkageLogs,
  alarmStreamUrl,
} from '../api.js'

// M11 报警联动面板
// 报警事件列表（SSE 实时推送） + 联动规则管理 + 一键应急响应 + 联动日志
// 风格与 EmergencyOrchPanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 严重程度颜色：CRITICAL=红 / WARN=黄 / INFO=蓝
// SSE 订阅：EventSource 实时推送报警事件，AbortController 前置检查守卫
// 经验来源：2026-09-12-abort-signal-precheck-before-eventsource-subscribe（订阅前置检查）
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（竞态守卫）

const POLL_MS = 5000

// 严重程度元数据
const SEVERITY_META = {
  CRITICAL: { color: 'var(--crit)', label: '严重', weight: 0 },
  WARN: { color: 'var(--warn)', label: '警告', weight: 1 },
  WARNING: { color: 'var(--warn)', label: '警告', weight: 1 },
  INFO: { color: 'var(--cyan)', label: '信息', weight: 2 },
  ERROR: { color: 'var(--crit)', label: '错误', weight: 0 },
}

// 规范化严重程度
function normSeverity(s) {
  if (!s) return 'INFO'
  const u = String(s).toUpperCase()
  if (SEVERITY_META[u]) return u
  if (u.includes('CRIT')) return 'CRITICAL'
  if (u.includes('WARN')) return 'WARN'
  if (u.includes('ERR')) return 'ERROR'
  return 'INFO'
}

// 报警来源类型
const SOURCE_TYPES = [
  { key: 'surveillance', label: '安防设备' },
  { key: 'drone', label: '无人机' },
  { key: 'sensor', label: '传感器' },
  { key: 'manual', label: '人工' },
  { key: 'system', label: '系统' },
]

// 联动规则触发动作类型
const ACTION_TYPES = [
  { key: 'recon', label: '无人机侦察' },
  { key: 'hover', label: '悬停监控' },
  { key: 'record', label: '录像存证' },
  { key: 'alert', label: '声光报警' },
  { key: 'notify', label: '通知推送' },
]

export default function AlarmPanel() {
  // ---- 状态 ----
  const [events, setEvents] = useState([])               // 报警事件列表
  const [rules, setRules] = useState([])                 // 联动规则列表
  const [linkageLogs, setLinkageLogs] = useState([])     // 联动日志
  const [selectedEventId, setSelectedEventId] = useState(null)
  const [editingRule, setEditingRule] = useState(null)   // null | 'new' | rule 对象
  const [error, setError] = useState(null)
  const [sseStatus, setSseStatus] = useState('connecting') // connecting | open | closed
  const [responding, setResponding] = useState(false)

  // 筛选
  const [filterSeverity, setFilterSeverity] = useState('')
  const [filterSource, setFilterSource] = useState('')
  const [filterUnack, setFilterUnack] = useState(false)

  // 事件列表引用（供 SSE 回调读取最新值做去重）
  const eventsRef = useRef([])
  eventsRef.current = events

  // ---- SSE 实时订阅报警事件 ----
  // 经验：在订阅函数体第一行插入前置检查（signal.aborted），创建 EventSource 之前
  useEffect(() => {
    const controller = new AbortController()
    let es = null
    let retryTimer = null
    let closed = false

    const subscribe = () => {
      // 经验：前置检查 —— 在创建 EventSource 之前判断 signal.aborted
      if (controller.signal.aborted) return

      es = new EventSource(alarmStreamUrl)

      es.onopen = () => {
        if (controller.signal.aborted) { es.close(); return }
        setSseStatus('open')
      }

      es.onmessage = (ev) => {
        // 经验：abort 后不更新任何 state
        if (controller.signal.aborted) return
        let data
        try {
          data = JSON.parse(ev.data)
        } catch (e) {
          return
        }
        // 兼容单事件 / 批量事件
        const incoming = Array.isArray(data) ? data : [data]
        setEvents((prev) => {
          const seen = new Set(prev.map((e) => e.id || (e.eventId || '') + '|' + (e.ts || e.timestamp || '')))
          const fresh = incoming.filter((e) => {
            const key = e.id || (e.eventId || '') + '|' + (e.ts || e.timestamp || '')
            if (seen.has(key)) return false
            seen.add(key)
            return true
          })
          return [...fresh, ...prev].slice(0, 200)
        })
      }

      es.onerror = () => {
        if (controller.signal.aborted) return
        setSseStatus('closed')
        if (es) es.close()
        // 断线 5s 自动重连
        if (!closed) retryTimer = setTimeout(subscribe, 5000)
      }
    }

    subscribe()

    return () => {
      // 经验：cleanup 中 abort + 关闭 EventSource
      closed = true
      controller.abort()
      clearTimeout(retryTimer)
      if (es) es.close()
    }
  }, [])

  // ---- 加载报警事件列表（初始 + 兜底，SSE 断线时仍有数据）----
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await listAlarmEvents({ limit: 50 })
        if (cancelled) return
        const list = Array.isArray(data) ? data : (data && data.events) || []
        // 仅在初始为空时填充，避免覆盖 SSE 实时数据
        setEvents((prev) => prev.length === 0 ? list : prev)
      } catch (e) {
        // 静默失败，SSE 会补上
      }
    }
    load()
    return () => { cancelled = true }
  }, [])

  // ---- 轮询联动规则 + 联动日志 ----
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const [rulesData, logsData] = await Promise.all([
          listAlarmRules().catch(() => []),
          listLinkageLogs({ limit: 30 }).catch(() => []),
        ])
        if (cancelled) return
        setRules(Array.isArray(rulesData) ? rulesData : (rulesData && rulesData.rules) || [])
        setLinkageLogs(Array.isArray(logsData) ? logsData : (logsData && logsData.logs) || [])
      } catch (e) {
        // 静默失败
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ---- 派生数据 ----
  const filteredEvents = useMemo(() => {
    return events.filter((e) => {
      if (filterSeverity && normSeverity(e.severity) !== filterSeverity) return false
      if (filterSource && (e.source || e.sourceType) !== filterSource) return false
      if (filterUnack && e.acknowledged) return false
      return true
    })
  }, [events, filterSeverity, filterSource, filterUnack])

  const unackCount = useMemo(() => events.filter((e) => !e.acknowledged).length, [events])
  const selectedEvent = useMemo(
    () => events.find((e) => (e.id || e.eventId) === selectedEventId),
    [events, selectedEventId]
  )

  // ---- 确认报警 ----
  const handleAck = useCallback(async (eventId) => {
    try {
      await acknowledgeAlarm(eventId)
      setEvents((prev) => prev.map((e) => ((e.id || e.eventId) === eventId ? { ...e, acknowledged: true } : e)))
    } catch (e) {
      setError('确认报警失败：' + e.message)
    }
  }, [])

  // ---- 批量确认 ----
  const handleAckAll = useCallback(async () => {
    const unackIds = events.filter((e) => !e.acknowledged).map((e) => e.id || e.eventId).filter(Boolean)
    if (unackIds.length === 0) return
    try {
      await acknowledgeAlarms(unackIds)
      setEvents((prev) => prev.map((e) => ({ ...e, acknowledged: true })))
    } catch (e) {
      setError('批量确认失败：' + e.message)
    }
  }, [events])

  // ---- 一键应急响应 ----
  const handleRespond = useCallback(async (eventId) => {
    setResponding(true)
    setError(null)
    try {
      await triggerEmergencyResponse(eventId)
      setEvents((prev) => prev.map((e) => ((e.id || e.eventId) === eventId ? { ...e, responded: true } : e)))
    } catch (e) {
      setError('应急响应触发失败：' + e.message)
    } finally {
      setResponding(false)
    }
  }, [])

  // ---- 规则保存（创建 / 更新）----
  const handleSaveRule = useCallback(async (rule) => {
    setError(null)
    try {
      if (rule.id) {
        await updateAlarmRule(rule.id, rule)
      } else {
        await createAlarmRule(rule)
      }
      setEditingRule(null)
      // 轮询会自动刷新
    } catch (e) {
      setError('保存规则失败：' + e.message)
    }
  }, [])

  // ---- 规则删除 ----
  const handleDeleteRule = useCallback(async (id) => {
    try {
      await deleteAlarmRule(id)
    } catch (e) {
      setError('删除规则失败：' + e.message)
    }
  }, [])

  // ---- 规则测试 ----
  const handleTestRule = useCallback(async (id) => {
    try {
      await testAlarmRule(id)
    } catch (e) {
      setError('测试规则失败：' + e.message)
    }
  }, [])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        报警联动
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左侧：报警事件列表 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 筛选栏 + SSE 状态 */}
          <div style={{ ...cardStyle, padding: 8, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <span style={{
              fontSize: 9, padding: '2px 6px', borderRadius: 3,
              border: `1px solid ${sseStatus === 'open' ? 'var(--ok)' : 'var(--warn)'}`,
              color: sseStatus === 'open' ? 'var(--ok)' : 'var(--warn)',
            }}>
              ● SSE {sseStatus === 'open' ? '已连接' : sseStatus === 'connecting' ? '连接中' : '断线重连'}
            </span>
            <select value={filterSeverity} onChange={(e) => setFilterSeverity(e.target.value)} style={filterSelectStyle}>
              <option value="">全部级别</option>
              <option value="CRITICAL">严重</option>
              <option value="WARN">警告</option>
              <option value="INFO">信息</option>
            </select>
            <select value={filterSource} onChange={(e) => setFilterSource(e.target.value)} style={filterSelectStyle}>
              <option value="">全部来源</option>
              {SOURCE_TYPES.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
            </select>
            <label style={{ fontSize: 10, color: 'var(--dim)', display: 'flex', alignItems: 'center', gap: 3, cursor: 'pointer' }}>
              <input type="checkbox" checked={filterUnack} onChange={(e) => setFilterUnack(e.target.checked)} />
              仅未确认
            </label>
            <span style={{ fontSize: 10, color: 'var(--warn)', marginLeft: 'auto' }}>
              {unackCount > 0 ? `${unackCount} 条未确认` : '全部已确认'}
            </span>
            {unackCount > 0 && (
              <button onClick={handleAckAll} style={miniBtnStyle}>全部确认</button>
            )}
          </div>

          {/* 事件列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ maxHeight: 380, overflowY: 'auto' }}>
              {filteredEvents.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无报警事件</div>
              ) : (
                filteredEvents.map((e, i) => {
                  const eid = e.id || e.eventId || i
                  const sev = normSeverity(e.severity)
                  const meta = SEVERITY_META[sev] || SEVERITY_META.INFO
                  const ts = e.ts != null ? e.ts : e.timestamp
                  const time = ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--'
                  const isSel = eid === selectedEventId
                  const source = e.source || e.sourceType || '--'
                  const type = e.type || e.alarmType || '--'
                  const location = e.location || (e.lat != null && e.lon != null ? `${Number(e.lat).toFixed(4)}, ${Number(e.lon).toFixed(4)}` : null)
                  return (
                    <div
                      key={eid}
                      onClick={() => setSelectedEventId(eid)}
                      style={{
                        padding: '6px 10px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-2)' : 'transparent',
                        borderLeft: `3px solid ${meta.color}`,
                        opacity: e.acknowledged ? 0.6 : 1,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: meta.color, fontWeight: 'bold', flexShrink: 0 }}>[{meta.label}]</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {e.message || e.description || e.title || type}
                          </span>
                        </div>
                        <span style={{ fontSize: 9, color: 'var(--dim-2)', flexShrink: 0, fontFamily: 'var(--mono)' }}>{time}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        <span>来源：{source}</span>
                        <span>类型：{type}</span>
                        {location && <span>位置：{location}</span>}
                        {e.acknowledged && <span style={{ color: 'var(--ok)' }}>✓ 已确认</span>}
                        {e.responded && <span style={{ color: 'var(--cyan)' }}>✓ 已响应</span>}
                      </div>
                      {/* 选中事件的操作按钮 */}
                      {isSel && (
                        <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                          {!e.acknowledged && (
                            <button onClick={(ev) => { ev.stopPropagation(); handleAck(eid) }} style={miniBtnStyle}>
                              确认
                            </button>
                          )}
                          <button
                            onClick={(ev) => { ev.stopPropagation(); handleRespond(eid) }}
                            disabled={responding}
                            style={{
                              ...miniBtnStyle,
                              border: '1px solid var(--crit)', color: responding ? 'var(--dim)' : 'var(--crit)',
                              fontWeight: 'bold', opacity: responding ? 0.6 : 1,
                            }}
                          >
                            {responding ? '响应中…' : '🚁 一键应急响应'}
                          </button>
                        </div>
                      )}
                    </div>
                  )
                })
              )}
            </div>
          </div>
        </div>

        {/* 右侧：联动规则 + 联动日志 */}
        <div style={{ flex: '1 1 360px', minWidth: 320, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 联动规则管理 */}
          <div style={{ ...cardStyle, padding: 8 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
              <div style={labelStyle}>联动规则（{rules.length}）</div>
              <button onClick={() => setEditingRule('new')} style={miniBtnStyle}>＋ 新建规则</button>
            </div>
            <div style={{ maxHeight: 200, overflowY: 'auto' }}>
              {rules.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 8, textAlign: 'center' }}>暂无联动规则</div>
              ) : (
                rules.map((r, i) => {
                  const rid = r.id || r.ruleId || i
                  const action = ACTION_TYPES.find((a) => a.key === (r.action || r.actionType)) || { label: r.action || '--' }
                  return (
                    <div key={rid} style={{
                      padding: '4px 6px', marginBottom: 3, borderRadius: 3,
                      border: '1px solid var(--line-2)', background: 'var(--bg-1)',
                    }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}>
                        <span style={{ fontSize: 11, color: 'var(--text)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {r.name || r.ruleName || `规则 ${rid}`}
                        </span>
                        <div style={{ display: 'flex', gap: 3, flexShrink: 0 }}>
                          <button onClick={() => setEditingRule(r)} style={{ ...miniBtnStyle, fontSize: 9, padding: '0 5px' }} title="编辑">✎</button>
                          <button onClick={() => handleTestRule(rid)} style={{ ...miniBtnStyle, fontSize: 9, padding: '0 5px' }} title="测试">▶</button>
                          <button onClick={() => handleDeleteRule(rid)} style={{ ...miniBtnStyle, fontSize: 9, padding: '0 5px', color: 'var(--crit)' }} title="删除">✕</button>
                        </div>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        {r.alarmType || r.condition || '--'} → {action.label}
                        {r.enabled === false && <span style={{ color: 'var(--warn)', marginLeft: 6 }}>（已禁用）</span>}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 联动日志 */}
          <div style={{ ...cardStyle, padding: 8 }}>
            <div style={labelStyle}>联动日志（{linkageLogs.length}）</div>
            <div style={{
              maxHeight: 220, overflowY: 'auto', marginTop: 4, fontSize: 10, fontFamily: 'var(--mono)',
              background: 'var(--bg-1)', borderRadius: 3, padding: 4, border: '1px solid var(--line-2)',
            }}>
              {linkageLogs.length > 0 ? (
                linkageLogs.map((log, i) => {
                  const ts = log.ts != null ? log.ts : log.timestamp
                  const time = ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--'
                  const result = String(log.result || log.status || '').toUpperCase()
                  const resultColor = result === 'SUCCESS' || result === 'DONE' ? 'var(--ok)' : result === 'FAILED' || result === 'ERROR' ? 'var(--crit)' : 'var(--cyan)'
                  return (
                    <div key={i} style={{ padding: '2px 0', borderBottom: '1px solid var(--line-2)', color: 'var(--text)' }}>
                      <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                        <span style={{ color: 'var(--dim-2)', flexShrink: 0 }}>{time}</span>
                        <span style={{ color: 'var(--text)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {log.alarmEvent || log.eventId || '--'}
                        </span>
                        <span style={{ color: resultColor, flexShrink: 0, fontSize: 9 }}>[{result || '--'}]</span>
                      </div>
                      {(log.rule || log.task) && (
                        <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 1 }}>
                          {log.rule && `规则：${log.rule} `}
                          {log.task && `→ 任务：${log.task}`}
                        </div>
                      )}
                    </div>
                  )
                })
              ) : (
                <div style={{ color: 'var(--dim-2)', textAlign: 'center', padding: 12 }}>暂无联动日志</div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* 规则编辑弹窗 */}
      {editingRule && (
        <RuleEditor
          rule={editingRule === 'new' ? null : editingRule}
          onSave={handleSaveRule}
          onCancel={() => setEditingRule(null)}
        />
      )}
    </div>
  )
}

// ===== 规则编辑弹窗 =====
function RuleEditor({ rule, onSave, onCancel }) {
  const [form, setForm] = useState(() => rule || {
    name: '',
    alarmType: '',
    source: '',
    action: 'recon',
    missionTemplate: '',
    enabled: true,
    priority: 'P1',
  })
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    setSaving(true)
    try {
      await onSave(form)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      onClick={onCancel}
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1000,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: 'var(--bg-1)', border: '1px solid var(--line-2)', borderRadius: 6,
          padding: 16, minWidth: 340, maxHeight: '80vh', overflowY: 'auto',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h3 style={{ fontSize: 13, margin: 0, color: 'var(--text)' }}>
            {rule ? '编辑联动规则' : '新建联动规则'}
          </h3>
          <button onClick={onCancel} style={{ ...miniBtnStyle, padding: '0 6px' }}>✕</button>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Field label="规则名称">
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} style={modalInputStyle} placeholder="如：入侵报警→无人机侦察" />
          </Field>
          <Field label="报警类型（触发条件）">
            <input value={form.alarmType} onChange={(e) => setForm({ ...form, alarmType: e.target.value })} style={modalInputStyle} placeholder="如：motion_detection / intrusion / offline" />
          </Field>
          <div style={{ display: 'flex', gap: 8 }}>
            <Field label="报警来源" style={{ flex: 1 }}>
              <select value={form.source} onChange={(e) => setForm({ ...form, source: e.target.value })} style={modalInputStyle}>
                <option value="">全部来源</option>
                {SOURCE_TYPES.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
              </select>
            </Field>
            <Field label="响应动作" style={{ flex: 1 }}>
              <select value={form.action} onChange={(e) => setForm({ ...form, action: e.target.value })} style={modalInputStyle}>
                {ACTION_TYPES.map((a) => <option key={a.key} value={a.key}>{a.label}</option>)}
              </select>
            </Field>
          </div>
          <Field label="无人机任务模板 ID">
            <input value={form.missionTemplate} onChange={(e) => setForm({ ...form, missionTemplate: e.target.value })} style={modalInputStyle} placeholder="如：recon_template_01" />
          </Field>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <Field label="优先级" style={{ flex: 1 }}>
              <select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} style={modalInputStyle}>
                <option value="P0">P0 紧急</option>
                <option value="P1">P1 高</option>
                <option value="P2">P2 中</option>
                <option value="P3">P3 低</option>
              </select>
            </Field>
            <label style={{ fontSize: 10, color: 'var(--dim)', display: 'flex', alignItems: 'center', gap: 3, cursor: 'pointer', alignSelf: 'flex-end', paddingBottom: 4 }}>
              <input type="checkbox" checked={form.enabled !== false} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
              启用
            </label>
          </div>
          <button
            onClick={handleSave}
            disabled={saving || !form.name}
            style={{
              padding: '6px 0', fontSize: 11, cursor: saving ? 'not-allowed' : 'pointer',
              border: '1px solid var(--cyan)', background: saving ? 'var(--bg-2)' : 'transparent',
              color: saving ? 'var(--dim)' : 'var(--cyan)', borderRadius: 3, fontWeight: 'bold',
              opacity: saving || !form.name ? 0.6 : 1, marginTop: 4,
            }}
          >
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ===== 子组件 =====
function Field({ label, children, style }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 2, ...style }}>
      <span style={{ fontSize: 10, color: 'var(--dim-2)' }}>{label}</span>
      {children}
    </label>
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

const filterSelectStyle = {
  padding: '2px 6px',
  fontSize: 10,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-1)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
}

const modalInputStyle = {
  width: '100%',
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
  boxSizing: 'border-box',
}