package io.aerofleet.mavlink.security;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 重放攻击防护：按 linkId 跟踪最近时间戳，拒绝大幅回退的消息。
 * <p>
 * 判定逻辑：
 * <ul>
 *   <li>首次见到的 linkId → 以该 timestamp 为基准，返回 true</li>
 *   <li>timestamp ≥ last → 更新基准，返回 true</li>
 *   <li>回退 ≤ {@link #REPLAY_THRESHOLD_TICKS} → 允许但不更新基准，返回 true</li>
 *   <li>回退 > {@link #REPLAY_THRESHOLD_TICKS} → 返回 false（拒绝）</li>
 * </ul>
 */
public class TimestampTracker {

    /** 允许的时间戳回退阈值（tick） */
    public static final long REPLAY_THRESHOLD_TICKS = 500;

    private final ConcurrentHashMap<Integer, Long> lastTimestamps = new ConcurrentHashMap<>();

    /**
     * 检查指定链路的 timestamp 是否可接受。
     *
     * @param linkId    链路标识
     * @param timestamp 消息时间戳（tick）
     * @return true=接受，false=拒绝（疑似重放）
     */
    public boolean check(int linkId, long timestamp) {
        Long last = lastTimestamps.get(linkId);
        if (last == null) {
            // 首次见到该 linkId，以当前 timestamp 为基准
            lastTimestamps.put(linkId, timestamp);
            return true;
        }

        if (timestamp >= last) {
            // 正常递进或持平，更新基准
            lastTimestamps.put(linkId, timestamp);
            return true;
        }

        // 回退场景
        long rollback = last - timestamp;
        if (rollback <= REPLAY_THRESHOLD_TICKS) {
            // 小幅回退，允许但不更新基准
            return true;
        }

        // 大幅回退，疑似重放攻击
        return false;
    }
}