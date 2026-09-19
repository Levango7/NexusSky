package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SURVEILLANCE_STATUS (msgId=479) 编解码单测（M14 安防报警）。
 */
@DisplayName("SurveillanceStatusMsg 编解码 (msgId=479)")
class SurveillanceStatusMsgTest {

    private static SurveillanceStatusMsg roundtrip(SurveillanceStatusMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return SurveillanceStatusMsg.decode(frame);
    }

    @Test
    @DisplayName("消息 ID=479、LEN=14、CRC_EXTRA=263")
    void messageIdAndConstants() {
        assertThat(SurveillanceStatusMsg.ID).isEqualTo(479);
        assertThat(SurveillanceStatusMsg.LEN).isEqualTo(14);
        assertThat(SurveillanceStatusMsg.CRC_EXTRA).isEqualTo(263);
    }

    @Test
    @DisplayName("全字段 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        SurveillanceStatusMsg orig = new SurveillanceStatusMsg(
                1700000000L, 86400L, 1001, 0, 0, 8, 10);
        SurveillanceStatusMsg back = roundtrip(orig);

        assertThat(back.lastEventMs).isEqualTo(1700000000L);
        assertThat(back.uptimeSec).isEqualTo(86400L);
        assertThat(back.deviceId).isEqualTo(1001);
        assertThat(back.deviceType).isEqualTo(0);
        assertThat(back.status).isEqualTo(0);
        assertThat(back.onlineCameras).isEqualTo(8);
        assertThat(back.totalCameras).isEqualTo(10);
    }

    @Test
    @DisplayName("encode 产出 14 字节 payload")
    void encodeLength() {
        SurveillanceStatusMsg msg = new SurveillanceStatusMsg(
                0, 0, 0, 0, 0, 0, 0);
        assertThat(msg.encode()).hasSize(14);
        assertThat(msg.messageId()).isEqualTo(479);
    }

    @Test
    @DisplayName("各字段设置/获取：海康在线 + 8/10 摄像头")
    void fieldAccessHikvisionOnline() {
        SurveillanceStatusMsg msg = new SurveillanceStatusMsg(
                123456L, 3600L, 2002, 0, 0, 8, 10);
        assertThat(msg.deviceId).isEqualTo(2002);
        assertThat(msg.deviceType).isEqualTo(0);   // 海康
        assertThat(msg.status).isEqualTo(0);       // 在线
        assertThat(msg.onlineCameras).isEqualTo(8);
        assertThat(msg.totalCameras).isEqualTo(10);
    }

    @Test
    @DisplayName("边界值：lastEventMs/uptimeSec/deviceId 及 u8 字段最大最小")
    void boundaryValues() {
        // 最大值（deviceType/status 受语义范围约束：[0,3]/[0,3]）
        SurveillanceStatusMsg maxMsg = new SurveillanceStatusMsg(
                0xFFFFFFFFL, 0xFFFFFFFFL, 0xFFFF, 3, 3, 255, 255);
        SurveillanceStatusMsg maxBack = roundtrip(maxMsg);
        assertThat(maxBack.lastEventMs).isEqualTo(0xFFFFFFFFL);
        assertThat(maxBack.uptimeSec).isEqualTo(0xFFFFFFFFL);
        assertThat(maxBack.deviceId).isEqualTo(0xFFFF);
        assertThat(maxBack.deviceType).isEqualTo(3);
        assertThat(maxBack.status).isEqualTo(3);
        assertThat(maxBack.onlineCameras).isEqualTo(255);
        assertThat(maxBack.totalCameras).isEqualTo(255);

        // 最小值
        SurveillanceStatusMsg minMsg = new SurveillanceStatusMsg(
                0, 0, 0, 0, 0, 0, 0);
        SurveillanceStatusMsg minBack = roundtrip(minMsg);
        assertThat(minBack.lastEventMs).isZero();
        assertThat(minBack.uptimeSec).isZero();
        assertThat(minBack.deviceId).isZero();
        assertThat(minBack.deviceType).isZero();
        assertThat(minBack.status).isZero();
        assertThat(minBack.onlineCameras).isZero();
        assertThat(minBack.totalCameras).isZero();
    }

    @Test
    @DisplayName("设备类型语义：0=海康, 1=大华, 2=宇视, 3=其他")
    void deviceTypeSemantics() {
        for (int type = 0; type <= 3; type++) {
            SurveillanceStatusMsg orig = new SurveillanceStatusMsg(
                    1L, 1L, 1, type, 0, 1, 1);
            SurveillanceStatusMsg back = roundtrip(orig);
            assertThat(back.deviceType).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("状态语义：0=在线, 1=离线, 2=故障, 3=维护")
    void statusSemantics() {
        for (int status = 0; status <= 3; status++) {
            SurveillanceStatusMsg orig = new SurveillanceStatusMsg(
                    1L, 1L, 1, 0, status, 1, 1);
            SurveillanceStatusMsg back = roundtrip(orig);
            assertThat(back.status).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("短 payload 容忍解码：仅 9 字节时还原 lastEventMs+uptimeSec+deviceId，其余填 0")
    void decodeShortPayloadTolerant() {
        byte[] shortPayload = new byte[]{
            0x39, 0x30, 0x00, 0x00,  // lastEventMs=12345
            0x01, 0x00, 0x00, 0x00,  // uptimeSec=1
            0x01                     // deviceId 高字节缺失（不完整，不读）
        };
        MavlinkFrame frame = new MavlinkFrame(9, 0, 0, 0, 1, 1, 479, shortPayload, 0);
        SurveillanceStatusMsg msg = SurveillanceStatusMsg.decode(frame);

        assertThat(msg.lastEventMs).isEqualTo(12345L);
        assertThat(msg.uptimeSec).isEqualTo(1L);
        assertThat(msg.deviceId).isZero();   // len <= 9，填 0
        assertThat(msg.deviceType).isZero();
        assertThat(msg.status).isZero();
        assertThat(msg.onlineCameras).isZero();
        assertThat(msg.totalCameras).isZero();
    }

    @Test
    @DisplayName("toString 包含关键字段")
    void toStringContainsFields() {
        SurveillanceStatusMsg msg = new SurveillanceStatusMsg(
                100L, 200L, 5, 1, 2, 3, 4);
        String s = msg.toString();
        assertThat(s).contains("dev=5", "type=1", "status=2", "cams=3/4");
    }
}