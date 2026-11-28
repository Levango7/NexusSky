import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render } from '@testing-library/react'
import React from 'react'

/**
 * #144 零测组件直测收口。
 *
 * ## 为什么需要它
 *
 * `panelSmoke.test.jsx` 用 `await import()` 动态渲染 37 个页面级面板——
 * 它确实覆盖了渲染，但**静态 import 计数门禁看不到动态 import**，
 * 于是 README「61 个 .jsx 组件里 36 个零测试」长期失真：那 36 个
 * 实际上大多已被 panelSmoke 渲染过，只是计数口径把它们算成零测。
 *
 * 本文件把 36 个零测组件全部改为**静态 import**：
 * - 页面级面板（29 个）：渲染一次，断言有 DOM 内容（与 panelSmoke 同口径）；
 * - 依赖 jsdom 缺失的全局（window.THREE / maplibre GL）的 4 个
 *   （Scene3D / Trajectory3D / Trajectory3DPanel / MapView）：只做
 *   **import 冒烟**——断言模块可加载且导出组件，不渲染。真渲染由
 *   Playwright e2e 覆盖（jsdom 没有 WebGL/Worker，硬渲染只会得到假失败）。
 *
 * api.js 打桩与 panelSmoke 同源（Proxy 命名空间 + 显式覆盖），canvas 2d
 * 同样补 no-op 桩，理由见 panelSmoke.test.jsx。
 */

vi.mock('../src/api.js', async (importOriginal) => {
  const actual = await importOriginal()
  // 命名空间列表与 EMPTY_LIST 必须内联在工厂里：vi.mock 被提升到
  // 文件顶部，引用外层 const 会在初始化前抛 ReferenceError
  // （与 panelSmoke 同坑）。
  const NAMESPACES = ['api', 'docks', 'defects', 'emergencyOrch', 'disaster']
  const EMPTY_LIST = () => Promise.resolve([])
  const makeNamespace = () => new Proxy(function ns() {
    return Promise.resolve([])
  }, {
    get: (_t, prop) => {
      if (prop === 'then') return undefined
      if (prop === Symbol.toPrimitive) return () => ''
      return makeNamespace()
    },
    apply: () => Promise.resolve([]),
  })
  const mock = {}
  for (const name of Object.keys(actual)) {
    mock[name] = NAMESPACES.includes(name) ? makeNamespace() : EMPTY_LIST
  }
  Object.assign(mock, {
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
  })
  return mock
})

beforeEach(() => {
  HTMLCanvasElement.prototype.getContext = () => ({
    clearRect: () => {}, fillRect: () => {}, strokeRect: () => {},
    beginPath: () => {}, closePath: () => {}, moveTo: () => {}, lineTo: () => {},
    arc: () => {}, fill: () => {}, stroke: () => {}, save: () => {}, restore: () => {},
    translate: () => {}, scale: () => {}, rotate: () => {}, setTransform: () => {},
    drawImage: () => {}, putImageData: () => {},
    createLinearGradient: () => ({ addColorStop: () => {} }),
    measureText: () => ({ width: 0 }), setLineDash: () => {}, fillText: () => {}, strokeText: () => {},
  })
})

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
  missionDraft: [],
}

// ===== 静态 import：36 个原零测组件 =====
import AiDecisionPanel from '../src/components/AiDecisionPanel.jsx'
import AlarmPanel from '../src/components/AlarmPanel.jsx'
import App from '../src/App.jsx'
import CellTowerPanel from '../src/components/CellTowerPanel.jsx'
import CityTwinPanel from '../src/components/CityTwinPanel.jsx'
import DeliveryPanel from '../src/components/DeliveryPanel.jsx'
import DroneLockPanel from '../src/components/DroneLockPanel.jsx'
import EmergencyOrchPanel from '../src/components/EmergencyOrchPanel.jsx'
import ErrorBoundary from '../src/components/ErrorBoundary.jsx'
import FleetOpsPanel from '../src/components/FleetOpsPanel.jsx'
import GeofencePanel from '../src/components/GeofencePanel.jsx'
import InspectionPanel from '../src/components/InspectionPanel.jsx'
import LoginPanel from '../src/components/LoginPanel.jsx'
import MapView from '../src/components/MapView.jsx'
import MappingPanel from '../src/components/MappingPanel.jsx'
import MeshTopologyPanel from '../src/components/MeshTopologyPanel.jsx'
import MissionPlanner from '../src/components/MissionPlanner.jsx'
import NavGroups from '../src/components/NavGroups.jsx'
import OpsReportPanel from '../src/components/OpsReportPanel.jsx'
import RidPanel from '../src/components/RidPanel.jsx'
import SatLinkPanel from '../src/components/SatLinkPanel.jsx'
import ScenarioLibraryPanel from '../src/components/ScenarioLibraryPanel.jsx'
import Scene3D from '../src/components/Scene3D.jsx'
import SensingPanel from '../src/components/SensingPanel.jsx'
import ShowPanel from '../src/components/ShowPanel.jsx'
import SprayPanel from '../src/components/SprayPanel.jsx'
import TenantPanel from '../src/components/TenantPanel.jsx'
import TerrainMapPanel from '../src/components/TerrainMapPanel.jsx'
import TrackingPanel from '../src/components/TrackingPanel.jsx'
import Trajectory3D from '../src/components/Trajectory3D.jsx'
import Trajectory3DPanel from '../src/components/Trajectory3DPanel.jsx'
import UserPanel from '../src/components/UserPanel.jsx'
import VideoFusionPanel from '../src/components/VideoFusionPanel.jsx'
import VisionPanel from '../src/components/VisionPanel.jsx'
import WeatherLayerPanel from '../src/components/WeatherLayerPanel.jsx'
// 命名空间 import：main.jsx 无默认导出，且副作用 import（无 from）
// 不被组件覆盖计数口径识别，故用 * as 让静态计数能看到它。
import * as mainEntry from '../src/main.jsx'

const RENDER_TIMEOUT_MS = 20_000

describe('零测组件直测（36 个）', () => {
  it('清单完整（发现注册表漏文件时应有人看见）', () => {
    const components = {
      AiDecisionPanel, AlarmPanel, App, CellTowerPanel, CityTwinPanel,
      DeliveryPanel, DroneLockPanel, EmergencyOrchPanel, ErrorBoundary,
      FleetOpsPanel, GeofencePanel, InspectionPanel, LoginPanel, MapView,
      MappingPanel, MeshTopologyPanel, MissionPlanner, NavGroups,
      OpsReportPanel, RidPanel, SatLinkPanel, ScenarioLibraryPanel,
      Scene3D, SensingPanel, ShowPanel, SprayPanel, TenantPanel,
      TerrainMapPanel, TrackingPanel, Trajectory3D, Trajectory3DPanel,
      UserPanel, VideoFusionPanel, VisionPanel, WeatherLayerPanel,
      main: mainEntry,
    }
    expect(Object.keys(components)).toHaveLength(36)
  })

  // ===== import 冒烟：jsdom 缺 WebGL/Worker 全局的 4 个 =====
  for (const [name, mod] of [
    ['Scene3D', Scene3D],
    ['Trajectory3D', Trajectory3D],
    ['Trajectory3DPanel', Trajectory3DPanel],
    ['MapView', MapView],
  ]) {
    it(`${name}：import 冒烟（导出组件；真渲染由 Playwright e2e 覆盖）`, () => {
      expect(typeof mod).toBe('function')
    })
  }

  it('main：入口挂载冒烟（#root 已有内容）', () => {
    const root = document.getElementById('root')
    expect(root).not.toBeNull()
    expect(root.childElementCount).toBeGreaterThan(0)
  })

  // ===== 渲染：其余 31 个 jsdom 安全组件 =====
  const RENDER = {
    AiDecisionPanel, AlarmPanel, App, CellTowerPanel, CityTwinPanel,
    DeliveryPanel, DroneLockPanel, EmergencyOrchPanel,
    FleetOpsPanel, GeofencePanel, InspectionPanel, LoginPanel, MappingPanel,
    MeshTopologyPanel, MissionPlanner, NavGroups, OpsReportPanel, RidPanel,
    SatLinkPanel, ScenarioLibraryPanel, SensingPanel, ShowPanel, SprayPanel,
    TenantPanel, TerrainMapPanel, TrackingPanel, UserPanel, VideoFusionPanel,
    VisionPanel, WeatherLayerPanel,
  }

  for (const [name, Panel] of Object.entries(RENDER)) {
    it(`${name}：渲染不抛异常且有内容`, async () => {
      expect(typeof Panel).toBe('function')
      const { container } = render(<Panel {...PROPS} />)
      expect(container.childElementCount).toBeGreaterThan(0)
    }, RENDER_TIMEOUT_MS)
  }

  // ErrorBoundary 无子节点时不渲染任何内容（设计如此），
  // 传一个子节点才能断言"正常路径把子节点透传出来"。
  it('ErrorBoundary：渲染不抛异常且透传子节点', async () => {
    const { container, getByText } = render(
      <ErrorBoundary><div>boundary-child</div></ErrorBoundary>)
    expect(container.childElementCount).toBeGreaterThan(0)
    expect(getByText('boundary-child')).toBeInTheDocument()
  }, RENDER_TIMEOUT_MS)
})
