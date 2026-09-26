package io.aerofleet.cloud.voicecmd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VoiceCommandParser} 单元测试。
 * <p>
 * 测试各种语音指令解析场景，包括中英文关键词、复合指令和未知指令。
 */
@DisplayName("VoiceCommandParser 语音指令解析")
class VoiceCommandParserTest {

    private final VoiceCommandParser parser = new VoiceCommandParser();

    // --- 动作解析：中文关键词 ---

    @Test
    @DisplayName("解析中文'起飞'指令")
    void parse_takeoff_cn() {
        ParsedCommand cmd = parser.parse("起飞");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.TAKEOFF);
        assertThat(cmd.getRawText()).isEqualTo("起飞");
        assertThat(cmd.getConfidencePct()).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("解析中文'降落'指令")
    void parse_land_cn() {
        ParsedCommand cmd = parser.parse("降落");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.LAND);
        assertThat(cmd.getConfidencePct()).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("解析中文'返航'指令")
    void parse_return_cn() {
        ParsedCommand cmd = parser.parse("返航");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.RETURN);
        assertThat(cmd.getConfidencePct()).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("解析中文'悬停'指令")
    void parse_hover_cn() {
        ParsedCommand cmd = parser.parse("悬停");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.HOVER);
        assertThat(cmd.getConfidencePct()).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("解析中文'拍照'指令")
    void parse_photo_cn() {
        ParsedCommand cmd = parser.parse("拍照");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.PHOTO);
        assertThat(cmd.getConfidencePct()).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("解析中文'录像'指令")
    void parse_record_cn() {
        ParsedCommand cmd = parser.parse("录像");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.RECORD);
        assertThat(cmd.getConfidencePct()).isGreaterThanOrEqualTo(60);
    }

    // --- 动作解析：英文关键词 ---

    @Test
    @DisplayName("解析英文'takeoff'指令")
    void parse_takeoff_en() {
        ParsedCommand cmd = parser.parse("takeoff");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.TAKEOFF);
        assertThat(cmd.getConfidencePct()).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("解析英文'land'指令")
    void parse_land_en() {
        ParsedCommand cmd = parser.parse("land");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.LAND);
    }

    @Test
    @DisplayName("解析英文'return'指令")
    void parse_return_en() {
        ParsedCommand cmd = parser.parse("return");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.RETURN);
    }

    @Test
    @DisplayName("解析英文'rtl'指令")
    void parse_rtl_en() {
        ParsedCommand cmd = parser.parse("rtl");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.RETURN);
    }

    @Test
    @DisplayName("解析英文'hover'指令")
    void parse_hover_en() {
        ParsedCommand cmd = parser.parse("hover");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.HOVER);
    }

    @Test
    @DisplayName("解析英文'photo'指令")
    void parse_photo_en() {
        ParsedCommand cmd = parser.parse("photo");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.PHOTO);
    }

    @Test
    @DisplayName("解析英文'record'指令")
    void parse_record_en() {
        ParsedCommand cmd = parser.parse("record");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.RECORD);
    }

    // --- 参数解析 ---

    @Test
    @DisplayName("解析'高度50米'参数")
    void parse_altitude_cn() {
        ParsedCommand cmd = parser.parse("高度50米");

        assertThat(cmd.getAltitudeM()).isEqualTo(50.0);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.SET_ALTITUDE);
    }

    @Test
    @DisplayName("解析'altitude 100'参数")
    void parse_altitude_en() {
        ParsedCommand cmd = parser.parse("altitude 100");

        assertThat(cmd.getAltitudeM()).isEqualTo(100.0);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.SET_ALTITUDE);
    }

    @Test
    @DisplayName("解析'速度10'参数")
    void parse_speed_cn() {
        ParsedCommand cmd = parser.parse("速度10");

        assertThat(cmd.getSpeedMps()).isEqualTo(10.0);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.SET_SPEED);
    }

    @Test
    @DisplayName("解析'speed 15'参数")
    void parse_speed_en() {
        ParsedCommand cmd = parser.parse("speed 15");

        assertThat(cmd.getSpeedMps()).isEqualTo(15.0);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.SET_SPEED);
    }

    @Test
    @DisplayName("解析'前往东门'目标位置")
    void parse_target_cn() {
        ParsedCommand cmd = parser.parse("前往东门");

        assertThat(cmd.getTargetName()).isEqualTo("东门");
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.FLY_TO);
    }

    @Test
    @DisplayName("解析'go to gate'目标位置")
    void parse_target_en() {
        ParsedCommand cmd = parser.parse("go to gate");

        assertThat(cmd.getTargetName()).isEqualTo("gate");
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.FLY_TO);
    }

    // --- 设备编号解析 ---

    @Test
    @DisplayName("解析'无人机1号'设备编号")
    void parse_sysid_cn() {
        ParsedCommand cmd = parser.parse("无人机1号起飞");

        assertThat(cmd.getSysid()).isEqualTo(1);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.TAKEOFF);
    }

    @Test
    @DisplayName("解析'drone 2'设备编号")
    void parse_sysid_en() {
        ParsedCommand cmd = parser.parse("drone 2 takeoff");

        assertThat(cmd.getSysid()).isEqualTo(2);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.TAKEOFF);
    }

    // --- 优先级解析 ---

    @Test
    @DisplayName("解析'紧急'优先级")
    void parse_emergency_cn() {
        ParsedCommand cmd = parser.parse("紧急起飞");

        assertThat(cmd.getPriority()).isEqualTo(ParsedCommand.Priority.HIGH);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.TAKEOFF);
    }

    @Test
    @DisplayName("解析'emergency'优先级")
    void parse_emergency_en() {
        ParsedCommand cmd = parser.parse("emergency land");

        assertThat(cmd.getPriority()).isEqualTo(ParsedCommand.Priority.HIGH);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.LAND);
    }

    // --- 复合指令解析 ---

    @Test
    @DisplayName("解析复合指令：'无人机一号起飞，高度50米，前往东门侦察'")
    void parse_complex_command() {
        ParsedCommand cmd = parser.parse("无人机1号起飞，高度50米，前往东门侦察");

        assertThat(cmd.getSysid()).isEqualTo(1);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.TAKEOFF);
        assertThat(cmd.getAltitudeM()).isEqualTo(50.0);
        assertThat(cmd.getTargetName()).isEqualTo("东门侦察");
        assertThat(cmd.getConfidencePct()).isGreaterThanOrEqualTo(60);
    }

    @Test
    @DisplayName("解析复合指令：'drone 3 takeoff altitude 80 go to north gate'")
    void parse_complex_command_en() {
        ParsedCommand cmd = parser.parse("drone 3 takeoff altitude 80 go to north gate");

        assertThat(cmd.getSysid()).isEqualTo(3);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.TAKEOFF);
        assertThat(cmd.getAltitudeM()).isEqualTo(80.0);
        assertThat(cmd.getTargetName()).isEqualTo("north gate");
    }

    @Test
    @DisplayName("解析复合指令：'紧急返航无人机2号'")
    void parse_emergency_return() {
        ParsedCommand cmd = parser.parse("紧急返航无人机2号");

        assertThat(cmd.getPriority()).isEqualTo(ParsedCommand.Priority.HIGH);
        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.RETURN);
        assertThat(cmd.getSysid()).isEqualTo(2);
    }

    // --- 未知指令与边界情况 ---

    @Test
    @DisplayName("未知指令返回 UNKNOWN")
    void parse_unknown() {
        ParsedCommand cmd = parser.parse("今天天气怎么样");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.UNKNOWN);
        assertThat(cmd.getConfidencePct()).isEqualTo(0);
    }

    @Test
    @DisplayName("空文本返回 UNKNOWN")
    void parse_empty() {
        ParsedCommand cmd = parser.parse("");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.UNKNOWN);
        assertThat(cmd.getConfidencePct()).isEqualTo(0);
    }

    @Test
    @DisplayName("null 文本返回 UNKNOWN")
    void parse_null() {
        ParsedCommand cmd = parser.parse(null);

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.UNKNOWN);
        assertThat(cmd.getConfidencePct()).isEqualTo(0);
    }

    @Test
    @DisplayName("纯空白文本返回 UNKNOWN")
    void parse_blank() {
        ParsedCommand cmd = parser.parse("   ");

        assertThat(cmd.getAction()).isEqualTo(ParsedCommand.Action.UNKNOWN);
        assertThat(cmd.getConfidencePct()).isEqualTo(0);
    }

    // --- 默认值验证 ---

    @Test
    @DisplayName("默认优先级为 NORMAL")
    void parse_defaultPriority() {
        ParsedCommand cmd = parser.parse("起飞");

        assertThat(cmd.getPriority()).isEqualTo(ParsedCommand.Priority.NORMAL);
    }

    @Test
    @DisplayName("默认 sysid 为 -1（未指定）")
    void parse_defaultSysid() {
        ParsedCommand cmd = parser.parse("起飞");

        assertThat(cmd.getSysid()).isEqualTo(-1);
    }
}