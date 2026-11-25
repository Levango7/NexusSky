package io.aerofleet.cloud.gateway;

import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.MissionAckMsg;
import io.aerofleet.mavlink.messages.MissionCountMsg;
import io.aerofleet.mavlink.messages.MissionRequest;
import io.aerofleet.mavlink.messages.MissionRequestInt;
import io.aerofleet.mavlink.messages.MissionRequestList;
import io.aerofleet.mavlink.messages.Statustext;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 sysid 滑动窗口频率限制器（从 {@link UdpGateway} 抽取的独立横切组件）。
 *
 * <p>职责边界：只管「某 sysid 在最近 1 秒内的帧数是否超限」这一件事，与
 * transport/ingest/路由表/签名零耦合。三条互不相通的预算：
 * <ul>
 *   <li><b>主窗口</b>（{@code aerofleet.udp.max-frame-rate-per-sysid}，默认 100/s）：
 *       入站非关键帧的准入。</li>
 *   <li><b>关键帧独立预算</b>（{@code aerofleet.udp.max-critical-frame-rate-per-sysid}，
 *       默认 50/s）：ACK/心跳/任务协议回执等低频控制面帧，避免高码率流在每秒开头
 *       把主窗口吃满导致关键帧成批被丢（SITL 实测 340k+ 条，docs/sitl-integration.md
 *       §12.2-⑤）。关键帧只查本预算、不占主窗口；预算 {@code <=0} 时豁免关闭、
 *       关键帧退回主窗口（旧行为）。</li>
 *   <li><b>出站定向广播预算</b>（复用主窗口上限）：广播把一帧放大成 N 帧
 *       （N = 已知实例数），不限就等于把上限乘以链路数；落在广播路径上 ⇒ 每个实例
 *       实得「总配额 / 实例数」（按实例摊分，总帧数不放大，§12.4 判据 2）。</li>
 * </ul>
 *
 * <p>线程语义与原 {@code UdpGateway} 完全一致：map 用 {@link ConcurrentHashMap}，
 * 每个 sysid 的窗口队列用 {@code synchronized(window)} 保护（与原实现逐字相同）。
 */
public class FrameRateLimiter {

    /** 关键帧判定集：GCS 功能依赖其「必达」的低频控制面帧。 */
    private static final Set<Integer> CRITICAL_FRAME_MSGIDS = Set.of(
            CommandAck.ID, MissionAckMsg.ID, MissionRequest.ID, MissionRequestInt.ID,
            MissionCountMsg.ID, MissionRequestList.ID, Heartbeat.ID, Statustext.ID);

    /** 主窗口上限（每 sysid 每秒）。 */
    private final int maxFrameRatePerSysid;

    /** 关键帧独立预算（每 sysid 每秒）；<=0 关闭豁免（关键帧退回主窗口，旧行为）。 */
    private int maxCriticalFrameRatePerSysid = 50;

    /** 主窗口：key=sysid, value=最近1秒内的时间戳队列。 */
    private final ConcurrentHashMap<Integer, Deque<Long>> rateLimitWindows = new ConcurrentHashMap<>();
    /** 关键帧独立预算窗口：key=sysid。 */
    private final ConcurrentHashMap<Integer, Deque<Long>> criticalRateWindows = new ConcurrentHashMap<>();
    /** 出站定向广播预算窗口：key=sysid。 */
    private final ConcurrentHashMap<Integer, Deque<Long>> broadcastRateWindows = new ConcurrentHashMap<>();

    public FrameRateLimiter(int maxFrameRatePerSysid) {
        this.maxFrameRatePerSysid = maxFrameRatePerSysid;
    }

    /** 设置关键帧预算（{@code @Value} 注入由 {@link UdpGateway} 转发；测试亦可直接注入）。 */
    void setMaxCriticalFrameRatePerSysid(int maxCriticalFrameRatePerSysid) {
        this.maxCriticalFrameRatePerSysid = maxCriticalFrameRatePerSysid;
    }

    int maxFrameRate() {
        return maxFrameRatePerSysid;
    }

    int maxCriticalFrameRate() {
        return maxCriticalFrameRatePerSysid;
    }

    /** 该 msgId 是否走关键帧独立预算（预算关闭时返回 false，退回主窗口）。 */
    boolean isCritical(int msgId) {
        return maxCriticalFrameRatePerSysid > 0 && CRITICAL_FRAME_MSGIDS.contains(msgId);
    }

    /**
     * 入站帧准入：关键帧走独立小预算，非关键帧走主窗口。
     *
     * @return true=允许通过, false=超限需丢弃
     */
    boolean allowIngest(int sysid, int msgId) {
        return isCritical(msgId) ? criticalRateAllow(sysid) : rateLimitAllow(sysid);
    }

    /** 出站定向广播准入（预算按 sysid 计、按实例摊分）。 */
    boolean allowOutboundBroadcast(int sysid) {
        return outboundBroadcastAllow(sysid);
    }

    /** 滑动窗口：检查 sysid 在最近 1 秒内的帧数是否超主窗口上限。 */
    private boolean rateLimitAllow(int sysid) {
        long now = System.currentTimeMillis();
        Deque<Long> window = rateLimitWindows.computeIfAbsent(sysid, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 1000) {
                window.pollFirst();
            }
            if (window.size() >= maxFrameRatePerSysid) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** 关键帧独立预算：与主窗口同型的滑动窗口，但配额独立。 */
    private boolean criticalRateAllow(int sysid) {
        long now = System.currentTimeMillis();
        Deque<Long> window = criticalRateWindows.computeIfAbsent(sysid, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 1000) {
                window.pollFirst();
            }
            if (window.size() >= maxCriticalFrameRatePerSysid) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** 出站定向广播预算：上限复用主窗口（按实例摊分，总帧数不放大）。 */
    private boolean outboundBroadcastAllow(int sysid) {
        long now = System.currentTimeMillis();
        Deque<Long> window = broadcastRateWindows.computeIfAbsent(sysid, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 1000) {
                window.pollFirst();
            }
            if (window.size() >= maxFrameRatePerSysid) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** 路由老化时清理广播窗口（key 与路由同生命周期）。 */
    void pruneBroadcastWindows(Collection<Integer> deadSysids) {
        if (deadSysids.isEmpty()) {
            return;
        }
        Set<Integer> dead = new HashSet<>(deadSysids);
        broadcastRateWindows.keySet().removeAll(dead);
    }

    /**
     * 限速窗口过期清理：移除长时间无活动的 sysid 窗口，防止内存泄漏。
     * 主窗口、关键帧预算窗口与出站广播窗口同一批清理。
     *
     * @param cutoffMs 早于此毫秒时间戳的窗口视为过期
     * @return 清理的窗口数量（用于日志）
     */
    int pruneStaleWindows(long cutoffMs) {
        int before = rateLimitWindows.size() + criticalRateWindows.size() + broadcastRateWindows.size();
        rateLimitWindows.entrySet().removeIf(entry -> isStale(entry.getValue(), cutoffMs));
        criticalRateWindows.entrySet().removeIf(entry -> isStale(entry.getValue(), cutoffMs));
        broadcastRateWindows.entrySet().removeIf(entry -> isStale(entry.getValue(), cutoffMs));
        return before - rateLimitWindows.size() - criticalRateWindows.size() - broadcastRateWindows.size();
    }

    private static boolean isStale(Deque<Long> window, long cutoffMs) {
        synchronized (window) {
            return window.isEmpty() || window.peekLast() < cutoffMs;
        }
    }
}
