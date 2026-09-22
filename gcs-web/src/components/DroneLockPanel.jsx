import React, { useState, useEffect, useCallback } from 'react'
import {
  lockDrone,
  unlockDrone,
  getLockStatus,
  getLockedDrones,
  getAllLockStates,
  clearLockState,
} from '../api.js'
import { fmtTime, toArray } from '../utils/panelUtils.js'

// 无人机远程锁定/解锁面板（安全防盗功能）
// 锁定状态总览 + 已锁定无人机列表 + 锁定操作表单 + 解锁操作 + 清除锁定记录
// 风格与 AlarmPanel / SurveillancePanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 5s；AbortController 竞态守卫
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（AbortController 竞态守卫）
// 注：getLockStatus 为单个无人机锁定状态查询，保留 import 以对齐 api.js 契约

const POLL_MS = 5000

const LOCK_ACTIONS = [
  { key: 'DISARM', label: '解除武装(DISARM)' },
  { key: 'FORCE_LAND', label: '强制降落(FORCE_LAND)' },
  { key: 'RETURN_TO_LAUNCH', label: '返航(RTL)' },
]

// 动作 → 颜色
const ACTION_COLOR = {
  DISARM: 'var(--warn)',
  FORCE_LAND: 'var(--crit)',
  RETURN_TO_LAUNCH: 'var(--cyan)',
}


export default function DroneLockPanel() {
  // ---- 状态 ----
  const [allStates, setAllStates] = useState([])       // 所有无人机锁定状态
  const [lockedList, setLockedList] = useState([])     // 已锁定无人机列表
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  // 锁定操作表单
  const [formSysid, setFormSysid] = useState('')
  const [formReason, setFormReason] = useState('')
  const [formLockedBy, setFormLockedBy] = useState('')
  const [formAction, setFormAction] = useState('DISARM')

  // ---- 轮询锁定状态（5s）----
  // 经验：useEffect 中使用 AbortController + cancelled flag 守卫竞态
  useEffect(() => {
    const controller = new AbortController()
    let cancelled = false

    const load = async () => {
      // 经验：abort 后不更新任何 state
      if (controller.signal.aborted) return
      try {
        const [statesData, lockedData] = await Promise.all([
          getAllLockStates().catch(() => []),
          getLockedDrones().catch(() => []),
        ])
        if (cancelled || controller.signal.aborted) return
        setAllStates(toArray(statesData, 'states'))
        setLockedList(toArray(lockedData, 'drones'))
      } catch (e) {
        // 静默失败，下一轮轮询会重试
      }
    }

    load()
    const timer = setInterval(load, POLL_MS)

    return () => {
      cancelled = true
      controller.abort()
      clearInterval(timer)
    }
  }, [])

  // ---- 刷新状态（操作成功后手动调用，立即拉取最新数据）----
  const refreshStates = useCallback(async () => {
    try {
      const [statesData, lockedData] = await Promise.all([
        getAllLockStates().catch(() => []),
        getLockedDrones().catch(() => []),
      ])
      setAllStates(toArray(statesData, 'states'))
      setLockedList(toArray(lockedData, 'drones'))
    } catch (e) {
      // 静默失败，轮询会补上
    }
  }, [])

  // ---- 锁定无人机 ----
  const handleLock = useCallback(async (e) => {
    if (e && e.preventDefault) e.preventDefault()
    const sysid = Number(formSysid)
    if (!Number.isFinite(sysid) || sysid < 0) {
      setError('sysid 必须为非负数字')
      return
    }
    if (!formReason.trim()) {
      setError('请填写锁定原因(reason)')
      return
    }
    if (!formLockedBy.trim()) {
      setError('请填写锁定操作人(lockedBy)')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await lockDrone(sysid, {
        reason: formReason.trim(),
        lockedBy: formLockedBy.trim(),
        action: formAction,
      })
      // 成功后刷新状态列表
      await refreshStates()
      // 重置表单（保留 action 选择，方便连续操作）
      setFormSysid('')
      setFormReason('')
      setFormLockedBy('')
    } catch (err) {
      setError('锁定失败：' + (err && err.message ? err.message : String(err)))
    } finally {
      setSubmitting(false)
    }
  }, [formSysid, formReason, formLockedBy, formAction, refreshStates])

  // ---- 解锁无人机 ----
  // 弹出 prompt 输入 unlockedBy，调用 unlockDrone(sysid, {unlockedBy})
  const handleUnlock = useCallback(async (sysid) => {
    const unlockedBy = window.prompt(`解锁无人机 #${sysid}\n请输入解锁操作人(unlockedBy)：`, '')
    if (unlockedBy == null) return // 用户点击取消
    if (!String(unlockedBy).trim()) {
      setError('解锁操作人(unlockedBy)不能为空')
      return
    }
    setError(null)
    try {
      await unlockDrone(sysid, { unlockedBy: String(unlockedBy).trim() })
      // 成功后刷新状态列表
      await refreshStates()
    } catch (err) {
      setError('解锁失败：' + (err && err.message ? err.message : String(err)))
    }
  }, [refreshStates])

  // ---- 清除锁定记录（管理操作）----
  // 确认提示后调用 clearLockState(sysid)
  const handleClear = useCallback(async (sysid) => {
    if (!window.confirm(
      `确认清除无人机 #${sysid} 的锁定记录？\n此为管理操作，将删除该无人机的锁定状态记录。`
    )) return
    setError(null)
    try {
      await clearLockState(sysid)
      // 成功后刷新状态列表
      await refreshStates()
    } catch (err) {
      setError('清除锁定记录失败：' + (err && err.message ? err.message : String(err)))
    }
  }, [refreshStates])

  // ---- 派生：已锁定数量 ----
  const lockedCount = lockedList.length

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        无人机远程锁定
        <span style={{ fontSize: 10, color: 'var(--dim)', marginLeft: 8 }}>
          已锁定 {lockedCount} 架
        </span>
      </h2>

      {error && (
        <div style={{
          color: 'var(--crit)', fontSize: 11, marginBottom: 8,
          padding: '4px 8px', background: 'var(--bg-2)',
          borderRadius: 4, border: '1px solid var(--crit)',
        }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左侧：锁定状态总览 + 已锁定无人机列表 */}
        <div style={{ flex: '1 1 520px', minWidth: 420, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {/* 1. 锁定状态总览（顶部）*/}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{
              padding: '6px 10px', borderBottom: '1px solid var(--line-2)',
              fontSize: 11, color: 'var(--dim)',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}>
              <span>锁定状态总览</span>
              <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>{allStates.length} 条记录</span>
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {allStates.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>
                  暂无锁定状态记录
                </div>
              ) : (
                allStates.map((s, i) => {
                  const sysid = s.sysid != null ? s.sysid : i
                  const locked = !!s.locked
                  const color = locked ? 'var(--crit)' : 'var(--ok)'
                  const action = s.action || '--'
                  const actColor = ACTION_COLOR[action] || 'var(--dim)'
                  return (
                    <div
                      key={'state_' + sysid + '_' + i}
                      style={{
                        padding: '6px 10px',
                        borderBottom: '1px solid var(--line-2)',
                        borderLeft: `3px solid ${color}`,
                      }}
                    >
                      <div style={{
                        display: 'flex', justifyContent: 'space-between',
                        alignItems: 'center', gap: 8,
                      }}>
                        <div style={{
                          display: 'flex', gap: 6, alignItems: 'center',
                          flex: 1, minWidth: 0, flexWrap: 'wrap',
                        }}>
                          <span style={{ fontSize: 11, fontWeight: 'bold', color, flexShrink: 0 }}>
                            {locked ? '🔒' : '🔓'} #{sysid}
                          </span>
                          <span style={{
                            fontSize: 9, padding: '1px 5px', borderRadius: 2,
                            border: `1px solid ${color}`, color, flexShrink: 0,
                          }}>
                            {locked ? '已锁定' : '未锁定'}
                          </span>
                          <span style={{
                            fontSize: 9, padding: '1px 5px', borderRadius: 2,
                            border: `1px solid ${actColor}`, color: actColor, flexShrink: 0,
                          }}>
                            {action}
                          </span>
                        </div>
                        {/* 5. 清除锁定记录按钮（管理操作）*/}
                        <button
                          onClick={() => handleClear(sysid)}
                          style={{
                            ...miniBtnStyle,
                            color: 'var(--warn)',
                            borderColor: 'var(--warn)',
                          }}
                          title="清除该无人机的锁定状态记录（管理操作）"
                        >
                          清除
                        </button>
                      </div>
                      <div style={{
                        fontSize: 9, color: 'var(--dim-2)', marginTop: 3,
                        display: 'flex', gap: 12, flexWrap: 'wrap',
                      }}>
                        <span>原因：{s.reason || '--'}</span>
                        <span>锁定人：{s.lockedBy || '--'}</span>
                        <span>锁定时间：{fmtTime(s.lockedAtMs)}</span>
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 2. 已锁定无人机列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{
              padding: '6px 10px', borderBottom: '1px solid var(--line-2)',
              fontSize: 11, color: 'var(--dim)',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}>
              <span>已锁定无人机</span>
              <span style={{ fontSize: 9, color: 'var(--crit)' }}>{lockedCount} 架</span>
            </div>
            <div style={{
              padding: 8, display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center',
            }}>
              {lockedCount === 0 ? (
                <span style={{ fontSize: 10, color: 'var(--dim-2)' }}>无已锁定无人机</span>
              ) : (
                lockedList.map((d, i) => {
                  // 兼容裸数字数组与对象数组
                  const sysid = (typeof d === 'object' && d !== null)
                    ? (d.sysid != null ? d.sysid : i)
                    : d
                  return (
                    <span
                      key={'locked_' + sysid + '_' + i}
                      style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4,
                        fontSize: 10, padding: '2px 6px', borderRadius: 3,
                        border: '1px solid var(--crit)', color: 'var(--crit)',
                        background: 'var(--bg-1)',
                      }}
                    >
                      🔒 #{sysid}
                      {/* 4. 解锁按钮 */}
                      <button
                        onClick={() => handleUnlock(sysid)}
                        style={{
                          ...miniBtnStyle,
                          color: 'var(--ok)', borderColor: 'var(--ok)',
                          padding: '1px 5px', fontSize: 9, marginLeft: 2,
                        }}
                        title={`解锁无人机 #${sysid}`}
                      >
                        解锁
                      </button>
                    </span>
                  )
                })
              )}
            </div>
          </div>
        </div>

        {/* 右侧：3. 锁定操作表单 */}
        <div style={{ flex: '0 1 320px', minWidth: 280, display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={cardStyle}>
            <div style={{
              fontSize: 11, color: 'var(--dim)', marginBottom: 8,
              borderBottom: '1px solid var(--line-2)', paddingBottom: 4,
            }}>
              锁定操作
            </div>
            <form onSubmit={handleLock} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <label style={labelStyle}>
                <span>sysid（无人机编号）</span>
                <input
                  type="number"
                  min="0"
                  step="1"
                  value={formSysid}
                  onChange={(e) => setFormSysid(e.target.value)}
                  placeholder="例如 1"
                  style={inputStyle}
                  required
                />
              </label>
              <label style={labelStyle}>
                <span>reason（锁定原因）</span>
                <input
                  type="text"
                  value={formReason}
                  onChange={(e) => setFormReason(e.target.value)}
                  placeholder="例如 失联超过30分钟"
                  style={inputStyle}
                  required
                />
              </label>
              <label style={labelStyle}>
                <span>lockedBy（操作人）</span>
                <input
                  type="text"
                  value={formLockedBy}
                  onChange={(e) => setFormLockedBy(e.target.value)}
                  placeholder="例如 admin"
                  style={inputStyle}
                  required
                />
              </label>
              <label style={labelStyle}>
                <span>action（锁定动作）</span>
                <select
                  value={formAction}
                  onChange={(e) => setFormAction(e.target.value)}
                  style={selectStyle}
                >
                  {LOCK_ACTIONS.map((a) => (
                    <option key={a.key} value={a.key}>{a.label}</option>
                  ))}
                </select>
              </label>
              <button
                type="submit"
                disabled={submitting}
                style={{
                  ...miniBtnStyle,
                  fontSize: 11, padding: '5px 10px',
                  color: 'var(--crit)', borderColor: 'var(--crit)',
                  cursor: submitting ? 'not-allowed' : 'pointer',
                  opacity: submitting ? 0.6 : 1, marginTop: 4,
                }}
              >
                {submitting ? '锁定中…' : '🔒 锁定无人机'}
              </button>
            </form>
          </div>

          {/* 操作说明 */}
          <div style={{ ...cardStyle, fontSize: 9, color: 'var(--dim-2)', lineHeight: 1.6 }}>
            <div style={{ color: 'var(--dim)', fontSize: 10, marginBottom: 4 }}>操作说明</div>
            <div>• <b style={{ color: 'var(--warn)' }}>DISARM</b>：解除武装，电机停转</div>
            <div>• <b style={{ color: 'var(--crit)' }}>FORCE_LAND</b>：强制原地降落</div>
            <div>• <b style={{ color: 'var(--cyan)' }}>RETURN_TO_LAUNCH</b>：自动返航</div>
            <div style={{ marginTop: 4 }}>• 已锁定无人机可点击"解锁"按钮</div>
            <div>• "清除"为管理操作，删除锁定记录</div>
          </div>
        </div>
      </div>
    </div>
  )
}

// ===== 内联样式（与 AlarmPanel / SurveillancePanel 保持一致）=====
const cardStyle = {
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 4,
  padding: '6px 10px',
}

const labelStyle = {
  fontSize: 10,
  color: 'var(--dim-2)',
  marginBottom: 2,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
}

const miniBtnStyle = {
  fontSize: 10,
  padding: '2px 8px',
  cursor: 'pointer',
  border: '1px solid var(--line-2)',
  background: 'transparent',
  color: 'var(--dim)',
  borderRadius: 3,
}

const inputStyle = {
  width: '100%',
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-1)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
  boxSizing: 'border-box',
}

const selectStyle = {
  width: '100%',
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-1)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
  boxSizing: 'border-box',
}