/**
 * 地理计算（纯函数，供测试直接覆盖）。
 *
 * 抽出前 `haversine` 内嵌在 App.jsx、`circularLayout` 内嵌在 CellTowerPanel.jsx，
 * 测试无法触达——要挂载整个组件树才能跑，而这两处逻辑本身与 React 毫无关系。
 */

/** 地球平均半径（米）。 */
export const EARTH_RADIUS_M = 6371000

/**
 * 两点大圆距离（米），Haversine 公式。
 *
 * @param {{lat:number, lon:number}} a 起点
 * @param {{lat:number, lon:number}} b 终点
 * @returns {number} 米
 */
export function haversine(a, b) {
  const dLa = ((b.lat - a.lat) * Math.PI) / 180
  const dLo = ((b.lon - a.lon) * Math.PI) / 180
  const la1 = (a.lat * Math.PI) / 180
  const la2 = (b.lat * Math.PI) / 180
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLo / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h))
}

/**
 * 圆形布局：把 n 个节点均匀分布在以 (cx, cy) 为心、radius 为半径的圆周上。
 *
 * 起始角为 -π/2（正上方），逆时针均匀分布。
 * 单节点落在圆心（而不是圆周上）——只有一个节点时圆周上没有"分布"可言，
 * 放圆心视觉上更合理。
 *
 * @param {Array<{sysid:number}>} nodes
 * @param {number} cx 圆心 x
 * @param {number} cy 圆心 y
 * @param {number} radius 半径
 * @returns {Object<number, {x:number, y:number}>} 以 sysid 为键的坐标表
 */
export function circularLayout(nodes, cx, cy, radius) {
  const n = nodes.length
  if (n === 0) return {}
  if (n === 1) return { [nodes[0].sysid]: { x: cx, y: cy } }
  const layout = {}
  for (let i = 0; i < n; i++) {
    const angle = (2 * Math.PI * i) / n - Math.PI / 2
    layout[nodes[i].sysid] = {
      x: cx + radius * Math.cos(angle),
      y: cy + radius * Math.sin(angle),
    }
  }
  return layout
}
