import React from 'react'
import { cardStyle, fmtTime } from '../utils/panelUtils.js'
import {
  normalizeDecisionEvent,
  normalizeAdaptivePath,
  normalizeEdgeTaskStatus,
} from '../utils/aiDecision.js'

// M11 AI 自主决策面板：消费 useWebSocket 注入的三条 WS 事件流。
// - decision-event（DECISION_EVENT 30051）：机载决策引擎变化沿事件
// - adaptive-path（ADAPTIVE_PATH 30052）：自适应航径航点改写（仅真实路径变化时下发）
// - edge-task-status（EDGE_TASK_STATUS 30053）：机载边缘栈逐任务状态
// hook 只存原始帧 + receivedAt，归一化在渲染时做（utils/aiDecision.js 纯函数，便于直测）。
// 风格与 CityTwinPanel / AlertFeed 一致。

// 三条流共用的事件列表卡片骨架（标题 + 协议标注 + 计数 + 滚动列表），内容由 children 提供
function EventListCard({ title, tag, count, children }) {
  return (
    <div style={{ ...cardStyle, padding: 0, overflow: 'hidden', flex: '1 1 380px', minWidth: 320 }}>
      <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 11, color: 'var(--text)' }}>{title}（{count}）</span>
        <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>{tag}</span>
      </div>
      <div style={{ maxHeight: 320, overflowY: 'auto' }}>{children}</div>
    </div>
  )
}

// 空态：主提示 + 次行说明（帧的触发条件，帮操作员区分"没数据"与"功能没开"）
function EmptyHint({ main, sub }) {
  return (
    <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center', lineHeight: 1.6 }}>
      {main}<br />
      <span style={{ fontSize: 9 }}>{sub}</span>
    </div>
  )
}

export default function AiDecisionPanel({ decisionEvents, adaptivePaths, edgeTasks }) {
  // 归一化 + 过滤完全空壳帧（data 缺失时归一化全 null 的行不展示，与事件流语义不符）
  const decisionRows = (decisionEvents || [])
    .map((raw) => normalizeDecisionEvent(raw, raw.receivedAt))
    .filter((e) => e && (e.decisionType != null || e.reason != null || e.confidence != null))
  const adaptiveRows = (adaptivePaths || [])
    .map((raw) => normalizeAdaptivePath(raw, raw.receivedAt))
    .filter((p) => p && (p.lat != null || p.originalWaypointSeq != null || p.adjustmentReason != null))
  const edgeRows = (edgeTasks || [])
    .map((raw) => normalizeEdgeTaskStatus(raw, raw.receivedAt))
    .filter((t) => t && (t.taskId != null || t.taskType != null || t.status != null))

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>AI 自主决策</h2>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        {/* 决策事件流 */}
        <EventListCard title="决策事件" tag="DECISION_EVENT 30051" count={decisionRows.length}>
          {decisionRows.length === 0 ? (
            <EmptyHint main="暂无决策事件" sub="决策事件由机载自主决策引擎在决策变化沿下发；未开启自主决策的设备无帧" />
          ) : (
            decisionRows.map((e, i) => (
              <div key={`${e.receivedAt}-${i}`} style={{ padding: '5px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${e.decisionTypeColor}` }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 10, fontWeight: 'bold', color: 'var(--cyan)' }}>
                    #{e.sysid != null ? e.sysid : '?'}
                    <span style={{ color: e.decisionTypeColor, marginLeft: 6 }}>[{e.decisionTypeLabel ?? '--'}]</span>
                    <span style={{ color: 'var(--dim-2)', fontWeight: 'normal', marginLeft: 6 }}>{e.reasonLabel ?? '--'}</span>
                  </span>
                  <span style={{ fontSize: 9, color: 'var(--dim-2)', fontFamily: 'var(--mono)' }}>
                    {fmtTime(e.sourceTimestamp ?? e.receivedAt)}
                  </span>
                </div>
                <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  <span>置信度 <b style={{ color: 'var(--text)' }}>{e.confidencePct != null ? `${e.confidencePct}%` : '--'}</b></span>
                  <span>触发值 <b style={{ color: 'var(--text)' }}>{e.triggerValue != null ? e.triggerValue.toFixed(1) : '--'}</b></span>
                </div>
              </div>
            ))
          )}
        </EventListCard>

        {/* 自适应航迹改写 */}
        <EventListCard title="自适应航迹改写" tag="ADAPTIVE_PATH 30052" count={adaptiveRows.length}>
          {adaptiveRows.length === 0 ? (
            <EmptyHint main="暂无航迹改写" sub="ADAPTIVE_PATH 仅在自适应航径真实改变航点时下发（路径不变不公告）" />
          ) : (
            adaptiveRows.map((p, i) => (
              <div key={`${p.receivedAt}-${i}`} style={{ padding: '5px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: '3px solid var(--ok)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 10, color: 'var(--cyan)', fontWeight: 'bold' }}>
                    #{p.sysid != null ? p.sysid : '?'} 航点 #{p.originalWaypointSeq != null ? p.originalWaypointSeq : '?'}
                  </span>
                  <span style={{ fontSize: 9, color: 'var(--dim-2)', fontFamily: 'var(--mono)' }}>
                    {p.lat != null && p.lon != null ? `${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}` : '--'}
                  </span>
                </div>
                <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  <span>原因 <b style={{ color: 'var(--warn)' }}>[{p.reasonLabel ?? '--'}]</b></span>
                  <span>新高度 <b style={{ color: 'var(--text)' }}>{p.altM != null ? `${p.altM.toFixed(0)} m` : '--'}</b></span>
                  <span>风 <b style={{ color: 'var(--text)' }}>{p.windSpeed != null ? `${p.windSpeed.toFixed(1)} m/s` : '--'}</b>{p.windDirDeg != null ? ` @ ${p.windDirDeg.toFixed(0)}°` : ''}</span>
                  <span>{fmtTime(p.receivedAt)}</span>
                </div>
              </div>
            ))
          )}
        </EventListCard>

        {/* 边缘任务状态 */}
        <EventListCard title="边缘任务" tag="EDGE_TASK_STATUS 30053" count={edgeRows.length}>
          {edgeRows.length === 0 ? (
            <EmptyHint main="暂无边缘任务上报" sub="仅开启机载边缘栈（视频分析 / 传感器融合）的设备逐任务上报" />
          ) : (
            edgeRows.map((t, i) => (
              <div key={`${t.receivedAt}-${i}`} style={{ padding: '5px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${t.statusColor}` }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 10, color: 'var(--cyan)', fontWeight: 'bold' }}>
                    #{t.sysid != null ? t.sysid : '?'} [{t.taskTypeLabel ?? '--'}]
                  </span>
                  <span style={{ fontSize: 9, color: t.statusColor }}>[{t.statusLabel ?? '--'}]</span>
                </div>
                <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  <span>任务 <b style={{ color: 'var(--text)' }}>#{t.taskId != null ? t.taskId : '?'}</b></span>
                  <span>耗时 <b style={{ color: 'var(--text)' }}>{t.processingMs != null ? `${t.processingMs.toFixed(0)} ms` : '--'}</b></span>
                  <span>结果 <b style={{ color: 'var(--text)' }}>{t.resultSize != null ? `${t.resultSize} B` : '--'}</b></span>
                  <span>{fmtTime(t.receivedAt)}</span>
                </div>
              </div>
            ))
          )}
        </EventListCard>
      </div>
    </div>
  )
}
