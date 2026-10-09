import React, { useState, useEffect, useCallback, useRef } from 'react'
import { getAuthToken } from '../api.js'
import { fmtPct } from '../utils/format'

const POLL_MS = 5000

/** 状态 → 色标（与后端 DockState 枚举一一对应）。 */
const STATE_COLORS = {
  OFFLINE: '#6b7280',
  IDLE: '#22c55e',
  OPENING: '#eab308',
  OPEN: '#38bdf8',
  CLOSING: '#eab308',
  CHARGING: '#a855f7',
  EXCHANGING: '#f97316',
  FAULT: '#ef4444',
  MAINTENANCE: '#94a3b8',
}

const STATE_LABELS = {
  OFFLINE: '离线',
  IDLE: '空闲',
  OPENING: '开门中',
  OPEN: '门已开',
  CLOSING: '关门中',
  CHARGING: '充电中',
  EXCHANGING: '换电中',
  FAULT: '故障',
  MAINTENANCE: '维护',
}

/** 动作按钮：仅在后端状态机允许的状态下可点（前端置灰只是提示，裁决在后端）。 */
const ACTIONS = [
  { method: 'door_open', label: '开门', allow: ['IDLE', 'CHARGING'] },
  { method: 'door_close', label: '关门', allow: ['OPEN'] },
  { method: 'battery_swap', label: '换电', allow: ['IDLE', 'CHARGING'] },
  { method: 'reboot', label: '重启', allow: ['FAULT', 'MAINTENANCE', 'IDLE', 'CHARGING', 'OPEN'] },
]

/**
 * 机巢管控面板（F2）。
 *
 * 左侧机巢列表（状态色标）→ 右侧详情：动作按钮（非法态置灰）、
 * 最近状态迁移时间线、定时任务列表、利用率/健康度度量卡片。
 * 动作失败如实显示后端返回的 409/504 原因，不吞错。
 */
export default function DockPanel() {
  const [docks, setDocks] = useState([])
  const [selectedId, setSelectedId] = useState(null)
  const [detail, setDetail] = useState(null)
  const [schedules, setSchedules] = useState([])
  const [metrics, setMetrics] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [showRegister, setShowRegister] = useState(false)
  const [form, setForm] = useState({ name: '', sn: '', model: '', droneSysid: '' })
  const [schedForm, setSchedForm] = useState({ name: '', cron: '0 0 6 * * *', waypoints: '[[22.5916,113.9345,60]]' })
  const selectedRef = useRef(selectedId)

  const authHeaders = useCallback(() => {
    const token = getAuthToken()
    return token ? { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' }
  }, [])

  const loadAll = useCallback(async () => {
    try {
      const res = await fetch('/api/v1/docks', { headers: authHeaders() })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const list = await res.json()
      setDocks(list)
      setError(null)
      const id = selectedRef.current
      if (id != null) {
        const [dRes, sRes, mRes] = await Promise.all([
          fetch(`/api/v1/docks/${id}`, { headers: authHeaders() }),
          fetch(`/api/v1/docks/${id}/schedules`, { headers: authHeaders() }),
          fetch(`/api/v1/docks/${id}/metrics?days=7`, { headers: authHeaders() }),
        ])
        if (dRes.ok) setDetail(await dRes.json())
        if (sRes.ok) setSchedules(await sRes.json())
        if (mRes.ok) setMetrics(await mRes.json())
      }
    } catch (e) {
      setError(e.message || '获取机巢列表失败')
    }
  }, [authHeaders])

  useEffect(() => {
    selectedRef.current = selectedId
    loadAll()
    const t = setInterval(loadAll, POLL_MS)
    return () => clearInterval(t)
  }, [selectedId, loadAll])

  const sendCommand = async (method) => {
    if (selectedId == null) return
    setBusy(true)
    try {
      const res = await fetch(`/api/v1/docks/${selectedId}/commands`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ method }),
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) {
          setError(`命令被拒绝（${res.status}）：${body.result || body.message || '未知原因'}`)
      } else {
        setError(null)
      }
      await loadAll()
    } catch (e) {
      setError(e.message || '命令下发失败')
    } finally {
      setBusy(false)
    }
  }

  const registerDock = async () => {
    try {
      const res = await fetch('/api/v1/docks', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({
          name: form.name,
          sn: form.sn,
          model: form.model,
          droneSysid: form.droneSysid ? Number(form.droneSysid) : null,
        }),
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) {
        setError(`注册失败（${res.status}）：${body.result || '未知原因'}`)
        return
      }
      setShowRegister(false)
      setForm({ name: '', sn: '', model: '', droneSysid: '' })
      setSelectedId(body.id)
      setError(null)
    } catch (e) {
      setError(e.message || '注册失败')
    }
  }

  const createSchedule = async () => {
    if (selectedId == null) return
    try {
      let waypoints = null
      try {
        waypoints = JSON.parse(schedForm.waypoints)
      } catch {
        setError('航点 JSON 解析失败')
        return
      }
      const res = await fetch(`/api/v1/docks/${selectedId}/schedules`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ name: schedForm.name, cron: schedForm.cron, waypoints }),
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) {
        setError(`创建定时任务失败（${res.status}）：${body.result || '未知原因'}`)
        return
      }
      setSchedForm({ ...schedForm, name: '' })
      setError(null)
      await loadAll()
    } catch (e) {
      setError(e.message || '创建定时任务失败')
    }
  }

  const toggleSchedule = async (sid, enabled) => {
    try {
      await fetch(`/api/v1/docks/${selectedId}/schedules/${sid}/enable`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ enabled }),
      })
      await loadAll()
    } catch (e) {
      setError(e.message || '切换定时任务失败')
    }
  }

  const st = detail ? detail.state : null

  return (
    <div className="page page-split">
      {/* 左：机巢列表 */}
      <div style={{ width: 260, flexShrink: 0, overflowY: 'auto' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <strong>机巢（{docks.length}）</strong>
          <button onClick={() => setShowRegister(!showRegister)} style={{ cursor: 'pointer' }}>
            {showRegister ? '取消' : '注册'}
          </button>
        </div>
        {showRegister && (
          <div style={{ border: '1px solid #334155', borderRadius: 6, padding: 8, marginBottom: 8, display: 'grid', gap: 4 }}>
            {[['name', '名称'], ['sn', '序列号'], ['model', '型号'], ['droneSysid', '托管机 sysid']].map(([k, label]) => (
              <input
                key={k}
                placeholder={label}
                value={form[k]}
                onChange={(e) => setForm({ ...form, [k]: e.target.value })}
                style={{ padding: 4 }}
              />
            ))}
            <button onClick={registerDock} disabled={!form.name || !form.sn}>提交注册</button>
          </div>
        )}
        {docks.map((d) => (
          <div
            key={d.id}
            onClick={() => setSelectedId(d.id)}
            style={{
              border: `1px solid ${d.id === selectedId ? '#38bdf8' : '#334155'}`,
              borderRadius: 6,
              padding: 8,
              marginBottom: 6,
              cursor: 'pointer',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>{d.name}</span>
              <span style={{
                background: STATE_COLORS[d.state] || '#6b7280',
                borderRadius: 4, padding: '1px 6px', fontSize: 12, color: '#0b1220', fontWeight: 600,
              }}>
                {STATE_LABELS[d.state] || d.state}
              </span>
            </div>
            <div style={{ fontSize: 12, color: '#94a3b8', marginTop: 4 }}>
              SN {d.sn}{d.temperatureC != null ? ` · ${d.temperatureC.toFixed(1)}℃` : ''}
              {d.batteryPct != null ? ` · 备电 ${d.batteryPct}%` : ''}
            </div>
          </div>
        ))}
        {docks.length === 0 && (
          <div className="empty-state" style={{ padding: '28px 8px' }}>
            <span className="es-title">暂无机巢</span>
            <span className="es-hint">点右上「注册」新增</span>
          </div>
        )}
      </div>

      {/* 右：详情 */}
      <div style={{ flex: 1, overflowY: 'auto' }}>
        {error && (
          <div className="notice crit">{error}</div>
        )}
        {!detail && (
          <div className="empty-state">
            <span className="es-hint">选择左侧机巢查看详情</span>
          </div>
        )}
        {detail && (
          <>
            <h3 className="page-title">
              {detail.name}
              <span style={{ marginLeft: 8, color: STATE_COLORS[st] || '#6b7280' }}>
                ● {STATE_LABELS[st] || st}
              </span>
              {detail.rebootPending && <span style={{ marginLeft: 8, fontSize: 13, color: '#eab308' }}>重启中…</span>}
            </h3>

            {/* 动作 */}
            <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
              {ACTIONS.map((a) => {
                const allowed = a.allow.includes(st) && !detail.rebootPending
                return (
                  <button
                    key={a.method}
                    disabled={!allowed || busy}
                    onClick={() => sendCommand(a.method)}
                    title={allowed ? '' : `当前状态（${st}）不允许`}
                    style={{ padding: '4px 12px', cursor: allowed ? 'pointer' : 'not-allowed' }}
                  >
                    {a.label}
                  </button>
                )
              })}
            </div>

            {/* 度量卡片 */}
            {metrics && metrics.summary && (
              <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
                <MetricCard label="可用率(7d)" value={fmtPct(metrics.summary.availabilityPct)} />
                <MetricCard label="利用率(7d)" value={fmtPct(metrics.summary.utilizationPct)} />
                <MetricCard label="架次(7d)" value={metrics.summary.sorties} />
                <MetricCard label="飞行分钟(7d)" value={Math.round(metrics.summary.flightMinutes)} />
                <MetricCard label="开关门(7d)" value={metrics.summary.doorCycles} />
                <MetricCard label="故障(7d)" value={metrics.summary.faultCount} warn={metrics.summary.faultCount > 0} />
                <MetricCard label="温度告警(7d)" value={metrics.summary.tempExcursions} warn={metrics.summary.tempExcursions > 0} />
                <MetricCard label="换电(7d)" value={metrics.summary.batterySwaps} />
                <MetricCard label="均充电(min)" value={metrics.summary.avgChargeTimeMin ?? '—'} />
              </div>
            )}

            {/* 定时任务 */}
            <h4 style={{ margin: '8px 0 6px' }}>无人值守定时任务</h4>
            <div style={{ display: 'grid', gap: 4, marginBottom: 8 }}>
              {schedules.map((s) => (
                <div key={s.id} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
                  <span style={{ width: 130 }}>{s.name}</span>
                  <code style={{ color: '#7dd3fc' }}>{s.cron}</code>
                  <span style={{ color: s.enabled ? '#22c55e' : '#94a3b8' }}>{s.enabled ? '启用' : '停用'}</span>
                  <span style={{ color: '#94a3b8' }}>
                    {s.lastResult ? `最近：${s.lastResult}` : ''} 
                  </span>
                  <button onClick={() => toggleSchedule(s.id, !s.enabled)} style={{ marginLeft: 'auto' }}>
                    {s.enabled ? '停用' : '启用'}
                  </button>
                </div>
              ))}
              {schedules.length === 0 && <span style={{ color: '#94a3b8', fontSize: 13 }}>暂无定时任务</span>}
            </div>
            <div style={{ display: 'flex', gap: 4, marginBottom: 12 }}>
              <input placeholder="任务名" value={schedForm.name}
                     onChange={(e) => setSchedForm({ ...schedForm, name: e.target.value })}
                     style={{ width: 110, padding: 4 }} />
              <input placeholder="cron" value={schedForm.cron}
                     onChange={(e) => setSchedForm({ ...schedForm, cron: e.target.value })}
                     style={{ width: 130, padding: 4 }} />
              <input placeholder="航点 JSON" value={schedForm.waypoints}
                     onChange={(e) => setSchedForm({ ...schedForm, waypoints: e.target.value })}
                     style={{ flex: 1, padding: 4 }} />
              <button onClick={createSchedule} disabled={!schedForm.name}>添加</button>
            </div>

            {/* 状态时间线 */}
            <h4 style={{ margin: '8px 0 6px' }}>最近状态迁移</h4>
            <div style={{ fontSize: 13 }}>
              {(detail.recentTransitions || []).map((t, i) => (
                <div key={i} style={{ display: 'flex', gap: 8, padding: '2px 0' }}>
                  <span style={{ color: '#94a3b8', width: 60 }}>
                    {new Date(t.ts).toLocaleTimeString()}
                  </span>
                  <span>
                    {t.from ? `${STATE_LABELS[t.from] || t.from} → ` : ''}
                    <b>{STATE_LABELS[t.to] || t.to}</b>
                  </span>
                  <span style={{ color: '#64748b' }}>{t.reason}</span>
                </div>
              ))}
              {(detail.recentTransitions || []).length === 0 && (
                <span style={{ color: '#94a3b8' }}>暂无迁移记录</span>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function MetricCard({ label, value, warn }) {
  return (
    <div style={{
      border: `1px solid ${warn ? '#ef4444' : '#334155'}`,
      borderRadius: 6, padding: '6px 10px', minWidth: 96,
    }}>
      <div style={{ fontSize: 11, color: '#94a3b8' }}>{label}</div>
      <div style={{ fontSize: 18, fontWeight: 600, color: warn ? '#f87171' : '#e2e8f0' }}>{value}</div>
    </div>
  )
}