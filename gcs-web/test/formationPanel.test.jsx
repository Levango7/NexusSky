import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import FormationPanel from '../src/components/FormationPanel.jsx'

// vi.mock 必须留在模块顶层（vitest 会把它提升到 import 之前），放进 beforeEach 无效。
vi.mock('../src/api.js', () => ({
  commandFormation: vi.fn().mockResolvedValue({ ok: true }),
  removeFormationMember: vi.fn().mockResolvedValue({ ok: true }),
  createFormation: vi.fn().mockResolvedValue({ ok: true }),
  setFormationLights: vi.fn().mockResolvedValue({ ok: true }),
  formations: { list: vi.fn().mockResolvedValue([]) },
}))

/**
 * 编队成员的目标坐标只有一半时不得崩（2026-10-24）。
 *
 * 缺陷（`GAPS.md` 第八节第 4 条）：`FormationPanel.jsx` 的 `MemberRow` 渲染目标坐标时写的是
 *
 *     {target.lat != null
 *       ? `${target.lat.toFixed(5)}, ${target.lon.toFixed(5)} · ${target.alt ?? '--'}m`
 *       : '--'}
 *
 * 只判了 `lat`，却无条件对 `lon` 调 `.toFixed()`。WS 帧里单侧坐标是可能出现的
 * （编队刚下发、飞机还没回报经度；或某台机 GPS 只解出一半）。此时 `lon` 为
 * undefined ⇒ **渲染期抛 TypeError** ⇒ React 卸载该子树（外层有 ErrorBoundary 就是整块面板白掉，
 * 没有就是整个页面白掉）。这不是"显示不好看"，是**渲染崩溃**。
 *
 * 判据：`render()` 不抛错，且该成员行仍然渲染出来（显示 `--` 而不是崩）。
 *
 * 顺带覆盖同文件第 864 行 `LedBadge` 的亮度：`led.brightness ?? '--'` 之后再拼 `%`
 * ⇒ 缺亮度时显示 `--%`。这里断言不出现这种半截文案。
 */

beforeEach(() => {
  // 本组用例只渲染不点击；api 已在模块顶层 mock。
})

afterEach(() => {
  vi.restoreAllMocks()
})

/** 一个最小可渲染的编队：成员目标只有纬度、没有经度。 */
function formationWithHalfTarget() {
  return [
    {
      formationId: 1,
      name: '编队-半坐标',
      state: 'ACTIVE',
      members: [
        { sysid: 7, role: 'LEADER', online: true, target: { lat: 22.5431 } },
      ],
    },
  ]
}

describe('FormationPanel 成员目标坐标', () => {
  it('纬度存在、经度缺失时不得抛错（渲染崩溃）', () => {
    // 面板会自动选中第一个编队（L70-73），所以渲染即会走到 MemberRow
    expect(() => render(<FormationPanel formations={formationWithHalfTarget()} />)).not.toThrow()
  })

  it('单侧坐标的成员行仍然渲染出来，坐标列降级为 --', () => {
    render(<FormationPanel formations={formationWithHalfTarget()} />)
    // 成员行在（按 sysid 定位）
    const row = screen.getByText('#7').closest('tr')
    expect(row).not.toBeNull()
    // 该行不得出现「半个坐标」或 undefined（只在这个行内断言，避免把面板其他位置的
    // 无关文案算进来 —— 第一版把断言写成全页 queryByText(/undefined/)，结果匹配到
    // 了另一个面板字段，属于断言过宽而非组件缺陷）
    expect(row.textContent).not.toMatch(/undefined/)
    expect(row.textContent).not.toMatch(/22\.54310,\s*$/m)
    // 坐标列降级为 --
    expect(row.textContent).toContain('--')
  })

  it('完整坐标仍然正常显示（证明降级没把正常路径关掉）', () => {
    const full = [
      {
        formationId: 2,
        name: '编队-全坐标',
        state: 'ACTIVE',
        members: [
          { sysid: 8, role: 'LEADER', online: true, target: { lat: 22.5431, lon: 113.934, alt: 30 } },
        ],
      },
    ]
    render(<FormationPanel formations={full} />)
    expect(screen.getByText(/22\.54310, 113\.93400/)).toBeInTheDocument()
  })
})
