import React, { useState } from 'react'

const MODE_LABEL = {
  STANDBY: '待命',
  MANUAL: '待命',
  ARMED: '已解锁',
  MISSION: '任务中',
  RTL: '返航',
  unknown: '未知',
}

// 航空姿态仪（人工地平仪）：roll 旋转、pitch 平移，蓝上棕下
function AttitudeIndicator({ roll = 0, pitch = 0 }) {
  const r = (roll * 180) / Math.PI || 0
  const p = (pitch * 180) / Math.PI || 0
  const shift = Math.max(-30, Math.min(30, p)) * 2.2
  return (
    <svg width="150" height="150" viewBox="0 0 150 150" className="attitude-svg">
      <defs>
        <clipPath id="att-clip">
          <circle cx="75" cy="75" r="62" />
        </clipPath>
      </defs>
      <g clipPath="url(#att-clip)">
        <g transform={`rotate(${-r} 75 75)`}>
          <g transform={`translate(0 ${shift})`}>
            <rect x="-40" y="-60" width="230" height="135" fill="#1c5d99" />
            <rect x="-40" y="75" width="230" height="135" fill="#7a5230" />
            <line x1="-40" y1="75" x2="190" y2="75" stroke="#fff" strokeWidth="1.6" />
            {/* pitch 刻度线 */}
            {[10, 20, 30].map((d) => (
              <g key={d}>
                <line x1="55" y1={75 - d * 2.2} x2="95" y2={75 - d * 2.2} stroke="#fff" strokeWidth="1" opacity=".8" />
                <line x1="55" y1={75 + d * 2.2} x2="95" y2={75 + d * 2.2} stroke="#fff" strokeWidth="1" opacity=".8" />
              </g>
            ))}
          </g>
        </g>
      </g>
      {/* 固定机身标志 */}
      <g stroke="#ffd166" strokeWidth="2.5" fill="none">
        <line x1="42" y1="75" x2="62" y2="75" />
        <line x1="88" y1="75" x2="108" y2="75" />
        <circle cx="75" cy="75" r="2.6" fill="#ffd166" stroke="none" />
      </g>
      <circle cx="75" cy="75" r="62" fill="none" stroke="#2d3850" strokeWidth="2" />
      {/* roll 弧刻度 */}
      <text x="75" y="16" textAnchor="middle" fill="#7787a3" fontSize="8" fontFamily="monospace">{r.toFixed(0)}°</text>
      <text x="75" y="146" textAnchor="middle" fill="#7787a3" fontSize="8" fontFamily="monospace">P {p.toFixed(0)}°</text>
    </svg>
  )
}

export default function TelemetryPanel({ drone, telemetry, onCommand }) {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  const cmd = async (type, alt) => {
    setBusy(true)
    setResult(null)
    try {
      const r = await onCommand(type, alt)
      setResult(r)
    } catch (e) {
      setResult({ status: 'error', result: e.message })
    } finally {
      setBusy(false)
    }
  }

  const t = telemetry || {}
  const mode = drone?.mode || t.mode || 'unknown'
  const gs = t.groundspeed != null ? t.groundspeed : null
  const hdg = t.heading != null ? t.heading : null

  return (
    <div className="panel">
      <div className="panel-head">
        <h3>飞行仪表</h3>
        <span className={`mode-pill mode-${mode}`}>
          {MODE_LABEL[mode] || mode}
        </span>
      </div>
      <div className="panel-body">
        <div className="hud-hero">
          <div className="hud-cell hl">
            <label>相对高度 ALT</label>
            <b>{t.relativeAlt != null ? t.relativeAlt.toFixed(1) : '--'}</b>
            <span className="unit">m</span>
          </div>
          <div className="hud-cell hl">
            <label>地速 GS</label>
            <b>{gs != null ? gs.toFixed(1) : '--'}</b>
            <span className="unit">m/s</span>
          </div>
          <div className="hud-cell">
            <label>航向 HDG</label>
            <b>{hdg != null ? Math.round(hdg) : '--'}</b>
            <span className="unit">°</span>
          </div>
          <div className="hud-cell">
            <label>电量 BAT</label>
            <b className={batteryClass(t.battery)}>{t.battery != null ? t.battery : '--'}</b>
            <span className="unit">%</span>
          </div>
          <div className="hud-cell">
            <label>电压 V</label>
            <b>{t.voltage != null ? (t.voltage / 1000).toFixed(1) : '--'}</b>
            <span className="unit">V</span>
          </div>
          <div className="hud-cell">
            <label>爬升率 VS</label>
            <b>{t.climb != null ? t.climb.toFixed(1) : '--'}</b>
            <span className="unit">m/s</span>
          </div>
        </div>

        <div className="attitude-box">
          <AttitudeIndicator roll={t.roll} pitch={t.pitch} />
          <div className="att-side">
            <div className={`mini-cell ${t.gpsHealthy === false ? 'cell-crit' : ''}`}>
              <label>卫星</label>
              <b className={t.gpsHealthy === false ? 'batt-crit' : ''}>{t.satellites ?? '--'}</b>
            </div>
            <div className="mini-cell"><label>航点</label><b>{t.missionSeq != null && t.missionTotal != null ? `${t.missionSeq}/${t.missionTotal}` : '--'}</b></div>
            <div className="mini-cell"><label>油门</label><b>{t.throttle != null ? t.throttle : '--'}</b></div>
            <div className="mini-cell">
              <label>链路</label>
              <SignalBars dbm={t.rssiDbm} />
            </div>
          </div>
        </div>

        <div className="cmd-grid">
          <button className="btn arm" disabled={busy || !drone} onClick={() => cmd('arm')}>
            <span className="icon">🔓</span>解锁
          </button>
          <button className="btn disarm" disabled={busy || !drone} onClick={() => cmd('disarm')}>
            <span className="icon">🔒</span>上锁
          </button>
          <button className="btn takeoff" disabled={busy || !drone} onClick={() => cmd('takeoff', 30)}>
            <span className="icon">🛫</span>起飞
          </button>
          <button className="btn start tall" style={{ gridColumn: '1 / 3' }} disabled={busy || !drone} onClick={() => cmd('start_mission')}>
            <span className="icon">▶</span>开始任务
          </button>
          <button className="btn rtl" disabled={busy || !drone} onClick={() => cmd('rtl')}>
            <span className="icon">⟲</span>返航
          </button>
          <button className="btn kill" disabled={busy || !drone} onClick={() => cmd('disarm')} title="紧急上锁">
            <span className="icon">✋</span>急停
          </button>
        </div>

        {result && (
          <div className={`cmd-result ${result.status === 'ok' ? 'ok' : 'bad'}`}>
            {result.status === 'ok' ? '✓' : '✕'} {result.status} / {String(result.result).slice(0, 60)}
          </div>
        )}
      </div>
    </div>
  )
}

function batteryClass(b) {
  if (b == null) return ''
  if (b <= 20) return 'batt-crit'
  if (b <= 40) return 'batt-warn'
  return 'batt-ok'
}

// 信号条（E1）：-50 dBm 满格 -> -100 dBm 断，4 格量化
function SignalBars({ dbm }) {
  if (dbm == null || Number.isNaN(dbm)) return <b className="dim">--</b>
  const bars = dbm >= -60 ? 4 : dbm >= -75 ? 3 : dbm >= -88 ? 2 : dbm >= -98 ? 1 : 0
  const color = bars >= 3 ? 'bars-ok' : bars === 2 ? 'bars-warn' : bars <= 1 ? 'bars-crit' : ''
  return (
    <span className={`signal-bars ${color}`} title={`${dbm.toFixed(0)} dBm`}>
      {[1, 2, 3, 4].map((i) => (
        <i key={i} className={i <= bars ? 'on' : ''} />
      ))}
    </span>
  )
}
