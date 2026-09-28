import React, { useState, useEffect, useRef, useCallback } from 'react'
import { getAuthToken } from '../api.js'

const POLL_MS = 2000

const SOURCES = ['truth', 'vision-source', 'pixels', 'external']

const SOURCE_LABELS = {
  truth: '投影真值',
  'vision-source': '模拟检测',
  pixels: 'Blob像素',
  external: '外部推理',
}

/**
 * CV 评测指标面板（F1）。
 *
 * 按国网机巡缺陷算法评测口径展示识别率 / 误检比 / 处理时间三指标，
 * 参考线：识别率 ≥85%、误检比 ≤15%。数值如实计算，truth 模式为上界校准。
 */
export default function CvEvalPanel() {
  const [metrics, setMetrics] = useState(null)
  const [source, setSource] = useState('')
  const [error, setError] = useState(null)
  const sourceRef = useRef(source)

  const fetchMetrics = useCallback(async (src) => {
    try {
      const token = getAuthToken()
      const url = src ? `/api/v1/cv-eval/metrics?source=${encodeURIComponent(src)}` : '/api/v1/cv-eval/metrics'
      const res = await fetch(url, {
        headers: token ? { 'Authorization': `Bearer ${token}` } : {},
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setMetrics(await res.json())
      setError(null)
    } catch (e) {
      setError(e.message || '获取评测指标失败')
    }
  }, [])

  useEffect(() => {
    sourceRef.current = source
    fetchMetrics(source)
    const t = setInterval(() => fetchMetrics(sourceRef.current), POLL_MS)
    return () => clearInterval(t)
  }, [source, fetchMetrics])

  const reset = async () => {
    try {
      const token = getAuthToken()
      await fetch('/api/v1/cv-eval/reset', {
        method: 'POST',
        headers: token ? { 'Authorization': `Bearer ${token}` } : {},
      })
      fetchMetrics(sourceRef.current)
    } catch (e) {
      setError(e.message || '重置失败')
    }
  }

  // 指标档位配色：识别率绿≥85%/黄≥70%/红；误检比绿≤15%/黄≤30%/红
  const gradeColor = (value, good, warn, lowerIsBetter) => {
    if (value == null) return 'var(--dim)'
    const hit = lowerIsBetter ? value <= good : value >= good
    if (hit) return '#2de2a5'
    const mid = lowerIsBetter ? value <= warn : value >= warn
    return mid ? '#ffc857' : '#ff5d5d'
  }

  const recall = metrics?.recall
  const fdr = metrics?.falseDetectionRatio
  const avg = metrics?.latencyAvgMs
  const p95 = metrics?.latencyP95Ms
  const fmtPct = (v) => (v == null ? '--' : `${(v * 100).toFixed(1)}%`)
  const fmtMs = (v) => (v == null ? '--' : `${v.toFixed(1)} ms`)

  return (
    <div style={{ padding: 16, height: '100%', overflow: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 style={{ margin: 0, fontSize: 18, color: 'var(--cyan)' }}>CV 智能识别评测</h2>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <span className="chip mono" style={{ fontSize: 10 }}>
            帧数 {metrics?.frames ?? '--'}
          </span>
          <button className="chip mono" style={{ fontSize: 10, cursor: 'pointer', background: 'rgba(255,93,93,0.15)' }} onClick={reset}>
            重置
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 6, marginBottom: 12, flexWrap: 'wrap' }}>
        <button
          key="all"
          className="chip mono"
          style={{ fontSize: 10, cursor: 'pointer', background: source === '' ? 'rgba(0,212,255,0.25)' : 'rgba(255,255,255,0.05)' }}
          onClick={() => setSource('')}
        >
          全部
        </button>
        {SOURCES.map((s) => (
          <button
            key={s}
            className="chip mono"
            style={{ fontSize: 10, cursor: 'pointer', background: source === s ? 'rgba(0,212,255,0.25)' : 'rgba(255,255,255,0.05)' }}
            onClick={() => setSource(s)}
          >
            {SOURCE_LABELS[s] || s}
          </button>
        ))}
      </div>

      {error && (
        <div style={{ textAlign: 'center', padding: 12, color: 'var(--crit)', marginBottom: 8 }}>⚠ {error}</div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginBottom: 8 }}>
        <MetricCard
          title="识别率"
          value={fmtPct(recall)}
          color={gradeColor(recall, 0.85, 0.70, false)}
          sub="参考线 ≥85%（国网口径）"
        />
        <MetricCard
          title="误检比"
          value={fmtPct(fdr)}
          color={gradeColor(fdr, 0.15, 0.30, true)}
          sub="参考线 ≤15%（国网口径）"
        />
        <MetricCard
          title="处理时间"
          value={fmtMs(avg)}
          color="var(--cyan)"
          sub={`p95 ${fmtMs(p95)}`}
        />
      </div>

      <div style={{ fontSize: 10, color: 'var(--dim)', marginBottom: 12 }}>
        检出 {metrics?.truePositives ?? '--'} TP / {metrics?.falsePositives ?? '--'} FP ·
        真值 {metrics?.truthTotal ?? '--'} · truth 模式为投影上界校准（识别率≈100%），
        pixels/external 反映真实检测质量
      </div>

      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11 }}>
        <thead>
          <tr style={{ color: 'var(--dim)', textAlign: 'left' }}>
            <th style={thStyle}>帧号</th>
            <th style={thStyle}>检测源</th>
            <th style={thStyle}>真值</th>
            <th style={thStyle}>TP</th>
            <th style={thStyle}>FP</th>
            <th style={thStyle}>耗时</th>
          </tr>
        </thead>
        <tbody>
          {(metrics?.perFrame || []).slice().reverse().map((f) => (
            <tr key={`${f.source}-${f.frameSeq}`} style={{ borderTop: '1px solid rgba(255,255,255,0.06)' }}>
              <td style={tdStyle}>#{f.frameSeq}</td>
              <td style={tdStyle}>{SOURCE_LABELS[f.source] || f.source}</td>
              <td style={tdStyle}>{f.truthCount}</td>
              <td style={{ ...tdStyle, color: '#2de2a5' }}>{f.tp}</td>
              <td style={{ ...tdStyle, color: f.fp > 0 ? '#ff5d5d' : 'var(--text)' }}>{f.fp}</td>
              <td style={tdStyle}>{f.latencyMs?.toFixed(1)} ms</td>
            </tr>
          ))}
        </tbody>
      </table>

      {metrics && metrics.frames === 0 && (
        <div style={{ textAlign: 'center', padding: 30, color: 'var(--dim)' }}>
          <div style={{ fontSize: 28, marginBottom: 6 }}>🎯</div>
          <div style={{ fontSize: 13 }}>暂无评测数据</div>
          <div style={{ fontSize: 11, marginTop: 4 }}>执行一次「拍摄→定位」后此处将出现逐帧指标</div>
        </div>
      )}
    </div>
  )
}

function MetricCard({ title, value, color, sub }) {
  return (
    <div style={{ padding: '10px 12px', borderRadius: 6, background: 'rgba(255,255,255,0.04)' }}>
      <div style={{ fontSize: 10, color: 'var(--dim)' }}>{title}</div>
      <div style={{ fontSize: 22, color, fontFamily: 'monospace', margin: '2px 0' }}>{value}</div>
      <div style={{ fontSize: 9, color: 'var(--dim)' }}>{sub}</div>
    </div>
  )
}

const thStyle = { padding: '4px 8px', fontWeight: 500 }
const tdStyle = { padding: '4px 8px', fontFamily: 'monospace' }
