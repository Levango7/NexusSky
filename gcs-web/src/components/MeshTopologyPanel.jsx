import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

// Mesh 拓扑可视化面板（M5 应急 mesh 自愈组网）
// 展示节点、链路、链路质量分级、路由路径
// 通过 WebSocket 实时刷新（mesh-topology 事件）
// 风格与 HardwarePanel 一致：卡片布局 + 内联 CSS + CSS 变量

const POLL_MS = 2000

// 链路质量颜色（与 LinkQuality 枚举一致）
const QUALITY_COLORS = {
  EXCELLENT: '#2ECC71',
  GOOD: '#3498DB',
  FAIR: '#F39C12',
  POOR: '#E74C3C',
}

const QUALITY_LABELS = {
  EXCELLENT: '优秀',
  GOOD: '良好',
  FAIR: '一般',
  POOR: '差',
}

// 圆形布局：将节点均匀分布在圆周上
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

export default function MeshTopologyPanel({ meshTopology, onSelectNode, onSelectRoute }) {
  const [topology, setTopology] = useState(meshTopology || null)
  const [links, setLinks] = useState(null)
  const [selectedNode, setSelectedNode] = useState(null)
  const [nodeNeighbors, setNodeNeighbors] = useState(null)
  const [nodeRoutes, setNodeRoutes] = useState(null)
  const [pollErr, setPollErr] = useState(null)
  const [now, setNow] = useState(Date.now())
  const timerRef = useRef(null)

  // 定时更新 now 用于 isOffline 判断（避免渲染中调用 Date.now()）
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])

  // 初始加载 + 周期刷新拓扑
  const refreshTopology = useCallback(async () => {
    try {
      const [topo, lnks] = await Promise.all([
        api.getMeshTopology().catch(() => null),
        api.getMeshLinks().catch(() => null),
      ])
      setTopology(topo)
      setLinks(lnks)
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

  // 选中节点时加载邻居表与路由表
  useEffect(() => {
    if (selectedNode == null) {
      setNodeNeighbors(null)
      setNodeRoutes(null)
      return
    }
    let cancelled = false
    const load = async () => {
      try {
        const [neighbors, routes] = await Promise.all([
          api.getMeshNeighbors(selectedNode).catch(() => null),
          api.getMeshRoutes(selectedNode).catch(() => null),
        ])
        if (cancelled) return
        setNodeNeighbors(neighbors)
        setNodeRoutes(routes)
      } catch (e) {
        if (cancelled) return
        setNodeNeighbors(null)
        setNodeRoutes(null)
      }
    }
    load()
    return () => { cancelled = true }
  }, [selectedNode])

  // WebSocket 事件合并（由 App.jsx 注入 meshTopology）
  useEffect(() => {
    if (meshTopology && meshTopology.type === 'mesh-topology') {
      refreshTopology()
    }
  }, [meshTopology, refreshTopology])

  const handleNodeClick = (sysid) => {
    setSelectedNode(sysid)
    if (onSelectNode) onSelectNode(sysid)
  }

  // 渲染拓扑图
  const nodes = topology?.nodes || []
  const linkList = links?.links || []
  const hasData = nodes.length > 0

  // SVG 尺寸
  const svgW = 600
  const svgH = 400
  const cx = svgW / 2
  const cy = svgH / 2
  const radius = Math.min(svgW, svgH) / 2 - 40

  // 圆形布局（节点数 ≤ 50）
  const layout = hasData && nodes.length <= 50
    ? circularLayout(nodes, cx, cy, radius)
    : {}

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        应急 Mesh 自愈组网
      </h2>

      {pollErr && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8 }}>
          拓扑获取失败：{pollErr}
        </div>
      )}

      {/* 拓扑概览 */}
      <div style={{
        display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap',
      }}>
        <div style={cardStyle}>
          <div style={labelStyle}>节点数</div>
          <div style={valueStyle}>{topology?.nodeCount ?? '--'}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>链路数</div>
          <div style={valueStyle}>{links?.linkCount ?? '--'}</div>
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
            {/* 渲染链路（边） */}
            {linkList.map((link, i) => {
              const fromPos = layout[link.from]
              const toPos = layout[link.to]
              if (!fromPos || !toPos) return null
              const color = QUALITY_COLORS[link.quality] || 'var(--dim)'
              return (
                <line
                  key={`link-${i}`}
                  x1={fromPos.x} y1={fromPos.y}
                  x2={toPos.x} y2={toPos.y}
                  stroke={color}
                  strokeWidth={2}
                  opacity={0.7}
                />
              )
            })}
            {/* 渲染节点 */}
            {nodes.map((node) => {
              const pos = layout[node.sysid]
              if (!pos) return null
              const isSelected = selectedNode === node.sysid
              const isOffline = node.lastUpdateMs
                && now - node.lastUpdateMs > 6000
              return (
                <g
                  key={`node-${node.sysid}`}
                  onClick={() => handleNodeClick(node.sysid)}
                  style={{ cursor: 'pointer' }}
                >
                  <circle
                    cx={pos.x} cy={pos.y}
                    r={isSelected ? 16 : 12}
                    fill={isOffline ? 'var(--dim)' : 'var(--cyan)'}
                    stroke={isSelected ? 'var(--text)' : 'none'}
                    strokeWidth={2}
                    opacity={isOffline ? 0.4 : 0.9}
                  />
                  <text
                    x={pos.x} y={pos.y + 4}
                    textAnchor="middle"
                    fontSize={10}
                    fill="var(--bg-1)"
                    fontFamily="var(--mono)"
                  >
                    {node.sysid}
                  </text>
                  <text
                    x={pos.x} y={pos.y + 26}
                    textAnchor="middle"
                    fontSize={9}
                    fill="var(--dim-2)"
                  >
                    {node.neighborCount}邻
                  </text>
                </g>
              )
            })}
          </svg>
          {/* 链路质量图例 */}
          <div style={{
            display: 'flex', gap: 12, justifyContent: 'center', marginTop: 4, fontSize: 10,
          }}>
            {Object.entries(QUALITY_LABELS).map(([key, label]) => (
              <span key={key} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <span style={{
                  display: 'inline-block', width: 10, height: 2,
                  background: QUALITY_COLORS[key],
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
          暂无 mesh 拓扑数据（请确认 drone-sim 已启用 --mesh）
        </div>
      )}

      {/* 选中节点详情 */}
      {selectedNode != null && (
        <div style={{
          background: 'var(--bg-2)', borderRadius: 6, padding: 12,
          border: '1px solid var(--line-2)',
        }}>
          <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
            节点 #{selectedNode} 详情
          </h3>

          {/* 邻居表 */}
          <div style={{ marginBottom: 8 }}>
            <div style={labelStyle}>邻居表</div>
            {nodeNeighbors && nodeNeighbors.neighbors ? (
              <table style={tableStyle}>
                <thead>
                  <tr>
                    <th style={thStyle}>sysid</th>
                    <th style={thStyle}>RSSI(dBm)</th>
                    <th style={thStyle}>质量</th>
                  </tr>
                </thead>
                <tbody>
                  {nodeNeighbors.neighbors.map((n) => (
                    <tr key={n.sysid}>
                      <td style={tdStyle}>{n.sysid}</td>
                      <td style={tdStyle}>{n.rssiDbm}</td>
                      <td style={{ ...tdStyle, color: QUALITY_COLORS[n.quality] || 'var(--dim)' }}>
                        {QUALITY_LABELS[n.quality] || n.quality}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <div style={{ color: 'var(--dim-2)', fontSize: 10 }}>无邻居数据</div>
            )}
          </div>

          {/* 路由表 */}
          <div>
            <div style={labelStyle}>路由表</div>
            {nodeRoutes && nodeRoutes.routes && nodeRoutes.routes.length > 0 ? (
              <table style={tableStyle}>
                <thead>
                  <tr>
                    <th style={thStyle}>目标</th>
                    <th style={thStyle}>下一跳</th>
                    <th style={thStyle}>跳数</th>
                    <th style={thStyle}>度量</th>
                  </tr>
                </thead>
                <tbody>
                  {nodeRoutes.routes.map((r, i) => (
                    <tr key={i}>
                      <td style={tdStyle}>{r.targetSysId}</td>
                      <td style={tdStyle}>{r.nextHop}</td>
                      <td style={tdStyle}>{r.hopCount}</td>
                      <td style={tdStyle}>{r.metric?.toFixed(2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <div style={{ color: 'var(--dim-2)', fontSize: 10 }}>无路由数据</div>
            )}
          </div>
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