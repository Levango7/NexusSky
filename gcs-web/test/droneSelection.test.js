import { describe, it, expect } from 'vitest'
import { isDroneSelected } from '../src/utils/droneSelection.js'

/**
 * 回归测试：MapView 选中态判定
 *
 * 这条契约在 2026-10-01 之前是**隐式**的，且实现是错的：
 *   App.jsx 传的 selected 是无人机**对象**（drones.find(...) 的结果），
 *   而 MapView 两处写的是 `selected === sysid`（对象 === 数字，恒 false）。
 * 后果：「选中机标记高亮」与「选中机轨迹渐变」两段代码从未执行过，
 * 且没有任何运行时信号——日志、告警、测试都没有。
 *
 * 下面每条断言都直接对应这个 bug 的一个失效面。
 */
describe('isDroneSelected', () => {
  it('selected 是对象时按 sysid 判定（这正是原 bug 的形状）', () => {
    const selected = { sysid: 7, mode: 'MISSION', armed: true }
    expect(isDroneSelected(selected, 7)).toBe(true)
  })

  it('sysid 为数字字符串时也能匹配（Object.keys(multiTracks) 出来的是字符串）', () => {
    const selected = { sysid: 7 }
    expect(isDroneSelected(selected, '7')).toBe(true)
  })

  it('非选中机返回 false', () => {
    const selected = { sysid: 7 }
    expect(isDroneSelected(selected, 3)).toBe(false)
    expect(isDroneSelected(selected, 8)).toBe(false)
  })

  it('未选择设备（undefined）时全部为 false，不抛异常', () => {
    expect(isDroneSelected(undefined, 1)).toBe(false)
    expect(isDroneSelected(null, 1)).toBe(false)
  })

  it('传入裸数字 selected 时返回 false——契约是「对象」，不是静默兼容', () => {
    // 刻意不兼容裸数字。若哪天有人把 selected 改回数字，
    // 这里会立刻红，而不是像原 bug 那样悄悄让高亮全部消失。
    expect(isDroneSelected(7, 7)).toBe(false)
  })

  it('对象缺 sysid 字段时返回 false，不与 undefined 比较', () => {
    expect(isDroneSelected({ mode: 'MISSION' }, 7)).toBe(false)
    expect(isDroneSelected({ sysid: null }, null)).toBe(false)
  })

  it('sysid 为 null/undefined 时返回 false', () => {
    expect(isDroneSelected({ sysid: 7 }, null)).toBe(false)
    expect(isDroneSelected({ sysid: 7 }, undefined)).toBe(false)
  })

  it('机队里多机场景：只有真正选中的那架为 true', () => {
    const selected = { sysid: 3 }
    const fleet = [1, 2, 3, 4, 5].map((sysid) => isDroneSelected(selected, sysid))
    expect(fleet).toEqual([false, false, true, false, false])
  })
})
