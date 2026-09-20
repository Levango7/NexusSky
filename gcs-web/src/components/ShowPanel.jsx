import React, { useState, useEffect, useCallback } from 'react'
import {
  createShowFormation,
  listShowFormations,
  getShowFormation,
  calculateShowPositions,
  createShowTask,
  listShowTasks,
  getShowTask,
  startShowTask,
  abortShowTask,
  getShowActions,
  configureShowMusicSync,
} from '../api.js'

// P4 编队表演面板
// 队形定义管理 + 队形位置计算 + 表演任务管理 + 动作序列 + 音乐同步

const POLL_MS = 5000

const FORMATION_TYPES = [
  { key: 'LINE', label: '直线' },
  { key: 'CIRCLE', label: '圆形' },
  { key: 'GRID', label: '网格' },
  { key: 'V_SHAPE', label: 'V字形' },
  { key: 'DIAMOND', label: '菱形' },
  { key: 'SPIRAL', label: '螺旋' },
  { key: 'HEART', label: '心形' },
  { key: 'STAR', label: '星形' },
]

const SHOW_STATUS_META = {
  CREATED: { color: 'var(--dim)', label: '已创建' },
  DEPLOYING: { color: 'var(--cyan)', label: '部署中' },
  PERFORMING: { color: 'var(--warn)', label: '表演中' },
  COMPLETED: { color: 'var(--ok)', label: '已完成' },
  ABORTED: { color: 'var(--crit)', label: '已中止' },
}

const ACTION_TYPE_META = {
  TAKEOFF: { color: 'var(--ok)', label: '起飞' },
  MOVE: { color: 'var(--cyan)', label: '移动' },
  TRANSFORM: { color: 'var(--warn)', label: '变换' },
  LIGHTS: { color: 'var(--cyan)', label: '灯光' },
  LAND: { color: 'var(--dim)', label: '降落' },
}

function fmtTime(ts) {
  if (ts == null || ts === '') return '--'
  const n = Number(ts)
  if (!Number.isFinite(n)) return String(ts)
  return new Date(n).toLocaleString('zh-CN', { hour12: false })
}

function pick(obj, ...keys) {
  if (!obj) return null
  for (const k of keys) {
    if (obj[k] != null) return obj[k]
  }
  return null
}

export default function ShowPanel() {
  const [formations, setFormations] = useState([])
  const [tasks, setTasks] = useState([])
  const [error, setError] = useState(null)
  const [selectedFormationId, setSelectedFormationId] = useState(null)
  const [selectedTaskId, setSelectedTaskId] = useState(null)
  const [positions, setPositions] = useState(null)
  const [actions, setActions] = useState([])
  const [taskDetail, setTaskDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [startingTask, setStartingTask] = useState(false)
  const [abortingTask, setAbortingTask] = useState(false)
  const [calculatingPos, setCalculatingPos] = useState(false)

  const [formForm, setFormForm] = useState({
    type: 'LINE',
    name: '',
    droneCount: '',
    spacingM: '',
  })
  const [formFormError, setFormFormError] = useState(null)
  const [creatingFormation, setCreatingFormation] = useState(false)

  const [taskForm, setTaskForm] = useState({
    formationId: '',
    name: '',
  })
  const [taskFormError, setTaskFormError] = useState(null)
  const [creatingTask, setCreatingTask] = useState(false)

  const [musicForm, setMusicForm] = useState({ bpm: '', musicUrl: '', startTimeOffsetSec: '' })
  const [musicSaving, setMusicSaving] = useState(false)
  const [musicError, setMusicError] = useState(null)

  useEffect(() => {
    const controller = new AbortController()
    let timer = null
    let stopped = false
    const load = async () => {
      try {
        const [fData, tData] = await Promise.all([
          listShowFormations().catch(() => []),
          listShowTasks().catch(() => []),
        ])
        if (controller.signal.aborted || stopped) return
        setFormations(Array.isArray(fData) ? fData : (fData && fData.formations) || [])
        setTasks(Array.isArray(tData) ? tData : (tData && tData.tasks) || [])
        setError(null)
      } catch (e) {
        if (controller.signal.aborted || stopped) return
        setError(e && e.message ? e.message : String(e))
      }
    }
    load()
    timer = setInterval(load, POLL_MS)
    return () => { stopped = true; controller.abort(); clearInterval(timer) }
  }, [])

  useEffect(() => {
    if (selectedTaskId == null) { setTaskDetail(null); setActions([]); return }
    let cancelled = false
    const load = async () => {
      setDetailLoading(true)
      try {
        const [detail, actionsData] = await Promise.all([
          getShowTask(selectedTaskId),
          getShowActions(selectedTaskId).catch(() => []),
        ])
        if (cancelled) return
        setTaskDetail(detail)
        setActions(Array.isArray(actionsData) ? actionsData : (actionsData && actionsData.actions) || [])
      } catch (e) {
        if (cancelled) return
        setError(e && e.message ? e.message : String(e))
      } finally {
        if (!cancelled) setDetailLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [selectedTaskId])

  const handleCreateFormation = useCallback(async () => {
    setFormFormError(null)
    const droneCount = Number(formForm.droneCount)
    const spacingM = Number(formForm.spacingM)
    if (!formForm.name.trim()) {
      setFormFormError('队形名称为必填项')
      return
    }
    if (!Number.isFinite(droneCount) || droneCount <= 0) {
      setFormFormError('无人机数量必须为正整数')
      return
    }
    if (!Number.isFinite(spacingM) || spacingM <= 0) {
      setFormFormError('间距必须为正数')
      return
    }
    setCreatingFormation(true)
    try {
      await createShowFormation({
        type: formForm.type,
        name: formForm.name.trim(),
        droneCount,
        spacingM,
      })
      setFormForm((prev) => ({ ...prev, name: '', droneCount: '', spacingM: '' }))
    } catch (e) {
      setFormFormError('创建队形失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setCreatingFormation(false)
    }
  }, [formForm])

  const handleCalculatePositions = useCallback(async () => {
    if (selectedFormationId == null) return
    setCalculatingPos(true)
    try {
      // 从选中的队形定义中获取 droneCount
      const formation = formations.find((f) => pick(f, 'id', 'formationId') === selectedFormationId)
      const droneCount = formation ? pick(formation, 'droneCount') : undefined
      const data = await calculateShowPositions(selectedFormationId, { droneCount })
      setPositions(data)
    } catch (e) {
      setError('计算位置失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setCalculatingPos(false)
    }
  }, [selectedFormationId, formations])

  const handleCreateTask = useCallback(async () => {
    setTaskFormError(null)
    const formationId = taskForm.formationId.trim()
    if (!formationId) {
      setTaskFormError('队形ID不能为空')
      return
    }
    if (!taskForm.name.trim()) {
      setTaskFormError('任务名称为必填项')
      return
    }
    setCreatingTask(true)
    try {
      await createShowTask({
        formationId,
        name: taskForm.name.trim(),
      })
      setTaskForm((prev) => ({ ...prev, formationId: '', name: '' }))
    } catch (e) {
      setTaskFormError('创建任务失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setCreatingTask(false)
    }
  }, [taskForm])

  const handleStartTask = useCallback(async () => {
    if (selectedTaskId == null) return
    setStartingTask(true)
    try {
      await startShowTask(selectedTaskId)
    } catch (e) {
      setError('启动失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setStartingTask(false)
    }
  }, [selectedTaskId])

  const handleAbortTask = useCallback(async () => {
    if (selectedTaskId == null) return
    setAbortingTask(true)
    try {
      await abortShowTask(selectedTaskId)
    } catch (e) {
      setError('中止失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setAbortingTask(false)
    }
  }, [selectedTaskId])

  const handleMusicSync = useCallback(async () => {
    if (selectedTaskId == null) return
    setMusicSaving(true)
    setMusicError(null)
    try {
      const payload = {}
      if (musicForm.bpm) payload.bpm = Number(musicForm.bpm)
      if (musicForm.musicUrl) payload.musicUrl = musicForm.musicUrl
      if (musicForm.startTimeOffsetSec) payload.startTimeOffsetSec = Number(musicForm.startTimeOffsetSec)
      await configureShowMusicSync(selectedTaskId, payload)
    } catch (e) {
      setMusicError('配置失败：' + (e && e.message ? e.message : String(e)))
    } finally {
      setMusicSaving(false)
    }
  }, [selectedTaskId, musicForm])

  const updateFormForm = useCallback((k, v) => setFormForm((p) => ({ ...p, [k]: v })), [])
  const updateTaskForm = useCallback((k, v) => setTaskForm((p) => ({ ...p, [k]: v })), [])
  const updateMusicForm = useCallback((k, v) => setMusicForm((p) => ({ ...p, [k]: v })), [])

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>编队表演</h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左列：队形列表 + 创建队形 + 位置计算 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 队形列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>队形定义（{formations.length}）</span>
            </div>
            <div style={{ maxHeight: 240, overflowY: 'auto' }}>
              {formations.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无队形定义</div>
              ) : (
                formations.map((f, i) => {
                  const fid = pick(f, 'id', 'formationId')
                  const typeLabel = FORMATION_TYPES.find((ft) => ft.key === pick(f, 'type'))?.label || pick(f, 'type') || '--'
                  const isSel = fid === selectedFormationId
                  return (
                    <div
                      key={fid != null ? fid : i}
                      onClick={() => setSelectedFormationId(fid)}
                      style={{
                        padding: '6px 10px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-3, var(--bg-1))' : 'transparent',
                        borderLeft: `3px solid ${isSel ? 'var(--cyan)' : 'var(--dim)'}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: 'var(--cyan)', fontWeight: 'bold', flexShrink: 0 }}>#{fid != null ? fid : '?'}</span>
                          <span style={{ fontSize: 11, color: 'var(--text)' }}>{typeLabel}</span>
                        </div>
                        <span style={{ fontSize: 9, color: 'var(--dim-2)' }}>{pick(f, 'droneCount') != null ? `${pick(f, 'droneCount')} 架` : '--'}</span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2 }}>
                        间距：{pick(f, 'spacingM') != null ? `${Number(pick(f, 'spacingM')).toFixed(1)} m` : '--'}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 创建队形 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>创建队形定义</div>
            {formFormError && <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 4 }}>⚠ {formFormError}</div>}
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <div style={{ flex: '1 1 120px' }}>
                <div style={labelStyle}>队形类型</div>
                <select value={formForm.type} onChange={(e) => updateFormForm('type', e.target.value)} style={modalInputStyle}>
                  {FORMATION_TYPES.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
                </select>
              </div>
              <div style={{ flex: '1 1 120px' }}>
                <div style={labelStyle}>队形名称</div>
                <input type="text" value={formForm.name} onChange={(e) => updateFormForm('name', e.target.value)} style={modalInputStyle} placeholder="必填" />
              </div>
              <div style={{ flex: '1 1 80px' }}>
                <div style={labelStyle}>无人机数</div>
                <input type="number" min="1" value={formForm.droneCount} onChange={(e) => updateFormForm('droneCount', e.target.value)} style={modalInputStyle} placeholder="架数" />
              </div>
              <div style={{ flex: '1 1 80px' }}>
                <div style={labelStyle}>间距 (m)</div>
                <input type="number" step="any" value={formForm.spacingM} onChange={(e) => updateFormForm('spacingM', e.target.value)} style={modalInputStyle} placeholder="米" />
              </div>
            </div>
            <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
              <button onClick={handleCreateFormation} disabled={creatingFormation} style={{ ...miniBtnStyle, padding: '4px 12px', border: '1px solid var(--cyan)', color: 'var(--cyan)', cursor: creatingFormation ? 'not-allowed' : 'pointer', opacity: creatingFormation ? 0.5 : 1 }}>{creatingFormation ? '创建中…' : '创建队形'}</button>
              {selectedFormationId != null && (
                <button onClick={handleCalculatePositions} disabled={calculatingPos} style={{ ...miniBtnStyle, padding: '4px 12px', border: '1px solid var(--warn)', color: 'var(--warn)', cursor: calculatingPos ? 'not-allowed' : 'pointer', opacity: calculatingPos ? 0.5 : 1 }}>{calculatingPos ? '计算中…' : '计算位置'}</button>
              )}
            </div>
          </div>

          {/* 队形位置计算结果 */}
          {positions && (
            <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
              <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
                <span style={{ fontSize: 11, color: 'var(--text)' }}>队形位置（{Array.isArray(pick(positions, 'positions')) ? pick(positions, 'positions').length : 0}）</span>
              </div>
              <div style={{ maxHeight: 200, overflowY: 'auto' }}>
                {(Array.isArray(pick(positions, 'positions')) ? pick(positions, 'positions') : []).map((p, i) => (
                  <div key={i} style={{ fontSize: 9, color: 'var(--dim-2)', padding: '3px 10px', borderBottom: '1px solid var(--line-2)', fontFamily: 'var(--mono)' }}>
                    <span style={{ color: 'var(--cyan)' }}>#{pick(p, 'droneIndex', 'index') != null ? pick(p, 'droneIndex', 'index') : i}</span>
                    {' '}x:{pick(p, 'x', 'relX') != null ? Number(pick(p, 'x', 'relX')).toFixed(2) : '--'}
                    {' '}y:{pick(p, 'y', 'relY') != null ? Number(pick(p, 'y', 'relY')).toFixed(2) : '--'}
                    {' '}z:{pick(p, 'z', 'relZ') != null ? Number(pick(p, 'z', 'relZ')).toFixed(2) : '--'}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* 右列：表演任务 + 详情 + 动作序列 + 音乐同步 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 创建表演任务 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>创建表演任务</div>
            {taskFormError && <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 4 }}>⚠ {taskFormError}</div>}
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <div style={{ flex: '1 1 80px' }}>
                <div style={labelStyle}>队形ID</div>
                <input type="text" value={taskForm.formationId} onChange={(e) => updateTaskForm('formationId', e.target.value)} style={modalInputStyle} placeholder="ID" />
              </div>
              <div style={{ flex: '1 1 160px' }}>
                <div style={labelStyle}>任务名称</div>
                <input type="text" value={taskForm.name} onChange={(e) => updateTaskForm('name', e.target.value)} style={modalInputStyle} placeholder="必填" />
              </div>
              <button onClick={handleCreateTask} disabled={creatingTask} style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: creatingTask ? 0.5 : 1, cursor: creatingTask ? 'not-allowed' : 'pointer' }}>{creatingTask ? '创建中…' : '创建'}</button>
            </div>
          </div>

          {/* 表演任务列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
              <span style={{ fontSize: 11, color: 'var(--text)' }}>表演任务（{tasks.length}）</span>
            </div>
            <div style={{ maxHeight: 200, overflowY: 'auto' }}>
              {tasks.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 12, textAlign: 'center' }}>暂无表演任务</div>
              ) : (
                tasks.map((t, i) => {
                  const tid = pick(t, 'id', 'taskId')
                  const status = pick(t, 'status')
                  const statusMeta = SHOW_STATUS_META[status] || { color: 'var(--dim)', label: status || '--' }
                  const isSel = tid === selectedTaskId
                  return (
                    <div
                      key={tid != null ? tid : i}
                      onClick={() => setSelectedTaskId(tid)}
                      style={{
                        padding: '6px 10px', borderBottom: '1px solid var(--line-2)', cursor: 'pointer',
                        background: isSel ? 'var(--bg-3, var(--bg-1))' : 'transparent',
                        borderLeft: `3px solid ${isSel ? 'var(--cyan)' : statusMeta.color}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <span style={{ fontSize: 9, color: 'var(--cyan)', fontWeight: 'bold' }}>#{tid != null ? tid : '?'}</span>
                        <span style={{ fontSize: 11, color: 'var(--text)', flex: 1 }}>{pick(t, 'name') || '--'}</span>
                        <span style={{ fontSize: 9, padding: '1px 5px', borderRadius: 3, border: `1px solid ${statusMeta.color}`, color: statusMeta.color }}>{statusMeta.label}</span>
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 任务详情 + 操作 */}
          {selectedTaskId != null && (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span>任务详情 #{selectedTaskId}</span>
                <div style={{ display: 'flex', gap: 4 }}>
                  <button onClick={handleStartTask} disabled={startingTask} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--ok)', borderColor: 'var(--ok)', opacity: startingTask ? 0.5 : 1, cursor: startingTask ? 'not-allowed' : 'pointer' }}>{startingTask ? '启动中…' : '启动'}</button>
                  <button onClick={handleAbortTask} disabled={abortingTask} style={{ ...miniBtnStyle, fontSize: 9, color: 'var(--crit)', borderColor: 'var(--crit)', opacity: abortingTask ? 0.5 : 1, cursor: abortingTask ? 'not-allowed' : 'pointer' }}>{abortingTask ? '中止中…' : '中止'}</button>
                </div>
              </div>
              {detailLoading ? (
                <div style={{ fontSize: 10, color: 'var(--cyan)' }}>加载中…</div>
              ) : taskDetail ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', display: 'flex', flexDirection: 'column', gap: 3 }}>
                  <div>状态：<span style={{ color: SHOW_STATUS_META[pick(taskDetail, 'status')]?.color || 'var(--text)' }}>{SHOW_STATUS_META[pick(taskDetail, 'status')]?.label || pick(taskDetail, 'status') || '--'}</span></div>
                  <div>队形ID：{pick(taskDetail, 'formationId') || '--'}</div>
                  <div>无人机数：{pick(taskDetail, 'droneCount') || '--'}</div>
                </div>
              ) : (
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>暂无详情</div>
              )}
            </div>
          )}

          {/* 动作序列 */}
          {actions.length > 0 && (
            <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
              <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)' }}>
                <span style={{ fontSize: 11, color: 'var(--text)' }}>动作序列（{actions.length}）</span>
              </div>
              <div style={{ maxHeight: 160, overflowY: 'auto' }}>
                {actions.map((a, i) => {
                  const actionType = pick(a, 'type', 'actionType')
                  const meta = ACTION_TYPE_META[actionType] || { color: 'var(--dim)', label: actionType || '--' }
                  return (
                    <div key={i} style={{ padding: '4px 10px', borderBottom: '1px solid var(--line-2)', borderLeft: `3px solid ${meta.color}` }}>
                      <div style={{ fontSize: 9, display: 'flex', gap: 6, alignItems: 'center' }}>
                        <span style={{ color: meta.color, fontWeight: 'bold' }}>{meta.label}</span>
                        <span style={{ color: 'var(--dim-2)' }}>{pick(a, 'description', 'desc') || ''}</span>
                        <span style={{ color: 'var(--dim-2)', marginLeft: 'auto' }}>t={pick(a, 'startTime', 'time') != null ? `${pick(a, 'startTime', 'time')}s` : '--'}</span>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* 音乐同步配置 */}
          {selectedTaskId != null && (
            <div style={{ ...cardStyle, padding: 10 }}>
              <div style={{ fontSize: 11, color: 'var(--text)', marginBottom: 6 }}>音乐同步配置</div>
              {musicError && <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 4 }}>⚠ {musicError}</div>}
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 60px' }}>
                  <div style={labelStyle}>BPM</div>
                  <input type="number" value={musicForm.bpm} onChange={(e) => updateMusicForm('bpm', e.target.value)} style={modalInputStyle} placeholder="BPM" />
                </div>
                <div style={{ flex: '1 1 140px' }}>
                  <div style={labelStyle}>音乐URL</div>
                  <input type="text" value={musicForm.musicUrl} onChange={(e) => updateMusicForm('musicUrl', e.target.value)} style={modalInputStyle} placeholder="URL" />
                </div>
                <div style={{ flex: '1 1 80px' }}>
                  <div style={labelStyle}>偏移(s)</div>
                  <input type="number" value={musicForm.startTimeOffsetSec} onChange={(e) => updateMusicForm('startTimeOffsetSec', e.target.value)} style={modalInputStyle} placeholder="秒" />
                </div>
                <button onClick={handleMusicSync} disabled={musicSaving} style={{ ...miniBtnStyle, color: 'var(--cyan)', borderColor: 'var(--cyan)', opacity: musicSaving ? 0.5 : 1, cursor: musicSaving ? 'not-allowed' : 'pointer' }}>{musicSaving ? '保存中…' : '保存'}</button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

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