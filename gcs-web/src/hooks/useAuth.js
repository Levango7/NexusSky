import { useState, useCallback, useEffect } from 'react'
import { isAuthenticated, logout } from '../api.js'

/**
 * 认证状态管理 hook
 * 管理 authed 状态及登录/登出逻辑
 */
export default function useAuth() {
  const [authed, setAuthed] = useState(isAuthenticated())
  // 最近一次"认证失效"的原因：api.js 的 401 只发事件不刷新页面，原因随事件带出，
  // 供登录页说明"为什么突然回到登录界面"（旧行为是整页刷新，说明随之被毁掉）。
  const [authError, setAuthError] = useState(null)

  const handleLoginSuccess = useCallback(() => {
    setAuthed(true)
    setAuthError(null)
  }, [])

  const handleLogout = useCallback(() => {
    logout()
    setAuthed(false)
  }, [])

  // api.js 遇到 401 只发事件、不整页刷新（刷新会毁掉调用方的错误提示与作业现场）。
  // 这里负责把界面切回登录态：LoginPanel 由此显示 authError。
  useEffect(() => {
    const onAuthExpired = (e) => {
      setAuthError((e && e.detail && e.detail.message) || '认证已过期，请重新登录')
      setAuthed(false)
    }
    window.addEventListener('nexus-auth-expired', onAuthExpired)
    return () => window.removeEventListener('nexus-auth-expired', onAuthExpired)
  }, [])

  return { authed, authError, handleLoginSuccess, handleLogout }
}
