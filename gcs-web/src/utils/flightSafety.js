/**
 * 飞行命令的二次确认文案（2026-10-01 新增）。
 *
 * 背景：`TelemetryPanel` 的 6 个飞行命令与 `Joystick` 的上锁按钮此前全部是
 * 单击直达后端（arm / disarm / takeoff / start_mission / rtl / kill），
 * 与 QGC / Mission Planner 的交互惯例相反——误触或机队中选错目标机时，
 * 后果不可逆（空中上锁 = 立即坠落）。
 *
 * 判据：命令一旦下发**不可撤销**且在误触时后果不可逆 → 需确认。
 *
 * 刻意**不**确认的命令是 'rtl'：返航是应急回收动作，模态框会在最需要它的
 * 那一秒挡住操作员。QGC 与 Mission Planner 同样不对 RTL 做二次确认。
 * 下面的 `rtl` 模板保留但注释掉，若产品决定改为确认，取消注释即可
 * （`tests/flightCommands.test.js` 里有一条断言会把「刻意不确认」固化成契约）。
 */

/** 空中判定：mode 非 STANDBY 且已解锁。返回 null 表示地面上。 */
export function isAirborne(drone) {
  if (!drone) return null
  if (!drone.armed) return null
  return drone.mode && drone.mode !== 'STANDBY' ? drone.mode : null
}

/** 描述被操作的飞机，让确认框明确指出「对哪一架」——机队场景下 sysid 是唯一可靠标识。命名与 DroneList 一致。 */
export function describeDrone(drone) {
  if (!drone) return '未选择设备'
  const name = drone.callsign || (drone.sysid != null ? `Drone-${drone.sysid}` : '当前飞机')
  const mode = isAirborne(drone)
  return mode ? `${name}（${mode}${drone.armed ? '·已解锁' : ''}）` : name
}

const CONFIRM_TEMPLATES = {
  arm: '解锁 {drone}？\n\n电机将立即开始转动，请确认周围无人员与障碍物。',
  disarm: '上锁 {drone}？{airborne}',
  takeoff: '起飞 {drone}？\n\n将爬升至 {alt} m 并开始自主飞行。',
  start_mission: '开始任务 {drone}？\n\n飞机将脱离人工操纵转入自主飞行航线。',
  kill: '紧急终止 {drone}？\n\n该指令立即切断动力，飞机将从当前高度坠落。仅在失控时使用。',
  // rtl: '返航 {drone}？\n\n飞机将返航并降落。',   ← 刻意不启用，见文件头说明
}

// 空中上锁 = 立即坠落，这句必须显式说清楚，不能只问「确定吗」
function airborneWarning(drone) {
  const mode = isAirborne(drone)
  return mode
    ? `\n\n⚠ 该机当前在空中（${mode}），上锁会导致立即失控坠落。\n若非紧急，请改用「返航」。`
    : '\n\n上锁后需重新解锁才能飞行。'
}

/**
 * 返回待确认的文案；返回 null 表示该命令**无需**确认，调用方应直接执行。
 * @param {string} type   命令类型，与后端 `{"type": ...}` 一致
 * @param {object} drone  目标机对象（DroneList 的元素形状）
 * @param {number} [alt]  起飞高度等可选参数
 */
export function confirmText(type, drone, alt) {
  const tpl = CONFIRM_TEMPLATES[type]
  if (!tpl) return null
  return tpl
    .replace(/\{drone\}/g, describeDrone(drone))
    .replace(/\{airborne\}/g, airborneWarning(drone))
    .replace(/\{alt\}/g, String(alt != null ? alt : 30))
}
