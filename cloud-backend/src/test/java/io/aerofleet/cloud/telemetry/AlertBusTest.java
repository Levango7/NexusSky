package io.aerofleet.cloud.telemetry;

import io.aerofleet.cloud.gateway.AlertEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AlertBus 单元测试：告警事件总线。
 * <p>
 * 覆盖要点：publish/subscribe/unsubscribe/多订阅者/异常隔离。
 * 遵循华为防御性测试实践：异常与边界场景优先。
 */
@DisplayName("AlertBus 告警事件总线")
class AlertBusTest {

    // ==================== 边界场景测试（最高优先级） ====================

    @Test
    @DisplayName("AB-publish-无订阅者-不抛异常")
    void testPublish_noSubscriber_noException() {
        AlertBus bus = new AlertBus();
        AlertEntry entry = new AlertEntry(1, "warn", System.currentTimeMillis());
        assertThatCode(() -> bus.publish(1, entry)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AB-unsubscribe-未订阅的 listener-不抛异常")
    void testUnsubscribe_notSubscribed_noException() {
        AlertBus bus = new AlertBus();
        assertThatCode(() -> bus.unsubscribe(e -> {})).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AB-unsubscribe-null listener-不抛异常")
    void testUnsubscribe_nullListener_noException() {
        AlertBus bus = new AlertBus();
        assertThatCode(() -> bus.unsubscribe(null)).doesNotThrowAnyException();
    }

    // ==================== 正常业务流程测试 ====================

    @Test
    @DisplayName("AB-subscribe+publish-订阅者收到正确事件")
    void testSubscribeThenPublish_listenerReceivesEvent() {
        AlertBus bus = new AlertBus();
        AtomicReference<AlertBus.AlertEvent> received = new AtomicReference<>();
        bus.subscribe(received::set);

        AlertEntry entry = new AlertEntry(2, "low battery", 1234L);
        bus.publish(7, entry);

        AlertBus.AlertEvent ev = received.get();
        assertThat(ev).isNotNull();
        assertThat(ev.sysid()).isEqualTo(7);
        assertThat(ev.entry()).isSameAs(entry);
    }

    @Test
    @DisplayName("AB-publish-事件字段完整传递")
    void testPublish_eventFieldsPropagated() {
        AlertBus bus = new AlertBus();
        AtomicReference<AlertBus.AlertEvent> received = new AtomicReference<>();
        bus.subscribe(received::set);

        AlertEntry entry = new AlertEntry(6, "critical: link lost", 99_999L);
        bus.publish(3, entry);

        AlertBus.AlertEvent ev = received.get();
        assertThat(ev.sysid()).isEqualTo(3);
        assertThat(ev.entry().severity).isEqualTo(6);
        assertThat(ev.entry().text).isEqualTo("critical: link lost");
        assertThat(ev.entry().ts).isEqualTo(99_999L);
    }

    @Test
    @DisplayName("AB-多订阅者-全部收到同一事件")
    void testMultipleSubscribers_allReceive() {
        AlertBus bus = new AlertBus();
        AtomicInteger countA = new AtomicInteger();
        AtomicInteger countB = new AtomicInteger();
        AtomicInteger countC = new AtomicInteger();
        bus.subscribe(e -> countA.incrementAndGet());
        bus.subscribe(e -> countB.incrementAndGet());
        bus.subscribe(e -> countC.incrementAndGet());

        AlertEntry entry = new AlertEntry(1, "msg", 1L);
        bus.publish(1, entry);

        assertThat(countA.get()).isEqualTo(1);
        assertThat(countB.get()).isEqualTo(1);
        assertThat(countC.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("AB-unsubscribe-已订阅 listener-不再收到事件")
    void testUnsubscribe_subscribed_stopsReceiving() {
        AlertBus bus = new AlertBus();
        AtomicInteger count = new AtomicInteger();
        java.util.function.Consumer<AlertBus.AlertEvent> listener = e -> count.incrementAndGet();
        bus.subscribe(listener);

        bus.publish(1, new AlertEntry(1, "a", 1L));
        assertThat(count.get()).isEqualTo(1);

        bus.unsubscribe(listener);
        bus.publish(1, new AlertEntry(1, "b", 2L));
        assertThat(count.get()).isEqualTo(1); // 仍为 1，未收到第二次
    }

    @Test
    @DisplayName("AB-多次 publish-订阅者每次都收到")
    void testMultiplePublishes_listenerReceivesAll() {
        AlertBus bus = new AlertBus();
        List<Integer> sysids = new ArrayList<>();
        bus.subscribe(e -> sysids.add(e.sysid()));

        bus.publish(1, new AlertEntry(1, "a", 1L));
        bus.publish(2, new AlertEntry(1, "b", 2L));
        bus.publish(3, new AlertEntry(1, "c", 3L));

        assertThat(sysids).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("AB-重复 subscribe 同一 listener-收到两次")
    void testDuplicateSubscribe_receivesTwice() {
        AlertBus bus = new AlertBus();
        AtomicInteger count = new AtomicInteger();
        java.util.function.Consumer<AlertBus.AlertEvent> listener = e -> count.incrementAndGet();
        bus.subscribe(listener);
        bus.subscribe(listener);

        bus.publish(1, new AlertEntry(1, "x", 1L));
        assertThat(count.get()).isEqualTo(2);
    }

    // ==================== 异常隔离测试 ====================

    @Test
    @DisplayName("AB-订阅者抛 RuntimeException-不影响其他订阅者")
    void testFailingSubscriber_doesNotAffectOthers() {
        AlertBus bus = new AlertBus();
        AtomicInteger countAfterFailure = new AtomicInteger();
        bus.subscribe(e -> { throw new RuntimeException("boom"); });
        bus.subscribe(e -> countAfterFailure.incrementAndGet());

        assertThatCode(() -> bus.publish(1, new AlertEntry(1, "x", 1L)))
                .doesNotThrowAnyException();
        assertThat(countAfterFailure.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("AB-中间订阅者抛异常-后续订阅者仍收到")
    void testFailingSubscriberInMiddle_subsequentStillReceive() {
        AlertBus bus = new AlertBus();
        List<String> order = new ArrayList<>();
        bus.subscribe(e -> order.add("before"));
        bus.subscribe(e -> { order.add("failing"); throw new RuntimeException("boom"); });
        bus.subscribe(e -> order.add("after"));

        bus.publish(1, new AlertEntry(1, "x", 1L));

        assertThat(order).containsExactly("before", "failing", "after");
    }

    @Test
    @DisplayName("AB-所有订阅者均抛异常-publish 不抛出")
    void testAllSubscribersFail_publishDoesNotThrow() {
        AlertBus bus = new AlertBus();
        bus.subscribe(e -> { throw new RuntimeException("a"); });
        bus.subscribe(e -> { throw new IllegalStateException("b"); });

        assertThatCode(() -> bus.publish(1, new AlertEntry(1, "x", 1L)))
                .doesNotThrowAnyException();
    }

    // ==================== AlertEvent record 测试 ====================

    @Test
    @DisplayName("AB-AlertEvent-相等性与组件访问")
    void testAlertEvent_equalityAndAccessors() {
        AlertEntry entry = new AlertEntry(3, "text", 100L);
        AlertBus.AlertEvent ev1 = new AlertBus.AlertEvent(5, entry);
        AlertBus.AlertEvent ev2 = new AlertBus.AlertEvent(5, entry);
        AlertBus.AlertEvent ev3 = new AlertBus.AlertEvent(6, entry);

        assertThat(ev1.sysid()).isEqualTo(5);
        assertThat(ev1.entry()).isSameAs(entry);
        assertThat(ev1).isEqualTo(ev2);
        assertThat(ev1).isNotEqualTo(ev3);
        assertThat(ev1.hashCode()).isEqualTo(ev2.hashCode());
    }
}