package io.aerofleet.cloud.vision;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExternalVisionSource} 外部推理服务客户端测试（F1，spec §4.1）。
 * <p>
 * 用内置 HttpServer 起本地 stub：200 合法 JSON / 200 非法 JSON / 500 /
 * 单条字段非法 / confidence 越界。覆盖失败不抛异常的契约（spec N1）。
 */
@DisplayName("ExternalVisionSource 外部推理服务客户端")
class ExternalVisionSourceTest {

    private HttpServer server;
    /** stub 当前响应：[0]=status，[1]=body。 */
    private final AtomicReference<int[]> statusRef = new AtomicReference<>(new int[]{200});
    private final AtomicReference<String> bodyRef = new AtomicReference<>("[]");
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>(new byte[0]);
    private String endpoint;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/infer", exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            int status = statusRef.get()[0];
            byte[] resp = bodyRef.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/infer";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ExternalVisionSource newSource(String ep) {
        return new ExternalVisionSource(ep, 2000);
    }

    private CameraShot shot() {
        return new CameraShot(1, System.currentTimeMillis(), 22.59, 113.93, 100,
                0, 0, 0, -90, 0, List.of());
    }

    private CameraPose pose() {
        return new CameraPose(0, 0, 0, -90, 0);
    }

    @Test
    @DisplayName("200+合法 JSON → 检出转换为 VisionDetection（像素坐标）")
    void validResponseParsed() {
        bodyRef.set("[{\"kind\":\"insulator\",\"confidence\":0.92,\"u\":120.5,\"v\":80.0},"
                + "{\"kind\":\"tower\",\"confidence\":0.77,\"u\":300,\"v\":200}]");

        List<VisionDetection> dets = newSource(endpoint).detect(shot(), pose(), new byte[]{1, 2, 3});

        assertThat(dets).hasSize(2);
        assertThat(dets.get(0).kind()).isEqualTo("insulator");
        assertThat(dets.get(0).u()).isEqualTo(120.5);
        assertThat(dets.get(0).confidence()).isEqualTo(0.92);
        assertThat(dets.get(0).trackId()).isEqualTo(-1);
    }

    @Test
    @DisplayName("请求体为原始 JPEG 字节，Content-Type image/jpeg")
    void postsJpegBytes() {
        bodyRef.set("[]");
        byte[] fakeJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

        newSource(endpoint).detect(shot(), pose(), fakeJpeg);

        assertThat(receivedBody.get()).isEqualTo(fakeJpeg);
    }

    @Test
    @DisplayName("200+非法 JSON → 空列表不抛（spec N1）")
    void malformedJsonReturnsEmpty() {
        bodyRef.set("not-json{{{");

        assertThat(newSource(endpoint).detect(shot(), pose(), new byte[]{1}))
                .isEmpty();
    }

    @Test
    @DisplayName("500 → 空列表不抛（spec N1）")
    void serverErrorReturnsEmpty() {
        statusRef.set(new int[]{500});
        bodyRef.set("[]");

        assertThat(newSource(endpoint).detect(shot(), pose(), new byte[]{1}))
                .isEmpty();
    }

    @Test
    @DisplayName("单条字段缺失/越界 → 该条丢弃，其余保留")
    void invalidEntriesSkipped() {
        bodyRef.set("[{\"kind\":\"ok\",\"confidence\":0.5,\"u\":1,\"v\":2},"
                + "{\"kind\":\"no-conf\",\"u\":3,\"v\":4},"
                + "{\"kind\":\"bad-conf\",\"confidence\":1.5,\"u\":5,\"v\":6},"
                + "{\"kind\":\"\",\"confidence\":0.5,\"u\":7,\"v\":8}]");

        List<VisionDetection> dets = newSource(endpoint).detect(shot(), pose(), new byte[]{1});

        assertThat(dets).hasSize(1);
        assertThat(dets.get(0).kind()).isEqualTo("ok");
    }

    @Test
    @DisplayName("endpoint 为空（N2）→ detect 恒返回空列表")
    void emptyEndpointReturnsEmpty() {
        assertThat(newSource("").detect(shot(), pose(), new byte[]{1})).isEmpty();
        assertThat(newSource("  ").detect(shot(), pose(), new byte[]{1})).isEmpty();
    }

    @Test
    @DisplayName("jpeg 为 null/空 → 空列表")
    void nullJpegReturnsEmpty() {
        assertThat(newSource(endpoint).detect(shot(), pose(), null)).isEmpty();
        assertThat(newSource(endpoint).detect(shot(), pose(), new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("元数据级 detect() 返回空（外部源只走 jpeg 重载）")
    void metadataLevelDetectReturnsEmpty() {
        assertThat(newSource(endpoint).detect(shot(), pose())).isEmpty();
    }
}
