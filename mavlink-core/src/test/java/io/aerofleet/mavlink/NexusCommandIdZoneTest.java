package io.aerofleet.mavlink;

import io.aerofleet.mavlink.enums.MavEnums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NexusSky 自定义 MAV_CMD 私有区守卫。
 * <p>
 * 背景：2026-10-04 之前，本仓库 8 条自定义命令（环境配置 310-312、喷洒/抛投/载荷
 * 320-322、雷达/旋翼配置 420/421）直接占用 MAVLink 官方与方言的命令分配带。
 * 其中 420/421 与 ArduPilot 方言实锤冲突（420=NAV_GUIDED_ENABLE、
 * 421=NAV_CONTINUE_AND_CHANGE_ALT），其余虽未实锤，但按消息 ID 治理的同一标准
 * （docs/sitl-integration.md §7：官方分配带必须**整带**避开，而非只躲已知值），
 * 全部 8 条于同日搬入私有区**命令子段 30080-30099**（见 {@link MavEnums} 注释里的
 * 新旧映射）。命令与消息是两个命名空间，数值本可重叠，取 30080+ 纯为与消息段
 * 30000-30063 保持视觉分离。
 * <p>
 * 本测试把所有 NEXUS 命令常量钉在命令子段内且两两互异——**谁把常量改回官方带
 * （例如手滑写回 420），构建会红**。
 */
@DisplayName("NexusSky 自定义 MAV_CMD：私有区命令子段 30080-30099 守卫")
class NexusCommandIdZoneTest {

    /** 命令子段下界（含）。 */
    private static final int ZONE_MIN = 30080;
    /** 命令子段上界（含）。 */
    private static final int ZONE_MAX = 30099;

    /** 全部 8 条 NEXUS 命令（名称仅用于失败信息，不改判语义）。 */
    private static final Map<String, Integer> NEXUS_COMMANDS = new HashMap<>();

    static {
        NEXUS_COMMANDS.put("MAV_CMD_NEXUS_ENV_SET_WIND", MavEnums.MAV_CMD_NEXUS_ENV_SET_WIND);
        NEXUS_COMMANDS.put("MAV_CMD_NEXUS_ENV_SET_WEATHER", MavEnums.MAV_CMD_NEXUS_ENV_SET_WEATHER);
        NEXUS_COMMANDS.put("MAV_CMD_NEXUS_ENV_SET_THRESHOLDS", MavEnums.MAV_CMD_NEXUS_ENV_SET_THRESHOLDS);
        NEXUS_COMMANDS.put("MAV_CMD_NEXUS_SPRAY_CONTROL", MavEnums.MAV_CMD_NEXUS_SPRAY_CONTROL);
        NEXUS_COMMANDS.put("MAV_CMD_NEXUS_GRIPPER_CONTROL", MavEnums.MAV_CMD_NEXUS_GRIPPER_CONTROL);
        NEXUS_COMMANDS.put("MAV_CMD_NEXUS_PAYLOAD_QUERY", MavEnums.MAV_CMD_NEXUS_PAYLOAD_QUERY);
        NEXUS_COMMANDS.put("MAV_CMD_NEXUS_RADAR_CONFIG", MavEnums.MAV_CMD_NEXUS_RADAR_CONFIG);
        NEXUS_COMMANDS.put("MAV_CMD_NEXUS_ROTOR_CONFIG", MavEnums.MAV_CMD_NEXUS_ROTOR_CONFIG);
    }

    @Test
    @DisplayName("8 条 NEXUS 命令全部落在私有区命令子段 30080-30099")
    void allNexusCommandsInsidePrivateZone() {
        NEXUS_COMMANDS.forEach((name, id) ->
                assertTrue(id >= ZONE_MIN && id <= ZONE_MAX,
                        name + "=" + id + " 必须在命令子段 [" + ZONE_MIN + ", " + ZONE_MAX
                                + "] 内（改回官方带会与 MAVLink 方言冲突）"));
    }

    @Test
    @DisplayName("8 条 NEXUS 命令两两互异（防重定义互相顶掉派发路由）")
    void allNexusCommandsPairwiseDistinct() {
        assertEquals(NEXUS_COMMANDS.size(), NEXUS_COMMANDS.values().stream().distinct().count(),
                "NEXUS 命令 ID 出现重复——机载 switch 路由会被先命中的 case 顶掉");
    }

    @Test
    @DisplayName("新旧映射保持相对次序（310-312→30080-30082 / 320-322→30083-30085 / 420/421→30086/30087）")
    void migrationPreservesRelativeOrder() {
        assertEquals(30080, MavEnums.MAV_CMD_NEXUS_ENV_SET_WIND);
        assertEquals(30081, MavEnums.MAV_CMD_NEXUS_ENV_SET_WEATHER);
        assertEquals(30082, MavEnums.MAV_CMD_NEXUS_ENV_SET_THRESHOLDS);
        assertEquals(30083, MavEnums.MAV_CMD_NEXUS_SPRAY_CONTROL);
        assertEquals(30084, MavEnums.MAV_CMD_NEXUS_GRIPPER_CONTROL);
        assertEquals(30085, MavEnums.MAV_CMD_NEXUS_PAYLOAD_QUERY);
        assertEquals(30086, MavEnums.MAV_CMD_NEXUS_RADAR_CONFIG);
        assertEquals(30087, MavEnums.MAV_CMD_NEXUS_ROTOR_CONFIG);
    }
}
