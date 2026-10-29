package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.CommandInt;
import io.aerofleet.mavlink.messages.CommandLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 带位置命令的 COMMAND_INT 自动路由（P2-F2）。
 *
 * <p>真机契约：ArduPilot 对 {@code MAV_CMD_DO_REPOSITION} 用 COMMAND_LONG 发送一律回
 * {@code MAV_RESULT=8}（COMMAND_INT_ONLY；AP SITL 实测，与模式无关）。{@code command()} 对
 * {@link io.aerofleet.cloud.mission.common.DroneCommandService} 内登记的
 * {@code COMMAND_INT_REQUIRED} 集合自动改走 COMMAND_INT：p5/p6 作为 lat/lon 转 x/y*1e7、
 * p7 作为相对高 z、frame=GLOBAL_RELATIVE_ALT_INT。本测钉住：路由后的**线上报文与字段**、
 * ACK 关联（复用 COMMAND_ACK 通道）、非登记命令仍走 LONG 的回归、以及 NaN 经纬度的快速拒绝。
 */
class CommandIntRoutingTest {

    private final List<MavlinkFrame> sent = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private PendingAcks pendings;
    private DroneCommandService svc;

    @BeforeEach
    void setUp() throws Exception {
        UdpGateway gateway = mock(UdpGateway.class);
        doAnswer(inv -> {
            sent.add(inv.getArgument(1));
            return null;
        }).when(gateway).send(anyInt(), any(MavlinkFrame.class));
        pendings = new PendingAcks();
        DeviceRegistry registry = new DeviceRegistry();
        DroneSnapshot snap = registry.registerIfAbsent(1);
        snap.online = true;
        svc = new DroneCommandService(gateway, pendings, registry, 1);
    }

    @Test
    @DisplayName("DO_REPOSITION 自动改走 COMMAND_INT：线上 msgId=75、x/y=1e7 缩放、frame=REL_ALT_INT")
    void doRepositionRoutedAsCommandInt() throws Exception {
        float lat = -35.362813f;
        float lon = 149.165237f;
        float alt = 25f;

        Future<Integer> fut = executor.submit(
                () -> svc.command(1, MavEnums.MAV_CMD_DO_REPOSITION, 5f, 0f, 0f, 1.5f, lat, lon, alt));

        await().atMost(Duration.ofSeconds(5)).until(() -> !framesOf(CommandInt.ID).isEmpty());
        CommandInt ci = CommandInt.decode(framesOf(CommandInt.ID).get(0));
        assertThat(ci.command).isEqualTo(MavEnums.MAV_CMD_DO_REPOSITION);
        assertThat(ci.frame).isEqualTo(MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT);
        assertThat(ci.param1).isEqualTo(5f);
        assertThat(ci.param4).isEqualTo(1.5f);
        assertThat(ci.x).isEqualTo(Math.round(lat * 1e7));
        assertThat(ci.y).isEqualTo(Math.round(lon * 1e7));
        assertThat(ci.z).isEqualTo(alt);

        // ACK 关联复用既有 COMMAND_ACK 通道（按 command 号匹配，与报文格式无关）
        pendings.offer(CommandAck.ID,
                new CommandAck(MavEnums.MAV_CMD_DO_REPOSITION, MavEnums.MAV_RESULT_ACCEPTED,
                        0, 0, 255, 190), 1);
        assertThat(fut.get(5, TimeUnit.SECONDS)).isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
    }

    @Test
    @DisplayName("回归：非登记命令（DISARM）仍走 COMMAND_LONG（msgId=76）")
    void otherCommandsStayCommandLong() throws Exception {
        Future<Integer> fut = executor.submit(
                () -> svc.command(1, MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, 0f, 0f, 0f, 0f, 0f, 0f, 0f));

        await().atMost(Duration.ofSeconds(5)).until(() -> !framesOf(CommandLong.ID).isEmpty());
        assertThat(framesOf(CommandInt.ID)).isEmpty();
        CommandLong cl = CommandLong.decode(framesOf(CommandLong.ID).get(0));
        assertThat(cl.command).isEqualTo(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM);

        pendings.offer(CommandAck.ID,
                new CommandAck(MavEnums.MAV_CMD_COMPONENT_ARM_DISARM, MavEnums.MAV_RESULT_ACCEPTED,
                        0, 0, 255, 190), 1);
        assertThat(fut.get(5, TimeUnit.SECONDS)).isEqualTo(MavEnums.MAV_RESULT_ACCEPTED);
    }

    @Test
    @DisplayName("NaN 经纬度快速拒绝：不发帧、不消耗重试（COMMAND_INT 无法表达未知位置）")
    void nanLatLonRejectedBeforeSend() {
        assertThatThrownBy(() -> svc.command(1, MavEnums.MAV_CMD_DO_REPOSITION,
                0f, 0f, 0f, 0f, Float.NaN, 149.165237f, 25f))
                .isInstanceOf(DroneCommandService.CommandException.class)
                .hasMessageContaining("finite lat/lon");
        assertThat(sent).isEmpty();
    }

    private List<MavlinkFrame> framesOf(int messageId) {
        return sent.stream().filter(f -> f.getMessageId() == messageId).toList();
    }
}
