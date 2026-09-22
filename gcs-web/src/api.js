const BASE = '/api/v1/v1'

// ---- Token management ----
// Token 持久化到 sessionStorage，页面刷新后可恢复，关闭浏览器即清除
const TOKEN_KEY = 'nexus_auth_token'
const USER_KEY = 'nexus_current_user'

let authToken = null
let currentUser = null

// 模块加载时从 sessionStorage 恢复 token 和 user
try {
  authToken = sessionStorage.getItem(TOKEN_KEY)
  currentUser = JSON.parse(sessionStorage.getItem(USER_KEY) || 'null')
} catch (e) {
  authToken = null
  currentUser = null
}

// 验证 JWT 基本格式：header.payload.signature（三段以 . 分隔，每段非空）
function isValidJwtFormat(token) {
  if (typeof token !== 'string' || !token) return false
  const parts = token.split('.')
  return parts.length === 3 && parts.every((p) => p.length > 0)
}

export function setAuthToken(token) {
  if (!isValidJwtFormat(token)) {
    throw new Error('Token 格式无效：JWT 应为 header.payload.signature 三段结构')
  }
  authToken = token
  try { sessionStorage.setItem(TOKEN_KEY, token) } catch (e) { /* sessionStorage 不可用时静默降级 */ }
}

export function getAuthToken() { return authToken }

export function setCurrentUser(user) {
  currentUser = user
  try { sessionStorage.setItem(USER_KEY, JSON.stringify(user)) } catch (e) { /* sessionStorage 不可用时静默降级 */ }
}

export function getCurrentUser() { return currentUser }

export function isAuthenticated() { return !!authToken }

export function clearAuthToken() {
  authToken = null
  currentUser = null
  try {
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(USER_KEY)
  } catch (e) { /* sessionStorage 不可用时静默降级 */ }
}

export function logout() { clearAuthToken() }

// 请求超时错误类型
export class TimeoutError extends Error {
  constructor(timeoutMs) {
    super(`请求超时（${timeoutMs}ms）`)
    this.name = 'TimeoutError'
    this.timeoutMs = timeoutMs
  }
}

const DEFAULT_TIMEOUT_MS = 15000

async function jsonFetch(url, options = {}) {
  const { timeout = DEFAULT_TIMEOUT_MS, ...fetchOptions } = options

  if (authToken) {
    fetchOptions.headers = { ...(fetchOptions.headers || {}), 'Authorization': `Bearer ${authToken}` }
  }

  // AbortController + setTimeout 实现请求超时控制
  // 经验来源：2026-09-12-abortcontroller-timeout-cleartimeout-finally-block
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  fetchOptions.signal = controller.signal

  let res
  try {
    res = await fetch(url, fetchOptions)
  } catch (e) {
    if (e.name === 'AbortError') {
      throw new TimeoutError(timeout)
    }
    throw e
  } finally {
    clearTimeout(timer)
  }

  // 401 响应：token 过期或无效，自动清除并跳转登录
  if (res.status === 401) {
    clearAuthToken()
    // 避免在登录页面自身触发循环跳转
    if (!location.pathname.includes('/login')) {
      location.reload()
    }
    throw new Error('认证已过期，请重新登录')
  }

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

  // ---- Tracking (飞行轨迹追踪与丢失找回) ----
  // base 路径 /api/v1/tracking（独立于 v1 BASE）
  // 获取无人机飞行轨迹（limit 限制点数）
  getFlightTrack: (sysid, limit) => {
    const qs = limit != null ? `?limit=${encodeURIComponent(limit)}` : ''
    return jsonFetch(`/api/v1/tracking/${sysid}/track${qs}`)
  },

  // 轨迹回放：时间范围 [from, to] + 点数限制
  replayTrack: (sysid, from, to, limit) => {
    const qs = new URLSearchParams({ from, to, limit }).toString()
    return jsonFetch(`/api/v1/tracking/${sysid}/replay?${qs}`)
  },

  // 最近一次已知位置
  getLastKnown: (sysid) => jsonFetch(`/api/v1/tracking/${sysid}/last-known`),

  // 丢失无人机列表
  getLostDrones: () => jsonFetch('/api/v1/tracking/lost'),

  // 搜索引导（预测航向/距离）
  getSearchGuide: (sysid) => jsonFetch(`/api/v1/tracking/${sysid}/search-guide`),

  // 触发丢失无人机扫描
  scanLostDrones: () => jsonFetch('/api/v1/tracking/scan'),

  // ---- Geofence (地理围栏) ----
  // base 路径 /api/v1/geofence（独立于 v1 BASE）
  // 创建围栏区域（zone JSON）
  createGeofenceZone: (zone) =>
    jsonFetch('/api/v1/geofence/zones', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(zone),
    }),

  // 查询所有围栏区域
  listGeofenceZones: () => jsonFetch('/api/v1/geofence/zones'),

  // 查询单个围栏区域
  getGeofenceZone: (id) => jsonFetch(`/api/v1/geofence/zones/${id}`),

  // 更新围栏区域（zone JSON）
  updateGeofenceZone: (id, zone) =>
    jsonFetch(`/api/v1/geofence/zones/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(zone),
    }),

  // 删除围栏区域
  deleteGeofenceZone: (id) =>
    jsonFetch(`/api/v1/geofence/zones/${id}`, { method: 'DELETE' }),

  // 查询围栏突破事件（sysid 与 zoneId 均为可选筛选）
  getGeofenceBreaches: (sysid, zoneId) => {
    const params = {}
    if (sysid != null) params.sysid = sysid
    if (zoneId != null) params.zoneId = zoneId
    const qs = new URLSearchParams(params).toString()
    return jsonFetch(`/api/v1/geofence/breaches${qs ? '?' + qs : ''}`)
  },

  // 触发围栏检查
  checkGeofence: () =>
    jsonFetch('/api/v1/geofence/check', { method: 'POST' }),

  // ---- DroneLock (无人机锁定/解锁) ----
  // base 路径 /api/v1/drone-lock（独立于 v1 BASE）
  // 锁定无人机（payload 含 reason/lockedBy/action）
  lockDrone: (sysid, payload) =>
    jsonFetch(`/api/v1/drone-lock/${sysid}/lock`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 解锁无人机（payload 含 unlockedBy）
  unlockDrone: (sysid, payload) =>
    jsonFetch(`/api/v1/drone-lock/${sysid}/unlock`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 查询单机锁定状态
  getLockStatus: (sysid) => jsonFetch(`/api/v1/drone-lock/${sysid}`),

  // 查询所有已锁定无人机
  getLockedDrones: () => jsonFetch('/api/v1/drone-lock/locked'),

  // 查询全部锁定状态
  getAllLockStates: () => jsonFetch('/api/v1/drone-lock/all'),

  // 清除单机锁定状态
  clearLockState: (sysid) =>
    jsonFetch(`/api/v1/drone-lock/${sysid}`, { method: 'DELETE' }),

  // ---- AutoDispatch (自动出警 P0) ----
  // base 路径 /api/v1/autodispatch（独立于 v1 BASE）
  // 手动触发出警：经纬度 + 报警ID + 无人机数
  triggerAutoDispatch: (payload) =>
    jsonFetch('/api/v1/autodispatch/trigger', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 出警历史列表
  getAutoDispatchHistory: () => jsonFetch('/api/v1/autodispatch/history'),

  // 进行中出警任务
  getAutoDispatchActive: () => jsonFetch('/api/v1/autodispatch/active'),

  // 中止出警任务
  abortAutoDispatch: (dispatchId) =>
    jsonFetch(`/api/v1/autodispatch/${dispatchId}/abort`, { method: 'POST' }),

  // 获取自动出警配置
  getAutoDispatchConfig: () => jsonFetch('/api/v1/autodispatch/config'),

  // 更新自动出警配置
  updateAutoDispatchConfig: (config) =>
    jsonFetch('/api/v1/autodispatch/config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    }),

  // ---- VideoStream (视频流管理 P0) ----
  // base 路径 /api/v1/video-stream
  // 获取视频流地址
  getVideoStreamUrl: (sysid) => jsonFetch(`/api/v1/video-stream/${sysid}/url`),

  // 启动视频流
  startVideoStream: (sysid) =>
    jsonFetch(`/api/v1/video-stream/${sysid}/start`, { method: 'POST' }),

  // 停止视频流
  stopVideoStream: (sysid) =>
    jsonFetch(`/api/v1/video-stream/${sysid}/stop`, { method: 'POST' }),

  // 查询活跃视频流
  getActiveVideoStreams: () => jsonFetch('/api/v1/video-stream/active'),

  // ---- VoiceIntercom (语音对讲 P0) ----
  // base 路径 /api/v1/voice-intercom
  // 启动语音对讲
  startVoiceIntercom: (sysid) =>
    jsonFetch(`/api/v1/voice-intercom/${sysid}/start`, { method: 'POST' }),

  // 停止语音对讲
  stopVoiceIntercom: (sysid) =>
    jsonFetch(`/api/v1/voice-intercom/${sysid}/stop`, { method: 'POST' }),

  // 广播喊话
  broadcastVoice: (sysid, payload) =>
    jsonFetch(`/api/v1/voice-intercom/${sysid}/broadcast`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // ---- Scenarios (应急场景库 P1) ----
  // base 路径 /api/v1/scenarios
  // 获取场景模板列表
  getScenarioTemplates: () => jsonFetch('/api/v1/scenarios/templates'),

  // 获取场景模板详情
  getScenarioTemplate: (id) => jsonFetch(`/api/v1/scenarios/templates/${id}`),

  // 按灾害类型获取模板
  getScenarioTemplatesByType: (disasterType) =>
    jsonFetch(`/api/v1/scenarios/templates/by-type/${disasterType}`),

  // 启动场景
  launchScenario: (templateId, payload) =>
    jsonFetch(`/api/v1/scenarios/launch/${templateId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload || {}),
    }),

  // 进行中场景列表
  getActiveScenarioLaunches: () => jsonFetch('/api/v1/scenarios/launch/active'),

  // 场景历史
  getScenarioLaunchHistory: () => jsonFetch('/api/v1/scenarios/launch/history'),

  // 中止场景
  abortScenarioLaunch: (launchId) =>
    jsonFetch(`/api/v1/scenarios/launch/${launchId}/abort`, { method: 'POST' }),

  // 查询场景启动状态
  getScenarioLaunchStatus: (launchId) =>
    jsonFetch(`/api/v1/scenarios/launch/${launchId}/status`),

  // 启动演练
  startScenarioDrill: (templateId, payload) =>
    jsonFetch(`/api/v1/scenarios/drill/${templateId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload || {}),
    }),

  // 获取演练结果
  getScenarioDrillResult: (drillId) =>
    jsonFetch(`/api/v1/scenarios/drill/${drillId}/result`),

  // 演练历史
  getScenarioDrillHistory: () => jsonFetch('/api/v1/scenarios/drill/history'),

  // ---- Inspection (智能巡检 P1) ----
  // base 路径 /api/v1/inspection
  // 创建巡检任务
  createInspectionTask: (payload) =>
    jsonFetch('/api/v1/inspection/tasks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 巡检任务列表
  listInspectionTasks: () => jsonFetch('/api/v1/inspection/tasks'),

  // 巡检任务详情
  getInspectionTask: (id) => jsonFetch(`/api/v1/inspection/tasks/${id}`),

  // 启动巡检任务
  startInspectionTask: (id) =>
    jsonFetch(`/api/v1/inspection/tasks/${id}/start`, { method: 'POST' }),

  // 中止巡检任务
  abortInspectionTask: (id) =>
    jsonFetch(`/api/v1/inspection/tasks/${id}/abort`, { method: 'POST' }),

  // 巡检任务进度
  getInspectionProgress: (id) =>
    jsonFetch(`/api/v1/inspection/tasks/${id}/progress`),

  // 巡检报告
  getInspectionReport: (taskId) =>
    jsonFetch(`/api/v1/inspection/reports/${taskId}`),

  // 异常清单
  getInspectionAnomalies: (taskId) =>
    jsonFetch(`/api/v1/inspection/reports/${taskId}/anomalies`),

  // 巡检照片（GPS标注）
  getInspectionPhotos: (taskId) =>
    jsonFetch(`/api/v1/inspection/reports/${taskId}/photos`),

  // ---- Health (健康管理 P1) ----
  // base 路径 /api/v1/health
  // 单机健康详情
  getDroneHealth: (sysid) => jsonFetch(`/api/v1/health/${sysid}`),

  // 机队健康总览
  getFleetHealth: () => jsonFetch('/api/v1/health/fleet'),

  // 单机健康历史
  getDroneHealthHistory: (sysid) => jsonFetch(`/api/v1/health/${sysid}/history`),

  // 单机部件健康详情
  getComponentHealth: (sysid, component) =>
    jsonFetch(`/api/v1/health/${sysid}/components/${component}`),

  // 健康告警列表
  getHealthWarnings: () => jsonFetch('/api/v1/health/warnings'),

  // ---- Maintenance (维护管理 P1) ----
  // base 路径 /api/v1/maintenance
  // 维护记录列表
  listMaintenanceRecords: () => jsonFetch('/api/v1/maintenance/records'),

  // 创建维护记录
  createMaintenanceRecord: (payload) =>
    jsonFetch('/api/v1/maintenance/records', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 更新维护记录
  updateMaintenanceRecord: (id, payload) =>
    jsonFetch(`/api/v1/maintenance/records/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 预测性维护建议（全机队）
  getMaintenancePredictions: () => jsonFetch('/api/v1/maintenance/predictions'),

  // 单机预测性维护建议
  getDroneMaintenancePredictions: (sysid) =>
    jsonFetch(`/api/v1/maintenance/predictions/${sysid}`),

  // 维护计划
  getMaintenanceSchedule: () => jsonFetch('/api/v1/maintenance/schedule'),

  // ---- CommAdapt (多模态通信自适应 P2) ----
  // base 路径 /api/v1/comm-adapt
  // 获取所有链路质量（机队级）
  getCommLinks: () => jsonFetch('/api/v1/comm-adapt/quality/fleet'),

  // 获取指定无人机链路质量（含综合评分）
  getCommLink: (sysid) => jsonFetch(`/api/v1/comm-adapt/quality/${sysid}`),

  // 获取综合质量评分（与 getCommLink 合并，复用同一端点）
  getCommScore: (sysid) => jsonFetch(`/api/v1/comm-adapt/quality/${sysid}`),

  // 获取链路切换决策（推荐列表）
  getCommDecision: () => jsonFetch('/api/v1/comm-adapt/recommendations'),

  // 执行故障切换（sysid 在 path 中，body 含 targetLink）
  executeCommFailover: (sysid, targetLink) =>
    jsonFetch(`/api/v1/comm-adapt/switch/${sysid}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ targetLink }),
    }),

  // 获取故障切换历史（按 sysid 过滤）
  getCommFailoverHistory: (sysid) => jsonFetch(`/api/v1/comm-adapt/failover/history/${sysid}`),

  // 获取自适应配置
  getCommConfig: () => jsonFetch('/api/v1/comm-adapt/config'),

  // 更新自适应配置
  updateCommConfig: (config) =>
    jsonFetch('/api/v1/comm-adapt/config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    }),

  // ---- Mapping (航拍测绘 P2) ----
  // base 路径 /api/v1/mapping
  // 创建测绘任务
  createMappingTask: (payload) =>
    jsonFetch('/api/v1/mapping/tasks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 列出所有测绘任务
  listMappingTasks: () => jsonFetch('/api/v1/mapping/tasks'),

  // 获取测绘任务详情
  getMappingTask: (id) => jsonFetch(`/api/v1/mapping/tasks/${id}`),

  // 获取航点（航线规划，GET 请求）
  planMappingRoute: (id) => jsonFetch(`/api/v1/mapping/tasks/${id}/waypoints`),

  // 获取采集照片
  getMappingPhotos: (id) => jsonFetch(`/api/v1/mapping/tasks/${id}/photos`),

  // 生成测绘成果（process 端点，采集在 process 中自动完成）
  generateMappingResult: (id) =>
    jsonFetch(`/api/v1/mapping/tasks/${id}/process`, { method: 'POST' }),

  // 获取测绘成果
  getMappingResult: (id) => jsonFetch(`/api/v1/mapping/tasks/${id}/result`),

  // ---- VoiceCmd (语音指挥 P3) ----
  // base 路径 /api/v1/voice-cmd
  // 解析语音指令
  parseVoiceCommand: (text) =>
    jsonFetch('/api/v1/voice-cmd/parse', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    }),

  // 执行指令
  executeVoiceCommand: (payload) =>
    jsonFetch('/api/v1/voice-cmd/execute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 确认执行
  confirmVoiceCommand: (pendingId) =>
    jsonFetch(`/api/v1/voice-cmd/confirm/${pendingId}`, { method: 'POST' }),

  // 语音播报（sysid 在 path 中）
  broadcastVoiceMessage: (sysid, payload) =>
    jsonFetch(`/api/v1/voice-cmd/broadcast/${sysid}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 获取播报状态
  getVoiceStatus: (sysid) => jsonFetch(`/api/v1/voice-cmd/broadcast/${sysid}/status`),

  // 告警播报（GET 请求）
  broadcastVoiceAlert: (sysid) =>
    jsonFetch(`/api/v1/voice-cmd/broadcast/${sysid}/alert`),

  // 获取指令历史
  getVoiceHistory: () => jsonFetch('/api/v1/voice-cmd/history'),

  // 获取待确认指令
  getVoicePending: () => jsonFetch('/api/v1/voice-cmd/pending'),

  // ---- CityTwin (数字孪生城市 P3) ----
  // base 路径 /api/v1/city-twin
  // 创建城市模型
  createCityModel: (payload) =>
    jsonFetch('/api/v1/city-twin/models', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 列出所有城市模型
  listCityModels: () => jsonFetch('/api/v1/city-twin/models'),

  // 获取城市模型详情
  getCityModel: (id) => jsonFetch(`/api/v1/city-twin/models/${id}`),

  // 获取实时态势（current 端点）
  getCitySituation: () => jsonFetch('/api/v1/city-twin/situation/current'),

  // 创建灾害模拟（type 在 path 中，参数通过 query params 传递）
  createCitySimulation: (type, payload) => {
    const qs = new URLSearchParams(payload).toString()
    return jsonFetch(`/api/v1/city-twin/simulation/${type}${qs ? '?' + qs : ''}`, {
      method: 'POST',
    })
  },

  // 获取模拟详情
  getCitySimulation: (id) => jsonFetch(`/api/v1/city-twin/simulation/${id}`),

  // 创建态势标绘
  createCityMarker: (payload) =>
    jsonFetch('/api/v1/city-twin/markers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 列出态势标绘
  listCityMarkers: () => jsonFetch('/api/v1/city-twin/markers'),

  // ---- Delivery2 (物流配送 P4) ----
  // base 路径 /api/v1/delivery2
  // 创建配送任务
  createDeliveryTask: (payload) =>
    jsonFetch('/api/v1/delivery2/tasks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 列出所有配送任务
  listDeliveryTasks: () => jsonFetch('/api/v1/delivery2/tasks'),

  // 获取配送任务详情
  getDeliveryTask: (id) => jsonFetch(`/api/v1/delivery2/tasks/${id}`),

  // 启动配送
  startDeliveryTask: (id) =>
    jsonFetch(`/api/v1/delivery2/tasks/${id}/start`, { method: 'POST' }),

  // 中止配送
  abortDeliveryTask: (id) =>
    jsonFetch(`/api/v1/delivery2/tasks/${id}/abort`, { method: 'POST' }),

  // 优化路线（GET 请求获取路线）
  optimizeDeliveryRoute: (id) => jsonFetch(`/api/v1/delivery2/tasks/${id}/route`),

  // 执行投放（body: { method })
  deliverDeliveryTask: (id, body) =>
    jsonFetch(`/api/v1/delivery2/tasks/${id}/deliver`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  // 获取配送状态
  getDeliveryStatus: (id) => jsonFetch(`/api/v1/delivery2/tasks/${id}/status`),

  // 确认签收
  confirmDeliveryTask: (id) =>
    jsonFetch(`/api/v1/delivery2/tasks/${id}/confirm`, { method: 'POST' }),

  // 搜索降落点
  searchLandingSites: (params = {}) => {
    const qs = new URLSearchParams(params).toString()
    return jsonFetch(`/api/v1/delivery2/landing-sites${qs ? '?' + qs : ''}`)
  },

  // ---- Show (编队表演 P4) ----
  // base 路径 /api/v1/show
  // 创建队形定义
  createShowFormation: (payload) =>
    jsonFetch('/api/v1/show/formations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 列出所有队形
  listShowFormations: () => jsonFetch('/api/v1/show/formations'),

  // 获取队形详情
  getShowFormation: (id) => jsonFetch(`/api/v1/show/formations/${id}`),

  // 计算队形位置（body: { droneCount }）
  calculateShowPositions: (id, body) =>
    jsonFetch(`/api/v1/show/formations/${id}/positions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  // 创建表演任务
  createShowTask: (payload) =>
    jsonFetch('/api/v1/show/tasks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 列出所有表演任务
  listShowTasks: () => jsonFetch('/api/v1/show/tasks'),

  // 获取表演任务详情
  getShowTask: (id) => jsonFetch(`/api/v1/show/tasks/${id}`),

  // 启动表演
  startShowTask: (id) =>
    jsonFetch(`/api/v1/show/tasks/${id}/start`, { method: 'POST' }),

  // 中止表演
  abortShowTask: (id) =>
    jsonFetch(`/api/v1/show/tasks/${id}/abort`, { method: 'POST' }),

  // 获取动作序列
  getShowActions: (id) => jsonFetch(`/api/v1/show/tasks/${id}/actions`),

  // 配置音乐同步
  configureShowMusicSync: (id, payload) =>
    jsonFetch(`/api/v1/show/tasks/${id}/music-sync`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // ---- Auth ----
  login: (username, password) => jsonFetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  }),
  refreshToken: (token) => jsonFetch('/api/v1/auth/refresh', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` },
  }),

  // ---- Tenants ----
  listTenants: () => jsonFetch(`${BASE}/tenants`),
  getTenant: (id) => jsonFetch(`${BASE}/tenants/${id}`),
  createTenant: (name, code, enabled) => jsonFetch(`${BASE}/tenants`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, code, enabled }),
  }),
  updateTenant: (id, name, code, enabled) => jsonFetch(`${BASE}/tenants/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, code, enabled }),
  }),
  deleteTenant: (id) => jsonFetch(`${BASE}/tenants/${id}`, { method: 'DELETE' }),

  // ---- Users ----
  listUsers: () => jsonFetch(`${BASE}/users`),
  getUser: (id) => jsonFetch(`${BASE}/users/${id}`),
  createUser: (username, password, role, tenantId, enabled) => jsonFetch(`${BASE}/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, role, tenantId, enabled }),
  }),
  updateUser: (id, role, enabled, tenantId, password) => jsonFetch(`${BASE}/users/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role, enabled, tenantId, ...(password ? { password } : {}) }),
  }),
  deleteUser: (id) => jsonFetch(`${BASE}/users/${id}`, { method: 'DELETE' }),
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

// WebSocket URL 构建函数：在 URL 中携带 token 参数
export function getWsUrl() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const base = `${proto}://${location.host}/ws/telemetry`
  if (authToken) {
    return `${base}?token=${encodeURIComponent(authToken)}`
  }
  return base
}

// 向后兼容：保留 wsUrl 常量导出（基于模块加载时的 token 计算）
export const wsUrl = getWsUrl()
// ---- Budget Mode (丐版模式配置) ----
// 根据预算档位限制可见面板，用于在低成本硬件上裁剪功能。
// 经验来源：2026-09-17-react-mount-existing-components-export-signature-dialog-wrap（命名导出用法）
export const BUDGET_MODES = {
  FULL: null,            // 完整版（默认，全部面板可用）
  TOY: 'toy',            // 百元级：仅遥测+航拍+简易地图+状态
  STANDARD: 'standard',  // 千元级：+编队+Mesh+航点+应急
  ADVANCED: 'advanced',  // 进阶版：+光流+红外
}

// 合法预算档位列表（不含 FULL/null，null 表示完整版单独处理）
const VALID_BUDGET_MODES = ['toy', 'standard', 'advanced']

// 规范化 budgetMode：未知值 fallback 到 'standard' 并 console.warn 告警
// null/undefined 原样返回（表示完整版）；合法值原样返回
export function normalizeBudgetMode(mode) {
  if (mode == null) return null
  if (VALID_BUDGET_MODES.includes(mode)) return mode
  console.warn(`Unknown budgetMode "${mode}", falling back to "standard"`)
  return 'standard'
}

// 百元级可用的面板
export const TOY_PANELS = ['telemetry', 'camera', 'map', 'status']
// 千元级可用的面板
export const STANDARD_PANELS = [...TOY_PANELS, 'formation', 'mesh', 'mission', 'emergency']
// 进阶版可用的面板
export const ADVANCED_PANELS = [...STANDARD_PANELS, 'thermal', 'opticalflow']

// 根据预算档位返回可用面板列表；null 表示全部可用（完整版）
// 传入未知 budgetMode 会 fallback 到 'standard' 并告警
export function getAvailablePanels(budgetMode) {
  const mode = normalizeBudgetMode(budgetMode)
  if (!mode || mode === BUDGET_MODES.FULL) return null // null = all
  if (mode === BUDGET_MODES.TOY) return TOY_PANELS
  if (mode === BUDGET_MODES.STANDARD) return STANDARD_PANELS
  if (mode === BUDGET_MODES.ADVANCED) return ADVANCED_PANELS
  return null
}

// 判断单个面板在指定预算档位下是否可用
// 传入未知 budgetMode 会 fallback 到 'standard' 并告警
export function isPanelAvailable(panelName, budgetMode) {
  const mode = normalizeBudgetMode(budgetMode)
  const available = getAvailablePanels(mode)
  if (available === null) return true // 全部可用
  return available.includes(panelName)
}
// ---- Surveillance (安防视频监控 M10) ----
// 安防设备管理 API 挂载在 /api/surveillance 下（独立于 v1 BASE）
// 支持海康/大华/宇视等厂商设备注册、RTSP 流获取、PTZ 云台控制、子网自动发现
const SURVEILLANCE_BASE = '/api/v1/surveillance'

// 查询已注册的安防设备列表（含在线状态、厂商、通道数等）
export async function listSurveillanceDevices() {
  return jsonFetch(`${SURVEILLANCE_BASE}/devices`)
}

// 手动注册安防设备（IP/端口/厂商/用户名/密码/通道数）
export async function registerSurveillanceDevice(device) {
  return jsonFetch(`${SURVEILLANCE_BASE}/devices`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(device),
  })
}

// 注销安防设备
export async function unregisterSurveillanceDevice(deviceId) {
  return jsonFetch(`${SURVEILLANCE_BASE}/devices/${deviceId}`, { method: 'DELETE' })
}

// 获取设备某通道的流地址（RTSP / HLS / WS-FLV），前端按协议渲染
export async function getDeviceStream(deviceId, channel) {
  const qs = channel != null ? `?channel=${encodeURIComponent(channel)}` : ''
  return jsonFetch(`${SURVEILLANCE_BASE}/devices/${deviceId}/stream${qs}`)
}

// PTZ 云台控制：cmd = up|down|left|right|zoom_in|zoom_out|stop
export async function ptzControl(deviceId, cmd) {
  return jsonFetch(`${SURVEILLANCE_BASE}/devices/${deviceId}/ptz`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cmd }),
  })
}

// 子网自动发现安防设备（扫描 192.168.x.0/24 等）
export async function discoverDevices(subnet) {
  return jsonFetch(`${SURVEILLANCE_BASE}/discover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ subnet }),
  })
}

// 查询最近安防事件（报警 / 移动检测 / 离线等），支持分页与时间范围
export async function listSurveillanceEvents(params = {}) {
  const qs = new URLSearchParams(params).toString()
  return jsonFetch(`${SURVEILLANCE_BASE}/events${qs ? '?' + qs : ''}`)
}

// ---- Alarms (报警联动 M11) ----
// 报警事件管理 API 挂载在 /api/alarms 下（独立于 v1 BASE）
// 支持 SSE 实时推送、联动规则管理、一键应急响应触发无人机侦察任务
const ALARM_BASE = '/api/v1/alarms'

// 报警事件 SSE 订阅地址（EventSource 用）
export const alarmStreamUrl = `${location.protocol === 'https:' ? 'https' : 'http'}://${
  location.host
}${ALARM_BASE}/stream`

// 查询报警事件列表（支持 severity/source/type/since/until/acknowledged 等筛选）
export async function listAlarmEvents(params = {}) {
  const qs = new URLSearchParams(params).toString()
  return jsonFetch(`${ALARM_BASE}/events${qs ? '?' + qs : ''}`)
}

// 确认（消除未读）报警事件
export async function acknowledgeAlarm(eventId) {
  return jsonFetch(`${ALARM_BASE}/events/${eventId}/ack`, { method: 'POST' })
}

// 批量确认报警事件
export async function acknowledgeAlarms(eventIds) {
  return jsonFetch(`${ALARM_BASE}/events/ack-batch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ eventIds }),
  })
}

// 查询联动规则列表（报警类型 → 无人机任务模板）
export async function listAlarmRules() {
  return jsonFetch(`${ALARM_BASE}/rules`)
}

// 创建联动规则
export async function createAlarmRule(rule) {
  return jsonFetch(`${ALARM_BASE}/rules`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(rule),
  })
}

// 更新联动规则
export async function updateAlarmRule(id, rule) {
  return jsonFetch(`${ALARM_BASE}/rules/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(rule),
  })
}

// 删除联动规则
export async function deleteAlarmRule(id) {
  return jsonFetch(`${ALARM_BASE}/rules/${id}`, { method: 'DELETE' })
}

// 测试联动规则（模拟触发，不真正派发无人机）
export async function testAlarmRule(id) {
  return jsonFetch(`${ALARM_BASE}/rules/${id}/test`, { method: 'POST' })
}

// 一键应急响应：选中报警事件 → 触发无人机侦察任务
export async function triggerEmergencyResponse(eventId, payload = {}) {
  return jsonFetch(`${ALARM_BASE}/events/${eventId}/respond`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

// 查询联动日志（报警 → 规则 → 无人机任务 → 执行结果）
export async function listLinkageLogs(params = {}) {
  const qs = new URLSearchParams(params).toString()
  return jsonFetch(`${ALARM_BASE}/linkage-logs${qs ? '?' + qs : ''}`)
}
// ---- 命名导出：tracking / geofence / droneLock / autodispatch / videostream / voiceintercom / scenarios / inspection / health / maintenance / commAdapt / mapping / voiceCmd / cityTwin / delivery / show（供面板组件 import）----
export const {
  getFlightTrack, replayTrack, getLastKnown, getLostDrones, getSearchGuide, scanLostDrones,
  createGeofenceZone, listGeofenceZones, getGeofenceZone, updateGeofenceZone, deleteGeofenceZone, getGeofenceBreaches, checkGeofence,
  lockDrone, unlockDrone, getLockStatus, getLockedDrones, getAllLockStates, clearLockState,
  triggerAutoDispatch, getAutoDispatchHistory, getAutoDispatchActive, abortAutoDispatch, getAutoDispatchConfig, updateAutoDispatchConfig,
  getVideoStreamUrl, startVideoStream, stopVideoStream, getActiveVideoStreams,
  startVoiceIntercom, stopVoiceIntercom, broadcastVoice,
  getScenarioTemplates, getScenarioTemplate, getScenarioTemplatesByType, launchScenario, getActiveScenarioLaunches, getScenarioLaunchHistory, abortScenarioLaunch, getScenarioLaunchStatus, startScenarioDrill, getScenarioDrillResult, getScenarioDrillHistory,
  createInspectionTask, listInspectionTasks, getInspectionTask, startInspectionTask, abortInspectionTask, getInspectionProgress, getInspectionReport, getInspectionAnomalies, getInspectionPhotos,
  getDroneHealth, getFleetHealth, getDroneHealthHistory, getComponentHealth, getHealthWarnings,
  listMaintenanceRecords, createMaintenanceRecord, updateMaintenanceRecord, getMaintenancePredictions, getDroneMaintenancePredictions, getMaintenanceSchedule,
  getCommLinks, getCommLink, getCommScore, getCommDecision, executeCommFailover, getCommFailoverHistory, getCommConfig, updateCommConfig,
  createMappingTask, listMappingTasks, getMappingTask, planMappingRoute, getMappingPhotos, generateMappingResult, getMappingResult,
  parseVoiceCommand, executeVoiceCommand, confirmVoiceCommand, broadcastVoiceMessage, getVoiceStatus, broadcastVoiceAlert, getVoiceHistory, getVoicePending,
  createCityModel, listCityModels, getCityModel, getCitySituation, createCitySimulation, getCitySimulation, createCityMarker, listCityMarkers,
  createDeliveryTask, listDeliveryTasks, getDeliveryTask, startDeliveryTask, abortDeliveryTask, optimizeDeliveryRoute, deliverDeliveryTask, getDeliveryStatus, confirmDeliveryTask, searchLandingSites,
  createShowFormation, listShowFormations, getShowFormation, calculateShowPositions, createShowTask, listShowTasks, getShowTask, startShowTask, abortShowTask, getShowActions, configureShowMusicSync,
  login, refreshToken,
  listTenants, getTenant, createTenant, updateTenant, deleteTenant,
  listUsers, getUser, createUser, updateUser, deleteUser,
} = api
