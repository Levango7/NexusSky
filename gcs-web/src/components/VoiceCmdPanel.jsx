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
import { POLL_MS, fmtTime, pick, cardStyle, labelStyle, miniBtnStyle, modalInputStyle } from '../utils/panelUtils.js'

// P3 语音指挥面板
// 语音指令解析 + 执行 + 待确认 + 播报 + 告警 + 历史
// 风格与 TrackingPanel / GeofencePanel 一致

// 优先级 → 颜色 / 标签
const PRIORITY_META = {
  LOW: { color: 'var(--ok)', label: '低' },
  MEDIUM: { color: 'var(--cyan)', label: '中' },
  NORMAL: { color: 'var(--ok)', label: '普通' },
  HIGH: { color: 'var(--warn)', label: '高' },
  CRITICAL: { color: 'var(--crit)', label: '紧急' },
}

// 执行状态 → 颜色 / 标签（对齐后端 ExecutionResult.Status）
const STATUS_META = {
  EXECUTED: { color: 'var(--ok)', label: '已执行' },
  PENDING_CONFIRMATION: { color: 'var(--warn)', label: '待确认' },
  REJECTED: { color: 'var(--crit)', label: '已拒绝' },
  FAILED: { color: 'var(--crit)', label: '失败' },
}



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
  const [alertSysid, setAlertSysid] = useState('')
  const [alerting, setAlerting] = useState(false)
  const [alertResult, setAlertResult] = useState(null)

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
        const historyList = Array.isArray(historyData) ? historyData : (historyData && historyData.history) || []
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
        targetName: pick(parsed, 'targetName', 'target'),
        sysid: pick(parsed, 'sysid') || 1,
        altitudeM: pick(parsed, 'altitudeM', 'altitude'),
        speedMps: pick(parsed, 'speedMps', 'speed'),
        targetLat: pick(parsed, 'targetLat'),
        targetLon: pick(parsed, 'targetLon'),
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
    if (!text) {
      setBroadcastResult({ error: '请输入播报内容' })
      return
    }
    const sysid = broadcastSysid.trim()
    if (!sysid) {
      setBroadcastResult({ error: '请输入 sysid' })
      return
    }
    setBroadcasting(true)
    setBroadcastResult(null)
    try {
      const data = await broadcastVoiceMessage(Number(sysid), { text })
      setBroadcastResult(data)
    } catch (e) {
      setBroadcastResult({ error: e && e.message ? e.message : String(e) })
    } finally {
      setBroadcasting(false)
    }
  }, [broadcastText, broadcastSysid])

  // ---- 告警播报 ----
  const handleAlert = useCallback(async () => {
    const sysid = alertSysid.trim()
    if (!sysid) {
      setAlertResult({ error: '请输入 sysid' })
      return
    }
    setAlerting(true)
    setAlertResult(null)
    try {
      const data = await broadcastVoiceAlert(Number(sysid))
      setAlertResult(data)
    } catch (e) {
      setAlertResult({ error: e && e.message ? e.message : String(e) })
    } finally {
      setAlerting(false)
    }
  }, [alertSysid])

  // ---- 查询播报状态 ----
  const handleQueryStatus = useCallback(async () => {
    const sysid = statusSysid.trim()
    if (!sysid) {
      setVoiceStatus({ error: '请输入 sysid' })
      return
    }
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
                aria-label="语音指令输入"
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
                <div>目标：<span style={{ color: 'var(--cyan)' }}>{pick(parsed, 'targetName', 'target') || '--'}</span></div>
                <div>高度：<span style={{ color: 'var(--text)' }}>{pick(parsed, 'altitudeM', 'altitude') != null ? `${pick(parsed, 'altitudeM', 'altitude')}m` : '--'}</span></div>
                <div>速度：<span style={{ color: 'var(--text)' }}>{pick(parsed, 'speedMps', 'speed') != null ? `${pick(parsed, 'speedMps', 'speed')}m/s` : '--'}</span></div>
                <div>优先级：
                  <span style={{ color: PRIORITY_META[pick(parsed, 'priority')]?.color || 'var(--dim)' }}>
                    {PRIORITY_META[pick(parsed, 'priority')]?.label || pick(parsed, 'priority') || '--'}
                  </span>
                </div>
                <div>置信度：<span style={{ color: 'var(--text)' }}>{pick(parsed, 'confidencePct', 'confidence') != null ? `${pick(parsed, 'confidencePct', 'confidence')}%` : '--'}</span></div>
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
                <div>状态：<span style={{ color: 'var(--text)' }}>{pick(execResult, 'status') || '--'}</span></div>
                <div>消息：<span style={{ color: 'var(--text)' }}>{pick(execResult, 'message') || '--'}</span></div>
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
                  const cmd = pick(p, 'command') || p
                  const priority = pick(cmd, 'priority')
                  const meta = PRIORITY_META[priority] || { color: 'var(--dim)', label: priority || '--' }
                  return (
                    <div key={pid != null ? pid : `pending-${i}`} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: meta.color, fontWeight: 'bold' }}>[{meta.label}]</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {pick(cmd, 'action') || '--'} → {pick(cmd, 'targetName', 'target') || '--'}
                          </span>
                        </div>
                        <button
                          onClick={() => handleConfirm(pid)}
                          style={{ ...miniBtnStyle, fontSize: 9, padding: '0 6px', color: 'var(--ok)', borderColor: 'var(--ok)' }}
                        >
                          确认执行
                        </button>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>{pick(cmd, 'rawText', 'originalText', 'text') || '--'}</div>
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
                aria-label="播报 sysid"
                placeholder="sysid（必填）"
                value={broadcastSysid}
                onChange={(e) => setBroadcastSysid(e.target.value)}
                style={{ ...modalInputStyle, width: 120, flex: '0 0 120px' }}
              />
              <input
                type="text"
                aria-label="播报内容"
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
                aria-label="告警 sysid"
                placeholder="sysid（必填）"
                value={alertSysid}
                onChange={(e) => setAlertSysid(e.target.value)}
                style={{ ...modalInputStyle, width: 120, flex: '0 0 120px' }}
              />
              <button
                onClick={handleAlert}
                disabled={alerting}
                style={{ ...miniBtnStyle, color: 'var(--crit)', borderColor: 'var(--crit)', opacity: alerting ? 0.5 : 1, cursor: alerting ? 'not-allowed' : 'pointer' }}
              >
                {alerting ? '告警中…' : '告警播报'}
              </button>
            </div>
            {alertResult && (
              <div style={{ fontSize: 10, color: alertResult.error ? 'var(--crit)' : 'var(--ok)', padding: '4px 6px', background: 'var(--bg-1)', borderRadius: 3 }}>
                {alertResult.error ? `⚠ ${alertResult.error}` : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                    <div>告警文本：<span style={{ color: 'var(--text)' }}>{pick(alertResult, 'text') || '--'}</span></div>
                    <div>告警类型：<span style={{ color: 'var(--text)' }}>{pick(alertResult, 'alertType') || '--'}</span></div>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* 播报状态 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>播报状态</div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <input
                type="number"
                aria-label="查询状态 sysid"
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
                <div>播报状态：<span style={{ color: 'var(--text)' }}>{pick(voiceStatus, 'text') || '--'}</span></div>
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
                  const status = pick(h, 'status')
                  const statusMeta = STATUS_META[status] || { color: 'var(--dim)', label: status || '--' }
                  return (
                    <div key={pick(h, 'commandId') ?? `cmd-${i}`} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${statusMeta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: statusMeta.color, fontWeight: 'bold' }}>[{statusMeta.label}]</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {pick(h, 'executedAction') || '--'}
                          </span>
                        </div>
                        <span style={{ fontSize: 9, color: 'var(--dim-2)', flexShrink: 0, fontFamily: 'var(--mono)' }}>{pick(h, 'commandId') || '--'}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>{pick(h, 'message') || '--'}</div>
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

