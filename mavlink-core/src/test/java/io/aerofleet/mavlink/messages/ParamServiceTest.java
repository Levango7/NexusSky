package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAVLink 参数服务族（PARAM_REQUEST_READ / PARAM_REQUEST_LIST / PARAM_VALUE / PARAM_SET）回归。
 *
 * <p>存在理由：参数服务是 GCS 读/写参数的主通路（官方序列：读全量 = REQUEST_LIST →
 * 目标逐条回 PARAM_VALUE；读单个 = REQUEST_READ → PARAM_VALUE；写 = PARAM_SET → 目标回
 * PARAM_VALUE——<b>即使写失败也回</b>，失败靠请求方超时/值比对判定）。对照批
 * （docs/protocol-vendor-integration.md §2 MAVLink 行，2026-11-13）发现 mavlink-core 此前
 * 无任何 {@code PARAM_*} 消息类，本族为该协议面的补全。
 *
 * <p>帧字节取自 pymavlink（v20 dialect）官方打包，非手工构造；{@code param_request_read}
 * 另取 MAVLink2 尾零裁剪帧（{@code param_id} 尾部 NUL 被裁）以钉住入口零填充路径。
 */
class ParamServiceTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // sys=255 comp=190 seq=0；param_id="THROTTLE_X"（10 字符，尾部 NUL 填充到 16）
    private static final String OFFICIAL_READ_FULL =
            "fd14000000ffbe140000070001015448524f54544c455f580000000000001435";
    private static final String OFFICIAL_READ_TRIM =
            "fd0e000000ffbe140000070001015448524f54544c455f58572b";
    private static final String OFFICIAL_LIST =
            "fd02000000ffbe150000010188c0";
    private static final String OFFICIAL_VALUE =
            "fd19000000ffbe1600000000c03f640007005448524f54544c455f5800000000000009d1c1";
    private static final String OFFICIAL_SET =
            "fd17000000ffbe1700000000c03f01015448524f54544c455f5800000000000009065e";

    private static ParamRequestRead sampleRead() {
        return new ParamRequestRead(7, 1, 1, "THROTTLE_X");
    }

    private static ParamValue sampleValue() {
        return new ParamValue(100, 7, "THROTTLE_X", 1.5f, 9);
    }

    private static ParamSet sampleSet() {
        return new ParamSet(1, 1, "THROTTLE_X", 1.5f, 9);
    }

    @Test
    @DisplayName("注册表：id/LEN/CRC_EXTRA 与官方一致（pymavlink 核验）")
    void registryHasParameterMessages() {
        assertEquals(20, MavlinkMessageInfo.lengthOf(ParamRequestRead.ID));
        assertEquals(214, MavlinkMessageInfo.crcExtraOf(ParamRequestRead.ID));
        assertEquals(2, MavlinkMessageInfo.lengthOf(ParamRequestList.ID));
        assertEquals(159, MavlinkMessageInfo.crcExtraOf(ParamRequestList.ID));
        assertEquals(25, MavlinkMessageInfo.lengthOf(ParamValue.ID));
        assertEquals(220, MavlinkMessageInfo.crcExtraOf(ParamValue.ID));
        assertEquals(23, MavlinkMessageInfo.lengthOf(ParamSet.ID));
        assertEquals(168, MavlinkMessageInfo.crcExtraOf(ParamSet.ID));
    }

    @Test
    @DisplayName("四条消息编码与 pymavlink 官方帧逐字节一致")
    void encodeMatchesOfficialFrames() {
        assertArrayEquals(hex(OFFICIAL_READ_FULL), sampleRead().toFrame(255, 190, 0).encodeV2());
        assertArrayEquals(hex(OFFICIAL_LIST), new ParamRequestList(1, 1).toFrame(255, 190, 0).encodeV2());
        assertArrayEquals(hex(OFFICIAL_VALUE), sampleValue().toFrame(255, 190, 0).encodeV2());
        assertArrayEquals(hex(OFFICIAL_SET), sampleSet().toFrame(255, 190, 0).encodeV2());
    }

    @Test
    @DisplayName("解码官方裁剪帧（param_id 尾零被裁）：分派入口零填充后字段全部正确")
    void decodeOfficialTrimmedFrame() {
        MavlinkFrame f = MavlinkFrame.decodeV2Verified(hex(OFFICIAL_READ_TRIM));
        assertEquals(14, f.getPayloadLength(), "wire payload 应为 14 字节（尾零被裁）");

        ParamRequestRead decoded = (ParamRequestRead) MavlinkMessage.decode(f);
        assertEquals(7, decoded.paramIndex);
        assertEquals(1, decoded.targetSystem);
        assertEquals(1, decoded.targetComponent);
        assertEquals("THROTTLE_X", decoded.paramId, "裁剪不影响字符串截断语义");
    }

    @Test
    @DisplayName("字节级往返：四条消息编码 → v2 校验解码 → 字段不变")
    void roundTripThroughBytes() {
        ParamRequestRead read = (ParamRequestRead) MavlinkMessage.decode(
                MavlinkFrame.decodeV2Verified(sampleRead().toFrame(255, 190, 0).encodeV2()));
        assertEquals(7, read.paramIndex);
        assertEquals("THROTTLE_X", read.paramId);

        ParamRequestList list = (ParamRequestList) MavlinkMessage.decode(
                MavlinkFrame.decodeV2Verified(new ParamRequestList(1, 1).toFrame(255, 190, 0).encodeV2()));
        assertEquals(1, list.targetSystem);
        assertEquals(1, list.targetComponent);

        ParamValue value = (ParamValue) MavlinkMessage.decode(
                MavlinkFrame.decodeV2Verified(sampleValue().toFrame(255, 190, 0).encodeV2()));
        assertEquals(100, value.paramCount);
        assertEquals(7, value.paramIndex);
        assertEquals("THROTTLE_X", value.paramId);
        assertEquals(1.5f, value.paramValue);
        assertEquals(9, value.paramType);

        ParamSet set = (ParamSet) MavlinkMessage.decode(
                MavlinkFrame.decodeV2Verified(sampleSet().toFrame(255, 190, 0).encodeV2()));
        assertEquals(1, set.targetSystem);
        assertEquals(1.5f, set.paramValue);
        assertEquals(9, set.paramType);
    }

    @Test
    @DisplayName("param_id char[16] 填充/截断：短名编码补 NUL，解码截到首个 NUL")
    void paramIdPaddingAndTruncation() {
        byte[] payload = new ParamRequestRead(-1, 1, 1, "A").encode();
        assertEquals(20, payload.length);
        assertEquals('A', payload[4]);
        for (int i = 5; i < 20; i++) {
            assertEquals(0, payload[i], "param_id 余段应为 NUL 填充");
        }
        ParamRequestRead decoded = ParamRequestRead.decode(
                MavlinkFrame.decodeV2Verified(new ParamRequestRead(-1, 1, 1, "A").toFrame(255, 190, 0).encodeV2()));
        assertEquals("A", decoded.paramId);
        assertEquals(-1, decoded.paramIndex, "官方语义：index=-1 表示按名匹配");
    }

    @Test
    @DisplayName("协议序列形状：REQUEST_LIST/READ 定向、SET→VALUE 回执字段映射")
    void protocolSequenceShapes() {
        // 读全量：GCS 广播 REQUEST_LIST（目标字段即接收方）
        ParamRequestList list = (ParamRequestList) MavlinkMessage.decode(
                MavlinkFrame.decodeV2Verified(new ParamRequestList(1, 1).toFrame(255, 190, 0).encodeV2()));
        assertEquals(1, list.targetSystem);
        assertEquals(1, list.targetComponent);

        // 读单个：REQUEST_READ 按名（index=-1）或按索引
        ParamRequestRead byName = new ParamRequestRead(-1, 1, 1, "THROTTLE_X");
        ParamRequestRead byIndex = new ParamRequestRead(7, 1, 1, "");
        assertTrue(byName.paramId.length() > 0 && byName.paramIndex == -1);
        assertTrue(byIndex.paramId.isEmpty() && byIndex.paramIndex == 7);

        // 写：PARAM_SET → 目标回 PARAM_VALUE（同 id/值/类型；count/index 由目标参数表决定）
        ParamSet set = sampleSet();
        ParamValue echo = new ParamValue(100, 7, set.paramId, set.paramValue, set.paramType);
        assertEquals(set.paramId, echo.paramId);
        assertEquals(set.paramValue, echo.paramValue);
        assertEquals(set.paramType, echo.paramType);
        assertEquals(100, echo.paramCount, "param_count 是目标参数表长度，不是请求方字段");
    }
}
