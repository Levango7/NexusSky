import React, { useState, useEffect, useRef, useCallback } from 'react'
import { getAuthToken } from '../api.js'

const POLL_MS = 2000

const RID_STATUS_COLORS = {
  BROADCASTING: '#2de2a5',
  NOT_BROADCASTING: '#888',
  BROADCASTING_ERROR: '#ff5d5d',
}

const RID_STATUS_LABELS = {
  BROADCASTING: '正在广播',
  NOT_BROADCASTING: '未广播',
  BROADCASTING_ERROR: '广播错误',
}

const ID_TYPE_LABELS = {
  0: '序列号',
  1: 'CAA注册号',
  2: 'UTM分配ID',
  3: '特定会话ID',
}

const UA_TYPE_LABELS = {
  0: '无', 1: '固定翼', 2: '直升机/多旋翼', 3: '旋翼机', 4: 'VTOL',
  5: '扑翼机', 6: '滑翔机', 7: '风筝', 8: '自由气球', 9: '系留气球',
  10: '飞艇', 11: '自由落体/降落伞', 12: '火箭', 13: '系留动力飞行器',
  14: '地面障碍物', 15: '其他',
}

const LOCATION_STATUS_LABELS = {
  0: '未声明', 1: '地面', 2: '空中', 3: '紧急',
}

const OPERATOR_LOCATION_TYPE_LABELS = {
  0: '起飞点', 1: '实时GNSS', 2: '固定位置',
}

const DESCRIPTION_TYPE_LABELS = {
  0: '文本描述', 1: '私有用途',
}

function maskId(id) {
  if (!id || id.length <= 4) return id ? '****' : '--'
  return id.slice(0, 2) + '****' + id.slice(-2)
}

function formatTimestamp(ts) {
  if (!ts || ts <= 0) return '--'
  return new Date(ts * 1000).toLocaleTimeString('zh-CN', { hour12: false })
}

function formatLastReceived(ms) {
  if (!ms || ms <= 0) return '--'
  const diff = Date.now() - ms
  if (diff < 1000) return '刚刚'
  if (diff < 60000) return `${Math.floor(diff / 1000)}秒前`
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`
  return new Date(ms).toLocaleTimeString('zh-CN', { hour12: false })
}

function InfoItem({ label, value }) {
  return (
    <div style={{ padding: '4px 8px', borderRadius: 4, background: 'rgba(255,255,255,0.03)' }}>
      <div style={{ fontSize: 10, color: 'var(--dim)' }}>{label}</div>
      <div style={{ fontSize: 12, color: 'var(--text)', fontFamily: 'monospace' }}>{value}</div>
    </div>
  )
}

export default function RidPanel() {
  const [ridList, setRidList] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [expandedSysid, setExpandedSysid] = useState(null)
  const pollRef = useRef(null)

  const fetchRidStatus = useCallback(async () => {
    try {
      const token = getAuthToken()
      const res = await fetch('/api/v1/rid/status', {
        headers: token ? { 'Authorization': `Bearer ${token}` } : {},
      })
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`)
      }
      const data = await res.json()
      setRidList(data.snapshots || data || [])
      setError(null)
    } catch (e) {
      setError(e.message || '获取 RID 状态失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchRidStatus()
    pollRef.current = setInterval(fetchRidStatus, POLL_MS)
    return () => clearInterval(pollRef.current)
  }, [fetchRidStatus])

  const stats = {
    broadcasting: ridList.filter((r) => r.ridStatus === 'BROADCASTING').length,
    notBroadcasting: ridList.filter((r) => r.ridStatus === 'NOT_BROADCASTING').length,
    error: ridList.filter((r) => r.ridStatus === 'BROADCASTING_ERROR').length,
  }

  return (
    <div style={{ padding: 16, height: '100%', overflow: 'auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 style={{ margin: 0, fontSize: 18, color: 'var(--cyan)' }}>RID 运行识别状态</h2>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span className="chip mono" style={{ fontSize: 10, color: '#2de2a5', background: 'rgba(45,226,165,0.2)' }}>
            广播 {stats.broadcasting}
          </span>
          <span className="chip mono" style={{ fontSize: 10, color: '#888', background: 'rgba(136,136,136,0.2)' }}>
            未广播 {stats.notBroadcasting}
          </span>
          {stats.error > 0 && (
            <span className="chip mono" style={{ fontSize: 10, color: '#ff5d5d', background: 'rgba(255,93,93,0.2)' }}>
              错误 {stats.error}
            </span>
          )}
        </div>
      </div>

      {!loading && !error && ridList.length === 0 && (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--dim)' }}>
          <div style={{ fontSize: 32, marginBottom: 8 }}>📡</div>
          <div style={{ fontSize: 14 }}>暂无 RID 状态数据</div>
          <div style={{ fontSize: 12, marginTop: 4 }}>等待无人机上报 Remote ID 信息…</div>
        </div>
      )}

      {loading && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--dim)' }}>加载 RID 数据…</div>
      )}

      {error && !loading && (
        <div style={{ textAlign: 'center', padding: 20, color: 'var(--crit)' }}>⚠ {error}</div>
      )}

      {!loading && !error && ridList.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {ridList.map((rid) => {
            const statusColor = RID_STATUS_COLORS[rid.ridStatus] || '#888'
            const statusLabel = RID_STATUS_LABELS[rid.ridStatus] || rid.ridStatus
            const isExpanded = expandedSysid === rid.sysid
            const basicId = rid.basicId || {}
            const location = rid.location || {}
            const system = rid.system || {}
            const selfId = rid.selfId || {}
            const operatorId = rid.operatorId || {}

            return (
              <div
                key={rid.sysid}
                style={{
                  borderRadius: 8,
                  background: 'var(--card)',
                  border: `1px solid ${statusColor}40`,
                  overflow: 'hidden',
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '10px 12px',
                    cursor: 'pointer',
                  }}
                  onClick={() => setExpandedSysid(isExpanded ? null : rid.sysid)}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <div
                      style={{
                        width: 10,
                        height: 10,
                        borderRadius: '50%',
                        background: statusColor,
                        boxShadow: `0 0 6px ${statusColor}`,
                      }}
                    />
                    <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)' }}>
                      无人机 #{rid.sysid}
                    </span>
                    <span
                      className="chip mono"
                      style={{ fontSize: 10, color: statusColor, background: statusColor + '20' }}
                    >
                      {statusLabel}
                    </span>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: 11, color: 'var(--dim)' }}>
                      {basicId.uasId ? maskId(basicId.uasId) : '--'}
                    </span>
                    <span style={{ fontSize: 12, color: 'var(--dim)' }}>
                      {isExpanded ? '▼' : '▶'}
                    </span>
                  </div>
                </div>

                {isExpanded && (
                  <div style={{ padding: '0 12px 12px 12px', borderTop: `1px solid ${statusColor}20` }}>
                    <div style={{ marginTop: 10 }}>
                      <div style={{ fontSize: 12, color: 'var(--cyan)', marginBottom: 6 }}>基本信息</div>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 6 }}>
                        <InfoItem label="系统 ID" value={rid.sysid} />
                        <InfoItem label="UAS 标识" value={basicId.uasId ? maskId(basicId.uasId) : '--'} />
                        <InfoItem label="ID 类型" value={ID_TYPE_LABELS[basicId.idType] || '--'} />
                        <InfoItem label="无人机类型" value={UA_TYPE_LABELS[basicId.uaType] || '--'} />
                      </div>
                    </div>

                    <div style={{ marginTop: 10 }}>
                      <div style={{ fontSize: 12, color: 'var(--cyan)', marginBottom: 6 }}>位置信息</div>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 6 }}>
                        <InfoItem label="纬度" value={location.latitude != null ? location.latitude.toFixed(6) : '--'} />
                        <InfoItem label="经度" value={location.longitude != null ? location.longitude.toFixed(6) : '--'} />
                        <InfoItem label="气压高度" value={location.altitudeBarometric != null ? `${location.altitudeBarometric.toFixed(1)} m` : '--'} />
                        <InfoItem label="大地高度" value={location.altitudeGeodetic != null ? `${location.altitudeGeodetic.toFixed(1)} m` : '--'} />
                        <InfoItem label="相对高度" value={location.height != null ? `${location.height.toFixed(1)} m` : '--'} />
                        <InfoItem label="位置状态" value={LOCATION_STATUS_LABELS[location.status] || '--'} />
                        <InfoItem label="航向" value={location.direction != null && location.direction !== 361 ? `${location.direction}°` : '--'} />
                        <InfoItem label="水平速度" value={location.speedHorizontal != null && location.speedHorizontal !== 254.25 ? `${location.speedHorizontal.toFixed(2)} m/s` : '--'} />
                        <InfoItem label="时间戳" value={formatTimestamp(location.timestamp)} />
                      </div>
                    </div>

                    <div style={{ marginTop: 10 }}>
                      <div style={{ fontSize: 12, color: 'var(--cyan)', marginBottom: 6 }}>系统信息</div>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 6 }}>
                        <InfoItem label="操作者位置类型" value={OPERATOR_LOCATION_TYPE_LABELS[system.operatorLocationType] || '--'} />
                        <InfoItem label="区域无人机数" value={system.areaCount || '--'} />
                        <InfoItem label="区域半径" value={system.areaRadius != null ? `${system.areaRadius} m` : '--'} />
                        <InfoItem label="区域上限" value={system.areaCeiling != null ? `${system.areaCeiling.toFixed(1)} m` : '--'} />
                        <InfoItem label="区域下限" value={system.areaFloor != null ? `${system.areaFloor.toFixed(1)} m` : '--'} />
                      </div>
                    </div>

                    <div style={{ marginTop: 10 }}>
                      <div style={{ fontSize: 12, color: 'var(--cyan)', marginBottom: 6 }}>自描述信息</div>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 6 }}>
                        <InfoItem label="描述类型" value={DESCRIPTION_TYPE_LABELS[selfId.descriptionType] || '--'} />
                        <InfoItem label="描述文本" value={selfId.description || '--'} />
                      </div>
                    </div>

                    <div style={{ marginTop: 10 }}>
                      <div style={{ fontSize: 12, color: 'var(--cyan)', marginBottom: 6 }}>操作者信息</div>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 6 }}>
                        <InfoItem label="操作者 ID" value={operatorId.operatorId ? maskId(operatorId.operatorId) : '--'} />
                        <InfoItem label="操作者纬度" value={system.operatorLatitude != null ? system.operatorLatitude.toFixed(6) : '--'} />
                        <InfoItem label="操作者经度" value={system.operatorLongitude != null ? system.operatorLongitude.toFixed(6) : '--'} />
                      </div>
                    </div>

                    <div style={{ marginTop: 10 }}>
                      <div style={{ fontSize: 12, color: 'var(--cyan)', marginBottom: 6 }}>最后接收时间</div>
                      <InfoItem label="距上次接收" value={formatLastReceived(rid.lastReceivedTime)} />
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}