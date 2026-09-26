import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

// 地形分区图面板（M8 复杂地形适配，FR-31）
// 展示地形分区色块、飞行限制区多边形、变更历史
// 通过 WebSocket 实时刷新（terrain-update 事件）
// 风格与 SatLinkPanel 一致：卡片布局 + 内联 CSS + CSS 变量

const POLL_MS = 2000

// 地形类型标签（与 drone-sim TerrainType 枚举一致）
const TERRAIN_LABELS = {
  0: '开阔平地',
  1: '森林',
  2: '城市',
  3: '丘陵',
  4: '山地',
  5: '水域',
  6: '沼泽',
  7: '沙漠',
  8: '工业区',
}

// 地形类型颜色（与 MapView 色板协调）
const TERRAIN_COLORS = {
  0: '#3a4a5a', // 开阔平地 - 深灰蓝
  1: '#2d5a2d', // 森林 - 深绿
  2: '#6b4a3a', // 城市 - 棕褐
  3: '#4a5a3a', // 丘陵 - 橄榄
  4: '#7a6a5a', // 山地 - 岩棕
  5: '#1a4a6a', // 水域 - 深蓝
  6: '#3a4a3a', // 沼泽 - 暗绿
  7: '#8a7a4a', // 沙漠 - 沙黄
  8: '#5a3a3a', // 工业区 - 暗红棕
}

// 限制类型标签
const RESTRICTION_LABELS = {
  0: '禁飞区',
  1: '限高区',
  2: '限速区',
  3: '临时管制',
}

// 变更原因标签
const CHANGE_REASON_LABELS = {
  0: '初始建图',
  1: '森林砍伐',
  2: '建筑施工',
  3: '自然灾害',
  4: '季节变化',
  5: '手动更新',
}

export default function TerrainMapPanel({ terrainData }) {
  const [mapData, setMapData] = useState(null)
  const [restrictions, setRestrictions] = useState(null)
  const [changes, setChanges] = useState(null)
  const [pollErr, setPollErr] = useState(null)
  const [buildMsg, setBuildMsg] = useState(null)
  const [buildForm, setBuildForm] = useState({
    originLat: 30.0,
    originLon: 120.0,
    widthM: 1000,
    heightM: 1000,
    gridResolution: 50,
  })
  const timerRef = useRef(null)
  // 建图消息自动消失的 timer，组件卸载时需清理，避免内存泄漏
  // 经验来源：2026-09-16-react-side-effect-cleanup-timeout-ref-callback-leak
  const buildTimerRef = useRef(null)

  // 初始加载 + 周期刷新
  const refresh = useCallback(async () => {
    try {
      const [mp, rs, ch] = await Promise.all([
        api.getTerrainMap().catch(() => null),
        api.getTerrainRestrictions().catch(() => null),
        api.getTerrainChanges().catch(() => null),
      ])
      setMapData(mp)
      setRestrictions(rs)
      setChanges(ch)
      setPollErr(null)
    } catch (e) {
      setPollErr(e.message)
    }
  }, [])

  useEffect(() => {
    refresh()
    timerRef.current = setInterval(refresh, POLL_MS)
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [refresh])

  // WebSocket 事件合并（terrain-update / terrain-restriction）
  useEffect(() => {
    if (terrainData && (terrainData.type === 'terrain-update' || terrainData.type === 'terrain-restriction')) {
      refresh()
    }
  }, [terrainData, refresh])

  // 组件卸载时清理建图消息 timer，防止 setState 作用于已卸载组件
  useEffect(() => {
    return () => {
      if (buildTimerRef.current) clearTimeout(buildTimerRef.current)
    }
  }, [])

  // 触发建图
  const handleBuild = async () => {
    try {
      const res = await api.buildTerrainMap(buildForm)
      setBuildMsg(`建图已触发：${res.message || 'accepted'}`)
      if (buildTimerRef.current) clearTimeout(buildTimerRef.current)
      buildTimerRef.current = setTimeout(() => setBuildMsg(null), 4000)
      refresh()
    } catch (e) {
      setBuildMsg(`建图失败：${e.message}`)
      if (buildTimerRef.current) clearTimeout(buildTimerRef.current)
      buildTimerRef.current = setTimeout(() => setBuildMsg(null), 4000)
    }
  }

  const mapAvailable = mapData?.available === true
  const mapWidth = mapData?.mapWidth || 0
  const mapHeight = mapData?.mapHeight || 0
  const gridCells = mapData?.gridCells || []
  const restrictionList = restrictions?.restrictions || []
  const changeList = changes?.changes || []

  // 渲染地形分区色块（canvas 网格）
  // 简单窗口化：限制最大渲染单元格数量，避免大网格全量渲染导致性能问题
  const MAX_RENDER_CELLS = 4000
  const renderTerrainGrid = () => {
    if (!mapAvailable || gridCells.length === 0) return null
    const cellSize = Math.max(4, Math.min(20, 400 / Math.max(mapWidth, mapHeight)))
    const canvasW = mapWidth * cellSize
    const canvasH = mapHeight * cellSize
    // 虚拟化：单元格过多时只渲染前 MAX_RENDER_CELLS 个，其余提示
    const totalCells = gridCells.length
    const truncated = totalCells > MAX_RENDER_CELLS
    const visibleCells = truncated ? gridCells.slice(0, MAX_RENDER_CELLS) : gridCells
    return (
      <div style={{
        background: 'var(--bg-2)', borderRadius: 6, padding: 12, marginBottom: 12,
        border: '1px solid var(--line-2)',
      }}>
        <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
          地形分区图（{mapWidth}×{mapHeight} · 分辨率 {mapData?.gridResolution}m）
        </h3>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <svg width={canvasW} height={canvasH} style={{ border: '1px solid var(--line-2)', borderRadius: 3 }}>
            {visibleCells.map((cellType, idx) => {
              const row = Math.floor(idx / mapWidth)
              const col = idx % mapWidth
              const color = TERRAIN_COLORS[cellType] || '#333'
              return (
                <rect
                  key={`${row}-${col}`}
                  x={col * cellSize}
                  y={row * cellSize}
                  width={cellSize}
                  height={cellSize}
                  fill={color}
                  title={`(${col},${row}) ${TERRAIN_LABELS[cellType] || '未知'}`}
                />
              )
            })}
          </svg>
          {/* 图例 */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 10 }}>
            {Object.entries(TERRAIN_LABELS).map(([code, label]) => (
              <div key={code} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{
                  display: 'inline-block', width: 12, height: 12,
                  background: TERRAIN_COLORS[code] || '#333',
                  border: '1px solid var(--line-2)', borderRadius: 2,
                }} />
                <span style={{ color: 'var(--dim-2)' }}>{label}</span>
              </div>
            ))}
          </div>
        </div>
        {truncated && (
          <div style={{ fontSize: 10, color: 'var(--warn)', marginTop: 6 }}>
            ⚠ 网格过大（{totalCells} 格），仅渲染前 {MAX_RENDER_CELLS} 格以保证性能，完整数据请缩小建图范围或提高分辨率。
          </div>
        )}
        <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 6 }}>
          版本 v{mapData?.version ?? '--'} · 原点 ({mapData?.originLat?.toFixed(5) ?? '--'}, {mapData?.originLon?.toFixed(5) ?? '--'})
        </div>
      </div>
    )
  }

  // 渲染飞行限制区
  const renderRestrictions = () => (
    <div style={{
      background: 'var(--bg-2)', borderRadius: 6, padding: 12, marginBottom: 12,
      border: '1px solid var(--line-2)',
    }}>
      <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
        飞行限制区（{restrictions?.count ?? 0}）
      </h3>
      {restrictionList.length > 0 ? (
        <table style={tableStyle}>
          <thead>
            <tr>
              <th style={thStyle}>类型</th>
              <th style={thStyle}>限值</th>
              <th style={thStyle}>顶点数</th>
              <th style={thStyle}>时间</th>
            </tr>
          </thead>
          <tbody>
            {restrictionList.map((r, i) => (
              // 优先使用业务标识；缺失时，无行内状态的静态展示行允许前缀索引兜底。
              <tr key={r.id ?? r.name ?? r.timestamp ?? `r-${i}`}>
                <td style={{ ...tdStyle, color: r.restrictionType === 0 ? 'var(--crit)' : 'var(--warn)' }}>
                  {RESTRICTION_LABELS[r.restrictionType] || `类型${r.restrictionType}`}
                </td>
                <td style={tdStyle}>{r.limitValue}</td>
                <td style={tdStyle}>{(r.area || []).length}</td>
                <td style={{ ...tdStyle, fontSize: 9, color: 'var(--dim-2)' }}>
                  {r.timestamp ? new Date(r.timestamp).toLocaleTimeString('zh-CN', { hour12: false }) : '--'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div style={{ color: 'var(--dim-2)', fontSize: 10 }}>暂无飞行限制区数据</div>
      )}
    </div>
  )

  // 渲染变更历史
  const renderChanges = () => (
    <div style={{
      background: 'var(--bg-2)', borderRadius: 6, padding: 12, marginBottom: 12,
      border: '1px solid var(--line-2)',
    }}>
      <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
        地形变更历史（{changes?.count ?? 0}）
      </h3>
      {changeList.length > 0 ? (
        <table style={tableStyle}>
          <thead>
            <tr>
              <th style={thStyle}>版本</th>
              <th style={thStyle}>原因</th>
              <th style={thStyle}>影响格数</th>
              <th style={thStyle}>时间</th>
            </tr>
          </thead>
          <tbody>
            {changeList.slice().reverse().map((c, i) => (
              // 优先使用记录标识或地形版本，避免倒序展示时新增记录改变已有行的 key。
              // 缺失业务标识时，无行内状态的静态展示行允许前缀索引兜底。
              <tr key={c.id ?? c.terrainVersion ?? c.timestamp ?? `ch-${i}`}>
                <td style={tdStyle}>v{c.terrainVersion}</td>
                <td style={{ ...tdStyle, color: c.changeReason === 3 ? 'var(--crit)' : 'var(--text)' }}>
                  {CHANGE_REASON_LABELS[c.changeReason] || `原因${c.changeReason}`}
                </td>
                <td style={tdStyle}>{(c.affectedCells || []).length}</td>
                <td style={{ ...tdStyle, fontSize: 9, color: 'var(--dim-2)' }}>
                  {c.timestamp ? new Date(c.timestamp).toLocaleTimeString('zh-CN', { hour12: false }) : '--'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div style={{ color: 'var(--dim-2)', fontSize: 10 }}>暂无变更历史</div>
      )}
    </div>
  )

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        复杂地形适配
      </h2>

      {pollErr && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8 }}>
          数据获取失败：{pollErr}
        </div>
      )}

      {/* 概览卡片 */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <div style={cardStyle}>
          <div style={labelStyle}>地形图</div>
          <div style={valueStyle}>{mapAvailable ? '可用' : '无'}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>版本</div>
          <div style={valueStyle}>{mapData?.version ?? '--'}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>限制区</div>
          <div style={valueStyle}>{restrictions?.count ?? '--'}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>变更记录</div>
          <div style={valueStyle}>{changes?.count ?? '--'}</div>
        </div>
      </div>

      {/* 建图触发器 */}
      <div style={{
        background: 'var(--bg-2)', borderRadius: 6, padding: 12, marginBottom: 12,
        border: '1px solid var(--line-2)',
      }}>
        <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
          触发地形建图
        </h3>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <label style={inputLabelStyle}>
            <span style={labelStyle}>原点纬度</span>
            <input
              type="number" step="0.00001" value={buildForm.originLat}
              onChange={(e) => setBuildForm({ ...buildForm, originLat: parseFloat(e.target.value) || 0 })}
              style={inputStyle}
            />
          </label>
          <label style={inputLabelStyle}>
            <span style={labelStyle}>原点经度</span>
            <input
              type="number" step="0.00001" value={buildForm.originLon}
              onChange={(e) => setBuildForm({ ...buildForm, originLon: parseFloat(e.target.value) || 0 })}
              style={inputStyle}
            />
          </label>
          <label style={inputLabelStyle}>
            <span style={labelStyle}>宽度(m)</span>
            <input
              type="number" value={buildForm.widthM}
              onChange={(e) => setBuildForm({ ...buildForm, widthM: parseFloat(e.target.value) || 0 })}
              style={inputStyle}
            />
          </label>
          <label style={inputLabelStyle}>
            <span style={labelStyle}>高度(m)</span>
            <input
              type="number" value={buildForm.heightM}
              onChange={(e) => setBuildForm({ ...buildForm, heightM: parseFloat(e.target.value) || 0 })}
              style={inputStyle}
            />
          </label>
          <label style={inputLabelStyle}>
            <span style={labelStyle}>分辨率(m)</span>
            <input
              type="number" value={buildForm.gridResolution}
              onChange={(e) => setBuildForm({ ...buildForm, gridResolution: parseFloat(e.target.value) || 0 })}
              style={inputStyle}
            />
          </label>
          <button
            className="btn primary"
            style={{ padding: '5px 14px', fontSize: 11 }}
            onClick={handleBuild}
          >
            建图
          </button>
        </div>
        {buildMsg && (
          <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 6 }}>{buildMsg}</div>
        )}
      </div>

      {renderTerrainGrid()}
      {renderRestrictions()}
      {renderChanges()}
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

const inputLabelStyle = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
}

const inputStyle = {
  background: 'var(--bg)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  padding: '3px 6px',
  color: 'var(--text)',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  width: 80,
}