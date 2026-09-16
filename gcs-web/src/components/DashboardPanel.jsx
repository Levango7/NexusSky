import React, { useMemo } from 'react'

/**
 * 仪表盘汇总面板
 *
 * 多无人机概览卡片网格 + 顶部汇总栏。
 *
 * Props:
 *  - drones:   无人机列表，元素结构同 DroneList 使用的 drone 对象
 *              { sysid, callsign, online, battery, mode, armed,
 *                relativeAlt, groundspeed, heading, gpsHealthy, satellites, ... }
 *  - onSelect: 点击卡片回调，参数为 sysid
 *
 * 顶部汇总：总在线数 / 平均电池 / 最高高度 / 告警数
 * 响应式网格：手机 1 列，平板 2 列，桌面 3-4 列（由 CSS 控制）
 */

const CYAN = '#00d4ff'
const OK = '#2de2a5'
const WARN = '#ffb224'
const CRIT = '#ff5d5d'

const MODE_LABEL = {
  STANDBY: '待命',
  MANUAL: '待命',
  ARMED: '已解锁',
  MISSION: '任务中',
  RTL: '返航',
  unknown: '未知',
}

function battClass(b) {
  if (b == null) return ''
  if (b <= 20) return 'batt-crit'
  if (b <= 40) return 'batt-warn'
  return 'batt-ok'
}

function battColor(b) {
  if (b == null) return 'var(--dim)'
  if (b <= 20) return CRIT
  if (b <= 40) return WARN
  return OK
}

// 电池条（横向）
function BattBar({ value }) {
  const v = value == null ? 0 : Math.max(0, Math.min(100, value))
  const color = battColor(value)
  return (
    <div className="db-batt-bar">
      <div className="db-batt-fill" style={{ width: `${v}%`, background: color }} />
    </div>
  )
}

// 航向小箭头
function HdgArrow({ heading }) {
  if (heading == null) return <span className="dim">--</span>
  const ang = heading
  return (
    <span className="db-hdg" title={`${Math.round(heading)}°`}>
      <svg width="14" height="14" viewBox="0 0 14 14" style={{ verticalAlign: '-2px' }}>
        <g transform={`rotate(${ang} 7 7)`}>
          <path d="M7 1 L10 9 L7 7 L4 9 Z" fill={CYAN} />
        </g>
      </svg>
      <span className="mono">{Math.round(heading)}°</span>
    </span>
  )
}

// 在线指示
function OnlineDot({ online }) {
  return (
    <span className={`db-dot ${online ? 'on' : 'off'}`} title={online ? '在线' : '离线'} />
  )
}

// 单个无人机卡片
function DroneCard({ drone, onSelect }) {
  const d = drone || {}
  const name = d.callsign || `Drone-${d.sysid}`
  const batt = d.battery
  const alt = d.relativeAlt
  const spd = d.groundspeed
  const hdg = d.heading
  const gpsOk = d.gpsHealthy !== false
  const sats = d.satellites
  return (
    <div
      className={`db-card ${d.online ? 'online' : 'offline'}`}
      onClick={() => onSelect && onSelect(d.sysid)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if ((e.key === 'Enter' || e.key === ' ') && onSelect) onSelect(d.sysid)
      }}
    >
      <div className="db-card-head">
        <OnlineDot online={d.online} />
        <span className="db-name">{name}</span>
        <span className="db-sysid mono">#{d.sysid}</span>
        <span className={`db-mode mode-pill mode-${d.mode || 'unknown'}`}>
          {MODE_LABEL[d.mode] || d.mode || '未知'}
        </span>
      </div>
      <div className="db-card-body">
        <div className="db-row">
          <label>电量</label>
          <BattBar value={batt} />
          <b className={`mono ${battClass(batt)}`}>{batt != null ? `${batt}%` : '--'}</b>
        </div>
        <div className="db-row">
          <label>高度</label>
          <b className="mono">{alt != null ? `${alt.toFixed(1)} m` : '--'}</b>
        </div>
        <div className="db-row">
          <label>地速</label>
          <b className="mono">{spd != null ? `${spd.toFixed(1)} m/s` : '--'}</b>
        </div>
        <div className="db-row">
          <label>航向</label>
          <HdgArrow heading={hdg} />
        </div>
        <div className="db-row">
          <label>GPS</label>
          <span className={`db-gps ${gpsOk ? 'ok' : 'bad'}`}>
            <i className="dotp" />
            {gpsOk ? '正常' : '降级'}
            {sats != null && <span className="dim mono"> · {sats}星</span>}
          </span>
        </div>
      </div>
    </div>
  )
}

export default function DashboardPanel({ drones, onSelect }) {
  const list = Array.isArray(drones) ? drones : []

  // 顶部汇总
  const summary = useMemo(() => {
    const online = list.filter((d) => d.online).length
    const battVals = list.map((d) => d.battery).filter((b) => b != null)
    const avgBatt = battVals.length > 0 ? battVals.reduce((a, b) => a + b, 0) / battVals.length : null
    const altVals = list.map((d) => d.relativeAlt).filter((a) => a != null)
    const maxAlt = altVals.length > 0 ? Math.max(...altVals) : null
    // 告警数：离线 + 电量<=20 + GPS 降级
    let alerts = 0
    for (const d of list) {
      if (!d.online) alerts++
      if (d.battery != null && d.battery <= 20) alerts++
      if (d.gpsHealthy === false) alerts++
    }
    return { online, total: list.length, avgBatt, maxAlt, alerts }
  }, [list])

  return (
    <div className="panel dashboard-panel">
      <div className="panel-head">
        <h3>仪表盘</h3>
        <span className="mono" style={{ fontSize: 11, color: 'var(--dim)' }}>
          {summary.online}/{summary.total} 在线
        </span>
      </div>
      <div className="panel-body">
        {/* 顶部汇总栏 */}
        <div className="db-summary">
          <div className="db-sum-cell">
            <label>在线 / 总数</label>
            <b className="mono">
              <span style={{ color: OK }}>{summary.online}</span>
              <span className="dim"> / {summary.total}</span>
            </b>
          </div>
          <div className="db-sum-cell">
            <label>平均电量</label>
            <b className={`mono ${battClass(summary.avgBatt)}`}>
              {summary.avgBatt != null ? `${summary.avgBatt.toFixed(0)}%` : '--'}
            </b>
          </div>
          <div className="db-sum-cell">
            <label>最高高度</label>
            <b className="mono" style={{ color: CYAN }}>
              {summary.maxAlt != null ? `${summary.maxAlt.toFixed(0)} m` : '--'}
            </b>
          </div>
          <div className="db-sum-cell">
            <label>告警数</label>
            <b className="mono" style={{ color: summary.alerts > 0 ? CRIT : 'var(--text)' }}>
              {summary.alerts}
            </b>
          </div>
        </div>

        {/* 卡片网格 */}
        {list.length === 0 ? (
          <div className="empty-hint">
            <span className="big">🛰</span>
            暂无设备
            <br />
            启动 drone-sim 后约 2 秒内出现
          </div>
        ) : (
          <div className="db-grid">
            {list.map((d) => (
              <DroneCard key={d.sysid} drone={d} onSelect={onSelect} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}