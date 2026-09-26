// P2-P4 面板共享工具函数和样式
// 提取自6个面板组件的重复定义，统一维护

// 轮询间隔（毫秒）
export const POLL_MS = 5000

// 时间格式化（兼容数字毫秒时间戳和 ISO 字符串）
export function fmtTime(ts) {
  if (ts == null || ts === '') return '--'
  const t = typeof ts === 'number' ? ts : Date.parse(ts)
  if (isNaN(t)) return String(ts)
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

// 规范化列表数据：兼容裸数组 / {key:[]} / 其他对象包裹的数组结构
export function toArray(data, fallbackKey) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data[fallbackKey])) return data[fallbackKey]
  if (data && typeof data === 'object') {
    for (const k of Object.keys(data)) {
      if (Array.isArray(data[k])) return data[k]
    }
  }
  return []
}

// 字段兼容提取（从对象中按优先级取第一个非null值）
export function pick(obj, ...keys) {
  if (!obj) return null
  for (const k of keys) {
    if (obj[k] != null) return obj[k]
  }
  return null
}

// 纬度1度对应的米数近似值
export const METERS_PER_DEGREE_LAT = 111320

// ---- 共享内联样式 ----

export const cardStyle = {
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 4,
  padding: '6px 10px',
}

export const labelStyle = {
  fontSize: 10,
  color: 'var(--dim-2)',
  marginBottom: 2,
}

export const miniBtnStyle = {
  fontSize: 10,
  padding: '2px 8px',
  cursor: 'pointer',
  border: '1px solid var(--line-2)',
  background: 'transparent',
  color: 'var(--dim)',
  borderRadius: 3,
}

export const modalInputStyle = {
  width: '100%',
  padding: '4px 6px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--text)',
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  borderRadius: 3,
  outline: 'none',
  boxSizing: 'border-box',
}