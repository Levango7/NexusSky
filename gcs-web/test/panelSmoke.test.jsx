import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render } from '@testing-library/react'
import { readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 全面板渲染冒烟（2026-10-12 新增）。
 *
 * ## 为什么需要它
 *
 * 外部评估报告的原话：45 个前端面板里只有 4 个有真实组件测试，"界面层基本裸奔"。
 * 后果不是"测试覆盖率数字难看"，而是**面板里任何一个语法/导入/空值错误都会直接白屏**，
 * 而白屏在所有单测都是绿的情况下发生——因为根本没人在单测里渲染过它。
 *
 * 本文件把"渲染一次"变成最低门槛：每个页面级面板都要能在 jsdom 里渲染出内容
 * （空数据/错误态都算通过，白屏/抛异常算失败）。
 *
 * ## 实现要点
 *
 * api.js 用 Proxy 打桩：任何命名空间、任何方法都返回"空数组"的 Promise。
 * 这样面板走到接口调用处拿到空数据，自然渲染空态——正是要断言的路径。
 *
 * ## 已知不覆盖（写入注释而不是静默跳过）
 *
 * - Trajectory3DPanel：依赖 `window.THREE`（index.html 里以 script 标签注入的全局），
 *   jsdom 没有。它的初始化逻辑应由 e2e 覆盖，这里渲染只会得到假失败。
 * - SurveillancePanel：依赖 `window.Hls` 与 video 元素播放，jsdom 无法真播。
 */

// 面板渲染只需要"接口不炸、返回空数据"。为了让任何面板的 import 都能解析，
// 这里枚举 api.js 的真实导出名（漏一个 vitest 就直接报 No "xxx" export），
// 其中命名空间（api/docks/defects/emergencyOrch/disaster）用 Proxy 承接任意方法调用。
const NAMESPACES = ['api', 'docks', 'defects', 'emergencyOrch', 'disaster']
const EMPTY_LIST = () => Promise.resolve([])

vi.mock('../src/api.js', () => {
  const makeNamespace = () => new Proxy(function ns() {
    return Promise.resolve([])
  }, {
    get: (_t, prop) => {
      if (prop === 'then') return undefined     // 别被当 thenable
      if (prop === Symbol.toPrimitive) return () => ''
      return makeNamespace()
    },
    apply: () => Promise.resolve([]),
  })

  const mock = {
    getWsUrl: () => 'ws://localhost:1/ws/telemetry',
    wsUrl: () => 'ws://localhost:1/ws/telemetry',
    isAuthenticated: () => false,
    getAuthToken: () => null,
    getCurrentUser: () => null,
    setAuthToken: () => {},
    setCurrentUser: () => {},
    clearAuthToken: () => {},
    logout: () => {},
    resolveApiUrl: (u) => u,
    normalizeBudgetMode: (b) => b || 'full',
    getAvailablePanels: () => [],
    isPanelAvailable: () => true,
    BUDGET_MODES: ['toy', 'standard', 'full', 'advanced'],
    STANDARD_PANELS: [],
    ADVANCED_PANELS: [],
    TOY_PANELS: [],
    EMERGENCY_STANDARD_PANELS: [],
    EMERGENCY_TOY_PANELS: [],
    TimeoutError: class TimeoutError extends Error {},
    fetchStreamToken: () => Promise.resolve('tok'),
    alarmStreamUrl: () => '/api/v1/alarm/stream?streamToken=tok',
    surveillanceStreamUrl: () => '/api/v1/surveillance/stream?streamToken=tok',
  }
  for (const ns of NAMESPACES) mock[ns] = makeNamespace()
  return mock
})

// jsdom 没有 canvas 2d 实现（getContext 抛 Not implemented）：
// LinkQualityPanel / WeatherLayerPanel / ThermalOverlayPanel 会在挂载时画图。
// 给一个全 no-op 的 2d 上下文桩，让"能不能渲染"这条判断不被环境能力卡住。
beforeEach(() => {
  HTMLCanvasElement.prototype.getContext = () => ({
    clearRect: () => {}, fillRect: () => {}, strokeRect: () => {},
    beginPath: () => {}, closePath: () => {}, moveTo: () => {}, lineTo: () => {},
    arc: () => {}, fill: () => {}, stroke: () => {}, save: () => {}, restore: () => {},
    translate: () => {}, scale: () => {}, rotate: () => {}, setTransform: () => {},
    drawImage: () => {}, putImageData: () => {}, createLinearGradient: () => ({ addColorStop: () => {} }),
    measureText: () => ({ width: 0 }), setLineDash: () => {}, fillText: () => {}, strokeText: () => {},
  })
})

const HERE = dirname(fileURLToPath(import.meta.url))
const COMPONENTS = join(HERE, '..', 'src', 'components')

/** 页面级面板 = 组件里带 className="page 的面板（与 App.vue 的视图注册口径一致）。 */
function pagePanels() {
  return readdirSync(COMPONENTS)
    .filter((f) => f.endsWith('Panel.jsx'))
    .filter((f) => {
      const src = require('node:fs').readFileSync(join(COMPONENTS, f), 'utf-8')
      return src.includes('className="page')
    })
    .map((f) => f.replace(/\.jsx$/, ''))
}

/** 不覆盖的面板及原因（见文件头注释）。 */
const SKIP = {
  Trajectory3DPanel: '依赖 window.THREE 全局（index.html script 注入），jsdom 无此环境',
  SurveillancePanel: '依赖 window.Hls 与真实 video 播放，jsdom 无法播放',
}

/** 宽松的 props：面板要什么给什么，缺的走 undefined（空态）。 */
const PROPS = {
  selectedSysid: 1,
  selectedSysidRef: { current: 1 },
  drones: [],
  drone: null,
  telemetry: {},
  track: [],
  multiTracks: {},
  telemetryHistory: {},
  alerts: [],
  formations: [],
  meshTopology: null,
  satLinkData: null,
  terrainData: null,
  cellTowerData: null,
  twinStates: {},
  decisionEvents: [],
  adaptivePaths: [],
  edgeTasks: [],
  taskAssignments: [],
  conflictAlerts: [],
  taskStatuses: [],
  alarmTriggers: [],
  alarmAcks: [],
  surveillanceStatuses: {},
  mapRef: { current: null },
  onSelectNode: () => {},
  onSelectRoute: () => {},
  onSelectDrone: () => {},
  apiOk: true,
  refresh: () => {},
  budget: 'full',
}

describe('页面级面板渲染冒烟（37 个）', () => {
  const panels = pagePanels()

  it('面板清单非空（发现注册表漏文件时应有人看见）', () => {
    expect(panels.length).toBeGreaterThan(30)
  })

  for (const name of panels) {
    if (SKIP[name]) {
      it(`${name}：${SKIP[name]}`, () => {
        expect(true).toBe(true)
      })
      continue
    }

    it(`${name}：渲染不抛异常且有内容`, async () => {
      const mod = await import(`../src/components/${name}.jsx`)
      const Panel = mod.default
      expect(typeof Panel).toBe('function')
      const { container } = render(<Panel {...PROPS} />)
      // 至少渲染出 DOM（白屏 = container 里没有元素）
      expect(container.childElementCount).toBeGreaterThan(0)
    })
  }
})
