package io.aerofleet.cloud.mission.common;

import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.MissionItemInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务项规范化回归（PX4 首验实测）。
 *
 * <p>PX4 的两条硬契约在任务项层面：
 * <ul>
 *   <li>帧是硬校验（parse_mavlink_mission_item）：带坐标帧（0/3/5/6）进「位置命令」分支，
 *       RTL(20)/拍照(2000) 这类无坐标命令混进去 → 整单回 {@code MAV_MISSION_UNSUPPORTED}
 *       （SITL 实测：MISSION_ACK result=3，且 PX4 随即中止传输——全任务作废）；
 *       无坐标命令必须 {@code MAV_FRAME_MISSION(2)}。AP 根本不看帧。</li>
 *   <li>{@code autocontinue} 被真正消费（mission.cpp：「switch to next waypoint if
 *       autocontinue flag set」）——置 0 时到点即停不再前进（SITL 实测：起飞到 20m 后
 *       悬停 150s 不奔航点）。AP 与 drone-sim 都不消费该位，历来自带 0 也全绿。</li>
 * </ul>
 * 其余改写：{@code GLOBAL_RELATIVE_ALT(3)} → {@code ..._INT(6)}（同语义，INT 报文规范编码）。
 */
class MissionItemNormalizationTest {

    @Test
    @DisplayName("按命令种类改写：RTL/拍照→2，位置命令 3→6，绝对高 0/5 与已是 6 的原样")
    void normalizeItems_byCommandKind() {
        List<MissionItemInt> in = List.of(
                item(0, MavEnums.MAV_CMD_NAV_WAYPOINT, MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT),
                item(1, MavEnums.MAV_CMD_NAV_TAKEOFF, MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT),
                item(2, MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH, MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT),
                item(3, MavEnums.MAV_CMD_IMAGE_START_CAPTURE, MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT),
                item(4, MavEnums.MAV_CMD_NAV_WAYPOINT, MavEnums.MAV_FRAME_GLOBAL),
                item(5, MavEnums.MAV_CMD_NAV_WAYPOINT, MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT));

        List<MissionItemInt> out = DroneCommandService.normalizeMissionItems(in);

        assertThat(out).extracting(it -> it.frame).containsExactly(
                MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,   // waypoint 3→6
                MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,   // takeoff 3→6
                MavEnums.MAV_FRAME_MISSION,                   // RTL → 2（PX4 硬校验）
                MavEnums.MAV_FRAME_MISSION,                   // 拍照 → 2
                MavEnums.MAV_FRAME_GLOBAL,                    // 绝对高（含 AP home 槽）原样
                MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT);  // 已是 6
    }

    @Test
    @DisplayName("autocontinue 一律置 1：PX4 置 0 会到点即停（SITL 起飞后悬停 150s 实测）")
    void normalizeItems_forcesAutocontinue() {
        // 构造 autocontinue=0 的项（历史线上形态：toMissionItems/IncidentDispatch 都发 0）
        List<MissionItemInt> in = List.of(
                new MissionItemInt(1, 1, 0, MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT,
                        MavEnums.MAV_CMD_NAV_TAKEOFF, 0, 0,
                        0f, 0f, 0f, 0f, -353632622, 1491652375, 20f,
                        MavEnums.MAV_MISSION_TYPE_MISSION));

        List<MissionItemInt> out = DroneCommandService.normalizeMissionItems(in);

        assertThat(out.get(0).autocontinue).isEqualTo(1);
        // 入参不被修改
        assertThat(in.get(0).autocontinue).isZero();
    }

    @Test
    @DisplayName("规范化不改语义字段：seq/command/坐标/高度/参数原样，仅 frame/autocontinue 变")
    void normalizeItems_keepsEverythingElse() {
        MissionItemInt src = item(7, MavEnums.MAV_CMD_NAV_WAYPOINT,
                MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT);
        MissionItemInt out = DroneCommandService.normalizeMissionItems(List.of(src)).get(0);

        assertThat(out.seq).isEqualTo(src.seq);
        assertThat(out.command).isEqualTo(src.command);
        assertThat(out.x).isEqualTo(src.x);
        assertThat(out.y).isEqualTo(src.y);
        assertThat(out.z).isEqualTo(src.z);
        assertThat(out.param1).isEqualTo(src.param1);
        assertThat(out.targetSystem).isEqualTo(src.targetSystem);
        assertThat(out.missionType).isEqualTo(src.missionType);
        // 入参不被修改
        assertThat(src.frame).isEqualTo(MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT);
    }

    @Test
    @DisplayName("无需改写项原引用返回；需改写项生成新对象（省对象，且不改入参）")
    void normalizeItems_reusesUnchangedItems() {
        // 已规范形态：frame=0（绝对高）+ autocontinue=1
        MissionItemInt home = new MissionItemInt(1, 1, 0, MavEnums.MAV_FRAME_GLOBAL,
                MavEnums.MAV_CMD_NAV_WAYPOINT, 0, 1,
                0f, 0f, 0f, 0f, -353632622, 1491652375, 584f,
                MavEnums.MAV_MISSION_TYPE_MISSION);
        MissionItemInt rtl = item(1, MavEnums.MAV_CMD_NAV_RETURN_TO_LAUNCH,
                MavEnums.MAV_FRAME_GLOBAL_RELATIVE_ALT);

        List<MissionItemInt> out = DroneCommandService.normalizeMissionItems(List.of(home, rtl));

        assertThat(out.get(0)).isSameAs(home);
        assertThat(out.get(1)).isNotSameAs(rtl);
        assertThat(out.get(1).frame).isEqualTo(MavEnums.MAV_FRAME_MISSION);
        assertThat(out.get(1).autocontinue).isEqualTo(1);
    }

    private static MissionItemInt item(int seq, int command, int frame) {
        return new MissionItemInt(1, 1, seq, frame, command, 0, 0,
                0f, 0f, 0f, 0f, -353632622, 1491652375, 20f,
                MavEnums.MAV_MISSION_TYPE_MISSION);
    }
}
