/**
 * 轨迹点合并：把「内存实时窗口」与「库内当日历史」拼成一条折线。
 *
 * 为什么需要它（本轮实测，不是推测）：
 *   - 后端 `FlightTrackStore` 是**有界内存 deque**（默认 3600 点 ≈ 20Hz 下 3 分钟），
 *     重启时 `loadLastKnownPositions()` 只塞 **1 个**最后已知点。
 *   - `GET /api/v1/drones/{sysid}/track` 吃的就是这块内存，所以重启后只剩一个点；
 *     前端 `useDrones.loadTelemetry` 又把该结果**整体覆盖**到 `track` state 上。
 *   - 而 `GET /api/v1/flightlog/track?day=&sysid=`（FlightLogController.java:47）
 *     从 `flight_log` 出**当天**轨迹，不受重启影响——这条通路一直存在，前端从未调用。
 *   ⇒ 症状：重启或刷新后，地图上的历史轨迹消失，只剩最后位置一个点。
 *
 * 三个来源的点形状一致，都是 `{lat, lon, alt, ts}`：
 *   - `DroneViews.track()`（api/dto/DroneViews.java:114-122）
 *   - `FlightLogController.track()` 的 `Map.of("lat",…,"lon",…,"alt",…,"ts",…)`
 *   - `useWebSocket` 实时追加的点
 * 所以这里**不做** `timestampMs` 之类的适配层：注意 `GET /api/v1/tracking/{sysid}/track`
 * 序列化的是 `FlightTrackStore.TrackPoint`，那个才是 `timestampMs`，它走的是
 * TrackingPanel 那条通路，不经过本函数。
 */

/**
 * 主轨迹保留的最大点数（约 5Hz × 300s = 1500，超出按最近优先截断）。
 * 原先定义在 `hooks/useDrones.js` 里；搬到共享模块是为了让「装载历史」与
 * 「实时追加」两条路共用同一个窗口值——分处两文件时改一处不改另一处不会有任何测试失败。
 */
export const MAX_TRACK_POINTS = 1500

/** 取点的排序键；非有限值（缺字段、字符串、NaN）返回 null 表示该点不可用。 */
function tsOf(point) {
  return point && Number.isFinite(point.ts) ? point.ts : null
}

/**
 * 合并实时点与库内历史点，按 `ts` 升序返回，**不修改入参**。
 *
 * 口径：
 *   - 同一 `ts` 只保留一个点，且**保留 live 那个对象本身**（不重建、不丢字段）——
 *     实时点比历史点新，历史点不得把它顶掉。
 *   - `ts` 缺失或非有限的点直接丢弃：它们无法参与排序，留在折线里会画出零长段或 NaN 坐标。
 *   - `limit > 0` 时保留**最新**的 limit 个；`limit <= 0` 表示不截断（与后端 `/track` 同口径）。
 *
 * @param {Array<{lat:number,lon:number,alt:number,ts:number}>} livePoints 内存/实时窗口点
 * @param {Array<{lat:number,lon:number,alt:number,ts:number}>} persistedPoints 库内当日点
 * @param {number} [limit=MAX_TRACK_POINTS] 窗口上限
 * @returns {Array} 升序、按 ts 去重后的点数组（新数组）
 */
export function mergeTrack(livePoints, persistedPoints, limit = MAX_TRACK_POINTS) {
  const live = Array.isArray(livePoints) ? livePoints : []
  const persisted = Array.isArray(persistedPoints) ? persistedPoints : []

  const byTs = new Map()
  // 先灌 live：后续同 ts 的历史点会被下面 `has` 判断挡掉。
  for (const point of live) {
    const ts = tsOf(point)
    if (ts === null) continue
    if (!byTs.has(ts)) byTs.set(ts, point)
  }
  for (const point of persisted) {
    const ts = tsOf(point)
    if (ts === null) continue
    if (!byTs.has(ts)) byTs.set(ts, point)
  }

  const merged = [...byTs.values()].sort((a, b) => a.ts - b.ts)
  if (limit > 0 && merged.length > limit) {
    return merged.slice(merged.length - limit)
  }
  return merged
}
