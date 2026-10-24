import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import RocPanel from '../src/components/RocPanel.jsx'

/**
 * RocPanel 的「席位选择」不得把空选项当成 0 号席位（2026-10-24）。
 *
 * 缺陷（`GAPS.md` 第八节第 5 条）：下拉框 `<select>` 的第一项是
 * `<option value="">选择席位…</option>`，onChange 里写的是
 * `setSeatId(Number(e.target.value))` —— 而 **`Number('') === 0`**。
 * 于是操作员把席位选回「选择席位…」时，`seatId` 变成 **0**，
 * `load()` 的守卫 `seatRef.current != null`（0 也满足）便去拉
 * `/api/v1/roc/seats/0` 与 `/0/incidents` —— 而后端席位 ID 从 **1** 起分配
 * （`RocSeatService.idSeq = new AtomicLong(1)`），0 永远不是合法席位，
 * 两个请求必然 4xx；组件里 `if (res.ok)` 没有 else 分支，失败全程静默。
 *
 * 判据不是「界面上有没有报错」（静默失败时界面一切正常），而是
 * **有没有真的发出 `/seats/0` 这个请求**。所以这里 mock `global.fetch`
 * 记录真实 URL，而不是断言 UI 文案。
 *
 * 两个用例成对：① 选回空 ⇒ 不得出现 /seats/0；② 选真实席位 ⇒ 必须出现
 * /seats/<id>（证明修复没有把正常选择一起关掉）。
 */

/** 记录所有真正发出的 URL。 */
let calls

function installFetch(seats) {
  calls = []
  global.fetch = vi.fn((url) => {
    const u = String(url)
    calls.push(u)
    if (u.endsWith('/api/v1/roc/seats')) {
      return Promise.resolve({ ok: true, json: async () => seats })
    }
    if (/\/api\/v1\/roc\/seats\/\d+$/.test(u)) {
      return Promise.resolve({ ok: true, json: async () => ({ seatId: 1, fleet: [], maxDrones: 9 }) })
    }
    if (/\/api\/v1\/roc\/seats\/\d+\/incidents$/.test(u)) {
      return Promise.resolve({ ok: true, json: async () => [] })
    }
    return Promise.resolve({ ok: true, json: async () => ({}) })
  })
}

beforeEach(() => {
  installFetch([
    { seatId: 1, operatorName: 'op-1', maxDrones: 9 },
    { seatId: 2, operatorName: 'op-2', maxDrones: 9 },
  ])
})

afterEach(() => {
  vi.restoreAllMocks()
  delete global.fetch
})

/** 等到席位列表渲染出来（说明首轮 load 已完成）。 */
async function waitForSeats() {
  await waitFor(() => {
    expect(screen.getByRole('option', { name: /op-1/ })).toBeInTheDocument()
  })
}

describe('RocPanel 席位选择', () => {
  it('选回「选择席位…」（空值）时不得请求 0 号席位', async () => {
    render(<RocPanel />)
    await waitForSeats()

    const select = screen.getByRole('combobox')
    // 先选一个真实席位，再选回空 —— 复现操作员的真实动作序列
    fireEvent.change(select, { target: { value: '1' } })
    await waitFor(() => {
      expect(calls.some((u) => /\/seats\/1$/.test(u))).toBe(true)
    })

    calls.length = 0
    fireEvent.change(select, { target: { value: '' } })
    // 给 load() 一个完整的执行窗口（含一次轮询周期之外的时间）
    await new Promise((r) => setTimeout(r, 50))

    const zeroHits = calls.filter((u) => /\/seats\/0(\/|$)/.test(u))
    expect(zeroHits, `不该请求 0 号席位，实际发了：${JSON.stringify(zeroHits)}`).toEqual([])
  })

  it('选真实席位时仍然会请求该席位（证明修复没把正常选择关掉）', async () => {
    render(<RocPanel />)
    await waitForSeats()

    const select = screen.getByRole('combobox')
    calls.length = 0
    fireEvent.change(select, { target: { value: '2' } })

    await waitFor(() => {
      expect(calls.some((u) => /\/seats\/2$/.test(u))).toBe(true)
    })
  })
})
