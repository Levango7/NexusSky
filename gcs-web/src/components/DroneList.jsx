import React from 'react'

const MODE_LABEL = {
  STANDBY: '待命',
  ARMED: '已解锁',
  MISSION: '任务中',
  RTL: '返航',
  unknown: '未知',
}

export default function DroneList({ drones, selectedSysid, onSelect }) {
  return (
    <div className="panel">
      <div className="panel-head">
        <h3>机队</h3>
        <span className="mono" style={{ fontSize: 11, color: 'var(--dim)' }}>
          {drones.filter((d) => d.online).length}/{drones.length} 在线
        </span>
      </div>
      <div className="panel-body">
        {drones.length === 0 && (
          <div className="empty-hint">
            <span className="big">🛰</span>
            暂无设备上线
            <br />
            启动 drone-sim 后约 2 秒内出现
          </div>
        )}
        <ul className="drone-list">
          {drones.map((d) => (
            <li
              key={d.sysid}
              className={`drone-item ${d.sysid === selectedSysid ? 'selected' : ''} ${
                d.online ? 'online' : 'offline'
              }`}
              onClick={() => onSelect(d.sysid)}
            >
              <span className="dot" />
              <span className="name">{d.callsign || `Drone-${d.sysid}`}</span>
              <span className={`batt ${battClass(d.battery)}`}>
                {d.battery != null ? `${d.battery}` : '--'}
                <span style={{ fontSize: 10, opacity: .7 }}>%</span>
              </span>
              <span className="sub">
                <span>{MODE_LABEL[d.mode] || d.mode || '待命'}</span>
                <span>{d.armed ? '🔓 已解锁' : '🔒 上锁'}</span>
                <span>{d.online ? '● 在线' : '○ 离线'}</span>
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

function battClass(b) {
  if (b == null) return ''
  if (b <= 20) return 'batt-crit'
  if (b <= 40) return 'batt-warn'
  return 'batt-ok'
}
