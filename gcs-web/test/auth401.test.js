import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { api, setAuthToken, clearAuthToken } from '../src/api.js'

// P0-3 回归（2026-10-12）：旧实现在 401 时 location.reload()，而"防循环"守卫判的是
// location.pathname.includes('/login')——本前端是 SPA（视图切换不产生路由），那个守卫
// 恒为假，于是：① 登录输错密码也整页刷新，错误提示被毁掉（用户只见页面闪一下）；
// ② 作业中任何后台 401 把整个页面连现场一起刷掉。
// 修法：只发 nexus-auth-expired 事件，由 useAuth 翻登录态。这里钉住"不再刷新"。
describe('401 处理：不整页刷新，改为发事件 + 抛错', () => {
  let reloadSpy
  let eventCount
  let lastEvent

  beforeEach(() => {
    clearAuthToken()
    // 形状合法即可（api.js 只校验 header.payload.signature 三段，不验签）
    setAuthToken('eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2ln')
    reloadSpy = vi.fn()
    // jsdom 的 location.reload 不可直接 spy：整体替换全局 location（只保留本测试要用的字段）
    vi.stubGlobal('location', { pathname: '/whatever-slot', reload: reloadSpy })
    eventCount = 0
    lastEvent = null
    window.addEventListener('nexus-auth-expired', (e) => {
      eventCount++
      lastEvent = e
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('401：不调 location.reload，发 nexus-auth-expired，且错误信息可被调用方显示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      status: 401,
      ok: false,
      json: async () => ({ error: 'bad credentials' }),
    }))

    await expect(api.listDrones()).rejects.toThrow(/认证已过期/)

    expect(reloadSpy).not.toHaveBeenCalled()
    expect(eventCount).toBe(1)
    expect(lastEvent.detail.message).toContain('认证已过期')
  })

  it('401 会清掉令牌（后续请求不再带旧的）', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      status: 401,
      ok: false,
      json: async () => ({ error: 'expired' }),
    }))

    await expect(api.listDrones()).rejects.toThrow()
    // 再发一次：不应再带 Authorization 头
    await expect(api.listDrones()).rejects.toThrow()
    const calls = fetch.mock.calls
    // 令牌已清：第二次请求的 fetch 参数里不再有 Authorization
    const secondInit = calls[1][1] || {}
    const headers = secondInit.headers || {}
    expect(headers.Authorization).toBeUndefined()
  })
})
