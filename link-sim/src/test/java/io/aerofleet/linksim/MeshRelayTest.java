package io.aerofleet.linksim;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一跳静态中继单测（design.md §6.1，9 个用例）。
 *
 * <p>同包 {@code io.aerofleet.linksim}，可访问包私有 {@link RelayConfig}/{@link RelayNode}
 * /{@link ImpairmentEngine}。每条用例用独立端口对，中继跑在 daemon 线程，测试结束随 JVM 退出回收。
 *
 * <p>地址学习前置：多数用例需先让飞机侧发包学习 {@code droneAddr}、GCS 侧发包学习
 * {@code gcsAddr}，否则上行/下行等待期会静默丢弃（FR-11）。
 */
class MeshRelayTest {

    /** 端口分配基址（递增，避免用例间端口冲突）。 */
    private static final AtomicInteger PORT_BASE = new AtomicInteger(34541);
    private static final Pattern FWD_PATTERN = Pattern.compile("fwd=(\\d+)");

    /** 分配下一个可用端口（偶数递增 2 避免相邻占用）。 */
    private static int nextPort() {
        return PORT_BASE.getAndAdd(2);
    }

    /** 用 RelayConfig.parse 构造配置（画像 lan，测试构造器注入 engine 时画像不影响）。 */
    private static RelayConfig newCfg(int gcsPort, int relayPort) {
        return RelayConfig.parse(new String[]{
                "--relay", "--gcs-port", String.valueOf(gcsPort),
                "--relay-port", String.valueOf(relayPort),
                "--uplink-profile", "lan", "--downlink-profile", "lan"
        });
    }

    /** 无损伤 engine：delay=0、不丢包、不限带宽（10 参数全 0）。 */
    private static ImpairmentEngine lanEngine() {
        return new ImpairmentEngine(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 100% 丢包 engine：pGoodDrop=pBadDrop=1.0 → 总是丢弃。 */
    private static ImpairmentEngine dropAllEngine() {
        return new ImpairmentEngine(0, 0, 1.0, 1.0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * 用<b>真实 MAVLink v2 帧</b>证明一跳中继是字节级透明转发。
     *
     * <p><b>为什么必须用真实帧</b>：2026-10-12 拆除的多跳扩展曾有一整套测试
     * （MultiHopRelayTest，11 例）全绿，而它在真实帧上根本不可能工作——
     * 那些测试用的是 {@code new byte[13]} 这类合成数组。合成数组的"最后一字节"
     * 是随手填的 7，真实帧的最后一字节是 <b>CRC</b>。于是 11 个通过的测试
     * 一起掩盖了"读 CRC 当跳数、又覆写 CRC"这个致命缺陷。
     *
     * <p>现在这个用例以真实帧为输入，断言中继输出与输入逐字节相同。
     * 它同时是拆除后留下的回归护栏：有人再往转发路径里塞任何
     * "顺手改一下帧字节"的逻辑，这里会立刻红。
     */
    @Test
    @Timeout(20)
    void realMavlinkFrame_isForwardedByteForByte() throws Exception {
        int gcsPort = nextPort(), relayPort = nextPort();

        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, lanEngine(), lanEngine());
        startRelay(node);

        // 真实 MAVLink v2 帧：Heartbeat + **签名块**（INC bit0 置位）。
        //
        // 这里必须用四参 toFrame(..., signer) 重载。踩过的坑：三参 toFrame(sysid, compid, seq)
        // 走 MavlinkFrame.of(...)，incompatibilityFlags=0，产出的是未签名帧（21B）——
        // 而拆多跳的实测证据恰恰来自**签名**帧（34B，CRC 末字节 106）。用未签名帧当护栏，
        // 针对签名块 / applyStripSignature / 签名帧长度判据的回归全都抓不到。
        // 所以下面另有断言钉死"这确实是一帧签名帧"，注释与构造不同步的事故不会再复发。
        io.aerofleet.mavlink.security.MavlinkSigner signer =
                new io.aerofleet.mavlink.security.MavlinkSigner(true, "link-sim-real-frame-secret");
        io.aerofleet.mavlink.messages.Heartbeat hb =
                new io.aerofleet.mavlink.messages.Heartbeat(
                        0, 2, 6, 0, io.aerofleet.mavlink.enums.MavEnums.MAV_STATE_ACTIVE);
        byte[] real = hb.toFrame(42, 1, 0, signer).encodeV2();

        // 钉死帧形：签名帧 = 10 字节 v2 帧头 + payload + 2 字节 CRC + SIGNATURE_DATA_LENGTH
        int expectedLen = 10 + hb.encode().length + 2 + io.aerofleet.mavlink.MavlinkFrame.SIGNATURE_DATA_LENGTH;
        Assertions.assertEquals(1, real[2] & 0x01,
                "INC bit0 必须置位，否则这不是签名帧，本用例就退化成了未签名帧的护栏");
        Assertions.assertEquals(expectedLen, real.length,
                "签名帧长度应为 10+payload+2+" + io.aerofleet.mavlink.MavlinkFrame.SIGNATURE_DATA_LENGTH);

        try (DatagramSocket droneListener = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket gcs = new DatagramSocket()) {

            // 地址学习：relayPort 收过谁就记住谁是飞机，gcsPort 收过谁就记住谁是 GCS。
            // 注意后一个探针本身就会被当上行转发给飞机，所以要先排空它。
            send(droneListener, relayPort, new byte[]{9});   // 让中继学到 droneAddr
            Thread.sleep(80);
            send(gcs, gcsPort, new byte[]{2});               // 让中继学到 gcsAddr
            Thread.sleep(80);

            DatagramPacket drainPkt = new DatagramPacket(new byte[2048], 2048);
            droneListener.setSoTimeout(3000);
            try {
                droneListener.receive(drainPkt);             // 吃掉被转发的探针
            } catch (java.net.SocketTimeoutException ignored) {
                // 没收到也不致命：不影响下面的断言
            }

            // GCS -> 飞机：真实帧走上行
            send(gcs, gcsPort, real);

            byte[] buf = new byte[2048];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            droneListener.setSoTimeout(5000);
            droneListener.receive(pkt);
            byte[] got = Arrays.copyOf(buf, pkt.getLength());

            Assertions.assertArrayEquals(real, got,
                    "一跳中继必须字节级透明：输入真实帧 " + real.length + "B，输出 " + got.length + "B");
        }
    }

    /** 在 daemon 线程启动中继（run 阻塞，daemon 不阻止 JVM 退出）。 */
    private static Thread startRelay(RelayNode node) {
        Thread t = new Thread(() -> {
            try {
                node.run();
            } catch (Exception e) {
                // 中继线程异常不杀测试（端口冲突等已在构造期 exit）
            }
        }, "relay-test");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 发送 UDP 数据到 127.0.0.1:dstPort。 */
    private static void send(DatagramSocket from, int dstPort, byte[] data) throws IOException {
        from.send(new DatagramPacket(data, data.length, new InetSocketAddress("127.0.0.1", dstPort)));
    }

    /** 接收 UDP 数据，超时返回 null。 */
    private static byte[] recv(DatagramSocket s, int timeoutMs) throws IOException {
        s.setSoTimeout(timeoutMs);
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        try {
            s.receive(pkt);
            return Arrays.copyOf(buf, pkt.getLength());
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    /** 从 stats 字符串提取 fwd 计数。 */
    private static long forwarded(String stats) {
        Matcher m = FWD_PATTERN.matcher(stats);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** 绑定到 127.0.0.1 任意端口的测试 socket。 */
    private static DatagramSocket localSocket() throws IOException {
        return new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
    }

    // === 9 个用例 ===

    /** 用例1：上行透明转发——gcs 侧发包到 gcsPort，relay 侧收到字节完全一致（FR-02/05/24）。 */
    @Test
    @Timeout(10)
    void uplinkForwardBytesIntact() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, lanEngine(), lanEngine());
        startRelay(node);
        Thread.sleep(150);

        byte[] payload = "HELLO-DRONE".getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket drone = localSocket();
             DatagramSocket gcs = localSocket()) {
            // 先学习 droneAddr（飞机侧发包到 relayPort）
            send(drone, relayPort, new byte[]{1});
            Thread.sleep(150);
            // 上行发包
            send(gcs, gcsPort, payload);
            byte[] got = recv(drone, 2000);
            Assertions.assertNotNull(got, "上行应转发到 drone");
            Assertions.assertArrayEquals(payload, got, "上行字节应完全一致");
        }
    }

    /** 用例2：下行透明转发——飞机侧发包到 relayPort，gcs 侧收到字节完全一致（FR-02/06/24）。 */
    @Test
    @Timeout(10)
    void downlinkForwardBytesIntact() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, lanEngine(), lanEngine());
        startRelay(node);
        Thread.sleep(150);

        byte[] payload = "TELEMETRY-DATA".getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket drone = localSocket();
             DatagramSocket gcs = localSocket()) {
            // 先学习 gcsAddr（GCS 侧发包到 gcsPort）
            send(gcs, gcsPort, new byte[]{1});
            Thread.sleep(150);
            // 下行发包
            send(drone, relayPort, payload);
            byte[] got = recv(gcs, 2000);
            Assertions.assertNotNull(got, "下行应转发到 gcs");
            Assertions.assertArrayEquals(payload, got, "下行字节应完全一致");
        }
    }

    /** 用例3：双向独立损伤——uplink 100% drop、downlink LAN，断言上行全丢、下行正常（FR-14/15/16）。 */
    @Test
    @Timeout(10)
    void bidirectionalIndependentImpairment() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, dropAllEngine(), lanEngine());
        startRelay(node);
        Thread.sleep(150);

        try (DatagramSocket drone = localSocket();
             DatagramSocket gcs = localSocket()) {
            // 学习双方地址（drone 先发，gcs 后发；gcs 的学习包被 uplink 100% drop 但 gcsAddr 已学习）
            send(drone, relayPort, new byte[]{1});
            Thread.sleep(150);
            send(gcs, gcsPort, new byte[]{1});
            Thread.sleep(150);

            // 下行正常：drone 发包 → gcs 收到
            byte[] downPayload = "DOWN-OK".getBytes(StandardCharsets.UTF_8);
            send(drone, relayPort, downPayload);
            byte[] downGot = recv(gcs, 2000);
            Assertions.assertNotNull(downGot, "下行（LAN）应正常转发");
            Assertions.assertArrayEquals(downPayload, downGot, "下行字节一致");

            // 上行全丢：gcs 发包 → drone 收不到
            send(gcs, gcsPort, "UP-WILL-DROP".getBytes(StandardCharsets.UTF_8));
            byte[] upGot = recv(drone, 800);
            Assertions.assertNull(upGot, "上行（100% drop）应全部丢弃");
        }
    }

    /** 用例4：地址学习——飞机先发包学习 droneAddr，gcs 发命令被投递到 droneAddr（FR-09/10/11）。 */
    @Test
    @Timeout(10)
    void learnOnceDroneAddress() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, lanEngine(), lanEngine());
        startRelay(node);
        Thread.sleep(150);

        try (DatagramSocket drone = localSocket();
             DatagramSocket gcs = localSocket()) {
            // 飞机发包学习 droneAddr
            send(drone, relayPort, new byte[]{1});
            Thread.sleep(150);
            // gcs 发命令，应投递到 droneAddr（即 drone 的地址）
            byte[] cmd = "ARM".getBytes(StandardCharsets.UTF_8);
            send(gcs, gcsPort, cmd);
            byte[] got = recv(drone, 2000);
            Assertions.assertNotNull(got, "命令应投递到已学习 droneAddr");
            Assertions.assertArrayEquals(cmd, got, "命令字节一致");
            // 验证 cfg.droneAddr 已学习
            Assertions.assertNotNull(cfg.droneAddr, "droneAddr 应已被学习");
        }
    }

    /** 用例5：上行等待期——droneAddr 未学习前，gcs 发命令无转发（FR-11）。 */
    @Test
    @Timeout(10)
    void uplinkWaitPeriod() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, lanEngine(), lanEngine());
        startRelay(node);
        Thread.sleep(150);

        try (DatagramSocket drone = localSocket();
             DatagramSocket gcs = localSocket()) {
            // droneAddr 未学习，gcs 直接发命令
            send(gcs, gcsPort, "COMMAND".getBytes(StandardCharsets.UTF_8));
            // drone 侧收不到（上行等待期静默丢弃）
            byte[] got = recv(drone, 800);
            Assertions.assertNull(got, "droneAddr 未学习时上行不应转发");
        }
    }

    /** 用例6：丢弃语义——注入 100% drop engine，对端收不到任何包（FR-08）。 */
    @Test
    @Timeout(10)
    void dropSemantics() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, dropAllEngine(), dropAllEngine());
        startRelay(node);
        Thread.sleep(150);

        try (DatagramSocket drone = localSocket();
             DatagramSocket gcs = localSocket()) {
            // 学习双方地址（学习包不走 verdict，不被 drop）
            send(drone, relayPort, new byte[]{1});
            Thread.sleep(150);
            send(gcs, gcsPort, new byte[]{1});
            Thread.sleep(150);

            // 下行 100% drop：drone 发包 → gcs 收不到
            send(drone, relayPort, "DOWN-DROP".getBytes(StandardCharsets.UTF_8));
            byte[] downGot = recv(gcs, 800);
            Assertions.assertNull(downGot, "下行 100% drop 应丢弃");

            // 上行 100% drop：gcs 发包 → drone 收不到
            send(gcs, gcsPort, "UP-DROP".getBytes(StandardCharsets.UTF_8));
            byte[] upGot = recv(drone, 800);
            Assertions.assertNull(upGot, "上行 100% drop 应丢弃");
        }
    }

    /** 用例7：透明性——转发前后字节序列完全一致（含多字节 MAVLink 帧）（FR-23/24）。 */
    @Test
    @Timeout(10)
    void transparencyNoFrameModification() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, lanEngine(), lanEngine());
        startRelay(node);
        Thread.sleep(150);

        // 构造模拟 MAVLink v1 帧：0xFE + len + sysid + compid + msgid + payload(36B) + crc(2B) = 42B
        byte[] mavlinkFrame = new byte[42];
        mavlinkFrame[0] = (byte) 0xFE;       // 帧头
        mavlinkFrame[1] = 36;                 // 载荷长度
        mavlinkFrame[2] = 1;                  // sysid
        mavlinkFrame[3] = 1;                  // compid
        mavlinkFrame[4] = 76;                 // msgid (COMMAND_LONG)
        for (int i = 5; i < 40; i++) mavlinkFrame[i] = (byte) (i & 0xFF);  // payload
        mavlinkFrame[40] = 0x12;              // CRC low
        mavlinkFrame[41] = 0x34;              // CRC high

        try (DatagramSocket drone = localSocket();
             DatagramSocket gcs = localSocket()) {
            send(drone, relayPort, new byte[]{1});
            Thread.sleep(150);
            send(gcs, gcsPort, mavlinkFrame);
            byte[] got = recv(drone, 2000);
            Assertions.assertNotNull(got, "MAVLink 帧应转发");
            Assertions.assertArrayEquals(mavlinkFrame, got, "MAVLink 帧字节应逐字节一致");
        }
    }

    /** 用例8：第三方源不改变对端——learn-once 保持 gcsAddr 不被第三方覆盖（FR-12）。 */
    @Test
    @Timeout(10)
    void thirdPartySourceDoesNotChangePeer() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        RelayNode node = new RelayNode(cfg, lanEngine(), lanEngine());
        startRelay(node);
        Thread.sleep(150);

        try (DatagramSocket gcs1 = localSocket();
             DatagramSocket gcs2 = localSocket();
             DatagramSocket drone = localSocket()) {
            // gcs1 发包学习 gcsAddr
            send(gcs1, gcsPort, new byte[]{1});
            Thread.sleep(150);
            // gcs2（第三方源）发包，不应改变 gcsAddr
            send(gcs2, gcsPort, new byte[]{2});
            Thread.sleep(150);
            // drone 发包学习 droneAddr，下行应转发到 gcs1（learn-once 保持）
            byte[] data = "DATA".getBytes(StandardCharsets.UTF_8);
            send(drone, relayPort, data);
            byte[] got1 = recv(gcs1, 2000);
            Assertions.assertNotNull(got1, "下行应转发到已学习 gcs1");
            Assertions.assertArrayEquals(data, got1, "gcs1 收到字节一致");
            // gcs2 收不到（转发目标是 gcs1，非 gcs2）
            byte[] got2 = recv(gcs2, 800);
            Assertions.assertNull(got2, "第三方 gcs2 不应收到转发");
        }
    }

    /** 用例9：统计独立——上下行 forwarded 计数各自独立累计（FR-16）。 */
    @Test
    @Timeout(15)
    void independentStats() throws Exception {
        int gcsPort = nextPort();
        int relayPort = nextPort();
        RelayConfig cfg = newCfg(gcsPort, relayPort);
        ImpairmentEngine uplink = lanEngine();
        ImpairmentEngine downlink = lanEngine();
        RelayNode node = new RelayNode(cfg, uplink, downlink);
        startRelay(node);
        Thread.sleep(150);

        try (DatagramSocket drone = localSocket();
             DatagramSocket gcs = localSocket()) {
            // 学习双方地址（drone 学习包不走 verdict；gcs 学习包走 uplink verdict → uplink fwd=1）
            send(drone, relayPort, new byte[]{1});
            Thread.sleep(150);
            send(gcs, gcsPort, new byte[]{1});
            Thread.sleep(150);

            // 上行 10 包
            for (int i = 0; i < 10; i++) {
                send(gcs, gcsPort, new byte[]{(byte) i});
            }
            // 下行 3 包
            for (int i = 0; i < 3; i++) {
                send(drone, relayPort, new byte[]{(byte) i});
            }
            Thread.sleep(500);

            // uplink fwd=11（1 学习 + 10 正式），downlink fwd=3
            Assertions.assertEquals(11, forwarded(uplink.stats()), "上行统计独立累计");
            Assertions.assertEquals(3, forwarded(downlink.stats()), "下行统计独立累计");
        }
    }
}