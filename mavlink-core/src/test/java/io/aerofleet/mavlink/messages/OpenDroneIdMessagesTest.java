package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.MavlinkParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OPEN_DRONE_ID_* 消息编解码单测（C2 RID 里程碑）：
 * 1. 各消息类 encode→decode round-trip 字段一致
 * 2. payload 长度与 MavlinkMessageInfo.lengthOf 一致
 * 3. 字符串字段 0 填充
 * 4. MESSAGE_PACK pack→encode→decode→unpack round-trip
 * 5. MavlinkMessageInfo 扩展区 isKnown/lengthOf
 * 6. MavlinkMessage.decode() 分发
 */
class OpenDroneIdMessagesTest {

    /** 构造 v2 帧 → 解析 → decode 往返。 */
    private static MavlinkMessage roundtrip(MavlinkMessage msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (CRC registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    // ========== 1. 各消息类 encode→decode round-trip ==========

    @Test
    @DisplayName("BasicId encode→decode round-trip")
    void basicIdRoundtrip() {
        byte[] uasId = new byte[20];
        Arrays.fill(uasId, (byte) 0xAB);
        OpenDroneIdBasicId orig = new OpenDroneIdBasicId(1, 2, uasId);
        OpenDroneIdBasicId back = (OpenDroneIdBasicId) roundtrip(orig);
        assertEquals(orig.idType, back.idType, "idType 应一致");
        assertEquals(orig.uaType, back.uaType, "uaType 应一致");
        assertArrayEquals(orig.uasId, back.uasId, "uasId 应一致");
    }

    @Test
    @DisplayName("Location encode→decode round-trip")
    void locationRoundtrip() {
        OpenDroneIdLocation orig = new OpenDroneIdLocation(
                1, 18000, 400, -100, 399999999, 1160000000,
                120.5f, 150.0f, 1, 3, 2, 1, 2, 3600.0f);
        OpenDroneIdLocation back = (OpenDroneIdLocation) roundtrip(orig);
        assertEquals(orig.status, back.status, "status 应一致");
        assertEquals(orig.direction, back.direction, "direction 应一致");
        assertEquals(orig.speedHorizontal, back.speedHorizontal, "speedHorizontal 应一致");
        assertEquals(orig.speedVertical, back.speedVertical, "speedVertical 应一致");
        assertEquals(orig.latitude, back.latitude, "latitude 应一致");
        assertEquals(orig.longitude, back.longitude, "longitude 应一致");
        assertEquals(orig.altitudeBarometric, back.altitudeBarometric, 1e-6, "altitudeBarometric 应一致");
        assertEquals(orig.altitudeGeodetic, back.altitudeGeodetic, 1e-6, "altitudeGeodetic 应一致");
        assertEquals(orig.heightReference, back.heightReference, "heightReference 应一致");
        assertEquals(orig.horizontalAccuracy, back.horizontalAccuracy, "horizontalAccuracy 应一致");
        assertEquals(orig.verticalAccuracy, back.verticalAccuracy, "verticalAccuracy 应一致");
        assertEquals(orig.barometerAccuracy, back.barometerAccuracy, "barometerAccuracy 应一致");
        assertEquals(orig.speedAccuracy, back.speedAccuracy, "speedAccuracy 应一致");
        assertEquals(orig.timestamp, back.timestamp, 1e-6, "timestamp 应一致");
    }

    @Test
    @DisplayName("SelfId encode→decode round-trip")
    void selfIdRoundtrip() {
        byte[] description = new byte[23];
        Arrays.fill(description, (byte) 0x41); // 'A'
        OpenDroneIdSelfId orig = new OpenDroneIdSelfId(0, description);
        OpenDroneIdSelfId back = (OpenDroneIdSelfId) roundtrip(orig);
        assertEquals(orig.descriptionType, back.descriptionType, "descriptionType 应一致");
        assertArrayEquals(orig.description, back.description, "description 应一致");
    }

    @Test
    @DisplayName("System encode→decode round-trip")
    void systemRoundtrip() {
        OpenDroneIdSystem orig = new OpenDroneIdSystem(
                1, 0, 1, 399999999, 1160000000, 1, 500, 200.0f, 50.0f);
        OpenDroneIdSystem back = (OpenDroneIdSystem) roundtrip(orig);
        assertEquals(orig.flags, back.flags, "flags 应一致");
        assertEquals(orig.operatorLocationType, back.operatorLocationType, "operatorLocationType 应一致");
        assertEquals(orig.classificationType, back.classificationType, "classificationType 应一致");
        assertEquals(orig.operatorLatitude, back.operatorLatitude, "operatorLatitude 应一致");
        assertEquals(orig.operatorLongitude, back.operatorLongitude, "operatorLongitude 应一致");
        assertEquals(orig.areaCount, back.areaCount, "areaCount 应一致");
        assertEquals(orig.areaRadius, back.areaRadius, "areaRadius 应一致");
        assertEquals(orig.areaCeiling, back.areaCeiling, 1e-6, "areaCeiling 应一致");
        assertEquals(orig.areaFloor, back.areaFloor, 1e-6, "areaFloor 应一致");
    }

    @Test
    @DisplayName("OperatorId encode→decode round-trip")
    void operatorIdRoundtrip() {
        byte[] operatorId = new byte[20];
        Arrays.fill(operatorId, (byte) 0x42); // 'B'
        OpenDroneIdOperatorId orig = new OpenDroneIdOperatorId(0, operatorId);
        OpenDroneIdOperatorId back = (OpenDroneIdOperatorId) roundtrip(orig);
        assertEquals(orig.operatorIdType, back.operatorIdType, "operatorIdType 应一致");
        assertArrayEquals(orig.operatorId, back.operatorId, "operatorId 应一致");
    }

    // ========== 2. payload 长度验证 ==========

    @Test
    @DisplayName("BasicId payload 长度与 MavlinkMessageInfo.lengthOf 一致")
    void basicIdPayloadLength() {
        OpenDroneIdBasicId msg = new OpenDroneIdBasicId(0, 0, new byte[20]);
        assertEquals(OpenDroneIdBasicId.LEN, msg.encode().length, "encode 长度应为 LEN");
        assertEquals(OpenDroneIdBasicId.LEN, MavlinkMessageInfo.lengthOf(OpenDroneIdBasicId.ID),
                "MavlinkMessageInfo.lengthOf 应与 LEN 一致");
    }

    @Test
    @DisplayName("Location payload 长度与 MavlinkMessageInfo.lengthOf 一致")
    void locationPayloadLength() {
        OpenDroneIdLocation msg = new OpenDroneIdLocation(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(OpenDroneIdLocation.LEN, msg.encode().length, "encode 长度应为 LEN");
        assertEquals(OpenDroneIdLocation.LEN, MavlinkMessageInfo.lengthOf(OpenDroneIdLocation.ID),
                "MavlinkMessageInfo.lengthOf 应与 LEN 一致");
    }

    @Test
    @DisplayName("SelfId payload 长度与 MavlinkMessageInfo.lengthOf 一致")
    void selfIdPayloadLength() {
        OpenDroneIdSelfId msg = new OpenDroneIdSelfId(0, new byte[23]);
        assertEquals(OpenDroneIdSelfId.LEN, msg.encode().length, "encode 长度应为 LEN");
        assertEquals(OpenDroneIdSelfId.LEN, MavlinkMessageInfo.lengthOf(OpenDroneIdSelfId.ID),
                "MavlinkMessageInfo.lengthOf 应与 LEN 一致");
    }

    @Test
    @DisplayName("System payload 长度与 MavlinkMessageInfo.lengthOf 一致")
    void systemPayloadLength() {
        OpenDroneIdSystem msg = new OpenDroneIdSystem(0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(OpenDroneIdSystem.LEN, msg.encode().length, "encode 长度应为 LEN");
        assertEquals(OpenDroneIdSystem.LEN, MavlinkMessageInfo.lengthOf(OpenDroneIdSystem.ID),
                "MavlinkMessageInfo.lengthOf 应与 LEN 一致");
    }

    @Test
    @DisplayName("OperatorId payload 长度与 MavlinkMessageInfo.lengthOf 一致")
    void operatorIdPayloadLength() {
        OpenDroneIdOperatorId msg = new OpenDroneIdOperatorId(0, new byte[20]);
        assertEquals(OpenDroneIdOperatorId.LEN, msg.encode().length, "encode 长度应为 LEN");
        assertEquals(OpenDroneIdOperatorId.LEN, MavlinkMessageInfo.lengthOf(OpenDroneIdOperatorId.ID),
                "MavlinkMessageInfo.lengthOf 应与 LEN 一致");
    }

    @Test
    @DisplayName("MessagePack payload 长度与 MavlinkMessageInfo.lengthOf 一致")
    void messagePackPayloadLength() {
        OpenDroneIdMessagePack msg = new OpenDroneIdMessagePack(25, 0, new byte[250]);
        assertEquals(OpenDroneIdMessagePack.LEN, msg.encode().length, "encode 长度应为 LEN");
        assertEquals(OpenDroneIdMessagePack.LEN, MavlinkMessageInfo.lengthOf(OpenDroneIdMessagePack.ID),
                "MavlinkMessageInfo.lengthOf 应与 LEN 一致");
    }

    // ========== 3. 字符串字段 0 填充测试 ==========

    @Test
    @DisplayName("BasicId uasId 不足长度以 0 填充")
    void basicIdUasIdZeroPadding() {
        // 构造一个只有5字节的 uasId，encode 后应填充到 20 字节
        byte[] shortUasId = {1, 2, 3, 4, 5};
        byte[] paddedUasId = new byte[20];
        System.arraycopy(shortUasId, 0, paddedUasId, 0, 5);
        OpenDroneIdBasicId msg = new OpenDroneIdBasicId(0, 0, paddedUasId);
        byte[] payload = msg.encode();
        // position 2-6 应为 1,2,3,4,5，position 7-21 应为 0
        assertEquals(1, payload[2], "uasId[0] 应为 1");
        assertEquals(5, payload[6], "uasId[4] 应为 5");
        for (int i = 7; i < 22; i++) {
            assertEquals(0, payload[i], "uasId 填充区 position " + i + " 应为 0");
        }
    }

    @Test
    @DisplayName("SelfId description 不足长度以 0 填充")
    void selfIdDescriptionZeroPadding() {
        byte[] shortDesc = {0x41, 0x42, 0x43}; // "ABC"
        byte[] paddedDesc = new byte[23];
        System.arraycopy(shortDesc, 0, paddedDesc, 0, 3);
        OpenDroneIdSelfId msg = new OpenDroneIdSelfId(0, paddedDesc);
        byte[] payload = msg.encode();
        assertEquals(0x41, payload[1], "description[0] 应为 'A'");
        assertEquals(0x43, payload[3], "description[2] 应为 'C'");
        for (int i = 4; i < 24; i++) {
            assertEquals(0, payload[i], "description 填充区 position " + i + " 应为 0");
        }
    }

    @Test
    @DisplayName("OperatorId operatorId 不足长度以 0 填充")
    void operatorIdZeroPadding() {
        byte[] shortOpId = {0x42, 0x43}; // "BC"
        byte[] paddedOpId = new byte[20];
        System.arraycopy(shortOpId, 0, paddedOpId, 0, 2);
        OpenDroneIdOperatorId msg = new OpenDroneIdOperatorId(0, paddedOpId);
        byte[] payload = msg.encode();
        assertEquals(0x42, payload[1], "operatorId[0] 应为 'B'");
        assertEquals(0x43, payload[2], "operatorId[1] 应为 'C'");
        for (int i = 3; i < 21; i++) {
            assertEquals(0, payload[i], "operatorId 填充区 position " + i + " 应为 0");
        }
    }

    // ========== 4. MESSAGE_PACK pack→encode→decode→unpack round-trip ==========

    @Test
    @DisplayName("MessagePack 打包3条消息（BASIC_ID + LOCATION + SYSTEM）→ 解包后各子消息字段一致")
    void messagePack3MsgsRoundtrip() {
        // 注意：Location 在 MESSAGE_PACK 中只保留前 24 字节 payload，
        // position 24+ 的字段（horizontalAccuracy 等）会丢失，因此测试中设为 0
        byte[] uasId = new byte[20];
        Arrays.fill(uasId, (byte) 0xAB);
        OpenDroneIdBasicId basicId = new OpenDroneIdBasicId(1, 2, uasId);

        OpenDroneIdLocation location = new OpenDroneIdLocation(
                1, 18000, 400, -100, 399999999, 1160000000,
                120.5f, 150.0f, 1, 0, 0, 0, 0, 0.0f);

        OpenDroneIdSystem system = new OpenDroneIdSystem(
                1, 0, 1, 399999999, 1160000000, 1, 500, 200.0f, 50.0f);

        List<MavlinkMessage> msgs = List.of(basicId, location, system);
        OpenDroneIdMessagePack pack = OpenDroneIdMessagePack.pack(msgs);

        assertEquals(25, pack.getSingleMsgSize(), "singleMsgSize 应为 25");
        assertEquals(3, pack.getMsgPackSize(), "msgPackSize 应为 3");

        // encode → decode → unpack
        byte[] payload = pack.encode();
        assertEquals(252, payload.length, "payload 长度应为 252");

        // 构造帧解码
        MavlinkFrame frame = pack.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept MessagePack frame");
        OpenDroneIdMessagePack decoded = (OpenDroneIdMessagePack) MavlinkMessage.decode(r.frame);
        assertNotNull(decoded, "decode should return OpenDroneIdMessagePack");

        List<MavlinkMessage> unpacked = decoded.unpack();
        assertEquals(3, unpacked.size(), "解包后应有 3 条子消息");

        // 验证 BasicId
        assertTrue(unpacked.get(0) instanceof OpenDroneIdBasicId, "第1条应为 BasicId");
        OpenDroneIdBasicId backBasicId = (OpenDroneIdBasicId) unpacked.get(0);
        assertEquals(basicId.idType, backBasicId.idType, "BasicId idType 应一致");
        assertEquals(basicId.uaType, backBasicId.uaType, "BasicId uaType 应一致");
        assertArrayEquals(basicId.uasId, backBasicId.uasId, "BasicId uasId 应一致");

        // 验证 Location（前24字节字段一致，超出部分为0）
        assertTrue(unpacked.get(1) instanceof OpenDroneIdLocation, "第2条应为 Location");
        OpenDroneIdLocation backLocation = (OpenDroneIdLocation) unpacked.get(1);
        assertEquals(location.status, backLocation.status, "Location status 应一致");
        assertEquals(location.direction, backLocation.direction, "Location direction 应一致");
        assertEquals(location.speedHorizontal, backLocation.speedHorizontal, "Location speedHorizontal 应一致");
        assertEquals(location.speedVertical, backLocation.speedVertical, "Location speedVertical 应一致");
        assertEquals(location.latitude, backLocation.latitude, "Location latitude 应一致");
        assertEquals(location.longitude, backLocation.longitude, "Location longitude 应一致");
        assertEquals(location.altitudeBarometric, backLocation.altitudeBarometric, 1e-6,
                "Location altitudeBarometric 应一致");
        assertEquals(location.altitudeGeodetic, backLocation.altitudeGeodetic, 1e-6,
                "Location altitudeGeodetic 应一致");
        assertEquals(location.heightReference, backLocation.heightReference, "Location heightReference 应一致");

        // 验证 System
        assertTrue(unpacked.get(2) instanceof OpenDroneIdSystem, "第3条应为 System");
        OpenDroneIdSystem backSystem = (OpenDroneIdSystem) unpacked.get(2);
        assertEquals(system.flags, backSystem.flags, "System flags 应一致");
        assertEquals(system.operatorLocationType, backSystem.operatorLocationType,
                "System operatorLocationType 应一致");
        assertEquals(system.classificationType, backSystem.classificationType,
                "System classificationType 应一致");
        assertEquals(system.operatorLatitude, backSystem.operatorLatitude, "System operatorLatitude 应一致");
        assertEquals(system.operatorLongitude, backSystem.operatorLongitude, "System operatorLongitude 应一致");
        assertEquals(system.areaCount, backSystem.areaCount, "System areaCount 应一致");
        assertEquals(system.areaRadius, backSystem.areaRadius, "System areaRadius 应一致");
        assertEquals(system.areaCeiling, backSystem.areaCeiling, 1e-6, "System areaCeiling 应一致");
        assertEquals(system.areaFloor, backSystem.areaFloor, 1e-6, "System areaFloor 应一致");
    }

    @Test
    @DisplayName("MessagePack 打包5条消息（含 SELF_ID + OPERATOR_ID）→ 解包后正确")
    void messagePack5MsgsRoundtrip() {
        byte[] uasId = new byte[20];
        Arrays.fill(uasId, (byte) 0xAB);
        OpenDroneIdBasicId basicId = new OpenDroneIdBasicId(1, 2, uasId);

        OpenDroneIdLocation location = new OpenDroneIdLocation(
                1, 18000, 400, -100, 399999999, 1160000000,
                120.5f, 150.0f, 1, 0, 0, 0, 0, 0.0f);

        byte[] description = new byte[23];
        Arrays.fill(description, (byte) 0x41);
        OpenDroneIdSelfId selfId = new OpenDroneIdSelfId(0, description);

        OpenDroneIdSystem system = new OpenDroneIdSystem(
                1, 0, 1, 399999999, 1160000000, 1, 500, 200.0f, 50.0f);

        byte[] operatorId = new byte[20];
        Arrays.fill(operatorId, (byte) 0x42);
        OpenDroneIdOperatorId opId = new OpenDroneIdOperatorId(0, operatorId);

        List<MavlinkMessage> msgs = List.of(basicId, location, selfId, system, opId);
        OpenDroneIdMessagePack pack = OpenDroneIdMessagePack.pack(msgs);

        assertEquals(5, pack.getMsgPackSize(), "msgPackSize 应为 5");

        // encode → decode → unpack
        MavlinkFrame frame = pack.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept MessagePack frame");
        OpenDroneIdMessagePack decoded = (OpenDroneIdMessagePack) MavlinkMessage.decode(r.frame);

        List<MavlinkMessage> unpacked = decoded.unpack();
        assertEquals(5, unpacked.size(), "解包后应有 5 条子消息");

        // 验证 SelfId
        assertTrue(unpacked.get(2) instanceof OpenDroneIdSelfId, "第3条应为 SelfId");
        OpenDroneIdSelfId backSelfId = (OpenDroneIdSelfId) unpacked.get(2);
        assertEquals(selfId.descriptionType, backSelfId.descriptionType, "SelfId descriptionType 应一致");
        assertArrayEquals(selfId.description, backSelfId.description, "SelfId description 应一致");

        // 验证 OperatorId
        assertTrue(unpacked.get(4) instanceof OpenDroneIdOperatorId, "第5条应为 OperatorId");
        OpenDroneIdOperatorId backOpId = (OpenDroneIdOperatorId) unpacked.get(4);
        assertEquals(opId.operatorIdType, backOpId.operatorIdType, "OperatorId operatorIdType 应一致");
        assertArrayEquals(opId.operatorId, backOpId.operatorId, "OperatorId operatorId 应一致");
    }

    @Test
    @DisplayName("MessagePack 打包超过10条 → 抛出 IllegalArgumentException")
    void messagePackOverLimitThrows() {
        byte[] uasId = new byte[20];
        OpenDroneIdBasicId basicId = new OpenDroneIdBasicId(0, 0, uasId);
        // 构造 11 条消息
        MavlinkMessage[] msgs = new MavlinkMessage[11];
        Arrays.fill(msgs, basicId);
        List<MavlinkMessage> msgList = Arrays.asList(msgs);

        assertThrows(IllegalArgumentException.class,
                () -> OpenDroneIdMessagePack.pack(msgList),
                "打包超过10条消息应抛出 IllegalArgumentException");
    }

    // ========== 5. MavlinkMessageInfo 扩展测试 ==========

    @Test
    @DisplayName("MavlinkMessageInfo.isKnown 扩展区测试")
    void messageInfoIsKnownExtended() {
        assertTrue(MavlinkMessageInfo.isKnown(12900), "isKnown(12900) 应为 true (BasicId)");
        assertTrue(MavlinkMessageInfo.isKnown(12901), "isKnown(12901) 应为 true (Location)");
        assertTrue(MavlinkMessageInfo.isKnown(12903), "isKnown(12903) 应为 true (SelfId)");
        assertTrue(MavlinkMessageInfo.isKnown(12904), "isKnown(12904) 应为 true (System)");
        assertTrue(MavlinkMessageInfo.isKnown(12905), "isKnown(12905) 应为 true (OperatorId)");
        assertTrue(MavlinkMessageInfo.isKnown(12915), "isKnown(12915) 应为 true (MessagePack)");
        assertFalse(MavlinkMessageInfo.isKnown(12999), "isKnown(12999) 应为 false (未知)");
        assertFalse(MavlinkMessageInfo.isKnown(12902), "isKnown(12902) 应为 false (未注册)");
    }

    @Test
    @DisplayName("MavlinkMessageInfo.lengthOf 扩展区测试")
    void messageInfoLengthOfExtended() {
        assertEquals(22, MavlinkMessageInfo.lengthOf(12900), "lengthOf(12900) 应为 22");
        assertEquals(36, MavlinkMessageInfo.lengthOf(12901), "lengthOf(12901) 应为 36");
        assertEquals(24, MavlinkMessageInfo.lengthOf(12903), "lengthOf(12903) 应为 24");
        assertEquals(23, MavlinkMessageInfo.lengthOf(12904), "lengthOf(12904) 应为 23");
        assertEquals(21, MavlinkMessageInfo.lengthOf(12905), "lengthOf(12905) 应为 21");
        assertEquals(252, MavlinkMessageInfo.lengthOf(12915), "lengthOf(12915) 应为 252");
        assertEquals(-1, MavlinkMessageInfo.lengthOf(12999), "lengthOf(12999) 应为 -1");
    }

    // ========== 6. MavlinkMessage.decode() 分发测试 ==========

    @Test
    @DisplayName("MavlinkMessage.decode msgId=12900 → OpenDroneIdBasicId")
    void decodeDispatches12900() {
        byte[] uasId = new byte[20];
        OpenDroneIdBasicId msg = new OpenDroneIdBasicId(1, 2, uasId);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof OpenDroneIdBasicId, "msgId=12900 → OpenDroneIdBasicId");
    }

    @Test
    @DisplayName("MavlinkMessage.decode msgId=12915 → OpenDroneIdMessagePack")
    void decodeDispatches12915() {
        OpenDroneIdMessagePack msg = OpenDroneIdMessagePack.pack(List.of(
                new OpenDroneIdBasicId(0, 0, new byte[20])));
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof OpenDroneIdMessagePack, "msgId=12915 → OpenDroneIdMessagePack");
    }

    @Test
    @DisplayName("MavlinkMessage.decode msgId=12901 → OpenDroneIdLocation")
    void decodeDispatches12901() {
        OpenDroneIdLocation msg = new OpenDroneIdLocation(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof OpenDroneIdLocation, "msgId=12901 → OpenDroneIdLocation");
    }

    @Test
    @DisplayName("MavlinkMessage.decode msgId=12903 → OpenDroneIdSelfId")
    void decodeDispatches12903() {
        OpenDroneIdSelfId msg = new OpenDroneIdSelfId(0, new byte[23]);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof OpenDroneIdSelfId, "msgId=12903 → OpenDroneIdSelfId");
    }

    @Test
    @DisplayName("MavlinkMessage.decode msgId=12904 → OpenDroneIdSystem")
    void decodeDispatches12904() {
        OpenDroneIdSystem msg = new OpenDroneIdSystem(0, 0, 0, 0, 0, 0, 0, 0, 0);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof OpenDroneIdSystem, "msgId=12904 → OpenDroneIdSystem");
    }

    @Test
    @DisplayName("MavlinkMessage.decode msgId=12905 → OpenDroneIdOperatorId")
    void decodeDispatches12905() {
        OpenDroneIdOperatorId msg = new OpenDroneIdOperatorId(0, new byte[20]);
        MavlinkMessage decoded = roundtrip(msg);
        assertTrue(decoded instanceof OpenDroneIdOperatorId, "msgId=12905 → OpenDroneIdOperatorId");
    }
}