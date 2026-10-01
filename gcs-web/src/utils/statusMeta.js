/**
 * 任务优先级 / 告警严重度的元数据与规范化（单一真相源）。
 *
 * 本文件抽出前，`PRIORITY_META` + `normPriority` 在 EmergencyOrchPanel 与
 * UnifiedCommandPanel 各有一份，`SEVERITY_META` + `normSeverity` 在 AlarmPanel 与
 * UnifiedCommandPanel 各有一份——四份逐字节相同的复制品。复制品之间一旦只改一份，
 * 同一个优先级就会在两个面板显示成不同颜色/标签，而且没有任何测试会发现。
 * 现统一到此处，各面板 import。
 *
 * 注意 VoiceCmdPanel 另有一份同名 PRIORITY_META，**故意不合并**：那是语音指令的
 * 优先级域（键为 LOW/MEDIUM/NORMAL/HIGH/CRITICAL，无 weight 字段），与本文件的
 * 任务优先级域（键为 P0-P3 + HIGH/MEDIUM/LOW，带 weight）不是同一套语义。
 */

/** 任务优先级元数据。weight 越小越紧急（用于排序）。 */
export const PRIORITY_META = {
  P0: { label: 'P0 紧急', color: 'var(--crit)', weight: 0 },
  P1: { label: 'P1 高', color: 'var(--warn)', weight: 1 },
  P2: { label: 'P2 中', color: 'var(--gold)', weight: 2 },
  P3: { label: 'P3 低', color: 'var(--cyan)', weight: 3 },
  HIGH: { label: '高', color: 'var(--crit)', weight: 0 },
  MEDIUM: { label: '中', color: 'var(--warn)', weight: 1 },
  LOW: { label: '低', color: 'var(--cyan)', weight: 2 },
}

/** 告警严重度元数据。weight 越小越严重（用于排序）。 */
export const SEVERITY_META = {
  CRITICAL: { color: 'var(--crit)', label: '严重', weight: 0 },
  WARN: { color: 'var(--warn)', label: '警告', weight: 1 },
  WARNING: { color: 'var(--warn)', label: '警告', weight: 1 },
  INFO: { color: 'var(--cyan)', label: '信息', weight: 2 },
  ERROR: { color: 'var(--crit)', label: '错误', weight: 0 },
}

/**
 * 规范化优先级键。
 *
 * 后端在不同接口下会回 `P0` / `P0_CRITICAL` / `HIGH` / 小写 `p1` 等多种写法，
 * 统一收敛到 PRIORITY_META 里存在的键。
 *
 * 契约：无法识别的输入一律降级为 'P3'（最低优先级），**不抛错**——优先级是展示用的
 * 辅助信息，一个脏数据不该让整个面板崩掉。
 *
 * @param {*} p 原始优先级
 * @returns {'P0'|'P1'|'P2'|'P3'|'HIGH'|'MEDIUM'|'LOW'}
 */
export function normPriority(p) {
  if (!p) return 'P3'
  const u = String(p).toUpperCase()
  if (PRIORITY_META[u]) return u
  if (u.startsWith('P0')) return 'P0'
  if (u.startsWith('P1')) return 'P1'
  if (u.startsWith('P2')) return 'P2'
  if (u.startsWith('P3')) return 'P3'
  return 'P3'
}

/**
 * 规范化严重程度键。
 *
 * 契约：无法识别的输入降级为 'INFO'（最低严重度），不抛错。
 *
 * @param {*} s 原始严重程度
 * @returns {'CRITICAL'|'WARN'|'WARNING'|'INFO'|'ERROR'}
 */
export function normSeverity(s) {
  if (!s) return 'INFO'
  const u = String(s).toUpperCase()
  if (SEVERITY_META[u]) return u
  if (u.includes('CRIT')) return 'CRITICAL'
  if (u.includes('WARN')) return 'WARN'
  if (u.includes('ERR')) return 'ERROR'
  return 'INFO'
}
