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

export default function MapView({ telemetry, track, missionDraft, onMapClick, selected, orbitOverlay }) {
  const mapRef = useRef(null)
  const mapInstance = useRef(null)
  const markerRef = useRef(null)
  const followedRef = useRef(false)
  const [mapError, setMapError] = useState(null)
  const [retryKey, setRetryKey] = useState(0)
  const [loadedRetryKey, setLoadedRetryKey] = useState(null)
  // 用 ref 记录是否已记录过地图错误，避免闭包陷阱：
  // state 的闭包值在 effect 创建时固定，直接读 state 永远是旧值
  const errorLoggedRef = useRef(false)
  // keep the latest click handler in a ref so the map listener (bound once)
  // always calls the freshest closure
  const clickRef = useRef(null)
  clickRef.current = onMapClick

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
    // 监听地图加载错误，按严重程度分级处理：
    // - 瓦片级错误（单个瓦片 404/超时）：地图仍可正常使用，仅 console.warn，不触发降级
    // - 其他错误（样式加载失败、WebGL 上下文异常等关键资源错误）：触发降级 UI
    map.on('error', (e) => {
      const msg = (e.error && e.error.message) || ''
      // MapLibre 对单个瓦片错误也会触发 error 事件，不能因个别瓦片失败整图降级
      if (msg.includes('tile') || msg.includes('Tile')) {
        console.warn('[MapView] 瓦片加载失败（已忽略，不影响地图使用）:', msg)
        return
      }
      // 非瓦片错误视为关键错误：每次初始化仅处理首个（去重），显示降级 UI
      if (!errorLoggedRef.current) {
        errorLoggedRef.current = true
        setMapError(msg || '地图加载错误')
      }
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'bottom-right')
    map.addControl(new maplibregl.ScaleControl(), 'bottom-left')
    mapInstance.current = map

    // Shift+click on the map adds a waypoint (at the current cruise altitude)
    // to the mission draft - the most common GCS planning gesture.
    map.on('click', (e) => {
      if (e.originalEvent && e.originalEvent.shiftKey && clickRef.current) {
        clickRef.current({
          lat: Number(e.lngLat.lat.toFixed(6)),
          lon: Number(e.lngLat.lng.toFixed(6)),
        })
      }
    })
    // Crosshair cursor with Shift held - hint for the planning gesture
    const canvas = map.getCanvas()

    map.on('mousemove', (e) => {
      const shift = e.originalEvent && e.originalEvent.shiftKey
      canvas.style.cursor = shift ? 'crosshair' : ''
    })

    const el = document.createElement('div')
    el.className = 'drone-marker'
    el.innerHTML =
      '<svg width="34" height="34" viewBox="0 0 34 34"><circle cx="17" cy="17" r="16" fill="rgba(0,200,255,.15)" stroke="#00c8ff" stroke-width="1.5"/><path d="M17 6 L17 28 M8 15 L26 15" stroke="#00c8ff" stroke-width="3" stroke-linecap="round"/></svg>'
    markerRef.current = new maplibregl.Marker({ element: el })
      .setLngLat([DRONE_HOME.lon, DRONE_HOME.lat])
      .addTo(map)

    // 图层只能在样式加载完成后添加，否则 MapLibre 抛 "Style is not done loading"
    map.on('load', () => {
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
      // Orbit ring (D2): the active auto-redirect circle around a target
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
      // 样式异步加载完成后重绘，避免依赖数据不变时新地图保持空白。
      setLoadedRetryKey(retryKey)
    })

    return () => {
      if (map) map.remove()
      mapInstance.current = null
    }
  }, [retryKey]) // retryKey 变化时重新初始化地图

  // 实时位置更新 + 任务草稿航线绘制
  useEffect(() => {
    const map = mapInstance.current
    const marker = markerRef.current
    if (!map || !marker || !telemetry || telemetry.lat == null) return

    marker.setLngLat([telemetry.lon, telemetry.lat])

    // 任务草稿航线：样式未加载完成时跳过，load 完成后会再次重绘
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

  // 轨迹重绘
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

  // 环绕圈重绘（D2：视觉面板提交任务时传入 center/radius）
  useEffect(() => {
    const map = mapInstance.current
    if (!map) return
    if (map.isStyleLoaded?.() && map.getSource('orbit-ring')) {
      const features = []
      if (orbitOverlay?.center && orbitOverlay.radiusM > 0) {
        // 64 段折线近似圆（MapLibre 无原生 circle geometry）
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

  // 地图加载失败降级 UI：显示错误信息和重试按钮
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

  return <div ref={mapRef} className="map-view" />
}
