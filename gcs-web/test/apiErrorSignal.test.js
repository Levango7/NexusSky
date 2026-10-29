/**
 * API 失败必须留下信号。
 *
 * 背景：gcs-web 有 80+ 处 `.catch(() => [])` / `.catch(() => null)`，请求失败会变成
 * "空数据"，面板显示"暂无数据"——用户在界面上、开发在 devtools 里都看不出是失败。
 * 本仓 2026-10-21 就因此吃过亏（前端调 12 个不存在的端点，404 被静默吞掉）。
 *
 * 这里钉住四件事：
 * ① HTTP 非 2xx ⇒ 发 `nexus-api-error`，带 message 与 status，并照常抛错；
 * ② 网络错误 ⇒ 发事件（status=0）；
 * ③ 超时 ⇒ 发事件且错误仍是 TimeoutError（超时语义不被吃掉）；
 * ④ 调用方主动取消 ⇒ **不发**事件（否则切面板就刷错误提示）；
 * ⑤ 401 ⇒ 走既有的 nexus-auth-expired，不重复发 API 错误。
 */
import { describe, it, expect, afterEach, vi } from 'vitest'

vi.stubGlobal('location', { protocol: 'https:', host: 'gcs.example.test' })
const { api, TimeoutError, API_ERROR_EVENT } = await import('../src/api.js')

const realFetch = globalThis.fetch

function collect() {
  const seen = []
  const handler = (e) => seen.push(e.detail)
  window.addEventListener(API_ERROR_EVENT, handler)
  return { seen, stop: () => window.removeEventListener(API_ERROR_EVENT, handler) }
}

function abortError() {
  const e = new Error('The operation was aborted.')
  e.name = 'AbortError'
  return e
}

afterEach(() => {
  globalThis.fetch = realFetch
  vi.restoreAllMocks()
})

describe('api 失败信号', () => {
  it('HTTP 500 ⇒ 发事件（含 status 与 message），并抛错', async () => {
    globalThis.fetch = vi.fn(() => Promise.resolve({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ error: '数据库炸了' }),
    }))
    const { seen, stop } = collect()
    try {
      await expect(api.listDrones()).rejects.toThrow('数据库炸了')
      expect(seen).toHaveLength(1)
      expect(seen[0]).toMatchObject({ message: '数据库炸了', status: 500 })
      expect(seen[0].url).toContain('/api/v1/drones')
    } finally {
      stop()
    }
  })

  it('网络错误 ⇒ 发事件（status=0）', async () => {
    globalThis.fetch = vi.fn(() => Promise.reject(new TypeError('Failed to fetch')))
    const { seen, stop } = collect()
    try {
      await expect(api.listDrones()).rejects.toThrow('Failed to fetch')
      expect(seen).toHaveLength(1)
      expect(seen[0]).toMatchObject({ message: 'Failed to fetch', status: 0 })
    } finally {
      stop()
    }
  })

  it('超时 ⇒ 发事件且错误仍是 TimeoutError', async () => {
    globalThis.fetch = vi.fn((_url, opts = {}) => new Promise((_r, reject) => {
      opts.signal.addEventListener('abort', () => reject(abortError()), { once: true })
    }))
    const { seen, stop } = collect()
    try {
      await expect(api.getLostDrones({ timeout: 5 })).rejects.toBeInstanceOf(TimeoutError)
      expect(seen).toHaveLength(1)
      expect(seen[0].message).toContain('请求超时')
    } finally {
      stop()
    }
  })

  it('调用方主动取消 ⇒ 不发事件', async () => {
    globalThis.fetch = vi.fn((_url, opts = {}) => new Promise((_r, reject) => {
      opts.signal.addEventListener('abort', () => reject(abortError()), { once: true })
    }))
    const { seen, stop } = collect()
    const controller = new AbortController()
    try {
      const p = api.getLostDrones({ signal: controller.signal })
      controller.abort()
      await expect(p).rejects.toMatchObject({ name: 'AbortError' })
      expect(seen).toHaveLength(0)
    } finally {
      stop()
    }
  })

  it('401 ⇒ 只发 nexus-auth-expired，不重复发 API 错误', async () => {
    globalThis.fetch = vi.fn(() => Promise.resolve({
      ok: false,
      status: 401,
      json: () => Promise.resolve({}),
    }))
    const { seen, stop } = collect()
    const authEvents = []
    const onAuth = (e) => authEvents.push(e.detail)
    window.addEventListener('nexus-auth-expired', onAuth)
    try {
      await expect(api.listDrones()).rejects.toThrow('认证已过期')
      expect(authEvents).toHaveLength(1)
      expect(seen).toHaveLength(0)
    } finally {
      window.removeEventListener('nexus-auth-expired', onAuth)
      stop()
    }
  })
})