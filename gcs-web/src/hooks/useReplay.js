import { useState } from 'react'

/**
 * 回放与追踪管理 hook
 * 管理 replayTrack、replayProgress、trackingOverlay、trackColorMode 状态
 */
export default function useReplay() {
  const [replayTrack, setReplayTrack] = useState(null)      // 2D回放轨迹数据
  const [replayProgress, setReplayProgress] = useState(0)   // 回放进度 0-1
  const [trackingOverlay, setTrackingOverlay] = useState(null) // 追踪面板叠加数据
  const [trackColorMode, setTrackColorMode] = useState('single') // 轨迹着色模式

  return {
    replayTrack,
    setReplayTrack,
    replayProgress,
    setReplayProgress,
    trackingOverlay,
    setTrackingOverlay,
    trackColorMode,
    setTrackColorMode,
  }
}