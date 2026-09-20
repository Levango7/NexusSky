import React, { useState, useEffect, useCallback } from 'react'
import {
  createDeliveryTask,
  listDeliveryTasks,
  getDeliveryTask,
  startDeliveryTask,
  abortDeliveryTask,
  optimizeDeliveryRoute,
  deliverDeliveryTask,
  getDeliveryStatus,
  confirmDeliveryTask,
  searchLandingSites,
} from '../api.js'

// P4 物流配送面板
// 配送任务创建 + 路线优化 + 状态追踪 + 降落点搜索
// 风格与 TrackingPanel / GeofencePanel 一致

const POLL_MS = 5000

// 配送类型
const DELIVERY_TYPES = [
  { key: 'STANDARD', label: '标准配送' },
  { key: 'EXPRESS', label: '急件配送' },
  { key: 'MEDICAL', label: '医疗配送' },
  { key: 'EMERGENCY', label: '应急配送' },
]

// 配送状态 → 颜色 / 标签
const DELIVERY_STATUS_META = {
  CREATED: { color: 'var(--dim)', label: '已创建' },
  ROUTE_OPTIMIZED: { color: 'var(--cyan)', label: '路线已优化' },
  STARTED: { color: 'var(--warn)', label: '配送中' },
  APPROACHING: { color: 'var(--warn)', label: '接近目标' },
  DELIVERING: { color: 'var(--warn)', label: '投放中' },
  DELIVERED: { color: 'var(--ok)', label: '已投放' },
  CONFIRMED: { color: 'var(--ok)', label: '已签收' },
  ABORTED: { color: 'var(--crit)', label: '已中止' },
  FAILED: { color: 'var(--crit)', label: '失败' },
}

// 地面类型
const GROUND_TYPES = ['CONCRETE', 'GRASS', 'SOIL', 'WATER', 'ROOF']

// 投放方式
const DELIVERY_METHODS = [
  { key: 'AIR_DROP', label: '空投' },
  { key: 'LAND_DELIVER', label: '降落交付' },
  { key: 'ROPE_LOWER', label: '绳索降下' },
]

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

export default function DeliveryPanel() {
  // ---- 任务列表 ----
  const [tasks, setTasks] = useState([])
  const [tasksError, setTasksError] = useState(null)

  // ---- 选中任务 ----
  const [selectedTaskId, setSelectedTaskId] = useState(null)
  const [taskDetail, setTaskDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState(null)

  // ---- 配送状态 ----
  const [deliveryStatus, setDeliveryStatus] = useState(null)

  // ---- 路线优化 ----
  const [optimizing, setOptimizing] = useState(false)

  // ---- 操作按钮状态 ----
  const [starting, setStarting] = useState(false)
  const [aborting, setAborting] = useState(false)
  const [delivering, setDelivering] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [deliverMethod, setDeliverMethod] = useState('AIR_DROP')

  // ---- 创建表单 ----
  const [form, setForm] = useState({
    type: 'STANDARD',
    payloadKg: '',
    priority: 'MEDIUM',
    pickupLat: '',
    pickupLon: '',
    dropoffLat: '',
    dropoffLon: '',
  })
  const [formError, setFormError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  // ---- 降落点搜索 ----
  const [landingSearch, setLandingSearch] = useState({
    lat: '',
    lon: '',
    radius: '',
    groundType: '',
  })
  const [landingSites, setLandingSites] = useState([])
  const [landingLoading, setLandingLoading] = useState(false)
  const [landingError, setLandingError] = useState(null)

  // ---- 轮询任务列表 ----
  useEffect(() => {
    const controller = new AbortController()
    let timer = null
    let stopped = false

    const load = async () => {
      try {
        const data = await listDeliveryTasks()
        if (controller.signal.aborted || stopped) return
        const list = Array.isArray(data) ? data : (data && data.tasks) || []
        setTasks(list)
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

  // ---- 选中任务 → 加载详情 + 状态 ----
  useEffect(() => {
    if (selectedTaskId == null) {
      setTaskDetail(null)
      setDeliveryStatus(null)
      return
    }
    let cancelled = false
    const load = async () => {
      setDetailLoading(true)
      setDetailError(null)
      try {
        const [detail, status] = await Promise.all([
          getDeliveryTask(selectedTaskId),
          getDeliveryStatus(selectedTaskId).catch(() => null),
        ])
        if (cancelled) return
        setTaskDetail(detail)
        setDeliveryStatus(status)
      } catch (e) {
        if (cancelled) return
        setDetailError(e && e.message ? e.message : String(e))
      } finally {
        if (!cancelled) setDetailLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [selectedTaskId])

  // ---- 创建配送任务 ----
  const handleCreate = useCallback(async (e) => {
    if (e && e.preventDefault) e.preventDefault()
    setFormError(null)

    const pickupLat = Number(form.pickupLat)
    const pickupLon = Number(form.pickupLon)
    const dropoffLat = Number(form.dropoffLat)
    const dropoffLon = Number(form.dropoffLon)
    const payloadKg = Number(form.payloadKg)

    if (!Number.isFinite(pickupLat) || !Number.isFinite(pickupLon) || !Number.isFinite(dropoffLat) || !Number.isFinite(dropoffLon)) {
      setFormError('起降坐标必须为有效数字')
      return
    }
    if (!Number.isFinite(payloadKg) || payloadKg <= 0) {
      setFormError('负载重量必须为正数')
      return
    }

    const payload = {
      type: form.type,
      payloadKg,
      priority: form.priority,
      pickup: { lat: pickupLat, lon: pickupLon },
      dropoff: { lat: dropoffLat, lon: dropoffLon },
    }

    setSubmitting(true)
    try {
      await createDeliveryTask(payload)
      setForm((prev) => ({ ...prev, payloadKg: '', pickupLat: '', pickupLon: '', dropoffLat: '', dropoffLon: '' }))
    } catch (err) {
      setFormError('创建任务失败：' + (err && err.message ? err.message : String(err)))
    } finally {
      setSubmitting(false)
    }
  }, [form])

  // ---- 路线优化 ----
  const handleOptimize = useCallback(async () => {
    if (selectedTaskId == null) return
    setOptimizing(true)
    try {
      await optimizeDeliveryRoute(selectedTaskId)
    } catch (e) {
      setDetailError('路线优化失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setOptimizing(false)
    }
  }, [selectedTaskId])

  // ---- 启动配送 ----
  const handleStart = useCallback(async () => {
    if (selectedTaskId == null) return
    setStarting(true)
    try {
      await startDeliveryTask(selectedTaskId)
    } catch (e) {
      setDetailError('启动失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setStarting(false)
    }
  }, [selectedTaskId])

  // ---- 中止配送 ----
  const handleAbort = useCallback(async () => {
    if (selectedTaskId == null) return
    setAborting(true)
    try {
      await abortDeliveryTask(selectedTaskId)
    } catch (e) {
      setDetailError('中止失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setAborting(false)
    }
  }, [selectedTaskId])

  // ---- 执行投放 ----
  const handleDeliver = useCallback(async () => {
    if (selectedTaskId == null) return
    setDelivering(true)
    try {
      await deliverDeliveryTask(selectedTaskId, { method: deliverMethod })
    } catch (e) {
      setDetailError('投放失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setDelivering(false)
    }
  }, [selectedTaskId, deliverMethod])

  // ---- 确认签收 ----
  const handleConfirm = useCallback(async () => {
    if (selectedTaskId == null) return
    setConfirming(true)
    try {
      await confirmDeliveryTask(selectedTaskId)
    } catch (e) {
      setDetailError('签收失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setConfirming(false)
    }
  }, [selectedTaskId])

  // ---- 搜索降落点 ----
  const handleSearchLanding = useCallback(async () => {
    setLandingError(null)
    const params = {}
    if (landingSearch.lat) params.lat = landingSearch.lat
    if (landingSearch.lon) params.lon = landingSearch.lon
    if (landingSearch.radius) params.radius = landingSearch.radius
    if (landingSearch.groundType) params.groundType = landingSearch.groundType
    setLandingLoading(true)
    try {
      const data = await searchLandingSites(params)
      const list = Array.isArray(data) ? data : (data && data.sites) || []
      setLandingSites(list)
    } catch (e) {
      setLandingError(e && e.message ? e.message : String(e))
      setLandingSites([])
    } finally {
      setLandingLoading(false)
    }
  }, [landingSearch])

  const updateForm = useCallback((key, value) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }, [])

  const updateLandingSearch = useCallback((key, value) => {
    setLandingSearch((prev) => ({ ...prev, [key]: value }))
  }, [])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>物流配送</h2>

      {tasksError && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {tasksError}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左列：任务列表 + 创建表单 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 配送任务列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>配送任务（{tasks.length}）</span>
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {tasks.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无配送任务</div>
              ) : (
                tasks.map((t, i) => {
                  const tid = pick(t, 'id', 'taskId')
                  const status = pick(t, 'status')
                  const statusMeta = DELIVERY_STATUS_META[status] || { color: 'var(--dim)', label: status || '--' }
                  const typeLabel = DELIVERY_TYPES.find((dt) => dt.key === pick(t, 'type'))?.label || pick(t, 'type') || '--'
                  const isSel = tid === selectedTaskId
                  return (
                    <div
                      key={tid != null ? tid : i}
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
                        {pick(t, 'payloadKg') != null ? `${Number(pick(t, 'payloadKg')).toFixed(1)} kg` : '--'} · {fmtTime(pick(t, 'createdAt', 'created'))}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 创建配送任务 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>创建配送任务</div>
            {formError && <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 4 }}>⚠ {formError}</div>}
            <form onSubmit={handleCreate} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 120px' }}>
                  <div style={labelStyle}>配送类型</div>
                  <select value={form.type} onChange={(e) => updateForm('type', e.target.value)} style={modalInputStyle}>
                    {DELIVERY_TYPES.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
                  </select>
                </div>
                <div style={{ flex: '1 1 80px' }}>
                  <div style={labelStyle}>负载 (kg)</div>
                  <input type="number" step="any" value={form.payloadKg} onChange={(e) => updateForm('payloadKg', e.target.value)} style={modalInputStyle} placeholder="kg" />
                </div>
                <div style={{ flex: '1 1 120px' }}>
                  <div style={labelStyle}>优先级</div>
                  <select value={form.priority} onChange={(e) => updateForm('priority', e.target.value)} style={modalInputStyle}>
                    <option value="LOW">低</option>
                    <option value="MEDIUM">中</option>
                    <option value="HIGH">高</option>
                    <option value="CRITICAL">紧急</option>
                  </select>
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>起飞纬度</div>
                  <input type="number" step="any" value={form.pickupLat} onChange={(e) => updateForm('pickupLat', e.target.value)} style={modalInputStyle} placeholder="纬度" />
                </div>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>起飞经度</div>
                  <input type="number" step="any" value={form.pickupLon} onChange={(e) => updateForm('pickupLon', e.target.value)} style={modalInputStyle} placeholder="经度" />
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>降落纬度</div>
                  <input type="number" step="any" value={form.dropoffLat} onChange={(e) => updateForm('dropoffLat', e.target.value)} style={modalInputStyle} placeholder="纬度" />
                </div>
                <div style={{ flex: '1 1 100px' }}>
                  <div style={labelStyle}>降落经度</div>
                  <input type="number" step="any" value={form.dropoffLon} onChange={(e) => updateForm('dropoffLon', e.target.value)} style={modalInputStyle} placeholder="经度" />
                </div>
              </div>
              <button type="submit" disabled={submitting} style={{ ...miniBtnStyle, padding: '4px 12px', alignSelf: 'flex-start', border: '1px solid var(--cyan)', color: 'var(--cyan)', cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.5 : 1 }}>{submitting ? '创建中…' : '创建任务'}</button>
            </form>
          </div>
        </div>

        {/* 右列：任务详情 + 状态 + 降落点搜索 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 任务详情 + 操作按钮 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>任务详情 {selectedTaskId != null ? `#${selectedTaskId}` : ''}</span>
              {selectedTaskId != null && (
                <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                  <button onClick={handleOptimize} disabled={optimizing} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: optimizing ? 0.5 : 1, cursor: optimizing ? 'not-allowed' : 'pointer' }}>{optimizing ? '优化中…' : '优化路线'}</button>
                  <button onClick={handleStart} disabled={starting} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--ok)', borderColor: 'var(--ok)', opacity: starting ? 0.5 : 1, cursor: starting ? 'not-allowed' : 'pointer' }}>{starting ? '启动中…' : '启动'}</button>
                  <button onClick={handleDeliver} disabled={delivering} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--warn)', borderColor: 'var(--warn)', opacity: delivering ? 0.5 : 1, cursor: delivering ? 'not-allowed' : 'pointer' }}>{delivering ? '投放中…' : '投放'}</button>
                  <select value={deliverMethod} onChange={(e) => setDeliverMethod(e.target.value)} style={{ ...miniBtnStyle, fontSize: 9, padding: '1px 4px' }}>
                    {DELIVERY_METHODS.map((m) => <option key={m.key} value={m.key}>{m.label}</option>)}
                  </select>
                  <button onClick={handleConfirm} disabled={confirming} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--ok)', borderColor: 'var(--ok)', opacity: confirming ? 0.5 : 1, cursor: confirming ? 'not-allowed' : 'pointer' }}>{confirming ? '签收中…' : '签收'}</button>
                  <button onClick={handleAbort} disabled={aborting} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--crit)', borderColor: 'var(--crit)', opacity: aborting ? 0.5 : 1, cursor: aborting ? 'not-allowed' : 'pointer' }}>{aborting ? '中止中…' : '中止'}</button>
                </div>
              )}
            </div>
            {detailError && <div style={{ fontSize: 10, color: 'var(--crit)', marginBottom: 4 }}>⚠ {detailError}</div>}
            {detailLoading ? (
              <div style={{ fontSize: 10, color: 'var(--cyan)' }}>加载中…</div>
            ) : taskDetail ? (
              <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3 }}>
                <div>类型：<span style={{ color: 'var(--text)' }}>{DELIVERY_TYPES.find((dt) => dt.key === pick(taskDetail, 'type'))?.label || pick(taskDetail, 'type') || '--'}</span></div>
                <div>状态：<span style={{ color: DELIVERY_STATUS_META[pick(taskDetail, 'status')]?.color || 'var(--text)' }}>{DELIVERY_STATUS_META[pick(taskDetail, 'status')]?.label || pick(taskDetail, 'status') || '--'}</span></div>
                <div>负载：{pick(taskDetail, 'payloadKg') != null ? `${Number(pick(taskDetail, 'payloadKg')).toFixed(1)} kg` : '--'}</div>
                <div>起飞点：{pick(taskDetail, 'pickupLat') != null ? `${Number(pick(taskDetail, 'pickupLat')).toFixed(4)}, ${Number(pick(taskDetail, 'pickupLon')).toFixed(4)}` : '--'}</div>
                <div>降落点：{pick(taskDetail, 'dropoffLat') != null ? `${Number(pick(taskDetail, 'dropoffLat')).toFixed(4)}, ${Number(pick(taskDetail, 'dropoffLon')).toFixed(4)}` : '--'}</div>
              </div>
            ) : (
              <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>选择任务查看详情</div>
            )}
          </div>

          {/* 配送状态追踪 */}
          {deliveryStatus && (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>配送状态追踪</div>
              <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3 }}>
                <div>当前状态：<span style={{ color: DELIVERY_STATUS_META[pick(deliveryStatus, 'status')]?.color || 'var(--text)' }}>{DELIVERY_STATUS_META[pick(deliveryStatus, 'status')]?.label || pick(deliveryStatus, 'status') || '--'}</span></div>
                <div>剩余距离：{pick(deliveryStatus, 'remainingDistanceM', 'remainingDistance') != null ? `${Number(pick(deliveryStatus, 'remainingDistanceM', 'remainingDistance')).toFixed(0)} m` : '--'}</div>
                <div>预估到达：{pick(deliveryStatus, 'estimatedArrivalMin', 'etaMin') != null ? `${Number(pick(deliveryStatus, 'estimatedArrivalMin', 'etaMin')).toFixed(0)} 分钟` : '--'}</div>
                <div>航点序列：{Array.isArray(pick(deliveryStatus, 'waypoints', 'route')) ? pick(deliveryStatus, 'waypoints', 'route').length + ' 个航点' : '--'}</div>
              </div>
            </div>
          )}

          {/* 降落点搜索 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>降落点搜索</div>
            {landingError && <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 4 }}>⚠ {landingError}</div>}
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 6 }}>
              <div style={{ flex: '1 1 80px' }}>
                <input type="number" step="any" placeholder="纬度" value={landingSearch.lat} onChange={(e) => updateLandingSearch('lat', e.target.value)} style={modalInputStyle} />
              </div>
              <div style={{ flex: '1 1 80px' }}>
                <input type="number" step="any" placeholder="经度" value={landingSearch.lon} onChange={(e) => updateLandingSearch('lon', e.target.value)} style={modalInputStyle} />
              </div>
              <div style={{ flex: '1 1 80px' }}>
                <input type="number" placeholder="半径(m)" value={landingSearch.radius} onChange={(e) => updateLandingSearch('radius', e.target.value)} style={modalInputStyle} />
              </div>
              <div style={{ flex: '1 1 100px' }}>
                <select value={landingSearch.groundType} onChange={(e) => updateLandingSearch('groundType', e.target.value)} style={modalInputStyle}>
                  <option value="">不限</option>
                  {GROUND_TYPES.map((g) => <option key={g} value={g}>{g}</option>)}
                </select>
              </div>
              <button onClick={handleSearchLanding} disabled={landingLoading} style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: landingLoading ? 0.5 : 1, cursor: landingLoading ? 'not-allowed' : 'pointer' }}>{landingLoading ? '搜索中…' : '搜索'}</button>
            </div>
            {landingSites.length > 0 && (
              <div style={{ maxHeight: 160, overflowY: 'auto' }}>
                {landingSites.map((s, i) => (
                  <div key={i} style={{ fontSize: 9, color: 'var(--dim-2)', padding: '4px 0', borderBottom: '1px solid var(--line-2)' }}>
                    <span style={{ color: 'var(--cyan)' }}>#{pick(s, 'id', 'siteId') != null ? pick(s, 'id', 'siteId') : i + 1}</span>
                    {' '}{pick(s, 'groundType') || '--'}
                    {' · '}{pick(s, 'lat') != null ? Number(pick(s, 'lat')).toFixed(4) : '--'}, {pick(s, 'lon') != null ? Number(pick(s, 'lon')).toFixed(4) : '--'}
                    {' · '}评分：<b style={{ color: 'var(--ok)' }}>{pick(s, 'score', 'rating') != null ? Number(pick(s, 'score', 'rating')).toFixed(1) : '--'}</b>
                  </div>
                ))}
              </div>
            )}
          </div>
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