import React, { useState, useEffect, useCallback, useRef } from 'react'
import {
  createInspectionTask,
  listInspectionTasks,
  getInspectionTask,
  startInspectionTask,
  abortInspectionTask,
  getInspectionProgress,
  getInspectionReport,
  getInspectionAnomalies,
  getInspectionPhotos,
} from '../api.js'
import { toArray } from '../utils/panelUtils.js'

// 智能巡检面板（P1）
// 任务创建 + 任务列表 + 任务详情 + 巡检报告 + 异常详情 + 行业预设模板
// 风格与 TrackingPanel / GeofencePanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 5s；AbortController 竞态守卫
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（AbortController 竞态守卫）

const POLL_MS = 5000

// 行业预设模板
const INDUSTRY_TEMPLATES = [
  { key: 'POWER_GRID', label: '电力巡检', icon: '⚡', desc: '输电线路/铁塔/绝缘子巡检' },
  { key: 'PIPELINE', label: '管道巡检', icon: '🔗', desc: '油气管道泄漏/腐蚀检测' },
  { key: 'RAILWAY', label: '铁路巡检', icon: '🚆', desc: '铁轨/路基/接触网巡检' },
  { key: 'SOLAR', label: '光伏巡检', icon: '☀', desc: '光伏面板热斑/裂纹检测' },
]

// 任务状态 → 颜色 / 标签
const TASK_STATUS = {
  PENDING: { color: 'var(--warn)', label: '待执行' },
  RUNNING: { color: 'var(--cyan)', label: '巡检中' },
  PAUSED: { color: 'var(--warn)', label: '已暂停' },
  COMPLETED: { color: 'var(--ok)', label: '已完成' },
  ABORTED: { color: 'var(--crit)', label: '已中止' },
  FAILED: { color: 'var(--crit)', label: '失败' },
}

function taskStatusMeta(s) {
  return TASK_STATUS[s] || { color: 'var(--dim)', label: s || '--' }
}

// 异常严重程度 → 颜色
const SEVERITY_COLOR = {
  CRITICAL: 'var(--crit)',
  HIGH: 'var(--crit)',
  MEDIUM: 'var(--warn)',
  LOW: 'var(--dim)',
  INFO: 'var(--cyan)',
}

function severityColor(s) {
  return SEVERITY_COLOR[s] || 'var(--dim)'
}

// ---- 字段兼容提取 ----
function pick(obj, ...keys) {
  if (!obj) return null
  for (const k of keys) {
    if (obj[k] != null) return obj[k]
  }
  return null
}


// 格式化时间戳
function fmtTime(ts) {
  if (ts == null || ts === '') return '--'
  const t = typeof ts === 'number' ? ts : Date.parse(ts)
  if (isNaN(t)) return String(ts)
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

export default function InspectionPanel() {
  // ---- 任务列表 ----
  const [tasks, setTasks] = useState([])
  const [filterStatus, setFilterStatus] = useState('')  // 状态筛选
  const [selectedTask, setSelectedTask] = useState(null)
  const [taskDetail, setTaskDetail] = useState(null)
  const [taskProgress, setTaskProgress] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const detailAbortRef = useRef(null)

  // ---- 报告 / 异常 / 照片 ----
  const [report, setReport] = useState(null)
  const [anomalies, setAnomalies] = useState([])
  const [photos, setPhotos] = useState([])
  const [reportLoading, setReportLoading] = useState(false)

  const [error, setError] = useState(null)
  const [formError, setFormError] = useState(null)

  // ---- 创建任务表单 ----
  const [createForm, setCreateForm] = useState({
    template: 'POWER_GRID',
    sysid: '',
    areaLat: '',
    areaLon: '',
    areaRadius: '500',
    name: '',
  })
  const [creating, setCreating] = useState(false)

  // ---- 操作中状态 ----
  const [startingId, setStartingId] = useState(null)
  const [abortingId, setAbortingId] = useState(null)

  // ---- 轮询任务列表 ----
  useEffect(() => {
    const controller = new AbortController()
    let cancelled = false

    const load = async () => {
      if (controller.signal.aborted) return
      try {
        const data = await listInspectionTasks()
        if (cancelled || controller.signal.aborted) return
        setTasks(toArray(data, 'tasks'))
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

  // ---- 选中任务 → 加载详情 + 进度 + 报告 + 异常 + 照片 ----
  useEffect(() => {
    if (selectedTask == null) {
      setTaskDetail(null)
      setTaskProgress(null)
      setReport(null)
      setAnomalies([])
      setPhotos([])
      return
    }

    const controller = new AbortController()
    if (detailAbortRef.current) detailAbortRef.current.abort()
    detailAbortRef.current = controller

    let cancelled = false
    const load = async () => {
      setDetailLoading(true)
      try {
        const [detail, progress, rpt, anoms, phs] = await Promise.all([
          getInspectionTask(selectedTask).catch(() => null),
          getInspectionProgress(selectedTask).catch(() => null),
          getInspectionReport(selectedTask).catch(() => null),
          getInspectionAnomalies(selectedTask).catch(() => []),
          getInspectionPhotos(selectedTask).catch(() => []),
        ])
        if (cancelled || controller.signal.aborted) return
        setTaskDetail(detail)
        setTaskProgress(progress)
        setReport(rpt)
        setAnomalies(toArray(anoms, 'anomalies'))
        setPhotos(toArray(phs, 'photos'))
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
  }, [selectedTask])

  // ---- 创建巡检任务 ----
  const handleCreate = useCallback(async (e) => {
    if (e && e.preventDefault) e.preventDefault()
    setFormError(null)
    const sysid = Number(createForm.sysid)
    if (!Number.isFinite(sysid) || sysid <= 0) {
      setFormError('请输入有效的无人机 sysid')
      return
    }
    const areaLat = Number(createForm.areaLat)
    const areaLon = Number(createForm.areaLon)
    if (!createForm.areaLat.trim() || !Number.isFinite(areaLat)) {
      setFormError('请输入有效的区域纬度')
      return
    }
    if (!createForm.areaLon.trim() || !Number.isFinite(areaLon)) {
      setFormError('请输入有效的区域经度')
      return
    }
    const areaRadius = Math.max(10, Number(createForm.areaRadius) || 500)
    const payload = {
      template: createForm.template,
      sysid,
      area: { lat: areaLat, lon: areaLon, radiusM: areaRadius },
      name: createForm.name.trim() || undefined,
    }
    setCreating(true)
    try {
      const result = await createInspectionTask(payload)
      setCreateForm((prev) => ({ ...prev, sysid: '', areaLat: '', areaLon: '', name: '' }))
      // 选中新建任务
      const newId = pick(result, 'id', 'taskId')
      if (newId != null) setSelectedTask(newId)
    } catch (e) {
      setFormError('创建巡检任务失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setCreating(false)
    }
  }, [createForm])

  // ---- 启动任务 ----
  const handleStart = useCallback(async (id) => {
    setStartingId(id)
    setError(null)
    try {
      await startInspectionTask(id)
    } catch (e) {
      setError('启动任务失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setStartingId(null)
    }
  }, [])

  // ---- 中止任务 ----
  const handleAbort = useCallback(async (id) => {
    if (!window.confirm(`确认中止巡检任务 #${id}？`)) return
    setAbortingId(id)
    setError(null)
    try {
      await abortInspectionTask(id)
    } catch (e) {
      setError('中止任务失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setAbortingId(null)
    }
  }, [])

  // ---- 派生：按状态筛选 ----
  const filteredTasks = filterStatus
    ? tasks.filter((t) => pick(t, 'status', 'state') === filterStatus)
    : tasks

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        智能巡检
        <span style={{ fontSize: 10, color: 'var(--dim)', marginLeft: 8 }}>
          任务 {tasks.length}
        </span>
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* ===== 左列：行业模板 + 创建任务 + 任务列表 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {/* 行业预设模板 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4 }}>
              行业预设模板
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {INDUSTRY_TEMPLATES.map((t) => (
                <div
                  key={t.key}
                  onClick={() => setCreateForm((prev) => ({ ...prev, template: t.key }))}
                  style={{
                    flex: '1 1 140px', padding: '6px 8px', borderRadius: 3, cursor: 'pointer',
                    border: `1px solid ${createForm.template === t.key ? 'var(--cyan)' : 'var(--line-2)'}`,
                    background: createForm.template === t.key ? 'var(--bg-3, var(--bg-1))' : 'var(--bg-1)',
                  }}
                >
                  <div style={{ fontSize: 11, color: createForm.template === t.key ? 'var(--cyan)' : 'var(--text)', display: 'flex', gap: 4, alignItems: 'center' }}>
                    <span>{t.icon}</span>
                    <span>{t.label}</span>
                  </div>
                  <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>{t.desc}</div>
                </div>
              ))}
            </div>
          </div>

          {/* 创建巡检任务 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4 }}>
              创建巡检任务
            </div>
            {formError && (
              <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 6, padding: '3px 6px', background: 'var(--bg-1)', borderRadius: 3, border: '1px solid var(--crit)' }}>
                ⚠ {formError}
              </div>
            )}
            <form onSubmit={handleCreate} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>任务名称（可选）</div>
                  <input
                    type="text"
                    value={createForm.name}
                    onChange={(e) => setCreateForm((p) => ({ ...p, name: e.target.value }))}
                    style={inputStyle}
                    placeholder="例：电力线巡检-A区"
                  />
                </div>
                <div style={{ flex: '0 0 90px' }}>
                  <div style={labelStyle}>sysid</div>
                  <input
                    type="number"
                    min="1"
                    value={createForm.sysid}
                    onChange={(e) => setCreateForm((p) => ({ ...p, sysid: e.target.value }))}
                    style={inputStyle}
                    placeholder="1"
                  />
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>区域纬度</div>
                  <input
                    type="number"
                    step="any"
                    value={createForm.areaLat}
                    onChange={(e) => setCreateForm((p) => ({ ...p, areaLat: e.target.value }))}
                    style={inputStyle}
                    placeholder="30.12345"
                  />
                </div>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>区域经度</div>
                  <input
                    type="number"
                    step="any"
                    value={createForm.areaLon}
                    onChange={(e) => setCreateForm((p) => ({ ...p, areaLon: e.target.value }))}
                    style={inputStyle}
                    placeholder="120.12345"
                  />
                </div>
                <div style={{ flex: '0 0 90px' }}>
                  <div style={labelStyle}>半径(m)</div>
                  <input
                    type="number"
                    min="10"
                    value={createForm.areaRadius}
                    onChange={(e) => setCreateForm((p) => ({ ...p, areaRadius: e.target.value }))}
                    style={inputStyle}
                  />
                </div>
              </div>
              <button
                type="submit"
                disabled={creating}
                style={{
                  ...miniBtnStyle, padding: '4px 12px', alignSelf: 'flex-start',
                  color: 'var(--cyan)', borderColor: 'var(--cyan)',
                  cursor: creating ? 'not-allowed' : 'pointer', opacity: creating ? 0.5 : 1,
                }}
              >
                {creating ? '创建中…' : '创建任务'}
              </button>
            </form>
          </div>

          {/* 任务列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 11, color: 'var(--dim)' }}>巡检任务（{filteredTasks.length}）</span>
              <select
                value={filterStatus}
                onChange={(e) => setFilterStatus(e.target.value)}
                style={{ ...inputStyle, marginLeft: 'auto', width: 110, fontSize: 10, padding: '2px 4px' }}
              >
                <option value="">全部状态</option>
                {Object.entries(TASK_STATUS).map(([k, v]) => (
                  <option key={k} value={k}>{v.label}</option>
                ))}
              </select>
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {filteredTasks.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无巡检任务</div>
              ) : (
                filteredTasks.map((t, i) => {
                  const tid = pick(t, 'id', 'taskId') ?? i
                  const st = pick(t, 'status', 'state') || 'PENDING'
                  const meta = taskStatusMeta(st)
                  const isSel = tid === selectedTask
                  const isStarting = startingId === tid
                  const isAborting = abortingId === tid
                  return (
                    <div
                      key={tid}
                      onClick={() => setSelectedTask((prev) => (prev === tid ? null : tid))}
                      style={{
                        padding: '6px 10px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-3, var(--bg-1))' : 'transparent',
                        borderLeft: `3px solid ${meta.color}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 11, fontWeight: 'bold', color: meta.color, flexShrink: 0 }}>#{tid}</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {pick(t, 'name', 'taskName') || '--'}
                          </span>
                        </div>
                        <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${meta.color}`, color: meta.color, flexShrink: 0 }}>
                          {meta.label}
                        </span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
                        <span>无人机：#{pick(t, 'sysid') || '--'}</span>
                        <span>模板：{INDUSTRY_TEMPLATES.find((it) => it.key === pick(t, 'template', 'industry'))?.label || pick(t, 'template', 'industry') || '--'}</span>
                        {st === 'PENDING' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); handleStart(tid) }}
                            disabled={isStarting}
                            style={{ ...miniBtnStyle, fontSize: 9, padding: '0 5px', color: 'var(--ok)', borderColor: 'var(--ok)', cursor: isStarting ? 'not-allowed' : 'pointer', opacity: isStarting ? 0.5 : 1 }}
                          >
                            {isStarting ? '启动中…' : '启动'}
                          </button>
                        )}
                        {(st === 'RUNNING' || st === 'PAUSED') && (
                          <button
                            onClick={(e) => { e.stopPropagation(); handleAbort(tid) }}
                            disabled={isAborting}
                            style={{ ...miniBtnStyle, fontSize: 9, padding: '0 5px', color: 'var(--crit)', borderColor: 'var(--crit)', cursor: isAborting ? 'not-allowed' : 'pointer', opacity: isAborting ? 0.5 : 1 }}
                          >
                            {isAborting ? '中止中…' : '中止'}
                          </button>
                        )}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>
        </div>

        {/* ===== 右列：任务详情 + 进度 + 报告 + 异常 + 照片 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {selectedTask != null ? (
            <>
              {/* 任务详情 + 进度 */}
              <div style={{ ...cardStyle, padding: 10 }}>
                <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span>任务详情 #{selectedTask}</span>
                  {detailLoading && <span style={{ fontSize: 9, color: 'var(--cyan)' }}>加载中…</span>}
                </div>
                {taskDetail ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                    {[
                      { label: '名称', value: pick(taskDetail, 'name', 'taskName') },
                      { label: '状态', value: taskStatusMeta(pick(taskDetail, 'status', 'state')).label },
                      { label: '无人机', value: `#${pick(taskDetail, 'sysid') || '--'}` },
                      { label: '模板', value: INDUSTRY_TEMPLATES.find((it) => it.key === pick(taskDetail, 'template', 'industry'))?.label || pick(taskDetail, 'template', 'industry') },
                      { label: '创建时间', value: fmtTime(pick(taskDetail, 'createdAt', 'timestamp')) },
                    ].filter((e) => e.value != null).map((e) => (
                      <div key={e.label} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 10, padding: '2px 0', borderBottom: '1px solid var(--line-2)' }}>
                        <span style={{ color: 'var(--dim-2)' }}>{e.label}</span>
                        <span style={{ color: 'var(--text)' }}>{e.value}</span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无详情</div>
                )}

                {/* 进度 */}
                {taskProgress && (
                  <div style={{ marginTop: 8, paddingTop: 8, borderTop: '1px solid var(--line-2)' }}>
                    <div style={{ fontSize: 10, color: 'var(--dim)', marginBottom: 4 }}>巡检进度</div>
                    <div style={{ display: 'flex', gap: 12, fontSize: 10, color: 'var(--dim-2)', flexWrap: 'wrap' }}>
                      <span>航点：<b style={{ color: 'var(--cyan)' }}>{pick(taskProgress, 'waypointIndex', 'currentWaypoint') ?? '--'}/{pick(taskProgress, 'totalWaypoints', 'waypointCount') ?? '--'}</b></span>
                      <span>照片：<b style={{ color: 'var(--ok)' }}>{pick(taskProgress, 'photoCount', 'photos') ?? '--'}</b></span>
                      <span>异常：<b style={{ color: 'var(--crit)' }}>{pick(taskProgress, 'anomalyCount', 'anomalies') ?? '--'}</b></span>
                      {pick(taskProgress, 'progressPct', 'progress') != null && (
                        <span>进度：<b style={{ color: 'var(--cyan)' }}>{Number(pick(taskProgress, 'progressPct', 'progress')).toFixed(1)}%</b></span>
                      )}
                    </div>
                  </div>
                )}
              </div>

              {/* 巡检报告 */}
              {report && (
                <div style={{ ...cardStyle, padding: 10 }}>
                  <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4 }}>
                    巡检报告
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                    {[
                      { label: '覆盖率', value: pick(report, 'coveragePct', 'coverage') != null ? `${Number(pick(report, 'coveragePct', 'coverage')).toFixed(1)}%` : null },
                      { label: '总照片数', value: pick(report, 'totalPhotos', 'photoCount') },
                      { label: '异常数', value: pick(report, 'totalAnomalies', 'anomalyCount') },
                      { label: '巡检时长', value: pick(report, 'durationSec', 'duration') != null ? `${Number(pick(report, 'durationSec', 'duration')).toFixed(0)}s` : null },
                      { label: '生成时间', value: fmtTime(pick(report, 'generatedAt', 'createdAt', 'timestamp')) },
                    ].filter((e) => e.value != null).map((e) => (
                      <div key={e.label} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 10, padding: '2px 0', borderBottom: '1px solid var(--line-2)' }}>
                        <span style={{ color: 'var(--dim-2)' }}>{e.label}</span>
                        <span style={{ color: 'var(--text)' }}>{e.value}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 异常清单 */}
              <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
                <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
                  异常清单（{anomalies.length}）
                </div>
                <div style={{ maxHeight: 240, overflowY: 'auto' }}>
                  {anomalies.length === 0 ? (
                    <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无异常</div>
                  ) : (
                    anomalies.map((a, i) => {
                      const aid = pick(a, 'id', 'anomalyId') ?? i
                      const sev = pick(a, 'severity', 'level') || 'LOW'
                      const sc = severityColor(sev)
                      return (
                        <div key={aid} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${sc}` }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                            <span style={{ fontSize: 11, color: 'var(--text)' }}>{pick(a, 'type', 'anomalyType') || '--'}</span>
                            <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${sc}`, color: sc }}>{sev}</span>
                          </div>
                          <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                            {pick(a, 'lat') != null && pick(a, 'lon') != null && (
                              <span>位置：{Number(pick(a, 'lat')).toFixed(4)}, {Number(pick(a, 'lon')).toFixed(4)}</span>
                            )}
                            {pick(a, 'confidence') != null && (
                              <span>置信度：{(Number(pick(a, 'confidence')) * (Number(pick(a, 'confidence')) <= 1 ? 100 : 1)).toFixed(0)}%</span>
                            )}
                            {pick(a, 'description', 'desc') != null && (
                              <span>{pick(a, 'description', 'desc')}</span>
                            )}
                          </div>
                        </div>
                      )
                    })
                  )}
                </div>
              </div>

              {/* GPS标注照片 */}
              {photos.length > 0 && (
                <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
                  <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
                    GPS标注照片（{photos.length}）
                  </div>
                  <div style={{ maxHeight: 160, overflowY: 'auto' }}>
                    {photos.map((p, i) => {
                      const pid = pick(p, 'id', 'photoId') ?? i
                      return (
                        <div key={pid} style={{ padding: '4px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 9, color: 'var(--dim-2)', display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                          <span style={{ color: 'var(--cyan)' }}>#{pid}</span>
                          {pick(p, 'lat') != null && pick(p, 'lon') != null && (
                            <span>({Number(pick(p, 'lat')).toFixed(4)}, {Number(pick(p, 'lon')).toFixed(4)})</span>
                          )}
                          {pick(p, 'url', 'path', 'thumbnail') != null && (
                            <span style={{ color: 'var(--dim)', fontFamily: 'var(--mono)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                              {pick(p, 'url', 'path', 'thumbnail')}
                            </span>
                          )}
                        </div>
                      )
                    })}
                  </div>
                </div>
              )}
            </>
          ) : (
            <div style={{ ...cardStyle, padding: 20, textAlign: 'center' }}>
              <div style={{ fontSize: 11, color: 'var(--dim-2)' }}>← 选择左侧任务查看详情</div>
            </div>
          )}
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