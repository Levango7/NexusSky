/**
 * 电池电量的展示分级（单一真相源）。
 *
 * 抽出前这个逻辑在仓库里有 **6 份**，且分成两套互不相同的阈值：
 *
 *   DashboardPanel / DroneList / TelemetryCharts / TelemetryPanel   20 / 40
 *   EmergencyOrchPanel / UnifiedCommandPanel                       15 / 30
 *
 * 后果不是"多了几行"：同一架飞机在总览面板显示绿色、在指挥表格里显示黄色，
 * 操作员会以为数据不一致。复制品的真正危害是**改一处不改另一处**。
 *
 * 现统一到 20 / 40（6 份里占 4 份，且与 CSS 里的 `.batt-warn` 类一致）。
 * 若产品口径要改成 15 / 30，只改下面两个常量即可——所有调用点会自动跟随。
 * 改动前请确认是否要同步产品文档与后端告警阈值。
 */

export const BATT_OK = '#2de2a5'
export const BATT_WARN = '#ffb224'
export const BATT_CRIT = '#ff5d5d'

/** 严重电量阈值（含）。 */
export const BATT_CRIT_AT = 20
/** 警告电量阈值（含）。 */
export const BATT_WARN_AT = 40

/**
 * 电量分级（唯一判级函数，其余两个只是把同一结果翻译成不同表现形式）。
 *
 * 契约：`null` / `undefined` / 空串 / NaN 一律返回 `null`（表示"未知"，不套任何样式），
 * **不是**返回 'batt-ok' / 绿色——把"没数据"画成"电量充足"是误导。
 * 空串必须单独判掉：JS 里 `Number('') === 0`，不特判会被当成 0% 电量显示成红色。
 *
 * @param {*} b 电量百分比
 * @returns {'crit'|'warn'|'ok'|null}
 */
export function battLevel(b) {
  if (b == null || b === '') return null
  const n = Number(b)
  if (Number.isNaN(n)) return null
  if (n <= BATT_CRIT_AT) return 'crit'
  if (n <= BATT_WARN_AT) return 'warn'
  return 'ok'
}

/**
 * 电量 → CSS 类名。
 *
 * 契约：无数据显示空串（不套任何样式）。
 *
 * @param {*} b 电量百分比
 * @returns {''|'batt-crit'|'batt-warn'|'batt-ok'}
 */
export function battClass(b) {
  const lv = battLevel(b)
  if (lv == null) return ''
  return `batt-${lv}`
}

/**
 * 电量 → 十六进制颜色（用于 SVG 图表等不能吃 CSS 变量的场合）。
 *
 * @param {*} b 电量百分比
 * @returns {string}
 */
export function battColor(b) {
  const lv = battLevel(b)
  if (lv == null) return 'var(--dim)'
  return lv === 'crit' ? BATT_CRIT : lv === 'warn' ? BATT_WARN : BATT_OK
}

/**
 * 电量 → CSS 变量颜色（用于表格单元格的行内样式）。
 *
 * 与 {@link battColor} 判级完全一致，只是表现形式不同——两者的阈值不允许各改各的，
 * 测试里对三者做了交叉断言。
 *
 * @param {*} b 电量百分比
 * @returns {string}
 */
export function battColorVar(b) {
  const lv = battLevel(b)
  if (lv == null) return 'var(--dim)'
  return lv === 'crit' ? 'var(--crit)' : lv === 'warn' ? 'var(--warn)' : 'var(--ok)'
}
