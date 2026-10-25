import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

const POLL_MS = 2000
const CONTROL_POLL_MS = 3000

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

const DEFAULT_FORM = {
  delayMs: 0,
  jitterMs: 0,
  dropPct: 0,
  bandwidthKbps: 0,
  partitionCycleSec: 0,
  partitionDownSec: 0,
}

/** 控制面视图 → 滑块表单（只用上行；面板默认双向同时改）。 */
function formFromControl(control) {
  const d = control?.uplink || {}
  return {
    delayMs: d.delayMs ?? 0,
    jitterMs: d.jitterMs ?? 0,
    dropPct: d.dropPct ?? 0,
    bandwidthKbps: d.bandwidthKbps ?? 0,
    partitionCycleSec: d.partitionCycleSec ?? 0,
    partitionDownSec: d.partitionDownSec ?? 0,
  }
}

function formatBandwidth(kbps) {
  if (!kbps) return '不限速'
  return kbps >= 1000 ? `${(kbps / 1000).toFixed(1)} Mbps` : `${Math.round(kbps)} kbps`
}

/**
 * 链路质量面板。
 *
 * <p><b>质量读数</b>（P1 #9/#10）：/api/v1/comm-adapt/quality/fleet，按机 × 链路
 * 的实测评分，2s 轮询攒历史曲线。
 *
 * <p><b>损伤控制</b>（P2 #20，2026-10-24 恢复）：link-sim 新增了运行时控制面
 * （HTTP GET/PATCH /profile，只绑 127.0.0.1），云端转发为
 * /api/v1/link-sim/control（GET=OBSERVER，PATCH=OPERATOR）。
 * 与 P1 #11 移除的假滑块的区别：这里的每个滑块都读自控制面的**当前真实值**，
 * 拖动后必须点「应用」才发 PATCH，成功后以响应回填；控制面不可达时整块控件
 * 禁用并显示原因（503），不再出现「拖了没反应」。
 *
 * <p>参数的权威校验在 link-sim 侧：越界/互斥会返回 400，面板原样显示后端原因。
 * 丢包滑块是<b>独立随机丢包</b>（uniform），应用后会替换画像的 GE 突发模型，
 * 界面上用 dropModel 标记说清楚。
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

  const [control, setControl] = useState(null)
  const [controlError, setControlError] = useState(null)
  const [form, setForm] = useState(null)
  const [profileChoice, setProfileChoice] = useState('')
  const [busy, setBusy] = useState(false)
  // 用户正在拖滑块时，轮询回来的值不能覆盖表单（否则滑块会"弹回去"）。
  const dirtyRef = useRef(false)

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

  const fetchControl = useCallback(async () => {
    try {
      const data = await api.getLinkSimControl()
      setControl(data)
      if (!dirtyRef.current) {
        setForm(formFromControl(data))
      }
      setProfileChoice((prev) => prev || (data.profile || '').split('/')[0])
      setControlError(null)
    } catch (e) {
      // 控制通道默认关闭（link-sim 未带 --control-port）是正常部署形态，
      // 不是"面板坏了"：如实标注不可用并把所有控件禁用。
      setControl(null)
      setControlError(e.message || 'link-sim 控制通道不可用')
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
    fetchControl()
    const timer = setInterval(fetchControl, CONTROL_POLL_MS)
    return () => clearInterval(timer)
  }, [fetchControl])

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

  const setField = (key, value) => {
    dirtyRef.current = true
    setForm((prev) => {
      const next = { ...(prev || DEFAULT_FORM), [key]: value }
      if (key === 'partitionCycleSec') {
        // 周期为 0 时黑洞窗口必须为 0（后端语义：无周期分区）；否则不能超过周期。
        next.partitionDownSec = value === 0
          ? 0
          : Math.min(next.partitionDownSec || 0, value)
      }
      if (key === 'partitionDownSec') {
        next.partitionDownSec = Math.min(value, next.partitionCycleSec || 0)
      }
      return next
    })
  }

  const applyPatch = async (patch) => {
    setBusy(true)
    try {
      const data = await api.patchLinkSimControl(patch)
      setControl(data)
      dirtyRef.current = false
      setForm(formFromControl(data))
      setProfileChoice((data.profile || '').split('/')[0])
      setControlError(null)
    } catch (e) {
      setControlError(e.message || '应用损伤参数失败')
    } finally {
      setBusy(false)
    }
  }

  const applyDamage = () => {
    if (!form) return
    applyPatch({ ...form })
  }

  const current = fleet.find((it) => it.sysid === selectedSysid) || fleet[0] || null
  const bestLink = bestLinkOf(current)
  const currentScore = current?.overallScore ?? 0
  const linkColor = getLinkColor(currentScore)
  const online = !!control
  const up = control?.uplink
  const down = control?.downlink
  const profiles = control?.profiles || []

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

      <div style={{ padding: 12, borderRadius: 8, background: 'var(--card)', border: '1px solid var(--border)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
          <h3 style={{ fontSize: 13, margin: 0 }}>仿真损伤（link-sim 控制面）</h3>
          <span
            className="chip mono"
            style={{
              fontSize: 10,
              color: online ? '#2de2a5' : 'var(--dim)',
              background: online ? '#2de2a520' : 'transparent',
              border: '1px solid var(--border)',
            }}
          >
            {online ? `在线 · ${control.mode} · 丢包模型 ${up?.dropModel || 'ge'}` : '离线'}
          </span>
        </div>

        {!online && (
          <p style={{ fontSize: 11, color: 'var(--dim)', margin: '0 0 8px 0', lineHeight: 1.7 }}>
            控制通道不可用：{controlError}。link-sim 必须以
            {' '}<code>--control-port 14601</code>{' '}
            启动（默认 0=关闭），云端才能转发损伤参数；此处不显示假控件。
          </p>
        )}

        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 10, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 11, color: 'var(--dim)' }}>画像</span>
          <select
            value={profileChoice}
            disabled={!online || busy}
            onChange={(e) => setProfileChoice(e.target.value)}
            style={{ padding: '4px 8px', fontSize: 12, background: 'var(--bg)', color: 'var(--text)', border: '1px solid var(--border)', borderRadius: 4 }}
          >
            {profiles.map((p) => <option key={p} value={p}>{p}</option>)}
            {profiles.length === 0 && <option value="">--</option>}
          </select>
          <button
            className="btn sm"
            disabled={!online || busy || !profileChoice}
            onClick={() => applyPatch({ profile: profileChoice })}
          >
            切换画像
          </button>
          <button className="btn sm" disabled={!online || busy} onClick={() => applyPatch({ reset: true })}>
            恢复画像默认
          </button>
          <span style={{ fontSize: 10, color: 'var(--dim)' }}>
            上行 {up?.stats ? `fwd ${up.stats.forwarded} / drop ${up.stats.dropped} (${up.stats.dropPct.toFixed(1)}%) / ${up.stats.avgDelayMs}ms` : '--'}
            {' · '}
            下行 {down?.stats ? `fwd ${down.stats.forwarded} / drop ${down.stats.dropped}` : '--'}
          </span>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '8px 16px' }}>
          <SliderRow
            label="基准延迟" value={form?.delayMs ?? 0} min={0} max={2000} step={5}
            disabled={!online || busy} onChange={(v) => setField('delayMs', v)}
            display={`${Math.round(form?.delayMs ?? 0)} ms`}
          />
          <SliderRow
            label="抖动" value={form?.jitterMs ?? 0} min={0} max={1000} step={5}
            disabled={!online || busy} onChange={(v) => setField('jitterMs', v)}
            display={`${Math.round(form?.jitterMs ?? 0)} ms`}
          />
          <SliderRow
            label="丢包（独立随机）" value={form?.dropPct ?? 0} min={0} max={100} step={0.5}
            disabled={!online || busy} onChange={(v) => setField('dropPct', v)}
            display={`${(form?.dropPct ?? 0).toFixed(1)} %`}
          />
          <SliderRow
            label="带宽" value={form?.bandwidthKbps ?? 0} min={0} max={100000} step={100}
            disabled={!online || busy} onChange={(v) => setField('bandwidthKbps', v)}
            display={formatBandwidth(form?.bandwidthKbps ?? 0)}
          />
          <SliderRow
            label="分区周期" value={form?.partitionCycleSec ?? 0} min={0} max={120} step={0.5}
            disabled={!online || busy} onChange={(v) => setField('partitionCycleSec', v)}
            display={form?.partitionCycleSec ? `${form.partitionCycleSec.toFixed(1)} s` : '关闭'}
          />
          <SliderRow
            label="分区黑洞窗口" value={form?.partitionDownSec ?? 0} min={0} max={60} step={0.5}
            disabled={!online || busy || !form?.partitionCycleSec}
            onChange={(v) => setField('partitionDownSec', v)}
            display={form?.partitionDownSec ? `${form.partitionDownSec.toFixed(1)} s` : '0 s'}
          />
        </div>

        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 10 }}>
          <button className="btn primary sm" disabled={!online || busy || !form} onClick={applyDamage}>
            {busy ? '应用中…' : '应用损伤'}
          </button>
          <span style={{ fontSize: 10, color: 'var(--dim)' }}>
            应用后双向生效（direction=both）；丢包滑块切换到独立随机模型，恢复画像默认即回到 GE 突发模型。
          </span>
        </div>
      </div>

      {loading && <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>加载链路数据…</div>}
      {error && !loading && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--crit)' }}>⚠ {error}</div>
      )}
    </div>
  )
}

function SliderRow({ label, value, min, max, step, disabled, onChange, display }) {
  return (
    <label style={{ display: 'block', fontSize: 11, color: 'var(--dim)' }}>
      <span style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 2 }}>
        <span>{label}</span>
        <span className="mono" style={{ color: disabled ? 'var(--dim)' : 'var(--text)' }}>{display}</span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        style={{ width: '100%', accentColor: '#00d4ff' }}
      />
    </label>
  )
}
