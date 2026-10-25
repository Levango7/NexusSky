package io.aerofleet.cloud.ntrip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NTRIP 客户端的契约测试。
 * <p>
 * <b>不依赖任何外部 caster</b>：用 {@link ServerSocket} 在本机起一个假 caster，
 * 由它按 NTRIP 的真实字节序列回应（{@code ICY 200 OK} / {@code HTTP/1.1 200 OK} / 401），
 * 然后发真实 RTCM 帧。这样测的是**协议字节**，不是"我们自己的 mock 会不会被自己骗过"。
 * <p>
 * 为什么值得这么写：NTRIP 的坑全在字节层——{@code ICY} 不是合法 HTTP 状态行、
 * 头部之后紧跟二进制流不能多读一个字节、RTCM 帧会跨 read 边界。
 * 这些用 mock 对象都测不出来。
 */
class NtripClientTest {

    // ---------- 1. 请求构造 ----------

    @Test
    @DisplayName("请求行与 NTRIP 必需头齐全；带认证时用 Basic base64")
    void buildsRequestWithAuth() {
        NtripClient c = new NtripClient("caster.example", 2101, "RTCM32", "user", "pass");
        String req = new String(c.buildRequest(), StandardCharsets.UTF_8);

        assertTrue(req.startsWith("GET /RTCM32 HTTP/1.1\r\n"), "请求行应带前导斜杠：" + req);
        assertTrue(req.contains("Host: caster.example:2101\r\n"));
        assertTrue(req.contains("Ntrip-Version: Ntrip/2.0\r\n"));
        assertTrue(req.contains("User-Agent: NTRIP "));
        // base64("user:pass") = dXNlcjpwYXNz
        assertTrue(req.contains("Authorization: Basic dXNlcjpwYXNz\r\n"), req);
        assertTrue(req.endsWith("\r\n\r\n"), "头部必须以空行结束");
    }

    @Test
    @DisplayName("无认证时不得发 Authorization 头（公共 caster 会因此拒绝）")
    void omitsAuthWhenNoUser() {
        NtripClient c = new NtripClient("h", 2101, "/M", null, null);
        String req = new String(c.buildRequest(), StandardCharsets.UTF_8);
        assertFalse(req.contains("Authorization:"));
    }

    @Test
    @DisplayName("挂载点不带斜杠时自动补上（NTRIP 请求行必须是绝对路径）")
    void normalizesMountpoint() {
        String req = new String(new NtripClient("h", 1, "MOUNT", null, null).buildRequest(),
                StandardCharsets.UTF_8);
        assertTrue(req.startsWith("GET /MOUNT "));
    }

    // ---------- 2. 状态行判定 ----------

    @Test
    @DisplayName("ICY 200 OK（NTRIP 1.0）与 HTTP/1.1 200 OK（2.0）都算成功")
    void acceptsBothStatusLineFlavors() {
        assertTrue(NtripClient.isSuccess("ICY 200 OK"));
        assertTrue(NtripClient.isSuccess("HTTP/1.1 200 OK"));
        assertTrue(NtripClient.isSuccess("HTTP/1.0 200 OK"));
        assertFalse(NtripClient.isSuccess("HTTP/1.1 401 Unauthorized"));
        assertFalse(NtripClient.isSuccess("SOURCETABLE 200 OK"), "SOURCE 响应不是流，不能当成功");
        assertFalse(NtripClient.isSuccess(""));
    }

    // ---------- 3. 真连接：成功路径 ----------

    @Test
    @DisplayName("连上假 caster：ICY 握手后读到的第一个字节就是 RTCM 前导码（头部没有多吞）")
    void connectsAndReadsFirstByteWithoutSwallowing() throws Exception {
        byte[] rtcm = Rtcm3Framer.buildFrame(new byte[]{1, 2, 3, 4, 5});
        try (FakeCaster caster = new FakeCaster("ICY 200 OK\r\nNtrip-Version: Ntrip/2.0\r\n\r\n", rtcm)) {
            NtripClient c = new NtripClient("127.0.0.1", caster.port(), "M", null, null, 3000, 3000);
            c.connect();
            assertTrue(c.isConnected());

            byte[] buf = new byte[64];
            int n = c.read(buf);
            assertTrue(n > 0, "应读到数据");
            // 关键：第一个字节必须是 0xD3，说明头部只读到空行为止
            assertEquals(0xD3, buf[0] & 0xFF, "头部之后多读的字节会让 RTCM 流错位");
            c.close();
            assertFalse(c.isConnected());
        }
    }

    @Test
    @DisplayName("401 时抛 NtripException 且消息里带状态行（排障全靠它）")
    void rejectsNon200() throws Exception {
        try (FakeCaster caster = new FakeCaster(
                "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"ntrip\"\r\n\r\n",
                new byte[0])) {
            NtripClient c = new NtripClient("127.0.0.1", caster.port(), "M", "u", "bad", 3000, 3000);
            NtripClient.NtripException e =
                    assertThrows(NtripClient.NtripException.class, c::connect);
            assertTrue(e.getMessage().contains("401"), e.getMessage());
            assertFalse(c.isConnected());
        }
    }

    @Test
    @DisplayName("对端直接关闭（无响应）时报「未返回任何响应」，不是空指针")
    void handlesSilentClose() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    // 立刻关掉，什么都不写
                } catch (Exception ignored) {
                    // 测试线程内异常无需上报
                }
            });
            t.setDaemon(true);
            t.start();
            NtripClient c = new NtripClient("127.0.0.1", ss.getLocalPort(), "M", null, null, 3000, 3000);
            NtripClient.NtripException e =
                    assertThrows(NtripClient.NtripException.class, c::connect);
            assertTrue(e.getMessage().contains("未返回任何响应"), e.getMessage());
        }
    }

    // ---------- 4. 真连接：端到端帧切分 ----------

    @Test
    @DisplayName("端到端：假 caster 发 3 条帧，客户端 + framer 切出 3 条且内容逐字节相同")
    void endToEndFraming() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            sent.add(Rtcm3Framer.buildFrame(payloadOf(i * 7)));
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (byte[] f : sent) {
            stream.write(f);
        }

        try (FakeCaster caster = new FakeCaster("ICY 200 OK\r\n\r\n", stream.toByteArray())) {
            NtripClient c = new NtripClient("127.0.0.1", caster.port(), "M", null, null, 3000, 3000);
            c.connect();

            Rtcm3Framer framer = new Rtcm3Framer();
            List<byte[]> got = new ArrayList<>();
            byte[] buf = new byte[97]; // 故意不是帧长的整数倍，逼出跨块重组
            long deadline = System.currentTimeMillis() + 5000;
            while (got.size() < 3 && System.currentTimeMillis() < deadline) {
                int n = c.read(buf);
                if (n < 0) {
                    break;
                }
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                got.addAll(framer.feed(chunk));
            }
            c.close();

            assertEquals(3, got.size(), "应切出 3 条帧，实际 " + got.size());
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(sent.get(i), got.get(i), "第 " + i + " 条帧内容必须逐字节相同");
            }
            assertEquals(3, framer.framesEmitted());
            assertEquals(0, framer.crcFailures());
        }
    }

    @Test
    @DisplayName("假 caster 收到的是我们发的那份请求（含 Authorization）")
    void casterSeesOurRequest() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    InputStream in = s.getInputStream();
                    ByteArrayOutputStream head = new ByteArrayOutputStream();
                    int prev = -1, cur;
                    while ((cur = in.read()) >= 0) {
                        head.write(cur);
                        if (prev == '\r' && cur == '\n') {
                            byte[] h = head.toByteArray();
                            if (h.length >= 4 && h[h.length - 2] == '\r' && h[h.length - 3] == '\n') {
                                break;
                            }
                        }
                        prev = cur;
                    }
                    seen.set(head.toString(StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    out.write("ICY 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    latch.countDown();
                } catch (Exception ignored) {
                    // 测试线程内异常无需上报
                }
            });
            t.setDaemon(true);
            t.start();

            NtripClient c = new NtripClient("127.0.0.1", ss.getLocalPort(), "RTCM32", "u", "p", 3000, 3000);
            c.connect();
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            c.close();

            String req = seen.get();
            assertTrue(req.contains("GET /RTCM32 HTTP/1.1"), req);
            assertTrue(req.contains("Authorization: Basic dTpw"), "base64(u:p) = dTpw，实际：" + req);
        }
    }

    // ---------- 辅助 ----------

    private static byte[] payloadOf(int n) {
        byte[] p = new byte[n];
        for (int i = 0; i < n; i++) {
            p[i] = (byte) (i * 3 + 1);
        }
        return p;
    }

    /** 本机假 caster：接受一个连接，回状态行 + 数据后关闭。 */
    private static final class FakeCaster implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;

        FakeCaster(String responseHead, byte[] body) throws Exception {
            this.server = new ServerSocket(0);
            this.worker = new Thread(() -> {
                try (Socket s = server.accept()) {
                    // 先把请求读掉（否则部分平台会 RST）
                    s.getInputStream().read(new byte[1024]);
                    OutputStream out = s.getOutputStream();
                    out.write(responseHead.getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                    out.flush();
                    Thread.sleep(200); // 让客户端读完再关
                } catch (Exception ignored) {
                    // 测试线程内异常无需上报
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            worker.join(2000);
            server.close();
        }
    }
}
