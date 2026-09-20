import React, { useState, useEffect, useCallback } from 'react'
import {
  parseVoiceCommand,
  executeVoiceCommand,
  confirmVoiceCommand,
  broadcastVoiceMessage,
  getVoiceStatus,
  broadcastVoiceAlert,
  getVoiceHistory,
  getVoicePending,
} from '../api.js'

// P3 语音指挥面板
// 语音指令解析 + 执行 + 待确认 + 播报 + 告警 + 历史
// 风格与 TrackingPanel / GeofencePanel 一致

const POLL_MS = 5000

// 优先级 → 颜色 / 标签
const PRIORITY_META = {
  LOW: { color: 'var(--ok)', label: '低' },
  MEDIUM: { color: 'var(--cyan)', label: '中' },
  HIGH: { color: 'var(--warn)', label: '高' },
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

// 播报状态模板
const BROADCAST_TEMPLATES = [
  { key: 'STATUS', label: '状态播报' },
  { key: 'WARNING', label: '警告播报' },
  { key: 'CUSTOM', label: '自定义播报' },
]

export default function VoiceCmdPanel() {
  // ---- 指令输入 + 解析 ----
  const [inputText, setInputText] = useState('')
  const [parsed, setParsed] = useState(null)
  const [parsing, setParsing] = useState(false)
  const [parseError, setParseError] = useState(null)

  // ---- 执行指令 ----
  const [executing, setExecuting] = useState(false)
  const [execResult, setExecResult] = useState(null)
  const [execError, setExecError] = useState(null)

  // ---- 待确认指令 ----
  const [pending, setPending] = useState([])
  const [pendingError, setPendingError] = useState(null)

  // ---- 播报 ----
  const [broadcastText, setBroadcastText] = useState('')
  const [broadcastSysid, setBroadcastSysid] = useState('')
  const [broadcasting, setBroadcasting] = useState(false)
  const [broadcastResult, setBroadcastResult] = useState(null)

  // ---- 告警播报 ----
  const [alertText, setAlertText] = useState('')
  const [alertSysid, setAlertSysid] = useState('')
  const [alerting, setAlerting] = useState(false)

  // ---- 播报状态 ----
  const [statusSysid, setStatusSysid] = useState('')
  const [voiceStatus, setVoiceStatus] = useState(null)
  const [statusLoading, setStatusLoading] = useState(false)

  // ---- 指令历史 ----
  const [history, setHistory] = useState([])
  const [historyError, setHistoryError] = useState(null)

  // ---- 轮询待确认指令 + 历史 ----
  useEffect(() => {
    const controller = new AbortController()
    let timer = null
    let stopped = false

    const load = async () => {
      try {
        const [pendingData, historyData] = await Promise.all([
          getVoicePending().catch(() => []),
          getVoiceHistory().catch(() => []),
        ])
        if (controller.signal.aborted || stopped) return
        const pendingList = Array.isArray(pendingData) ? pendingData : (pendingData && pendingData.pending) || []
        setPending(pendingList)
        setPendingError(null)
        const historyList = Array.isArray(historyData) ? historyData : (historyData && pendingData && historyData.history) || []
        setHistory(historyList)
        setHistoryError(null)
      } catch (e) {
        if (controller.signal.aborted || stopped) return
        setPendingError(e && e.message ? e.message : String(e))
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

  // ---- 解析指令 ----
  const handleParse = useCallback(async () => {
    const text = inputText.trim()
    if (!text) {
      setParseError('请输入指令文本')
      return
    }
    setParsing(true)
    setParseError(null)
    setParsed(null)
    try {
      const data = await parseVoiceCommand(text)
      setParsed(data)
    } catch (e) {
      setParseError(e && e.message ? e.message : String(e))
    } finally {
      setParsing(false)
    }
  }, [inputText])

  // ---- 执行指令 ----
  const handleExecute = useCallback(async () => {
    if (!parsed) return
    setExecuting(true)
    setExecError(null)
    setExecResult(null)
    try {
      const payload = {
        action: pick(parsed, 'action'),
        target: pick(parsed, 'target'),
        parameters: pick(parsed, 'parameters', 'params'),
        priority: pick(parsed, 'priority'),
      }
      const data = await executeVoiceCommand(payload)
      setExecResult(data)
    } catch (e) {
      setExecError(e && e.message ? e.message : String(e))
    } finally {
      setExecuting(false)
    }
  }, [parsed])

  // ---- 确认执行 ----
  const handleConfirm = useCallback(async (pendingId) => {
    try {
      await confirmVoiceCommand(pendingId)
      setPending((prev) => prev.filter((p) => pick(p, 'pendingId', 'id') !== pendingId))
    } catch (e) {
      setPendingError('确认失败：' + (e && e.message ? e.message : String(e)))
    }
  }, [])

  // ---- 语音播报 ----
  const handleBroadcast = useCallback(async () => {
    const text = broadcastText.trim()
    if (!text) return
    setBroadcasting(true)
    setBroadcastResult(null)
    try {
      const payload = { text }
      if (broadcastSysid.trim()) payload.sysid = Number(broadcastSysid)
      const data = await broadcastVoiceMessage(payload)
      setBroadcastResult(data)
    } catch (e) {
      setBroadcastResult({ error: e && e.message ? e.message : String(e) })
    } finally {
      setBroadcasting(false)
    }
  }, [broadcastText, broadcastSysid])

  // ---- 告警播报 ----
  const handleAlert = useCallback(async () => {
    const text = alertText.trim()
    if (!text) return
    setAlerting(true)
    try {
      const payload = { text }
      if (alertSysid.trim()) payload.sysid = Number(alertSysid)
      await broadcastVoiceAlert(payload)
    } catch (e) {
      setBroadcastResult({ error: e && e.message ? e.message : String(e) })
    } finally {
      setAlerting(false)
    }
  }, [alertText, alertSysid])

  // ---- 查询播报状态 ----
  const handleQueryStatus = useCallback(async () => {
    const sysid = statusSysid.trim()
    if (!sysid) return
    setStatusLoading(true)
    try {
      const data = await getVoiceStatus(sysid)
      setVoiceStatus(data)
    } catch (e) {
      setVoiceStatus({ error: e && e.message ? e.message : String(e) })
    } finally {
      setStatusLoading(false)
    }
  }, [statusSysid])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>语音指挥</h2>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左列：指令输入 + 解析结果 + 执行 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 指令输入 + 解析 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>语音指令输入</div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <input
                type="text"
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') handleParse() }}
                style={{ ...modalInputStyle, flex: '1 1 200px' }}
                placeholder="输入语音指令文本"
              />
              <button
                onClick={handleParse}
                disabled={parsing}
                style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: parsing ? 0.5 : 1, cursor: parsing ? 'not-allowed' : 'pointer' }}
              >
                {parsing ? '解析中…' : '解析指令'}
              </button>
            </div>
            {parseError && <div style={{ fontSize: 10, color: 'var(--crit)', marginBottom: 4 }}>⚠ {parseError}</div>}
            {parsed && (
              <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3, padding: '6px 0' }}>
                <div>动作：<span style={{ color: 'var(--text)', fontWeight: 'bold' }}>{pick(parsed, 'action') || '--'}</span></div>
                <div>目标：<span style={{ color: 'var(--cyan)' }}>{pick(parsed, 'target') || '--'}</span></div>
                <div>参数：<span style={{ color: 'var(--text)' }}>{JSON.stringify(pick(parsed, 'parameters', 'params') || {})}</span></div>
                <div>优先级：
                  <span style={{ color: PRIORITY_META[pick(parsed, 'priority')]?.color || 'var(--dim)' }}>
                    {PRIORITY_META[pick(parsed, 'priority')]?.label || pick(parsed, 'priority') || '--'}
                  </span>
                </div>
                <div>置信度：<span style={{ color: 'var(--text)' }}>{pick(parsed, 'confidence') != null ? `${Number(pick(parsed, 'confidence')).toFixed(2)}` : '--'}</span></div>
              </div>
            )}
            {parsed && (
              <button
                onClick={handleExecute}
                disabled={executing}
                style={{ ...miniBtnStyle, padding: '4px 12px', border: '1px solid var(--ok)', color: 'var(--ok)', cursor: executing ? 'not-allowed' : 'pointer', opacity: executing ? 0.5 : 1 }}
              >
                {executing ? '执行中…' : '执行指令'}
              </button>
            )}
            {execError && <div style={{ fontSize: 10, color: 'var(--crit)', marginTop: 4 }}>⚠ {execError}</div>}
            {execResult && (
              <div style={{ fontSize: 10, color: 'var(--ok)', marginTop: 4, padding: '4px 6px', background: 'var(--bg-1)', borderRadius: 3 }}>
                执行结果：{JSON.stringify(execResult)}
              </div>
            )}
          </div>

          {/* 待确认指令 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>待确认指令（{pending.length}）</span>
            </div>
            <div style={{ maxHeight: 200, overflowY: 'auto' }}>
              {pending.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无待确认指令</div>
              ) : (
                pending.map((p, i) => {
                  const pid = pick(p, 'pendingId', 'id')
                  const priority = pick(p, 'priority')
                  const meta = PRIORITY_META[priority] || { color: 'var(--dim)', label: priority || '--' }
                  return (
                    <div key={pid != null ? pid : i} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: meta.color, fontWeight: 'bold' }}>[{meta.label}]</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {pick(p, 'action') || '--'} → {pick(p, 'target') || '--'}
                          </span>
                        </div>
                        <button
                          onClick={() => handleConfirm(pid)}
                          style={{ ...miniBtnStyle, fontSize: 9, padding: '0 6px', color: 'var(--ok)', borderColor: 'var(--ok)' }}
                        >
                          确认执行
                        </button>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>{pick(p, 'originalText', 'text') || '--'}</div>
                    </div>
                  )
                })
              )}
            </div>
          </div>
        </div>

        {/* 右列：播报 + 告警 + 状态 + 历史 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 语音播报 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>语音播报</div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <input
                type="number"
                placeholder="sysid（可选）"
                value={broadcastSysid}
                onChange={(e) => setBroadcastSysid(e.target.value)}
                style={{ ...modalInputStyle, width: 120, flex: '0 0 120px' }}
              />
              <input
                type="text"
                placeholder="播报内容"
                value={broadcastText}
                onChange={(e) => setBroadcastText(e.target.value)}
                style={{ ...modalInputStyle, flex: '1 1 160px' }}
              />
              <button
                onClick={handleBroadcast}
                disabled={broadcasting}
                style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: broadcasting ? 0.5 : 1, cursor: broadcasting ? 'not-allowed' : 'pointer' }}
              >
                {broadcasting ? '播报中…' : '播报'}
              </button>
            </div>
            {broadcastResult && (
              <div style={{ fontSize: 10, color: broadcastResult.error ? 'var(--crit)' : 'var(--ok)', padding: '4px 6px', background: 'var(--bg-1)', borderRadius: 3 }}>
                {broadcastResult.error ? `⚠ ${broadcastResult.error}` : '播报成功'}
              </div>
            )}
          </div>

          {/* 告警播报 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>告警播报</div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <input
                type="number"
                placeholder="sysid（可选）"
                value={alertSysid}
                onChange={(e) => setAlertSysid(e.target.value)}
                style={{ ...modalInputStyle, width: 120, flex: '0 0 120px' }}
              />
              <input
                type="text"
                placeholder="告警内容"
                value={alertText}
                onChange={(e) => setAlertText(e.target.value)}
                style={{ ...modalInputStyle, flex: '1 1 160px' }}
              />
              <button
                onClick={handleAlert}
                disabled={alerting}
                style={{ ...miniBtnStyle, color: 'var(--crit)', borderColor: 'var(--crit)', opacity: alerting ? 0.5 : 1, cursor: alerting ? 'not-allowed' : 'pointer' }}
              >
                {alerting ? '告警中…' : '告警播报'}
              </button>
            </div>
          </div>

          {/* 播报状态 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>播报状态</div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <input
                type="number"
                placeholder="sysid"
                value={statusSysid}
                onChange={(e) => setStatusSysid(e.target.value)}
                style={{ ...modalInputStyle, width: 90, flex: '0 0 90px' }}
              />
              <button
                onClick={handleQueryStatus}
                disabled={statusLoading}
                style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: statusLoading ? 0.5 : 1, cursor: statusLoading ? 'not-allowed' : 'pointer' }}
              >
                {statusLoading ? '查询中…' : '查询状态'}
              </button>
            </div>
            {voiceStatus && (
              <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3 }}>
                <div>状态：<span style={{ color: 'var(--text)' }}>{pick(voiceStatus, 'status', 'state') || '--'}</span></div>
                <div>当前播报：<span style={{ color: 'var(--text)' }}>{pick(voiceStatus, 'currentMessage', 'message') || '--'}</span></div>
                {voiceStatus.error && <div style={{ color: 'var(--crit)' }}>⚠ {voiceStatus.error}</div>}
              </div>
            )}
          </div>

          {/* 指令历史 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>指令历史（{history.length}）</span>
            </div>
            <div style={{ maxHeight: 200, overflowY: 'auto' }}>
              {history.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无指令历史</div>
              ) : (
                history.map((h, i) => {
                  const priority = pick(h, 'priority')
                  const meta = PRIORITY_META[priority] || { color: 'var(--dim)', label: priority || '--' }
                  return (
                    <div key={i} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: meta.color, fontWeight: 'bold' }}>[{meta.label}]</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {pick(h, 'action') || '--'} → {pick(h, 'target') || '--'}
                          </span>
                        </div>
                        <span style={{ fontSize: 9, color: 'var(--dim-2)', flexShrink: 0, fontFamily: 'var(--mono)' }}>{fmtTime(pick(h, 'timestamp', 'ts'))}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>{pick(h, 'originalText', 'text') || '--'}</div>
                    </div>
                  )
                })
              )}
            </div>
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