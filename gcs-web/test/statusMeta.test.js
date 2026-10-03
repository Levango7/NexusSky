import { describe, it, expect } from 'vitest'
import {
  normPriority,
  normSeverity,
  PRIORITY_META,
  SEVERITY_META,
} from '../src/utils/statusMeta'

describe('statusMeta: normPriority', () => {
  it('原样透传元数据里存在的键', () => {
    for (const k of Object.keys(PRIORITY_META)) {
      expect(normPriority(k)).toBe(k)
    }
  })

  it('大小写不敏感', () => {
    expect(normPriority('p0')).toBe('P0')
    expect(normPriority('High')).toBe('HIGH')
    expect(normPriority('medium')).toBe('MEDIUM')
  })

  it('带后缀的变体收敛到基本档（P0_CRITICAL / P1-URGENT 之类）', () => {
    expect(normPriority('P0_CRITICAL')).toBe('P0')
    expect(normPriority('P1_URGENT')).toBe('P1')
    expect(normPriority('P2-NORMAL')).toBe('P2')
    expect(normPriority('P3_LOW')).toBe('P3')
  })

  it('无法识别时降级为 P3（最低优先级）而不是抛错', () => {
    // 优先级是展示用辅助信息，一个脏数据不该让整个面板崩掉
    expect(normPriority('???')).toBe('P3')
    expect(normPriority('P9')).toBe('P3')
    expect(normPriority({})).toBe('P3')
    expect(normPriority([])).toBe('P3')
  })

  it('空值降级为 P3', () => {
    expect(normPriority(null)).toBe('P3')
    expect(normPriority(undefined)).toBe('P3')
    expect(normPriority('')).toBe('P3')
    expect(normPriority(0)).toBe('P3') // 0 是假值，与 null 同路径
  })
})

describe('statusMeta: normPriority 输出永远可查表', () => {
  it('任意输入的返回值都存在于 PRIORITY_META', () => {
    // 这条比逐个断言更重要：它保证组件里 PRIORITY_META[normPriority(x)] 永不 undefined
    const inputs = [null, undefined, '', 0, 'P0', 'p1', 'HIGH', 'P0_CRITICAL', 'zzz', 42, [], {}]
    for (const v of inputs) {
      expect(PRIORITY_META[normPriority(v)]).toBeDefined()
    }
  })
})

describe('statusMeta: normSeverity', () => {
  it('原样透传元数据里存在的键', () => {
    for (const k of Object.keys(SEVERITY_META)) {
      expect(normSeverity(k)).toBe(k)
    }
  })

  it('大小写不敏感', () => {
    expect(normSeverity('critical')).toBe('CRITICAL')
    expect(normSeverity('Warn')).toBe('WARN')
    expect(normSeverity('error')).toBe('ERROR')
  })

  it('按子串归类（仅对元数据里没有的键生效）', () => {
    // 'WARNING' 本身就是合法键，走的是原样透传分支，不是子串分支
    expect(normSeverity('WARNING')).toBe('WARNING')
    expect(normSeverity('SEVERE_CRITICAL')).toBe('CRITICAL')
    expect(normSeverity('ERR_TIMEOUT')).toBe('ERROR')
    expect(normSeverity('WARN_LOW')).toBe('WARN')
  })

  it('无法识别时降级为 INFO 而不是抛错', () => {
    expect(normSeverity('???')).toBe('INFO')
    expect(normSeverity('NOTICE')).toBe('INFO')
    expect(normSeverity(123)).toBe('INFO')
  })

  it('空值降级为 INFO', () => {
    expect(normSeverity(null)).toBe('INFO')
    expect(normSeverity(undefined)).toBe('INFO')
    expect(normSeverity('')).toBe('INFO')
  })

  it('任意输入的返回值都存在于 SEVERITY_META', () => {
    const inputs = [null, undefined, '', 0, 'CRITICAL', 'warn', 'ERR_X', 'zzz', 42, [], {}]
    for (const v of inputs) {
      expect(SEVERITY_META[normSeverity(v)]).toBeDefined()
    }
  })
})

describe('statusMeta: 元数据自身的一致性', () => {
  it('weight 越小越紧急/严重', () => {
    const pw = PRIORITY_META.P0.weight
    const lw = PRIORITY_META.P3.weight
    expect(pw).toBeLessThan(lw)
    const cw = SEVERITY_META.CRITICAL.weight
    const iw = SEVERITY_META.INFO.weight
    expect(cw).toBeLessThan(iw)
  })

  it('每个条目都有 color 与 label（否则面板会渲染出空样式）', () => {
    for (const m of Object.values(PRIORITY_META)) {
      expect(m.color).toBeTruthy()
      expect(m.label).toBeTruthy()
    }
    for (const m of Object.values(SEVERITY_META)) {
      expect(m.color).toBeTruthy()
      expect(m.label).toBeTruthy()
    }
  })
})
