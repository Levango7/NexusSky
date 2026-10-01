/**
 * 数值格式化与档位配色（纯函数，供测试直接覆盖）。
 *
 * 抽出前 `fmtDur` 内嵌在 App.jsx，`fmtPct` / `fmtMs` / `gradeColor` 是 CvEvalPanel
 * 组件体内的闭包——测试要挂载整个组件树才够得着，而它们与 React 毫无关系。
 *
 * 所有函数的共同契约：**null / undefined 一律渲染成"未知"（-- 或 dim 色），
 * 绝不退化成 0 或绿色。** 把"没数据"显示成"正常值"是监控界面最典型的误导。
 */

/**
 * 秒 → mm:ss。
 *
 * 契约：`0`、负数、null、undefined 一律返回 '--:--'，而不是 '--:00'。
 * 飞行时长为 0 与"没有时长记录"是两回事，混起来会显示成已飞 0 秒。
 *
 * @param {number} sec 秒
 * @returns {string} mm:ss
 */
export function fmtDur(sec) {
  if (!sec || sec < 0) return '--:--'
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/**
 * 百分比格式化（输入是 0-1 比例值），null → '--'。
 *
 * @param {number|null} v 0-1 的比例值
 * @returns {string}
 */
export function fmtPct(v) {
  return v == null ? '--' : `${(v * 100).toFixed(1)}%`
}

/**
 * 毫秒格式化，null → '--'。
 *
 * @param {number|null} v 毫秒
 * @returns {string}
 */
export function fmtMs(v) {
  return v == null ? '--' : `${v.toFixed(1)} ms`
}

/** 档位配色：达标绿、临界黄、不达标红。 */
export const GRADE_OK = '#2de2a5'
export const GRADE_WARN = '#ffc857'
export const GRADE_BAD = '#ff5d5d'
export const GRADE_UNKNOWN = 'var(--dim)'

/**
 * 指标档位配色。
 *
 * 用于"越大越好"（识别率）与"越小越好"（误检比）两类指标。
 * 阈值边界取闭区间：`value <= good` / `value >= good` 算达标，
 * 因此**恰好等于阈值时算达标**，不是临界。
 *
 * @param {number|null} value 指标值
 * @param {number} good 达标阈值
 * @param {number} warn 临界阈值
 * @param {boolean} lowerIsBetter true 表示越小越好（如误检比）
 * @returns {string} CSS 颜色
 */
export function gradeColor(value, good, warn, lowerIsBetter) {
  if (value == null) return GRADE_UNKNOWN
  const hit = lowerIsBetter ? value <= good : value >= good
  if (hit) return GRADE_OK
  const mid = lowerIsBetter ? value <= warn : value >= warn
  return mid ? GRADE_WARN : GRADE_BAD
}
