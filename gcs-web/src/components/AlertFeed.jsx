import React from 'react'

const SEV_META = {
  0: { color: 'var(--crit)', label: 'EMERG' },
  1: { color: 'var(--crit)', label: 'ALERT' },
  2: { color: 'var(--crit)', label: 'CRIT' },
  3: { color: 'var(--crit)', label: 'ERROR' },
  4: { color: 'var(--warn)', label: 'WARN' },
  5: { color: 'var(--cyan)', label: 'NOTICE' },
  6: { color: 'var(--dim)', label: 'INFO' },
  7: { color: 'var(--dim-2)', label: 'DEBUG' },
}

export default function AlertFeed({ alerts }) {
  return (
    <div className="panel alerts">
      <div className="panel-head">
        <h3>事件与告警</h3>
        {alerts.length > 0 && (
          <span className="mono" style={{ fontSize: 11, color: 'var(--dim)' }}>{alerts.length} 条</span>
        )}
      </div>
      <div className="panel-body">
        {alerts.length === 0 && (
          <div className="empty-hint" style={{ padding: '10px 0' }}>暂无事件</div>
        )}
        <div className="alert-scroll">
          <ul className="alert-list">
            {alerts.map((a, i) => {
              const meta = SEV_META[a.severity ?? 6] || SEV_META[6]
              return (
                <li key={i} style={{ borderLeftColor: meta.color }}>
                  <span className="sev" style={{ color: meta.color }}>[{meta.label}]</span>
                  {a.text}
                  <time>{new Date(a.ts).toLocaleTimeString('zh-CN', { hour12: false })}</time>
                </li>
              )
            })}
          </ul>
        </div>
      </div>
    </div>
  )
}
