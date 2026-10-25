import React from 'react'

/**
 * 热成像叠加面板——**功能未实现，已停用**（P1 #12，2026-10-24 分诊）。
 *
 * <p>此前本面板轮询 GET /api/v1/vision/thermal/sources 并画逐点热力图，但该端点
 * 在后端根本不存在，面板实际永远处于 404 报错态；「历史回放」按钮更是前端拿
 * 当前数据加随机扰动<b>伪造</b>的假功能。按前端诚实性原则（参照物理模型按钮
 * 先例），移除轮询与伪造回放，改为如实说明。
 *
 * <p>分诊确证的后端现状（为什么"改读任务列表"也不诚实）：
 * <ul>
 *   <li>后端只有 POST /api/v1/thermal/tasks + GET /tasks/{taskId} 任务壳——没有任何
 *       ingest 把任务从 PENDING 推进到 RUNNING/COMPLETED（ThermalDataMsg
 *       MAVLink 30012 在 cloud-backend 零引用），列表里全是永不完成的壳任务</li>
 *   <li>任务结果只有聚合统计（tempMean/Max/Min/hotspotCount），没有面板需要的
 *       「带经纬度的热源点列表」——逐点地理坐标需要图像→地理位姿解算
 *       （drone-sim 的 Hotspot 是图像坐标 u/v，不是 lat/lon）</li>
 * </ul>
 *
 * <p>恢复条件（详见 docs/todo.md P2 登记）：接线 30012 ingest + 补机队级热状态
 * 聚合读端点 + 热源地理定位（位姿解算）。
 */
export default function ThermalOverlayPanel() {
  return (
    <div className="page">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 className="page-title">热成像叠加面板</h2>
        <span className="chip" style={{ fontSize: 10, background: 'rgba(255,200,87,0.15)', color: 'var(--warn)' }}>
          未实现
        </span>
      </div>

      <div style={{ padding: 16, borderRadius: 8, background: 'var(--card)', border: '1px dashed var(--border)' }}>
        <h3 style={{ fontSize: 13, color: 'var(--warn)', margin: '0 0 10px 0' }}>热成像源读口径后端不存在，本面板已停用</h3>
        <p style={{ fontSize: 12, color: 'var(--dim)', margin: '0 0 8px 0', lineHeight: 1.8 }}>
          面板需要的「带经纬度的热源点列表」后端没有：既没有源列表端点，也没有带坐标的单点数据，
          连任务状态机都没接线（ThermalDataMsg 遥测消息未接入 cloud-backend）。
          此前面板在 404 下只能报错，「历史回放」是前端随机扰动伪造的假数据——均已移除。
        </p>
        <p style={{ fontSize: 12, color: 'var(--dim)', margin: '0 0 8px 0', lineHeight: 1.8 }}>
          <b>为什么不改读任务列表</b>：/api/v1/thermal/tasks 返回的是永不完成的任务壳
          （结果只有聚合温度统计），把一个假面板换成另一个假面板不叫修复。
        </p>
        <p style={{ fontSize: 11, color: 'var(--dim)', margin: 0, lineHeight: 1.8 }}>
          恢复条件：① cloud-backend 接线 MAVLink 30012 (ThermalDataMsg) ingest；
          ② 补机队级热状态聚合读端点；③ 热源地理定位（图像→地理位姿解算）。
          登记在 docs/todo.md P2。
        </p>
      </div>
    </div>
  )
}
