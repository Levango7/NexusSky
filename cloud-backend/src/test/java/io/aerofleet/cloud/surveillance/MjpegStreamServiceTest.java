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
        // 边界（变异收口）：3 字节（FF D8 FF）已越过 SOI+JPEG 标记，必须判真
        assertThat(MjpegStreamService.looksLikeJpeg(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})).isTrue();
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

    @Test
    @DisplayName("fps=0/负数走 200ms 兜底节奏——兜底不能变成除零崩溃")
    void pump_fpsZeroFallsBackToDefaultInterval() throws Exception {
        snapshotBody = ascii(jpeg(16));
        MjpegStreamService svc = newService();
        byte[] first = svc.fetchFrame(baseUrl + "/snapshot");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        snapshotStatus = 500; // 开流即断：只写首帧
        svc.pump(baseUrl + "/snapshot", 0, first, out);

        assertThat(countParts(out)).isEqualTo(1);
    }

    /**
     * 调度契约（变异收口）：interval 是真实节流。把"首轮对表"翻转
     * （{@code nextAt = now + interval → now - interval}）会让第二帧立刻冲出去，
     * 上述节奏在常规测试断言（帧数/内容）下不可见，只有时间下限能拦。
     * 下限取得宽松（名义 1000ms，断言 ≥400ms）：慢机器只会把间隔拉得更晚。
     */
    @Test
    @DisplayName("fps=1 时第二帧不早于节流下限到达（对表节奏是真实节流）")
    void pump_secondFrameRespectsInterval() throws Exception {
        snapshotBody = ascii(jpeg(16));
        MjpegStreamService svc = newService();
        byte[] first = svc.fetchFrame(baseUrl + "/snapshot");

        long startAt = System.nanoTime();
        TapStream out = new TapStream();
        Thread t = new Thread(() -> {
            try {
                svc.pump(baseUrl + "/snapshot", 1, first, out);
            } catch (Exception ignored) {
                // 中断收尾即可
            }
        });
        t.start();
        waitFor(() -> out.secondPartAtNanos != 0, 8000);
        snapshotStatus = 500;
        t.interrupt();
        t.join(3000);

        assertThat(out.secondPartAtNanos)
                .as("第二帧必须出现过（帧源在 8s 内保持供帧）").isNotZero();
        long elapsedMs = (out.secondPartAtNanos - startAt) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(400);
    }

    /**
     * 可中断性契约（变异收口）：pump 被 {@code interrupt} 时的收尾必须在
     * {@code catch} 里<b>先重置中断标记再返回</b>。如果把这一行删掉，pump 照样
     * 正常返回（帧数/flush 等断言全都无感），但调用线程的"我被要求停"信号
     * 被吞掉——线程池复用该线程时，下一个任务永远感知不到自己已被中断。
     * 只有在独立线程里等它返回、再查线程的中断状态才抓得到。
     */
    @Test
    @DisplayName("pump 收到中断后尽快返回，且线程中断标记被保留")
    void pump_interruptPreservesInterruptFlag() throws Exception {
        snapshotBody = ascii(jpeg(16));
        MjpegStreamService svc = newService();
        byte[] first = svc.fetchFrame(baseUrl + "/snapshot");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread t = new Thread(() -> {
            try {
                // fps=1 ⇒ 首帧之后 pump 进入 1000ms 的 sleep；中断会落在 sleep 里
                svc.pump(baseUrl + "/snapshot", 1, first, out);
            } catch (Exception ignored) {
                // 帧源一直活着，唯一出路就是这次中断
            }
        });
        t.start();
        Thread.sleep(250); // 稳定落在第一次 sleep（约 1000ms）内
        t.interrupt();
        t.join(3000);

        assertThat(t.isAlive())
                .as("收到中断后 pump 必须尽快返回（不许滞留在循环里）").isFalse();
        assertThat(t.isInterrupted())
                .as("pump 结束必须保留中断标记——sleep 抛出时 JVM 会清掉标记，"
                        + "catch 里必须显式恢复（这是给调用线程的契约，不是装饰）")
                .isTrue();
    }

    @Test
    @DisplayName("第 3 帧照常出现、帧字节完整落流、每段都 flush（半帧不许丢）")
    void pump_thirdPartFullBytesAndFlushes() throws Exception {
        byte[] frame = jpeg(64);
        snapshotBody = ascii(frame);
        MjpegStreamService svc = newService();
        byte[] first = svc.fetchFrame(baseUrl + "/snapshot");

        TapStream out = new TapStream();
        Thread t = new Thread(() -> {
            try {
                svc.pump(baseUrl + "/snapshot", 50, first, out);
            } catch (Exception ignored) {
                // 断源收尾即可
            }
        });
        t.start();
        waitFor(() -> countParts(out.bytes()) >= 4, 5000);
        snapshotStatus = 500;
        t.join(3000);

        String text = ascii(out.bytes());
        // 帧号跨三帧仍递增（若第二段起计数回退，"X-Frame-Seq: 3" 永远不出现）
        assertThat(text).contains("X-Frame-Seq: 3");
        // 帧字节必须完整出现在流中（只写半帧会找不到完整序列）
        assertThat(indexOf(out.bytes(), frame)).isGreaterThanOrEqualTo(0);
        // 每段 part 一次 flush：首帧 + 至少一段续帧
        assertThat(out.flushCount.get()).isGreaterThanOrEqualTo(2);

        // multipart 框架逐字钉死（变异收口）：每一段的帧数据后面必须紧跟 CRLF 再接
        // 下一段的 boundary。writePart 尾部的 {@code out.write(CRLF)}（L165）被删掉后，
        // 帧字节会与下一个 "--" 直接相接——帧数计数与帧内容断言全都察觉不到，
        // 但真实浏览器会把残缺的 multipart 解析成坏帧。
        byte[] raw = out.bytes();
        int from = 0;
        int framesSeen = 0;
        while (true) {
            int at = indexOf(raw, frame, from);
            if (at < 0) {
                break;
            }
            framesSeen++;
            assertThat(at + frame.length + 2 <= raw.length)
                    .as("第 %d 帧之后必须有分隔字节", framesSeen).isTrue();
            assertThat(raw[at + frame.length]).as("第 %d 帧后必须是 \\r", framesSeen).isEqualTo((byte) '\r');
            assertThat(raw[at + frame.length + 1]).as("第 %d 帧后必须是 \\n", framesSeen).isEqualTo((byte) '\n');
            from = at + 1;
        }
        assertThat(framesSeen).as("至少两段帧完整落流").isGreaterThanOrEqualTo(2);
    }

    /** 计数/计时输出流：记 flush 次数与各 part 首次出现的时刻。 */
    private static final class TapStream extends OutputStream {
        final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        final AtomicInteger flushCount = new AtomicInteger();
        /** "X-Frame-Seq: 2"（第二段 part 的帧号头）首次出现的时刻。 */
        volatile long secondPartAtNanos;

        byte[] bytes() {
            return delegate.toByteArray();
        }

        @Override
        public void write(int b) {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            String chunk = new String(b, off, len, StandardCharsets.ISO_8859_1);
            if (secondPartAtNanos == 0 && chunk.contains("X-Frame-Seq: 2")) {
                secondPartAtNanos = System.nanoTime();
            }
            delegate.write(b, off, len);
        }

        @Override
        public void flush() {
            flushCount.incrementAndGet();
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int countParts(ByteArrayOutputStream out) {
        return countParts(out.toByteArray());
    }

    private static int countParts(byte[] raw) {
        String s = ascii(raw);
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