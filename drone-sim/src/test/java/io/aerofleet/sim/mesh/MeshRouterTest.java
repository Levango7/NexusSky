package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.messages.MeshHeartbeatMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * MeshRouter AODV-lite 路由引擎核心单测（M5 应急 mesh，FR-01~22a）。
 * <p>
 * transport 传 null（sendFrame 对 null 安全跳过），聚焦验证路由逻辑而非网络 IO。
 * 覆盖邻居发现、HELLO 忽略自身、按需建路、帧级 hopCount 递减/丢弃、度量计算、close 生命周期。
 */
@DisplayName("MeshRouter AODV-lite 路由引擎 (FR-01~22a)")
class MeshRouterTest {

    private static final InetSocketAddress NEIGHBOR_ADDR = new InetSocketAddress("10.0.0.2", 14551);

    private static MeshRouter newRouter(int selfSysid) {
        return new MeshRouter(selfSysid, MeshRouterConfig.defaults(), (io.aerofleet.mavlink.transport.UdpMavlinkTransport) null);
    }

    @Test
    @DisplayName("收到 HELLO 新增邻居，neighborCount 递增")
    void onMeshHeartbeatAddsNeighbor() {
        MeshRouter router = newRouter(1);
        router.start();
        MeshHeartbeatMsg hello = new MeshHeartbeatMsg(2, 0, 0, 0, 100, 0, 0L);

        router.onMeshHeartbeat(hello, NEIGHBOR_ADDR, -50);

        assertThat(router.neighborCount()).isEqualTo(1);
        assertThat(router.snapshotNeighbors().contains(2)).isTrue();
    }

    @Test
    @DisplayName("收到自身 HELLO 被忽略，不新增邻居")
    void onMeshHeartbeatIgnoresSelf() {
        MeshRouter router = newRouter(1);
        router.start();
        MeshHeartbeatMsg hello = new MeshHeartbeatMsg(1, 0, 0, 0, 100, 0, 0L);

        router.onMeshHeartbeat(hello, NEIGHBOR_ADDR, -50);

        assertThat(router.neighborCount()).isZero();
    }

    @Test
    @DisplayName("sendTo 无路由时发起 RREQ 并返回 QUEUED_RREQ")
    void sendToWithNoRouteQueuesRreq() {
        MeshRouter router = newRouter(1);
        router.start();

        MeshRouter.SendResult result = router.sendTo(99, new byte[]{1, 2, 3});
        assertThat(result).isEqualTo(MeshRouter.SendResult.QUEUED_RREQ);
    }

    @Test
    @DisplayName("forwardFrame hopCount=5 正常转发返回 SENT，newHopCount=4")
    void forwardFrameHopCountDecrement() {
        MeshRouter router = newRouter(1);
        router.start();

        MeshRouter.ForwardOutcome outcome = router.forwardFrame(new byte[]{1}, 5);
        assertThat(outcome.result()).isEqualTo(MeshRouter.SendResult.SENT);
        assertThat(outcome.newHopCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("forwardFrame hopCount > maxHops(15) 丢弃返回 DROPPED_HOP_LIMIT")
    void forwardFrameHopLimitExceeded() {
        MeshRouter router = newRouter(1);
        router.start();

        MeshRouter.ForwardOutcome outcome = router.forwardFrame(new byte[]{1}, 16);
        assertThat(outcome.result()).isEqualTo(MeshRouter.SendResult.DROPPED_HOP_LIMIT);
    }

    @Test
    @DisplayName("forwardFrame hopCount <= 0 丢弃返回 DROPPED_HOP_LIMIT")
    void forwardFrameHopCountZeroDropped() {
        MeshRouter router = newRouter(1);
        router.start();

        assertThat(router.forwardFrame(new byte[]{1}, 0).result())
                .isEqualTo(MeshRouter.SendResult.DROPPED_HOP_LIMIT);
        assertThat(router.forwardFrame(new byte[]{1}, -1).result())
                .isEqualTo(MeshRouter.SendResult.DROPPED_HOP_LIMIT);
    }

    @Test
    @DisplayName("computeMetric 公式：hopCount×W1 + (100+RSSI)×W2 + delay×W3")
    void computeMetricFormula() {
        MeshRouter router = newRouter(1);
        // 默认 W1=1.0, W2=0.5, W3=0.1
        // metric(2, -50, 100) = 2*1 + 50*0.5 + 100*0.1 = 2 + 25 + 10 = 37
        assertThat(router.computeMetric(2, -50, 100))
                .isCloseTo(37.0, within(1e-9));
    }

    @Test
    @DisplayName("computeMetric RSSI=0 用保守默认 -100，delay<=0 用默认 500ms")
    void computeMetricConservativeDefaults() {
        MeshRouter router = newRouter(1);
        // rssi=0 → -100, delay=0 → 500
        // metric(1, 0, 0) = 1*1 + 0*0.5 + 500*0.1 = 1 + 0 + 50 = 51
        assertThat(router.computeMetric(1, 0, 0))
                .isCloseTo(51.0, within(1e-9));
    }

    @Test
    @DisplayName("close 后 isClosed 返回 true，forwardFrame 返回 DROPPED_NO_ROUTE")
    void closeSetsClosedFlag() {
        MeshRouter router = newRouter(1);
        router.start();
        assertThat(router.isClosed()).isFalse();

        router.close();
        assertThat(router.isClosed()).isTrue();
        assertThat(router.forwardFrame(new byte[]{1}, 5).result())
                .isEqualTo(MeshRouter.SendResult.DROPPED_NO_ROUTE);
    }

    @Test
    @DisplayName("close 后邻居表与路由表清空")
    void closeClearsTables() {
        MeshRouter router = newRouter(1);
        router.start();
        MeshHeartbeatMsg hello = new MeshHeartbeatMsg(2, 0, 0, 0, 100, 0, 0L);
        router.onMeshHeartbeat(hello, NEIGHBOR_ADDR, -50);
        assertThat(router.neighborCount()).isEqualTo(1);

        router.close();
        assertThat(router.neighborCount()).isZero();
    }

    @Test
    @DisplayName("tick 在 null transport 下不抛异常")
    void tickDoesNotCrashWithNullTransport() {
        MeshRouter router = newRouter(1);
        router.start();
        // 多次 tick 模拟周期驱动
        for (int i = 0; i < 5; i++) {
            router.tick(System.currentTimeMillis() + i * 1000L);
        }
        assertThat(router.isClosed()).isFalse();
    }

    @Test
    @DisplayName("updateState 更新位置/电量后 broadcastHello 不抛异常")
    void updateStateAndBroadcastHello() {
        MeshRouter router = newRouter(1);
        router.start();
        router.updateState(400000000, 1160000000, 50000, 75);
        router.broadcastHello(System.currentTimeMillis());
        // 无异常即通过；neighborCount 不因 HELLO 发送而改变
        assertThat(router.neighborCount()).isZero();
    }

    @Test
    @DisplayName("selfSysid 返回构造时指定的 sysid")
    void selfSysid() {
        MeshRouter router = newRouter(42);
        assertThat(router.selfSysid()).isEqualTo(42);
    }
}