import React, { useMemo } from 'react'
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
import DisasterCommPanel from './components/DisasterCommPanel.jsx'
import TelemetryCharts from './components/TelemetryCharts.jsx'
import DashboardPanel from './components/DashboardPanel.jsx'
import Scene3D from './components/Scene3D.jsx'
import Trajectory3D from './components/Trajectory3D.jsx'
import { api, isPanelAvailable, BUDGET_MODES, getCurrentUser } from './api.js'
import BudgetBadge from './components/BudgetBadge.jsx'
import LoginPanel from './components/LoginPanel.jsx'
import TenantPanel from './components/TenantPanel.jsx'
import UserPanel from './components/UserPanel.jsx'
import useAuth from './hooks/useAuth.js'
import useDrones from './hooks/useDrones.js'
import useWebSocket from './hooks/useWebSocket.js'
import useUI, { VIEW_PANEL_MAP } from './hooks/useUI.js'
import useMission from './hooks/useMission.js'
import useReplay from './hooks/useReplay.js'

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

export default function App() {
  // 认证状态
  const { authed, setAuthed, handleLoginSuccess, handleLogout } = useAuth()

  // 无人机数据（含指数退避轮询、遥测加载）
  const {
    drones,
    selectedSysid,
    setSelectedSysid,
    telemetry,
    setTelemetry,
    track,
    apiOk,
    selectedSysidRef,
    loadTelemetry,
  } = useDrones()

  // WebSocket 实时推送数据（依赖 selectedSysidRef 和 setTelemetry）
  const {
    wsState,
    alerts,
    formations,
    meshTopology,
    satLinkData,
    terrainData,
    cellTowerData,
    telemetryHistory,
    multiTracks,
  } = useWebSocket(selectedSysidRef, setTelemetry)

  // UI 状态（视图、时钟、移动端侧栏、丐版模式）
  const { view, setView, now, mobileRail, setMobileRail, budgetMode, setBudgetMode } = useUI()

  // 任务规划
  const { missionDraft, setMissionDraft, orbitOverlay, setOrbitOverlay } = useMission()

  // 回放与追踪
  const {
    replayTrack,
    replayProgress,
    trackingOverlay,
    setTrackingOverlay,
    trackColorMode,
  } = useReplay()

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

  // 未认证时渲染登录面板
  if (!authed) {
    return <LoginPanel onLoginSuccess={handleLoginSuccess} />
  }

  const currentUser = getCurrentUser()
  const isAdmin = currentUser && currentUser.role === 'ADMIN'

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
              { key: 'disastercomm', label: '灾害通信' },
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
      ) : view === 'disastercomm' ? (
        <div className="gcs-body" style={{ display: 'block' }}>
          <DisasterCommPanel />
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
