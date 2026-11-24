import React, { useCallback, useEffect, useState } from 'react'
import {
  listBalconyDropPoints, createBalconyDropPoint, updateBalconyDropPoint, deleteBalconyDropPoint,
  listAgriSupply, createAgriSupply, updateAgriSupply, deleteAgriSupply,
  listEmergencyRoles, createEmergencyRole, updateEmergencyRole, deleteEmergencyRole,
} from '../api.js'

/**
 * 场景注册表管理面板（V43 三张表）：阳台落点 / 农资流水 / 应急节点角色。
 * 简单 CRUD：分页列表（每类最多 200 行）+ 表单新建/编辑 + 删除。
 * 读 OBSERVER、写 OPERATOR 由后端 RBAC 负责；本面板只展示返回的错误。
 */

const RESOURCES = [
  {
    key: 'balcony', label: '阳台落点', idKey: 'point_id',
    list: listBalconyDropPoints, create: createBalconyDropPoint,
    update: updateBalconyDropPoint, remove: deleteBalconyDropPoint,
    fields: [
      { key: 'buildingId', col: 'building_id', label: '楼栋', required: true },
      { key: 'floor', col: 'floor', label: '楼层', type: 'number', required: true },
      { key: 'lat', col: 'lat', label: '纬度', type: 'number', required: true },
      { key: 'lon', col: 'lon', label: '经度', type: 'number', required: true },
      { key: 'altM', col: 'alt_m', label: '高度(m)', type: 'number', required: true },
      { key: 'maxPayloadKg', col: 'max_payload_kg', label: '承重(kg)', type: 'number' },
      { key: 'status', col: 'status', label: '状态' },
    ],
  },
  {
    key: 'agri', label: '农资流水', idKey: 'entry_id',
    list: listAgriSupply, create: createAgriSupply,
    update: updateAgriSupply, remove: deleteAgriSupply,
    fields: [
      { key: 'taskId', col: 'task_id', label: '任务ID' },
      { key: 'sysid', col: 'sysid', label: 'sysid', type: 'number' },
      { key: 'supplyType', col: 'supply_type', label: '类型', required: true },
      { key: 'action', col: 'action', label: '动作', required: true },
      { key: 'quantity', col: 'quantity', label: '数量', type: 'number', required: true },
      { key: 'unit', col: 'unit', label: '单位', required: true },
      { key: 'balanceAfter', col: 'balance_after', label: '结余', type: 'number' },
    ],
  },
  {
    key: 'emergency', label: '应急节点', idKey: 'role_id',
    list: listEmergencyRoles, create: createEmergencyRole,
    update: updateEmergencyRole, remove: deleteEmergencyRole,
    fields: [
      { key: 'planId', col: 'plan_id', label: '预案ID' },
      { key: 'sysid', col: 'sysid', label: 'sysid', type: 'number', required: true },
      { key: 'role', col: 'role', label: '角色', required: true },
      { key: 'linkBudgetMs', col: 'link_budget_ms', label: '链路预算(ms)', type: 'number' },
      { key: 'status', col: 'status', label: '状态' },
    ],
  },
]

const btn = (primary) => ({
  padding: '4px 10px', fontSize: 11, cursor: 'pointer',
  background: primary ? 'var(--cyan)' : 'transparent',
  color: primary ? '#04121a' : 'var(--text)',
  border: '1px solid var(--cyan)', borderRadius: 3,
})

function emptyForm() {
  return Object.fromEntries(RESOURCES.flatMap((r) => r.fields.map((f) => [f.key, ''])))
}

export default function ScenarioRegistryPanel() {
  const [active, setActive] = useState(0)
  const [rows, setRows] = useState([])
  const [form, setForm] = useState(emptyForm)
  const [editingId, setEditingId] = useState(null)
  const [filter, setFilter] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const resource = RESOURCES[active]

  const load = useCallback(async (index, signal) => {
    try {
      const data = await RESOURCES[index].list()
      if (!signal?.aborted) {
        setRows(Array.isArray(data) ? data : [])
        setError('')
      }
    } catch (e) {
      if (e.name !== 'AbortError' && !signal?.aborted) {
        setError(e.message || '加载失败')
        setRows([])
      }
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    load(active, controller.signal)
    return () => controller.abort()
  }, [active, load])

  const switchTo = (index) => {
    setActive(index)
    setEditingId(null)
    setForm(emptyForm())
    setFilter('')
  }

  const submit = async (e) => {
    e.preventDefault()
    const body = {}
    for (const f of resource.fields) {
      const raw = form[f.key]
      if (raw === '' || raw == null) continue
      body[f.key] = f.type === 'number' ? Number(raw) : raw
    }
    setBusy(true)
    try {
      if (editingId) await resource.update(editingId, body)
      else await resource.create(body)
      setEditingId(null)
      setForm(emptyForm())
      await load(active)
    } catch (err) {
      setError(err.message || '保存失败')
    } finally {
      setBusy(false)
    }
  }

  const edit = (row) => {
    setEditingId(row[resource.idKey])
    setForm(Object.fromEntries(resource.fields.map((f) => [f.key, row[f.col] ?? ''])))
  }

  const remove = async (row) => {
    const id = row[resource.idKey]
    if (!id || !window.confirm(`删除 ${resource.label} ${id}？`)) return
    setBusy(true)
    try {
      await resource.remove(id)
      if (editingId === id) {
        setEditingId(null)
        setForm(emptyForm())
      }
      await load(active)
    } catch (err) {
      setError(err.message || '删除失败')
    } finally {
      setBusy(false)
    }
  }

  const filterKey = resource.fields[0].col
  const shown = filter
    ? rows.filter((r) => String(r[filterKey] ?? '').includes(filter))
    : rows

  return (
    <div className="page">
      <h3>场景注册表</h3>
      <p style={{ color: 'var(--dim)', fontSize: 11 }}>
        V43 三张场景表的基础增删改查（阳台落点 / 农资流水 / 应急节点角色）。
        写操作需 OPERATOR 角色；删除前会二次确认。
      </p>

      <div style={{ display: 'flex', gap: 6, margin: '10px 0' }}>
        {RESOURCES.map((r, i) => (
          <button key={r.key} type="button" style={btn(i === active)} onClick={() => switchTo(i)}>
            {r.label}
          </button>
        ))}
      </div>

      {error && <div style={{ color: 'var(--crit)', fontSize: 11, marginBottom: 8 }}>{error}</div>}

      <form onSubmit={submit} style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 14 }}>
        {resource.fields.map((f) => (
          <label key={f.key} style={{ fontSize: 11, color: 'var(--dim)', display: 'flex', flexDirection: 'column', gap: 2 }}>
            {f.label}{f.required ? ' *' : ''}
            <input
              value={form[f.key]}
              type={f.type || 'text'}
              required={!!f.required}
              onChange={(e) => setForm((prev) => ({ ...prev, [f.key]: e.target.value }))}
              style={{ background: 'var(--panel-2)', border: '1px solid var(--line-2)', color: 'var(--text)', padding: '3px 6px', width: 120 }}
            />
          </label>
        ))}
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 6 }}>
          <button type="submit" style={btn(true)} disabled={busy}>{editingId ? '保存修改' : '新建'}</button>
          {editingId && (
            <button type="button" style={btn(false)} onClick={() => { setEditingId(null); setForm(emptyForm()) }}>取消</button>
          )}
        </div>
      </form>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <input
          value={filter}
          placeholder={`按${resource.fields[0].label}过滤`}
          onChange={(e) => setFilter(e.target.value)}
          style={{ background: 'var(--panel-2)', border: '1px solid var(--line-2)', color: 'var(--text)', padding: '3px 6px', fontSize: 11 }}
        />
        <span style={{ color: 'var(--dim)', fontSize: 11 }}>{shown.length} 条</span>
      </div>

      <div style={{ overflowX: 'auto', border: '1px solid var(--line-2)' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11 }}>
          <thead>
            <tr>
              {resource.fields.map((f) => <th key={f.key} style={{ textAlign: 'left', padding: 5, borderBottom: '1px solid var(--line-2)' }}>{f.label}</th>)}
              <th style={{ padding: 5, borderBottom: '1px solid var(--line-2)' }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {shown.length === 0 ? (
              <tr><td colSpan={resource.fields.length + 1} style={{ padding: 10, color: 'var(--dim)' }}>暂无数据</td></tr>
            ) : shown.map((row) => (
              <tr key={row[resource.idKey]}>
                {resource.fields.map((f) => (
                  <td key={f.key} style={{ padding: 5, borderBottom: '1px solid var(--line-2)' }}>
                    {String(row[f.col] ?? '--')}
                  </td>
                ))}
                <td style={{ padding: 5, whiteSpace: 'nowrap' }}>
                  <button type="button" style={btn(false)} onClick={() => edit(row)}>编辑</button>
                  <button type="button" style={btn(false)} onClick={() => remove(row)} disabled={busy}>删除</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
