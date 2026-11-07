package io.aerofleet.cloud.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.security.JwtTokenProvider;
import io.aerofleet.mavlink.messages.TaskStatusMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 遥测扇出的<b>阻塞隔离</b>与<b>背压</b>回归。
 * <p>
 * 背景（证据链）：{@code UdpMavlinkTransport} 只有一条接收线程，它内联回调
 * {@code UdpGateway.onFrame → ingest.handle → publishEvent}，而本 handler 是同步
 * {@code @EventListener}。改造前 {@code sendMessage} 就跑在那条线程上——任一「连上但不读」
 * 的客户端把 socket 缓冲填满，就能把整条机队的遥测摄入一起堵死。
 * <p>
 * 本类钉住三件事：① 调用线程不再被慢客户端阻塞；② 队列有界、满了丢最旧并计数（不 OOM、不挂死）；
 * ③ 剥离到单消费者后，同一会话的帧顺序不变。
 */
class TelemetryWsFanoutIsolationTest {

    private static final int SYSID = 7001;

    private final DeviceRegistry registry = mock(DeviceRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private TelemetryWebSocketHandler handler;

    /** devMode=true：跳过握手鉴权，专注扇出行为。 */
    private TelemetryWebSocketHandler newHandler() {
        when(registry.isKnownDevice(SYSID)).thenReturn(true);
        when(registry.tenantOf(SYSID)).thenReturn(null); // 已登记未归属 → GLOBAL，只投全局会话
        handler = new TelemetryWebSocketHandler(
                mock(JwtTokenProvider.class), registry, true, 100, 100, mapper);
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
    @DisplayName("慢客户端不阻塞调用线程：摄入路径立即返回，帧转由消费者线程投递")
    void slowClientDoesNotBlockCallerThread() throws Exception {
        newHandler();
        CountDownLatch release = new CountDownLatch(1);
        WebSocketSession slow = slowSession("slow", "10.8.0.1", release);
        WebSocketSession fast = session("fast", "10.8.0.2");
        handler.afterConnectionEstablished(slow);
        handler.afterConnectionEstablished(fast);

        long t0 = System.nanoTime();
        handler.onMavlinkMessage(frame(1L));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // 改造前：这里会一直卡在 slow.sendMessage 上，直到 latch 超时（10s）。
        assertThat(elapsedMs)
                .as("调用线程（模拟 UDP 接收线程）不得被慢客户端阻塞")
                .isLessThan(500);

        release.countDown();
        await().atMost(java.time.Duration.ofSeconds(10)).until(handler::drained);
        verify(fast).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("队列饱和时丢最旧并计数，不阻塞、不无界增长")
    void saturatedQueueDropsOldestAndCounts() throws Exception {
        newHandler();
        CountDownLatch release = new CountDownLatch(1);
        WebSocketSession slow = slowSession("slow", "10.8.1.1", release);
        handler.afterConnectionEstablished(slow);

        int extra = 50;
        int total = TelemetryWebSocketHandler.OUTBOUND_QUEUE_CAPACITY + extra;
        for (int i = 0; i < total; i++) {
            handler.onMavlinkMessage(frame(i));
        }

        assertThat(handler.droppedFrames())
                .as("队列满后必须丢帧并计数，而不是把调用线程堵住")
                .isGreaterThan(0);
        assertThat(handler.outboundQueueDepth())
                .as("积压深度不得超过容量（有界）")
                .isLessThanOrEqualTo(TelemetryWebSocketHandler.OUTBOUND_QUEUE_CAPACITY);

        release.countDown();
        await().atMost(java.time.Duration.ofSeconds(20)).until(handler::drained);
    }

    @Test
    @DisplayName("剥离到单消费者后，同一会话的帧顺序不变")
    void framesKeepEnqueueOrder() throws Exception {
        newHandler();
        WebSocketSession client = session("c1", "10.8.2.1");
        handler.afterConnectionEstablished(client);

        int n = 20;
        for (int i = 0; i < n; i++) {
            handler.onMavlinkMessage(frame(i));
        }
        await().atMost(java.time.Duration.ofSeconds(5)).until(handler::drained);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(client, org.mockito.Mockito.times(n)).sendMessage(captor.capture());
        List<String> payloads = captor.getAllValues().stream().map(TextMessage::getPayload).toList();
        assertThat(payloads).hasSize(n);
        for (int i = 0; i < n; i++) {
            assertThat(payloads.get(i))
                    .as("第 %d 帧必须仍是第 %d 帧（单消费者保序）", i, i)
                    .contains("\"timestamp\":" + i);
        }
    }
}
