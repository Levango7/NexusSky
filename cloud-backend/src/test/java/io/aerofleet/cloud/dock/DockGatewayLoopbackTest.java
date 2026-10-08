package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.sim.DockSim;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实链路：cloud-backend {@link SimLoopbackGateway} ↔ drone-sim {@link DockSim}。
 *
 * 用真 HTTP（回环随机端口），不起 Spring 上下文——验证的是协议形状与状态推进语义：
 * 开门命令往返（tid 回读）、机巢侧非法态拒绝（result!=0）、未知 method 处理、
 * 换电时序。这是 e2e 的缩小版，跑在单测里保证每次构建都过。
 */
@DisplayName("SimLoopbackGateway ↔ DockSim 真实 HTTP 往返")
class DockGatewayLoopbackTest {

    private static DockSim sim;
    private static SimLoopbackGateway gateway;
    private static int port;

    @BeforeAll
    static void boot() throws Exception {
        // 取一个空闲端口（ServerSocket(0) 拿号后立即释放，紧接 DockSim 绑定；
        // 极小概率被抢，但 CI 上无端口竞争源）
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        sim = new DockSim("DOCK-E2E", "测试机巢", port, null, 60_000, 9, null);
        sim.start();

        DockProperties props = new DockProperties();
        props.setSimBaseUrl("http://127.0.0.1:" + port);
        props.setCommandTimeoutMs(3000);
        gateway = new SimLoopbackGateway(new ObjectMapper(), props);
    }

    @AfterAll
    static void shutdown() {
        if (sim != null) {
            sim.close();
        }
    }

    @Test
    @DisplayName("开门：机巢确认 result=0，tid 回读一致，状态推进到 OPENING")
    void openDoorRoundTrip() {
        sim.forceState("IDLE");

        DockGateway.DockReply reply = gateway.sendCommand("DOCK-E2E", DockCommand.OPEN_DOOR, Map.of());

        assertThat(reply.ok()).isTrue();
        assertThat(reply.tid()).isGreaterThan(0);
        assertThat(sim.state()).isEqualTo("OPENING");
    }

    @Test
    @DisplayName("机巢侧非法态：result!=0 + message 点名当前状态")
    void dockSideRejectSurfacesMessage() {
        sim.forceState("OFFLINE");

        DockGateway.DockReply reply = gateway.sendCommand("DOCK-E2E", DockCommand.OPEN_DOOR, Map.of());

        assertThat(reply.ok()).isFalse();
        assertThat(reply.result()).isEqualTo(1);
        assertThat(reply.message()).contains("state=OFFLINE");
    }

    @Test
    @DisplayName("未知 method 字符串：fromMethod 立即拒绝（控制器 400 语义）")
    void unknownMethodRejectedAtParse() {
        assertThatThrownBy(() -> DockCommand.fromMethod("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("换电时序：EXCHANGING → 5s 后 CHARGING 且电量回 20")
    void swapBatterySequence() throws Exception {
        sim.forceState("CHARGING");

        DockGateway.DockReply reply = gateway.sendCommand("DOCK-E2E", DockCommand.SWAP_BATTERY, Map.of());
        assertThat(reply.ok()).isTrue();
        assertThat(sim.state()).isEqualTo("EXCHANGING");

        // DockSim 的换电时序是 5s：有界等待（最多 8s），不 mock 时钟——
        // 这是"真实链路"测试，跳过时序等于跳过被测语义。
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && !"CHARGING".equals(sim.state())) {
            Thread.sleep(200);
        }
        assertThat(sim.state()).isEqualTo("CHARGING");
        assertThat(sim.osdJson()).contains("\"batteryPct\":20");
    }

    @Test
    @DisplayName("通道不可达：连接失败抛 DockGatewayException（控制器 504 语义）")
    void unreachableTransportThrows() {
        DockProperties dead = new DockProperties();
        dead.setSimBaseUrl("http://127.0.0.1:1"); // 保留端口，必然拒绝
        dead.setCommandTimeoutMs(500);
        SimLoopbackGateway bad = new SimLoopbackGateway(new ObjectMapper(), dead);

        assertThatThrownBy(() -> bad.sendCommand("DOCK-E2E", DockCommand.OPEN_DOOR, Map.of()))
                .isInstanceOf(DockGateway.DockGatewayException.class);
    }
}