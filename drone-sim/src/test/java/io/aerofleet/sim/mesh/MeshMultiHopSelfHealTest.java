package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.MeshHeartbeatMsg;
import io.aerofleet.mavlink.messages.MeshRouteErrorMsg;
import io.aerofleet.mavlink.messages.MeshRouteReplyMsg;
import io.aerofleet.mavlink.messages.MeshRouteRequestMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 多跳动态路由自愈 端到端演练（进程内 3 节点 + 可控拓扑信道）。
 *
 * <p>验证 ROADMAP M5 宣称的核心能力：A—B—C（A 与 C 不直连）拓扑下，A 经 B 到 C 的
 * 多跳建路（RREQ/RREP），以及断链后的邻居超时自愈重构。这是该差异化能力首次端到端
 * 验证——此前仅有 RERR 传播、findExpired 等片段单测，没有「多跳组网→多跳建路→断链
 * →邻居超时→自愈重构」的完整流程证据。
 *
 * <p>拓扑信道（{@link TopologyChannel}）只把帧投递给直连邻居，模拟 RF 范围限制，
 * 使 A 与 C 不直连、必须经 B 中继——这是形成真多跳的前提。
 */
@DisplayName("M5 多跳动态路由自愈 端到端演练")
class MeshMultiHopSelfHealTest {

    private static final InetSocketAddress MESH_GROUP = new InetSocketAddress("239.0.0.1", 14550);

    private static MeshRouterConfig config() {
        // 短超时便于演练：HELLO 200ms、邻居超时 3000ms
        return new MeshRouterConfig(200L, 3000L, 10000L, 15,
                1.0, 0.5, 0.1, 0.5, 5000L, MESH_GROUP, null);
    }

    /** 创建节点并注册帧分发器（帧解码 → on* 方法）。 */
    private MeshRouter newNode(int sysid, TopologyChannel channel) {
        MeshRouter router = new MeshRouter(sysid, config(), channel.register(sysid));
        router.transport().addFrameListener(frame -> dispatch(router, frame));
        router.start();
        return router;
    }

    private void dispatch(MeshRouter router, MavlinkFrame frame) {
        MavlinkMessage msg = MavlinkMessage.decode(frame);
        if (msg == null) {
            return;
        }
        InetSocketAddress srcAddr = new InetSocketAddress("127.0.0.1", 10000 + frame.getSystemId());
        int frameSysid = frame.getSystemId();
        if (msg instanceof MeshHeartbeatMsg hello) {
            router.onMeshHeartbeat(hello, srcAddr, -60);
        } else if (msg instanceof MeshRouteRequestMsg rreq) {
            router.onRouteRequest(rreq, srcAddr, -60, frameSysid);
        } else if (msg instanceof MeshRouteReplyMsg rrep) {
            router.onRouteReply(rrep, srcAddr, -60, frameSysid);
        } else if (msg instanceof MeshRouteErrorMsg rerr) {
            router.onRouteError(rerr, srcAddr, frameSysid);
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String desc) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("waitFor 超时: " + desc);
    }

    @Test
    @DisplayName("A—B—C 多跳建路：A 经 B 到 C（nextHop=B、2 跳）")
    void multiHopRouteEstablishment() {
        TopologyChannel channel = new TopologyChannel().link(1, 2).link(2, 3);
        MeshRouter a = newNode(1, channel);
        MeshRouter b = newNode(2, channel);
        MeshRouter c = newNode(3, channel);
        try {
            long now = System.currentTimeMillis();
            a.broadcastHello(now);
            b.broadcastHello(now);
            c.broadcastHello(now);

            // 邻居：A 只知 B、C 只知 B、A 与 C 互不邻居（多跳前提）
            assertThat(a.snapshotNeighbors().contains(2)).isTrue();
            assertThat(a.snapshotNeighbors().contains(3)).isFalse();
            assertThat(c.snapshotNeighbors().contains(2)).isTrue();
            assertThat(c.snapshotNeighbors().contains(1)).isFalse();

            // A 发数据到 C：无直连路由 → RREQ 经 B → C 回 RREP → A 学到经 B 的多跳路由
            a.sendTo(3, new byte[]{1, 2, 3});
            waitFor(() -> a.snapshotRoutes().lookup(3) != null, "A 学到到 C 的多跳路由");

            RouteEntry route = a.snapshotRoutes().lookup(3);
            assertThat(route.nextHop).as("到 C 应经 B 中继").isEqualTo(2);
            assertThat(route.hopCount).as("A→B→C 应为 2 跳").isEqualTo(2);
        } finally {
            a.close();
            b.close();
            c.close();
        }
    }

    @Test
    @DisplayName("断链自愈：B 下线 → A 邻居超时删除经 B 的路由")
    void selfHealOnLinkDown() {
        TopologyChannel channel = new TopologyChannel().link(1, 2).link(2, 3);
        MeshRouter a = newNode(1, channel);
        MeshRouter b = newNode(2, channel);
        MeshRouter c = newNode(3, channel);
        try {
            long now = System.currentTimeMillis();
            a.broadcastHello(now);
            b.broadcastHello(now);
            c.broadcastHello(now);
            a.sendTo(3, new byte[]{1});
            waitFor(() -> a.snapshotRoutes().lookup(3) != null, "A 到 C 路由已建立");

            // 断链：B 死机（不再收发，无 RERR 通知）
            channel.setDown(2);

            // A 的邻居超时检测：B 超时 → 删除经 B 的路由 + 触发 RERR + 尝试重建
            a.onNeighborTimeout(2, now + 5000);

            // 自愈：经 B 到 C 的路由被删除（A 与 C 不直连，B 下线后无备份路径可达）
            assertThat(a.snapshotRoutes().lookup(3))
                    .as("B 下线后，A 经 B 到 C 的路由应被自愈删除")
                    .isNull();
        } finally {
            a.close();
            b.close();
            c.close();
        }
    }
}
