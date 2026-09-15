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
}

export const wsUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${
  location.host
}/ws/telemetry`
