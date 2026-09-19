import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { api } from '../api.js'

// 任务规划器：手动加点 / 一键模板 / 距离统计 / 上传
export default function MissionPlanner({ drone, missionDraft, setMissionDraft, onUploaded }) {
  const [status, setStatus] = useState(null)
  const [busy, setBusy] = useState(false)
  const idPrefix = useId()
  const nextIdRef = useRef(0)
  const createWaypoint = useCallback((waypoint) => ({
    ...waypoint,
    id: `${idPrefix}-${nextIdRef.current++}`,
  }), [idPrefix])
  // 地图点击由父组件添加无 id 航点；统一补齐后用于渲染并回写草稿。
  const waypoints = useMemo(() => missionDraft.map((w) => (
    w.id == null ? createWaypoint(w) : w
  )), [missionDraft, createWaypoint])
  useEffect(() => {
    if (missionDraft.some((w) => w.id == null)) {
      // 不覆盖规范化期间父组件可能追加的航点，下次渲染会处理新草稿。
      setMissionDraft((current) => current === missionDraft ? waypoints : current)
    }
  }, [missionDraft, waypoints, setMissionDraft])

  const base = drone || {}

  const addWaypoint = (dlat = 0, dlon = 0.0011) => {
    const last = missionDraft.length > 0 ? missionDraft[missionDraft.length - 1] : null
    const lat = Number(((last?.lat ?? base.lat ?? 22.5907) + dlat).toFixed(6))
    const lon = Number(((last?.lon ?? base.lon ?? 113.9345) + dlon).toFixed(6))
    const waypoint = createWaypoint({ cmd: 'waypoint', lat, lon, alt: 50, holdTime: 2 })
    setMissionDraft((current) => [...current, waypoint])
  }

  // 一键生成：起降点 + 方形测绘航线
  const genSurvey = () => {
    const lat = base.lat ?? 22.5907
    const lon = base.lon ?? 113.9345
    const d = 0.0011
    setMissionDraft([
      { cmd: 'takeoff', lat, lon, alt: 30, holdTime: 0 },
      { cmd: 'waypoint', lat: lat + d, lon, alt: 60, holdTime: 2 },
      { cmd: 'waypoint', lat: lat + d, lon: lon + d, alt: 60, holdTime: 2 },
      { cmd: 'waypoint', lat, lon: lon + d, alt: 60, holdTime: 2 },
      { cmd: 'waypoint', lat, lon, alt: 60, holdTime: 2 },
      { cmd: 'rtl', lat, lon, alt: 0, holdTime: 0 },
    ].map(createWaypoint))
  }

  // 一键生成：直线往返巡检航线
  const genPatrol = () => {
    const lat = base.lat ?? 22.5907
    const lon = base.lon ?? 113.9345
    setMissionDraft([
      { cmd: 'takeoff', lat, lon, alt: 30, holdTime: 0 },
      { cmd: 'waypoint', lat, lon: lon + 0.0022, alt: 40, holdTime: 3 },
      { cmd: 'waypoint', lat, lon, alt: 40, holdTime: 3 },
      { cmd: 'rtl', lat, lon, alt: 0, holdTime: 0 },
    ].map(createWaypoint))
  }

  const removeAt = (i) => setMissionDraft(missionDraft.filter((_, j) => j !== i))

  // Pull the mission currently stored on the drone into the draft for
  // editing - the other half of the GCS mission workflow.
  const download = async () => {
    if (!drone) return
    setBusy(true)
    setStatus(null)
    try {
      const r = await api.downloadMission(drone.sysid)
      if (r.status === 'ok') {
        const cmdName = { 22: 'takeoff', 16: 'waypoint', 20: 'rtl', 21: 'land', 19: 'return' }
        const items = (r.items || []).map((it) => createWaypoint({
          cmd: cmdName[it.command] || `cmd${it.command}`,
          lat: it.lat,
          lon: it.lon,
          alt: it.alt,
          holdTime: it.holdTime || 0,
        }))
        setMissionDraft(items)
        setStatus(r.count > 0 ? `已读取机载任务 ${r.count} 项` : '机载无任务')
      } else {
        setStatus('读取失败: ' + (r.result || 'unknown'))
      }
    } catch (e) {
      setStatus('读取失败: ' + e.message)
    } finally {
      setBusy(false)
    }
  }

  const upload = async () => {
    if (!drone || missionDraft.length === 0) return
    setBusy(true)
    setStatus(null)
    try {
      const r = await api.uploadMission(drone.sysid, missionDraft)
      setStatus(r.status === 'ok' ? `已上传 ${r.uploaded} 个航点` : JSON.stringify(r))
      if (onUploaded) onUploaded()
    } catch (e) {
      setStatus('上传失败: ' + e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel">
      <div className="panel-head">
        <h3>任务规划</h3>
        <span className="mono" style={{ fontSize: 11, color: 'var(--dim)' }}>
          {missionDraft.length} 点
        </span>
      </div>
      <div className="panel-body">
        <div className="mission-hint" style={{ fontSize: 11, color: 'var(--dim)', marginBottom: 6 }}>
          💡 地图上 <b>Shift+点击</b> 可直接添加航点
        </div>
        <div className="mission-actions">
          <button className="btn" onClick={() => addWaypoint()}>＋ 航点</button>
          <button className="btn" onClick={genSurvey}>▣ 测绘模板</button>
          <button className="btn" onClick={genPatrol}>⇄ 巡检模板</button>
          <button className="btn" onClick={() => setMissionDraft([])} disabled={missionDraft.length === 0}>
            ✕ 清空
          </button>
          <button className="btn" onClick={download} disabled={busy || !drone}>
            ⬇ 读取机载任务
          </button>
          <button
            className="btn primary"
            style={{ gridColumn: '1 / -1' }}
            onClick={upload}
            disabled={busy || !drone || missionDraft.length === 0}
          >
            ⬆ 上传任务到飞机
          </button>
        </div>

        {missionDraft.length > 0 && (
          <ol className="mission-list">
            {waypoints.map((w, i) => (
              <li key={w.id}>
                <span className="seq-badge">{i + 1}</span>
                <code>{w.cmd}</code>
                <span className="coords">
                  {w.lat?.toFixed(5)}, {w.lon?.toFixed(5)} · {w.alt}m
                </span>
                <button className="x" onClick={() => removeAt(i)} title="删除">×</button>
              </li>
            ))}
          </ol>
        )}

        {status && (
          <div className={`cmd-result ${/失败/.test(status) ? 'bad' : 'ok'}`}>{status}</div>
        )}
      </div>
    </div>
  )
}
