import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

const POLL_MS = 3000
const DEFAULT_THRESHOLD = 60

/**
 * 热成像面板——机队热像读数（P2 #19，2026-10-24 接线后启用）。
 *
 * <p>数据链：drone-sim 1Hz 合成温度场 → THERMAL_DATA(30012) → cloud-backend
 * {@code ThermalIngestListener} 写快照 → 本面板轮询 GET /api/v1/thermal/status。
 * 显示的是<b>机队聚合口径</b>（每机的温度统计五元组 + 热点数），不是逐点热力图——
 * 逐点经纬度需要图像→地理位姿解算，登记在 docs/todo.md P2 #19 恢复条件 ③。
 *
 * <p>历史包袱（诚实记录）：本面板此前是假功能——轮询不存在的端点永远 404，
 * 「历史回放」是前端随机扰动伪造的数据（P1 #12 移除）。现在的每一格都来自
 * 真实遥测；温度阈值告警是客户端过滤（tempMax ≥ 阈值），不是后端判定。
 */
export default function ThermalOverlayPanel() {
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [threshold, setThreshold] = useState(DEFAULT_THRESHOLD)
  const [updatedAt, setUpdatedAt] = useState(null)
  const pollRef = useRef(null)

  const fetchStatus = useCallback(async () => {
    try {
      const data = await api.getThermalStatus()
      setItems(data.items || [])
      setUpdatedAt(Date.now())
      setError(null)
    } catch (e) {
      setError(e.message || '获取机队热读数失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchStatus()
    pollRef.current = setInterval(fetchStatus, POLL_MS)
    return () => clearInterval(pollRef.current)
  }, [fetchStatus])

  const alarms = items.filter((it) => it.tempMax >= threshold)

  const tempColor = (t) => {
    if (t >= 70) return '#e03030'
    if (t >= 60) return '#ff5d5d'
    if (t >= 50) return '#ff8c1a'
    if (t >= 40) return '#ffc857'
    return '#2de2a5'
  }

  return (
    <div className="page">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 className="page-title">热成像面板</h2>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span className="chip mono" style={{ fontSize: 10 }}>
            {items.length} 台机上报
          </span>
          {alarms.length > 0 && (
            <span className="chip" style={{ background: 'rgba(255,93,93,0.2)', color: 'var(--crit)', fontSize: 10 }}>
              {alarms.length} 超阈
            </span>
          )}
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, marginBottom: 12, alignItems: 'center' }}>
        <label style={{ fontSize: 12, color: 'var(--dim)' }}>温度阈值告警（tempMax ≥）</label>
        <input
          type="range"
          min={30}
          max={80}
          value={threshold}
          onChange={(e) => setThreshold(Number(e.target.value))}
          style={{ flex: 1, maxWidth: 200 }}
        />
        <span className="chip mono" style={{ fontSize: 11, color: threshold >= 60 ? 'var(--crit)' : 'var(--warn)' }}>
          {threshold}°C
        </span>
      </div>

      {items.length > 0 && (
        <div style={{ padding: 12, borderRadius: 8, background: 'var(--card)', border: '1px solid var(--border)', marginBottom: 12 }}>
          <div style={{ display: 'grid', gridTemplateColumns: '60px 1fr 1fr 90px 1fr', gap: 8, fontSize: 10, color: 'var(--dim)', marginBottom: 6 }}>
            <span>sysid</span>
            <span>温度场 max / mean</span>
            <span>min / 标准差</span>
            <span>热点数</span>
            <span>最近上报</span>
          </div>
          {items.map((it) => (
            <div
              key={it.sysid}
              style={{
                display: 'grid',
                gridTemplateColumns: '60px 1fr 1fr 90px 1fr',
                gap: 8,
                fontSize: 12,
                padding: '6px 0',
                borderTop: '1px solid var(--line)',
                alignItems: 'center',
              }}
            >
              <span className="mono">#{it.sysid}</span>
              <span className="mono" style={{ color: tempColor(it.tempMax), fontWeight: 600 }}>
                {Number(it.tempMax).toFixed(1)}°C / {Number(it.tempMean).toFixed(1)}°C
              </span>
              <span className="mono" style={{ color: 'var(--dim)' }}>
                {Number(it.tempMin).toFixed(1)}°C / σ{Number(it.tempStdDev).toFixed(1)}
              </span>
              <span className="mono">{it.hotspotCount}</span>
              <span style={{ fontSize: 10, color: 'var(--dim)' }}>
                {updatedAt ? `${Math.max(0, Math.round((updatedAt - it.updatedAtMs) / 1000))}s 前` : '--'}
              </span>
            </div>
          ))}
        </div>
      )}

      {alarms.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <h3 style={{ fontSize: 14, color: 'var(--crit)', marginBottom: 8 }}>超阈机列表（tempMax ≥ {threshold}°C）</h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 8 }}>
            {alarms.map((a) => (
              <div key={a.sysid} style={{ padding: 8, borderRadius: 6, background: 'rgba(255,93,93,0.1)', border: '1px solid rgba(255,93,93,0.3)' }}>
                <div style={{ fontSize: 12, color: 'var(--crit)', fontWeight: 600 }}>
                  #{a.sysid} · {Number(a.tempMax).toFixed(1)}°C
                </div>
                <div style={{ fontSize: 10, color: 'var(--dim)', marginTop: 2 }}>
                  均值 {Number(a.tempMean).toFixed(1)}°C · 热点 {a.hotspotCount}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {items.length === 0 && !loading && !error && (
        <div style={{ padding: 16, borderRadius: 8, background: 'var(--card)', border: '1px dashed var(--border)' }}>
          <p style={{ fontSize: 12, color: 'var(--dim)', margin: 0, lineHeight: 1.8 }}>
            机队暂无热像读数：THERMAL_DATA(30012) 由机载热像源 1Hz 下传，
            只有 online 且上报过的机会出现在这里。若机队在线但此处持续为空，
            检查 drone-sim 的热像源装配（P2 #19 接线守卫：ThermalDataEmissionTest）。
          </p>
        </div>
      )}

      {loading && <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>加载机队热读数…</div>}
      {error && !loading && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--crit)' }}>⚠ {error}</div>
      )}
    </div>
  )
}
