// M11 AI 自主决策三帧（decision-event / adaptive-path / edge-task-status）的前端归一化。
//
// 帧形状（TelemetryWebSocketHandler）：{ type, sysid, data:<MavlinkMessage>, timestamp }
// data 字段与 MAVLink 原始单位一致：newLat/newLon 1E7 度、windDirection cdeg、
// confidence 0.0-1.0、processingTimeMs ms。
//
// 码表与生产端一一对应，改协议时两侧同步：
// - DECISION_EVENT(30051) decisionType/reason ↔ drone-sim VirtualDrone.decisionTypeCodeOf / reasonCodeOf
// - ADAPTIVE_PATH(30052) adjustmentReason ↔ AdaptivePathMsg javadoc（0=风场 1=障碍物 2=地形 3=电量）
// - EDGE_TASK_STATUS(30053) taskType/status ↔ EdgeTaskStatusMsg javadoc
//
// 纯函数（不碰 DOM / 时钟），便于 vitest 直测；receivedAt 由调用方传入，测试不依赖真实时钟。

/** DECISION_EVENT(30051) decisionType 码 → 展示元数据（255=未知）。 */
export const DECISION_TYPE_META = {
  0: { label: '返航', color: 'var(--warn)' },
  1: { label: '避障', color: 'var(--cyan)' },
  2: { label: '自适应航径', color: 'var(--ok)' },
  3: { label: '紧急降落', color: 'var(--crit)' },
  255: { label: '未知', color: 'var(--dim)' },
}

/** DECISION_EVENT(30051) reason 触发原因码 → 标签（255=未知）。 */
export const DECISION_REASON_META = {
  0: '低电量',
  1: '链路丢失',
  2: 'GPS 退化',
  3: '强风',
  4: '障碍物',
  5: '电量优化',
  255: '未知',
}

/** ADAPTIVE_PATH(30052) adjustmentReason 码 → 标签。 */
export const ADJUST_REASON_META = {
  0: '风场',
  1: '障碍物',
  2: '地形',
  3: '电量',
}

/** EDGE_TASK_STATUS(30053) taskType 码 → 标签。 */
export const EDGE_TASK_TYPE_META = {
  0: '视频分析',
  1: '传感器融合',
  2: '目标检测',
}

/** EDGE_TASK_STATUS(30053) status 码 → 展示元数据。 */
export const EDGE_TASK_STATUS_META = {
  0: { label: '待处理', color: 'var(--dim)' },
  1: { label: '处理中', color: 'var(--cyan)' },
  2: { label: '完成', color: 'var(--ok)' },
  3: { label: '失败', color: 'var(--crit)' },
}

/** 有限数字守卫：非有限数字返回 null（WS 帧来自网络，绝不产出 NaN）。 */
function num(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/**
 * 归一化一条决策事件帧的 data 为展示用字段。
 * 字段缺失/非法时输出 null 字段（面板显示 '--'），非对象输入返回 null，绝不抛异常。
 *
 * @param {Object|null|undefined} raw 帧 data（DecisionEventMsg 序列化）
 * @param {number} receivedAt 前端收到该帧的本地时间戳（ms）
 * @returns {Object|null} { sysid, decisionType, decisionTypeLabel, decisionTypeColor,
 *                          reason, reasonLabel, triggerValue, confidence, confidencePct,
 *                          sourceTimestamp, receivedAt }
 */
export function normalizeDecisionEvent(raw, receivedAt) {
  if (!raw || typeof raw !== 'object') return null
  const decisionType = num(raw.decisionType)
  const typeMeta = decisionType != null ? DECISION_TYPE_META[decisionType] : null
  const reason = num(raw.reason)
  const confidence = num(raw.confidence)
  return {
    sysid: num(raw.sysId),
    decisionType,
    decisionTypeLabel: typeMeta ? typeMeta.label : decisionType != null ? `码 ${decisionType}` : null,
    decisionTypeColor: typeMeta ? typeMeta.color : 'var(--dim)',
    reason,
    reasonLabel: reason != null ? (DECISION_REASON_META[reason] ?? `码 ${reason}`) : null,
    triggerValue: num(raw.triggerValue),
    confidence,
    confidencePct: confidence != null ? Math.round(confidence * 100) : null,
    sourceTimestamp: num(raw.timestamp),
    receivedAt,
  }
}

/**
 * 归一化一条自适应航迹改写帧的 data 为展示用字段。
 * 单位换算：newLat/newLon 1E7 → 度；windDirection cdeg → 度（0-360 归一）。
 *
 * @param {Object|null|undefined} raw 帧 data（AdaptivePathMsg 序列化）
 * @param {number} receivedAt 前端收到该帧的本地时间戳（ms）
 * @returns {Object|null} { sysid, lat, lon, altM, windSpeed, windDirDeg,
 *                          originalWaypointSeq, adjustmentReason, reasonLabel, receivedAt }
 */
export function normalizeAdaptivePath(raw, receivedAt) {
  if (!raw || typeof raw !== 'object') return null
  const lat = num(raw.newLat) != null ? raw.newLat / 1e7 : null
  const lon = num(raw.newLon) != null ? raw.newLon / 1e7 : null
  const windDirCdeg = num(raw.windDirection)
  const windDirDeg = windDirCdeg != null ? ((windDirCdeg / 100) % 360 + 360) % 360 : null
  const adjustmentReason = num(raw.adjustmentReason)
  return {
    sysid: num(raw.sysId),
    lat,
    lon,
    altM: num(raw.newAlt),
    windSpeed: num(raw.windSpeed),
    windDirDeg,
    originalWaypointSeq: num(raw.originalWaypointSeq),
    adjustmentReason,
    reasonLabel: adjustmentReason != null ? (ADJUST_REASON_META[adjustmentReason] ?? `码 ${adjustmentReason}`) : null,
    receivedAt,
  }
}

/**
 * 归一化一条边缘任务状态帧的 data 为展示用字段。
 *
 * @param {Object|null|undefined} raw 帧 data（EdgeTaskStatusMsg 序列化）
 * @param {number} receivedAt 前端收到该帧的本地时间戳（ms）
 * @returns {Object|null} { sysid, taskId, taskType, taskTypeLabel, status, statusLabel,
 *                          statusColor, processingMs, resultSize, receivedAt }
 */
export function normalizeEdgeTaskStatus(raw, receivedAt) {
  if (!raw || typeof raw !== 'object') return null
  const taskType = num(raw.taskType)
  const status = num(raw.status)
  const statusMeta = status != null ? EDGE_TASK_STATUS_META[status] : null
  return {
    sysid: num(raw.sysId),
    taskId: num(raw.edgeTaskId),
    taskType,
    taskTypeLabel: taskType != null ? (EDGE_TASK_TYPE_META[taskType] ?? `码 ${taskType}`) : null,
    status,
    statusLabel: statusMeta ? statusMeta.label : status != null ? `码 ${status}` : null,
    statusColor: statusMeta ? statusMeta.color : 'var(--dim)',
    processingMs: num(raw.processingTimeMs),
    resultSize: num(raw.resultSize),
    receivedAt,
  }
}
