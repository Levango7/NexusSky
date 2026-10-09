/**
 * 视图导航分组（顶栏导航单一来源）。
 *
 * 45 个视图平铺在顶栏会排成 3 行、占 118px——按业务域收成 8 组，
 * 顶栏恢复一行（~56px），组内视图进下拉菜单。
 *
 * 约定：
 *  - VIEW_LABELS 是每个视图的唯一显示名（导航/下拉/title 全部取自这里）；
 *  - 组顺序 = 顶栏按钮顺序；组内 views 顺序 = 下拉菜单顺序；
 *  - adminOnly 的组只对 ADMIN 显示（与 App.jsx 原有的 isAdmin 行为一致）；
 *  - 预算档位过滤不在这里做：NavGroups 用 VIEW_PANEL_MAP + isPanelAvailable 过滤，
 *    本文件只描述"有哪些视图、叫什么名字、归在哪组"。
 */

export const VIEW_LABELS = {
  dashboard: '仪表盘',
  scene3d: '3D 视图',
  control: '操控',
  formation: '编队',
  spray: '喷洒',
  hardware: '硬件',
  mesh: 'Mesh',
  celltower: '基站',
  satlink: '星地中继',
  terrain: '地形',
  emergency: '应急编排',
  surveillance: '安防监控',
  alarm: '报警联动',
  tracking: '追踪',
  geofence: '围栏',
  rid: 'RID',
  cveval: 'CV评测',
  defect: '缺陷工单',
  routetpl: '航线模板',
  opsreport: '运营报表',
  roc: 'ROC席位',
  sensing: '侦测态势',
  dock: '机巢',
  dronelock: '锁机',
  autodispatch: '自动出警',
  aidecision: 'AI 决策',
  fleetops: '机队协同',
  scenariolib: '场景库',
  inspection: '智能巡检',
  health: '健康管理',
  commadapt: '通信自适应',
  mapping: '航拍测绘',
  voicecmd: '语音指挥',
  citytwin: '数字孪生',
  delivery: '物流配送',
  show: '编队表演',
  disastercomm: '灾害通信',
  unifiedcmd: '空地指挥',
  videofusion: '视频融合',
  thermal: '热成像',
  weather: '气象',
  linkquality: '链路质量',
  trajectory3d: '3D轨迹',
  tenants: '租户管理',
  users: '用户管理',
}

export const VIEW_GROUPS = [
  { key: 'overview', label: '总览', views: ['dashboard'] },
  { key: 'flight', label: '飞行', views: ['control', 'scene3d', 'trajectory3d', 'formation', 'spray', 'hardware'] },
  { key: 'mission', label: '任务', views: ['routetpl', 'mapping', 'terrain', 'tracking', 'geofence', 'inspection', 'weather', 'defect'] },
  { key: 'emergency', label: '应急', views: ['emergency', 'roc', 'sensing', 'alarm', 'autodispatch', 'scenariolib', 'voicecmd', 'unifiedcmd', 'citytwin', 'disastercomm'] },
  { key: 'comms', label: '通信', views: ['mesh', 'celltower', 'satlink', 'commadapt', 'linkquality', 'videofusion', 'thermal'] },
  { key: 'ops', label: '运营', views: ['surveillance', 'dronelock', 'dock', 'fleetops', 'aidecision', 'delivery', 'show', 'opsreport', 'health'] },
  { key: 'compliance', label: '合规', views: ['rid', 'cveval'] },
  { key: 'admin', label: '管理', views: ['tenants', 'users'], adminOnly: true },
]
