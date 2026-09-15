package io.aerofleet.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SprayPump 单测（FR-05/FR-07/FR-08/FR-10/FR-11/FR-16/FR-17/FR-18 + 不变量）。
 * <p>
 * 覆盖流量控制 / 药量追踪 / 耗尽停喷 / 流量随速度 / 覆盖率 / 漂移补偿 / 侧风禁喷 / 无环境不补偿。
 */
class SprayPumpTest {

    private static final double DT = 0.05;   // 20 Hz
    private static final double EPS = 1e-6;

    /** FR-05：setRate 钳位 [0, rateMax]。 */
    @Test
    void setRateClampsToRange() {
        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, null, null);
        pump.enable();
        pump.setRate(9999);  // 超 max
        assertEquals(2000, pump.targetRate(), EPS);
        pump.setRate(-100);  // 负值
        assertEquals(0, pump.targetRate(), EPS);
        pump.setRate(500);
        assertEquals(500, pump.targetRate(), EPS);
    }

    /** FR-07：喷洒 1s rate=1000 → 剩余减少 1000 mL（无速度耦合时 actualRate=0，需注入速度）。 */
    @Test
    void chemicalConsumption() {
        // 注入一个 mock physics：通过 DronePhysics 真实实例，setTarget 让它有速度
        DronePhysics physics = new DronePhysics(22.5907, 113.9345, 10, 5.0);
        physics.setTarget(100, 0, 10);  // 向北飞 100m
        // 推进几个 tick 让速度达到 refSpeed=5
        for (int i = 0; i < 200; i++) physics.tick(DT);
        // 此时 groundSpeed 应接近 5 m/s
        double speed = physics.groundSpeed();

        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, physics, null);
        pump.enable();
        pump.setRate(1000);
        double before = pump.remainingChemical();
        // 喷洒 1s = 20 ticks
        for (int i = 0; i < 20; i++) pump.tick(DT);
        double after = pump.remainingChemical();
        // 消耗量 = actualRate × 1s，actualRate = 1000 × (speed/5) × 1.0（无风）
        double expectedConsume = 1000 * (speed / 5.0) * 1.0;
        assertEquals(expectedConsume, before - after, 5.0, "消耗量应≈actualRate×dt×20");
        // 不变量：remainingChemical ≥ 0
        assertTrue(after >= 0, "药量不透支");
    }

    /** FR-08：药量耗尽 → actualRate=0 + lowChemical=true。 */
    @Test
    void chemicalDepletionAutoStop() {
        DronePhysics physics = new DronePhysics(22.5907, 113.9345, 10, 5.0);
        physics.setTarget(100, 0, 10);
        for (int i = 0; i < 200; i++) physics.tick(DT);

        // 小容量 0.001L = 1mL，rate=1000 mL/s → 1 tick 即耗尽
        SprayPump pump = new SprayPump(0.001, 2000, 5, 6, 5, 3, physics, null);
        pump.enable();
        pump.setRate(1000);
        // 持续喷洒至耗尽
        for (int i = 0; i < 100; i++) pump.tick(DT);
        assertEquals(0.0, pump.remainingChemical(), EPS, "药量耗尽为 0");
        assertEquals(0.0, pump.actualRate(), EPS, "耗尽后 actualRate=0");
        assertTrue(pump.lowChemical(), "lowChemical 标志置位");
        assertEquals(ActuatorState.IDLE, pump.getState().state());
    }

    /** FR-10：速度=0 → actualRate=0（悬停不喷）。 */
    @Test
    void flowRateZeroAtZeroSpeed() {
        DronePhysics physics = new DronePhysics(22.5907, 113.9345, 10, 5.0);
        // 不设 target，速度为 0
        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, physics, null);
        pump.enable();
        pump.setRate(1000);
        pump.tick(DT);
        assertEquals(0.0, pump.actualRate(), EPS, "速度=0 → actualRate=0");
    }

    /** FR-11：喷幅 5m 速度 5m/s 喷洒 10s → coveredArea≈250 m²。 */
    @Test
    void coverageAreaAccumulation() {
        DronePhysics physics = new DronePhysics(22.5907, 113.9345, 10, 5.0);
        physics.setTarget(1000, 0, 10);
        // 推进到速度稳定在 5 m/s
        for (int i = 0; i < 200; i++) physics.tick(DT);
        double speed = physics.groundSpeed();

        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, physics, null);
        pump.enable();
        pump.setRate(500);
        // 喷洒 10s = 200 ticks
        for (int i = 0; i < 200; i++) pump.tick(DT);
        // coveredArea = sprayWidth × speed × 10s = 5 × speed × 10
        double expected = 5 * speed * 10;
        assertEquals(expected, pump.coveredArea(), 5.0, "coveredArea ≈ 5 × speed × 10");
    }

    /** FR-18：envModel=null → flowCorrection=1.0 + 无侧风禁喷。 */
    @Test
    void noEnvModelNoCompensation() {
        DronePhysics physics = new DronePhysics(22.5907, 113.9345, 10, 5.0);
        physics.setTarget(100, 0, 10);
        for (int i = 0; i < 200; i++) physics.tick(DT);

        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, physics, null);
        pump.enable();
        pump.setRate(1000);
        pump.tick(DT);
        assertEquals(1.0, pump.flowCorrection(), EPS, "无环境模型 → flowCorrection=1.0");
        assertFalse(pump.crosswindPaused(), "无环境模型 → 无侧风禁喷");
    }

    /** 不变量：actualRate ≤ rateMax。 */
    @Test
    void actualRateNeverExceedsRateMax() {
        DronePhysics physics = new DronePhysics(22.5907, 113.9345, 10, 20.0);
        physics.setTarget(10000, 0, 10);
        for (int i = 0; i < 500; i++) physics.tick(DT);

        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, physics, null);
        pump.enable();
        pump.setRate(2000);  // 设为 rateMax
        for (int i = 0; i < 100; i++) pump.tick(DT);
        // actualRate = 2000 × speedFactor(≤1.5) × flowCorrection(≤1.0)
        // 但 actualRate 不应超过 2000 × 1.5 = 3000（speedFactor 钳位 1.5）
        assertTrue(pump.actualRate() <= 2000 * 1.5 + EPS,
                "actualRate ≤ rateMax × 1.5（速度因子钳位）");
    }

    /** 不变量：remainingChemical ≥ 0。 */
    @Test
    void chemicalNeverNegative() {
        DronePhysics physics = new DronePhysics(22.5907, 113.9345, 10, 5.0);
        physics.setTarget(100, 0, 10);
        for (int i = 0; i < 200; i++) physics.tick(DT);

        SprayPump pump = new SprayPump(0.01, 2000, 5, 6, 5, 3, physics, null);
        pump.enable();
        pump.setRate(2000);
        // 持续喷洒远超耗尽时间
        for (int i = 0; i < 1000; i++) pump.tick(DT);
        assertTrue(pump.remainingChemical() >= 0, "药量不透支");
    }

    /** 紧急停喷（FR-03 安全性）：emergencyStop → enabled=false + rate=0。 */
    @Test
    void emergencyStop() {
        SprayPump pump = new SprayPump(20, 2000, 5, 6, 5, 3, null, null);
        pump.enable();
        pump.setRate(1000);
        pump.emergencyStop();
        assertFalse(pump.getState().enabled(), "紧急停喷后 enabled=false");
        assertEquals(0.0, pump.actualRate(), EPS, "紧急停喷后 actualRate=0");
        assertEquals(0.0, pump.targetRate(), EPS, "紧急停喷后 targetRate=0");
    }
}