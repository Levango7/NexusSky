import { useState, useRef, useEffect } from 'react'
import { getWsUrl } from '../api.js'

/** 每台机的遥测历史保留点数。与 TelemetryCharts 的 60s 窗口配套（5Hz × 60s = 300）。 */
const HISTORY_PER_DRONE = 300

/** 重连退避：1s 起步，每次翻倍，封顶 30s。 */
const RECONNECT_BASE_MS = 1_000
const RECONNECT_MAX_MS = 30_000

/**
 * 静默看门狗阈值。服务端 1Hz 起推（多机时更高），45s 一个帧都没有即在链路上已经死了
 * ——典型诱因：NAT/代理把连接静默回收（不发 FIN，onclose 永不到来）。
 * 浏览器 WebSocket 无法主动发 WS PING（JS API 不发控制帧），服务端也只读不认上行帧，
 * 所以只能靠"多久没收到东西"判定，到了就主动重连。
 */
const SILENCE_TIMEOUT_MS = 45_000

/**
 * UI 刷新节流窗口。遥测帧按机 1-5Hz、多机并发可达 45 帧/秒，逐帧 setState 会让
 * React 每帧重渲染整棵树。这里先把帧按机缓存，窗口末一次性合并落 state
 * （history 逐条追加不丢点，快照只取每机最后一帧）。
 */
const FLUSH_INTERVAL_MS = 100

/**
 * 缓存帧的最大年龄。超过即从缓冲头部裁掉——比"按条数裁"更讲道理：
 * 按条数裁会在一次突发灌帧（重连补发/测试批量灌）时丢掉中间点，而曲线要的是密度；
 * 按年龄裁只丢"已经晚到没有绘制价值"的帧，内存上界仍是"1 秒的帧量"。
 */
const BUFFER_MAX_AGE_MS = 1_000

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
 * @param {Function} [appendTrackPoint] - 来自 useDrones 的实时轨迹点追加器（可选）
 */
export default function useWebSocket(selectedSysidRef, setTelemetry, appendTrackPoint) {
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

  // M10 集群调度三帧 + 4a 空地联动三帧（2026-10-06 补消费）。
  // 这六帧后端自 2026-10-05 起就已有真实生产者（TaskAssignmentService / ConflictScanService /
  // AlarmLinkageEngine / SurveillanceStatusPusher），但前端此前**无匹配分支**——
  // 帧到达 onmessage 末尾即被静默丢弃。后果：M10 全部对外价值对操作员不可见
  // （谁被派了任务、两机何时会撞、进度到哪一步只存在于云端日志）。
  // 保留策略按语义分两类，与上方感知三帧同一判据：
  //   离散事件（分配/冲突/状态迁移/报警触发/报警确认）-> 数组头插留 50 条；
  //   周期状态（安防设备 1Hz 推送）-> 按 deviceId 只留最新。
  const [taskAssignments, setTaskAssignments] = useState([])
  const [conflictAlerts, setConflictAlerts] = useState([])
  const [taskStatuses, setTaskStatuses] = useState([])
  const [alarmTriggers, setAlarmTriggers] = useState([])
  const [alarmAcks, setAlarmAcks] = useState([])
  const [surveillanceStatuses, setSurveillanceStatuses] = useState({})

  const wsRef = useRef(null)

  // 待合并的遥测帧：sysid -> 帧数组（见 FLUSH_INTERVAL_MS 注释）
  const telemetryBufferRef = useRef({})
  const flushTimerRef = useRef(null)

  const flushTelemetry = () => {
    flushTimerRef.current = null
    const buffer = telemetryBufferRef.current
    telemetryBufferRef.current = {}
    const sysids = Object.keys(buffer)
    if (sysids.length === 0) return

    // 选中机的快照：最后一个到达的帧
    const selectedSysid = selectedSysidRef.current
    for (const sysid of sysids) {
      const frames = buffer[sysid]
      const last = frames[frames.length - 1]
      if (Number(sysid) === selectedSysid) {
        setTelemetry((prev) => ({ ...(prev || {}), ...last.data, sysid: Number(sysid) }))
        // 主轨迹实时增长：选中机的每个有效位置点都追加（MapView/统计消费 track）
        if (appendTrackPoint && last.data && last.data.lat != null && last.data.lon != null) {
          for (const frame of frames) {
            const d = frame.data || {}
            if (d.lat == null || d.lon == null) continue
            appendTrackPoint({
              lat: d.lat,
              lon: d.lon,
              alt: d.relativeAlt || d.alt || 0,
              ts: frame.receivedAt,
            })
          }
        }
      }

      // 多机轨迹：只跟到最后一帧的位置（中间态对折线无意义）
      setMultiTracks((prev) => {
        const d = last.data || {}
        if (d.lat == null || d.lon == null) return prev
        const point = { lat: d.lat, lon: d.lon, alt: d.relativeAlt || d.alt || 0, ts: last.receivedAt }
        const existing = prev[Number(sysid)] || []
        const next = [...existing, point]
        return { ...prev, [Number(sysid)]: next.length > 30 ? next.slice(next.length - 30) : next }
      })

      // 历史曲线：窗口内每帧都要（图表靠密度）
      setTelemetryHistory((prev) => {
        let bucket = prev[Number(sysid)] || []
        let next = bucket
        for (const frame of frames) {
          const d = frame.data || {}
          next = [...next, {
            ts: frame.receivedAt,
            sysid: Number(sysid),
            voltage: d.voltage,
            battery: d.battery,
            relativeAlt: d.relativeAlt,
            groundspeed: d.groundspeed,
            heading: d.heading,
          }]
        }
        if (next.length > HISTORY_PER_DRONE) next = next.slice(next.length - HISTORY_PER_DRONE)
        return { ...prev, [Number(sysid)]: next }
      })
    }
  }

  // WebSocket 实时遥测（指数退避重连 + 静默看门狗 + 帧合并刷新）
  useEffect(() => {
    let ws
    let closed = false
    let retryTimer = null
    let watchdogTimer = null
    let attempt = 0

    const armWatchdog = () => {
      clearTimeout(watchdogTimer)
      watchdogTimer = setTimeout(() => {
        // 静默超时：链路已死但没收到 FIN，主动断开触发重连（onclose 一定会来）
        setWsState('reconnecting')
        if (ws) { try { ws.close() } catch (e) { /* 已断 */ } }
      }, SILENCE_TIMEOUT_MS)
    }

    const connect = () => {
      ws = new WebSocket(getWsUrl())
      wsRef.current = ws
      ws.onopen = () => {
        setWsState('open')
        attempt = 0          // 连上了就重置退避
        armWatchdog()
      }
      ws.onclose = () => {
        setWsState('closed')
        clearTimeout(watchdogTimer)
        if (closed) return
        const delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** attempt)
        attempt = Math.min(attempt + 1, 10)
        retryTimer = setTimeout(connect, delay)
      }
      ws.onerror = () => setWsState('error')
      ws.onmessage = (ev) => {
        armWatchdog()   // 收到任何帧都算活着
        let msg
        try {
          msg = JSON.parse(ev.data)
        } catch (e) {
          return
        }
        if (msg.type === 'telemetry' || msg.type === 'status') {
          // 缓存而非逐帧 setState（见 FLUSH_INTERVAL_MS）
          const sysid = msg.sysid
          const bucket = telemetryBufferRef.current[sysid] || (telemetryBufferRef.current[sysid] = [])
          const now = Date.now()
          bucket.push({ data: msg.data || {}, receivedAt: now })
          while (bucket.length > 0 && now - bucket[0].receivedAt > BUFFER_MAX_AGE_MS) {
            bucket.shift()
          }
          if (flushTimerRef.current == null) {
            flushTimerRef.current = setTimeout(flushTelemetry, FLUSH_INTERVAL_MS)
          }
          return
        }
        if (msg.type === 'alert') {
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
        } else if (msg.type === 'task-assignment') {
          // M10 任务分配（TASK_ASSIGNMENT 30048，TaskAssignmentService 发布）
          setTaskAssignments((prev) => [{ ...(msg.data || {}), frameSysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'conflict-alert') {
          // M10 冲突告警（CONFLICT_ALERT 30049，ConflictScanService 5s 周期两两扫描）
          setConflictAlerts((prev) => [{ ...(msg.data || {}), frameSysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'task-status') {
          // M10 任务状态（TASK_STATUS 30050，ASSIGNED→IN_PROGRESS→COMPLETED/ABORTED）
          setTaskStatuses((prev) => [{ ...(msg.data || {}), frameSysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'alarm-trigger') {
          // 4a 报警触发（ALARM_TRIGGER 30057，AlarmLinkageEngine 落库后发布）
          setAlarmTriggers((prev) => [{ ...(msg.data || {}), frameSysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'alarm-ack') {
          // 4a 报警确认（ALARM_ACK 30058，AUTO_DISPATCH 派遣成功后逐机发布）
          setAlarmAcks((prev) => [{ ...(msg.data || {}), frameSysid: msg.sysid, receivedAt: Date.now() }, ...prev.slice(0, 49)])
        } else if (msg.type === 'surveillance-status') {
          // 4a 安防设备状态（SURVEILLANCE_STATUS 30059，SurveillanceStatusPusher 1Hz 周期）：
          // 周期状态可覆盖，按 deviceId 只留最新。注意分桶键取 data.deviceId 而非帧级
          // sysid —— 这是设备源帧，帧级 sysid 无意义（用 0 分桶会把所有设备塞进同一桶）。
          const deviceId = msg.data?.deviceId
          setSurveillanceStatuses((prev) => (
            deviceId == null
              ? prev
              : { ...prev, [deviceId]: { ...(msg.data || {}), receivedAt: Date.now() } }
          ))
        }
      }
    }
    connect()
    return () => {
      closed = true
      clearTimeout(retryTimer)
      clearTimeout(watchdogTimer)
      clearTimeout(flushTimerRef.current)
      flushTimerRef.current = null
      telemetryBufferRef.current = {}
      flushTelemetry()
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
    taskAssignments,
    conflictAlerts,
    taskStatuses,
    alarmTriggers,
    alarmAcks,
    surveillanceStatuses,
  }
}