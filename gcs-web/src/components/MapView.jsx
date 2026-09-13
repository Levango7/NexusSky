import React, { useEffect, useRef } from 'react'
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

export default function MapView({ telemetry, track, missionDraft, onMapClick, selected }) {
  const mapRef = useRef(null)
  const mapInstance = useRef(null)
  const markerRef = useRef(null)
  const followedRef = useRef(false)
  // keep the latest click handler in a ref so the map listener (bound once)
  // always calls the freshest closure
  const clickRef = useRef(null)
  clickRef.current = onMapClick

  useEffect(() => {
    const map = new maplibregl.Map({
      container: mapRef.current,
      style: MAP_STYLE,
      center: [DRONE_HOME.lon, DRONE_HOME.lat],
      zoom: 15,
      attributionControl: false,
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
    canvas.addEventListener('keydown', () => {})
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
    })

    return () => {
      map.remove()
      mapInstance.current = null
    }
  }, [])

  // 实时位置更新 + 任务草稿航线绘制
  useEffect(() => {
    const map = mapInstance.current
    const marker = markerRef.current
    if (!map || !marker || !telemetry || telemetry.lat == null) return

    marker.setLngLat([telemetry.lon, telemetry.lat])

    // 任务草稿航线：地图样式未加载完成时跳过本轮（下次状态变化重绘）
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
  }, [telemetry, missionDraft])

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
  }, [track])

  return <div ref={mapRef} className="map-view" />
}
