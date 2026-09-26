import React, { useEffect, useRef, useState, useMemo } from 'react'
import { loadThree, geoTo3D, COLOR, createOrbitState, applyOrbit, buildTerrain } from './Scene3DUtils.js'
import { api } from '../api.js'

export default function Trajectory3DPanel({
  track = [],
  telemetry = null,
  selected = null,
  missionDraft = [],
}) {
  const containerRef = useRef(null)
  const [status, setStatus] = useState('loading')
  const [hint, setHint] = useState('')
  const [showHistory, setShowHistory] = useState(true)
  const [showPredict, setShowPredict] = useState(true)
  const [showWaypoints, setShowWaypoints] = useState(true)
  const [showTerrain, setShowTerrain] = useState(true)
  const [predictSteps, setPredictSteps] = useState(15)
  const sceneObj = useRef(null)
  const toggleRef = useRef({ showHistory: true, showPredict: true, showWaypoints: true, showTerrain: true, predictSteps: 15 })

  useEffect(() => {
    toggleRef.current = { showHistory, showPredict, showWaypoints, showTerrain, predictSteps }
  }, [showHistory, showPredict, showWaypoints, showTerrain, predictSteps])

  useEffect(() => {
    let renderer, scene, camera, animationId, disposed = false
    let dom, onDown, onMove, onUp, onWheel, ro
    const cam = createOrbitState()

    loadThree()
      .then((THREE) => {
        if (disposed) return
        const el = containerRef.current
        if (!el) return

        scene = new THREE.Scene()
        scene.background = new THREE.Color(0x0b0e14)
        scene.fog = new THREE.Fog(0x0b0e14, 200, 500)

        const w = el.clientWidth || 600
        const h = el.clientHeight || 400
        camera = new THREE.PerspectiveCamera(55, w / h, 0.1, 2000)
        applyOrbit(camera, cam)

        renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
        renderer.setSize(w, h)
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
        el.appendChild(renderer.domElement)

        scene.add(new THREE.AmbientLight(0x404858, 1.1))
        const dir = new THREE.DirectionalLight(0xffffff, 0.8)
        dir.position.set(40, 80, 30)
        scene.add(dir)

        const terrainGroup = buildTerrain(THREE)
        terrainGroup.name = 'terrain-group'
        scene.add(terrainGroup)

        dom = renderer.domElement
        let dragging = false, lastX = 0, lastY = 0
        onDown = (e) => { dragging = true; lastX = e.clientX; lastY = e.clientY }
        onMove = (e) => {
          if (!dragging) return
          cam.theta -= (e.clientX - lastX) * 0.005
          cam.phi -= (e.clientY - lastY) * 0.005
          cam.phi = Math.max(0.08, Math.min(Math.PI - 0.08, cam.phi))
          lastX = e.clientX; lastY = e.clientY
          applyOrbit(camera, cam)
        }
        onUp = () => { dragging = false }
        onWheel = (e) => {
          e.preventDefault()
          cam.radius *= 1 + e.deltaY * 0.001
          cam.radius = Math.max(8, Math.min(500, cam.radius))
          applyOrbit(camera, cam)
        }
        dom.addEventListener('mousedown', onDown)
        window.addEventListener('mousemove', onMove)
        window.addEventListener('mouseup', onUp)
        dom.addEventListener('wheel', onWheel, { passive: false })

        const onResize = () => {
          if (!el) return
          const nw = el.clientWidth, nh = el.clientHeight
          if (nw === 0 || nh === 0) return
          camera.aspect = nw / nh
          camera.updateProjectionMatrix()
          renderer.setSize(nw, nh)
        }
        ro = new ResizeObserver(onResize)
        ro.observe(el)

        const animate = () => {
          animationId = requestAnimationFrame(animate)
          renderer.render(scene, camera)
        }
        animate()

        sceneObj.current = { THREE, scene, camera, renderer, cam }
        setStatus('ready')
      })
      .catch((e) => {
        setStatus('error')
        setHint(e.message || 'Three.js 加载失败')
      })

    return () => {
      disposed = true
      if (animationId) cancelAnimationFrame(animationId)
      if (dom) {
        dom.removeEventListener('mousedown', onDown)
        dom.removeEventListener('wheel', onWheel)
      }
      if (onMove) window.removeEventListener('mousemove', onMove)
      if (onUp) window.removeEventListener('mouseup', onUp)
      if (ro) ro.disconnect()
      if (renderer) {
        renderer.dispose()
        if (renderer.domElement && renderer.domElement.parentNode) {
          renderer.domElement.parentNode.removeChild(renderer.domElement)
        }
      }
    }
  }, [])

  useEffect(() => {
    const obj = sceneObj.current
    if (!obj || status !== 'ready') return
    const { THREE, scene, cam } = obj

    const old = scene.getObjectByName('traj-enh-group')
    if (old) {
      scene.remove(old)
      old.traverse((c) => {
        if (c.geometry) c.geometry.dispose()
        if (c.material) c.material.dispose()
      })
    }

    const t = toggleRef.current
    const group = new THREE.Group()
    group.name = 'traj-enh-group'

    if (t.showTerrain) {
      const terrain = scene.getObjectByName('terrain-group')
      if (!terrain) {
        const tg = buildTerrain(THREE)
        tg.name = 'terrain-group'
        scene.add(tg)
      }
    } else {
      const terrain = scene.getObjectByName('terrain-group')
      if (terrain) scene.remove(terrain)
    }

    if (!track || track.length < 2) {
      scene.add(group)
      return
    }

    const pts = track.map((p) => {
      const v = geoTo3D(p.lat, p.lon, p.alt)
      return new THREE.Vector3(v.x, v.y + 2, v.z)
    })

    if (t.showHistory) {
      const geo = new THREE.BufferGeometry().setFromPoints(pts)
      const colors = new Float32Array(pts.length * 3)
      const c1 = new THREE.Color(COLOR.cyan)
      const c2 = new THREE.Color(COLOR.gold)
      for (let i = 0; i < pts.length; i++) {
        const r = i / (pts.length - 1)
        const c = c1.clone().lerp(c2, r)
        colors[i * 3] = c.r
        colors[i * 3 + 1] = c.g
        colors[i * 3 + 2] = c.b
      }
      geo.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3))
      group.add(new THREE.Line(geo, new THREE.LineBasicMaterial({ vertexColors: true, linewidth: 2 })))
    }

    if (t.showPredict) {
      const last = track[track.length - 1]
      const prev = track[track.length - 2]
      if (last && prev) {
        const dLat = last.lat - prev.lat
        const dLon = last.lon - prev.lon
        const dAlt = (last.alt || 0) - (prev.alt || 0)
        const predPts = []
        const steps = t.predictSteps
        for (let i = 1; i <= steps; i++) {
          const v = geoTo3D(
            last.lat + dLat * i * 1.5,
            last.lon + dLon * i * 1.5,
            (last.alt || 0) + dAlt * i * 1.5
          )
          predPts.push(new THREE.Vector3(v.x, v.y + 2, v.z))
        }
        const pGeo = new THREE.BufferGeometry().setFromPoints(predPts)
        const pMat = new THREE.LineDashedMaterial({
          color: COLOR.warn,
          dashSize: 1.5,
          gapSize: 1.2,
          transparent: true,
          opacity: 0.5,
        })
        const pLine = new THREE.Line(pGeo, pMat)
        pLine.computeLineDistances()
        group.add(pLine)

        const endV = predPts[predPts.length - 1]
        const endMarker = new THREE.Mesh(
          new THREE.SphereGeometry(1.5, 12, 12),
          new THREE.MeshStandardMaterial({
            color: COLOR.warn,
            emissive: 0x442200,
            transparent: true,
            opacity: 0.7,
          })
        )
        endMarker.position.copy(endV)
        group.add(endMarker)
      }
    }

    if (t.showWaypoints) {
      const sphereGeo = new THREE.SphereGeometry(1.2, 14, 14)
      missionDraft.forEach((w) => {
        if (w.lat == null) return
        const v = geoTo3D(w.lat, w.lon, w.alt || 0)
        const mat = new THREE.MeshStandardMaterial({
          color: COLOR.ok,
          emissive: 0x004422,
          transparent: true,
          opacity: 0.9,
        })
        const s = new THREE.Mesh(sphereGeo, mat)
        s.position.set(v.x, v.y + 2, v.z)
        group.add(s)
      })
    }

    if (track.length >= 2) {
      const mkEnd = (pt, color, emissive) => {
        const v = geoTo3D(pt.lat, pt.lon, pt.alt)
        const m = new THREE.Mesh(
          new THREE.SphereGeometry(1.8, 16, 16),
          new THREE.MeshStandardMaterial({ color, emissive })
        )
        m.position.set(v.x, v.y + 2, v.z)
        group.add(m)
      }
      mkEnd(track[0], COLOR.ok, 0x004433)
      mkEnd(track[track.length - 1], COLOR.crit, 0x440000)
    }

    scene.add(group)

    const box = new THREE.Box3().setFromPoints(pts)
    const center = new THREE.Vector3()
    box.getCenter(center)
    cam.target.x = center.x
    cam.target.y = center.y
    cam.target.z = center.z
    const size = new THREE.Vector3()
    box.getSize(size)
    cam.radius = Math.max(60, Math.max(size.x, size.z) * 2.2 + 40)
    applyOrbit(obj.camera, cam)
  }, [track, missionDraft, status, showHistory, showPredict, showWaypoints, showTerrain, predictSteps])

  return (
    <div style={{ padding: 16, height: '100%', overflow: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 style={{ margin: 0, fontSize: 18, color: 'var(--cyan)' }}>3D 轨迹增强</h2>
        <div style={{ display: 'flex', gap: 6 }}>
          <button
            className={`btn ${showHistory ? 'primary' : ''}`}
            style={{ padding: '3px 10px', fontSize: 10 }}
            onClick={() => setShowHistory((v) => !v)}
          >
            历史轨迹
          </button>
          <button
            className={`btn ${showPredict ? 'primary' : ''}`}
            style={{ padding: '3px 10px', fontSize: 10 }}
            onClick={() => setShowPredict((v) => !v)}
          >
            预测轨迹
          </button>
          <button
            className={`btn ${showWaypoints ? 'primary' : ''}`}
            style={{ padding: '3px 10px', fontSize: 10 }}
            onClick={() => setShowWaypoints((v) => !v)}
          >
            航点标记
          </button>
          <button
            className={`btn ${showTerrain ? 'primary' : ''}`}
            style={{ padding: '3px 10px', fontSize: 10 }}
            onClick={() => setShowTerrain((v) => !v)}
          >
            地形高程
          </button>
        </div>
      </div>

      <div ref={containerRef} style={{ width: '100%', height: 400, borderRadius: 8, border: '1px solid var(--border)', background: '#0b0e14' }} />

      {status === 'loading' && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>加载 3D 轨迹引擎…</div>
      )}
      {status === 'error' && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--crit)' }}>
          ⚠ 3D 引擎加载失败
          <div style={{ fontSize: 11, color: 'var(--dim)', marginTop: 4 }}>{hint}</div>
        </div>
      )}

      {status === 'ready' && (
        <div style={{ marginTop: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ fontSize: 11, color: 'var(--dim)' }}>预测步数</span>
          <input
            type="range"
            min={5}
            max={30}
            value={predictSteps}
            onChange={(e) => setPredictSteps(Number(e.target.value))}
            style={{ flex: 1, maxWidth: 200 }}
          />
          <span className="chip mono" style={{ fontSize: 10 }}>{predictSteps} 步</span>
        </div>
      )}

      {status === 'ready' && track.length === 0 && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>暂无轨迹数据</div>
      )}

      {status === 'ready' && track.length > 0 && (
        <div style={{ marginTop: 8, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <span className="chip mono" style={{ fontSize: 10 }}>
            {track.length} 轨迹点
          </span>
          {selected && (
            <span className="chip mono" style={{ fontSize: 10 }}>
              无人机 #{selected.sysid}
            </span>
          )}
          {telemetry && telemetry.alt != null && (
            <span className="chip mono" style={{ fontSize: 10 }}>
              当前高度 {telemetry.alt.toFixed(0)}m
            </span>
          )}
        </div>
      )}
    </div>
  )
}