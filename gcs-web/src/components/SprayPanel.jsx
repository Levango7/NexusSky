import React, { useState, useEffect, useRef } from 'react'
import { api } from '../api.js'

// 喷洒物流面板（M2）
// 喷洒任务创建 / 喷洒状态查询 / 夹爪控制 / 物流配送序列
// 1Hz 轮询刷新喷洒状态与配送序列
// 风格与 FormationPanel 一致：卡片布局 + 内联 CSS + CSS 变量

const POLL_MS = 1000

const PUMP_STATE_META = {
  IDLE: { label: '空闲', color: 'var(--dim)' },
  RUNNING: { label: '喷洒中', color: 'var(--ok)' },
  PAUSED: { label: '暂停', color: 'var(--warn)' },
  FAULT: { label: '故障', color: 'var(--crit)' },
}

const DELIVERY_STATE_META = {
  PENDING: { label: '待配送', color: 'var(--dim)' },
  EN_ROUTE: { label: '配送中', color: 'var(--cyan)' },
  ARRIVED: { label: '已到达', color: 'var(--ok)' },
  DROPPED: { label: '已投递', color: 'var(--ok)' },
  FAILED: { label: '失败', color: 'var(--crit)' },
}

const inputStyle = {
  width: '100%',
  padding: '5px 7px',
  fontSize: 11,
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 4,
  color: 'var(--text)',
  fontFamily: 'var(--mono)',
}

const labelStyle = { fontSize: 10, color: 'var(--dim-2)', marginBottom: 2, display: 'block' }

export default function SprayPanel({ drones = [] }) {
  const [sysid, setSysid] = useState(drones[0]?.sysid ?? null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  // 喷洒任务创建表单
  const [form, setForm] = useState({
    volume: 10, // 喷洒量 L
    rate: 1.5, // 喷洒速率 L/s
    segments: '22.5916,113.9345;22.5920,113.9350;22.5924,113.9345', // 航段列表
  })

  // 喷洒状态
  const [status, setStatus] = useState(null)
  // 配送序列
  const [sequence, setSequence] = useState(null)
  // 夹爪状态
  const [gripper, setGripper] = useState(null)
  // 轮询错误
  const [pollErr, setPollErr] = useState(null)

  const timerRef = useRef(null)

  // 自动选第一个无人机
  useEffect(() => {
    if (sysid == null && drones.length > 0) setSysid(drones[0].sysid)
  }, [drones, sysid])

  // 1Hz 轮询喷洒状态 + 配送序列
  useEffect(() => {
    if (sysid == null) return
    let cancelled = false
    const poll = async () => {
      try {
        const [s, seq] = await Promise.all([
          api.getSprayStatus(sysid).catch(() => null),
          api.getDeliverySequence(sysid).catch(() => null),
        ])
        if (cancelled) return
        setStatus(s)
        setSequence(seq)
        setPollErr(null)
      } catch (e) {
        if (cancelled) return
        setPollErr(e.message)
      }
    }
    poll()
    timerRef.current = setInterval(poll, POLL_MS)
    return () => {
      cancelled = true
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [sysid])

  // 切机时清状态
  useEffect(() => {
    setStatus(null)
    setSequence(null)
    setGripper(null)
    setResult(null)
    setPollErr(null)
  }, [sysid])

  // 统一 API 调用包装：管理 busy/result
  const call = async (fn, label) => {
    setBusy(true)
    setResult(null)
    try {
      const r = await fn()
      setResult({ ok: true, msg: `${label} ✓` })
      return r
    } catch (e) {
      setResult({ ok: false, msg: `${label} ✕ ${e.message}` })
      return null
    } finally {
      setBusy(false)
    }
  }

  const handleCreateTask = async () => {
    if (sysid == null) {
      setResult({ ok: false, msg: '请先选择无人机' })
      return
    }
    // 解析航段：格式 "lat1,lon1;lat2,lon2;..."
    const segments = (form.segments || '')
      .split(/[;\n]+/)
      .map((s) => s.trim())
      .filter(Boolean)
      .map((pair) => {
        const [lat, lon] = pair.split(/[,\s]+/).map(Number)
        return { lat, lon }
      })
      .filter((p) => !Number.isNaN(p.lat) && !Number.isNaN(p.lon))
    if (segments.length < 1) {
      setResult({ ok: false, msg: '至少需要 1 个航段' })
      return
    }
    await call(
      () =>
        api.createSprayTask({
          sysid,
          volume: Number(form.volume),
          rate: Number(form.rate),
          segments,
        }),
      '创建喷洒任务'
    )
  }

  const handleGripper = (open) => {
    if (sysid == null) return
    call(
      () =>
        api.controlGripper(sysid, open).then((r) => {
          setGripper(open)
          return r
        }),
      `夹爪${open ? '张开' : '闭合'}`
    )
  }

  const upd = (k) => (e) => setForm({ ...form, [k]: e.target.value })

  const pumpMeta = status?.pumpState
    ? PUMP_STATE_META[status.pumpState] || { label: status.pumpState, color: 'var(--dim)' }
    : null

  // 液量百分比
  const totalVol = status?.totalVolume || form.volume
  const remainVol = status?.remainingVolume ?? null
  const remainPct =
    remainVol != null && totalVol > 0 ? Math.max(0, Math.min(100, (remainVol / totalVol) * 100)) : null

  const sites = sequence?.sites || []

  return (
    <div style={{ display: 'flex', height: '100%', minHeight: 0 }}>
      {/* 左栏：无人机选择 + 喷洒任务创建表单 */}
      <div
        style={{
          width: 340,
          flex: 'none',
          borderRight: '1px solid var(--line)',
          background: 'var(--bg-2)',
          overflowY: 'auto',
        }}
      >
        <div className="panel">
          <div className="panel-head">
            <h3>喷洒任务</h3>
          </div>
          <div className="panel-body">
            {/* 无人机选择 */}
            <div style={{ marginBottom: 10 }}>
              <label style={labelStyle}>目标无人机 sysid</label>
              <select
                style={inputStyle}
                value={sysid ?? ''}
                onChange={(e) => setSysid(e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">— 选择 —</option>
                {drones.map((d) => (
                  <option key={d.sysid} value={d.sysid}>
                    #{d.sysid} {d.online ? '在线' : '离线'}
                  </option>
                ))}
              </select>
            </div>

            {/* 创建表单 */}
            <div
              style={{
                background: 'var(--panel)',
                border: '1px solid var(--line-2)',
                borderRadius: 'var(--r)',
                padding: 10,
                display: 'grid',
                gridTemplateColumns: '1fr 1fr',
                gap: 7,
              }}
            >
              <div>
                <label style={labelStyle}>喷洒量 (L)</label>
                <input
                  style={inputStyle}
                  type="number"
                  min="0"
                  step="0.5"
                  value={form.volume}
                  onChange={upd('volume')}
                />
              </div>
              <div>
                <label style={labelStyle}>喷洒速率 (L/s)</label>
                <input
                  style={inputStyle}
                  type="number"
                  min="0"
                  step="0.1"
                  value={form.rate}
                  onChange={upd('rate')}
                />
              </div>
              <div style={{ gridColumn: '1 / -1' }}>
                <label style={labelStyle}>
                  航段列表（lat,lon 用分号或换行分隔）
                </label>
                <textarea
                  style={{ ...inputStyle, minHeight: 64, resize: 'vertical' }}
                  value={form.segments}
                  onChange={upd('segments')}
                  placeholder="22.5916,113.9345;22.5920,113.9350"
                />
              </div>
              <div style={{ gridColumn: '1 / -1' }}>
                <button
                  className="btn primary"
                  style={{ width: '100%' }}
                  disabled={busy || sysid == null}
                  onClick={handleCreateTask}
                >
                  创建喷洒任务
                </button>
              </div>
            </div>

            {result && (
              <div className={`cmd-result ${result.ok ? 'ok' : 'bad'}`} style={{ marginTop: 10 }}>
                {result.msg}
              </div>
            )}
          </div>
        </div>

        {/* 夹爪控制 */}
        <div className="panel">
          <div className="panel-head">
            <h3>夹爪控制</h3>
            <span
              style={{
                fontSize: 11,
                color: gripper ? 'var(--ok)' : 'var(--dim-2)',
                fontFamily: 'var(--mono)',
              }}
            >
              {gripper == null ? '未知' : gripper ? '张开' : '闭合'}
            </span>
          </div>
          <div className="panel-body">
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 7 }}>
              <button
                className="btn arm"
                disabled={busy || sysid == null}
                onClick={() => handleGripper(true)}
              >
                <span className="icon">✋</span>张开
              </button>
              <button
                className="btn"
                disabled={busy || sysid == null}
                onClick={() => handleGripper(false)}
              >
                <span className="icon">✊</span>闭合
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* 右栏：喷洒状态 + 配送序列 */}
      <div style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
        {sysid == null ? (
          <div className="empty-hint" style={{ textAlign: 'center', paddingTop: 60 }}>
            <span className="big">🛩</span>
            从左侧选择一架无人机
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14, maxWidth: 880 }}>
            {/* 喷洒状态卡片 */}
            <div
              style={{
                background: 'linear-gradient(180deg, var(--panel-2), var(--panel))',
                border: '1px solid var(--line-2)',
                borderRadius: 'var(--r)',
                padding: 14,
              }}
            >
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: 12,
                }}
              >
                <div>
                  <div style={{ fontSize: 11, color: 'var(--dim)', letterSpacing: 1 }}>
                    喷洒状态
                  </div>
                  <div
                    className="mono"
                    style={{ fontSize: 18, fontWeight: 700, color: 'var(--cyan)' }}
                  >
                    #{sysid}
                  </div>
                </div>
                {pumpMeta && (
                  <span
                    className="mono"
                    style={{
                      fontSize: 11,
                      padding: '4px 10px',
                      borderRadius: 999,
                      border: `1px solid ${pumpMeta.color}55`,
                      color: pumpMeta.color,
                      background: 'var(--panel-2)',
                      letterSpacing: 0.5,
                    }}
                  >
                    {pumpMeta.label}
                  </span>
                )}
              </div>

              {pollErr && (
                <div className="cmd-result bad" style={{ marginBottom: 10 }}>
                  状态查询失败: {pollErr}
                </div>
              )}

              {!status ? (
                <div className="empty-hint">暂无喷洒数据</div>
              ) : (
                <>
                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: '1fr 1fr 1fr',
                      gap: 10,
                    }}
                  >
                    <SummaryItem
                      label="剩余液量"
                      value={remainVol != null ? `${remainVol.toFixed(1)} L` : '--'}
                      color="var(--cyan)"
                    />
                    <SummaryItem
                      label="喷洒速率"
                      value={status.rate != null ? `${status.rate.toFixed(2)} L/s` : '--'}
                      color="var(--ok)"
                    />
                    <SummaryItem
                      label="已喷洒"
                      value={
                        status.sprayedVolume != null
                          ? `${status.sprayedVolume.toFixed(1)} L`
                          : '--'
                      }
                    />
                  </div>

                  {/* 液量进度条 */}
                  {remainPct != null && (
                    <div style={{ marginTop: 12 }}>
                      <div
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          fontSize: 10,
                          color: 'var(--dim-2)',
                          marginBottom: 4,
                        }}
                      >
                        <span>液量</span>
                        <span className="mono">{remainPct.toFixed(0)}%</span>
                      </div>
                      <div
                        style={{
                          height: 8,
                          borderRadius: 4,
                          background: 'var(--bg-2)',
                          border: '1px solid var(--line)',
                          overflow: 'hidden',
                        }}
                      >
                        <div
                          style={{
                            height: '100%',
                            width: `${remainPct}%`,
                            background:
                              'linear-gradient(90deg, var(--cyan), var(--ok))',
                            transition: 'width .3s',
                          }}
                        />
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>

            {/* 物流配送序列 */}
            <div className="panel" style={{ borderBottom: 'none' }}>
              <div className="panel-head">
                <h3>物流配送序列</h3>
                <span className="mono" style={{ fontSize: 11, color: 'var(--dim)' }}>
                  {sites.length} 站
                </span>
              </div>
              <div className="panel-body">
                {sites.length === 0 ? (
                  <div className="empty-hint">暂无配送站点</div>
                ) : (
                  <table
                    style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}
                  >
                    <thead>
                      <tr
                        style={{
                          color: 'var(--dim)',
                          fontSize: 10,
                          letterSpacing: 1,
                          textAlign: 'left',
                        }}
                      >
                        <th style={thStyle}>#</th>
                        <th style={thStyle}>坐标</th>
                        <th style={thStyle}>载荷</th>
                        <th style={thStyle}>状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {sites.map((s, i) => (
                        <DeliveryRow key={s.id ?? i} site={s} idx={i} />
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

const thStyle = {
  padding: '6px 8px',
  borderBottom: '1px solid var(--line)',
  fontWeight: 600,
}

/* ---------- 摘要单项 ---------- */
function SummaryItem({ label, value, color }) {
  return (
    <div>
      <div style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: 1 }}>{label}</div>
      <div
        className="mono"
        style={{ fontSize: 15, fontWeight: 600, color: color || 'var(--text)' }}
      >
        {value}
      </div>
    </div>
  )
}

/* ---------- 配送站点行 ---------- */
function DeliveryRow({ site, idx }) {
  const meta = site.state
    ? DELIVERY_STATE_META[site.state] || { label: site.state, color: 'var(--dim)' }
    : { label: '--', color: 'var(--dim-2)' }
  const coord =
    site.lat != null && site.lon != null
      ? `${site.lat.toFixed(5)}, ${site.lon.toFixed(5)}`
      : '--'
  return (
    <tr style={{ borderBottom: '1px solid var(--line)' }}>
      <td
        style={{
          padding: '7px 8px',
          fontFamily: 'var(--mono)',
          fontWeight: 700,
          color: 'var(--cyan)',
          width: 36,
        }}
      >
        {idx + 1}
      </td>
      <td
        style={{
          padding: '7px 8px',
          fontFamily: 'var(--mono)',
          fontSize: 10.5,
          color: 'var(--dim)',
        }}
      >
        {coord}
      </td>
      <td style={{ padding: '7px 8px', fontSize: 11, color: 'var(--dim)' }}>
        {site.payload ?? '--'}
      </td>
      <td style={{ padding: '7px 8px' }}>
        <span
          className="mono"
          style={{
            fontSize: 10,
            padding: '2px 8px',
            borderRadius: 999,
            border: `1px solid ${meta.color}55`,
            color: meta.color,
            background: 'var(--panel-2)',
            letterSpacing: 0.5,
          }}
        >
          {meta.label}
        </span>
      </td>
    </tr>
  )
}