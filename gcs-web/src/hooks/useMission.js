import { useState } from 'react'

/**
 * 任务规划管理 hook
 * 管理 missionDraft（航点草稿）和 orbitOverlay（轨道环覆盖层）状态
 */
export default function useMission() {
  const [missionDraft, setMissionDraft] = useState([])
  const [orbitOverlay, setOrbitOverlay] = useState(null) // {center, radiusM} for the map ring

  return {
    missionDraft,
    setMissionDraft,
    orbitOverlay,
    setOrbitOverlay,
  }
}