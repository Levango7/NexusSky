import React, { useMemo, useRef, useEffect, useState, useId } from 'react'

/**
 * 实时遥测图表组件（纯 SVG，无第三方依赖）
 *
 * 显示内容：
 *  - 电池电压 / 剩余电量曲线（最近 60 秒滚动窗口）
 *  - 高度曲线（最近 60 秒）
 *  - 速度曲线（最近 60 秒）
 *  - 航向罗盘（圆形指示器）
 *
 * Props:
 *  - telemetry: 当前遥测对象（同 TelemetryPanel 使用的结构）
 *  - history:  历史遥测数据点数组，元素形如
 *              { ts, voltage, battery, relativeAlt, groundspeed, heading, ... }
 *
 * 设计：暗色科技风，背景 #0a0e14，主色 #00d4ff，与现有 UI 一致。
 * 每 1 秒由父组件驱动重渲染（依赖 telemetry/history 变化），SVG 路径
 * 使用线性插值，配合 CSS transition 实现平滑过渡。
 */

const WINDOW_MS = 60 * 1000 // 60 秒滚动窗口
const CYAN = '#00d4ff'
const OK = '#2de2a5'
const WARN = '#ffb224'
const CRIT = '#ff5d5d'
const GOLD = '#ffc857'

// 取数值或 null
function num(v) {
  return v == null || Number.isNaN(v) ? null : v
}

// 电池颜色
function battColor(b) {
  if (b == null) return 'var(--dim)'
  if (b <= 20) return CRIT
  if (b <= 40) return WARN
  return OK
}

// 将历史数据按字段提取并裁剪到时间窗口，返回 [{t, v}] 序列
// t 归一化为 [0,1]，0=窗口起点，1=窗口终点
function series(history, field, now) {
  const out = []
  if (!Array.isArray(history) || history.length === 0) return out
  const start = now - WINDOW_MS
  for (let i = 0; i < history.length; i++) {
    const p = history[i]
    if (!p) continue
    const ts = p.ts || 0
    if (ts < start) continue
    const v = num(p[field])
    if (v == null) continue
    out.push({ t: Math.max(0, Math.min(1, (ts - start) / WINDOW_MS)), v })
  }
  return out
}

// 构建 SVG 折线 path（在 width×height 视口内，按 min/max 归一化）
function buildPath(points, width, height, min, max, padY = 4) {
  if (points.length === 0) return ''
  const range = max - min || 1
  const usableH = height - padY * 2
  let d = ''
  points.forEach((p, i) => {
    const x = p.t * width
    const y = padY + (1 - (p.v - min) / range) * usableH
    d += (i === 0 ? 'M' : 'L') + x.toFixed(2) + ' ' + y.toFixed(2) + ' '
  })
  return d.trim()
}

// 构建带填充的面积 path（折线 + 底边闭合）
function buildAreaPath(points, width, height, min, max, padY = 4) {
  if (points.length === 0) return ''
  const line = buildPath(points, width, height, min, max, padY)
  const last = points[points.length - 1]
  const first = points[0]
  const range = max - min || 1
  const usableH = height - padY * 2
  const xFirst = first.t * width
  const xLast = last.t * width
  // 闭合到底部
  return `${line} L${xLast.toFixed(2)} ${height} L${xFirst.toFixed(2)} ${height} Z`
}

// 计算 min/max，给一点上下留白
function extent(points, fallbackMin, fallbackMax, pad = 0.08) {
  if (points.length === 0) return { min: fallbackMin, max: fallbackMax }
  let mn = Infinity
  let mx = -Infinity
  for (const p of points) {
    if (p.v < mn) mn = p.v
    if (p.v > mx) mx = p.v
  }
  if (mn === mx) {
    mn -= 1
    mx += 1
  }
  const span = mx - mn
  return { min: mn - span * pad, max: mx + span * pad }
}

// 单条曲线卡片
function CurveCard({ title, unit, points, color, min, max, fallback, fmt }) {
  const W = 240
  const H = 56
  const gradId = useId()
  const ext = extent(points, min, max)
  const linePath = useMemo(() => buildPath(points, W, H, ext.min, ext.max), [points, ext.min, ext.max])
  const areaPath = useMemo(() => buildAreaPath(points, W, H, ext.min, ext.max), [points, ext.min, ext.max])
  const last = points.length > 0 ? points[points.length - 1].v : fallback
  // 网格线（3 条横线）
  const grids = [0.25, 0.5, 0.75].map((g) => (g * H).toFixed(1))
  return (
    <div className="tchart-card">
      <div className="tchart-head">
        <span className="tchart-title">{title}</span>
        <span className="tchart-val mono" style={{ color }}>
          {last != null ? (fmt ? fmt(last) : last.toFixed(1)) : '--'}
          <span className="tchart-unit">{unit}</span>
        </span>
      </div>
      <svg className="tchart-svg" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none">
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity="0.35" />
            <stop offset="100%" stopColor={color} stopOpacity="0" />
          </linearGradient>
        </defs>
        {/* 网格 */}
        {grids.map((y, i) => (
          <line key={i} x1="0" y1={y} x2={W} y2={y} stroke="#1f2738" strokeWidth="1" strokeDasharray="3 4" />
        ))}
        {areaPath && <path d={areaPath} fill={`url(#${gradId})`} />}
        {linePath && (
          <path
            d={linePath}
            fill="none"
            stroke={color}
            strokeWidth="1.6"
            strokeLinejoin="round"
            strokeLinecap="round"
            style={{ transition: 'd .3s ease' }}
          />
        )}
        {/* 末端高亮点 */}
        {points.length > 0 && (() => {
          const p = points[points.length - 1]
          const x = p.t * W
          const y = 4 + (1 - (p.v - ext.min) / (ext.max - ext.min || 1)) * (H - 8)
          return <circle cx={x.toFixed(2)} cy={y.toFixed(2)} r="2.4" fill={color} />
        })()}
      </svg>
    </div>
  )
}

// 航向罗盘
function Compass({ heading }) {
  const r = 52
  const cx = 60
  const cy = 60
  const h = num(heading)
  // 罗盘指针：0° 指向上方，顺时针
  const ang = h != null ? (h * Math.PI) / 180 : 0
  const tipX = cx + Math.sin(ang) * (r - 10)
  const tipY = cy - Math.cos(ang) * (r - 10)
  const tailX = cx - Math.sin(ang) * 14
  const tailY = cy + Math.cos(ang) * 14
  // 刻度（每 30°）
  const ticks = []
  for (let i = 0; i < 12; i++) {
    const a = (i * 30 * Math.PI) / 180
    const x1 = cx + Math.sin(a) * (r - 4)
    const y1 = cy - Math.cos(a) * (r - 4)
    const x2 = cx + Math.sin(a) * r
    const y2 = cy - Math.cos(a) * r
    ticks.push(
      <line key={i} x1={x1} y1={y1} x2={x2} y2={y2} stroke="#2d3850" strokeWidth={i % 3 === 0 ? 1.6 : 1} />
    )
  }
  return (
    <div className="tchart-card compass-card">
      <div className="tchart-head">
        <span className="tchart-title">航向</span>
        <span className="tchart-val mono" style={{ color: CYAN }}>
          {h != null ? Math.round(h) : '--'}
          <span className="tchart-unit">°</span>
        </span>
      </div>
      <svg className="compass-svg" viewBox="0 0 120 120">
        <circle cx={cx} cy={cy} r={r} fill="none" stroke="#2d3850" strokeWidth="1.5" />
        <circle cx={cx} cy={cy} r={r - 8} fill="none" stroke="#1a2130" strokeWidth="1" />
        {ticks}
        {/* 方位字 */}
        <text x={cx} y={cy - r + 9} textAnchor="middle" fill={CYAN} fontSize="9" fontWeight="700">N</text>
        <text x={cx + r - 4} y={cy + 3} textAnchor="middle" fill="#7787a3" fontSize="8">E</text>
        <text x={cx} y={cy + r - 2} textAnchor="middle" fill="#7787a3" fontSize="8">S</text>
        <text x={cx - r + 4} y={cy + 3} textAnchor="middle" fill="#7787a3" fontSize="8">W</text>
        {h != null && (
          <g style={{ transition: 'transform .3s ease' }}>
            <line x1={tailX} y1={tailY} x2={tipX} y2={tipY} stroke={CYAN} strokeWidth="2.4" strokeLinecap="round" />
            <circle cx={tipX} cy={tipY} r="3.2" fill={CYAN} />
            <circle cx={tailX} cy={tailY} r="2" fill="#4a5770" />
          </g>
        )}
        <circle cx={cx} cy={cy} r="2.4" fill="#0a0e14" stroke={CYAN} strokeWidth="1" />
      </svg>
    </div>
  )
}

export default function TelemetryCharts({ telemetry, history }) {
  // 用一个 1s 节流的 now 值，避免高频刷新抖动
  const [nowTick, setNowTick] = useState(Date.now())
  const rafRef = useRef(0)
  const lastTickRef = useRef(0)
  useEffect(() => {
    const tick = () => {
      const t = Date.now()
      if (t - lastTickRef.current >= 1000) {
        lastTickRef.current = t
        setNowTick(t)
      }
      rafRef.current = requestAnimationFrame(tick)
    }
    rafRef.current = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(rafRef.current)
  }, [])

  const t = telemetry || {}
  // 把当前 telemetry 也作为一个数据点附加到序列末尾，保证曲线持续延伸
  const histWithNow = useMemo(() => {
    const arr = Array.isArray(history) ? history.slice() : []
    if (t && Object.keys(t).length > 0) {
      arr.push({
        ts: nowTick,
        voltage: num(t.voltage),
        battery: num(t.battery),
        relativeAlt: num(t.relativeAlt),
        groundspeed: num(t.groundspeed),
        heading: num(t.heading),
      })
    }
    return arr
  }, [history, t, nowTick])

  const battPts = useMemo(() => series(histWithNow, 'battery', nowTick), [histWithNow, nowTick])
  const voltPts = useMemo(() => series(histWithNow, 'voltage', nowTick), [histWithNow, nowTick])
  const altPts = useMemo(() => series(histWithNow, 'relativeAlt', nowTick), [histWithNow, nowTick])
  const spdPts = useMemo(() => series(histWithNow, 'groundspeed', nowTick), [histWithNow, nowTick])

  // 当前值（用于卡片右上角显示）
  const curVolt = num(t.voltage) != null ? t.voltage / 1000 : null // 电压通常以 mV 给出
  const curBatt = num(t.battery)
  const curAlt = num(t.relativeAlt)
  const curSpd = num(t.groundspeed)
  const curHdg = num(t.heading)

  return (
    <div className="panel tcharts-panel">
      <div className="panel-head">
        <h3>实时遥测曲线</h3>
        <span className="mono" style={{ fontSize: 10, color: 'var(--dim)' }}>60s 滚动</span>
      </div>
      <div className="panel-body">
        <div className="tcharts-grid">
          <CurveCard
            title="电量"
            unit="%"
            points={battPts}
            color={battColor(curBatt) === 'var(--dim)' ? CYAN : battColor(curBatt)}
            min={0}
            max={100}
            fallback={curBatt}
            fmt={(v) => v.toFixed(0)}
          />
          <CurveCard
            title="电压"
            unit="V"
            points={voltPts.map((p) => ({ t: p.t, v: p.v / 1000 }))}
            color={GOLD}
            min={9}
            max={13}
            fallback={curVolt}
            fmt={(v) => v.toFixed(1)}
          />
          <CurveCard
            title="高度"
            unit="m"
            points={altPts}
            color={CYAN}
            min={0}
            max={50}
            fallback={curAlt}
            fmt={(v) => v.toFixed(1)}
          />
          <CurveCard
            title="地速"
            unit="m/s"
            points={spdPts}
            color={OK}
            min={0}
            max={15}
            fallback={curSpd}
            fmt={(v) => v.toFixed(1)}
          />
          <Compass heading={curHdg} />
        </div>
      </div>
    </div>
  )
}