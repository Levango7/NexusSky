package io.aerofleet.sim.comm;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.transport.UdpIngestObserver;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p><b>钉住的五条事实</b>：
 * <ol>
 *   <li>配了默认目标 ⇒ 发往默认目标；没配 ⇒ 发往最近对端；两者都没 ⇒ 静默丢弃不抛。</li>
 *   <li>{@code transport} 为 null 在构造期就拒绝（把配置错误挡在启动期）。</li>
 *   <li>{@code close()} 关掉底层：关闭后再 {@code send} 抛 {@code IOException}，
 *       而不是把帧悄悄吞掉。</li>
 *   <li>{@code addFrameListener / getLocalPort / unwrap} 如实透传到底层。</li>
 *   <li>（2026-11-10 补）<b>收包侧三条契约</b>：一个数据报内连续多帧全部交付；
 *       监听器抛异常被隔离且计入 {@code onListenerError}；长度等于接收缓冲上限时报截断。
 *       这三条此前无任何测试覆盖，而它们正是「帧莫名消失」与「收包线程静默停摆」的
 *       唯一可观测入口——异步化重构（收包与业务解耦）必须先有它们托底。</li>
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
    @DisplayName("addFrameListener 透传到底层 + 收包侧三条契约：包内多帧、监听器异常隔离、满包截断")
    void frameListenerReceivesInboundFrame() throws IOException {
        // 先注入观察者：收包线程每轮循环开头重读这个 volatile 字段，所以第二个及以后
        // 的数据报一定能被观测到（第一个可能仍用 NOOP，故不对它做观察者断言）。
        AtomicInteger listenerErrors = new AtomicInteger();
        AtomicInteger truncated = new AtomicInteger();
        transport.setIngestObserver(new UdpIngestObserver() {
            @Override public void onDatagramReceived(int bytes) { }
            @Override public void onParseErrors(int crcErrors, long noiseBytes) { }
            @Override public void onTruncatedDatagram(int bytes) { truncated.set(bytes); }
            @Override public void onListenerError() { listenerErrors.incrementAndGet(); }
            @Override public void onReceiveError() { }
        });

        UdpTransportAdapter adapter = new UdpTransportAdapter(transport);
        List<byte[]> got = new CopyOnWriteArrayList<>();
        adapter.addFrameListener(f -> got.add(f.encodeV2()));

        MavlinkFrame f = frame(7, (byte) 1, (byte) 2, (byte) 3);
        peerSendsTo(f.encodeV2(), transportAddress());

        await(() -> !got.isEmpty());
        assertThat(got).hasSize(1);
        assertThat(got.get(0)).isEqualTo(f.encodeV2());

        // 契约 1：一个 UDP 包内连续两帧必须都交付。MAVLink 是流式协议，
        // 只解析第一帧会让高频注入下的吞吐凭空减半，且现象是「帧莫名消失」——
        // 在指标上看起来跟摄入丢包一模一样，极难归因。
        byte[] f1 = frame(7, (byte) 4).encodeV2();
        byte[] f2 = frame(7, (byte) 5).encodeV2();
        byte[] twoInOne = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, twoInOne, 0, f1.length);
        System.arraycopy(f2, 0, twoInOne, f1.length, f2.length);
        peerSendsTo(twoInOne, transportAddress());
        await(() -> got.size() >= 3);
        assertThat(got).as("同一数据报内的连续两帧都要回调，不能只解析第一帧").hasSize(3);

        // 契约 2：监听器抛异常必须被隔离，且计入 onListenerError。
        // 否则一个坏监听器会让整条收包线程静默停摆，而线上只能看到「没有数据了」。
        List<byte[]> afterBad = new CopyOnWriteArrayList<>();
        adapter.addFrameListener(x -> { throw new IllegalStateException("boom"); });
        adapter.addFrameListener(x -> afterBad.add(x.encodeV2()));

        peerSendsTo(frame(7, (byte) 6).encodeV2(), transportAddress());
        await(() -> !afterBad.isEmpty());
        assertThat(afterBad).as("抛异常的监听器不得阻断后续监听器").hasSize(1);
        assertThat(listenerErrors.get())
                .as("异常必须被计入 onListenerError，否则线上无从归因").isGreaterThanOrEqualTo(1);

        // 契约 3：长度等于接收缓冲上限 ⇒ 报截断。
        // 这是 JVM 内唯一能直接观测到的「缓冲不足」信号；内核队列溢出无法直接计数。
        peerSendsTo(new byte[2048], transportAddress());
        await(() -> truncated.get() > 0);
        assertThat(truncated.get()).as("2048B 满包必须触发截断信号").isGreaterThan(0);
    }
}
