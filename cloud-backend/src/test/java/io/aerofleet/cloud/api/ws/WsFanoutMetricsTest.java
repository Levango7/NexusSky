package io.aerofleet.cloud.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.metrics.WsFanoutMetrics;
import io.aerofleet.cloud.security.JwtTokenProvider;
import io.aerofleet.mavlink.messages.TaskStatusMsg;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WsFanoutMetrics} 的接线回归：确认扇出背压真的出现在指标注册表上。
 * <p>
 * 放在 {@code io.aerofleet.cloud.api.ws} 包内（被测类在 {@code metrics} 包）是刻意的：
 * 收尾要用 handler 的包私有 {@code stopDispatcher()} 与 {@code OUTBOUND_QUEUE_CAPACITY}，
 * 不为测试把它们提成公开 API。
 * <p>
 * 为什么值得单独钉住：指标的价值全在「名字对不对、数值是不是活的」。
 * 注册错名字（拼错、漏 register）不会让任何功能失败——只会在真出事那天发现
 * 告警规则查不到序列。这类缺陷只有断言注册表本身才能拦住。
 */
class WsFanoutMetricsTest {

    private static final int SYSID = 7101;

    private static final String M_SESSIONS = "aerofleet.ws.sessions";
    private static final String M_QUEUE_DEPTH = "aerofleet.ws.fanout.queue.depth";
    private static final String M_DROPPED = "aerofleet.ws.fanout.dropped";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final DeviceRegistry deviceRegistry = mock(DeviceRegistry.class);
    private TelemetryWebSocketHandler handler;

    /** devMode=true：跳过握手鉴权，专注指标接线。 */
    private TelemetryWebSocketHandler newHandler() {
        when(deviceRegistry.isKnownDevice(SYSID)).thenReturn(true);
        when(deviceRegistry.tenantOf(SYSID)).thenReturn(null); // 已登记未归属 → GLOBAL
        handler = new TelemetryWebSocketHandler(
                mock(JwtTokenProvider.class), deviceRegistry, true, 100, 100, new ObjectMapper());
        return handler;
    }

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.stopDispatcher();
        }
    }

    private WebSocketSession session(String id, String ip) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(new HashMap<String, Object>());
        when(s.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(s.getRemoteAddress()).thenReturn(new InetSocketAddress(ip, 40000));
        return s;
    }

    /** 一个「连上但不读」的客户端：sendMessage 一直挂着，直到放行。 */
    private WebSocketSession slowSession(String id, String ip, CountDownLatch release) throws Exception {
        WebSocketSession s = session(id, ip);
        doAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(s).sendMessage(any(TextMessage.class));
        return s;
    }

    private MavlinkMessageEvent frame(long timestamp) {
        return new MavlinkMessageEvent(this, SYSID, TaskStatusMsg.ID, null, timestamp);
    }

    @Test
    @DisplayName("三个指标都注册成功，且数值跟随 handler 实时状态变化")
    void metersTrackLiveHandlerState() {
        newHandler();
        new WsFanoutMetrics(meterRegistry, fixedProvider(handler));

        assertThat(meterRegistry.get(M_SESSIONS).gauge().value())
                .as("尚无连接时会话数应为 0")
                .isZero();
        assertThat(meterRegistry.get(M_QUEUE_DEPTH).gauge().value())
                .as("空载时队列深度应为 0")
                .isZero();
        assertThat(meterRegistry.get(M_DROPPED).functionCounter().count())
                .as("未发生丢弃时计数应为 0")
                .isZero();

        handler.afterConnectionEstablished(session("c1", "10.9.0.1"));
        handler.afterConnectionEstablished(session("c2", "10.9.0.2"));

        assertThat(meterRegistry.get(M_SESSIONS).gauge().value())
                .as("Gauge 必须读实时值，而不是注册那一刻的快照")
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("队列饱和丢弃后，Prometheus 侧能看到同一个单调计数")
    void droppedFramesVisibleOnRegistry() throws Exception {
        newHandler();
        new WsFanoutMetrics(meterRegistry, fixedProvider(handler));

        CountDownLatch release = new CountDownLatch(1);
        handler.afterConnectionEstablished(slowSession("slow", "10.9.1.1", release));

        int total = TelemetryWebSocketHandler.OUTBOUND_QUEUE_CAPACITY + 50;
        for (int i = 0; i < total; i++) {
            handler.onMavlinkMessage(frame(i));
        }
        long dropped = handler.droppedFrames();

        assertThat(dropped)
                .as("前置条件：本场景必须真的发生丢弃，否则下面的断言毫无意义")
                .isGreaterThan(0L);
        assertThat(meterRegistry.get(M_DROPPED).functionCounter().count())
                .as("指标值必须与 handler 的计数一致（FunctionCounter 读的是同一个原子量）")
                .isEqualTo((double) dropped);
        assertThat(meterRegistry.get(M_QUEUE_DEPTH).gauge().value())
                .as("积压深度不得超过容量（有界）")
                .isLessThanOrEqualTo((double) TelemetryWebSocketHandler.OUTBOUND_QUEUE_CAPACITY);

        release.countDown();
        await().atMost(java.time.Duration.ofSeconds(20)).until(handler::drained);
    }

    @Test
    @DisplayName("上下文里没有 handler bean 时静默缺席，不抛异常也不注册半套指标")
    void absentHandlerRegistersNothing() {
        new WsFanoutMetrics(meterRegistry, emptyProvider());

        assertThat(meterRegistry.getMeters())
                .as("切片测试/未启用 WebSocket 时不应留下任何 aerofleet.ws.* 序列")
                .isEmpty();
    }

    /** 始终返回同一个 bean 的 ObjectProvider（模拟已就绪的 Spring 容器）。 */
    private static <T> ObjectProvider<T> fixedProvider(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }
        };
    }

    /** 构造一个「拿不到任何 bean」的 ObjectProvider。 */
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                throw new NoSuchBeanDefinitionException("n/a");
            }

            @Override
            public T getObject() {
                throw new NoSuchBeanDefinitionException("n/a");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }
}
