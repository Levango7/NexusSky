package io.aerofleet.cloud.mission.delivery;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.gateway.TelemetryIngestService;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.PayloadStatus;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配送按需载荷查询端到端（真链路）——清单 §二「30085 载荷查询全链路」的收敛验证。
 * <p>
 * 与 {@link DeliveryPayloadQueryTest}（mock 版）的分工：那边钉 REST 层的 404 / 命令参数 /
 * 失败降级；这边不打桩命令通道，走完整真实路径：
 * <pre>
 *   REST(DeliveryController.queryPayload) → DroneCommandService
 *     → UdpGateway(真 socket) → 真实 UDP → VirtualDrone 机载
 *     → handlePayloadQuery（gripper 非空才 ACCEPTED）→ 立即回传 PayloadStatus(30006)
 *     → COMMAND_ACK 回程 → PendingAcks → REST 返回 status=ok
 * </pre>
 * 装配与端口约定同 {@code EnvOverrideE2ETest}（见该测试 javadoc 的接线说明）。
 * <p>
 * 断言口径说明：REST ok 本身就要求机载真实回 ACCEPTED，即 {@code handlePayloadQuery}
 * 确实执行（gripper 为 null 时该 handler 返回 UNSUPPORTED，REST 会变 error）；
 * 30006 的存在另外证「机载回传生产者活着」。注意机载在 actuatorsEnabled 下每 1Hz 也会
 * 周期上报 30006，故本测试不去比较"查询前后帧数"，那会与周期上报混淆、变成脆弱断言。
 */
@DisplayName("DeliveryController 端到端：REST → 真 UDP → 机载 handlePayloadQuery → 回传 30006")
class DeliveryPayloadQueryE2ETest {

    /** 云端网关绑定端口（避开 EnvOverrideE2E 的 24840/24841 与既有测试段）。 */
    private static final int GATEWAY_PORT = 24850;
    /** 机载 VirtualDrone 绑定端口。 */
    private static final int DRONE_PORT = 24851;
    private static final int DRONE_SYSID = 1;
    private static final long WAIT_MS = 15_000;

    private UdpGateway gateway;
    private VirtualDrone drone;
    private DeliveryController controller;
    private final List<MavlinkMessageEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        events.clear();
        PendingAcks pendings = new PendingAcks();

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

        // --actuators：gripper 非空，handlePayloadQuery 才不是 UNSUPPORTED（30085 的机载前置）
        drone = new VirtualDrone(SimConfig.parse(new String[]{
                "--port=" + DRONE_PORT, "--failsafe=off", "--actuators"}));
        drone.start();

        DeviceRegistry registry = new DeviceRegistry();
        registry.provision(DRONE_SYSID, null);
        DroneCommandService commands = new DroneCommandService(gateway, pendings, registry, DRONE_SYSID);

        // 配送单只是数据源（REST 入参 → 目标 sysid），非被测链路，故按单测惯例打桩
        DeliveryService deliveryService = mock(DeliveryService.class);
        when(deliveryService.sequence(1)).thenReturn(new DeliverySequence(1, DRONE_SYSID, List.of()));
        controller = new DeliveryController(deliveryService, commands);

        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (gateway.routeOf(DRONE_SYSID) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(gateway.routeOf(DRONE_SYSID))
                .as("网关未学到机载路由 sysid=" + DRONE_SYSID).isNotNull();
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

    @Test
    @DisplayName("按需查询：REST ok（真 ACK 往返）+ 机载回传 PayloadStatus(30006) 到云端")
    void queryPayload_endToEnd_onboardReplies30006() throws Exception {
        Map<String, Object> resp = controller.queryPayload(1);

        assertThat(resp.get("status"))
                .as("REST 应返回 ok —— 机载 handlePayloadQuery 只有在 gripper 装配时回 ACCEPTED")
                .isEqualTo("ok");
        assertThat(resp.get("sysid")).isEqualTo(DRONE_SYSID);

        // 30006 的真实生产者（机载 sendPayloadStatus）必须在这条 UDP 回程上被云端解出
        long deadline = System.currentTimeMillis() + WAIT_MS;
        boolean sawPayloadStatus = false;
        while (System.currentTimeMillis() < deadline && !sawPayloadStatus) {
            sawPayloadStatus = events.stream()
                    .anyMatch(e -> e.getMessage() instanceof PayloadStatus);
            if (!sawPayloadStatus) {
                Thread.sleep(50);
            }
        }
        assertThat(sawPayloadStatus)
                .as("未在云端收到机载 PayloadStatus(30006)：机载回传生产者未接线")
                .isTrue();
    }
}