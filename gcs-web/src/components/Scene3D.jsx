import React, { useEffect, useRef, useState } from 'react'
import {
  REF,
  SCALE,
  M_PER_DEG_LAT,
  M_PER_DEG_LON,
  COLOR,
  geoTo3D,
  loadThree,
  createOrbitState,
  applyOrbit,
  buildDroneModel,
  buildTerrain,
} from './Scene3DUtils.js'

/**
 * Scene3D —— Three.js 3D 可视化主场景
 *
 * 功能：
 *  - 3D 地形网格 + 无人机模型
 *  - 经纬度 → 3D 坐标转换，实时展示无人机位置
 *  - 历史航迹 + 预测轨迹 3D 线绘制
 *  - 编队队形几何 + 成员位置展示
 *  - 鼠标旋转 / 滚轮缩放 / 右键平移相机控制
 *  - 从 WebSocket 实时更新（经 props.telemetry / formations 传入）
 *
 * 常量与辅助函数已提取至 Scene3DUtils.js。
 * Three.js 通过 CDN 引入，不修改 package.json（避免 BOM 字节序标记问题）。
 * 经验来源：2026-09-16-package-json-bom-breaks-vite-build
 */

export { geoTo3D, loadThree } from './Scene3DUtils.js'

export default function Scene3D({
  drones = [],
  telemetry = null,
  track = [],
  formations = [],
  selected = null,
  terrainData = null,
}) {
  const containerRef = useRef(null)
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [hint, setHint] = useState('')
  // 用 ref 存放可变对象，避免 re-render
  const sceneObj = useRef(null)

  useEffect(() => {
    let renderer, scene, camera, animationId, disposed = false
    // 提升事件监听器 / ResizeObserver 引用到 useEffect 顶层，以便 cleanup 能访问。
    // 经验来源：2026-09-16-react-side-effect-cleanup-timeout-ref-callback-leak
    let dom, onDown, onMove, onUp, onWheel, onCtx, ro
    const cam = createOrbitState()
    const droneModels = new Map() // sysid -> { group, rotors, label }
    const trackLineRef = { current: null }
    const predLineRef = { current: null }
    const formationGroupRef = { current: null }

    loadThree()
      .then((THREE) => {
        if (disposed) return
        const el = containerRef.current
        if (!el) return

        // ---- 场景 ----
        scene = new THREE.Scene()
        scene.background = new THREE.Color(0x0b0e14)
        scene.fog = new THREE.Fog(0x0b0e14, 200, 600)

        const w = el.clientWidth || 800
        const h = el.clientHeight || 600
        camera = new THREE.PerspectiveCamera(55, w / h, 0.1, 2000)
        applyOrbit(camera, cam)

        renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
        renderer.setSize(w, h)
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
        el.appendChild(renderer.domElement)

        // ---- 灯光 ----
        scene.add(new THREE.AmbientLight(0x404858, 1.2))
        const dir = new THREE.DirectionalLight(0xffffff, 0.9)
        dir.position.set(50, 100, 30)
        scene.add(dir)
        const dir2 = new THREE.DirectionalLight(0x00d4ff, 0.4)
        dir2.position.set(-40, 60, -30)
        scene.add(dir2)

        // ---- 地形 ----
        scene.add(buildTerrain(THREE))

        // ---- 坐标轴指示（home 点）----
        const axes = new THREE.AxesHelper(8)
        axes.position.set(0, 0, 0)
        scene.add(axes)

        // home 点标记
        const homeMat = new THREE.MeshBasicMaterial({ color: COLOR.cyan })
        const home = new THREE.Mesh(new THREE.SphereGeometry(1.2, 16, 16), homeMat)
        scene.add(home)

        // ---- 轨迹线容器 ----
        // 历史轨迹（渐变色）
        // 预测轨迹（虚线半透明）

        // ---- 编队组容器 ----
        formationGroupRef.current = new THREE.Group()
        scene.add(formationGroupRef.current)

        // ---- 相机控制 ----
        dom = renderer.domElement
        let dragging = null // 'rotate' | 'pan'
        let lastX = 0, lastY = 0

        onDown = (e) => {
          if (e.button === 2) dragging = 'pan'
          else dragging = 'rotate'
          lastX = e.clientX
          lastY = e.clientY
        }
        onMove = (e) => {
          if (!dragging) return
          const dx = e.clientX - lastX
          const dy = e.clientY - lastY
          lastX = e.clientX
          lastY = e.clientY
          if (dragging === 'rotate') {
            cam.theta -= dx * 0.005
            cam.phi -= dy * 0.005
            cam.phi = Math.max(0.08, Math.min(Math.PI - 0.08, cam.phi))
          } else {
            const scale = cam.radius * 0.0015
            cam.target.x -= dx * scale * Math.cos(cam.theta) + dy * scale * Math.sin(cam.theta) * 0
            cam.target.z += dx * scale * Math.sin(cam.theta) - dy * scale * Math.cos(cam.theta) * 0
            // 简化平移：沿屏幕方向
            cam.target.x -= dx * scale * Math.sin(cam.theta)
            cam.target.z -= dx * scale * Math.cos(cam.theta)
            cam.target.y += dy * scale
          }
          applyOrbit(camera, cam)
        }
        onUp = () => { dragging = null }
        onWheel = (e) => {
          e.preventDefault()
          cam.radius *= 1 + e.deltaY * 0.001
          cam.radius = Math.max(10, Math.min(800, cam.radius))
          applyOrbit(camera, cam)
        }
        onCtx = (e) => e.preventDefault()
        dom.addEventListener('mousedown', onDown)
        window.addEventListener('mousemove', onMove)
        window.addEventListener('mouseup', onUp)
        dom.addEventListener('wheel', onWheel, { passive: false })
        dom.addEventListener('contextmenu', onCtx)

        // ---- 响应式 ----
        const onResize = () => {
          if (!el) return
          const nw = el.clientWidth
          const nh = el.clientHeight
          if (nw === 0 || nh === 0) return
          camera.aspect = nw / nh
          camera.updateProjectionMatrix()
          renderer.setSize(nw, nh)
        }
        ro = new ResizeObserver(onResize)
        ro.observe(el)

        // ---- 渲染循环 ----
        let t0 = 0
        const animate = (t) => {
          animationId = requestAnimationFrame(animate)
          // 旋翼旋转动画
          droneModels.forEach((m) => {
            if (m.rotors) m.rotors.forEach((r, i) => (r.rotation.y += (i % 2 ? 0.4 : -0.4)))
          })
          renderer.render(scene, camera)
        }
        animate(0)

        sceneObj.current = {
          THREE,
          scene,
          camera,
          renderer,
          cam,
          droneModels,
          trackLineRef,
          predLineRef,
          formationGroupRef,
        }
        setStatus('ready')
        setHint('')
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
        dom.removeEventListener('contextmenu', onCtx)
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

  // ---- 更新无人机模型（drones + telemetry）----
  useEffect(() => {
    const obj = sceneObj.current
    if (!obj || status !== 'ready') return
    const { THREE, scene, droneModels } = obj

    // 选中无人机优先用 telemetry 实时位置
    const liveMap = new Map()
    drones.forEach((d) => {
      const t = d.sysid === (selected && selected.sysid) && telemetry ? telemetry : d
      if (t && t.lat != null && t.lon != null) {
        liveMap.set(d.sysid, {
          lat: t.lat,
          lon: t.lon,
          alt: t.alt ?? t.relativeAlt ?? 0,
          heading: t.heading ?? 0,
          online: d.online,
        })
      }
    })

    // 移除消失的
    droneModels.forEach((m, sysid) => {
      if (!liveMap.has(sysid)) {
        // 释放 geometry 和 material，避免 GPU 内存泄漏
        m.group.traverse((obj) => {
          if (obj.geometry) obj.geometry.dispose()
          if (obj.material) {
            if (Array.isArray(obj.material)) obj.material.forEach((mat) => mat.dispose())
            else obj.material.dispose()
          }
        })
        scene.remove(m.group)
        droneModels.delete(sysid)
      }
    })

    // 新增 / 更新
    liveMap.forEach((info, sysid) => {
      let m = droneModels.get(sysid)
      if (!m) {
        const group = buildDroneModel(THREE)
        scene.add(group)
        m = { group, rotors: group.userData.rotors }
        droneModels.set(sysid, m)
      }
      const p = geoTo3D(info.lat, info.lon, info.alt)
      m.group.position.set(p.x, p.y + 2, p.z)
      m.group.rotation.y = -((info.heading || 0) * Math.PI) / 180
      // 离线变暗
      m.group.children.forEach((c) => {
        if (c.material && c.material.color) {
          c.material.opacity = info.online ? 1 : 0.4
          c.material.transparent = !info.online
        }
      })
    })
  }, [drones, telemetry, selected, status])

  // ---- 更新轨迹线（track）----
  useEffect(() => {
    const obj = sceneObj.current
    if (!obj || status !== 'ready') return
    const { THREE, scene, trackLineRef, predLineRef } = obj

    // 移除旧线
    if (trackLineRef.current) {
      scene.remove(trackLineRef.current)
      trackLineRef.current.geometry.dispose()
      trackLineRef.current.material.dispose()
      trackLineRef.current = null
    }
    if (predLineRef.current) {
      scene.remove(predLineRef.current)
      predLineRef.current.geometry.dispose()
      predLineRef.current.material.dispose()
      predLineRef.current = null
    }

    if (!track || track.length < 2) return

    // 历史轨迹：渐变色（cyan → gold）
    const pts = track.map((p) => {
      const v = geoTo3D(p.lat, p.lon, p.alt)
      return new THREE.Vector3(v.x, v.y + 2, v.z)
    })
    const geo = new THREE.BufferGeometry().setFromPoints(pts)
    // 顶点渐变色
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
    const mat = new THREE.LineBasicMaterial({ vertexColors: true, linewidth: 2 })
    const line = new THREE.Line(geo, mat)
    scene.add(line)
    trackLineRef.current = line

    // 预测轨迹：取最后一点的速度方向外推 8 个点（简单线性预测）
    const last = track[track.length - 1]
    const prev = track[track.length - 2]
    if (last && prev) {
      const dLat = last.lat - prev.lat
      const dLon = last.lon - prev.lon
      const dAlt = (last.alt || 0) - (prev.alt || 0)
      const predPts = []
      for (let i = 1; i <= 8; i++) {
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
        dashSize: 2,
        gapSize: 1.5,
        transparent: true,
        opacity: 0.5,
      })
      const pLine = new THREE.Line(pGeo, pMat)
      pLine.computeLineDistances()
      scene.add(pLine)
      predLineRef.current = pLine
    }
  }, [track, status])

  // ---- 更新编队展示（formations）----
  useEffect(() => {
    const obj = sceneObj.current
    if (!obj || status !== 'ready') return
    const { THREE, formationGroupRef } = obj
    const grp = formationGroupRef.current
    if (!grp) return

    // 清空旧编队
    while (grp.children.length) {
      const c = grp.children[0]
      grp.remove(c)
      if (c.geometry) c.geometry.dispose()
      if (c.material) c.material.dispose()
    }

    formations.forEach((f) => {
      const members = (f.members || []).filter((m) => m.target && m.target.lat != null)
      if (members.length < 2) return

      // 成员位置 → 3D
      const pts3d = members.map((m) => {
        const v = geoTo3D(m.target.lat, m.target.lon, m.target.alt || 0)
        return new THREE.Vector3(v.x, v.y + 2, v.z)
      })

      // 队形连线（按顺序连接成员）
      const lineGeo = new THREE.BufferGeometry().setFromPoints(pts3d)
      // 闭合（首尾相连）
      if (f.shape === 'CIRCLE' || f.shape === 'DIAMOND') {
        lineGeo.setFromPoints([...pts3d, pts3d[0]])
      }
      const lineMat = new THREE.LineBasicMaterial({
        color: f.state === 'STABLE' ? COLOR.ok : COLOR.cyan,
        transparent: true,
        opacity: 0.6,
      })
      grp.add(new THREE.Line(lineGeo, lineMat))

      // 成员位置球体
      const sphereGeo = new THREE.SphereGeometry(1.5, 16, 16)
      members.forEach((m, i) => {
        const isLeader = m.role === 'LEADER'
        const mat = new THREE.MeshStandardMaterial({
          color: isLeader ? COLOR.warn : COLOR.cyan,
          emissive: isLeader ? 0x442200 : 0x003344,
          transparent: true,
          opacity: m.online === false ? 0.4 : 1,
        })
        const sphere = new THREE.Mesh(sphereGeo, mat)
        sphere.position.copy(pts3d[i])
        grp.add(sphere)
      })
    })
  }, [formations, status])

  return (
    <div className="scene3d-wrap">
      <div ref={containerRef} className="scene3d-canvas" />
      {status === 'loading' && (
        <div className="scene3d-overlay">
          <div className="scene3d-spinner" />
          <span>正在加载 3D 引擎…</span>
        </div>
      )}
      {status === 'error' && (
        <div className="scene3d-overlay err">
          <span>⚠ 3D 引擎加载失败</span>
          <span className="dim" style={{ fontSize: 11 }}>{hint}</span>
        </div>
      )}
      {status === 'ready' && (
        <div className="scene3d-hud">
          <span className="chip mono">3D · THREE r128</span>
          <span className="chip">
            无人机 <b className="mono">{drones.length}</b>
          </span>
          <span className="chip">
            编队 <b className="mono">{formations.length}</b>
          </span>
          <span className="chip dim" style={{ fontSize: 10 }}>
            左键旋转 · 滚轮缩放 · 右键平移
          </span>
        </div>
      )}
    </div>
  )
}
