import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

// 移动基站载荷面板（M6 移动基站载荷抽象）
// 展示基站列表、覆盖可视化、终端接入表、配置下发、漫游触发
// 通过 WebSocket 实时刷新（celltower-topology 事件）
// 风格与 MeshTopologyPanel 一致：卡片布局 + 内联 CSS + CSS 变量

const POLL_MS = 2000

// 制式颜色
const CELL_TYPE_COLORS = {
  0: '#3498DB',   // LTE - 蓝
  1: '#2ECC71',   // WIFI - 绿
  2: '#F39C12',   // LORA - 橙
}

const CELL_TYPE_LABELS = {
  0: 'LTE 微蜂窝',
  1: 'WiFi Mesh',
  2: 'LoRa',
}

// 终端类型标签
const TERMINAL_TYPE_LABELS = {
  0: '手机',
  1: '对讲机',
  2: '传感器',
}

// 漫游原因标签
const HANDOVER_REASON_LABELS = {
  0: '信号弱',
  1: '负载均衡',
  2: '基站关闭',
}

// 圆形布局：将基站均匀分布在圆周上
function circularLayout(nodes, cx, cy, radius) {
  const n = nodes.length
  if (n === 0) return {}
  if (n === 1) return { [nodes[0].sysid]: { x: cx, y: cy } }
  const layout = {}
  for (let i = 0; i < n; i++) {
    const angle = (2 * Math.PI * i) / n - Math.PI / 2
    layout[nodes[i].sysid] = {
      x: cx + radius * Math.cos(angle),
      y: cy + radius * Math.sin(angle),
    }
  }
  return layout
}

export default function CellTowerPanel({ cellTowerData }) {
  const [topology, setTopology] = useState(null)
  const [handovers, setHandovers] = useState(null)
  const [selectedTower, setSelectedTower] = useState(null)
  const [towerTerminals, setTowerTerminals] = useState(null)
  const [pollErr, setPollErr] = useState(null)
  const [configForm, setConfigForm] = useState({ cellType: 0, txPowerDbm: 20, maxTerminals: 50, frequencyChannel: 1 })
  const [handoverForm, setHandoverForm] = useState({ terminalId: 0, toSysid: 0, reason: 0 })
  const [actionMsg, setActionMsg] = useState(null)
  const timerRef = useRef(null)

  // 初始加载 + 周期刷新拓扑
  const refreshTopology = useCallback(async () => {
    try {
      const [topo, hov] = await Promise.all([
        api.getCellTowers().catch(() => null),
        api.getCellTowerHandovers().catch(() => null),
      ])
      setTopology(topo)
      setHandovers(hov)
      setPollErr(null)
    } catch (e) {
      setPollErr(e.message)
    }
  }, [])

  useEffect(() => {
    refreshTopology()
    timerRef.current = setInterval(refreshTopology, POLL_MS)
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [refreshTopology])

  // 选中基站时加载终端列表
  useEffect(() => {
    if (selectedTower == null) {
      setTowerTerminals(null)
      return
    }
    let cancelled = false
    const load = async () => {
      try {
        const terminals = await api.getCellTowerTerminals(selectedTower).catch(() => null)
        if (!cancelled) setTowerTerminals(terminals)
      } catch (e) {
        if (!cancelled) setTowerTerminals(null)
      }
    }
    load()
    return () => { cancelled = true }
  }, [selectedTower])

  // WebSocket 事件合并（由 App.jsx 注入 cellTowerData）
  useEffect(() => {
    if (cellTowerData && cellTowerData.type === 'celltower-topology') {
      refreshTopology()
    }
  }, [cellTowerData, refreshTopology])

  const handleTowerClick = (sysid) => {
    setSelectedTower(sysid)
  }

  // 下发配置
  const handleConfigSubmit = async (e) => {
    e.preventDefault()
    if (selectedTower == null) return
    setActionMsg(null)
    try {
      const result = await api.configureCellTower(selectedTower, configForm)
      setActionMsg({ type: 'ok', text: `配置已下发: 制式=${CELL_TYPE_LABELS[result.cellType] || result.cellType}, 功率=${result.txPowerDbm}dBm` })
    } catch (err) {
      setActionMsg({ type: 'err', text: `配置下发失败: ${err.message}` })
    }
  }

  // 触发漫游
  const handleHandoverSubmit = async (e) => {
    e.preventDefault()
    if (selectedTower == null) return
    setActionMsg(null)
    try {
      const result = await api.triggerCellTowerHandover(selectedTower, handoverForm)
      setActionMsg({ type: 'ok', text: `漫游已触发: 终端=${result.terminalId} → 目标机=${result.toSysid}` })
    } catch (err) {
      setActionMsg({ type: 'err', text: `漫游触发失败: ${err.message}` })
    }
  }

  // 渲染拓扑图
  const towers = topology?.towers || []
  const hasData = towers.length > 0

  // SVG 尺寸
  const svgW = 600
  const svgH = 400
  const cx = svgW / 2
  const cy = svgH / 2
  const radius = Math.min(svgW, svgH) / 2 - 50

  // 圆形布局
  const layout = hasData && towers.length <= 20
    ? circularLayout(towers, cx, cy, radius)
    : {}

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        移动基站载荷
      </h2>

      {pollErr && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8 }}>
          拓扑获取失败：{pollErr}
        </div>
      )}

      {actionMsg && (
        <div style={{
          color: actionMsg.type === 'ok' ? 'var(--cyan)' : 'var(--crit)',
          fontSize: 11, marginBottom: 8,
        }}>
          {actionMsg.text}
        </div>
      )}

      {/* 拓扑概览 */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <div style={cardStyle}>
          <div style={labelStyle}>基站数</div>
          <div style={valueStyle}>{topology?.towerCount ?? '--'}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>终端数</div>
          <div style={valueStyle}>{topology?.terminalCount ?? '--'}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>拓扑版本</div>
          <div style={valueStyle}>{topology?.version ?? '--'}</div>
        </div>
      </div>

      {/* 拓扑图 SVG */}
      {hasData ? (
        <div style={{
          background: 'var(--bg-2)', borderRadius: 6, padding: 8, marginBottom: 12,
          border: '1px solid var(--line-2)',
        }}>
          <svg width="100%" viewBox={`0 0 ${svgW} ${svgH}`} style={{ maxHeight: 400 }}>
            {/* 渲染基站 */}
            {towers.map((tower) => {
              const pos = layout[tower.sysid]
              if (!pos) return null
              const isSelected = selectedTower === tower.sysid
              const color = CELL_TYPE_COLORS[tower.cellType] || 'var(--dim)'
              const utilColor = tower.capacityUtilization > 80 ? 'var(--crit)'
                : tower.capacityUtilization > 50 ? '#F39C12' : 'var(--cyan)'
              // 覆盖圆
              const coverageR = Math.min(40, Math.max(15, tower.coverageRadiusM / 50))
              return (
                <g
                  key={`tower-${tower.sysid}`}
                  onClick={() => handleTowerClick(tower.sysid)}
                  style={{ cursor: 'pointer' }}
                >
                  {/* 覆盖范围 */}
                  <circle
                    cx={pos.x} cy={pos.y}
                    r={coverageR}
                    fill={color}
                    fillOpacity={0.1}
                    stroke={color}
                    strokeWidth={1}
                    strokeDasharray="3 3"
                  />
                  {/* 基站节点 */}
                  <circle
                    cx={pos.x} cy={pos.y}
                    r={isSelected ? 14 : 10}
                    fill={color}
                    stroke={isSelected ? 'var(--text)' : 'none'}
                    strokeWidth={2}
                    opacity={0.9}
                  />
                  <text
                    x={pos.x} y={pos.y + 3}
                    textAnchor="middle"
                    fontSize={9}
                    fill="var(--bg-1)"
                    fontFamily="var(--mono)"
                  >
                    {tower.sysid}
                  </text>
                  <text
                    x={pos.x} y={pos.y + 24}
                    textAnchor="middle"
                    fontSize={8}
                    fill="var(--dim-2)"
                  >
                    {CELL_TYPE_LABELS[tower.cellType] || '?'}
                  </text>
                  <text
                    x={pos.x} y={pos.y + 36}
                    textAnchor="middle"
                    fontSize={8}
                    fill={utilColor}
                  >
                    {tower.connectedTerminals}终端 {tower.capacityUtilization}%
                  </text>
                </g>
              )
            })}
          </svg>
          {/* 制式图例 */}
          <div style={{
            display: 'flex', gap: 12, justifyContent: 'center', marginTop: 4, fontSize: 10,
          }}>
            {Object.entries(CELL_TYPE_LABELS).map(([key, label]) => (
              <span key={key} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <span style={{
                  display: 'inline-block', width: 10, height: 10,
                  borderRadius: '50%',
                  background: CELL_TYPE_COLORS[key],
                }} />
                <span style={{ color: 'var(--dim-2)' }}>{label}</span>
              </span>
            ))}
          </div>
        </div>
      ) : (
        <div style={{
          background: 'var(--bg-2)', borderRadius: 6, padding: 24, marginBottom: 12,
          border: '1px solid var(--line-2)', textAlign: 'center', color: 'var(--dim-2)',
          fontSize: 11,
        }}>
          暂无基站数据（请确认 drone-sim 已启用 --celltower）
        </div>
      )}

      {/* 选中基站详情 */}
      {selectedTower != null && (
        <div style={{
          background: 'var(--bg-2)', borderRadius: 6, padding: 12,
          border: '1px solid var(--line-2)', marginBottom: 12,
        }}>
          <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
            基站 #{selectedTower} 详情
          </h3>

          {/* 终端接入表 */}
          <div style={{ marginBottom: 12 }}>
            <div style={labelStyle}>接入终端</div>
            {towerTerminals && towerTerminals.terminals && towerTerminals.terminals.length > 0 ? (
              <table style={tableStyle}>
                <thead>
                  <tr>
                    <th style={thStyle}>终端ID</th>
                    <th style={thStyle}>类型</th>
                    <th style={thStyle}>接入基站</th>
                  </tr>
                </thead>
                <tbody>
                  {towerTerminals.terminals.map((t) => (
                    <tr key={t.terminalId}>
                      <td style={tdStyle}>{t.terminalId}</td>
                      <td style={tdStyle}>{TERMINAL_TYPE_LABELS[t.terminalType] || t.terminalType}</td>
                      <td style={tdStyle}>{t.connectedSysid}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <div style={{ color: 'var(--dim-2)', fontSize: 10 }}>无终端接入</div>
            )}
          </div>

          {/* 配置下发 */}
          <div style={{ marginBottom: 12 }}>
            <div style={labelStyle}>配置下发</div>
            <form onSubmit={handleConfigSubmit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
              <label style={formLabelStyle}>
                制式
                <select
                  style={formInputStyle}
                  value={configForm.cellType}
                  onChange={(e) => setConfigForm({ ...configForm, cellType: parseInt(e.target.value) })}
                >
                  <option value={0}>LTE 微蜂窝</option>
                  <option value={1}>WiFi Mesh</option>
                  <option value={2}>LoRa</option>
                </select>
              </label>
              <label style={formLabelStyle}>
                发射功率(dBm)
                <input
                  type="number"
                  style={formInputStyle}
                  value={configForm.txPowerDbm}
                  onChange={(e) => setConfigForm({ ...configForm, txPowerDbm: parseInt(e.target.value) || 0 })}
                />
              </label>
              <label style={formLabelStyle}>
                最大终端数
                <input
                  type="number"
                  style={formInputStyle}
                  value={configForm.maxTerminals}
                  onChange={(e) => setConfigForm({ ...configForm, maxTerminals: parseInt(e.target.value) || 0 })}
                />
              </label>
              <label style={formLabelStyle}>
                频段
                <input
                  type="number"
                  style={formInputStyle}
                  value={configForm.frequencyChannel}
                  onChange={(e) => setConfigForm({ ...configForm, frequencyChannel: parseInt(e.target.value) || 0 })}
                />
              </label>
              <button type="submit" className="btn primary" style={{ padding: '4px 12px', fontSize: 11 }}>
                下发
              </button>
            </form>
          </div>

          {/* 漫游触发 */}
          <div>
            <div style={labelStyle}>漫游切换</div>
            <form onSubmit={handleHandoverSubmit} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
              <label style={formLabelStyle}>
                终端ID
                <input
                  type="number"
                  style={formInputStyle}
                  value={handoverForm.terminalId}
                  onChange={(e) => setHandoverForm({ ...handoverForm, terminalId: parseInt(e.target.value) || 0 })}
                />
              </label>
              <label style={formLabelStyle}>
                目标基站sysid
                <input
                  type="number"
                  style={formInputStyle}
                  value={handoverForm.toSysid}
                  onChange={(e) => setHandoverForm({ ...handoverForm, toSysid: parseInt(e.target.value) || 0 })}
                />
              </label>
              <label style={formLabelStyle}>
                切换原因
                <select
                  style={formInputStyle}
                  value={handoverForm.reason}
                  onChange={(e) => setHandoverForm({ ...handoverForm, reason: parseInt(e.target.value) })}
                >
                  <option value={0}>信号弱</option>
                  <option value={1}>负载均衡</option>
                  <option value={2}>基站关闭</option>
                </select>
              </label>
              <button type="submit" className="btn primary" style={{ padding: '4px 12px', fontSize: 11 }}>
                触发漫游
              </button>
            </form>
          </div>
        </div>
      )}

      {/* 漫游切换历史 */}
      {handovers && handovers.handovers && handovers.handovers.length > 0 && (
        <div style={{
          background: 'var(--bg-2)', borderRadius: 6, padding: 12,
          border: '1px solid var(--line-2)',
        }}>
          <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
            漫游切换历史（最近 {handovers.count} 条）
          </h3>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>终端ID</th>
                <th style={thStyle}>源基站</th>
                <th style={thStyle}>目标基站</th>
                <th style={thStyle}>原因</th>
                <th style={thStyle}>时间</th>
              </tr>
            </thead>
            <tbody>
              {handovers.handovers.slice(-20).reverse().map((h, i) => (
                <tr key={i}>
                  <td style={tdStyle}>{h.terminalId}</td>
                  <td style={tdStyle}>{h.fromSysid}</td>
                  <td style={tdStyle}>{h.toSysid}</td>
                  <td style={tdStyle}>{HANDOVER_REASON_LABELS[h.reason] || h.reason}</td>
                  <td style={tdStyle}>{new Date(h.timestamp).toLocaleTimeString('zh-CN', { hour12: false })}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ===== 内联样式 =====
const cardStyle = {
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 4,
  padding: '6px 10px',
  minWidth: 80,
}

const labelStyle = {
  fontSize: 10,
  color: 'var(--dim-2)',
  marginBottom: 2,
}

const valueStyle = {
  fontSize: 16,
  fontWeight: 'bold',
  color: 'var(--text)',
  fontFamily: 'var(--mono)',
}

const tableStyle = {
  width: '100%',
  borderCollapse: 'collapse',
  fontSize: 10,
  fontFamily: 'var(--mono)',
}

const thStyle = {
  textAlign: 'left',
  padding: '3px 6px',
  borderBottom: '1px solid var(--line-2)',
  color: 'var(--dim-2)',
  fontWeight: 'normal',
}

const tdStyle = {
  padding: '3px 6px',
  borderBottom: '1px solid var(--line-2)',
  color: 'var(--text)',
}

const formLabelStyle = {
  display: 'flex',
  flexDirection: 'column',
  fontSize: 10,
  color: 'var(--dim-2)',
  gap: 2,
}

const formInputStyle = {
  padding: '3px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  background: 'var(--bg-1)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  color: 'var(--text)',
  minWidth: 80,
}