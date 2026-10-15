package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventRepository;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import io.aerofleet.cloud.alarm.AlarmLinkageEngine;
import io.aerofleet.cloud.alarm.AlarmToOrchBridge;
import io.aerofleet.cloud.api.dto.LoRaAlarmDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoRa 回传报警接收服务单测。
 * <p>
 * 覆盖 LoRaAlarmDto → AlarmEvent 转换、AlarmEventStore/AlarmLinkageEngine 降级处理、
 * 统计信息记录与边界条件。
 */
@DisplayName("LoRaRelayService 回传报警接收服务")
class LoRaRelayServiceTest {

    private LoRaRelayService service;
    private AlarmEventStore eventStore;
    private AlarmLinkageEngine linkageEngine;
    private final ApplicationEventPublisher noopPublisher = event -> { };

    @BeforeEach
    void setUp() {
        // 创建 AlarmEventStore 并注入 mock repository
        eventStore = new AlarmEventStore();
        AlarmEventRepository mockRepo = Mockito.mock(AlarmEventRepository.class);
        Map<String, AlarmEvent> eventMap = new ConcurrentHashMap<>();
        Mockito.when(mockRepo.save(Mockito.any(AlarmEvent.class))).thenAnswer(inv -> {
            AlarmEvent e = inv.getArgument(0);
            eventMap.put(e.getId(), e);
            return e;
        });
        Mockito.when(mockRepo.findById(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(eventMap.get(inv.getArgument(0))));
        Mockito.when(mockRepo.findAll())
                .thenAnswer(inv -> new ArrayList<>(eventMap.values()));
        Mockito.when(mockRepo.findAll(Mockito.any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            List<AlarmEvent> all = new ArrayList<>(eventMap.values());
            Sort sort = pageable.getSort();
            if (sort != null && sort.isSorted()) {
                for (Sort.Order order : sort) {
                    if ("timestampMs".equals(order.getProperty())) {
                        Comparator<AlarmEvent> cmp = Comparator.comparingLong(AlarmEvent::getTimestampMs);
                        if (order.isDescending()) {
                            cmp = cmp.reversed();
                        }
                        all.sort(cmp);
                    }
                }
            }
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), all.size());
            List<AlarmEvent> subList = start < all.size() ? new ArrayList<>(all.subList(start, end)) : new ArrayList<>();
            return new PageImpl<>(subList, pageable, all.size());
        });
        Mockito.when(mockRepo.count()).thenAnswer(inv -> (long) eventMap.size());
        Mockito.doAnswer(inv -> {
            AlarmEvent e = inv.getArgument(0);
            eventMap.remove(e.getId());
            return null;
        }).when(mockRepo).delete(Mockito.any(AlarmEvent.class));
        try {
            java.lang.reflect.Field f = AlarmEventStore.class.getDeclaredField("repository");
            f.setAccessible(true);
            f.set(eventStore, mockRepo);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("注入 mock repository 失败", e);
        }

        // 创建 AlarmLinkageEngine
        EmergencyOrchService orchService = new EmergencyOrchService(null, noopPublisher);
        AlarmToOrchBridge bridge = new AlarmToOrchBridge(orchService);
        linkageEngine = new AlarmLinkageEngine(eventStore, bridge);

        // 创建 LoRaRelayService 并注入依赖
        service = new LoRaRelayService();
        try {
            java.lang.reflect.Field f1 = LoRaRelayService.class.getDeclaredField("alarmEventStore");
            f1.setAccessible(true);
            f1.set(service, eventStore);

            java.lang.reflect.Field f2 = LoRaRelayService.class.getDeclaredField("alarmLinkageEngine");
            f2.setAccessible(true);
            f2.set(service, linkageEngine);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("注入依赖失败", e);
        }
    }

    // =====================================================================
    // receiveLoRaAlarm
    // =====================================================================

    @Test
    @DisplayName("接收 LoRa 报警并转换为 AlarmEvent")
    void receiveLoRaAlarmConvertsToAlarmEvent() {
        LoRaAlarmDto dto = createDto(101, "FIRE", 39.9, 116.3, 3);

        Map<String, Object> result = service.receiveLoRaAlarm(dto);

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(result.get("eventId")).isNotNull();
        assertThat(eventStore.size()).isEqualTo(1);
    }

    // =====================================================================
    // 降级路径
    // =====================================================================
    //
    // 三个依赖组合此前只断言 status=="SUCCESS" —— 而正常路径那条也断言同样的东西，
    // 于是三条的断言集是正常路径的真子集，读起来像在测降级，实际分辨不出谁是谁。
    // 现在每条断言**只属于自己那个组合**的可观测差异：
    //   缺 store   → 联动引擎仍应被调用（这才是"缺 store 不影响联动"的证据）
    //   缺 linkage → store 仍应收到事件（这才是"缺联动不影响落库"的证据）
    //   都缺       → 事件仍生成并返回 eventId、统计仍自增（通道本身不依赖任一方）
    //
    // 注意：AlarmEventStore 是裸 @Component、AlarmLinkageEngine 是裸 @Service，
    // 全仓无 @Conditional*/@Profile 命中它们，所以生产里这两个字段**永远非 null**，
    // 降级分支在生产不可达。它是防御代码 + 给"将来真做成可选装配"留的安全网。
    // 类 Javadoc 里"降级模式"那段已同步改成如实描述（见 LoRaRelayService）。

    /**
     * 构造一个只注入指定依赖的服务；{@code useMockEngine} 为 true 时联动引擎用 mock，
     * 以便验证"缺 store 时联动仍被调用"。
     */
    private LoRaRelayService serviceWith(boolean withStore, boolean withEngine,
                                         boolean useMockEngine) {
        LoRaRelayService svc = new LoRaRelayService();
        try {
            if (withStore) {
                java.lang.reflect.Field f = LoRaRelayService.class.getDeclaredField("alarmEventStore");
                f.setAccessible(true);
                f.set(svc, eventStore);
            }
            if (withEngine) {
                java.lang.reflect.Field f = LoRaRelayService.class.getDeclaredField("alarmLinkageEngine");
                f.setAccessible(true);
                f.set(svc, useMockEngine ? Mockito.mock(AlarmLinkageEngine.class) : linkageEngine);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("注入依赖失败", e);
        }
        return svc;
    }

    @Test
    @DisplayName("降级①AlarmEventStore 缺席：联动引擎仍被调用，事件仍返回 eventId")
    void alarmEventStoreUnavailable_stillRunsLinkageAndReturnsEventId() {
        AlarmLinkageEngine mockEngine = Mockito.mock(AlarmLinkageEngine.class);
        // 必须给非 null 返回值：processEvent 只有一个 return、恒返回非 null，
        // 而 receiveLoRaAlarm 会直接 result.getMatchedCount() —— mock 默认返回 null 会 NPE，
        // 被 catch 成 FAILED，那是 mock 的产物不是生产行为。
        Mockito.when(mockEngine.processEvent(Mockito.any()))
                .thenReturn(new AlarmLinkageEngine.ProcessResult("evt-1", 0, List.of()));

        LoRaRelayService degraded = new LoRaRelayService();
        try {
            // 只注入联动引擎，不注入 store
            java.lang.reflect.Field f = LoRaRelayService.class.getDeclaredField("alarmLinkageEngine");
            f.setAccessible(true);
            f.set(degraded, mockEngine);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        Map<String, Object> result = degraded.receiveLoRaAlarm(createDto(101, "FIRE", 39.9, 116.3, 3));

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        // 缺 store 不该影响 eventId 的生成与回传
        assertThat(result.get("eventId")).as("缺 store 时仍应生成并返回 eventId").isNotNull();
        // 核心断言：联动照常发生 —— 这条断言只属于"缺 store"这个组合
        Mockito.verify(mockEngine, Mockito.times(1)).processEvent(Mockito.any());
        // 事件确实没落库（store 缺席）
        assertThat(eventStore.size()).isZero();
        // 通道自身统计不受影响
        Map<String, Object> stats = degraded.getRelayStats();
        assertThat(stats.get("totalReceived")).isEqualTo(1L);
        assertThat(stats.get("totalSuccess")).isEqualTo(1L);
        assertThat(stats.get("totalFailed")).isEqualTo(0L);
    }

    @Test
    @DisplayName("降级②AlarmLinkageEngine 缺席：store 仍收到完整事件")
    void alarmLinkageEngineUnavailable_stillStoresEvent() {
        LoRaRelayService degraded = serviceWith(true, false, false);

        Map<String, Object> result = degraded.receiveLoRaAlarm(createDto(101, "FIRE", 39.9, 116.3, 3));

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        // 核心断言：联动缺席但落库照旧，且落的是完整事件 —— 只属于"缺 linkage"这个组合
        assertThat(eventStore.size()).isEqualTo(1);
        AlarmEventStore.PageResult page = eventStore.query(0, 10, null, null);
        assertThat(page.getItems()).hasSize(1);
        AlarmEvent stored = page.getItems().get(0);
        assertThat(stored.getEventType()).isEqualTo(AlarmEvent.EventType.FIRE);
        assertThat(stored.getSourceDeviceId()).isEqualTo("LoRa-101");
        assertThat(stored.getSeverity()).isEqualTo(AlarmEvent.Severity.WARN);
        assertThat(result.get("eventId")).isEqualTo(stored.getId());
    }

    @Test
    @DisplayName("降级③两个依赖都缺席：通道仍生成事件、返回 eventId、统计自增")
    void bothDependenciesUnavailable_stillProducesEventAndCounts() {
        LoRaRelayService degraded = serviceWith(false, false, false);

        Map<String, Object> result = degraded.receiveLoRaAlarm(createDto(101, "FIRE", 39.9, 116.3, 3));

        // 核心断言：没有任一依赖，事件对象本身仍然被构造出来并回传 id
        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(result.get("eventId")).as("两依赖皆缺席时仍应产出 eventId").isNotNull();
        assertThat(result.get("message")).isEqualTo("LoRa回传报警处理成功");
        assertThat(eventStore.size()).isZero();
        Map<String, Object> stats = degraded.getRelayStats();
        assertThat(stats.get("totalReceived")).isEqualTo(1L);
        assertThat(stats.get("totalSuccess")).isEqualTo(1L);
        assertThat(stats.get("totalFailed")).isEqualTo(0L);
        assertThat((double) stats.get("successRate")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("null DTO 返回失败结果")
    void nullDtoReturnsFailed() {
        Map<String, Object> result = service.receiveLoRaAlarm(null);

        assertThat(result.get("status")).isEqualTo("FAILED");
    }

    // =====================================================================
    // 统计信息
    // =====================================================================

    @Test
    @DisplayName("统计信息正确记录")
    void relayStatsCorrectAfterSingleAlarm() {
        LoRaAlarmDto dto = createDto(101, "FIRE", 39.9, 116.3, 3);

        service.receiveLoRaAlarm(dto);

        Map<String, Object> stats = service.getRelayStats();
        assertThat(stats.get("totalReceived")).isEqualTo(1L);
        assertThat(stats.get("totalSuccess")).isEqualTo(1L);
        assertThat(stats.get("totalFailed")).isEqualTo(0L);
        assertThat((double) stats.get("successRate")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("多次接收报警后统计累计")
    void relayStatsAccumulateAfterMultipleAlarms() {
        service.receiveLoRaAlarm(createDto(101, "FIRE", 39.9, 116.3, 3));
        service.receiveLoRaAlarm(createDto(102, "INTRUSION", 40.0, 116.4, 4));
        service.receiveLoRaAlarm(createDto(103, "MOTION", 40.1, 116.5, 2));

        Map<String, Object> stats = service.getRelayStats();
        assertThat(stats.get("totalReceived")).isEqualTo(3L);
        assertThat(stats.get("totalSuccess")).isEqualTo(3L);
        assertThat(stats.get("totalFailed")).isEqualTo(0L);
        assertThat(eventStore.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("失败报警计入统计")
    void failedAlarmCountedInStats() {
        service.receiveLoRaAlarm(null);  // 失败
        service.receiveLoRaAlarm(createDto(101, "FIRE", 39.9, 116.3, 3));  // 成功

        Map<String, Object> stats = service.getRelayStats();
        assertThat(stats.get("totalReceived")).isEqualTo(2L);
        assertThat(stats.get("totalSuccess")).isEqualTo(1L);
        assertThat(stats.get("totalFailed")).isEqualTo(1L);
        assertThat((double) stats.get("successRate")).isEqualTo(0.5);
    }

    // =====================================================================
    // 报警类型映射
    // =====================================================================

    @Test
    @DisplayName("FIRE 报警类型正确映射为 AlarmEvent.EventType.FIRE")
    void fireAlarmTypeMapping() {
        LoRaAlarmDto dto = createDto(101, "FIRE", 39.9, 116.3, 3);

        service.receiveLoRaAlarm(dto);

        AlarmEventStore.PageResult result = eventStore.query(0, 10, null, null);
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getEventType()).isEqualTo(AlarmEvent.EventType.FIRE);
    }

    @Test
    @DisplayName("INTRUSION 报警类型正确映射为 AlarmEvent.EventType.INTRUSION")
    void intrusionAlarmTypeMapping() {
        LoRaAlarmDto dto = createDto(101, "INTRUSION", 39.9, 116.3, 4);

        service.receiveLoRaAlarm(dto);

        AlarmEventStore.PageResult result = eventStore.query(0, 10, null, null);
        assertThat(result.getItems().get(0).getEventType()).isEqualTo(AlarmEvent.EventType.INTRUSION);
    }

    @Test
    @DisplayName("MOTION 报警类型正确映射为 AlarmEvent.EventType.MOTION")
    void motionAlarmTypeMapping() {
        LoRaAlarmDto dto = createDto(101, "MOTION", 39.9, 116.3, 2);

        service.receiveLoRaAlarm(dto);

        AlarmEventStore.PageResult result = eventStore.query(0, 10, null, null);
        assertThat(result.getItems().get(0).getEventType()).isEqualTo(AlarmEvent.EventType.MOTION);
    }

    @Test
    @DisplayName("未知报警类型映射为 AlarmEvent.EventType.CUSTOM")
    void unknownAlarmTypeMapping() {
        LoRaAlarmDto dto = createDto(101, "GAS_LEAK", 39.9, 116.3, 3);

        service.receiveLoRaAlarm(dto);

        AlarmEventStore.PageResult result = eventStore.query(0, 10, null, null);
        assertThat(result.getItems().get(0).getEventType()).isEqualTo(AlarmEvent.EventType.CUSTOM);
    }

    @Test
    @DisplayName("severity 1-2 映射为 INFO，3 映射为 WARN，4-5 映射为 CRITICAL")
    void severityMapping() {
        service.receiveLoRaAlarm(createDto(101, "MOTION", 39.9, 116.3, 1));
        service.receiveLoRaAlarm(createDto(102, "MOTION", 39.9, 116.3, 2));
        service.receiveLoRaAlarm(createDto(103, "MOTION", 39.9, 116.3, 3));
        service.receiveLoRaAlarm(createDto(104, "MOTION", 39.9, 116.3, 4));
        service.receiveLoRaAlarm(createDto(105, "MOTION", 39.9, 116.3, 5));

        AlarmEventStore.PageResult result = eventStore.query(0, 10, null, null);
        List<AlarmEvent> events = result.getItems();
        assertThat(events).hasSize(5);

        // 按 deviceId 排序便于断言
        events.sort(Comparator.comparing(AlarmEvent::getSourceDeviceId));
        assertThat(events.get(0).getSeverity()).isEqualTo(AlarmEvent.Severity.INFO);   // severity=1
        assertThat(events.get(1).getSeverity()).isEqualTo(AlarmEvent.Severity.INFO);   // severity=2
        assertThat(events.get(2).getSeverity()).isEqualTo(AlarmEvent.Severity.WARN);   // severity=3
        assertThat(events.get(3).getSeverity()).isEqualTo(AlarmEvent.Severity.CRITICAL); // severity=4
        assertThat(events.get(4).getSeverity()).isEqualTo(AlarmEvent.Severity.CRITICAL); // severity=5
    }

    // =====================================================================
    // 边界条件
    // =====================================================================

    @Test
    @DisplayName("空统计：初始状态下所有计数为 0")
    void emptyStatsInitialState() {
        Map<String, Object> stats = service.getRelayStats();

        assertThat(stats.get("totalReceived")).isEqualTo(0L);
        assertThat(stats.get("totalSuccess")).isEqualTo(0L);
        assertThat(stats.get("totalFailed")).isEqualTo(0L);
        assertThat((double) stats.get("successRate")).isEqualTo(0.0);
        assertThat((double) stats.get("avgLatencyMs")).isEqualTo(0.0);
    }

    // =====================================================================
    // 辅助方法
    // =====================================================================

    /** 创建 LoRaAlarmDto 测试对象。 */
    private static LoRaAlarmDto createDto(int deviceId, String alarmType,
                                          double lat, double lon, int severity) {
        return new LoRaAlarmDto(deviceId, alarmType, lat, lon,
                System.currentTimeMillis(), severity, 1, -85.0);
    }
}