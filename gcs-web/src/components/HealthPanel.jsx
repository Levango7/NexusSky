import React, { useState, useEffect, useCallback, useRef } from 'react'
import {
  getDroneHealth,
  getFleetHealth,
  getDroneHealthHistory,
  getHealthWarnings,
  listMaintenanceRecords,
  createMaintenanceRecord,
  updateMaintenanceRecord,
  getMaintenancePredictions,
  getMaintenanceSchedule,
} from '../api.js'
import { toArray, pick, fmtTime, POLL_MS } from '../utils/panelUtils.js'

// 健康管理面板（P1）
// 机队健康总览 + 单机健康详情 + 健康告警 + 预测性维护 + 维护记录 + 维护计划
// 风格与 TrackingPanel / GeofencePanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 5s；AbortController 竞态守卫
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（AbortController 竞态守卫）


// 7 部件名称
const COMPONENTS = [
  { key: 'BATTERY', label: '电池' },
  { key: 'MOTOR', label: '电机' },
  { key: 'VIBRATION', label: '振动' },
  { key: 'TEMPERATURE', label: '温度' },
  { key: 'COMMUNICATION', label: '通信' },
  { key: 'IMU', label: 'IMU' },
  { key: 'GPS', label: 'GPS' },
]

// 健康评分 → 颜色
function scoreColor(score) {
  if (score == null) return 'var(--dim-2)'
  const n = Number(score)
  if (isNaN(n)) return 'var(--dim-2)'
  // 兼容 0-1 和 0-100
  const pct = n <= 1 ? n * 100 : n
  if (pct >= 80) return 'var(--ok)'
  if (pct >= 60) return 'var(--warn)'
  return 'var(--crit)'
}

function fmtScore(score) {
  if (score == null) return '--'
  const n = Number(score)
  if (isNaN(n)) return '--'
  const pct = n <= 1 ? n * 100 : n
  return pct.toFixed(0)
}


// 告警级别 → 颜色
const WARNING_LEVEL = {
  CRITICAL: { color: 'var(--crit)', label: '严重' },
  HIGH: { color: 'var(--crit)', label: '高' },
  MEDIUM: { color: 'var(--warn)', label: '中' },
  LOW: { color: 'var(--dim)', label: '低' },
  INFO: { color: 'var(--cyan)', label: '信息' },
}

function warningMeta(l) {
  return WARNING_LEVEL[l] || { color: 'var(--dim)', label: l || '--' }
}

export default function HealthPanel() {
  // ---- 机队健康 ----
  const [fleetHealth, setFleetHealth] = useState([])
  // ---- 健康告警 ----
  const [warnings, setWarnings] = useState([])
  // ---- 维护记录 ----
  const [records, setRecords] = useState([])
  // ---- 预测性维护 ----
  const [predictions, setPredictions] = useState([])
  // ---- 维护计划 ----
  const [schedule, setSchedule] = useState([])

  const [error, setError] = useState(null)
  const [formError, setFormError] = useState(null)

  // ---- 选中的无人机 ----
  const [selectedSysid, setSelectedSysid] = useState(null)
  const [droneDetail, setDroneDetail] = useState(null)
  const [droneHistory, setDroneHistory] = useState([])
  const [detailLoading, setDetailLoading] = useState(false)
  const detailAbortRef = useRef(null)

  // ---- 创建维护记录表单 ----
  const [createForm, setCreateForm] = useState({
    sysid: '',
    type: 'ROUTINE',
    description: '',
    scheduledAt: '',
  })
  const [creating, setCreating] = useState(false)

  // ---- 轮询机队健康 + 告警 + 维护记录 + 预测 + 计划 ----
  useEffect(() => {
    const controller = new AbortController()
    let cancelled = false

    const load = async () => {
      if (controller.signal.aborted) return
      try {
        const [fleetData, warnData, recData, predData, schedData] = await Promise.all([
          getFleetHealth().catch(() => []),
          getHealthWarnings().catch(() => []),
          listMaintenanceRecords().catch(() => []),
          getMaintenancePredictions().catch(() => []),
          getMaintenanceSchedule().catch(() => []),
        ])
        if (cancelled || controller.signal.aborted) return
        setFleetHealth(toArray(fleetData, 'drones'))
        setWarnings(toArray(warnData, 'warnings'))
        setRecords(toArray(recData, 'records'))
        setPredictions(toArray(predData, 'predictions'))
        setSchedule(toArray(schedData, 'schedule'))
      } catch (e) {
        // 静默失败
      }
    }

    load()
    const timer = setInterval(load, POLL_MS)
    return () => {
      cancelled = true
      controller.abort()
      clearInterval(timer)
    }
  }, [])

  // ---- 选中无人机 → 加载详情 + 历史 ----
  useEffect(() => {
    if (selectedSysid == null) {
      setDroneDetail(null)
      setDroneHistory([])
      return
    }

    const controller = new AbortController()
    if (detailAbortRef.current) detailAbortRef.current.abort()
    detailAbortRef.current = controller

    let cancelled = false
    const load = async () => {
      setDetailLoading(true)
      try {
        const [detail, history] = await Promise.all([
          getDroneHealth(selectedSysid).catch(() => null),
          getDroneHealthHistory(selectedSysid).catch(() => []),
        ])
        if (cancelled || controller.signal.aborted) return
        setDroneDetail(detail)
        setDroneHistory(toArray(history, 'history'))
      } catch (e) {
        if (cancelled || controller.signal.aborted) return
      } finally {
        if (!cancelled && !controller.signal.aborted) setDetailLoading(false)
      }
    }
    load()

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [selectedSysid])

  // ---- 创建维护记录 ----
  const handleCreateRecord = useCallback(async (e) => {
    if (e && e.preventDefault) e.preventDefault()
    setFormError(null)
    const sysid = Number(createForm.sysid)
    if (!Number.isFinite(sysid) || sysid <= 0) {
      setFormError('请输入有效的无人机 sysid')
      return
    }
    const payload = {
      sysid,
      type: createForm.type,
      description: createForm.description.trim() || undefined,
    }
    if (createForm.scheduledAt) {
      const t = new Date(createForm.scheduledAt).getTime()
      if (!isNaN(t)) payload.scheduledAt = t
    }
    setCreating(true)
    try {
      await createMaintenanceRecord(payload)
      setCreateForm((prev) => ({ ...prev, sysid: '', description: '', scheduledAt: '' }))
    } catch (e) {
      setFormError('创建维护记录失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setCreating(false)
    }
  }, [createForm])

  // ---- 派生：机队平均分 ----
  const fleetAvg = fleetHealth.length > 0
    ? fleetHealth.reduce((sum, d) => sum + (Number(pick(d, 'score', 'healthScore')) || 0), 0) / fleetHealth.length
    : 0

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        健康管理
        <span style={{ fontSize: 10, color: 'var(--dim)', marginLeft: 8 }}>
          机队 {fleetHealth.length} 架 · 告警 {warnings.length} · 维护 {records.length}
        </span>
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* ===== 左列：机队总览 + 健康告警 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {/* 机队健康总览 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: 11, color: 'var(--dim)' }}>机队健康总览（{fleetHealth.length}）</span>
              <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>
                平均分：<b style={{ color: scoreColor(fleetAvg) }}>{fmtScore(fleetAvg)}</b>
              </span>
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {fleetHealth.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无机队健康数据</div>
              ) : (
                fleetHealth.map((d, i) => {
                  const sysid = pick(d, 'sysid', 'id') ?? i
                  const score = pick(d, 'score', 'healthScore')
                  const sc = scoreColor(score)
                  const isSel = sysid === selectedSysid
                  return (
                    <div
                      key={sysid}
                      onClick={() => setSelectedSysid((prev) => (prev === sysid ? null : sysid))}
                      style={{
                        padding: '6px 10px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-3, var(--bg-1))' : 'transparent',
                        borderLeft: `3px solid ${sc}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 11, fontWeight: 'bold', color: 'var(--text)' }}>#{sysid}</span>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                          <span style={{ fontSize: 14, fontWeight: 'bold', color: sc, fontFamily: 'var(--mono)' }}>
                            {fmtScore(score)}
                          </span>
                          <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>/100</span>
                        </div>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        {pick(d, 'status', 'healthStatus') || '--'}
                        {pick(d, 'lastCheck', 'lastCheckedAt') != null && ` · ${fmtTime(pick(d, 'lastCheck', 'lastCheckedAt'))}`}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 健康告警 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
              健康告警（{warnings.length}）
            </div>
            <div style={{ maxHeight: 240, overflowY: 'auto' }}>
              {warnings.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无告警</div>
              ) : (
                warnings.map((w, i) => {
                  const wid = pick(w, 'id', 'warningId') ?? i
                  const lvl = pick(w, 'level', 'severity') || 'LOW'
                  const meta = warningMeta(lvl)
                  return (
                    <div key={wid} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {pick(w, 'message', 'title', 'description') || '--'}
                          </span>
                        </div>
                        <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${meta.color}`, color: meta.color, flexShrink: 0 }}>
                          {meta.label}
                        </span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        {pick(w, 'sysid') != null && <span>无人机：#{pick(w, 'sysid')}</span>}
                        {pick(w, 'component') != null && <span>部件：{pick(w, 'component')}</span>}
                        <span>{fmtTime(pick(w, 'timestamp', 'createdAt', 'triggeredAt'))}</span>
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>
        </div>

        {/* ===== 右列：单机详情 + 预测性维护 + 维护记录 + 维护计划 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {/* 单机健康详情 */}
          {selectedSysid != null ? (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span>单机健康 #{selectedSysid}</span>
                {detailLoading && <span style={{ fontSize: 9, color: 'var(--cyan)' }}>加载中…</span>}
              </div>
              {droneDetail ? (
                <div>
                  {/* 总分 */}
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8, padding: '4px 6px', background: 'var(--bg-1)', borderRadius: 3 }}>
                    <span style={{ fontSize: 10, color: 'var(--dim-2)' }}>总评分</span>
                    <span style={{ fontSize: 16, fontWeight: 'bold', color: scoreColor(pick(droneDetail, 'score', 'healthScore')), fontFamily: 'var(--mono)' }}>
                      {fmtScore(pick(droneDetail, 'score', 'healthScore'))}<span style={{ fontSize: 10, color: 'var(--dim-2)' }}>/100</span>
                    </span>
                  </div>
                  {/* 7 部件评分 */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                    {COMPONENTS.map((c) => {
                      const compData = pick(droneDetail, 'components', 'componentHealth') || {}
                      const compScore = typeof compData === 'object' ? pick(compData[c.key.toLowerCase()], 'score', 'value') ?? pick(compData[c.key], 'score', 'value') : null
                      const sc = scoreColor(compScore)
                      return (
                        <div key={c.key} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, fontSize: 10, padding: '3px 0', borderBottom: '1px solid var(--line-2)' }}>
                          <span style={{ color: 'var(--dim-2)' }}>{c.label}</span>
                          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                            <span style={{ color: sc, fontWeight: 'bold', fontFamily: 'var(--mono)' }}>{fmtScore(compScore)}</span>
                            {/* 评分条 */}
                            <div style={{ width: 60, height: 4, background: 'var(--bg-1)', borderRadius: 2, overflow: 'hidden' }}>
                              <div style={{ width: `${Math.min(100, fmtScore(compScore) === '--' ? 0 : Number(fmtScore(compScore)))}%`, height: '100%', background: sc }} />
                            </div>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                </div>
              ) : (
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无健康数据</div>
              )}
            </div>
          ) : (
            <div style={{ ...cardStyle, padding: 20, textAlign: 'center' }}>
              <div style={{ fontSize: 11, color: 'var(--dim-2)' }}>← 选择左侧无人机查看详情</div>
            </div>
          )}

          {/* 预测性维护建议 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
              预测性维护建议（{predictions.length}）
            </div>
            <div style={{ maxHeight: 180, overflowY: 'auto' }}>
              {predictions.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无维护建议</div>
              ) : (
                predictions.map((p, i) => {
                  const pid = pick(p, 'id', 'predictionId') ?? i
                  const urgency = pick(p, 'urgency', 'priority') || 'LOW'
                  const meta = warningMeta(urgency)
                  return (
                    <div key={pid} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 11, color: 'var(--text)' }}>
                          #{pick(p, 'sysid') || '--'} · {pick(p, 'component', 'type') || '--'}
                        </span>
                        <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${meta.color}`, color: meta.color }}>{meta.label}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        {pick(p, 'description', 'recommendation', 'message') || '--'}
                        {pick(p, 'estimatedTime', 'eta') != null && ` · 预计：${fmtTime(pick(p, 'estimatedTime', 'eta'))}`}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 创建维护记录 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4 }}>
              创建维护记录
            </div>
            {formError && (
              <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 6, padding: '3px 6px', background: 'var(--bg-1)', borderRadius: 3, border: '1px solid var(--crit)' }}>
                ⚠ {formError}
              </div>
            )}
            <form onSubmit={handleCreateRecord} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '0 0 90px' }}>
                  <div style={labelStyle}>sysid</div>
                  <input type="number" min="1" value={createForm.sysid} onChange={(e) => setCreateForm((p) => ({ ...p, sysid: e.target.value }))} style={inputStyle} placeholder="1" />
                </div>
                <div style={{ flex: '1 1 120px' }}>
                  <div style={labelStyle}>类型</div>
                  <select value={createForm.type} onChange={(e) => setCreateForm((p) => ({ ...p, type: e.target.value }))} style={inputStyle}>
                    <option value="ROUTINE">例行维护</option>
                    <option value="REPAIR">维修</option>
                    <option value="REPLACE">更换</option>
                    <option value="CALIBRATION">校准</option>
                    <option value="INSPECTION">检查</option>
                  </select>
                </div>
                <div style={{ flex: '1 1 150px' }}>
                  <div style={labelStyle}>计划时间（可选）</div>
                  <input type="datetime-local" value={createForm.scheduledAt} onChange={(e) => setCreateForm((p) => ({ ...p, scheduledAt: e.target.value }))} style={inputStyle} />
                </div>
              </div>
              <input type="text" value={createForm.description} onChange={(e) => setCreateForm((p) => ({ ...p, description: e.target.value }))} style={inputStyle} placeholder="维护描述（可选）" />
              <button type="submit" disabled={creating} style={{ ...miniBtnStyle, padding: '4px 12px', alignSelf: 'flex-start', color: 'var(--cyan)', borderColor: 'var(--cyan)', cursor: creating ? 'not-allowed' : 'pointer', opacity: creating ? 0.5 : 1 }}>
                {creating ? '创建中…' : '创建记录'}
              </button>
            </form>
          </div>

          {/* 维护记录 + 计划 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
              维护记录（{records.length}）· 计划（{schedule.length}）
            </div>
            <div style={{ maxHeight: 240, overflowY: 'auto' }}>
              {records.length === 0 && schedule.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无维护记录</div>
              ) : (
                <>
                  {/* 维护计划（按时间排序）*/}
                  {schedule.map((s, i) => {
                    const sid = pick(s, 'id', 'scheduleId') ?? i
                    return (
                      <div key={'s_' + sid} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: '3px solid var(--cyan)' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                          <span style={{ fontSize: 11, color: 'var(--cyan)' }}>📅 #{pick(s, 'sysid') || '--'} · {pick(s, 'type') || '--'}</span>
                          <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>{fmtTime(pick(s, 'scheduledAt', 'time', 'date'))}</span>
                        </div>
                        {pick(s, 'description', 'desc') != null && (
                          <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>{pick(s, 'description', 'desc')}</div>
                        )}
                      </div>
                    )
                  })}
                  {/* 维护记录 */}
                  {records.map((r, i) => {
                    const rid = pick(r, 'id', 'recordId') ?? i
                    const st = pick(r, 'status', 'state') || 'COMPLETED'
                    const stColor = st === 'COMPLETED' ? 'var(--ok)' : st === 'PENDING' ? 'var(--warn)' : 'var(--dim)'
                    return (
                      <div key={'r_' + rid} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${stColor}` }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                          <span style={{ fontSize: 11, color: 'var(--text)' }}>🔧 #{pick(r, 'sysid') || '--'} · {pick(r, 'type') || '--'}</span>
                          <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${stColor}`, color: stColor }}>{st}</span>
                        </div>
                        <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                          {pick(r, 'description', 'desc') != null && <span>{pick(r, 'description', 'desc')}</span>}
                          <span>{fmtTime(pick(r, 'createdAt', 'timestamp', 'completedAt'))}</span>
                        </div>
                      </div>
                    )
                  })}
                </>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

// ===== 内联样式（与 TrackingPanel / GeofencePanel 保持一致）=====
const cardStyle = {
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 4,
  padding: '6px 10px',
}

const labelStyle = {
  fontSize: 10,
  color: 'var(--dim-2)',
  marginBottom: 2,
}

const miniBtnStyle = {
  fontSize: 10,
  padding: '2px 8px',
  cursor: 'pointer',
  border: '1px solid var(--line-2)',
  background: 'transparent',
  color: 'var(--dim)',
  borderRadius: 3,
}

const inputStyle = {
  width: '100%',
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-1)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
  boxSizing: 'border-box',
}