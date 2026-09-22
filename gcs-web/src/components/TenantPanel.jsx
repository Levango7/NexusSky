import React, { useState, useEffect, useCallback } from 'react'
import { api } from '../api.js'
import { fmtTime, toArray } from '../utils/panelUtils.js'

// 租户管理面板
// 列出所有租户 + 创建/编辑/删除租户
// 风格与 AlarmPanel / DroneLockPanel 一致：卡片布局 + CSS 变量


export default function TenantPanel() {
  const [tenants, setTenants] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  // 创建/编辑表单
  const [editing, setEditing] = useState(null) // null | 'new' | tenant 对象
  const [formName, setFormName] = useState('')
  const [formCode, setFormCode] = useState('')
  const [formEnabled, setFormEnabled] = useState(true)

  const loadTenants = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.listTenants()
      setTenants(toArray(data, 'tenants'))
    } catch (e) {
      setError(e.message || '加载租户列表失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadTenants()
  }, [loadTenants])

  const handleCreate = async () => {
    if (!formName || !formCode) {
      setError('名称和编码不能为空')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await api.createTenant(formName, formCode, formEnabled)
      setEditing(null)
      setFormName('')
      setFormCode('')
      setFormEnabled(true)
      await loadTenants()
    } catch (e) {
      setError(e.message || '创建租户失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleUpdate = async (id) => {
    if (!formName || !formCode) {
      setError('名称和编码不能为空')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await api.updateTenant(id, formName, formCode, formEnabled)
      setEditing(null)
      setFormName('')
      setFormCode('')
      setFormEnabled(true)
      await loadTenants()
    } catch (e) {
      setError(e.message || '更新租户失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id) => {
    if (!window.confirm('确认删除该租户？删除后不可恢复。')) return
    setError(null)
    try {
      await api.deleteTenant(id)
      await loadTenants()
    } catch (e) {
      setError(e.message || '删除租户失败')
    }
  }

  const startEdit = (tenant) => {
    setEditing(tenant)
    setFormName(tenant.name || '')
    setFormCode(tenant.code || '')
    setFormEnabled(tenant.enabled !== false)
  }

  const startNew = () => {
    setEditing('new')
    setFormName('')
    setFormCode('')
    setFormEnabled(true)
  }

  const cancelEdit = () => {
    setEditing(null)
    setFormName('')
    setFormCode('')
    setFormEnabled(true)
    setError(null)
  }

  return (
    <div style={{ padding: 16, overflow: 'auto', height: '100%' }}>
      {/* 标题与操作 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--dim)', letterSpacing: '2px', textTransform: 'uppercase' }}>
          租户管理
        </h2>
        {editing === null && (
          <button className="btn primary" onClick={startNew} style={{ padding: '6px 14px', fontSize: 12 }}>
            + 新建租户
          </button>
        )}
      </div>

      {error && (
        <div style={{
          padding: '8px 12px', marginBottom: 12, borderRadius: 6,
          fontSize: 12, color: 'var(--crit)',
          background: 'var(--crit-dim)', border: '1px solid rgba(255,93,93,.3)',
        }}>
          {error}
        </div>
      )}

      {/* 创建/编辑表单 */}
      {editing !== null && (
        <div style={{
          background: 'linear-gradient(180deg, var(--panel-2), var(--panel))',
          border: '1px solid var(--line-2)',
          borderRadius: 'var(--r)',
          padding: 16,
          marginBottom: 16,
        }}>
          <h3 style={{ fontSize: 12, fontWeight: 700, color: 'var(--cyan)', marginBottom: 12, letterSpacing: '1px' }}>
            {editing === 'new' ? '新建租户' : '编辑租户'}
          </h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>名称</label>
              <input
                type="text"
                value={formName}
                onChange={(e) => setFormName(e.target.value)}
                placeholder="租户名称"
                style={{
                  width: '100%', padding: '8px 10px', fontSize: 12,
                  background: 'var(--bg-2)', border: '1px solid var(--line-2)',
                  borderRadius: 6, color: 'var(--text)', outline: 'none',
                }}
              />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>编码</label>
              <input
                type="text"
                value={formCode}
                onChange={(e) => setFormCode(e.target.value)}
                placeholder="租户编码"
                style={{
                  width: '100%', padding: '8px 10px', fontSize: 12,
                  background: 'var(--bg-2)', border: '1px solid var(--line-2)',
                  borderRadius: 6, color: 'var(--text)', outline: 'none',
                }}
              />
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12 }}>
            <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>启用状态</label>
            <input
              type="checkbox"
              checked={formEnabled}
              onChange={(e) => setFormEnabled(e.target.checked)}
              style={{ width: 16, height: 16, accentColor: 'var(--cyan)' }}
            />
            <span style={{ fontSize: 11, color: formEnabled ? 'var(--ok)' : 'var(--dim)' }}>
              {formEnabled ? '启用' : '禁用'}
            </span>
          </div>
          <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
            <button
              className="btn primary"
              disabled={submitting}
              onClick={() => editing === 'new' ? handleCreate() : handleUpdate(editing.id)}
              style={{ padding: '8px 16px', fontSize: 12 }}
            >
              {submitting ? '提交中...' : (editing === 'new' ? '创建' : '保存')}
            </button>
            <button
              className="btn"
              onClick={cancelEdit}
              style={{ padding: '8px 16px', fontSize: 12 }}
            >
              取消
            </button>
          </div>
        </div>
      )}

      {/* 租户列表 */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--dim)' }}>加载中...</div>
      ) : tenants.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--dim)' }}>
          <div style={{ fontSize: 26, marginBottom: 8, opacity: .5 }}>∅</div>
          暂无租户数据
        </div>
      ) : (
        <table style={{
          width: '100%',
          borderCollapse: 'collapse',
          fontSize: 12,
          background: 'var(--panel)',
          border: '1px solid var(--line)',
          borderRadius: 'var(--r)',
          overflow: 'hidden',
        }}>
          <thead>
            <tr style={{ background: 'var(--panel-2)', borderBottom: '1px solid var(--line-2)' }}>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>ID</th>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>名称</th>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>编码</th>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>状态</th>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>创建时间</th>
              <th style={{ padding: '10px 12px', textAlign: 'right', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {tenants.map((t) => (
              <tr key={t.id} style={{ borderBottom: '1px solid var(--line)' }}>
                <td style={{ padding: '8px 12px', fontFamily: 'var(--mono)', color: 'var(--dim)' }}>{t.id}</td>
                <td style={{ padding: '8px 12px', fontWeight: 600 }}>{t.name}</td>
                <td style={{ padding: '8px 12px', fontFamily: 'var(--mono)', color: 'var(--cyan)' }}>{t.code}</td>
                <td style={{ padding: '8px 12px' }}>
                  <span className={`badge ${t.enabled !== false ? 'st-active' : ''}`} style={{
                    color: t.enabled !== false ? 'var(--ok)' : 'var(--dim)',
                    borderColor: t.enabled !== false ? 'rgba(45,226,165,.4)' : 'var(--line-2)',
                  }}>
                    {t.enabled !== false ? '启用' : '禁用'}
                  </span>
                </td>
                <td style={{ padding: '8px 12px', fontFamily: 'var(--mono)', color: 'var(--dim)', fontSize: 11 }}>
                  {fmtTime(t.createdAt || t.created_at)}
                </td>
                <td style={{ padding: '8px 12px', textAlign: 'right' }}>
                  <button
                    className="btn"
                    onClick={() => startEdit(t)}
                    style={{ padding: '4px 10px', fontSize: 11, marginRight: 4 }}
                  >
                    编辑
                  </button>
                  <button
                    className="btn kill"
                    onClick={() => handleDelete(t.id)}
                    style={{ padding: '4px 10px', fontSize: 11 }}
                  >
                    删除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}