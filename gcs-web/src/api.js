const BASE = '/api/v1'

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

// jsonFetch 会自动附带 Authorization: Bearer，所以 url 一旦被外部值带出本前端，
// 等于把令牌送到别的宿主（CodeQL js/request-forgery #1）。只放行同源 /api/v1/ 路径。
const API_PREFIX = '/api/v1/'
const ORIGIN_PROBE = 'http://nexussky.invalid'

export function resolveApiUrl(url) {
  if (typeof url !== 'string' || !url) throw new Error(`非法请求 URL：${String(url)}`)
  let parsed
  try {
    parsed = new URL(url, ORIGIN_PROBE)
  } catch {
    throw new Error(`非法请求 URL：${url}`)
  }
  // origin 不变 ⇒ 既不是绝对 URL / 协议相对 URL（//host），也没有靠 .. 段爬出本站
  if (parsed.origin !== ORIGIN_PROBE) throw new Error(`拒绝跨源请求：${url}`)
  if (!parsed.pathname.startsWith(API_PREFIX)) throw new Error(`拒绝非 API 路径：${parsed.pathname}`)
  return parsed.pathname + parsed.search
}

async function jsonFetch(rawUrl, options = {}) {
  const url = resolveApiUrl(rawUrl)
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

  // 401 响应：token 过期或无效。清除令牌，并让界面切回登录态。
  // **不整页 reload**：本前端是 SPA（视图切换不产生路由），location.pathname
  // 永远不含 /login，旧代码那道"避免循环跳转"的守卫恒为假——于是登录输错密码
  // 也会触发刷新，错误提示被一起毁掉（用户只见页面闪一下）；而作业中任何后台
  // 401 又会把整个页面连现场一起刷掉。改为只发事件，由 useAuth 翻登录态，
  // 调用方的错误信息（下面这句）照常抛给界面显示。
  if (res.status === 401) {
    clearAuthToken()
    const message = '认证已过期，请重新登录'
    window.dispatchEvent(new CustomEvent('nexus-auth-expired', { detail: { message } }))
    throw new Error(message)
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

  // 当日**库内**历史轨迹（重启安全）：GET /api/v1/flightlog/track?sysid=&day=
  // 与 getTrack / getFlightTrack 的区别：那两条吃 FlightTrackStore 的有界内存窗口
  // （默认 3600 点，且后端重启后只剩 loadLastKnownPositions 塞的 1 个点）；
  // 这条吃 flight_log，按操作员本地日返回 {lat,lon,alt,ts}，不受重启影响。
  // day 省略时由后端按当天解释，所以这里不能拼成 day=undefined。
  getDayTrack: (sysid, day) => {
    const qs = new URLSearchParams({ sysid })
    if (day != null) qs.set('day', day)
    return jsonFetch(`${BASE}/flightlog/track?${qs}`)
  },

  // ---- Formation (编队表演 M1) ----
  // 创建编队：members=sysid 数组 + 队形参数 + 参考点经纬高
  createFormation: (payload) =>
    jsonFetch(`${BASE}/formation`, {
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
    jsonFetch(`${BASE}/spray`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),

  // 查询喷洒状态：泵状态 / 剩余液量 / 喷洒速率
  getSprayStatus: (sysid) => jsonFetch(`${BASE}/spray/by-sysid/${sysid}`),

  // 夹爪控制：开/关
  controlGripper: (sysid, open) =>
    // 夹爪命令后端本来就有（MAV_CMD_NEXUS_GRIPPER_CONTROL：START=抓取 / DROP=释放），
    // 但只挂在「按配送序列 id」的控制端点上，前端手里只有 sysid ⇒ 走按 sysid 的口径。
    jsonFetch(`${BASE}/delivery/by-sysid/${sysid}/control`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: open ? 'START' : 'DROP' }),
    }),

  // 物流配送序列：站点列表 + 状态
  getDeliverySequence: (sysid) => jsonFetch(`${BASE}/delivery/by-sysid/${sysid}`),

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

  // ---- Thermal Vision (热成像可视化) ----
  getThermalSources: () => jsonFetch('/api/v1/vision/thermal/sources'),

  // ---- Environment (气象环境) ----
  getEnvStatus: () => jsonFetch('/api/v1/env/status'),
  getEnvAlerts: () => jsonFetch('/api/v1/env-alerts'),

  // ---- Link Simulation (链路仿真) ----
  getLinkSimProfiles: () => jsonFetch('/api/v1/link-sim/profiles'),
  getLinkSimStatus: () => jsonFetch('/api/v1/link-sim/status'),
  setLinkSimDamage: (params) => jsonFetch('/api/v1/link-sim/damage', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
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
  // SSE 流令牌：EventSource 带不了 Authorization 头，开流前换 60s 单次用短令牌
  fetchStreamToken: () => fetchStreamToken(),

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

// ---- Dock (机巢管控 F2) ----
// DockPanel 此前用裸 fetch 自己拼 Authorization 头——绕过统一 API 层意味着：
// 无超时控制、无 401 统一处理（token 过期时面板只显示 HTTP 401 而不翻登录态）、
// 也没有 apiUrlGuard 的同源校验。现统一走 jsonFetch。
export const docks = {
  list: () => jsonFetch(`${BASE}/docks`),

  detail: (id) => jsonFetch(`${BASE}/docks/${id}`),

  metrics: (id, days = 7) => jsonFetch(`${BASE}/docks/${id}/metrics?days=${days}`),

  schedules: (id) => jsonFetch(`${BASE}/docks/${id}/schedules`),

  register: (body) =>
    jsonFetch(`${BASE}/docks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  command: (id, body) =>
    jsonFetch(`${BASE}/docks/${id}/commands`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  createSchedule: (id, body) =>
    jsonFetch(`${BASE}/docks/${id}/schedules`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  setScheduleEnabled: (id, sid, enabled) =>
    jsonFetch(`${BASE}/docks/${id}/schedules/${sid}/enable`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled }),
    }),
}

// ---- Defects & Work Orders (缺陷闭环 F4) ----
// 与 docks 同因收编：DefectPanel 此前也自己拼 Authorization 头（无超时/无 401 处理）。
export const defects = {
  list: () => jsonFetch(`${BASE}/defects`),

  workOrders: () => jsonFetch(`${BASE}/defects/work-orders`),

  createWorkOrder: (body) =>
    jsonFetch(`${BASE}/defects/work-orders`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  transitionWorkOrder: (id, action) =>
    jsonFetch(`${BASE}/defects/work-orders/${id}/transition`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action }),
    }),

  verifyWorkOrder: (id, sysid) =>
    jsonFetch(`${BASE}/defects/work-orders/${id}/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sysid }),
    }),

  setStatus: (id, status) =>
    jsonFetch(`${BASE}/defects/${id}/status`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    }),
}

// ---- Emergency Orchestration (应急任务编排 M9) ----
// 注意：应急编排 API 挂载在 /api/v1/emergency 下（独立于 v1 BASE，但带 v1 前缀）
const EMERGENCY_BASE = '/api/v1/emergency'

export const emergencyOrch = {
  // 获取场景预设列表
  getScenarios: () => jsonFetch(`${EMERGENCY_BASE}/scenarios`),

  // 启动编排：type 是**后端 int 0..3**（就是 GET /scenarios 返回的 type 字段），body={ centerLat, centerLon, radius }
  //   centerLat/centerLon 单位是 1E7 度，radius 单位是米（后端 EmergencyOrchController/Javadoc 明写）。
  //   原注释写的 type=earthquake|mudslide|fire|custom 与 radiusKm 是错的 —— 后端 @PathVariable 是 int，
  //   且后端读的是 "radius" 不是 "radiusKm"（radiusKm 在后端出现 0 次）。那个错误注释是前端
  //   两个面板「一键启动」长期 400 / 半径被静默忽略的直接来源。
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

// ---- Disaster Comm (灾害应急通信 P2-4) ----
// base 路径 /api/v1/disaster（独立于 v1 BASE）
const DISASTER_BASE = '/api/v1/disaster'

export const disaster = {
  // 获取灾害模式状态（active/inactive + 触发原因 + 恢复率）
  getStatus: () => jsonFetch(`${DISASTER_BASE}/status`),

  // 激活灾害模式（body 含 reason）
  activate: (reason) =>
    jsonFetch(`${DISASTER_BASE}/activate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason }),
    }),

  // 退出灾害模式
  deactivate: () =>
    jsonFetch(`${DISASTER_BASE}/deactivate`, { method: 'POST' }),

  // 获取分簇拓扑（簇ID/簇头/成员/簇半径）
  getClusters: () => jsonFetch(`${DISASTER_BASE}/clusters`),

  // 获取 QoS 优先级队列状态（4级队列深度/吞吐）
  getQoS: () => jsonFetch(`${DISASTER_BASE}/qos`),

  // 获取异构链路桥接状态（WiFi/LTE/LoRa/Sat 链路质量）
  getLinks: () => jsonFetch(`${DISASTER_BASE}/links`),
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
  FULL: null,                       // 完整版（默认，全部面板可用）
  TOY: 'toy',                       // 百元级：仅遥测+航拍+简易地图+状态
  STANDARD: 'standard',             // 千元级：+编队+Mesh+航点+应急
  ADVANCED: 'advanced',             // 进阶版：+光流+红外
  EMERGENCY_TOY: 'emergency-toy',         // 应急百元级：百元级+mesh通信+应急编排+热源搜救
  EMERGENCY_STANDARD: 'emergency-standard', // 应急千元级：千元级+安防监控+报警联动+空地指挥+灾害通信
}

// 合法预算档位列表（不含 FULL/null，null 表示完整版单独处理）
const VALID_BUDGET_MODES = ['toy', 'standard', 'advanced', 'emergency-toy', 'emergency-standard']

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
export const ADVANCED_PANELS = [...STANDARD_PANELS, 'thermal', 'opticalflow', 'surveillance']
// 应急百元级可用的面板：基础操控 + mesh通信 + 应急编排 + 热源搜救
export const EMERGENCY_TOY_PANELS = ['telemetry', 'camera', 'map', 'status', 'mesh', 'emergency', 'thermal']
// 应急千元级可用的面板：千元级 + 灾害应急全能力
export const EMERGENCY_STANDARD_PANELS = [...STANDARD_PANELS, 'thermal', 'surveillance', 'alarm', 'disastercomm', 'unifiedcmd', 'videofusion']

// 根据预算档位返回可用面板列表；null 表示全部可用（完整版）
// 传入未知 budgetMode 会 fallback 到 'standard' 并告警
export function getAvailablePanels(budgetMode) {
  const mode = normalizeBudgetMode(budgetMode)
  if (!mode || mode === BUDGET_MODES.FULL) return null // null = all
  if (mode === BUDGET_MODES.TOY) return TOY_PANELS
  if (mode === BUDGET_MODES.STANDARD) return STANDARD_PANELS
  if (mode === BUDGET_MODES.ADVANCED) return ADVANCED_PANELS
  if (mode === BUDGET_MODES.EMERGENCY_TOY) return EMERGENCY_TOY_PANELS
  if (mode === BUDGET_MODES.EMERGENCY_STANDARD) return EMERGENCY_STANDARD_PANELS
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
// 安防设备管理 API 挂载在 /api/v1/surveillance 下（未复用通用 BASE 常量）
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

// 安防设备事件 SSE 订阅地址（EventSource 用）。
// 与报警流同机制：先 fetchStreamToken() 换短令牌，再经 ?streamToken= 开流
export function surveillanceStreamUrl(deviceId, streamToken) {
  const base = `${location.protocol === 'https:' ? 'https' : 'http'}://${
    location.host
  }${SURVEILLANCE_BASE}/devices/${encodeURIComponent(deviceId)}/events`
  return streamToken ? `${base}?streamToken=${encodeURIComponent(streamToken)}` : base
}

// 浏览器可播实时画面（MJPEG）的 <img src>。
//
// <img> 标签不会也不能携带 Authorization 头，所以生产链上开流前必须先用正常
// 认证换一枚短命单次用令牌，经 ?streamToken= 传入 —— 与 SSE 两条流完全同机制。
// **不把长效 JWT 放进 URL**：该 URL 会长期躺在访问日志与 Referer 里，等于把 bearer
// 公开给所有能看到日志的人（StreamTokenService 存在的理由就是这个）。
//
// fps 只是帧率提示；帧源掉线时后端结束响应，此时这里拿到的地址需要重新换令牌重挂，
// 调用方负责在需要时重新生成（每次生成都会换一枚新令牌）。
export function mjpegPreviewUrl(deviceId, streamToken, fps) {
  const base = `${location.protocol === 'https:' ? 'https' : 'http'}://${
    location.host
  }${SURVEILLANCE_BASE}/devices/${encodeURIComponent(deviceId)}/mjpeg`
  const qs = []
  if (streamToken) qs.push(`streamToken=${encodeURIComponent(streamToken)}`)
  if (fps && fps > 0) qs.push(`fps=${encodeURIComponent(fps)}`)
  return qs.length ? `${base}?${qs.join('&')}` : base
}

// ---- Alarms (报警联动 M11) ----
// 报警事件管理 API 挂载在 /api/v1/alarms 下（未复用通用 BASE 常量）
// 支持 SSE 实时推送、联动规则管理、一键应急响应触发无人机侦察任务
const ALARM_BASE = '/api/v1/alarms'

// 签发 SSE 流令牌：EventSource 无法携带 Authorization 头，生产链上开流前
// 必须先用正常认证换一枚 60s 单次用短令牌（每次建流/重连都取新令牌）
export async function fetchStreamToken() {
  const resp = await jsonFetch('/api/v1/auth/stream-token', { method: 'POST' })
  return resp && resp.token ? resp.token : ''
}

// 报警事件 SSE 订阅地址（EventSource 用）。
// 鉴权经 ?streamToken= 短令牌，不把长效 JWT 放进 URL（访问日志可见）
export function alarmStreamUrl(streamToken) {
  const base = `${location.protocol === 'https:' ? 'https' : 'http'}://${location.host}${ALARM_BASE}/stream`
  return streamToken ? `${base}?streamToken=${encodeURIComponent(streamToken)}` : base
}

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
// ---- AirGroundCoordination (空地一体化协同 P2) ----
// base 路径 /api/v1/air-ground（独立于 v1 BASE）
const AIR_GROUND_BASE = '/api/v1/air-ground'

// 获取空地一体化融合态势（安防设备 + 无人机 + Mesh + 灾区边界等综合数据）
export async function getAirGroundSituation() {
  return jsonFetch(`${AIR_GROUND_BASE}/situation`)
}

// 报警事件触发无人机侦察（安防检测目标 → 无人机航拍确认）
// 后端口径：POST /api/v1/air-ground/recon —— 报警字段走 **body**，不是路径参数
// （2026-10-21 实测：前端原来调 /air-ground/recon/alarm/{eventId}，后端没有这条端点）
export async function triggerReconFromAlarm(eventId, payload = {}) {
  return jsonFetch(`${AIR_GROUND_BASE}/recon`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...payload, eventId }),
  })
}

// PTZ 跟踪联动（安防摄像头 PTZ 自动跟踪检测目标）
// 后端口径：POST /api/v1/air-ground/ptz-track —— 目标位置走 body 的 lat/lon
// （后端字段名是 lat/lon，不是 targetLat/targetLon；2026-10-21 实测前端原来两处都错）
// confidence：后端 GeoTarget.isReliable() 要求 >= 0.6，否则静默返回 LOW_CONFIDENCE
//（看着像成功、其实没动）。这是**操作员手动触发**（点按钮即意图），故按高置信度下发。
export async function triggerPtzTracking(deviceId, payload = {}) {
  const { targetLat, targetLon, ...rest } = payload
  return jsonFetch(`${AIR_GROUND_BASE}/ptz-track`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...rest,
      lat: targetLat ?? rest.lat,
      lon: targetLon ?? rest.lon,
      confidence: rest.confidence ?? 1,
      sourceDeviceId: rest.sourceDeviceId ?? deviceId,
    }),
  })
}

// ---- VideoFusion (视频融合 P1) ----
// base 路径 /api/v1/video-fusion（独立于 v1 BASE）
// 安防 RTSP 流 + 无人机航拍画面同屏显示，支持画中画、录制
const VIDEO_FUSION_BASE = '/api/v1/video-fusion'

// 获取安防摄像头流列表（返回 deviceId, name, rtspUrl, online）
export async function getSurveillanceStreams() {
  return jsonFetch(`${VIDEO_FUSION_BASE}/surveillance/streams`)
}

// 获取无人机航拍画面列表（返回 sysid, name, feedUrl, online）
export async function getDroneVideoFeeds() {
  return jsonFetch(`${VIDEO_FUSION_BASE}/drone/feeds`)
}

// 开始录制（deviceId 为安防摄像头 ID 或无人机 sysid）
export async function startRecording(deviceId) {
  return jsonFetch(`${VIDEO_FUSION_BASE}/recording/${deviceId}/start`, { method: 'POST' })
}

// 停止录制
export async function stopRecording(deviceId) {
  return jsonFetch(`${VIDEO_FUSION_BASE}/recording/${deviceId}/stop`, { method: 'POST' })
}

// ---- 命名导出：tracking / geofence / droneLock / autodispatch / videostream / voiceintercom / scenarios / inspection / health / maintenance / commAdapt / mapping / voiceCmd / cityTwin / delivery / show / videoFusion（供面板组件 import）----
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
