package io.aerofleet.cloud.gateway;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.SysStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TelemetryIngestService}.
 *
 * <p>After the event-driven refactor, TelemetryIngestService only decodes frames
 * and publishes {@link MavlinkMessageEvent}s via {@link ApplicationEventPublisher}.
 * All business logic (device registration, ack completion, etc.) has moved to
 * @EventListener classes. These tests verify the thin decode-and-publish layer.
 */
@DisplayName("TelemetryIngestService: MAVLink 帧解码与事件发布")
class TelemetryIngestServiceTest {

    private static final int SYSID = 7;
    private static final int COMPID = 1;
    private static final int SEQ = 0;

    private ApplicationEventPublisher eventPublisher;
    private TelemetryIngestService service;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new TelemetryIngestService(eventPublisher);
    }

    @Test
    @DisplayName("构造器：接受 ApplicationEventPublisher 不抛异常")
    void constructor_noException() {
        assertThatCode(() -> new TelemetryIngestService(mock(ApplicationEventPublisher.class)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("handle(null frame) 抛出 NPE：catch 块访问 frame.getMessageId() 二次抛异常（源码限制）")
    void handle_nullFrame_throwsNpeDueToCatchBlockAccessingFrame() {
        assertThatCode(() -> service.handle(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("HEARTBEAT：发布 MavlinkMessageEvent")
    void handle_heartbeat_publishesEvent() {
        Heartbeat hb = new Heartbeat(3, 2, 6, 0, 4);
        MavlinkFrame frame = hb.toFrame(SYSID, COMPID, SEQ);
        service.handle(frame);
        verify(eventPublisher).publishEvent(any(MavlinkMessageEvent.class));
    }

    @Test
    @DisplayName("SYS_STATUS：发布 MavlinkMessageEvent")
    void handle_sysStatus_publishesEvent() {
        SysStatus st = new SysStatus(0, 0, 0, 200, 12000, 100, 87);
        MavlinkFrame frame = st.toFrame(SYSID, COMPID, SEQ);
        service.handle(frame);
        verify(eventPublisher).publishEvent(any(MavlinkMessageEvent.class));
    }

    @Test
    @DisplayName("COMMAND_ACK：发布 MavlinkMessageEvent")
    void handle_commandAck_publishesEvent() {
        CommandAck ack = new CommandAck(16, 0, -1, 0, 0, 0);
        MavlinkFrame frame = ack.toFrame(SYSID, COMPID, SEQ);
        service.handle(frame);
        verify(eventPublisher).publishEvent(any(MavlinkMessageEvent.class));
    }

    @Test
    @DisplayName("未知 msgId：decode 返回 null，不发布事件，不抛异常")
    void handle_unknownMsgId_noEventNoException() {
        MavlinkFrame frame = MavlinkFrame.of(SYSID, COMPID, SEQ, 99, 0, new byte[0]);
        assertThatCode(() -> service.handle(frame)).doesNotThrowAnyException();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("损坏帧（HEARTBEAT msgId 但 payload 过短）：不抛异常")
    void handle_corruptedFrame_noException() {
        MavlinkFrame frame = MavlinkFrame.of(SYSID, COMPID, SEQ, Heartbeat.ID, Heartbeat.CRC_EXTRA, new byte[0]);
        assertThatCode(() -> service.handle(frame)).doesNotThrowAnyException();
    }
}
