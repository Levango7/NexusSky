package io.aerofleet.sim.sat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SatBandwidthAllocator 卫星带宽分配器单测（FR-24 卫星链路带宽分配）。
 * <p>
 * 覆盖低带宽模式比例分配、高带宽模式全量分配、阈值边界、QoS 比例验证。
 */
@DisplayName("SatBandwidthAllocator 卫星带宽分配 (FR-24)")
class SatBandwidthAllocatorTest {

    private final SatBandwidthAllocator allocator = new SatBandwidthAllocator();

    // ===== 低带宽模式（<100kbps）=====

    @Test
    @DisplayName("低带宽模式：9.6kbps 天通按 QoS 比例分配")
    void lowBandwidthTiantongAllocation() {
        long totalBw = 9_600L; // 天通典型带宽

        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(totalBw);

        assertThat(result.lowBandwidthMode()).isTrue();
        assertThat(result.totalBandwidthBps()).isEqualTo(9_600L);

        // EMERGENCY 60% = 5760
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.EMERGENCY))
                .isEqualTo(5_760L);
        // COMMAND 25% = 2400
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.COMMAND))
                .isEqualTo(2_400L);
        // MAPPING 10% = 960
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.MAPPING))
                .isEqualTo(960L);
        // ROUTINE 5% = 480
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.ROUTINE))
                .isEqualTo(480L);
    }

    @Test
    @DisplayName("低带宽模式：2.4kbps 铱星按 QoS 比例分配")
    void lowBandwidthIridiumAllocation() {
        long totalBw = 2_400L; // 铱星典型带宽

        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(totalBw);

        assertThat(result.lowBandwidthMode()).isTrue();

        // EMERGENCY 60% = 1440
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.EMERGENCY))
                .isEqualTo(1_440L);
        // COMMAND 25% = 600
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.COMMAND))
                .isEqualTo(600L);
        // MAPPING 10% = 240
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.MAPPING))
                .isEqualTo(240L);
        // ROUTINE 5% = 120
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.ROUTINE))
                .isEqualTo(120L);
    }

    @Test
    @DisplayName("低带宽模式：各 QoS 分配比例之和 = 100%")
    void lowBandwidthRatiosSumTo100Percent() {
        long totalBw = 50_000L; // 50kbps

        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(totalBw);

        assertThat(result.lowBandwidthMode()).isTrue();

        long sum = result.getAllocation(SatBandwidthAllocator.QoSClass.EMERGENCY)
                + result.getAllocation(SatBandwidthAllocator.QoSClass.COMMAND)
                + result.getAllocation(SatBandwidthAllocator.QoSClass.MAPPING)
                + result.getAllocation(SatBandwidthAllocator.QoSClass.ROUTINE);

        assertThat(sum).isEqualTo(totalBw);
    }

    @Test
    @DisplayName("低带宽模式：EMERGENCY 分配最多（60%）")
    void emergencyGetsMostBandwidth() {
        long totalBw = 80_000L; // 80kbps

        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(totalBw);

        long emergency = result.getAllocation(SatBandwidthAllocator.QoSClass.EMERGENCY);
        long command = result.getAllocation(SatBandwidthAllocator.QoSClass.COMMAND);
        long mapping = result.getAllocation(SatBandwidthAllocator.QoSClass.MAPPING);
        long routine = result.getAllocation(SatBandwidthAllocator.QoSClass.ROUTINE);

        assertThat(emergency).isGreaterThan(command);
        assertThat(command).isGreaterThan(mapping);
        assertThat(mapping).isGreaterThan(routine);
    }

    // ===== 高带宽模式（≥100kbps）=====

    @Test
    @DisplayName("高带宽模式：100Mbps 星链各 QoS 均可使用全量带宽")
    void highBandwidthStarlinkAllocation() {
        long totalBw = 100_000_000L; // 星链典型带宽 100Mbps

        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(totalBw);

        assertThat(result.lowBandwidthMode()).isFalse();

        // 各等级均可使用全量带宽
        for (SatBandwidthAllocator.QoSClass qos : SatBandwidthAllocator.QoSClass.values()) {
            assertThat(result.getAllocation(qos)).isEqualTo(totalBw);
        }
    }

    @Test
    @DisplayName("高带宽模式：1Mbps 各 QoS 均可使用全量带宽")
    void highBandwidth1MbpsAllocation() {
        long totalBw = 1_000_000L; // 1Mbps

        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(totalBw);

        assertThat(result.lowBandwidthMode()).isFalse();
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.EMERGENCY))
                .isEqualTo(1_000_000L);
        assertThat(result.getAllocation(SatBandwidthAllocator.QoSClass.ROUTINE))
                .isEqualTo(1_000_000L);
    }

    // ===== 阈值边界 =====

    @Test
    @DisplayName("阈值边界：99kbps 仍为低带宽模式")
    void threshold99kbpsIsLowBandwidth() {
        long totalBw = 99_999L; // 99.999kbps < 100kbps

        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(totalBw);

        assertThat(result.lowBandwidthMode()).isTrue();
    }

    @Test
    @DisplayName("阈值边界：100kbps 为非低带宽模式")
    void threshold100kbpsIsNotLowBandwidth() {
        long totalBw = 100_000L; // 正好 100kbps

        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(totalBw);

        assertThat(result.lowBandwidthMode()).isFalse();
    }

    @Test
    @DisplayName("阈值边界：isLowBandwidth 方法一致性")
    void isLowBandwidthConsistency() {
        assertThat(allocator.isLowBandwidth(0L)).isTrue();
        assertThat(allocator.isLowBandwidth(99_999L)).isTrue();
        assertThat(allocator.isLowBandwidth(100_000L)).isFalse();
        assertThat(allocator.isLowBandwidth(1_000_000L)).isFalse();
    }

    // ===== 零带宽 =====

    @Test
    @DisplayName("零带宽：低带宽模式，各 QoS 分配 0")
    void zeroBandwidthAllocation() {
        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(0L);

        assertThat(result.lowBandwidthMode()).isTrue();
        assertThat(result.totalBandwidthBps()).isZero();

        for (SatBandwidthAllocator.QoSClass qos : SatBandwidthAllocator.QoSClass.values()) {
            assertThat(result.getAllocation(qos)).isZero();
        }
    }

    // ===== QoS 比例验证 =====

    @Test
    @DisplayName("QoS 比例：EMERGENCY=60% COMMAND=25% MAPPING=10% ROUTINE=5%")
    void qosRatiosCorrect() {
        assertThat(SatBandwidthAllocator.QoSClass.EMERGENCY.allocationRatio()).isEqualTo(0.60);
        assertThat(SatBandwidthAllocator.QoSClass.COMMAND.allocationRatio()).isEqualTo(0.25);
        assertThat(SatBandwidthAllocator.QoSClass.MAPPING.allocationRatio()).isEqualTo(0.10);
        assertThat(SatBandwidthAllocator.QoSClass.ROUTINE.allocationRatio()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("QoS 比例之和 = 1.0")
    void qosRatiosSumToOne() {
        double sum = 0.0;
        for (SatBandwidthAllocator.QoSClass qos : SatBandwidthAllocator.QoSClass.values()) {
            sum += qos.allocationRatio();
        }
        assertThat(sum).isEqualTo(1.0);
    }

    // ===== 异常处理 =====

    @Test
    @DisplayName("负带宽抛出 IllegalArgumentException")
    void negativeBandwidthThrows() {
        assertThatThrownBy(() -> allocator.allocate(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be >= 0");
    }

    // ===== QoSClass fromCode =====

    @Test
    @DisplayName("QoSClass fromCode：0→EMERGENCY, 1→COMMAND, 2→MAPPING, 3→ROUTINE")
    void qosClassFromCode() {
        assertThat(SatBandwidthAllocator.QoSClass.fromCode(0))
                .isEqualTo(SatBandwidthAllocator.QoSClass.EMERGENCY);
        assertThat(SatBandwidthAllocator.QoSClass.fromCode(1))
                .isEqualTo(SatBandwidthAllocator.QoSClass.COMMAND);
        assertThat(SatBandwidthAllocator.QoSClass.fromCode(2))
                .isEqualTo(SatBandwidthAllocator.QoSClass.MAPPING);
        assertThat(SatBandwidthAllocator.QoSClass.fromCode(3))
                .isEqualTo(SatBandwidthAllocator.QoSClass.ROUTINE);
        // 越界 code 默认回退到 ROUTINE
        assertThat(SatBandwidthAllocator.QoSClass.fromCode(99))
                .isEqualTo(SatBandwidthAllocator.QoSClass.ROUTINE);
    }

    // ===== 分配结果不可变性 =====

    @Test
    @DisplayName("分配结果 Map 不可变")
    void allocationMapIsImmutable() {
        SatBandwidthAllocator.BandwidthAllocation result = allocator.allocate(50_000L);

        assertThatThrownBy(() -> result.allocations()
                .put(SatBandwidthAllocator.QoSClass.EMERGENCY, 999L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ===== 辅助方法 =====

    /** AssertJ 风格的异常断言辅助（避免引入额外的 assertj 依赖方法）。 */
    private static <T extends Throwable> void assertThrows(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            Class<T> expectedType) {
        org.assertj.core.api.Assertions.assertThatThrownBy(callable).isInstanceOf(expectedType);
    }

    /** AssertJ 风格的异常断言辅助（带消息检查）。 */
    private static void assertThrows(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            Class<? extends Throwable> expectedType,
            String messageContains) {
        org.assertj.core.api.Assertions.assertThatThrownBy(callable)
                .isInstanceOf(expectedType)
                .hasMessageContaining(messageContains);
    }
}