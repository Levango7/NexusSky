package io.aerofleet.sim.comm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LoRaMavlinkTransport 集成测试：MAVLink 帧通过 LoRa 传输的完整流程。
 * <p>
 * 覆盖帧级 send/receive 闭环、大帧分片/重组正确性、多帧交替收发、
 * 分片队列管理（getPendingFragments/pollFragment）、frameId 递增、边界与异常。
 */
@DisplayName("LoRaMavlinkTransport MAVLink-over-LoRa 适配器")
class LoRaMavlinkTransportTest {

    /** 构造指定大小的测试帧，内容按 i*7+3 填充以便校验数据完整性。 */
    private static byte[] makeFrame(int size) {
        byte[] frame = new byte[size];
        for (int i = 0; i < size; i++) {
            frame[i] = (byte) (i * 7 + 3);
        }
        return frame;
    }

    /** 模拟物理层传输：从 sender 取出所有待发送分片，逐片喂入 receiver。 */
    private static void physicalDelivery(LoRaMavlinkTransport sender, LoRaMavlinkTransport receiver) {
        List<byte[]> frags = sender.getPendingFragments();
        for (byte[] frag : frags) {
            receiver.receive(frag);
        }
        sender.clearPendingFragments();
    }

    // ===== 完整流程 =====

    @Nested
    @DisplayName("帧级 send/receive 完整流程")
    class EndToEnd {

        @Test
        @DisplayName("小帧（<47B）单分片完整收发")
        void smallFrame_singleFragment_roundTrip() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();
            byte[] frame = makeFrame(20);

            sender.send(frame);
            assertThat(sender.pendingFragmentCount()).isEqualTo(1);

            physicalDelivery(sender, receiver);

            byte[] received = receiver.receive();
            assertThat(received).isEqualTo(frame);
            assertThat(receiver.completedFrameCount()).isZero();
        }

        @Test
        @DisplayName("中等帧（=47B 边界）单分片满载收发")
        void mediumFrame_exactBoundary_roundTrip() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();
            byte[] frame = makeFrame(47);

            sender.send(frame);
            assertThat(sender.pendingFragmentCount()).isEqualTo(1);

            physicalDelivery(sender, receiver);
            assertThat(receiver.receive()).isEqualTo(frame);
        }

        @Test
        @DisplayName("大帧（200B）多分片完整收发，数据一致")
        void largeFrame_multipleFragments_roundTrip() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();
            byte[] frame = makeFrame(200);

            sender.send(frame);
            // 200 字节 → ceil(200/47) = 5 分片
            assertThat(sender.pendingFragmentCount()).isEqualTo(5);

            physicalDelivery(sender, receiver);
            byte[] received = receiver.receive();
            assertThat(received).isEqualTo(frame);
            assertThat(received).hasSize(200);
        }

        @Test
        @DisplayName("超大帧（1000B）分片/重组正确性")
        void veryLargeFrame_fragmentReassembleCorrectness() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();
            byte[] frame = makeFrame(1000);

            sender.send(frame);
            // ceil(1000/47) = 22 分片
            assertThat(sender.pendingFragmentCount()).isEqualTo(22);

            physicalDelivery(sender, receiver);
            assertThat(receiver.receive()).isEqualTo(frame);
        }
    }

    // ===== 多帧交替 =====

    @Nested
    @DisplayName("多帧交替发送/接收")
    class Interleaved {

        @Test
        @DisplayName("两帧分片交替到达，各自独立重组成功")
        void twoFrames_interleavedFragments_bothReassembled() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();
            byte[] frameA = makeFrame(100);  // 3 分片
            byte[] frameB = makeFrame(60);   // 2 分片

            int idA = sender.send(frameA);
            int idB = sender.send(frameB);
            assertThat(idA).isNotEqualTo(idB);

            List<byte[]> allFrags = sender.getPendingFragments();
            // frameA: frags[0..2], frameB: frags[3..4]
            assertThat(allFrags).hasSize(5);

            // 交替喂入：A0, B0, A1, A2(A 收齐), B1(B 收齐)
            receiver.receive(allFrags.get(0)); // A0
            receiver.receive(allFrags.get(3)); // B0
            receiver.receive(allFrags.get(1)); // A1
            assertThat(receiver.completedFrameCount()).isZero();
            receiver.receive(allFrags.get(2)); // A2 → A 收齐
            assertThat(receiver.completedFrameCount()).isEqualTo(1);
            receiver.receive(allFrags.get(4)); // B1 → B 收齐
            assertThat(receiver.completedFrameCount()).isEqualTo(2);

            // FIFO 弹出：A 先收齐，故先弹出 A
            byte[] first = receiver.receive();
            byte[] second = receiver.receive();
            assertThat(first).isEqualTo(frameA);
            assertThat(second).isEqualTo(frameB);
            assertThat(receiver.receive()).isNull();
        }

        @Test
        @DisplayName("三帧连续发送，按收齐顺序弹出")
        void threeFrames_sequentialSend_orderedReceive() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();
            byte[] frame1 = makeFrame(30);   // 1 分片
            byte[] frame2 = makeFrame(50);   // 2 分片
            byte[] frame3 = makeFrame(15);   // 1 分片

            sender.send(frame1);
            sender.send(frame2);
            sender.send(frame3);

            physicalDelivery(sender, receiver);

            // 全部收齐，按收齐顺序（frame1 先收齐，frame2 其次，frame3 最后）
            assertThat(receiver.receive()).isEqualTo(frame1);
            assertThat(receiver.receive()).isEqualTo(frame2);
            assertThat(receiver.receive()).isEqualTo(frame3);
        }

        @Test
        @DisplayName("同一帧分片乱序到达仍能正确重组")
        void singleFrame_outOfOrderFragments_reassembled() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();
            byte[] frame = makeFrame(150);  // 4 分片

            sender.send(frame);
            List<byte[]> frags = sender.getPendingFragments();
            assertThat(frags).hasSize(4);

            // 乱序：index 2, 0, 3, 1
            assertThat(receiver.receive(frags.get(2))).isNull();
            assertThat(receiver.receive(frags.get(0))).isNull();
            assertThat(receiver.receive(frags.get(3))).isNull();
            assertThat(receiver.receive(frags.get(1))).isEqualTo(frame);
        }
    }

    // ===== 分片队列管理 =====

    @Nested
    @DisplayName("分片队列管理")
    class FragmentQueue {

        @Test
        @DisplayName("getPendingFragments 返回快照，不弹出")
        void getPendingFragments_returnsSnapshot() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            t.send(makeFrame(100));

            List<byte[]> first = t.getPendingFragments();
            List<byte[]> second = t.getPendingFragments();

            assertThat(first).hasSameSizeAs(second);
            assertThat(t.pendingFragmentCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("pollFragment FIFO 逐片弹出")
        void pollFragment_fifoOrder() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            t.send(makeFrame(100));

            List<byte[]> snapshot = t.getPendingFragments();
            for (byte[] expected : snapshot) {
                byte[] polled = t.pollFragment();
                assertThat(polled).isEqualTo(expected);
            }
            assertThat(t.pollFragment()).isNull();
            assertThat(t.pendingFragmentCount()).isZero();
        }

        @Test
        @DisplayName("clearPendingFragments 清空待发送队列")
        void clearPendingFragments_emptiesQueue() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            t.send(makeFrame(100));
            assertThat(t.pendingFragmentCount()).isEqualTo(3);

            t.clearPendingFragments();
            assertThat(t.pendingFragmentCount()).isZero();
            assertThat(t.getPendingFragments()).isEmpty();
        }

        @Test
        @DisplayName("多次 send 累积分片到待发送队列")
        void multipleSends_accumulateFragments() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            t.send(makeFrame(20));   // 1 分片
            t.send(makeFrame(100));  // 3 分片
            t.send(makeFrame(50));   // 2 分片

            assertThat(t.pendingFragmentCount()).isEqualTo(6);
        }
    }

    // ===== frameId 管理 =====

    @Nested
    @DisplayName("frameId 递增管理")
    class FrameIdSequence {

        @Test
        @DisplayName("连续 send 返回递增 frameId")
        void send_returnsIncrementingFrameId() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            int id0 = t.send(makeFrame(10));
            int id1 = t.send(makeFrame(10));
            int id2 = t.send(makeFrame(10));

            assertThat(id1).isEqualTo(id0 + 1);
            assertThat(id2).isEqualTo(id0 + 2);
        }

        @Test
        @DisplayName("frameId 在 0-255 循环")
        void frameId_wrapsAround256() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            int lastId = 0;
            for (int i = 0; i < 256; i++) {
                lastId = t.send(makeFrame(5));
            }
            // 发送 256 帧后，frameId 应回到 0
            int nextId = t.send(makeFrame(5));
            assertThat(nextId).isEqualTo(0);
            assertThat(lastId).isEqualTo(255);
        }
    }

    // ===== 边界与异常 =====

    @Nested
    @DisplayName("边界与异常处理")
    class EdgeCases {

        @Test
        @DisplayName("空帧 send 产生单个仅含头的分片")
        void emptyFrame_singleHeaderFragment() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();

            sender.send(new byte[0]);
            assertThat(sender.pendingFragmentCount()).isEqualTo(1);
            assertThat(sender.getPendingFragments().get(0)).hasSize(3);

            physicalDelivery(sender, receiver);
            assertThat(receiver.receive()).isEqualTo(new byte[0]);
        }

        @Test
        @DisplayName("receive() 空完成队列返回 null")
        void receive_emptyQueue_returnsNull() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            assertThat(t.receive()).isNull();
        }

        @Test
        @DisplayName("receive(null) 返回 null 不抛异常")
        void receive_nullFragment_returnsNull() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            assertThat(t.receive((byte[]) null)).isNull();
        }

        @Test
        @DisplayName("receive 过短分片（<3B）返回 null")
        void receive_shortFragment_returnsNull() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            assertThat(t.receive(new byte[2])).isNull();
        }

        @Test
        @DisplayName("send(null) 抛 IllegalArgumentException")
        void send_nullFrame_throws() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            assertThatThrownBy(() -> t.send(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("构造时 loRa=null 抛 IllegalArgumentException")
        void constructor_nullLoRa_throws() {
            assertThatThrownBy(() -> new LoRaMavlinkTransport(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("部分分片到达时 receive() 不返回未完成帧")
        void partialFragments_notCompleted() {
            LoRaMavlinkTransport sender = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport receiver = LoRaMavlinkTransport.defaultConfig();
            sender.send(makeFrame(100));  // 3 分片

            List<byte[]> frags = sender.getPendingFragments();
            receiver.receive(frags.get(0));  // 仅 1/3
            receiver.receive(frags.get(1));  // 2/3

            assertThat(receiver.completedFrameCount()).isZero();
            assertThat(receiver.receive()).isNull();
        }
    }

    // ===== 配置与延迟 =====

    @Nested
    @DisplayName("配置与延迟估算")
    class ConfigAndLatency {

        @Test
        @DisplayName("defaultConfig 包装默认 LoRa 参数")
        void defaultConfig_wrapsDefaultLoRa() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            assertThat(t.loRa().spreadingFactor()).isEqualTo(7);
            assertThat(t.loRa().frequencyMHz()).isEqualTo(433.0);
            assertThat(t.loRa().maxPayloadBytes()).isEqualTo(50);
        }

        @Test
        @DisplayName("longRange 包装 SF12 远距离配置")
        void longRange_wrapsSF12() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.longRange();
            assertThat(t.loRa().spreadingFactor()).isEqualTo(12);
            assertThat(t.loRa().codingRate()).isEqualTo(8);
        }

        @Test
        @DisplayName("estimatedLatencyMs 委托底层 LoRaTransport")
        void estimatedLatencyMs_delegatesToLoRa() {
            LoRaMavlinkTransport t = LoRaMavlinkTransport.defaultConfig();
            long lat = t.estimatedLatencyMs(50);
            // SF7 默认 50ms 基础延迟，50B 占 1 个时隙
            assertThat(lat).isEqualTo(50L);
        }

        @Test
        @DisplayName("远距离配置延迟高于默认配置")
        void longRangeLatency_higherThanDefault() {
            LoRaMavlinkTransport def = LoRaMavlinkTransport.defaultConfig();
            LoRaMavlinkTransport lr = LoRaMavlinkTransport.longRange();
            int payload = 100;
            assertThat(def.estimatedLatencyMs(payload))
                    .isLessThan(lr.estimatedLatencyMs(payload));
        }
    }
}