package io.aerofleet.cloud.rid;

import io.aerofleet.cloud.rid.model.BasicIdData;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.OpenDroneIdBasicId;
import io.aerofleet.mavlink.messages.OpenDroneIdLocation;
import io.aerofleet.mavlink.messages.OpenDroneIdMessagePack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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

        @Test
        @DisplayName("解码失败时 WARN 日志且不更新状态")
        void onFrame_decodeFailure_doesNotUpdateState() {
            // msgId=12950 在 RID 范围内但无对应解码器，MavlinkMessage.decode 返回 null
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
}