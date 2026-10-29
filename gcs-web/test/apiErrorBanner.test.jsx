/**
 * 提示条必须真的显示、并能关闭 —— 否则"有请求失败"这个信号等于没接。
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, cleanup, act } from '@testing-library/react'
import ApiErrorBanner from '../src/components/ApiErrorBanner.jsx'
import { API_ERROR_EVENT } from '../src/api.js'

function emitError(detail) {
  act(() => {
    window.dispatchEvent(new CustomEvent(API_ERROR_EVENT, { detail }))
  })
}

describe('ApiErrorBanner', () => {
  it('无失败时不渲染', () => {
    const { container } = render(<ApiErrorBanner />)
    expect(container.firstChild).toBeNull()
    cleanup()
  })

  it('收到失败事件后显示 message 与 HTTP 状态', () => {
    render(<ApiErrorBanner />)
    emitError({ url: '/api/v1/drones', message: '数据库炸了', status: 500 })
    const banner = screen.getByTestId('api-error-banner')
    expect(banner.textContent).toContain('数据库炸了')
    expect(banner.textContent).toContain('HTTP 500')
    cleanup()
  })

  it('多次失败会累计计数', () => {
    render(<ApiErrorBanner />)
    emitError({ url: '/a', message: '失败一', status: 500 })
    emitError({ url: '/b', message: '失败二', status: 502 })
    const banner = screen.getByTestId('api-error-banner')
    expect(banner.textContent).toContain('累计 2 次')
    expect(banner.textContent).toContain('失败二')
    cleanup()
  })

  it('点关闭后消失', () => {
    render(<ApiErrorBanner />)
    emitError({ url: '/a', message: '失败', status: 500 })
    act(() => {
      screen.getByLabelText('关闭请求失败提示').click()
    })
    expect(screen.queryByTestId('api-error-banner')).toBeNull()
    cleanup()
  })

  it('8 秒后自动消失', () => {
    vi.useFakeTimers()
    try {
      render(<ApiErrorBanner />)
      act(() => {
        window.dispatchEvent(new CustomEvent(API_ERROR_EVENT, {
          detail: { url: '/a', message: '失败', status: 500 },
        }))
      })
      expect(screen.queryByTestId('api-error-banner')).not.toBeNull()
      act(() => {
        vi.advanceTimersByTime(8000)
      })
      expect(screen.queryByTestId('api-error-banner')).toBeNull()
    } finally {
      vi.useRealTimers()
      cleanup()
    }
  })
})