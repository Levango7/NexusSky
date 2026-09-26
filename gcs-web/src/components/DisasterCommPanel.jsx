import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { disaster } from '../api.js'

// 灾害应急通信综合态势面板（P2-4）
// 灾害模式状态 + 分簇拓扑 + QoS 优先级队列 + 异构链路桥接 + 灾区通信隔离
// 风格与 EmergencyOrchPanel / MeshTopologyPanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 2s

const POLL_MS = 2000

// QoS 优先级定义（与 spec FR-02 一致）
const QOS_PRIORITIES = [
  { key: 'EMERGENCY', label: '搜救通讯', color: 'var(--crit)', weight: 0 },
  { key: 'COMMAND', label: '指挥通讯', color: 'var(--warn)', weight: 1 },
  { key: 'MAPPING', label: '测绘数据', color: 'var(--gold)', weight: 2 },
  { key: 'ROUTINE', label: '常规遥测', color: 'var(--cyan)', weight: 3 },
]

// 异构链路类型定义
const LINK_TYPES = {
  WiFi: { label: 'WiFi', color: '#3498DB', icon: '📶' },
  LTE: { label: 'LTE', color: '#2ECC71', icon: '📡' },
  LoRa: { label: 'LoRa', color: '#F39C12', icon: '〰' },
  Sat: { label: '卫星', color: '#9B59B6', icon: '🛰' },
}

// 链路质量颜色
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

// 圆环仪表盘（恢复率 / 连通率）
function Gauge({ value, label, color, size = 86 }) {
  const pct = Math.max(0, Math.min(100, Number(value) || 0))
  const r = size / 2 - 8
  const c = 2 * Math.PI * r
  const offset = c * (1 - pct / 100)
  const cx = size / 2
  return (
    <div style={{ textAlign: 'center', minWidth: size + 10 }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle cx={cx} cy={cx} r={r} fill="none" stroke="var(--line-2)" strokeWidth="5" />
        <circle
          cx={cx} cy={cx} r={r} fill="none" stroke={color} strokeWidth="5"
          strokeDasharray={c} strokeDashoffset={offset}
          transform={`rotate(-90 ${cx} ${cx})`} strokeLinecap="round"
          style={{ transition: 'stroke-dashoffset 0.4s ease' }}
        />
        <text
          x={cx} y={cx + 5} textAnchor="middle" fontSize="14" fontWeight="bold"
          fill="var(--text)" fontFamily="var(--mono)"
        >
          {pct.toFixed(0)}%
        </text>
      </svg>
      <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 2 }}>{label}</div>
    </div>
  )
}

// 簇拓扑可视化（圆形布局 + 簇头高亮）
function ClusterView({ cluster }) {
  const members = cluster.members || []
  const headId = cluster.clusterHead
  const n = members.length
  const svgW = 200
  const svgH = 160
  const cx = svgW / 2
  const cy = svgH / 2
  const radius = Math.min(svgW, svgH) / 2 - 25

  // 圆形布局
  const positions = {}
  if (n === 1) {
    positions[members[0]] = { x: cx, y: cy }
  } else if (n > 1) {
    for (let i = 0; i < n; i++) {
      const angle = (2 * Math.PI * i) / n - Math.PI / 2
      positions[members[i]] = {
        x: cx + radius * Math.cos(angle),
        y: cy + radius * Math.sin(angle),
      }
    }
  }

  // 簇半径圈
  const clusterRadius = cluster.clusterRadius || 0
  const radiusCircleR = Math.min(radius, clusterRadius * 10) // 缩放显示

  return (
    <div style={{
      background: 'var(--bg-2)', borderRadius: 6, padding: 8,
      border: '1px solid var(--line-2)', minWidth: 220,
    }}>
      <div style={{ fontSize: 11, fontWeight: 'bold', color: 'var(--text)', marginBottom: 4 }}>
        簇 #{cluster.clusterId ?? '--'}
      </div>
      <div style={{ fontSize: 10, color: 'var(--dim-2)', marginBottom: 4 }}>
        簇头: <span style={{ color: 'var(--cyan)', fontFamily: 'var(--mono)' }}>{headId ?? '--'}</span>
        {' · '}半径: <span style={{ fontFamily: 'var(--mono)' }}>{clusterRadius.toFixed(0) ?? '--'}m</span>
        {' · '}成员: <span style={{ fontFamily: 'var(--mono)' }}>{n}</span>
      </div>
      <svg width="100%" viewBox={`0 0 ${svgW} ${svgH}`} style={{ maxHeight: 160 }}>
        {/* 簇半径圈 */}
        {radiusCircleR > 0 && (
          <circle
            cx={cx} cy={cy} r={radiusCircleR}
            fill="none" stroke="var(--line-2)" strokeWidth="1"
            strokeDasharray="4 3" opacity={0.5}
          />
        )}
        {/* 簇头到成员的连线 */}
        {headId != null && members.map((m) => {
          if (m === headId) return null
          const from = positions[headId]
          const to = positions[m]
          if (!from || !to) return null
          return (
            <line
              key={`edge-${m}`}
              x1={from.x} y1={from.y}
              x2={to.x} y2={to.y}
              stroke="var(--line-2)" strokeWidth="1" opacity={0.5}
            />
          )
        })}
        {/* 成员节点 */}
        {members.map((m) => {
          const pos = positions[m]
          if (!pos) return null
          const isHead = m === headId
          return (
            <g key={`node-${m}`}>
              <circle
                cx={pos.x} cy={pos.y}
                r={isHead ? 10 : 7}
                fill={isHead ? 'var(--cyan)' : 'var(--bg-3)'}
                stroke={isHead ? 'var(--text)' : 'var(--line-2)'}
                strokeWidth={isHead ? 2 : 1}
              />
              <text
                x={pos.x} y={pos.y + 3}
                textAnchor="middle" fontSize={8}
                fill={isHead ? 'var(--bg-1)' : 'var(--dim)'}
                fontFamily="var(--mono)"
              >
                {m}
              </text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

// QoS 队列条形图
function QoSQueueBar({ priority, depth, throughput, maxDepth }) {
  const pct = maxDepth > 0 ? Math.min(100, (depth / maxDepth) * 100) : 0
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 2 }}>
        <span style={{ fontSize: 10, color: priority.color, fontWeight: 'bold' }}>
          {priority.label}
        </span>
        <span style={{ fontSize: 10, color: 'var(--dim-2)', fontFamily: 'var(--mono)' }}>
          深度 {depth} · {throughput?.toFixed(1) ?? '--'} kbps
        </span>
      </div>
      <div style={{
        height: 8, background: 'var(--bg-3)', borderRadius: 2,
        border: '1px solid var(--line-2)', overflow: 'hidden',
      }}>
        <div style={{
          width: `${pct}%`, height: '100%',
          background: priority.color,
          transition: 'width 0.3s ease',
          borderRadius: 2,
        }} />
      </div>
    </div>
  )
}

export default function DisasterCommPanel() {
  // ---- 状态 ----
  const [status, setStatus] = useState(null)
  const [clusters, setClusters] = useState(null)
  const [qos, setQoS] = useState(null)
  const [links, setLinks] = useState(null)
  const [activating, setActivating] = useState(false)
  const [deactivating, setDeactivating] = useState(false)
  const [activateReason, setActivateReason] = useState('manual')
  const [error, setError] = useState(null)
  const [pollError, setPollError] = useState(null)
  const timerRef = useRef(null)

  // ---- 轮询加载所有灾害通信数据 ----
  const refreshAll = useCallback(async () => {
    try {
      const [st, cl, qs, lk] = await Promise.all([
        disaster.getStatus().catch(() => null),
        disaster.getClusters().catch(() => null),
        disaster.getQoS().catch(() => null),
        disaster.getLinks().catch(() => null),
      ])
      setStatus(st)
      setClusters(cl)
      setQoS(qs)
      setLinks(lk)
      setPollError(null)
    } catch (e) {
      setPollError(e.message)
    }
  }, [])

  useEffect(() => {
    refreshAll()
    timerRef.current = setInterval(refreshAll, POLL_MS)
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [refreshAll])

  // ---- 激活灾害模式 ----
  const handleActivate = useCallback(async () => {
    setError(null)
    setActivating(true)
    try {
      await disaster.activate(activateReason)
      await refreshAll()
    } catch (e) {
      setError('激活灾害模式失败：' + e.message)
    } finally {
      setActivating(false)
    }
  }, [activateReason, refreshAll])

  // ---- 退出灾害模式 ----
  const handleDeactivate = useCallback(async () => {
    setError(null)
    setDeactivating(true)
    try {
      await disaster.deactivate()
      await refreshAll()
    } catch (e) {
      setError('退出灾害模式失败：' + e.message)
    } finally {
      setDeactivating(false)
    }
  }, [refreshAll])

  // ===== 派生数据 =====
  const isActive = status?.mode === 'active' || status?.active === true
  const triggerReason = status?.triggerReason || status?.reason || '--'
  const recoveryRate = status?.recoveryRate ?? status?.recovery ?? 0
  const affectedNodes = status?.affectedNodes ?? status?.affectedCount ?? 0

  // 灾区列表（通信隔离视图）
  const disasterZones = useMemo(() => {
    if (!clusters) return []
    // 兼容 { clusters: [...] } 或 [...] 或 { zones: [...] }
    const clusterList = Array.isArray(clusters) ? clusters :
      (clusters?.clusters || clusters?.zones || [])
    // 按 disasterZoneId 分组（如有），否则全部归为一个灾区
    const zoneMap = {}
    for (const c of clusterList) {
      const zid = c.disasterZoneId || c.zoneId || 'zone-default'
      if (!zoneMap[zid]) zoneMap[zid] = []
      zoneMap[zid].push(c)
    }
    return Object.entries(zoneMap).map(([zid, cls]) => ({
      zoneId: zid,
      clusters: cls,
      nodeCount: cls.reduce((sum, c) => sum + (c.members?.length || 0), 0),
    }))
  }, [clusters])

  // 簇列表
  const clusterList = useMemo(() => {
    if (!clusters) return []
    return Array.isArray(clusters) ? clusters :
      (clusters?.clusters || clusters?.zones || [])
  }, [clusters])

  // QoS 队列数据
  const qosQueues = useMemo(() => {
    if (!qos) return []
    // 兼容 { queues: [...] } 或 [...] 或 { priorityQueues: [...] }
    const list = Array.isArray(qos) ? qos :
      (qos?.queues || qos?.priorityQueues || [])
    return list
  }, [qos])

  // 计算 QoS 最大深度（用于条形图比例）
  const maxQoSDepth = useMemo(() => {
    if (qosQueues.length === 0) return 1
    return Math.max(1, ...qosQueues.map((q) => q.depth || q.queueDepth || 0))
  }, [qosQueues])

  // 异构链路列表
  const linkList = useMemo(() => {
    if (!links) return []
    // 兼容 { links: [...] } 或 [...] 或 { bridges: [...] }
    return Array.isArray(links) ? links :
      (links?.links || links?.bridges || [])
  }, [links])

  // 节点连通率（从链路数据估算）
  const connectivityRate = useMemo(() => {
    if (linkList.length === 0) return 0
    const goodLinks = linkList.filter((l) => {
      const q = l.quality || l.linkQuality
      return q === 'EXCELLENT' || q === 'GOOD'
    })
    return Math.round((goodLinks.length / linkList.length) * 100)
  }, [linkList])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        灾害应急通信综合态势
      </h2>

      {error && (
        <div style={{
          color: 'var(--crit)', fontSize: 11, marginBottom: 8,
          padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4,
          border: '1px solid var(--crit)',
        }}>
          ⚠ {error}
        </div>
      )}
      {pollError && (
        <div style={{ color: 'var(--warn)', fontSize: 11, marginBottom: 8 }}>
          轮询异常：{pollError}
        </div>
      )}

      {/* ===== 第一行：灾害模式状态 + 激活/退出按钮 ===== */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap', alignItems: 'stretch' }}>
        {/* 灾害模式状态指示器 */}
        <div style={{
          ...cardStyle, flex: '1 1 240px', padding: 12,
          borderLeft: `3px solid ${isActive ? 'var(--crit)' : 'var(--ok)'}`,
        }}>
          <div style={labelStyle}>灾害模式状态</div>
          <div style={{
            fontSize: 18, fontWeight: 'bold',
            color: isActive ? 'var(--crit)' : 'var(--ok)',
            fontFamily: 'var(--mono)',
          }}>
            {isActive ? '● 已激活' : '○ 未激活'}
          </div>
          {isActive && (
            <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 4 }}>
              触发原因: <span style={{ color: 'var(--warn)' }}>{triggerReason}</span>
            </div>
          )}
          <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 2 }}>
            受影响节点: <span style={{ fontFamily: 'var(--mono)' }}>{affectedNodes}</span>
          </div>
        </div>

        {/* 激活/退出按钮 */}
        <div style={{
          ...cardStyle, flex: '0 0 200px', padding: 12,
          display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 8,
        }}>
          {!isActive ? (
            <>
              <div style={labelStyle}>激活灾害模式</div>
              <select
                value={activateReason}
                onChange={(e) => setActivateReason(e.target.value)}
                style={{
                  padding: '4px 8px', fontSize: 11,
                  background: 'var(--bg-3)', color: 'var(--text)',
                  border: '1px solid var(--line-2)', borderRadius: 4,
                }}
              >
                <option value="manual">手动激活</option>
                <option value="heartbeat_timeout">心跳大面积超时</option>
                <option value="terrain_change">地形变更事件</option>
                <option value="earthquake">地震检测</option>
                <option value="mudslide">泥石流检测</option>
              </select>
              <button
                onClick={handleActivate}
                disabled={activating}
                style={{
                  padding: '6px 14px', fontSize: 11, cursor: activating ? 'not-allowed' : 'pointer',
                  border: '1px solid var(--crit)',
                  background: activating ? 'var(--bg-2)' : 'transparent',
                  color: activating ? 'var(--dim)' : 'var(--crit)',
                  borderRadius: 4, fontWeight: 'bold',
                  opacity: activating ? 0.6 : 1,
                }}
              >
                {activating ? '激活中…' : '▶ 一键激活'}
              </button>
            </>
          ) : (
            <>
              <div style={labelStyle}>退出灾害模式</div>
              <div style={{ fontSize: 10, color: 'var(--dim-2)', marginBottom: 4 }}>
                恢复率需 &gt;80% 且 30 分钟无新灾害事件
              </div>
              <button
                onClick={handleDeactivate}
                disabled={deactivating}
                style={{
                  padding: '6px 14px', fontSize: 11, cursor: deactivating ? 'not-allowed' : 'pointer',
                  border: '1px solid var(--ok)',
                  background: deactivating ? 'var(--bg-2)' : 'transparent',
                  color: deactivating ? 'var(--dim)' : 'var(--ok)',
                  borderRadius: 4, fontWeight: 'bold',
                  opacity: deactivating ? 0.6 : 1,
                }}
              >
                {deactivating ? '退出中…' : '■ 退出灾害模式'}
              </button>
            </>
          )}
        </div>

        {/* 恢复率仪表盘 */}
        <div style={{
          ...cardStyle, flex: '0 0 auto', padding: 8,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <Gauge
            value={recoveryRate}
            label="灾区恢复率"
            color={recoveryRate >= 80 ? 'var(--ok)' : recoveryRate >= 50 ? 'var(--warn)' : 'var(--crit)'}
          />
        </div>
      </div>

      {/* ===== 第二行：分簇拓扑 + QoS 优先级队列 ===== */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        {/* 分簇拓扑可视化 */}
        <div style={{
          ...cardStyle, flex: '1 1 480px', padding: 12,
        }}>
          <div style={labelStyle}>分簇拓扑可视化</div>
          {clusterList.length > 0 ? (
            <div style={{
              display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8,
            }}>
              {clusterList.map((c) => (
                <ClusterView key={c.clusterId ?? Math.random()} cluster={c} />
              ))}
            </div>
          ) : (
            <div style={{
              textAlign: 'center', color: 'var(--dim-2)', fontSize: 11,
              padding: 24,
            }}>
              暂无分簇数据（节点数 ≤20 时无需分簇，或灾害模式未激活）
            </div>
          )}
        </div>

        {/* QoS 优先级队列状态 */}
        <div style={{
          ...cardStyle, flex: '1 1 300px', padding: 12,
        }}>
          <div style={labelStyle}>QoS 优先级队列</div>
          {qosQueues.length > 0 ? (
            <div style={{ marginTop: 8 }}>
              {QOS_PRIORITIES.map((p) => {
                // 匹配后端返回的队列数据
                const qData = qosQueues.find(
                  (q) => (q.priority || q.priorityClass || q.class) === p.key
                ) || {}
                return (
                  <QoSQueueBar
                    key={p.key}
                    priority={p}
                    depth={qData.depth || qData.queueDepth || 0}
                    throughput={qData.throughputKbps || qData.throughput || 0}
                    maxDepth={maxQoSDepth}
                  />
                )
              })}
            </div>
          ) : (
            <div style={{
              textAlign: 'center', color: 'var(--dim-2)', fontSize: 11,
              padding: 24,
            }}>
              暂无 QoS 数据（灾害模式激活后启用优先级队列）
            </div>
          )}
        </div>
      </div>

      {/* ===== 第三行：异构链路桥接 + 灾区通信隔离 ===== */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        {/* 异构链路桥接状态 */}
        <div style={{
          ...cardStyle, flex: '1 1 400px', padding: 12,
        }}>
          <div style={labelStyle}>异构链路桥接状态</div>
          {linkList.length > 0 ? (
            <table style={tableStyle}>
              <thead>
                <tr>
                  <th style={thStyle}>链路类型</th>
                  <th style={thStyle}>桥接节点</th>
                  <th style={thStyle}>质量</th>
                  <th style={thStyle}>带宽</th>
                  <th style={thStyle}>延迟</th>
                </tr>
              </thead>
              <tbody>
                {linkList.map((link, i) => {
                  const type = link.type || link.linkType || 'WiFi'
                  const meta = LINK_TYPES[type] || LINK_TYPES.WiFi
                  const quality = link.quality || link.linkQuality || 'FAIR'
                  return (
                    <tr key={`link-${i}`}>
                      <td style={tdStyle}>
                        <span style={{ color: meta.color }}>{meta.icon}</span>{' '}
                        <span style={{ fontSize: 10 }}>{meta.label}</span>
                      </td>
                      <td style={tdStyle}>{link.bridgeNode || link.node || '--'}</td>
                      <td style={{ ...tdStyle, color: QUALITY_COLORS[quality] || 'var(--dim)' }}>
                        {QUALITY_LABELS[quality] || quality}
                      </td>
                      <td style={tdStyle}>{link.bandwidthKbps?.toFixed(0) || link.bandwidth || '--'} kbps</td>
                      <td style={tdStyle}>{link.latencyMs?.toFixed(0) || link.latency || '--'} ms</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          ) : (
            <div style={{
              textAlign: 'center', color: 'var(--dim-2)', fontSize: 11,
              padding: 24,
            }}>
              暂无异构链路数据
            </div>
          )}
          {/* 链路类型图例 */}
          <div style={{
            display: 'flex', gap: 12, justifyContent: 'center', marginTop: 8, fontSize: 10,
          }}>
            {Object.entries(LINK_TYPES).map(([key, meta]) => (
              <span key={key} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <span style={{ color: meta.color }}>{meta.icon}</span>
                <span style={{ color: 'var(--dim-2)' }}>{meta.label}</span>
              </span>
            ))}
          </div>
        </div>

        {/* 灾区通信隔离视图 */}
        <div style={{
          ...cardStyle, flex: '1 1 300px', padding: 12,
        }}>
          <div style={labelStyle}>灾区通信隔离</div>
          {disasterZones.length > 0 ? (
            <div style={{ marginTop: 8 }}>
              {disasterZones.map((zone) => (
                <div
                  key={zone.zoneId}
                  style={{
                    marginBottom: 8, padding: '6px 10px',
                    background: 'var(--bg-3)', borderRadius: 4,
                    border: '1px solid var(--line-2)',
                  }}
                >
                  <div style={{ fontSize: 11, fontWeight: 'bold', color: 'var(--text)' }}>
                    灾区 {zone.zoneId}
                  </div>
                  <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 2 }}>
                    节点数: <span style={{ fontFamily: 'var(--mono)' }}>{zone.nodeCount}</span>
                    {' · '}簇数: <span style={{ fontFamily: 'var(--mono)' }}>{zone.clusters.length}</span>
                  </div>
                  <div style={{ fontSize: 10, color: 'var(--cyan)', marginTop: 2 }}>
                    区内通信自主 · 跨区经卫星/HAPS中继
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div style={{
              textAlign: 'center', color: 'var(--dim-2)', fontSize: 11,
              padding: 24,
            }}>
              暂无灾区隔离数据（灾害模式激活后自动划分灾区）
            </div>
          )}
        </div>
      </div>

      {/* ===== 第四行：恢复率仪表盘 + 连通率 ===== */}
      <div style={{
        ...cardStyle, padding: 12, display: 'flex', gap: 16,
        justifyContent: 'space-around', alignItems: 'center', flexWrap: 'wrap',
      }}>
        <Gauge
          value={recoveryRate}
          label="灾区恢复率"
          color={recoveryRate >= 80 ? 'var(--ok)' : recoveryRate >= 50 ? 'var(--warn)' : 'var(--crit)'}
          size={70}
        />
        <Gauge
          value={connectivityRate}
          label="节点连通率"
          color={connectivityRate >= 80 ? 'var(--ok)' : connectivityRate >= 50 ? 'var(--warn)' : 'var(--crit)'}
          size={70}
        />
        {/* 链路质量分布统计 */}
        <div style={{ minWidth: 120 }}>
          <div style={labelStyle}>链路质量分布</div>
          <div style={{ marginTop: 4 }}>
            {Object.entries(QUALITY_LABELS).map(([key, label]) => {
              const count = linkList.filter((l) => {
                const q = l.quality || l.linkQuality
                return q === key
              }).length
              const total = linkList.length || 1
              const pct = Math.round((count / total) * 100)
              return (
                <div key={key} style={{
                  display: 'flex', justifyContent: 'space-between',
                  fontSize: 10, marginBottom: 2,
                }}>
                  <span style={{ color: QUALITY_COLORS[key] }}>{label}</span>
                  <span style={{ color: 'var(--dim-2)', fontFamily: 'var(--mono)' }}>
                    {count} ({pct}%)
                  </span>
                </div>
              )
            })}
          </div>
        </div>
        {/* 灾害模式参数摘要 */}
        {isActive && (
          <div style={{ minWidth: 140 }}>
            <div style={labelStyle}>灾害模式参数</div>
            <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 4 }}>
              <div>HELLO 间隔: <span style={{ color: 'var(--cyan)', fontFamily: 'var(--mono)' }}>缩短</span></div>
              <div>邻居超时: <span style={{ color: 'var(--cyan)', fontFamily: 'var(--mono)' }}>放宽</span></div>
              <div>分簇路由: <span style={{ color: 'var(--cyan)' }}>已启用</span></div>
              <div>QoS 队列: <span style={{ color: 'var(--cyan)' }}>已启用</span></div>
              <div>层级切换: <span style={{ color: 'var(--cyan)', fontFamily: 'var(--mono)' }}>2s</span></div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

// ===== 内联样式 =====
const cardStyle = {
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 6,
  padding: '6px 10px',
}

const labelStyle = {
  fontSize: 10,
  color: 'var(--dim-2)',
  marginBottom: 4,
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