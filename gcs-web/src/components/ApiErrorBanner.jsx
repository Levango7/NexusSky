import React, { useEffect, useState } from 'react'
import { API_ERROR_EVENT } from '../api.js'

const AUTO_HIDE_MS = 8000

/**
 * API 失败提示条。
 *
 * 背景：gcs-web 的 80+ 处 `.catch(() => [])` 会把请求失败变成"空数据"，
 * 面板显示"暂无数据"，用户与开发都看不出是失败还是本来没有。
 * 这里订阅 `api.js` 发出的 `nexus-api-error` 事件，在顶栏下方提示一次
 * （最多 8s 自动消失，也可手动关闭）；不改变任何组件的既有兜底行为。
 */
export default function ApiErrorBanner() {
  const [failure, setFailure] = useState(null)
  const [count, setCount] = useState(0)

  useEffect(() => {
    const onError = (event) => {
      setFailure(event.detail || {})
      setCount((n) => n + 1)
    }
    window.addEventListener(API_ERROR_EVENT, onError)
    return () => window.removeEventListener(API_ERROR_EVENT, onError)
  }, [])

  useEffect(() => {
    if (!failure) return undefined
    const timer = setTimeout(() => setFailure(null), AUTO_HIDE_MS)
    return () => clearTimeout(timer)
  }, [failure])

  if (!failure) return null

  return (
    <div className="api-error-banner" role="status" data-testid="api-error-banner">
      <span className="api-error-text">
        有请求失败{count > 1 ? `（本次会话累计 ${count} 次）` : ''}：{failure.message}
        {failure.status ? ` · HTTP ${failure.status}` : ''}
      </span>
      <button
        type="button"
        className="api-error-close"
        aria-label="关闭请求失败提示"
        onClick={() => setFailure(null)}
      >
        ×
      </button>
    </div>
  )
}