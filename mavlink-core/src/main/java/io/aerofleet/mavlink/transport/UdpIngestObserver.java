package io.aerofleet.mavlink.transport;

/**
 * UDP 摄入侧信号出口（<b>依赖倒置边界</b>）。
 *
 * <p>本接口由 {@code mavlink-core} 定义、由上层（{@code cloud-backend} 的
 * {@code UdpGateway}）注入实现，把解析失败、监听器异常、疑似截断等信号接到
 * Micrometer / Prometheus。<b>mavlink-core 不依赖 Micrometer、Spring 或 cloud-backend</b>，
 * 因此不会出现反向依赖，也不会把监控后端绑进协议库。
 *
 * <p><b>为什么需要它</b>：{@link io.aerofleet.mavlink.MavlinkParser} 的
 * {@code crcErrors} / {@code noiseBytes} 只是内存 long，解析失败的帧连
 * {@code ParseResult} 都不会产生，于是「UDP 摄入到底丢了多少」在应用层完全不可见。
 * 容量实测（{@code docs/capacity/README.md}）当时只能靠「注入量 − WS 探针量」反推，
 * 无法区分内核丢包、CRC 失败与策略丢弃三类。
 *
 * <p><b>线程约束（实现方必须遵守）</b>：所有回调都在 {@code UdpMavlinkTransport}
 * 的<b>收包线程内同步执行</b>。实现必须是 O(1)、不得阻塞、不得做 IO、不得抛异常——
 * 任何耗时都会直接压低摄入吞吐（该线程每帧预算在 4,100 帧/s 时只有约 244μs）。
 * 传输层不包装 try/catch 兜底：实现方抛异常会拖垮收包循环，这是有意的失败可见设计。
 *
 * <p>默认实现是 {@link #NOOP}，不注入观察者时行为与本接口加入前完全一致，
 * 因此 {@code VirtualDrone}、硬件适配器与全部既有测试的调用点零改动。
 */
public interface UdpIngestObserver {

    /**
     * 每收到一个 UDP 数据报（<b>解析之前</b>）回调一次。
     *
     * <p>Java 的 {@code DatagramSocket} 没有跨平台的内核接收队列溢出计数接口
     * （Linux 需 {@code SO_RXQ_OVFL} 或解析 {@code /proc/net/udp}，Windows 无等价物）。
     * 因此「内核缓冲溢出」只能<b>间接对账</b>：注入端送出的包数 − 本计数 = 内核层丢弃数。
     * 这是本指标存在的主要理由，不要把它解释成「溢出直接计数」。
     *
     * @param bytes 数据报长度
     */
    void onDatagramReceived(int bytes);

    /**
     * 一个数据报解析结束后的失败增量（按包聚合，不是每帧回调，避免热路径放大）。
     *
     * @param crcErrors  本包内 CRC / 未知消息校验失败而被丢弃的<b>帧</b>数
     * @param noiseBytes 本包内跳过的非 STX 噪声<b>字节</b>数（不是帧数，单位不同）
     */
    void onParseErrors(int crcErrors, long noiseBytes);

    /**
     * 数据报长度恰好等于接收缓冲区上限（{@code buf.length}），
     * 疑似被内核截断尾部——这是唯一能在 JVM 内直接观测的「缓冲不足」信号。
     */
    void onTruncatedDatagram(int bytes);

    /** 单个帧监听器抛异常，该帧未进入业务链（其余监听器仍继续）。 */
    void onListenerError();

    /** {@code socket.receive} 抛 IOException，本轮没有数据报。 */
    void onReceiveError();

    /** 空实现：不注入观察者时的默认值，保持既有行为逐字节不变。 */
    UdpIngestObserver NOOP = new UdpIngestObserver() {
        @Override
        public void onDatagramReceived(int bytes) {
        }

        @Override
        public void onParseErrors(int crcErrors, long noiseBytes) {
        }

        @Override
        public void onTruncatedDatagram(int bytes) {
        }

        @Override
        public void onListenerError() {
        }

        @Override
        public void onReceiveError() {
        }
    };
}
