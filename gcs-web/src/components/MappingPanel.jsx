import React, { useState, useEffect, useCallback, useRef } from 'react'
import {
  createMappingTask,
  listMappingTasks,
  getMappingTask,
  planMappingRoute,
  getMappingPhotos,
  generateMappingResult,
  getMappingResult,
} from '../api.js'
import { POLL_MS, fmtTime, pick, METERS_PER_DEGREE_LAT, cardStyle, labelStyle, miniBtnStyle, modalInputStyle } from '../utils/panelUtils.js'

// P2 航拍测绘面板
// 测绘任务创建 + 航线规划 + 采集照片 + 测绘成果
// 风格与 TrackingPanel / GeofencePanel 一致

// 测绘类型
const MAPPING_TYPES = [
  { key: 'ORTHO_PHOTO', label: '正射影像' },
  { key: 'DEM', label: 'DEM 高程模型' },
  { key: 'THREE_D_MODEL', label: '3D 模型' },
  { key: 'MIXED', label: '混合测绘' },
]

// 任务状态 → 颜色 / 标签（对齐后端 MappingTask.Status 枚举）
const TASK_STATUS_META = {
  PENDING: { color: 'var(--dim)', label: '待处理' },
  PLANNING: { color: 'var(--cyan)', label: '规划中' },
  IN_PROGRESS: { color: 'var(--warn)', label: '执行中' },
  COMPLETED: { color: 'var(--ok)', label: '已完成' },
  FAILED: { color: 'var(--crit)', label: '失败' },
}


export default function MappingPanel() {
  // ---- 任务列表 ----
  const [tasks, setTasks] = useState([])
  const [tasksError, setTasksError] = useState(null)
  const successTimerRef = useRef(null)

  // 组件卸载时清理 successMsg timer，防止 setState 作用于已卸载组件
  useEffect(() => {
    return () => {
      if (successTimerRef.current) clearTimeout(successTimerRef.current)
    }
  }, [])


  // ---- 选中任务 ----
  const [selectedTaskId, setSelectedTaskId] = useState(null)
  const [taskDetail, setTaskDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState(null)

  // ---- 航线规划 ----
  const [route, setRoute] = useState(null)
  const [planningRoute, setPlanningRoute] = useState(false)

  // ---- 采集照片 ----
  const [photos, setPhotos] = useState([])

  const [photosLoading, setPhotosLoading] = useState(false)

  // ---- 测绘成果 ----
  const [result, setResult] = useState(null)
  const [generating, setGenerating] = useState(false)
  const [resultLoading, setResultLoading] = useState(false)

  // ---- 操作成功提示 ----
  const [successMsg, setSuccessMsg] = useState(null)

  // ---- 创建表单 ----
  const [form, setForm] = useState({
    type: 'ORTHO_PHOTO',
    name: '',
    centerLat: '',
    centerLon: '',
    widthM: '',
    heightM: '',
    altitudeM: '',
    overlapPct: '',
  })
  const [formError, setFormError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  // ---- 轮询任务列表 + 测绘区域 ----
  useEffect(() => {
    const controller = new AbortController()
    let timer = null
    let stopped = false

    const load = async () => {
      try {
        const tasksData = await listMappingTasks().catch(() => [])
        if (controller.signal.aborted || stopped) return
        const tasksList = Array.isArray(tasksData) ? tasksData : (tasksData && tasksData.tasks) || []
        setTasks(tasksList)
        setTasksError(null)
      } catch (e) {
        if (controller.signal.aborted || stopped) return
        setTasksError(e && e.message ? e.message : String(e))
      }
    }

    load()
    timer = setInterval(load, POLL_MS)

    return () => {
      stopped = true
      controller.abort()
      clearInterval(timer)
    }
  }, [])

  // ---- 选中任务 → 加载详情 ----
  useEffect(() => {
    if (selectedTaskId == null) {
      setTaskDetail(null)
      setRoute(null)
      setPhotos([])
      setResult(null)
      return
    }

    let cancelled = false
    const load = async () => {
      setDetailLoading(true)
      setDetailError(null)
      try {
        const data = await getMappingTask(selectedTaskId)
        if (cancelled) return
        setTaskDetail(data)
      } catch (e) {
        if (cancelled) return
        setDetailError(e && e.message ? e.message : String(e))
        setTaskDetail(null)
      } finally {
        if (!cancelled) setDetailLoading(false)
      }
    }
    load()

    return () => { cancelled = true }
  }, [selectedTaskId])

  // ---- 创建测绘任务 ----
  const handleCreate = useCallback(async (e) => {
    if (e && e.preventDefault) e.preventDefault()
    setFormError(null)

    const centerLat = Number(form.centerLat)
    const centerLon = Number(form.centerLon)
    const widthM = Number(form.widthM)
    const heightM = Number(form.heightM)
    const altitudeM = Number(form.altitudeM)
    const overlapPct = Number(form.overlapPct)

    if (!form.name || !form.name.trim()) {
      setFormError('任务名称必填')
      return
    }
    if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) {
      setFormError('中心坐标必须为有效数字')
      return
    }
    if (!Number.isFinite(widthM) || widthM <= 0 || !Number.isFinite(heightM) || heightM <= 0) {
      setFormError('区域宽高必须为正数')
      return
    }

    // 将矩形区域（中心点 + 宽高）转换为 polygon 顶点列表
    // 纬度 1 度 ≈ METERS_PER_DEGREE_LAT m，经度 1 度 ≈ METERS_PER_DEGREE_LAT * cos(lat) m
    const dLat = heightM / 2 / METERS_PER_DEGREE_LAT
    const dLon = widthM / 2 / (METERS_PER_DEGREE_LAT * Math.cos(centerLat * Math.PI / 180))
    const points = [
      [centerLat - dLat, centerLon - dLon],
      [centerLat - dLat, centerLon + dLon],
      [centerLat + dLat, centerLon + dLon],
      [centerLat + dLat, centerLon - dLon],
    ]

    const payload = {
      name: form.name.trim(),
      type: form.type,
      area: {
        type: 'polygon',
        points,
      },
      altitudeM: Number.isFinite(altitudeM) ? altitudeM : undefined,
      overlapPct: Number.isFinite(overlapPct) ? overlapPct : undefined,
    }

    setSubmitting(true)
    try {
      await createMappingTask(payload)
      setForm((prev) => ({ ...prev, name: '', centerLat: '', centerLon: '', widthM: '', heightM: '', altitudeM: '', overlapPct: '' }))
    } catch (err) {
      setFormError('创建任务失败：' + (err && err.message ? err.message : String(err)))
    } finally {
      setSubmitting(false)
    }
  }, [form])

  // ---- 规划航线 ----
  const handlePlanRoute = useCallback(async () => {
    if (selectedTaskId == null) return
    setPlanningRoute(true)
    try {
      const data = await planMappingRoute(selectedTaskId)
      // 后端返回 List<MappingWaypoint>（数组），规范化为统一对象格式
      if (Array.isArray(data)) {
        setRoute({ waypoints: data, waypointCount: data.length })
      } else {
        setRoute(data)
      }
      setSuccessMsg('航线规划成功')
      if (successTimerRef.current) clearTimeout(successTimerRef.current)
      successTimerRef.current = setTimeout(() => setSuccessMsg(null), 3000)
    } catch (e) {
      setDetailError('航线规划失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setPlanningRoute(false)
    }
  }, [selectedTaskId])


  // ---- 获取照片列表 ----
  const handleGetPhotos = useCallback(async () => {
    if (selectedTaskId == null) return
    setPhotosLoading(true)
    try {
      const data = await getMappingPhotos(selectedTaskId)
      const list = Array.isArray(data) ? data : (data && data.photos) || []
      setPhotos(list)
    } catch (e) {
      setDetailError('获取照片失败：' + (e && e.message ? e.message : String(e)))
      setPhotos([])
    } finally {
      setPhotosLoading(false)
    }
  }, [selectedTaskId])

  // ---- 生成测绘成果 ----
  const handleGenerateResult = useCallback(async () => {
    if (selectedTaskId == null) return
    setGenerating(true)
    try {
      await generateMappingResult(selectedTaskId)
      setSuccessMsg('测绘成果生成已启动')
      if (successTimerRef.current) clearTimeout(successTimerRef.current)
      successTimerRef.current = setTimeout(() => setSuccessMsg(null), 3000)
    } catch (e) {
      setDetailError('生成成果失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setGenerating(false)
    }
  }, [selectedTaskId])

  // ---- 获取测绘成果 ----
  const handleGetResult = useCallback(async () => {
    if (selectedTaskId == null) return
    setResultLoading(true)
    try {
      const data = await getMappingResult(selectedTaskId)
      // 后端返回 List<MappingResult>（数组），合并多个成果的 URL 到统一对象
      if (Array.isArray(data)) {
        const merged = {}
        for (const item of data) {
          if (item.orthophotoUrl) merged.orthophotoUrl = item.orthophotoUrl
          if (item.demUrl) merged.demUrl = item.demUrl
          if (item.modelUrl) merged.modelUrl = item.modelUrl
          if (item.model3dUrl) merged.model3dUrl = item.model3dUrl
        }
        setResult(Object.keys(merged).length > 0 ? merged : (data.length > 0 ? data[0] : null))
      } else {
        setResult(data)
      }
    } catch (e) {
      setDetailError('获取成果失败：' + (e && e.message ? e.message : String(e)))
      setResult(null)
    } finally {
      setResultLoading(false)
    }
  }, [selectedTaskId])

  const updateForm = useCallback((key, value) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }, [])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>航拍测绘</h2>

      {tasksError && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {tasksError}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左列：任务列表 + 创建表单 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 测绘任务列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>测绘任务（{tasks.length}）</span>
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {tasks.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无测绘任务</div>
              ) : (
                tasks.map((t, i) => {
                  const tid = pick(t, 'id', 'taskId')
                  const typeLabel = MAPPING_TYPES.find((mt) => mt.key === pick(t, 'type'))?.label || pick(t, 'type') || '--'
                  const status = pick(t, 'status')
                  const statusMeta = TASK_STATUS_META[status] || { color: 'var(--dim)', label: status || '--' }
                  const isSel = tid === selectedTaskId
                  return (
                    <div
                      key={tid != null ? tid : `task-${i}`}
                      onClick={() => setSelectedTaskId(tid)}
                      style={{
                        padding: '6px 10px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-3, var(--bg-1))' : 'transparent',
                        borderLeft: `3px solid ${isSel ? 'var(--cyan)' : statusMeta.color}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: 'var(--cyan)', fontWeight: 'bold', flexShrink: 0 }}>#{tid != null ? tid : '?'}</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{typeLabel}</span>
                        </div>
                        <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 3, border: `1px solid ${statusMeta.color}`, color: statusMeta.color }}>{statusMeta.label}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        {pick(t, 'areaName', 'area', 'name') || '--'} · {fmtTime(pick(t, 'createdAt', 'created'))}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 创建测绘任务表单 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>创建测绘任务</div>
            {formError && (
              <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 6, padding: '3px 6px', background: 'var(--bg-1)', borderRadius: 3, border: '1px solid var(--crit)' }}>⚠ {formError}</div>
            )}
            <form onSubmit={handleCreate} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 140px' }}>
                  <div style={labelStyle}>测绘类型</div>
                  <select aria-label="测绘类型" value={form.type} onChange={(e) => updateForm('type', e.target.value)} style={modalInputStyle}>
                    {MAPPING_TYPES.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
                  </select>
                </div>
                <div style={{ flex: '1 1 160px' }}>
                  <div style={labelStyle}>任务名称</div>
                  <input aria-label="任务名称" type="text" value={form.name} onChange={(e) => updateForm('name', e.target.value)} style={modalInputStyle} placeholder="必填" />
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>中心纬度</div>
                  <input aria-label="中心纬度" type="number" step="any" value={form.centerLat} onChange={(e) => updateForm('centerLat', e.target.value)} style={modalInputStyle} placeholder="纬度" />
                </div>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>中心经度</div>
                  <input aria-label="中心经度" type="number" step="any" value={form.centerLon} onChange={(e) => updateForm('centerLon', e.target.value)} style={modalInputStyle} placeholder="经度" />
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>区域宽度 (m)</div>
                  <input aria-label="区域宽度（米）" type="number" step="any" value={form.widthM} onChange={(e) => updateForm('widthM', e.target.value)} style={modalInputStyle} placeholder="米" />
                </div>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>区域高度 (m)</div>
                  <input aria-label="区域高度（米）" type="number" step="any" value={form.heightM} onChange={(e) => updateForm('heightM', e.target.value)} style={modalInputStyle} placeholder="米" />
                </div>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>飞行高度 (m)</div>
                  <input aria-label="飞行高度（米）" type="number" step="any" value={form.altitudeM} onChange={(e) => updateForm('altitudeM', e.target.value)} style={modalInputStyle} placeholder="米" />
                </div>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>重叠率 (%)</div>
                  <input aria-label="重叠率（百分比）" type="number" step="any" value={form.overlapPct} onChange={(e) => updateForm('overlapPct', e.target.value)} style={modalInputStyle} placeholder="%" />
                </div>
              </div>
              <button
                type="submit"
                disabled={submitting}
                style={{ ...miniBtnStyle, padding: '4px 12px', alignSelf: 'flex-start', border: '1px solid var(--cyan)', color: 'var(--cyan)', cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.5 : 1 }}
              >
                {submitting ? '创建中…' : '创建任务'}
              </button>
            </form>
          </div>
        </div>

        {/* 右列：任务详情 + 航线 + 照片 + 成果 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 任务详情 + 操作按钮 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>任务详情 {selectedTaskId != null ? `#${selectedTaskId}` : ''}</span>
              {selectedTaskId != null && (
                <div style={{ display: 'flex', gap: 4 }}>
                  <button onClick={handlePlanRoute} disabled={planningRoute} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: planningRoute ? 0.5 : 1, cursor: planningRoute ? 'not-allowed' : 'pointer' }}>{planningRoute ? '规划中…' : '规划航线'}</button>

                  <button onClick={handleGenerateResult} disabled={generating} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--ok)', borderColor: 'var(--ok)', opacity: generating ? 0.5 : 1, cursor: generating ? 'not-allowed' : 'pointer' }}>{generating ? '生成中…' : '生成成果'}</button>
                </div>
              )}
            </div>
            {detailError && <div style={{ fontSize: 10, color: 'var(--crit)', marginBottom: 4 }}>⚠ {detailError}</div>}
            {successMsg && <div style={{ fontSize: 10, color: 'var(--ok)', marginBottom: 4 }}>✓ {successMsg}</div>}
            {detailLoading ? (
              <div style={{ fontSize: 10, color: 'var(--cyan)' }}>加载中…</div>
            ) : taskDetail ? (
              <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3 }}>
                <div>类型：<span style={{ color: 'var(--text)' }}>{MAPPING_TYPES.find((mt) => mt.key === pick(taskDetail, 'type'))?.label || pick(taskDetail, 'type') || '--'}</span></div>
                <div>状态：<span style={{ color: TASK_STATUS_META[pick(taskDetail, 'status')]?.color || 'var(--text)' }}>{TASK_STATUS_META[pick(taskDetail, 'status')]?.label || pick(taskDetail, 'status') || '--'}</span></div>
                <div>区域：{pick(taskDetail, 'areaName', 'area', 'name') || '--'}</div>
                <div>航点数：{pick(taskDetail, 'waypointCount', 'routePoints') != null ? pick(taskDetail, 'waypointCount', 'routePoints') : '--'}</div>
              </div>
            ) : (
              <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>选择任务查看详情</div>
            )}
          </div>

          {/* 航线规划 */}
          {route && (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>航线规划</div>
              <div style={{ fontSize: 10, color: 'var(--dim-2)', marginBottom: 4 }}>
                航点数：<b style={{ color: 'var(--cyan)' }}>{pick(route, 'waypointCount', 'points') != null ? pick(route, 'waypointCount', 'points') : (Array.isArray(pick(route, 'waypoints', 'points')) ? pick(route, 'waypoints', 'points').length : '--')}</b>
                {' · '}总距离：<b style={{ color: 'var(--text)' }}>{pick(route, 'totalDistanceM', 'distance') != null ? `${Number(pick(route, 'totalDistanceM', 'distance')).toFixed(0)} m` : '--'}</b>
              </div>
              <div style={{ maxHeight: 160, overflowY: 'auto' }}>
                {(Array.isArray(pick(route, 'waypoints', 'points')) ? pick(route, 'waypoints', 'points') : []).map((wp, i) => (
                  <div key={`wp-${i}`} style={{ fontSize: 9, color: 'var(--dim-2)', padding: '2px 0', borderBottom: '1px solid var(--line-2)', fontFamily: 'var(--mono)' }}>
                    WP{i + 1}: {Number(pick(wp, 'lat')).toFixed(6)}, {Number(pick(wp, 'lon')).toFixed(6)} · {pick(wp, 'alt', 'altitude') != null ? `${Number(pick(wp, 'alt', 'altitude')).toFixed(0)}m` : '--'}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 采集照片 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>采集照片（{photos.length}）</span>
              {selectedTaskId != null && (
                <button onClick={handleGetPhotos} disabled={photosLoading} style={{ ...miniBtnStyle, marginLeft: 'auto', fontSize: 9, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: photosLoading ? 0.5 : 1, cursor: photosLoading ? 'not-allowed' : 'pointer' }}>{photosLoading ? '加载中…' : '刷新照片'}</button>
              )}
            </div>
            <div style={{ maxHeight: 200, overflowY: 'auto' }}>
              {photos.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无照片</div>
              ) : (
                photos.map((p, i) => (
                  <div key={`photo-${i}`} style={{ fontSize: 9, color: 'var(--dim-2)', padding: '4px 10px', borderBottom: '1px solid var(--line-2)', fontFamily: 'var(--mono)' }}>
                    <span style={{ color: 'var(--cyan)' }}>#{pick(p, 'id', 'index') != null ? pick(p, 'id', 'index') : i + 1}</span>
                    {' '}{pick(p, 'filename', 'name') || '--'}
                    {' · '}{pick(p, 'lat') != null ? Number(pick(p, 'lat')).toFixed(6) : '--'}, {pick(p, 'lon') != null ? Number(pick(p, 'lon')).toFixed(6) : '--'}
                  </div>
                ))
              )}
            </div>
          </div>

          {/* 测绘成果 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>测绘成果</span>
              {selectedTaskId != null && (
                <button onClick={handleGetResult} disabled={resultLoading} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--ok)', borderColor: 'var(--ok)', opacity: resultLoading ? 0.5 : 1, cursor: resultLoading ? 'not-allowed' : 'pointer' }}>{resultLoading ? '加载中…' : '获取成果'}</button>
              )}
            </div>
            {result ? (
              <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3 }}>
                {pick(result, 'orthomosaicUrl', 'orthomosaic') && <div>正射影像：<a href={pick(result, 'orthomosaicUrl', 'orthomosaic')} target="_blank" rel="noopener" style={{ color: 'var(--cyan)' }}>查看</a></div>}
                {pick(result, 'demUrl', 'dem') && <div>DEM：<a href={pick(result, 'demUrl', 'dem')} target="_blank" rel="noopener" style={{ color: 'var(--cyan)' }}>查看</a></div>}
                {pick(result, 'modelUrl', 'model3dUrl', 'model') && <div>3D 模型：<a href={pick(result, 'modelUrl', 'model3dUrl', 'model')} target="_blank" rel="noopener" style={{ color: 'var(--cyan)' }}>查看</a></div>}
              </div>
            ) : (
              <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无测绘成果</div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

