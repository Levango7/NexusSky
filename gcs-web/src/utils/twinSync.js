// M13 数字孪生实时同步（twin-state-sync 帧 / TWIN_STATE_SYNC 30055）的前端归一化。
//
// 帧形状（TelemetryWebSocketHandler）：{ type:'twin-state-sync', sysid, data:<TwinStateSyncMsg>, timestamp }
// data 字段与 MAVLink 原始单位一致：twinLat/twinLon 1E7 度、twinAlt mm、
// twinHeading cdeg、twinVelocity m/s、driftMeters m、twinBattery %（255=未知）。
//
// 纯函数（不碰 DOM/Date.now 之外的任何环境），便于 vitest 直测；
// Age 计算（ageMs）由调用方传入 receivedAt/now，测试不依赖真实时钟。

/** battery u8 的未知哨兵（MAVLink battery_remaining 惯例）。 */
export const TWIN_BATTERY_UNKNOWN = 255

/**
 * 归一化一条孪生同步帧的 data 为展示用字段。
 * 字段缺失/非法时输出 null（面板显示 '--'），绝不抛异常——WS 帧来自网络。
 *
 * @param {Object|null|undefined} raw 帧 data（TwinStateSyncMsg 序列化）
 * @param {number} receivedAt 前端收到该帧的本地时间戳（ms）
 * @returns {Object|null} 归一化结果，非法帧返回 null：
 *   { sysid, lat, lon, altM, headingDeg, velocity, driftM, battery, syncTimestamp, receivedAt }
 */
export function normalizeTwinState(raw, receivedAt) {
  if (!raw || typeof raw !== 'object') return null
  const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)

  const lat = num(raw.twinLat) != null ? raw.twinLat / 1e7 : null
  const lon = num(raw.twinLon) != null ? raw.twinLon / 1e7 : null
  const altM = num(raw.twinAlt) != null ? raw.twinAlt / 1000 : null
  const headingCdeg = num(raw.twinHeading)
  const headingDeg = headingCdeg != null ? ((headingCdeg / 100) % 360 + 360) % 360 : null
  const batteryRaw = num(raw.twinBattery)

  return {
    sysid: num(raw.sysId) != null ? raw.sysId : null,
    lat,
    lon,
    altM,
    headingDeg,
    velocity: num(raw.twinVelocity),
    driftM: num(raw.driftMeters),
    // 255=未知 → null（面板显示 '--'）；0-100 保留原值
    battery: batteryRaw == null || batteryRaw === TWIN_BATTERY_UNKNOWN ? null : batteryRaw,
    syncTimestamp: num(raw.syncTimestamp),
    receivedAt,
  }
}

/**
 * 同步新鲜度分档：孪生按每 sysid 1Hz 节拍发布，超过 5s 没有新帧视为失联。
 *
 * @param {Object} state normalizeTwinState 的输出
 * @param {number} now 当前时间戳（ms）
 * @returns {'fresh'|'stale'} 5s 内有帧为 fresh
 */
export function twinSyncFreshness(state, now) {
  if (!state || state.receivedAt == null) return 'stale'
  return now - state.receivedAt <= 5000 ? 'fresh' : 'stale'
}
