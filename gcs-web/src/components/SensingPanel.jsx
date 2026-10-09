import React, { useState, useEffect, useCallback } from 'react'
import { getAuthToken } from '../api.js'

const POLL_MS = 3000

const SOURCE_LABELS = {
  COUNTER_DRONE_RADAR: { label: '反制雷达', color: '#38bdf8' },
  FIVE_G_SENSING: { label: '5G-A 通感', color: '#a855f7' },
}
const CLASS_LABELS = { DRONE: '无人机', BIRD: '鸟类', UNKNOWN: '未识别' }

/**
 * 侦测态势面板（E2/E4）：非合作目标航迹表——反制雷达侦测 + 5G-A 通感双源。
 * **只读态势**：本面板无任何反制控制（不自研反制，spec §4 立场）。
 */
export default function SensingPanel() {
  const [data, setData] = useState(null)
  const [sources, setSources] = useState([])
  const [filter, setFilter] = useState('')
  const [alertOnly, setAlertOnly] = useState(false)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      const token = getAuthToken()
      const auth = token ? { Authorization: `Bearer ${token}` } : {}
      const q = new URLSearchParams()
      if (filter) q.set('source', filter)
      if (alertOnly) q.set('alertOnly', 'true')
      const [t, s] = await Promise.all([
        fetch(`/api/v1/sensing/tracks?${q}`, { headers: auth }),
        fetch('/api/v1/sensing/sources', { headers: auth }),
      ])
      if (!t.ok) throw new Error(`HTTP ${t.status}`)
      setData(await t.json())
      if (s.ok) setSources(await s.json())
      setError(null)
    } catch (e) {
      setError(e.message || '侦测态势加载失败')
    }
  }, [filter, alertOnly])

  useEffect(() => {
    load()
    const t = setInterval(load, POLL_MS)
    return () => clearInterval(t)
  }, [load])

  return (
    <div className="page">
      <h3 className="page-title">侦测态势（非合作目标）</h3>
      <div className="page-sub">只读态势 —— 本系统不自研反制器材，仅接入侦测数据</div>

      <div className="toolbar">
        <button aria-pressed={filter === ''} onClick={() => setFilter('')}>全部</button>
        {Object.entries(SOURCE_LABELS).map(([key, meta]) => (
          <button key={key} aria-pressed={filter === key} onClick={() => setFilter(key)}>
            {meta.label}
          </button>
        ))}
        <label style={{ fontSize: 12, display: 'flex', gap: 4, alignItems: 'center' }}>
          <input type="checkbox" checked={alertOnly}
                 onChange={(e) => setAlertOnly(e.target.checked)} />
          仅告警
        </label>
      </div>

      {error && <div className="notice crit">{error}</div>}

      {data && (
        <>
          <div className="stat-grid">
            <Card label="航迹" value={data.count} />
            <Card label="告警" value={data.alerts} warn={data.alerts > 0} />
            <Card label="来源" value={sources.length} />
          </div>

          <table>
            <thead>
              <tr>
                {['来源', '航迹', '位置', '高度m', '速度m/s', '分类', '置信', '距我方m', ''].map((h) => (
                  <th key={h}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {(data.tracks || []).map((t) => {
                const meta = SOURCE_LABELS[t.sourceType] || { label: t.sourceType, color: '#6b7280' }
                return (
                  <tr key={t.trackKey} className={t.alert ? 'row-crit' : undefined}>
                    <td style={{ color: meta.color }}>{meta.label}</td>
                    <td>{t.trackId}</td>
                    <td>{Number(t.lat).toFixed(5)}, {Number(t.lon).toFixed(5)}</td>
                    <td>{t.altM ?? '—'}</td>
                    <td>{t.speedMps != null ? Number(t.speedMps).toFixed(1) : '—'}</td>
                    <td>{CLASS_LABELS[t.classification] || t.classification}</td>
                    <td>{Number(t.confidence).toFixed(2)}</td>
                    <td>{t.nearestFleetM ?? '—'}</td>
                    <td style={{ color: 'var(--crit)', fontWeight: 700 }}>
                      {t.alert ? '近域告警' : ''}
                    </td>
                  </tr>
                )
              })}
              {(data.tracks || []).length === 0 && (
                <tr><td colSpan={9} className="dim">
                  {alertOnly ? '当前无告警航迹' : '暂无侦测航迹'}
                </td></tr>
              )}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}

function Card({ label, value, warn }) {
  return (
    <div className={`stat-cell${warn ? ' crit' : ''}`}>
      <label>{label}</label>
      <b>{value}</b>
    </div>
  )
}
