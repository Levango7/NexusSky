/**
 * api.js 纯函数/会话状态单测（vitest）。
 *
 * 环境说明：api.js 模块加载时会执行 getWsUrl()（读取浏览器 location）并尝试
 * sessionStorage 恢复 token——node 测试环境没有这两个全局。sessionStorage 的
 * 访问在源码里已有 try/catch 兜底；location 没有，所以必须在动态 import 之前
 * 用 vi.stubGlobal 补桩（ESM 静态 import 会被提升到打桩语句之前执行，故只能
 * 动态 import）。
 *
 * 覆盖面：JWT 格式校验与 token 会话、getWsUrl 协议/token 传递/编码、
 * budgetMode 规范化与面板裁剪（档位包含关系是产品口径，裁错面板属于
 * 功能回退）。
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

// 必须先于 import('./api') 执行（见文件头说明）
vi.stubGlobal('location', { protocol: 'https:', host: 'gcs.example.test' })
const api = await import('./api')

describe('token 会话管理', () => {
  beforeEach(() => {
    api.clearAuthToken()
  })

  it('setAuthToken 拒绝非三段结构（非 JWT 格式）', () => {
    expect(() => api.setAuthToken('abc')).toThrow()
    expect(() => api.setAuthToken('a.b')).toThrow()
    expect(() => api.setAuthToken('')).toThrow()
    expect(() => api.setAuthToken('a..c')).toThrow() // 空段
    expect(api.isAuthenticated()).toBe(false)
  })

  it('setAuthToken 接受三段 JWT，getAuthToken/isAuthenticated 生效', () => {
    api.setAuthToken('aa.bb.cc')
    expect(api.getAuthToken()).toBe('aa.bb.cc')
    expect(api.isAuthenticated()).toBe(true)
  })

  it('clearAuthToken 后回到未认证态', () => {
    api.setAuthToken('aa.bb.cc')
    api.clearAuthToken()
    expect(api.getAuthToken()).toBeNull()
    expect(api.isAuthenticated()).toBe(false)
  })

  it('logout 等价于 clearAuthToken', () => {
    api.setAuthToken('aa.bb.cc')
    api.logout()
    expect(api.isAuthenticated()).toBe(false)
  })
})

describe('getWsUrl', () => {
  afterEach(() => {
    vi.stubGlobal('location', { protocol: 'https:', host: 'gcs.example.test' })
    api.clearAuthToken()
  })

  it('https 页面 → wss，无 token 不带查询串', () => {
    vi.stubGlobal('location', { protocol: 'https:', host: 'h1.example.test' })
    expect(api.getWsUrl()).toBe('wss://h1.example.test/ws/telemetry')
  })

  it('http 页面 → ws', () => {
    vi.stubGlobal('location', { protocol: 'http:', host: 'h2.example.test' })
    expect(api.getWsUrl()).toBe('ws://h2.example.test/ws/telemetry')
  })

  it('有 token 时以 ?token= 携带，特殊字符经 encodeURIComponent 编码', () => {
    vi.stubGlobal('location', { protocol: 'https:', host: 'h3.example.test' })
    api.setAuthToken('a+b.c=d.e') // 三段均非空，通过格式校验
    expect(api.getWsUrl()).toBe('wss://h3.example.test/ws/telemetry?token=a%2Bb.c%3Dd.e')
  })

  it('模块级常量 wsUrl 按加载时桩环境构建', () => {
    expect(api.wsUrl).toBe('wss://gcs.example.test/ws/telemetry')
  })
})

describe('normalizeBudgetMode', () => {
  let warn
  beforeEach(() => {
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
  })
  afterEach(() => {
    warn.mockRestore()
  })

  it('null/undefined 原样返回 null（完整版），不告警', () => {
    expect(api.normalizeBudgetMode(null)).toBeNull()
    expect(api.normalizeBudgetMode(undefined)).toBeNull()
    expect(warn).not.toHaveBeenCalled()
  })

  it('五个合法档位原样返回，不告警', () => {
    for (const m of ['toy', 'standard', 'advanced', 'emergency-toy', 'emergency-standard']) {
      expect(api.normalizeBudgetMode(m)).toBe(m)
    }
    expect(warn).not.toHaveBeenCalled()
  })

  it('未知值（含空串）fallback 到 standard 并 console.warn', () => {
    expect(api.normalizeBudgetMode('bogus')).toBe('standard')
    expect(api.normalizeBudgetMode('')).toBe('standard')
    expect(warn).toHaveBeenCalledTimes(2)
    expect(warn.mock.calls[0][0]).toContain('bogus')
  })
})

describe('预算档位面板裁剪', () => {
  it('null（完整版）= 全部可用', () => {
    expect(api.getAvailablePanels(null)).toBeNull()
    expect(api.isPanelAvailable('any-panel', null)).toBe(true)
  })

  it('百元级面板恰为基础四项', () => {
    expect(api.TOY_PANELS).toEqual(['telemetry', 'camera', 'map', 'status'])
  })

  it('档位包含关系：toy ⊂ standard ⊂ advanced，standard ⊂ emergency-standard', () => {
    for (const p of api.TOY_PANELS) expect(api.STANDARD_PANELS).toContain(p)
    for (const p of api.STANDARD_PANELS) expect(api.ADVANCED_PANELS).toContain(p)
    for (const p of api.STANDARD_PANELS) expect(api.EMERGENCY_STANDARD_PANELS).toContain(p)
  })

  it('应急档位特有面板存在（应急百元级含 mesh/thermal，应急千元级含 videofusion）', () => {
    expect(api.EMERGENCY_TOY_PANELS).toContain('mesh')
    expect(api.EMERGENCY_TOY_PANELS).toContain('emergency')
    expect(api.EMERGENCY_TOY_PANELS).toContain('thermal')
    expect(api.EMERGENCY_STANDARD_PANELS).toContain('videofusion')
    expect(api.EMERGENCY_STANDARD_PANELS).toContain('disastercomm')
  })

  it('getAvailablePanels 按档位返回对应列表', () => {
    expect(api.getAvailablePanels('toy')).toBe(api.TOY_PANELS)
    expect(api.getAvailablePanels('standard')).toBe(api.STANDARD_PANELS)
    expect(api.getAvailablePanels('advanced')).toBe(api.ADVANCED_PANELS)
    expect(api.getAvailablePanels('emergency-toy')).toBe(api.EMERGENCY_TOY_PANELS)
    expect(api.getAvailablePanels('emergency-standard')).toBe(api.EMERGENCY_STANDARD_PANELS)
  })

  it('isPanelAvailable 按档位裁剪：toy 无 formation，standard 有', () => {
    expect(api.isPanelAvailable('telemetry', 'toy')).toBe(true)
    expect(api.isPanelAvailable('formation', 'toy')).toBe(false)
    expect(api.isPanelAvailable('formation', 'standard')).toBe(true)
  })
})

describe('SSE 流令牌（alarmStreamUrl / fetchStreamToken / surveillanceStreamUrl）', () => {
  beforeEach(() => {
    vi.stubGlobal('location', { protocol: 'https:', host: 'test.local' })
  })

  it('alarmStreamUrl(token) 构造含 ?streamToken= 的绝对 URL，encodeURIComponent 编码', async () => {
    const api2 = await import('./api')
    const url = api2.alarmStreamUrl('t+ok.en')
    expect(url).toBe('https://test.local/api/v1/alarms/stream?streamToken=t%2Bok.en')
  })

  it('alarmStreamUrl 无 token 时仍构造基础 URL（无需令牌时的回退形状）', async () => {
    const api2 = await import('./api')
    expect(api2.alarmStreamUrl()).toBe('https://test.local/api/v1/alarms/stream')
  })

  it('surveillanceStreamUrl 也接受 deviceId + streamToken 构造', async () => {
    const api2 = await import('./api')
    expect(api2.surveillanceStreamUrl('dev-1', 's_tok_1'))
      .toBe('https://test.local/api/v1/surveillance/devices/dev-1/events?streamToken=s_tok_1')
  })

  it('fetchStreamToken 是异步函数（可调用）', async () => {
    const api2 = await import('./api')
    expect(typeof api2.fetchStreamToken).toBe('function')
  })
})

/**
 * MJPEG 预览 URL（<img src> 用）。
 *
 * <p>这条 URL 的口径是一条**安全**决定：`?` 里只放短命单次令牌，绝不放长效 JWT。
 * 断言必须钉住参数名 —— 只断言"能生成一个 URL"的话，把 `streamToken` 改成 `jwt`
 * 也能全绿，而那等于把 bearer 暴露给所有能看到访问日志/Referer 的人。
 * 这也是它与 SSE 两条流共用 StreamTokenService 的原因，不是各写一套。
 */
describe('mjpegPreviewUrl（<img> 预览，凭据口径）', () => {
  it('凭据走 ?streamToken=，不带长效 JWT 参数名', async () => {
    const api2 = await import('./api')
    const url = api2.mjpegPreviewUrl('cam 1', 's_tok_1', 5)
    expect(url).toBe('https://test.local/api/v1/surveillance/devices/cam%201/mjpeg'
      + '?streamToken=s_tok_1&fps=5')
    expect(url).not.toMatch(/[?&]token=/)
    expect(url).not.toMatch(/[?&]jwt=/)
    expect(url).not.toMatch(/[?&]authorization=/i)
  })

  it('token 与 deviceId 都做 URL 编码（token 常含 + / = 等字符）', async () => {
    const api2 = await import('./api')
    const url = api2.mjpegPreviewUrl('cam-1', 'a+b/c=d', 0)
    expect(url).toContain('streamToken=a%2Bb%2Fc%3Dd')
    // fps<=0 时不带 fps 参数（后端有默认值）
    expect(url).not.toContain('fps=')
  })

  it('无令牌时仍返回可用地址（dev 模式链上全放行）', async () => {
    const api2 = await import('./api')
    const url = api2.mjpegPreviewUrl('cam-1', '', 5)
    expect(url).toBe('https://test.local/api/v1/surveillance/devices/cam-1/mjpeg?fps=5')
    expect(url).not.toContain('streamToken')
  })
})

/**
 * link-sim 运行时控制（P2 #20）。
 *
 * 这组断言钉的是"面板真的会打到控制端点"：方法必须是 PATCH（后端只映射了
 * @PatchMapping，写成 POST 会 405）、body 是 JSON、错误消息取后端 result。
 * 组件层不重复测滑块（渲染细节易变），这里测的是前后端之间的 HTTP 契约。
 */
describe('link-sim 运行时控制 API（P2 #20）', () => {
  const realFetch = globalThis.fetch

  afterEach(() => {
    vi.stubGlobal('fetch', realFetch)
  })

  function stubFetch(response) {
    const calls = []
    vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
      calls.push({ url, options })
      return response
    }))
    return calls
  }

  it('getLinkSimControl 读 GET /api/v1/link-sim/control', async () => {
    const calls = stubFetch({
      ok: true,
      status: 200,
      json: async () => ({ profile: 'lte', mode: 'profile', profiles: ['lan', 'lte'] }),
    })

    const view = await api.getLinkSimControl()

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe('/api/v1/link-sim/control')
    expect(calls[0].options.method).toBeUndefined()
    expect(view.profile).toBe('lte')
  })

  it('patchLinkSimControl 用 PATCH + JSON body（POST 会被后端 405）', async () => {
    const calls = stubFetch({
      ok: true,
      status: 200,
      json: async () => ({ profile: 'lte', mode: 'custom' }),
    })

    await api.patchLinkSimControl({ direction: 'uplink', delayMs: 120 })

    expect(calls[0].url).toBe('/api/v1/link-sim/control')
    expect(calls[0].options.method).toBe('PATCH')
    expect(calls[0].options.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(calls[0].options.body)).toEqual({ direction: 'uplink', delayMs: 120 })
  })

  it('后端 400 的 result 作为异常消息（不能只剩 "HTTP 400"）', async () => {
    stubFetch({
      ok: false,
      status: 400,
      json: async () => ({ status: 'error', result: "'delayMs' out of range [0.0, 5000.0]: -5.0" }),
    })

    await expect(api.patchLinkSimControl({ delayMs: -5 }))
      .rejects.toThrow("'delayMs' out of range")
  })

  it('控制通道关闭（503）时错误消息透传启动提示', async () => {
    stubFetch({
      ok: false,
      status: 503,
      json: async () => ({
        status: 'error',
        result: 'link-sim control unreachable at http://127.0.0.1:14601/profile; start link-sim with --control-port',
      }),
    })

    await expect(api.getLinkSimControl()).rejects.toThrow('--control-port')
  })
})
