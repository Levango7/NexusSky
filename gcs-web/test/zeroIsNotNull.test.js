import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 「0 被当成缺失」的形状守卫。
 *
 * 这一族的现场是这样的：`droneCount` 真的可以是 0（一架无人机都没有），
 * 但写法是 `pick(d, 'droneCount', 'drones') || '--'` ⇒ 界面上"0 架"显示成 `--`，
 * 与"这个字段没回"长得一模一样；`App.jsx` 的 `stats.maxAlt` 同理（贴地飞行显示 `--`）。
 *
 * 为什么不只写单测：单测能钉住**这次改到的那几个点**，钉不住**下一个新面板**——
 * 而这类写法在 JSX 里长得跟正确写法几乎一样，review 很容易放过。
 * 所以这里扫的是一种**形状**（数值字段 + `|| '--'`，或"真值判断 + `: '--'`"），
 * 命中即红，并列出文件与行号。
 *
 * 判据本身也有牙：下面 T2/T3/T4 分别证明它 ① 抓得住两种坏形状、
 * ② 不误伤两种正确写法、③ 不把注释里的说明文字当输入（注释命中是这一族门禁的
 * 经典假红来源，见 singleSourceOfTruth.test.js 的同一处理）。
 */

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = join(HERE, '..', 'src')

/**
 * 数值字段名单：**0 是合法读数**的那些。
 * 只列已经出过事或同族已核过的名字，避免把"字符串字段用 ||"这种无害写法卷进来。
 */
const NUMERIC_FIELDS = ['droneCount', 'areaCount', 'maxAlt', 'count', 'qty', 'remaining']

function matchers(field) {
  return [
    // 形状 A：`field || '--'`，含 pick(..., 'field', ...) 这种把字段名藏在实参里的写法
    new RegExp(`(?:pick\\([^)]*'${field}'[^)]*\\)|\\b${field}\\b)\\s*\\|\\|\\s*['"]--['"]`),
    // 形状 B：真值判断吞掉 0 —— `field ? field.toFixed(0) + ' m' : '--'`
    new RegExp(`\\b${field}\\b\\s*\\?[^:]{0,80}:\\s*['"]--['"]`),
  ]
}

/** 去掉注释后再匹配，避免注释里的说明文字造成误判（同 singleSourceOfTruth）。 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

function findZeroSwallowers(src) {
  const hits = []
  stripComments(src)
    .split('\n')
    .forEach((line, idx) => {
      for (const field of NUMERIC_FIELDS) {
        if (matchers(field).some((re) => re.test(line))) {
          hits.push({ line: idx + 1, field, text: line.trim().slice(0, 90) })
        }
      }
    })
  return hits
}

function allSrcFiles() {
  const out = []
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) walk(full)
      else if (/\.(jsx?|tsx?)$/.test(entry.name)) out.push(full)
    }
  }
  walk(SRC)
  return out
}

const offenders = []
for (const f of allSrcFiles()) {
  for (const h of findZeroSwallowers(readFileSync(f, 'utf-8'))) {
    offenders.push(`${f.replace(SRC + '/', '')}:${h.line} [${h.field}] ${h.text}`)
  }
}

describe('形状守卫：数值字段的 0 不得被当成缺失', () => {
  it('名单非空（名单被清空时这条守卫会退化成永真）', () => {
    expect(NUMERIC_FIELDS.length).toBeGreaterThan(0)
    expect(NUMERIC_FIELDS).toEqual(expect.arrayContaining(['droneCount', 'maxAlt']))
  })

  it('src/ 下没有任何一处把数值字段的 0 写成缺失', () => {
    expect(offenders).toEqual([])
  })

  it('自证 ①：两种坏形状都必须被抓到', () => {
    const badA = `const a = pick(d, 'droneCount', 'drones') || '--'`
    const badB = `<span>{stats.maxAlt ? stats.maxAlt.toFixed(0) + ' m' : '--'}</span>`
    const hitsA = findZeroSwallowers(badA)
    const hitsB = findZeroSwallowers(badB)
    expect(hitsA).toHaveLength(1)
    expect(hitsA[0].field).toBe('droneCount')
    expect(hitsB).toHaveLength(1)
    expect(hitsB[0].field).toBe('maxAlt')
  })

  it('自证 ②：两种正确写法都不得被误伤', () => {
    const goodNullish = `const a = pick(d, 'droneCount', 'drones') ?? '--'`
    const goodExplicit = `const b = stats.maxAlt != null ? stats.maxAlt.toFixed(0) + ' m' : '--'`
    expect(findZeroSwallowers(goodNullish)).toEqual([])
    expect(findZeroSwallowers(goodExplicit)).toEqual([])
  })

  it('自证 ③：注释里出现坏形状不算命中（否则守卫会被自己的说明文字喂饱）', () => {
    const commented = `// 原来写的是 droneCount || '--'，0 架会被吞掉\nconst c = droneCount ?? '--'\n`
    expect(findZeroSwallowers(commented)).toEqual([])
  })

  it('本次修掉的三处站点，改前形状确实命中过判据（留下可复核的对照）', () => {
    // 这三条是从 git 上的旧写法逐字抄来的"坏样本"，用来证明判据对准的是真实现场，
    // 不是先有了判据再凑样本。
    const historical = [
      `<span>无人机：{pick(d, 'droneCount', 'drones') || '--'}</span>`,
      `<InfoItem label="区域无人机数" value={system.areaCount || '--'} />`,
      `<span>最高高度 <b className="mono">{stats.maxAlt ? stats.maxAlt.toFixed(0) + ' m' : '--'}</b></span>`,
    ]
    for (const line of historical) {
      expect(findZeroSwallowers(line).length, `旧写法没被抓住：${line}`).toBe(1)
    }
  })
})
