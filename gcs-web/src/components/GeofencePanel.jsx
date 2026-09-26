import React, { useState, useEffect, useCallback } from 'react'
import {
  createGeofenceZone,
  listGeofenceZones,
  deleteGeofenceZone,
  getGeofenceBreaches,
  checkGeofence,
} from '../api.js'
import { POLL_MS, fmtTime, cardStyle, labelStyle, miniBtnStyle, modalInputStyle } from '../utils/panelUtils.js'

// M12 电子围栏管理面板
// 围栏区域列表（CRUD） + 创建围栏表单（CIRCLE/POLYGON） + 越界历史 + 手动检查
// 风格与 AlarmPanel / SurveillancePanel 一致：卡片布局 + 内联 CSS + CSS 变量
// 轮询间隔 5s；AbortController 竞态守卫
// 经验来源：2026-09-16-useeffect-fetch-abortcontroller-race-guard（AbortController 竞态守卫）


// 围栏类型
const ZONE_TYPES = [
  { key: 'CIRCLE', label: '圆形' },
  { key: 'POLYGON', label: '多边形' },
]

// 围栏动作
const ZONE_ACTIONS = [
  { key: 'WARN', label: '警告' },
  { key: 'LOCK_RTH', label: '锁定返航' },
]

// 动作 → 颜色
const ACTION_COLOR = {
  WARN: 'var(--warn)',
  LOCK_RTH: 'var(--crit)',
}

// 越界类型 → 颜色 / 标签
const BREACH_META = {
  EXIT: { color: 'var(--crit)', label: '离开' },
  ENTER: { color: 'var(--ok)', label: '进入' },
}


// 解析多边形 points 文本框内容（每行一个 "lat,lon"）
// 返回 { points: [{lat, lon}, ...] | null, error: string | null }
function parsePoints(text) {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  if (lines.length < 3) {
    return { points: null, error: '多边形至少需要 3 个点' }
  }
  const points = []
  for (let i = 0; i < lines.length; i++) {
    const parts = lines[i].split(',').map((s) => s.trim())
    if (parts.length < 2) {
      return { points: null, error: `第 ${i + 1} 行格式错误，应为 "lat,lon"` }
    }
    const lat = Number(parts[0])
    const lon = Number(parts[1])
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
      return { points: null, error: `第 ${i + 1} 行坐标不是有效数字` }
    }
    points.push({ lat, lon })
  }
  return { points, error: null }
}

export default function GeofencePanel() {
  // ---- 状态 ----
  const [zones, setZones] = useState([])                 // 围栏区域列表
  const [breaches, setBreaches] = useState([])           // 越界历史
  const [error, setError] = useState(null)
  const [formError, setFormError] = useState(null)
  const [checkResult, setCheckResult] = useState(null)   // 手动检查结果
  const [checking, setChecking] = useState(false)
  const [deletingId, setDeletingId] = useState(null)     // 正在删除的围栏 id
  const [submitting, setSubmitting] = useState(false)

  // 越界历史 sysid 过滤
  const [filterSysid, setFilterSysid] = useState('')

  // 创建表单字段
  const [form, setForm] = useState({
    id: '',
    name: '',
    type: 'CIRCLE',
    action: 'WARN',
    centerLat: '',
    centerLon: '',
    radiusM: '',
    pointsText: '',
  })

  // ---- 轮询围栏列表 + 越界历史 ----
  // 经验：在 useEffect 内创建 AbortController，将 signal 传入 fetch 等异步操作
  // 经验：在 cleanup 中调用 controller.abort()
  // 经验：在 catch 中判断 err.name === 'AbortError'（或 signal.aborted），若是则直接 return，不更新任何 state
  useEffect(() => {
    const controller = new AbortController()
    let timer = null
    let stopped = false

    const load = async () => {
      try {
        const [zonesData, breachesData] = await Promise.all([
          listGeofenceZones().catch((e) => {
            if (e && e.name === 'AbortError') throw e
            return []
          }),
          getGeofenceBreaches(null, null).catch((e) => {
            if (e && e.name === 'AbortError') throw e
            return []
          }),
        ])
        // 经验：abort 后不更新任何 state
        if (controller.signal.aborted || stopped) return
        setZones(Array.isArray(zonesData) ? zonesData : (zonesData && zonesData.zones) || [])
        setBreaches(Array.isArray(breachesData) ? breachesData : (breachesData && breachesData.breaches) || [])
        setError(null)
      } catch (e) {
        if (e && e.name === 'AbortError') return
        if (controller.signal.aborted || stopped) return
        setError('加载围栏数据失败：' + (e && e.message ? e.message : String(e)))
      }
    }

    load()
    timer = setInterval(load, POLL_MS)

    return () => {
      // 经验：cleanup 中 abort + 清除定时器
      stopped = true
      controller.abort()
      clearInterval(timer)
    }
  }, [])

  // ---- 表单字段更新 ----
  const updateForm = useCallback((key, value) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }, [])

  // ---- 创建围栏 ----
  const handleCreate = useCallback(async (e) => {
    if (e && e.preventDefault) e.preventDefault()
    setFormError(null)
    setError(null)

    // 基础字段校验
    const id = Number(form.id)
    if (!Number.isFinite(id) || id <= 0) {
      setFormError('id 必须为正整数')
      return
    }
    if (!form.name || !form.name.trim()) {
      setFormError('name 不能为空')
      return
    }
    if (!ZONE_TYPES.some((t) => t.key === form.type)) {
      setFormError('type 非法')
      return
    }
    if (!ZONE_ACTIONS.some((a) => a.key === form.action)) {
      setFormError('action 非法')
      return
    }

    // 构造 zoneObj
    let zoneObj
    if (form.type === 'CIRCLE') {
      const centerLat = Number(form.centerLat)
      const centerLon = Number(form.centerLon)
      const radiusM = Number(form.radiusM)
      if (!Number.isFinite(centerLat) || !Number.isFinite(centerLon)) {
        setFormError('centerLat / centerLon 必须为有效数字')
        return
      }
      if (!Number.isFinite(radiusM) || radiusM <= 0) {
        setFormError('radiusM 必须为正数')
        return
      }
      zoneObj = {
        id,
        name: form.name.trim(),
        type: 'CIRCLE',
        action: form.action,
        centerLat,
        centerLon,
        radiusM,
      }
    } else {
      // POLYGON
      const { points, error: pErr } = parsePoints(form.pointsText)
      if (pErr) {
        setFormError(pErr)
        return
      }
      zoneObj = {
        id,
        name: form.name.trim(),
        type: 'POLYGON',
        action: form.action,
        points,
      }
    }

    setSubmitting(true)
    try {
      await createGeofenceZone(zoneObj)
      // 重置表单（保留 type/action 选择以便连续创建）
      setForm((prev) => ({
        id: '',
        name: '',
        type: prev.type,
        action: prev.action,
        centerLat: '',
        centerLon: '',
        radiusM: '',
        pointsText: '',
      }))
      // 轮询会自动刷新列表
    } catch (err) {
      setFormError('创建围栏失败：' + (err && err.message ? err.message : String(err)))
    } finally {
      setSubmitting(false)
    }
  }, [form])

  // ---- 删除围栏 ----
  const handleDelete = useCallback(async (id) => {
    setDeletingId(id)
    setError(null)
    try {
      await deleteGeofenceZone(id)
      // 乐观更新，避免等轮询
      setZones((prev) => prev.filter((z) => z.id !== id))
    } catch (err) {
      setError('删除围栏失败：' + (err && err.message ? err.message : String(err)))
    } finally {
      setDeletingId(null)
    }
  }, [])

  // ---- 手动检查 ----
  const handleCheck = useCallback(async () => {
    setChecking(true)
    setError(null)
    setCheckResult(null)
    try {
      const result = await checkGeofence()
      // 结果可能是 { newBreaches: N, breaches: [...] } 或直接数组
      const newCount = result && typeof result === 'object' && 'newBreaches' in result
        ? Number(result.newBreaches)
        : Array.isArray(result)
          ? result.length
          : 0
      const detailList = result && typeof result === 'object' && Array.isArray(result.breaches)
        ? result.breaches
        : Array.isArray(result)
          ? result
          : []
      setCheckResult({ newCount, breaches: detailList })
    } catch (err) {
      setError('手动检查失败：' + (err && err.message ? err.message : String(err)))
    } finally {
      setChecking(false)
    }
  }, [])

  // ---- 派生：越界历史按 sysid 过滤 ----
  const filteredBreaches = filterSysid.trim() === ''
    ? breaches
    : breaches.filter((b) => {
        const sysid = b.sysid != null ? String(b.sysid) : ''
        return sysid.includes(filterSysid.trim())
      })

  return (
    <div style={{ padding: 16, color: 'var(--text)' }}>
      <h2 style={{ fontSize: 16, margin: '0 0 12px 0', color: 'var(--text)' }}>
        电子围栏
      </h2>

      {error && (
        <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8, padding: '4px 8px', background: 'var(--bg-2)', borderRadius: 4, border: '1px solid var(--crit)' }}>
          ⚠ {error}
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {/* 左侧：围栏区域列表 + 创建表单 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 围栏区域列表 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={labelStyle}>围栏区域（{zones.length}）</div>
              <button
                onClick={handleCheck}
                disabled={checking}
                style={{
                  ...miniBtnStyle,
                  marginLeft: 'auto',
                  opacity: checking ? 0.5 : 1,
                  cursor: checking ? 'not-allowed' : 'pointer',
                  border: '1px solid var(--cyan)',
                  color: 'var(--cyan)',
                }}
              >
                {checking ? '检查中…' : '手动检查'}
              </button>
            </div>
            <div style={{ maxHeight: 320, overflowY: 'auto' }}>
              {zones.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>暂无围栏区域</div>
              ) : (
                zones.map((z) => {
                  const zid = z.id
                  const typeLabel = ZONE_TYPES.find((t) => t.key === z.type)?.label || z.type || '--'
                  const actionLabel = ZONE_ACTIONS.find((a) => a.key === z.action)?.label || z.action || '--'
                  const actionColor = ACTION_COLOR[z.action] || 'var(--dim)'
                  const isDeleting = deletingId === zid
                  return (
                    <div
                      key={zid}
                      style={{
                        padding: '6px 10px',
                        borderBottom: '1px solid var(--line-2)',
                        borderLeft: `3px solid ${z.enabled ? actionColor : 'var(--dim)'}`,
                        opacity: z.enabled ? 1 : 0.55,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: 'var(--cyan)', fontWeight: 'bold', flexShrink: 0 }}>#{zid}</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {z.name || '--'}
                          </span>
                        </div>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexShrink: 0 }}>
                          <span style={{
                            fontSize: 9, padding: '1px 5px', borderRadius: 3,
                            border: `1px solid ${z.enabled ? 'var(--ok)' : 'var(--dim)'}`,
                            color: z.enabled ? 'var(--ok)' : 'var(--dim)',
                          }}>
                            {z.enabled ? '启用' : '禁用'}
                          </span>
                          <button
                            onClick={() => handleDelete(zid)}
                            disabled={isDeleting}
                            style={{
                              ...miniBtnStyle,
                              fontSize: 9, padding: '0 6px',
                              color: 'var(--crit)',
                              border: '1px solid var(--crit)',
                              cursor: isDeleting ? 'not-allowed' : 'pointer',
                              opacity: isDeleting ? 0.5 : 1,
                            }}
                          >
                            {isDeleting ? '删除中…' : '删除'}
                          </button>
                        </div>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        <span>类型：{typeLabel}</span>
                        <span style={{ color: actionColor }}>动作：{actionLabel}</span>
                        {z.type === 'CIRCLE' && (
                          <>
                            <span>中心：{Number(z.centerLat).toFixed(4)}, {Number(z.centerLon).toFixed(4)}</span>
                            <span>半径：{z.radiusM != null ? `${Number(z.radiusM).toFixed(0)} m` : '--'}</span>
                          </>
                        )}
                        {z.type === 'POLYGON' && (
                          <span>点数：{Array.isArray(z.points) ? z.points.length : '--'}</span>
                        )}
                      </div>
                    </div>
                  )
                })
              )}
            </div>
          </div>

          {/* 创建围栏表单 */}
          <div style={{ ...cardStyle, padding: 10 }}>
            <div style={{ ...labelStyle, marginBottom: 6, fontSize: 11, color: 'var(--text)' }}>创建围栏</div>
            {formError && (
              <div style={{ color: 'var(--crit)', fontSize: 10, marginBottom: 6, padding: '3px 6px', background: 'var(--bg-1)', borderRadius: 3, border: '1px solid var(--crit)' }}>
                ⚠ {formError}
              </div>
            )}
            <form onSubmit={handleCreate} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '0 0 80px' }}>
                  <div style={labelStyle}>id</div>
                  <input
                    type="number"
                    min="1"
                    step="1"
                    value={form.id}
                    onChange={(e) => updateForm('id', e.target.value)}
                    style={modalInputStyle}
                    placeholder="正整数"
                  />
                </div>
                <div style={{ flex: '1 1 160px' }}>
                  <div style={labelStyle}>name</div>
                  <input
                    type="text"
                    value={form.name}
                    onChange={(e) => updateForm('name', e.target.value)}
                    style={modalInputStyle}
                    placeholder="围栏名称"
                  />
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 120px' }}>
                  <div style={labelStyle}>type</div>
                  <select
                    value={form.type}
                    onChange={(e) => updateForm('type', e.target.value)}
                    style={modalInputStyle}
                  >
                    {ZONE_TYPES.map((t) => <option key={t.key} value={t.key}>{t.label}</option>)}
                  </select>
                </div>
                <div style={{ flex: '1 1 120px' }}>
                  <div style={labelStyle}>action</div>
                  <select
                    value={form.action}
                    onChange={(e) => updateForm('action', e.target.value)}
                    style={modalInputStyle}
                  >
                    {ZONE_ACTIONS.map((a) => <option key={a.key} value={a.key}>{a.label}</option>)}
                  </select>
                </div>
              </div>

              {/* CIRCLE 字段 */}
              {form.type === 'CIRCLE' && (
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <div style={{ flex: '1 1 100px' }}>
                    <div style={labelStyle}>centerLat</div>
                    <input
                      type="number"
                      step="any"
                      value={form.centerLat}
                      onChange={(e) => updateForm('centerLat', e.target.value)}
                      style={modalInputStyle}
                      placeholder="纬度"
                    />
                  </div>
                  <div style={{ flex: '1 1 100px' }}>
                    <div style={labelStyle}>centerLon</div>
                    <input
                      type="number"
                      step="any"
                      value={form.centerLon}
                      onChange={(e) => updateForm('centerLon', e.target.value)}
                      style={modalInputStyle}
                      placeholder="经度"
                    />
                  </div>
                  <div style={{ flex: '1 1 100px' }}>
                    <div style={labelStyle}>radiusM</div>
                    <input
                      type="number"
                      min="0"
                      step="any"
                      value={form.radiusM}
                      onChange={(e) => updateForm('radiusM', e.target.value)}
                      style={modalInputStyle}
                      placeholder="半径(米)"
                    />
                  </div>
                </div>
              )}

              {/* POLYGON 字段 */}
              {form.type === 'POLYGON' && (
                <div>
                  <div style={labelStyle}>points（每行一个 "lat,lon"，至少 3 行）</div>
                  <textarea
                    value={form.pointsText}
                    onChange={(e) => updateForm('pointsText', e.target.value)}
                    style={{ ...modalInputStyle, minHeight: 80, resize: 'vertical', fontFamily: 'var(--mono)' }}
                    placeholder={'30.12345,120.12345\n30.12400,120.12400\n30.12300,120.12450'}
                    rows={4}
                  />
                </div>
              )}

              <button
                type="submit"
                disabled={submitting}
                style={{
                  ...miniBtnStyle,
                  padding: '4px 12px',
                  alignSelf: 'flex-start',
                  border: '1px solid var(--cyan)',
                  color: 'var(--cyan)',
                  cursor: submitting ? 'not-allowed' : 'pointer',
                  opacity: submitting ? 0.5 : 1,
                }}
              >
                {submitting ? '创建中…' : '创建围栏'}
              </button>
            </form>
          </div>
        </div>

        {/* 右侧：越界历史 + 手动检查结果 */}
        <div style={{ flex: '1 1 420px', minWidth: 360, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {/* 手动检查结果 */}
          {checkResult && (
            <div style={{ ...cardStyle, padding: 10, border: '1px solid var(--cyan)' }}>
              <div style={{ ...labelStyle, marginBottom: 4, color: 'var(--cyan)', fontSize: 11 }}>
                手动检查结果 · 新越界 {checkResult.newCount} 条
              </div>
              {checkResult.breaches.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)' }}>无新越界事件</div>
              ) : (
                <div style={{ maxHeight: 160, overflowY: 'auto' }}>
                  {checkResult.breaches.slice(0, 20).map((b, i) => {
                    const meta = BREACH_META[b.breachType] || { color: 'var(--dim)', label: b.breachType || '--' }
                    return (
                      <div key={b.sysid != null ? b.sysid : i} style={{ fontSize: 9, color: 'var(--dim-2)', padding: '2px 0', borderBottom: '1px solid var(--line-2)' }}>
                        <span style={{ color: meta.color, fontWeight: 'bold' }}>[{meta.label}]</span>{' '}
                        <span style={{ color: 'var(--cyan)' }}>#{b.zoneId}</span>{' '}
                        {b.zoneName || '--'}{' '}
                        {b.lat != null && b.lon != null ? `(${Number(b.lat).toFixed(4)}, ${Number(b.lon).toFixed(4)})` : ''}{' '}
                        {fmtTime(b.timestampMs)}
                      </div>
                    )
                  })}
                  {checkResult.breaches.length > 20 && (
                    <div style={{ fontSize: 9, color: 'var(--dim-2)', padding: '4px 0', textAlign: 'center' }}>
                      …共 {checkResult.breaches.length} 条，仅显示前 20 条
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* 越界历史 */}
          <div style={{ ...cardStyle, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--line-2)', display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={labelStyle}>越界历史（{filteredBreaches.length}{filterSysid.trim() !== '' ? ` / ${breaches.length}` : ''}）</div>
              <input
                type="text"
                value={filterSysid}
                onChange={(e) => setFilterSysid(e.target.value)}
                placeholder="按 sysid 过滤"
                style={{
                  ...modalInputStyle,
                  marginLeft: 'auto',
                  width: 140,
                  fontSize: 10,
                  padding: '2px 6px',
                }}
              />
            </div>
            <div style={{ maxHeight: 420, overflowY: 'auto' }}>
              {filteredBreaches.length === 0 ? (
                <div style={{ fontSize: 10, color: 'var(--dim-2)', padding: 16, textAlign: 'center' }}>
                  {filterSysid.trim() !== '' ? '无匹配的越界事件' : '暂无越界事件'}
                </div>
              ) : (
                filteredBreaches.map((b, i) => {
                  const sysid = b.sysid != null ? b.sysid : i
                  const meta = BREACH_META[b.breachType] || { color: 'var(--dim)', label: b.breachType || '--' }
                  return (
                    <div
                      key={sysid}
                      style={{
                        padding: '6px 10px',
                        borderBottom: '1px solid var(--line-2)',
                        borderLeft: `3px solid ${meta.color}`,
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 9, color: meta.color, fontWeight: 'bold', flexShrink: 0 }}>[{meta.label}]</span>
                          <span style={{ fontSize: 11, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                            {b.zoneName || '--'}
                          </span>
                        </div>
                        <span style={{ fontSize: 9, color: 'var(--dim-2)', flexShrink: 0, fontFamily: 'var(--mono)' }}>
                          {fmtTime(b.timestampMs)}
                        </span>
                      </div>
                      <div style={{ fontSize: 9, color: 'var(--dim-2)', marginTop: 2, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        <span>sysid：{b.sysid != null ? b.sysid : '--'}</span>
                        <span style={{ color: 'var(--cyan)' }}>zoneId：{b.zoneId != null ? b.zoneId : '--'}</span>
                        {b.lat != null && b.lon != null && (
                          <span>位置：{Number(b.lat).toFixed(4)}, {Number(b.lon).toFixed(4)}</span>
                        )}
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

