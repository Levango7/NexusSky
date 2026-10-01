/**
 * 选中态判定的**单一真相来源**。
 *
 * 为什么单独抽一个函数（2026-10-01 修复）：
 * `App.jsx` 传下来的 `selected` 是**无人机对象**（`drones.find(d => d.sysid === selectedSysid)`
 * 的结果，或 undefined），但 `MapView` 内部两处写的是 `selected === sysid`——
 * 对象与数字比较**恒为 false**，于是「选中机标记高亮」（createDroneMarkerSVG 的
 * size/箭头/虚线环）与「选中机轨迹渐变」（buildTrackGradient 分支）两段代码
 * 从未执行过，且运行期没有任何信号（日志、告警、测试都没有）。
 *
 * 契约（有意严格）：
 *   - `selected` 必须是**对象**（含 `sysid`）或 null/undefined。
 *     传数字会返回 false——这是刻意的：宁可高亮不亮，也不要静默回到旧 bug。
 *   - `sysid` 允许是数字或数字字符串（`Object.keys(multiTracks)` 出来的是字符串），
 *     内部统一按 Number 比较。
 *
 * 独立成无依赖模块而不是就地内联，是为了能绕开 maplibre-gl 的
 * `?worker&url` 导入在测试环境里的解析问题，让这条契约可被单测钉住。
 */
export function isDroneSelected(selected, sysid) {
  if (selected == null || typeof selected !== 'object') return false
  if (sysid == null) return false
  const selectedSysid = selected.sysid
  if (selectedSysid == null) return false
  return Number(selectedSysid) === Number(sysid)
}
