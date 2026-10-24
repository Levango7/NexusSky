// 飞行统计（航程 / 时长 / 最高高度）——从 App.jsx 的内联 useMemo 抽出来的纯函数。
//
// 为什么要抽：原来"没有轨迹"返回的是 `maxAlt: 0`，而展示层写的是
// `stats.maxAlt ? stats.maxAlt.toFixed(0) + ' m' : '--'` —— 于是**贴地飞行（真 0 米）**
// 和**一条轨迹都还没有**在界面上长成同一个 `--`，操作员读不出区别（GAPS §八-2）。
// 这里把两种情况拆开：算不了 ⇒ `null`（不知道）；算出来是 0 ⇒ 就是 0。
//
// 展示侧配套用 `fmtMeters`：**只有 null/undefined/NaN 才降级成 `--`**，0 照原样显示。
import { haversine } from './geo.js'

/**
 * 由时间升序的轨迹点算出面板上那条 strip 需要的三个数。
 *
 * 语义与原内联实现保持一致的地方：`alt` 缺失的点按 0 参与取最大（不新增判断，
 * 免得把"修 0/缺失混淆"这一批撑成一次遥测字段大改）。
 *
 * @param {Array<{lat:number, lon:number, alt?:number, ts:number}>|null|undefined} track
 * @returns {{dist:number, dur:number, maxAlt:number|null}} `maxAlt` 为 `null` 表示"无法计算"，
 *          不是"高度为 0"
 */
export function flightStatsOf(track) {
  if (!Array.isArray(track) || track.length < 2) return { dist: 0, dur: 0, maxAlt: null }
  let d = 0
  for (let i = 1; i < track.length; i++) d += haversine(track[i - 1], track[i])
  const dur = (track[track.length - 1].ts - track[0].ts) / 1000
  const maxAlt = Math.max(...track.map((p) => p.alt || 0))
  return { dist: d / 1000, dur, maxAlt }
}

/**
 * 米数展示：0 是合法读数，只有真正缺失才是 `--`。
 *
 * @param {number|null|undefined} v
 * @param {number} digits 小数位
 * @returns {string} 例如 `12 m` / `0 m`，缺失时 `--`
 */
export function fmtMeters(v, digits = 0) {
  if (v == null || Number.isNaN(v)) return '--'
  return `${Number(v).toFixed(digits)} m`
}
