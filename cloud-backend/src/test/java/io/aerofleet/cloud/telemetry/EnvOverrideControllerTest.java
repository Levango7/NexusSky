package io.aerofleet.cloud.telemetry;

import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
 * 环境参数远程覆写控制器单测（MAV_CMD 30080-30082 生产者，边界清零 2026-10-05）。
 * <p>
 * 覆盖参数校验（与 drone-sim 机载校验同构）与命令下发透传。
 */
@DisplayName("EnvOverrideController 环境覆写 (30080-30082)")
class EnvOverrideControllerTest {

    private DroneCommandService commands;
    private EnvOverrideController controller;

    @BeforeEach
    void setUp() {
        commands = mock(DroneCommandService.class);
        controller = new EnvOverrideController(commands);
    }

    @Test
    @DisplayName("风场覆写：校验通过下发 30080，param1=风速 param2=风向")
    void setWindSendsCommand() {
        when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenReturn(0);

        Map<String, Object> resp = controller.setWind(3,
                Map.of("speedMps", 8.5, "directionDeg", 270));

        assertThat(resp.get("status")).isEqualTo("ok");
        verify(commands).command(3, MavEnums.MAV_CMD_NEXUS_ENV_SET_WIND,
                8.5f, 270f, 0f, 0f, 0f, 0f, 0f);
    }

    @Test
    @DisplayName("风场覆写：风速超界/风向越界/缺字段返回 400")
    void setWindRejectsInvalidInput() {
        assertThatThrownBy(() -> controller.setWind(3, Map.of("speedMps", 51, "directionDeg", 0)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> controller.setWind(3, Map.of("speedMps", 5, "directionDeg", 360)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> controller.setWind(3, Map.of("speedMps", 5)))
                .isInstanceOf(BadRequestException.class);
        verify(commands, never()).command(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());
    }

    @Test
    @DisplayName("天气覆写：校验通过下发 30081")
    void setWeatherSendsCommand() {
        when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenReturn(0);

        Map<String, Object> resp = controller.setWeather(3,
                Map.of("weatherCode", 2, "rainRate", 30));

        assertThat(resp.get("status")).isEqualTo("ok");
        verify(commands).command(3, MavEnums.MAV_CMD_NEXUS_ENV_SET_WEATHER,
                2f, 30f, 0f, 0f, 0f, 0f, 0f);
    }

    @Test
    @DisplayName("天气覆写：weatherCode>4 或 rainRate 负数返回 400")
    void setWeatherRejectsInvalidInput() {
        assertThatThrownBy(() -> controller.setWeather(3, Map.of("weatherCode", 5, "rainRate", 0)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> controller.setWeather(3, Map.of("weatherCode", 0, "rainRate", -1)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("阈值覆写：校验通过下发 30082")
    void setThresholdsSendsCommand() {
        when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenReturn(0);

        Map<String, Object> resp = controller.setThresholds(3,
                Map.of("windWarnMps", 8, "windCritMps", 12));

        assertThat(resp.get("status")).isEqualTo("ok");
        verify(commands).command(3, MavEnums.MAV_CMD_NEXUS_ENV_SET_THRESHOLDS,
                8f, 12f, 0f, 0f, 0f, 0f, 0f);
    }

    @Test
    @DisplayName("阈值覆写：windWarn >= windCrit 返回 400（与机载校验一致）")
    void setThresholdsRejectsInvertedRange() {
        assertThatThrownBy(() -> controller.setThresholds(3,
                Map.of("windWarnMps", 12, "windCritMps", 8)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> controller.setThresholds(3,
                Map.of("windWarnMps", 8, "windCritMps", 8)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("命令异常沿 DroneController 惯例返回 status=error 而非 5xx")
    void commandFailureReturnsErrorStatus() {
        when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenThrow(new DroneCommandService.CommandException("command timed out", -1));

        Map<String, Object> resp = controller.setWind(3,
                Map.of("speedMps", 5, "directionDeg", 90));

        assertThat(resp.get("status")).isEqualTo("error");
        assertThat((String) resp.get("result")).contains("timed out");
    }

    @Test
    @DisplayName("环境命令端到端：30080/30081/30082 三条 MAV_CMD 均有真实生产者，无缺失前置链路")
    void allThreeEnvCommandsHaveRealProducers() {
        // 本轮边界清零（2026-10-05）：EnvOverrideController 已接入 DroneCommandService，
        // 30080（ENV_SET_WIND）、30081（ENV_SET_WEATHER）、30082（ENV_SET_THRESHOLDS）
        // 全部有 REST 端点 + 机载处理器（VirtualDrone 831-838 行），无缺失前置链路。
        assertThat(MavEnums.MAV_CMD_NEXUS_ENV_SET_WIND).isEqualTo(30080);
        assertThat(MavEnums.MAV_CMD_NEXUS_ENV_SET_WEATHER).isEqualTo(30081);
        assertThat(MavEnums.MAV_CMD_NEXUS_ENV_SET_THRESHOLDS).isEqualTo(30082);
    }
}
