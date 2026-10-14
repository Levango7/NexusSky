package io.aerofleet.cloud.alarm;

import io.aerofleet.cloud.autodispatch.AutoDispatchService;
import io.aerofleet.cloud.autodispatch.DispatchResult;
import io.aerofleet.cloud.autodispatch.VoiceIntercomService;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.AlarmAckMsg;
import io.aerofleet.mavlink.messages.AlarmTriggerMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报警联动引擎 WS 帧发布单测（边界清零 2026-10-05）。
 * <p>
 * 覆盖 AlarmTriggerMsg(30057)（事件摄取即通知，含显式租户）与
 * AlarmAckMsg(30058)（AUTO_DISPATCH 派遣成功 = 机载开始响应，
 * 承载真实 sysid/ETA）的发布与字段映射。
 */
@DisplayName("AlarmLinkageEngine 报警帧发布 (30057/30058)")
class AlarmLinkageEngineFrameTest {

    private AlarmEventStore store;
    private ApplicationEventPublisher publisher;
    private AutoDispatchService autoDispatchService;
    private VoiceIntercomService voiceIntercomService;
    private AlarmLinkageEngine engine;

    @BeforeEach
    void setUp() {
        store = new AlarmEventStore();
        // AlarmEventStore 的 repository 字段为 @Autowired 注入，测试中反射注入 mock
        // （沿 AlarmLinkageEngineTest 惯例），store() 依赖 repository.count/save。
        AlarmEventRepository mockRepo = Mockito.mock(AlarmEventRepository.class);
        java.util.Map<String, AlarmEvent> eventMap = new java.util.concurrent.ConcurrentHashMap<>();
        Mockito.when(mockRepo.save(Mockito.any(AlarmEvent.class))).thenAnswer(inv -> {
            AlarmEvent e = inv.getArgument(0);
            eventMap.put(e.getId(), e);
            return e;
        });
        Mockito.when(mockRepo.count()).thenAnswer(inv -> (long) eventMap.size());
        try {
            java.lang.reflect.Field f = AlarmEventStore.class.getDeclaredField("repository");
            f.setAccessible(true);
            f.set(store, mockRepo);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("注入 mock repository 失败", e);
        }

        publisher = mock(ApplicationEventPublisher.class);
        autoDispatchService = mock(AutoDispatchService.class);
        voiceIntercomService = mock(VoiceIntercomService.class);
        AlarmToOrchBridge bridge = Mockito.mock(AlarmToOrchBridge.class);
        engine = new AlarmLinkageEngine(store, bridge, autoDispatchService,
                voiceIntercomService, publisher);
    }

    private static AlarmEvent event(AlarmEvent.EventType type, AlarmEvent.Severity severity,
                                    String deviceId, Integer tenantId) {
        AlarmEvent e = new AlarmEvent("evt-1", deviceId, "cam-01",
                type, severity, "intrusion at gate", 30.1234567, 120.7654321, 12.5,
                System.currentTimeMillis(), false);
        e.setTenantId(tenantId);
        return e;
    }

    private static AlarmLinkageRule autoDispatchRule(String id, AlarmEvent.EventType matchType) {
        return new AlarmLinkageRule(id, "rule-" + id, true,
                matchType, AlarmEvent.Severity.INFO, Set.of(),
                AlarmLinkageRule.ActionType.AUTO_DISPATCH,
                1, Double.NaN, Double.NaN, 500, 80, null);
    }

    private List<MavlinkMessageEvent> capturedEvents(int expectedCount) {
        ArgumentCaptor<MavlinkMessageEvent> captor =
                ArgumentCaptor.forClass(MavlinkMessageEvent.class);
        verify(publisher, times(expectedCount)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("processEvent 发布 30057：类型/严重度/E7/设备哈希/显式租户正确")
    void processEventPublishesTriggerFrame() {
        AlarmEvent e = event(AlarmEvent.EventType.INTRUSION, AlarmEvent.Severity.WARN,
                "cam-main-gate", 42);
        engine.processEvent(e);

        MavlinkMessageEvent event = capturedEvents(1).get(0);
        assertThat(event.getMsgId()).isEqualTo(AlarmTriggerMsg.ID);
        assertThat(event.isTenantExplicit()).isTrue();
        assertThat(event.getOwnerTenantId()).isEqualTo(42);

        AlarmTriggerMsg msg = (AlarmTriggerMsg) event.getMessage();
        assertThat(msg.timestamp).isEqualTo(e.getTimestampMs());
        assertThat(msg.lat).isEqualTo(301234567);
        assertThat(msg.lon).isEqualTo(1207654321);
        assertThat(msg.sourceDeviceId).isEqualTo(AlarmLinkageEngine.deviceIdToU16("cam-main-gate"));
        assertThat(msg.alt).isEqualTo(12500);
        assertThat(msg.alarmType).isEqualTo(AlarmEvent.EventType.INTRUSION.ordinal());
        assertThat(msg.severity).isEqualTo(AlarmEvent.Severity.WARN.level());
        assertThat(msg.description).isEqualTo("intrusion at gate");
    }

    @Test
    @DisplayName("无租户事件的 30057 帧显式声明未归属（全局域）")
    void tenantlessEventPublishesWithExplicitNullTenant() {
        AlarmEvent e = event(AlarmEvent.EventType.FIRE, AlarmEvent.Severity.CRITICAL,
                "cam-2", null);
        engine.processEvent(e);

        MavlinkMessageEvent event = capturedEvents(1).get(0);
        assertThat(event.isTenantExplicit()).isTrue();
        assertThat(event.getOwnerTenantId()).isNull();
        assertThat(((AlarmTriggerMsg) event.getMessage()).alarmType)
                .isEqualTo(AlarmEvent.EventType.FIRE.ordinal());
    }

    @Test
    @DisplayName("AUTO_DISPATCH 派遣成功逐机发布 30058：sysid/ETA/ackResult=1")
    void autoDispatchPublishesAckPerDrone() {
        when(autoDispatchService.dispatchDrone(anyDouble(), anyDouble(), anyString(), anyInt()))
                .thenReturn(new DispatchResult("d-1", DispatchResult.Status.SUCCESS,
                        List.of(new DispatchResult.DispatchedDrone(9, true, 180)),
                        "ok"));
        engine.addRule(autoDispatchRule("r1", AlarmEvent.EventType.FIRE));
        AlarmEvent e = event(AlarmEvent.EventType.FIRE, AlarmEvent.Severity.CRITICAL,
                "cam-3", 7);
        engine.processEvent(e);

        List<MavlinkMessageEvent> events = capturedEvents(2);
        MavlinkMessageEvent ackEvent = events.get(1);
        assertThat(ackEvent.getMsgId()).isEqualTo(AlarmAckMsg.ID);
        assertThat(ackEvent.getSysid()).isEqualTo(9);
        assertThat(ackEvent.isTenantExplicit()).isTrue();
        assertThat(ackEvent.getOwnerTenantId()).isEqualTo(7);

        AlarmAckMsg ack = (AlarmAckMsg) ackEvent.getMessage();
        assertThat(ack.alarmId).isEqualTo(AlarmLinkageEngine.idToU32("evt-1"));
        assertThat(ack.estimatedArrivalSec).isEqualTo(180);
        assertThat(ack.droneSysid).isEqualTo(9);
        assertThat(ack.ackResult).isEqualTo(1);
    }

    @Test
    @DisplayName("AUTO_DISPATCH 无可用无人机不发布 30058（仅 30057）")
    void autoDispatchNoDronePublishesNoAck() {
        when(autoDispatchService.dispatchDrone(anyDouble(), anyDouble(), anyString(), anyInt()))
                .thenReturn(new DispatchResult("d-2", DispatchResult.Status.NO_DRONE,
                        List.of(), "no drone"));
        engine.addRule(autoDispatchRule("r2", AlarmEvent.EventType.FIRE));
        engine.processEvent(event(AlarmEvent.EventType.FIRE, AlarmEvent.Severity.CRITICAL,
                "cam-4", null));

        List<MavlinkMessageEvent> events = capturedEvents(1);
        assertThat(events.get(0).getMsgId()).isEqualTo(AlarmTriggerMsg.ID);
    }

    @Test
    @DisplayName("旧构造器（无发布器）不发布帧且不崩溃")
    void legacyConstructorSkipsPublishing() throws Exception {
        // 关键：把 publisher **真的交给 legacy**，而不是验证 setUp 里那个
        // 只给了另一个 engine 的 mock。原写法 verify(publisher, never()) 里，
        // legacy 与 publisher 之间不存在任何引用关系 —— 那是一条结构性
        // 不可能失败的断言，实测给 legacy 注入真实发布器后它照样全绿。
        //
        // 现在改成：先用 5 参构造器造一个同 publisher 的 engine 做正向对照，
        // 再断言 legacy 自己持的 eventPublisher 是 null。
        AlarmLinkageEngine legacy = new AlarmLinkageEngine(store,
                Mockito.mock(AlarmToOrchBridge.class));

        java.lang.reflect.Field f = AlarmLinkageEngine.class.getDeclaredField("eventPublisher");
        f.setAccessible(true);
        assertThat(f.get(legacy))
                .as("旧构造器必须不持有发布器 —— 这才是「不发布帧」的前提")
                .isNull();

        // 反向对照：5 参构造器确实持有它，证明上面那条 null 不是构造器普遍如此
        assertThat(f.get(engine))
                .as("5 参构造器应持有发布器")
                .isSameAs(publisher);

        // 跑一遍事件处理，确认既不发布也不崩
        legacy.processEvent(event(AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO,
                "cam-5", null));
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("设备 ID → u16 哈希稳定且在范围内")
    void deviceIdHashStable() {
        int a = AlarmLinkageEngine.deviceIdToU16("cam-main-gate");
        assertThat(a).isEqualTo(AlarmLinkageEngine.deviceIdToU16("cam-main-gate"));
        assertThat(a).isBetween(0, 0xFFFF);
        assertThat(AlarmLinkageEngine.deviceIdToU16(null)).isZero();
    }
}
