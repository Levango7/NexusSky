const BASE = '/api/v1'

async function jsonFetch(url, options) {
  const res = await fetch(url, options)
  let body = null
  try {
    body = await res.json()
  } catch (e) {
    body = null
  }
  if (!res.ok) {
    throw new Error((body && body.error) || `HTTP ${res.status}`)
  }
  return body
}

export const api = {
  listDrones: () => jsonFetch(`${BASE}/drones`),

  getTelemetry: (sysid) => jsonFetch(`${BASE}/drones/${sysid}/telemetry`),

  getTrack: (sysid) => jsonFetch(`${BASE}/drones/${sysid}/track`),

  uploadMission: (sysid, items) =>
    jsonFetch(`${BASE}/drones/${sysid}/mission`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ items }),
    }),

  // Read back the mission stored on the drone (mission download protocol)
  downloadMission: (sysid) => jsonFetch(`${BASE}/drones/${sysid}/mission`),

  sendCommand: (sysid, type, alt) =>
    jsonFetch(`${BASE}/drones/${sysid}/commands`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type, alt }),
    }),

  // Virtual joystick (MANUAL_CONTROL passthrough, ~10 Hz while held)
  sendJoystick: (sysid, x, y, z, r) =>
    jsonFetch(`${BASE}/drones/${sysid}/joystick`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ x, y, z, r }),
    }),

  // ---- Vision pipeline (batch A / D1-D2) ----

  // One-shot capture -> geolocate -> truth score (synchronous, ~5s)
  triggerCapture: (sysid) =>
    jsonFetch(`${BASE}/vision/drones/${sysid}/capture`, { method: 'POST' }),

  // Start an async orbit; returns { jobId } immediately (202)
  startOrbit: (sysid, { lat, lon, radiusM = 25, altM = 60, photos = 4 }) =>
    jsonFetch(`${BASE}/vision/drones/${sysid}/orbit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lat, lon, radiusM, altM, photos }),
    }),

  // Poll an orbit job: state / per-station progress / final result
  getOrbitJob: (jobId) => jsonFetch(`${BASE}/vision/jobs/${jobId}`),

  // Target tracks of a drone (id, state, hits, lastSeen, predicted)
  getTracks: (sysid) => jsonFetch(`${BASE}/vision/drones/${sysid}/tracks`),

  // Flight log query (day defaults to today server-side)
  getFlightLog: (query = {}) => {
    const qs = new URLSearchParams(query).toString()
    return jsonFetch(`${BASE}/flightlog${qs ? '?' + qs : ''}`)
  },

  // ---- Formation (编队表演 M1) ----
  // 创建编队：members=sysid 数组 + 队形参数 + 参考点经纬高
  createFormation: (payload) =>
    jsonFetch(`${BASE}/formation/create`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 查询单个编队状态
  getFormation: (id) => jsonFetch(`${BASE}/formation/${id}`),

  // 编队命令（TAKEOFF/RTL/DISSOLVE 等；alt 仅 TAKEOFF 用）
  commandFormation: (id, type, alt) =>
    jsonFetch(`${BASE}/formation/${id}/command`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type, alt }),
    }),

  // 队形平滑变换：newShape + 插值步数
  transitionFormation: (id, newShape, steps) =>
    jsonFetch(`${BASE}/formation/${id}/transition`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ newShape, steps }),
    }),

  // 灯光控制：on/pattern/color/brightness/freq/sync
  lightsFormation: (id, payload) =>
    jsonFetch(`${BASE}/formation/${id}/lights`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 查询编队灯光状态
  getLights: (id) => jsonFetch(`${BASE}/formation/${id}/lights`),

  // 移除单机并重整队形
  removeMember: (id, sysid) =>
    jsonFetch(`${BASE}/formation/${id}/members/${sysid}`, { method: 'DELETE' }),

  // 解散编队（在飞成员 RTL 后置 DISSOLVED）
  dissolveFormation: (id) =>
    jsonFetch(`${BASE}/formation/${id}/dissolve`, { method: 'POST' }),

  // ---- Spray & Delivery (喷洒物流 M2) ----
  // 创建喷洒任务：sysid + 喷洒量 + 速率 + 航段列表
  createSprayTask: (payload) =>
    jsonFetch(`${BASE}/spray/task`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 查询喷洒状态：泵状态 / 剩余液量 / 喷洒速率
  getSprayStatus: (sysid) => jsonFetch(`${BASE}/spray/status/${sysid}`),

  // 夹爪控制：开/关
  controlGripper: (sysid, open) =>
    jsonFetch(`${BASE}/spray/gripper`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sysid, open }),
    }),

  // 物流配送序列：站点列表 + 状态
  getDeliverySequence: (sysid) => jsonFetch(`${BASE}/delivery/sequence/${sysid}`),

  // ---- Hardware Abstraction (硬件抽象 M4) ----
  // 雷达扫描配置：扫描模式 / 方位角范围 / 周期
  configureRadar: (payload) =>
    jsonFetch(`${BASE}/radar/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 雷达状态：当前波束方位
  getRadarStatus: (sysid) => jsonFetch(`${BASE}/radar/status/${sysid}`),

  // 雷达检测目标列表：距离/方位/RCS/跟踪状态
  getRadarTargets: (sysid) => jsonFetch(`${BASE}/radar/targets/${sysid}`),

  // 旋翼遥测：RPM / 推力 / 扭矩 / 桨距角
  getRotorTelemetry: (sysid) => jsonFetch(`${BASE}/rotor/telemetry/${sysid}`),

  // LiDAR 数据：最近距离 / 点数
  getLidarData: (sysid) => jsonFetch(`${BASE}/lidar/data/${sysid}`),

  // IMU 数据：加速度计 / 陀螺仪 / 磁力计
  getImuData: (sysid) => jsonFetch(`${BASE}/imu/data/${sysid}`),

  // 物理模型切换：kinematics / aero
  setPhysicsModel: (sysid, model) =>
    jsonFetch(`${BASE}/hardware/physics-model`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sysid, model }),
    }),

  // ---- Mesh Topology (应急 mesh 自愈组网 M5) ----
  // 获取全网拓扑
  getMeshTopology: () => jsonFetch(`${BASE}/mesh/topology`),

  // 获取单节点拓扑
  getMeshNodeTopology: (sysid) => jsonFetch(`${BASE}/mesh/topology/${sysid}`),

  // 获取单节点路由表
  getMeshRoutes: (sysid) => jsonFetch(`${BASE}/mesh/routes/${sysid}`),

  // 获取单节点邻居表
  getMeshNeighbors: (sysid) => jsonFetch(`${BASE}/mesh/neighbors/${sysid}`),

  // 获取所有链路及质量分级
  getMeshLinks: () => jsonFetch(`${BASE}/mesh/links`),

  // ---- Sat Relay (星-空-地多层级中继 M7) ----
  // 获取所有卫星链路状态
  getSatLinkStatus: () => jsonFetch(`${BASE}/sat-link/status`),

  // 获取所有过境计划
  getSatLinkPasses: () => jsonFetch(`${BASE}/sat-link/passes`),

  // 获取最近路由决策历史
  getSatLinkRoutes: () => jsonFetch(`${BASE}/sat-link/routes`),

  // 获取当前切换策略
  getSatLinkStrategy: () => jsonFetch(`${BASE}/sat-link/strategy`),

  // 设置切换策略（运行时热更新）
  setSatLinkStrategy: (strategy) =>
    jsonFetch(`${BASE}/sat-link/strategy`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ strategy }),
    }),

  // 获取星座配置
  getSatLinkConstellation: () => jsonFetch(`${BASE}/sat-link/constellation`),

  // ---- Terrain Adapt (复杂地形适配 M8) ----
  // 获取当前地形分区图
  getTerrainMap: () => jsonFetch(`${BASE}/terrain/map`),

  // 获取飞行限制区列表
  getTerrainRestrictions: () => jsonFetch(`${BASE}/terrain/restrictions`),

  // 获取地形变更历史（分页）
  getTerrainChanges: (offset = 0, limit = 50) =>
    jsonFetch(`${BASE}/terrain/changes?offset=${offset}&limit=${limit}`),

  // 触发地形建图（指挥员权限）
  buildTerrainMap: (payload) =>
    jsonFetch(`${BASE}/terrain/build`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // ---- Cell Tower (移动基站载荷 M6) ----
  // 获取全网基站拓扑
  getCellTowers: () => jsonFetch(`${BASE}/celltowers`),

  // 获取单基站状态
  getCellTower: (sysid) => jsonFetch(`${BASE}/celltowers/${sysid}`),

  // 下发基站配置
  configureCellTower: (sysid, config) =>
    jsonFetch(`${BASE}/celltowers/${sysid}/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    }),

  // 获取基站接入终端列表
  getCellTowerTerminals: (sysid) => jsonFetch(`${BASE}/celltowers/${sysid}/terminals`),

  // 触发漫游切换
  triggerCellTowerHandover: (sysid, handover) =>
    jsonFetch(`${BASE}/celltowers/${sysid}/handover`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(handover),
    }),

  // 获取漫游切换历史
  getCellTowerHandovers: () => jsonFetch(`${BASE}/celltowers/handovers`),
}

// ---- Emergency Orchestration (应急任务编排 M9) ----
// 注意：应急编排 API 挂载在 /api/v1/emergency 下（独立于 v1 BASE，但带 v1 前缀）
const EMERGENCY_BASE = '/api/v1/emergency'

export const emergencyOrch = {
  // 获取场景预设列表
  getScenarios: () => jsonFetch(`${EMERGENCY_BASE}/scenarios`),

  // 启动编排：type=earthquake|mudslide|fire|custom，body={ centerLat, centerLon, radiusKm }
  startScenario: (type, body) =>
    jsonFetch(`${EMERGENCY_BASE}/scenarios/${type}/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  // 查询计划状态（轮询 2s）
  getPlan: (planId) => jsonFetch(`${EMERGENCY_BASE}/orch/${planId}`),

  // 紧急停止
  abort: (planId) =>
    jsonFetch(`${EMERGENCY_BASE}/orch/${planId}/abort`, { method: 'POST' }),

  // 查询覆盖率/连通率
  getCoverage: (planId) => jsonFetch(`${EMERGENCY_BASE}/orch/${planId}/coverage`),

  // 查询优先级队列
  getPriorityQueue: (planId) => jsonFetch(`${EMERGENCY_BASE}/orch/${planId}/priority/queue`),

  // 调整任务优先级
  adjustPriority: (planId, body) =>
    jsonFetch(`${EMERGENCY_BASE}/orch/${planId}/priority`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
}

export const wsUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${
  location.host
}/ws/telemetry`
