import React, { useState, useEffect, useCallback, useRef } from 'react'
import { getAuthToken } from '../api.js'

const POLL_MS = 3000

/**
 * ROC 一控多机席位面板（E1）：席位机队网格 + 批量指令 + 警情驱动调度（对表深圳 1+7+N）。
 * 警情只建议不执行——派飞由操作员显式确认（dispatch 按钮）。
 */
export default function RocPanel() {
  const [seatId, setSeatId] = useState(null)
  const [seatView, setSeatView] = useState(null)
  const [incidents, setIncidents] = useState([])
  const [seats, setSeats] = useState([])
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [newOp, setNewOp] = useState('')
  const [fleetInput, setFleetInput] = useState('')
  const [incForm, setIncForm] = useState({ lat: '22.5910', lon: '113.9340', priority: 'P1', description: '' })
  const seatRef = useRef(seatId)

  const auth = useCallback(() => {
    const token = getAuthToken()
    return token ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' }
  }, [])

  const load = useCallback(async () => {
    try {
      const list = await fetch('/api/v1/roc/seats', { headers: auth() })
      if (list.ok) setSeats(await list.json())
      if (seatRef.current != null) {
        const [v, i] = await Promise.all([
          fetch(`/api/v1/roc/seats/${seatRef.current}`, { headers: auth() }),
          fetch(`/api/v1/roc/seats/${seatRef.current}/incidents`, { headers: auth() }),
        ])
        if (v.ok) setSeatView(await v.json())
        if (i.ok) setIncidents(await i.json())
      }
      setError(null)
    } catch (e) {
      setError(e.message || '加载失败')
    }
  }, [auth])

  useEffect(() => {
    seatRef.current = seatId
    load()
    const t = setInterval(load, POLL_MS)
    return () => clearInterval(t)
  }, [seatId, load])

  const call = async (method, url, body) => {
    setBusy(true)
    try {
      const res = await fetch(url, { method, headers: auth(), body: body ? JSON.stringify(body) : undefined })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) {
        setError(`${url} → ${res.status}: ${data.result || ''}`)
        return null
      }
      setError(null)
      await load()
      return data
    } catch (e) {
      setError(e.message)
      return null
    } finally {
      setBusy(false)
    }
  }

  const createSeat = async () => {
    const r = await call('POST', '/api/v1/roc/seats', { operatorName: newOp })
    if (r) setSeatId(r.seatId)
  }

  const bindFleet = () => {
    const sysids = fleetInput.split(/[,\s]+/).filter(Boolean).map(Number).filter((n) => !Number.isNaN(n))
    if (sysids.length === 0) { setError('机队输入无效'); return }
    call('PUT', `/api/v1/roc/seats/${seatId}/fleet`, { sysids })
  }

  const batch = (action) => call('POST', `/api/v1/roc/seats/${seatId}/commands`, { action })

  const reportIncident = () =>
    call('POST', `/api/v1/roc/seats/${seatId}/incidents`, {
      lat: Number(incForm.lat), lon: Number(incForm.lon),
      priority: incForm.priority, description: incForm.description,
    })

  const dispatch = (id) => call('POST', `/api/v1/roc/seats/${seatId}/incidents/${id}/dispatch`, { holdSec: 30 })

  const batteryColor = (b) => (b == null ? '#6b7280' : b >= 50 ? '#22c55e' : b >= 25 ? '#eab308' : '#ef4444')

  return (
    <div className="page">
      <h3 className="page-title">ROC 一控多机席位</h3>

      {/* 席位管理 */}
      <div style={{ display: 'flex', gap: 6, marginBottom: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <input placeholder="操作员名" value={newOp} onChange={(e) => setNewOp(e.target.value)} style={{ width: 110, padding: 4 }} />
        <button onClick={createSeat} disabled={busy || !newOp}>创建席位</button>
        <select value={seatId ?? ''} onChange={(e) => setSeatId(Number(e.target.value))} style={{ padding: 4 }}>
          <option value="">选择席位…</option>
          {seats.map((s) => (
            <option key={s.seatId} value={s.seatId}>
              #{s.seatId} {s.operatorName}（{s.fleetSize} 机）
            </option>
          ))}
        </select>
        {seatId != null && (
          <>
            <input placeholder="机队 sysid 逗号分隔" value={fleetInput}
                   onChange={(e) => setFleetInput(e.target.value)} style={{ width: 160, padding: 4 }} />
            <button onClick={bindFleet} disabled={busy}>绑定机队</button>
          </>
        )}
      </div>

      {error && <div style={{ background: '#7f1d1d', borderRadius: 6, padding: '6px 10px', marginBottom: 8, fontSize: 13 }}>{error}</div>}

      {/* 机队网格 */}
      {seatView && (
        <>
          <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
            {['arm', 'takeoff', 'rtl', 'land'].map((a) => (
              <button key={a} onClick={() => batch(a)} disabled={busy}>{a}</button>
            ))}
            <span style={{ fontSize: 12, color: '#94a3b8', alignSelf: 'center' }}>批量指令作用于全部席位机</span>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 14 }}>
            {(seatView.fleet || []).map((d) => (
              <div key={d.sysid} style={{
                border: `1px solid ${d.online ? (d.armed ? '#38bdf8' : '#334155') : '#7f1d1d'}`,
                borderRadius: 6, padding: '6px 10px', minWidth: 130,
                opacity: d.online ? 1 : 0.5,
              }}>
                <div style={{ fontWeight: 700 }}>Drone-{d.sysid} {!d.online && <span style={{ color: '#ef4444', fontSize: 12 }}>离线</span>}</div>
                <div style={{ fontSize: 12, color: '#94a3b8' }}>{d.mode}</div>
                <div style={{ fontSize: 12, color: batteryColor(d.battery) }}>电池 {d.battery ?? '--'}%</div>
                <div style={{ fontSize: 11, color: '#64748b' }}>
                  {d.lat != null ? `${Number(d.lat).toFixed(4)}, ${Number(d.lon).toFixed(4)}` : '无定位'}
                  {d.relativeAlt != null ? ` · ${Number(d.relativeAlt).toFixed(0)}m` : ''}
                </div>
              </div>
            ))}
            {(seatView.fleet || []).length === 0 && (
              <span style={{ color: '#94a3b8', fontSize: 13 }}>未绑定机队</span>
            )}
          </div>
        </>
      )}

      {/* 警情 */}
      {seatId != null && (
        <>
          <h4 style={{ margin: '4px 0 6px' }}>警情登记（只建议不执行，派飞需确认）</h4>
          <div style={{ display: 'flex', gap: 4, marginBottom: 8, flexWrap: 'wrap' }}>
            <input value={incForm.lat} onChange={(e) => setIncForm({ ...incForm, lat: e.target.value })} style={{ width: 90, padding: 4 }} placeholder="lat" />
            <input value={incForm.lon} onChange={(e) => setIncForm({ ...incForm, lon: e.target.value })} style={{ width: 90, padding: 4 }} placeholder="lon" />
            <select value={incForm.priority} onChange={(e) => setIncForm({ ...incForm, priority: e.target.value })} style={{ padding: 4 }}>
              {['P0', 'P1', 'P2'].map((p) => <option key={p}>{p}</option>)}
            </select>
            <input value={incForm.description} onChange={(e) => setIncForm({ ...incForm, description: e.target.value })} style={{ width: 130, padding: 4 }} placeholder="描述" />
            <button onClick={reportIncident} disabled={busy}>登记警情</button>
          </div>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid #334155' }}>
                {['#', '优先级', '位置', '状态', '建议', '操作'].map((h) => (
                  <th key={h} style={{ textAlign: 'left', padding: '4px 8px' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {incidents.map((i) => (
                <tr key={i.incidentId} style={{ borderBottom: '1px solid #1e293b' }}>
                  <td style={{ padding: '4px 8px' }}>#{i.incidentId}</td>
                  <td style={{ padding: '4px 8px', color: i.priority === 'P0' ? '#ef4444' : i.priority === 'P1' ? '#f97316' : '#eab308' }}>{i.priority}</td>
                  <td style={{ padding: '4px 8px' }}>{Number(i.lat).toFixed(4)}, {Number(i.lon).toFixed(4)}</td>
                  <td style={{ padding: '4px 8px' }}>{i.status}</td>
                  <td style={{ padding: '4px 8px' }}>
                    {i.suggestedSysid ? `Drone-${i.suggestedSysid}（${i.suggestionReason}）` : i.suggestionReason || '—'}
                  </td>
                  <td style={{ padding: '4px 8px' }}>
                    {i.suggestedSysid && i.status === 'SUGGESTED' && (
                      <button onClick={() => dispatch(i.incidentId)} disabled={busy}>确认派飞</button>
                    )}
                  </td>
                </tr>
              ))}
              {incidents.length === 0 && (
                <tr><td colSpan={6} style={{ padding: 8, color: '#94a3b8' }}>暂无警情</td></tr>
              )}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}