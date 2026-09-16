import React, { useState, useEffect, useRef } from 'react'
import { api } from '../api.js'

// 硬件抽象面板（M4）
// 雷达扫描配置/状态/目标 + 旋翼遥测 + LiDAR + IMU + 物理模型切换
// 1Hz 轮询刷新所有硬件状态
// 风格与 FormationPanel 一致：卡片布局 + 内联 CSS + CSS 变量

const POLL_MS = 1000

const RADAR_MODES = ['SECTOR', 'FULL', 'STARE']

const TRACK_STATE_META = {
  INIT: { label: '初始', color: 'var(--dim)' },
  ACQUIRE: { label: '捕获', color: 'var(--cyan)' },
  TRACK: { label: '跟踪', color: 'var(--ok)' },
  LOST: { label: '丢失', color: 'var(--crit)' },
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

export default function HardwarePanel({ drones = [] }) {
  const [sysid, setSysid] = useState(drones[0]?.sysid ?? null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  // 雷达配置表单
  const [radarForm, setRadarForm] = useState({
    mode: 'SECTOR',
    azStart: 0,
    azEnd: 180,
    period: 2, // 扫描周期 s
  })

  // 物理模型
  const [physicsModel, setPhysicsModel] = useState('kinematics')

  // 硬件状态
  const [radarStatus, setRadarStatus] = useState(null)
  const [radarTargets, setRadarTargets] = useState(null)
  const [rotor, setRotor] = useState(null)
  const [lidar, setLidar] = useState(null)
  const [imu, setImu] = useState(null)
  const [pollErr, setPollErr] = useState(null)

  const timerRef = useRef(null)

  // 自动选第一个无人机
  useEffect(() => {
    if (sysid == null && drones.length > 0) setSysid(drones[0].sysid)
  }, [drones, sysid])

  // 1Hz 轮询所有硬件状态
  useEffect(() => {
    if (sysid == null) return
    let cancelled = false
    const poll = async () => {
      try {
        const [rs, rt, ro, li, im] = await Promise.all([
          api.getRadarStatus(sysid).catch(() => null),
          api.getRadarTargets(sysid).catch(() => null),
          api.getRotorTelemetry(sysid).catch(() => null),
          api.getLidarData(sysid).catch(() => null),
          api.getImuData(sysid).catch(() => null),
        ])
        if (cancelled) return
        setRadarStatus(rs)
        setRadarTargets(rt)
        setRotor(ro)
        setLidar(li)
        setImu(im)
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
    setRadarStatus(null)
    setRadarTargets(null)
    setRotor(null)
    setLidar(null)
    setImu(null)
    setResult(null)
    setPollErr(null)
  }, [sysid])

  // 统一 API 调用包装
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

  const handleRadarConfig = () => {
    if (sysid == null) return
    call(
      () =>
        api.configureRadar({
          sysid,
          mode: radarForm.mode,
          azStart: Number(radarForm.azStart),
          azEnd: Number(radarForm.azEnd),
          period: Number(radarForm.period),
        }),
      '雷达配置'
    )
  }

  const handlePhysicsModel = (model) => {
    if (sysid == null) return
    call(
      () =>
        api.setPhysicsModel(sysid, model).then((r) => {
          setPhysicsModel(model)
          return r
        }),
      `物理模型 → ${model}`
    )
  }

  const updRadar = (k) => (e) => setRadarForm({ ...radarForm, [k]: e.target.value })

  const targets = radarTargets?.targets || []
  const rotors = rotor?.rotors || (rotor ? [rotor] : [])

  return (
    <div style={{ display: 'flex', height: '100%', minHeight: 0 }}>
      {/* 左栏：无人机选择 + 雷达配置 + 物理模型 */}
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
            <h3>硬件抽象</h3>
          </div>
          <div className="panel-body">
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

            {pollErr && (
              <div className="cmd-result bad" style={{ marginBottom: 10 }}>
                状态查询失败: {pollErr}
              </div>
            )}

            {result && (
              <div className={`cmd-result ${result.ok ? 'ok' : 'bad'}`} style={{ marginBottom: 10 }}>
                {result.msg}
              </div>
            )}
          </div>
        </div>

        {/* 雷达扫描配置 */}
        <div className="panel">
          <div className="panel-head">
            <h3>雷达扫描配置</h3>
          </div>
          <div className="panel-body">
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
              <div style={{ gridColumn: '1 / -1' }}>
                <label style={labelStyle}>扫描模式</label>
                <select style={inputStyle} value={radarForm.mode} onChange={updRadar('mode')}>
                  {RADAR_MODES.map((m) => (
                    <option key={m} value={m}>
                      {m}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label style={labelStyle}>方位角起始 (°)</label>
                <input
                  style={inputStyle}
                  type="number"
                  min="0"
                  max="359"
                  value={radarForm.azStart}
                  onChange={updRadar('azStart')}
                />
              </div>
              <div>
                <label style={labelStyle}>方位角终止 (°)</label>
                <input
                  style={inputStyle}
                  type="number"
                  min="0"
                  max="359"
                  value={radarForm.azEnd}
                  onChange={updRadar('azEnd')}
                />
              </div>
              <div style={{ gridColumn: '1 / -1' }}>
                <label style={labelStyle}>扫描周期 (s)</label>
                <input
                  style={inputStyle}
                  type="number"
                  min="0.1"
                  step="0.1"
                  value={radarForm.period}
                  onChange={updRadar('period')}
                />
              </div>
              <div style={{ gridColumn: '1 / -1' }}>
                <button
                  className="btn primary"
                  style={{ width: '100%' }}
                  disabled={busy || sysid == null}
                  onClick={handleRadarConfig}
                >
                  下发配置
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* 物理模型切换 */}
        <div className="panel">
          <div className="panel-head">
            <h3>物理模型</h3>
            <span
              className="mono"
              style={{ fontSize: 11, color: 'var(--cyan)' }}
            >
              {physicsModel}
            </span>
          </div>
          <div className="panel-body">
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 7 }}>
              <button
                className={`btn ${physicsModel === 'kinematics' ? 'primary' : ''}`}
                disabled={busy || sysid == null}
                onClick={() => handlePhysicsModel('kinematics')}
              >
                运动学
              </button>
              <button
                className={`btn ${physicsModel === 'aero' ? 'primary' : ''}`}
                disabled={busy || sysid == null}
                onClick={() => handlePhysicsModel('aero')}
              >
                气动
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* 右栏：雷达状态/目标 + 旋翼 + LiDAR + IMU */}
      <div style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
        {sysid == null ? (
          <div className="empty-hint" style={{ textAlign: 'center', paddingTop: 60 }}>
            <span className="big">📡</span>
            从左侧选择一架无人机
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14, maxWidth: 880 }}>
            {/* 雷达状态 */}
            <Card title="雷达状态" sysid={sysid}>
              {!radarStatus ? (
                <div className="empty-hint">暂无雷达数据</div>
              ) : (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10 }}>
                  <SummaryItem
                    label="波束方位"
                    value={radarStatus.beamAz != null ? `${radarStatus.beamAz.toFixed(1)}°` : '--'}
                    color="var(--cyan)"
                  />
                  <SummaryItem
                    label="扫描模式"
                    value={radarStatus.mode || '--'}
                    color="var(--ok)"
                  />
                  <SummaryItem
                    label="扫描周期"
                    value={
                      radarStatus.period != null ? `${radarStatus.period.toFixed(1)} s` : '--'
                    }
                  />
                </div>
              )}
            </Card>

            {/* 雷达目标 */}
            <div className="panel" style={{ borderBottom: 'none' }}>
              <div className="panel-head">
                <h3>雷达目标</h3>
                <span className="mono" style={{ fontSize: 11, color: 'var(--dim)' }}>
                  {targets.length} 个
                </span>
              </div>
              <div className="panel-body">
                {targets.length === 0 ? (
                  <div className="empty-hint">暂无检测目标</div>
                ) : (
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                    <thead>
                      <tr
                        style={{
                          color: 'var(--dim)',
                          fontSize: 10,
                          letterSpacing: 1,
                          textAlign: 'left',
                        }}
                      >
                        <th style={thStyle}>ID</th>
                        <th style={thStyle}>距离</th>
                        <th style={thStyle}>方位</th>
                        <th style={thStyle}>RCS</th>
                        <th style={thStyle}>跟踪</th>
                      </tr>
                    </thead>
                    <tbody>
                      {targets.map((t, i) => (
                        <TargetRow key={t.id ?? i} t={t} />
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </div>

            {/* 旋翼遥测 */}
            <Card title="旋翼遥测" sysid={sysid}>
              {rotors.length === 0 ? (
                <div className="empty-hint">暂无旋翼数据</div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {rotors.map((r, i) => (
                    <div
                      key={i}
                      style={{
                        padding: 10,
                        background: 'var(--panel)',
                        border: '1px solid var(--line)',
                        borderRadius: 8,
                      }}
                    >
                      <div
                        style={{
                          fontSize: 10,
                          color: 'var(--dim-2)',
                          marginBottom: 6,
                          letterSpacing: 1,
                        }}
                      >
                        旋翼 #{i + 1}
                      </div>
                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 8 }}>
                        <SummaryItem
                          label="RPM"
                          value={r.rpm != null ? r.rpm.toFixed(0) : '--'}
                          color="var(--cyan)"
                        />
                        <SummaryItem
                          label="推力 (N)"
                          value={r.thrust != null ? r.thrust.toFixed(1) : '--'}
                          color="var(--ok)"
                        />
                        <SummaryItem
                          label="扭矩 (N·m)"
                          value={r.torque != null ? r.torque.toFixed(2) : '--'}
                        />
                        <SummaryItem
                          label="桨距角 (°)"
                          value={r.pitchAngle != null ? r.pitchAngle.toFixed(1) : '--'}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </Card>

            {/* LiDAR + IMU 并排 */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              {/* LiDAR */}
              <div className="panel" style={{ borderBottom: 'none' }}>
                <div className="panel-head">
                  <h3>LiDAR</h3>
                </div>
                <div className="panel-body">
                  {!lidar ? (
                    <div className="empty-hint">暂无 LiDAR 数据</div>
                  ) : (
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                      <SummaryItem
                        label="最近距离"
                        value={
                          lidar.nearestDistance != null
                            ? `${lidar.nearestDistance.toFixed(2)} m`
                            : '--'
                        }
                        color="var(--cyan)"
                      />
                      <SummaryItem
                        label="点数"
                        value={lidar.pointCount != null ? lidar.pointCount : '--'}
                        color="var(--ok)"
                      />
                    </div>
                  )}
                </div>
              </div>

              {/* IMU */}
              <div className="panel" style={{ borderBottom: 'none' }}>
                <div className="panel-head">
                  <h3>IMU</h3>
                </div>
                <div className="panel-body">
                  {!imu ? (
                    <div className="empty-hint">暂无 IMU 数据</div>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      <Vec3
                        label="加速度计"
                        unit="m/s²"
                        vec={imu.accelerometer}
                        color="var(--cyan)"
                      />
                      <Vec3
                        label="陀螺仪"
                        unit="rad/s"
                        vec={imu.gyroscope}
                        color="var(--ok)"
                      />
                      <Vec3
                        label="磁力计"
                        unit="μT"
                        vec={imu.magnetometer}
                        color="var(--gold)"
                      />
                    </div>
                  )}
                </div>
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

/* ---------- 卡片容器 ---------- */
function Card({ title, sysid, children }) {
  return (
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
          <div style={{ fontSize: 11, color: 'var(--dim)', letterSpacing: 1 }}>{title}</div>
          {sysid != null && (
            <div className="mono" style={{ fontSize: 16, fontWeight: 700, color: 'var(--cyan)' }}>
              #{sysid}
            </div>
          )}
        </div>
      </div>
      {children}
    </div>
  )
}

/* ---------- 摘要单项 ---------- */
function SummaryItem({ label, value, color }) {
  return (
    <div>
      <div style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: 1 }}>{label}</div>
      <div className="mono" style={{ fontSize: 15, fontWeight: 600, color: color || 'var(--text)' }}>
        {value}
      </div>
    </div>
  )
}

/* ---------- 三维向量展示 ---------- */
function Vec3({ label, unit, vec, color }) {
  const v = vec || {}
  return (
    <div
      style={{
        padding: 8,
        background: 'var(--panel)',
        border: '1px solid var(--line)',
        borderRadius: 6,
      }}
    >
      <div style={{ fontSize: 10, color: 'var(--dim-2)', marginBottom: 4, letterSpacing: 1 }}>
        {label} <span style={{ color: 'var(--dim)' }}>({unit})</span>
      </div>
      <div
        className="mono"
        style={{ fontSize: 11.5, color: color || 'var(--text)', display: 'flex', gap: 10 }}
      >
        <span>x {v.x != null ? v.x.toFixed(3) : '--'}</span>
        <span>y {v.y != null ? v.y.toFixed(3) : '--'}</span>
        <span>z {v.z != null ? v.z.toFixed(3) : '--'}</span>
      </div>
    </div>
  )
}

/* ---------- 雷达目标行 ---------- */
function TargetRow({ t }) {
  const meta = t.trackState
    ? TRACK_STATE_META[t.trackState] || { label: t.trackState, color: 'var(--dim)' }
    : { label: '--', color: 'var(--dim-2)' }
  return (
    <tr style={{ borderBottom: '1px solid var(--line)' }}>
      <td
        style={{
          padding: '7px 8px',
          fontFamily: 'var(--mono)',
          fontWeight: 700,
          color: 'var(--cyan)',
        }}
      >
        {t.id ?? '--'}
      </td>
      <td style={{ padding: '7px 8px', fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--text)' }}>
        {t.distance != null ? `${t.distance.toFixed(1)} m` : '--'}
      </td>
      <td style={{ padding: '7px 8px', fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--dim)' }}>
        {t.azimuth != null ? `${t.azimuth.toFixed(1)}°` : '--'}
      </td>
      <td style={{ padding: '7px 8px', fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--dim)' }}>
        {t.rcs != null ? `${t.rcs.toFixed(2)} m²` : '--'}
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