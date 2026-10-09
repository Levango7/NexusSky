import React, { useMemo } from 'react'
import { haversine } from './utils/geo'
import { fmtDur } from './utils/format'
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
import RidPanel from './components/RidPanel.jsx'
import CvEvalPanel from './components/CvEvalPanel.jsx'
import DefectPanel from './components/DefectPanel.jsx'
import RouteTemplatePanel from './components/RouteTemplatePanel.jsx'
import OpsReportPanel from './components/OpsReportPanel.jsx'
import RocPanel from './components/RocPanel.jsx'
import SensingPanel from './components/SensingPanel.jsx'
import DockPanel from './components/DockPanel.jsx'
import DroneLockPanel from './components/DroneLockPanel.jsx'
import AutoDispatchPanel from './components/AutoDispatchPanel.jsx'
import ScenarioLibraryPanel from './components/ScenarioLibraryPanel.jsx'
import InspectionPanel from './components/InspectionPanel.jsx'
import HealthPanel from './components/HealthPanel.jsx'
import CommAdaptPanel from './components/CommAdaptPanel.jsx'
import MappingPanel from './components/MappingPanel.jsx'
import VoiceCmdPanel from './components/VoiceCmdPanel.jsx'
import CityTwinPanel from './components/CityTwinPanel.jsx'
import AiDecisionPanel from './components/AiDecisionPanel.jsx'
import FleetOpsPanel from './components/FleetOpsPanel.jsx'
import DeliveryPanel from './components/DeliveryPanel.jsx'
import ShowPanel from './components/ShowPanel.jsx'
import DisasterCommPanel from './components/DisasterCommPanel.jsx'
import UnifiedCommandPanel from './components/UnifiedCommandPanel.jsx'
import VideoFusionPanel from './components/VideoFusionPanel.jsx'
import ThermalOverlayPanel from './components/ThermalOverlayPanel.jsx'
import WeatherLayerPanel from './components/WeatherLayerPanel.jsx'
import LinkQualityPanel from './components/LinkQualityPanel.jsx'
import Trajectory3DPanel from './components/Trajectory3DPanel.jsx'
import TelemetryCharts from './components/TelemetryCharts.jsx'
import DashboardPanel from './components/DashboardPanel.jsx'
import Scene3D from './components/Scene3D.jsx'
import Trajectory3D from './components/Trajectory3D.jsx'
import { api, BUDGET_MODES, getCurrentUser } from './api.js'
import BudgetBadge from './components/BudgetBadge.jsx'
import LoginPanel from './components/LoginPanel.jsx'
import TenantPanel from './components/TenantPanel.jsx'
import UserPanel from './components/UserPanel.jsx'
import NavGroups from './components/NavGroups.jsx'
import useAuth from './hooks/useAuth.js'
import useDrones from './hooks/useDrones.js'
import useWebSocket from './hooks/useWebSocket.js'
import useUI from './hooks/useUI.js'
import useMission from './hooks/useMission.js'
import useReplay from './hooks/useReplay.js'

class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error }
  }

  componentDidCatch(error, errorInfo) {
    console.error('面板组件崩溃:', error, errorInfo)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24, height: '100%' }}>
          <div style={{ textAlign: 'center', color: '#888' }}>
            <p style={{ fontSize: 14, margin: '4px 0' }}>⚠ 面板组件崩溃</p>
            <p style={{ fontSize: 12, margin: '4px 0' }}>{this.state.error?.message}</p>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}

function PanelWrapper({ children }) {
  return (
    <div className="gcs-body" style={{ display: 'block' }}>
      <ErrorBoundary>
        {children}
      </ErrorBoundary>
    </div>
  )
}

// 距离与时长格式化：见 utils/geo

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
    twinStates,
    decisionEvents,
    adaptivePaths,
    edgeTasks,
    visionDetections,
    sensorFusions,
    predictions,
    taskAssignments,
    conflictAlerts,
    taskStatuses,
    alarmTriggers,
    alarmAcks,
    surveillanceStatuses,
  } = useWebSocket(selectedSysidRef, setTelemetry)

  // UI 状态（视图、时钟、移动端侧栏、丐版模式）
  const { view: storedView, setView, now, mobileRail, setMobileRail, budgetMode, setBudgetMode } = useUI()

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
  // 越权视图防御：视图会持久化到 localStorage（也可被手工改值），非管理员
  // 落进管理视图时不给渲染入口。后端 RBAC 是最终防线（无注解即 403），
  // 这里只保证 UI 不出现越权面板。
  const view = (!isAdmin && (storedView === 'tenants' || storedView === 'users')) ? 'dashboard' : storedView

  return (
    <div className="gcs-root">
      <header className="topbar">
        <div className="brand">
          <svg className="brand-mark" width="26" height="26" viewBox="0 0 26 26" role="img" aria-label="NexusSky 标志">
            <title>NexusSky 无人机地面站标志</title>
            <circle cx="13" cy="13" r="11.5" fill="none" stroke="#00d4ff" strokeWidth="1.2" />
            <path d="M13 4 A 9 9 0 0 1 22 13 L 13 13 Z" fill="#00d4ff" opacity=".35" />
            <path d="M13 13 L 20 20" stroke="#00d4ff" strokeWidth="1.4" />
            <circle cx="13" cy="13" r="2.2" fill="#00d4ff" />
          </svg>
          <div className="brand-text">
            <span className="brand-name">NEXUSSKY</span>
            <span className="brand-sub">天枢 · 无人机地面站</span>
          </div>
        </div>

        <div className="topbar-center">
          <NavGroups view={view} onSelect={setView} budgetMode={budgetMode} isAdmin={isAdmin} />
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
            <option disabled>────────</option>
            <option value={BUDGET_MODES.EMERGENCY_TOY}>应急·百元级</option>
            <option value={BUDGET_MODES.EMERGENCY_STANDARD}>应急·千元级</option>
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
          {/* 当前用户信息（移动端由 CSS 隐藏，用户名仍在 title 里） */}
          {currentUser && (
            <span className="chip user-chip" title={currentUser.username}>
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
          <ErrorBoundary>
            <DashboardPanel
              drones={drones}
              onSelect={(sysid) => {
                setSelectedSysid(sysid)
                setView('control')
              }}
            />
          </ErrorBoundary>
        </div>
      ) : view === 'formation' ? (
        <PanelWrapper>
          <FormationPanel formations={formations} drones={drones} />
        </PanelWrapper>
      ) : view === 'spray' ? (
        <PanelWrapper>
          <SprayPanel drones={drones} />
        </PanelWrapper>
      ) : view === 'hardware' ? (
        <PanelWrapper>
          <HardwarePanel drones={drones} />
        </PanelWrapper>
      ) : view === 'mesh' ? (
        <PanelWrapper>
          <MeshTopologyPanel meshTopology={meshTopology} />
        </PanelWrapper>
      ) : view === 'celltower' ? (
        <PanelWrapper>
          <CellTowerPanel cellTowerData={cellTowerData} />
        </PanelWrapper>
      ) : view === 'satlink' ? (
        <PanelWrapper>
          <SatLinkPanel satLinkData={satLinkData} />
        </PanelWrapper>
      ) : view === 'terrain' ? (
        <PanelWrapper>
          <TerrainMapPanel terrainData={terrainData} />
        </PanelWrapper>
      ) : view === 'emergency' ? (
        <PanelWrapper>
          <EmergencyOrchPanel />
        </PanelWrapper>
      ) : view === 'surveillance' ? (
        <PanelWrapper>
          <SurveillancePanel />
        </PanelWrapper>
      ) : view === 'alarm' ? (
        <PanelWrapper>
          <AlarmPanel />
        </PanelWrapper>
      ) : view === 'tracking' ? (
        <PanelWrapper>
          <TrackingPanel
            onTrackLoaded={(overlay) => setTrackingOverlay(overlay)}
          />
        </PanelWrapper>
      ) : view === 'geofence' ? (
        <PanelWrapper>
          <GeofencePanel />
        </PanelWrapper>
      ) : view === 'rid' ? (
        <PanelWrapper>
          <RidPanel />
        </PanelWrapper>
      ) : view === 'cveval' ? (
        <PanelWrapper>
          <CvEvalPanel />
        </PanelWrapper>
      ) : view === 'defect' ? (
        <PanelWrapper>
          <DefectPanel />
        </PanelWrapper>
      ) : view === 'routetpl' ? (
        <PanelWrapper>
          <RouteTemplatePanel drone={selected} />
        </PanelWrapper>
      ) : view === 'opsreport' ? (
        <PanelWrapper>
          <OpsReportPanel />
        </PanelWrapper>
      ) : view === 'roc' ? (
        <PanelWrapper>
          <RocPanel />
        </PanelWrapper>
      ) : view === 'sensing' ? (
        <PanelWrapper>
          <SensingPanel />
        </PanelWrapper>
      ) : view === 'dock' ? (
        <PanelWrapper>
          <DockPanel />
        </PanelWrapper>
      ) : view === 'dronelock' ? (
        <PanelWrapper>
          <DroneLockPanel />
        </PanelWrapper>
      ) : view === 'autodispatch' ? (
        <PanelWrapper>
          <AutoDispatchPanel />
        </PanelWrapper>
      ) : view === 'aidecision' ? (
        <PanelWrapper>
          <AiDecisionPanel
            decisionEvents={decisionEvents}
            adaptivePaths={adaptivePaths}
            edgeTasks={edgeTasks}
            visionDetections={visionDetections}
            sensorFusions={sensorFusions}
            predictions={predictions}
          />
        </PanelWrapper>
      ) : view === 'fleetops' ? (
        <PanelWrapper>
          <FleetOpsPanel
            taskAssignments={taskAssignments}
            conflictAlerts={conflictAlerts}
            taskStatuses={taskStatuses}
            alarmTriggers={alarmTriggers}
            alarmAcks={alarmAcks}
            surveillanceStatuses={surveillanceStatuses}
          />
        </PanelWrapper>
      ) : view === 'scenariolib' ? (
        <PanelWrapper>
          <ScenarioLibraryPanel />
        </PanelWrapper>
      ) : view === 'inspection' ? (
        <PanelWrapper>
          <InspectionPanel />
        </PanelWrapper>
      ) : view === 'health' ? (
        <PanelWrapper>
          <HealthPanel />
        </PanelWrapper>
      ) : view === 'commadapt' ? (
        <PanelWrapper>
          <CommAdaptPanel />
        </PanelWrapper>
      ) : view === 'mapping' ? (
        <PanelWrapper>
          <MappingPanel />
        </PanelWrapper>
      ) : view === 'voicecmd' ? (
        <PanelWrapper>
          <VoiceCmdPanel />
        </PanelWrapper>
      ) : view === 'citytwin' ? (
        <PanelWrapper>
          <CityTwinPanel twinStates={twinStates} />
        </PanelWrapper>
      ) : view === 'delivery' ? (
        <PanelWrapper>
          <DeliveryPanel />
        </PanelWrapper>
      ) : view === 'show' ? (
        <PanelWrapper>
          <ShowPanel />
        </PanelWrapper>
      ) : view === 'disastercomm' ? (
        <PanelWrapper>
          <DisasterCommPanel />
        </PanelWrapper>
      ) : view === 'unifiedcmd' ? (
        <PanelWrapper>
          <UnifiedCommandPanel />
        </PanelWrapper>
      ) : view === 'videofusion' ? (
        <PanelWrapper>
          <VideoFusionPanel />
        </PanelWrapper>
      ) : view === 'thermal' ? (
        <PanelWrapper>
          <ThermalOverlayPanel />
        </PanelWrapper>
      ) : view === 'weather' ? (
        <PanelWrapper>
          <WeatherLayerPanel />
        </PanelWrapper>
      ) : view === 'linkquality' ? (
        <PanelWrapper>
          <LinkQualityPanel />
        </PanelWrapper>
      ) : view === 'trajectory3d' ? (
        <PanelWrapper>
          <Trajectory3DPanel
            track={track}
            telemetry={telemetry}
            selected={selected}
            missionDraft={missionDraft}
          />
        </PanelWrapper>
      ) : view === 'tenants' ? (
        <PanelWrapper>
          <TenantPanel />
        </PanelWrapper>
      ) : view === 'users' ? (
        <PanelWrapper>
          <UserPanel />
        </PanelWrapper>
      ) : view === 'scene3d' ? (
        <div className="scene3d-layout">
          <ErrorBoundary>
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
          </ErrorBoundary>
          <ErrorBoundary>
            <div className="scene3d-side">
              <Trajectory3D
                track={track}
                missionDraft={missionDraft}
                telemetry={telemetry}
                selected={selected}
              />
            </div>
          </ErrorBoundary>
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
          <ErrorBoundary>
            <DroneList drones={drones} selectedSysid={selectedSysid} onSelect={(sysid) => { setSelectedSysid(sysid); setMobileRail(null) }} />
          </ErrorBoundary>
          <ErrorBoundary>
            <MissionPlanner
              drone={selected}
              missionDraft={missionDraft}
              setMissionDraft={setMissionDraft}
              onUploaded={() => loadTelemetry(selectedSysid)}
            />
          </ErrorBoundary>
        </aside>

        <main className="stage">
          <ErrorBoundary>
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
          </ErrorBoundary>
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
          <ErrorBoundary>
            <Joystick drone={selected} />
          </ErrorBoundary>
          <ErrorBoundary>
            <VisionPanel
              drone={selected}
              onOrbitActive={(active, overlay) => {
                // Draw the orbit ring while a job runs; clear it on completion
                setOrbitOverlay(active ? overlay : null)
              }}
            />
          </ErrorBoundary>
          <ErrorBoundary>
            <TelemetryPanel
              drone={selected}
              telemetry={telemetry}
              onCommand={async (type, alt) => {
                if (selectedSysid == null) return { status: 'error', result: '未选择设备' }
                return api.sendCommand(selectedSysid, type, alt)
              }}
            />
          </ErrorBoundary>
          <ErrorBoundary>
            <TelemetryCharts
              telemetry={telemetry}
              history={selectedSysid != null ? telemetryHistory[selectedSysid] : undefined}
              sysid={selectedSysid}
            />
          </ErrorBoundary>
          <ErrorBoundary>
            <AlertFeed alerts={alerts} />
          </ErrorBoundary>
        </aside>
      </div>
      )}
    </div>
  )
}
