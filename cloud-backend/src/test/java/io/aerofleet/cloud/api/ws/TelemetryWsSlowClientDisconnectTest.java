package io.aerofleet.cloud.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 慢客户端防护回归（B 档，GAPS §十五）。
 *
 * <p>背景：{@code TelemetryWebSocketHandler.dispatch()} 原先靠 {@code synchronized (s)} 串行化同会话发送。
 * 但这把锁会屏蔽 Spring {@link ConcurrentWebSocketSessionDecorator} 的限流检查——装饰器只在
 * 「另一线程正持有 flushLock 时」才执行 {@code checkSessionLimits()}，发送时长超 {@code sendTimeLimit}
 * 或缓冲超 {@code bufferSizeLimit} 即抛 {@link SessionLimitExceededException} 并主动断开会话。
 * 有手工锁在，单会话发送永远串行、检查永不触发，慢客户端（连上但不读数据）会一直占住调度线程。
 *
 * <p>B 档移除手工锁、让装饰器套上并提供 flushLock 互斥 + 超时/缓冲保护。本测试验证：
 * <ol>
 *   <li>装饰器契约：首帧阻塞超 sendTimeLimit 时，后续并发推送触发 SessionLimitExceededException；</li>
 *   <li>handler 集成：该异常被 dispatch 的 catch 捕获后，会话被移出连接集（不再占用线程）。</li>
 * </ol>
 */
@DisplayName("遥测 WS：慢客户端阻塞被装饰器断开并移出连接集")
class TelemetryWsSlowClientDisconnectTest {

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

    @Test
    @DisplayName("装饰器契约：首帧阻塞超过 sendTimeLimit 时，后续并发推送抛 SessionLimitExceededException")
    void decoratorTripsOnSlowSendUnderConcurrency() throws Exception {
        WebSocketSession raw = session("slow", IP);
        final int sendTimeLimitMs = 50;
        // 模拟「连上但不读数据」的客户端：服务端 sendMessage 长时间阻塞（远超 sendTimeLimit）
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            Thread.sleep(400);
            return null;
        }).when(raw).sendMessage(any());

        // 缓冲上限给足（1MB），让本用例只验证「发送时长」这一条路径
        ConcurrentWebSocketSessionDecorator dec =
                new ConcurrentWebSocketSessionDecorator(raw, sendTimeLimitMs, 1024 * 1024);

        AtomicReference<Throwable> secondError = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                dec.sendMessage(new TextMessage("{\"a\":1}"));
            } catch (Throwable ignored) {
                // 首个发送可能正常返回，也可能被后续状态影响，无关紧要
            }
        });
        Thread second = new Thread(() -> {
            try {
                entered.await();
                // 关键：装饰器的 sendTimeLimit 从「本次发送开始」起算（sendStartTime），
                // 且 checkSessionLimits() 只在拿不到 flushLock 时才被调用。
                // 因此必须等 elapsed > sendTimeLimit 之后再发，否则判定不成立（这是本用例
                // 第一版的错：entered 一 countdown 就立刻发，elapsed≈0，永远不触发）。
                // 150ms vs 50ms 留 3 倍余量，且远小于首帧 400ms 的阻塞时长，不依赖精确时序。
                Thread.sleep(150);
                dec.sendMessage(new TextMessage("{\"b\":2}"));
            } catch (Throwable t) {
                secondError.set(t);
            }
        });
        first.start();
        second.start();
        first.join(5000);
        second.join(5000);

        assertThat(secondError.get())
                .as("慢客户端阻塞期间，后续并发推送必须触发 SessionLimitExceededException，否则无法主动断开")
                .isInstanceOf(SessionLimitExceededException.class);
    }

    @Test
    @DisplayName("handler 集成：装饰器抛出 SessionLimitExceededException 后，该会话被移出连接集")
    void handlerEvictsSessionOnDecoratorLimit() throws Exception {
        // throws Exception 是必需的：下面 doAnswer(...).when(raw).sendMessage(any()) 走的是
        // WebSocketSession 接口签名，sendMessage 声明 throws IOException（受检异常）。
        // 缺这一条会在 javac 阶段报 "unreported exception java.io.IOException"，
        // 连累 cloud-backend 全部构建 job（2026-10-31 run 41221275756 即此因）。
        TelemetryWebSocketHandler handler = handler(0, 0);
        WebSocketSession raw = session("slow", IP);
        // 模拟装饰器已判定超限并断开：sendMessage 直接抛 SessionLimitExceededException
        doAnswer(inv -> {
            throw new SessionLimitExceededException(
                    "simulated slow-client send-time limit exceeded",
                    CloseStatus.SESSION_NOT_RELIABLE);
        }).when(raw).sendMessage(any());

        ConcurrentWebSocketSessionDecorator dec =
                new ConcurrentWebSocketSessionDecorator(raw, 10_000, 1024);
        handler.afterConnectionEstablished(dec);
        assertThat(handler.connectionCount()).isEqualTo(1);

        handler.broadcastPublicInfra("{\"k\":\"v\"}");

        assertThat(handler.connectionCount())
                .as("装饰器断开设定的会话必须被 dispatch 的 catch 移出连接集，否则会持续占用调度线程")
                .isEqualTo(0);
    }
}
