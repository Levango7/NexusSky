import React, { useState, useEffect, useCallback } from 'react'
import { getAuthToken } from '../api.js'

const GROUPS = [
  { key: 'day', label: '按日' },
  { key: 'drone', label: '按机' },
  { key: 'tenant', label: '按租户' },
]

/**
 * 运营报表面板（E6）：架次/时长/里程/能耗/成本核算——物流与植保结算口径。
 * 成本是参数化模型（cost-per-hour 由部署方配置），面板如实展示费率。
 */
export default function OpsReportPanel() {
  const [groupBy, setGroupBy] = useState('day')
  const [days, setDays] = useState(7)
  const [report, setReport] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      const token = getAuthToken()
      const to = new Date()
      const from = new Date(to.getTime() - days * 86_400_000)
      const res = await fetch(
        `/api/v1/operations/report?from=${from.toISOString()}&to=${to.toISOString()}&groupBy=${groupBy}`,
        { headers: token ? { Authorization: `Bearer ${token}` } : {} },
      )
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setReport(await res.json())
      setError(null)
    } catch (e) {
      setError(e.message || '报表加载失败')
    }
  }, [groupBy, days])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div style={{ padding: 12, height: '100%', boxSizing: 'border-box', overflowY: 'auto' }}>
      <h3 style={{ margin: '0 0 8px' }}>运营报表（单位经济）</h3>

      <div style={{ display: 'flex', gap: 8, marginBottom: 10, alignItems: 'center' }}>
        {GROUPS.map((g) => (
          <button key={g.key} onClick={() => setGroupBy(g.key)}
                  style={{ padding: '4px 12px', cursor: 'pointer',
                           borderColor: groupBy === g.key ? '#38bdf8' : '#334155',
                           fontWeight: groupBy === g.key ? 700 : 400 }}>
            {g.label}
          </button>
        ))}
        <select value={days} onChange={(e) => setDays(Number(e.target.value))} style={{ padding: 4 }}>
          {[1, 7, 14, 31].map((d) => (
            <option key={d} value={d}>近 {d} 天</option>
          ))}
        </select>
        <button onClick={load}>刷新</button>
      </div>

      {error && (
        <div style={{ background: '#7f1d1d', borderRadius: 6, padding: '6px 10px', marginBottom: 8, fontSize: 13 }}>
          {error}
        </div>
      )}

      {report && (
        <>
          <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
            <Card label="架次" value={report.summary.sorties} />
            <Card label="飞行分钟" value={report.summary.flightMinutes} />
            <Card label="里程 km" value={report.summary.distanceKm} />
            <Card label="能耗 %" value={report.summary.batteryUsedPct} />
            <Card label={`成本（${report.costPerHour}/h）`} value={report.summary.cost} />
          </div>

          <h4 style={{ margin: '4px 0 6px' }}>分组统计</h4>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse', marginBottom: 14 }}>
            <thead>
              <tr style={{ borderBottom: '1px solid #334155' }}>
                <th style={{ textAlign: 'left', padding: '4px 8px' }}>分组</th>
                <th style={{ textAlign: 'right', padding: '4px 8px' }}>架次</th>
                <th style={{ textAlign: 'right', padding: '4px 8px' }}>分钟</th>
                <th style={{ textAlign: 'right', padding: '4px 8px' }}>km</th>
              </tr>
            </thead>
            <tbody>
              {(report.groups || []).map((g) => (
                <tr key={g.key} style={{ borderBottom: '1px solid #1e293b' }}>
                  <td style={{ padding: '4px 8px' }}>{g.key}</td>
                  <td style={{ padding: '4px 8px', textAlign: 'right' }}>{g.sorties}</td>
                  <td style={{ padding: '4px 8px', textAlign: 'right' }}>{g.flightMinutes}</td>
                  <td style={{ padding: '4px 8px', textAlign: 'right' }}>{g.distanceKm}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h4 style={{ margin: '4px 0 6px' }}>单架次明细（按时长排序，前 20）</h4>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid #334155' }}>
                {['机', '起飞时间', '分钟', 'km', '能耗%', '主模式'].map((h) => (
                  <th key={h} style={{ textAlign: 'left', padding: '4px 8px' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {(report.topSorties || []).map((s, i) => (
                <tr key={i} style={{ borderBottom: '1px solid #1e293b' }}>
                  <td style={{ padding: '3px 8px' }}>Drone-{s.sysid}</td>
                  <td style={{ padding: '3px 8px' }}>{new Date(s.startMs).toLocaleString()}</td>
                  <td style={{ padding: '3px 8px' }}>{s.minutes}</td>
                  <td style={{ padding: '3px 8px' }}>{s.distanceKm}</td>
                  <td style={{ padding: '3px 8px' }}>{s.batteryUsed}</td>
                  <td style={{ padding: '3px 8px' }}>{s.mode}</td>
                </tr>
              ))}
              {(report.topSorties || []).length === 0 && (
                <tr><td colSpan={6} style={{ padding: 8, color: '#94a3b8' }}>窗口内无飞行</td></tr>
              )}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}

function Card({ label, value }) {
  return (
    <div style={{ border: '1px solid #334155', borderRadius: 6, padding: '6px 10px', minWidth: 110 }}>
      <div style={{ fontSize: 11, color: '#94a3b8' }}>{label}</div>
      <div style={{ fontSize: 18, fontWeight: 600 }}>{value ?? '—'}</div>
    </div>
  )
}