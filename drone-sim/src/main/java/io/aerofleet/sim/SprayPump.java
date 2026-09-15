package io.aerofleet.sim;

/**
 * 喷洒泵实现（FR-07~FR-11, FR-16~FR-18）。
 * <p>
 * 实现 {@link Actuator} 通用契约，持药量/覆盖率/漂移补偿参数；
 * 由 {@link VirtualDrone#tickOnce} 在物理 tick 后调用 {@link #tick(double)} 驱动：
 * <ol>
 *   <li>读取 {@link DronePhysics#groundSpeed()} 做速度耦合（FR-10）</li>
 *   <li>读取 {@link EnvironmentModel#windVector()} 做漂移补偿 + 侧风禁喷判定（FR-16/17/18）</li>
 *   <li>计算 actualRate = rate × speedFactor × flowCorrection</li>
 *   <li>药量消耗 Math.max(0, remaining - actualRate×dt)（FR-07/08 不透支）</li>
 *   <li>覆盖率累积 coveredArea += sprayWidth × speed × dt（FR-11）</li>
 * </ol>
 *
 * <p>线程安全：所有 mutable 字段 volatile；tick 由 VirtualDrone tick 线程独占调用，
 * 遥测线程经 {@link #getState()}/化学百分比等只读方法并发读。
 *
 * <p>不变量（见 spec.md §6.2）：
 * <ul>
 *   <li>{@code remainingChemical ≥ 0}（药量不透支，tick 中 Math.max(0, ...) 钳位）</li>
 *   <li>{@code actualRate ≤ rateMax}（setRate 钳位 [0, rateMax]）</li>
 *   <li>{@code flowCorrection > 0}（1/(1+windSpeed/refWindSpeed) 恒正）</li>
 * </ul>
 */
public final class SprayPump implements Actuator {

    // ---- 配置（构造期确定，不可变）----
    private final double capacityMl;        // 药箱容量 mL
    private final double rateMax;           // 最大流量 mL/s
    private final double sprayWidth;        // 喷幅 m
    private final double crosswindMax;      // 侧风禁喷阈值 m/s
    private final double refSpeed;          // 参考速度 m/s（流量耦合基准）
    private final double refWindSpeed;      // 参考风速 m/s（漂移补偿基准）

    // ---- 运行时状态（volatile，tick 线程写 / 遥测线程读）----
    private volatile boolean enabled = false;
    private volatile double rate = 0;           // 当前目标流量 mL/s
    private volatile double actualRate = 0;     // 实际流量（经速度+风修正后）
    private volatile double remainingChemical;  // 剩余药量 mL
    private volatile double coveredArea = 0;    // 已喷洒面积 m²
    private volatile double totalArea = 0;      // 任务总面积 m²
    private volatile double driftOffsetAngle = 0;  // 漂移补偿偏置角 deg
    private volatile double flowCorrection = 1.0;  // 流量修正系数
    private volatile boolean lowChemical = false;
    private volatile boolean crosswindPaused = false;

    // ---- 外部依赖（由 VirtualDrone 注入，可为 null）----
    private final DronePhysics physics;
    private final EnvironmentModel envModel;

    /**
     * 构造喷洒泵。
     *
     * @param capacityL    药箱容量 L（FR-07：remainingChemical 初始 = capacity × 1000）
     * @param rateMax      最大流量 mL/s（FR-05：setRate 钳位上界）
     * @param sprayWidth   喷幅 m（FR-11：覆盖率累积用）
     * @param crosswindMax 侧风禁喷阈值 m/s（FR-17，默认 6）
     * @param refSpeed     参考速度 m/s（FR-10：流量速度耦合基准，默认 5）
     * @param refWindSpeed 参考风速 m/s（FR-16：漂移补偿基准，默认 3）
     * @param physics      物理引擎（提供 groundSpeed，可为 null）
     * @param envModel     环境模型（提供 windVector，可为 null → FR-18 不补偿）
     */
    public SprayPump(double capacityL, double rateMax, double sprayWidth,
                     double crosswindMax, double refSpeed, double refWindSpeed,
                     DronePhysics physics, EnvironmentModel envModel) {
        this.capacityMl = capacityL * 1000.0;
        this.rateMax = rateMax;
        this.sprayWidth = sprayWidth;
        this.crosswindMax = crosswindMax;
        this.refSpeed = refSpeed;
        this.refWindSpeed = refWindSpeed;
        this.physics = physics;
        this.envModel = envModel;
        this.remainingChemical = this.capacityMl;
    }

    /**
     * 每个 tick 驱动喷洒泵（由 VirtualDrone.tickOnce 在物理 tick 后调用）。
     * <p>
     * FR-10 流量随速度调节 + FR-16 漂移补偿 + FR-17 侧风禁喷 + FR-07 药量追踪 + FR-11 覆盖率。
     *
     * @param dt 步长秒（VirtualDrone.TICK_MS / 1000.0 = 0.05s）
     */
    public void tick(double dt) {
        if (!enabled || rate <= 0) {
            actualRate = 0;
            return;
        }

        // 1. 读取速度（FR-10）
        double speed = physics != null ? physics.groundSpeed() : 0;
        double speedFactor = clamp(speed / refSpeed, 0, 1.5);

        // 2. 漂移补偿 + 侧风禁喷（FR-16/17/18）
        if (envModel != null && envModel.isEnabled()) {
            double[] wind = envModel.windVector();
            double windSpeed = Math.hypot(wind[0], wind[1]);
            // 侧风禁喷（FR-17）
            if (windSpeed > crosswindMax) {
                actualRate = 0;
                crosswindPaused = true;
                return;  // 暂停喷洒，不消耗药量
            }
            crosswindPaused = false;
            // 漂移补偿（FR-16）：偏置角 + 流量修正系数
            driftOffsetAngle = Math.toDegrees(Math.atan2(wind[1], wind[0]));
            flowCorrection = 1.0 / (1.0 + windSpeed / refWindSpeed);
        } else {
            // FR-18 无环境模型时不补偿
            flowCorrection = 1.0;
            crosswindPaused = false;
        }

        // 3. 实际流量 = 目标流量 × 速度因子 × 漂移修正
        actualRate = rate * speedFactor * flowCorrection;

        // 4. 药量消耗（FR-07/08 不透支）
        double consume = actualRate * dt;
        remainingChemical = Math.max(0, remainingChemical - consume);
        if (remainingChemical <= 0) {
            actualRate = 0;
            rate = 0;
            lowChemical = true;  // FR-08 耗尽自动停喷
        }

        // 5. 覆盖率累积（FR-11）
        coveredArea += sprayWidth * speed * dt;
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
        rate = 0;
        actualRate = 0;
    }

    @Override
    public void setRate(double r) {
        rate = clamp(r, 0, rateMax);
    }

    @Override
    public ActuatorStatus getState() {
        ActuatorState s = !enabled ? ActuatorState.DISABLED
                         : actualRate > 0 ? ActuatorState.ACTIVE
                         : ActuatorState.IDLE;
        return new ActuatorStatus(enabled, actualRate, s);
    }

    // ---- 喷洒泵专属查询 ----

    /** 药量百分比（FR-09 告警判定用）。 */
    public double chemicalPercent() {
        return capacityMl > 0 ? remainingChemical / capacityMl * 100 : 0;
    }

    /** 覆盖率百分比（FR-11）。 */
    public double coveragePercent() {
        return totalArea > 0 ? coveredArea / totalArea * 100 : 0;
    }

    /** 剩余药量 mL。 */
    public double remainingChemical() {
        return remainingChemical;
    }

    /** 已喷洒面积 m²。 */
    public double coveredArea() {
        return coveredArea;
    }

    /** 药箱容量 mL。 */
    public double capacityMl() {
        return capacityMl;
    }

    /** 最大流量 mL/s。 */
    public double rateMax() {
        return rateMax;
    }

    /** 漂移补偿偏置角 deg。 */
    public double driftOffsetAngle() {
        return driftOffsetAngle;
    }

    /** 流量修正系数（0, 1.5]。 */
    public double flowCorrection() {
        return flowCorrection;
    }

    /** 实际流量 mL/s（经速度+风修正后）。 */
    public double actualRate() {
        return actualRate;
    }

    /** 目标流量 mL/s（setRate 设定值）。 */
    public double targetRate() {
        return rate;
    }

    /** 是否药量低（FR-08 标志）。 */
    public boolean lowChemical() {
        return lowChemical;
    }

    /** 是否因侧风暂停喷洒（FR-17）。 */
    public boolean crosswindPaused() {
        return crosswindPaused;
    }

    /** 喷幅 m。 */
    public double sprayWidth() {
        return sprayWidth;
    }

    // ---- 任务参数注入 ----

    /** 设置任务总面积 m²（由 SprayTask 规划注入，用于覆盖率计算）。 */
    public void setTotalArea(double totalArea) {
        this.totalArea = totalArea;
    }

    // ---- 紧急控制 ----

    /** 紧急停喷（FR-03 安全性）：立即关闭喷洒泵并清零流量。 */
    public void emergencyStop() {
        rate = 0;
        actualRate = 0;
        enabled = false;
    }

    /** 重置药量低标志（药箱补液后由调用方调用）。 */
    public void clearLowChemical() {
        lowChemical = false;
    }

    // ---- 辅助 ----

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}