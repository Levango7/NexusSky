package io.aerofleet.cloud.telemetry;

import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环境参数远程覆写 API（M0b 边界清零，2026-10-05）。
 * <p>
 * MAV_CMD 30080-30082（机载环境配置命令）的云端生产者：校验参数后经
 * {@link DroneCommandService} 下发 COMMAND_LONG，同步等待机载 COMMAND_ACK。
 * 机载侧由 drone-sim {@code handleEnvSetWind/Weather/Thresholds} 处理
 * （校验拒绝返回 MAV_RESULT_DENIED）。协议面此前保留给 SITL/外部工具，
 * 现补齐 GCS 远程配置入口。
 * <p>
 * 端点（均 OPERATOR）：
 * <pre>
 * POST /api/v1/env/{sysid}/wind        {speedMps: 0-50, directionDeg: [0,360)}
 * POST /api/v1/env/{sysid}/weather     {weatherCode: 0-4, rainRate: 0-255}
 * POST /api/v1/env/{sysid}/thresholds  {windWarnMps: >=0, windCritMps: >windWarn}
 * </pre>
 * 返回 {@code {sysid, command, result}}；命令超时/被拒返回
 * {@code {status:"error", result:...}}（沿 DroneController 惯例），不发 5xx。
 */
@RestController
@RequestMapping("/api/v1/env")
@RequireRole(Role.OPERATOR)
public class EnvOverrideController {
    private static final Logger log = LoggerFactory.getLogger(EnvOverrideController.class);

    private final DroneCommandService commands;

    public EnvOverrideController(DroneCommandService commands) {
        this.commands = commands;
    }

    /**
     * FR-28 风场覆写：MAV_CMD 30080，param1=风速(m/s)，param2=风向(°, 0=正北顺时针)。
     */
    @PostMapping("/{sysid}/wind")
    public Map<String, Object> setWind(@PathVariable int sysid,
                                       @RequestBody Map<String, Number> body) {
        Number speed = required(body, "speedMps");
        Number direction = required(body, "directionDeg");
        if (speed.doubleValue() < 0 || speed.doubleValue() > 50) {
            throw new BadRequestException("speedMps must be in [0, 50]: " + speed);
        }
        if (direction.doubleValue() < 0 || direction.doubleValue() >= 360) {
            throw new BadRequestException("directionDeg must be in [0, 360): " + direction);
        }
        return send(sysid, MavEnums.MAV_CMD_NEXUS_ENV_SET_WIND,
                "wind-override",
                (float) speed.doubleValue(), (float) direction.doubleValue());
    }

    /**
     * FR-28 天气覆写：MAV_CMD 30081，param1=天气代码(0-4)，param2=降雨率(0-255)。
     */
    @PostMapping("/{sysid}/weather")
    public Map<String, Object> setWeather(@PathVariable int sysid,
                                          @RequestBody Map<String, Number> body) {
        Number weatherCode = required(body, "weatherCode");
        Number rainRate = required(body, "rainRate");
        if (weatherCode.intValue() < 0 || weatherCode.intValue() > 4) {
            throw new BadRequestException("weatherCode must be in [0, 4]: " + weatherCode);
        }
        if (rainRate.intValue() < 0 || rainRate.intValue() > 255) {
            throw new BadRequestException("rainRate must be in [0, 255]: " + rainRate);
        }
        return send(sysid, MavEnums.MAV_CMD_NEXUS_ENV_SET_WEATHER,
                "weather-override",
                (float) weatherCode.intValue(), (float) rainRate.intValue());
    }

    /**
     * FR-28 告警阈值覆写：MAV_CMD 30082，param1=预警风速(m/s)，param2=临界风速(m/s)，
     * 必须 windWarn < windCrit（与机载校验一致）。
     */
    @PostMapping("/{sysid}/thresholds")
    public Map<String, Object> setThresholds(@PathVariable int sysid,
                                             @RequestBody Map<String, Number> body) {
        Number windWarn = required(body, "windWarnMps");
        Number windCrit = required(body, "windCritMps");
        if (windWarn.doubleValue() < 0 || windCrit.doubleValue() < 0) {
            throw new BadRequestException("thresholds must be non-negative");
        }
        if (windWarn.doubleValue() >= windCrit.doubleValue()) {
            throw new BadRequestException(
                    "windWarnMps must be less than windCritMps: " + windWarn + " >= " + windCrit);
        }
        return send(sysid, MavEnums.MAV_CMD_NEXUS_ENV_SET_THRESHOLDS,
                "threshold-override",
                (float) windWarn.doubleValue(), (float) windCrit.doubleValue());
    }

    private static Number required(Map<String, Number> body, String key) {
        Number v = body.get(key);
        if (v == null) {
            throw new BadRequestException("missing required field: " + key);
        }
        return v;
    }

    /** 下发命令并组装响应；失败沿 DroneController 惯例返回 status=error。 */
    private Map<String, Object> send(int sysid, int mavCommand, String commandName,
                                     float p1, float p2) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sysid", sysid);
        resp.put("command", commandName);
        try {
            int result = commands.command(sysid, mavCommand, p1, p2, 0, 0, 0, 0, 0);
            resp.put("status", "ok");
            resp.put("result", result);
        } catch (DroneCommandService.CommandException e) {
            log.warn("env override {} failed for sysid={}: {}", commandName, sysid, e.getMessage());
            resp.put("status", "error");
            resp.put("result", e.getMessage());
        }
        return resp;
    }
}
