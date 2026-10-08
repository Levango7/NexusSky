package io.aerofleet.cloud.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F5 命令路由三态语义：协议有栈→委托；协议无栈→拒绝（WARN 不静默丢）；
 * 设备未知→拒绝。栈受理与否由栈决定，路由层不假成功。
 */
@DisplayName("CommandRouter — 跨品牌路由三态")
class CommandRouterTest {

    private DeviceRegistry registry;
    private DeviceGateway mavlinkStack;
    private CommandRouter router;
    private DroneSnapshot mavDrone;
    private DroneSnapshot djiDrone;

    @BeforeEach
    void setUp() {
        registry = mock(DeviceRegistry.class);
        mavlinkStack = mock(DeviceGateway.class);
        when(mavlinkStack.protocol()).thenReturn("mavlink");
        // PoC 注册面只有 MAVLink 栈：dji-cloud 是标记就位、栈未接入（诚实边界）
        router = new CommandRouter(registry, List.of(mavlinkStack));

        mavDrone = new DroneSnapshot(9);
        mavDrone.protocol = "mavlink";
        mavDrone.online = true;
        djiDrone = new DroneSnapshot(10);
        djiDrone.protocol = "dji-cloud";
        djiDrone.online = true;

        when(registry.get(9)).thenReturn(mavDrone);
        when(registry.get(10)).thenReturn(djiDrone);
        when(registry.get(99)).thenReturn(null);
    }

    @Test
    @DisplayName("mavlink 设备 → 委托 MAVLink 栈，受理与否由栈决定")
    void mavlinkDelegates() {
        when(mavlinkStack.send(eq(9), anyInt(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat())).thenReturn(true);

        assertThat(router.route(9, 176, 1f, 0, 0, 0, 0, 0, 0)).isTrue();
        verify(mavlinkStack).send(eq(9), eq(176), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    @DisplayName("栈拒绝（DENIED/UNSUPPORTED）→ 路由如实返回 false，不假成功")
    void stackRefusalPropagates() {
        when(mavlinkStack.send(anyInt(), anyInt(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat())).thenReturn(false);

        assertThat(router.route(9, 400, 0, 0, 0, 0, 0, 0, 0)).isFalse();
    }

    @Test
    @DisplayName("dji-cloud 设备：标记就位但栈未接入 → 明确拒绝（不静默丢命令）")
    void djiCloudRefusedUntilStackExists() {
        assertThat(router.route(10, 176, 1f, 0, 0, 0, 0, 0, 0)).isFalse();
        // 关键：没有错投到 MAVLink 栈（协议中立——dji 设备的命令不许从 mavlink 栈漏出去）
        verify(mavlinkStack, never()).send(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    @DisplayName("未知设备 → 拒绝")
    void unknownDeviceRefused() {
        assertThat(router.route(99, 176, 1f, 0, 0, 0, 0, 0, 0)).isFalse();
    }

    @Test
    @DisplayName("protocol 缺省 mavlink（既有注册路径零变化）")
    void defaultProtocolIsMavlink() {
        DroneSnapshot fresh = new DroneSnapshot(11);
        assertThat(fresh.protocol).isEqualTo("mavlink");
    }
}
