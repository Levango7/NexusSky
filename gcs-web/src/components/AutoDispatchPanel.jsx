import React, { useState, useEffect, useCallback, useRef } from 'react'
import {
  triggerAutoDispatch,
  getAutoDispatchHistory,
  getAutoDispatchActive,
  abortAutoDispatch,
  getAutoDispatchConfig,
  updateAutoDispatchConfig,
  getVideoStreamUrl,
  startVideoStream,
  stopVideoStream,
  getActiveVideoStreams,
  startVoiceIntercom,
  stopVoiceIntercom,
  broadcastVoice,
} from '../api.js'
import { toArray, pick, fmtTime, POLL_MS } from '../utils/panelUtils.js'

// 自动出警面板（P0）
// 自动出警配置 + 手动触发出警 + 出警历史 + 进行中任务 + 视频流管理 + 语音对讲
// 风格与 TrackingPanel / GeofencePanel / DroneLockPanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 5s；AbortController 竞态守卫
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（AbortController 竞态守卫）


// 出警状态 → 颜色 / 标签
const DISPATCH_STATUS = {
  PENDING: { color: 'var(--warn)', label: '待命' },
  DISPATCHING: { color: 'var(--cyan)', label: '出警中' },
  EN_ROUTE: { color: 'var(--cyan)', label: '前往中' },
  ON_SCENE: { color: 'var(--cyan)', label: '已到场' },
  RETURNING: { color: 'var(--dim)', label: '返航中' },
  COMPLETED: { color: 'var(--ok)', label: '已完成' },
  ABORTED: { color: 'var(--crit)', label: '已中止' },
  FAILED: { color: 'var(--crit)', label: '失败' },
}

function statusMeta(s) {
  return DISPATCH_STATUS[s] || { color: 'var(--dim)', label: s || '--' }
}

export default function AutoDispatchPanel() {
  // ---- 出警配置 ----
  const [config, setConfig] = useState(null)
  const [configEditing, setConfigEditing] = useState(null)
  const [configSaving, setConfigSaving] = useState(false)

  // ---- 进行中出警任务 ----
  const [activeDispatches, setActiveDispatches] = useState([])
  // ---- 出警历史 ----
  const [history, setHistory] = useState([])
  // ---- 活跃视频流 ----
  const [activeStreams, setActiveStreams] = useState([])

  const [error, setError] = useState(null)
  const [formError, setFormError] = useState(null)

  // ---- 手动触发出警表单 ----
  const [triggerForm, setTriggerForm] = useState({
    lat: '',
    lon: '',
    alarmId: '',
    droneCount: '1',
  })
  const [triggering, setTriggering] = useState(false)
  const [triggerResult, setTriggerResult] = useState(null)

  // ---- 视频流 / 语音对讲操作 ----
  const [streamSysid, setStreamSysid] = useState('')
  const [streamUrl, setStreamUrl] = useState(null)
  const [streamLoading, setStreamLoading] = useState(false)
  const [voiceSysid, setVoiceSysid] = useState('')
  const [broadcastText, setBroadcastText] = useState('')
  const [voiceBusy, setVoiceBusy] = useState(null) // 'start' | 'broadcast' | null
  const [abortingId, setAbortingId] = useState(null)

  // ---- 轮询进行中任务 + 历史 + 活跃流 ----
  useEffect(() => {
    const controller = new AbortController()
    let cancelled = false

    const load = async () => {
      if (controller.signal.aborted) return
      try {
        const [activeData, historyData, streamsData] = await Promise.all([
          getAutoDispatchActive().catch(() => []),
          getAutoDispatchHistory().catch(() => []),
          getActiveVideoStreams().catch(() => []),
        ])
        if (cancelled || controller.signal.aborted) return
        setActiveDispatches(toArray(activeData, 'dispatches'))
        setHistory(toArray(historyData, 'dispatches'))
        setActiveStreams(toArray(streamsData, 'streams'))
      } catch (e) {
        // 静默失败，下一轮轮询会重试
      }
    }

    load()
    const timer = setInterval(load, POLL_MS)
    return () => {
      cancelled = true
      controller.abort()
      clearInterval(timer)
    }
  }, [])

  // ---- 加载出警配置（仅一次）----
  useEffect(() => {
    const controller = new AbortController()
    let cancelled = false

    const load = async () => {
      try {
        const cfg = await getAutoDispatchConfig()
        if (cancelled || controller.signal.aborted) return
        setConfig(cfg)
        setConfigEditing(cfg || {})
      } catch (e) {
        if (cancelled || controller.signal.aborted) return
        // 配置加载失败不阻塞面板
      }
    }
    load()
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [])

  // ---- 保存配置 ----
  const handleSaveConfig = useCallback(async () => {
    setConfigSaving(true)
    setError(null)
    try {
      const cfg = await updateAutoDispatchConfig(configEditing)
      setConfig(cfg)
    } catch (e) {
      setError('保存配置失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setConfigSaving(false)
    }
  }, [configEditing])

  // ---- 手动触发出警 ----
  const handleTrigger = useCallback(async (e) => {
    if (e && e.preventDefault) e.preventDefault()
    setFormError(null)
    const lat = Number(triggerForm.lat)
    const lon = Number(triggerForm.lon)
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
      setFormError('经纬度必须为有效数字')
      return
    }
    const droneCount = Math.max(1, Math.min(20, Number(triggerForm.droneCount) || 1))
    const payload = {
      lat,
      lon,
      alarmId: triggerForm.alarmId.trim() || undefined,
      droneCount,
    }
    setTriggering(true)
    try {
      const result = await triggerAutoDispatch(payload)
      setTriggerResult(result)
      setTriggerForm((prev) => ({ ...prev, lat: '', lon: '', alarmId: '' }))
    } catch (e) {
      setFormError('触发出警失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setTriggering(false)
    }
  }, [triggerForm])

  // ---- 中止出警 ----
  const handleAbort = useCallback(async (dispatchId) => {
    if (!window.confirm(`确认中止出警任务 #${dispatchId}？`)) return
    setAbortingId(dispatchId)
    setError(null)
    try {
      await abortAutoDispatch(dispatchId)
      setActiveDispatches((prev) => prev.filter((d) => pick(d, 'dispatchId', 'id') !== dispatchId))
    } catch (e) {
      setError('中止出警失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setAbortingId(null)
    }
  }, [])

  // ---- 视频流操作 ----
  const handleGetStreamUrl = useCallback(async () => {
    const sysid = Number(streamSysid)
    if (!Number.isFinite(sysid)) {
      setError('请输入有效的 sysid')
      return
    }
    setStreamLoading(true)
    setError(null)
    try {
      const result = await getVideoStreamUrl(sysid)
      setStreamUrl(result)
    } catch (e) {
      setError('获取视频流失败：' + (e && e.message ? e.message : String(e)))
      setStreamUrl(null)
    } finally {
      setStreamLoading(false)
    }
  }, [streamSysid])

  const handleStreamAction = useCallback(async (action) => {
    const sysid = Number(streamSysid)
    if (!Number.isFinite(sysid)) {
      setError('请输入有效的 sysid')
      return
    }
    setStreamLoading(true)
    setError(null)
    try {
      if (action === 'start') {
        await startVideoStream(sysid)
      } else {
        await stopVideoStream(sysid)
      }
    } catch (e) {
      setError(`${action === 'start' ? '启动' : '停止'}视频流失败：` + (e && e.message ? e.message : String(e)))
    } finally {
      setStreamLoading(false)
    }
  }, [streamSysid])

  // ---- 语音对讲操作 ----
  const handleVoiceAction = useCallback(async (action) => {
    const sysid = Number(voiceSysid)
    if (!Number.isFinite(sysid)) {
      setError('请输入有效的 sysid')
      return
    }
    setVoiceBusy(action === 'start' ? 'start' : 'broadcast')
    setError(null)
    try {
      if (action === 'start') {
        await startVoiceIntercom(sysid)
      } else if (action === 'stop') {
        await stopVoiceIntercom(sysid)
      } else if (action === 'broadcast') {
        if (!broadcastText.trim()) {
          setError('请输入广播内容')
          return
        }
        await broadcastVoice(sysid, { text: broadcastText.trim() })
        setBroadcastText('')
      }
    } catch (e) {
      setError(`语音操作失败：` + (e && e.message ? e.message : String(e)))
    } finally {
      setVoiceBusy(null)
    }
  }, [voiceSysid, broadcastText])

  // ---- 配置表单字段更新 ----
  const updateConfigField = useCallback((key, value) => {
    setConfigEditing((prev) => ({ ...prev, [key]: value }))
  }, [])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        自动出警
        <span style={{ fontSize: 10, color: 'var(--dim)', marginLeft: 8 }}>
          进行中 {activeDispatches.length} · 历史 {history.length}
        </span>
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* ===== 左列：配置 + 手动触发 + 进行中任务 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {/* 自动出警配置 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4 }}>
              出警配置
            </div>
            {configEditing ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <label style={labelStyle}>
                  <span>启用自动出警</span>
                  <input
                    type="checkbox"
                    checked={!!configEditing.enabled}
                    onChange={(e) => updateConfigField('enabled', e.target.checked)}
                  />
                </label>
                <label style={labelStyle}>
                  <span>最小电量（%）</span>
                  <input
                    type="number"
                    min="0"
                    max="100"
                    value={configEditing.minBatteryPct != null ? configEditing.minBatteryPct : ''}
                    onChange={(e) => updateConfigField('minBatteryPct', Number(e.target.value))}
                    style={inputStyle}
                    placeholder="例如 30"
                  />
                </label>
                <label style={labelStyle}>
                  <span>最大距离（m）</span>
                  <input
                    type="number"
                    min="0"
                    value={configEditing.maxDistanceM != null ? configEditing.maxDistanceM : ''}
                    onChange={(e) => updateConfigField('maxDistanceM', Number(e.target.value))}
                    style={inputStyle}
                    placeholder="例如 5000"
                  />
                </label>
                <label style={labelStyle}>
                  <span>默认无人机数</span>
                  <input
                    type="number"
                    min="1"
                    max="20"
                    value={configEditing.defaultDroneCount != null ? configEditing.defaultDroneCount : ''}
                    onChange={(e) => updateConfigField('defaultDroneCount', Number(e.target.value))}
                    style={inputStyle}
                    placeholder="例如 1"
                  />
                </label>
                <button
                  onClick={handleSaveConfig}
                  disabled={configSaving}
                  style={{
                    ...miniBtnStyle,
                    color: 'var(--cyan)', borderColor: 'var(--cyan)',
                    padding: '4px 12px', alignSelf: 'flex-start',
                    cursor: configSaving ? 'not-allowed' : 'pointer',
                    opacity: configSaving ? 0.5 : 1,
                  }}
                >
                  {configSaving ? '保存中…' : '保存配置'}
                </button>
              </div>
            ) : (
              <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>加载配置中…</div>
            )}
          </div>

          {/* 手动触发出警 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4 }}>
              手动触发出警
            </div>
            {formError && (
              <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 6, padding: '3px 6px', background: 'var(--bg-1)', borderRadius: 3, border: '1px solid var(--crit)' }}>
                ⚠ {formError}
              </div>
            )}
            <form onSubmit={handleTrigger} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 120px' }}>
                  <div style={labelStyle}>纬度</div>
                  <input
                    type="number"
                    step="any"
                    value={triggerForm.lat}
                    onChange={(e) => setTriggerForm((p) => ({ ...p, lat: e.target.value }))}
                    style={inputStyle}
                    placeholder="30.12345"
                  />
                </div>
                <div style={{ flex: '1 1 120px' }}>
                  <div style={labelStyle}>经度</div>
                  <input
                    type="number"
                    step="any"
                    value={triggerForm.lon}
                    onChange={(e) => setTriggerForm((p) => ({ ...p, lon: e.target.value }))}
                    style={inputStyle}
                    placeholder="120.12345"
                  />
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 140px' }}>
                  <div style={labelStyle}>报警ID（可选）</div>
                  <input
                    type="text"
                    value={triggerForm.alarmId}
                    onChange={(e) => setTriggerForm((p) => ({ ...p, alarmId: e.target.value }))}
                    style={inputStyle}
                    placeholder="ALARM-001"
                  />
                </div>
                <div style={{ flex: '0 0 100px' }}>
                  <div style={labelStyle}>无人机数</div>
                  <input
                    type="number"
                    min="1"
                    max="20"
                    value={triggerForm.droneCount}
                    onChange={(e) => setTriggerForm((p) => ({ ...p, droneCount: e.target.value }))}
                    style={inputStyle}
                  />
                </div>
              </div>
              <button
                type="submit"
                disabled={triggering}
                style={{
                  ...miniBtnStyle,
                  padding: '4px 12px', alignSelf: 'flex-start',
                  color: 'var(--warn)', borderColor: 'var(--warn)',
                  cursor: triggering ? 'not-allowed' : 'pointer',
                  opacity: triggering ? 0.5 : 1,
                }}
              >
                {triggering ? '触发中…' : '🚨 触发出警'}
              </button>
            </form>
            {triggerResult && (
              <div style={{ fontSize: 10, color: 'var(--ok)', marginTop: 6, padding: '4px 6px', background: 'var(--bg-1)', borderRadius: 3, border: '1px solid var(--ok)' }}>
                ✓ 出警已触发 · ID：{pick(triggerResult, 'dispatchId', 'id') || '--'}
              </div>
            )}
          </div>

          {/* 进行中出警任务 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
              进行中出警任务（{activeDispatches.length}）
            </div>
            <div style={{ maxHeight: 280, overflowY: 'auto' }}>
              {activeDispatches.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无进行中任务</div>
              ) : (
                activeDispatches.map((d, i) => {
                  const did = pick(d, 'dispatchId', 'id', 'taskId') ?? i
                  const st = pick(d, 'status', 'state') || 'PENDING'
                  const meta = statusMeta(st)
                  const isAborting = abortingId === did
                  return (
                    <div key={did} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 11, fontWeight: 'bold', color: meta.color, flexShrink: 0 }}>#{did}</span>
                          <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${meta.color}`, color: meta.color, flexShrink: 0 }}>
                            {meta.label}
                          </span>
                        </div>
                        <button
                          onClick={() => handleAbort(did)}
                          disabled={isAborting}
                          style={{
                            ...miniBtnStyle, fontSize: 9, padding: '0 6px',
                            color: 'var(--crit)', borderColor: 'var(--crit)',
                            cursor: isAborting ? 'not-allowed' : 'pointer', opacity: isAborting ? 0.5 : 1,
                          }}
                        >
                          {isAborting ? '中止中…' : '中止'}
                        </button>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        <span>无人机：{pick(d, 'droneCount', 'drones') || '--'}</span>
                        {pick(d, 'lat') != null && pick(d, 'lon') != null && (
                          <span>位置：{Number(pick(d, 'lat')).toFixed(4)}, {Number(pick(d, 'lon')).toFixed(4)}</span>
                        )}
                        <span>时间：{fmtTime(pick(d, 'triggeredAt', 'createdAt', 'timestamp'))}</span>
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>
        </div>

        {/* ===== 右列：视频流 + 语音对讲 + 出警历史 ===== */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>

          {/* 视频流管理 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4 }}>
              视频流管理
            </div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <input
                type="number"
                placeholder="sysid"
                value={streamSysid}
                onChange={(e) => setStreamSysid(e.target.value)}
                style={{ ...inputStyle, width: 90, flex: '0 0 90px' }}
              />
              <button
                onClick={() => handleStreamAction('start')}
                disabled={streamLoading}
                style={{ ...miniBtnStyle, color: 'var(--ok)', borderColor: 'var(--ok)', opacity: streamLoading ? 0.5 : 1, cursor: streamLoading ? 'not-allowed' : 'pointer' }}
              >
                启动
              </button>
              <button
                onClick={() => handleStreamAction('stop')}
                disabled={streamLoading}
                style={{ ...miniBtnStyle, color: 'var(--crit)', borderColor: 'var(--crit)', opacity: streamLoading ? 0.5 : 1, cursor: streamLoading ? 'not-allowed' : 'pointer' }}
              >
                停止
              </button>
              <button
                onClick={handleGetStreamUrl}
                disabled={streamLoading}
                style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: streamLoading ? 0.5 : 1, cursor: streamLoading ? 'not-allowed' : 'pointer' }}
              >
                {streamLoading ? '…' : '获取地址'}
              </button>
            </div>
            {streamUrl && (
              <div style={{ fontSize: 9, color: 'var(--dim-2)', fontFamily: 'var(--mono)', padding: '4px 6px', background: 'var(--bg-1)', borderRadius: 3, wordBreak: 'break-all' }}>
                {pick(streamUrl, 'url', 'streamUrl', 'rtspUrl') || JSON.stringify(streamUrl)}
              </div>
            )}
            {activeStreams.length > 0 && (
              <div style={{ marginTop: 6 }}>
                <div style={{ fontSize: 9, color: 'var(--dim-2)', marginBottom: 3 }}>活跃流（{activeStreams.length}）</div>
                {activeStreams.map((s, i) => (
                  <div key={i} style={{ fontSize: 9, color: 'var(--dim)', padding: '2px 0' }}>
                    <span style={{ color: 'var(--cyan)' }}>#{pick(s, 'sysid', 'id') ?? i}</span>
                    {' '}
                    {pick(s, 'status', 'state') || '--'}
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* 语音对讲 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 8, borderBottom: '1px solid var(--line-2)', paddingBottom: 4 }}>
              语音对讲 / 广播喊话
            </div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <input
                type="number"
                placeholder="sysid"
                value={voiceSysid}
                onChange={(e) => setVoiceSysid(e.target.value)}
                style={{ ...inputStyle, width: 90, flex: '0 0 90px' }}
              />
              <button
                onClick={() => handleVoiceAction('start')}
                disabled={voiceBusy != null}
                style={{ ...miniBtnStyle, color: 'var(--ok)', borderColor: 'var(--ok)', opacity: voiceBusy ? 0.5 : 1, cursor: voiceBusy ? 'not-allowed' : 'pointer' }}
              >
                开始对讲
              </button>
              <button
                onClick={() => handleVoiceAction('stop')}
                disabled={voiceBusy != null}
                style={{ ...miniBtnStyle, color: 'var(--crit)', borderColor: 'var(--crit)', opacity: voiceBusy ? 0.5 : 1, cursor: voiceBusy ? 'not-allowed' : 'pointer' }}
              >
                停止对讲
              </button>
            </div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'flex-start', flexWrap: 'wrap' }}>
              <textarea
                value={broadcastText}
                onChange={(e) => setBroadcastText(e.target.value)}
                style={{ ...inputStyle, minHeight: 50, resize: 'vertical', flex: '1 1 200px' }}
                placeholder="输入广播喊话内容…"
                rows={2}
              />
              <button
                onClick={() => handleVoiceAction('broadcast')}
                disabled={voiceBusy != null}
                style={{ ...miniBtnStyle, color: 'var(--warn)', borderColor: 'var(--warn)', padding: '4px 10px', opacity: voiceBusy ? 0.5 : 1, cursor: voiceBusy ? 'not-allowed' : 'pointer' }}
              >
                {voiceBusy === 'broadcast' ? '广播中…' : '📢 广播'}
              </button>
            </div>
          </div>

          {/* 出警历史 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', fontSize: 11, color: 'var(--dim)' }}>
              出警历史（{history.length}）
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {history.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无出警历史</div>
              ) : (
                history.map((d, i) => {
                  const did = pick(d, 'dispatchId', 'id', 'taskId') ?? i
                  const st = pick(d, 'status', 'state') || 'COMPLETED'
                  const meta = statusMeta(st)
                  return (
                    <div key={did} style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 11, fontWeight: 'bold', color: meta.color }}>#{did}</span>
                        <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 2, border: `1px solid ${meta.color}`, color: meta.color }}>
                          {meta.label}
                        </span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        <span>无人机：{pick(d, 'droneCount', 'drones') || '--'}</span>
                        <span>时间：{fmtTime(pick(d, 'triggeredAt', 'createdAt', 'timestamp', 'completedAt'))}</span>
                      </div>
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

// ===== 内联样式（与 TrackingPanel / GeofencePanel 保持一致）=====
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
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
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

const inputStyle = {
  width: '100%',
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-1)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
  boxSizing: 'border-box',
}