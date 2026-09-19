import React, { useEffect, useRef, useState, useMemo } from 'react'
import { loadThree, geoTo3D } from './Scene3D.jsx'

/**
 * Trajectory3D —— 3D 轨迹回放组件
 *
 * 功能：
 *  - 历史轨迹：渐变色线条（cyan → gold）
 *  - 预测轨迹：虚线 + 半透明
 *  - 航点标记：3D 球体（mission 航点 / 轨迹采样点）
 *  - 时间轴拖拽回放：拖动滑块沿轨迹移动高亮标记球
 *
 * 复用 Scene3D 导出的 loadThree / geoTo3D，避免重复加载 Three.js。
 */

const COLOR = {
  cyan: 0x00d4ff,
  ok: 0x2de2a5,
  warn: 0xffb224,
  crit: 0xff5d5d,
  gold: 0xffc857,
}

export default function Trajectory3D({
  track = [],
  predictedTrack = null,
  missionDraft = [],
  telemetry = null,
  selected = null,
}) {
  const containerRef = useRef(null)
  const [status, setStatus] = useState('loading')
  const [hint, setHint] = useState('')
  const [progress, setProgress] = useState(1) // 0..1 时间轴位置
  const sceneObj = useRef(null)
  const playheadRef = useRef(null) // { mesh, points, tsArr }

  // 时间戳数组（用于回放定位）
  const tsArr = useMemo(() => {
    if (!track || track.length === 0) return []
    return track.map((p) => p.ts || 0)
  }, [track])

  // 当前回放点信息
  const playheadInfo = useMemo(() => {
    if (!track || track.length === 0) return null
    const idx = Math.min(Math.floor(progress * (track.length - 1)), track.length - 1)
    const p = track[idx]
    return { idx, total: track.length, point: p }
  }, [track, progress])

  useEffect(() => {
    let renderer, scene, camera, animationId, disposed = false
    // 提升事件监听器 / ResizeObserver 引用到 useEffect 顶层，以便 cleanup 能访问。
    // 经验来源：2026-09-16-react-side-effect-cleanup-timeout-ref-callback-leak
    let dom, onDown, onMove, onUp, onWheel, ro
    const cam = { theta: Math.PI / 3, phi: Math.PI / 2.5, radius: 120, target: { x: 0, y: 8, z: 0 } }

    function applyCam() {
      const sinPhi = Math.sin(cam.phi)
      camera.position.x = cam.target.x + cam.radius * sinPhi * Math.sin(cam.theta)
      camera.position.y = cam.target.y + cam.radius * Math.cos(cam.phi)
      camera.position.z = cam.target.z + cam.radius * sinPhi * Math.cos(cam.theta)
      camera.lookAt(cam.target.x, cam.target.y, cam.target.z)
    }

    loadThree()
      .then((THREE) => {
        if (disposed) return
        const el = containerRef.current
        if (!el) return

        scene = new THREE.Scene()
        scene.background = new THREE.Color(0x0b0e14)
        scene.fog = new THREE.Fog(0x0b0e14, 150, 400)

        const w = el.clientWidth || 600
        const h = el.clientHeight || 400
        camera = new THREE.PerspectiveCamera(55, w / h, 0.1, 2000)
        applyCam()

        renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
        renderer.setSize(w, h)
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
        el.appendChild(renderer.domElement)

        // 灯光
        scene.add(new THREE.AmbientLight(0x404858, 1.1))
        const dir = new THREE.DirectionalLight(0xffffff, 0.8)
        dir.position.set(40, 80, 30)
        scene.add(dir)

        // 地面网格
        const grid = new THREE.GridHelper(300, 30, 0x00d4ff, 0x232c40)
        grid.material.opacity = 0.25
        grid.material.transparent = true
        scene.add(grid)

        // 回放标记球（高亮）
        const playMesh = new THREE.Mesh(
          new THREE.SphereGeometry(2, 20, 20),
          new THREE.MeshStandardMaterial({
            color: COLOR.crit,
            emissive: 0x660000,
            transparent: true,
            opacity: 0.95,
          })
        )
        playMesh.visible = false
        scene.add(playMesh)
        playheadRef.current = { mesh: playMesh }

        // 相机控制（简版：左键旋转 / 滚轮缩放）
        dom = renderer.domElement
        let dragging = false, lastX = 0, lastY = 0
        onDown = (e) => { dragging = true; lastX = e.clientX; lastY = e.clientY }
        onMove = (e) => {
          if (!dragging) return
          cam.theta -= (e.clientX - lastX) * 0.005
          cam.phi -= (e.clientY - lastY) * 0.005
          cam.phi = Math.max(0.08, Math.min(Math.PI - 0.08, cam.phi))
          lastX = e.clientX; lastY = e.clientY
          applyCam()
        }
        onUp = () => { dragging = false }
        onWheel = (e) => {
          e.preventDefault()
          cam.radius *= 1 + e.deltaY * 0.001
          cam.radius = Math.max(8, Math.min(500, cam.radius))
          applyCam()
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

        sceneObj.current = { THREE, scene, camera, renderer, cam, applyCam }
        setStatus('ready')
      })
      .catch((e) => {
        setStatus('error')
        setHint(e.message || 'Three.js 加载失败')
      })

    return () => {
      disposed = true
      if (animationId) cancelAnimationFrame(animationId)
      // 清理事件监听器（防止组件卸载后仍持有 DOM/window 引用）
      if (dom) {
        dom.removeEventListener('mousedown', onDown)
        dom.removeEventListener('wheel', onWheel)
      }
      if (onMove) window.removeEventListener('mousemove', onMove)
      if (onUp) window.removeEventListener('mouseup', onUp)
      // 清理 ResizeObserver
      if (ro) ro.disconnect()
      // 清理 renderer
      if (renderer) {
        renderer.dispose()
        if (renderer.domElement && renderer.domElement.parentNode) {
          renderer.domElement.parentNode.removeChild(renderer.domElement)
        }
      }
    }
  }, [])

  // ---- 绘制轨迹 + 航点 ----
  useEffect(() => {
    const obj = sceneObj.current
    if (!obj || status !== 'ready') return
    const { THREE, scene, cam, applyCam } = obj

    // 清除上一轮的轨迹组
    const old = scene.getObjectByName('traj-group')
    if (old) {
      scene.remove(old)
      old.traverse((c) => {
        if (c.geometry) c.geometry.dispose()
        if (c.material) c.material.dispose()
      })
    }

    if (!track || track.length < 2) return

    const group = new THREE.Group()
    group.name = 'traj-group'

    const pts = track.map((p) => {
      const v = geoTo3D(p.lat, p.lon, p.alt)
      return new THREE.Vector3(v.x, v.y + 2, v.z)
    })

    // 历史轨迹：渐变色线
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
    group.add(new THREE.Line(geo, new THREE.LineBasicMaterial({ vertexColors: true })))

    // 预测轨迹：虚线半透明
    const pred = predictedTrack && predictedTrack.length >= 2 ? predictedTrack : null
    if (!pred) {
      // 简单线性外推
      const last = track[track.length - 1]
      const prev = track[track.length - 2]
      if (last && prev) {
        const dLat = last.lat - prev.lat
        const dLon = last.lon - prev.lon
        const dAlt = (last.alt || 0) - (prev.alt || 0)
        const predPts = []
        for (let i = 1; i <= 10; i++) {
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
          opacity: 0.45,
        })
        const pLine = new THREE.Line(pGeo, pMat)
        pLine.computeLineDistances()
        group.add(pLine)
      }
    } else {
      const predPts = pred.map((p) => {
        const v = geoTo3D(p.lat, p.lon, p.alt)
        return new THREE.Vector3(v.x, v.y + 2, v.z)
      })
      const pGeo = new THREE.BufferGeometry().setFromPoints(predPts)
      const pMat = new THREE.LineDashedMaterial({
        color: COLOR.warn,
        dashSize: 1.5,
        gapSize: 1.2,
        transparent: true,
        opacity: 0.45,
      })
      const pLine = new THREE.Line(pGeo, pMat)
      pLine.computeLineDistances()
      group.add(pLine)
    }

    // 航点标记球体（mission 航点优先，否则取轨迹采样点）
    const waypoints = missionDraft && missionDraft.length > 0 ? missionDraft : []
    const sphereGeo = new THREE.SphereGeometry(1.2, 14, 14)
    waypoints.forEach((w, i) => {
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

    // 轨迹起点 / 终点标记
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

    scene.add(group)

    // 自动聚焦轨迹中心
    const box = new THREE.Box3().setFromPoints(pts)
    const center = new THREE.Vector3()
    box.getCenter(center)
    cam.target.x = center.x
    cam.target.y = center.y
    cam.target.z = center.z
    const size = new THREE.Vector3()
    box.getSize(size)
    cam.radius = Math.max(60, Math.max(size.x, size.z) * 2.2 + 40)
    applyCam()
  }, [track, predictedTrack, missionDraft, status])

  // ---- 回放标记球位置 ----
  useEffect(() => {
    const obj = sceneObj.current
    if (!obj || status !== 'ready') return
    const ph = playheadRef.current
    if (!ph || !ph.mesh) return
    if (!track || track.length === 0) {
      ph.mesh.visible = false
      return
    }
    const idx = Math.min(Math.floor(progress * (track.length - 1)), track.length - 1)
    const p = track[idx]
    if (!p || p.lat == null) {
      ph.mesh.visible = false
      return
    }
    const v = geoTo3D(p.lat, p.lon, p.alt)
    ph.mesh.position.set(v.x, v.y + 2, v.z)
    ph.mesh.visible = true
  }, [progress, track, status])

  const fmtTs = (ts) => {
    if (!ts) return '--:--:--'
    const d = new Date(ts)
    return d.toLocaleTimeString('zh-CN', { hour12: false })
  }

  return (
    <div className="traj3d-wrap">
      <div className="traj3d-head">
        <h3>3D 轨迹回放</h3>
        <span className="chip mono" style={{ fontSize: 10 }}>
          {track.length} 点
        </span>
      </div>
      <div ref={containerRef} className="traj3d-canvas" />
      {status === 'loading' && (
        <div className="scene3d-overlay">
          <div className="scene3d-spinner" />
          <span>加载 3D 轨迹引擎…</span>
        </div>
      )}
      {status === 'error' && (
        <div className="scene3d-overlay err">
          <span>⚠ 3D 引擎加载失败</span>
          <span className="dim" style={{ fontSize: 11 }}>{hint}</span>
        </div>
      )}
      {status === 'ready' && track.length > 0 && (
        <div className="traj3d-timeline">
          <div className="traj3d-ts">
            <span className="dim">起点</span>
            <b className="mono">{fmtTs(track[0].ts)}</b>
            <span className="dim" style={{ margin: '0 6px' }}>→</span>
            <b className="mono">{fmtTs(track[track.length - 1].ts)}</b>
            <span className="dim">终点</span>
          </div>
          <input
            type="range"
            min={0}
            max={1000}
            value={Math.round(progress * 1000)}
            onChange={(e) => setProgress(Number(e.target.value) / 1000)}
            className="traj3d-slider"
          />
          <div className="traj3d-playhead">
            {playheadInfo && (
              <>
                <span className="chip mono">
                  #{playheadInfo.idx + 1}/{playheadInfo.total}
                </span>
                {playheadInfo.point && (
                  <span className="chip mono" style={{ fontSize: 10 }}>
                    {playheadInfo.point.lat?.toFixed(5)}, {playheadInfo.point.lon?.toFixed(5)} ·{' '}
                    {(playheadInfo.point.alt || 0).toFixed(0)}m
                  </span>
                )}
                <span className="chip mono" style={{ fontSize: 10 }}>
                  {fmtTs(playheadInfo.point?.ts)}
                </span>
              </>
            )}
          </div>
        </div>
      )}
      {status === 'ready' && track.length === 0 && (
        <div className="traj3d-empty">暂无轨迹数据</div>
      )}
    </div>
  )
}