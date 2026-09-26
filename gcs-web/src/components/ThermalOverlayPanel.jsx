import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

const POLL_MS = 3000

const TEMP_GRADIENT = [
  { temp: 20, color: '#2d3850', label: '20°C' },
  { temp: 30, color: '#1a6e3a', label: '30°C' },
  { temp: 40, color: '#8acb2a', label: '40°C' },
  { temp: 50, color: '#ffc857', label: '50°C' },
  { temp: 60, color: '#ff8c1a', label: '60°C' },
  { temp: 70, color: '#ff5d5d', label: '70°C' },
  { temp: 80, color: '#e03030', label: '80°C+' },
]

function getTempColor(temp) {
  for (let i = TEMP_GRADIENT.length - 1; i >= 0; i--) {
    if (temp >= TEMP_GRADIENT[i].temp) return TEMP_GRADIENT[i].color
  }
  return TEMP_GRADIENT[0].color
}

export default function ThermalOverlayPanel() {
  const [sources, setSources] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [threshold, setThreshold] = useState(60)
  const [alarms, setAlarms] = useState([])
  const [replayMode, setReplayMode] = useState(false)
  const [replayProgress, setReplayProgress] = useState(0)
  const [replayData, setReplayData] = useState([])
  const canvasRef = useRef(null)
  const pollRef = useRef(null)

  const fetchSources = useCallback(async () => {
    try {
      const data = await api.getThermalSources()
      setSources(data.sources || data || [])
      const triggered = (data.sources || data || []).filter((s) => s.temp >= threshold)
      setAlarms(triggered)
      setError(null)
    } catch (e) {
      setError(e.message || '获取热源数据失败')
    } finally {
      setLoading(false)
    }
  }, [threshold])

  useEffect(() => {
    fetchSources()
    pollRef.current = setInterval(fetchSources, POLL_MS)
    return () => clearInterval(pollRef.current)
  }, [fetchSources])

  useEffect(() => {
    if (!replayMode) {
      setReplayData([])
      setReplayProgress(0)
      return
    }
    const snapshots = []
    for (let i = 0; i < 20; i++) {
      snapshots.push(
        sources.map((s) => ({
          ...s,
          temp: s.temp + Math.sin(i * 0.3) * 8 + (Math.random() - 0.5) * 4,
        }))
      )
    }
    setReplayData(snapshots)
  }, [replayMode, sources])

  const displaySources = replayMode && replayData.length > 0
    ? replayData[Math.min(Math.floor(replayProgress * replayData.length), replayData.length - 1)] || []
    : sources

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    const w = canvas.width
    const h = canvas.height
    ctx.clearRect(0, 0, w, h)

    ctx.fillStyle = '#0b0e14'
    ctx.fillRect(0, 0, w, h)

    ctx.strokeStyle = '#232c40'
    ctx.lineWidth = 0.5
    for (let x = 0; x < w; x += 40) {
      ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke()
    }
    for (let y = 0; y < h; y += 40) {
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke()
    }

    displaySources.forEach((src) => {
      const cx = ((src.lon - 113.93) * 1000 + w / 2) % w
      const cy = ((22.59 - src.lat) * 1000 + h / 2) % h
      const radius = Math.max(8, Math.min(40, src.temp * 0.5))
      const color = getTempColor(src.temp)

      const gradient = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius)
      gradient.addColorStop(0, color)
      gradient.addColorStop(0.6, color + '80')
      gradient.addColorStop(1, color + '00')
      ctx.fillStyle = gradient
      ctx.beginPath()
      ctx.arc(cx, cy, radius, 0, Math.PI * 2)
      ctx.fill()

      if (src.temp >= threshold) {
        ctx.strokeStyle = '#ff5d5d'
        ctx.lineWidth = 2
        ctx.beginPath()
        ctx.arc(cx, cy, radius + 4, 0, Math.PI * 2)
        ctx.stroke()
      }

      ctx.fillStyle = '#e8edf5'
      ctx.font = '10px monospace'
      ctx.fillText(`${src.temp.toFixed(1)}°C`, cx + radius + 4, cy + 3)
      if (src.label) {
        ctx.fillText(src.label, cx + radius + 4, cy + 16)
      }
    })
  }, [displaySources, threshold])

  return (
    <div style={{ padding: 16, height: '100%', overflow: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 style={{ margin: 0, fontSize: 18, color: 'var(--cyan)' }}>热成像叠加面板</h2>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span className="chip mono" style={{ fontSize: 10 }}>
            {displaySources.length} 热源
          </span>
          {alarms.length > 0 && (
            <span className="chip" style={{ background: 'rgba(255,93,93,0.2)', color: 'var(--crit)', fontSize: 10 }}>
              {alarms.length} 告警
            </span>
          )}
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, marginBottom: 12, alignItems: 'center' }}>
        <label style={{ fontSize: 12, color: 'var(--dim)' }}>温度阈值告警</label>
        <input
          type="range"
          min={20}
          max={80}
          value={threshold}
          onChange={(e) => setThreshold(Number(e.target.value))}
          style={{ flex: 1, maxWidth: 200 }}
        />
        <span className="chip mono" style={{ fontSize: 11, color: threshold >= 60 ? 'var(--crit)' : 'var(--warn)' }}>
          {threshold}°C
        </span>
        <button
          className={`btn ${replayMode ? 'primary' : ''}`}
          style={{ padding: '4px 12px', fontSize: 11 }}
          onClick={() => setReplayMode((v) => !v)}
        >
          {replayMode ? '停止回放' : '历史回放'}
        </button>
      </div>

      {replayMode && replayData.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <input
            type="range"
            min={0}
            max={1000}
            value={Math.round(replayProgress * 1000)}
            onChange={(e) => setReplayProgress(Number(e.target.value) / 1000)}
            style={{ width: '100%' }}
          />
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, color: 'var(--dim)', marginTop: 4 }}>
            <span>回放进度 {(replayProgress * 100).toFixed(0)}%</span>
            <span>帧 {Math.min(Math.floor(replayProgress * replayData.length) + 1, replayData.length)}/{replayData.length}</span>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', gap: 12 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <canvas
            ref={canvasRef}
            width={600}
            height={400}
            style={{ width: '100%', height: 'auto', background: '#0b0e14', borderRadius: 8, border: '1px solid var(--border)' }}
          />
        </div>
        <div style={{ width: 200, flexShrink: 0 }}>
          <div style={{ fontSize: 12, color: 'var(--dim)', marginBottom: 8 }}>温度梯度</div>
          {TEMP_GRADIENT.map((g) => (
            <div key={g.temp} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <div style={{ width: 16, height: 16, borderRadius: 3, background: g.color }} />
              <span style={{ fontSize: 11, color: g.temp >= threshold ? 'var(--crit)' : 'var(--text)' }}>{g.label}</span>
            </div>
          ))}
        </div>
      </div>

      {alarms.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <h3 style={{ fontSize: 14, color: 'var(--crit)', marginBottom: 8 }}>温度告警列表</h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 8 }}>
            {alarms.map((a, i) => (
              <div key={i} style={{ padding: 8, borderRadius: 6, background: 'rgba(255,93,93,0.1)', border: '1px solid rgba(255,93,93,0.3)' }}>
                <div style={{ fontSize: 12, color: 'var(--crit)', fontWeight: 600 }}>
                  {a.temp.toFixed(1)}°C
                </div>
                <div style={{ fontSize: 10, color: 'var(--dim)' }}>
                  {a.label || `热源#${i + 1}`} · 阈值 {threshold}°C
                </div>
                {a.lat && a.lon && (
                  <div style={{ fontSize: 10, color: 'var(--dim)', marginTop: 2 }}>
                    {a.lat.toFixed(4)}, {a.lon.toFixed(4)}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {loading && <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>加载热源数据…</div>}
      {error && !loading && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--crit)' }}>⚠ {error}</div>
      )}
    </div>
  )
}