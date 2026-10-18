package io.aerofleet.cloud.surveillance;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MjpegStreamService} 单元测试。
 *
 * <p>用一个真的 HTTP 帧源（{@link HttpServer} 起在随机端口）而不是 mock，
 * 因为要验的正是"能不能真的从设备取到 JPEG 帧"——mock 掉 HTTP 客户端就等于
 * 把被测的东西一起 mock 掉了。
 */
@DisplayName("MjpegStreamService 从 HTTP 帧源取帧并发 multipart")
class MjpegStreamServiceTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger snapshotHits = new AtomicInteger();
    private volatile String snapshotBody = "";
    private volatile int snapshotStatus = 200;
    private volatile String snapshotContentType = "image/jpeg";

    private static byte[] jpeg(int n) {
        // 最小合法 JPEG 头：SOI + 一段假负载。真实编码器产出的更长，
        // 但本测试只关心"能取到 / 能不能被识别为 JPEG"，不关心熵编码。
        byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] body = new byte[n];
        for (int i = 0; i < n; i++) {
            body[i] = (byte) (i % 251);
        }
        byte[] tail = {(byte) 0xFF, (byte) 0xD9};
        byte[] all = new byte[head.length + body.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        System.arraycopy(tail, 0, all, head.length + body.length, tail.length);
        return all;
    }

    private static String ascii(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/snapshot", ex -> {
            snapshotHits.incrementAndGet();
            byte[] body = snapshotBody.getBytes(StandardCharsets.ISO_8859_1);
            ex.getResponseHeaders().add("Content-Type", snapshotContentType);
            ex.sendResponseHeaders(snapshotStatus, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private MjpegStreamService newService() {
        return new MjpegStreamService();
    }

    // ===== 取帧 =====

    @Test
    @DisplayName("从 HTTP 帧源取到 JPEG 帧")
    void fetchesJpegFrame() {
        snapshotBody = ascii(jpeg(64));

        byte[] got = newService().fetchFrame(baseUrl + "/snapshot");

        assertThat(got).isNotNull();
        assertThat(MjpegStreamService.looksLikeJpeg(got)).isTrue();
        // 期望值取自构帧函数本身，不手算 —— 上一版手算成 68（实际 70）而红。
        assertThat(got).isEqualTo(jpeg(64));
    }

    @Test
    @DisplayName("null / 空白地址直接返回 null（不是去请求一个空串）")
    void blankUrlReturnsNull() {
        assertThat(newService().fetchFrame(null)).isNull();
        assertThat(newService().fetchFrame("   ")).isNull();
    }

    @Test
    @DisplayName("不可达地址返回 null，不抛异常（上层据此报 502 而不是 500）")
    void unreachableReturnsNull() {
        // 端口 1 上不会有服务在听
        assertThat(newService().fetchFrame("http://127.0.0.1:1/snapshot")).isNull();
    }

    /**
     * 拿到 HTML 错误页时**必须**判失败。若把它当帧发出去，界面会显示一张
     * "画得出的图"，而设备其实没在推流 —— 这正是本轮要消灭的那类假象。
     */
    @Test
    @DisplayName("返回的不是 JPEG（如 HTML 错误页）时判失败，不当帧发")
    void nonJpegIsRejected() {
        snapshotBody = "<html><body>404 not found</body></html>";
        snapshotContentType = "text/html";

        assertThat(newService().fetchFrame(baseUrl + "/snapshot")).isNull();
    }

    @Test
    @DisplayName("HTTP 500 时判失败")
    void httpErrorRejected() {
        snapshotStatus = 500;
        snapshotBody = "boom";

        assertThat(newService().fetchFrame(baseUrl + "/snapshot")).isNull();
    }

    @Test
    @DisplayName("JPEG 识别：SOI 头才算，空数组/非 JPEG 都不算")
    void jpegDetection() {
        assertThat(MjpegStreamService.looksLikeJpeg(jpeg(8))).isTrue();
        assertThat(MjpegStreamService.looksLikeJpeg(null)).isFalse();
        assertThat(MjpegStreamService.looksLikeJpeg(new byte[0])).isFalse();
        assertThat(MjpegStreamService.looksLikeJpeg(new byte[]{(byte) 0xFF, (byte) 0xD8})).isFalse();
        assertThat(MjpegStreamService.looksLikeJpeg(new byte[]{0x47, 0x49, 0x46, 0x38})).isFalse();
    }

    // ===== multipart 输出 =====

    @Test
    @DisplayName("pump 发出的 multipart 含 boundary / 每帧 Content-Length / 递增帧号")
    void pumpEmitsMultipartParts() throws Exception {
        snapshotBody = ascii(jpeg(40));
        MjpegStreamService svc = newService();
        byte[] first = svc.fetchFrame(baseUrl + "/snapshot");
        assertThat(first).isNotNull();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 跑够几帧就停：pump 是无限循环，这里用 fps 拉高 + 帧源计数到阈值后停源，
        // 让它自然收尾（收尾路径本身也是被测行为）
        Thread t = new Thread(() -> {
            try {
                svc.pump(baseUrl + "/snapshot", 50, first, out);
            } catch (Exception ignored) {
                // 线程结束即可
            }
        });
        t.start();
        waitFor(() -> countParts(out) >= 3, 5000);
        snapshotStatus = 500; // 断源 => pump 应结束
        t.join(3000);

        String text = ascii(out.toByteArray());
        assertThat(text).contains("--" + MjpegStreamService.boundary());
        assertThat(text).contains("Content-Type: image/jpeg");
        assertThat(text).contains("Content-Length: " + jpeg(40).length);
        assertThat(text).contains("X-Frame-Seq: 1");
        assertThat(text).contains("X-Frame-Seq: 2");
        // 断源后不应再补占位帧：帧号不应凭空出现第 4 段
        assertThat(countParts(out)).isLessThan(6);
    }

    @Test
    @DisplayName("帧源中途断掉时 pump 结束而不是无限发占位帧")
    void pumpEndsWhenSourceDrops() throws Exception {
        snapshotBody = ascii(jpeg(20));
        MjpegStreamService svc = newService();
        byte[] first = svc.fetchFrame(baseUrl + "/snapshot");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        snapshotStatus = 500; // 开流即断：只剩首帧那一段
        svc.pump(baseUrl + "/snapshot", 10, first, out);

        assertThat(countParts(out)).isEqualTo(1);
    }

    private static int countParts(ByteArrayOutputStream out) {
        String s = ascii(out.toByteArray());
        int n = 0;
        int i = s.indexOf("X-Frame-Seq:");
        while (i >= 0) {
            n++;
            i = s.indexOf("X-Frame-Seq:", i + 1);
        }
        return n;
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("Content-Type 带 boundary，与输出实际使用的分隔符一致")
    void contentTypeCarriesBoundary() {
        assertThat(MjpegStreamService.contentType())
                .isEqualTo("multipart/x-mixed-replace; boundary=" + MjpegStreamService.boundary());
    }
}