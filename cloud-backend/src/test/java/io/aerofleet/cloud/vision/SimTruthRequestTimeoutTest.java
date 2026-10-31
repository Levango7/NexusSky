package io.aerofleet.cloud.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aerofleet.cloud.defect.DefectService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * sim-truth HTTP 调用的**读取超时**（连接超时只覆盖 TCP 握手）。
 *
 * <p>复现方式：本地 {@link HttpServer} 接受连接后**不响应**（或 8 秒后才响应）。
 * 客户端只设了 {@code connectTimeout}，没有 {@code HttpRequest.timeout} 时，
 * 调用线程会一直等到服务端关闭连接——生产上就是「请求线程被一个不响应的
 * sidecar 永久占住」。修复后应在 3 秒内抛 {@link HttpTimeoutException} /
 * 走失败分支返回 null。
 *
 * <p>{@code @Timeout(20)} 是回归保护：若有人删掉读超时，用例最多等 8 秒后
 * 以「异常类型不对 / 耗时超界」判红，而不是把整个测试套件挂死。
 */
@DisplayName("sim-truth HTTP 读取超时（不响应/慢响应的 sidecar 不得挂死调用线程）")
class SimTruthRequestTimeoutTest {

    private static final long SERVER_DELAY_MS = 8_000;
    private static final long MAX_ELAPSED_MS = 6_000;

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hang", exchange -> {
            sleepQuietly();
            closeQuietly(exchange);
        });
        server.createContext("/camera", exchange -> {
            sleepQuietly();
            try {
                byte[] body = new byte[] {1, 2, 3};
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // 客户端已按读超时断开：预期路径，不再回写
            } finally {
                closeQuietly(exchange);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "hung-sim-truth");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("CaptureService.getJson：服务端不响应 ⇒ 读超时抛 HttpTimeoutException")
    void captureJsonTimesOut() {
        CaptureService service = newCaptureService();

        assertThatThrownBy(() -> service.getJson(base + "/hang"))
                .isInstanceOf(HttpTimeoutException.class);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("CaptureService.fetchJpeg：慢响应超过读超时 ⇒ 返回 null 且不拖到服务端响应")
    void captureJpegTimesOut() {
        CaptureService service = newCaptureService();

        long start = System.nanoTime();
        byte[] jpeg = service.fetchJpeg(1);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(jpeg).as("读超时应走失败分支返回 null").isNull();
        assertThat(elapsedMs)
                .as("不得等到服务端 8s 后才返回")
                .isLessThan(MAX_ELAPSED_MS);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("OrbitService.getJson：服务端不响应 ⇒ 读超时抛 HttpTimeoutException")
    void orbitJsonTimesOut() {
        OrbitService service = new OrbitService(
                mock(DroneCommandService.class),
                mock(GeolocationSolver.class),
                mock(TargetTracker.class),
                new ObjectMapper(),
                base);

        assertThatThrownBy(() -> service.getJson(base + "/hang"))
                .isInstanceOf(HttpTimeoutException.class);
    }

    private static CaptureService newCaptureService() {
        return new CaptureService(
                mock(DroneCommandService.class),
                mock(DeviceRegistry.class),
                mock(GeolocationSolver.class),
                new ObjectMapper(),
                mock(CvEvalService.class),
                mock(DefectService.class),
                mock(ObjectProvider.class),
                base,
                "truth");
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(SERVER_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(HttpExchange exchange) {
        try {
            exchange.close();
        } catch (RuntimeException ignored) {
            // 连接已被客户端超时关闭：关闭时的状态异常无需上报
        }
    }
}
