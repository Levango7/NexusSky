package io.aerofleet.cloud.ntrip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RTCM3 帧切分器的边界测试。
 * <p>
 * 这一层的错法只有一种但后果很重：**切错边界**。切早了飞控收到半帧整条丢弃（定位精度不升），
 * 切晚了把下一条帧的头吞掉（连续丢帧），CRC 不校验则把损坏的改正数当有效值发出去
 * （位置反而更偏）。所以下面每条都对着一种真实错法。
 */
class Rtcm3FramerTest {

    @Test
    @DisplayName("单帧完整：一次 feed 切出 1 条，内容逐字节相同")
    void singleCompleteFrame() {
        byte[] frame = Rtcm3Framer.buildFrame(new byte[]{10, 20, 30});
        Rtcm3Framer f = new Rtcm3Framer();
        List<byte[]> out = f.feed(frame);
        assertEquals(1, out.size());
        assertArrayEquals(frame, out.get(0));
        assertEquals(1, f.framesEmitted());
        assertEquals(0, f.crcFailures());
    }

    @Test
    @DisplayName("跨块：一帧分三次喂（头/中/尾），只在第三次吐出")
    void frameSplitAcrossChunks() {
        byte[] frame = Rtcm3Framer.buildFrame(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        Rtcm3Framer f = new Rtcm3Framer();

        assertTrue(f.feed(Arrays.copyOfRange(frame, 0, 2)).isEmpty(), "只有 2 字节头部，不该出帧");
        assertTrue(f.feed(Arrays.copyOfRange(frame, 2, 6)).isEmpty(), "仍不完整");
        List<byte[]> out = f.feed(Arrays.copyOfRange(frame, 6, frame.length));
        assertEquals(1, out.size());
        assertArrayEquals(frame, out.get(0));
    }

    @Test
    @DisplayName("多帧粘连：一次喂 3 帧，按顺序吐出 3 条")
    void multipleFramesInOneChunk() {
        byte[] a = Rtcm3Framer.buildFrame(new byte[]{1});
        byte[] b = Rtcm3Framer.buildFrame(new byte[]{2, 2});
        byte[] c = Rtcm3Framer.buildFrame(new byte[]{3, 3, 3});
        byte[] all = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        System.arraycopy(c, 0, all, a.length + b.length, c.length);

        List<byte[]> out = new Rtcm3Framer().feed(all);
        assertEquals(3, out.size());
        assertArrayEquals(a, out.get(0));
        assertArrayEquals(b, out.get(1));
        assertArrayEquals(c, out.get(2));
    }

    @Test
    @DisplayName("前导垃圾字节：跳过它们后仍能正确对齐（并计入 resyncBytes）")
    void skipsLeadingGarbage() {
        byte[] frame = Rtcm3Framer.buildFrame(new byte[]{9, 9});
        byte[] withGarbage = new byte[frame.length + 5];
        // 故意用 0x00 0xFF 等，且不包含 0xD3
        byte[] junk = {0x00, (byte) 0xFF, 0x12, 0x34, 0x56};
        System.arraycopy(junk, 0, withGarbage, 0, junk.length);
        System.arraycopy(frame, 0, withGarbage, junk.length, frame.length);

        Rtcm3Framer f = new Rtcm3Framer();
        List<byte[]> out = f.feed(withGarbage);
        assertEquals(1, out.size());
        assertArrayEquals(frame, out.get(0));
        assertEquals(5, f.resyncBytes(), "5 个垃圾字节都应计入 resync");
    }

    @Test
    @DisplayName("CRC 错：整帧丢弃，不转发（转发错的改正数比不转发更糟）")
    void dropsFrameWithBadCrc() {
        byte[] frame = Rtcm3Framer.buildFrame(new byte[]{7, 7, 7, 7});
        frame[frame.length - 1] ^= 0xFF; // 破坏 CRC 末字节

        Rtcm3Framer f = new Rtcm3Framer();
        List<byte[]> out = f.feed(frame);
        assertEquals(0, out.size(), "CRC 错的帧不能出现在结果里");
        assertEquals(0, f.framesEmitted());
        assertTrue(f.crcFailures() >= 1, "应记录一次 CRC 失败");
    }

    @Test
    @DisplayName("载荷里的 0xD3 不会被误当前导码（长度字段自校验）")
    void preambleInsidePayloadDoesNotSplit() {
        // 载荷里塞一个 0xD3，且后面跟一个非法长度，framer 不应在此处断开
        byte[] payload = {(byte) 0xD3, (byte) 0xFF, (byte) 0xFF, 1, 2, 3};
        byte[] frame = Rtcm3Framer.buildFrame(payload);

        List<byte[]> out = new Rtcm3Framer().feed(frame);
        assertEquals(1, out.size());
        assertArrayEquals(frame, out.get(0));
    }

    @Test
    @DisplayName("空 chunk 不影响状态；null 也不抛")
    void emptyChunkIsNoop() {
        Rtcm3Framer f = new Rtcm3Framer();
        assertTrue(f.feed(new byte[0]).isEmpty());
        assertTrue(f.feed(null).isEmpty());
        assertEquals(0, f.framesEmitted());
    }

    @Test
    @DisplayName("buildFrame 产出可被 pymavlink 同级算法复算的合法帧：长度字段与 CRC 自洽")
    void buildFrameIsSelfConsistent() {
        byte[] payload = new byte[300];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        byte[] frame = Rtcm3Framer.buildFrame(payload);

        assertEquals(Rtcm3Framer.PREAMBLE, frame[0] & 0xFF);
        int len = ((frame[1] & 0x03) << 8) | (frame[2] & 0xFF);
        assertEquals(300, len, "长度字段必须等于载荷长度");
        assertEquals(300 + Rtcm3Framer.OVERHEAD, frame.length);
        int crc = Rtcm3Framer.crc24q(frame, 0, 3 + len);
        int embedded = ((frame[3 + len] & 0xFF) << 16)
                | ((frame[4 + len] & 0xFF) << 8) | (frame[5 + len] & 0xFF);
        assertEquals(crc, embedded, "嵌入的 CRC 必须与重算值一致");
    }

    @Test
    @DisplayName("超过 1023 字节的载荷被拒（10 位长度字段装不下）")
    void rejectsOversizedPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> Rtcm3Framer.buildFrame(new byte[1024]));
    }

    @Test
    @DisplayName("reset 后旧流的半帧不污染新流")
    void resetClearsPartial() {
        byte[] frame = Rtcm3Framer.buildFrame(new byte[]{1, 2, 3, 4});
        Rtcm3Framer f = new Rtcm3Framer();
        f.feed(Arrays.copyOfRange(frame, 0, 4)); // 半帧
        f.reset();
        List<byte[]> out = f.feed(frame);
        assertEquals(1, out.size(), "reset 后应把新帧当作全新的流");
        assertArrayEquals(frame, out.get(0));
    }
}
