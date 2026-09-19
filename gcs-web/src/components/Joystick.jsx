import React, { useRef, useState, useEffect } from 'react'
import { api } from '../api.js'

// 虚拟摇杆：按住拖动发送 MANUAL_CONTROL（10Hz），松开 2 秒后自动悬停。
// 轴映射与 PX4 一致：y 前后（+前）、x 左右（+右）、z 油门（500=悬停）、r 旋转。
export default function Joystick({ drone }) {
  const [stick, setStick] = useState({ x: 0, y: 0 })
  const [throttle, setThrottle] = useState(500)
  const padRef = useRef(null)
  const sendingRef = useRef(false)
  const timerRef = useRef(null)
  const stateRef = useRef({ x: 0, y: 0, z: 500, r: 0 })
  stateRef.current = { x: stick.x, y: stick.y, z: throttle, r: 0 }

  // 组件卸载时停止发送循环并清理 pending timer，防止卸载后幽灵 API 调用。
  // 经验来源：2026-09-16-react-component-settimeout-useref-useeffect-cleanup
  useEffect(() => {
    return () => {
      sendingRef.current = false
      if (timerRef.current) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [])

  const sendLoop = () => {
    // 10 Hz sender: runs while the stick is engaged or throttle is off-center
    const tick = async () => {
      if (!sendingRef.current) return
      try {
        const { x, y, z, r } = stateRef.current
        await api.sendJoystick(drone.sysid, x, y, z, r)
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
    sendingRef.current = true
    sendLoop()
    moveStick(e)
  }

  const moveStick = (e) => {
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
    sendingRef.current = false
    if (timerRef.current) clearTimeout(timerRef.current)
    setStick({ x: 0, y: 0 })
  }

  const disarm = async () => {
    sendingRef.current = false
    if (timerRef.current) clearTimeout(timerRef.current)
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
          onMouseDown={engage}
          onMouseMove={moveStick}
          onMouseUp={release}
          onMouseLeave={release}
          title={armed ? '按住拖动飞行' : '需先解锁'}
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
