import React, { useEffect, useRef, useState } from 'react'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'

// 免费无密钥瓦片：CartoDB dark（户外地面站审美：深色高对比）
const MAP_STYLE = {
  version: 8,
  sources: {
    basemap: {
      type: 'raster',
      tiles: [
        'https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png',
        'https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png',
        'https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png',
      ],
      tileSize: 256,
      attribution: '© OpenStreetMap contributors © CARTO',
    },
  },
  layers: [
    { id: 'base', type: 'raster', source: 'basemap' },
  ],
}

const DRONE_HOME = { lat: 22.5907, lon: 113.9345 }

// 10色预定义无人机色板，按 sysid 取模分配
const DRONE_COLORS = [
  '#00c8ff', '#ff6ec7', '#2de2a5', '#ffb224', '#ff5d5d',
  '#a78bfa', '#fbbf24', '#34d399', '#f87171', '#60a5fa',
]

// 编队色板（紫色系，与多机色板区分）
const FORMATION_COLORS = [
  '#9d4edd', '#c77dff', '#7b2cbf', '#e0aaff', '#5a189a',
  '#b5179e', '#7209b7', '#3c096c', '#f72585', '#4361ee',
]

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function getDroneColor(sysid) {
  return DRONE_COLORS[sysid % DRONE_COLORS.length]
}

function getFormationColor(formationId) {
  return FORMATION_COLORS[formationId % FORMATION_COLORS.length]
}

// 高度颜色映射：绿(低)→黄(中)→红(高)
function altitudeToColor(alt, minAlt, maxAlt) {
  if (maxAlt <= minAlt) return '#2de2a5'
  const t = Math.max(0, Math.min(1, (alt - minAlt) / (maxAlt - minAlt)))
  if (t < 0.5) {
    const r = Math.round(45 + (255 - 45) * (t * 2))
    const g = Math.round(226 + (178 - 226) * (t * 2))
    const b = Math.round(165 + (36 - 165) * (t * 2))
    return `rgb(${r},${g},${b})`
  } else {
    const t2 = (t - 0.5) * 2
    const r = 255
    const g = Math.round(178 + (93 - 178) * t2)
    const b = Math.round(36 + (93 - 36) * t2)
    return `rgb(${r},${g},${b})`
  }
}

// 生成轨迹着色的 line-gradient expression
function buildTrackGradient(mode, trackPoints) {
  if (!trackPoints || trackPoints.length < 2) return null

  if (mode === 'time') {
    // 暗→亮渐变
    return [
      'interpolate', ['linear'], ['line-progress'],
      0, 'rgba(255,179,0,0.15)',
      1, '#ffb300',
    ]
  }

  if (mode === 'altitude') {
    // 绿(低)→黄(中)→红(高)
    const minAlt = Math.min(...trackPoints.map(p => p.alt || 0))
    const maxAlt = Math.max(...trackPoints.map(p => p.alt || 0))
    const stops = []
    for (let i = 0; i < trackPoints.length; i++) {
      const progress = i / (trackPoints.length - 1)
      const color = altitudeToColor(trackPoints[i].alt || 0, minAlt, maxAlt)
      stops.push(progress, color)
    }
    return ['interpolate', ['linear'], ['line-progress'], ...stops]
  }

  return null // single mode
}

// 创建多机标记 SVG（三角箭头，按 heading 旋转）
function createDroneMarkerSVG(color, heading, isSelected, isOnline) {
  const size = isSelected ? 40 : 30
  const opacity = isOnline ? 1 : 0.4
  const strokeColor = isOnline ? color : '#888'
  const fillColor = isOnline ? color : '#666'
  const cx = size / 2
  const cy = size / 2
  const arrowLen = isSelected ? 12 : 9
  const arrowWidth = isSelected ? 8 : 6

  const eSize = escapeHtml(size)
  const eOpacity = escapeHtml(opacity)
  const eCx = escapeHtml(cx)
  const eCy = escapeHtml(cy)
  const eStrokeColor = escapeHtml(strokeColor)
  const eFillColor = escapeHtml(fillColor)
  const eStrokeWidth = escapeHtml(isSelected ? 2.5 : 1.5)
  const eHeading = escapeHtml(heading || 0)
  const eArrowLen = escapeHtml(arrowLen)
  const eArrowWidthHalf = escapeHtml(arrowWidth / 2)
  const eArrowLenHalf = escapeHtml(arrowLen / 2)
  const eColor = escapeHtml(color)
  const eCxPlus3 = escapeHtml(cx + 3)

  return `<svg width="${eSize}" height="${eSize}" viewBox="0 0 ${eSize} ${eSize}" style="opacity:${eOpacity};display:block">
    <circle cx="${eCx}" cy="${eCy}" r="${escapeHtml(cx - 2)}" fill="rgba(0,0,0,.3)" stroke="${eStrokeColor}" stroke-width="${eStrokeWidth}"/>
    <g transform="rotate(${eHeading} ${eCx} ${eCy})">
      <path d="M${eCx} ${escapeHtml(cy - arrowLen)} L${escapeHtml(cx - arrowWidth / 2)} ${eArrowLenHalf} L${escapeHtml(cx + arrowWidth / 2)} ${eArrowLenHalf} Z" fill="${eFillColor}" stroke="${eStrokeColor}" stroke-width="1"/>
    </g>
    ${isSelected ? `<circle cx="${eCx}" cy="${eCy}" r="${eCxPlus3}" fill="none" stroke="${eColor}" stroke-width="2" stroke-dasharray="3,2"/>` : ''}
  </svg>`
}

// 创建回放标记球 SVG
function createReplayMarkerSVG() {
  return `<svg width="20" height="20" viewBox="0 0 20 20" style="display:block">
    <circle cx="10" cy="10" r="8" fill="#ff3333" stroke="#fff" stroke-width="2"/>
    <circle cx="10" cy="10" r="3" fill="#fff"/>
  </svg>`
}

export default function MapView({
  // 现有 props
  telemetry, track, missionDraft, onMapClick, selected, orbitOverlay,
  // 新增 props
  drones, multiTracks, trackColorMode, replayTrack, replayProgress,
  formations, trackingOverlay, onDroneSelect,
}) {
  const mapRef = useRef(null)
  const mapInstance = useRef(null)
  const markerRef = useRef(null)
  const followedRef = useRef(false)
  const [mapError, setMapError] = useState(null)
  const [retryKey, setRetryKey] = useState(0)
  const [loadedRetryKey, setLoadedRetryKey] = useState(null)
  // 用 ref 记录是否已记录过地图错误，避免闭包陷阱
  const errorLoggedRef = useRef(false)
  // keep the latest click handler in a ref so the map listener (bound once)
  // always calls the freshest closure
  const clickRef = useRef(null)
  clickRef.current = onMapClick

  // 多机标记 refs: { sysid: Marker }
  const droneMarkersRef = useRef({})
  // 回放标记 ref
  const replayMarkerRef = useRef(null)
  // 着色模式内部状态（可与外部 trackColorMode prop 同步）
  const [colorMode, setColorMode] = useState(trackColorMode || 'single')
  // 脉冲动画 ID
  const pulseAnimRef = useRef(null)

  // 同步外部 trackColorMode prop 到内部状态
  useEffect(() => {
    if (trackColorMode) setColorMode(trackColorMode)
  }, [trackColorMode])

  // ─── 地图初始化 ────────────────────────────────────────────
  useEffect(() => {
    let map = null
    errorLoggedRef.current = false
    followedRef.current = false
    try {
      map = new maplibregl.Map({
        container: mapRef.current,
        style: MAP_STYLE,
        center: [DRONE_HOME.lon, DRONE_HOME.lat],
        zoom: 15,
        attributionControl: false,
      })
    } catch (e) {
      setMapError(e.message || '地图初始化失败')
      return
    }
    // 监听地图加载错误，按严重程度分级处理
    map.on('error', (e) => {
      const msg = (e.error && e.error.message) || ''
      if (msg.includes('tile') || msg.includes('Tile')) {
        console.warn('[MapView] 瓦片加载失败（已忽略，不影响地图使用）:', msg)
        return
      }
      if (!errorLoggedRef.current) {
        errorLoggedRef.current = true
        setMapError(msg || '地图加载错误')
      }
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'bottom-right')
    map.addControl(new maplibregl.ScaleControl(), 'bottom-left')
    mapInstance.current = map

    // Shift+click on the map adds a waypoint
    map.on('click', (e) => {
      if (e.originalEvent && e.originalEvent.shiftKey && clickRef.current) {
        clickRef.current({
          lat: Number(e.lngLat.lat.toFixed(6)),
          lon: Number(e.lngLat.lng.toFixed(6)),
        })
      }
    })

    const canvas = map.getCanvas()
    map.on('mousemove', (e) => {
      const shift = e.originalEvent && e.originalEvent.shiftKey
      canvas.style.cursor = shift ? 'crosshair' : ''
    })

    // 单机标记（保持原有）
    const el = document.createElement('div')
    el.className = 'drone-marker'
    el.innerHTML =
      '<svg width="34" height="34" viewBox="0 0 34 34"><circle cx="17" cy="17" r="16" fill="rgba(0,200,255,.15)" stroke="#00c8ff" stroke-width="1.5"/><path d="M17 6 L17 28 M8 15 L26 15" stroke="#00c8ff" stroke-width="3" stroke-linecap="round"/></svg>'
    markerRef.current = new maplibregl.Marker({ element: el })
      .setLngLat([DRONE_HOME.lon, DRONE_HOME.lat])
      .addTo(map)

    // 图层只能在样式加载完成后添加
    map.on('load', () => {
      // ── 现有 layers ──
      map.addSource('track-line', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'track-line',
        type: 'line',
        source: 'track-line',
        paint: {
          'line-color': '#ffb300',
          'line-width': 2.5,
          'line-opacity': 0.9,
        },
      })
      map.addSource('mission-line', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'mission-line',
        type: 'line',
        source: 'mission-line',
        paint: {
          'line-color': '#00e5a0',
          'line-width': 2,
          'line-dasharray': [4, 3],
        },
      })
      map.addLayer({
        id: 'mission-points',
        type: 'circle',
        source: 'mission-line',
        paint: {
          'circle-radius': 5,
          'circle-color': '#00e5a0',
        },
      })
      map.addSource('orbit-ring', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'orbit-ring',
        type: 'line',
        source: 'orbit-ring',
        paint: {
          'line-color': '#ff6ec7',
          'line-width': 2,
          'line-dasharray': [2, 2],
        },
      })

      // ── 回放轨迹 source + layer ──
      map.addSource('replay-track', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'replay-track',
        type: 'line',
        source: 'replay-track',
        paint: {
          'line-color': '#00ffff',
          'line-width': 3,
          'line-opacity': 0.8,
          'line-gradient': [
            'interpolate', ['linear'], ['line-progress'],
            0, '#00ffff',
            1, '#ffd700',
          ],
        },
      })

      // ── 编队连线 source + layer ──
      map.addSource('formation-links', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'formation-links',
        type: 'line',
        source: 'formation-links',
        paint: {
          'line-color': ['get', 'color'],
          'line-width': 1.5,
          'line-opacity': 0.6,
          'line-dasharray': [3, 3],
        },
      })

      // ── 编队中心 source + layer ──
      map.addSource('formation-centers', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'formation-centers',
        type: 'circle',
        source: 'formation-centers',
        paint: {
          'circle-radius': 8,
          'circle-color': ['get', 'color'],
          'circle-stroke-width': 2,
          'circle-stroke-color': '#fff',
          'circle-opacity': 0.8,
        },
      })
      map.addLayer({
        id: 'formation-labels',
        type: 'symbol',
        source: 'formation-centers',
        layout: {
          'text-field': ['get', 'label'],
          'text-size': 12,
          'text-offset': [0, -1.5],
        },
        paint: {
          'text-color': '#fff',
          'text-halo-color': '#000',
          'text-halo-width': 2,
        },
      })

      // ── 丢失无人机 source + layer ──
      map.addSource('lost-drones', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'lost-drones',
        type: 'circle',
        source: 'lost-drones',
        paint: {
          'circle-radius': 10,
          'circle-color': '#ff0000',
          'circle-opacity': 0.6,
          'circle-stroke-width': 2,
          'circle-stroke-color': '#ff0000',
        },
      })

      // ── 搜索引导区域 source + layer ──
      map.addSource('search-guide', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'search-guide',
        type: 'fill',
        source: 'search-guide',
        paint: {
          'fill-color': '#ff0000',
          'fill-opacity': 0.2,
        },
      })

      // ── 查询轨迹 source + layer ──
      map.addSource('query-track', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      })
      map.addLayer({
        id: 'query-track',
        type: 'line',
        source: 'query-track',
        paint: {
          'line-color': '#a78bfa',
          'line-width': 2,
          'line-opacity': 0.8,
        },
      })

      setLoadedRetryKey(retryKey)
    })

    return () => {
      // 清理所有多机标记
      Object.values(droneMarkersRef.current).forEach(m => m.remove())
      droneMarkersRef.current = {}
      // 清理回放标记
      if (replayMarkerRef.current) {
        replayMarkerRef.current.remove()
        replayMarkerRef.current = null
      }
      // 清理脉冲动画
      if (pulseAnimRef.current) {
        cancelAnimationFrame(pulseAnimRef.current)
        pulseAnimRef.current = null
      }
      if (map) map.remove()
      mapInstance.current = null
    }
  }, [retryKey]) // retryKey 变化时重新初始化地图

  // ─── 实时位置更新 + 任务草稿航线绘制（保持不变） ────────────
  useEffect(() => {
    const map = mapInstance.current
    const marker = markerRef.current
    if (!map || !marker || !telemetry || telemetry.lat == null) return

    marker.setLngLat([telemetry.lon, telemetry.lat])

    if (map.isStyleLoaded?.() && map.getSource('mission-line')) {
      const coords = missionDraft.map((w) => [w.lon, w.lat])
      map.getSource('mission-line').setData({
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            geometry: {
              type: 'LineString',
              coordinates: [[telemetry.lon, telemetry.lat], ...coords],
            },
          },
          ...coords.map((c, i) => ({
            type: 'Feature',
            geometry: { type: 'Point', coordinates: c },
            properties: { seq: i + 1 },
          })),
        ],
      })
    }

    if (!followedRef.current) {
      map.flyTo({ center: [telemetry.lon, telemetry.lat], zoom: 16, duration: 1500 })
      followedRef.current = true
    }
  }, [telemetry, missionDraft, retryKey, loadedRetryKey])

  // ─── 轨迹重绘（保持不变） ──────────────────────────────────
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (map.isStyleLoaded?.() && map.getSource('track-line')) {
      if (track && track.length > 1) {
        map.getSource('track-line').setData({
          type: 'Feature',
          geometry: {
            type: 'LineString',
            coordinates: track.map((p) => [p.lon, p.lat]),
          },
        })
      }
    }
  }, [track, retryKey, loadedRetryKey])

  // ─── 环绕圈重绘（保持不变） ────────────────────────────────
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (map.isStyleLoaded?.() && map.getSource('orbit-ring')) {
      const features = []
      if (orbitOverlay?.center && orbitOverlay.radiusM > 0) {
        const pts = []
        const { lat, lon } = orbitOverlay.center
        for (let i = 0; i <= 64; i++) {
          const a = (i / 64) * 2 * Math.PI
          const dLat = (orbitOverlay.radiusM * Math.cos(a)) / 111320
          const dLon = (orbitOverlay.radiusM * Math.sin(a)) / (111320 * Math.cos((lat * Math.PI) / 180))
          pts.push([lon + dLon, lat + dLat])
        }
        features.push({
          type: 'Feature',
          geometry: { type: 'LineString', coordinates: pts },
        })
      }
      map.getSource('orbit-ring').setData({
        type: 'FeatureCollection',
        features,
      })
    }
  }, [orbitOverlay, retryKey, loadedRetryKey])

  // ─── ① 多机位置标记 + heading 方向指示 ─────────────────────
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (!map.isStyleLoaded?.()) return

    if (!drones || drones.length === 0) {
      // 清理所有多机标记
      Object.values(droneMarkersRef.current).forEach(m => m.remove())
      droneMarkersRef.current = {}
      return
    }

    const currentSysids = new Set(drones.map(d => d.sysid))
    // 清理不再存在的无人机标记
    Object.keys(droneMarkersRef.current).forEach(sysid => {
      if (!currentSysids.has(Number(sysid))) {
        droneMarkersRef.current[sysid].remove()
        delete droneMarkersRef.current[sysid]
      }
    })

    // 创建/更新标记
    drones.forEach(drone => {
      const { sysid, lat, lon, heading, online } = drone
      if (lat == null || lon == null) return

      const color = getDroneColor(sysid)
      const isSelected = selected === sysid
      const isOnline = online !== false

      const existing = droneMarkersRef.current[sysid]
      if (existing) {
        // 更新位置
        existing.setLngLat([lon, lat])
        // 更新 SVG（heading/selected/online 可能变化）
        const el = existing.getElement()
        el.innerHTML = createDroneMarkerSVG(color, heading, isSelected, isOnline)
      } else {
        // 创建新标记
        const el = document.createElement('div')
        el.className = 'drone-marker-multi'
        el.innerHTML = createDroneMarkerSVG(color, heading, isSelected, isOnline)
        el.style.cursor = 'pointer'
        el.addEventListener('click', (e) => {
          e.stopPropagation()
          if (onDroneSelect) onDroneSelect(sysid)
        })
        const marker = new maplibregl.Marker({ element: el })
          .setLngLat([lon, lat])
          .addTo(map)
        droneMarkersRef.current[sysid] = marker
      }
    })
  }, [drones, selected, retryKey, loadedRetryKey, onDroneSelect])

  // ─── ② 多机轨迹线 ─────────────────────────────────────────
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (!map.isStyleLoaded?.()) return

    const sysids = multiTracks ? Object.keys(multiTracks) : []
    const currentSysids = new Set(sysids)

    // 清理不再存在的轨迹 source/layer
    const existingLayers = map.getStyle()?.layers || []
    existingLayers.forEach(layer => {
      if (layer.id.startsWith('multi-track-')) {
        const sysid = layer.id.replace('multi-track-', '')
        if (!currentSysids.has(sysid)) {
          if (map.getLayer(layer.id)) map.removeLayer(layer.id)
          if (map.getSource(layer.id)) map.removeSource(layer.id)
        }
      }
    })

    // 创建/更新每架机的轨迹
    sysids.forEach(sysid => {
      const trackPoints = multiTracks[sysid]
      if (!trackPoints || trackPoints.length < 2) return

      // 保留最近 30 个点
      const recentPoints = trackPoints.slice(-30)
      const color = getDroneColor(Number(sysid))
      const sourceId = `multi-track-${sysid}`
      const layerId = `multi-track-${sysid}`

      // 创建 source（如果不存在）
      if (!map.getSource(sourceId)) {
        map.addSource(sourceId, {
          type: 'geojson',
          data: { type: 'FeatureCollection', features: [] },
        })
      }

      // 创建 layer（如果不存在）
      if (!map.getLayer(layerId)) {
        map.addLayer({
          id: layerId,
          type: 'line',
          source: sourceId,
          paint: {
            'line-color': color,
            'line-width': 1.5,
            'line-opacity': 0.7,
          },
        })
      }

      // 更新数据
      map.getSource(sourceId).setData({
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: recentPoints.map(p => [p.lon, p.lat]),
        },
      })
    })
  }, [multiTracks, retryKey, loadedRetryKey])

  // ─── ③ 轨迹着色模式切换（只影响选中机的轨迹） ──────────────
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (!map.isStyleLoaded?.()) return

    const sysids = multiTracks ? Object.keys(multiTracks) : []
    sysids.forEach(sysid => {
      const layerId = `multi-track-${sysid}`
      if (!map.getLayer(layerId)) return

      const numSysid = Number(sysid)
      const color = getDroneColor(numSysid)
      const isSelected = selected === numSysid

      if (isSelected && colorMode !== 'single') {
        const trackPoints = multiTracks[sysid]
        if (trackPoints && trackPoints.length >= 2) {
          const recentPoints = trackPoints.slice(-30)
          const gradient = buildTrackGradient(colorMode, recentPoints)
          if (gradient) {
            map.setPaintProperty(layerId, 'line-gradient', gradient)
          }
        }
      } else {
        // 非选中机或 single 模式：使用单色 gradient（始终返回同一颜色）
        map.setPaintProperty(layerId, 'line-gradient', [
          'interpolate', ['linear'], ['line-progress'],
          0, color,
          1, color,
        ])
      }
    })
  }, [colorMode, selected, multiTracks, retryKey, loadedRetryKey])

  // ─── ④ 2D 轨迹回放 ────────────────────────────────────────
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (!map.isStyleLoaded?.()) return

    if (!replayTrack || replayTrack.length === 0) {
      // 清理回放元素
      if (replayMarkerRef.current) {
        replayMarkerRef.current.remove()
        replayMarkerRef.current = null
      }
      if (map.getSource('replay-track')) {
        map.getSource('replay-track').setData({
          type: 'FeatureCollection',
          features: [],
        })
      }
      return
    }

    // 绘制完整轨迹线（渐变色 cyan→gold，在 layer 创建时已设置 line-gradient）
    if (map.getSource('replay-track')) {
      map.getSource('replay-track').setData({
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: replayTrack.map(p => [p.lon, p.lat]),
        },
      })
    }

    // 回放标记球位置
    const progress = Math.max(0, Math.min(1, replayProgress || 0))
    const idx = Math.floor(progress * (replayTrack.length - 1))
    const clampedIdx = Math.min(idx, replayTrack.length - 1)
    const pos = replayTrack[clampedIdx]

    if (pos) {
      if (replayMarkerRef.current) {
        replayMarkerRef.current.setLngLat([pos.lon, pos.lat])
      } else {
        const el = document.createElement('div')
        el.className = 'replay-marker'
        el.innerHTML = createReplayMarkerSVG()
        replayMarkerRef.current = new maplibregl.Marker({ element: el })
          .setLngLat([pos.lon, pos.lat])
          .addTo(map)
      }
    }
  }, [replayTrack, replayProgress, retryKey, loadedRetryKey])

  // ─── ⑤ 编队队形可视化 ─────────────────────────────────────
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (!map.isStyleLoaded?.()) return

    if (!formations || formations.length === 0) {
      if (map.getSource('formation-links')) {
        map.getSource('formation-links').setData({
          type: 'FeatureCollection',
          features: [],
        })
      }
      if (map.getSource('formation-centers')) {
        map.getSource('formation-centers').setData({
          type: 'FeatureCollection',
          features: [],
        })
      }
      return
    }

    const linkFeatures = []
    const centerFeatures = []

    formations.forEach(formation => {
      const { formationId, members, centerLat, centerLon } = formation
      const color = getFormationColor(formationId)

      // 成员之间的连线（全连接）
      for (let i = 0; i < members.length; i++) {
        for (let j = i + 1; j < members.length; j++) {
          linkFeatures.push({
            type: 'Feature',
            geometry: {
              type: 'LineString',
              coordinates: [
                [members[i].lon, members[i].lat],
                [members[j].lon, members[j].lat],
              ],
            },
            properties: { color },
          })
        }
      }

      // 编队中心标记
      if (centerLat != null && centerLon != null) {
        centerFeatures.push({
          type: 'Feature',
          geometry: {
            type: 'Point',
            coordinates: [centerLon, centerLat],
          },
          properties: {
            color,
            label: `F${formationId}`,
          },
        })
      }
    })

    if (map.getSource('formation-links')) {
      map.getSource('formation-links').setData({
        type: 'FeatureCollection',
        features: linkFeatures,
      })
    }
    if (map.getSource('formation-centers')) {
      map.getSource('formation-centers').setData({
        type: 'FeatureCollection',
        features: centerFeatures,
      })
    }
  }, [formations, retryKey, loadedRetryKey])

  // ─── ⑥ TrackingPanel 集成叠加 ─────────────────────────────
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (!map.isStyleLoaded?.()) return

    if (!trackingOverlay) {
      // 清理所有 tracking overlay
      if (map.getSource('lost-drones')) {
        map.getSource('lost-drones').setData({
          type: 'FeatureCollection',
          features: [],
        })
      }
      if (map.getSource('search-guide')) {
        map.getSource('search-guide').setData({
          type: 'FeatureCollection',
          features: [],
        })
      }
      if (map.getSource('query-track')) {
        map.getSource('query-track').setData({
          type: 'FeatureCollection',
          features: [],
        })
      }
      // 停止脉冲动画
      if (pulseAnimRef.current) {
        cancelAnimationFrame(pulseAnimRef.current)
        pulseAnimRef.current = null
      }
      return
    }

    // 丢失无人机：红色脉冲 circle 标记
    if (trackingOverlay.lostDrones) {
      const features = trackingOverlay.lostDrones.map(d => ({
        type: 'Feature',
        geometry: {
          type: 'Point',
          coordinates: [d.lon, d.lat],
        },
        properties: { sysid: d.sysid },
      }))
      if (map.getSource('lost-drones')) {
        map.getSource('lost-drones').setData({
          type: 'FeatureCollection',
          features,
        })
      }

      // 启动脉冲动画（仅在 lostDrones 不为空时）
      if (features.length > 0 && !pulseAnimRef.current) {
        const startTime = Date.now()
        const animate = () => {
          const elapsed = (Date.now() - startTime) / 1000
          const pulse = 0.3 + 0.3 * Math.sin(elapsed * 3) // 0.0 ~ 0.6
          if (map.getLayer('lost-drones')) {
            map.setPaintProperty('lost-drones', 'circle-opacity', pulse)
            map.setPaintProperty('lost-drones', 'circle-stroke-opacity', pulse + 0.3)
          }
          pulseAnimRef.current = requestAnimationFrame(animate)
        }
        animate()
      }
    } else {
      // 没有 lostDrones 时停止脉冲动画
      if (pulseAnimRef.current) {
        cancelAnimationFrame(pulseAnimRef.current)
        pulseAnimRef.current = null
      }
    }

    // 搜索引导区域：半透明圆 fill
    if (trackingOverlay.searchGuide) {
      const { lat, lon, radius } = trackingOverlay.searchGuide
      const pts = []
      for (let i = 0; i <= 64; i++) {
        const a = (i / 64) * 2 * Math.PI
        const dLat = (radius * Math.cos(a)) / 111320
        const dLon = (radius * Math.sin(a)) / (111320 * Math.cos((lat * Math.PI) / 180))
        pts.push([lon + dLon, lat + dLat])
      }
      if (map.getSource('search-guide')) {
        map.getSource('search-guide').setData({
          type: 'FeatureCollection',
          features: [{
            type: 'Feature',
            geometry: { type: 'Polygon', coordinates: [pts] },
          }],
        })
      }
    } else if (map.getSource('search-guide')) {
      map.getSource('search-guide').setData({
        type: 'FeatureCollection',
        features: [],
      })
    }

    // 查询轨迹线：紫色
    if (trackingOverlay.queryTrack && trackingOverlay.queryTrack.length > 1) {
      if (map.getSource('query-track')) {
        map.getSource('query-track').setData({
          type: 'Feature',
          geometry: {
            type: 'LineString',
            coordinates: trackingOverlay.queryTrack.map(p => [p.lon, p.lat]),
          },
        })
      }
    } else if (map.getSource('query-track')) {
      map.getSource('query-track').setData({
        type: 'FeatureCollection',
        features: [],
      })
    }
  }, [trackingOverlay, retryKey, loadedRetryKey])

  // ─── 地图加载失败降级 UI ───────────────────────────────────
  if (mapError) {
    return (
      <div className="map-view map-error" ref={mapRef}>
        <div>
          <div style={{ fontSize: 28, opacity: .4, marginBottom: 8 }}>🗺️</div>
          <b style={{ color: 'var(--crit)', display: 'block', marginBottom: 6 }}>地图加载失败</b>
          <div style={{ fontSize: 11, color: 'var(--dim-2)', marginBottom: 14, wordBreak: 'break-word' }}>
            {mapError}
          </div>
          <button
            className="btn primary"
            onClick={() => {
              errorLoggedRef.current = false
              setMapError(null)
              setRetryKey((k) => k + 1)
            }}
          >
            ⟳ 重试
          </button>
        </div>
      </div>
    )
  }

  return (
    <div ref={mapRef} className="map-view">
      {/* 轨迹着色模式切换按钮组（仅有多机轨迹时显示） */}
      {multiTracks && Object.keys(multiTracks).length > 0 && (
        <div
          className="track-color-mode-btns"
          style={{
            position: 'absolute',
            top: 10,
            right: 10,
            zIndex: 10,
            display: 'flex',
            gap: 4,
            pointerEvents: 'none',
          }}
        >
          {['single', 'time', 'altitude'].map(mode => (
            <button
              key={mode}
              className={`btn small ${colorMode === mode ? 'primary' : ''}`}
              style={{
                pointerEvents: 'auto',
                padding: '4px 8px',
                fontSize: 11,
                opacity: colorMode === mode ? 1 : 0.6,
              }}
              onClick={() => setColorMode(mode)}
            >
              {mode === 'single' ? '单色' : mode === 'time' ? '时间' : '高度'}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
