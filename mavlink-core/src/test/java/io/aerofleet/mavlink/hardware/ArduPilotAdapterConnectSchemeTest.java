package io.aerofleet.mavlink.hardware;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArduPilotAdapter} 连接串前缀的**诚实语义**。
 *
 * <p>背景：`tcp://` 曾"骨架阶段先用 UDP 顶替"——而 ArduPilot SITL 的 TCP 默认口（5760）
 * 根本不收 UDP，拿它连真 SITL 必然静默失败（`docs/real-integration-program.md` P2 点名
 * "这个谎"）。2026-10-28 起与 `serial://` 同款：**明确拒绝 + 指路**，不做静默降级。
 * 本类把这条语义钉住——把拒绝改回"UDP 顶替"或改成抛异常，这里都会红。
 */
@DisplayName("ArduPilotAdapter：未实现的连接串必须明确拒绝，不静默降级")
class ArduPilotAdapterConnectSchemeTest {

    @Test
    @DisplayName("tcp:// 明确拒绝（不再 UDP 顶替）：connect=false 且连接状态不变")
    void tcpSchemeIsRejectedExplicitly() {
        HardwareAdapter adapter = HardwareAdapterFactory.create("tcp://127.0.0.1:5760");

        assertFalse(adapter.connect("tcp://127.0.0.1:5760"),
                "tcp:// 未实现必须如实返回 false，不许再拿 UDP 顶替装连接成功");
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("serial:// 明确拒绝（既有行为不回退）")
    void serialSchemeIsRejected() {
        HardwareAdapter adapter = HardwareAdapterFactory.create("serial://COM3:57600");

        assertFalse(adapter.connect("serial://COM3:57600"));
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("不认识的前缀明确拒绝（不抛异常、不静默）")
    void unknownSchemeIsRejected() {
        HardwareAdapter adapter = HardwareAdapterFactory.create("tcp://127.0.0.1:5760");

        assertFalse(adapter.connect("foo://bar"));
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("udp:// 仍然可用（回归：诚实化改造不能误伤真路径）")
    void udpSchemeStillConnectsAndDisconnects() {
        HardwareAdapter adapter = HardwareAdapterFactory.create("udp://127.0.0.1:14550");
        try {
            assertTrue(adapter.connect("udp://127.0.0.1:14550"));
            assertTrue(adapter.isConnected());
        } finally {
            adapter.disconnect();
        }
        assertFalse(adapter.isConnected(), "disconnect 后状态必须复位");
    }
}
