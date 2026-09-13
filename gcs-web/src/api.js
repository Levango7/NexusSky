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
}

export const wsUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${
  location.host
}/ws/telemetry`
