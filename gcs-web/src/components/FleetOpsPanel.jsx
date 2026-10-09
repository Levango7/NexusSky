import React from 'react'
import { cardStyle, fmtTime } from '../utils/panelUtils.js'
import {
  normalizeTaskAssignment,
  normalizeConflictAlert,
  normalizeTaskStatus,
  normalizeAlarmTrigger,
  normalizeAlarmAck,
  normalizeSurveillanceStatus,
  TASK_ID_IS_HASH,
} from '../utils/schedulingFrames.js'

/**
 * 机队协同态势面板（M10 集群调度三帧 + 4a 空地联动三帧）。
 *
 * ## 为什么这个面板存在
 *
 * 这六个 WS 帧后端自 2026-10-05 起全部有真实生产者，但前端直到 2026-10-06 才补上消费分支——
 * 在此之前帧照常到达、无错、无告警，然后被 `onmessage` 末尾静默丢弃。后果是 M10
 * （集群智能调度，对外主卖点之一）的全部价值对操作员不可见：谁被派了哪个任务、
 * 两机何时会撞、任务进度走到哪一步，都只存在于云端日志里。
 *
 * ## 边界（面板内如实展示，不做美化）
 *
 * ① **taskId 是不可逆哈希**（`String.hashCode() & 0xFFFFFFFF`）。面板标为「协议任务号」，
 *    可用于帧流内关联（同任务的 assignment/status 同值），**不能**拿去查 REST 接口。
 * ② **ALARM_TRIGGER 无 alarmId**，故 trigger 与 ack 协议层无法关联。面板分两段展示，
 *    不做"这条报警已派机"的连线——那会是编造。
 * ③ 帧里的 `sourceDeviceId` 是 u16 哈希，非真实设备 id；设备名请看 AlarmPanel（SSE，有真 id）。
 * ④ 安防设备状态 `totalCameras` 恒为 1、`uptimeSec` 重启清零——协议现状，不在 UI 上假装是长跑值。
 * ⑤ ConflictType.AIRSPACE(0) 当前不可达（机对几何扫描只产出 PATH/COLLISION）；
 *    TaskStatus.FAILED(3) 无生命周期路径。码表保留但不假设出现。
 */

// 三条流共用的列表卡片骨架（风格与 AiDecisionPanel 一致）
function EventListCard({ title, tag, count, children, maxHeight = 260 }) {
  return (
    <div style={{ ...cardStyle, padding: 0, overflow: 'hidden', flex: '1 1 340px', minWidth: 300 }}>
      <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 11, color: 'var(--text)' }}>{title}（{count}）</span>
        <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>{tag}</span>
      </div>
      <div style={{ maxHeight, overflowY: 'auto' }}>{children}</div>
    </div>
  )
}

function EmptyHint({ main, sub }) {
  return (
    <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center', lineHeight: 1.6 }}>
      {main}<br />
      <span style={{ fontSize: 9 }}>{sub}</span>
    </div>
  )
}

/** 语义标签小徽标。tone 直接吃码表里的 color 字段。 */
function Tag({ tone, children }) {
  return (
    <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 3, color: tone || 'var(--dim)', border: `1px solid ${tone || 'var(--dim)'}` }}>
      {children}
    </span>
  )
}

/** 冲突倒计时条：≤10s 判 imminent（与后端 ConflictScanService 分档口径一致）。 */
function CountdownBar({ seconds, tone }) {
  const s = typeof seconds === 'number' && Number.isFinite(seconds) ? seconds : null
  // 0-60s 线性占满，60s 以上留满格（超出部分不再压低视觉紧迫度）
  const ratio = s == null ? 0 : Math.max(0, Math.min(1, 1 - s / 60))
  return (
    <div style={{ height: 3, background: 'var(--line-2)', borderRadius: 2, overflow: 'hidden', marginTop: 3 }}>
      <div style={{ width: `${ratio * 100}%`, height: '100%', background: tone || 'var(--dim)', transition: 'width 200ms' }} />
    </div>
  )
}

export default function FleetOpsPanel({
  taskAssignments = [],
  conflictAlerts = [],
  taskStatuses = [],
  alarmTriggers = [],
  alarmAcks = [],
  surveillanceStatuses = {},
}) {
  // 归一化 + 过滤空壳帧（data 缺失时归一化出全 null 的行不展示，与事件流语义不符）
  const assignRows = taskAssignments
    .map((raw) => normalizeTaskAssignment(raw, raw.receivedAt))
    .filter((r) => r && (r.protocolTaskId != null || r.assignedSysid != null))
  const conflictRows = conflictAlerts
    .map((raw) => normalizeConflictAlert(raw, raw.receivedAt))
    .filter((r) => r && (r.conflictingSysid != null || r.minDistanceM != null))
  const statusRows = taskStatuses
    .map((raw) => normalizeTaskStatus(raw, raw.receivedAt))
    .filter((r) => r && (r.protocolTaskId != null || r.status != null))
  const triggerRows = alarmTriggers
    .map((raw) => normalizeAlarmTrigger(raw, raw.receivedAt))
    .filter((r) => r && (r.alarmType != null || r.description != null || r.lat != null))
  const ackRows = alarmAcks
    .map((raw) => normalizeAlarmAck(raw, raw.receivedAt))
    .filter((r) => r && (r.alarmId != null || r.droneSysid != null))
  // 安防设备状态是按 deviceId 分桶的周期状态（1Hz），取最新值排序即可
  const deviceRows = Object.values(surveillanceStatuses || {})
    .map((raw) => normalizeSurveillanceStatus(raw, raw.receivedAt))
    .filter((r) => r && r.deviceId != null)
    .sort((a, b) => a.deviceId - b.deviceId)

  // 任务状态汇总：按 protocolTaskId 归并，取最新一条状态 + 最高进度。
  // 协议任务号是帧流内唯一可用的任务键（见文件头边界①）。
  const byTask = new Map()
  for (const s of statusRows) {
    if (s.protocolTaskId == null) continue
    const prev = byTask.get(s.protocolTaskId)
    if (!prev || (s.receivedAt ?? 0) >= (prev.receivedAt ?? 0)) byTask.set(s.protocolTaskId, s)
  }

  return (
    <div className="page">
      <h2 style={{ fontSize: 16, margin: '0 0 4px 0', color: 'var(--text)' }}>机队协同态势</h2>
      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginBottom: 10, lineHeight: 1.6 }}>
        集群调度（M10 30048-30050）与空地联动（4a 30057-30059）实时流。
        {TASK_ID_IS_HASH && ' 任务号为协议侧 u32 哈希，可用于帧流内关联，不能反查 REST 接口。'}
      </div>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        {/* ===== 任务分配 ===== */}
        <EventListCard title="任务分配" tag="TASK_ASSIGNMENT 30048" count={assignRows.length}>
          {assignRows.length === 0 ? (
            <EmptyHint main="暂无任务分配事件" sub="触发：POST /api/v1/scheduling/requests/{id}/assign 或 pollNextTask" />
          ) : assignRows.map((r, i) => (
            <div key={`a${r.protocolTaskId}-${i}`} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                <Tag tone={r.priorityColor}>{r.priorityLabel || '--'}</Tag>
                <span style={{ fontSize: 10 }}>{r.taskTypeLabel || '任务'}</span>
                <span style={{ fontSize: 10, color: 'var(--cyan)' }}>
                  → Drone-{r.assignedSysid ?? '?'}
                </span>
                <span style={{ fontSize: 9, color: 'var(--dim-2)', marginLeft: 'auto' }}>
                  #{r.protocolTaskId ?? '--'}
                </span>
              </div>
              <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                目标 {r.lat != null ? r.lat.toFixed(5) : '--'} / {r.lon != null ? r.lon.toFixed(5) : '--'}
                {' · '}{r.altM ?? '--'} m · {fmtTime(r.receivedAt)}
              </div>
            </div>
          ))}
        </EventListCard>

        {/* ===== 冲突告警 ===== */}
        <EventListCard title="冲突告警" tag="CONFLICT_ALERT 30049" count={conflictRows.length}>
          {conflictRows.length === 0 ? (
            <EmptyHint main="无冲突" sub="触发：ConflictScanService 5s 周期对在线无人机做 4D 轨迹预测两两扫描" />
          ) : conflictRows.map((r, i) => (
            <div key={`c${r.conflictingSysId}-${i}`} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                <Tag tone={r.severityColor}>{r.severityLabel || '--'}</Tag>
                <span style={{ fontSize: 10 }}>{r.conflictTypeLabel || '冲突'}</span>
                <span style={{ fontSize: 10, color: 'var(--warn)' }}>
                  Drone-{r.sysid ?? '?'} ↔ Drone-{r.conflictingSysid ?? '?'}
                </span>
                <span style={{ fontSize: 10, color: r.imminent ? 'var(--crit)' : 'var(--dim)', marginLeft: 'auto' }}>
                  {r.timeToConflictS != null ? `${r.timeToConflictS.toFixed(1)}s` : '--'}
                </span>
              </div>
              <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                最近距离 {r.minDistanceM != null ? r.minDistanceM.toFixed(1) : '--'} m · {fmtTime(r.receivedAt)}
              </div>
              <CountdownBar seconds={r.timeToConflictS} tone={r.severityColor} />
            </div>
          ))}
        </EventListCard>

        {/* ===== 任务状态 ===== */}
        <EventListCard title="任务进度" tag="TASK_STATUS 30050" count={byTask.size}>
          {byTask.size === 0 ? (
            <EmptyHint main="暂无任务状态" sub="触发：POST /api/v1/scheduling/tasks/{id}/start 或 /complete" />
          ) : [...byTask.entries()].map(([taskId, s]) => (
            <div key={String(taskId)} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <Tag tone={s.statusColor}>{s.statusLabel || '--'}</Tag>
                <span style={{ fontSize: 10, color: 'var(--dim-2)', marginLeft: 'auto' }}>#{taskId}</span>
                <span style={{ fontSize: 10 }}>{s.progressPercent ?? 0}%</span>
              </div>
              <div style={{ height: 3, background: 'var(--line-2)', borderRadius: 2, marginTop: 4, overflow: 'hidden' }}>
                <div style={{ width: `${s.progressPercent ?? 0}%`, height: '100%', background: s.statusColor, transition: 'width 200ms' }} />
              </div>
            </div>
          ))}
        </EventListCard>

        {/* ===== 报警触发 ===== */}
        <EventListCard title="报警触发" tag="ALARM_TRIGGER 30057" count={triggerRows.length}>
          {triggerRows.length === 0 ? (
            <EmptyHint main="暂无报警" sub="触发：POST /api/v1/alarms/events 后由 AlarmLinkageEngine 发布" />
          ) : triggerRows.map((r, i) => (
            <div key={`t${i}-${r.sourceTimestamp}`} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                <Tag tone={r.severityColor}>{r.severityLabel || '--'}</Tag>
                <span style={{ fontSize: 10 }}>{r.alarmTypeLabel || '报警'}</span>
                <span style={{ fontSize: 9, color: 'var(--dim-2)', marginLeft: 'auto' }}>{fmtTime(r.receivedAt)}</span>
              </div>
              <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {r.description || '（无描述）'}
              </div>
              <div style={{ fontSize: 9, color: 'var(--dim-2)' }}>
                位置 {r.lat != null ? r.lat.toFixed(5) : '--'} / {r.lon != null ? r.lon.toFixed(5) : '--'}
                {' · '}设备哈希 {r.sourceDeviceIdHash ?? '--'}
              </div>
            </div>
          ))}
        </EventListCard>

        {/* ===== 派遣确认 ===== */}
        <EventListCard title="派遣确认" tag="ALARM_ACK 30058" count={ackRows.length}>
          {ackRows.length === 0 ? (
            <EmptyHint main="暂无派遣" sub="触发：AUTO_DISPATCH 派遣成功后逐受派无人机发布" />
          ) : ackRows.map((r, i) => (
            <div key={`k${r.alarmId}-${i}`} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                <Tag tone={r.ackResultColor}>{r.ackResultLabel || '--'}</Tag>
                <span style={{ fontSize: 10, color: 'var(--cyan)' }}>Drone-{r.droneSysid ?? '?'}</span>
                <span style={{ fontSize: 10, marginLeft: 'auto' }}>ETA {r.etaText}</span>
              </div>
              <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                alarmId {r.alarmId ?? '--'} · {fmtTime(r.receivedAt)}
              </div>
            </div>
          ))}
        </EventListCard>

        {/* ===== 安防设备状态（周期状态，按 deviceId 分桶） ===== */}
        <EventListCard title="安防设备" tag="SURVEILLANCE_STATUS 30059 · 1Hz" count={deviceRows.length} maxHeight={200}>
          {deviceRows.length === 0 ? (
            <EmptyHint main="暂无设备状态帧" sub="触发：SurveillanceStatusPusher 1s 周期（无 WS 客户端时跳过）" />
          ) : deviceRows.map((d) => (
            <div key={d.deviceId} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', gap: 6, alignItems: 'center' }}>
              <Tag tone={d.statusColor}>{d.statusLabel || '--'}</Tag>
              <span style={{ fontSize: 10 }}>设备 {d.deviceId}</span>
              <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>{d.vendorLabel || '--'}</span>
              <span style={{ fontSize: 9, color: 'var(--dim-2)', marginLeft: 'auto' }}>
                {d.onlineCameras ?? '--'}/{d.totalCameras ?? '--'} 路 · 运行 {d.uptimeText}
              </span>
            </div>
          ))}
        </EventListCard>
      </div>

      {/* 边界提示常驻：这两条是协议实况，不是可修复的 UI 缺陷 */}
      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 12, lineHeight: 1.7 }}>
        协议边界（非 UI 缺陷）：① 任务号为 u32 哈希，不可反查 REST taskId；
        ② ALARM_TRIGGER 无 alarmId，报警触发与派遣确认**无法在协议层关联**，
        故本面板不画关联线；③ 设备帧 totalCameras 恒为 1、uptimeSec 重启清零。
      </div>
    </div>
  )
}