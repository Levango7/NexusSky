package io.aerofleet.cloud.mission.formation;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.springframework.stereotype.Component;

/**
 * 队内时钟同步（FR-05/FR-06，DFX 4.2 volatile 并发安全）。
 *
 * 跟踪 Leader 飞机心跳 {@code time_usec} 作为队内时间基准，提供统一相位起点。
 * 5s 未更新视为基准失效（{@link #CLOCK_STALE_MS}）。
 *
 * 集成策略（design.md §2.1.3.4）：{@code FormationClock} 注入 {@link DeviceRegistry}，
 * 读路径失效时按 Leader sysid 自愈重采（{@link #refreshFromRegistry(int)}），
 * <b>零修改</b> {@code TelemetryIngestService}。
 *
 * 并发安全：
 *   - {@code formationClockUs} volatile long：REST 线程读、注册表重采写
 *   - {@code lastLeaderHeartbeatMs} volatile long：同上
 *   - {@code leaderSysid} volatile int：创建时写、读路径读
 */
@Component
public class FormationClock {

    /** 5s 未更新视为基准失效。 */
    static final long CLOCK_STALE_MS = 5_000;

    /** 队内时间基准（Leader 心跳 time_usec，微秒）。 */
    private volatile long formationClockUs = 0;

    /** 最近一次 Leader 心跳到达的 epoch ms。 */
    private volatile long lastLeaderHeartbeatMs = 0;

    /** 最近一次轮询的 Leader sysid（<=0 = 未设置），供读路径自愈重采。 */
    private volatile int leaderSysid = -1;

    private final DeviceRegistry registry;

    public FormationClock(DeviceRegistry registry) {
        this.registry = registry;
    }

    /**
     * 更新队内时间基准（Leader 心跳到达时调用）。
     *
     * @param timeUsec Leader 心跳的 time_usec（微秒）
     */
    public void onLeaderHeartbeat(long timeUsec) {
        formationClockUs = timeUsec;
        lastLeaderHeartbeatMs = System.currentTimeMillis();
    }

    /**
     * 获取队内时间基准（微秒）；未建立（==0）或已失效（超过 5s）返回 -1。
     *
     * @return 队内时间基准（微秒），或 -1 表示未建立/已失效
     */
    public long formationClockUs() {
        if (formationClockUs == 0
                || System.currentTimeMillis() - lastLeaderHeartbeatMs > CLOCK_STALE_MS) {
            // 读路径自愈：Leader 心跳一直在流，但基准只在编队创建时采过一次，
            // 5s 后必然判失效 ⇒ sync=true 的灯光命令（FR-11）恒定 400。
            // 重采口径不变：仍是"Leader 最新心跳"，Leader 真离线时 registry
            // 给不出新心跳，基准照旧失效。
            refreshFromRegistry(leaderSysid);
        }
        if (formationClockUs == 0) {
            return -1;
        }
        if (System.currentTimeMillis() - lastLeaderHeartbeatMs > CLOCK_STALE_MS) {
            return -1;
        }
        return formationClockUs;
    }

    /**
     * 时钟基准是否已建立且有效。
     *
     * @return true 表示基准已建立且未失效
     */
    public boolean ready() {
        return formationClockUs() != -1;
    }

    /**
     * 从 DeviceRegistry 轮询 Leader 心跳时间戳，推算 time_usec 并更新基准。
     * 由 {@code FormationService.create()} 调用；读路径失效时也会自愈重采。
     *
     * @param leaderSysid Leader 飞机 sysid（<= 0 表示无 Leader，跳过）
     */
    public void pollLeaderHeartbeat(int leaderSysid) {
        this.leaderSysid = leaderSysid;
        refreshFromRegistry(leaderSysid);
    }

    /** 按记住的 Leader sysid 重采一次心跳；Leader 不在线时保持原基准不动。 */
    private void refreshFromRegistry(int leaderSysid) {
        if (leaderSysid <= 0) {
            return;
        }
        DroneSnapshot snap = registry.get(leaderSysid);
        if (snap == null || !snap.online || snap.lastHeartbeatMs <= 0) {
            return;
        }
        // 从 epoch ms 推算 time_usec（微秒）：心跳时间戳 × 1000
        onLeaderHeartbeat(snap.lastHeartbeatMs * 1000L);
    }
}