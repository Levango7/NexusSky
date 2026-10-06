// M10 集群调度三帧 + 4a 空地联动三帧的前端归一化。
//
// 帧形状（TelemetryWebSocketHandler.WS_TYPE_MAP → /ws/telemetry）：
//   { type, sysid, data:<MavlinkMessage>, timestamp }
//
// 这六帧在 2026-10-06 之前**后端已生产、前端零消费**——帧到达 useWebSocket 的
// onmessage 末尾，无匹配分支即被静默丢弃。后果是 M10 的全部对外价值对操作员不可见：
// 谁被派了哪个任务、两机何时会撞、任务进度走到哪一步，都只存在于云端日志里。
//
// 码表与生产端一一对应，改协议时两侧同步：
// - TASK_ASSIGNMENT(30048) taskType/priority ↔ TaskAssignmentMsg javadoc
// - CONFLICT_ALERT(30049) conflictType/severity ↔ ConflictAlertMsg javadoc
// - TASK_STATUS(30050) status ↔ TaskStatusMsg javadoc
// - ALARM_TRIGGER(30057) alarmType/severity ↔ AlarmTriggerMsg javadoc
// - ALARM_ACK(30058) ackResult ↔ AlarmAckMsg javadoc
// - SURVEILLANCE_STATUS(30059) deviceType/status ↔ SurveillanceStatusMsg javadoc
//
// 纯函数（不碰 DOM / 时钟），便于 vitest 直测；receivedAt 由调用方传入，测试不依赖真实时钟。

/**
 * ⚠️ taskId 的不可逆性（协议边界，必须在 UI 上说明）
 *
 * 帧里的 `taskId` 是 REST 侧字符串 id 的 `hashCode() & 0xFFFFFFFF`
 * （`TaskAssignmentService.taskIdToU32`），**不可逆**。因此：
 *   - ✅ 可以在帧流内部做关联：同一个任务的 assignment / status 帧哈希相同，
 *      可用它把"派了任务"和"进度 60%"串起来；
 *   - ❌ 不能用它反查 REST `/scheduling/requests/{id}`：消费方无法从帧拿回原始
 *      taskId，除非自持一张哈希→id 的映射表（云端才有）。
 *
 * 面板因此把该字段标为「协议任务号」而非「任务 ID」，避免操作员拿它去查 REST 接口。
 */
export const TASK_ID_IS_HASH = true

// 码表形状统一约定：全部映射到 { label, color }，没有一张映射到裸字符串。
// 最初这里是混着的（类型/厂商三张给字符串，优先级/状态给 {label,color}），
// 于是消费方写 `META[1].label` 时对字符串表拿到 undefined——本模块自己的第一版
// 测试就是这么翻的。既然六张表必然被同一批面板读，形状不统一就是等着人踩。

/** TASK_ASSIGNMENT(30048) taskType 码 → 标签（javadoc：0=测绘 1=喷洒 2=中继 3=搜救）。 */
export const TASK_TYPE_META = {
  0: { label: '测绘', color: 'var(--cyan)' },
  1: { label: '喷洒', color: 'var(--ok)' },
  2: { label: '中继', color: 'var(--dim)' },
  3: { label: '搜救', color: 'var(--crit)' },
}

/** TASK_ASSIGNMENT(30048) priority 码 → 标签（REST 0-10 等宽分档：0-2/3-5/6-8/9-10）。 */
export const TASK_PRIORITY_META = {
  1: { label: '低', color: 'var(--dim)' },
  2: { label: '中', color: 'var(--cyan)' },
  3: { label: '高', color: 'var(--warn)' },
  4: { label: '紧急', color: 'var(--crit)' },
}

/** CONFLICT_ALERT(30049) conflictType 码 → 标签（javadoc：0=空域 1=航径 2=碰撞）。 */
export const CONFLICT_TYPE_META = {
  0: { label: '空域', color: 'var(--warn)' },
  1: { label: '航径', color: 'var(--cyan)' },
  2: { label: '碰撞', color: 'var(--crit)' },
}

/** CONFLICT_ALERT(30049) severity 1-4 → 展示元数据。 */
export const CONFLICT_SEVERITY_META = {
  1: { label: '低', color: 'var(--dim)' },
  2: { label: '中', color: 'var(--cyan)' },
  3: { label: '高', color: 'var(--warn)' },
  4: { label: '危急', color: 'var(--crit)' },
}

/**
 * TASK_STATUS(30050) status 码 → 展示元数据。
 *
 * FAILED(3) 当前**无生命周期路径**——调度模型里没有"执行失败"态
 * （见 ROADMAP M10 诚实边界）。码表保留该项是为了协议完整：若将来加失败态，
 * 面板无需改前端即可正确显示，而不是退化成"码 3"。
 */
export const TASK_STATUS_META = {
  0: { label: '已分配', color: 'var(--dim)' },
  1: { label: '执行中', color: 'var(--cyan)' },
  2: { label: '已完成', color: 'var(--ok)' },
  3: { label: '失败', color: 'var(--crit)' },
  4: { label: '已中止', color: 'var(--warn)' },
}

/** ALARM_TRIGGER(30057) alarmType 码 → 标签（javadoc：0=运动 1=入侵 2=火灾 3=门禁 4=自定义）。 */
export const ALARM_TYPE_META = {
  0: { label: '运动检测', color: 'var(--cyan)' },
  1: { label: '入侵', color: 'var(--warn)' },
  2: { label: '火灾', color: 'var(--crit)' },
  3: { label: '门禁', color: 'var(--warn)' },
  4: { label: '自定义', color: 'var(--dim)' },
}

/** ALARM_TRIGGER(30057) severity 码 → 展示元数据（0=INFO 1=WARN 2=CRITICAL）。 */
export const ALARM_SEVERITY_META = {
  0: { label: '提示', color: 'var(--dim)' },
  1: { label: '警告', color: 'var(--warn)' },
  2: { label: '严重', color: 'var(--crit)' },
}

/** ALARM_ACK(30058) ackResult 码 → 展示元数据（0=已收到 1=已开始响应 2=无法响应 3=拒绝）。 */
export const ALARM_ACK_META = {
  0: { label: '已收到', color: 'var(--cyan)' },
  1: { label: '已开始响应', color: 'var(--ok)' },
  2: { label: '无法响应', color: 'var(--warn)' },
  3: { label: '拒绝', color: 'var(--crit)' },
}

/** SURVEILLANCE_STATUS(30059) deviceType 码 → 标签（0=海康 1=大华 2=宇视 3=其他）。 */
export const SURVEILLANCE_VENDOR_META = {
  0: { label: '海康', color: 'var(--cyan)' },
  1: { label: '大华', color: 'var(--cyan)' },
  2: { label: '宇视', color: 'var(--cyan)' },
  3: { label: '其他', color: 'var(--dim)' },
}

/** SURVEILLANCE_STATUS(30059) status 码 → 展示元数据（0=在线 1=离线 2=故障 3=维护）。 */
export const SURVEILLANCE_STATUS_META = {
  0: { label: '在线', color: 'var(--ok)' },
  1: { label: '离线', color: 'var(--dim)' },
  2: { label: '故障', color: 'var(--crit)' },
  3: { label: '维护', color: 'var(--warn)' },
}

/**
 * 载荷守卫：只接受 plain object。
 *
 * 刻意排除数组与 null：`typeof [] === 'object'`，只判 typeof 会让 `[]` 走进归一化
 * 并产出一堆 null 字段的"看起来正常"的对象——面板于是渲染出一行全 -- 的幽灵记录，
 * 而调用方以为收到了数据。数组不是合法的 MAVLink 消息序列化形态，直接判 null。
 *
 * @param {*} v
 * @returns {boolean}
 */
function isRecord(v) {
  return v != null && typeof v === 'object' && !Array.isArray(v)
}

/** 有限数字守卫：非有限数字返回 null（WS 帧来自网络，绝不产出 NaN）。 */
function num(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/** 非空字符串守卫（帧里 description 可能是空串或 null）。 */
function str(v) {
  return typeof v === 'string' && v.length > 0 ? v : null
}

/**
 * 查码表取 label，未命中回退「码 N」，code 为 null 回退 null。
 * 全部码表形状为 { label, color }（见上方形状约定），故统一读 .label。
 */
function lookup(table, code) {
  const meta = lookupMeta(table, code)
  return meta == null ? null : meta.label
}

/** 查带 {label,color} 的码表，未命中回退灰。 */
function lookupMeta(table, code) {
  if (code == null) return null
  return table[code] ?? { label: `码 ${code}`, color: 'var(--dim)' }
}

/**
 * 归一化一条任务分配帧的 data（TASK_ASSIGNMENT 30048）。
 * 单位换算：targetLat/targetLon 1E7 → 度；targetAlt 已是米。
 *
 * @param {Object|null|undefined} raw 帧 data（TaskAssignmentMsg 序列化）
 * @param {number} receivedAt 前端收到该帧的本地时间戳（ms）
 * @returns {Object|null} { sysid, protocolTaskId, lat, lon, altM, taskType, taskTypeLabel,
 *                          priority, priorityLabel, priorityColor, assignedSysid, receivedAt }
 */
export function normalizeTaskAssignment(raw, receivedAt) {
  if (!isRecord(raw)) return null
  const taskType = num(raw.taskType)
  const priority = num(raw.priority)
  const priorityMeta = lookupMeta(TASK_PRIORITY_META, priority)
  return {
    // 发送方是云端调度器（SENDER_SYSID），真正干活的是 assignedSysid；
    // 两个都带上前端才能在"谁派的"和"派给谁"之间连线。
    sysid: num(raw.sysId),
    protocolTaskId: num(raw.taskId),
    lat: num(raw.targetLat) != null ? raw.targetLat / 1e7 : null,
    lon: num(raw.targetLon) != null ? raw.targetLon / 1e7 : null,
    altM: num(raw.targetAlt),
    taskType,
    taskTypeLabel: lookup(TASK_TYPE_META, taskType),
    priority,
    priorityLabel: priorityMeta ? priorityMeta.label : null,
    priorityColor: priorityMeta ? priorityMeta.color : 'var(--dim)',
    assignedSysid: num(raw.assignedSysId),
    receivedAt,
  }
}

/**
 * 归一化一条冲突告警帧的 data（CONFLICT_ALERT 30049）。
 * 单位换算：minDistance 米、timeToConflict 秒，均为 float 原值不做取整
 * （取整会让"0.4 秒后冲突"显示成 0 秒，正是最需要看清的那一档）。
 *
 * @param {Object|null|undefined} raw 帧 data（ConflictAlertMsg 序列化）
 * @param {number} receivedAt
 * @returns {Object|null} { sysid, conflictingSysid, minDistanceM, timeToConflictS,
 *                          imminent, conflictType, conflictTypeLabel, severity,
 *                          severityLabel, severityColor, receivedAt }
 */
export function normalizeConflictAlert(raw, receivedAt) {
  if (!isRecord(raw)) return null
  const conflictType = num(raw.conflictType)
  const severity = num(raw.severity)
  const severityMeta = lookupMeta(CONFLICT_SEVERITY_META, severity)
  const ttConflict = num(raw.timeToConflict)
  return {
    sysid: num(raw.sysId),
    conflictingSysid: num(raw.conflictingSysId),
    minDistanceM: num(raw.minDistance),
    timeToConflictS: ttConflict,
    // imminent 供面板高亮用：倒计时越近越红。阈值取 10s —— 与后端
    // ConflictScanService 的严重度分档口径一致，避免同一冲突在两处显示不同紧急度。
    imminent: ttConflict != null && ttConflict <= 10,
    conflictType,
    conflictTypeLabel: lookup(CONFLICT_TYPE_META, conflictType),
    severity,
    severityLabel: severityMeta ? severityMeta.label : null,
    severityColor: severityMeta ? severityMeta.color : 'var(--dim)',
    receivedAt,
  }
}

/**
 * 归一化一条任务状态帧的 data（TASK_STATUS 30050）。
 * 进度越界（>100 或 <0）夹到区间内而不是原样透出——协议说 0-100，
 * 出了区间说明生产端有问题，UI 不该跟着画出负进度条。
 *
 * @param {Object|null|undefined} raw 帧 data（TaskStatusMsg 序列化）
 * @param {number} receivedAt
 * @returns {Object|null} { sysid, protocolTaskId, status, statusLabel, statusColor,
 *                          progressPercent, sourceTimestamp, receivedAt }
 */
export function normalizeTaskStatus(raw, receivedAt) {
  if (!isRecord(raw)) return null
  const status = num(raw.status)
  const statusMeta = lookupMeta(TASK_STATUS_META, status)
  const progress = num(raw.progressPercent)
  return {
    sysid: num(raw.sysId),
    protocolTaskId: num(raw.taskId),
    status,
    statusLabel: statusMeta ? statusMeta.label : null,
    statusColor: statusMeta ? statusMeta.color : 'var(--dim)',
    progressPercent: progress != null ? Math.min(100, Math.max(0, progress)) : null,
    sourceTimestamp: num(raw.timestamp),
    receivedAt,
  }
}

/**
 * 归一化一条报警触发帧的 data（ALARM_TRIGGER 30057）。
 * 单位换算：lat/lon 1E7 → 度；alt mm → m。
 *
 * ⚠️ **该消息没有 alarmId 字段**（协议实况，见 AlarmTriggerMsg 的 7 个字段：
 * timestamp/lat/lon/sourceDeviceId/alt/alarmType/severity/description）。
 * 而 ALARM_ACK(30058) 带 alarmId —— 于是 **trigger 帧与 ack 帧在协议层无法关联**：
 * 操作员看到"某处发生火灾"和"Drone-3 已在响应"，却无法确认后者是不是这条报警的响应。
 * 这是协议设计缺口，不是前端归一化能补的（补一个假 alarmId 只会把缺口藏起来）。
 * 当前唯一可用的关联键是 `sourceDeviceId` 哈希 + `timestamp` 邻近。
 * 修复路径：在 AlarmTriggerMsg 增补 alarmId（需同步 mavlink-core LEN/CRC_EXTRA、
 * MavlinkMessageInfo、兼容脚本），属协议变更，不在本前端任务范围内。
 *
 * ⚠️ 该消息也没有 sysId：报警是**设备源**帧，帧级 sysid 来自
 * `MavlinkMessageEvent.getSysId()`，对设备源帧无意义。sysid 因此为 null，
 * 面板按"无归属"呈现，不参与按机分组。
 *
 * @param {Object|null|undefined} raw 帧 data（AlarmTriggerMsg 序列化）
 * @param {number} receivedAt
 * @returns {Object|null} { sysid, lat, lon, altM, alarmType, alarmTypeLabel,
 *                          severity, severityLabel, severityColor, sourceDeviceIdHash,
 *                          description, sourceTimestamp, correlatable, receivedAt }
 */
export function normalizeAlarmTrigger(raw, receivedAt) {
  if (!isRecord(raw)) return null
  const alarmType = num(raw.alarmType)
  const severity = num(raw.severity)
  const severityMeta = lookupMeta(ALARM_SEVERITY_META, severity)
  return {
    sysid: num(raw.sysId) != null ? num(raw.sysId) : num(raw.sysid),
    lat: num(raw.lat) != null ? raw.lat / 1e7 : null,
    lon: num(raw.lon) != null ? raw.lon / 1e7 : null,
    altM: num(raw.alt) != null ? raw.alt / 1000 : null,
    alarmType,
    alarmTypeLabel: lookup(ALARM_TYPE_META, alarmType),
    severity,
    severityLabel: severityMeta ? severityMeta.label : null,
    severityColor: severityMeta ? severityMeta.color : 'var(--dim)',
    sourceDeviceIdHash: num(raw.sourceDeviceId),
    description: str(raw.description),
    sourceTimestamp: num(raw.timestamp),
    // 显式告知消费侧"本帧无法与 ack 关联"，让面板据此决定是否显示关联列
    correlatable: false,
    receivedAt,
  }
}

/**
 * 归一化一条报警确认帧的 data（ALARM_ACK 30058）。
 * 这是「报警 → 自动派遣」闭环的关键一帧：操作员需要看到哪台机接了单、还要多久到。
 *
 * ⚠️ 帧里的 alarmId 无法与 ALARM_TRIGGER(30057) 对上——后者没有该字段。
 * 详见 {@link normalizeAlarmTrigger} 的说明。故 alarmId 只作展示，不作关联键。
 *
 * @param {Object|null|undefined} raw 帧 data（AlarmAckMsg 序列化）
 * @param {number} receivedAt
 * @returns {Object|null} { sysid, alarmId, droneSysid, estimatedArrivalSec, etaText,
 *                          ackResult, ackResultLabel, ackResultColor, sourceTimestamp,
 *                          correlatable, receivedAt }
 */
export function normalizeAlarmAck(raw, receivedAt) {
  if (!isRecord(raw)) return null
  const ackResult = num(raw.ackResult)
  const ackMeta = lookupMeta(ALARM_ACK_META, ackResult)
  const eta = num(raw.estimatedArrivalSec)
  return {
    sysid: num(raw.sysId) != null ? num(raw.sysId) : num(raw.sysid),
    alarmId: num(raw.alarmId),
    droneSysid: num(raw.droneSysid),
    estimatedArrivalSec: eta,
    // ETA 直接给字符串，面板不重复做单位换算/哨兵值判断
    etaText: eta != null ? `${Math.round(eta)}s` : '--',
    ackResult,
    ackResultLabel: ackMeta ? ackMeta.label : null,
    ackResultColor: ackMeta ? ackMeta.color : 'var(--dim)',
    sourceTimestamp: num(raw.timestamp),
    correlatable: false,
    receivedAt,
  }
}

/**
 * 归一化一条安防设备状态帧的 data（SURVEILLANCE_STATUS 30059）。
 *
 * ⚠️ 已知语义边界（README「WS_TYPE_MAP 13 帧」条目如实登记）：
 *   - `totalCameras` 恒为 1：单 RTSP 通道模型，无多通道设备；
 *   - `uptimeSec` 是进程内计数，重启清零，`lastEventMs` 不持久化；
 *   - status 的 FAULT(2) / MAINTENANCE(3) 当前**不可达**——设备模型只有
 *     ONLINE/OFFLINE。码表保留以备扩展，面板不得假设它们一定会出现。
 *
 * @param {Object|null|undefined} raw 帧 data（SurveillanceStatusMsg 序列化）
 * @param {number} receivedAt
 * @returns {Object|null} { deviceId, vendor, vendorLabel, status, statusLabel, statusColor,
 *                          onlineCameras, totalCameras, uptimeSec, uptimeText, lastEventMs,
 *                          receivedAt }
 */
export function normalizeSurveillanceStatus(raw, receivedAt) {
  if (!isRecord(raw)) return null
  const deviceType = num(raw.deviceType)
  const status = num(raw.status)
  const statusMeta = lookupMeta(SURVEILLANCE_STATUS_META, status)
  const vendorMeta = lookupMeta(SURVEILLANCE_VENDOR_META, deviceType)
  const uptime = num(raw.uptimeSec)
  return {
    deviceId: num(raw.deviceId),
    vendor: deviceType,
    vendorLabel: vendorMeta ? vendorMeta.label : null,
    status,
    statusLabel: statusMeta ? statusMeta.label : null,
    statusColor: statusMeta ? statusMeta.color : 'var(--dim)',
    onlineCameras: num(raw.onlineCameras),
    totalCameras: num(raw.totalCameras),
    uptimeSec: uptime,
    // 长跑进程下 uptimeSec 会很大，格式化收在这里而不是面板里
    uptimeText: uptime != null ? formatUptime(uptime) : '--',
    lastEventMs: num(raw.lastEventMs),
    receivedAt,
  }
}

/**
 * 把秒数格式化为 "3d 4h" / "2h 15m" / "42s"。
 * 纯函数，面板与测试共用；null/非有限输入返回 '--'。
 *
 * @param {number} totalSec
 * @returns {string}
 */
export function formatUptime(totalSec) {
  const s = num(totalSec)
  if (s == null || s < 0) return '--'
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = Math.floor(s % 60)
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m ${sec}s`
  return `${sec}s`
}