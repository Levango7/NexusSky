/**
 * 调用方取消（AbortSignal）必须真的取消请求，且不能被报成"超时"。
 *
 * 背景（2026-10-23）：TrackingPanel 的 4 条请求（lost / search-guide / track / replay）
 * 各自带 AbortController，但 api 层这 4 个包装函数原先没有 options 形参 ⇒ 实参被 JS
 * 静默丢弃；即便透传下去，jsonFetch 也会无条件用超时 controller 覆盖 options.signal
 * ⇒ abort() 对真实请求零效果。更糟的是任何 AbortError 都被翻成 TimeoutError，
 * 于是用户点了取消，界面上报的是"请求超时（15000ms）"。
 *
 * 这里同时钉住四件事：
 * ① 4 个包装函数把 options（含 signal / headers）透传到 fetch；
 * ② 调用方 abort ⇒ 请求真的被中止，错误保持 AbortError（不是 TimeoutError）；
 * ③ 超时仍然报 TimeoutError（合并 signal 不能把超时语义吃掉）；
 * ④ 请求结束后摘掉调用方的 abort 监听（长轮询下每次重挂 controller 会泄漏监听）。
 */
import { describe, it, expect, afterEach, vi } from 'vitest'

vi.stubGlobal('location', { protocol: 'https:', host: 'gcs.example.test' })
const { api, TimeoutError } = await import('../src/api.js')

const realFetch = globalThis.fetch

function abortError() {
  const e = new Error('The operation was aborted.')
  e.name = 'AbortError'
  return e
}

// 与浏览器语义一致：signal 已 abort ⇒ 立即以 AbortError 拒绝；否则等到 abort。
// 若收到的是已完成的请求则由调用方自行替换 fetch。
function stubAbortableFetch() {
  const fn = vi.fn((_url, opts = {}) => new Promise((_resolve, reject) => {
    const signal = opts.signal
    if (!signal) return
    if (signal.aborted) { reject(abortError()); return }
    signal.addEventListener('abort', () => reject(abortError()), { once: true })
  }))
  globalThis.fetch = fn
  return fn
}

const wrappers = [
  ['getLostDrones', (opts) => api.getLostDrones(opts)],
  ['getSearchGuide', (opts) => api.getSearchGuide(7, opts)],
  ['getFlightTrack', (opts) => api.getFlightTrack(7, 100, opts)],
  ['replayTrack', (opts) => api.replayTrack(7, 1000, 2000, 100, opts)],
]

afterEach(() => {
  globalThis.fetch = realFetch
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('调用方取消信号：4 个 tracking 包装函数', () => {
  it.each(wrappers)('%s 把 options 透传给 fetch（signal + headers）', async (_name, call) => {
    globalThis.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ ok: 1 }) }))
    const controller = new AbortController()

    await call({ signal: controller.signal, headers: { 'X-Probe': '1' } })

    const opts = globalThis.fetch.mock.calls[0][1]
    expect(opts.signal).toBeInstanceOf(AbortSignal)
    // 传给 fetch 的是"合并后的" controller signal，不是调用方那一个
    expect(opts.signal).not.toBe(controller.signal)
    expect(opts.signal.aborted).toBe(false)
    expect(opts.headers['X-Probe']).toBe('1')
  })

  it.each(wrappers)('%s：调用方 abort ⇒ 请求真中止，错误是 AbortError 而非 TimeoutError', async (_name, call) => {
    const fetchMock = stubAbortableFetch()
    const controller = new AbortController()

    const errP = call({ signal: controller.signal }).then(
      () => { throw new Error('不该成功——调用方已取消') },
      (e) => e,
    )
    await vi.waitFor(() => { expect(fetchMock).toHaveBeenCalledTimes(1) })

    controller.abort()
    const err = await errP

    expect(err.name).toBe('AbortError')
    expect(err).not.toBeInstanceOf(TimeoutError)
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
  })

  it('调用方 signal 已处于 aborted 状态 ⇒ 立即以 AbortError 拒绝，且 fetch 拿到已 abort 的 signal', async () => {
    const fetchMock = stubAbortableFetch()
    const controller = new AbortController()
    controller.abort()

    const err = await api.getLostDrones({ signal: controller.signal }).catch((e) => e)

    expect(err.name).toBe('AbortError')
    expect(err).not.toBeInstanceOf(TimeoutError)
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
  })

  it('超时仍报 TimeoutError（合并 signal 不能吃掉超时语义）', async () => {
    vi.useFakeTimers()
    stubAbortableFetch()
    const controller = new AbortController()

    const errP = api.getFlightTrack(7, null, { timeout: 50, signal: controller.signal }).then(
      () => { throw new Error('不该成功——应当超时') },
      (e) => e,
    )
    await vi.advanceTimersByTimeAsync(60)
    const err = await errP

    expect(err).toBeInstanceOf(TimeoutError)
    expect(err.timeoutMs).toBe(50)
    // 超时只该 abort 内部 controller，不该把调用方的 controller 也标成已取消
    expect(controller.signal.aborted).toBe(false)
  })

  it('请求结束后摘掉调用方的 abort 监听（防长轮询泄漏）', async () => {
    globalThis.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ n: 1 }) }))
    const controller = new AbortController()
    const addSpy = vi.spyOn(controller.signal, 'addEventListener')
    const rmSpy = vi.spyOn(controller.signal, 'removeEventListener')

    await expect(api.getLostDrones({ signal: controller.signal })).resolves.toEqual({ n: 1 })

    expect(addSpy).toHaveBeenCalledTimes(1)
    const handler = addSpy.mock.calls[0][1]
    expect(rmSpy).toHaveBeenCalledWith('abort', handler)
  })
})
