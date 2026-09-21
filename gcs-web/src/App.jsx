import React, { useEffect, useRef, useState, useCallback, useMemo } from 'react'
import MapView from './components/MapView.jsx'
import DroneList from './components/DroneList.jsx'
import TelemetryPanel from './components/TelemetryPanel.jsx'
import MissionPlanner from './components/MissionPlanner.jsx'
import AlertFeed from './components/AlertFeed.jsx'
import Joystick from './components/Joystick.jsx'
import VisionPanel from './components/VisionPanel.jsx'
import FormationPanel from './components/FormationPanel.jsx'
import SprayPanel from './components/SprayPanel.jsx'
import HardwarePanel from './components/HardwarePanel.jsx'
import MeshTopologyPanel from './components/MeshTopologyPanel.jsx'
import SatLinkPanel from './components/SatLinkPanel.jsx'
import TerrainMapPanel from './components/TerrainMapPanel.jsx'
import CellTowerPanel from './components/CellTowerPanel.jsx'
import EmergencyOrchPanel from './components/EmergencyOrchPanel.jsx'
import SurveillancePanel from './components/SurveillancePanel.jsx'
import AlarmPanel from './components/AlarmPanel.jsx'
import TrackingPanel from './components/TrackingPanel.jsx'
import GeofencePanel from './components/GeofencePanel.jsx'
import DroneLockPanel from './components/DroneLockPanel.jsx'
import AutoDispatchPanel from './components/AutoDispatchPanel.jsx'
import ScenarioLibraryPanel from './components/ScenarioLibraryPanel.jsx'
import InspectionPanel from './components/InspectionPanel.jsx'
import HealthPanel from './components/HealthPanel.jsx'
import CommAdaptPanel from './components/CommAdaptPanel.jsx'
import MappingPanel from './components/MappingPanel.jsx'
import VoiceCmdPanel from './components/VoiceCmdPanel.jsx'
import CityTwinPanel from './components/CityTwinPanel.jsx'
import DeliveryPanel from './components/DeliveryPanel.jsx'
import ShowPanel from './components/ShowPanel.jsx'
import TelemetryCharts from './components/TelemetryCharts.jsx'
import DashboardPanel from './components/DashboardPanel.jsx'
import Scene3D from './components/Scene3D.jsx'
import Trajectory3D from './components/Trajectory3D.jsx'
import { api, getWsUrl, isPanelAvailable, BUDGET_MODES, isAuthenticated, getCurrentUser, logout } from './api.js'
import BudgetBadge from './components/BudgetBadge.jsx'
import LoginPanel from './components/LoginPanel.jsx'
import TenantPanel from './components/TenantPanel.jsx'
import UserPanel from './components/UserPanel.jsx'

function haversine(a, b) {
  const R = 6371000
  const dLa = ((b.lat - a.lat) * Math.PI) / 180
  const dLo = ((b.lon - a.lon) * Math.PI) / 180
  const la1 = (a.lat * Math.PI) / 180
  const la2 = (b.lat * Math.PI) / 180
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLo / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}

function fmtDur(sec) {
  if (!sec || sec < 0) return '--:--'
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

// 视图标签 -> 预算面板名称映射（用于丐版模式下隐藏不可用面板）
// 经验来源：2026-09-17-react-mount-existing-components-export-signature-dialog-wrap
const VIEW_PANEL_MAP = {
  dashboard: 'status',     // 仪表盘/状态（百元级可用）
  scene3d: 'mission',      // 3D 视图归入千元级（依赖航点/地图渲染）
  control: 'telemetry',    // 主操控视图（百元级可用，含地图+遥测+航拍）
  formation: 'formation',  // 编队（千元级可用）
  spray: 'mission',        // 喷洒属于任务范畴（千元级可用）
  hardware: 'opticalflow', // 硬件抽象含光流/红外（进阶版可用）
  mesh: 'mesh',            // Mesh 拓扑（千元级可用）
  celltower: 'mesh',       // 基站属于 mesh 通信（千元级可用）
  satlink: 'mesh',         // 星地中继属于通信（千元级可用）
  terrain: 'mission',      // 地形属于任务规划（千元级可用）
  emergency: 'emergency',  // 应急编排（千元级可用）
  surveillance: 'mission', // 安防监控归入任务范畴（千元级可用）
  alarm: 'emergency',      // 报警联动归入应急范畴（千元级可用）
  tracking: 'mission',     // 飞行追踪/遗失查找归入任务范畴（千元级可用）
  geofence: 'mission',     // 电子围栏归入任务范畴（千元级可用）
  dronelock: 'status',     // 远程锁机归入状态管理（百元级可用）
  autodispatch: 'emergency', // 自动出警归入应急范畴（千元级可用）
  scenariolib: 'emergency', // 场景库归入应急范畴（千元级可用）
  inspection: 'mission',   // 智能巡检归入任务范畴（千元级可用）
  health: 'status',        // 健康管理归入状态管理（百元级可用）
  commadapt: 'mesh',       // 通信自适应归入通信范畴（千元级可用）
  mapping: 'mission',      // 航拍测绘归入任务范畴（千元级可用）
  voicecmd: 'emergency',   // 语音指挥归入应急范畴（千元级可用）
  citytwin: 'emergency',   // 数字孪生归入应急范畴（千元级可用）
  delivery: 'mission',     // 物流配送归入任务范畴（千元级可用）
  show: 'formation',       // 编队表演归入编队范畴（千元级可用）
  tenants: 'status',       // 租户管理归入状态管理（百元级可用）
  users: 'status',         // 用户管理归入状态管理（百元级可用）
}

export default function App() {
  const [authed, setAuthed] = useState(isAuthenticated())
  const [drones, setDrones] = useState([])
  const [selectedSysid, setSelectedSysid] = useState(null)
  const [telemetry, setTelemetry] = useState(null)
  const [track, setTrack] = useState([])
  const [alerts, setAlerts] = useState([])
  const [missionDraft, setMissionDraft] = useState([])
  const [orbitOverlay, setOrbitOverlay] = useState(null)   // {center, radiusM} for the map ring
  const [wsState, setWsState] = useState('connecting')
  const [apiOk, setApiOk] = useState(false)
  const [now, setNow] = useState(Date.now())
  const [view, setView] = useState('control')               // 'control' | 'dashboard' | 'formation' | 'spray' | 'hardware' | 'mesh' | 'celltower' | 'scene3d'
  const [formations, setFormations] = useState([])          // 编队列表（WebSocket 推送）
  const [meshTopology, setMeshTopology] = useState(null)    // mesh 拓扑（WebSocket 推送）
  const [satLinkData, setSatLinkData] = useState(null)      // sat-link 数据（WebSocket 推送）
  const [terrainData, setTerrainData] = useState(null)      // terrain 数据（WebSocket 推送）
  const [cellTowerData, setCellTowerData] = useState(null)  // celltower 数据（WebSocket 推送）
  const [telemetryHistory, setTelemetryHistory] = useState([]) // 遥测历史数据点（最近 120 个）
  const [mobileRail, setMobileRail] = useState(null) // 移动端侧栏抽屉：null | 'left' | 'right'
  const [budgetMode, setBudgetMode] = useState(null) // 丐版预算档位：null=完整版 | 'toy' | 'standard' | 'advanced'
  const [multiTracks, setMultiTracks] = useState({})        // 多机轨迹：{ sysid: [{lat, lon, alt, ts}] }
  const [trackColorMode, setTrackColorMode] = useState('single')  // 轨迹着色模式
  const [replayTrack, setReplayTrack] = useState(null)      // 2D回放轨迹数据
  const [replayProgress, setReplayProgress] = useState(0)   // 回放进度 0-1
  const [trackingOverlay, setTrackingOverlay] = useState(null)  // 追踪面板叠加数据
  const wsRef = useRef(null)
  // 用 ref 跟踪 selectedSysid，使 WebSocket onmessage 能读取最新值而无需重连
  // 经验来源：2026-09-13-yjs-multi-provider-destroy-order（effect 依赖与 ref 解耦模式）
  const selectedSysidRef = useRef(selectedSysid)
  selectedSysidRef.current = selectedSysid
  // 用 ref 跟踪 apiOk，使指数退避 effect 能读取最新值
  const apiOkRef = useRef(false)
  apiOkRef.current = apiOk

  const selected = drones.find((d) => d.sysid === selectedSysid)
  const onlineCount = drones.filter((d) => d.online).length

  // 飞行统计（从轨迹实时计算：航程 / 时长 / 最高高度）
  const stats = useMemo(() => {
    if (!track || track.length < 2) return { dist: 0, dur: 0, maxAlt: 0 }
    let d = 0
    for (let i = 1; i < track.length; i++) d += haversine(track[i - 1], track[i])
    const dur = (track[track.length - 1].ts - track[0].ts) / 1000
    const maxAlt = Math.max(...track.map((p) => p.alt || 0))
    return { dist: d / 1000, dur, maxAlt }
  }, [track])

  const refreshDrones = useCallback(async () => {
    try {
      const list = await api.listDrones()
      setDrones(list)
      setApiOk(true)
      if (list.length > 0 && !list.some((d) => d.sysid === selectedSysid)) {
        setSelectedSysid(list[0].sysid)
      }
    } catch (e) {
      setApiOk(false)
    }
  }, [selectedSysid])

  const loadTelemetry = useCallback(async (sysid) => {
    try {
      const [t, tr] = await Promise.all([api.getTelemetry(sysid), api.getTrack(sysid)])
      setTelemetry(t)
      setTrack(Array.isArray(tr) ? tr : [])
    } catch (e) {
      setTelemetry(null)
      setTrack([])
    }
  }, [])

  // refreshDrones 轮询：正常 2s，API 离线时指数退避（2→4→8→16→30s）
  // 经验来源：2026-09-19-settimeout-recursive-send-chain-unmount-cleanup（递归 setTimeout + cleanup）
  const BACKOFF_STEPS = [2000, 4000, 8000, 16000, 30000]
  useEffect(() => {
    let timer = null
    let backoffIndex = 0

    const poll = async () => {
      await refreshDrones()
      // 通过 ref 读取最新 apiOk 状态，避免闭包捕获旧值
      if (!apiOkRef.current) {
        backoffIndex = Math.min(backoffIndex + 1, BACKOFF_STEPS.length - 1)
      } else {
        backoffIndex = 0
      }
      timer = setTimeout(poll, BACKOFF_STEPS[backoffIndex])
    }

    timer = setTimeout(poll, BACKOFF_STEPS[0])
    return () => clearTimeout(timer)
  }, [refreshDrones])

  useEffect(() => {
    if (selectedSysid != null) loadTelemetry(selectedSysid)
  }, [selectedSysid, loadTelemetry])

  // 时钟
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])

  // 丐版模式切换后，若当前视图在该档位下不可用，自动回退到主操控视图
  useEffect(() => {
    if (!isPanelAvailable(VIEW_PANEL_MAP[view], budgetMode)) {
      setView('control')
    }
  }, [budgetMode, view])

  // WebSocket 实时遥测（断线 3s 自动重连）
  useEffect(() => {
    let ws
    let closed = false
    let retryTimer = null

    const connect = () => {
      ws = new WebSocket(wsUrl)
      wsRef.current = ws
      ws.onopen = () => setWsState('open')
      ws.onclose = () => {
        setWsState('closed')
        if (!closed) retryTimer = setTimeout(connect, 3000)
      }
      ws.onmessage = (ev) => {
        let msg
        try {
          msg = JSON.parse(ev.data)
        } catch (e) {
          return
        }
        if (msg.type === 'telemetry' || msg.type === 'status') {
          if (msg.sysid === selectedSysidRef.current) {
            setTelemetry((prev) => ({ ...(prev || {}), ...msg.data, sysid: msg.sysid }))
          }
          // 累积多机轨迹（每架机保留最近30个点）
          setMultiTracks((prev) => {
            const sysid = msg.sysid
            const d = msg.data || {}
            if (d.lat == null || d.lon == null) return prev
            const point = { lat: d.lat, lon: d.lon, alt: d.relativeAlt || d.alt || 0, ts: Date.now() }
            const existing = prev[sysid] || []
            const next = [...existing, point]
            return { ...prev, [sysid]: next.length > 30 ? next.slice(next.length - 30) : next }
          })
          // 追加遥测历史数据点（保留最近 120 个）
          const d = msg.data || {}
          setTelemetryHistory((prev) => {
            const point = {
              ts: Date.now(),
              sysid: msg.sysid,
              voltage: d.voltage,
              battery: d.battery,
              relativeAlt: d.relativeAlt,
              groundspeed: d.groundspeed,
              heading: d.heading,
            }
            const next = [...prev, point]
            return next.length > 120 ? next.slice(next.length - 120) : next
          })
        } else if (msg.type === 'alert') {
          setAlerts((prev) => [{ ...msg.data, ts: Date.now(), sysid: msg.sysid }, ...prev.slice(0, 49)])
        } else if (msg.type === 'formation') {
          // 编队状态推送（FormationPusher 1Hz）：data 为单编队或编队数组
          setFormations((prev) => {
            const data = msg.data
            if (Array.isArray(data)) return data
            if (!data || data.formationId == null) return prev
            const idx = prev.findIndex((f) => f.formationId === data.formationId)
            if (idx >= 0) {
              const next = [...prev]
              next[idx] = data
              return next
            }
            return [...prev, data]
          })
        } else if (msg.type === 'mesh-topology') {
          // mesh 拓扑变化推送（MeshTopologyPusher 2Hz）
          setMeshTopology(msg)
        } else if (msg.type === 'sat-link') {
          // 星-空-地中继数据推送（SatLinkPusher 2Hz）
          setSatLinkData(msg)
        } else if (msg.type === 'terrain-update' || msg.type === 'terrain-restriction') {
          // 地形变更/限制区推送（TerrainPusher 2Hz，M8 FR-31）
          setTerrainData(msg)
        } else if (msg.type === 'celltower-topology') {
          // 基站拓扑变化推送（CellTowerPusher 2Hz，M6）
          setCellTowerData(msg)
        }
      }
    }
    connect()
    return () => {
      closed = true
      clearTimeout(retryTimer)
      ws.close()
    }
  }, []) // WebSocket 只连接一次，selectedSysid 变化通过 ref 读取，不重连

  // 未认证时渲染登录面板
  if (!authed) {
    return <LoginPanel onLoginSuccess={() => setAuthed(true)} />
  }

  const currentUser = getCurrentUser()
  const isAdmin = currentUser && currentUser.role === 'ADMIN'

  const handleLogout = () => {
    logout()
    setAuthed(false)
  }

  return (
    <div className="gcs-root">
      <header className="topbar">
        <div className="brand">
          <svg className="brand-mark" width="26" height="26" viewBox="0 0 26 26" role="img" aria-label="NexusSky 标志">
            <title>NexusSky 无人机地面站标志</title>
            <circle cx="13" cy="13" r="11.5" fill="none" stroke="#00d4ff" stroke-width="1.2" />
            <path d="M13 4 A 9 9 0 0 1 22 13 L 13 13 Z" fill="#00d4ff" opacity=".35" />
            <path d="M13 13 L 20 20" stroke="#00d4ff" stroke-width="1.4" />
            <circle cx="13" cy="13" r="2.2" fill="#00d4ff" />
          </svg>
          <div className="brand-text">
            <span className="brand-name">NEXUSSKY</span>
            <span className="brand-sub">天枢 · 无人机地面站</span>
          </div>
        </div>

        <div className="topbar-center">
          <div className="view-tabs" style={{ display: 'inline-flex', gap: 4, marginRight: 6 }}>
            {[
              { key: 'dashboard', label: '仪表盘' },
              { key: 'scene3d', label: '3D 视图' },
              { key: 'control', label: '操控' },
              { key: 'formation', label: '编队' },
              { key: 'spray', label: '喷洒' },
              { key: 'hardware', label: '硬件' },
              { key: 'mesh', label: 'Mesh' },
              { key: 'celltower', label: '基站' },
              { key: 'satlink', label: '星地中继' },
              { key: 'terrain', label: '地形' },
              { key: 'emergency', label: '应急编排' },
              { key: 'surveillance', label: '安防监控' },
              { key: 'alarm', label: '报警联动' },
              { key: 'tracking', label: '追踪' },
              { key: 'geofence', label: '围栏' },
              { key: 'dronelock', label: '锁机' },
              { key: 'autodispatch', label: '自动出警' },
              { key: 'scenariolib', label: '场景库' },
              { key: 'inspection', label: '智能巡检' },
              { key: 'health', label: '健康管理' },
              { key: 'commadapt', label: '通信自适应' },
              { key: 'mapping', label: '航拍测绘' },
              { key: 'voicecmd', label: '语音指挥' },
              { key: 'citytwin', label: '数字孪生' },
              { key: 'delivery', label: '物流配送' },
              { key: 'show', label: '编队表演' },
              ...(isAdmin ? [
                { key: 'tenants', label: '租户管理' },
                { key: 'users', label: '用户管理' },
              ] : []),
            ]
              .filter((tab) => isPanelAvailable(VIEW_PANEL_MAP[tab.key], budgetMode))
              .map((tab) => (
                <button
                  key={tab.key}
                  className={`btn ${view === tab.key ? 'primary' : ''}`}
                  style={{ padding: '4px 12px', fontSize: 11 }}
                  onClick={() => setView(tab.key)}
                >
                  {tab.label}
                </button>
              ))}
          </div>
          <span className="chip mono">{new Date(now).toLocaleTimeString('zh-CN', { hour12: false })}</span>
          <span className="chip">
            机队 <b className="mono">{onlineCount}</b>
            <span className="dim">/{drones.length}</span>
          </span>
          <span className={`chip ${apiOk ? 'ok' : 'bad'}`}>
            <i className="dotp" /> 云端 {apiOk ? '已连接' : '离线'}
          </span>
        </div>

        <div className="topbar-right">
          {/* 丐版预算档位切换：选择后隐藏不可用面板 */}
          <select
            value={budgetMode || ''}
            onChange={(e) => setBudgetMode(e.target.value || null)}
            title="切换预算档位"
            aria-label="切换预算档位"
            style={{ padding: '4px 8px', fontSize: 11, marginRight: 6 }}
          >
            <option value="">完整版</option>
            <option value={BUDGET_MODES.TOY}>丐版·百元级</option>
            <option value={BUDGET_MODES.STANDARD}>丐版·千元级</option>
            <option value={BUDGET_MODES.ADVANCED}>丐版·进阶</option>
          </select>
          <BudgetBadge mode={budgetMode} />
          <span className={`ws-badge ${wsState === 'open' ? 'ok' : 'bad'}`}>
            <i className="dotp" />
            {wsState === 'open' ? 'LIVE' : 'RECONNECTING'}
          </span>
          {/* 移动端侧栏切换按钮（汉堡菜单），仅在小屏显示 */}
          <button
            className="btn mobile-rail-toggle"
            onClick={() => setMobileRail(mobileRail === 'left' ? null : 'left')}
            title="机队/任务侧栏"
            aria-label="切换机队侧栏"
          >
            <span className="icon">☰</span>
          </button>
          <button
            className="btn mobile-rail-toggle"
            onClick={() => setMobileRail(mobileRail === 'right' ? null : 'right')}
            title="操控/告警侧栏"
            aria-label="切换操控侧栏"
          >
            <span className="icon">⚙</span>
          </button>
          {/* 当前用户信息 */}
          {currentUser && (
            <span className="chip" title={currentUser.username}>
              <span style={{ fontSize: 11, color: 'var(--cyan)' }}>
                {currentUser.role === 'ADMIN' ? '管理员' : currentUser.role === 'OPERATOR' ? '操作员' : '观察者'}
              </span>
              <span className="dim" style={{ fontSize: 10 }}>{currentUser.username}</span>
            </span>
          )}
          {/* 登出按钮 */}
          <button
            className="btn"
            onClick={handleLogout}
            title="登出"
            style={{ padding: '4px 10px', fontSize: 11 }}
          >
            登出
          </button>
        </div>
      </header>

      {view === 'dashboard' ? (
        <div className="gcs-body" style={{ display: 'block', overflow: 'auto' }}>
          <DashboardPanel
            drones={drones}
            onSelect={(sysid) => {
              setSelectedSysid(sysid)
              setView('control')
            }}
          />
        </div>
      ) : view === 'formation' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <FormationPanel formations={formations} drones={drones} />
        </div>
      ) : view === 'spray' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <SprayPanel drones={drones} />
        </div>
      ) : view === 'hardware' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <HardwarePanel drones={drones} />
        </div>
      ) : view === 'mesh' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <MeshTopologyPanel meshTopology={meshTopology} />
        </div>
      ) : view === 'celltower' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <CellTowerPanel cellTowerData={cellTowerData} />
        </div>
      ) : view === 'satlink' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <SatLinkPanel satLinkData={satLinkData} />
        </div>
      ) : view === 'terrain' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <TerrainMapPanel terrainData={terrainData} />
        </div>
      ) : view === 'emergency' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <EmergencyOrchPanel />
        </div>
      ) : view === 'surveillance' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <SurveillancePanel />
        </div>
      ) : view === 'alarm' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <AlarmPanel />
        </div>
      ) : view === 'tracking' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <TrackingPanel
            onTrackLoaded={(overlay) => setTrackingOverlay(overlay)}
          />
        </div>
      ) : view === 'geofence' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <GeofencePanel />
        </div>
      ) : view === 'dronelock' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <DroneLockPanel />
        </div>
      ) : view === 'autodispatch' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <AutoDispatchPanel />
        </div>
      ) : view === 'scenariolib' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <ScenarioLibraryPanel />
        </div>
      ) : view === 'inspection' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <InspectionPanel />
        </div>
      ) : view === 'health' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <HealthPanel />
        </div>
      ) : view === 'commadapt' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <CommAdaptPanel />
        </div>
      ) : view === 'mapping' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <MappingPanel />
        </div>
      ) : view === 'voicecmd' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <VoiceCmdPanel />
        </div>
      ) : view === 'citytwin' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <CityTwinPanel />
        </div>
      ) : view === 'delivery' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <DeliveryPanel />
        </div>
      ) : view === 'show' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <ShowPanel />
        </div>
      ) : view === 'tenants' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <TenantPanel />
        </div>
      ) : view === 'users' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <UserPanel />
        </div>
      ) : view === 'scene3d' ? (
        <div className="scene3d-layout">
          <div className="scene3d-main">
            <Scene3D
              drones={drones}
              telemetry={telemetry}
              track={track}
              formations={formations}
              selected={selected}
              terrainData={terrainData}
            />
          </div>
          <div className="scene3d-side">
            <Trajectory3D
              track={track}
              missionDraft={missionDraft}
              telemetry={telemetry}
              selected={selected}
            />
          </div>
        </div>
      ) : (
      <div className="gcs-body">
        {/* 移动端侧栏抽屉遮罩：点击/Enter/Escape 关闭（带 role/tabIndex/aria-label 保证键盘与读屏可访问） */}
        {mobileRail && (
          <div
            className="mobile-rail-mask"
            role="button"
            aria-label="关闭侧栏"
            tabIndex={0}
            onClick={() => setMobileRail(null)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === 'Escape') {
                e.preventDefault()
                setMobileRail(null)
              }
            }}
          />
        )}
        <aside className={`rail left ${mobileRail === 'left' ? 'mobile-open' : ''}`}>
          <DroneList drones={drones} selectedSysid={selectedSysid} onSelect={(sysid) => { setSelectedSysid(sysid); setMobileRail(null) }} />
          <MissionPlanner
            drone={selected}
            missionDraft={missionDraft}
            setMissionDraft={setMissionDraft}
            onUploaded={() => loadTelemetry(selectedSysid)}
          />
        </aside>

        <main className="stage">
          <MapView
            telemetry={telemetry}
            track={track}
            missionDraft={missionDraft}
            selected={selected}
            orbitOverlay={orbitOverlay}
            drones={drones}
            multiTracks={multiTracks}
            trackColorMode={trackColorMode}
            replayTrack={replayTrack}
            replayProgress={replayProgress}
            formations={formations}
            trackingOverlay={trackingOverlay}
            onDroneSelect={(sysid) => setSelectedSysid(sysid)}
            onMapClick={({ lat, lon }) => {
              // Shift+click on the map appends a waypoint to the draft
              setMissionDraft((prev) => [
                ...prev,
                { cmd: 'waypoint', lat, lon, alt: 50, holdTime: 2 },
              ])
            }}
          />
          {/* 故障状态横幅：GPS 降级 / 链路丢失时压在地图上方 */}
          {telemetry && telemetry.gpsHealthy === false && (
            <div className="fault-banner crit">
              <span className="fault-icon">⚠</span>
              <div>
                <b>GPS 信号降级</b>
                <span className="fault-sub">
                  fixType {telemetry.fixType ?? '--'} · 卫星 {telemetry.satellites ?? '--'} —— 位置报告不可信
                </span>
              </div>
            </div>
          )}
          {telemetry && telemetry.online === false && (
            <div className="fault-banner warn">
              <span className="fault-icon">📡</span>
              <div>
                <b>链路丢失</b>
                <span className="fault-sub">心跳超时 —— 设备已标记离线</span>
              </div>
            </div>
          )}
          <div className="stage-strip">
            <span>飞行时间 <b className="mono">{fmtDur(stats.dur)}</b></span>
            <span>航程 <b className="mono">{stats.dist >= 1 ? stats.dist.toFixed(2) + ' km' : Math.round(stats.dist * 1000) + ' m'}</b></span>
            <span>最高高度 <b className="mono">{stats.maxAlt ? stats.maxAlt.toFixed(0) + ' m' : '--'}</b></span>
            {selected && <span>链路 <b className="mono">UDP 14550</b></span>}
          </div>
        </main>

        <aside className={`rail right ${mobileRail === 'right' ? 'mobile-open' : ''}`}>
          <Joystick drone={selected} />
          <VisionPanel
            drone={selected}
            onOrbitActive={(active, overlay) => {
              // Draw the orbit ring while a job runs; clear it on completion
              setOrbitOverlay(active ? overlay : null)
            }}
          />
          <TelemetryPanel
            drone={selected}
            telemetry={telemetry}
            onCommand={async (type, alt) => {
              if (selectedSysid == null) return { status: 'error', result: '未选择设备' }
              return api.sendCommand(selectedSysid, type, alt)
            }}
          />
          <TelemetryCharts telemetry={telemetry} history={telemetryHistory} />
          <AlertFeed alerts={alerts} />
        </aside>
      </div>
      )}
    </div>
  )
}
