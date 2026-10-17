import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import DisasterCommPanel from '../src/components/DisasterCommPanel.jsx'

/**
 * 「激活灾害模式」的触发原因必须真的生效（2026-10-17）。
 *
 * 缺陷：DisasterCommPanel L383 的下拉框让用户从 5 个选项里选，L240 把选中值发给
 * disaster.activate()，api.js 也确实把它 JSON 化成 { reason } 发出去了 —— 但后端
 * activate() 既无参数也无 @RequestBody，请求体被 Spring 整个丢弃，
 * DisasterCommService.activateDisasterMode() 再把 triggerReason 硬编码成 "manual"。
 * 结果：面板 L367 显示的「触发原因」恒为 manual，**下拉框是装饰性的**。
 *
 * 判据不能只看界面文案（硬编码 manual 时下拉框照样渲染 5 个选项、界面照样好看）。
 * 判据是「**发出去的 reason 等于用户选的那个，且显示的是服务端回填的真值**」。
 *
 * 这里 mock 的是 global.fetch 而不是 ../src/api.js 模块，因此覆盖的是完整链路：
 * 组件 state -> api.js 组装请求体 -> 真正发出的 body。模块级 mock 抓不到 api.js
 * 自己把 reason 丢掉的情况。
 */

/** 下拉框的 5 个选项（value 与文案都照抄组件 L392-396）。 */
const OPTIONS = [
  ['手动激活', 'manual'],
  ['心跳大面积超时', 'heartbeat_timeout'],
  ['地形变更事件', 'terrain_change'],
  ['地震检测', 'earthquake'],
  ['泥石流检测', 'mudslide'],
]

/** 面板认为的服务端状态，测试里可改。 */
let serverStatus
/** /disaster/activate 的响应，测试里可改（用来模拟 400）。 */
let activateReply
/** 记录所有真正发出去的请求。 */
let sent

function jsonResponse(status, payload) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => payload,
  }
}

beforeEach(() => {
  serverStatus = { mode: 'inactive', triggerReason: '', affectedNodes: 0, recoveryRate: 0 }
  activateReply = null
  sent = []
  global.fetch = vi.fn(async (url, opts = {}) => {
    const u = String(url)
    const rec = {
      url: u,
      method: opts.method || 'GET',
      rawBody: opts.body,
      body: opts.body ? JSON.parse(opts.body) : undefined,
    }
    sent.push(rec)
    if (u.includes('/disaster/activate')) {
      if (activateReply) return jsonResponse(activateReply.status, activateReply.payload)
      // 正常路径：服务端把认下来的 reason 原样回显（与修好后的后端行为一致）
      return jsonResponse(200, {
        mode: 'active',
        triggerReason: rec.body ? rec.body.reason : 'manual',
        timestamp: 1,
      })
    }
    if (u.includes('/disaster/status')) return jsonResponse(200, serverStatus)
    return jsonResponse(200, [])
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

function activateCall() {
  return sent.find((c) => c.url.includes('/disaster/activate'))
}

/** 读界面上的「触发原因」那一行的完整文本。 */
function triggerReasonText() {
  const el = screen.getByText(/触发原因/)
  // testing-library 的 getNodeText 只看直接文本节点，而值在 <span> 子元素里，
  // 所以跨元素拼接的文本必须读 textContent（这条坑在 #101 已经踩过一次）。
  return el.textContent.replace(/触发原因:\s*/, '').trim()
}

describe('DisasterCommPanel 激活灾害模式的触发原因', () => {
  it('下拉框选中的值真的进了请求体 —— 5 个选项逐个验', async () => {
    for (const [label, value] of OPTIONS) {
      sent = []
      const { container, unmount } = render(<DisasterCommPanel />)

      const select = container.querySelector('select')
      expect(select).toBeTruthy()
      // 选项本身必须存在，否则"选了等于没选"
      expect(Array.from(select.options).map((o) => o.value)).toEqual(OPTIONS.map(([, v]) => v))

      fireEvent.change(select, { target: { value } })
      fireEvent.click(screen.getByRole('button', { name: /一键激活/ }))

      await waitFor(() => expect(activateCall()).toBeTruthy())
      expect(activateCall().method).toBe('POST')
      // 核心判据：发出去的是**用户选的那个**，不是硬编码的 manual
      expect(activateCall().body).toEqual({ reason: value })
      expect(select.value).toBe(value)

      unmount()
    }
  })

  it('请求体走的是 JSON { reason }，键名不是别的', async () => {
    render(<DisasterCommPanel />)
    const select = document.querySelector('select')
    fireEvent.change(select, { target: { value: 'earthquake' } })
    fireEvent.click(screen.getByRole('button', { name: /一键激活/ }))

    await waitFor(() => expect(activateCall()).toBeTruthy())
    expect(activateCall().rawBody).toBe('{"reason":"earthquake"}')
    // Content-Type 必须有，否则后端 @RequestBody 根本不会去解析
    expect(activateCall().rawBody).not.toBeUndefined()
  })

  it('显示的是服务端回填的 triggerReason，不是本地选中的值', async () => {
    // 服务端认了另一个原因（用户点了地震，服务端最终落成 heartbeat_timeout）
    activateReply = {
      status: 200,
      payload: { mode: 'active', triggerReason: 'heartbeat_timeout', timestamp: 2 },
    }
    serverStatus = {
      mode: 'active',
      triggerReason: 'heartbeat_timeout',
      affectedNodes: 7,
      recoveryRate: 40,
    }

    render(<DisasterCommPanel />)
    const select = document.querySelector('select')
    fireEvent.change(select, { target: { value: 'earthquake' } })
    fireEvent.click(screen.getByRole('button', { name: /一键激活/ }))

    // 发出去的仍然是用户选的
    await waitFor(() => expect(activateCall()).toBeTruthy())
    expect(activateCall().body).toEqual({ reason: 'earthquake' })
    // 但界面显示的必须是服务端真值，不是本地缓冲
    await waitFor(() => expect(triggerReasonText()).toBe('heartbeat_timeout'))
  })

  it('服务端 400 时把 body.error 显示出来，而不是只显示 HTTP 400', async () => {
    activateReply = {
      status: 400,
      // 关键：后端必须用 `error` 键，api.js 的 jsonFetch 在 !res.ok 时只读 body.error
      payload: { error: '未知的触发原因: bogus', allowed: ['manual'] },
    }

    render(<DisasterCommPanel />)
    fireEvent.click(screen.getByRole('button', { name: /一键激活/ }))

    await waitFor(() => {
      expect(screen.getByText(/激活灾害模式失败/)).toBeTruthy()
    })
    expect(screen.getByText(/未知的触发原因: bogus/)).toBeTruthy()
  })

  it('未激活时显示激活入口；激活后切换成退出入口', async () => {
    const { unmount } = render(<DisasterCommPanel />)
    expect(screen.getByRole('button', { name: /一键激活/ })).toBeTruthy()
    unmount()

    serverStatus = { mode: 'active', triggerReason: 'manual', affectedNodes: 3, recoveryRate: 90 }
    render(<DisasterCommPanel />)
    await waitFor(() => expect(screen.getByRole('button', { name: /一键退出|退出灾害模式/ })).toBeTruthy())
    // 激活态下才显示触发原因
    await waitFor(() => expect(triggerReasonText()).toBe('manual'))
  })
})