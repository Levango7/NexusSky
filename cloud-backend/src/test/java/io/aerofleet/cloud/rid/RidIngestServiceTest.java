package io.aerofleet.cloud.rid;

import io.aerofleet.cloud.rid.model.BasicIdData;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.mavlink.MavlinkParser;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.OpenDroneIdBasicId;
import io.aerofleet.mavlink.messages.OpenDroneIdLocation;
import io.aerofleet.mavlink.messages.OpenDroneIdMessagePack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RidIngestService} 单元测试（直接实例化，无 Spring 上下文）。
 * <p>
 * Mock {@link RidWebSocketHandler}，使用真实 {@link RidStateManager} 和 {@link RidConfig}，
 * 验证消息过滤、解码分发、MessagePack 解包与快照查询功能。
 */
@DisplayName("RidIngestService RID 接入服务")
class RidIngestServiceTest {

    private RidStateManager stateManager;
    private RidWebSocketHandler wsHandler;
    private RidConfig config;
    private RidIngestService service;

    @BeforeEach
    void setUp() {
        config = new RidConfig();
        stateManager = new RidStateManager(config);
        wsHandler = Mockito.mock(RidWebSocketHandler.class);
        service = new RidIngestService(stateManager, wsHandler, config);
    }

    // ─── 消息过滤 ───

    @Nested
    @DisplayName("消息过滤")
    class MessageFiltering {

        @Test
        @DisplayName("msgId < 12900 时 onFrame 不处理")
        void onFrame_msgIdBelowRange_doesNotProcess() {
            MavlinkFrame frame = createFrame(1, 1, 12899, new byte[0]);

            service.onFrame(frame);

            assertThat(stateManager.get(1)).isNull();
            verify(wsHandler, never()).pushRidUpdate(Mockito.anyInt(), Mockito.any());
        }

        @Test
        @DisplayName("msgId > 12999 时 onFrame 不处理")
        void onFrame_msgIdAboveRange_doesNotProcess() {
            MavlinkFrame frame = createFrame(1, 1, 13000, new byte[0]);

            service.onFrame(frame);

            assertThat(stateManager.get(1)).isNull();
            verify(wsHandler, never()).pushRidUpdate(Mockito.anyInt(), Mockito.any());
        }
    }

    // ─── 解码失败 ───

    @Nested
    @DisplayName("解码失败")
    class DecodeFailure {

        /**
         * MAVLink2 尾零裁剪的合法帧：发送方可省略 payload 尾部的全零字节（C 参考
         * 实现 {@code _mav_trim_payload} 默认如此），接收方必须按零填充解读。
         * 3 字节 BASIC_ID = idType/uaType + 空 uasId——实际链路里 uasId 不足 20
         * 字节时被裁成这样是常态。
         * <p>
         * 修复历史：解码入口曾不做零填充，这类帧读越界抛
         * {@link java.nio.BufferUnderflowException}，被 catch 后整帧丢弃。SITL
         * 首验中同一根因让 MISSION_ACK(ACCEPTED)（尾部 type/mission_type/opaque_id
         * 全零被裁到 2 字节）解码越界丢弃，任务上传永远"看不到成功"，2.5s 后重发
         * 收到的 ERROR ack 被误判为最终结果。
         * <p>
         * 这条用例会先让帧真的过一遍 {@link MavlinkParser}，证明它不是"造出来的"。
         */
        @Test
        @DisplayName("截短 payload（尾零裁剪）的 RID 帧：零填充解码、不抛异常、正常入库")
        void onFrame_truncatedPayload_zeroFills() {
            MavlinkFrame frame = truncatedBasicIdFrame(3);

            // 前置：确认这帧真的能过 CRC（也就是真的能从网络到达 onFrame）
            assertThat(frame).as("截短帧应能通过 MavlinkParser 的 CRC 校验").isNotNull();
            assertThat(frame.getMessageId()).isEqualTo(OpenDroneIdBasicId.ID);
            assertThat(frame.getPayloadLength()).isLessThan(OpenDroneIdBasicId.LEN);

            service.onFrame(frame);   // 修复前：这里抛 BufferUnderflowException 且整帧被丢弃

            // 尾零裁剪帧按规范零填充解码：idType=0、uaType=0、uasId 空——与含零尾的
            // 全长帧语义相同，正常入库。
            RidSnapshot snapshot = stateManager.get(1);
            assertThat(snapshot).as("零填充解码后应生成 RID 快照").isNotNull();
            assertThat(snapshot.basicId()).isNotNull();
            assertThat(snapshot.basicId().idType()).isZero();
            assertThat(snapshot.basicId().uaType()).isZero();
            assertThat(snapshot.basicId().uasId()).isEmpty();
        }

        @Test
        @DisplayName("MessagePack 声明 255 条子消息（远超 250 字节字段）：解包越界被兜住")
        void onFrame_messagePackClaimingTooManySubMessages_doesNotThrow() {
            // OPEN_DRONE_ID_MESSAGE_PACK.LEN=252 = 2 字节头(singleMsgSize, msgPackSize) + 250 字节 messages
            // decode 只按 payload 实际长度读，头两字节完全由发送方决定。
            // pack() 侧有 MAX_MSG_PACK_SIZE=10 的检查，decode()/unpack() 侧没有 ——
            // 发送方声明 255 条时 unpack() 会以 25 字节步长读到 250 字节数组之外。
            byte[] payload = new byte[OpenDroneIdMessagePack.LEN];
            payload[0] = (byte) OpenDroneIdMessagePack.SINGLE_MSG_SIZE;  // singleMsgSize = 25
            payload[1] = (byte) 255;                                    // msgPackSize = 255

            MavlinkFrame frame = crcValidFrame(OpenDroneIdMessagePack.ID, payload);
            assertThat(frame).as("该帧应能通过 CRC 校验").isNotNull();
            assertThat(frame.getPayloadLength()).isEqualTo(OpenDroneIdMessagePack.LEN);

            service.onFrame(frame);   // 修复前：unpack() 抛 ArrayIndexOutOfBoundsException

            assertThat(stateManager.get(1)).isNull();
            verify(wsHandler, never()).pushRidUpdate(Mockito.anyInt(), Mockito.any());
        }

        /**
         * 防御分支：RID 区间内但无对应解码器的 msgId。
         * <p>
         * <strong>这条不是生产可达路径</strong>：12950 未在 {@code MavlinkMessageInfo}
         * 登记，{@code crcExtraOf} 会抛 {@code MavlinkException}，帧在 CRC 阶段就被
         * {@code MavlinkParser} 丢掉了，到不了 {@code onFrame}。它覆盖的是"有人绕过
         * 解析器直接调用 onFrame"这一情形——把 {@code msg==null} 守卫整块删掉会让
         * {@code null} 流到 {@code processRidMessage}，在 {@code msg.getClass()} 上
         * NPE，本用例会转红（已变异验证）。
         * <p>
         * 真正可达的解码失败是上面两条（截短 payload / MessagePack 声明 255 条子消息），
         * 它们此前零覆盖，因为旧用例挑的是这条不可达路径。
         */
        @Test
        @DisplayName("RID 区间内无解码器的 msgId（防御分支，生产不可达）：不抛异常、不更新状态")
        void onFrame_unregisteredMsgIdInRange_returnsWithoutUpdating() {
            MavlinkFrame frame = createFrame(1, 1, 12950, new byte[24]);

            service.onFrame(frame);

            assertThat(stateManager.get(1)).isNull();
            verify(wsHandler, never()).pushRidUpdate(Mockito.anyInt(), Mockito.any());
        }
    }

    // ─── 消息分发 ───

    @Nested
    @DisplayName("消息分发")
    class MessageDispatch {

        @Test
        @DisplayName("OpenDroneIdBasicId 消息 → updateBasicId 被调用")
        void onFrame_basicId_updatesBasicId() {
            OpenDroneIdBasicId msg = new OpenDroneIdBasicId(1, 2,
                    "UAS-001".getBytes(StandardCharsets.UTF_8));
            MavlinkFrame frame = msg.toFrame(1, 1, 0);

            service.onFrame(frame);

            RidSnapshot snapshot = stateManager.get(1);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.basicId()).isNotNull();
            assertThat(snapshot.basicId().idType()).isEqualTo(1);
            assertThat(snapshot.basicId().uaType()).isEqualTo(2);
            assertThat(snapshot.basicId().uasId()).isEqualTo("UAS-001");
        }

        @Test
        @DisplayName("OpenDroneIdLocation 消息 → updateLocation 被调用")
        void onFrame_location_updatesLocation() {
            OpenDroneIdLocation msg = new OpenDroneIdLocation(
                    2, 18000, 0, 0,
                    399000000, 1163000000, 100.0f, 120.0f,
                    0, 10, 10, 10, 10, 1.0f);
            MavlinkFrame frame = msg.toFrame(1, 1, 0);

            service.onFrame(frame);

            RidSnapshot snapshot = stateManager.get(1);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.location()).isNotNull();
            assertThat(snapshot.location().latitude()).isEqualTo(39.9);
            assertThat(snapshot.location().longitude()).isEqualTo(116.3);
        }

        @Test
        @DisplayName("OpenDroneIdMessagePack 消息 → unpack 后逐条处理")
        void onFrame_messagePack_unpacksAndProcesses() {
            OpenDroneIdBasicId basicIdMsg = new OpenDroneIdBasicId(1, 2,
                    "UAS-001".getBytes(StandardCharsets.UTF_8));
            OpenDroneIdLocation locationMsg = new OpenDroneIdLocation(
                    2, 18000, 0, 0,
                    399000000, 1163000000, 100.0f, 120.0f,
                    0, 10, 10, 10, 10, 1.0f);

            OpenDroneIdMessagePack pack = OpenDroneIdMessagePack.pack(
                    List.of(basicIdMsg, locationMsg));
            MavlinkFrame frame = pack.toFrame(1, 1, 0);

            service.onFrame(frame);

            RidSnapshot snapshot = stateManager.get(1);
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.basicId()).isNotNull();
            assertThat(snapshot.basicId().uasId()).isEqualTo("UAS-001");
            assertThat(snapshot.location()).isNotNull();
            assertThat(snapshot.location().latitude()).isEqualTo(39.9);
        }
    }

    // ─── 快照查询 ───

    @Nested
    @DisplayName("快照查询")
    class SnapshotQuery {

        @Test
        @DisplayName("getSnapshot 返回正确数据")
        void getSnapshot_returnsCorrectData() {
            OpenDroneIdBasicId msg = new OpenDroneIdBasicId(1, 2,
                    "UAS-001".getBytes(StandardCharsets.UTF_8));
            MavlinkFrame frame = msg.toFrame(1, 1, 0);
            service.onFrame(frame);

            RidSnapshot snapshot = service.getSnapshot(1);

            assertThat(snapshot).isNotNull();
            assertThat(snapshot.sysid()).isEqualTo(1);
            assertThat(snapshot.basicId().uasId()).isEqualTo("UAS-001");
        }

        @Test
        @DisplayName("getSnapshot 不存在的 sysid 返回 null")
        void getSnapshot_nonExistent_returnsNull() {
            assertThat(service.getSnapshot(99)).isNull();
        }

        @Test
        @DisplayName("getAllSnapshots 返回正确数据列表")
        void getAllSnapshots_returnsCorrectList() {
            OpenDroneIdBasicId msg1 = new OpenDroneIdBasicId(1, 2,
                    "UAS-001".getBytes(StandardCharsets.UTF_8));
            OpenDroneIdBasicId msg2 = new OpenDroneIdBasicId(1, 2,
                    "UAS-002".getBytes(StandardCharsets.UTF_8));
            service.onFrame(msg1.toFrame(1, 1, 0));
            service.onFrame(msg2.toFrame(2, 1, 0));

            List<RidSnapshot> all = service.getAllSnapshots();

            assertThat(all).hasSize(2);
            assertThat(all.get(0).sysid()).isEqualTo(1);
            assertThat(all.get(1).sysid()).isEqualTo(2);
        }

        @Test
        @DisplayName("getAllSnapshots 空列表")
        void getAllSnapshots_empty_returnsEmptyList() {
            assertThat(service.getAllSnapshots()).isEmpty();
        }
    }

    // ─── 辅助方法 ───

    /**
     * 构造一个简单的 MavlinkFrame（非签名帧，CRC=0）。
     * <p>
     * <strong>注意</strong>：这个构造器不经过 CRC 校验，造出的帧在生产里根本到不了
     * {@code onFrame}（{@code MavlinkParser} 会在 CRC 阶段丢掉它）。凡是要验证
     * "生产会发生什么"的用例，都必须改用 {@link #crcValidFrame}。
     *
     * @param systemId  系统 ID
     * @param componentId 组件 ID
     * @param messageId 消息 ID
     * @param payload   payload 字节
     * @return MavlinkFrame 实例
     */
    private static MavlinkFrame createFrame(int systemId, int componentId,
                                            int messageId, byte[] payload) {
        return new MavlinkFrame(payload.length, 0, 0, 0,
                systemId, componentId, messageId, payload, 0);
    }

    /**
     * 构造一帧<strong>真的能从网络到达 onFrame</strong> 的 RID 帧：
     * 用 {@link MavlinkFrame#of} 按 payload 的真实长度算 CRC_EXTRA，
     * 编码成 v2 线格式，再过一遍 {@link MavlinkParser} 取回解析结果。
     * <p>
     * payload 故意短于 dialect 的 LEN —— 解析器只校验 CRC，不会按 LEN 补齐或拒绝，
     * 所以这种帧在真实链路上是合法到达、然后在解码器里读越界的。
     *
     * @param msgId  已登记的 RID msgId
     * @param payload 截短的 payload
     * @return 解析器解出的帧；若 CRC 没通过则返回 {@code null}
     */
    private static MavlinkFrame crcValidFrame(int msgId, byte[] payload) {
        byte[] wire = MavlinkFrame.of(1, 1, 0, msgId,
                MavlinkMessageInfo.crcExtraOf(msgId), payload).encodeV2();
        MavlinkParser.ParseResult parsed = new MavlinkParser().parse(ByteBuffer.wrap(wire));
        return parsed == null ? null : parsed.frame;
    }

    /** OPEN_DRONE_ID_BASIC_ID 的截短帧，payload 长度由参数给定。 */
    private static MavlinkFrame truncatedBasicIdFrame(int payloadLen) {
        return crcValidFrame(OpenDroneIdBasicId.ID, new byte[payloadLen]);
    }
}