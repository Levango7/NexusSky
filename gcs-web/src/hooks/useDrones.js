import { useState, useRef, useCallback, useEffect } from 'react'
import { api } from '../api.js'

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
  }
}