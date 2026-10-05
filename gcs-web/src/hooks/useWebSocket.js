import { useState, useRef, useEffect } from 'react'
import { getWsUrl } from '../api.js'

/** 每台机的遥测历史保留点数。与 TelemetryCharts 的 60s 窗口配套（5Hz × 60s = 300）。 */
const HISTORY_PER_DRONE = 300

/**
 * WebSocket 实时数据管理 hook
 * 管理 wsState 及所有 WebSocket 推送数据：
 * alerts, formations, meshTopology, satLinkData, terrainData, cellTowerData,
 * telemetryHistory, multiTracks, twinStates, decisionEvents, adaptivePaths, edgeTasks
 *
 * telemetryHistory 的形状是 `{ [sysid]: Point[] }`（按机分桶）——消费方应取
 * `telemetryHistory[sysid]`，不要把它当扁平数组遍历。
 *
 * 关键约束：selectedSysid 通过 ref 读取，WebSocket 只连接一次，不因 selectedSysid 变化重连
 * 经验来源：2026-09-13-yjs-multi-provider-destroy-order（effect 依赖与 ref 解耦模式）
 *
 * @param {React.MutableRefObject} selectedSysidRef - 当前选中无人机的 sysid ref
 * @param {Function} setTelemetry - 来自 useDrones 的 telemetry setter（用于实时更新遥测）
 */
export default function useWebSocket(selectedSysidRef, setTelemetry) {
  const [wsState, setWsState] = useState('connecting')
  const [alerts, setAlerts] = useState([])
  const [formations, setFormations] = useState([])
  const [meshTopology, setMeshTopology] = useState(null)
  const [satLinkData, setSatLinkData] = useState(null)
  const [terrainData, setTerrainData] = useState(null)
  const [cellTowerData, setCellTowerData] = useState(null)
  const [telemetryHistory, setTelemetryHistory] = useState({})
  const [multiTracks, setMultiTracks] = useState({})
  // M13 每机孪生同步（TwinSyncListener 1Hz 发布 30055 → WS "twin-state-sync" 帧）。
  // 形状 { [sysid]: { ...data, receivedAt } }，保留 MAVLink 原始单位，
  // 单位换算由 utils/twinSync.js 的纯函数在消费侧做（便于直测）。
  const [twinStates, setTwinStates] = useState({})

  // M11 AI 自主决策三帧（"decision-event" 30051 / "adaptive-path" 30052 /
  // "edge-task-status" 30053）：事件流数组，新帧头插、各留最近 50 条（与 alerts 同模式）。
  // 决策/航迹改写/边缘任务是离散事件而非可覆盖状态，不能按 sysid 只留最新。
  // 形状 [{ ...data, sysid, receivedAt }]，归一化由 utils/aiDecision.js 在消费侧做。
  const [decisionEvents, setDecisionEvents] = useState([])
  const [adaptivePaths, setAdaptivePaths] = useState([])
  const [edgeTasks, setEdgeTasks] = useState([])

  // 感知侧三帧（"vision-detection" 30014 / "sensor-fusion" 30054 / "prediction-result" 30056）：
  // 此前后端已由 WS_TYPE_MAP 下发，但前端无消费分支，帧到即被丢弃（AiDecisionPanel 只覆盖 30051-30053）。
  // 保留策略按帧的语义分两类：视觉检测/轨迹预测是离散事件 -> 数组头插留 50 条；
  // 传感器融合是周期状态（2Hz 级）-> 按 sysid 只留最新，避免刷屏且天然覆盖旧值。
  const [visionDetections, setVisionDetections] = useState([])
  const [sensorFusions, setSensorFusions] = useState({})
  const [predictions, setPredictions] = useState([])

  const wsRef = useRef(null)

  // WebSocket 实时遥测（断线 3s 自动重连）
  useEffect(() => {
    let ws
    let closed = false
    let retryTimer = null

    const connect = () => {
      ws = new WebSocket(getWsUrl())
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
          // 追加遥测历史数据点。
          // 2026-10-01 修复：此前所有机型的点被塞进**同一个扁平数组**，
          // 而消费端（TelemetryCharts 的 series()）从不过滤 sysid ——
          // 2 架机以上时电量/高度/速度曲线会把不同飞机的数据交错画在一起。
          // 现在按 sysid 分桶，每桶独立保留最近 HISTORY_PER_DRONE 个点。
          // 容量依据：图表窗口 60s（TelemetryCharts.WINDOW_MS），遥测 1-5Hz，
          // 5Hz×60s=300 点刚好铺满窗口；再多是浪费内存（窗口外会被 series 丢弃）。
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
            const bucket = prev[msg.sysid] || []
            const next = [...bucket, point]
            return {
              ...prev,
              [msg.sysid]: next.length > HISTORY_PER_DRONE ? next.slice(next.length - HISTORY_PER_DRONE) : next,
            }
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
        } else if (msg.type === 'twin-state-sync') {
          // 每机孪生同步（TwinSyncListener 1Hz 节流，M13）：按 sysid 分桶只留最新
          setTwinStates((prev) => ({
            ...prev,
            [msg.sysid]: { ...(msg.data || {}), receivedAt: Date.now() },
          }))
        } else if (msg.type === 'decision-event') {
          // M11 决策事件（DECISION_EVENT 30051，决策引擎变化沿）：新帧头插，保留最近 50 条
          setDecisionEvents((prev) => [{ ...(msg.data || {}), sysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'adaptive-path') {
          // M11 自适应航迹改写（ADAPTIVE_PATH 30052，仅真实路径变化时下发）
          setAdaptivePaths((prev) => [{ ...(msg.data || {}), sysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'edge-task-status') {
          // M12 边缘任务状态（EDGE_TASK_STATUS 30053，机载边缘栈逐任务上报）
          setEdgeTasks((prev) => [{ ...(msg.data || {}), sysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'vision-detection') {
          // 视觉检测（VISION_DETECTION 30014，机载视觉源逐目标上报）：离散事件，新帧头插留 50 条
          setVisionDetections((prev) => [{ ...(msg.data || {}), sysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'sensor-fusion') {
          // 传感器融合态（SENSOR_FUSION_DATA 30054，周期上报）：可覆盖状态，按 sysid 只留最新
          setSensorFusions((prev) => ({ ...prev, [msg.sysid]: { ...(msg.data || {}), receivedAt: Date.now() } }))
        } else if (msg.type === 'prediction-result') {
          // 轨迹预测（PREDICTION_RESULT 30056）：离散预测事件，新帧头插留 50 条
          setPredictions((prev) => [{ ...(msg.data || {}), sysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
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

  return {
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
  }
}