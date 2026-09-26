# NexusSky API 快速参考

> 60 个 @RestController · 318 个端点 · 认证: JWT Bearer Token 或 X-API-Key

## 无人机控制

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/drones | 无人机列表 |
| GET | /api/v1/drones/{sysid} | 单无人机详情 |
| GET | /api/v1/drones/{sysid}/telemetry | 遥测数据快照 |
| GET | /api/v1/drones/{sysid}/track | 飞行轨迹 |
| GET | /api/v1/drones/{sysid}/mission | 下载当前任务 |
| POST | /api/v1/drones/{sysid}/joystick | 虚拟摇杆（需 OPERATOR） |
| POST | /api/v1/drones/{sysid}/mission | 上传航点任务（需 OPERATOR） |
| POST | /api/v1/drones/{sysid}/commands | 发送飞行命令（需 OPERATOR） |
| GET | /api/v1/flightlog | 查询飞行日志 |
| GET | /api/v1/flightlog/track | 查询指定无人机某日轨迹 |

## 飞行追踪

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/tracking/{sysid}/track | 获取飞行轨迹 |
| GET | /api/tracking/{sysid}/replay | 历史轨迹回放 |
| GET | /api/tracking/{sysid}/last-known | 最后已知位置 |
| GET | /api/tracking/lost | 失联无人机列表 |
| GET | /api/tracking/{sysid}/search-guide | 辅助查找信息 |
| GET | /api/tracking/scan | 触发失联检测扫描 |

## 电子围栏

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/geofence/zones | 创建围栏区域 |
| GET | /api/geofence/zones | 列出所有围栏区域 |
| GET | /api/geofence/zones/{id} | 获取单个围栏区域 |
| PUT | /api/geofence/zones/{id} | 更新围栏区域 |
| DELETE | /api/geofence/zones/{id} | 删除围栏区域 |
| GET | /api/geofence/breaches | 获取越界历史 |
| POST | /api/geofence/check | 手动触发全量围栏检查 |

## 远程锁机

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/drone-lock/{sysid}/lock | 锁定无人机 |
| POST | /api/drone-lock/{sysid}/unlock | 解锁无人机 |
| GET | /api/drone-lock/{sysid} | 获取锁定状态 |
| GET | /api/drone-lock/locked | 获取所有已锁定无人机 |
| GET | /api/drone-lock/all | 获取所有无人机锁定状态 |
| DELETE | /api/drone-lock/{sysid} | 清除锁定状态记录 |

## 安防监控

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/surveillance/devices | 注册安防设备 |
| GET | /api/v1/surveillance/devices | 列出所有安防设备 |
| GET | /api/v1/surveillance/devices/{id} | 获取设备详情 |
| DELETE | /api/v1/surveillance/devices/{id} | 注销设备 |
| GET | /api/v1/surveillance/devices/{id}/stream | 获取 RTSP 流 URL |
| POST | /api/v1/surveillance/devices/{id}/ptz | PTZ 控制 |
| POST | /api/v1/surveillance/discover | 发现子网内设备 |
| POST | /api/v1/surveillance/rapid-deploy | 一键扫描并自动注册 |
| POST | /api/v1/surveillance/scan | 仅扫描子网发现设备 |
| GET | /api/v1: surveillance/devices/{id}/events | 设备事件 SSE |
| GET | /api/v1/surveillance/events | 查询全局安防事件列表 |

## 报警联动

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/alarms/events | 接收报警事件 |
| GET | /api/alarms/events | 查询报警事件列表 |
| GET | /api/alarms/events/{id} | 获取报警事件详情 |
| POST | /api/alarms/events/{id}/ack | 确认报警（需 OPERATOR） |
| POST | /api/alarms/events/ack-batch | 批量确认报警（需 OPERATOR） |
| POST | /api/alarms/events/{id}/respond | 一键应急响应（需 OPERATOR） |
| GET | /api/alarms/stream | 报警事件 SSE 实时推送 |
| GET | /api/alarms/linkage-logs | 查询联动执行日志 |
| GET | /api/alarms/rules | 列出联动规则 |
| POST | /api/alarms/rules | 创建联动规则（需 OPERATOR） |
| PUT | /api/alarms/rules/{id} | 更新联动规则（需 OPERATOR） |
| DELETE | /api/alarms/rules/{id} | 删除联动规则（需 OPERATOR） |
| POST | /api/alarms/rules/{id}/test | 测试联动规则（需 OPERATOR） |

## 应急指挥

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/emergency-command | 创建指挥命令（需 OPERATOR） |
| GET | /api/emergency-command | 列出指挥命令 |
| GET | /api/emergency-command/{id} | 获取命令详情 |
| POST | /api/emergency-command/{id}/assess | 研判（需 OPERATOR） |
| POST | /api/emergency-command/{id}/deploy | 部署（需 OPERATOR） |
| POST | /api/emergency-command/{id}/execute | 开始执行（需 OPERATOR） |
| POST | /api/emergency-command/{id}/evaluate | 评估（需 OPERATOR） |
| POST | /api/emergency-command/{id}/close | 总结关闭（需 OPERATOR） |
| POST | /api/emergency-command/{id}/one-click | 一键应急响应（需 OPERATOR） |
| GET | /api/emergency-command/{id}/history | 获取阶段转移历史 |
| POST | /api/v1/emergency/orch/start | 启动编排计划（需 OPERATOR） |
| POST | /api/v1/emergency/orch/{planId}/abort | 中止编排计划（需 OPERATOR） |
| GET | /api/v1/emergency/orch/{planId} | 查询计划状态 |
| GET | /api/v1/emergency/orch/{planId}/progress | 查询阶段进度 |
| GET | /api/v1/emergency/orch/{planId}/coverage | 查询覆盖信息 |
| POST | /api/v1/emergency/orch/{planId}/replan | 重规划（需 OPERATOR） |
| POST | /api/v1/emergency/orch/{planId}/priority | 调整任务优先级（需 OPERATOR） |
| GET | /api/v1/emergency/orch/{planId}/priority/queue | 查询优先级队列 |
| GET | /api/v1/emergency/scenarios | 查询场景预设列表 |
| POST | /api/v1/emergency/scenarios/{type}/start | 加载预设并启动（需 OPERATOR） |

## 编队表演

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/formation | 创建编队 |
| GET | /api/v1/formation/{id} | 查询编队状态 |
| POST | /api/v1/formation/{id}/command | 下发编队命令 |
| POST | /api/v1/formation/{id}/transition | 队形变换 |
| POST | /api/v1/formation/{id}/lights | 灯光控制 |
| GET | /api/v1/formation/{id}/lights | 查询灯光状态 |
| DELETE | /api/v1/formation/{id}/members/{sysid} | 单机脱离 |
| POST | /api/v1/formation/{id}/dissolve | 解散编队 |

## 喷洒物流

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/spray | 创建喷洒任务（需 OPERATOR） |
| GET | /api/v1/spray/{id} | 查询喷洒任务状态 |
| POST | /api/v1/spray/{id}/control | 控制喷洒任务（需 OPERATOR） |
| POST | /api/v1/delivery | 创建配送任务（需 OPERATOR） |
| GET | /api/v1/delivery/{id} | 查询配送任务状态 |
| POST | /api/v1/delivery/{id}/control | 控制配送任务（需 OPERATOR） |
| GET | /api/v1/delivery/{id}/payload | 查询当前负载清单 |

## 视觉感知

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/vision/drones/{sysid}/capture | 拍照并地理定位（需 OPERATOR） |
| POST | /api/v1/vision/drones/{sysid}/orbit | 启动环绕飞行（需 OPERATOR） |
| GET | /api/v1/vision/jobs/{jobId} | 查询环绕任务进度 |
| GET | /api/v1/vision/drones/{sysid}/tracks | 查询跟踪目标列表 |
| POST | /api/v1/thermal/tasks | 创建热成像任务（需 OPERATOR） |
| GET | /api/v1/thermal/tasks/{taskId} | 查询热成像任务状态 |
| POST | /api/v1/multispectral/tasks | 创建多光谱任务（需 OPERATOR） |
| GET | /api/v1/multispectral/tasks/{taskId} | 查询多光谱任务状态 |
| POST | /api/v1/obstacle/config | 配置避障（需 ADMIN） |
| GET | /api/v1/obstacle/config/{sysid} | 查询避障配置 |
| GET | /api/v1/obstacle/status/{sysid} | 避障状态查询 |
| POST | /api/v1/obstacle/{sysid}/release | 紧急悬停解除（需 OPERATOR） |

## 硬件抽象

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/radar/config | 配置雷达扫描（需 ADMIN） |
| GET | /api/v1/radar/config/{sysid} | 查询雷达配置 |
| GET | /api/v1/radar/status/{sysid} | 查询扫描状态 |
| GET | /api/v1/radar/targets/{sysid} | 查询目标列表 |
| POST | /api/v1/rotor/config | 配置气动参数（需 ADMIN） |
| GET | /api/v1/rotor/telemetry/{sysid} | 查询气动遥测 |
| GET | /api/v1/lidar/data/{sysid} | 查询 LiDAR 数据 |
| GET | /api/v1/imu/data/{sysid} | 查询 IMU 数据 |

## 通信组网

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/mesh/topology | 获取全网拓扑 |
| GET | /api/v1/mesh/topology/{sysid} | 获取单节点拓扑 |
| GET | /api/v1/mesh/routes/{sysid} | 获取单节点路由表 |
| GET | /api/v1/mesh/neighbors/{sysid} | 获取单节点邻居表 |
| GET | /api/v1/mesh/links | 获取所有链路及质量分级 |
| GET | /api/v1/celltowers | 获取全网基站拓扑 |
| GET | /api/v1/celltowers/{sysid} | 获取单基站状态 |
| PUT | /api/v1/celltowers/{sysid}/config | 下发基站配置（需 ADMIN） |
| GET | /api/v1/celltowers/{sysid}/terminals | 获取基站接入终端列表 |
| POST | /api/v1/celltowers/{sysid}/handover | 触发漫游切换（需 OPERATOR） |
| GET | /api/v1/celltowers/handovers | 获取漫游切换历史 |
| GET | /api/v1/sat-link/status | 获取所有卫星链路状态 |
| GET | /api/v1/sat-link/status/{satId} | 获取单星链路状态 |
| GET | /api/v1/sat-link/passes | 获取所有过境计划 |
| GET | /api/v1/sat-link/passes/{satId} | 获取单星过境计划 |
| GET | /api/v1/sat-link/routes | 获取最近路由决策历史 |
| GET | /api/v1/sat-link/strategy | 获取当前切换策略 |
| PUT | /api/v1/sat-link/strategy | 设置切换策略（需 ADMIN） |
| GET | /api/v1/sat-link/constellation | 获取星座配置 |
| GET | /api/v1/terrain/map | 获取当前地形分区图 |
| GET | /api/v1/terrain/restrictions | 获取飞行限制区列表 |
| GET | /api/v1/terrain/changes | 获取地形变更历史 |
| POST | /api/v1/terrain/build | 触发地形建图（需 OPERATOR） |

## 集群调度

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/scheduling/tasks | 创建调度任务（需 OPERATOR） |
| GET | /api/v1/scheduling/tasks | 列出所有调度任务 |
| DELETE | /api/v1/scheduling/tasks/{id} | 取消调度任务（需 OPERATOR） |
| POST | /api/v1/scheduling/conflicts/check | 检查冲突 |
| GET | /api/v1/scheduling/status | 调度状态 |
| GET | /api/v1/squad/roles | 当前角色状态 |
| POST | /api/v1/squad/assign | 重计算并分派环绕任务（需 OPERATOR） |
| POST | /api/v1/squad/leader/{sysid} | 手动设置长机（需 OPERATOR） |

## AI 决策

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/ai/decisions | 获取所有无人机决策 |
| GET | /api/v1/ai/decisions/{sysid} | 获取单机决策列表 |

## 边缘计算

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/edge/results | 提交边缘计算结果 |
| GET | /api/v1/edge/tasks | 获取所有边缘任务 |
| GET | /api/v1/edge/fusion/{sysid} | 获取融合数据 |

## 数字孪生

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/twin/state | 获取所有数字孪生状态 |
| GET | /api/v1/twin/state/{sysid} | 获取单机数字孪生状态 |
| GET | /api/v1/twin/predict/{sysid} | 轨迹预测 |
| GET | /api/v1/twin/compare/{sysid} | 虚实对比 |

## 环境气象

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/env-alerts | 查询环境告警历史 |

## 安全认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/login | 用户登录 |
| POST | /api/auth/refresh | 刷新令牌 |
| POST | /api/v1/auth/api-key | 生成 API Key |
| DELETE | /api/v1/auth/api-key/{keyId} | 撤销 API Key |
| GET | /api/v1/auth/api-key | 列出当前用户的 API Key |

## 审计日志

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/audit/logs | 查询审计日志（需 ADMIN） |

## 许可证

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/license/info | 查询当前 License 信息 |
| POST | /api/license/activate | 激活 License |
| GET | /api/license/verify | 验证 License 有效性 |

## 自动出警

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/autodispatch/trigger | 手动触发自动出警 |
| GET | /api/v1/autodispatch/history | 查询出警历史 |
| GET | /api/v1/autodispatch/active | 查询进行中的出警任务 |
| POST | /api/v1/autodispatch/{dispatchId}/abort | 中止出警任务 |
| GET | /api/v1/autodispatch/config | 获取自动出警配置 |
| PUT | /api/v1/autodispatch/config | 更新自动出警配置 |
| POST | /api/v1/voice-intercom/{sysid}/start | 启动双向语音对讲 |
| POST | /api/v1/voice-intercom/{sysid}/stop | 停止语音对讲 |
| GET | /api/v1/voice-intercom/{sysid}/status | 语音对讲状态查询 |
| POST | /api/v1/voice-intercom/{sysid}/broadcast | 广播喊话 |
| GET | /api/v1/video-stream/{sysid}/url | 获取视频流 URL |
| POST | /api/v1/video-stream/{sysid}/start | 启动视频流推送 |
| POST | /api/v1/video-stream/{sysid}/stop | 停止视频流 |
| GET | /api/v1/video-stream/{sysid}/status | 视频流状态查询 |
| GET | /api/v1/video-stream/active | 查询所有活跃视频流 |

## 语音指挥

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/voice-cmd/parse | 解析语音文本为指令 |
| POST | /api/v1/voice-cmd/execute | 执行语音指令 |
| POST | /api/v1/voice-cmd/confirm/{pendingId} | 确认紧急指令 |
| POST | /api/v1/voice-cmd/broadcast/{sysid} | 发送语音播报 |
| GET | /api/v1/voice-cmd/broadcast/{sysid}/status | 获取状态播报文本 |
| GET | /api/v1/voice-cmd/broadcast/{sysid}/alert | 获取告警播报文本 |
| GET | /api/v1/voice-cmd/history | 查询指令历史 |
| GET | /api/v1/voice-cmd/pending | 查询待确认指令 |

## 空地协同

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/air-ground/situation | 获取空地协同态势融合视图（需 OBSERVER） |
| POST | /api/v1/air-ground/recon | 从安防告警触发无人机侦察（需 OPERATOR） |
| POST | /api/v1/air-ground/ptz-track | 无人机目标触发 PTZ 联动追踪（需 OPERATOR） |
| POST | /api/v1/air-ground/start | 启动空地协同指挥流程（需 OPERATOR） |
| GET | /api/v1/air-ground/evaluate/{coordinationId} | 执行评估阶段（需 OBSERVER） |
| GET | /api/v1/air-ground/report/{coordinationId} | 获取指挥报告（需 OBSERVER） |

## 通信自适应

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/comm-adapt/quality/{sysid} | 获取单机通信质量 |
| GET | /api/v1/comm-adapt/quality/fleet | 获取机队通信质量总览 |
| GET | /api/v1/comm-adapt/recommendations | 获取链路切换建议 |
| POST | /api/v1/comm-adapt/switch/{sysid} | 手动切换链路 |
| GET | /api/v1/comm-adapt/failover/history/{sysid} | 获取故障切换历史 |
| GET | /api/v1/comm-adapt/topology | 获取通信拓扑 |
| GET | /api/v1/comm-adapt/config | 获取自适应配置 |
| PUT | /api/v1/comm-adapt/config | 更新自适应配置 |

## 灾害通信

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/disaster/status | 获取灾害模式状态 |
| POST | /api/v1/disaster/activate | 手动激活灾害模式 |
| POST | /api/v1/disaster/deactivate | 手动退出灾害模式 |
| GET | /api/v1/disaster/clusters | 获取分簇拓扑 |
| GET | /api/v1/disaster/qos | 获取 QoS 优先级队列状态 |
| GET | /api/v1/disaster/links | 获取异构链路桥接状态 |

## 健康管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/health/{sysid} | 获取单机健康评分 |
| GET | /api/v1/health/fleet | 获取机队健康总览 |
| GET | /api/v1/health/{sysid}/history | 获取健康评分历史 |
| GET | /api/v1/health/{sysid}/components/{component} | 获取单部件详情 |
| GET | /api/v1/health/warnings | 获取所有健康告警 |
| GET | /api/v1/maintenance/records | 查询维护记录 |
| POST | /api/v1/maintenance/records | 创建维护记录 |
| PUT | /api/v1/maintenance/records/{id} | 更新维护记录 |
| GET | /api/v1/maintenance/predictions | 获取所有预测性维护建议 |
| GET | /api/v1/maintenance/predictions/{sysid} | 获取单机预测性维护建议 |
| GET | /api/v1/maintenance/schedule | 获取维护计划 |

## 智能巡检

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/inspection/tasks | 创建巡检任务 |
| GET | /api/v1/inspection/tasks | 列出巡检任务 |
| GET | /api/v1/inspection/tasks/{id} | 获取任务详情 |
| POST | /api/v1/inspection/tasks/{id}/start | 启动巡检 |
| POST | /api/v1/inspection/tasks/{id}/abort | 中止巡检 |
| GET | /api/v1/inspection/tasks/{id}/progress | 查询进度 |
| GET | /api/v1/inspection/templates | 列出巡检模板预设 |
| GET | /api/v1/inspection/reports/{taskId} | 获取巡检报告 |
| GET | /api/v1/inspection/reports/{taskId}/anomalies | 获取异常清单 |
| GET | /api/v1/inspection/reports/{taskId}/photos | 获取照片列表 |
| POST | /api/v1/inspection/reports/{taskId}/export | 导出报告 |

## 航拍测绘

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/mapping/tasks | 创建测绘任务 |
| GET | /api/v1/mapping/tasks | 列出测绘任务 |
| GET | /api/v1/mapping/tasks/{id} | 获取任务详情 |
| POST | /api/v1/mapping/tasks/{id}/start | 启动测绘 |
| POST | /api/v1/mapping/tasks/{id}/abort | 中止测绘 |
| GET | /api/v1/mapping/tasks/{id}/waypoints | 获取航线规划 |
| GET | /api/v1/mapping/tasks/{id}/photos | 获取采集照片 |
| GET | /api/v1/mapping/tasks/{id}/result | 获取测绘成果 |
| POST | /api/v1/mapping/tasks/{id}/process | 触发成果生成 |
| GET | /api/v1/mapping/results | 列出所有测绘成果 |
| GET | /api/v1/mapping/results/{id}/download | 下载测绘成果 |

## 编排管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/orch/plans | 创建编排计划 |
| GET | /api/v1/orch/plans | 列出所有编排计划 |
| GET | /api/v1/orch/plans/{planId} | 查询指定计划详情 |
| POST | /api/v1/orch/plans/{planId}/start | 启动计划 |
| POST | /api/v1/orch/plans/{planId}/pause | 暂停计划 |
| POST | /api/v1/orch/plans/{planId}/resume | 恢复计划 |
| POST | /api/v1/orch/plans/{planId}/abort | 中止计划 |
| GET | /api/v1/orch/plans/{planId}/progress | 查询步骤进度 |

## 场景管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/scenarios/templates | 列出所有场景模板 |
| GET | /api/v1/scenarios/templates/{id} | 获取模板详情 |
| POST | /api/v1/scenarios/templates | 创建自定义模板 |
| PUT | /api/v1/scenarios/templates/{id} | 更新模板 |
| DELETE | /api/v1/scenarios/templates/{id} | 删除模板 |
| GET | /api/v1/scenarios/templates/by-type/{disasterType} | 按灾害类型筛选 |
| POST | /api/v1/scenarios/launch/{templateId} | 一键启动场景 |
| GET | /api/v1/scenarios/launch/active | 查询进行中的场景 |
| GET | /api/v1/scenarios/launch/history | 查询历史启动记录 |
| POST | /api/v1/scenarios/launch/{launchId}/abort | 中止场景执行 |
| GET | /api/v1/scenarios/launch/{launchId}/status | 查询场景执行状态 |
| POST | /api/v1/scenarios/drill/{templateId} | 启动演练 |
| GET | /api/v1/scenarios/drill/{drillId}/result | 获取演练评估报告 |
| GET | /api/v1/scenarios/drill/history | 演练历史 |

## 编队表演-灯光秀

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/show/formations | 创建队形定义 |
| GET | /api/v1/show/formations | 列出所有队形 |
| GET | /api/v1/show/formations/{id} | 获取队形详情 |
| POST | /api/v1/show/formations/{id}/positions | 计算队形位置 |
| POST | /api/v1/show/tasks | 创建表演任务 |
| GET | /api/v1/show/tasks | 列出所有表演任务 |
| GET | /api/v1/show/tasks/{id} | 获取任务详情 |
| POST | /api/v1/show/tasks/{id}/start | 启动表演 |
| POST | /api/v1/show/tasks/{id}/abort | 中止表演 |
| GET | /api/v1/show/tasks/{id}/actions | 获取动作序列 |
| POST | /api/v1/show/tasks/{id}/music-sync | 配置音乐同步 |

## 物流配送2

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/delivery2/tasks | 创建配送任务 |
| GET | /api/v1/delivery2/tasks | 列出配送任务 |
| GET | /api/v1/delivery2/tasks/{id} | 获取任务详情 |
| POST | /api/v1/delivery2/tasks/{id}/start | 启动配送 |
| POST | /api/v1/delivery2/tasks/{id}/abort | 中止配送 |
| GET | /api/v1/delivery2/tasks/{id}/route | 获取优化路线 |
| POST | /api/v1/delivery2/tasks/{id}/deliver | 执行投放 |
| GET | /api/v1/delivery2/tasks/{id}/status | 配送状态 |
| POST | /api/v1/delivery2/tasks/{id}/confirm | 确认签收 |
| GET | /api/v1/delivery2/landing-sites | 搜索降落点 |

## 数字孪生-城市

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/city-twin/simulation/flood | 洪水模拟 |
| POST | /api/v1/city-twin/simulation/fire | 火灾模拟 |
| POST | /api/v1/city-twin/simulation/earthquake | 地震模拟 |
| POST | /api/v1/city-twin/simulation/evacuation | 疏散模拟 |
| GET | /api/v1/city-twin/simulation/{id} | 获取模拟结果 |
| GET | /api/v1/city-twin/simulation/history | 模拟历史 |
| GET | /api/v1/city-twin/situation/current | 获取当前态势 |
| GET | /api/v1/city-twin/situation/history | 获取历史态势 |
| GET | /api/v1/city-twin/situation/drones | 获取所有无人机位置 |
| GET | /api/v1/city-twin/situation/alerts | 获取所有告警标记 |
| GET | /api/v1/city-twin/playback/drones/{sysid} | 无人机轨迹回放 |
| GET | /api/v1/city-twin/playback/alerts | 告警事件回放 |
| GET | /api/v1/city-twin/playback/situation | 综合态势回放 |
| GET | /api/v1/city-twin/markers | 列出所有标绘 |
| POST | /api/v1/city-twin/markers | 创建标绘 |
| DELETE | /api/v1/city-twin/markers/{id} | 删除标绘 |
| PUT | /api/v1/city-twin/markers/{id} | 更新标绘 |
| GET | /api/v1/city-twin/models | 列出所有城市模型 |
| GET | /api/v1/city-twin/models/{id} | 获取模型详情 |
| POST | /api/v1/city-twin/models | 上传/注册新模型 |
| DELETE | /api/v1/city-twin/models/{id} | 删除模型 |
| PUT | /api/v1/city-twin/models/{id}/refresh | 刷新模型数据 |

## 离线自治

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/offline-alarm/batch-upload | 批量上传离线报警 |
| GET | /api/v1/offline-alarm/pending | 获取待上传列表 |
| GET | /api/v1/offline-alarm/cache-stats | 获取缓存统计 |
| POST | /api/v1/offline-alarm/flush | 手动触发批量上传 |
| POST | /api/v1/offline-alarm/edge-ai/trigger | 模拟边缘 AI 检测 |
| GET | /api/v1/offline-alarm/edge-ai/stats | 获取边缘 AI 统计 |
| POST | /api/v1/offline-alarm/edge-ai/configure | 配置检测类型 |

## LoRa 回传

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/loRa/alarm | 接收 LoRa 回传告警 |
| GET | /api/v1/loRa/stats | 获取 LoRa 回传通道统计 |

## 用户管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/users | 列出当前租户用户（需 ADMIN） |
| GET | /api/v1/users/{id} | 获取用户详情（需 ADMIN） |
| POST | /api/v1/users | 创建用户（需 ADMIN） |
| PUT | /api/v1/users/{id} | 更新用户（需 ADMIN） |
| DELETE | /api/v1/users/{id} | 删除用户（需 ADMIN） |

## 租户管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/tenants | 列出所有租户（需 ADMIN） |
| GET | /api/v1/tenants/{id} | 获取租户详情（需 ADMIN） |
| POST | /api/v1/tenants | 创建租户（需 ADMIN） |
| PUT | /api/v1/tenants/{id} | 更新租户（需 ADMIN） |
| DELETE | /api/v1/tenants/{id} | 删除租户（需 ADMIN） |

## Webhook 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/webhooks | 注册 webhook |
| GET | /api/v1/webhooks | 列出当前租户的 webhook |
| DELETE | /api/v1/webhooks/{id} | 注销 webhook |

## OpenAPI 导出

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/openapi/json | 导出 OpenAPI JSON |
| GET | /api/v1/openapi/yaml | 导出 OpenAPI YAML |