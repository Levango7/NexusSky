import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import {
  getSurveillanceStreams,
  getDroneVideoFeeds,
  startRecording,
  stopRecording,
} from '../api.js'

// 视频融合面板：安防 RTSP 视频流 + 无人机航拍画面同屏显示
// 左侧安防 4 分屏 + 右侧无人机 2 分屏 + 画中画模式 + 流选择 + 全屏 + 录制
// 风格与 SurveillancePanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 经验来源：2026-09-23-react-multi-panel-unified-view-integration-checklist（组件创建路径与风格）

const POLL_MS = 5000

// 在线状态 → 颜色
const ONLINE_COLOR = {
  online: 'var(--ok)',
  offline: 'var(--dim)',
  error: 'var(--crit)',
}

// 模拟噪点占位画面：生成随机噪点 SVG data URI
function generateNoiseDataUri(label, w = 320, h = 180) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
    <rect width="${w}" height="${h}" fill="#0a0a0a"/>
    ${Array.from({ length: 40 }, () => {
      const x = Math.floor(Math.random() * w)
      const y = Math.floor(Math.random() * h)
      const r = Math.floor(Math.random() * 3) + 1
      const o = (Math.random() * 0.3 + 0.05).toFixed(2)
      return `<circle cx="${x}" cy="${y}" r="${r}" fill="#3a3a3a" opacity="${o}"/>`
    }).join('')}
    <text x="${w / 2}" y="${h / 2 - 8}" text-anchor="middle" fill="#555" font-size="12" font-family="monospace">${label}</text>
    <text x="${w / 2}" y="${h / 2 + 10}" text-anchor="middle" fill="#333" font-size="9" font-family="monospace">SIMULATED FEED</text>
  </svg>`
  return `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`
}

// 单个视频画面（模拟占位）
function VideoTile({
  source,       // { id, name, url, online } 或 null
  type,         // 'surveillance' | 'drone'
  selected,
  onSelect,
  onFullscreen,
  isMain,       // 是否为画中画主画面
  isPip,        // 是否为画中画小窗
}) {
  const [noiseUri, setNoiseUri] = useState(null)
  const [timestamp, setTimestamp] = useState('')

  // 每 3s 刷新噪点和时间戳，模拟实时视频流
  useEffect(() => {
    if (!source) return
    const update = () => {
      setNoiseUri(generateNoiseDataUri(source.name || source.id))
      setTimestamp(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
    }
    update()
    const timer = setInterval(update, 3000)
    return () => clearInterval(timer)
  }, [source])

  const name = source
    ? `${source.name || source.id}${type === 'drone' ? ' · 航拍' : ''}`
    : '空位'

  const status = source ? (source.online ? 'online' : 'offline') : 'offline'

  return (
    <div
      onClick={onSelect}
      style={{
        position: 'relative',
        background: 'var(--bg-1)',
        border: `1px solid ${selected ? 'var(--cyan)' : isMain ? 'var(--ok)' : 'var(--line-2)'}`,
        borderRadius: 3,
        overflow: 'hidden',
        cursor: 'pointer',
        minHeight: 0,
        display: 'flex',
        flexDirection: 'column',
        ...(isPip ? {
          position: 'absolute',
          bottom: 8,
          right: 8,
          width: '30%',
          height: '30%',
          zIndex: 10,
          boxShadow: '0 4px 12px rgba(0,0,0,0.5)',
          border: '1px solid var(--cyan)',
        } : {}),
      }}
    >
      {/* 画面标题栏 */}
      <div style={{
        fontSize: 10, padding: '2px 6px', background: 'var(--bg-2)',
        color: selected ? 'var(--cyan)' : isMain ? 'var(--ok)' : 'var(--dim)', whiteSpace: 'nowrap',
        overflow: 'hidden', textOverflow: 'ellipsis', flexShrink: 0,
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <span>{name}</span>
        <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
          {source && source.online && (
            <span style={{ fontSize: 8, color: 'var(--ok)' }}>●LIVE</span>
          )}
          {source && (
            <span style={{
              color: ONLINE_COLOR[status] || 'var(--dim)',
              fontSize: 9,
            }}>●</span>
          )}
          {source && onFullscreen && !isPip && (
            <button
              onClick={(e) => { e.stopPropagation(); onFullscreen() }}
              style={{
                ...miniBtnStyle, fontSize: 8, padding: '0 4px',
              }}
              title="全屏"
            >⛶</button>
          )}
        </div>
      </div>

      {/* 画面内容 */}
      <div style={{ flex: 1, position: 'relative', background: '#000', minHeight: 0 }}>
        {!source && (
          <div style={tileOverlayStyle}>空闲</div>
        )}
        {source && !source.online && (
          <div style={tileOverlayStyle}>
            <div style={{ textAlign: 'center' }}>
              <div>设备离线</div>
              <div style={{ fontSize: 8, color: 'var(--dim-2)', marginTop: 4 }}>{source.name || source.id}</div>
            </div>
          </div>
        )}
        {source && source.online && noiseUri && (
          <>
            <img
              src={noiseUri}
              alt={name}
              style={{
                width: '100%', height: '100%', objectFit: 'cover', display: 'block',
                filter: 'contrast(1.1) brightness(0.9)',
              }}
            />
            {/* 时间戳叠加 */}
            <div style={{
              position: 'absolute', bottom: 4, left: 4,
              fontSize: 8, color: '#aaa', fontFamily: 'var(--mono)',
              background: 'rgba(0,0,0,0.5)', padding: '1px 4px', borderRadius: 2,
            }}>
              {timestamp}
            </div>
            {/* 模拟扫描线效果 */}
            <div style={{
              position: 'absolute', inset: 0, pointerEvents: 'none',
              background: 'linear-gradient(transparent 50%, rgba(0,0,0,0.08) 50%)',
              backgroundSize: '100% 4px',
            }} />
          </>
        )}
      </div>
    </div>
  )
}

export default function VideoFusionPanel() {
  // ---- 状态 ----
  const [surveillanceStreams, setSurveillanceStreams] = useState([])
  const [droneFeeds, setDroneFeeds] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  // 安防流选择：4 个槽位，每个槽位可分配一个摄像头
  const [surveillanceSlots, setSurveillanceSlots] = useState([null, null, null, null])
  // 无人机流选择：2 个槽位
  const [droneSlots, setDroneSlots] = useState([null, null])

  // 选中的画面（用于高亮）
  const [selectedTile, setSelectedTile] = useState(null) // { side: 'surveillance'|'drone', index }

  // 画中画模式
  const [pipMode, setPipMode] = useState(false)       // 是否开启画中画
  const [pipMain, setPipMain] = useState(null)         // 主画面 { side, index }
  const [pipSide, setPipSide] = useState('surveillance') // 画中画所在区域

  // 全屏模式
  const [fullscreenTile, setFullscreenTile] = useState(null) // { side, index }

  // 录制状态
  const [recordingIds, setRecordingIds] = useState(new Set())

  // ---- 轮询流列表 ----
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const [survData, droneData] = await Promise.allSettled([
          getSurveillanceStreams(),
          getDroneVideoFeeds(),
        ])
        if (cancelled) return

        const survList = survData.status === 'fulfilled'
          ? (Array.isArray(survData.value) ? survData.value : (survData.value && survData.value.streams) || [])
          : []
        const droneList = droneData.status === 'fulfilled'
          ? (Array.isArray(droneData.value) ? droneData.value : (droneData.value && droneData.value.feeds) || [])
          : []

        setSurveillanceStreams(survList)
        setDroneFeeds(droneList)
        setLoading(false)

        // 自动填充：首次加载时把在线设备填入空槽位
        setSurveillanceSlots((prev) => {
          if (prev.some((s) => s !== null)) return prev
          const next = [...prev]
          let idx = 0
          for (const s of survList) {
            if (idx >= 4) break
            if (s.online) {
              next[idx] = s.deviceId || s.id
              idx++
            }
          }
          return next
        })
        setDroneSlots((prev) => {
          if (prev.some((s) => s !== null)) return prev
          const next = [...prev]
          let idx = 0
          for (const d of droneList) {
            if (idx >= 2) break
            if (d.online) {
              next[idx] = d.sysid || d.id
              idx++
            }
          }
          return next
        })
      } catch (e) {
        if (cancelled) return
        setError('流列表加载失败：' + (e && e.message))
        setLoading(false)
      }
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => { cancelled = true; clearInterval(timer) }
  }, [])

  // ---- 派生数据 ----
  const survTiles = useMemo(() => {
    return surveillanceSlots.map((id) => {
      if (!id) return null
      return surveillanceStreams.find((s) => (s.deviceId || s.id) === id) || null
    })
  }, [surveillanceSlots, surveillanceStreams])

  const droneTiles = useMemo(() => {
    return droneSlots.map((id) => {
      if (!id) return null
      return droneFeeds.find((d) => (d.sysid || d.id) === id) || null
    })
  }, [droneSlots, droneFeeds])

  // ---- 画面选择 ----
  const handleTileSelect = useCallback((side, index) => {
    setSelectedTile({ side, index })
  }, [])

  // ---- 流选择下拉：将选中的流分配到当前选中的槽位 ----
  const handleStreamAssign = useCallback((side, streamId) => {
    if (!selectedTile) return
    if (side === 'surveillance') {
      setSurveillanceSlots((prev) => {
        const next = [...prev]
        next[selectedTile.index] = streamId || null
        return next
      })
    } else {
      setDroneSlots((prev) => {
        const next = [...prev]
        next[selectedTile.index] = streamId || null
        return next
      })
    }
  }, [selectedTile])

  // ---- 全屏 ----
  const handleFullscreen = useCallback((side, index) => {
    setFullscreenTile({ side, index })
  }, [])

  // ---- 画中画模式切换 ----
  const togglePipMode = useCallback(() => {
    setPipMode((prev) => {
      if (!prev) {
        // 开启画中画：默认选当前选中画面为主画面
        if (selectedTile) {
          setPipMain(selectedTile)
          setPipSide(selectedTile.side === 'surveillance' ? 'drone' : 'surveillance')
        } else {
          setPipMain({ side: 'surveillance', index: 0 })
          setPipSide('drone')
        }
      } else {
        setPipMain(null)
      }
      return !prev
    })
  }, [selectedTile])

  // ---- 录制控制 ----
  const handleRecording = useCallback(async (deviceId) => {
    if (recordingIds.has(deviceId)) {
      try {
        await stopRecording(deviceId)
        setRecordingIds((prev) => {
          const next = new Set(prev)
          next.delete(deviceId)
          return next
        })
      } catch (e) {
        setError('停止录制失败：' + e.message)
      }
    } else {
      try {
        await startRecording(deviceId)
        setRecordingIds((prev) => new Set(prev).add(deviceId))
      } catch (e) {
        setError('开始录制失败：' + e.message)
      }
    }
  }, [recordingIds])

  // ---- 全屏渲染 ----
  if (fullscreenTile) {
    const source = fullscreenTile.side === 'surveillance'
      ? survTiles[fullscreenTile.index]
      : droneTiles[fullscreenTile.index]
    return (
      <div style={{ padding: 16, color: 'var(--text)', height: '100%', display: 'flex', flexDirection: 'column' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <h2 style={{ fontSize: 16, margin: 0, color: 'var(--text)' }}>
            视频融合 · 全屏
          </h2>
          <button
            onClick={() => setFullscreenTile(null)}
            style={{ ...miniBtnStyle, padding: '4px 12px' }}
          >
            ✕ 退出全屏
          </button>
        </div>
        <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
          <VideoTile
            source={source}
            type={fullscreenTile.side}
            selected={false}
            isMain={true}
          />
        </div>
      </div>
    )
  }

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        视频融合面板
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      {/* 视频控制工具栏 */}
      <div style={{ ...cardStyle, padding: 8, marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <span style={labelStyle}>视频控制</span>

        {/* 流选择 */}
        <select
          onChange={(e) => handleStreamAssign('surveillance', e.target.value)}
          value={selectedTile && selectedTile.side === 'surveillance' ? (surveillanceSlots[selectedTile.index] || '') : ''}
          style={selectStyle}
          disabled={!selectedTile || selectedTile.side !== 'surveillance'}
        >
          <option value="">— 安防摄像头 —</option>
          {surveillanceStreams.map((s) => (
            <option key={s.deviceId || s.id} value={s.deviceId || s.id}>
              {s.name || s.deviceId || s.id} {s.online ? '●' : '○'}
            </option>
          ))}
        </select>

        <select
          onChange={(e) => handleStreamAssign('drone', e.target.value)}
          value={selectedTile && selectedTile.side === 'drone' ? (droneSlots[selectedTile.index] || '') : ''}
          style={selectStyle}
          disabled={!selectedTile || selectedTile.side !== 'drone'}
        >
          <option value="">— 无人机航拍 —</option>
          {droneFeeds.map((d) => (
            <option key={d.sysid || d.id} value={d.sysid || d.id}>
              {d.name || d.sysid || d.id} {d.online ? '●' : '○'}
            </option>
          ))}
        </select>

        {/* 画中画模式 */}
        <button
          onClick={togglePipMode}
          style={{
            ...miniBtnStyle,
            padding: '3px 10px',
            border: `1px solid ${pipMode ? 'var(--cyan)' : 'var(--line-2)'}`,
            background: pipMode ? 'var(--bg-2)' : 'transparent',
            color: pipMode ? 'var(--cyan)' : 'var(--dim)',
          }}
          title="画中画模式"
        >
          {pipMode ? '▣ 画中画 ON' : '▢ 画中画'}
        </button>

        {/* 录制按钮（针对选中画面） */}
        {selectedTile && (() => {
          const source = selectedTile.side === 'surveillance'
            ? survTiles[selectedTile.index]
            : droneTiles[selectedTile.index]
          if (!source) return null
          const deviceId = source.deviceId || source.id || source.sysid
          const isRecording = recordingIds.has(deviceId)
          return (
            <button
              onClick={() => handleRecording(deviceId)}
              style={{
                ...miniBtnStyle,
                padding: '3px 10px',
                border: `1px solid ${isRecording ? 'var(--crit)' : 'var(--line-2)'}`,
                color: isRecording ? 'var(--crit)' : 'var(--dim)',
                background: isRecording ? 'var(--bg-2)' : 'transparent',
              }}
              title={isRecording ? '停止录制' : '开始录制'}
            >
              {isRecording ? '● 录制中' : '○ 录制'}
            </button>
          )
        })()}
      </div>

      {/* 主显示区域：左侧安防 + 右侧无人机 */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {/* 左侧：安防视频流 4 分屏 */}
        <div style={{ flex: '1 1 60%', minWidth: 320, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <div style={{ ...labelStyle, display: 'flex', alignItems: 'center', gap: 6 }}>
            <span>安防视频流（4 分屏）</span>
            <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>
              {survTiles.filter(Boolean).length}/4 槽位已分配
            </span>
          </div>
          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(2, 1fr)',
            gridTemplateRows: 'repeat(2, 1fr)',
            gap: 4,
            background: 'var(--bg-1)',
            border: '1px solid var(--line-2)',
            borderRadius: 4,
            padding: 4,
            aspectRatio: '32 / 18',
            minHeight: 240,
            position: 'relative',
          }}>
            {survTiles.map((source, i) => {
              // 画中画模式下，主画面放大，其他缩小
              const isMain = pipMode && pipMain && pipMain.side === 'surveillance' && pipMain.index === i
              const isPip = pipMode && pipSide === 'surveillance' && !isMain && source !== null
              if (isMain && pipMode) {
                return (
                  <div key={i} style={{ gridColumn: '1 / 3', gridRow: '1 / 3', position: 'relative' }}>
                    <VideoTile
                      source={source}
                      type="surveillance"
                      selected={selectedTile && selectedTile.side === 'surveillance' && selectedTile.index === i}
                      onSelect={() => handleTileSelect('surveillance', i)}
                      onFullscreen={() => handleFullscreen('surveillance', i)}
                      isMain={true}
                    />
                    {/* 其他在线画面作为画中画小窗 */}
                    {survTiles.map((pipSource, j) => {
                      if (j === i || !pipSource) return null
                      return (
                        <VideoTile
                          key={`pip-${j}`}
                          source={pipSource}
                          type="surveillance"
                          selected={false}
                          onSelect={() => handleTileSelect('surveillance', j)}
                          isPip={true}
                        />
                      )
                    })}
                  </div>
                )
              }
              if (isPip) return null // 画中画模式下非主画面由主画面内渲染
              return (
                <VideoTile
                  key={i}
                  source={source}
                  type="surveillance"
                  selected={selectedTile && selectedTile.side === 'surveillance' && selectedTile.index === i}
                  onSelect={() => handleTileSelect('surveillance', i)}
                  onFullscreen={() => handleFullscreen('surveillance', i)}
                />
              )
            })}
          </div>
        </div>

        {/* 右侧：无人机航拍 2 分屏 */}
        <div style={{ flex: '1 1 35%', minWidth: 200, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <div style={{ ...labelStyle, display: 'flex', alignItems: 'center', gap: 6 }}>
            <span>无人机航拍（2 分屏）</span>
            <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>
              {droneTiles.filter(Boolean).length}/2 槽位已分配
            </span>
          </div>
          <div style={{
            display: 'grid',
            gridTemplateColumns: '1fr',
            gridTemplateRows: 'repeat(2, 1fr)',
            gap: 4,
            background: 'var(--bg-1)',
            border: '1px solid var(--line-2)',
            borderRadius: 4,
            padding: 4,
            aspectRatio: '16 / 18',
            minHeight: 240,
            position: 'relative',
          }}>
            {droneTiles.map((source, i) => {
              const isMain = pipMode && pipMain && pipMain.side === 'drone' && pipMain.index === i
              const isPip = pipMode && pipSide === 'drone' && !isMain && source !== null
              if (isMain && pipMode) {
                return (
                  <div key={i} style={{ gridRow: '1 / 3', position: 'relative' }}>
                    <VideoTile
                      source={source}
                      type="drone"
                      selected={selectedTile && selectedTile.side === 'drone' && selectedTile.index === i}
                      onSelect={() => handleTileSelect('drone', i)}
                      onFullscreen={() => handleFullscreen('drone', i)}
                      isMain={true}
                    />
                    {droneTiles.map((pipSource, j) => {
                      if (j === i || !pipSource) return null
                      return (
                        <VideoTile
                          key={`pip-${j}`}
                          source={pipSource}
                          type="drone"
                          selected={false}
                          onSelect={() => handleTileSelect('drone', j)}
                          isPip={true}
                        />
                      )
                    })}
                  </div>
                )
              }
              if (isPip) return null
              return (
                <VideoTile
                  key={i}
                  source={source}
                  type="drone"
                  selected={selectedTile && selectedTile.side === 'drone' && selectedTile.index === i}
                  onSelect={() => handleTileSelect('drone', i)}
                  onFullscreen={() => handleFullscreen('drone', i)}
                />
              )
            })}
          </div>
        </div>
      </div>

      {/* 底部：设备状态摘要 */}
      <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
        <div style={{ ...cardStyle, flex: '1 1 300px', padding: 8 }}>
          <div style={labelStyle}>安防摄像头（{surveillanceStreams.length}）</div>
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 4 }}>
            {surveillanceStreams.length > 0 ? surveillanceStreams.map((s) => {
              const id = s.deviceId || s.id
              const isAssigned = surveillanceSlots.includes(id)
              return (
                <span
                  key={id}
                  style={{
                    fontSize: 9, padding: '2px 6px', borderRadius: 3,
                    background: isAssigned ? 'var(--bg-2)' : 'transparent',
                    border: `1px solid ${isAssigned ? 'var(--cyan)' : 'var(--line-2)'}`,
                    color: s.online ? 'var(--text)' : 'var(--dim-2)',
                    cursor: 'pointer',
                  }}
                  onClick={() => {
                    // 点击设备名 → 分配到第一个空槽位
                    const emptyIdx = surveillanceSlots.findIndex((slot) => !slot)
                    if (emptyIdx !== -1) {
                      setSurveillanceSlots((prev) => {
                        const next = [...prev]
                        next[emptyIdx] = id
                        return next
                      })
                    }
                  }}
                >
                  {s.online ? '●' : '○'} {s.name || id}
                </span>
              )
            }) : (
              <span style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无安防摄像头</span>
            )}
          </div>
        </div>

        <div style={{ ...cardStyle, flex: '1 1 200px', padding: 8 }}>
          <div style={labelStyle}>无人机航拍（{droneFeeds.length}）</div>
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 4 }}>
            {droneFeeds.length > 0 ? droneFeeds.map((d) => {
              const id = d.sysid || d.id
              const isAssigned = droneSlots.includes(id)
              return (
                <span
                  key={id}
                  style={{
                    fontSize: 9, padding: '2px 6px', borderRadius: 3,
                    background: isAssigned ? 'var(--bg-2)' : 'transparent',
                    border: `1px solid ${isAssigned ? 'var(--cyan)' : 'var(--line-2)'}`,
                    color: d.online ? 'var(--text)' : 'var(--dim-2)',
                    cursor: 'pointer',
                  }}
                  onClick={() => {
                    const emptyIdx = droneSlots.findIndex((slot) => !slot)
                    if (emptyIdx !== -1) {
                      setDroneSlots((prev) => {
                        const next = [...prev]
                        next[emptyIdx] = id
                        return next
                      })
                    }
                  }}
                >
                  {d.online ? '●' : '○'} {d.name || id}
                </span>
              )
            }) : (
              <span style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无无人机航拍</span>
            )}
          </div>
        </div>
      </div>

      {loading && (
        <div style={{ fontSize: 10, color: 'var(--dim-2)', textAlign: 'center', marginTop: 8 }}>
          正在加载视频流列表…
        </div>
      )}
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

const selectStyle = {
  padding: '3px 6px',
  fontSize: 10,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
}