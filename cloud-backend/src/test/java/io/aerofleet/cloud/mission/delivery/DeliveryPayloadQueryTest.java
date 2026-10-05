package io.aerofleet.cloud.mission.delivery;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.NotFoundException;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配送任务按需负载查询单测（MAV_CMD 30085 生产者，边界清零 2026-10-05）。
 * <p>
 * 覆盖 30085 命令下发透传、未知配送 404 与命令失败降级。
 */
@DisplayName("DeliveryController 按需负载查询 (30085)")
class DeliveryPayloadQueryTest {

    private DeliveryService deliveryService;
    private DroneCommandService commands;
    private DeliveryController controller;

    @BeforeEach
    void setUp() {
        deliveryService = mock(DeliveryService.class);
        commands = mock(DroneCommandService.class);
        controller = new DeliveryController(deliveryService, commands);
    }

    @Test
    @DisplayName("已知配送：向目标机下发 30085，返回 ok")
    void queryPayloadSendsCommand() {
        when(deliveryService.sequence(1)).thenReturn(
                new DeliverySequence(1, 5, List.of()));
        when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenReturn(0);

        Map<String, Object> resp = controller.queryPayload(1);

        assertThat(resp.get("status")).isEqualTo("ok");
        assertThat(resp.get("sysid")).isEqualTo(5);
        verify(commands).command(5, MavEnums.MAV_CMD_NEXUS_PAYLOAD_QUERY,
                0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("未知配送 ID 返回 404，不下发命令")
    void unknownDeliveryThrowsNotFound() {
        when(deliveryService.sequence(99)).thenReturn(null);
        assertThatThrownBy(() -> controller.queryPayload(99))
                .isInstanceOf(NotFoundException.class);
        verify(commands, never()).command(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    @DisplayName("命令超时/被拒返回 status=error 而非 5xx")
    void commandFailureReturnsErrorStatus() {
        when(deliveryService.sequence(2)).thenReturn(
                new DeliverySequence(2, 6, List.of()));
        when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenThrow(new DroneCommandService.CommandException("command timed out", -1));

        Map<String, Object> resp = controller.queryPayload(2);

        assertThat(resp.get("status")).isEqualTo("error");
        assertThat((String) resp.get("result")).contains("timed out");
    }
}
