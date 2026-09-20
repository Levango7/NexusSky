import React, { useState, useEffect, useCallback } from 'react'
import {
  getCommLinks,
  getCommScore,
  getCommDecision,
  executeCommFailover,
  getCommFailoverHistory,
  getCommConfig,
  updateCommConfig,
} from '../api.js'

// P2 多模态通信自适应面板
// 链路质量监控 + 综合评分 + 切换决策 + 故障切换历史 + 自适应配置
// 风格与 TrackingPanel / GeofencePanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 5s；AbortController 竞态守卫

const POLL_MS = 5000

// 链路类型
const LINK_TYPES = ['MESH', 'SATELLITE', 'CELLULAR']

// 链路类型 → 中文标签 / 颜色
const LINK_META = {
  MESH: { label: 'Mesh 网络', color: 'var(--cyan)' },
  SATELLITE: { label: '卫星链路', color: 'var(--ok)' },
  CELLULAR: { label: '蜂窝网络', color: 'var(--warn)' },
}

// 评分等级 → 颜色
const GRADE_COLOR = {
  A: 'var(--ok)',
  B: 'var(--cyan)',
  C: 'var(--warn)',
  D: 'var(--crit)',
  F: 'var(--crit)',
}

// 紧急程度 → 颜色 / 标签
const URGENCY_META = {
  LOW: { color: 'var(--ok)', label: '低' },
  MEDIUM: { color: 'var(--warn)', label: '中' },
  HIGH: { color: 'var(--crit)', label: '高' },
  CRITICAL: { color: 'var(--crit)', label: '紧急' },
}

// 格式化时间戳
function fmtTime(ts) {
  if (ts == null || ts === '') return '--'
  const n = Number(ts)
  if (!Number.isFinite(n)) return String(ts)
  return new Date(n).toLocaleString('zh-CN', { hour12: false })
}

// 字段兼容提取
function pick(obj, ...keys) {
  if (!obj) return null
  for (const k of keys) {
    if (obj[k] != null) return obj[k]
  }
  return null
}

export default function CommAdaptPanel() {
  // ---- 链路质量列表 ----
  const [links, setLinks] = useState([])
  const [linksError, setLinksError] = useState(null)

  // ---- 综合评分 ----
  const [scoreSysid, setScoreSysid] = useState('')
  const [score, setScore] = useState(null)
  const [scoreLoading, setScoreLoading] = useState(false)
  const [scoreError, setScoreError] = useState(null)

  // ---- 切换决策 ----
  const [decision, setDecision] = useState(null)
  const [decisionLoading, setDecisionLoading] = useState(false)
  const [decisionError, setDecisionError] = useState(null)

  // ---- 故障切换 ----
  const [failoverHistory, setFailoverHistory] = useState([])
  const [failoverError, setFailoverError] = useState(null)
  const [executingFailover, setExecutingFailover] = useState(false)

  // ---- 自适应配置 ----
  const [config, setConfig] = useState(null)
  const [configLoading, setConfigLoading] = useState(false)
  const [configSaving, setConfigSaving] = useState(false)
  const [configError, setConfigError] = useState(null)
  const [configForm, setConfigForm] = useState({
    scoreThresholdA: '',
    scoreThresholdB: '',
    scoreThresholdC: '',
    scoreThresholdD: '',
    switchIntervalMs: '',
    maxRetries: '',
  })

  // ---- 轮询链路质量 + 故障切换历史 ----
  useEffect(() => {
    const controller = new AbortController()
    let timer = null
    let stopped = false

    const load = async () => {
      try {
        const [linksData, failoverData] = await Promise.all([
          getCommLinks().catch(() => []),
          getCommFailoverHistory().catch(() => []),
        ])
        if (controller.signal.aborted || stopped) return
        const linksList = Array.isArray(linksData) ? linksData : (linksData && linksData.links) || []
        setLinks(linksList)
        setLinksError(null)
        const failoverList = Array.isArray(failoverData) ? failoverData : (failoverData && failoverData.history) || []
        setFailoverHistory(failoverList)
        setFailoverError(null)
      } catch (e) {
        if (controller.signal.aborted || stopped) return
        setLinksError(e && e.message ? e.message : String(e))
      }
    }

    load()
    timer = setInterval(load, POLL_MS)

    return () => {
      stopped = true
      controller.abort()
      clearInterval(timer)
    }
  }, [])

  // ---- 加载自适应配置 ----
  useEffect(() => {
    const load = async () => {
      setConfigLoading(true)
      try {
        const data = await getCommConfig()
        setConfig(data)
        setConfigForm({
          scoreThresholdA: pick(data, 'scoreThresholdA', 'score_threshold_a') ?? '',
          scoreThresholdB: pick(data, 'scoreThresholdB', 'score_threshold_b') ?? '',
          scoreThresholdC: pick(data, 'scoreThresholdC', 'score_threshold_c') ?? '',
          scoreThresholdD: pick(data, 'scoreThresholdD', 'score_threshold_d') ?? '',
          switchIntervalMs: pick(data, 'switchIntervalMs', 'switch_interval_ms') ?? '',
          maxRetries: pick(data, 'maxRetries', 'max_retries') ?? '',
        })
      } catch (e) {
        setConfigError(e && e.message ? e.message : String(e))
      } finally {
        setConfigLoading(false)
      }
    }
    load()
  }, [])

  // ---- 查询评分 ----
  const handleQueryScore = useCallback(async () => {
    const sysid = scoreSysid.trim()
    if (!sysid) {
      setScoreError('请输入 sysid')
      return
    }
    setScoreLoading(true)
    setScoreError(null)
    try {
      const data = await getCommScore(sysid)
      setScore(data)
    } catch (e) {
      setScoreError(e && e.message ? e.message : String(e))
      setScore(null)
    } finally {
      setScoreLoading(false)
    }
  }, [scoreSysid])

  // ---- 查询切换决策 ----
  const handleQueryDecision = useCallback(async () => {
    const sysid = scoreSysid.trim()
    if (!sysid) {
      setDecisionError('请输入 sysid')
      return
    }
    setDecisionLoading(true)
    setDecisionError(null)
    try {
      const data = await getCommDecision(sysid)
      setDecision(data)
    } catch (e) {
      setDecisionError(e && e.message ? e.message : String(e))
      setDecision(null)
    } finally {
      setDecisionLoading(false)
    }
  }, [scoreSysid])

  // ---- 执行故障切换 ----
  const handleFailover = useCallback(async () => {
    const sysid = scoreSysid.trim()
    if (!sysid) {
      setFailoverError('请输入 sysid')
      return
    }
    setExecutingFailover(true)
    setFailoverError(null)
    try {
      await executeCommFailover({ sysid: Number(sysid) })
    } catch (e) {
      setFailoverError(e && e.message ? e.message : String(e))
    } finally {
      setExecutingFailover(false)
    }
  }, [scoreSysid])

  // ---- 保存配置 ----
  const handleSaveConfig = useCallback(async () => {
    setConfigSaving(true)
    setConfigError(null)
    try {
      const payload = {}
      if (configForm.scoreThresholdA !== '') payload.scoreThresholdA = Number(configForm.scoreThresholdA)
      if (configForm.scoreThresholdB !== '') payload.scoreThresholdB = Number(configForm.scoreThresholdB)
      if (configForm.scoreThresholdC !== '') payload.scoreThresholdC = Number(configForm.scoreThresholdC)
      if (configForm.scoreThresholdD !== '') payload.scoreThresholdD = Number(configForm.scoreThresholdD)
      if (configForm.switchIntervalMs !== '') payload.switchIntervalMs = Number(configForm.switchIntervalMs)
      if (configForm.maxRetries !== '') payload.maxRetries = Number(configForm.maxRetries)
      const data = await updateCommConfig(payload)
      setConfig(data)
    } catch (e) {
      setConfigError(e && e.message ? e.message : String(e))
    } finally {
      setConfigSaving(false)
    }
  }, [configForm])

  const updateConfigForm = useCallback((key, value) => {
    setConfigForm((prev) => ({ ...prev, [key]: value }))
  }, [])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        多模态通信自适应
      </h2>

      {(linksError || failoverError) && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {linksError ? `链路数据刷新失败：${linksError}` : `故障切换失败：${failoverError}`}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左列：链路质量 + 评分 + 决策 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 链路质量监控 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>
                链路质量监控
                <span style={{ fontSize: 9, color: 'var(--dim-2)', marginLeft: 6 }}>（{links.length}）</span>
              </span>
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {links.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>
                  {linksError ? '刷新失败，等待重试' : '暂无链路数据'}
                </div>
              ) : (
                links.map((link, i) => {
                  const sysid = pick(link, 'sysid', 'id')
                  const linkType = pick(link, 'linkType', 'type')
                  const latency = pick(link, 'latency', 'latencyMs')
                  const bandwidth = pick(link, 'bandwidth', 'bandwidthMbps')
                  const packetLoss = pick(link, 'packetLoss', 'lossRate')
                  const rssi = pick(link, 'rssi', 'signalStrength')
                  const meta = LINK_META[linkType] || { label: linkType || '--', color: 'var(--dim)' }
                  return (
                    <div key={sysid != null ? sysid : i} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: 'var(--cyan)', fontWeight: 'bold', flexShrink: 0 }}>#{sysid != null ? sysid : '?'}</span>
                          <span style={{ fontSize: 11, color: meta.color, fontWeight: 'bold' }}>{meta.label}</span>
                        </div>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        <span>延迟：{latency != null ? `${Number(latency).toFixed(0)} ms` : '--'}</span>
                        <span>带宽：{bandwidth != null ? `${Number(bandwidth).toFixed(1)} Mbps` : '--'}</span>
                        <span>丢包：{packetLoss != null ? `${Number(packetLoss).toFixed(1)}%` : '--'}</span>
                        <span>RSSI：{rssi != null ? `${Number(rssi).toFixed(0)} dBm` : '--'}</span>
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 综合质量评分 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8 }}>综合质量评分</div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
              <input
                type="number"
                placeholder="sysid"
                value={scoreSysid}
                onChange={(e) => setScoreSysid(e.target.value)}
                style={{ ...modalInputStyle, width: 90, flex: '0 0 90px' }}
              />
              <button
                onClick={handleQueryScore}
                disabled={scoreLoading}
                style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: scoreLoading ? 0.5 : 1, cursor: scoreLoading ? 'not-allowed' : 'pointer' }}
              >
                {scoreLoading ? '查询中…' : '查询评分'}
              </button>
              <button
                onClick={handleQueryDecision}
                disabled={decisionLoading}
                style={{ ...miniBtnStyle, color: 'var(--warn)', borderColor: 'var(--warn)', opacity: decisionLoading ? 0.5 : 1, cursor: decisionLoading ? 'not-allowed' : 'pointer' }}
              >
                {decisionLoading ? '查询中…' : '查询决策'}
              </button>
              <button
                onClick={handleFailover}
                disabled={executingFailover}
                style={{ ...miniBtnStyle, color: 'var(--crit)', borderColor: 'var(--crit)', opacity: executingFailover ? 0.5 : 1, cursor: executingFailover ? 'not-allowed' : 'pointer' }}
              >
                {executingFailover ? '切换中…' : '故障切换'}
              </button>
            </div>
            {scoreError && <div style={{ fontSize: 10, color: 'var(--crit)', marginBottom: 4 }}>⚠ {scoreError}</div>}
            {decisionError && <div style={{ fontSize: 10, color: 'var(--crit)', marginBottom: 4 }}>⚠ {decisionError}</div>}
            {score && (
              <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 6 }}>
                <span style={{
                  fontSize: 24, fontWeight: 'bold', fontFamily: 'var(--mono)',
                  color: GRADE_COLOR[pick(score, 'grade')] || 'var(--text)',
                }}>
                  {pick(score, 'grade') || '--'}
                </span>
                <span style={{ fontSize: 14, color: 'var(--dim)', fontFamily: 'var(--mono)' }}>
                  {pick(score, 'score', 'totalScore') != null ? Number(pick(score, 'score', 'totalScore')).toFixed(1) : '--'} 分
                </span>
              </div>
            )}
            {decision && (
              <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3 }}>
                <div>当前链路：<span style={{ color: LINK_META[pick(decision, 'currentLink')]?.color || 'var(--text)' }}>{LINK_META[pick(decision, 'currentLink')]?.label || pick(decision, 'currentLink') || '--'}</span></div>
                <div>推荐链路：<span style={{ color: LINK_META[pick(decision, 'recommendedLink')]?.color || 'var(--ok)' }}>{LINK_META[pick(decision, 'recommendedLink')]?.label || pick(decision, 'recommendedLink') || '--'}</span></div>
                <div>紧急程度：
                  <span style={{ color: URGENCY_META[pick(decision, 'urgency')]?.color || 'var(--dim)' }}>
                    {URGENCY_META[pick(decision, 'urgency')]?.label || pick(decision, 'urgency') || '--'}
                  </span>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* 右列：故障切换历史 + 自适应配置 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 故障切换历史 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>故障切换历史（{failoverHistory.length}）</span>
            </div>
            <div style={{ maxHeight: 260, overflowY: 'auto' }}>
              {failoverHistory.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无故障切换记录</div>
              ) : (
                failoverHistory.map((h, i) => {
                  const sysid = pick(h, 'sysid', 'id')
                  const fromLink = pick(h, 'fromLink', 'from')
                  const toLink = pick(h, 'toLink', 'to')
                  const reason = pick(h, 'reason')
                  const ts = pick(h, 'timestamp', 'ts', 'time')
                  return (
                    <div key={sysid != null ? sysid : i} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: '3px solid var(--warn)' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 9, color: 'var(--cyan)', fontWeight: 'bold' }}>#{sysid != null ? sysid : '?'}</span>
                        <span style={{ fontSize: 9, color: 'var(--dim-2)', fontFamily: 'var(--mono)' }}>{fmtTime(ts)}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                        <span>{LINK_META[fromLink]?.label || fromLink || '--'} → {LINK_META[toLink]?.label || toLink || '--'}</span>
                        {reason && <span style={{ color: 'var(--warn)' }}>{reason}</span>}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 自适应配置 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8 }}>自适应配置</div>
            {configError && (
              <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 6, padding: '3px 6px', background: 'var(--bg-1)', borderRadius: 3, border: '1px solid var(--crit)' }}>⚠ {configError}</div>
            )}
            {configLoading ? (
              <div style={{ fontSize: 10, color: 'var(--cyan)' }}>加载中…</div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <div style={{ flex: '1 1 100px' }}>
                    <div style={labelStyle}>A 级阈值</div>
                    <input type="number" step="any" value={configForm.scoreThresholdA} onChange={(e) => updateConfigForm('scoreThresholdA', e.target.value)} style={modalInputStyle} placeholder="≥ A" />
                  </div>
                  <div style={{ flex: '1 1 100px' }}>
                    <div style={labelStyle}>B 级阈值</div>
                    <input type="number" step="any" value={configForm.scoreThresholdB} onChange={(e) => updateConfigForm('scoreThresholdB', e.target.value)} style={modalInputStyle} placeholder="≥ B" />
                  </div>
                  <div style={{ flex: '1 1 100px' }}>
                    <div style={labelStyle}>C 级阈值</div>
                    <input type="number" step="any" value={configForm.scoreThresholdC} onChange={(e) => updateConfigForm('scoreThresholdC', e.target.value)} style={modalInputStyle} placeholder="≥ C" />
                  </div>
                  <div style={{ flex: '1 1 100px' }}>
                    <div style={labelStyle}>D 级阈值</div>
                    <input type="number" step="any" value={configForm.scoreThresholdD} onChange={(e) => updateConfigForm('scoreThresholdD', e.target.value)} style={modalInputStyle} placeholder="≥ D" />
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <div style={{ flex: '1 1 160px' }}>
                    <div style={labelStyle}>切换间隔 (ms)</div>
                    <input type="number" value={configForm.switchIntervalMs} onChange={(e) => updateConfigForm('switchIntervalMs', e.target.value)} style={modalInputStyle} placeholder="毫秒" />
                  </div>
                  <div style={{ flex: '1 1 100px' }}>
                    <div style={labelStyle}>最大重试次数</div>
                    <input type="number" value={configForm.maxRetries} onChange={(e) => updateConfigForm('maxRetries', e.target.value)} style={modalInputStyle} placeholder="次数" />
                  </div>
                </div>
                <button
                  onClick={handleSaveConfig}
                  disabled={configSaving}
                  style={{ ...miniBtnStyle, padding: '4px 12px', alignSelf: 'flex-start', border: '1px solid var(--cyan)', color: 'var(--cyan)', cursor: configSaving ? 'not-allowed' : 'pointer', opacity: configSaving ? 0.5 : 1 }}
                >
                  {configSaving ? '保存中…' : '保存配置'}
                </button>
              </div>
            )}
          </div>
        </div>
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
}

const labelStyle = {
  fontSize: 10,
  color: 'var(--dim-2)',
  marginBottom: 2,
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

const modalInputStyle = {
  width: '100%',
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
  boxSizing: 'border-box',
}