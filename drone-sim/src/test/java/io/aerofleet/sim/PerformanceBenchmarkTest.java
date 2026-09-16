package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkMessageInfo;
import io.aerofleet.sim.mesh.RouteTable;
import io.aerofleet.sim.orch.CoverageOptimizer;
import io.aerofleet.sim.orch.DeploymentPlan;
import io.aerofleet.sim.orch.DroneInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能基准测试：验证 P1-P2 优化的效果。
 * <p>
 * 每个 test 方法测量一个优化点的吞吐量/延迟，打印结果供对比。
 * 断言只验证 opsPerSec > 0（不设硬性阈值，因为不同机器性能不同）。
 * <p>
 * 覆盖优化点：
 * <ul>
 *   <li>P1-1: MavlinkMessageInfo HashMap→数组直接索引（isKnown / lengthOf / crcExtraOf）</li>
 *   <li>P1-3: RouteTable 减少 ArrayList 复制（upsert / removeByNextHop / promoteBackup）</li>
 *   <li>P2-1: CoverageOptimizer Haversine 距离计算缓存（optimize 10 无人机场景）</li>
 * </ul>
 */
class PerformanceBenchmarkTest {

    /** 基准测试通用预热迭代次数。 */
    private static final int WARMUP = 100_000;

    // ==================== P1-1: MavlinkMessageInfo 数组查找 ====================

    @Test
    @DisplayName("P1-1: MavlinkMessageInfo.isKnown 数组查找 vs HashMap 查找吞吐量")
    void testMavlinkMessageInfoIsKnownThroughput() {
        int iterations = 10_000_000;
        int[] msgIds = {0, 30, 76, 450, 465, 467}; // 代表性 msgId

        // 预热
        for (int i = 0; i < WARMUP; i++) {
            for (int id : msgIds) {
                MavlinkMessageInfo.isKnown(id);
            }
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            for (int id : msgIds) {
                MavlinkMessageInfo.isKnown(id);
            }
        }
        long elapsed = System.nanoTime() - start;
        long totalOps = (long) iterations * msgIds.length;
        double opsPerSec = totalOps / (elapsed / 1e9);
        System.out.printf("P1-1 MavlinkMessageInfo.isKnown: %.0f ops/sec (%.2f ns/op)%n",
                opsPerSec, elapsed / (double) totalOps);
        assertTrue(opsPerSec > 0);
    }

    @Test
    @DisplayName("P1-1: MavlinkMessageInfo.lengthOf 数组查找吞吐量")
    void testMavlinkMessageInfoLengthOfThroughput() {
        int iterations = 10_000_000;
        int[] msgIds = {0, 30, 76, 450, 465, 467};

        // 预热
        for (int i = 0; i < WARMUP; i++) {
            for (int id : msgIds) {
                MavlinkMessageInfo.lengthOf(id);
            }
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            for (int id : msgIds) {
                MavlinkMessageInfo.lengthOf(id);
            }
        }
        long elapsed = System.nanoTime() - start;
        long totalOps = (long) iterations * msgIds.length;
        double opsPerSec = totalOps / (elapsed / 1e9);
        System.out.printf("P1-1 MavlinkMessageInfo.lengthOf: %.0f ops/sec (%.2f ns/op)%n",
                opsPerSec, elapsed / (double) totalOps);
        assertTrue(opsPerSec > 0);
    }

    @Test
    @DisplayName("P1-1: MavlinkMessageInfo.crcExtraOf 数组查找吞吐量")
    void testMavlinkMessageInfoCrcExtraOfThroughput() {
        int iterations = 10_000_000;
        int[] msgIds = {0, 30, 76, 450, 465, 467};

        // 预热
        for (int i = 0; i < WARMUP; i++) {
            for (int id : msgIds) {
                MavlinkMessageInfo.crcExtraOf(id);
            }
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            for (int id : msgIds) {
                MavlinkMessageInfo.crcExtraOf(id);
            }
        }
        long elapsed = System.nanoTime() - start;
        long totalOps = (long) iterations * msgIds.length;
        double opsPerSec = totalOps / (elapsed / 1e9);
        System.out.printf("P1-1 MavlinkMessageInfo.crcExtraOf: %.0f ops/sec (%.2f ns/op)%n",
                opsPerSec, elapsed / (double) totalOps);
        assertTrue(opsPerSec > 0);
    }

    // ==================== P1-3: RouteTable 减少 ArrayList 复制 ====================

    @Test
    @DisplayName("P1-3: RouteTable.upsert 吞吐量")
    void testRouteTableUpsertThroughput() {
        int iterations = 1_000_000;
        RouteTable table = new RouteTable(30000);

        // 预热
        for (int i = 0; i < 10_000; i++) {
            table.upsert(i % 100, i % 50, 1, 1.0, System.currentTimeMillis(), true);
        }
        table.clear();

        long now = System.currentTimeMillis();
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            table.upsert(i % 100, i % 50, 1, 1.0, now, true);
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = iterations / (elapsed / 1e9);
        System.out.printf("P1-3 RouteTable.upsert: %.0f ops/sec (%.2f ns/op)%n",
                opsPerSec, elapsed / (double) iterations);
        assertTrue(opsPerSec > 0);
    }

    @Test
    @DisplayName("P1-3: RouteTable.removeByNextHop 吞吐量")
    void testRouteTableRemoveByNextHopThroughput() {
        int rounds = 10_000;
        int targetsPerRound = 100;

        // 预热
        RouteTable warmupTable = new RouteTable(30000);
        long warmupNow = System.currentTimeMillis();
        for (int t = 0; t < targetsPerRound; t++) {
            warmupTable.upsert(t, t % 50, 1, 1.0, warmupNow, true);
        }
        for (int nh = 0; nh < 50; nh++) {
            warmupTable.removeByNextHop(nh);
        }

        long start = System.nanoTime();
        for (int r = 0; r < rounds; r++) {
            RouteTable table = new RouteTable(30000);
            long now = System.currentTimeMillis();
            for (int t = 0; t < targetsPerRound; t++) {
                table.upsert(t, t % 50, 1, 1.0, now, true);
            }
            // 删除所有以 nextHop 0..49 为下一跳的项
            for (int nh = 0; nh < 50; nh++) {
                table.removeByNextHop(nh);
            }
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = rounds / (elapsed / 1e9);
        System.out.printf("P1-3 RouteTable.removeByNextHop (100 targets, 50 nextHops): "
                        + "%.0f rounds/sec (%.3f ms/round)%n",
                opsPerSec, elapsed / 1e6 / rounds);
        assertTrue(opsPerSec > 0);
    }

    @Test
    @DisplayName("P1-3: RouteTable.promoteBackup 吞吐量")
    void testRouteTablePromoteBackupThroughput() {
        int iterations = 1_000_000;
        RouteTable table = new RouteTable(30000);
        long now = System.currentTimeMillis();

        // 为 100 个目标各建立主+备份路径
        for (int t = 0; t < 100; t++) {
            table.upsert(t, t, 1, 2.0, now, true);          // 主路径 metric=2.0
            table.upsert(t, t + 100, 2, 3.0, now, false);    // 备份路径 metric=3.0
        }

        // 预热：promoteBackup 后重新恢复主路径
        for (int i = 0; i < 10_000; i++) {
            int t = i % 100;
            table.promoteBackup(t);
            table.upsert(t, t, 1, 2.0, now, true);
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            int t = i % 100;
            table.promoteBackup(t);
            // 恢复主路径，保证下一轮 promoteBackup 仍有备份可提升
            table.upsert(t, t, 1, 2.0, now, true);
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = iterations / (elapsed / 1e9);
        System.out.printf("P1-3 RouteTable.promoteBackup: %.0f ops/sec (%.2f ns/op)%n",
                opsPerSec, elapsed / (double) iterations);
        assertTrue(opsPerSec > 0);
    }

    // ==================== P2-1: CoverageOptimizer Haversine 距离缓存 ====================

    @Test
    @DisplayName("P2-1: CoverageOptimizer.optimize 吞吐量（10 无人机场景）")
    void testCoverageOptimizerThroughput() {
        int iterations = 100;
        CoverageOptimizer optimizer = new CoverageOptimizer();

        // 创建测试数据：10 架无人机，支持 LTE + WiFi
        // DroneInfo(droneId, batteryPercent, currentLat, currentLon, supportedCellTypes)
        // supportedCellTypes: 1=LTE, 2=WiFi, 3=LoRa
        List<DroneInfo> drones = new ArrayList<>();
        Set<Integer> cellTypes = Set.of(1, 2); // LTE + WiFi
        for (int i = 1; i <= 10; i++) {
            drones.add(new DroneInfo(i, 80, 80.0 + i * 0.001,
                    120.0 + i * 0.001, cellTypes));
        }

        // 预热
        for (int i = 0; i < 5; i++) {
            optimizer.optimize(80.0, 120.0, 3000.0, drones, 0);
        }

        long start = System.nanoTime();
        DeploymentPlan lastPlan = null;
        for (int i = 0; i < iterations; i++) {
            lastPlan = optimizer.optimize(80.0, 120.0, 3000.0, drones, 0);
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = iterations / (elapsed / 1e9);
        System.out.printf("P2-1 CoverageOptimizer.optimize (10 drones, r=3000m): "
                        + "%.2f ops/sec (%.2f ms/op, coverage=%.2f%%, connect=%.2f%%)%n",
                opsPerSec, elapsed / 1e6 / iterations,
                lastPlan != null ? lastPlan.getCoverageRate() : 0.0,
                lastPlan != null ? lastPlan.getConnectRate() : 0.0);
        assertTrue(opsPerSec > 0);
    }
}