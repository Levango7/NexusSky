package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.MavlinkParser;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THERMAL_HOTSPOT (30064) 逐热点像素消息单测（P2 #19 条件③）。
 *
 * <p>这条消息存在的唯一理由：让云端能回答"热源在地面哪个位置"。30012 只有聚合量，
 * 像素坐标一个字节都没有；30064 把 (x, y, 峰值温度×10) 逐条下发。本测试守住：
 * <ul>
 *   <li>encode→decode 往返一致（含负坐标与 0.1℃ 精度）</li>
 *   <li>decode 分发：msgId=30064 解出 ThermalHotspotMsg 而非基类</li>
 *   <li>常量表登记：isKnown + LEN=26 + CRC_EXTRA 与字段签名一致</li>
 *   <li>容量语义：超 MAX_HOTSPOTS 拒绝、0 热点合法、LEN 与 MAX_HOTSPOTS 的关系不变</li>
 * </ul>
 */
class ThermalHotspotMsgTest {

    /** 构造 v2 帧 → 解析 → decode 往返（CRC/分发一并验证）。 */
    private static MavlinkMessage roundtrip(MavlinkMessage msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r, "parser must accept the frame (LEN/CRC registered?)");
        return MavlinkMessage.decode(r.frame);
    }

    @Test
    void threeHotspotsRoundtrip() {
        ThermalHotspotMsg orig = new ThermalHotspotMsg(2, 888_888L, true, new int[][]{
                {16, 12, 612}, {-4, -9, 1_000}, {31, 23, 505}});
        ThermalHotspotMsg back = (ThermalHotspotMsg) roundtrip(orig);

        assertEquals(2, back.sysid);
        assertEquals(888_888L, back.timestamp);
        assertTrue(back.truncated, "truncated 标志必须往返");
        assertEquals(3, back.count);
        assertArrayEquals(new int[]{16, 12, 612}, back.hotspots[0]);
        assertArrayEquals(new int[]{-4, -9, 1_000}, back.hotspots[1],
                "负像素坐标（边缘热点）必须按 i16 原样往返");
        assertArrayEquals(new int[]{31, 23, 505}, back.hotspots[2]);
    }

    @Test
    void zeroHotspotsRoundtrip() {
        ThermalHotspotMsg orig = new ThermalHotspotMsg(1, 0L, false, new int[0][]);
        ThermalHotspotMsg back = (ThermalHotspotMsg) roundtrip(orig);
        assertEquals(0, back.count);
        assertFalse(back.truncated);
        assertEquals(0, back.hotspots.length);
    }

    @Test
    void decodeDispatchesToThermalHotspotMsg() {
        ThermalHotspotMsg orig = new ThermalHotspotMsg(7, 42L, false, new int[][]{{3, 4, 523}});
        MavlinkFrame frame = orig.toFrame(1, 1, 0);
        MavlinkParser.ParseResult r = new MavlinkParser().parse(ByteBuffer.wrap(frame.encodeV2()));
        assertNotNull(r);
        MavlinkMessage decoded = MavlinkMessage.decode(r.frame);
        assertTrue(decoded instanceof ThermalHotspotMsg,
                "msgId=30064 should dispatch to ThermalHotspotMsg");
    }

    @Test
    void constantTableAgreesWithChecksum() {
        assertTrue(MavlinkMessageInfo.isKnown(ThermalHotspotMsg.ID),
                "30064 must be registered in the constant table or frames get rejected");
        assertEquals(26, MavlinkMessageInfo.lengthOf(ThermalHotspotMsg.ID));
        assertEquals(111, MavlinkMessageInfo.crcExtraOf(ThermalHotspotMsg.ID),
                "CRC_EXTRA must be the value recomputed by scripts/mavlink-crc-extra-gen.py");
        assertEquals(26, ThermalHotspotMsg.LEN);
        assertEquals(8 + ThermalHotspotMsg.MAX_HOTSPOTS * 6, ThermalHotspotMsg.LEN,
                "LEN = 8 字节头 + MAX_HOTSPOTS×6（每条 3 个 i16）；改了容量就要重算 CRC");
        assertEquals(3, ThermalHotspotMsg.MAX_HOTSPOTS);
    }

    @Test
    void overCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ThermalHotspotMsg(1, 0L, true,
                new int[][]{{1, 2, 300}, {3, 4, 300}, {5, 6, 300}, {7, 8, 300}}),
                "超容量必须显式拒绝，调用方据此截断并置 truncated");
        assertThrows(IllegalArgumentException.class, () -> new ThermalHotspotMsg(1, 0L, false,
                new int[][]{{1, 2}}),
                "每条热点必须是 [x, y, 峰值温度×10] 三元组");
    }

    @Test
    void inputArrayIsCopied() {
        int[][] src = {{1, 2, 300}};
        ThermalHotspotMsg msg = new ThermalHotspotMsg(1, 0L, false, src);
        src[0][0] = 99;
        assertEquals(1, msg.hotspots[0][0],
                "消息不可变：构造后修改入参数组不得影响消息内容");
    }
}
