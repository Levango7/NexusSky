package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EMERGENCY_MISSION_PLAN (msgId=465) 编解码单测（M9 应急任务编排）。
 */
@DisplayName("EmergencyMissionPlanMsg 编解码 (msgId=465)")
class EmergencyMissionPlanMsgTest {

    private static EmergencyMissionPlanMsg roundtrip(EmergencyMissionPlanMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return EmergencyMissionPlanMsg.decode(frame);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        EmergencyMissionPlanMsg orig = new EmergencyMissionPlanMsg(
                12345L, 0, 2, 1, 399000000, 1163000000, 5000, 12, 85, 92, 1, 1700000000L);
        EmergencyMissionPlanMsg back = roundtrip(orig);

        assertThat(back.planId).isEqualTo(12345L);
        assertThat(back.scenarioType).isEqualTo(0);
        assertThat(back.phase).isEqualTo(2);
        assertThat(back.phaseStatus).isEqualTo(1);
        assertThat(back.disasterCenterLat).isEqualTo(399000000);
        assertThat(back.disasterCenterLon).isEqualTo(1163000000);
        assertThat(back.disasterRadius).isEqualTo(5000);
        assertThat(back.droneCount).isEqualTo(12);
        assertThat(back.coverageRate).isEqualTo(85);
        assertThat(back.connectRate).isEqualTo(92);
        assertThat(back.priority).isEqualTo(1);
        assertThat(back.timestamp).isEqualTo(1700000000L);
    }

    @Test
    @DisplayName("消息 ID=465、LEN=25、CRC_EXTRA=249")
    void messageIdAndConstants() {
        assertThat(EmergencyMissionPlanMsg.ID).isEqualTo(465);
        assertThat(EmergencyMissionPlanMsg.LEN).isEqualTo(25);
        assertThat(EmergencyMissionPlanMsg.CRC_EXTRA).isEqualTo(249);
    }

    @Test
    @DisplayName("encode 产出 25 字节 payload")
    void encodeLength() {
        EmergencyMissionPlanMsg msg = new EmergencyMissionPlanMsg(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(msg.encode()).hasSize(25);
        assertThat(msg.messageId()).isEqualTo(465);
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 5 字节时还原 planId+scenarioType，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{0x39, 0x30, 0x00, 0x00, 0x02};
        MavlinkFrame frame = new MavlinkFrame(5, 0, 0, 0, 1, 1, 465, shortPayload, 0);
        EmergencyMissionPlanMsg msg = EmergencyMissionPlanMsg.decode(frame);

        assertThat(msg.planId).isEqualTo(12345L);
        assertThat(msg.scenarioType).isEqualTo(2);
        assertThat(msg.phase).isZero();
        assertThat(msg.disasterCenterLat).isZero();
        assertThat(msg.timestamp).isZero();
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        EmergencyMissionPlanMsg msg = new EmergencyMissionPlanMsg(
                999L, 1, 3, 2, 100, 200, 3000, 8, 90, 95, 2, 0L);
        String s = msg.toString();
        assertThat(s).contains("planId=999", "scenario=1", "phase=3", "drones=8", "cov=90%");
    }
}