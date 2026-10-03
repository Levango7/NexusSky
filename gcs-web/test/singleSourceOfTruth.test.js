import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 单一真相源守卫：防止「抽了 utils 又在组件里写一份」被悄悄回退。
 *
 * 这批去重（battClass / battColor / normPriority / normSeverity）在动手前各有
 * 2 份逐字节相同的复制品。复制品的危害不是"多了几行"，而是**改一处不改另一处**：
 * 同一个电量在列表里显示红色、在详情页显示绿色，而没有任何测试会失败。
 *
 * 所以除了测行为，还要测"组件确实引用了共享实现"。这比 lint 的
 * no-unused-vars 更进一步：lint 不会告诉你有人在组件里重新定义了一个同名函数。
 */

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = join(HERE, '..', 'src')
const COMPONENTS = join(SRC, 'components')

function read(file) {
  return readFileSync(file, 'utf-8')
}

function allComponentFiles() {
  return readdirSync(COMPONENTS)
    .filter((f) => f.endsWith('.jsx'))
    .map((f) => ({ name: f, src: read(join(COMPONENTS, f)) }))
}

/** 去掉注释后再匹配，避免注释里的说明文字造成误判 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

const files = allComponentFiles()

describe('单一真相源：组件不得再自行定义已抽出的函数', () => {
  const FORBIDDEN = ['battClass', 'battColor', 'battColorVar', 'batteryClass', 'normPriority', 'normSeverity', 'haversine', 'fmtDur', 'circularLayout', 'gradeColor', 'fmtPct', 'fmtMs']

  for (const fn of FORBIDDEN) {
    it(`src/components 下没有组件再定义 ${fn}()`, () => {
      const offenders = files.filter(({ src }) =>
        new RegExp(`(?:function\\s+${fn}\\s*\\(|const\\s+${fn}\\s*=)`).test(stripComments(src))
      )
      expect(
        offenders.map((o) => o.name),
        `${fn} 已在 utils 中实现，组件里不应再有本地定义（复制品会让两处显示不一致）`
      ).toEqual([])
    })
  }

  it('组件里也没有内联的电量阈值三元式（曾以 15/30 与 20/40 两套口径并存）', () => {
    const offenders = files.filter(({ src }) =>
      /batt\w*\s*<=\s*\d+/.test(stripComments(src)) ||
      /battery\w*\s*<=\s*\d+/.test(stripComments(src))
    )
    expect(
      offenders.map((o) => o.name),
      '电量阈值只能来自 utils/battery 的常量；内联阈值会造成同一架飞机在不同面板显示不同颜色'
    ).toEqual([])
  })
})

describe('单一真相源：引用方确实接到了共享实现', () => {
  const EXPECTED_IMPORTERS = {
    battClass: ['DashboardPanel.jsx', 'DroneList.jsx', 'TelemetryPanel.jsx'],
    battColor: ['DashboardPanel.jsx', 'TelemetryCharts.jsx'],
    battColorVar: ['EmergencyOrchPanel.jsx', 'UnifiedCommandPanel.jsx'],
    normPriority: ['EmergencyOrchPanel.jsx', 'UnifiedCommandPanel.jsx'],
    normSeverity: ['AlarmPanel.jsx', 'UnifiedCommandPanel.jsx'],
    circularLayout: ['CellTowerPanel.jsx', 'MeshTopologyPanel.jsx'],
    gradeColor: ['CvEvalPanel.jsx'],
  }

  for (const [fn, importers] of Object.entries(EXPECTED_IMPORTERS)) {
    it(`${fn} 被 ${importers.join(' / ')} 从 utils 导入`, () => {
      for (const name of importers) {
        const { src } = files.find((f) => f.name === name)
        expect(src, `${name} 应从 utils 导入 ${fn}`).toMatch(
          new RegExp(`import\\s*\\{[^}]*\\b${fn}\\b[^}]*\\}\\s*from\\s*'\\.\\./utils/`)
        )
      }
    })
  }

  it('App.jsx 从 utils 导入 haversine / fmtDur', () => {
    const src = read(join(SRC, 'App.jsx'))
    expect(src).toMatch(/import\s*\{[^}]*\bhaversine\b[^}]*\}\s*from\s*'\.\/utils\/geo'/)
    expect(src).toMatch(/import\s*\{[^}]*\bfmtDur\b[^}]*\}\s*from\s*'\.\/utils\/format'/)
  })
})

describe('单一真相源：utils 模块不互相重复实现', () => {
  it('battery.js 与 statusMeta.js 各自只导出一份 battClass/battColor/normPriority/normSeverity', () => {
    const utilsDir = join(SRC, 'utils')
    const jsFiles = readdirSync(utilsDir).filter((f) => f.endsWith('.js'))
    for (const fn of ['battClass', 'battColor', 'normPriority', 'normSeverity']) {
      const definers = jsFiles.filter((f) =>
        new RegExp(`export function ${fn}\\s*\\(`).test(read(join(utilsDir, f)))
      )
      expect(definers, `${fn} 只能有一处实现，实际有：${definers.join(', ')}`).toHaveLength(1)
    }
  })
})
