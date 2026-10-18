package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把设备的 HTTP 单帧快照源转成浏览器能播的 MJPEG 流。
 *
 * <h2>为什么是 MJPEG 而不是 RTSP 或 HLS</h2>
 *
 * <ul>
 *   <li><b>RTSP</b>：浏览器不能原生播。本平台也没有 RTSP 解码器（不引入 ffmpeg /
 *       javacv 依赖），所以 {@code /stream} 给出的地址对浏览器没有用 ——
 *       界面只能显示"RTSP 流需后端转码"，而"转码"这件事并不存在。
 *       <b>取不到源解决不了这个问题。</b></li>
 *   <li><b>HLS</b>：需要切片与转封装，且前端要为它加载 hls.js 并处理
 *       {@code MANIFEST_PARSED}；单设备预览不值得这份复杂度。</li>
 *   <li><b>MJPEG</b>：{@code multipart/x-mixed-replace} 里一帧一个 JPEG，
 *       浏览器用 {@code <img src>} 直接渲染，零插件、零依赖。首帧即可见。</li>
 * </ul>
 *
 * <h2>为什么轮询快照而不是直接透传上游的 MJPEG</h2>
 *
 * <p>透传看着更省事，但上游 multipart 的 boundary 与响应头里的 Content-Type
 * 绑定，换一个 boundary 就要重写每一帧的分隔符与头，脆且难测。这里改为
 * <b>按固定帧率取单帧、自己发 multipart</b>：每个 part 都独立校验过是 JPEG，
 * 帧率可控，上游是快照端点还是别的都不影响。
 *
 * <h2>失败语义</h2>
 *
 * <p>帧源不可达时<b>不返回假流</b>：先在响应头之前探一次，失败即明确报错，
 * 并说明连的是什么地址、什么错。已经开流之后掉线则结束该响应（浏览器会自动
 * 重连），不塞占位帧 —— 塞占位帧会让"有画面"和"画面是死的"看起来一样。
 */
@Service
public class MjpegStreamService {

    private static final Logger log = LoggerFactory.getLogger(MjpegStreamService.class);
    private static final String BOUNDARY = "aerofleetframe";
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final long CONNECT_TIMEOUT_MS = 2000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 取一帧 JPEG。
     *
     * @return JPEG 字节；不可达或不是 JPEG 时返回 {@code null}
     */
    public byte[] fetchFrame(String snapshotUrl) {
        if (snapshotUrl == null || snapshotUrl.isBlank()) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(snapshotUrl.trim()))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.warn("snapshot {} -> HTTP {}", snapshotUrl, resp.statusCode());
                return null;
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) {
                return null;
            }
            if (!looksLikeJpeg(body)) {
                // 关键：拿到 HTML 错误页或空响应时，宁可判定失败也不要把它当帧发出去。
                // 否则界面会显示一张"画得出的图"，而设备其实没在推流。
                log.warn("snapshot {} returned {} bytes that are not JPEG", snapshotUrl, body.length);
                return null;
            }
            return body;
        } catch (IllegalArgumentException e) {
            log.warn("snapshot {} is not a usable URI: {}", snapshotUrl, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("snapshot {} unreachable: {}", snapshotUrl, e.toString());
            return null;
        }
    }

    /** JPEG SOI 标记 {@code 0xFFD8}。 */
    static boolean looksLikeJpeg(byte[] b) {
        return b != null && b.length >= 3
                && (b[0] & 0xFF) == 0xFF
                && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF;
    }

    /**
     * 把帧持续写成 {@code multipart/x-mixed-replace}，直到客户端断开或帧源掉线。
     *
     * <p>调用方负责在<b>写响应头之前</b>先 {@link #fetchFrame} 探一帧并作为
     * {@code firstFrame} 传入：探不过就不该开流，否则浏览器会拿到一个 200 却永远
     * 等不到第一帧，界面上表现为"一直转圈"而不是报错。首帧直接复用探到的那张，
     * 不再取第二次。
     *
     * @param snapshotUrl 帧源地址
     * @param fps         目标帧率（<=0 视为 5）
     * @param firstFrame  已经探到并校验过的首帧
     * @param out         响应输出流
     */
    public void pump(String snapshotUrl, int fps, byte[] firstFrame, OutputStream out)
            throws IOException {
        long intervalMs = fps <= 0 ? 200 : Math.max(50, 1000L / fps);
        int seq = 1;
        writePart(out, firstFrame, seq++);
        out.flush();

        long nextAt = System.currentTimeMillis() + intervalMs;
        while (!Thread.currentThread().isInterrupted()) {
            long sleep = nextAt - System.currentTimeMillis();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                // 落后太多就重新对表，不追赶（追赶会让 CPU 打满且延迟越来越大）
                nextAt = System.currentTimeMillis();
            }
            byte[] frame = fetchFrame(snapshotUrl);
            if (frame == null || frame.length == 0) {
                // 帧源掉线：结束响应，让浏览器按自己的策略重连。**不发占位帧** ——
                // 占位帧会让"有画面"和"画面是死的"看起来一模一样。
                log.debug("mjpeg {} frame source dropped after {} frames", snapshotUrl, seq - 1);
                return;
            }
            writePart(out, frame, seq++);
            out.flush();
            nextAt += intervalMs;
        }
    }

    private static void writePart(OutputStream out, byte[] jpeg, int seq) throws IOException {
        out.write(("--" + BOUNDARY + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(("Content-Type: image/jpeg\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(("Content-Length: " + jpeg.length + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(("X-Frame-Seq: " + seq + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(CRLF);
        out.write(jpeg);
        out.write(CRLF);
    }

    private static final byte[] CRLF = "\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** multipart 的 Content-Type（含 boundary）。 */
    public static String contentType() {
        return "multipart/x-mixed-replace; boundary=" + BOUNDARY;
    }

    public static String boundary() {
        return BOUNDARY;
    }

    /** 仅用于测试：确认单帧不会超限。 */
    static int maxFrameBytes() {
        return MAX_FRAME_BYTES;
    }

    /** 把流读干（测试用）。 */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }
}