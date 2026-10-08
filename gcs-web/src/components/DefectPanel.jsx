import React, { useState, useEffect, useCallback, useRef } from 'react'
import { getAuthToken } from '../api.js'

const POLL_MS = 5000

const SEVERITY_COLORS = { P0: '#ef4444', P1: '#f97316', P2: '#eab308', P3: '#6b7280' }
const DEFECT_STATUS_LABELS = { OPEN: '待确认', CONFIRMED: '已确认', DISMISSED: '已驳回' }
const WO_STATUS_LABELS = {
  OPEN: '待派单', DISPATCHED: '已派单', IN_PROGRESS: '处置中',
  RESOLVED: '待复检', VERIFIED: '已闭环', REOPENED: '复检退回', CANCELLED: '已取消',
}
// 与后端 WorkOrderStateMachine.ALLOWED 一致（前端置灰只是提示，裁决在后端）
const WO_ACTIONS = [
  { action: 'dispatch', label: '派单', allow: ['OPEN', 'REOPENED'] },
  { action: 'start', label: '开工', allow: ['DISPATCHED', 'REOPENED'] },
  { action: 'resolve', label: '完成处置', allow: ['IN_PROGRESS'] },
  { action: 'cancel', label: '取消', allow: ['OPEN', 'DISPATCHED', 'IN_PROGRESS', 'RESOLVED', 'REOPENED'] },
]

/**
 * 缺陷报告与工单闭环面板（F4）。
 *
 * 左：缺陷列表（严重度色标，勾选建单）；右：工单列表（状态推进 + 复检）。
 * 复检要求托管机已在缺陷点附近（派飞属运维职责）——verify 按钮带 sysid 输入。
 * 报告下载：json/csv/md 三格式直链。
 */
export default function DefectPanel() {
  const [defects, setDefects] = useState([])
  const [workOrders, setWorkOrders] = useState([])
  const [selected, setSelected] = useState(new Set())
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [verifySysid, setVerifySysid] = useState('9')
  const [woTitle, setWoTitle] = useState('')
  const selRef = useRef(selected)
  const sysidRef = useRef(verifySysid)

  const authHeaders = useCallback(() => {
    const token = getAuthToken()
    return token
      ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
      : { 'Content-Type': 'application/json' }
  }, [])

  const load = useCallback(async () => {
    try {
      const [d, w] = await Promise.all([
        fetch('/api/v1/defects', { headers: authHeaders() }),
        fetch('/api/v1/defects/work-orders', { headers: authHeaders() }),
      ])
      if (d.ok) setDefects(await d.json())
      if (w.ok) setWorkOrders(await w.json())
      setError(null)
    } catch (e) {
      setError(e.message || '加载失败')
    }
  }, [authHeaders])

  useEffect(() => {
    selRef.current = selected
    const t = setInterval(load, POLL_MS)
    load()
    return () => clearInterval(t)
  }, [load])

  const toggle = (id) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const createWorkOrder = async () => {
    const ids = [...selRef.current]
    if (ids.length === 0) return
    setBusy(true)
    try {
      const res = await fetch('/api/v1/defects/work-orders', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ defectIds: ids, title: woTitle || null }),
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) {
        setError(`创建工单失败（${res.status}）：${body.result || ''}`)
      } else {
        setSelected(new Set())
        setWoTitle('')
        setError(null)
      }
      await load()
    } finally {
      setBusy(false)
    }
  }

  const woTransition = async (id, action) => {
    setBusy(true)
    try {
      const res = await fetch(`/api/v1/defects/work-orders/${id}/transition`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ action }),
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) setError(`流转被拒（${res.status}）：${body.result || ''}`)
      else setError(null)
      await load()
    } finally {
      setBusy(false)
    }
  }

  const woVerify = async (id) => {
    setBusy(true)
    try {
      const res = await fetch(`/api/v1/defects/work-orders/${id}/verify`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ sysid: Number(sysidRef.current) }),
      })
      const body = await res.json().catch(() => ({}))
      if (!res.ok) {
        setError(`复检失败（${res.status}）：${body.result || ''}`)
      } else {
        setError(body.result === 'VERIFIED'
          ? null
          : `复检退回：命中缺陷 ${JSON.stringify(body.hitDefects)}`)
      }
      await load()
    } finally {
      setBusy(false)
    }
  }

  const setDefectStatus = async (id, status) => {
    setBusy(true)
    try {
      const res = await fetch(`/api/v1/defects/${id}/status`, {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ status }),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        setError(`状态裁决被拒（${res.status}）：${body.result || ''}`)
      } else {
        setError(null)
      }
      await load()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ display: 'flex', gap: 12, padding: 12, height: '100%', boxSizing: 'border-box' }}>
      {/* 左：缺陷 */}
      <div style={{ flex: 1, minWidth: 320, overflowY: 'auto' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <strong>缺陷（{defects.length}）</strong>
          <a href="/api/v1/defects/report?format=csv" download style={{ fontSize: 13, color: '#7dd3fc' }}>
            下载报告 (csv/md/json)
          </a>
        </div>
        <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 6 }}>
          <a href="/api/v1/defects/report?format=csv" download>csv</a>
          {' · '}
          <a href="/api/v1/defects/report?format=md" download>md</a>
          {' · '}
          <a href="/api/v1/defects/report?format=json" target="_blank" rel="noreferrer">json</a>
        </div>
        {defects.map((d) => (
          <div key={d.id} style={{
            border: `1px solid ${selRef.current.has(d.id) ? '#38bdf8' : '#334155'}`,
            borderRadius: 6, padding: 8, marginBottom: 6, fontSize: 13,
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <input type="checkbox" checked={selected.has(d.id)} onChange={() => toggle(d.id)} />
              <span style={{ color: SEVERITY_COLORS[d.severity] || '#6b7280', fontWeight: 700 }}>
                {d.severity}
              </span>
              <span>{d.kind}</span>
              <span style={{ color: '#94a3b8' }}>conf {Number(d.confidence).toFixed(2)}</span>
              <span style={{ marginLeft: 'auto' }}>{DEFECT_STATUS_LABELS[d.status] || d.status}</span>
              <span style={{ color: '#64748b', fontSize: 12 }}>{d.source === 'auto' ? '自动' : '人工'}</span>
            </div>
            <div style={{ color: '#64748b', fontSize: 12, marginTop: 2 }}>
              {Number(d.lat).toFixed(5)}, {Number(d.lon).toFixed(5)}
              {d.note ? ` · ${d.note}` : ''}
            </div>
            <div style={{ marginTop: 4 }}>
              {d.status !== 'DISMISSED' && (
                <>
                  <button onClick={() => setDefectStatus(d.id, 'CONFIRMED')} disabled={busy} style={{ fontSize: 12 }}>确认</button>{' '}
                  <button onClick={() => setDefectStatus(d.id, 'DISMISSED')} disabled={busy} style={{ fontSize: 12 }}>驳回</button>
                </>
              )}
              {d.status === 'DISMISSED' && (
                <button onClick={() => setDefectStatus(d.id, 'OPEN')} disabled={busy} style={{ fontSize: 12 }}>重开</button>
              )}
            </div>
          </div>
        ))}
        {defects.length === 0 && <div style={{ color: '#94a3b8', fontSize: 13 }}>暂无缺陷——拍照自动晋升（conf≥0.6）或人工立案</div>}
        <div style={{ display: 'flex', gap: 4, marginTop: 8 }}>
          <input placeholder="工单标题（可选）" value={woTitle}
                 onChange={(e) => setWoTitle(e.target.value)} style={{ flex: 1, padding: 4 }} />
          <button onClick={createWorkOrder} disabled={busy || selected.size === 0}>
            从勾选建工单（{selected.size}）
          </button>
        </div>
      </div>

      {/* 右：工单 */}
      <div style={{ flex: 1, minWidth: 320, overflowY: 'auto' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <strong>工单（{workOrders.length}）</strong>
          <span style={{ fontSize: 12, color: '#94a3b8' }}>
            复检 sysid:
            <input value={verifySysid} onChange={(e) => { setVerifySysid(e.target.value); sysidRef.current = e.target.value }}
                   style={{ width: 40, marginLeft: 4, padding: 2 }} />
          </span>
        </div>
        {workOrders.map((w) => (
          <div key={w.id} style={{ border: '1px solid #334155', borderRadius: 6, padding: 8, marginBottom: 6, fontSize: 13 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontWeight: 600 }}>#{w.id}</span>
              <span>{w.title}</span>
              <span style={{ marginLeft: 'auto', color: '#7dd3fc' }}>
                {WO_STATUS_LABELS[w.status] || w.status}
              </span>
            </div>
            <div style={{ color: '#64748b', fontSize: 12, marginTop: 2 }}>
              缺陷 {JSON.stringify(w.defectIds)}
              {w.verifiedDetail ? ` · ${w.verifiedDetail}` : ''}
              {w.cancelReason ? ` · 取消原因：${w.cancelReason}` : ''}
            </div>
            <div style={{ display: 'flex', gap: 4, marginTop: 4, flexWrap: 'wrap' }}>
              {WO_ACTIONS.filter((a) => a.allow.includes(w.status)).map((a) => (
                <button key={a.action} disabled={busy} onClick={() => woTransition(w.id, a.action)}
                        style={{ fontSize: 12, padding: '2px 8px' }}>
                  {a.label}
                </button>
              ))}
              {w.status === 'RESOLVED' && (
                <button disabled={busy} onClick={() => woVerify(w.id)}
                        style={{ fontSize: 12, padding: '2px 8px', borderColor: '#22c55e' }}>
                  复检验证
                </button>
              )}
            </div>
          </div>
        ))}
        {workOrders.length === 0 && <div style={{ color: '#94a3b8', fontSize: 13 }}>暂无工单</div>}
      </div>

      {error && (
        <div style={{
          position: 'absolute', bottom: 12, left: '50%', transform: 'translateX(-50%)',
          background: '#7f1d1d', borderRadius: 6, padding: '6px 12px', fontSize: 13,
        }}>
          {error}
        </div>
      )}
    </div>
  )
}