package io.aerofleet.cloud.telemetry;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.gateway.TelemetryIngestService;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.EnvironmentStatus;
import io.aerofleet.sim.SimConfig;
import io.aerofleet.sim.VirtualDrone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环境覆写端到端（真链路）——清单 §二「30080-30082 环境覆盖完整性」的收敛验证。
 * <p>
 * 与 {@link EnvOverrideControllerTest}（mock 版）的分工：那边钉 REST 层校验与命令参数
 * 透传；这边**不打桩**，走完整真实路径：
 * <pre>
 *   REST(EnvOverrideController) → DroneCommandService
 *     → UdpGateway(真 socket，255/GCS 源) → 真实 UDP →
 *     VirtualDrone 机载校验（handleEnvSetWind/Weather/Thresholds）
 *     → COMMAND_ACK 回程 UDP → TelemetryIngestService 解码 → MavlinkMessageEvent
 *     → PendingAcks 完成 future → REST 返回 status=ok
 * </pre>
 * 并且用机载 1Hz 周期下传的 {@link EnvironmentStatus}（msgId 30002）证「机载状态真的变了」，
 * 而不是只证「命令发出去了」。
 * <p>
 * 装配说明（为什么这样接线）：
 * <ul>
 *   <li>事件总线用一个 lambda 代替 Spring：真实 {@link TelemetryIngestService} 只做
 *       「解码 + publishEvent」，lambda 把 {@link CommandAck} 事件转投
 *       {@link PendingAcks#onCommandAck}（生产环境由 {@code @EventListener} 完成同一件事）；
 *       其余事件落进 {@link #events} 供断言取用。除事件总线外无任何桩件。</li>
 *   <li>路由：网关构造时对 dronePort 开主动发现（QGroundControl 式握手），机载收到后
 *       把网关记为 lastPeer 并回传遥测 —— 网关据此学到 sysid→地址路由，
 *       {@code DroneCommandService} 的按 sysid 定向发送才成立。</li>
 *   <li>注册表：{@link DroneCommandService#resolveTarget} 拒绝未注册 sysid，
 *       故显式 provision（白名单关闭，与 dev 口径一致）。</li>
 * </ul>
 * <p>
 * 端口取 24840/24841，避开 UdpGatewayTest(24600+)、Lifecycle(24700+)、
 * NexusCommandDispatch(24750)、Budget(24590+) 的既有测试段。
 */
@DisplayName("EnvOverrideController 端到端：REST → 真 UDP → 机载校验 → 状态回传")
class EnvOverrideE2ETest {

    /** 云端网关绑定端口。 */
    private static final int GATEWAY_PORT = 24840;
    /** 机载 VirtualDrone 绑定端口（也是网关主动发现的目标端口）。 */
    private static final int DRONE_PORT = 24841;
    /** 机载 sysid（SimConfig 默认 1）。 */
    private static final int DRONE_SYSID = 1;
    /** 等待既成事实的最长时间（路由学习 / 1Hz 遥测）。 */
    private static final long WAIT_MS = 15_000;

    private UdpGateway gateway;
    private VirtualDrone drone;
    private EnvOverrideController controller;

    /** 全量解码事件（真 TelemetryIngestService 产出），供断言取用。 */
    private final List<MavlinkMessageEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        events.clear();
        PendingAcks pendings = new PendingAcks();

        // 事件总线替身：只做「分发」这一件事，路由规则与 @EventListener 等价
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof MavlinkMessageEvent e) {
                events.add(e);
                if (e.getMessage() instanceof CommandAck) {
                    pendings.onCommandAck(e);
                }
            }
        };
        TelemetryIngestService ingest = new TelemetryIngestService(publisher);

        gateway = new UdpGateway(GATEWAY_PORT, "127.0.0.1", DRONE_PORT, "",
                "127.0.0.1", false, 100, ingest, pendings);

        drone = new VirtualDrone(SimConfig.parse(new String[]{
                "--port=" + DRONE_PORT, "--failsafe=off", "--env"}));
        drone.start();

        DeviceRegistry registry = new DeviceRegistry();
        registry.provision(DRONE_SYSID, null);
        controller = new EnvOverrideController(
                new DroneCommandService(gateway, pendings, registry, DRONE_SYSID));

        // 等网关学到机载路由（发现握手 → 机载回传遥测 → 路由表写入）
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (gateway.routeOf(DRONE_SYSID) == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(gateway.routeOf(DRONE_SYSID))
                .as("网关未学到机载路由 sysid=" + DRONE_SYSID
                        + "（发现握手失败或机载未回传，后续按 sysid 定向发送会退化）")
                .isNotNull();
    }

    @AfterEach
    void tearDown() {
        if (drone != null) {
            drone.close();
            drone = null;
        }
        if (gateway != null) {
            gateway.shutdown();
            gateway = null;
        }
    }

    // ===== 用例 =====

    @Test
    @DisplayName("风场覆写：REST ok（真 ACK 往返）+ 机载 ENVIRONMENT_STATUS 风向变为 270°")
    void setWind_endToEnd_onboardDirectionOverridden() throws Exception {
        // 先拿到一帧覆写前的机载状态（证链路已活）
        EnvironmentStatus before = awaitEnvStatus(0);
        assertThat(before).as("未收到机载 1Hz ENVIRONMENT_STATUS(30002)").isNotNull();

        int fromIndex = events.size();
        Map<String, Object> resp = controller.setWind(DRONE_SYSID,
                Map.of("speedMps", 8.5, "directionDeg", 270));

        assertThat(resp.get("status"))
                .as("REST 应返回 ok —— 该结果只有在机载真实回 COMMAND_ACK(ACCEPTED) 时才成立")
                .isEqualTo("ok");

        EnvironmentStatus after = awaitEnvStatus(fromIndex);
        assertThat(after).as("覆写后未收到新的机载 ENVIRONMENT_STATUS").isNotNull();
        // 场景基线风向为 0，覆写目标 270°；机载风向按 current 慢漂移（每 tick 高斯×5×dt），
        // 数秒内偏移约 1°，故取 ±5°（cdeg：26500-27500）判定，避开 0/360 环绕区。
        assertThat(after.windDirection)
                .as("机载风向应被覆写为 270°（±5°），实际 cdeg=" + after.windDirection)
                .isBetween(26_500, 27_500);
    }

    @Test
    @DisplayName("天气覆写：REST ok（机载校验通过并回 ACCEPTED）")
    void setWeather_endToEnd_onboardAccepted() throws Exception {
        Map<String, Object> resp = controller.setWeather(DRONE_SYSID,
                Map.of("weatherCode", 2, "rainRate", 30));

        // 不断言 ENVIRONMENT_STATUS.weather 的取值：机载天气按场景概率逐 tick 转移
        // （EnvScenario.weatherStability），稳态值本身就是随机的，断言它等于 2 会 flaky。
        // 真正的证据是 ACK=ACCEPTED —— 机载 handleEnvSetWeather 校验通过并已 overrideWeather。
        assertThat(resp.get("status")).isEqualTo("ok");
    }

    @Test
    @DisplayName("阈值覆写：REST ok（机载校验通过并回 ACCEPTED）")
    void setThresholds_endToEnd_onboardAccepted() throws Exception {
        Map<String, Object> resp = controller.setThresholds(DRONE_SYSID,
                Map.of("windWarnMps", 8, "windCritMps", 12));

        // 阈值的作用面是机载告警引擎（EnvAlert 触发），不在周期状态消息里；
        // 以 ACK=ACCEPTED 证「机载 handler 校验通过并已 overrideThresholds」。
        assertThat(resp.get("status")).isEqualTo("ok");
    }

    // ===== 工具 =====

    /**
     * 自 {@code fromIndex} 起等待一帧机载 ENVIRONMENT_STATUS。
     *
     * @return 命中的状态消息；超时返回 null（调用方按未达记失败）
     */
    private EnvironmentStatus awaitEnvStatus(int fromIndex) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            List<MavlinkMessageEvent> snapshot = List.copyOf(events);
            for (int i = fromIndex; i < snapshot.size(); i++) {
                if (snapshot.get(i).getMessage() instanceof EnvironmentStatus es) {
                    return es;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }
}