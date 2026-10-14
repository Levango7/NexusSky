import { useState, useRef, useCallback, useEffect } from 'react'
import { api } from '../api.js'
import { mergeTrack, MAX_TRACK_POINTS } from '../utils/trackMerge.js'

/**
 * 无人机数据管理 hook
 * 管理 drones、selectedSysid、telemetry、track、apiOk 状态
 * 包含指数退避轮询和遥测加载逻辑
 *
 * 经验来源：2026-09-19-settimeout-recursive-send-chain-unmount-cleanup（递归 setTimeout + cleanup）
 * 经验来源：2026-09-13-yjs-multi-provider-destroy-order（effect 依赖与 ref 解耦模式）
 */
export default function useDrones() {
  const [drones, setDrones] = useState([])
  const [selectedSysid, setSelectedSysid] = useState(null)
  const [telemetry, setTelemetry] = useState(null)
  const [track, setTrack] = useState([])
  const [apiOk, setApiOk] = useState(false)

  // 用 ref 跟踪 selectedSysid，使 WebSocket onmessage 能读取最新值而无需重连
  const selectedSysidRef = useRef(selectedSysid)
  selectedSysidRef.current = selectedSysid
  // 用 ref 跟踪 apiOk，使指数退避 effect 能读取最新值
  const apiOkRef = useRef(false)
  apiOkRef.current = apiOk

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

    // 再叠一层**库内当日历史**。为什么必须叠：`/drones/{sysid}/track` 吃的是后端
    // FlightTrackStore 的有界内存窗口，后端重启后那里只剩 loadLastKnownPositions()
    // 塞回的 1 个最后已知点，地图上的历史折线就没了；而 `/flightlog/track?day=`
    // 从 flight_log 出当天整条轨迹，不受重启影响（该端点早已存在，前端一直没用）。
    // 单开一个 try：历史不可用只该少一段历史，不该把刚拿到的实时轨迹抹成空。
    try {
      const stored = await api.getDayTrack(sysid)
      setTrack((prev) => mergeTrack(prev, stored))
    } catch (e) {
      // flight_log 未开库 / 查询失败：保留实时轨迹，本函数不额外报错
    }
  }, [])

  /**
   * 追加一个实时航迹点（由 useWebSocket 的帧合并刷新调用）。
   * <p>
   * 为什么需要它：主地图的 track 此前只在"切换选中机"时从后端 GET 一次，飞行中
   * 再没有数据源——实时飞行的折线是**静止的**，与产品预期"实时轨迹增长"不符。
   * WS 面本来就在收这些帧（multiTracks 一直在长），只是没写回 track。
   *
   * <p>去重：同一时间戳的点直接忽略（flush 重入 / 后端重发保护）。
   */
  const appendTrackPoint = useCallback((point) => {
    if (!point || point.lat == null || point.lon == null) return
    setTrack((prev) => {
      if (prev.length > 0 && prev[prev.length - 1].ts === point.ts) return prev
      const next = [...prev, point]
      // 与后端 track 的窗口一致：只保留最近 MAX_TRACK_POINTS 个点
      return next.length > MAX_TRACK_POINTS ? next.slice(next.length - MAX_TRACK_POINTS) : next
    })
  }, [])

  // refreshDrones 轮询：正常 2s，API 离线时指数退避（2→4→8→16→30s）
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

  return {
    drones,
    selectedSysid,
    setSelectedSysid,
    telemetry,
    setTelemetry,
    track,
    setTrack,
    apiOk,
    selectedSysidRef,
    refreshDrones,
    loadTelemetry,
    appendTrackPoint,
  }
}