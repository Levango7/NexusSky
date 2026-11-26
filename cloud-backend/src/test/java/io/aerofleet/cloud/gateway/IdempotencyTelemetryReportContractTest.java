package io.aerofleet.cloud.gateway;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.GlobalPositionInt;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #149 幂等契约矩阵 · 上报（遥测帧 ingest）重复场景。
 *
 * <p>锁定 {@link TelemetryIngestService#handle(MavlinkFrame)} 的重复帧语义：
 * <ul>
 *   <li>不同帧 → 各发布一次事件；</li>
 *   <li><b>重复帧（同一 MAVLink 序列号、同一内容）→ 各发布一次事件</b>，
 *       即 ingest 是 <b>at-least-once</b>，不做序列号去重。</li>
 * </ul>
 *
 * <p><b>待裁决（OPEN RULING，owner）</b>：当前 ingest <b>没有</b>任何按
 * MAVLink 序列号 / 内容哈希的去重机制（{@code MavlinkFrame.sequence} 仅用于
 * 日志，不参与去重；{@code UdpGateway} 也不做 seq 去重）。重复帧会被
 * 无差别转发给下游（事件总线 / 监管上报 / 决策监控）。这是否为预期设计
 * （at-least-once + 消费方幂等）还是需要引入 seq 去重窗口，属 owner 裁决。
 * 本用例钉住当前 at-least-once 保证，使任何未来引入去重的改动都必须作为
 * 一次显式的契约变更（改这里 + 改文档），而不是静默行为漂移。
 */
@DisplayName("幂等契约矩阵：遥测上报重复帧")
class IdempotencyTelemetryReportContractTest {

    private TelemetryIngestService ingest;
    private CountingPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new CountingPublisher();
        ingest = new TelemetryIngestService(publisher);
    }

    @Test
    @DisplayName("不同内容的帧各发布一次事件")
    void distinctFramesEachPublishedOnce() {
        ingest.handle(positionFrame(1, 100));
        ingest.handle(positionFrame(1, 101));

        assertThat(publisher.events).hasSize(2);
        assertThat(publisher.sysids()).containsExactly(1, 1);
    }

    @Test
    @DisplayName("重复帧（同序列号同内容）各发布一次：at-least-once，不做 seq 去重")
    void duplicateFramesSameSequenceEachPublished() {
        MavlinkFrame first = positionFrame(2, 200);
        MavlinkFrame duplicate = positionFrame(2, 200);

        ingest.handle(first);
        ingest.handle(duplicate);

        assertThat(publisher.events).hasSize(2);
        assertThat(publisher.msgIds()).containsExactly(
                GlobalPositionInt.ID, GlobalPositionInt.ID);
    }

    /** 构造一个无人机 GLOBAL_POSITION_INT 帧（lat 随 seq 变化）。 */
    private static MavlinkFrame positionFrame(int sysid, int seq) {
        GlobalPositionInt p = new GlobalPositionInt(0,
                225900000 + seq, 1139345000, 584000, 2000, 0, 0, 0, 0);
        return p.toFrame(sysid, 1, seq);
    }

    /** 双方法计数发布器：Spring 6 的两个 publishEvent 重载都记数。 */
    private static final class CountingPublisher implements ApplicationEventPublisher {

        private final List<Object> events = new CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        List<Integer> sysids() {
            return events.stream()
                    .filter(MavlinkMessageEvent.class::isInstance)
                    .map(MavlinkMessageEvent.class::cast)
                    .map(MavlinkMessageEvent::getSysid)
                    .toList();
        }

        List<Integer> msgIds() {
            return events.stream()
                    .filter(MavlinkMessageEvent.class::isInstance)
                    .map(MavlinkMessageEvent.class::cast)
                    .map(MavlinkMessageEvent::getMsgId)
                    .toList();
        }
    }
}
