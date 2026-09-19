package io.aerofleet.sim.comm;

import io.aerofleet.mavlink.MavlinkFrame;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * MAVLink 通用传输层接口（M5 应急 mesh，3d 集成）。
 * <p>
 * 抽象 UDP 与 LoRa 两种底层传输，向上提供统一的帧级 send/receive 接口。
 * {@link MeshRouter} 通过此接口解耦底层传输细节，运行时根据
 * {@code MeshRouterConfig.transportType} 选择 UDP 或 LoRa 适配器。
 * <p>
 * 线程安全：实现需保证 {@link #send(MavlinkFrame)} 可被多线程并发调用，
 * {@link #addFrameListener(Consumer)} 可在任意时刻注册。
 * <p>
 * 生命周期：实现 {@link AutoCloseable#close()} 释放底层资源（线程、套接字、队列）。
 *
 * @see UdpTransportAdapter UDP 适配器（包装 UdpMavlinkTransport）
 * @see LoRaTransportAdapter LoRa 适配器（包装 LoRaMavlinkTransport，通过共享信道互通）
 */
public interface MavlinkTransport extends AutoCloseable {

    /**
     * 发送一个完整 MAVLink 帧。
     * <p>
     * UDP 实现：序列化后通过 DatagramSocket 发往最近对端或组播地址。
     * LoRa 实现：分片后入共享"空中信道"队列，由接收端取出重组。
     *
     * @param frame 待发送的 MAVLink 帧，不能为 null
     * @throws IOException 发送失败（如套接字已关闭）
     */
    void send(MavlinkFrame frame) throws IOException;

    /**
     * 注册帧监听器：收到完整 MAVLink 帧时回调。
     * <p>
     * 可注册多个监听器，实现按消息类型分发。监听器在接收线程中被回调，
     * 实现方需保证单个监听器异常不会拖垮接收循环。
     *
     * @param listener 帧回调，不能为 null
     */
    void addFrameListener(Consumer<MavlinkFrame> listener);

    /**
     * 返回本传输绑定的本地端口或信道标识。
     * <p>
     * UDP 实现返回 DatagramSocket 本地端口；LoRa 实现返回配置的信道标识
     * （如 -1 或 LoRa channel 编号），仅用于日志与诊断。
     *
     * @return 本地端口/信道标识
     */
    int getLocalPort();

    /**
     * 关闭传输层，释放底层资源（线程、套接字、队列）。
     * <p>
     * 收窄 {@link AutoCloseable#close()} 的异常声明：传输层 close 不抛受检异常。
     */
    @Override
    void close();
}