import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import BudgetBadge from '../src/components/BudgetBadge.jsx'

/**
 * BudgetBadge 组件级测试（2026-10-24 补口）。
 *
 * 刻意**不在测试里复制那份 labels/colors/ariaLabels 表**——那是 dockPanel 旧版踩过的
 * "镜像常量自证"（组件把表改错也照样全绿）。这里全部从真实渲染出来的 DOM 读。
 */

const MODES = ['toy', 'standard', 'advanced', 'emergency-toy', 'emergency-standard']

function badge(mode) {
  const { container } = render(<BudgetBadge mode={mode} />)
  return container.querySelector('[role="status"]')
}

describe('BudgetBadge：完整版不渲染，档位版渲染', () => {
  it('mode=null → 整个组件不产生任何 DOM（这是"完整版"的判据）', () => {
    const { container } = render(<BudgetBadge mode={null} />)
    expect(container.innerHTML).toBe('')
  })

  it('mode=undefined 同样不渲染（不是崩，也不是渲染一个空徽章）', () => {
    const { container } = render(<BudgetBadge />)
    expect(container.innerHTML).toBe('')
  })

  it.each(MODES)('%s → 有 role=status、aria-label 与 title 同源', (mode) => {
    const el = badge(mode)
    expect(el).not.toBeNull()
    const aria = el.getAttribute('aria-label')
    expect(aria).toMatch(/^预算模式: /)
    expect(el.getAttribute('title')).toBe(aria)
  })
})

describe('BudgetBadge：「丐版·」前缀只加在非应急档（组件自己的口径）', () => {
  // 组件头部注释：mode=null 才是完整版，所以 toy/standard/advanced 都属丐版档位序列，
  // 前缀是刻意的；应急档（emergency-*）是另一条序列，不该被冠以"丐版"。
  it.each(['toy', 'standard', 'advanced'])('%s 的文本以「丐版·」开头', (mode) => {
    expect(badge(mode).textContent.startsWith('丐版·')).toBe(true)
  })

  it.each(['emergency-toy', 'emergency-standard'])('%s 的文本不含「丐版」', (mode) => {
    expect(badge(mode).textContent).not.toContain('丐版')
  })

  it('五个已知档位的文本互不相同（防复制粘贴漏改标签）', () => {
    const texts = MODES.map((m) => badge(m).textContent)
    expect(new Set(texts).size).toBe(MODES.length)
    expect(texts).not.toContain('')
  })

  it('五个已知档位各自有底色，且互不相同', () => {
    const colors = MODES.map((m) => badge(m).style.backgroundColor)
    expect(colors.every((c) => c && c.length > 0)).toBe(true)
    expect(new Set(colors).size).toBe(MODES.length)
  })

  it('aria-label 里带的是档位键本身，便于与后端 license tier 对账', () => {
    for (const mode of MODES) {
      expect(badge(mode).getAttribute('aria-label')).toContain(mode)
    }
  })
})

describe('BudgetBadge：未知档位必须优雅回落，不许崩也不许留空', () => {
  it('未知 mode → 文本回落成「丐版·<mode>」、aria 回落成「预算模式: <mode>」、底色回落', () => {
    const el = badge('quantum')
    expect(el.textContent).toBe('丐版·quantum')
    expect(el.getAttribute('aria-label')).toBe('预算模式: quantum')
    expect(el.style.backgroundColor).toBeTruthy()
  })

  it('空串 mode 也不渲染（falsy 即"完整版/未知"，不出一个空白徽章）', () => {
    const { container } = render(<BudgetBadge mode="" />)
    expect(container.innerHTML).toBe('')
  })
})
