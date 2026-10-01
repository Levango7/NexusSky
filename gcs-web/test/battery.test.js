import { describe, it, expect } from 'vitest'
import {
  battClass,
  battColor,
  battColorVar,
  battLevel,
  BATT_OK,
  BATT_WARN,
  BATT_CRIT,
  BATT_CRIT_AT,
  BATT_WARN_AT,
} from '../src/utils/battery'

describe('battery: battClass', () => {
  it('低电量（≤20）判为严重', () => {
    expect(battClass(0)).toBe('batt-crit')
    expect(battClass(10)).toBe('batt-crit')
    expect(battClass(20)).toBe('batt-crit') // 边界含 20
  })

  it('中低电量（20<x≤40）判为警告', () => {
    expect(battClass(20.1)).toBe('batt-warn')
    expect(battClass(30)).toBe('batt-warn')
    expect(battClass(40)).toBe('batt-warn') // 边界含 40
  })

  it('高电量（>40）判为正常', () => {
    expect(battClass(40.1)).toBe('batt-ok')
    expect(battClass(100)).toBe('batt-ok')
  })

  it('无数据显示为空类，而不是"电量充足"', () => {
    // 这是本组断言里最要紧的一条：把"没数据"画成绿色是监控界面最典型的误导
    expect(battClass(null)).toBe('')
    expect(battClass(undefined)).toBe('')
    expect(battClass('')).not.toBe('batt-ok')
    expect(battClass(null)).not.toBe('batt-ok')
  })
})

describe('battery: battColor', () => {
  it('与 battClass 用同一套阈值', () => {
    const cases = [
      [0, 'batt-crit', BATT_CRIT],
      [20, 'batt-crit', BATT_CRIT],
      [21, 'batt-warn', BATT_WARN],
      [40, 'batt-warn', BATT_WARN],
      [41, 'batt-ok', BATT_OK],
      [100, 'batt-ok', BATT_OK],
    ]
    for (const [battery, expectedClass, expectedColor] of cases) {
      expect(battClass(battery)).toBe(expectedClass)
      expect(battColor(battery)).toBe(expectedColor)
    }
  })

  it('无数据显示为暗色，不是绿色', () => {
    expect(battColor(null)).toBe('var(--dim)')
    expect(battColor(undefined)).toBe('var(--dim)')
    expect(battColor(null)).not.toBe(BATT_OK)
  })

  it('阈值常量与实现一致（改阈值时不会被悄悄绕过）', () => {
    expect(BATT_CRIT_AT).toBe(20)
    expect(BATT_WARN_AT).toBe(40)
  })
})

describe('battery: 三个访问器判级必须完全一致', () => {
  // 这是本次去重的核心断言：抽出前 battClass/battColor/battColorVar 在 6 个地方
  // 各写各的，其中两处用 15/30、其余用 20/40 —— 同一架飞机换个面板就变色。
  // 三个访问器现在共用 battLevel，阈值只有一个来源。
  const LEVEL_OF_CLASS = { 'batt-crit': 'crit', 'batt-warn': 'warn', 'batt-ok': 'ok' }
  const LEVEL_OF_HEX = { [BATT_CRIT]: 'crit', [BATT_WARN]: 'warn', [BATT_OK]: 'ok' }
  const LEVEL_OF_VAR = {
    'var(--crit)': 'crit',
    'var(--warn)': 'warn',
    'var(--ok)': 'ok',
  }

  it('battClass / battColor / battColorVar / battLevel 在全域给出同一档', () => {
    for (let b = -5; b <= 105; b += 1) {
      const lv = battLevel(b)
      expect(LEVEL_OF_CLASS[battClass(b)]).toBe(lv)
      expect(LEVEL_OF_HEX[battColor(b)]).toBe(lv)
      expect(LEVEL_OF_VAR[battColorVar(b)]).toBe(lv)
    }
  })

  it('空值时三者都表示"未知"，且都不是"正常"档', () => {
    for (const v of [null, undefined, '', NaN]) {
      expect(battLevel(v)).toBeNull()
      expect(battClass(v)).toBe('')
      expect(battColor(v)).toBe('var(--dim)')
      expect(battColorVar(v)).toBe('var(--dim)')
    }
  })
})

describe('battery: 全域单调性', () => {
  it('电量升高时严重度只会下降，不会回升', () => {
    const rank = { 'batt-crit': 0, 'batt-warn': 1, 'batt-ok': 2 }
    let prev = -1
    for (let b = 0; b <= 100; b += 0.5) {
      const r = rank[battClass(b)]
      expect(r).toBeGreaterThanOrEqual(prev)
      prev = r
    }
  })
})
