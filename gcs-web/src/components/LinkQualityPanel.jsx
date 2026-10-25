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

/**
 * 链路质量面板（P1 #9/#10，2026-10-24 改造）。
 *
 * <p>此前读的 GET /api/v1/link-sim/profiles|status 在后端不存在（假数据面板）；
 * 真实口径是 /api/v1/comm-adapt/quality/fleet（按机 × 链路的质量评分）。
 * 本面板改为：选机 → 读该机 bestLinkType 那条链路的实测质量
 * （latencyMs / packetLossPct / bandwidthKbps→Mbps），2s 轮询攒历史曲线。
 *
 * <p>「链路损伤注入」滑块已移除（P1 #11）：link-sim 是 CLI UDP 代理，损伤参数
 * 启动时固定、无运行时控制通道，云端也没有转发端点——此前滑块拖了没有任何
 * 反应（失败被 .catch(() => {}) 静默吞掉），是假控件。
 */
export default function LinkQualityPanel() {
  const [fleet, setFleet] = useState([])
  const [selectedSysid, setSelectedSysid] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [history, setHistory] = useState([])
  const canvasRef = useRef(null)
  const pollRef = useRef(null)
  const historyRef = useRef([])
  const selectedRef = useRef(null)

  const bestLinkOf = (item) => {
    if (!item) return null
    const details = item.details || []
    return details.find((d) => d.linkType === item.bestLinkType) || details[0] || null
  }

  const fetchFleet = useCallback(async () => {
    try {
      const data = await api.getCommLinks()
      const items = data.items || []
      setFleet(items)
      // 选中机从机队消失（下线/注销）时回落到第一台
      const current = items.find((it) => it.sysid === selectedRef.current) || items[0] || null
      if (current && current.sysid !== selectedRef.current) {
        setSelectedSysid(current.sysid)
      }
      const best = bestLinkOf(current)
      if (best) {
        historyRef.current = [
          ...historyRef.current.slice(-59),
          {
            ts: Date.now(),
            latency: best.latencyMs || 0,
            packetLoss: best.packetLossPct || 0,
            bandwidth: (best.bandwidthKbps || 0) / 1000, // Kbps → Mbps
          },
        ]
        setHistory([...historyRef.current])
      }
      setError(null)
    } catch (e) {
      setError(e.message || '获取机队链路质量失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    selectedRef.current = selectedSysid
  }, [selectedSysid])

  useEffect(() => {
    fetchFleet()
    pollRef.current = setInterval(fetchFleet, POLL_MS)
    return () => clearInterval(pollRef.current)
  }, [fetchFleet])

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

  const current = fleet.find((it) => it.sysid === selectedSysid) || fleet[0] || null
  const bestLink = bestLinkOf(current)
  const currentScore = current?.overallScore ?? 0
  const linkColor = getLinkColor(currentScore)

  return (
    <div className="page">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 className="page-title">链路质量面板</h2>
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
          <label style={{ fontSize: 11, color: 'var(--dim)', display: 'block', marginBottom: 4 }}>选择无人机（真实链路质量口径）</label>
          <select
            value={current?.sysid ?? ''}
            onChange={(e) => setSelectedSysid(Number(e.target.value))}
            style={{ width: '100%', padding: '6px 8px', fontSize: 12, background: 'var(--card)', color: 'var(--text)', border: '1px solid var(--border)', borderRadius: 4 }}
          >
            {fleet.length === 0 && <option value="">（机队暂无链路质量数据）</option>}
            {fleet.map((it) => (
              <option key={it.sysid} value={it.sysid}>
                #{it.sysid} · {it.grade} · 最佳 {it.bestLinkType} · {it.overallScore} 分
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
          <div style={{ fontSize: 10, color: 'var(--dim)' }}>延迟{bestLink ? `（${bestLink.linkType}）` : ''}</div>
          <div style={{ fontSize: 16, color: '#ff5d5d', fontWeight: 600 }}>
            {bestLink?.latencyMs != null ? `${bestLink.latencyMs.toFixed(0)} ms` : '--'}
          </div>
        </div>
        <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
          <div style={{ fontSize: 10, color: 'var(--dim)' }}>丢包率</div>
          <div style={{ fontSize: 16, color: '#ffc857', fontWeight: 600 }}>
            {bestLink?.packetLossPct != null ? `${bestLink.packetLossPct.toFixed(2)}%` : '--'}
          </div>
        </div>
        <div style={{ padding: 8, borderRadius: 6, background: 'var(--card)', border: '1px solid var(--border)' }}>
          <div style={{ fontSize: 10, color: 'var(--dim)' }}>带宽</div>
          <div style={{ fontSize: 16, color: '#00d4ff', fontWeight: 600 }}>
            {bestLink?.bandwidthKbps != null ? `${(bestLink.bandwidthKbps / 1000).toFixed(1)} Mbps` : '--'}
          </div>
        </div>
      </div>

      <div style={{ padding: 12, borderRadius: 8, background: 'var(--card)', border: '1px dashed var(--border)' }}>
        <h3 style={{ fontSize: 13, color: 'var(--dim)', margin: '0 0 8px 0' }}>链路损伤注入（未实现）</h3>
        <p style={{ fontSize: 11, color: 'var(--dim)', margin: 0, lineHeight: 1.7 }}>
          后端没有损伤注入通道：link-sim 是 CLI UDP 代理，损伤参数在启动时按画像固定、
          运行期不可调，云端也没有转发端点（2026-10-24 分诊确证）。此前的三个滑块是
          「拖了没有任何反应」的假控件（失败被静默吞掉），已按前端诚实性原则移除。
          待 link-sim 增加运行时控制通道（HTTP/控制帧）+ 云端转发端点后恢复此面板。
        </p>
      </div>

      {loading && <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>加载链路数据…</div>}
      {error && !loading && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--crit)' }}>⚠ {error}</div>
      )}
    </div>
  )
}
