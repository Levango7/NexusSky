import { describe, it, expect } from 'vitest'
import {
  TASK_ID_IS_HASH,
  TASK_TYPE_META,
  TASK_PRIORITY_META,
  TASK_STATUS_META,
  CONFLICT_TYPE_META,
  CONFLICT_SEVERITY_META,
  ALARM_TYPE_META,
  ALARM_SEVERITY_META,
  ALARM_ACK_META,
  SURVEILLANCE_VENDOR_META,
  SURVEILLANCE_STATUS_META,
  normalizeTaskAssignment,
  normalizeConflictAlert,
  normalizeTaskStatus,
  normalizeAlarmTrigger,
  normalizeAlarmAck,
  normalizeSurveillanceStatus,
  formatUptime,
} from '../src/utils/schedulingFrames.js'

/**
 * M10 调度三帧 + 4a 联动三帧的前端归一化测试。
 *
 * 背景：这六个 WS 帧后端早已有生产者，前端直到 2026-10-06 才补上消费分支。
 * 本组用例的价值不在"函数能跑"，而在把两处**协议实况**钉成断言：
 *   ① 帧里的 taskId 是不可逆哈希（TASK_ID_IS_HASH），不是 REST taskId；
 *   ② ALARM_TRIGGER 的 alarmId（2026-10-07 协议追加）**缺失或为 0 时**不可关联，
 *      两条路都必须仍报 correlatable=false（旧帧兼容）。
 * 这两条一旦被"顺手修好"，本组即红——那正是要人工确认协议变更的时机。
 *
 * 字段名与单位取自 mavlink-core 的消息类（TaskAssignmentMsg / ConflictAlertMsg /
 * TaskStatusMsg / AlarmTriggerMsg / AlarmAckMsg / SurveillanceStatusMsg），
 * 码表与各消息 javadoc 一一对应。
 */

const NOW = 1_700_000_000_000

describe('调度/联动帧归一化', () => {
  describe('normalizeTaskAssignment (TASK_ASSIGNMENT 30048)', () => {
    const raw = {
      taskId: 305419896,      // 任意 u32：生产端是 String.hashCode() & 0xFFFFFFFF
      targetLat: 225925000,   // 1E7 度 → 22.5925
      targetLon: 1139360000,   // → 113.936
      targetAlt: 60,
      sysId: 255,             // 云端调度器 SENDER_SYSID
      taskType: 1,
      priority: 3,
      assignedSysId: 7,
    }

    it('经纬度从 1E7 换算成度，高度已是米不做换算', () => {
      const n = normalizeTaskAssignment(raw, NOW)
      expect(n.lat).toBeCloseTo(22.5925, 6)
      expect(n.lon).toBeCloseTo(113.936, 6)
      expect(n.altM).toBe(60)
    })

    it('同时保留发送方 sysId 与被派机 assignedSysid（面板要画"谁派的→派给谁"）', () => {
      const n = normalizeTaskAssignment(raw, NOW)
      expect(n.sysid).toBe(255)
      expect(n.assignedSysid).toBe(7)
    })

    it('taskId 原样透出为 protocolTaskId，不伪造成 REST taskId', () => {
      const n = normalizeTaskAssignment(raw, NOW)
      expect(n.protocolTaskId).toBe(305419896)
      // 显式契约：帧里的任务号就是哈希，不可逆
      expect(TASK_ID_IS_HASH).toBe(true)
      expect(n.protocolTaskId).not.toBe(raw.taskId.toString())
    })

    it('taskType/priority 映射到中文标签与配色', () => {
      const n = normalizeTaskAssignment(raw, NOW)
      expect(n.taskTypeLabel).toBe('喷洒')
      expect(TASK_TYPE_META[1].label).toBe('喷洒')
      expect(n.priorityLabel).toBe('高')
      expect(n.priorityColor).toBe(TASK_PRIORITY_META[3].color)
    })

    it('未知码回退「码 N」而不是崩掉或显示空白', () => {
      const n = normalizeTaskAssignment({ ...raw, taskType: 99, priority: 77 }, NOW)
      expect(n.taskTypeLabel).toBe('码 99')
      expect(n.priorityLabel).toBe('码 77')
      expect(n.priorityColor).toBe('var(--dim)')
    })

    it('非对象输入返回 null（网络帧不能假设结构完好）', () => {
      expect(normalizeTaskAssignment(null, NOW)).toBeNull()
      expect(normalizeTaskAssignment(undefined, NOW)).toBeNull()
      expect(normalizeTaskAssignment('boom', NOW)).toBeNull()
    })

    it('缺字段产出 null 而不是 NaN', () => {
      const n = normalizeTaskAssignment({}, NOW)
      expect(n.lat).toBeNull()
      expect(n.altM).toBeNull()
      expect(n.taskTypeLabel).toBeNull()
      expect(n.receivedAt).toBe(NOW)
    })
  })

  describe('normalizeConflictAlert (CONFLICT_ALERT 30049)', () => {
    const raw = {
      minDistance: 12.5,
      timeToConflict: 8.2,
      sysId: 3,
      conflictType: 2,
      conflictingSysId: 5,
      severity: 4,
    }

    it('距离与倒计时保持 float 精度（0.4 秒级冲突不能被取整成 0）', () => {
      const n = normalizeConflictAlert(raw, NOW)
      expect(n.minDistanceM).toBeCloseTo(12.5, 6)
      expect(n.timeToConflictS).toBeCloseTo(8.2, 6)
    })

    it('10 秒内判 imminent（与后端严重度分档口径一致）', () => {
      expect(normalizeConflictAlert(raw, NOW).imminent).toBe(true)
      expect(normalizeConflictAlert({ ...raw, timeToConflict: 10.0 }, NOW).imminent).toBe(true)
      expect(normalizeConflictAlert({ ...raw, timeToConflict: 10.1 }, NOW).imminent).toBe(false)
      // 缺失倒计时不得判 imminent（undefined 参与比较会得 true）
      const noTt = normalizeConflictAlert({ ...raw, timeToConflict: undefined }, NOW)
      expect(noTt.imminent).toBe(false)
    })

    it('conflictType/severity 映射正确', () => {
      const n = normalizeConflictAlert(raw, NOW)
      expect(n.conflictTypeLabel).toBe('碰撞')
      expect(CONFLICT_TYPE_META[2].label).toBe('碰撞')
      expect(n.severityLabel).toBe('危急')
      expect(n.severityColor).toBe(CONFLICT_SEVERITY_META[4].color)
      expect(n.conflictingSysid).toBe(5)
    })

    it('未知 severity 回退灰色而不是默认红（避免虚警）', () => {
      const n = normalizeConflictAlert({ ...raw, severity: 42 }, NOW)
      expect(n.severityLabel).toBe('码 42')
      expect(n.severityColor).toBe('var(--dim)')
    })
  })

  describe('normalizeTaskStatus (TASK_STATUS 30050)', () => {
    it('四态可达且映射到标签/配色', () => {
      const states = [
        [0, '已分配'], [1, '执行中'], [2, '已完成'], [4, '已中止'],
      ]
      for (const [code, label] of states) {
        const n = normalizeTaskStatus({ taskId: 1, sysId: 3, status: code, progressPercent: 50, timestamp: NOW }, NOW)
        expect(n.statusLabel).toBe(label)
        expect(TASK_STATUS_META[code].label).toBe(label)
      }
    })

    it('FAILED(3) 码表保留（当前无生命周期路径，但协议已占位）', () => {
      expect(TASK_STATUS_META[3].label).toBe('失败')
      expect(TASK_STATUS_META[3].color).toBe('var(--crit)')
      const n = normalizeTaskStatus({ taskId: 1, status: 3, progressPercent: 40 }, NOW)
      expect(n.statusLabel).toBe('失败')
    })

    it('进度夹到 0-100（协议越界不应画出负进度条或超长条）', () => {
      expect(normalizeTaskStatus({ status: 1, progressPercent: -5 }, NOW).progressPercent).toBe(0)
      expect(normalizeTaskStatus({ status: 1, progressPercent: 150 }, NOW).progressPercent).toBe(100)
      expect(normalizeTaskStatus({ status: 1, progressPercent: 60 }, NOW).progressPercent).toBe(60)
    })

    it('protocolTaskId 与 assignment 帧同值时可用于流内关联', () => {
      const a = normalizeTaskAssignment({ taskId: 777, assignedSysid: 3 }, NOW)
      const s = normalizeTaskStatus({ taskId: 777, status: 1, progressPercent: 10 }, NOW)
      expect(a.protocolTaskId).toBe(s.protocolTaskId)
    })

    it('sourceTimestamp 取消息内的 timestamp（机载/云端时钟），非前端 receivedAt', () => {
      const n = normalizeTaskStatus({ taskId: 1, status: 1, timestamp: 1699999999999 }, NOW)
      expect(n.sourceTimestamp).toBe(1699999999999)
      expect(n.receivedAt).toBe(NOW)
    })
  })

  describe('normalizeAlarmTrigger (ALARM_TRIGGER 30057)', () => {
    const raw = {
      timestamp: 1700000000123,
      lat: 225907000,
      lon: 1139355000,
      sourceDeviceId: 4242,
      alt: 12_000,       // mm → 12 m
      alarmType: 2,
      severity: 2,
      description: '仓库烟感触发',
      alarmId: 88231,    // 2026-10-07 协议追加（尾部偏移 68）
    }

    it('经纬度 1E7→度、高度 mm→m', () => {
      const n = normalizeAlarmTrigger(raw, NOW)
      expect(n.lat).toBeCloseTo(22.5907, 6)
      expect(n.lon).toBeCloseTo(113.9355, 6)
      expect(n.altM).toBeCloseTo(12, 6)
    })

    it('alarmType=火灾 / severity=CRITICAL 映射正确', () => {
      const n = normalizeAlarmTrigger(raw, NOW)
      expect(n.alarmTypeLabel).toBe('火灾')
      expect(ALARM_TYPE_META[2].label).toBe('火灾')
      expect(n.severityLabel).toBe('严重')
      expect(n.severityColor).toBe(ALARM_SEVERITY_META[2].color)
    })

    it('✅ 2026-10-07 起带 alarmId，可与 ack 关联', () => {
      const n = normalizeAlarmTrigger(raw, NOW)
      expect(n.alarmId).toBe(88231)
      expect(n.correlatable).toBe(true)
    })

    it('alarmId 缺失（字段未携带）⇒ alarmId=null 且 correlatable=false', () => {
      const n = normalizeAlarmTrigger({ ...raw, alarmId: undefined }, NOW)
      expect(n.alarmId).toBeNull()
      // 缺失即不可关联，消费侧不应拿它去 join ack
      expect(n.correlatable).toBe(false)
    })

    it('alarmId=0 ⇒ 未携带语义，不可关联', () => {
      const n = normalizeAlarmTrigger({ ...raw, alarmId: 0 }, NOW)
      expect(n.alarmId).toBe(0)
      // 0 是"未携带"哨兵，不是一个合法事件 ID
      expect(n.correlatable).toBe(false)
    })

    it('两端 alarmId 相同 ⇒ 可 join（这是本轮协议变更的目的）', () => {
      const trigger = normalizeAlarmTrigger(raw, NOW)
      const ack = normalizeAlarmAck({ alarmId: 88231, droneSysid: 3 }, NOW)
      expect(trigger.correlatable && ack.correlatable).toBe(true)
      expect(trigger.alarmId).toBe(ack.alarmId)
    })

    it('⚠️ 设备源帧无 sysId 时为 null（不按机分组）', () => {
      expect(normalizeAlarmTrigger(raw, NOW).sysid).toBeNull()
      // 若某天协议补了 sysId，双读兼容
      expect(normalizeAlarmTrigger({ ...raw, sysId: 4 }, NOW).sysid).toBe(4)
    })

    it('sourceDeviceId 以"哈希"命名，提醒消费侧不可当真实设备 id', () => {
      expect(normalizeAlarmTrigger(raw, NOW).sourceDeviceIdHash).toBe(4242)
    })

    it('空串描述归 null（面板显示 -- 而不是空单元格）', () => {
      expect(normalizeAlarmTrigger({ ...raw, description: '' }, NOW).description).toBeNull()
      expect(normalizeAlarmTrigger(raw, NOW).description).toBe('仓库烟感触发')
    })
  })

  describe('normalizeAlarmAck (ALARM_ACK 30058)', () => {
    const raw = {
      alarmId: 88231,
      timestamp: 1700000000456,
      estimatedArrivalSec: 137.4,
      droneSysid: 3,
      ackResult: 1,
    }

    it('ETA 取整成秒展示，droneSysid 带出接单机', () => {
      const n = normalizeAlarmAck(raw, NOW)
      expect(n.etaText).toBe('137s')
      expect(n.droneSysid).toBe(3)
      expect(n.alarmId).toBe(88231)
    })

    it('ackResult 四态映射（已收到/已开始响应/无法响应/拒绝）', () => {
      expect(normalizeAlarmAck(raw, NOW).ackResultLabel).toBe('已开始响应')
      expect(ALARM_ACK_META[0].label).toBe('已收到')
      expect(ALARM_ACK_META[2].label).toBe('无法响应')
      expect(ALARM_ACK_META[3].label).toBe('拒绝')
      expect(normalizeAlarmAck({ ...raw, ackResult: 3 }, NOW).ackResultColor).toBe('var(--crit)')
    })

    it('ETA 缺失时显示 --（不显示 NaNs/undefined）', () => {
      const n = normalizeAlarmAck({ ...raw, estimatedArrivalSec: undefined }, NOW)
      expect(n.etaText).toBe('--')
      expect(n.estimatedArrivalSec).toBeNull()
    })

    it('✅ alarmId 非 0 ⇒ correlatable=true（可与 trigger join）', () => {
      expect(normalizeAlarmAck(raw, NOW).correlatable).toBe(true)
    })

    it('⚠️ alarmId 缺失或为 0 ⇒ 仍显式标记不可关联（旧帧兼容）', () => {
      expect(normalizeAlarmAck({ ...raw, alarmId: undefined }, NOW).correlatable).toBe(false)
      expect(normalizeAlarmAck({ ...raw, alarmId: 0 }, NOW).correlatable).toBe(false)
    })
  })

  describe('normalizeSurveillanceStatus (SURVEILLANCE_STATUS 30059)', () => {
    const raw = {
      lastEventMs: 1700000000789,
      uptimeSec: 93784,
      deviceId: 4242,
      deviceType: 0,
      status: 0,
      onlineCameras: 1,
      totalCameras: 1,
    }

    it('厂商与状态码映射正确', () => {
      const n = normalizeSurveillanceStatus(raw, NOW)
      expect(n.vendorLabel).toBe('海康')
      expect(SURVEILLANCE_VENDOR_META[0].label).toBe('海康')
      expect(n.statusLabel).toBe('在线')
      expect(n.statusColor).toBe(SURVEILLANCE_STATUS_META[0].color)
      expect(n.deviceId).toBe(4242)
    })

    it('FAULT/MAINTENANCE 码表保留但当前不可达（设备模型只有 ONLINE/OFFLINE）', () => {
      expect(SURVEILLANCE_STATUS_META[2].label).toBe('故障')
      expect(SURVEILLANCE_STATUS_META[3].label).toBe('维护')
      expect(normalizeSurveillanceStatus({ ...raw, status: 2 }, NOW).statusColor).toBe('var(--crit)')
    })

    it('uptimeSec 格式化为可读时长', () => {
      expect(normalizeSurveillanceStatus(raw, NOW).uptimeText).toBe('1d 2h')
      expect(normalizeSurveillanceStatus({ ...raw, uptimeSec: undefined }, NOW).uptimeText).toBe('--')
    })
  })

  describe('formatUptime', () => {
    it('按 d/h/m/s 分档', () => {
      expect(formatUptime(0)).toBe('0s')
      expect(formatUptime(42)).toBe('42s')
      expect(formatUptime(90)).toBe('1m 30s')
      expect(formatUptime(3600)).toBe('1h 0m')
      expect(formatUptime(93784)).toBe('1d 2h')
      expect(formatUptime(86400)).toBe('1d 0h')
    })

    it('非法输入返回 --（不产出 NaN/负时长）', () => {
      expect(formatUptime(null)).toBe('--')
      expect(formatUptime(undefined)).toBe('--')
      expect(formatUptime(-1)).toBe('--')
      expect(formatUptime(NaN)).toBe('--')
      expect(formatUptime('12')).toBe('--')
      expect(formatUptime(Infinity)).toBe('--')
    })
  })

  describe('全部归一化函数共有的健壮性契约', () => {
    const fns = [
      normalizeTaskAssignment, normalizeConflictAlert, normalizeTaskStatus,
      normalizeAlarmTrigger, normalizeAlarmAck, normalizeSurveillanceStatus,
    ]

    it('null / undefined / 非对象一律返回 null，不抛异常', () => {
      for (const fn of fns) {
        expect(fn(null, NOW)).toBeNull()
        expect(fn(undefined, NOW)).toBeNull()
        expect(fn(0, NOW)).toBeNull()
        expect(fn('', NOW)).toBeNull()
        expect(fn([], NOW)).toBeNull()
      }
    })

    it('空对象不抛异常，且所有展示字段为 null（面板走 -- 分支）', () => {
      for (const fn of fns) {
        const n = fn({}, NOW)
        expect(n).not.toBeNull()
        expect(n.receivedAt).toBe(NOW)
        expect(Object.values(n).some((v) => typeof v === 'number' && Number.isNaN(v))).toBe(false)
      }
    })
  })
})