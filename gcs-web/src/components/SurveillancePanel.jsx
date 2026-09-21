import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import {
  listSurveillanceDevices,
  registerSurveillanceDevice,
  unregisterSurveillanceDevice,
  getDeviceStream,
  ptzControl,
  discoverDevices,
  listSurveillanceEvents,
} from '../api.js'

// M10 安防视频监控面板
// 设备列表（海康/大华/宇视） + 多画面分屏（1/4/9 宫格） + PTZ 云台控制
// 设备注册弹窗 + 子网发现 + 底部安防事件流
// 风格与 EmergencyOrchPanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 3s；视频流按协议渲染（HLS 优先原生 <video>，不支持时动态加载 hls.js CDN）
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（AbortController 竞态守卫）

const POLL_MS = 3000

// 支持的厂商
const VENDORS = [
  { key: 'hikvision', label: '海康威视' },
  { key: 'dahua', label: '大华' },
  { key: 'uniview', label: '宇视' },
  { key: 'other', label: '其他' },
]

// 宫格分屏配置
const GRID_LAYOUTS = [
  { key: 1, label: '1 画面', cols: 1, rows: 1 },
  { key: 4, label: '4 画面', cols: 2, rows: 2 },
  { key: 9, label: '9 画面', cols: 3, rows: 3 },
]

// PTZ 控制命令
const PTZ_BUTTONS = [
  { cmd: 'up', label: '▲', title: '上' },
  { cmd: 'left', label: '◀', title: '左' },
  { cmd: 'stop', label: '■', title: '停止' },
  { cmd: 'right', label: '▶', title: '右' },
  { cmd: 'down', label: '▼', title: '下' },
]

const PTZ_ZOOM = [
  // 修复 Major 7：与后端 OnvifClient.PTZ_COMMANDS 对齐（驼峰而非下划线）
  { cmd: 'zoomIn', label: '＋', title: '放大' },
  { cmd: 'zoomOut', label: '－', title: '缩小' },
]

// 在线状态 → 颜色
const ONLINE_COLOR = {
  online: 'var(--ok)',
  offline: 'var(--dim)',
  error: 'var(--crit)',
}

// 安防事件级别 → 颜色
const EVENT_LEVEL_COLOR = {
  alarm: 'var(--crit)',
  motion: 'var(--warn)',
  offline: 'var(--dim)',
  online: 'var(--ok)',
  info: 'var(--cyan)',
}

// hls.js CDN 地址（仅当浏览器不支持原生 HLS 时按需加载）
const HLS_JS_CDN = 'https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js'

// 动态加载 hls.js（全局缓存，避免重复注入）
let hlsJsPromise = null
function loadHlsJs() {
  if (hlsJsPromise) return hlsJsPromise
  hlsJsPromise = new Promise((resolve, reject) => {
    if (window.Hls) return resolve(window.Hls)
    const script = document.createElement('script')
    script.src = HLS_JS_CDN
    script.onload = () => resolve(window.Hls)
    script.onerror = () => reject(new Error('hls.js 加载失败'))
    document.head.appendChild(script)
  })
  return hlsJsPromise
}

// 单个视频画面：按协议渲染流
function VideoTile({ device, channel, selected, onSelect }) {
  const videoRef = useRef(null)
  const hlsRef = useRef(null)
  const [stream, setStream] = useState(null)       // { url, protocol }
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  // 拉取流地址并渲染
  useEffect(() => {
    if (!device || !device.id) return
    const controller = new AbortController()
    let hls = null

    const load = async () => {
      setLoading(true)
      setError(null)
      try {
        // 注意：getDeviceStream 内部用 jsonFetch，不直接接受 signal；
        // 此处用 controller 作为竞态守卫，避免卸载后 setState
        const data = await getDeviceStream(device.id, channel)
        if (controller.signal.aborted) return // 经验：abort 后不更新 state
        setStream(data)
        setLoading(false)

        // 按协议挂载流
        const protocol = (data && data.protocol) || guessProtocol(data && data.url)
        const url = data && data.url
        if (!url) return

        if (protocol === 'hls') {
          const video = videoRef.current
          if (!video) return
          // 原生 HLS（Safari / iOS）
          if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = url
            video.play().catch(() => {})
          } else {
            // 动态加载 hls.js
            try {
              const Hls = await loadHlsJs()
              if (controller.signal.aborted) return
              hls = new Hls({ enableWorker: true, lowLatencyMode: true })
              hlsRef.current = hls
              hls.loadSource(url)
              hls.attachMedia(video)
              hls.on(Hls.Events.MANIFEST_PARSED, () => {
                video.play().catch(() => {})
              })
              hls.on(Hls.Events.ERROR, (_evt, err) => {
                if (err.fatal) setError('视频流错误：' + err.type)
              })
            } catch (e) {
              setError('HLS 播放器加载失败')
            }
          }
        }
        // mjpeg / image：直接用 <img> 渲染，无需额外处理
      } catch (e) {
        if (e && e.name === 'AbortError') return // 经验：AbortError 直接 return
        if (controller.signal.aborted) return
        setError('获取视频流失败：' + (e && e.message))
        setLoading(false)
      }
    }

    load()
    return () => {
      controller.abort() // 经验：cleanup 中 abort
      if (hls) {
        hls.destroy()
        hlsRef.current = null
      }
      const video = videoRef.current
      if (video) {
        video.pause()
        video.removeAttribute('src')
        video.load()
      }
    }
  }, [device && device.id, channel])

  // 根据流数据渲染
  const protocol = (stream && stream.protocol) || guessProtocol(stream && stream.url)
  const isImg = protocol === 'mjpeg' || protocol === 'image'
  const isHls = protocol === 'hls'
  const isRtsp = protocol === 'rtsp'

  const name = device
    ? `${device.name || device.id}${channel != null ? ` · 通道${channel}` : ''}`
    : '空位'

  return (
    <div
      onClick={onSelect}
      style={{
        position: 'relative',
        background: 'var(--bg-1)',
        border: `1px solid ${selected ? 'var(--cyan)' : 'var(--line-2)'}`,
        borderRadius: 3,
        overflow: 'hidden',
        cursor: 'pointer',
        minHeight: 0,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* 画面标题栏 */}
      <div style={{
        fontSize: 10, padding: '2px 6px', background: 'var(--bg-2)',
        color: selected ? 'var(--cyan)' : 'var(--dim)', whiteSpace: 'nowrap',
        overflow: 'hidden', textOverflow: 'ellipsis', flexShrink: 0,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <span>{name}</span>
        {device && (
          <span style={{
            color: ONLINE_COLOR[device.status || 'offline'] || 'var(--dim)',
            fontSize: 9,
          }}>●</span>
        )}
      </div>
      {/* 画面内容 */}
      <div style={{ flex: 1, position: 'relative', background: '#000', minHeight: 0 }}>
        {loading && (
          <div style={tileOverlayStyle}>加载中…</div>
        )}
        {error && (
          <div style={{ ...tileOverlayStyle, color: 'var(--crit)' }}>{error}</div>
        )}
        {!loading && !error && !device && (
          <div style={tileOverlayStyle}>空闲</div>
        )}
        {!loading && !error && device && isRtsp && (
          <div style={tileOverlayStyle}>
            RTSP 流需后端转码
          </div>
        )}
        {!loading && !error && device && isImg && stream && stream.url && (
          <img
            src={stream.url}
            alt={name}
            style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }}
          />
        )}
        {!loading && !error && device && isHls && (
          <video
            ref={videoRef}
            style={{ width: '100%', height: '100%', objectFit: 'contain', background: '#000' }}
            muted
            autoPlay
            playsInline
          />
        )}
      </div>
    </div>
  )
}

// 根据 URL 猜测协议
function guessProtocol(url) {
  if (!url) return null
  if (url.endsWith('.m3u8')) return 'hls'
  if (url.startsWith('rtsp://')) return 'rtsp'
  if (url.endsWith('.mjpeg') || url.includes('mjpeg')) return 'mjpeg'
  if (url.match(/\.(jpg|jpeg|png|gif)/i) || url.includes('snapshot')) return 'image'
  return 'hls'
}

export default function SurveillancePanel() {
  // ---- 状态 ----
  const [devices, setDevices] = useState([])
  const [devicesLoading, setDevicesLoading] = useState(true)
  const [selectedDeviceId, setSelectedDeviceId] = useState(null)
  const [gridMode, setGridMode] = useState(4)             // 1 / 4 / 9
  const [tileAssign, setTileAssign] = useState({})        // { tileIndex: { deviceId, channel } }
  const [events, setEvents] = useState([])                // 安防事件流
  const [showRegister, setShowRegister] = useState(false) // 注册弹窗
  const [showDiscover, setShowDiscover] = useState(false) // 发现弹窗
  const [error, setError] = useState(null)
  const [ptzBusy, setPtzBusy] = useState(false)

  // 注册表单
  const [regForm, setRegForm] = useState({
    name: '', ip: '', port: 80, vendor: 'hikvision',
    username: 'admin', password: '', channels: 1,
  })
  const [registering, setRegistering] = useState(false)

  // 发现表单
  const [discoverSubnet, setDiscoverSubnet] = useState('192.168.1.0/24')
  const [discovering, setDiscovering] = useState(false)
  const [discoverResults, setDiscoverResults] = useState([])

  // ---- 轮询设备列表 ----
  // 使用 ref 读取 selectedDeviceId，避免选中设备变化时重启轮询
  const selectedDeviceIdRef = useRef(null)
  selectedDeviceIdRef.current = selectedDeviceId

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await listSurveillanceDevices()
        if (cancelled) return
        const list = Array.isArray(data) ? data : (data && data.devices) || []
        setDevices(list)
        setDevicesLoading(false)
        // 设备列表为空时清空选中，避免 selectedDeviceId 指向已不存在的设备
        // 导致后续 PTZ / 流请求失败
        if (list.length === 0) {
          if (selectedDeviceIdRef.current !== null) setSelectedDeviceId(null)
        } else if (!list.some((d) => d.id === selectedDeviceIdRef.current)) {
          // 默认选中第一个在线设备
          setSelectedDeviceId(list[0].id)
        }
      } catch (e) {
        if (cancelled) return
        setError('设备列表加载失败：' + e.message)
        setDevicesLoading(false)
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ---- 轮询安防事件 ----
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const data = await listSurveillanceEvents({ limit: 30 })
        if (cancelled) return
        const list = Array.isArray(data) ? data : (data && data.events) || []
        setEvents(list)
      } catch (e) {
        // 事件流加载失败不打断主流程
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ---- 派生数据 ----
  const selectedDevice = useMemo(
    () => devices.find((d) => d.id === selectedDeviceId),
    [devices, selectedDeviceId]
  )

  const grid = GRID_LAYOUTS.find((g) => g.key === gridMode) || GRID_LAYOUTS[1]
  const tileCount = grid.key
  const tiles = useMemo(() => {
    const arr = []
    for (let i = 0; i < tileCount; i++) {
      const assign = tileAssign[i]
      if (assign) {
        const dev = devices.find((d) => d.id === assign.deviceId)
        arr.push({ device: dev, channel: assign.channel })
      } else {
        arr.push({ device: null, channel: null })
      }
    }
    return arr
  }, [tileAssign, devices, tileCount])

  // 自动填充空位：把未分配的设备填入空画面
  const autoAssign = useCallback(() => {
    setTileAssign((prev) => {
      const next = { ...prev }
      const usedIds = new Set(Object.values(next).map((a) => a.deviceId).filter(Boolean))
      let tileIdx = 0
      for (const dev of devices) {
        if (usedIds.has(dev.id)) continue
        while (next[tileIdx] && next[tileIdx].deviceId) tileIdx++
        if (tileIdx >= tileCount) break
        next[tileIdx] = { deviceId: dev.id, channel: dev.channel || 0 }
        tileIdx++
      }
      return next
    })
  }, [devices, tileCount])

  // ---- PTZ 控制 ----
  const handlePtz = useCallback(async (cmd) => {
    if (!selectedDeviceId) return
    setPtzBusy(true)
    try {
      await ptzControl(selectedDeviceId, cmd)
    } catch (e) {
      setError('PTZ 控制失败：' + e.message)
    } finally {
      setPtzBusy(false)
    }
  }, [selectedDeviceId])

  // ---- 设备注册 ----
  const handleRegister = useCallback(async () => {
    setRegistering(true)
    setError(null)
    try {
      const payload = {
        ...regForm,
        port: Number(regForm.port),
        channels: Number(regForm.channels),
      }
      await registerSurveillanceDevice(payload)
      setShowRegister(false)
      // 轮询会自动刷新列表
    } catch (e) {
      setError('设备注册失败：' + e.message)
    } finally {
      setRegistering(false)
    }
  }, [regForm])

  // ---- 设备注销 ----
  const handleUnregister = useCallback(async (deviceId) => {
    try {
      await unregisterSurveillanceDevice(deviceId)
      if (selectedDeviceId === deviceId) setSelectedDeviceId(null)
      // 清理画面分配
      setTileAssign((prev) => {
        const next = {}
        for (const [k, v] of Object.entries(prev)) {
          if (v && v.deviceId !== deviceId) next[k] = v
        }
        return next
      })
    } catch (e) {
      setError('设备注销失败：' + e.message)
    }
  }, [selectedDeviceId])

  // ---- 子网发现 ----
  const handleDiscover = useCallback(async () => {
    setDiscovering(true)
    setError(null)
    setDiscoverResults([])
    try {
      const data = await discoverDevices(discoverSubnet)
      const list = Array.isArray(data) ? data : (data && data.devices) || []
      setDiscoverResults(list)
    } catch (e) {
      setError('设备发现失败：' + e.message)
    } finally {
      setDiscovering(false)
    }
  }, [discoverSubnet])

  // ---- 画面分配点击：选中设备填入指定画面 ----
  const handleTileSelect = useCallback((tileIndex) => {
    if (!selectedDeviceId) return
    setTileAssign((prev) => ({
      ...prev,
      [tileIndex]: { deviceId: selectedDeviceId, channel: selectedDevice ? (selectedDevice.channel || 0) : 0 },
    }))
  }, [selectedDeviceId, selectedDevice])

  // 选中画面索引（用于高亮）
  const selectedTileIndex = useMemo(() => {
    for (const [k, v] of Object.entries(tileAssign)) {
      if (v && v.deviceId === selectedDeviceId) return Number(k)
    }
    return null
  }, [tileAssign, selectedDeviceId])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        安防视频监控
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左侧：设备列表 */}
        <div style={{ ...cardStyle, flex: '0 0 220px', padding: 8, alignSelf: 'flex-start' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <div style={labelStyle}>设备列表（{devices.length}）</div>
            <button onClick={() => setShowRegister(true)} style={miniBtnStyle} title="注册设备">＋</button>
          </div>
          {devicesLoading ? (
            <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 8 }}>加载中…</div>
          ) : devices.length === 0 ? (
            <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 8 }}>暂无设备，点击「＋」注册</div>
          ) : (
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {devices.map((d) => {
                const isSel = d.id === selectedDeviceId
                const color = ONLINE_COLOR[d.status || 'offline'] || 'var(--dim)'
                const vendor = VENDORS.find((v) => v.key === d.vendor) || { label: d.vendor || '未知' }
                return (
                  <div
                    key={d.id}
                    onClick={() => setSelectedDeviceId(d.id)}
                    style={{
                      padding: '4px 6px', marginBottom: 2, borderRadius: 3, cursor: 'pointer',
                      background: isSel ? 'var(--bg-2)' : 'transparent',
                      border: `1px solid ${isSel ? 'var(--cyan)' : 'transparent'}`,
                      display: 'flex', alignItems: 'center', gap: 6,
                    }}
                  >
                    <span style={{ color, fontSize: 10 }}>●</span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 11, color: isSel ? 'var(--cyan)' : 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {d.name || d.id}
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {vendor.label} · {d.ip || '--'}:{d.port || '--'}
                      </div>
                    </div>
                    <button
                      onClick={(e) => { e.stopPropagation(); handleUnregister(d.id) }}
                      style={{ ...miniBtnStyle, fontSize: 8, padding: '0 4px' }}
                      title="注销设备"
                    >✕</button>
                  </div>
                )
              })}
            </div>
          )}
          <button
            onClick={() => setShowDiscover(true)}
            style={{ ...miniBtnStyle, width: '100%', marginTop: 6, padding: '3px 0' }}
          >
            🔍 子网发现
          </button>
        </div>

        {/* 右侧：视频分屏区 */}
        <div style={{ flex: '1 1 480px', minWidth: 320, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 工具栏：宫格切换 + 自动排布 */}
          <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
            <span style={labelStyle}>分屏</span>
            {GRID_LAYOUTS.map((g) => (
              <button
                key={g.key}
                onClick={() => setGridMode(g.key)}
                style={{
                  padding: '3px 10px', fontSize: 10, cursor: 'pointer', borderRadius: 3,
                  border: `1px solid ${gridMode === g.key ? 'var(--cyan)' : 'var(--line-2)'}`,
                  background: gridMode === g.key ? 'var(--bg-2)' : 'transparent',
                  color: gridMode === g.key ? 'var(--cyan)' : 'var(--dim)',
                }}
              >
                {g.label}
              </button>
            ))}
            <button onClick={autoAssign} style={{ ...miniBtnStyle, marginLeft: 'auto' }}>
              ⚡ 自动排布
            </button>
            <button onClick={() => setTileAssign({})} style={miniBtnStyle}>
              清空
            </button>
          </div>

          {/* 宫格画面 */}
          <div style={{
            display: 'grid',
            gridTemplateColumns: `repeat(${grid.cols}, 1fr)`,
            gridTemplateRows: `repeat(${grid.rows}, 1fr)`,
            gap: 4,
            background: 'var(--bg-1)',
            border: '1px solid var(--line-2)',
            borderRadius: 4,
            padding: 4,
            aspectRatio: `${grid.cols * 16} / ${grid.rows * 9}`,
            minHeight: 200,
          }}>
            {tiles.map((t, i) => (
              <VideoTile
                key={i}
                device={t.device}
                channel={t.channel}
                selected={selectedTileIndex === i}
                onSelect={() => handleTileSelect(i)}
              />
            ))}
          </div>

          {/* PTZ 控制面板 */}
          {selectedDevice && (
            <div style={{ ...cardStyle, padding: 8, display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
              <div style={labelStyle}>PTZ 云台 · {selectedDevice.name || selectedDevice.id}</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 28px)', gap: 3 }}>
                {PTZ_BUTTONS.map((b) => (
                  <button
                    key={b.cmd}
                    onClick={() => handlePtz(b.cmd)}
                    disabled={ptzBusy}
                    title={b.title}
                    style={{
                      width: 28, height: 28, fontSize: 12, cursor: ptzBusy ? 'not-allowed' : 'pointer',
                      border: '1px solid var(--line-2)', background: 'var(--bg-1)',
                      color: b.cmd === 'stop' ? 'var(--crit)' : 'var(--text)', borderRadius: 3,
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      opacity: ptzBusy ? 0.5 : 1,
                    }}
                  >
                    {b.label}
                  </button>
                ))}
              </div>
              <div style={{ display: 'flex', gap: 3, alignItems: 'center' }}>
                <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>变倍</span>
                {PTZ_ZOOM.map((b) => (
                  <button
                    key={b.cmd}
                    onClick={() => handlePtz(b.cmd)}
                    disabled={ptzBusy}
                    title={b.title}
                    style={{
                      width: 28, height: 28, fontSize: 12, cursor: ptzBusy ? 'not-allowed' : 'pointer',
                      border: '1px solid var(--line-2)', background: 'var(--bg-1)',
                      color: 'var(--text)', borderRadius: 3, opacity: ptzBusy ? 0.5 : 1,
                    }}
                  >
                    {b.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* 底部：安防事件流 */}
          <div style={{ ...cardStyle, padding: 8 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={labelStyle}>安防事件（{events.length}）</div>
            </div>
            <div style={{
              maxHeight: 140, overflowY: 'auto', marginTop: 4, fontSize: 10, fontFamily: 'var(--mono)',
              background: 'var(--bg-1)', borderRadius: 3, padding: 4, border: '1px solid var(--line-2)',
            }}>
              {events.length > 0 ? (
                events.map((e, i) => {
                  const ts = e.ts != null ? e.ts : e.timestamp
                  const level = String(e.level || e.type || 'info').toLowerCase()
                  const color = EVENT_LEVEL_COLOR[level] || 'var(--dim)'
                  const time = ts ? new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--'
                  const msg = e.message || e.description || e.text || JSON.stringify(e)
                  return (
                    <div key={i} style={{ display: 'flex', gap: 8, padding: '1px 0', color: 'var(--text)' }}>
                      <span style={{ color: 'var(--dim-2)', flexShrink: 0 }}>{time}</span>
                      <span style={{ color, flexShrink: 0 }}>[{level.toUpperCase()}]</span>
                      <span style={{ color: 'var(--text)', wordBreak: 'break-all' }}>{msg}</span>
                    </div>
                  )
                })
              ) : (
                <div style={{ color: 'var(--dim-2)', textAlign: 'center', padding: 8 }}>暂无安防事件</div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* 设备注册弹窗 */}
      {showRegister && (
        <Modal title="注册安防设备" onClose={() => setShowRegister(false)}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 280 }}>
            <Field label="设备名称">
              <input value={regForm.name} onChange={(e) => setRegForm({ ...regForm, name: e.target.value })} style={modalInputStyle} placeholder="如：前门摄像头" />
            </Field>
            <div style={{ display: 'flex', gap: 8 }}>
              <Field label="IP 地址" style={{ flex: 1 }}>
                <input value={regForm.ip} onChange={(e) => setRegForm({ ...regForm, ip: e.target.value })} style={modalInputStyle} placeholder="192.168.1.100" />
              </Field>
              <Field label="端口">
                <input type="number" value={regForm.port} onChange={(e) => setRegForm({ ...regForm, port: e.target.value })} style={modalInputStyle} />
              </Field>
            </div>
            <Field label="厂商">
              <select value={regForm.vendor} onChange={(e) => setRegForm({ ...regForm, vendor: e.target.value })} style={modalInputStyle}>
                {VENDORS.map((v) => <option key={v.key} value={v.key}>{v.label}</option>)}
              </select>
            </Field>
            <div style={{ display: 'flex', gap: 8 }}>
              <Field label="用户名" style={{ flex: 1 }}>
                <input value={regForm.username} onChange={(e) => setRegForm({ ...regForm, username: e.target.value })} style={modalInputStyle} />
              </Field>
              <Field label="密码" style={{ flex: 1 }}>
                <input type="password" value={regForm.password} onChange={(e) => setRegForm({ ...regForm, password: e.target.value })} style={modalInputStyle} />
              </Field>
            </div>
            <Field label="通道数">
              <input type="number" min="1" value={regForm.channels} onChange={(e) => setRegForm({ ...regForm, channels: e.target.value })} style={modalInputStyle} />
            </Field>
            <button
              onClick={handleRegister}
              disabled={registering || !regForm.ip}
              style={{
                padding: '6px 0', fontSize: 11, cursor: registering ? 'not-allowed' : 'pointer',
                border: '1px solid var(--cyan)', background: registering ? 'var(--bg-2)' : 'transparent',
                color: registering ? 'var(--dim)' : 'var(--cyan)', borderRadius: 3, fontWeight: 'bold',
                opacity: registering || !regForm.ip ? 0.6 : 1, marginTop: 4,
              }}
            >
              {registering ? '注册中…' : '注册'}
            </button>
          </div>
        </Modal>
      )}

      {/* 子网发现弹窗 */}
      {showDiscover && (
        <Modal title="子网设备发现" onClose={() => setShowDiscover(false)}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 320 }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
              <Field label="子网 CIDR" style={{ flex: 1 }}>
                <input value={discoverSubnet} onChange={(e) => setDiscoverSubnet(e.target.value)} style={modalInputStyle} placeholder="192.168.1.0/24" />
              </Field>
              <button
                onClick={handleDiscover}
                disabled={discovering}
                style={{ ...miniBtnStyle, padding: '4px 12px' }}
              >
                {discovering ? '扫描中…' : '扫描'}
              </button>
            </div>
            <div style={{
              maxHeight: 240, overflowY: 'auto', fontSize: 10, fontFamily: 'var(--mono)',
              background: 'var(--bg-1)', borderRadius: 3, padding: 4, border: '1px solid var(--line-2)',
            }}>
              {discoverResults.length > 0 ? (
                discoverResults.map((d, i) => (
                  <div key={i} style={{ display: 'flex', justifyContent: 'space-between', padding: '2px 4px', borderBottom: '1px solid var(--line-2)' }}>
                    <span style={{ color: 'var(--text)' }}>{d.ip || d.host || '--'}</span>
                    <span style={{ color: 'var(--dim-2)' }}>{d.vendor || d.manufacturer || '--'}</span>
                    <button
                      onClick={() => {
                        setRegForm({
                          name: d.name || d.ip || '',
                          ip: d.ip || '', port: d.port || 80,
                          vendor: d.vendor || 'other',
                          username: 'admin', password: '', channels: 1,
                        })
                        setShowDiscover(false)
                        setShowRegister(true)
                      }}
                      style={{ ...miniBtnStyle, fontSize: 9, padding: '0 6px' }}
                    >添加</button>
                  </div>
                ))
              ) : (
                <div style={{ color: 'var(--dim-2)', textAlign: 'center', padding: 12 }}>
                  {discovering ? '正在扫描子网…' : '点击「扫描」发现设备'}
                </div>
              )}
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

// ===== 子组件 =====

// 弹窗
function Modal({ title, onClose, children }) {
  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 1000,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: 'var(--bg-1)', border: '1px solid var(--line-2)', borderRadius: 6,
          padding: 16, minWidth: 300, maxHeight: '80vh', overflowY: 'auto',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h3 style={{ fontSize: 13, margin: 0, color: 'var(--text)' }}>{title}</h3>
          <button onClick={onClose} style={{ ...miniBtnStyle, padding: '0 6px' }}>✕</button>
        </div>
        {children}
      </div>
    </div>
  )
}

// 表单字段
function Field({ label, children, style }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 2, ...style }}>
      <span style={{ fontSize: 10, color: 'var(--dim-2)' }}>{label}</span>
      {children}
    </label>
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

const tileOverlayStyle = {
  position: 'absolute',
  inset: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 10,
  color: 'var(--dim-2)',
  background: '#000',
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