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
    @DisplayName("handle(null frame) 不抛异常：catch 块自身不解引用 frame（Javadoc 承诺 Never throws）")
    void handle_nullFrame_doesNotThrow() {
        // 此前这条断言的是 NPE —— 把 catch 块里的二次 NPE 钉成了期望行为。
        // 那是个真缺陷：handle() 的 Javadoc 写着 "Never throws"，而 catch 块里
        // frame.getMessageId() 在 frame 为 null 时会再炸一次，catch 成了抛出点。
        assertThatCode(() -> service.handle(null)).doesNotThrowAnyException();
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
        // 断言本身是有效的：把 handle() 里的 `if (msg == null) return;` 去掉，
        // 本条会以 NeverWantedButInvoked 转红（已变异验证）。
        //
        // 但要如实说明**可达性**：msgId 99 未在 MavlinkMessageInfo 登记，
        // crcExtraOf(99) 会抛 MavlinkException，帧在 MavlinkParser 的 CRC 阶段就被丢掉，
        // 因此**生产路径到不了 handle()**。本条覆盖的是"绕过解析器直接调用 handle"
        // 这一情形下 msg==null 守卫的行为 —— 与 LoRaRelay 那条防御分支同类。
        //
        // 生产里真正可达的解码失败是「已登记 msgId + 截短 payload」，
        // 由 handle_corruptedFrame 那条覆盖。
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
    @DisplayName("决策监控未注入（ai 模块缺席）：决策事件仍照常发布到事件总线，不打断收包线程")
    void handle_decisionEvent_monitorAbsent_stillPublishesEvent() {
        // 注意：本条**杀不掉**"去掉 decisionMonitor null 检查"这个变异（T4，0 杀）。
        // 原因是 handle() 外层的 catch (RuntimeException) 把两种行为从外部观察上抹平了：
        //   有 null 检查 -> 跳过决策落库，publishEvent 已执行
        //   无 null 检查 -> 决策落库处 NPE，但 publishEvent 也已执行，NPE 又被 catch 吞掉
        // 从调用方（UdpGateway 的收包线程）看，两者完全一样。
        // 所以这里断言的是**降级契约本身**：监控缺席不影响事件转发与收包线程存活，
        // 而不是断言某个内部实现点。要让 null 检查变得可测，得先去掉那个大 catch ——
        // 那会把"解码失败"和"下游抛异常"两类语义混在一起，是更大的改动。
        DecisionEventMsg msg = new DecisionEventMsg(0f, 0.5f, 1L, SYSID, 0, 0);

        assertThatCode(() -> service.handle(msg.toFrame(SYSID, COMPID, SEQ)))
                .doesNotThrowAnyException();

        // 降级仍要保证事件进总线 —— 这条断言在"整个决策分支被关掉"时也会红（T5）
        verify(eventPublisher).publishEvent(any(MavlinkMessageEvent.class));
    }
}
