import React, { useState, useEffect, useCallback } from 'react'
import { api, getCurrentUser } from '../api.js'

// 用户管理面板
// 列出用户 + 创建/编辑/删除用户
// 角色显示：ADMIN→管理员, OPERATOR→操作员, OBSERVER→观察者
// 删除操作不允许删除自己

const ROLE_LABELS = {
  ADMIN: '管理员',
  OPERATOR: '操作员',
  OBSERVER: '观察者',
}

const ROLE_COLORS = {
  ADMIN: 'var(--cyan)',
  OPERATOR: 'var(--ok)',
  OBSERVER: 'var(--dim)',
}

function formatTime(ts) {
  if (ts == null || ts === '') return '--'
  const n = Number(ts)
  if (!Number.isFinite(n)) return String(ts)
  try {
    return new Date(n).toLocaleString('zh-CN', { hour12: false })
  } catch (e) {
    return '--'
  }
}

function toArray(data, fallbackKey) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data[fallbackKey])) return data[fallbackKey]
  if (data && typeof data === 'object') {
    for (const k of Object.keys(data)) {
      if (Array.isArray(data[k])) return data[k]
    }
  }
  return []
}

export default function UserPanel() {
  const [users, setUsers] = useState([])
  const [tenants, setTenants] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  // 创建/编辑表单
  const [editing, setEditing] = useState(null) // null | 'new' | user 对象
  const [formUsername, setFormUsername] = useState('')
  const [formPassword, setFormPassword] = useState('')
  const [formRole, setFormRole] = useState('OBSERVER')
  const [formTenantId, setFormTenantId] = useState('')
  const [formEnabled, setFormEnabled] = useState(true)

  const currentUser = getCurrentUser()

  const loadUsers = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.listUsers()
      setUsers(toArray(data, 'users'))
    } catch (e) {
      setError(e.message || '加载用户列表失败')
    } finally {
      setLoading(false)
    }
  }, [])

  const loadTenants = useCallback(async () => {
    try {
      const data = await api.listTenants()
      setTenants(toArray(data, 'tenants'))
    } catch (e) {
      // 租户列表加载失败不阻塞用户列表
    }
  }, [])

  useEffect(() => {
    loadUsers()
    loadTenants()
  }, [loadUsers, loadTenants])

  const handleCreate = async () => {
    if (!formUsername || !formPassword) {
      setError('用户名和密码不能为空')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await api.createUser(formUsername, formPassword, formRole, formTenantId || null, formEnabled)
      setEditing(null)
      resetForm()
      await loadUsers()
    } catch (e) {
      setError(e.message || '创建用户失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleUpdate = async (id) => {
    setSubmitting(true)
    setError(null)
    try {
      await api.updateUser(id, formRole, formEnabled, formTenantId || null, formPassword || undefined)
      setEditing(null)
      resetForm()
      await loadUsers()
    } catch (e) {
      setError(e.message || '更新用户失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id, username) => {
    // 不允许删除自己
    if (currentUser && (currentUser.id === id || currentUser.username === username)) {
      setError('不能删除当前登录用户')
      return
    }
    if (!window.confirm(`确认删除用户 "${username}"？删除后不可恢复。`)) return
    setError(null)
    try {
      await api.deleteUser(id)
      await loadUsers()
    } catch (e) {
      setError(e.message || '删除用户失败')
    }
  }

  const resetForm = () => {
    setFormUsername('')
    setFormPassword('')
    setFormRole('OBSERVER')
    setFormTenantId('')
    setFormEnabled(true)
    setError(null)
  }

  const startEdit = (user) => {
    setEditing(user)
    setFormUsername(user.username || '')
    setFormPassword('') // 编辑时密码留空表示不修改
    setFormRole(user.role || 'OBSERVER')
    setFormTenantId(user.tenantId != null ? String(user.tenantId) : '')
    setFormEnabled(user.enabled !== false)
  }

  const startNew = () => {
    setEditing('new')
    resetForm()
  }

  const cancelEdit = () => {
    setEditing(null)
    resetForm()
  }

  return (
    <div style={{ padding: 16, overflow: 'auto', height: '100%' }}>
      {/* 标题与操作 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--dim)', letterSpacing: '2px', textTransform: 'uppercase' }}>
          用户管理
        </h2>
        {editing === null && (
          <button className="btn primary" onClick={startNew} style={{ padding: '6px 14px', fontSize: 12 }}>
            + 新建用户
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
            {editing === 'new' ? '新建用户' : '编辑用户'}
          </h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>用户名</label>
              <input
                type="text"
                value={formUsername}
                onChange={(e) => setFormUsername(e.target.value)}
                placeholder="用户名"
                disabled={editing !== 'new'}
                style={{
                  width: '100%', padding: '8px 10px', fontSize: 12,
                  background: editing !== 'new' ? 'var(--bg)' : 'var(--bg-2)',
                  border: '1px solid var(--line-2)',
                  borderRadius: 6, color: 'var(--text)', outline: 'none',
                  opacity: editing !== 'new' ? 0.6 : 1,
                }}
              />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>
                {editing === 'new' ? '密码' : '新密码（留空不修改）'}
              </label>
              <input
                type="password"
                value={formPassword}
                onChange={(e) => setFormPassword(e.target.value)}
                placeholder={editing === 'new' ? '密码' : '留空不修改'}
                style={{
                  width: '100%', padding: '8px 10px', fontSize: 12,
                  background: 'var(--bg-2)', border: '1px solid var(--line-2)',
                  borderRadius: 6, color: 'var(--text)', outline: 'none',
                }}
              />
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>角色</label>
              <select
                value={formRole}
                onChange={(e) => setFormRole(e.target.value)}
                style={{
                  width: '100%', padding: '8px 10px', fontSize: 12,
                  background: 'var(--bg-2)', border: '1px solid var(--line-2)',
                  borderRadius: 6, color: 'var(--text)', outline: 'none',
                }}
              >
                <option value="ADMIN">管理员</option>
                <option value="OPERATOR">操作员</option>
                <option value="OBSERVER">观察者</option>
              </select>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>租户</label>
              <select
                value={formTenantId}
                onChange={(e) => setFormTenantId(e.target.value)}
                style={{
                  width: '100%', padding: '8px 10px', fontSize: 12,
                  background: 'var(--bg-2)', border: '1px solid var(--line-2)',
                  borderRadius: 6, color: 'var(--text)', outline: 'none',
                }}
              >
                <option value="">无租户</option>
                {tenants.map((t) => (
                  <option key={t.id} value={t.id}>{t.name} ({t.code})</option>
                ))}
              </select>
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

      {/* 用户列表 */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--dim)' }}>加载中...</div>
      ) : users.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--dim)' }}>
          <div style={{ fontSize: 26, marginBottom: 8, opacity: .5 }}>∅</div>
          暂无用户数据
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
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>用户名</th>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>角色</th>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>租户ID</th>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>状态</th>
              <th style={{ padding: '10px 12px', textAlign: 'left', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>创建时间</th>
              <th style={{ padding: '10px 12px', textAlign: 'right', color: 'var(--dim)', fontSize: 10, letterSpacing: '1px', textTransform: 'uppercase' }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => {
              const isSelf = currentUser && (currentUser.id === u.id || currentUser.username === u.username)
              return (
                <tr key={u.id} style={{ borderBottom: '1px solid var(--line)' }}>
                  <td style={{ padding: '8px 12px', fontFamily: 'var(--mono)', color: 'var(--dim)' }}>{u.id}</td>
                  <td style={{ padding: '8px 12px', fontWeight: 600 }}>
                    {u.username}
                    {isSelf && <span style={{ marginLeft: 6, fontSize: 10, color: 'var(--cyan)' }}>(当前用户)</span>}
                  </td>
                  <td style={{ padding: '8px 12px' }}>
                    <span className="badge" style={{
                      color: ROLE_COLORS[u.role] || 'var(--dim)',
                      borderColor: `${ROLE_COLORS[u.role] || 'var(--line-2)'}55`,
                    }}>
                      {ROLE_LABELS[u.role] || u.role}
                    </span>
                  </td>
                  <td style={{ padding: '8px 12px', fontFamily: 'var(--mono)', color: 'var(--dim)' }}>
                    {u.tenantId != null ? u.tenantId : '--'}
                  </td>
                  <td style={{ padding: '8px 12px' }}>
                    <span className={`badge ${u.enabled !== false ? 'st-active' : ''}`} style={{
                      color: u.enabled !== false ? 'var(--ok)' : 'var(--dim)',
                      borderColor: u.enabled !== false ? 'rgba(45,226,165,.4)' : 'var(--line-2)',
                    }}>
                      {u.enabled !== false ? '启用' : '禁用'}
                    </span>
                  </td>
                  <td style={{ padding: '8px 12px', fontFamily: 'var(--mono)', color: 'var(--dim)', fontSize: 11 }}>
                    {formatTime(u.createdAt || u.created_at)}
                  </td>
                  <td style={{ padding: '8px 12px', textAlign: 'right' }}>
                    <button
                      className="btn"
                      onClick={() => startEdit(u)}
                      style={{ padding: '4px 10px', fontSize: 11, marginRight: 4 }}
                    >
                      编辑
                    </button>
                    <button
                      className="btn kill"
                      onClick={() => handleDelete(u.id, u.username)}
                      disabled={isSelf}
                      style={{ padding: '4px 10px', fontSize: 11 }}
                      title={isSelf ? '不能删除当前登录用户' : '删除用户'}
                    >
                      删除
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </div>
  )
}