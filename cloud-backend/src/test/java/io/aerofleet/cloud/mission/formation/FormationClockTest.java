package io.aerofleet.cloud.mission.formation;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队内时钟基准的读路径自愈守卫（FR-11 灯光同步的前置条件）。
 *
 * <p>缺陷背景：{@code FormationService.create()} 只在创建编队时采过一次 Leader
 * 心跳，之后没有任何刷新路径。CLOCK_STALE_MS=5s 一过，{@code formationClockUs()}
 * 恒返回 -1 ⇒ `ready()` 恒 false ⇒ sync=true 的灯光命令（FR-11）必被
 * BadRequest("formation clock not ready") 打回。e2e-formation 的灯光步实测
 * 400 就是这个原因：编队创建几十秒后才发文，基准早凉了。
 *
 * <p>修法：读路径失效时按记住的 Leader sysid 自愈重采。语义仍是「Leader 最新
 * 心跳」——Leader 真离线时 registry 给不出新心跳，基准照旧失效（本测试钉死
 * 这条边界，防止把"时钟永久有效"当修好）。
 */
@DisplayName("FormationClock：Leader 心跳 5s 后读路径自愈，Leader 离线照旧失效")
class FormationClockTest {

    private static final int LEADER = 1;

    private DeviceRegistry registry;
    private FormationClock clock;
    private DroneSnapshot leader;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
        clock = new FormationClock(registry);
        leader = registry.registerIfAbsent(LEADER);
        leader.online = true;
        leader.lastHeartbeatMs = System.currentTimeMillis();
    }

    @Test
    @DisplayName("创建后 5s 以上再读：自愈重采，仍就绪（FR-11 不再 400）")
    void staysReadyAfterStaleWindow() {
        clock.pollLeaderHeartbeat(LEADER);
        assertTrue(clock.ready(), "刚采过应就绪");

        // 把"上次采样时间"拨到 30s 前：等价于 create() 采完后过了半分钟才发文
        backdateLastSample(30_000);
        leader.lastHeartbeatMs = System.currentTimeMillis();

        assertTrue(clock.ready(),
                "Leader 心跳仍在流：读路径应自愈重采，而不是抱着过期采样判失效");
        assertTrue(clock.formationClockUs() > 0, "自愈后应给出新的队内时间基准");
    }

    @Test
    @DisplayName("Leader 离线：基准失效（自愈不会把死 Leader 的时钟救活）")
    void staleWhenLeaderOffline() {
        clock.pollLeaderHeartbeat(LEADER);
        backdateLastSample(30_000);
        leader.online = false;   // 掉线：registry 给不出新心跳

        assertFalse(clock.ready(), "Leader 离线时基准必须失效");
        assertEquals(-1L, clock.formationClockUs(), "离线时返回 -1");
    }

    @Test
    @DisplayName("从未轮询过 Leader：未建立，不就绪")
    void notReadyBeforeAnyPoll() {
        assertFalse(clock.ready(), "没有 Leader 轮询记录时不应就绪");
        assertEquals(-1L, clock.formationClockUs());

        // leaderSysid<=0（无 Leader）时自愈直接跳过
        clock.pollLeaderHeartbeat(0);
        backdateLastSample(30_000);
        assertFalse(clock.ready(), "leaderSysid<=0 时自愈跳过，仍不就绪");
    }

    /** 把内部"最近一次 Leader 心跳到达的 epoch ms"拨到过去，模拟长时间未采样。 */
    private void backdateLastSample(long msAgo) {
        try {
            java.lang.reflect.Field f = FormationClock.class
                    .getDeclaredField("lastLeaderHeartbeatMs");
            f.setAccessible(true);
            f.setLong(clock, System.currentTimeMillis() - msAgo);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
