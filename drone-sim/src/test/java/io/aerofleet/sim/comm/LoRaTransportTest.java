package io.aerofleet.sim.comm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoRaTransport 单测：LoRa 传输适配层分片/重组 + 参数配置。
 * <p>
 * 覆盖默认/远距离配置、小帧/大帧分片、数据完整性、部分重组、延迟估算、最大载荷。
 */
@DisplayName("LoRaTransport LoRa 传输适配层")
class LoRaTransportTest {

    @Test
    @DisplayName("defaultConfig 返回 433MHz/SF7/125kHz/4-5/20dBm/50B")
    void defaultConfig_returnsExpectedParams() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        assertThat(t.frequencyMHz()).isEqualTo(433.0);
        assertThat(t.spreadingFactor()).isEqualTo(7);
        assertThat(t.bandwidthKHz()).isEqualTo(125);
        assertThat(t.codingRate()).isEqualTo(5);   // 4/5
        assertThat(t.txPowerDbm()).isEqualTo(20);
        assertThat(t.maxPayloadBytes()).isEqualTo(50);
    }

    @Test
    @DisplayName("longRange 返回 SF12/4-8 远距离配置")
    void longRange_returnsSF12() {
        LoRaTransport t = LoRaTransport.longRange();
        assertThat(t.spreadingFactor()).isEqualTo(12);
        assertThat(t.codingRate()).isEqualTo(8);   // 4/8
        assertThat(t.frequencyMHz()).isEqualTo(433.0);
        assertThat(t.txPowerDbm()).isEqualTo(20);
    }

    @Test
    @DisplayName("小于 47 字节的载荷不分片（单分片）")
    void fragment_smallPayload_singleFragment() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        byte[] payload = new byte[30];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        byte[][] frags = t.fragment(payload, 1);
        assertThat(frags.length).isEqualTo(1);
        // 头 3 字节 + 载荷 30 字节
        assertThat(frags[0]).hasSize(3 + 30);
        // 头：frameId=1, total=1, index=0
        assertThat(frags[0][0] & 0xFF).isEqualTo(1);
        assertThat(frags[0][1] & 0xFF).isEqualTo(1);
        assertThat(frags[0][2] & 0xFF).isEqualTo(0);
    }

    @Test
    @DisplayName("大于 47 字节的载荷正确分片为多片")
    void fragment_largePayload_multipleFragments() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        // 100 字节 -> ceil(100/47) = 3 片
        byte[] payload = new byte[100];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        byte[][] frags = t.fragment(payload, 5);
        assertThat(frags.length).isEqualTo(3);
        // 每片头 total=3
        assertThat(frags[0][1] & 0xFF).isEqualTo(3);
        assertThat(frags[1][1] & 0xFF).isEqualTo(3);
        assertThat(frags[2][1] & 0xFF).isEqualTo(3);
        // index 递增
        assertThat(frags[0][2] & 0xFF).isEqualTo(0);
        assertThat(frags[1][2] & 0xFF).isEqualTo(1);
        assertThat(frags[2][2] & 0xFF).isEqualTo(2);
        // 前两片满载 47+3=50，最后片 6+3=9
        assertThat(frags[0]).hasSize(50);
        assertThat(frags[1]).hasSize(50);
        assertThat(frags[2]).hasSize(3 + (100 - 47 * 2));
    }

    @Test
    @DisplayName("分片后重组数据与原始一致")
    void fragment_preservesData() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        byte[] payload = new byte[120];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 3 + 7);
        }
        byte[][] frags = t.fragment(payload, 10);
        // 逐片重组
        byte[] reassembled = null;
        for (byte[] frag : frags) {
            reassembled = t.reassemble(10, frag);
        }
        assertThat(reassembled).isEqualTo(payload);
    }

    @Test
    @DisplayName("未收齐分片时 reassemble 返回 null")
    void reassemble_partialFragments_returnsNull() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        byte[] payload = new byte[100];
        byte[][] frags = t.fragment(payload, 7);
        assertThat(frags.length).isEqualTo(3);
        // 只喂前两片，应返回 null
        assertThat(t.reassemble(7, frags[0])).isNull();
        assertThat(t.reassemble(7, frags[1])).isNull();
    }

    @Test
    @DisplayName("收齐全部分片后 reassemble 返回原始数据")
    void reassemble_allFragments_returnsOriginal() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        byte[] payload = new byte[95];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (255 - i);
        }
        byte[][] frags = t.fragment(payload, 42);
        byte[] result = null;
        for (byte[] frag : frags) {
            result = t.reassemble(42, frag);
        }
        assertThat(result).isEqualTo(payload);
    }

    @Test
    @DisplayName("SF7 延迟低于 SF12 延迟")
    void estimatedLatency_sf7_lowerThanSF12() {
        LoRaTransport sf7 = LoRaTransport.defaultConfig();
        LoRaTransport sf12 = LoRaTransport.longRange();
        int payload = 50;
        long lat7 = sf7.estimatedLatencyMs(payload);
        long lat12 = sf12.estimatedLatencyMs(payload);
        assertThat(lat7).isLessThan(lat12);
        // SF7 ~50ms, SF12 ~2000ms
        assertThat(lat7).isEqualTo(50L);
        assertThat(lat12).isEqualTo(2000L);
    }

    @Test
    @DisplayName("maxPayload 默认配置返回正数（47）")
    void maxPayload_defaultConfig_positive() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        assertThat(t.maxPayload()).isPositive();
        assertThat(t.maxPayload()).isEqualTo(47); // 50 - 3
    }

    @Test
    @DisplayName("空载荷分片为单个仅含头的分片")
    void fragment_emptyPayload_singleHeaderOnlyFragment() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        byte[][] frags = t.fragment(new byte[0], 3);
        assertThat(frags.length).isEqualTo(1);
        assertThat(frags[0]).hasSize(3); // 仅头
        assertThat(frags[0][0] & 0xFF).isEqualTo(3);
        assertThat(frags[0][1] & 0xFF).isEqualTo(1);
        assertThat(frags[0][2] & 0xFF).isEqualTo(0);
        // 重组回空数组
        assertThat(t.reassemble(3, frags[0])).isEqualTo(new byte[0]);
    }

    @Test
    @DisplayName("正好 47 字节边界：单分片且满载")
    void fragment_exactChunkSizeBoundary() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        byte[] payload = new byte[47];
        byte[][] frags = t.fragment(payload, 1);
        assertThat(frags.length).isEqualTo(1);
        assertThat(frags[0]).hasSize(50); // 47 + 3
    }

    @Test
    @DisplayName("48 字节边界：两分片（47 + 1）")
    void fragment_justOverChunkSizeBoundary() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        byte[] payload = new byte[48];
        byte[][] frags = t.fragment(payload, 1);
        assertThat(frags.length).isEqualTo(2);
        assertThat(frags[0]).hasSize(50);     // 47 + 3
        assertThat(frags[1]).hasSize(3 + 1);  // 1 + 3
    }

    @Test
    @DisplayName("延迟随载荷增大而增加")
    void estimatedLatency_scalesWithPayload() {
        LoRaTransport t = LoRaTransport.defaultConfig();
        long small = t.estimatedLatencyMs(10);
        long medium = t.estimatedLatencyMs(50);
        long large = t.estimatedLatencyMs(100);
        assertThat(small).isLessThanOrEqualTo(medium);
        assertThat(medium).isLessThan(large);
    }
}