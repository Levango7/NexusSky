package io.aerofleet.sim.comm;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.transport.UdpMavlinkTransport;
import io.aerofleet.sim.SimLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * UDP 传输适配器：将 {@link UdpMavlinkTransport} 适配为 {@link MavlinkTransport}。
 * <p>
 * 用于 {@link io.aerofleet.sim.mesh.MeshRouter} 在 {@code TransportType=UDP} 模式下
 * 的统一传输调用。包装现有 UDP 实现，保持向后兼容。
 * <p>
 * 发送策略：优先发往 {@link #defaultDestination}（如 mesh 组播地址），若未设置则
 * 发往最近已知对端（{@link UdpMavlinkTransport#sendToLastPeer}）。
 * <p>
 * 线程安全：委托给 UdpMavlinkTransport 的并发安全实现。
 *
 * @see MavlinkTransport 通用传输接口
 * @see UdpMavlinkTransport 被包装的 UDP 传输
 */
public final class UdpTransportAdapter implements MavlinkTransport {

    private final UdpMavlinkTransport transport;
    /** 默认发送目标（如 mesh 组播地址 239.0.0.1:14550），null 时发往最近对端。 */
    private final InetSocketAddress defaultDestination;

    /**
     * 构造 UDP 适配器，指定默认发送目标。
     *
     * @param transport          被包装的 UDP 传输，不能为 null
     * @param defaultDestination 默认发送目标（mesh 组播地址），null 时发往最近对端
     */
    public UdpTransportAdapter(UdpMavlinkTransport transport, InetSocketAddress defaultDestination) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.transport = transport;
        this.defaultDestination = defaultDestination;
    }

    /**
     * 构造 UDP 适配器，无默认目标（发往最近对端）。
     *
     * @param transport 被包装的 UDP 传输
     */
    public UdpTransportAdapter(UdpMavlinkTransport transport) {
        this(transport, null);
    }

    /** 被包装的原始 UDP 传输（供调用方访问 UDP 特有 API）。 */
    public UdpMavlinkTransport unwrap() {
        return transport;
    }

    @Override
    public void send(MavlinkFrame frame) throws IOException {
        if (defaultDestination != null) {
            transport.send(frame, defaultDestination);
        } else {
            transport.sendToLastPeer(frame);
        }
    }

    @Override
    public void addFrameListener(Consumer<MavlinkFrame> listener) {
        transport.addFrameListener(listener);
    }

    @Override
    public int getLocalPort() {
        return transport.getLocalPort();
    }

    @Override
    public void close() {
        transport.close();
        SimLog.info("UdpTransportAdapter closed: port=" + transport.getLocalPort());
    }
}