import { useState, useEffect } from 'react'
import { isPanelAvailable } from '../api.js'

// 视图标签 -> 预算面板名称映射（用于丐版模式下隐藏不可用面板）
// 经验来源：2026-09-17-react-mount-existing-components-export-signature-dialog-wrap
export const VIEW_PANEL_MAP = {
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
  surveillance: 'surveillance', // 安防监控（千元级以上可用，独立面板）
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
  disastercomm: 'mesh',    // 灾害通信归入通信范畴（千元级可用）
  unifiedcmd: 'unifiedcmd',  // 空地指挥独立面板（千元级可用）
  videofusion: 'videofusion', // 视频融合独立面板（千元级可用）
  tenants: 'status',       // 租户管理归入状态管理（百元级可用）
  users: 'status',         // 用户管理归入状态管理（百元级可用）
}

/**
 * UI 状态管理 hook
 * 管理 view、now（时钟）、mobileRail、budgetMode 状态
 * 包含时钟 effect 和丐版模式切换后自动回退视图的 effect
 */
export default function useUI() {
  const [now, setNow] = useState(Date.now())
  const [view, setView] = useState('control')
  const [mobileRail, setMobileRail] = useState(null) // null | 'left' | 'right'
  const [budgetMode, setBudgetMode] = useState(null) // null=完整版 | 'toy' | 'standard' | 'advanced' | 'emergency-toy' | 'emergency-standard'

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

  return {
    view,
    setView,
    now,
    mobileRail,
    setMobileRail,
    budgetMode,
    setBudgetMode,
  }
}