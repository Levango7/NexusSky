package io.aerofleet.cloud.telemetry;

import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * GET  /api/v1/env/status              机队级环境状态读口径（FR-24 读面，见 envStatus）
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
    private final DeviceRegistry registry;

    public EnvOverrideController(DroneCommandService commands, DeviceRegistry registry) {
        this.commands = commands;
        this.registry = registry;
    }

    /**
     * 机队级环境状态读口径（FR-24 的读面；P1 #8）。
     * <p>
     * 数据源是各机遥测里的 ENVIRONMENT_STATUS 快照（drone-sim 每秒下发、
     * {@code TelemetrySnapshotListener} 写入 {@link DroneSnapshot}）——这里只做
     * 机队聚合，**不造数**：online 的机逐台进 {@code stations}（没有定位的机对
     * 气象图层无意义，不进站表但参与顶层均值）；顶层标量取 online 机上报值的
     * 算术平均（逐字段独立平均，谁上报算谁）；风向用**圆周均值**——359° 与 1°
     * 的机队平均必须是 0° 而不是 180°；天气取众数（并列取编号小者）。
     * 未上报的字段键缺省（前端按 {@code != null} 守卫），NaN 永不进 JSON；
     * 不造 turbulence/湍流区（后端无此概念，前端分支自然不画）。
     */
    @GetMapping("/status")
    public Map<String, Object> envStatus() {
        DoubleSummaryStatistics temp = new DoubleSummaryStatistics();
        DoubleSummaryStatistics humidity = new DoubleSummaryStatistics();
        DoubleSummaryStatistics windSpeed = new DoubleSummaryStatistics();
        double windSin = 0;
        double windCos = 0;
        long windDirReports = 0;
        Map<Integer, Integer> weatherVotes = new HashMap<>();
        List<Map<String, Object>> stations = new ArrayList<>();

        for (DroneSnapshot s : registry.all()) {
            if (!s.online) {
                continue;
            }
            Map<String, Object> station = new LinkedHashMap<>();
            station.put("sysid", s.sysid);
            if (!Double.isNaN(s.lat) && !Double.isNaN(s.lon)) {
                station.put("lat", s.lat);
                station.put("lon", s.lon);
            }
            putIfReported(station, "temp", s.envTemperature, temp);
            putIfReported(station, "humidity", s.envHumidity, humidity);
            putIfReported(station, "windSpeed", s.envWindSpeed, windSpeed);
            if (!Double.isNaN(s.envWindDirection)) {
                station.put("windDir", s.envWindDirection);
                double rad = Math.toRadians(s.envWindDirection);
                windSin += Math.sin(rad);
                windCos += Math.cos(rad);
                windDirReports++;
            }
            String weather = weatherName(s.envWeather);
            if (weather != null) {
                station.put("weather", weather);
                weatherVotes.merge(s.envWeather, 1, Integer::sum);
            }
            if (station.containsKey("lat")) {
                stations.add(station);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        if (temp.getCount() > 0) {
            resp.put("temp", temp.getAverage());
        }
        if (humidity.getCount() > 0) {
            resp.put("humidity", humidity.getAverage());
        }
        if (windSpeed.getCount() > 0) {
            resp.put("windSpeed", windSpeed.getAverage());
        }
        if (windDirReports > 0) {
            double deg = Math.toDegrees(Math.atan2(windSin / windDirReports, windCos / windDirReports));
            // 归一化到 [0,360)：不能用 "deg<0 则 +360"——-1.7e-14 这类负零级残差加 360
            // 会被双精度 ulp 吞成恰好 360.0（测试实测撞到）；取模对 0/360 边界都正确
            resp.put("windDir", (deg + 360.0) % 360.0);
        }
        String weather = topWeather(weatherVotes);
        if (weather != null) {
            resp.put("weather", weather);
        }
        resp.put("stations", stations);
        return resp;
    }

    /** 上报了（非 NaN）才进站表与均值累加器——NaN 序列化进 JSON 是非法字面量。 */
    private static void putIfReported(Map<String, Object> station, String key,
                                      double value, DoubleSummaryStatistics stats) {
        if (!Double.isNaN(value)) {
            station.put(key, value);
            stats.accept(value);
        }
    }

    /** MAVLink ENVIRONMENT_STATUS.weather（0-4，drone-sim Weather 枚举）→ 前端 WEATHER_MARKS 键；未知码返回 null。 */
    private static String weatherName(int code) {
        return switch (code) {
            case 0 -> "sunny";
            case 1 -> "cloudy";
            case 2 -> "rainy";
            case 3 -> "snowy";
            case 4 -> "fog";
            default -> null;
        };
    }

    /** 天气众数：票数最多者；并列取编号小者（确定性）；无票返回 null。 */
    private static String topWeather(Map<Integer, Integer> votes) {
        return votes.entrySet().stream()
                .min(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> weatherName(e.getKey()))
                .orElse(null);
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
