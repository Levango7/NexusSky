import { useState, useRef, useEffect } from 'react'
import { getWsUrl } from '../api.js'

/**
 * WebSocket 实时数据管理 hook
 * 管理 wsState 及所有 WebSocket 推送数据：
 * alerts, formations, meshTopology, satLinkData, terrainData, cellTowerData,
 * telemetryHistory, multiTracks
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
  const [telemetryHistory, setTelemetryHistory] = useState([])
  const [multiTracks, setMultiTracks] = useState({})

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
  }
}