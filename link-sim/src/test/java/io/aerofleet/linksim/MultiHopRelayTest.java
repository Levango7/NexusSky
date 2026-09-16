package io.aerofleet.linksim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多跳中继配置与 hopCount 逻辑单测（M5 应急 mesh，FR-06~08a）。
 * <p>
 * 覆盖 MultiHopRelayConfig 的 parse/defaults/常量，以及 RelayNode 的
 * extractHopCount/writeHopCount（通过反射访问包私有 static 方法）。
 */
@DisplayName("多跳中继配置与 hopCount 递减 (FR-06~08a)")
class MultiHopRelayTest {

    // ===== MultiHopRelayConfig 测试 =====

    @Test
    @DisplayName("defaults 默认多跳禁用、maxHops=15、组播 239.0.0.1:14550")
    void defaultsMultiHopDisabled() {
        MultiHopRelayConfig cfg = MultiHopRelayConfig.defaults();
        assertThat(cfg.multiHopEnabled).isFalse();
        assertThat(cfg.maxHops).isEqualTo(15);
        assertThat(cfg.meshGroupAddress)
                .isEqualTo(new InetSocketAddress("239.0.0.1", 14550));
    }

    @Test
    @DisplayName("parse --multi-hop 启用多跳")
    void parseEnablesMultiHop() {
        MultiHopRelayConfig cfg = MultiHopRelayConfig.parse(new String[]{"--multi-hop"});
        assertThat(cfg.multiHopEnabled).isTrue();
        assertThat(cfg.maxHops).isEqualTo(MultiHopRelayConfig.MAX_HOPS);
    }

    @Test
    @DisplayName("parse --mesh-group host:port 覆盖组播地址")
    void parseMeshGroup() {
        MultiHopRelayConfig cfg = MultiHopRelayConfig.parse(
                new String[]{"--mesh-group", "239.1.2.3:9999"});
        assertThat(cfg.meshGroupAddress)
                .isEqualTo(new InetSocketAddress("239.1.2.3", 9999));
    }

    @Test
    @DisplayName("parse 无参数返回默认配置")
    void parseNoArgsReturnsDefaults() {
        MultiHopRelayConfig cfg = MultiHopRelayConfig.parse(new String[]{});
        MultiHopRelayConfig def = MultiHopRelayConfig.defaults();
        assertThat(cfg.multiHopEnabled).isEqualTo(def.multiHopEnabled);
        assertThat(cfg.maxHops).isEqualTo(def.maxHops);
        assertThat(cfg.meshGroupAddress).isEqualTo(def.meshGroupAddress);
    }

    @Test
    @DisplayName("MAX_HOPS 常量为 15")
    void maxHopsConstant() {
        assertThat(MultiHopRelayConfig.MAX_HOPS).isEqualTo(15);
    }

    @Test
    @DisplayName("toString 包含 multiHop 与 maxHops")
    void configToString() {
        MultiHopRelayConfig cfg = MultiHopRelayConfig.parse(new String[]{"--multi-hop"});
        String s = cfg.toString();
        assertThat(s).contains("multiHop=true", "maxHops=15");
    }

    // ===== RelayNode extractHopCount / writeHopCount 反射测试 =====

    private static int invokeExtractHopCount(byte[] data) throws Exception {
        Method m = RelayNode.class.getDeclaredMethod("extractHopCount", byte[].class);
        m.setAccessible(true);
        return (int) m.invoke(null, (Object) data);
    }

    private static byte[] invokeWriteHopCount(byte[] data, int hopCount) throws Exception {
        Method m = RelayNode.class.getDeclaredMethod("writeHopCount", byte[].class, int.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, data, hopCount);
    }

    @Test
    @DisplayName("extractHopCount：短帧(<13B)返回 MAX_HOPS=15 兼容降级")
    void extractHopCountShortFrameReturnsMaxHops() throws Exception {
        byte[] shortFrame = new byte[12];
        assertThat(invokeExtractHopCount(shortFrame)).isEqualTo(15);
    }

    @Test
    @DisplayName("extractHopCount：取最后一字节作为 hopCount")
    void extractHopCountFromTail() throws Exception {
        byte[] frame = new byte[13];
        frame[12] = 7;
        assertThat(invokeExtractHopCount(frame)).isEqualTo(7);
    }

    @Test
    @DisplayName("writeHopCount：短帧(<13B) hopCount>0 追加 1 字节到帧尾")
    void writeHopCountAppendsByte() throws Exception {
        byte[] data = new byte[]{1, 2, 3};
        byte[] out = invokeWriteHopCount(data, 5);
        assertThat(out).hasSize(4);
        assertThat(out[3]).isEqualTo((byte) 5);
    }

    @Test
    @DisplayName("writeHopCount：长帧(>=13B) 原地更新尾部字节，帧长不变（避免帧持续增长）")
    void writeHopCountUpdatesInPlaceForLongFrame() throws Exception {
        byte[] data = new byte[13];
        data[12] = 7;
        byte[] out = invokeWriteHopCount(data, 5);
        assertThat(out).hasSize(13);
        assertThat(out[12]).isEqualTo((byte) 5);
    }

    @Test
    @DisplayName("writeHopCount：hopCount=0 仍保留尾部字节=0，接收方据此丢弃（避免重新当作新帧）")
    void writeHopCountZeroKeepsTailZero() throws Exception {
        // 短帧追加尾部 0（但 < 13B 仍按旧帧兼容降级，此处仅验证写入值）
        byte[] data = new byte[]{1, 2, 3};
        byte[] out = invokeWriteHopCount(data, 0);
        assertThat(out).hasSize(4);
        assertThat(out[3]).isEqualTo((byte) 0);

        // 长帧(>=13B)原地更新尾部为 0：接收方 extractHopCount 返回 0 → hopCount <= 0 丢弃
        byte[] longData = new byte[13];
        longData[12] = 7;
        byte[] outLong = invokeWriteHopCount(longData, 0);
        assertThat(outLong).hasSize(13);
        assertThat(outLong[12]).isEqualTo((byte) 0);
        assertThat(invokeExtractHopCount(outLong)).isZero();
    }
}