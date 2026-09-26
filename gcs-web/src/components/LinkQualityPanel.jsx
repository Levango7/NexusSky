import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

const POLL_MS = 2000

const LINK_COLORS = {
  excellent: '#2de2a5',
  good: '#00d4ff',
  fair: '#ffc857',
  poor: '#ff8c1a',
  critical: '#ff5d5d',
}

function getLinkColor(score) {
  if (score >= 90) return LINK_COLORS.excellent
  if (score >= 75) return LINK_COLORS.good
  if (score >= 50) return LINK_COLORS.fair
  if (score >= 25) return LINK_COLORS.poor
  return LINK_COLORS.critical
}

export default function LinkQualityPanel() {
  const [profiles, setProfiles] = useState([])
  const [selectedProfile, setSelectedProfile] = useState(null)
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [damageParams, setDamageParams] = useState({
    latency: 0,
    packetLoss: 0,
    bandwidth: 100,
  })
  const [history, setHistory] = useState([])
  const canvasRef = useRef(null)
  const pollRef = useRef(null)
  const historyRef = useRef([])

  const fetchProfiles = useCallback(async () => {
    try {
      const data = await api.getLinkSimProfiles()
      setProfiles(data.profiles || data || [])
      if (!selectedProfile && (data.profiles || data || []).length > 0) {
        setSelectedProfile((data.profiles || data)[0].id || (data.profiles || data)[0].name)
      }
      setError(null)
    } catch (e) {
      setError(e.message || '获取链路画像失败')
    } finally {
      setLoading(false)
    }
  }, [selectedProfile])

  const fetchStatus = useCallback(async () => {
    try {
      const data = await api.getLinkSimStatus()
      setStatus(data)
      const entry = {
        ts: Date.now(),
        latency: data.latency || 0,
        packetLoss: data.packetLoss || 0,
        bandwidth: data.bandwidth || 0,
        score: data.score || 0,
      }
      historyRef.current = [...historyRef.current.slice(-59), entry]
      setHistory([...historyRef.current])
      setError(null)
    } catch (e) {
      setError(e.message || '获取链路状态失败')
    }
  }, [])

  useEffect(() => {
    fetchProfiles()
    fetchStatus()
    pollRef.current = setInterval(fetchStatus, POLL_MS)
    return () => clearInterval(pollRef.current)
  }, [fetchProfiles, fetchStatus])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || history.length === 0) return
    const ctx = canvas.getContext('2d')
    const w = canvas.width
    const h = canvas.height

    ctx.clearRect(0, 0, w, h)
    ctx.fillStyle = '#0b0e14'
    ctx.fillRect(0, 0, w, h)

    const padding = 30
    const chartW = w - padding * 2
    const chartH = h - padding * 2

    ctx.strokeStyle = '#232c40'
    ctx.lineWidth = 0.5
    for (let i = 0; i <= 4; i++) {
      const y = padding + (chartH / 4) * i
      ctx.beginPath(); ctx.moveTo(padding, y); ctx.lineTo(w - padding, y); ctx.stroke()
    }

    const maxLatency = Math.max(200, ...history.map((d) => d.latency))
    const maxLoss = Math.max(10, ...history.map((d) => d.packetLoss))
    const maxBw = Math.max(100, ...history.map((d) => d.bandwidth))

    const drawLine = (key, maxVal, color, fill) => {
      ctx.strokeStyle = color
      ctx.lineWidth = 1.5
      ctx.beginPath()
      history.forEach((d, i) => {
        const x = padding + (chartW / Math.max(1, history.length - 1)) * i
        const y = padding + chartH - (d[key] / maxVal) * chartH
        if (i === 0) ctx.moveTo(x, y)
        else ctx.lineTo(x, y)
      })
      ctx.stroke()

      if (fill) {
        ctx.fillStyle = fill
        ctx.lineTo(padding + chartW, padding + chartH)
        ctx.lineTo(padding, padding + chartH)
        ctx.closePath()
        ctx.fill()
      }
    }

    drawLine('latency', maxLatency, '#ff5d5d', 'rgba(255,93,93,0.1)')
    drawLine('packetLoss', maxLoss, '#ffc857', 'rgba(255,193,87,0.1)')
    drawLine('bandwidth', maxBw, '#00d4ff', 'rgba(0,212,255,0.1)')

    ctx.fillStyle = '#ff5d5d'; ctx.font = '9px monospace'
    ctx.fillText(`延迟(ms) max=${maxLatency.toFixed(0)}`, padding, padding - 6)
    ctx.fillStyle = '#ffc857'
    ctx.fillText(`丢包(%) max=${maxLoss.toFixed(1)}`, padding + 120, padding - 6)
    ctx.fillStyle = '#00d4ff'
    ctx.fillText(`带宽(Mbps) max=${maxBw.toFixed(0)}`, padding + 240, padding - 6)
  }, [history])

  const currentScore = status?.score ?? 0
  const linkColor = getLinkColor(currentScore)

  return (
    <div style={{ padding: 16, height: '100%', overflow: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 style={{ margin: 0, fontSize: 18, color: 'var(--cyan)' }}>链路质量面板</h2>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span className="chip mono" style={{ fontSize: 10 }}>
            评分
          </span>
          <span
            className="chip mono"
            style={{ fontSize: 14, fontWeight: 700, color: linkColor, background: linkColor + '20' }}
          >
            {currentScore.toFixed(0)}
          </span>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, marginBottom: 12 }}>
        <div style={{ flex: 1 }}>
          <label style={{ fontSize: 11, color: 'var(--dim)', display: 'block', marginBottom: 4 }}>链路画像</label>
          <select
            value={selectedProfile || ''}
            onChange={(e) => setSelectedProfile(e.target.value)}
            style={{ width: '100%', padding: '6px 8px', fontSize: 12, background: 'var(--card)', color: 'var(--text)', border: '1px solid var(--border)', borderRadius: 4 }}
          >
            {profiles.map((p) => (
              <option key={p.id || p.name} value={p.id || p.name}>
                {p.name || p.id} {p.description ? `— ${p.description}` : ''}
              </option>
            ))}
          </select>
        </div>
      </div>

      <canvas
        ref={canvasRef}
        width={600}
        height={200}
        style={{ width: '100%', height: 'auto', background: '#0b0e14', borderRadius: 8, border: '1px solid var(--border)', marginBottom: 12 }}
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginBottom: 12 }}>
        <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
          <div style={{ fontSize: 10, color: 'var(--dim)' }}>延迟</div>
          <div style={{ fontSize: 16, color: '#ff5d5d', fontWeight: 600 }}>
            {status?.latency != null ? `${status.latency.toFixed(0)} ms` : '--'}
          </div>
        </div>
        <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
          <div style={{ fontSize: 10, color: 'var(--dim)' }}>丢包率</div>
          <div style={{ fontSize: 16, color: '#ffc857', fontWeight: 600 }}>
            {status?.packetLoss != null ? `${status.packetLoss.toFixed(2)}%` : '--'}
          </div>
        </div>
        <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
          <div style={{ fontSize: 10, color: 'var(--dim)' }}>带宽</div>
          <div style={{ fontSize: 16, color: '#00d4ff', fontWeight: 600 }}>
            {status?.bandwidth != null ? `${status.bandwidth.toFixed(1)} Mbps` : '--'}
          </div>
        </div>
      </div>

      <div style={{ padding: 12, borderRadius: 8, background: 'var(--card)', border: '1px solid var(--border)' }}>
        <h3 style={{ fontSize: 13, color: 'var(--cyan)', margin: '0 0 10px 0' }}>损伤参数调节</h3>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, marginBottom: 4 }}>
              <span style={{ color: 'var(--dim)' }}>注入延迟</span>
              <span className="mono" style={{ color: '#ff5d5d' }}>{damageParams.latency} ms</span>
            </div>
            <input
              type="range"
              min={0}
              max={500}
              value={damageParams.latency}
              onChange={(e) => setDamageParams((p) => ({ ...p, latency: Number(e.target.value) }))}
              style={{ width: '100%' }}
            />
          </div>
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, marginBottom: 4 }}>
              <span style={{ color: 'var(--dim)' }}>注入丢包率</span>
              <span className="mono" style={{ color: '#ffc857' }}>{damageParams.packetLoss}%</span>
            </div>
            <input
              type="range"
              min={0}
              max={30}
              value={damageParams.packetLoss}
              onChange={(e) => setDamageParams((p) => ({ ...p, packetLoss: Number(e.target.value) }))}
              style={{ width: '100%' }}
            />
          </div>
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, marginBottom: 4 }}>
              <span style={{ color: 'var(--dim)' }}>带宽限制</span>
              <span className="mono" style={{ color: '#00d4ff' }}>{damageParams.bandwidth} Mbps</span>
            </div>
            <input
              type="range"
              min={1}
              max={100}
              value={damageParams.bandwidth}
              onChange={(e) => setDamageParams((p) => ({ ...p, bandwidth: Number(e.target.value) }))}
              style={{ width: '100%' }}
            />
          </div>
        </div>
      </div>

      {loading && <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>加载链路数据…</div>}
      {error && !loading && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--crit)' }}>⚠ {error}</div>
      )}
    </div>
  )
}