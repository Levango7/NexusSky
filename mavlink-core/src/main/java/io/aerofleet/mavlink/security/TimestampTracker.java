package io.aerofleet.mavlink.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 签名重放防护：按官方规则校验每条已签名消息的时间戳。
 * <p>
 * 官方口径（与 pymavlink {@code check_signature}、MAVLink C 库一致）：
 * <ul>
 *   <li><b>流标识是三元组</b> {@code (linkId, systemId, componentId)}——同一 linkId 下不同
 *       源设备各自独立计时，只按 linkId 分桶会把两台机的时间戳混在一起误判；</li>
 *   <li>已见过的流：{@code timestamp} 必须<b>严格大于</b>上次值，相等即拒
 *       （旧实现用 {@code >=} 放行相等值，等于允许原帧重放一次）；</li>
 *   <li>新出现的流：允许落后本地时间<b>最多 60 秒</b>（48 位时间戳以 10 微秒为单位，
 *       即 {@link MavlinkSigner#REPLAY_WINDOW_TICKS} = 6,000,000 tick）。旧实现的阈值是
 *       500 tick，且单位口径都不对；</li>
 *   <li>48 位范围外的时间戳直接拒。</li>
 * </ul>
 * 时钟通过 {@link LongSupplier} 注入，便于把 60 秒窗口写成确定性测试。
 */
public class TimestampTracker {

    /** 流标识：linkId + 源系统 ID + 源组件 ID。 */
    public record StreamKey(int linkId, int systemId, int componentId) {
    }

    private final ConcurrentHashMap<StreamKey, Long> lastTimestamps = new ConcurrentHashMap<>();

    /** 本地"当前签名时间戳"来源；默认取系统时钟换算的官方 tick。 */
    private final LongSupplier nowSupplier;

    public TimestampTracker() {
        this(MavlinkSigner::currentSigningTimestamp);
    }

    /**
     * 指定本地时钟（主要用于测试：把"现在"固定住，60 秒重放窗口才可确定性验证）。
     *
     * @param nowSupplier 返回当前 10 微秒 tick 的供给器
     */
    public TimestampTracker(LongSupplier nowSupplier) {
        this.nowSupplier = nowSupplier;
    }

    /**
     * 校验某条签名消息的时间戳是否可接受。
     *
     * @param linkId    签名块里的链路 ID
     * @param systemId  帧源系统 ID
     * @param componentId 帧源组件 ID
     * @param timestamp 签名块里的 48 位时间戳（10 微秒单位）
     * @return true=接受（并已记入该流基准），false=拒绝（疑似重放）
     */
    public boolean check(int linkId, int systemId, int componentId, long timestamp) {
        if (timestamp < 0 || timestamp > MavlinkSigner.MAX_TIMESTAMP_48) {
            return false;
        }
        StreamKey key = new StreamKey(linkId, systemId, componentId);
        long now = nowSupplier.getAsLong();
        Long last = lastTimestamps.get(key);

        if (last != null) {
            if (timestamp <= last) {
                // 官方要求严格递增：相等或回退都按重放处理
                return false;
            }
        } else if (timestamp + MavlinkSigner.REPLAY_WINDOW_TICKS < now) {
            // 新流：落后本地时间超过 60 秒不接受
            return false;
        }

        lastTimestamps.put(key, timestamp);
        return true;
    }

    /** 该流当前记录的时间戳基准；未知流返回 null（用于测试与观测）。 */
    public Long lastKnown(StreamKey key) {
        return lastTimestamps.get(key);
    }

    /** 当前跟踪的流数量。 */
    public int trackedStreams() {
        return lastTimestamps.size();
    }

    /** 清空所有流的基准（链路重建/测试隔离用）。 */
    public void reset() {
        lastTimestamps.clear();
    }
}
