package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MavlinkDeviceGateway} 的契约测试（F5 多品牌双栈网关的 MAVLink 栈一侧）。
 *
 * <p><b>为什么要单独钉它</b>：SPI 可验证性门禁扫出本类"编译通过、启动通过、但测试源码里
 * 一次都没被点名"——既有网关测试用的是 {@link DeviceGateway} 接口替身，真正把命令交给
 * {@code DroneCommandService} 的这条适配路径没有任何断言。本类只做一件事：把"接口替身能过"
 * 与"真实例也对"之间的缺口补上。
 *
 * <p><b>钉住的两条事实</b>：
 * <ol>
 *   <li><b>参数原样转发</b>——9 个参数一位不差地交给 {@code DroneCommandService.command}：
 *       拦截链 / 重试 / COMMAND_ACK 全量复用既有路径，本类不许自作主张。</li>
 *   <li><b>受理判定不假成功</b>——只有 {@code MAV_RESULT_ACCEPTED(0)} 与
 *       {@code MAV_RESULT_IN_PROGRESS(5)} 算受理；{@code TEMPORARILY_REJECTED(1)} /
 *       {@code DENIED(2)} / {@code UNSUPPORTED(3)} / {@code FAILED(4)} 一律 false，
 *       调用方据此如实上报。受理是白名单，不是"非 0 即真"。</li>
 * </ol>
 */
@DisplayName("MavlinkDeviceGateway：协议标记 + DroneCommandService 的受理/拒绝翻译")
class MavlinkDeviceGatewayContractTest {

    private DroneCommandService commands;
    private MavlinkDeviceGateway gateway;

    @BeforeEach
    void setUp() {
        commands = mock(DroneCommandService.class);
        gateway = new MavlinkDeviceGateway(commands);
    }

    private void stubCommandResult(int result) {
        when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat())).thenReturn(result);
    }

    @Test
    @DisplayName("protocol() 就是 mavlink：与 DeviceRegistry 的 protocol 标记同源，写错会静默路由到别的栈")
    void protocolIsMavlink() {
        assertThat(gateway.protocol()).isEqualTo("mavlink");
    }

    @Test
    @DisplayName("send 把 9 个参数原样交给 DroneCommandService.command")
    void sendDelegatesAllNineParams() {
        stubCommandResult(MavEnums.MAV_RESULT_ACCEPTED);

        boolean accepted = gateway.send(231, 22, 1f, 2f, 3f, 4f, 5f, 6f, 7f);

        assertThat(accepted).isTrue();
        verify(commands).command(231, 22, 1f, 2f, 3f, 4f, 5f, 6f, 7f);
    }

    @Test
    @DisplayName("MAV_RESULT_ACCEPTED(0) ⇒ 受理 true")
    void acceptedIsAccepted() {
        stubCommandResult(MavEnums.MAV_RESULT_ACCEPTED);
        assertThat(gateway.send(1, 22, 0, 0, 0, 0, 0, 0, 0)).isTrue();
    }

    @Test
    @DisplayName("MAV_RESULT_IN_PROGRESS(5) ⇒ 受理 true（受理是白名单：0 与 5，其余皆否）")
    void inProgressIsAccepted() {
        stubCommandResult(MavEnums.MAV_RESULT_IN_PROGRESS);
        assertThat(gateway.send(1, 22, 0, 0, 0, 0, 0, 0, 0)).isTrue();
    }

    @ParameterizedTest(name = "result={0} ⇒ 不假成功（false）")
    @ValueSource(ints = {
            MavEnums.MAV_RESULT_TEMPORARILY_REJECTED,   // 1
            MavEnums.MAV_RESULT_DENIED,                 // 2
            MavEnums.MAV_RESULT_UNSUPPORTED,            // 3
            MavEnums.MAV_RESULT_FAILED})                // 4
    @DisplayName("被拒/失败/不支持一律 false，绝不把『没受理』报成『已受理』")
    void nonAcceptedResultsAreFalse(int result) {
        stubCommandResult(result);
        assertThat(gateway.send(1, 22, 0, 0, 0, 0, 0, 0, 0)).isFalse();
    }

    @Test
    @DisplayName("未知 result（如 99）也判 false：受理是白名单，不是『非 0 即真』")
    void unknownResultIsFalse() {
        stubCommandResult(99);
        assertThat(gateway.send(1, 22, 0, 0, 0, 0, 0, 0, 0)).isFalse();
    }
}
