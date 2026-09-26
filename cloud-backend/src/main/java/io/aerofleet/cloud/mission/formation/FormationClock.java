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
 * 在 {@code FormationService} 内部按 1Hz 轮询 Leader 心跳时间戳推算 time_usec，
 * <b>零修改</b> {@code TelemetryIngestService}。
 *
 * 并发安全：
 *   - {@code formationClockUs} volatile long：心跳线程写、REST 线程读
 *   - {@code lastLeaderHeartbeatMs} volatile long：同上
 */
@Component
public class FormationClock {

    /** 5s 未更新视为基准失效。 */
    static final long CLOCK_STALE_MS = 5_000;

    /** 队内时间基准（Leader 心跳 time_usec，微秒）。 */
    private volatile long formationClockUs = 0;

    /** 最近一次 Leader 心跳到达的 epoch ms。 */
    private volatile long lastLeaderHeartbeatMs = 0;

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
     * 由 FormationService 按 1Hz 调用（零修改 TelemetryIngestService）。
     *
     * @param leaderSysid Leader 飞机 sysid（<= 0 表示无 Leader，跳过）
     */
    public void pollLeaderHeartbeat(int leaderSysid) {
        if (leaderSysid <= 0) {
            return;
        }
        DroneSnapshot snap = registry.get(leaderSysid);
        if (snap == null || !snap.online || snap.lastHeartbeatMs <= 0) {
            return;
        }
        // 从 epoch ms 推算 time_usec（微秒）：心跳时间戳 × 1000
        long timeUsec = snap.lastHeartbeatMs * 1000L;
        onLeaderHeartbeat(timeUsec);
    }
}