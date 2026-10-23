import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import RouteTemplatePanel from '../src/components/RouteTemplatePanel.jsx'
import { getAuthToken } from '../src/api.js'

/**
 * RouteTemplatePanel 组件级测试（2026-10-24 补口）。
 *
 * 这个面板的价值全在**请求体形状**上：四种模板类型各自带不同的参数键，
 * 字段名拼错后端不会报错、只会给一条"看起来对"的航线。前端此前一条测试都没有，
 * 所以这些形状完全没人钉。这里用 fetch 桩把真实发出的 body 抓下来逐键断言。
 */

vi.mock('../src/api.js', () => ({
  getAuthToken: vi.fn(() => null),
}))

const GEO = '[[22.5908,113.9345],[22.5909,113.9345]]'

function okResponse(payload) {
  return { ok: true, status: 200, json: async () => payload }
}

function errResponse(status, payload) {
  return { ok: false, status, json: async () => payload ?? {} }
}

const GEN_RESULT = {
  waypointCount: 2,
  estKm: 0.31,
  altM: 60,
  legs: [{}, {}],
  waypoints: [
    { cmd: 'NAV_TAKEOFF', lat: 22.5908, lon: 113.9345, alt: 60, holdTime: 0 },
    { cmd: 'NAV_WAYPOINT', lat: 22.5909, lon: 113.9345, alt: 60, holdTime: 5 },
  ],
}

let fetchMock

beforeEach(() => {
  fetchMock = vi.fn().mockResolvedValue(okResponse(GEN_RESULT))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

function panel(props = {}) {
  return render(<RouteTemplatePanel drone={{ sysid: 7 }} {...props} />)
}

function bodyOfCall(index = 0) {
  return JSON.parse(fetchMock.mock.calls[index][1].body)
}

function headersOfCall(index = 0) {
  return fetchMock.mock.calls[index][1].headers
}

function urlOfCall(index = 0) {
  return fetchMock.mock.calls[index][0]
}

async function clickGenerate(container) {
  fireEvent.click(screen.getByText('生成预览'))
  await waitFor(() => expect(fetchMock).toHaveBeenCalled())
}

// 汇总行是 `<b>2</b> 航点 · 预计 <b>0.31</b> km`，文本被 <b> 拆成多段，
// 所以"预览出来了"这件事用航点表的行数判，不用 getByText 精确匹配。
async function expectPreviewReady() {
  await waitFor(() => expect(document.querySelectorAll('tbody tr')).toHaveLength(2))
}

describe('四种模板的请求体形状（字段名与后端契约）', () => {
  it('电力杆塔：type/altM + 环绕三参 + towers 数组，且按顺序编号 T001/T002', async () => {
    panel()
    await clickGenerate()
    const body = bodyOfCall(0)
    expect(body.type).toBe('tower')
    expect(body.altM).toBe(60)
    expect(body.orbitRadiusM).toBe(25)
    expect(body.orbitPoints).toBe(4)
    expect(body.hoverSec).toBe(5)
    expect(body.towers).toHaveLength(2)
    expect(body.towers[0]).toMatchObject({ towerNo: 'T001', lat: 22.5908, lon: 113.9345 })
    expect(body.towers[1].towerNo).toBe('T002')
    // 不该串进别的类型的字段
    expect(body).not.toHaveProperty('lineSpacingM')
    expect(body).not.toHaveProperty('offsetM')
    expect(body).not.toHaveProperty('polygon')
    expect(urlOfCall(0)).toBe('/api/v1/route-templates/generate')
  })

  it('光伏网格：走 polygon + lineSpacingM/directionDeg，不带杆塔参数', async () => {
    panel()
    fireEvent.click(screen.getByText('光伏网格'))
    await clickGenerate()
    const body = bodyOfCall(0)
    expect(body.type).toBe('solar')
    expect(body.lineSpacingM).toBe(30)
    expect(body.directionDeg).toBe(0)
    expect(body.polygon).toHaveLength(2)
    expect(body).not.toHaveProperty('towers')
    expect(body).not.toHaveProperty('orbitRadiusM')
  })

  it('管线带状：走 line + 只有 stepM', async () => {
    panel()
    fireEvent.click(screen.getByText('管线带状'))
    await clickGenerate()
    const body = bodyOfCall(0)
    expect(body.type).toBe('pipeline')
    expect(body.stepM).toBe(50)
    expect(body.line).toHaveLength(2)
    expect(body).not.toHaveProperty('polygon')
    expect(body).not.toHaveProperty('offsetM')
  })

  it('河湖岸线：polygon + stepM + offsetM（唯一带 offset 的类型）', async () => {
    panel()
    fireEvent.click(screen.getByText('河湖岸线'))
    await clickGenerate()
    const body = bodyOfCall(0)
    expect(body.type).toBe('shoreline')
    expect(body.stepM).toBe(50)
    expect(body.offsetM).toBe(0)
    expect(body.polygon).toHaveLength(2)
  })

  it('输入框里的数字字符串在请求体里必须是数字（后端按数值解析）', async () => {
    panel()
    const altInput = screen.getByDisplayValue('60')
    fireEvent.change(altInput, { target: { value: '120' } })
    await clickGenerate()
    expect(bodyOfCall(0).altM).toBe(120)
    expect(typeof bodyOfCall(0).altM).toBe('number')
  })
})

describe('生成预览的失败与鉴权路径', () => {
  it('坐标 JSON 语法错：报"解析失败"，且**根本不发请求**', async () => {
    panel()
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '[[1,2],' } })
    fireEvent.click(screen.getByText('生成预览'))
    await waitFor(() => expect(screen.getByText(/坐标 JSON 解析失败/)).toBeTruthy())
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('后端 4xx：错误文案带上状态码与 result，不静默', async () => {
    fetchMock.mockResolvedValueOnce(errResponse(400, { result: '参数越界' }))
    panel()
    await clickGenerate()
    await waitFor(() => expect(screen.getByText(/生成失败（400）：参数越界/)).toBeTruthy())
  })

  it('响应体不是 JSON 也不能崩，仍报出状态码', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 502, json: async () => { throw new Error('boom') } })
    panel()
    await clickGenerate()
    await waitFor(() => expect(screen.getByText(/生成失败（502）/)).toBeTruthy())
  })

  it('有 token 时带 Bearer 头与 JSON Content-Type', async () => {
    getAuthToken.mockReturnValue('jwt-token')
    panel()
    await clickGenerate()
    expect(headersOfCall(0).Authorization).toBe('Bearer jwt-token')
    expect(headersOfCall(0)['Content-Type']).toBe('application/json')
  })

  it('没 token 时不带 Authorization（而不是带 "Bearer null"）', async () => {
    getAuthToken.mockReturnValue(null)
    panel()
    await clickGenerate()
    expect(headersOfCall(0).Authorization).toBeUndefined()
    expect(headersOfCall(0)['Content-Type']).toBe('application/json')
  })

  it('成功渲染航点数/里程/高度，并逐行画出航点表', async () => {
    panel()
    await clickGenerate()
    await expectPreviewReady()
    expect(document.body.textContent).toContain('航点')
    expect(screen.getByText(/0\.31/)).toBeTruthy()
    const rows = document.querySelectorAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('NAV_TAKEOFF')
    // 经纬度按 6 位小数呈现（丢精度会让操作员误判航点位置）
    expect(rows[0].textContent).toContain('22.590800')
  })

  it('后端说成功却没带 waypoints：渲染 0 行而不是抛 TypeError', async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ waypointCount: 3, estKm: 1.2, altM: 60 }))
    panel()
    await clickGenerate()
    await waitFor(() => expect(document.querySelectorAll('tbody tr')).toHaveLength(0))
  })
})

describe('下发任务（两阶段：mission 上传 + start_mission）', () => {
  async function generateThenDispatch() {
    panel()
    await clickGenerate()
    await expectPreviewReady()
    fireEvent.click(screen.getByText(/下发到 Drone-7/))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
  }

  it('下发体是 { items: 预览返回的 waypoints }，端点带 sysid', async () => {
    await generateThenDispatch()
    expect(urlOfCall(1)).toBe('/api/v1/drones/7/mission')
    expect(bodyOfCall(1).items).toHaveLength(2)
    expect(bodyOfCall(1).items[0].cmd).toBe('NAV_TAKEOFF')
  })

  it('上传成功但启动失败：必须明说"任务已上传但启动失败"并给出状态码与处置建议', async () => {
    fetchMock
      .mockResolvedValueOnce(okResponse(GEN_RESULT))
      .mockResolvedValueOnce(okResponse({ ok: true }))
      .mockResolvedValueOnce(errResponse(409))
    panel()
    await clickGenerate()
    await expectPreviewReady()
    fireEvent.click(screen.getByText(/下发到 Drone-7/))
    await waitFor(() =>
      expect(screen.getByText(/任务已上传但启动失败（409）——先 ARM/)).toBeTruthy()
    )
  })

  it('两阶段都成功 ⇒ 清掉错误态', async () => {
    await generateThenDispatch()
    expect(screen.queryByText(/失败/)).toBeNull()
  })

  it('mission 上传就失败 ⇒ 绝不再发 start_mission（不能对没任务的机器喊启动）', async () => {
    fetchMock
      .mockResolvedValueOnce(okResponse(GEN_RESULT))
      .mockResolvedValueOnce(errResponse(500))
    panel()
    await clickGenerate()
    await expectPreviewReady()
    fireEvent.click(screen.getByText(/下发到 Drone-7/))
    await waitFor(() => expect(screen.getByText(/任务下发失败（500）/)).toBeTruthy())
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

describe('面板状态机的两条护栏', () => {
  it('没生成预览时下发按钮是 disabled；生成后才可用', async () => {
    panel()
    const dispatchBtn = screen.getByText(/下发到 Drone-7/).closest('button')
    expect(dispatchBtn.disabled).toBe(true)
    await clickGenerate()
    await waitFor(() => expect(dispatchBtn.disabled).toBe(false))
  })

  it('没选无人机时下发按钮不可用，且提示"先在左侧选择无人机"', async () => {
    panel({ drone: null })
    const btn = screen.getByText(/未选机/).closest('button')
    expect(btn.disabled).toBe(true)
    expect(btn.getAttribute('title')).toContain('先在左侧选择无人机')
  })

  it('切换模板类型会清掉上一次预览——防止拿杆塔的航点表去下发光伏任务', async () => {
    panel()
    await clickGenerate()
    await expectPreviewReady()
    fireEvent.click(screen.getByText('管线带状'))
    await waitFor(() => expect(document.querySelectorAll('tbody tr')).toHaveLength(0))
    expect(screen.getByText(/下发到 Drone-7/).closest('button').disabled).toBe(true)
  })

  it('请求飞行中 busy=true ⇒ 两个按钮都禁用（防重复下发）', async () => {
    let resolveFirst
    fetchMock.mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
    panel()
    fireEvent.click(screen.getByText('生成预览'))
    // fireEvent 自带 act()，busy 的 setState 到这里已经冲刷完
    expect(screen.getByText('生成预览').closest('button').disabled).toBe(true)
    expect(screen.getByText(/下发到 Drone-7/).closest('button').disabled).toBe(true)
    resolveFirst(okResponse(GEN_RESULT))
    await expectPreviewReady()
    expect(screen.getByText('生成预览').closest('button').disabled).toBe(false)
  })
})
