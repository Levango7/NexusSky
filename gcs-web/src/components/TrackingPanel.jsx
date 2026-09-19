import React, { useState, useEffect, useCallback, useRef } from 'react'
import {
  getFlightTrack,
  replayTrack,
  getLostDrones,
  getSearchGuide,
  scanLostDrones,
} from '../api.js'

// M12 无人机追踪 / 遗失辅助查找面板
// 失联无人机列表（轮询） + 搜索引导卡片 + 飞行轨迹查询 + 历史轨迹回放 + 手动扫描
// 风格与 SurveillancePanel / AlarmPanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 5s；所有异步请求使用 AbortController 防止竞态
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（竞态守卫）

const POLL_MS = 5000

// ---- 字段兼容提取 ----
// 后端字段命名未最终确定，按常见命名做兼容兜底
function pick(obj, ...keys) {
  if (!obj) return null
  for (const k of keys) {
    if (obj[k] != null) return obj[k]
  }
  return null
}

// 失联无人机字段归一化
function normLost(d) {
  const sysid = pick(d, 'sysid', 'id', 'sysId')
  const lat = pick(d, 'lat', 'lastLat', 'lastKnownLat')
  const lon = pick(d, 'lon', 'lastLon', 'lastKnownLon')
  const ts = pick(d, 'lostAt', 'lastSeen', 'lastContact', 'timestamp', 'ts')
  const battery = pick(d, 'battery', 'batteryPct', 'batteryLevel', 'batteryPercent')
  return { sysid, lat, lon, ts, battery, raw: d }
}

// 搜索引导字段归一化
function normGuide(g) {
  return {
    lat: pick(g, 'lastKnownLat', 'lat'),
    lon: pick(g, 'lastKnownLon', 'lon'),
    alt: pick(g, 'lastKnownAlt', 'alt', 'altitude'),
    heading: pick(g, 'trackDirection', 'heading', 'bearing', 'course'),
    speed: pick(g, 'speed', 'lastSpeed'),
    battery: pick(g, 'battery', 'batteryPct', 'batteryLevel'),
    radius: pick(g, 'estimatedRadius', 'searchRadius', 'radius', 'fallRadius'),
    raw: g,
  }
}

// 轨迹点字段归一化
function normTrackPoint(p) {
  return {
    time: pick(p, 'time', 'timestamp', 'ts', 't'),
    lat: pick(p, 'lat', 'latitude'),
    lon: pick(p, 'lon', 'longitude'),
    alt: pick(p, 'alt', 'altitude', 'height'),
    battery: pick(p, 'battery', 'batteryPct', 'batteryLevel'),
  }
}

// ---- 时间格式化 ----
function fmtTime(ts) {
  if (ts == null) return '--'
  const t = typeof ts === 'number' ? ts : Date.parse(ts)
  if (isNaN(t)) return '--'
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

// epoch ms → datetime-local 输入框值（本地时区）
function msToDatetimeLocal(ms) {
  if (ms == null || isNaN(ms)) return ''
  const d = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// datetime-local 输入框值 → epoch ms（浏览器按本地时区解析）
function datetimeLocalToMs(str) {
  if (!str) return null
  const t = new Date(str).getTime()
  return isNaN(t) ? null : t
}

// 电量格式化（兼容 0-1 和 0-100）
function fmtBattery(b) {
  if (b == null) return '--'
  const n = Number(b)
  if (isNaN(n)) return '--'
  const pct = n <= 1 ? n * 100 : n
  return pct.toFixed(0) + '%'
}

// 电量颜色
function batteryColor(b) {
  if (b == null) return 'var(--dim-2)'
  const n = Number(b)
  if (isNaN(n)) return 'var(--dim-2)'
  const pct = n <= 1 ? n * 100 : n
  if (pct <= 15) return 'var(--crit)'
  if (pct <= 30) return 'var(--warn)'
  return 'var(--ok)'
}

// 经纬度格式化
function fmtCoord(v, suffix) {
  if (v == null || isNaN(Number(v))) return '--'
  return Number(v).toFixed(6) + (suffix || '')
}

export default function TrackingPanel() {
  // ---- 失联列表（轮询）----
  const [lostDrones, setLostDrones] = useState([])
  const [lostError, setLostError] = useState(null)

  // ---- 搜索引导 ----
  const [selectedSysid, setSelectedSysid] = useState(null)
  const [guide, setGuide] = useState(null)
  const [guideLoading, setGuideLoading] = useState(false)
  const [guideError, setGuideError] = useState(null)
  const guideAbortRef = useRef(null)

  // ---- 飞行轨迹查询 ----
  const [trackSysid, setTrackSysid] = useState('')
  const [trackLimit, setTrackLimit] = useState(100)
  const [trackPoints, setTrackPoints] = useState([])
  const [trackLoading, setTrackLoading] = useState(false)
  const [trackError, setTrackError] = useState(null)
  const trackAbortRef = useRef(null)

  // ---- 历史轨迹回放 ----
  const [replaySysid, setReplaySysid] = useState('')
  const [replayFrom, setReplayFrom] = useState('')
  const [replayTo, setReplayTo] = useState('')
  const [replayLimit, setReplayLimit] = useState(1000)
  const [replayPoints, setReplayPoints] = useState([])
  const [replayLoading, setReplayLoading] = useState(false)
  const [replayError, setReplayError] = useState(null)
  const replayAbortRef = useRef(null)

  // ---- 手动扫描 ----
  const [scanning, setScanning] = useState(false)
  const [scanResult, setScanResult] = useState(null)
  const [scanError, setScanError] = useState(null)

  // ---- 轮询失联无人机列表 ----
  // 使用 AbortController 守卫竞态：cleanup 时 abort，避免卸载后 setState
  useEffect(() => {
    const controller = new AbortController()
    let cancelled = false

    const load = async () => {
      try {
        const data = await getLostDrones({ signal: controller.signal })
        if (cancelled || controller.signal.aborted) return
        const list = Array.isArray(data) ? data : (data && data.drones) || (data && data.list) || []
        setLostDrones(list)
        setLostError(null)
      } catch (e) {
        if (cancelled || controller.signal.aborted) return
        // 轮询失败不抛出，仅标记错误，下次轮询自愈
        setLostError(e && e.message ? e.message : String(e))
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

  // ---- 选中失联无人机 → 加载搜索引导 ----
  // 切换选中项时取消上一次未完成的请求，防止竞态
  useEffect(() => {
    if (selectedSysid == null) {
      setGuide(null)
      setGuideError(null)
      return
    }

    const controller = new AbortController()
    // 取消上一次请求
    if (guideAbortRef.current) guideAbortRef.current.abort()
    guideAbortRef.current = controller

    let cancelled = false
    const load = async () => {
      setGuideLoading(true)
      setGuideError(null)
      try {
        const data = await getSearchGuide(selectedSysid, { signal: controller.signal })
        if (cancelled || controller.signal.aborted) return
        setGuide(data)
      } catch (e) {
        if (cancelled || controller.signal.aborted) return
        setGuideError(e && e.message ? e.message : String(e))
        setGuide(null)
      } finally {
        if (!cancelled && !controller.signal.aborted) setGuideLoading(false)
      }
    }
    load()

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [selectedSysid])

  // ---- 飞行轨迹查询 ----
  const handleQueryTrack = useCallback(async () => {
    const sysid = trackSysid.trim()
    if (!sysid) {
      setTrackError('请输入 sysid')
      return
    }
    const limit = Math.max(1, Math.min(10000, Number(trackLimit) || 100))

    // 取消上一次请求
    if (trackAbortRef.current) trackAbortRef.current.abort()
    const controller = new AbortController()
    trackAbortRef.current = controller

    setTrackLoading(true)
    setTrackError(null)
    try {
      const data = await getFlightTrack(sysid, limit, { signal: controller.signal })
      if (controller.signal.aborted) return
      const list = Array.isArray(data) ? data : (data && data.points) || (data && data.track) || []
      setTrackPoints(list)
    } catch (e) {
      if (controller.signal.aborted) return
      setTrackError(e && e.message ? e.message : String(e))
      setTrackPoints([])
    } finally {
      if (!controller.signal.aborted) setTrackLoading(false)
    }
  }, [trackSysid, trackLimit])

  // ---- 历史轨迹回放 ----
  const handleReplay = useCallback(async () => {
    const sysid = replaySysid.trim()
    if (!sysid) {
      setReplayError('请输入 sysid')
      return
    }
    const fromMs = datetimeLocalToMs(replayFrom)
    const toMs = datetimeLocalToMs(replayTo)
    if (fromMs == null || toMs == null) {
      setReplayError('请选择有效的起始和结束时间')
      return
    }
    if (fromMs >= toMs) {
      setReplayError('起始时间必须早于结束时间')
      return
    }
    const limit = Math.max(1, Math.min(10000, Number(replayLimit) || 1000))

    // 取消上一次请求
    if (replayAbortRef.current) replayAbortRef.current.abort()
    const controller = new AbortController()
    replayAbortRef.current = controller

    setReplayLoading(true)
    setReplayError(null)
    try {
      const data = await replayTrack(sysid, fromMs, toMs, limit, { signal: controller.signal })
      if (controller.signal.aborted) return
      const list = Array.isArray(data) ? data : (data && data.points) || (data && data.track) || []
      setReplayPoints(list)
    } catch (e) {
      if (controller.signal.aborted) return
      setReplayError(e && e.message ? e.message : String(e))
      setReplayPoints([])
    } finally {
      if (!controller.signal.aborted) setReplayLoading(false)
    }
  }, [replaySysid, replayFrom, replayTo, replayLimit])

  // ---- 手动扫描 ----
  const handleScan = useCallback(async () => {
    setScanning(true)
    setScanError(null)
    try {
      const data = await scanLostDrones()
      setScanResult(data)
    } catch (e) {
      setScanError(e && e.message ? e.message : String(e))
    } finally {
      setScanning(false)
    }
  }, [])

  // ---- 点击失联无人机行 ----
  const handleSelectLost = useCallback((sysid) => {
    setSelectedSysid((prev) => (prev === sysid ? null : sysid))
  }, [])

  // ---- 派生：归一化失联列表 ----
  const lostNorm = lostDrones.map(normLost)

  // ---- 派生：扫描结果新失联归一化 ----
  const scanNewlyLost = scanResult
    ? (Array.isArray(scanResult.newlyLost)
        ? scanResult.newlyLost
        : (scanResult.newlyLost && scanResult.newlyLost.drones) || []).map(normLost)
    : []

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        无人机追踪 / 遗失辅助查找
      </h2>

      {(lostError || scanError) && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {lostError ? `失联列表刷新失败：${lostError}` : `扫描失败：${scanError}`}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* ===== 左列：失联列表 + 搜索引导 + 扫描结果 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 失联无人机列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{
              padding: '6px 10px', borderBottom: '1px solid var(--line-2)',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8,
            }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>
                失联无人机
                <span style={{ fontSize: 9, color: 'var(--dim-2)', marginLeft: 6 }}>
                  （{lostNorm.length}）
                </span>
              </span>
              <button
                onClick={handleScan}
                disabled={scanning}
                style={{
                  ...miniBtnStyle,
                  opacity: scanning ? 0.5 : 1,
                  cursor: scanning ? 'not-allowed' : 'pointer',
                  color: 'var(--warn)',
                  borderColor: 'var(--warn)',
                }}
              >
                {scanning ? '扫描中…' : '⟳ 手动扫描'}
              </button>
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {lostNorm.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>
                  {lostError ? '刷新失败，等待重试' : '暂无失联无人机'}
                </div>
              ) : (
                lostNorm.map((d, i) => {
                  const key = d.sysid != null ? d.sysid : i
                  const isSel = d.sysid === selectedSysid
                  return (
                    <div
                      key={key}
                      onClick={() => handleSelectLost(d.sysid)}
                      style={{
                        padding: '6px 10px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-3, var(--bg-1))' : 'transparent',
                        borderLeft: `3px solid ${isSel ? 'var(--warn)' : 'var(--crit)'}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 11, color: 'var(--crit)', fontWeight: 'bold', flexShrink: 0 }}>
                            #{d.sysid != null ? d.sysid : '?'}
                          </span>
                          <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>
                            失联 {fmtTime(d.ts)}
                          </span>
                        </div>
                        <span style={{
                          fontSize: 9, padding: '1px 5px', borderRadius: 3,
                          color: batteryColor(d.battery),
                          border: `1px solid ${batteryColor(d.battery)}`,
                        }}>
                          {fmtBattery(d.battery)}
                        </span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, fontFamily: 'var(--mono)' }}>
                        位置：{fmtCoord(d.lat, 'N')}  {fmtCoord(d.lon, 'E')}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 搜索引导卡片 */}
          {selectedSysid != null && (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{
                fontSize: 11, color: 'var(--text)', marginBottom: 8,
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              }}>
                <span>搜索引导 #{selectedSysid}</span>
                {guideLoading && <span style={{ fontSize: 9, color: 'var(--cyan)' }}>加载中…</span>}
              </div>
              {guideError ? (
                <div style={{ fontSize: 10, color: 'var(--crit)' }}>⚠ {guideError}</div>
              ) : guideLoading ? null : guide ? (
                <GuideView guide={guide} />
              ) : (
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无搜索引导数据</div>
              )}
            </div>
          )}

          {/* 手动扫描结果 */}
          {scanResult && (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{
                fontSize: 11, color: 'var(--text)', marginBottom: 8,
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              }}>
                <span>扫描结果</span>
                <button
                  onClick={() => setScanResult(null)}
                  style={{ ...miniBtnStyle, fontSize: 9, padding: '0 5px' }}
                >✕</button>
              </div>
              <div style={{ fontSize: 10, color: 'var(--dim)', marginBottom: 6, display: 'flex', gap: 12 }}>
                <span>总失联：<b style={{ color: 'var(--crit)' }}>{scanResult.totalLost != null ? scanResult.totalLost : '--'}</b></span>
                <span>新失联：<b style={{ color: 'var(--warn)' }}>{scanNewlyLost.length}</b></span>
              </div>
              {scanNewlyLost.length > 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  {scanNewlyLost.map((d, i) => (
                    <div key={d.sysid != null ? d.sysid : i} style={{
                      fontSize: 9, color: 'var(--dim-2)', fontFamily: 'var(--mono)',
                      padding: '3px 6px', background: 'var(--bg-1)', borderRadius: 3,
                    }}>
                      <span style={{ color: 'var(--crit)', fontWeight: 'bold' }}>#{d.sysid != null ? d.sysid : '?'}</span>
                      {' '}
                      {fmtTime(d.ts)}
                      {' '}
                      ({fmtCoord(d.lat)}, {fmtCoord(d.lon)})
                      {' '}
                      <span style={{ color: batteryColor(d.battery) }}>{fmtBattery(d.battery)}</span>
                    </div>
                  ))}
                </div>
              ) : (
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>本次扫描无新增失联</div>
              )}
            </div>
          )}
        </div>

        {/* ===== 右列：飞行轨迹查询 + 历史回放 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 飞行轨迹查询 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8 }}>飞行轨迹查询</div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
              <input
                type="number"
                placeholder="sysid"
                value={trackSysid}
                onChange={(e) => setTrackSysid(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') handleQueryTrack() }}
                style={{ ...modalInputStyle, width: 90, flex: '0 0 90px' }}
              />
              <label style={{ fontSize: 9, color: 'var(--dim-2)', display: 'flex', alignItems: 'center', gap: 3 }}>
                limit
                <input
                  type="number"
                  min={1}
                  max={10000}
                  value={trackLimit}
                  onChange={(e) => setTrackLimit(e.target.value)}
                  style={{ ...modalInputStyle, width: 70 }}
                />
              </label>
              <button
                onClick={handleQueryTrack}
                disabled={trackLoading}
                style={{
                  ...miniBtnStyle,
                  color: 'var(--cyan)', borderColor: 'var(--cyan)',
                  opacity: trackLoading ? 0.5 : 1, cursor: trackLoading ? 'not-allowed' : 'pointer',
                }}
              >
                {trackLoading ? '查询中…' : '查询'}
              </button>
            </div>
            {trackError && (
              <div style={{ fontSize: 10, color: 'var(--crit)', marginBottom: 6 }}>⚠ {trackError}</div>
            )}
            <TrackTable points={trackPoints} loading={trackLoading} emptyHint="输入 sysid 后查询轨迹" />
          </div>

          {/* 历史轨迹回放 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8 }}>历史轨迹回放</div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <input
                type="number"
                placeholder="sysid"
                value={replaySysid}
                onChange={(e) => setReplaySysid(e.target.value)}
                style={{ ...modalInputStyle, width: 90, flex: '0 0 90px' }}
              />
              <label style={{ fontSize: 9, color: 'var(--dim-2)', display: 'flex', alignItems: 'center', gap: 3 }}>
                limit
                <input
                  type="number"
                  min={1}
                  max={10000}
                  value={replayLimit}
                  onChange={(e) => setReplayLimit(e.target.value)}
                  style={{ ...modalInputStyle, width: 70 }}
                />
              </label>
            </div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
              <label style={{ fontSize: 9, color: 'var(--dim-2)', display: 'flex', alignItems: 'center', gap: 3 }}>
                从
                <input
                  type="datetime-local"
                  value={replayFrom}
                  onChange={(e) => setReplayFrom(e.target.value)}
                  style={{ ...modalInputStyle, width: 175 }}
                />
              </label>
              <label style={{ fontSize: 9, color: 'var(--dim-2)', display: 'flex', alignItems: 'center', gap: 3 }}>
                至
                <input
                  type="datetime-local"
                  value={replayTo}
                  onChange={(e) => setReplayTo(e.target.value)}
                  style={{ ...modalInputStyle, width: 175 }}
                />
              </label>
              <button
                onClick={handleReplay}
                disabled={replayLoading}
                style={{
                  ...miniBtnStyle,
                  color: 'var(--cyan)', borderColor: 'var(--cyan)',
                  opacity: replayLoading ? 0.5 : 1, cursor: replayLoading ? 'not-allowed' : 'pointer',
                }}
              >
                {replayLoading ? '回放中…' : '回放'}
              </button>
            </div>
            {replayError && (
              <div style={{ fontSize: 10, color: 'var(--crit)', marginBottom: 6 }}>⚠ {replayError}</div>
            )}
            <TrackTable points={replayPoints} loading={replayLoading} emptyHint="选择时间范围后回放轨迹" />
          </div>
        </div>
      </div>
    </div>
  )
}

// ===== 搜索引导视图 =====
function GuideView({ guide }) {
  const g = normGuide(guide)
  const entries = [
    { label: '最后已知位置', value: g.lat != null && g.lon != null ? `${fmtCoord(g.lat, 'N')}  ${fmtCoord(g.lon, 'E')}` : '--' },
    { label: '最后已知高度', value: g.alt != null ? `${Number(g.alt).toFixed(1)} m` : '--' },
    { label: '轨迹方向', value: g.heading != null ? `${Number(g.heading).toFixed(0)}°` : '--' },
    { label: '末速度', value: g.speed != null ? `${Number(g.speed).toFixed(1)} m/s` : '--' },
    { label: '电量', value: fmtBattery(g.battery), color: batteryColor(g.battery) },
    { label: '预计坠落半径', value: g.radius != null ? `${Number(g.radius).toFixed(1)} m` : '--', color: 'var(--warn)' },
  ]
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {entries.map((e) => (
        <div key={e.label} style={{
          display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8,
          fontSize: 10, padding: '3px 0', borderBottom: '1px solid var(--line-2)',
        }}>
          <span style={{ color: 'var(--dim-2)' }}>{e.label}</span>
          <span style={{ color: e.color || 'var(--text)', fontFamily: 'var(--mono)', fontWeight: 'bold' }}>
            {e.value}
          </span>
        </div>
      ))}
    </div>
  )
}

// ===== 轨迹点表格 =====
function TrackTable({ points, loading, emptyHint }) {
  if (loading) {
    return <div style={{ fontSize: 10, color: 'var(--cyan)', padding: '8px 0' }}>加载中…</div>
  }
  if (!points || points.length === 0) {
    return <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: '8px 0' }}>{emptyHint || '暂无数据'}</div>
  }
  const rows = points.map(normTrackPoint)
  return (
    <div>
      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginBottom: 4 }}>
        共 <b style={{ color: 'var(--cyan)' }}>{rows.length}</b> 个轨迹点
      </div>
      <div style={{ maxHeight: 260, overflow: 'auto', border: '1px solid var(--line-2)', borderRadius: 3 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 9, fontFamily: 'var(--mono)' }}>
          <thead>
            <tr style={{ position: 'sticky', top: 0, background: 'var(--bg-1)' }}>
              <th style={thStyle}>时间</th>
              <th style={thStyle}>纬度</th>
              <th style={thStyle}>经度</th>
              <th style={thStyle}>高度</th>
              <th style={thStyle}>电量</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((p, i) => (
              <tr key={i} style={{ borderBottom: '1px solid var(--line-2)' }}>
                <td style={{ ...tdStyle, color: 'var(--dim-2)', whiteSpace: 'nowrap' }}>{fmtTime(p.time)}</td>
                <td style={tdStyle}>{p.lat != null ? Number(p.lat).toFixed(6) : '--'}</td>
                <td style={tdStyle}>{p.lon != null ? Number(p.lon).toFixed(6) : '--'}</td>
                <td style={tdStyle}>{p.alt != null ? Number(p.alt).toFixed(1) : '--'}</td>
                <td style={{ ...tdStyle, color: batteryColor(p.battery) }}>{fmtBattery(p.battery)}</td>
              </tr>
            ))}
          </tbody>
        </table>
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

const thStyle = {
  textAlign: 'left',
  padding: '4px 6px',
  fontSize: 9,
  color: 'var(--dim)',
  fontWeight: 'normal',
  borderBottom: '1px solid var(--line-2)',
}

const tdStyle = {
  padding: '3px 6px',
  fontSize: 9,
  color: 'var(--text)',
}