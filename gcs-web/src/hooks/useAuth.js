import { useState, useCallback } from 'react'
import { isAuthenticated, getCurrentUser, logout } from '../api.js'

/**
 * 认证状态管理 hook
 * 管理 authed 状态及登录/登出逻辑
 */
export default function useAuth() {
  const [authed, setAuthed] = useState(isAuthenticated())

  const handleLoginSuccess = useCallback(() => {
    setAuthed(true)
  }, [])

  const handleLogout = useCallback(() => {
    logout()
    setAuthed(false)
  }, [])

  return { authed, setAuthed, handleLoginSuccess, handleLogout }
}