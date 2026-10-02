import React, { useRef, useState, useEffect } from 'react'
import { api } from '../api.js'

// 偏航瞬时按钮的发送值：MANUAL_CONTROL.r 后端校验范围 [-1000,1000]，取 ±400
const YAW_RATE = 400

// 虚拟摇杆：按住拖动发送 MANUAL_CONTROL（10Hz），松开后停止发送（飞控侧超时自动悬停）。
// 轴映射与 PX4 一致：y 前后（+前）、x 左右（+右）、z 油门（500=悬停）、r 偏航（+右转）。
// 交互：Pointer Events 统一鼠标/触摸/笔输入，按住期间 setPointerCapture（拖出边界仍持续跟踪），
// touch-action:none 阻止触摸滚动劫持；偏航为一对瞬时按钮（按住即转、松开 r 归 0）。
export default function Joystick({ drone }) {
  const [stick, setStick] = useState({ x: 0, y: 0 })
  const [throttle, setThrottle] = useState(500)
  const [yawHeld, setYawHeld] = useState(0) // 仅用于按钮高亮；发送值以 yawRef 为准（同步可读，避免渲染滞后）
  const padRef = useRef(null)
  const sendingRef = useRef(false)
  const timerRef = useRef(null)
  const stickHeldRef = useRef(false)
  const yawRef = useRef(0)
  const stateRef = useRef({ x: 0, y: 0, z: 500 })
  stateRef.current = { x: stick.x, y: stick.y, z: throttle }

  // 组件卸载时停止发送循环并清理 pending timer，防止卸载后幽灵 API 调用。
  // 经验来源：2026-09-16-react-component-settimeout-useref-useeffect-cleanup
  useEffect(() => {
    return () => {
      sendingRef.current = false
      stickHeldRef.current = false
      yawRef.current = 0
      if (timerRef.current) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [])

  // 10Hz 发送循环：摇杆按住或偏航非零期间持续运行，两者都松开后自停
  const ensureLoop = () => {
    if (sendingRef.current) return
    sendingRef.current = true
    const tick = async () => {
      if (!stickHeldRef.current && yawRef.current === 0) {
        sendingRef.current = false
        return
      }
      try {
        const { x, y, z } = stateRef.current
        await api.sendJoystick(drone.sysid, x, y, z, yawRef.current)
      } catch (e) {
        /* transient REST failure: next tick retries */
      }
      if (sendingRef.current) {
        timerRef.current = setTimeout(tick, 100)
      }
    }
    tick()
  }

  const engage = (e) => {
    if (!drone || !drone.armed) return
    e.currentTarget.setPointerCapture(e.pointerId)
    stickHeldRef.current = true
    ensureLoop()
    moveStick(e)
  }

  const moveStick = (e) => {
    if (!stickHeldRef.current) return // 仅按住期间跟随，忽略 hover 与释放后残余事件
    const pad = padRef.current
    if (!pad) return
    const rect = pad.getBoundingClientRect()
    const cx = rect.left + rect.width / 2
    const cy = rect.top + rect.height / 2
    // normalized -1..1, +y on screen = pitch down = fly backward
    let nx = (e.clientX - cx) / (rect.width / 2)
    let ny = (e.clientY - cy) / (rect.height / 2)
    nx = Math.max(-1, Math.min(1, nx))
    ny = Math.max(-1, Math.min(1, ny))
    setStick({ x: Math.round(nx * 1000), y: Math.round(-ny * 1000) })
  }

  const release = () => {
    stickHeldRef.current = false
    setStick({ x: 0, y: 0 })
  }

  // 偏航瞬时按钮：按下置值、松开归 0（幂等，pointerup 与 lostpointercapture 可能连发）
  const yawStart = (v) => (e) => {
    if (!drone || !drone.armed) return
    e.currentTarget.setPointerCapture(e.pointerId)
    yawRef.current = v
    setYawHeld(v)
    ensureLoop()
  }
  const yawEnd = () => {
    yawRef.current = 0
    setYawHeld(0)
  }

  const disarm = async () => {
    sendingRef.current = false
    if (timerRef.current) clearTimeout(timerRef.current)
    stickHeldRef.current = false
    yawEnd()
    setStick({ x: 0, y: 0 })
    try {
      await api.sendCommand(drone.sysid, 'disarm')
    } catch (e) {
      /* surfaced via alerts */
    }
  }

  const armed = drone?.armed
  const flying = drone?.mode === 'MANUAL'

  return (
    <div className="panel">
      <div className="panel-head">
        <h3>虚拟摇杆</h3>
        <span className={`chip ${flying ? 'ok' : ''}`} style={{ fontSize: 10 }}>
          {flying ? 'MANUAL' : armed ? 'ARMED' : '未解锁'}
        </span>
      </div>
      <div className="panel-body" style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
        <div
          ref={padRef}
          className={`joypad ${armed ? '' : 'disabled'}`}
          onPointerDown={engage}
          onPointerMove={moveStick}
          onPointerUp={release}
          onPointerCancel={release}
          onLostPointerCapture={release}
          style={{ touchAction: 'none', userSelect: 'none' }}
          title={armed ? '按住拖动飞行（鼠标/触摸）' : '需先解锁'}
        >
          <div
            className="joy-knob"
            style={{
              transform: `translate(${stick.x / 14}px, ${-stick.y / 14}px)`,
            }}
          />
          <span className="joy-label">←侧倾/前后→</span>
        </div>
        <div className="joy-side">
          <label style={{ fontSize: 11, color: 'var(--dim)' }}>偏航</label>
          <button
            className={`btn ${yawHeld === -YAW_RATE ? 'primary' : ''}`}
            disabled={!armed}
            onPointerDown={yawStart(-YAW_RATE)}
            onPointerUp={yawEnd}
            onPointerCancel={yawEnd}
            onLostPointerCapture={yawEnd}
            style={{ fontSize: 11, padding: '8px 10px', touchAction: 'none', userSelect: 'none' }}
            title="按住逆时针旋转"
          >
            ↺ 左转
          </button>
          <button
            className={`btn ${yawHeld === YAW_RATE ? 'primary' : ''}`}
            disabled={!armed}
            onPointerDown={yawStart(YAW_RATE)}
            onPointerUp={yawEnd}
            onPointerCancel={yawEnd}
            onLostPointerCapture={yawEnd}
            style={{ fontSize: 11, padding: '8px 10px', touchAction: 'none', userSelect: 'none' }}
            title="按住顺时针旋转"
          >
            ↻ 右转
          </button>
        </div>
        <div className="joy-side">
          <label style={{ fontSize: 11, color: 'var(--dim)' }}>油门 {Math.round(((throttle - 500) / 500) * 100)}%</label>
          <input
            type="range"
            min="0"
            max="1000"
            value={throttle}
            disabled={!armed}
            onChange={(e) => setThrottle(Number(e.target.value))}
            style={{ writingMode: 'vertical-lr', direction: 'rtl', height: 96 }}
          />
          <button className="btn" onClick={disarm} disabled={!armed} style={{ fontSize: 11 }}>
            上锁
          </button>
        </div>
      </div>
    </div>
  )
}
