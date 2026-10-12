package io.aerofleet.cloud.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 遥测 WS 两个并发/计数缺陷的回归（2026-10-12，外部评估报告 P1 项）。
 *
 * <ol>
 *   <li><b>连接限流双重扣减</b>：被拒连接在拒绝处扣一次、连接关闭时
 *       {@code afterConnectionClosed} 又按同一 IP/租户扣一次。计数只减不增，
 *       限流随时间漂移成"形同虚设"。</li>
 *   <li><b>广播无同步</b>：{@code dispatch} 对同一会话 {@code sendMessage} 时
 *       未加锁——遥测/调度/告警帧来自不同线程，并发写同一条连接会帧交错或抛
 *       IllegalStateException（客户端表现=偶发断连/半包 JSON）。</li>
 * </ol>
 */
@DisplayName("遥测 WS：限流计数只扣一次 + 同会话发送互斥")
class TelemetryWsConcurrencyTest {

    private static final String IP = "10.1.2.3";

    /** 属性表与生产一致（ConcurrentHashMap），并可指定模拟 IP。 */
    private WebSocketSession session(String id, String ip) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress(ip, 51234));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private TelemetryWebSocketHandler handler(int perIp, int perTenant) {
        return new TelemetryWebSocketHandler(
                mock(JwtTokenProvider.class),
                mock(DeviceRegistry.class),
                true,          // dev 模式：免 token
                perIp,
                perTenant,
                new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> ipCounts(TelemetryWebSocketHandler handler) {
        return (Map<String, Integer>) ReflectionTestUtils.getField(handler, "ipConnectionCounts");
    }

    @Test
    @DisplayName("被拒连接的计数只扣一次：拒绝 + 关闭后不应把别人那份也扣掉")
    void rejectedConnectionDecrementsOnlyOnce() {
        TelemetryWebSocketHandler handler = handler(1, 0);

        WebSocketSession first = session("sess-a", IP);
        handler.afterConnectionEstablished(first);   // 计入 1，达到上限

        WebSocketSession rejected = session("sess-b", IP);
        handler.afterConnectionEstablished(rejected); // 超限被拒（登记为 2 > 1）

        // 被拒连接随即被关闭：清理路径按 IP 扣一次
        handler.afterConnectionClosed(rejected, CloseStatus.POLICY_VIOLATION);
        Map<String, Integer> counts = ipCounts(handler);
        assertThat(counts.get(IP))
                .as("被拒连接拒绝处+关闭处各扣一次 ⇒ 计数会掉到 0，活着的连接反而被漏统计")
                .isEqualTo(1);

        // 真实占用释放后，新连接必须还能进来（若计数被扣成 0，这里反而放行更多——
        // 但反之若漂移成负值，合法连接会被永久拒绝；两种漂移都靠"只扣一次"消除）
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        assertThat(counts.get(IP)).isNull();

        WebSocketSession after = session("sess-c", IP);
        handler.afterConnectionEstablished(after);
        assertThat(counts.get(IP)).isEqualTo(1);
    }

    @Test
    @DisplayName("同一会话被两个线程同时广播：发送互斥，不并发")
    void concurrentBroadcastToSameSessionIsSerialized() throws Exception {
        TelemetryWebSocketHandler handler = handler(0, 0);
        WebSocketSession target = session("sess-shared", IP);
        handler.afterConnectionEstablished(target);

        AtomicInteger inFlight = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean(false);
        doAnswer(inv -> {
            if (inFlight.incrementAndGet() > 1) {
                overlapped.set(true);   // 两个线程同时在同一个会话的发送里
            }
            try {
                Thread.sleep(60);       // 停一会儿，把并发窗口放大
            } finally {
                inFlight.decrementAndGet();
            }
            return null;
        }).when(target).sendMessage(org.mockito.ArgumentMatchers.any());

        CountDownLatch start = new CountDownLatch(1);
        Runnable broadcast = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handler.broadcastPublicInfra("{\"k\":\"v\"}");
        };
        Thread t1 = new Thread(broadcast, "ws-broad-1");
        Thread t2 = new Thread(broadcast, "ws-broad-2");
        t1.start();
        t2.start();
        start.countDown();      // 同时放行，制造真实并发
        t1.join(5000);
        t2.join(5000);

        assertThat(overlapped.get())
                .as("同一会话的 sendMessage 必须互斥（未加锁时两个线程会同时进入同一会话的发送）")
                .isFalse();
        org.mockito.Mockito.verify(target, org.mockito.Mockito.times(2))
                .sendMessage(org.mockito.ArgumentMatchers.any());
    }
}
