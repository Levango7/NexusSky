package io.aerofleet.sim.comm;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UdpTransportAdapter} 的契约测试：拿真回环 UDP 把适配层的收发闭环钉住。
 *
 * <p><b>为什么要单独钉它</b>：SPI 可验证性门禁扫出本类"drone-sim 传输腿，测试源码里零引用"。
 * 它是 {@link MeshRouter} 在 {@code TransportType=UDP} 下的统一入口，而它的两个分支
 * （配了默认目标走组播、没配走最近对端）以及"关闭后是否还在悄悄收/发"都不会让任何
 * 既有单测转红——正是这类只在真机才暴露的缺陷。
 *
 * <p><b>刻意用真回环而不是替身</b>：本模块没有 mockito，且"序列化→DatagramSocket→对端解析"
 * 这条链上的任何一处错位（目标选择、关闭语义）只有真发真收才照得出来。
 *
 * <p><b>钉住的四条事实</b>：
 * <ol>
 *   <li>配了默认目标 ⇒ 发往默认目标；没配 ⇒ 发往最近对端；两者都没 ⇒ 静默丢弃不抛。</li>
 *   <li>{@code transport} 为 null 在构造期就拒绝（把配置错误挡在启动期）。</li>
 *   <li>{@code close()} 关掉底层：关闭后再 {@code send} 抛 {@code IOException}，
 *       而不是把帧悄悄吞掉。</li>
 *   <li>{@code addFrameListener / getLocalPort / unwrap} 如实透传到底层。</li>
 * </ol>
 */
@DisplayName("UdpTransportAdapter：UDP 分支选路、null 守卫、关闭语义、透传")
class UdpTransportAdapterContractTest {

    private UdpMavlinkTransport transport;
    private DatagramSocket peer;

    @BeforeEach
    void setUp() throws IOException {
        transport = new UdpMavlinkTransport("127.0.0.1", 0);
        peer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        peer.setSoTimeout(2000);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
        if (peer != null) {
            peer.close();
        }
    }

    private InetSocketAddress transportAddress() {
        return new InetSocketAddress("127.0.0.1", transport.getLocalPort());
    }

    private InetSocketAddress peerAddress() {
        return new InetSocketAddress("127.0.0.1", peer.getLocalPort());
    }

    private void peerSendsTo(byte[] data, InetSocketAddress to) throws IOException {
        peer.send(new DatagramPacket(data, data.length, to));
    }

    private byte[] peerReceivesOne() throws IOException {
        byte[] buf = new byte[512];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        peer.receive(p);
        return Arrays.copyOf(p.getData(), p.getLength());
    }

    private static MavlinkFrame frame(int sysid, byte... payload) {
        return MavlinkFrame.of(sysid, 1, 0, Heartbeat.ID, Heartbeat.CRC_EXTRA, payload);
    }

    /** 有界轮询：UDP 收包在独立线程，断言前必须等它落地，但绝不死等。 */
    private static void await(BooleanSupplier cond) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("等待条件在 3s 内未成立");
    }

    @Test
    @DisplayName("配了默认目标（mesh 组播地址）时 send 发往该目标")
    void sendGoesToDefaultDestinationWhenConfigured() throws IOException {
        UdpTransportAdapter adapter = new UdpTransportAdapter(transport, peerAddress());
        MavlinkFrame f = frame(7, (byte) 1, (byte) 2, (byte) 3);

        adapter.send(f);

        assertThat(peerReceivesOne()).isEqualTo(f.encodeV2());
    }

    @Test
    @DisplayName("没配默认目标时 send 发往最近对端（先由对端发一包把 lastPeer 喂出来）")
    void sendGoesToLastPeerWhenNoDefault() throws IOException {
        UdpTransportAdapter adapter = new UdpTransportAdapter(transport);
        peerSendsTo(frame(1, (byte) 9).encodeV2(), transportAddress());
        await(() -> transport.getLastPeer() != null);

        MavlinkFrame f = frame(7, (byte) 1, (byte) 2, (byte) 3);
        adapter.send(f);

        assertThat(peerReceivesOne()).isEqualTo(f.encodeV2());
    }

    @Test
    @DisplayName("无默认目标且从未收到过任何包：send 静默丢弃，不抛不阻塞")
    void sendWithNoLastPeerIsSilentlyDropped() {
        UdpTransportAdapter adapter = new UdpTransportAdapter(transport);

        assertThatCode(() -> adapter.send(frame(7, (byte) 1))).doesNotThrowAnyException();
        assertThat(transport.getLastPeer()).isNull();
    }

    @Test
    @DisplayName("transport 为 null：构造期就拒绝（把配置错误挡在启动期，而不是运行期 NPE）")
    void nullTransportRejectedAtConstruction() {
        assertThatThrownBy(() -> new UdpTransportAdapter(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transport must not be null");
    }

    @Test
    @DisplayName("unwrap() 交回被包装的原始 UDP 运输；getLocalPort() 透传")
    void unwrapAndPortDelegate() {
        UdpTransportAdapter adapter = new UdpTransportAdapter(transport);

        assertThat(adapter.unwrap()).isSameAs(transport);
        assertThat(adapter.getLocalPort()).isEqualTo(transport.getLocalPort());
    }

    @Test
    @DisplayName("close() 关掉底层：关闭后再 send 抛 IOException，而不是把帧悄悄吞掉")
    void sendAfterCloseFails() {
        UdpTransportAdapter adapter = new UdpTransportAdapter(transport, peerAddress());
        adapter.close();

        assertThatThrownBy(() -> adapter.send(frame(7, (byte) 1)))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("addFrameListener 透传到底层：对端发来的合法帧会回调到注册在适配器上的监听器")
    void frameListenerReceivesInboundFrame() throws IOException {
        UdpTransportAdapter adapter = new UdpTransportAdapter(transport);
        List<byte[]> got = new CopyOnWriteArrayList<>();
        adapter.addFrameListener(f -> got.add(f.encodeV2()));

        MavlinkFrame f = frame(7, (byte) 1, (byte) 2, (byte) 3);
        peerSendsTo(f.encodeV2(), transportAddress());

        await(() -> !got.isEmpty());
        assertThat(got).hasSize(1);
        assertThat(got.get(0)).isEqualTo(f.encodeV2());
    }
}
