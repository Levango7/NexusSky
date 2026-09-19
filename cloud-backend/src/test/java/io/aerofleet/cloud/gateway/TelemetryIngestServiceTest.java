package io.aerofleet.cloud.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.telemetry.AlertBus;
import io.aerofleet.cloud.telemetry.PendingAcks;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.SysStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link TelemetryIngestService}.
 *
 * <p>The service has 13 constructor dependencies; only 4 ({@link DeviceRegistry},
 * {@link PendingAcks}, {@link AlertBus}, {@link ObjectMapper}) are needed for the
 * message types under test. The remaining 9 controllers/services are passed as
 * {@code null} — {@code handle} wraps everything in try-catch so null deps never
 * propagate as exceptions to the caller.
 *
 * <p>MAVLink frames are built via {@code MavlinkMessage.toFrame(sysid, compid, seq)}
 * which auto-resolves the CRC_EXTRA and encodes the payload, yielding a frame that
 * round-trips through {@code MavlinkMessage.decode}.
 */
@DisplayName("TelemetryIngestService: MAVLink 帧分发")
class TelemetryIngestServiceTest {

    private static final int SYSID = 7;
    private static final int COMPID = 1;
    private static final int SEQ = 0;

    private DeviceRegistry registry;
    private PendingAcks pendings;
    private TelemetryIngestService service;

    /** Build a service with the 4 real deps and 9 null deps. */
    private TelemetryIngestService newService() {
        registry = new DeviceRegistry();
        pendings = new PendingAcks();
        AlertBus alerts = new AlertBus();
        ObjectMapper objectMapper = new ObjectMapper();
        return new TelemetryIngestService(
                registry, pendings, alerts,
                null, null, null, null, null, null, null, null, null, null, null,
                objectMapper);
    }

    @Test
    @DisplayName("构造器：9 个非必需依赖传 null 不抛异常")
    void constructor_withNullDeps_noException() {
        assertThatCode(() -> new TelemetryIngestService(
                new DeviceRegistry(), new PendingAcks(), new AlertBus(),
                null, null, null, null, null, null, null, null, null, null, null,
                new ObjectMapper())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("handle(null frame) 抛出 NPE：catch 块访问 frame.getMessageId() 二次抛异常（源码限制）")
    void handle_nullFrame_throwsNpeDueToCatchBlockAccessingFrame() {
        service = newService();
        // 源码 handle 的 catch 块调用 frame.getMessageId() 记录日志，
        // 当 frame 本身为 null 时该日志调用二次抛出 NPE。记录此已知行为。
        assertThatCode(() -> service.handle(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("frame");
    }

    @Test
    @DisplayName("HEARTBEAT：注册设备到 registry")
    void handle_heartbeat_registersDevice() {
        service = newService();
        Heartbeat hb = new Heartbeat(3 /*MISSION*/, 2, 6, 0, 4);
        MavlinkFrame frame = hb.toFrame(SYSID, COMPID, SEQ);
        service.handle(frame);
        assertThat(registry.get(SYSID)).isNotNull();
    }

    @Test
    @DisplayName("HEARTBEAT：设置 online=true 与 mode 标签")
    void handle_heartbeat_setsOnlineAndMode() {
        service = newService();
        Heartbeat hb = new Heartbeat(3, 2, 6, 0, 4);
        MavlinkFrame frame = hb.toFrame(SYSID, COMPID, SEQ);
        service.handle(frame);
        DroneSnapshot s = registry.get(SYSID);
        assertThat(s).isNotNull();
        assertThat(s.online).isTrue();
        assertThat(s.mode).isEqualTo("MISSION");
        assertThat(s.customMode).isEqualTo(3);
    }

    @Test
    @DisplayName("SYS_STATUS：更新 battery/voltage/load")
    void handle_sysStatus_updatesBattery() {
        service = newService();
        SysStatus st = new SysStatus(0, 0, 0, 200, 12000, 100, 87);
        MavlinkFrame frame = st.toFrame(SYSID, COMPID, SEQ);
        service.handle(frame);
        DroneSnapshot s = registry.get(SYSID);
        assertThat(s).isNotNull();
        assertThat(s.battery).isEqualTo(87);
        assertThat(s.voltage).isEqualTo(12000);
        assertThat(s.load).isEqualTo(200);
    }

    @Test
    @DisplayName("COMMAND_ACK：完成 pending ack future")
    void handle_commandAck_completesPending() {
        service = newService();
        int command = 16; // MAV_CMD_NAV_WAYPOINT
        CompletableFuture<CommandAck> future = pendings.expectCommandAck(command, SYSID);
        CommandAck ack = new CommandAck(command, 0 /*MAV_RESULT_ACCEPTED*/, -1, 0, 0, 0);
        MavlinkFrame frame = ack.toFrame(SYSID, COMPID, SEQ);
        service.handle(frame);
        assertThat(future).isDone();
        CommandAck received = future.getNow(null);
        assertThat(received).isNotNull();
        assertThat(received.command).isEqualTo(command);
        assertThat(received.result).isEqualTo(0);
    }

    @Test
    @DisplayName("未知 msgId：不抛异常")
    void handle_unknownMsgId_noException() {
        service = newService();
        // msgId 99 is not in MavlinkMessage.decode switch → decode returns null → handle returns
        MavlinkFrame frame = MavlinkFrame.of(SYSID, COMPID, SEQ, 99, 0, new byte[0]);
        assertThatCode(() -> service.handle(frame)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("损坏帧（HEARTBEAT msgId 但 payload 过短）：不抛异常")
    void handle_corruptedFrame_noException() {
        service = newService();
        MavlinkFrame frame = MavlinkFrame.of(SYSID, COMPID, SEQ, Heartbeat.ID, Heartbeat.CRC_EXTRA, new byte[0]);
        assertThatCode(() -> service.handle(frame)).doesNotThrowAnyException();
    }
}