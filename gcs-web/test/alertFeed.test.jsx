import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import AlertFeed from '../src/components/AlertFeed.jsx'

/**
 * AlertFeed 组件级测试（2026-10-24 补口）。
 *
 * 钉的是两件事：
 * ① severity → 标签/配色这条映射表**真的被用上**（SEV_META 没导出，所以全部从 DOM 读，
 *    绝不在测试里抄一份表——抄表就是 dockPanel 旧版那种镜像自证）；
 * ② 数据缺失（ts 没有 / severity 越界）必须回落成可读文案，不许出现
 *    "Invalid Date" 或 "undefined" 这种把脏状态摊在界面上的输出。
 */

const SEV_LABEL = [
  [0, 'EMERG'],
  [1, 'ALERT'],
  [2, 'CRIT'],
  [3, 'ERROR'],
  [4, 'WARN'],
  [5, 'NOTICE'],
  [6, 'INFO'],
  [7, 'DEBUG'],
]

function renderOne(alert) {
  const { container } = render(<AlertFeed alerts={[alert]} />)
  return { container, li: container.querySelector('li') }
}

describe('AlertFeed 空态', () => {
  it('空数组：显示"暂无事件"，且不显示计数文案', () => {
    render(<AlertFeed alerts={[]} />)
    expect(screen.getByText('暂无事件')).toBeTruthy()
    expect(screen.queryByText(/\d+ 条/)).toBeNull()
  })

  it('非空：计数就是条数（不是"渲染出的行数 + 1"之类）', () => {
    const three = [1, 2, 3].map((i) => ({ id: i, ts: 1700000000000 + i, sysid: 7, text: `e${i}`, severity: 6 }))
    const { container } = render(<AlertFeed alerts={three} />)
    expect(screen.getByText('3 条')).toBeTruthy()
    expect(container.querySelectorAll('li')).toHaveLength(3)
  })
})

describe('AlertFeed severity → 标签', () => {
  it.each(SEV_LABEL)('severity=%i 渲染成 [%s]', (sev, label) => {
    const { li } = renderOne({ id: 1, ts: 1700000000000, sysid: 7, text: 'x', severity: sev })
    expect(li.querySelector('.sev').textContent).toBe(`[${label}]`)
  })

  it('severity 缺省（undefined）按 INFO 处理，而不是空白', () => {
    const { li } = renderOne({ id: 1, ts: 1700000000000, sysid: 7, text: 'x' })
    expect(li.querySelector('.sev').textContent).toBe('[INFO]')
  })

  it('severity 越界（9 / -1）与非法值（字符串）都回落 INFO，绝不出现 undefined', () => {
    for (const sev of [9, -1, 'oops', {}]) {
      const { li } = renderOne({ id: 1, ts: 1700000000000, sysid: 7, text: 'x', severity: sev })
      expect(li.querySelector('.sev').textContent).toBe('[INFO]')
      expect(li.textContent).not.toContain('undefined')
    }
  })

  it('每条的配色与左侧色条同源（不是永远 var(--dim)）', () => {
    const { li: crit } = renderOne({ ts: 1700000000000, sysid: 7, text: 'x', severity: 2 })
    const { li: info } = renderOne({ ts: 1700000000000, sysid: 7, text: 'x', severity: 6 })
    const c = crit.querySelector('.sev').style.color
    expect(c).toBeTruthy()
    expect(c).not.toBe(info.querySelector('.sev').style.color)
  })
})

describe('AlertFeed 时间列：数据缺失不许显示成 Invalid Date', () => {
  it('ts 缺失 → 占位 --:--:--（本轮修复前这里直接是 "Invalid Date"）', () => {
    const { li } = renderOne({ id: 1, sysid: 7, text: 'x', severity: 6 })
    expect(li.querySelector('time').textContent).toBe('--:--:--')
    expect(li.textContent).not.toContain('Invalid Date')
  })

  it('ts 为 null 同样占位', () => {
    const { li } = renderOne({ id: 1, ts: null, sysid: 7, text: 'x', severity: 6 })
    expect(li.querySelector('time').textContent).toBe('--:--:--')
  })

  it('ts 是无法解析的值（垃圾字符串）→ 也占位，而不是把 Invalid Date 端上去', () => {
    const { li } = renderOne({ id: 1, ts: 'not-a-time', sysid: 7, text: 'x', severity: 6 })
    expect(li.querySelector('time').textContent).toBe('--:--:--')
  })

  it('ts 有效 → 24 小时制时分秒（hour12: false 的口径）', () => {
    const { li } = renderOne({ id: 1, ts: 1700000000000, sysid: 7, text: 'x', severity: 6 })
    expect(li.querySelector('time').textContent).toMatch(/^\d{1,2}:\d{2}:\d{2}$/)
  })
})

describe('AlertFeed 列表键与内容', () => {
  it('正文原样渲染，同毫秒同 sysid 的两条不会互相覆盖', () => {
    const same = [
      { id: 1, ts: 1700000000000, sysid: 7, text: '第一条', severity: 4 },
      { id: 1, ts: 1700000000000, sysid: 7, text: '第二条', severity: 4 },
    ]
    const { container } = render(<AlertFeed alerts={same} />)
    expect(container.querySelectorAll('li')).toHaveLength(2)
    expect(screen.getByText('第一条')).toBeTruthy()
    expect(screen.getByText('第二条')).toBeTruthy()
  })

  it('缺 id 也不崩（key 里有索引兜底）', () => {
    const { container } = render(<AlertFeed alerts={[{ ts: 1700000000000, sysid: 3, text: '无 id', severity: 1 }]} />)
    expect(container.querySelectorAll('li')).toHaveLength(1)
  })
})
