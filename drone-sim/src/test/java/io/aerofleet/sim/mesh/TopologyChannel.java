package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.sim.comm.MavlinkTransport;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 可控拓扑的 mesh 信道（测试基础设施）：按「直连邻居」投递帧，支持链路阻断与节点下线，
 * 从而能构造 A—B—C（A 与 C 不直连）的真多跳拓扑，并注入断链验证自愈重构。
 *
 * <p>与 LoRaAirChannel（全连接广播）的本质区别：本信道只把帧投递给发送方的直连邻居，
 * 模拟「广播但只有直连邻居听得到」的 RF 范围限制——这是形成多跳路由的前提。
 * 在全连接信道里 A 与 C 直连、RREQ 一跳即达，无法验证多跳建路与断链自愈。
 */
final class TopologyChannel {

    private final Set<Long> links = new HashSet<>();        // 无向直连边（min*1000+max 编码）
    private final Set<Long> blocked = new HashSet<>();      // 被阻断的边
    private final Set<Integer> down = new HashSet<>();      // 下线节点（不再收发，模拟死机）
    private final Map<Integer, NodeTransport> nodes = new ConcurrentHashMap<>();

    private static long edge(int a, int b) {
        return a < b ? a * 1000L + b : b * 1000L + a;
    }

    /** 声明一条直连边（无向）。 */
    TopologyChannel link(int a, int b) {
        links.add(edge(a, b));
        return this;
    }

    /** 阻断一条边（断链注入）：该边两端不再互通。 */
    void blockLink(int a, int b) {
        blocked.add(edge(a, b));
    }

    /** 节点下线：该节点不再收发任何帧（模拟死机，无 RERR 通知）。 */
    void setDown(int sysid) {
        down.add(sysid);
    }

    private boolean connected(int a, int b) {
        long e = edge(a, b);
        return links.contains(e) && !blocked.contains(e) && !down.contains(a) && !down.contains(b);
    }

    NodeTransport register(int sysid) {
        NodeTransport t = new NodeTransport(sysid, this);
        nodes.put(sysid, t);
        return t;
    }

    void unregister(int sysid) {
        nodes.remove(sysid);
    }

    /** 发送方广播一帧：投递到其直连且未阻断、双方均在线的邻居（跳过自己）。 */
    void broadcast(int fromSysid, MavlinkFrame frame) {
        for (NodeTransport node : nodes.values()) {
            if (node.sysid == fromSysid) {
                continue;
            }
            if (!connected(fromSysid, node.sysid)) {
                continue;
            }
            node.deliver(frame);
        }
    }

    /** 单节点在信道里的传输接口。 */
    static final class NodeTransport implements MavlinkTransport {
        private final int sysid;
        private final TopologyChannel channel;
        private volatile Consumer<MavlinkFrame> listener;

        NodeTransport(int sysid, TopologyChannel channel) {
            this.sysid = sysid;
            this.channel = channel;
        }

        @Override
        public void send(MavlinkFrame frame) throws IOException {
            channel.broadcast(sysid, frame);
        }

        @Override
        public void addFrameListener(Consumer<MavlinkFrame> l) {
            this.listener = l;
        }

        void deliver(MavlinkFrame frame) {
            Consumer<MavlinkFrame> l = listener;
            if (l != null) {
                l.accept(frame);
            }
        }

        @Override
        public int getLocalPort() {
            return 10000 + sysid;
        }

        @Override
        public void close() {
            channel.unregister(sysid);
        }
    }
}
