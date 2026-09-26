/**
 * Scene3DUtils —— Scene3D / Trajectory3D 共享的常量与辅助函数
 *
 * 从 Scene3D.jsx 提取，供 Scene3D 和 Trajectory3D 复用：
 *  - 常量：THREE_CDN, REF, SCALE, M_PER_DEG_LAT, M_PER_DEG_LON, COLOR
 *  - 函数：geoTo3D, loadThree, createOrbitState, applyOrbit, buildDroneModel, buildTerrain
 *
 * Three.js 通过 CDN 引入，不修改 package.json（避免 BOM 字节序标记问题）。
 * 经验来源：2026-09-16-package-json-bom-breaks-vite-build
 */

export const THREE_CDN = 'https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js'

// 参考点（无人机 home），与 MapView 保持一致
export const REF = { lat: 22.5907, lon: 113.9345 }
// 场景缩放：1 米 = SCALE 个 Three.js 单位
export const SCALE = 0.05
// 米/度换算
export const M_PER_DEG_LAT = 111320
export const M_PER_DEG_LON = M_PER_DEG_LAT * Math.cos((REF.lat * Math.PI) / 180)

// 颜色常量（与 styles.css 主题对齐）
export const COLOR = {
  cyan: 0x00d4ff,
  ok: 0x2de2a5,
  warn: 0xffb224,
  crit: 0xff5d5d,
  gold: 0xffc857,
  dim: 0x4a5770,
}

/**
 * 经纬高 → 3D 坐标（以 REF 为原点，东为 +X，上为 +Y，南为 +Z）
 */
export function geoTo3D(lat, lon, alt = 0) {
  const x = (lon - REF.lon) * M_PER_DEG_LON * SCALE
  const z = -(lat - REF.lat) * M_PER_DEG_LAT * SCALE
  const y = (alt || 0) * SCALE
  return { x, y, z }
}

/**
 * 动态加载 Three.js（CDN），返回 Promise<THREE>。
 * 导出供 Trajectory3D 复用，避免重复创建 script 标签。
 */
export function loadThree() {
  return new Promise((resolve, reject) => {
    if (window.THREE) return resolve(window.THREE)
    // 防止并发重复注入
    if (window.__threeLoading) {
      window.__threeLoading.then(resolve, reject)
      return
    }
    window.__threeLoading = new Promise((res, rej) => {
      const s = document.createElement('script')
      s.src = THREE_CDN
      s.async = true
      s.onload = () => res(window.THREE)
      s.onerror = () => rej(new Error('Three.js CDN 加载失败'))
      document.head.appendChild(s)
    })
    window.__threeLoading.then(resolve, reject)
  })
}

/**
 * 相机轨道控制器状态
 */
export function createOrbitState() {
  return {
    theta: Math.PI / 4, // 方位角
    phi: Math.PI / 3, // 极角（从 +Y 轴向下）
    radius: 180, // 距离
    target: { x: 0, y: 10, z: 0 }, // 目标点
  }
}

/**
 * 应用轨道状态到相机
 */
export function applyOrbit(camera, cam) {
  const sinPhi = Math.sin(cam.phi)
  camera.position.x = cam.target.x + cam.radius * sinPhi * Math.sin(cam.theta)
  camera.position.y = cam.target.y + cam.radius * Math.cos(cam.phi)
  camera.position.z = cam.target.z + cam.radius * sinPhi * Math.cos(cam.theta)
  camera.lookAt(cam.target.x, cam.target.y, cam.target.z)
}

/**
 * 构建无人机模型 Group（机身 + 旋翼臂 + 旋翼 + 方向锥）
 */
export function buildDroneModel(THREE) {
  const group = new THREE.Group()

  // 机身
  const body = new THREE.Mesh(
    new THREE.BoxGeometry(2.4, 0.8, 2.4),
    new THREE.MeshStandardMaterial({ color: 0x00d4ff, metalness: 0.6, roughness: 0.4 })
  )
  group.add(body)

  // 4 旋翼臂 + 旋翼
  const armMat = new THREE.MeshStandardMaterial({ color: 0x2d3850, metalness: 0.7, roughness: 0.3 })
  const rotorMat = new THREE.MeshStandardMaterial({
    color: 0x1a2130,
    metalness: 0.8,
    roughness: 0.2,
    transparent: true,
    opacity: 0.7,
  })
  const armLen = 3.2
  const positions = [
    [1, 1],
    [1, -1],
    [-1, 1],
    [-1, -1],
  ]
  const rotors = []
  positions.forEach(([sx, sz]) => {
    const arm = new THREE.Mesh(new THREE.BoxGeometry(armLen, 0.25, 0.25), armMat)
    arm.position.set((sx * armLen) / 2, 0, (sz * armLen) / 2)
    arm.rotation.y = Math.atan2(sz, sx)
    group.add(arm)

    const rotor = new THREE.Mesh(new THREE.CylinderGeometry(1.1, 1.1, 0.15, 20), rotorMat)
    rotor.position.set(sx * (armLen / 2), 0.5, sz * (armLen / 2))
    group.add(rotor)
    rotors.push(rotor)
  })

  // 方向锥（机头）
  const nose = new THREE.Mesh(
    new THREE.ConeGeometry(0.6, 1.6, 12),
    new THREE.MeshStandardMaterial({ color: 0xffb224, emissive: 0x442200 })
  )
  nose.position.set(0, 0, -2.2)
  nose.rotation.x = -Math.PI / 2
  group.add(nose)

  group.userData.rotors = rotors
  return group
}

/**
 * 构建地形网格（带轻微起伏的平面 + 网格线）
 */
export function buildTerrain(THREE) {
  const group = new THREE.Group()

  // 网格线
  const grid = new THREE.GridHelper(400, 40, 0x00d4ff, 0x232c40)
  grid.material.opacity = 0.35
  grid.material.transparent = true
  group.add(grid)

  // 地面平面（半透明深色）
  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(400, 400, 40, 40),
    new THREE.MeshStandardMaterial({
      color: 0x0b0e14,
      transparent: true,
      opacity: 0.8,
      side: THREE.DoubleSide,
    })
  )
  ground.rotation.x = -Math.PI / 2
  ground.position.y = -0.1
  group.add(ground)

  // 给地面顶点加轻微起伏，模拟地形
  const pos = ground.geometry.attributes.position
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i)
    const z = pos.getZ(i) // PlaneGeometry 旋转前 z 即平面第二维
    const h = Math.sin(x * 0.05) * Math.cos(z * 0.04) * 1.5 + Math.sin(x * 0.02 + z * 0.03) * 2.5
    // PlaneGeometry 默认在 XY 平面（z=0），绕 X 轴旋转 -PI/2 后：原 z → 世界 y（高度方向），
    // 原 y → 世界 -z（水平）。因此起伏高度必须在旋转前的 z 分量上设置。
    pos.setZ(i, h)
  }
  ground.geometry.attributes.position.needsUpdate = true
  ground.geometry.computeVertexNormals()

  return group
}