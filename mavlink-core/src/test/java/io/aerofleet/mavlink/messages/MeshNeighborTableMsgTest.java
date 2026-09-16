package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MESH_NEIGHBOR_TABLE (msgId=454) 编解码单测（M5 拓扑快照上报，FR-27）。
 * <p>
 * 可变长度消息，覆盖空邻居、多邻居往返、null 列表、上限截断、截断解码。
 */
@DisplayName("MeshNeighborTableMsg 编解码 (msgId=454)")
class MeshNeighborTableMsgTest {

    private static MeshNeighborTableMsg roundtrip(MeshNeighborTableMsg msg) {
        MavlinkFrame frame = msg.toFrame(1, 1, 0);
        return MeshNeighborTableMsg.decode(frame);
    }

    @Test
    @DisplayName("多邻居 encode→decode 往返一致")
    void encodeDecodeRoundtrip() {
        List<MeshNeighborTableMsg.NeighborInfo> neighbors = List.of(
                new MeshNeighborTableMsg.NeighborInfo(2, -45, 0),
                new MeshNeighborTableMsg.NeighborInfo(3, -75, 2),
                new MeshNeighborTableMsg.NeighborInfo(4, -90, 3));
        MeshNeighborTableMsg orig = new MeshNeighborTableMsg(1, 100000L, neighbors);
        MeshNeighborTableMsg back = roundtrip(orig);

        assertThat(back.sysid).isEqualTo(1);
        assertThat(back.timestamp).isEqualTo(100000L);
        assertThat(back.neighborCount()).isEqualTo(3);
        assertThat(back.neighbors.get(0).sysid()).isEqualTo(2);
        assertThat(back.neighbors.get(0).rssiDbm()).isEqualTo(-45);
        assertThat(back.neighbors.get(0).linkQualityOrdinal()).isEqualTo(0);
        assertThat(back.neighbors.get(1).sysid()).isEqualTo(3);
        assertThat(back.neighbors.get(1).rssiDbm()).isEqualTo(-75);
        assertThat(back.neighbors.get(2).linkQualityOrdinal()).isEqualTo(3);
    }

    @Test
    @DisplayName("空邻居列表：encode 产出 6 字节头部")
    void emptyNeighbors() {
        MeshNeighborTableMsg msg = new MeshNeighborTableMsg(1, 0L, List.of());
        assertThat(msg.encode()).hasSize(MeshNeighborTableMsg.HEADER_LEN);
        assertThat(msg.neighborCount()).isZero();

        MeshNeighborTableMsg back = roundtrip(msg);
        assertThat(back.neighborCount()).isZero();
    }

    @Test
    @DisplayName("null 邻居列表视为空列表")
    void nullNeighborsTreatedAsEmpty() {
        MeshNeighborTableMsg msg = new MeshNeighborTableMsg(1, 0L, null);
        assertThat(msg.neighbors).isEmpty();
        assertThat(msg.neighborCount()).isZero();
    }

    @Test
    @DisplayName("邻居数超过 255 上限截断为 255")
    void maxNeighborsTruncated() {
        List<MeshNeighborTableMsg.NeighborInfo> neighbors = new java.util.ArrayList<>();
        for (int i = 0; i < 260; i++) {
            neighbors.add(new MeshNeighborTableMsg.NeighborInfo(i + 1, -60, 1));
        }
        MeshNeighborTableMsg msg = new MeshNeighborTableMsg(1, 0L, neighbors);
        byte[] payload = msg.encode();
        // 头部 6 + 255 × 4 = 1026
        assertThat(payload).hasSize(MeshNeighborTableMsg.HEADER_LEN
                + MeshNeighborTableMsg.MAX_NEIGHBORS * MeshNeighborTableMsg.NEIGHBOR_ENTRY_LEN);
    }

    @Test
    @DisplayName("截断 payload 解码：声明 3 邻居但字节只够 1 个，解出 1 个")
    void decodeTruncatedNeighborList() {
        // 手工构造 payload：sysid=1, count=3, ts=0, 但只附 1 个邻居项
        byte[] payload = new byte[6 + 4];
        payload[0] = 1;            // sysid
        payload[1] = 3;            // declaredCount = 3
        // timestamp u32 @ offset 2 = 0
        payload[6] = 5;            // neighbor sysid
        payload[7] = (byte) -50;   // rssi
        payload[8] = 0;            // quality
        payload[9] = 0;            // reserved
        MavlinkFrame frame = new MavlinkFrame(payload.length, 0, 0, 0, 1, 1, 454, payload, 0);
        MeshNeighborTableMsg msg = MeshNeighborTableMsg.decode(frame);

        assertThat(msg.sysid).isEqualTo(1);
        assertThat(msg.neighborCount()).isEqualTo(1);
        assertThat(msg.neighbors.get(0).sysid()).isEqualTo(5);
    }

    @Test
    @DisplayName("常量：ID=454、HEADER_LEN=6、ENTRY_LEN=4、MAX_NEIGHBORS=255")
    void constants() {
        assertThat(MeshNeighborTableMsg.ID).isEqualTo(454);
        assertThat(MeshNeighborTableMsg.HEADER_LEN).isEqualTo(6);
        assertThat(MeshNeighborTableMsg.NEIGHBOR_ENTRY_LEN).isEqualTo(4);
        assertThat(MeshNeighborTableMsg.MAX_NEIGHBORS).isEqualTo(255);
    }
}