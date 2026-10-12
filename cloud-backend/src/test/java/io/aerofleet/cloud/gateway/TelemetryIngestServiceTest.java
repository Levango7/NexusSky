package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.ai.DecisionMonitorService;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.CommandAck;
import io.aerofleet.mavlink.messages.DecisionEventMsg;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.SysStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

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

    // ---- 决策事件落监控（2026-10-12：此前 DECISION_EVENT 只转发 WS、从不落库，
    // /api/v1/ai/decisions 恒为空——对外是个"有接口无数据"的假能力）----

    @Test
    @DisplayName("DECISION_EVENT：记入决策监控（类型名/原因/置信度）")
    void handle_decisionEvent_recordsIntoMonitor() {
        DecisionMonitorService monitor = new DecisionMonitorService();
        ReflectionTestUtils.setField(service, "decisionMonitor", monitor);

        // decisionType=1 → DecisionType.AVOID
        DecisionEventMsg msg = new DecisionEventMsg(12.5f, 0.75f, 1_700_000_000_000L,
                SYSID, 1, 7);
        service.handle(msg.toFrame(SYSID, COMPID, SEQ));

        java.util.List<java.util.Map<String, Object>> decisions = monitor.getDecisions(SYSID);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("type")).isEqualTo("AVOID");
        assertThat(decisions.get(0).get("confidence")).isEqualTo(0.75);
        assertThat(decisions.get(0).get("reason").toString()).contains("reason=7");
        // 事件照旧发布（WS 面不受影响）
        verify(eventPublisher).publishEvent(any(MavlinkMessageEvent.class));
    }

    @Test
    @DisplayName("决策类型码越界：记 UNKNOWN(n)，不抛异常也不打断收包线程")
    void handle_decisionEvent_unknownTypeCode_doesNotThrow() {
        DecisionMonitorService monitor = new DecisionMonitorService();
        ReflectionTestUtils.setField(service, "decisionMonitor", monitor);

        DecisionEventMsg msg = new DecisionEventMsg(0f, 0.5f, 1L, SYSID, 99, 0);
        assertThatCode(() -> service.handle(msg.toFrame(SYSID, COMPID, SEQ)))
                .doesNotThrowAnyException();

        assertThat(monitor.getDecisions(SYSID).get(0).get("type")).isEqualTo("UNKNOWN(99)");
    }

    @Test
    @DisplayName("决策监控未注入（ai 模块缺席）：解码照样不抛")
    void handle_decisionEvent_monitorAbsent_noException() {
        DecisionEventMsg msg = new DecisionEventMsg(0f, 0.5f, 1L, SYSID, 0, 0);
        assertThatCode(() -> service.handle(msg.toFrame(SYSID, COMPID, SEQ)))
                .doesNotThrowAnyException();
    }
}
