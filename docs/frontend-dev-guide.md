# NexusSky 前端开发指南

## 1. 项目概览

前端地面站（`gcs-web`）基于 React 18 + MapLibre GL + Three.js 构建，提供无人机实时操控、任务规划、3D 可视化等 39 个功能面板。

**技术栈**：

| 依赖 | 版本 | 用途 |
|---|---|---|
| React | 18.3.1 | UI 框架 |
| MapLibre GL | 4.7.0 | 2D 地图渲染 |
| Three.js | r128（CDN） | 3D 场景渲染 |
| Vite | 5.4.11 | 构建工具 + 开发服务器 |

**构建命令**：

```cmd
cd gcs-web
npm install        :: 安装依赖
npm run dev        :: 开发模式（端口 5173）
npm run build      :: 生产构建
npm run preview    :: 预览生产构建
```

## 2. 目录结构

```
gcs-web/
├── src/
│   ├── api.js              :: API 调用封装 + Token 管理
│   ├── App.jsx             :: 主应用组件（视图路由 + 布局）
│   ├── main.jsx            :: React 入口
│   ├── styles.css          :: 全局样式
│   ├── hooks/
│   │   ├── useAuth.js      :: 认证状态管理
│   │   ├── useDrones.js    :: 无人机数据 + 指数退避轮询
│   │   ├── useWebSocket.js :: WebSocket 实时推送
│   │   ├── useUI.js        :: UI 状态（视图、时钟、丐版模式）
│   │   ├── useMission.js   :: 任务规划状态
│   │   └── useReplay.js    :: 轨迹回放与追踪
│   ├── components/
│   │   ├── MapView.jsx         :: 2D 地图视图
│   │   ├── Scene3D.jsx         :: 3D 场景主组件
│   │   ├── Scene3DUtils.js     :: 3D 常量与辅助函数
│   │   ├── Trajectory3D.jsx    :: 3D 轨迹视图
│   │   ├── DroneList.jsx       :: 无人机列表
│   │   ├── TelemetryPanel.jsx  :: 遥测面板
│   │   ├── MissionPlanner.jsx  :: 任务规划
│   │   ├── Joystick.jsx        :: 虚拟摇杆
│   │   ├── AlertFeed.jsx       :: 告警流
│   │   ├── BudgetBadge.jsx     :: 丐版模式徽章
│   │   ├── LoginPanel.jsx      :: 登录面板
│   │   ├── DashboardPanel.jsx  :: 仪表盘
│   │   ├── ...Panel.jsx        :: 其他功能面板
│   ├── utils/
│   │   └── panelUtils.js  :: 面板共享工具函数和样式
│   └── scripts/
│       └── check-frontend.cjs :: 前端语法检查（沙箱环境）
├── package.json
├── dist/                   :: 生产构建输出
└── node_modules/
```

## 3. 组件创建路径与命名约定

### 3.1 命名规则

| 类型 | 命名规则 | 文件位置 | 示例 |
|---|---|---|---|
| 功能面板 | `<Name>Panel.jsx` | `src/components/` | `SprayPanel.jsx`、`FormationPanel.jsx` |
| 通用组件 | `<Name>.jsx` | `src/components/` | `BudgetBadge.jsx`、`Joystick.jsx` |
| Hook | `use<Name>.js` | `src/hooks/` | `useDrones.js`、`useWebSocket.js` |
| 工具函数 | `<name>Utils.js` | `src/utils/` | `panelUtils.js`、`Scene3DUtils.js` |
| API 调用 | 统一在 `api.js` | `src/` | `api.js` |

### 3.2 新增面板步骤

1. 创建组件文件 `src/components/<Name>Panel.jsx`
2. 在 `src/api.js` 中添加对应的 API 调用方法
3. 在 `src/hooks/useUI.js` 的 `VIEW_PANEL_MAP` 中注册面板映射
4. 在 `src/App.jsx` 中导入组件并添加视图渲染分支
5. 在 `src/App.jsx` 的视图标签数组中添加 tab 入口

**示例**：新增"巡检面板"

```jsx
// 1. 创建 src/components/InspectionPanel.jsx
export default function InspectionPanel() {
  // ...
}

// 2. 在 api.js 中添加 API 调用
api.getInspectionTasks = () => jsonFetch(`${BASE}/inspection/tasks`)

// 3. 在 useUI.js 的 VIEW_PANEL_MAP 中注册
inspection: 'mission',  // 归入任务范畴（千元级可用）

// 4. 在 App.jsx 中导入和渲染
import InspectionPanel from './components/InspectionPanel.jsx'
// 在视图标签数组中添加：
{ key: 'inspection', label: '智能巡检' }
// 在视图渲染分支中添加：
view === 'inspection' ? <InspectionPanel /> : ...
```

## 4. API 调用模式

### 4.1 api.js 核心架构

所有 API 调用通过 `gcs-web/src/api.js` 的 `api` 对象统一管理。

**Token 管理**：

| 函数 | 功能 |
|---|---|
| `setAuthToken(token)` | 存储 JWT 到 sessionStorage（验证格式后） |
| `getAuthToken()` | 获取当前 token |
| `setCurrentUser(user)` | 存储当前用户信息 |
| `getCurrentUser()` | 获取当前用户信息 |
| `isAuthenticated()` | 是否已认证 |
| `clearAuthToken()` | 清除 token 和用户信息 |
| `logout()` | 登出（等同 clearAuthToken） |

**请求函数 `jsonFetch`**：

```javascript
async function jsonFetch(url, options = {})
```

核心特性：
- 自动添加 `Authorization: Bearer <token>` Header
- `AbortController` + `setTimeout` 实现请求超时（默认 15 秒）
- 401 响应自动清除 token 并跳转登录页（避免在登录页自身触发循环跳转）
- 错误响应抛出 `Error`，消息取自 `body.error` 或 `HTTP <status>`

### 4.2 API 调用示例

```javascript
import { api } from '../api.js'

// GET 请求
const drones = await api.listDrones()
const telemetry = await api.getTelemetry(sysid)

// POST 请求
await api.uploadMission(sysid, items)
await api.sendCommand(sysid, 'ARM')
await api.createSprayTask(payload)

// PUT 请求
await api.setSatLinkStrategy(strategy)

// DELETE 请求
await api.deleteGeofenceZone(id)
```

### 4.3 新增 API 调用

在 `api.js` 的 `api` 对象中添加新方法：

```javascript
export const api = {
  // ... 现有方法

  // 新增：获取巡检任务列表
  getInspectionTasks: () => jsonFetch(`${BASE}/inspection/tasks`),

  // 新增：创建巡检任务（POST + JSON body）
  createInspectionTask: (payload) =>
    jsonFetch(`${BASE}/inspection/tasks`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
}
```

### 4.4 独立路径的 API

部分 API 使用独立路径（不在 `/api/v1` 前缀下），需要写完整路径：

```javascript
// Tracking API（路径 /api/v1/tracking/...）
api.getFlightTrack: (sysid, limit) => jsonFetch(`/api/v1/tracking/${sysid}/track${qs}`)

// Geofence API（路径 /api/v1/geofence/...）
api.createGeofenceZone: (zone) => jsonFetch('/api/v1/geofence/zones', { ... })

// DroneLock API（路径 /api/v1/drone-lock/...）
api.lockDrone: (sysid, payload) => jsonFetch(`/api/v1/drone-lock/${sysid}/lock`, { ... })

// AutoDispatch API（路径 /api/v1/autodispatch/...）
api.triggerAutoDispatch: (payload) => jsonFetch('/api/v1/autodispatch/trigger', { ... })
```

## 5. WebSocket 数据流说明

### 5.1 WebSocket 连接

WebSocket 连接在 `gcs-web/src/hooks/useWebSocket.js` 中管理：

- 连接端点：`/ws/telemetry`（通过 `getWsUrl()` 获取）
- 断线 3 秒自动重连
- 连接只建立一次，`selectedSysid` 变化通过 ref 读取，不触发重连
- 连接状态在顶部导航栏显示（`LIVE` / `RECONNECTING`）

### 5.2 消息类型与处理

`useWebSocket.js` 的 `onmessage` 处理以下消息类型：

| 消息类型 | 处理逻辑 | 数据更新 |
|---|---|---|
| `telemetry` / `status` | 匹配 `selectedSysid` 时更新遥测；累积多机轨迹（每架保留最近 30 个点）；追加遥测历史（保留最近 120 个点） | `setTelemetry`、`setMultiTracks`、`setTelemetryHistory` |
| `alert` | 添加到告警列表头部（保留最近 50 条） | `setAlerts` |
| `formation` | 单编队更新或全量替换编队数组 | `setFormations` |
| `mesh-topology` | mesh 拓扑变化推送（2Hz） | `setMeshTopology` |
| `sat-link` | 星-空-地中继数据推送（2Hz） | `setSatLinkData` |
| `terrain-update` / `terrain-restriction` | 地形变更/限制区推送（2Hz） | `setTerrainData` |
| `celltower-topology` | 基站拓扑变化推送（2Hz） | `setCellTowerData` |

### 5.3 WebSocket 数据消费

WebSocket 数据在 `App.jsx` 中通过 props 分发给各面板：

```jsx
const {
  wsState, alerts, formations, meshTopology,
  satLinkData, terrainData, cellTowerData,
  telemetryHistory, multiTracks,
} = useWebSocket(selectedSysidRef, setTelemetry)

// 分发到面板
<FormationPanel formations={formations} drones={drones} />
<MeshTopologyPanel meshTopology={meshTopology} />
<SatLinkPanel satLinkData={satLinkData} />
<TerrainMapPanel terrainData={terrainData} />
<CellTowerPanel cellTowerData={cellTowerData} />
<AlertFeed alerts={alerts} />
```

### 5.4 关键设计约束

WebSocket 只连接一次（`useEffect` 依赖数组为空 `[]`），`selectedSysid` 变化通过 `selectedSysidRef` 读取，避免重连。这一模式参考了 Yjs multi-provider destroy order 经验（effect 依赖与 ref 解耦）。

## 6. 面板过滤机制（budgetMode）

### 6.1 丐版模式概述

budgetMode 是一种面板可见性过滤机制，模拟不同预算档位下的功能裁剪。通过顶部导航栏的下拉框切换：

| budgetMode | 标签 | 可见面板类别 |
|---|---|---|
| `null`（默认） | 完整版 | 所有面板 |
| `toy` | 丐版·百元级 | 仅 `status` 类面板 |
| `standard` | 丐版·千元级 | `status` + `mission` + `formation` + `mesh` + `emergency` + `surveillance` + `unifiedcmd` + `videofusion` |
| `advanced` | 丐版·进阶 | 全部面板 |
| `emergency-toy` | 应急·百元级 | 仅 `status` 类面板 |
| `emergency-standard` | 应急·千元级 | 同 standard |

### 6.2 VIEW_PANEL_MAP 映射

`gcs-web/src/hooks/useUI.js` 中定义了视图标签到预算面板名称的映射：

```javascript
export const VIEW_PANEL_MAP = {
  dashboard: 'status',
  scene3d: 'mission',
  control: 'telemetry',
  formation: 'formation',
  spray: 'mission',
  hardware: 'opticalflow',
  mesh: 'mesh',
  celltower: 'mesh',
  satlink: 'mesh',
  terrain: 'mission',
  emergency: 'emergency',
  surveillance: 'surveillance',
  alarm: 'emergency',
  tracking: 'mission',
  geofence: 'mission',
  dronelock: 'status',
  autodispatch: 'emergency',
  scenariolib: 'emergency',
  inspection: 'mission',
  health: 'status',
  commadapt: 'mesh',
  mapping: 'mission',
  voicecmd: 'emergency',
  citytwin: 'emergency',
  delivery: 'mission',
  show: 'formation',
  disastercomm: 'mesh',
  unifiedcmd: 'unifiedcmd',
  videofusion: 'videofusion',
  tenants: 'status',
  users: 'status',
}
```

### 6.3 过滤逻辑

1. 视图标签数组通过 `isPanelAvailable(VIEW_PANEL_MAP[tab.key], budgetMode)` 过滤
2. 丐版模式切换后，若当前视图在该档位下不可用，自动回退到 `control`（主操控视图）
3. `BudgetBadge` 组件在顶部导航栏显示当前丐版档位标识

### 6.4 BudgetBadge 组件

`gcs-web/src/components/BudgetBadge.jsx` 在 `mode` 不为 null 时渲染徽章：

| mode | 显示文本 | 颜色 |
|---|---|---|
| `toy` | 丐版·百元级 | #f50（红色） |
| `standard` | 丐版·千元级 | #fa8c16（橙色） |
| `advanced` | 丐版·进阶 | #52c41a（绿色） |
| `emergency-toy` | 应急·百元级 | #cf1322（深红） |
| `emergency-standard` | 应急·千元级 | #d4380d（暗红） |

## 7. 状态管理模式

### 7.1 Hooks 架构

项目采用自定义 Hooks 管理状态，不使用 Redux 或 Context API。所有状态通过 props 在 `App.jsx` 中组合和分发。

| Hook | 职责 | 返回值 |
|---|---|---|
| `useAuth` | 认证状态管理 | `authed`, `setAuthed`, `handleLoginSuccess`, `handleLogout` |
| `useDrones` | 无人机数据 + 指数退避轮询 | `drones`, `selectedSysid`, `setSelectedSysid`, `telemetry`, `setTelemetry`, `track`, `apiOk`, `selectedSysidRef`, `refreshDrones`, `loadTelemetry` |
| `useWebSocket` | WebSocket 实时推送数据 | `wsState`, `alerts`, `formations`, `meshTopology`, `satLinkData`, `terrainData`, `cellTowerData`, `telemetryHistory`, `multiTracks` |
| `useUI` | UI 状态（视图、时钟、丐版模式） | `view`, `setView`, `now`, `mobileRail`, `setMobileRail`, `budgetMode`, `setBudgetMode` |
| `useMission` | 任务规划状态 | `missionDraft`, `setMissionDraft`, `orbitOverlay`, `setOrbitOverlay` |
| `useReplay` | 轨迹回放与追踪 | `replayTrack`, `replayProgress`, `trackingOverlay`, `setTrackingOverlay`, `trackColorMode` |

### 7.2 useDrones 轮询策略

`useDrones` 使用指数退避轮询获取无人机列表：

- 正常状态：每 2 秒轮询一次
- API 离线时：指数退避（2→4→8→16→30 秒）
- API 恢复后：立即回到 2 秒间隔
- 通过 `apiOkRef`（ref）读取最新状态，避免闭包捕获旧值

### 7.3 selectedSysid 的 ref 模式

`useDrones` 中使用 `selectedSysidRef` 跟踪当前选中的无人机 sysid：

```javascript
const selectedSysidRef = useRef(selectedSysid)
selectedSysidRef.current = selectedSysid
```

这使得 `useWebSocket` 的 `onmessage` 回调能读取最新的 `selectedSysid` 而无需重新建立 WebSocket 连接。这一模式参考了 Yjs multi-provider destroy order 经验（effect 依赖与 ref 解耦）。

### 7.4 飞行统计

`App.jsx` 中使用 `useMemo` 从轨迹数据实时计算飞行统计：

```javascript
const stats = useMemo(() => {
  if (!track || track.length < 2) return { dist: 0, dur: 0, maxAlt: 0 }
  let d = 0
  for (let i = 1; i < track.length; i++) d += haversine(track[i - 1], track[i])
  const dur = (track[track.length - 1].ts - track[0].ts) / 1000
  const maxAlt = Math.max(...track.map((p) => p.alt || 0))
  return { dist: d / 1000, dur, maxAlt }
}, [track])
```

## 8. 3D 场景开发说明

### 8.1 Three.js 集成方式

Three.js 通过 CDN 动态加载，不修改 `package.json`（避免 BOM 字节序标记问题，经验来源：2026-09-16-package-json-bom-breaks-vite-build）。

**CDN 地址**：`https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js`

**加载机制**（`Scene3DUtils.js` 的 `loadThree()`）：

```javascript
export function loadThree() {
  return new Promise((resolve, reject) => {
    if (window.THREE) return resolve(window.THREE)
    if (window.__threeLoading) {
      window.__threeLoading.then(resolve, reject)
      return
    }
    window.__threeLoading = new Promise((res, rej) => {
      const s = document.createElement('script')
      s.src = THREE_CDN
      s.async = true
      s.onload = () => res(window.THREE)
      s.onerror = () => rej(new Error('Three.js CDN 加载失败'))
      document.head.appendChild(s)
    })
    window.__threeLoading.then(resolve, reject)
  })
}
```

- 首次调用时动态注入 `<script>` 标签
- 并发调用时复用同一个 Promise（`window.__threeLoading`），防止重复注入
- 加载成功后缓存到 `window.THREE`

### 8.2 Scene3D 组件架构

`Scene3D`（`gcs-web/src/components/Scene3D.jsx`）是 3D 可视化主组件：

**Props**：

| Prop | 类型 | 说明 |
|---|---|---|
| `drones` | Array | 无人机列表 |
| `telemetry` | Object | 当前选中无人机遥测 |
| `track` | Array | 历史轨迹点 |
| `formations` | Array | 编队列表 |
| `selected` | Object | 当前选中无人机对象 |
| `terrainData` | Object | 地形数据 |

**状态**：`loading` → `ready` / `error`

**useEffect 分层**：

| useEffect | 依赖 | 职责 |
|---|---|---|
| 初始化场景 | `[]`（仅一次） | 创建 scene/camera/renderer、灯光、地形、相机控制、渲染循环 |
| 更新无人机模型 | `[drones, telemetry, selected, status]` | 根据 drones + telemetry 更新无人机 3D 模型位置和姿态 |
| 更新轨迹线 | `[track, status]` | 绘制历史轨迹（渐变色）和预测轨迹（虚线） |
| 更新编队展示 | `[formations, status]` | 绘制编队队形连线和成员位置球体 |

### 8.3 坐标系转换

`geoTo3D()` 函数将经纬高转换为 3D 坐标：

```javascript
export const REF = { lat: 22.5907, lon: 113.9345 }  // 参考点（无人机 home）
export const SCALE = 0.05  // 1 米 = 0.05 个 Three.js 单位

export function geoTo3D(lat, lon, alt = 0) {
  const x = (lon - REF.lon) * M_PER_DEG_LON * SCALE  // 东为 +X
  const z = -(lat - REF.lat) * M_PER_DEG_LAT * SCALE // 南为 +Z
  const y = (alt || 0) * SCALE                        // 上为 +Y
  return { x, y, z }
}
```

参考点 `REF` 必须与 `MapView` 的地图中心保持一致。

### 8.4 相机控制

自实现的轨道相机控制器（不使用 OrbitControls 插件）：

| 操作 | 功能 |
|---|---|
| 左键拖动 | 旋转（theta 方位角、phi 极角） |
| 滚轮 | 缩放（radius 距离，范围 10-800） |
| 右键拖动 | 平移（target 目标点） |

### 8.5 资源清理

组件卸载时必须清理：

- `cancelAnimationFrame` 停止渲染循环
- 移除所有事件监听器（mousedown、mousemove、mouseup、wheel、contextmenu）
- `ResizeObserver.disconnect()` 断开响应式监听
- `renderer.dispose()` 释放 WebGL 资源
- 移除 `renderer.domElement` 从 DOM

无人机模型移除时释放 geometry 和 material：

```javascript
m.group.traverse((obj) => {
  if (obj.geometry) obj.geometry.dispose()
  if (obj.material) {
    if (Array.isArray(obj.material)) obj.material.forEach((mat) => mat.dispose())
    else obj.material.dispose()
  }
})
```

### 8.6 Scene3DUtils 共享模块

`gcs-web/src/components/Scene3DUtils.js` 提取了 `Scene3D` 和 `Trajectory3D` 共享的常量与函数：

| 导出 | 类型 | 说明 |
|---|---|---|
| `THREE_CDN` | 常量 | Three.js CDN 地址 |
| `REF` | 常量 | 参考点经纬度 |
| `SCALE` | 常量 | 场景缩放比例 |
| `M_PER_DEG_LAT` / `M_PER_DEG_LON` | 常量 | 米/度换算 |
| `COLOR` | 常量 | 颜色常量（cyan/ok/warn/crit/gold/dim） |
| `geoTo3D` | 函数 | 经纬高 → 3D 坐标 |
| `loadThree` | 函数 | 动态加载 Three.js |
| `createOrbitState` | 函数 | 创建相机轨道状态 |
| `applyOrbit` | 函数 | 应用轨道状态到相机 |
| `buildDroneModel` | 函数 | 构建无人机 3D 模型 |
| `buildTerrain` | 函数 | 构建地形网格 |

### 8.7 新增 3D 元素步骤

1. 在 `Scene3DUtils.js` 中添加构建函数（如 `buildBuilding(THREE, lat, lon, height)`）
2. 在 `Scene3D.jsx` 的初始化 useEffect 中调用，或创建新的 useEffect 分层
3. 使用 `geoTo3D()` 转换经纬度到 3D 坐标
4. 在 cleanup 中释放 geometry 和 material

## 9. 前端构建注意事项

### 9.1 沙箱环境限制

沙箱内 esbuild 的 stdio-pipe 策略不可用，完整 `vite build` 在无限制环境中执行。沙箱内使用 `gcs-web/scripts/check-frontend.cjs` 进行语法检查：

```cmd
node gcs-web\scripts\check-frontend.cjs
```

该脚本使用 `@babel/parser` 进行全量语法检查和 import 图校验。

### 9.2 package.json BOM 问题

不要手动编辑 `package.json`（可能引入 BOM 字节序标记导致 Vite 构建失败）。如需修改依赖，使用 `npm install <package>` 命令。经验来源：2026-09-16-package-json-bom-breaks-vite-build。

### 9.3 Vite 代理配置

Vite dev server 代理 `/api` 和 `/ws` 到后端 8080 端口。如果后端地址变更，需修改 `vite.config.js` 中的 proxy 配置。

## 10. 面板共享工具

`gcs-web/src/utils/panelUtils.js` 提供面板间共享的工具函数和样式：

| 导出 | 类型 | 说明 |
|---|---|---|
| `POLL_MS` | 常量 | 轮询间隔（5000ms） |
| `fmtTime(ts)` | 函数 | 时间格式化（兼容数字毫秒和 ISO 字符串） |
| `toArray(data, fallbackKey)` | 函数 | 规范化列表数据（兼容裸数组/对象包裹的数组） |
| `pick(obj, ...keys)` | 函数 | 字段兼容提取（按优先级取第一个非 null 值） |
| `METERS_PER_DEGREE_LAT` | 常量 | 纬度1度对应的米数 |
| `cardStyle` | 常量 | 共享卡片样式 |
| `labelStyle` | 常量 | 共享标签样式 |
| `miniBtnStyle` | 常量 | 共享迷你按钮样式 |
| `modalInputStyle` | 常量 | 共享模态框输入样式 |