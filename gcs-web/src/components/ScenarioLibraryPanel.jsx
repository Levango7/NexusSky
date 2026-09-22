import React, { useState, useEffect, useCallback, useRef } from 'react'
import {
  getScenarioTemplates,
  getScenarioTemplate,
  getScenarioTemplatesByType,
  launchScenario,
  getActiveScenarioLaunches,
  getScenarioLaunchHistory,
  abortScenarioLaunch,
  getScenarioLaunchStatus,
  startScenarioDrill,
  getScenarioDrillResult,
  getScenarioDrillHistory,
} from '../api.js'
import { toArray } from '../utils/panelUtils.js'

// 应急场景库面板（P1）
// 场景模板列表 + 模板详情 + 一键启动 + 进行中场景 + 场景历史 + 演练模式
// 风格与 TrackingPanel / GeofencePanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 5s；AbortController 竞态守卫
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（AbortController 竞态守卫）

const POLL_MS = 5000

// 灾害类型
const DISASTER_TYPES = [
  { key: 'FIRE', label: '火灾' },
  { key: 'FLOOD', label: '洪水' },
  { key: 'EARTHQUAKE', label: '地震' },
  { key: 'MUDSLIDE', label: '泥石流' },
  { key: 'CHEMICAL_LEAK', label: '化工厂泄漏' },
  { key: 'MASS_EVENT', label: '群体性事件' },
]

// 场景启动状态 → 颜色 / 标签
const LAUNCH_STATUS = {
  PENDING: { color: 'var(--warn)', label: '待命' },
  LAUNCHING: { color: 'var(--cyan)', label: '启动中' },
  RUNNING: { color: 'var(--cyan)', label: '执行中' },
  COMPLETED: { color: 'var(--ok)', label: '已完成' },
  ABORTED: { color: 'var(--crit)', label: '已中止' },
  FAILED: { color: 'var(--crit)', label: '失败' },
}

function launchStatusMeta(s) {
  return LAUNCH_STATUS[s] || { color: 'var(--dim)', label: s || '--' }
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

export default function ScenarioLibraryPanel() {
  // ---- 模板列表 ----
  const [templates, setTemplates] = useState([])
  const [filterType, setFilterType] = useState('')  // 灾害类型筛选
  const [selectedTemplate, setSelectedTemplate] = useState(null)
  const [templateDetail, setTemplateDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const detailAbortRef = useRef(null)

  // ---- 进行中场景 ----
  const [activeLaunches, setActiveLaunches] = useState([])
  // ---- 场景历史 ----
  const [launchHistory, setLaunchHistory] = useState([])
  // ---- 演练历史 ----
  const [drillHistory, setDrillHistory] = useState([])

  const [error, setError] = useState(null)

  // ---- 启动场景表单 ----
  const [launchLat, setLaunchLat] = useState('')
  const [launchLon, setLaunchLon] = useState('')
  const [launching, setLaunching] = useState(false)

  // ---- 演练 ----
  const [drilling, setDrilling] = useState(false)
  const [drillResult, setDrillResult] = useState(null)
  const [drillResultLoading, setDrillResultLoading] = useState(false)

  // ---- 中止操作 ----
  const [abortingId, setAbortingId] = useState(null)

  // ---- 轮询模板列表（按灾害类型筛选）+ 进行中 + 历史 + 演练历史 ----
  useEffect(() => {
    const controller = new AbortController()
    let cancelled = false

    const load = async () => {
      if (controller.signal.aborted) return
      try {
        const templatesPromise = filterType
          ? getScenarioTemplatesByType(filterType).catch(() => [])
          : getScenarioTemplates().catch(() => [])
        const [tplData, activeData, histData, drillHistData] = await Promise.all([
          templatesPromise,
          getActiveScenarioLaunches().catch(() => []),
          getScenarioLaunchHistory().catch(() => []),
          getScenarioDrillHistory().catch(() => []),
        ])
        if (cancelled || controller.signal.aborted) return
        setTemplates(toArray(tplData, 'templates'))
        setActiveLaunches(toArray(activeData, 'launches'))
        setLaunchHistory(toArray(histData, 'launches'))
        setDrillHistory(toArray(drillHistData, 'drills'))
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
  }, [filterType])

  // ---- 选中模板 → 加载详情 ----
  useEffect(() => {
    if (selectedTemplate == null) {
      setTemplateDetail(null)
      return
    }
    const controller = new AbortController()
    if (detailAbortRef.current) detailAbortRef.current.abort()
    detailAbortRef.current = controller

    let cancelled = false
    const load = async () => {
      setDetailLoading(true)
      try {
        const detail = await getScenarioTemplate(selectedTemplate)
        if (cancelled || controller.signal.aborted) return
        setTemplateDetail(detail)
      } catch (e) {
        if (cancelled || controller.signal.aborted) return
        // 详情加载失败时用列表项兜底
        setTemplateDetail(null)
      } finally {
        if (!cancelled && !controller.signal.aborted) setDetailLoading(false)
      }
    }
    load()
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [selectedTemplate])

  // ---- 一键启动场景 ----
  const handleLaunch = useCallback(async () => {
    if (selectedTemplate == null) {
      setError('请先选择一个场景模板')
      return
    }
    const lat = Number(launchLat)
    const lon = Number(launchLon)
    const payload = {}
    if (launchLat.trim() && Number.isFinite(lat)) payload.lat = lat
    if (launchLon.trim() && Number.isFinite(lon)) payload.lon = lon
    setLaunching(true)
    setError(null)
    try {
      await launchScenario(selectedTemplate, payload)
      setLaunchLat('')
      setLaunchLon('')
    } catch (e) {
      setError('启动场景失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setLaunching(false)
    }
  }, [selectedTemplate, launchLat, launchLon])

  // ---- 中止场景 ----
  const handleAbort = useCallback(async (launchId) => {
    if (!window.confirm(`确认中止场景 #${launchId}？`)) return
    setAbortingId(launchId)
    setError(null)
    try {
      await abortScenarioLaunch(launchId)
      setActiveLaunches((prev) => prev.filter((l) => pick(l, 'launchId', 'id') !== launchId))
    } catch (e) {
      setError('中止场景失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setAbortingId(null)
    }
  }, [])

  // ---- 启动演练 ----
  const handleDrill = useCallback(async () => {
    if (selectedTemplate == null) {
      setError('请先选择一个场景模板')
      return
    }
    setDrilling(true)
    setError(null)
    setDrillResult(null)
    try {
      const result = await startScenarioDrill(selectedTemplate)
      // 如果返回了 drillId，自动拉取结果
      const drillId = pick(result, 'drillId', 'id')
      if (drillId != null) {
        setDrillResultLoading(true)
        try {
          const detail = await getScenarioDrillResult(drillId)
          setDrillResult(detail || result)
        } catch (e) {
          setDrillResult(result)
        } finally {
          setDrillResultLoading(false)
        }
      } else {
        setDrillResult(result)
      }
    } catch (e) {
      setError('启动演练失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setDrilling(false)
    }
  }, [selectedTemplate])

  // ---- 查看演练结果 ----
  const handleViewDrillResult = useCallback(async (drillId) => {
    setDrillResultLoading(true)
    setError(null)
    try {
      const result = await getScenarioDrillResult(drillId)
      setDrillResult(result)
    } catch (e) {
      setError('获取演练结果失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setDrillResultLoading(false)
    }
  }, [])

  // ---- 派生：当前展示的模板详情（优先用详情接口数据，否则用列表项）----
  const currentDetail = templateDetail || templates.find((t) => pick(t, 'id', 'templateId') === selectedTemplate)

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        应急场景库
        <span style={{ fontSize: 10, color: 'var(--dim)', marginLeft: 8 }}>
          模板 {templates.length} · 进行中 {activeLaunches.length}
        </span>
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* ===== 左列：模板列表 + 筛选 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {/* 灾害类型筛选 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>按灾害类型筛选</div>
            <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
              <button
                onClick={() => setFilterType('')}
                style={{ ...miniBtnStyle, fontSize: 9, padding: '2px 6px', color: filterType === '' ? 'var(--cyan)' : 'var(--dim)', borderColor: filterType === '' ? 'var(--cyan)' : 'var(--line-2)' }}
              >
                全部
              </button>
              {DISASTER_TYPES.map((t) => (
                <button
                  key={t.key}
                  onClick={() => setFilterType(t.key)}
                  style={{ ...miniBtnStyle, fontSize: 9, padding: '2px 6px', color: filterType === t.key ? 'var(--cyan)' : 'var(--dim)', borderColor: filterType === t.key ? 'var(--cyan)' : 'var(--line-2)' }}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          {/* 模板列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
              场景模板（{templates.length}）
            </div>
            <div style={{ maxHeight: 400, overflowY: 'auto' }}>
              {templates.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无场景模板</div>
              ) : (
                templates.map((t, i) => {
                  const tid = pick(t, 'id', 'templateId') ?? i
                  const isSel = tid === selectedTemplate
                  const typeLabel = DISASTER_TYPES.find((d) => d.key === pick(t, 'disasterType', 'type'))?.label || pick(t, 'disasterType', 'type') || '--'
                  return (
                    <div
                      key={tid}
                      onClick={() => setSelectedTemplate((prev) => (prev === tid ? null : tid))}
                      style={{
                        padding: '6px 10px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-3, var(--bg-1))' : 'transparent',
                        borderLeft: `3px solid ${isSel ? 'var(--cyan)' : 'var(--line-2)'}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                          {pick(t, 'name', 'templateName') || '--'}
                        </span>
                        <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: '1px solid var(--dim)', color: 'var(--dim)', flexShrink: 0 }}>
                          {typeLabel}
                        </span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        <span>无人机：{pick(t, 'droneCount', 'drones') || '--'}</span>
                        <span>半径：{pick(t, 'radiusM', 'radius') != null ? `${Number(pick(t, 'radiusM', 'radius')).toFixed(0)}m` : '--'}</span>
                        <span>高度：{pick(t, 'altitudeM', 'altitude', 'alt') != null ? `${Number(pick(t, 'altitudeM', 'altitude', 'alt')).toFixed(0)}m` : '--'}</span>
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>
        </div>

        {/* ===== 右列：模板详情 + 启动/演练 + 进行中 + 历史 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {/* 模板详情 + 启动/演练 */}
          {selectedTemplate != null && (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span>模板详情</span>
                {detailLoading && <span style={{ fontSize: 9, color: 'var(--cyan)' }}>加载中…</span>}
              </div>
              {currentDetail ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  {[
                    { label: '名称', value: pick(currentDetail, 'name', 'templateName') },
                    { label: '灾害类型', value: DISASTER_TYPES.find((d) => d.key === pick(currentDetail, 'disasterType', 'type'))?.label || pick(currentDetail, 'disasterType', 'type') },
                    { label: '无人机数', value: pick(currentDetail, 'droneCount', 'drones') },
                    { label: '覆盖半径', value: pick(currentDetail, 'radiusM', 'radius') != null ? `${Number(pick(currentDetail, 'radiusM', 'radius')).toFixed(0)} m` : null },
                    { label: '飞行高度', value: pick(currentDetail, 'altitudeM', 'altitude', 'alt') != null ? `${Number(pick(currentDetail, 'altitudeM', 'altitude', 'alt')).toFixed(0)} m` : null },
                    { label: '协同策略', value: pick(currentDetail, 'strategy', 'coordinationStrategy') },
                    { label: '通信模式', value: pick(currentDetail, 'commMode', 'communicationMode') },
                    { label: '描述', value: pick(currentDetail, 'description', 'desc') },
                  ].filter((e) => e.value != null).map((e) => (
                    <div key={e.label} style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 10, padding: '2px 0', borderBottom: '1px solid var(--line-2)' }}>
                      <span style={{ color: 'var(--dim-2)' }}>{e.label}</span>
                      <span style={{ color: 'var(--text)', textAlign: 'right' }}>{e.value}</span>
                    </div>
                  ))}

                  {/* 启动场景输入 */}
                  <div style={{ marginTop: 8, borderTop: '1px solid var(--line-2)', paddingTop: 8 }}>
                    <div style={{ fontSize: 10, color: 'var(--dim)', marginBottom: 4 }}>一键启动场景</div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 6 }}>
                      <input
                        type="number"
                        step="any"
                        value={launchLat}
                        onChange={(e) => setLaunchLat(e.target.value)}
                        style={{ ...inputStyle, flex: '1 1 100px' }}
                        placeholder="纬度"
                      />
                      <input
                        type="number"
                        step="any"
                        value={launchLon}
                        onChange={(e) => setLaunchLon(e.target.value)}
                        style={{ ...inputStyle, flex: '1 1 100px' }}
                        placeholder="经度"
                      />
                    </div>
                    <div style={{ display: 'flex', gap: 6 }}>
                      <button
                        onClick={handleLaunch}
                        disabled={launching}
                        style={{ ...miniBtnStyle, color: 'var(--warn)', borderColor: 'var(--warn)', padding: '4px 10px', cursor: launching ? 'not-allowed' : 'pointer', opacity: launching ? 0.5 : 1 }}
                      >
                        {launching ? '启动中…' : '🚀 启动场景'}
                      </button>
                      <button
                        onClick={handleDrill}
                        disabled={drilling}
                        style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', padding: '4px 10px', cursor: drilling ? 'not-allowed' : 'pointer', opacity: drilling ? 0.5 : 1 }}
                      >
                        {drilling ? '演练中…' : '🎬 演练模式'}
                      </button>
                    </div>
                  </div>

                  {/* 演练结果 */}
                  {drillResult && (
                    <div style={{ marginTop: 8, padding: 6, background: 'var(--bg-1)', borderRadius: 3, border: '1px solid var(--cyan)' }}>
                      <div style={{ fontSize: 10, color: 'var(--cyan)', marginBottom: 4 }}>
                        演练结果{drillResultLoading ? '（加载中…）' : ''}
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', fontFamily: 'var(--mono)', whiteSpace: 'pre-wrap', maxHeight: 200, overflow: 'auto' }}>
                        {typeof drillResult === 'string' ? drillResult : JSON.stringify(drillResult, null, 2)}
                      </div>
                    </div>
                  )}
                </div>
              ) : (
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无详情数据</div>
              )}
            </div>
          )}

          {/* 进行中场景 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
              进行中场景（{activeLaunches.length}）
            </div>
            <div style={{ maxHeight: 200, overflowY: 'auto' }}>
              {activeLaunches.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无进行中场景</div>
              ) : (
                activeLaunches.map((l, i) => {
                  const lid = pick(l, 'launchId', 'id') ?? i
                  const st = pick(l, 'status', 'state') || 'RUNNING'
                  const meta = launchStatusMeta(st)
                  const isAborting = abortingId === lid
                  return (
                    <div key={lid} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 11, fontWeight: 'bold', color: meta.color }}>#{lid}</span>
                          <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${meta.color}`, color: meta.color }}>{meta.label}</span>
                          <span style={{ fontSize: 10, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {pick(l, 'templateName', 'name') || '--'}
                          </span>
                        </div>
                        <button
                          onClick={() => handleAbort(lid)}
                          disabled={isAborting}
                          style={{ ...miniBtnStyle, fontSize: 9, padding: '0 6px', color: 'var(--crit)', borderColor: 'var(--crit)', cursor: isAborting ? 'not-allowed' : 'pointer', opacity: isAborting ? 0.5 : 1 }}
                        >
                          {isAborting ? '中止中…' : '中止'}
                        </button>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        时间：{fmtTime(pick(l, 'launchedAt', 'createdAt', 'timestamp'))}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 场景历史 + 演练历史 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
              场景历史（{launchHistory.length}）
            </div>
            <div style={{ maxHeight: 240, overflowY: 'auto' }}>
              {launchHistory.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无场景历史</div>
              ) : (
                launchHistory.map((l, i) => {
                  const lid = pick(l, 'launchId', 'id') ?? i
                  const st = pick(l, 'status', 'state') || 'COMPLETED'
                  const meta = launchStatusMeta(st)
                  return (
                    <div key={lid} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 11, fontWeight: 'bold', color: meta.color }}>#{lid}</span>
                        <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${meta.color}`, color: meta.color }}>{meta.label}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        {pick(l, 'templateName', 'name') || '--'} · {fmtTime(pick(l, 'launchedAt', 'createdAt', 'timestamp', 'completedAt'))}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 演练历史 */}
          {drillHistory.length > 0 && (
            <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
              <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
                演练历史（{drillHistory.length}）
              </div>
              <div style={{ maxHeight: 160, overflowY: 'auto' }}>
                {drillHistory.map((d, i) => {
                  const did = pick(d, 'drillId', 'id') ?? i
                  return (
                    <div key={did} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: '3px solid var(--cyan)' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 11, color: 'var(--cyan)' }}>演练 #{did}</span>
                        <button
                          onClick={() => handleViewDrillResult(did)}
                          disabled={drillResultLoading}
                          style={{ ...miniBtnStyle, fontSize: 9, padding: '0 6px', color: 'var(--cyan)', borderColor: 'var(--cyan)', cursor: drillResultLoading ? 'not-allowed' : 'pointer', opacity: drillResultLoading ? 0.5 : 1 }}
                        >
                          查看结果
                        </button>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        {fmtTime(pick(d, 'createdAt', 'timestamp', 'drilledAt'))}
                      </div>
                    </div>
                  )
                })}
              </div>
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