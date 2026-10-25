import { describe, it, expect } from 'vitest'
import { MODE_LABEL, MODE_UNKNOWN_TEXT, normModeLabel } from '../src/utils/statusMeta.js'

/**
 * `normModeLabel` 的三条规则各自钉死（GAPS §八-1，2026-10-25 用户拍板"缺省一律未知"）。
 *
 * 归一前有三份 `MODE_LABEL`，同一架"没有 mode"的设备在三个面板上分别是
 * 「未知」/「待命」/空白。其中 DroneList 那种兜底是**最坏的一种**：
 * "待命"是一种真实状态，用它兜"没数据"等于让操作员看到一个不存在的状态。
 */
describe('normModeLabel：规则 1 —— 没有数据不许冒充真实状态', () => {
  it('null / undefined / 空串 ⇒ 「未知」', () => {
    for (const v of [null, undefined, '']) {
      expect(normModeLabel(v)).toBe(MODE_UNKNOWN_TEXT)
    }
    expect(MODE_UNKNOWN_TEXT).toBe('未知')
  })

  it('绝不会返回"待命"来兜底（这条是当年那处分叉的回归线）', () => {
    for (const v of [null, undefined, '']) {
      expect(normModeLabel(v)).not.toBe('待命')
    }
  })
})

describe('normModeLabel：规则 2 —— 后端自己说不知道，就翻成中文的不知道', () => {
  it('大写 UNKNOWN / 小写 unknown ⇒ 「未知」', () => {
    // 旧表里那个小写 `unknown` 键其实永远不会命中（后端发的是大写 UNKNOWN），
    // 所以那三表面板上"UNKNOWN"一直是原样英文显示。归一时把它也收进规则里。
    expect(normModeLabel('UNKNOWN')).toBe('未知')
    expect(normModeLabel('unknown')).toBe('未知')
  })
})

describe('normModeLabel：规则 3 —— 表里有中文的用中文，表里没有的真实值原样透传', () => {
  it.each([
    ['STANDBY', '待命'],
    ['MANUAL', '待命'],
    ['ARMED', '已解锁'],
    ['MISSION', '任务中'],
    ['RTL', '返航'],
  ])('%s ⇒ %s', (mode, label) => {
    expect(normModeLabel(mode)).toBe(label)
  })

  it('大小写都认（后端不同接口下大小写不统一，不能因为大小写就退化成英文原文）', () => {
    expect(normModeLabel('rtl')).toBe('返航')
    expect(normModeLabel('armed')).toBe('已解锁')
  })

  it.each(['LAND', 'LOITER', 'TAKEOFF', 'AUTO', 'GUIDED', 'HOLD'])(
    '后端会发但表里没有中文的 %s ⇒ 原样透传（不降级成"未知"把真信息弄丢）',
    (mode) => {
      expect(normModeLabel(mode)).toBe(mode)
    }
  )

  it('透传的是原字符串本身，不是大写化后的形式（保留上游写法便于排查）', () => {
    expect(normModeLabel('Loiter')).toBe('Loiter')
  })
})

describe('MODE_LABEL 这张表本身', () => {
  it('MANUAL 的中文是"待命"——沿用归一前 Dashboard/Telemetry 两份旧表的既有映射', () => {
    // 这一格读起来别扭（手动 ≠ 待命），但它是**两个面板原本就有的口径**，
    // 不是本轮新造的词。要不要改成"手动"是独立的文案决策，
    // 已登记 .coord/GAPS.md 第八节，改的时候只需要动这张表。
    expect(MODE_LABEL.MANUAL).toBe('待命')
  })

  it('表里不再留小写 unknown 这种永远命中不到的键', () => {
    expect(Object.keys(MODE_LABEL)).not.toContain('unknown')
    expect(Object.keys(MODE_LABEL)).toEqual(expect.arrayContaining(['STANDBY', 'ARMED', 'MISSION', 'RTL', 'MANUAL']))
  })
})
