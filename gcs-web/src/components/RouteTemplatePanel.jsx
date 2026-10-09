import React, { useState } from 'react'
import { getAuthToken } from '../api.js'

const TYPES = [
  { key: 'tower', label: '电力杆塔', fields: 'towers' },
  { key: 'solar', label: '光伏网格', fields: 'polygon' },
  { key: 'pipeline', label: '管线带状', fields: 'line' },
  { key: 'shoreline', label: '河湖岸线', fields: 'polygon' },
]

const DEFAULT_PARAMS = {
  altM: 60, orbitRadiusM: 25, orbitPoints: 4, hoverSec: 5,
  lineSpacingM: 30, directionDeg: 0, stepM: 50, offsetM: 0,
}

/**
 * 行业航线模板面板（F3）：选类型 → 填参数 → 生成预览 → 一键下发任务。
 *
 * 坐标输入用 JSON 文本域（多边形/折线/杆塔表）——PoC 不做地图取点器，
 * 生成后的航点表+分段概览足以校验；地图取点属后续增强。
 */
export default function RouteTemplatePanel({ drone }) {
  const [type, setType] = useState('tower')
  const [params, setParams] = useState(DEFAULT_PARAMS)
  const [geoJson, setGeoJson] = useState(
    '[[22.5908,113.9345],[22.5909,113.9345]]')
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const buildBody = () => {
    let geo
    try {
      const arr = JSON.parse(geoJson)
      if (type === 'tower') {
        geo = { towers: arr.map((p, i) => ({ towerNo: `T${String(i + 1).padStart(3, '0')}`, lat: p[0], lon: p[1] })) }
      } else if (type === 'pipeline') {
        geo = { line: arr }
      } else {
        geo = { polygon: arr }
      }
    } catch (e) {
      throw new Error('坐标 JSON 解析失败：' + e.message)
    }
    const body = { type, altM: Number(params.altM) }
    if (type === 'tower') {
      body.orbitRadiusM = Number(params.orbitRadiusM)
      body.orbitPoints = Number(params.orbitPoints)
      body.hoverSec = Number(params.hoverSec)
    }
    if (type === 'solar') {
      body.lineSpacingM = Number(params.lineSpacingM)
      body.directionDeg = Number(params.directionDeg)
    }
    if (type === 'pipeline' || type === 'shoreline') {
      body.stepM = Number(params.stepM)
    }
    if (type === 'shoreline') {
      body.offsetM = Number(params.offsetM)
    }
    return { ...body, ...geo }
  }

  const generate = async () => {
    setBusy(true)
    try {
      const body = buildBody()
      const token = getAuthToken()
      const res = await fetch('/api/v1/route-templates/generate', {
        method: 'POST',
        headers: token
          ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
          : { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) {
        setError(`生成失败（${res.status}）：${data.result || ''}`)
        setResult(null)
      } else {
        setResult(data)
        setError(null)
      }
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const dispatch = async () => {
    if (!result || !drone) return
    setBusy(true)
    try {
      const token = getAuthToken()
      const res = await fetch(`/api/v1/drones/${drone.sysid}/mission`, {
        method: 'POST',
        headers: token
          ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
          : { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items: result.waypoints }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) {
        setError(`任务下发失败（${res.status}）：${data.result || ''}`)
      } else {
        const st = await fetch(`/api/v1/drones/${drone.sysid}/commands`, {
          method: 'POST',
          headers: token
            ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
            : { 'Content-Type': 'application/json' },
          body: JSON.stringify({ type: 'start_mission' }),
        })
        setError(st.ok ? null : `任务已上传但启动失败（${st.status}）——先 ARM`)
      }
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const numberField = (key, label) => (
    <label key={key} style={{ display: 'flex', gap: 4, alignItems: 'center', fontSize: 13 }}>
      {label}
      <input type="number" value={params[key]} style={{ width: 70, padding: 2 }}
             onChange={(e) => setParams({ ...params, [key]: e.target.value })} />
    </label>
  )

  const fieldsFor = (t) => {
    if (t === 'tower') return [numberField('orbitRadiusM', '环绕半径m'), numberField('orbitPoints', '环绕点数'), numberField('hoverSec', '拍照驻留s')]
    if (t === 'solar') return [numberField('lineSpacingM', '线间距m'), numberField('directionDeg', '主方向°')]
    if (t === 'pipeline') return [numberField('stepM', '步长m')]
    return [numberField('stepM', '步长m'), numberField('offsetM', '离岸偏移m')]
  }

  return (
    <div className="page">
      <h3 className="page-title">行业航线模板</h3>
      <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
        {TYPES.map((t) => (
          <button key={t.key} onClick={() => { setType(t.key); setResult(null) }}
                  style={{
                    padding: '4px 12px', cursor: 'pointer',
                    borderColor: type === t.key ? '#38bdf8' : '#334155',
                    fontWeight: type === t.key ? 700 : 400,
                  }}>
            {t.label}
          </button>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 10, marginBottom: 8, flexWrap: 'wrap' }}>
        {numberField('altM', '巡检高度m')}
        {fieldsFor(type)}
      </div>

      <textarea
        value={geoJson}
        onChange={(e) => setGeoJson(e.target.value)}
        rows={4}
        style={{ width: '100%', marginBottom: 8, padding: 6, fontFamily: 'monospace', fontSize: 12 }}
        placeholder={type === 'tower' ? '[[lat,lon],[lat,lon],...] 杆塔坐标（按巡检顺序）'
          : type === 'pipeline' ? '[[lat,lon],[lat,lon],...] 管道走向拐点' : '[[lat,lon],...] 多边形顶点'}
      />

      <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
        <button onClick={generate} disabled={busy}>生成预览</button>
        <button onClick={dispatch} disabled={busy || !result || !drone}
                title={drone ? '' : '先在左侧选择无人机'}>
          下发到 {drone ? `Drone-${drone.sysid}` : '（未选机）'}
        </button>
      </div>

      {error && (
        <div style={{ background: '#7f1d1d', borderRadius: 6, padding: '6px 10px', marginBottom: 8, fontSize: 13 }}>
          {error}
        </div>
      )}

      {result && (
        <div>
          <div style={{ fontSize: 14, marginBottom: 6 }}>
            <b>{result.waypointCount}</b> 航点 · 预计 <b>{result.estKm}</b> km · 高度 {result.altM}m
            {result.legs?.length > 1 && ` · ${result.legs.length} 分段（杆塔）`}
          </div>
          <div style={{ maxHeight: 300, overflowY: 'auto', border: '1px solid #334155', borderRadius: 6 }}>
            <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ position: 'sticky', top: 0, background: '#0f172a' }}>
                  {['#', 'cmd', 'lat', 'lon', 'alt', 'hold'].map((h) => (
                    <th key={h} style={{ textAlign: 'left', padding: '3px 8px', borderBottom: '1px solid #334155' }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.waypoints.map((w, i) => (
                  <tr key={i}>
                    <td style={{ padding: '2px 8px', color: '#64748b' }}>{i}</td>
                    <td style={{ padding: '2px 8px' }}>{w.cmd}</td>
                    <td style={{ padding: '2px 8px' }}>{Number(w.lat).toFixed(6)}</td>
                    <td style={{ padding: '2px 8px' }}>{Number(w.lon).toFixed(6)}</td>
                    <td style={{ padding: '2px 8px' }}>{w.alt}</td>
                    <td style={{ padding: '2px 8px' }}>{w.holdTime}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}