import React, { useState, useEffect, useRef, useCallback } from 'react'
import { api } from '../api.js'

// 星-空-地多层级中继面板（M7 星-空-地多层级中继）
// 展示卫星链路状态、过境计划、层级路由决策、切换策略选择器
// 通过 WebSocket 实时刷新（sat-link 事件）
// 风格与 MeshTopologyPanel 一致：卡片布局 + 内联 CSS + CSS 变量

const POLL_MS = 2000

// 层级标签
const LAYER_LABELS = {
  0: 'L0 地面',
  1: 'L1 Mesh',
  2: 'L2 HAPS',
  3: 'L3 LEO',
  4: 'L4 站/云',
}

// 策略标签
const STRATEGY_LABELS = {
  NEAR_FIRST: '近端优先',
  DELAY_OPTIMAL: '延迟最优',
  BANDWIDTH_OPTIMAL: '带宽最优',
  RELIABILITY_OPTIMAL: '可靠性最优',
}

export default function SatLinkPanel({ satLinkData }) {
  const [status, setStatus] = useState(null)
  const [passes, setPasses] = useState(null)
  const [routes, setRoutes] = useState(null)
  const [strategy, setStrategy] = useState(null)
  const [pollErr, setPollErr] = useState(null)
  const [strategyMsg, setStrategyMsg] = useState(null)
  const timerRef = useRef(null)

  // 初始加载 + 周期刷新
  const refresh = useCallback(async () => {
    try {
      const [st, ps, rt, sg] = await Promise.all([
        api.getSatLinkStatus().catch(() => null),
        api.getSatLinkPasses().catch(() => null),
        api.getSatLinkRoutes().catch(() => null),
        api.getSatLinkStrategy().catch(() => null),
      ])
      setStatus(st)
      setPasses(ps)
      setRoutes(rt)
      setStrategy(sg)
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

  // WebSocket 事件合并
  useEffect(() => {
    if (satLinkData && satLinkData.type === 'sat-link') {
      refresh()
    }
  }, [satLinkData, refresh])

  // 策略切换
  const handleStrategyChange = async (newStrategy) => {
    try {
      const res = await api.setSatLinkStrategy(newStrategy)
      setStrategyMsg(`策略已切换为 ${STRATEGY_LABELS[newStrategy] || newStrategy}`)
      setTimeout(() => setStrategyMsg(null), 3000)
      refresh()
    } catch (e) {
      setStrategyMsg(`切换失败：${e.message}`)
      setTimeout(() => setStrategyMsg(null), 3000)
    }
  }

  const satLinks = status?.links || []
  const passList = passes?.passes || []
  const routeList = routes?.routes || []
  const currentStrategy = strategy?.strategy || 'NEAR_FIRST'

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        星-空-地多层级中继
      </h2>

      {pollErr && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8 }}>
          数据获取失败：{pollErr}
        </div>
      )}

      {/* 概览卡片 */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <div style={cardStyle}>
          <div style={labelStyle}>可见卫星</div>
          <div style={valueStyle}>{satLinks.filter(s => s.visible).length}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>总卫星数</div>
          <div style={valueStyle}>{status?.satCount ?? '--'}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>过境计划</div>
          <div style={valueStyle}>{passes?.passCount ?? '--'}</div>
        </div>
        <div style={cardStyle}>
          <div style={labelStyle}>路由决策</div>
          <div style={valueStyle}>{routes?.routeCount ?? '--'}</div>
        </div>
      </div>

      {/* 策略选择器 */}
      <div style={{
        background: 'var(--bg-2)', borderRadius: 6, padding: 12, marginBottom: 12,
        border: '1px solid var(--line-2)',
      }}>
        <div style={labelStyle}>切换策略</div>
        <div style={{ display: 'flex', gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
          {Object.entries(STRATEGY_LABELS).map(([key, label]) => (
            <button
              key={key}
              className={`btn ${currentStrategy === key ? 'primary' : ''}`}
              style={{ padding: '4px 10px', fontSize: 11 }}
              onClick={() => handleStrategyChange(key)}
            >
              {label}
            </button>
          ))}
        </div>
        {strategyMsg && (
          <div style={{ fontSize: 10, color: 'var(--dim-2)', marginTop: 6 }}>
            {strategyMsg}
          </div>
        )}
      </div>

      {/* 卫星链路状态表 */}
      <div style={{
        background: 'var(--bg-2)', borderRadius: 6, padding: 12, marginBottom: 12,
        border: '1px solid var(--line-2)',
      }}>
        <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
          卫星链路状态
        </h3>
        {satLinks.length > 0 ? (
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>satId</th>
                <th style={thStyle}>可见</th>
                <th style={thStyle}>仰角°</th>
                <th style={thStyle}>延迟ms</th>
                <th style={thStyle}>带宽Mbps</th>
                <th style={thStyle}>仿真</th>
              </tr>
            </thead>
            <tbody>
              {satLinks.map((s) => (
                <tr key={s.satId}>
                  <td style={tdStyle}>{s.satId}</td>
                  <td style={{ ...tdStyle, color: s.visible ? 'var(--cyan)' : 'var(--dim)' }}>
                    {s.visible ? '✓' : '✗'}
                  </td>
                  <td style={tdStyle}>{s.elevationDeg}</td>
                  <td style={tdStyle}>{s.delayMs}</td>
                  <td style={tdStyle}>{s.bandwidthMbps}</td>
                  <td style={{ ...tdStyle, color: s.simulated ? 'var(--warn)' : 'var(--dim-2)' }}>
                    {s.simulated ? '仿真' : '真实'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div style={{ color: 'var(--dim-2)', fontSize: 10 }}>
            暂无卫星链路数据（请确认 drone-sim 已启用 --sat-relay）
          </div>
        )}
      </div>

      {/* 过境计划 */}
      <div style={{
        background: 'var(--bg-2)', borderRadius: 6, padding: 12, marginBottom: 12,
        border: '1px solid var(--line-2)',
      }}>
        <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
          过境计划
        </h3>
        {passList.length > 0 ? (
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>satId</th>
                <th style={thStyle}>开始(ms)</th>
                <th style={thStyle}>结束(ms)</th>
                <th style={thStyle}>持续(ms)</th>
                <th style={thStyle}>最大仰角°</th>
              </tr>
            </thead>
            <tbody>
              {passList.slice(0, 20).map((p, i) => (
                <tr key={i}>
                  <td style={tdStyle}>{p.satId}</td>
                  <td style={tdStyle}>{p.passStartMs}</td>
                  <td style={tdStyle}>{p.passEndMs}</td>
                  <td style={tdStyle}>{p.durationMs}</td>
                  <td style={tdStyle}>{p.maxElevationDeg}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div style={{ color: 'var(--dim-2)', fontSize: 10 }}>暂无过境计划数据</div>
        )}
      </div>

      {/* 路由决策历史 */}
      <div style={{
        background: 'var(--bg-2)', borderRadius: 6, padding: 12,
        border: '1px solid var(--line-2)',
      }}>
        <h3 style={{ fontSize: 12, margin: '0 0 8px 0', color: 'var(--text)' }}>
          路由决策历史
        </h3>
        {routeList.length > 0 ? (
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>选定层级</th>
                <th style={thStyle}>延迟ms</th>
                <th style={thStyle}>路径</th>
                <th style={thStyle}>原因</th>
              </tr>
            </thead>
            <tbody>
              {routeList.slice(-10).reverse().map((r, i) => (
                <tr key={i}>
                  <td style={tdStyle}>
                    {r.chosenLayer != null ? LAYER_LABELS[r.chosenLayer] || `L${r.chosenLayer}` : '不可达'}
                  </td>
                  <td style={tdStyle}>{r.estimatedDelayMs >= 0 ? r.estimatedDelayMs : '--'}</td>
                  <td style={tdStyle}>{(r.pathNodes || []).join('→')}</td>
                  <td style={{ ...tdStyle, fontSize: 9, color: 'var(--dim-2)' }}>
                    {r.decisionReason}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div style={{ color: 'var(--dim-2)', fontSize: 10 }}>暂无路由决策数据</div>
        )}
      </div>
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