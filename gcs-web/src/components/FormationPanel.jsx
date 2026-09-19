import React, { useState, useMemo, useEffect } from 'react'
import { api } from '../api.js'

// 编队观察面板（M1 T10）
// 展示活跃编队列表 + 队形俯视图 + 成员灯光状态 + 创建/命令/变换/灯光控制
// 编队数据由 App.jsx 通过 WebSocket formation 帧实时推送（1Hz），经 props.formations 传入

const SHAPES = ['LINE', 'COLUMN', 'VEE', 'CIRCLE', 'DIAMOND']

const STATE_META = {
  FORMING: { label: '组建中', cls: 'st-forming', color: 'var(--cyan)' },
  STABLE: { label: '稳定', cls: 'st-stable', color: 'var(--ok)' },
  TRANSITIONING: { label: '变换中', cls: 'st-trans', color: 'var(--warn)' },
  DISSOLVED: { label: '已解散', cls: 'st-dissolved', color: 'var(--dim-2)' },
}

const PATTERNS = ['STEADY', 'BLINK', 'BREATHE', 'CHASE', 'RAINBOW']

const ROLE_LABEL = { LEADER: '长机', WORKER: '僚机', RELAY: '中继' }

// 灯效 → 图标/颜色示意
const PATTERN_ICON = {
  STEADY: '●',
  BLINK: '◉',
  BREATHE: '◐',
  CHASE: '▹',
  RAINBOW: '🌈',
}

export default function FormationPanel({ formations = [], drones = [] }) {
  const [selectedId, setSelectedId] = useState(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)
  const [showCreate, setShowCreate] = useState(false)

  // 创建编队表单
  const [form, setForm] = useState({
    members: '1,2,3',
    shape: 'LINE',
    spacing: 5,
    heading: 0,
    refLat: 22.5907,
    refLon: 113.9345,
    refAlt: 50,
  })

  // 队形变换控件
  const [transShape, setTransShape] = useState('CIRCLE')
  const [transSteps, setTransSteps] = useState(4)

  // 灯光控制表单
  const [lights, setLights] = useState({
    on: true,
    pattern: 'BLINK',
    brightness: 80,
    freq: 2,
    colorR: 255,
    colorG: 0,
    colorB: 0,
    sync: true,
  })

  const selected = useMemo(
    () => formations.find((f) => f.formationId === selectedId),
    [formations, selectedId]
  )

  // 自动选中第一个编队（无选中时）
  useEffect(() => {
    if (selectedId == null && formations.length > 0) {
      setSelectedId(formations[0].formationId)
    }
  }, [formations, selectedId])

  // 统一的 API 调用包装：管理 busy/result
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

  const handleCreate = async () => {
    const members = form.members
      .split(/[,\s]+/)
      .map((s) => parseInt(s.trim(), 10))
      .filter((n) => !Number.isNaN(n))
    if (members.length < 2) {
      setResult({ ok: false, msg: '成员数量需 ≥ 2' })
      return
    }
    const r = await call(
      () =>
        api.createFormation({
          members,
          shape: form.shape,
          spacing: Number(form.spacing),
          heading: Number(form.heading),
          refLat: Number(form.refLat),
          refLon: Number(form.refLon),
          refAlt: Number(form.refAlt),
        }),
      '创建编队'
    )
    if (r && r.formationId != null) {
      setSelectedId(r.formationId)
      setShowCreate(false)
    }
  }

  const handleCommand = (type, alt) => {
    if (selectedId == null) return
    call(() => api.commandFormation(selectedId, type, alt), `命令 ${type}`)
  }

  const handleTransition = () => {
    if (selectedId == null) return
    call(
      () => api.transitionFormation(selectedId, transShape, Number(transSteps)),
      `变换 → ${transShape}`
    )
  }

  const handleLights = () => {
    if (selectedId == null) return
    call(() => api.lightsFormation(selectedId, lights), '灯光控制')
  }

  const handleDissolve = () => {
    if (selectedId == null) return
    call(() => api.dissolveFormation(selectedId), '解散编队')
  }

  const handleRemoveMember = (sysid) => {
    if (selectedId == null) return
    call(() => api.removeMember(selectedId, sysid), `移除 ${sysid}`)
  }

  return (
    <div style={{ display: 'flex', height: '100%', minHeight: 0 }}>
      {/* 左栏：编队列表 + 创建表单 */}
      <div
        style={{
          width: 320,
          flex: 'none',
          borderRight: '1px solid var(--line)',
          background: 'var(--bg-2)',
          overflowY: 'auto',
        }}
      >
        <div className="panel">
          <div className="panel-head">
            <h3>编队列表</h3>
            <button
              className="btn primary"
              style={{ padding: '4px 10px', fontSize: 11 }}
              onClick={() => setShowCreate((v) => !v)}
            >
              {showCreate ? '取消' : '＋ 新建'}
            </button>
          </div>
          <div className="panel-body">
            {showCreate && (
              <CreateForm
                form={form}
                setForm={setForm}
                onCreate={handleCreate}
                onCancel={() => setShowCreate(false)}
                busy={busy}
              />
            )}
            {formations.length === 0 && !showCreate && (
              <div className="empty-hint">
                <span className="big">🛩</span>
                暂无活跃编队
                <br />
                点击「＋ 新建」创建
              </div>
            )}
            <ul
              style={{
                listStyle: 'none',
                display: 'flex',
                flexDirection: 'column',
                gap: 6,
                marginTop: showCreate ? 10 : 0,
              }}
            >
              {formations.map((f) => (
                <FormationCard
                  key={f.formationId}
                  f={f}
                  selected={f.formationId === selectedId}
                  onSelect={() => setSelectedId(f.formationId)}
                />
              ))}
            </ul>
          </div>
        </div>
      </div>

      {/* 右栏：编队详情 + 命令 + 灯光 */}
      <div style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
        {!selected ? (
          <div className="empty-hint" style={{ textAlign: 'center', paddingTop: 60 }}>
            <span className="big">←</span>
            从左侧选择一个编队查看详情
          </div>
        ) : (
          <FormationDetail
            f={selected}
            busy={busy}
            result={result}
            transShape={transShape}
            setTransShape={setTransShape}
            transSteps={transSteps}
            setTransSteps={setTransSteps}
            lights={lights}
            setLights={setLights}
            onCommand={handleCommand}
            onTransition={handleTransition}
            onLights={handleLights}
            onDissolve={handleDissolve}
            onRemoveMember={handleRemoveMember}
          />
        )}
      </div>
    </div>
  )
}

/* ---------- 编队卡片 ---------- */
function FormationCard({ f, selected, onSelect }) {
  const meta = STATE_META[f.state] || { label: f.state, color: 'var(--dim)' }
  const onlineCount = (f.members || []).filter((m) => m.online).length
  return (
    <li
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onSelect()
        }
      }}
      style={{
        cursor: 'pointer',
        padding: '10px 12px',
        borderRadius: 'var(--r)',
        background: selected
          ? 'linear-gradient(135deg, rgba(0,212,255,.09), var(--panel))'
          : 'var(--panel)',
        border: selected
          ? '1px solid rgba(0,212,255,.55)'
          : '1px solid var(--line)',
        boxShadow: selected ? '0 0 0 1px rgba(0,212,255,.25)' : 'none',
        display: 'grid',
        gridTemplateColumns: '1fr auto',
        gap: '2px 8px',
        alignItems: 'center',
        transition: 'border-color .15s, background .15s',
      }}
    >
      <span style={{ fontWeight: 700, fontSize: 13 }}>
        编队 #{f.formationId}
      </span>
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
      <span style={{ gridColumn: '1 / -1', fontSize: 11, color: 'var(--dim)', display: 'flex', gap: 10, flexWrap: 'wrap' }}>
        <span>队形 <b style={{ color: 'var(--cyan)' }}>{f.shape}</b></span>
        <span>长机 <b style={{ color: 'var(--text)' }}>#{f.leader}</b></span>
        <span>
          成员 <b style={{ color: 'var(--ok)' }}>{onlineCount}</b>
          <span style={{ color: 'var(--dim-2)' }}>/{(f.members || []).length}</span>
        </span>
      </span>
    </li>
  )
}

/* ---------- 创建编队表单 ---------- */
function CreateForm({ form, setForm, onCreate, onCancel, busy }) {
  const upd = (k) => (e) => setForm({ ...form, [k]: e.target.value })
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
  return (
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
        <label style={labelStyle}>成员 sysid（逗号分隔）</label>
        <input style={inputStyle} value={form.members} onChange={upd('members')} placeholder="1,2,3" />
      </div>
      <div>
        <label style={labelStyle}>队形</label>
        <select style={inputStyle} value={form.shape} onChange={upd('shape')}>
          {SHAPES.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>
      <div>
        <label style={labelStyle}>间距 (m)</label>
        <input style={inputStyle} type="number" min="2" value={form.spacing} onChange={upd('spacing')} />
      </div>
      <div>
        <label style={labelStyle}>航向 (°)</label>
        <input style={inputStyle} type="number" min="0" max="359" value={form.heading} onChange={upd('heading')} />
      </div>
      <div>
        <label style={labelStyle}>参考高度 (m)</label>
        <input style={inputStyle} type="number" min="0" value={form.refAlt} onChange={upd('refAlt')} />
      </div>
      <div>
        <label style={labelStyle}>参考纬度</label>
        <input style={inputStyle} type="number" step="0.0001" value={form.refLat} onChange={upd('refLat')} />
      </div>
      <div>
        <label style={labelStyle}>参考经度</label>
        <input style={inputStyle} type="number" step="0.0001" value={form.refLon} onChange={upd('refLon')} />
      </div>
      <div style={{ gridColumn: '1 / -1', display: 'flex', gap: 6, marginTop: 2 }}>
        <button className="btn primary" style={{ flex: 1 }} disabled={busy} onClick={onCreate}>
          创建编队
        </button>
        <button className="btn" onClick={onCancel}>取消</button>
      </div>
    </div>
  )
}

/* ---------- 编队详情 ---------- */
function FormationDetail({
  f,
  busy,
  result,
  transShape,
  setTransShape,
  transSteps,
  setTransSteps,
  lights,
  setLights,
  onCommand,
  onTransition,
  onLights,
  onDissolve,
  onRemoveMember,
}) {
  const meta = STATE_META[f.state] || { label: f.state, color: 'var(--dim)' }
  const members = f.members || []
  const onlineCount = members.filter((m) => m.online).length

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, maxWidth: 880 }}>
      {/* 摘要卡片 */}
      <div
        style={{
          background: 'linear-gradient(180deg, var(--panel-2), var(--panel))',
          border: '1px solid var(--line-2)',
          borderRadius: 'var(--r)',
          padding: 14,
          display: 'grid',
          gridTemplateColumns: 'auto 1fr auto',
          gap: 14,
          alignItems: 'center',
        }}
      >
        <div>
          <div style={{ fontSize: 11, color: 'var(--dim)', letterSpacing: 1 }}>编队 ID</div>
          <div className="mono" style={{ fontSize: 22, fontWeight: 700, color: 'var(--cyan)' }}>
            #{f.formationId}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
          <SummaryItem label="状态" value={meta.label} color={meta.color} />
          <SummaryItem label="队形" value={f.shape} color="var(--cyan)" />
          <SummaryItem label="长机" value={`#${f.leader}`} />
          <SummaryItem label="成员" value={`${onlineCount}/${members.length}`} color="var(--ok)" />
          <SummaryItem label="版本" value={f.version ?? '--'} />
        </div>
        <FormationShapeView members={members} />
      </div>

      {/* 命令按钮 */}
      <div className="panel" style={{ borderBottom: 'none' }}>
        <div className="panel-head">
          <h3>编队命令</h3>
        </div>
        <div className="panel-body">
          <div className="cmd-grid" style={{ gridTemplateColumns: '1fr 1fr 1fr' }}>
            <button className="btn takeoff" disabled={busy} onClick={() => onCommand('TAKEOFF', 30)}>
              <span className="icon">🛫</span>起飞
            </button>
            <button className="btn rtl" disabled={busy} onClick={() => onCommand('RTL')}>
              <span className="icon">⟲</span>返航
            </button>
            <button
              className="btn kill"
              disabled={busy}
              onClick={onDissolve}
              title="解散编队（在飞成员 RTL 后置 DISSOLVED）"
            >
              <span className="icon">✋</span>解散
            </button>
          </div>

          {/* 队形变换 */}
          <div
            style={{
              marginTop: 10,
              padding: 10,
              background: 'var(--panel)',
              border: '1px solid var(--line)',
              borderRadius: 8,
              display: 'grid',
              gridTemplateColumns: 'auto auto 1fr auto',
              gap: 8,
              alignItems: 'end',
            }}
          >
            <span style={{ fontSize: 11, color: 'var(--dim)', fontWeight: 600 }}>队形变换</span>
            <div>
              <label style={{ fontSize: 9.5, color: 'var(--dim-2)', display: 'block' }}>目标队形</label>
              <select
                style={selectStyle}
                value={transShape}
                onChange={(e) => setTransShape(e.target.value)}
              >
                {SHAPES.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
            </div>
            <div>
              <label style={{ fontSize: 9.5, color: 'var(--dim-2)', display: 'block' }}>插值步数</label>
              <input
                style={selectStyle}
                type="number"
                min="1"
                value={transSteps}
                onChange={(e) => setTransSteps(e.target.value)}
              />
            </div>
            <button className="btn primary" disabled={busy} onClick={onTransition}>
              变换
            </button>
          </div>

          {result && (
            <div className={`cmd-result ${result.ok ? 'ok' : 'bad'}`} style={{ marginTop: 10 }}>
              {result.msg}
            </div>
          )}
        </div>
      </div>

      {/* 灯光控制 */}
      <LightsControl lights={lights} setLights={setLights} onLights={onLights} busy={busy} />

      {/* 成员列表 */}
      <div className="panel" style={{ borderBottom: 'none' }}>
        <div className="panel-head">
          <h3>成员明细</h3>
          <span className="mono" style={{ fontSize: 11, color: 'var(--dim)' }}>
            {members.length} 架
          </span>
        </div>
        <div className="panel-body">
          {members.length === 0 ? (
            <div className="empty-hint">无成员</div>
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <thead>
                <tr style={{ color: 'var(--dim)', fontSize: 10, letterSpacing: 1, textAlign: 'left' }}>
                  <th style={thStyle}>sysid</th>
                  <th style={thStyle}>链路</th>
                  <th style={thStyle}>角色</th>
                  <th style={thStyle}>目标位置</th>
                  <th style={thStyle}>灯光</th>
                  <th style={thStyle}>操作</th>
                </tr>
              </thead>
              <tbody>
                {members.map((m) => (
                  <MemberRow key={m.sysid} m={m} onRemove={() => onRemoveMember(m.sysid)} busy={busy} />
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}

const thStyle = {
  padding: '6px 8px',
  borderBottom: '1px solid var(--line)',
  fontWeight: 600,
}

const selectStyle = {
  padding: '4px 6px',
  fontSize: 11,
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 4,
  color: 'var(--text)',
  fontFamily: 'var(--mono)',
  width: '100%',
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

/* ---------- 队形俯视图 ---------- */
function FormationShapeView({ members }) {
  const pts = useMemo(() => {
    const valid = members.filter((m) => m.target && m.target.lat != null)
    if (valid.length === 0) return []
    const refLat = valid.reduce((s, m) => s + m.target.lat, 0) / valid.length
    const refLon = valid.reduce((s, m) => s + m.target.lon, 0) / valid.length
    const R = 6371000
    return valid.map((m) => ({
      sysid: m.sysid,
      online: m.online,
      north: (m.target.lat - refLat) * (Math.PI / 180) * R,
      east:
        (m.target.lon - refLon) *
        (Math.PI / 180) *
        R *
        Math.cos((refLat * Math.PI) / 180),
    }))
  }, [members])

  if (pts.length === 0) {
    return (
      <div
        style={{
          width: 100,
          height: 100,
          display: 'grid',
          placeItems: 'center',
          color: 'var(--dim-2)',
          fontSize: 10,
          border: '1px dashed var(--line)',
          borderRadius: 8,
        }}
      >
        无位置
      </div>
    )
  }

  const maxR = Math.max(...pts.map((p) => Math.hypot(p.north, p.east)), 1)
  const scale = 38 / maxR
  const cx = 50
  const cy = 50

  return (
    <svg
      width="100"
      height="100"
      viewBox="0 0 100 100"
      style={{ flex: 'none', filter: 'drop-shadow(0 2px 6px rgba(0,0,0,.4))' }}
    >
      <circle cx={cx} cy={cy} r={45} fill="none" stroke="var(--line)" strokeDasharray="2 3" />
      <line x1={cx - 45} y1={cy} x2={cx + 45} y2={cy} stroke="var(--line)" strokeWidth="0.5" />
      <line x1={cx} y1={cy - 45} x2={cx} y2={cy + 45} stroke="var(--line)" strokeWidth="0.5" />
      <text x={cx + 48} y={cy + 3} fill="var(--dim-2)" fontSize="6">E</text>
      <text x={cx} y={cy - 47} fill="var(--dim-2)" fontSize="6" textAnchor="middle">N</text>
      {pts.map((p) => {
        const x = cx + p.east * scale
        const y = cy - p.north * scale
        return (
          <g key={p.sysid}>
            <circle
              cx={x}
              cy={y}
              r={4.5}
              fill={p.online ? 'var(--cyan)' : 'var(--dim-2)'}
              stroke="var(--text)"
              strokeWidth="0.6"
            />
            <text
              x={x}
              y={y - 6}
              textAnchor="middle"
              fill="var(--dim)"
              fontSize="6.5"
              fontFamily="monospace"
            >
              {p.sysid}
            </text>
          </g>
        )
      })}
    </svg>
  )
}

/* ---------- 灯光控制面板 ---------- */
function LightsControl({ lights, setLights, onLights, busy }) {
  const upd = (k) => (e) => {
    const val = e.target.type === 'checkbox' ? e.target.checked : e.target.value
    setLights({ ...lights, [k]: val })
  }
  const labelStyle = { fontSize: 9.5, color: 'var(--dim-2)', display: 'block', marginBottom: 2 }
  const inputStyle = {
    width: '100%',
    padding: '4px 6px',
    fontSize: 11,
    background: 'var(--bg-2)',
    border: '1px solid var(--line-2)',
    borderRadius: 4,
    color: 'var(--text)',
    fontFamily: 'var(--mono)',
  }
  // 颜色预览
  const colorPreview = `rgb(${lights.colorR}, ${lights.colorG}, ${lights.colorB})`

  return (
    <div className="panel" style={{ borderBottom: 'none' }}>
      <div className="panel-head">
        <h3>灯光控制</h3>
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            fontSize: 11,
            color: lights.on ? 'var(--gold)' : 'var(--dim-2)',
          }}
        >
          <span
            style={{
              width: 12,
              height: 12,
              borderRadius: '50%',
              background: lights.on ? colorPreview : 'var(--dim-2)',
              boxShadow: lights.on ? `0 0 8px ${colorPreview}` : 'none',
            }}
          />
          {lights.on ? 'ON' : 'OFF'}
        </span>
      </div>
      <div className="panel-body">
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'auto 1fr 1fr 1fr 1fr auto',
            gap: 8,
            alignItems: 'end',
            padding: 10,
            background: 'var(--panel)',
            border: '1px solid var(--line)',
            borderRadius: 8,
          }}
        >
          <div>
            <label style={labelStyle}>开关</label>
            <input
              type="checkbox"
              checked={lights.on}
              onChange={upd('on')}
              style={{ width: 16, height: 16 }}
            />
          </div>
          <div>
            <label style={labelStyle}>模式</label>
            <select style={inputStyle} value={lights.pattern} onChange={upd('pattern')}>
              {PATTERNS.map((p) => (
                <option key={p} value={p}>{p}</option>
              ))}
            </select>
          </div>
          <div>
            <label style={labelStyle}>亮度 %</label>
            <input style={inputStyle} type="number" min="0" max="100" value={lights.brightness} onChange={upd('brightness')} />
          </div>
          <div>
            <label style={labelStyle}>频率 Hz</label>
            <input style={inputStyle} type="number" min="0" step="0.5" value={lights.freq} onChange={upd('freq')} />
          </div>
          <div>
            <label style={labelStyle}>同步</label>
            <input
              type="checkbox"
              checked={lights.sync}
              onChange={upd('sync')}
              style={{ width: 16, height: 16 }}
            />
          </div>
          <button className="btn primary" disabled={busy} onClick={onLights}>
            下发
          </button>
        </div>
        {/* 颜色 RGB */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr 1fr',
            gap: 8,
            marginTop: 8,
            padding: 10,
            background: 'var(--panel)',
            border: '1px solid var(--line)',
            borderRadius: 8,
          }}
        >
          {['colorR', 'colorG', 'colorB'].map((k, i) => (
            <div key={k}>
              <label style={labelStyle}>{['R', 'G', 'B'][i]}</label>
              <input
                style={inputStyle}
                type="number"
                min="0"
                max="255"
                value={lights[k]}
                onChange={upd(k)}
              />
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/* ---------- 成员行 ---------- */
function MemberRow({ m, onRemove, busy }) {
  const role = ROLE_LABEL[m.role] || m.role || '--'
  const led = m.led || {}
  const target = m.target || {}
  return (
    <tr style={{ borderBottom: '1px solid var(--line)' }}>
      <td style={{ padding: '7px 8px', fontFamily: 'var(--mono)', fontWeight: 700, color: 'var(--cyan)' }}>
        #{m.sysid}
      </td>
      <td style={{ padding: '7px 8px' }}>
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            fontSize: 11,
            color: m.online ? 'var(--ok)' : 'var(--dim-2)',
          }}
        >
          <span
            style={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              background: m.online ? 'var(--ok)' : 'var(--dim-2)',
              boxShadow: m.online ? '0 0 6px var(--ok)' : 'none',
            }}
          />
          {m.online ? '在线' : '离线'}
        </span>
      </td>
      <td style={{ padding: '7px 8px', fontSize: 11, color: 'var(--dim)' }}>{role}</td>
      <td style={{ padding: '7px 8px', fontFamily: 'var(--mono)', fontSize: 10.5, color: 'var(--dim)' }}>
        {target.lat != null ? `${target.lat.toFixed(5)}, ${target.lon.toFixed(5)} · ${target.alt ?? '--'}m` : '--'}
      </td>
      <td style={{ padding: '7px 8px' }}>
        <LedBadge led={led} />
      </td>
      <td style={{ padding: '7px 8px' }}>
        <button
          className="btn"
          style={{ padding: '3px 8px', fontSize: 10, borderColor: 'rgba(255,93,93,.4)', color: 'var(--crit)' }}
          disabled={busy}
          onClick={onRemove}
          title="移除该成员并重整队形"
        >
          移除
        </button>
      </td>
    </tr>
  )
}

/* ---------- 灯光状态徽标 ---------- */
function LedBadge({ led }) {
  if (!led || !led.on) {
    return <span style={{ fontSize: 11, color: 'var(--dim-2)' }}>○ 灭</span>
  }
  const color = `rgb(${led.colorR ?? 0}, ${led.colorG ?? 0}, ${led.colorB ?? 0})`
  const icon = PATTERN_ICON[led.pattern] || '●'
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 5,
        fontSize: 11,
        padding: '2px 7px',
        borderRadius: 999,
        border: '1px solid var(--line-2)',
        background: 'var(--panel-2)',
      }}
    >
      <span
        style={{
          color,
          textShadow: `0 0 6px ${color}`,
          fontSize: 13,
          lineHeight: 1,
        }}
      >
        {icon}
      </span>
      <span style={{ color: 'var(--gold)', fontFamily: 'var(--mono)', fontSize: 10 }}>
        {led.pattern || '?'}
      </span>
      <span style={{ color: 'var(--dim)', fontFamily: 'var(--mono)', fontSize: 10 }}>
        {led.brightness ?? '--'}%
      </span>
    </span>
  )
}