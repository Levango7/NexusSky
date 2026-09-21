import React, { useState } from 'react'
import { api, setAuthToken, setCurrentUser } from '../api.js'

// 登录面板组件
// 居中卡片式布局，使用现有 CSS 变量风格
// 登录成功后调用 setAuthToken + setCurrentUser + onLoginSuccess 回调
export default function LoginPanel({ onLoginSuccess }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  const handleLogin = async (e) => {
    e.preventDefault()
    if (!username || !password) {
      setError('请输入用户名和密码')
      return
    }
    setLoading(true)
    setError(null)
    try {
      const res = await api.login(username, password)
      // 后端返回 { token, user } 或 { access_token, user } 等
      const token = res.token || res.access_token
      const user = res.user || { username, role: res.role, tenantId: res.tenantId }
      if (token) {
        setAuthToken(token)
        setCurrentUser(user)
        onLoginSuccess?.(user)
      } else {
        setError('登录返回数据异常：未收到 token')
      }
    } catch (e) {
      setError(e.message || '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      background: 'var(--bg)',
      zIndex: 100,
    }}>
      <div style={{
        width: 360,
        maxWidth: '90vw',
        background: 'linear-gradient(180deg, var(--panel-2), var(--panel))',
        border: '1px solid var(--line-2)',
        borderRadius: 'var(--r)',
        padding: '32px 28px 24px',
        boxShadow: '0 8px 32px rgba(0,0,0,.5)',
      }}>
        {/* 标志 */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', marginBottom: 24 }}>
          <svg width="48" height="48" viewBox="0 0 26 26" role="img" aria-label="NexusSky 标志">
            <circle cx="13" cy="13" r="11.5" fill="none" stroke="#00d4ff" stroke-width="1.2" />
            <path d="M13 4 A 9 9 0 0 1 22 13 L 13 13 Z" fill="#00d4ff" opacity=".35" />
            <path d="M13 13 L 20 20" stroke="#00d4ff" stroke-width="1.4" />
            <circle cx="13" cy="13" r="2.2" fill="#00d4ff" />
          </svg>
          <span style={{
            fontWeight: 800,
            letterSpacing: '3px',
            fontSize: 18,
            marginTop: 10,
            background: 'linear-gradient(90deg, #fff, #9adfff)',
            WebkitBackgroundClip: 'text',
            backgroundClip: 'text',
            color: 'transparent',
          }}>NEXUSSKY</span>
          <span style={{ fontSize: 11, color: 'var(--dim)', letterSpacing: '2px', marginTop: 4 }}>
            天枢 · 无人机地面站
          </span>
        </div>

        {/* 登录表单 */}
        <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>用户名</label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="请输入用户名"
              autoFocus
              style={{
                width: '100%',
                padding: '10px 12px',
                fontSize: 13,
                background: 'var(--bg-2)',
                border: '1px solid var(--line-2)',
                borderRadius: 8,
                color: 'var(--text)',
                fontFamily: 'var(--sans)',
                outline: 'none',
              }}
              onFocus={(e) => { e.target.style.borderColor = 'rgba(0,212,255,.5)' }}
              onBlur={(e) => { e.target.style.borderColor = 'var(--line-2)' }}
            />
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label style={{ fontSize: 10, color: 'var(--dim)', letterSpacing: '1px' }}>密码</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入密码"
              style={{
                width: '100%',
                padding: '10px 12px',
                fontSize: 13,
                background: 'var(--bg-2)',
                border: '1px solid var(--line-2)',
                borderRadius: 8,
                color: 'var(--text)',
                fontFamily: 'var(--sans)',
                outline: 'none',
              }}
              onFocus={(e) => { e.target.style.borderColor = 'rgba(0,212,255,.5)' }}
              onBlur={(e) => { e.target.style.borderColor = 'var(--line-2)' }}
            />
          </div>

          {error && (
            <div style={{
              padding: '8px 12px',
              borderRadius: 6,
              fontSize: 12,
              color: 'var(--crit)',
              background: 'var(--crit-dim)',
              border: '1px solid rgba(255,93,93,.3)',
            }}>
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="btn primary"
            style={{ padding: '11px 10px', fontSize: 13, marginTop: 4 }}
          >
            {loading ? '登录中...' : '登录'}
          </button>
        </form>
      </div>
    </div>
  )
}