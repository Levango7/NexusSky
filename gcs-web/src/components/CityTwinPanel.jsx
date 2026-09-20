import React, { useState, useEffect, useCallback } from 'react'
import {
  createCityModel,
  listCityModels,
  getCityModel,
  getCitySituation,
  createCitySimulation,
  getCitySimulation,

  createCityMarker,
  listCityMarkers,
} from '../api.js'

// P3 数字孪生城市面板
// 城市模型管理 + 实时态势 + 灾害模拟 + 态势标绘
// 风格与 TrackingPanel / GeofencePanel 一致

const POLL_MS = 5000

// 灾害类型
const DISASTER_TYPES = [
  { key: 'FLOOD', label: '洪水' },
  { key: 'FIRE', label: '火灾' },
  { key: 'EARTHQUAKE', label: '地震' },
  { key: 'EVACUATION', label: '疏散' },
]

// 灾害类型 → 额外必填参数
const DISASTER_EXTRA_PARAMS = {
  FLOOD: [{ key: 'depthM', label: '水深（米）', placeholder: '如：2.5' }],
  FIRE: [{ key: 'windSpeed', label: '风速（m/s）', placeholder: '如：5' }],
  EARTHQUAKE: [{ key: 'magnitude', label: '震级', placeholder: '如：6.5' }],
  EVACUATION: [],
}

// 需要 durationMin 的灾害类型
const TYPES_REQUIRING_DURATION = ['FLOOD', 'FIRE', 'EARTHQUAKE']

// 模拟状态 → 颜色 / 标签
const SIM_STATUS_META = {
  CREATED: { color: 'var(--dim)', label: '已创建' },
  RUNNING: { color: 'var(--warn)', label: '运行中' },
  COMPLETED: { color: 'var(--ok)', label: '已完成' },
  FAILED: { color: 'var(--crit)', label: '失败' },
  ABORTED: { color: 'var(--dim)', label: '已中止' },
}

// 标绘类型 → 颜色 / 标签
const MARKER_TYPE_META = {
  POINT: { color: 'var(--cyan)', label: '标记点' },
  ROUTE: { color: 'var(--ok)', label: '路线' },
  AREA: { color: 'var(--warn)', label: '区域' },
}

// 格式化时间戳
function fmtTime(ts) {
  if (ts == null || ts === '') return '--'
  const n = Number(ts)
  if (!Number.isFinite(n)) return String(ts)
  return new Date(n).toLocaleString('zh-CN', { hour12: false })
}

// 字段兼容提取
function pick(obj, ...keys) {
  if (!obj) return null
  for (const k of keys) {
    if (obj[k] != null) return obj[k]
  }
  return null
}

export default function CityTwinPanel() {
  // ---- 城市模型列表 ----
  const [models, setModels] = useState([])
  const [modelsError, setModelsError] = useState(null)

  // ---- 实时态势 ----
  const [situation, setSituation] = useState(null)
  const [situationError, setSituationError] = useState(null)

  // ---- 灾害模拟 ----
  const [simulations, setSimulations] = useState([])
  const [selectedSimId, setSelectedSimId] = useState(null)
  const [simDetail, setSimDetail] = useState(null)
  const [simLoading, setSimLoading] = useState(false)
  const [simError, setSimError] = useState(null)

  // ---- 创建模拟表单 ----
  const [simForm, setSimForm] = useState({
    type: 'FLOOD',
    centerLat: '',
    centerLon: '',
    radiusKm: '',
    durationMin: '',
    depthM: '',
    windSpeed: '',
    magnitude: '',
  })
  const [simFormError, setSimFormError] = useState(null)
  const [creatingSim, setCreatingSim] = useState(false)

  // ---- 态势标绘 ----
  const [markers, setMarkers] = useState([])
  const [markerForm, setMarkerForm] = useState({
    type: 'POINT',
    lat: '',
    lon: '',
    label: '',
  })
  const [markerError, setMarkerError] = useState(null)
  const [creatingMarker, setCreatingMarker] = useState(false)

  // ---- 轮询城市模型 + 实时态势 + 标绘 ----
  useEffect(() => {
    const controller = new AbortController()
    let timer = null
    let stopped = false

    const load = async () => {
      try {
        const [modelsData, situationData, markersData] = await Promise.all([
          listCityModels().catch(() => []),
          getCitySituation().catch(() => null),
          listCityMarkers().catch(() => []),
        ])
        if (controller.signal.aborted || stopped) return
        const modelsList = Array.isArray(modelsData) ? modelsData : (modelsData && modelsData.models) || []
        setModels(modelsList)
        setModelsError(null)
        setSituation(situationData)
        setSituationError(null)
        const markersList = Array.isArray(markersData) ? markersData : (markersData && markersData.markers) || []
        setMarkers(markersList)
      } catch (e) {
        if (controller.signal.aborted || stopped) return
        setModelsError(e && e.message ? e.message : String(e))
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

  // ---- 选中模拟 → 加载详情 ----
  useEffect(() => {
    if (selectedSimId == null) {
      setSimDetail(null)
      return
    }
    let cancelled = false
    const load = async () => {
      setSimLoading(true)
      setSimError(null)
      try {
        const detail = await getCitySimulation(selectedSimId)
        if (cancelled) return
        setSimDetail(detail)
      } catch (e) {
        if (cancelled) return
        setSimError(e && e.message ? e.message : String(e))
      } finally {
        if (!cancelled) setSimLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [selectedSimId])

  // ---- 创建灾害模拟 ----
  const handleCreateSim = useCallback(async () => {
    setSimFormError(null)
    const centerLat = Number(simForm.centerLat)
    const centerLon = Number(simForm.centerLon)
    const radiusKm = Number(simForm.radiusKm)
    if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) {
      setSimFormError('中心坐标必须为有效数字')
      return
    }
    if (!Number.isFinite(radiusKm) || radiusKm <= 0) {
      setSimFormError('影响半径必须为正数')
      return
    }

    // durationMin 对 flood/fire/earthquake 必填
    const needsDuration = TYPES_REQUIRING_DURATION.includes(simForm.type)
    if (needsDuration) {
      const durationMin = Number(simForm.durationMin)
      if (!simForm.durationMin || !Number.isFinite(durationMin) || durationMin <= 0) {
        setSimFormError('时长（分钟）为必填项，必须为正数')
        return
      }
    }

    // 灾害类型特定参数验证
    const extraParams = DISASTER_EXTRA_PARAMS[simForm.type] || []
    for (const param of extraParams) {
      const val = Number(simForm[param.key])
      if (!simForm[param.key] || !Number.isFinite(val)) {
        setSimFormError(`${param.label}为必填项`)
        return
      }
      if (param.key === 'depthM' && val <= 0) {
        setSimFormError('水深必须大于0')
        return
      }
      if (param.key === 'windSpeed' && val < 0) {
        setSimFormError('风速不能为负数')
        return
      }
      if (param.key === 'magnitude' && val <= 0) {
        setSimFormError('震级必须大于0')
        return
      }
    }

    const queryParams = {
      centerLat,
      centerLon,
      radiusKm,
    }
    if (needsDuration) {
      queryParams.durationMin = Number(simForm.durationMin)
    }
    // 传递灾害类型特定参数
    for (const param of extraParams) {
      queryParams[param.key] = Number(simForm[param.key])
    }
    setCreatingSim(true)
    try {
      const data = await createCitySimulation(simForm.type.toLowerCase(), queryParams)
      const newId = pick(data, 'id', 'simulationId')
      if (newId != null) setSelectedSimId(newId)
      setSimForm((prev) => ({ ...prev, centerLat: '', centerLon: '', radiusKm: '', durationMin: '', depthM: '', windSpeed: '', magnitude: '' }))
    } catch (e) {
      setSimFormError('创建模拟失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setCreatingSim(false)
    }
  }, [simForm])


  // ---- 创建态势标绘 ----
  const handleCreateMarker = useCallback(async () => {
    setMarkerError(null)
    const lat = Number(markerForm.lat)
    const lon = Number(markerForm.lon)
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
      setMarkerError('坐标必须为有效数字')
      return
    }
    const payload = {
      type: markerForm.type,
      lat,
      lon,
      label: markerForm.label.trim() || undefined,
    }
    setCreatingMarker(true)
    try {
      await createCityMarker(payload)
      setMarkerForm((prev) => ({ ...prev, lat: '', lon: '', label: '' }))
    } catch (e) {
      setMarkerError('创建标绘失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setCreatingMarker(false)
    }
  }, [markerForm])

  const updateSimForm = useCallback((key, value) => {
    setSimForm((prev) => ({ ...prev, [key]: value }))
  }, [])

  const updateMarkerForm = useCallback((key, value) => {
    setMarkerForm((prev) => ({ ...prev, [key]: value }))
  }, [])

  // 派生：态势实体计数
  const entityCounts = situation ? {
    drones: Array.isArray(pick(situation, 'drones')) ? pick(situation, 'drones').length : 0,
    vehicles: Array.isArray(pick(situation, 'vehicles')) ? pick(situation, 'vehicles').length : 0,
    persons: Array.isArray(pick(situation, 'persons')) ? pick(situation, 'persons').length : 0,
    alerts: Array.isArray(pick(situation, 'alerts')) ? pick(situation, 'alerts').length : 0,
  } : { drones: 0, vehicles: 0, persons: 0, alerts: 0 }

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>数字孪生城市</h2>

      {modelsError && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {modelsError}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左列：城市模型 + 实时态势 + 标绘 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 城市模型列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>城市模型（{models.length}）</span>
            </div>
            <div style={{ maxHeight: 200, overflowY: 'auto' }}>
              {models.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无城市模型</div>
              ) : (
                models.map((m, i) => {
                  const mid = pick(m, 'id', 'modelId')
                  return (
                    <div key={mid != null ? mid : i} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: '3px solid var(--cyan)' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 9, color: 'var(--cyan)', fontWeight: 'bold' }}>#{mid != null ? mid : '?'}</span>
                        <span style={{ fontSize: 11, color: 'var(--text)' }}>{pick(m, 'name') || '--'}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        {pick(m, 'description') || '--'} · {fmtTime(pick(m, 'createdAt', 'created'))}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 实时态势 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>实时态势</div>
            {situationError ? (
              <div style={{ fontSize: 10, color: 'var(--crit)' }}>⚠ {situationError}</div>
            ) : situation ? (
              <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>无人机：<b style={{ color: 'var(--cyan)' }}>{entityCounts.drones}</b></div>
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>车辆：<b style={{ color: 'var(--ok)' }}>{entityCounts.vehicles}</b></div>
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>人员：<b style={{ color: 'var(--text)' }}>{entityCounts.persons}</b></div>
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>告警：<b style={{ color: 'var(--crit)' }}>{entityCounts.alerts}</b></div>
              </div>
            ) : (
              <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无态势数据</div>
            )}
          </div>

          {/* 态势标绘 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>态势标绘（{markers.length}）</span>
            </div>
            <div style={{ maxHeight: 160, overflowY: 'auto' }}>
              {markers.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无标绘</div>
              ) : (
                markers.map((m, i) => {
                  const markerType = pick(m, 'type')
                  const meta = MARKER_TYPE_META[markerType] || { color: 'var(--dim)', label: markerType || '--' }
                  return (
                    <div key={i} style={{ padding: '4px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ fontSize: 9, display: 'flex', gap: 6, alignItems: 'center' }}>
                        <span style={{ color: meta.color, fontWeight: 'bold' }}>[{meta.label}]</span>
                        <span style={{ color: 'var(--text)' }}>{pick(m, 'label') || '--'}</span>
                        <span style={{ color: 'var(--dim-2)', fontFamily: 'var(--mono)' }}>
                          {pick(m, 'lat') != null ? Number(pick(m, 'lat')).toFixed(4) : '--'}, {pick(m, 'lon') != null ? Number(pick(m, 'lon')).toFixed(4) : '--'}
                        </span>
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 创建标绘 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>创建标绘</div>
            {markerError && <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 4 }}>⚠ {markerError}</div>}
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <div style={{ flex: '1 1 100px' }}>
                <select value={markerForm.type} onChange={(e) => updateMarkerForm('type', e.target.value)} style={modalInputStyle}>
                  {Object.entries(MARKER_TYPE_META).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
                </select>
              </div>
              <div style={{ flex: '1 1 100px' }}>
                <input type="number" step="any" placeholder="纬度" value={markerForm.lat} onChange={(e) => updateMarkerForm('lat', e.target.value)} style={modalInputStyle} />
              </div>
              <div style={{ flex: '1 1 100px' }}>
                <input type="number" step="any" placeholder="经度" value={markerForm.lon} onChange={(e) => updateMarkerForm('lon', e.target.value)} style={modalInputStyle} />
              </div>
              <div style={{ flex: '1 1 120px' }}>
                <input type="text" placeholder="标签" value={markerForm.label} onChange={(e) => updateMarkerForm('label', e.target.value)} style={modalInputStyle} />
              </div>
              <button onClick={handleCreateMarker} disabled={creatingMarker} style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: creatingMarker ? 0.5 : 1, cursor: creatingMarker ? 'not-allowed' : 'pointer' }}>{creatingMarker ? '创建中…' : '创建'}</button>
            </div>
          </div>
        </div>

        {/* 右列：灾害模拟 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 创建灾害模拟 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>创建灾害模拟</div>
            {simFormError && <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 4 }}>⚠ {simFormError}</div>}
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <div style={{ flex: '1 1 120px' }}>
                <div style={labelStyle}>灾害类型</div>
                <select value={simForm.type} onChange={(e) => updateSimForm('type', e.target.value)} style={modalInputStyle}>
                  {DISASTER_TYPES.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
                </select>
              </div>
              <div style={{ flex: '1 1 100px' }}>
                <div style={labelStyle}>中心纬度</div>
                <input type="number" step="any" value={simForm.centerLat} onChange={(e) => updateSimForm('centerLat', e.target.value)} style={modalInputStyle} placeholder="纬度" />
              </div>
              <div style={{ flex: '1 1 100px' }}>
                <div style={labelStyle}>中心经度</div>
                <input type="number" step="any" value={simForm.centerLon} onChange={(e) => updateSimForm('centerLon', e.target.value)} style={modalInputStyle} placeholder="经度" />
              </div>
              <div style={{ flex: '1 1 80px' }}>
                <div style={labelStyle}>半径 (km)</div>
                <input type="number" step="any" value={simForm.radiusKm} onChange={(e) => updateSimForm('radiusKm', e.target.value)} style={modalInputStyle} placeholder="km" />
              </div>
              <div style={{ flex: '1 1 80px' }}>
                <div style={labelStyle}>{TYPES_REQUIRING_DURATION.includes(simForm.type) ? '时长 (min) *' : '时长 (min)'}</div>
                <input type="number" value={simForm.durationMin} onChange={(e) => updateSimForm('durationMin', e.target.value)} style={modalInputStyle} placeholder={TYPES_REQUIRING_DURATION.includes(simForm.type) ? '必填' : '可选'} />
              </div>
              {/* 灾害类型特定额外参数 */}
              {(DISASTER_EXTRA_PARAMS[simForm.type] || []).map((param) => (
                <div key={param.key} style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>{param.label} *</div>
                  <input type="number" step="any" value={simForm[param.key]} onChange={(e) => updateSimForm(param.key, e.target.value)} style={modalInputStyle} placeholder={param.placeholder} />
                </div>
              ))}
            </div>
            <button onClick={handleCreateSim} disabled={creatingSim} style={{ ...miniBtnStyle, marginTop: 6, padding: '4px 12px', border: '1px solid var(--warn)', color: 'var(--warn)', cursor: creatingSim ? 'not-allowed' : 'pointer', opacity: creatingSim ? 0.5 : 1 }}>{creatingSim ? '创建中…' : '创建模拟'}</button>
          </div>

          {/* 模拟详情 */}
          {selectedSimId != null && (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span>模拟详情 #{selectedSimId}</span>
              </div>
              {simError && <div style={{ fontSize: 10, color: 'var(--crit)', marginBottom: 4 }}>⚠ {simError}</div>}
              {simLoading ? (
                <div style={{ fontSize: 10, color: 'var(--cyan)' }}>加载中…</div>
              ) : simDetail ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3 }}>
                  <div>类型：<span style={{ color: 'var(--text)' }}>{DISASTER_TYPES.find((t) => t.key === pick(simDetail, 'type'))?.label || pick(simDetail, 'type') || '--'}</span></div>
                  <div>状态：<span style={{ color: SIM_STATUS_META[pick(simDetail, 'status')]?.color || 'var(--text)' }}>{SIM_STATUS_META[pick(simDetail, 'status')]?.label || pick(simDetail, 'status') || '--'}</span></div>
                  <div>影响半径：{pick(simDetail, 'radiusKm') != null ? `${Number(pick(simDetail, 'radiusKm')).toFixed(1)} km` : '--'}</div>
                  <div>当前帧：{pick(simDetail, 'currentFrame', 'frame') != null ? pick(simDetail, 'currentFrame', 'frame') : '--'}</div>
                </div>
              ) : (
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无模拟详情</div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// ===== 内联样式 =====
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

const modalInputStyle = {
  width: '100%',
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
  boxSizing: 'border-box',
}