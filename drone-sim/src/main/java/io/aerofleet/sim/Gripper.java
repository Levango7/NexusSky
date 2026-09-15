package io.aerofleet.sim;

/**
 * 抛投器实现（FR-19~FR-21）。
 * <p>
 * 实现 {@link Actuator} 通用契约，状态机管理抓取/投放周期：
 * <pre>
 *   IDLE → GRABBING → HOLDING → RELEASING → RELEASED → IDLE
 * </pre>
 * 每个转移由对应命令驱动，超时（默认 2s）转 {@link GripperState#FAULT}。
 *
 * <p>线程安全：所有 mutable 字段 volatile；tick 由 VirtualDrone tick 线程独占调用，
 * 命令处理（grab/release/reset）由 handleCommandLong 在 tick 线程内调用
 * （VirtualDrone.onFrame 在 synchronized(this) 块内调 handleCommandLong，
 * 与 tickOnce 同锁，故命令与 tick 串行执行，无并发风险）。
 *
 * <p>不变量（见 spec.md §6.2）：
 * <ul>
 *   <li>{@code state ∈ {IDLE, GRABBING, HOLDING, RELEASING, RELEASED, FAULT}}（状态机合法）</li>
 *   <li>{@code payloadWeight ≤ payloadMax}（grab 前校验，超重拒绝）</li>
 * </ul>
 */
public final class Gripper implements Actuator {

    private final double payloadMax;       // 最大负载 kg
    private final long timeoutMs = 2000;   // 抓取/投放超时 2s（FR-19）
    private final PayloadModel payload = new PayloadModel();

    private volatile boolean enabled = false;
    private volatile GripperState state = GripperState.IDLE;
    private volatile long stateEnterMs = 0;
    private volatile PayloadItem currentItem = null;
    private volatile double dropLat = 0;
    private volatile double dropLon = 0;
    private volatile long dropTimeMs = 0;

    /**
     * 构造抛投器。
     *
     * @param payloadMax 最大负载 kg（FR-20：grab 校验上界）
     */
    public Gripper(double payloadMax) {
        this.payloadMax = payloadMax;
    }

    /**
     * tick 检测超时（FR-19）。
     * <p>
     * 由 VirtualDrone.tickOnce 在物理 tick 后调用；
     * 检测 GRABBING/RELEASING 状态超时 → 转 FAULT。
     *
     * @param dt 步长秒（未使用，超时用墙钟判定）
     */
    public void tick(double dt) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if ((state == GripperState.GRABBING || state == GripperState.RELEASING)
                && now - stateEnterMs > timeoutMs) {
            state = GripperState.FAULT;  // 超时转 FAULT
        }
    }

    /**
     * 抓取负载（FR-20）。
     * <p>
     * 校验 enabled && state==IDLE && weight ≤ payloadMax →
     * GRABBING→HOLDING + payload.add(item)；
     * 超重/状态不符返回 false。
     *
     * @param item 待抓取负载
     * @return true 抓取成功；false 拒绝（未使能/非 IDLE/超重）
     */
    public boolean grab(PayloadItem item) {
        if (!enabled || state != GripperState.IDLE) return false;
        if (item.weightKg() > payloadMax) return false;  // 超重拒绝
        state = GripperState.GRABBING;
        stateEnterMs = System.currentTimeMillis();
        // 模拟抓取成功（假数据源，FR-19 状态机瞬时转移）
        state = GripperState.HOLDING;
        currentItem = item;
        payload.add(item);
        return true;
    }

    /**
     * 投放负载（FR-21）。
     * <p>
     * 校验 enabled && state==HOLDING →
     * RELEASING→RELEASED→IDLE + 记录投放位置/时间 + payload.remove + 清空 currentItem；
     * 非 HOLDING 投放返回 false。
     *
     * @param lat 投放位置纬度
     * @param lon 投放位置经度
     * @return true 投放成功；false 拒绝（未使能/非 HOLDING）
     */
    public boolean release(double lat, double lon) {
        if (!enabled || state != GripperState.HOLDING) return false;
        state = GripperState.RELEASING;
        stateEnterMs = System.currentTimeMillis();
        // 模拟投放成功（假数据源，FR-19 状态机瞬时转移）
        state = GripperState.RELEASED;
        dropLat = lat;
        dropLon = lon;
        dropTimeMs = System.currentTimeMillis();
        if (currentItem != null) {
            payload.remove(currentItem);
        }
        currentItem = null;
        state = GripperState.IDLE;  // 回到 IDLE 等待下次抓取
        return true;
    }

    /** 复位（从 FAULT 恢复到 IDLE，FR-19 异常场景）。 */
    public void reset() {
        state = GripperState.IDLE;
        currentItem = null;
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
    }

    @Override
    public void setRate(double r) {
        // 抛投器无流量概念，空实现（FR-02 契约要求）
    }

    @Override
    public ActuatorStatus getState() {
        ActuatorState s = !enabled ? ActuatorState.DISABLED
                         : state == GripperState.FAULT ? ActuatorState.FAULT
                         : state == GripperState.IDLE ? ActuatorState.IDLE
                         : ActuatorState.ACTIVE;
        return new ActuatorStatus(enabled, 0, s);
    }

    // ---- 抛投器专属查询 ----

    /** 抛投器状态机当前状态。 */
    public GripperState gripperState() {
        return state;
    }

    /** 负载模型（聚合查询用）。 */
    public PayloadModel payload() {
        return payload;
    }

    /** 最大负载 kg。 */
    public double payloadMax() {
        return payloadMax;
    }

    /** 当前抓取的负载（HOLDING 状态下非 null）。 */
    public PayloadItem currentItem() {
        return currentItem;
    }

    /** 最近一次投放位置纬度。 */
    public double dropLat() {
        return dropLat;
    }

    /** 最近一次投放位置经度。 */
    public double dropLon() {
        return dropLon;
    }

    /** 最近一次投放时间 epoch ms。 */
    public long dropTimeMs() {
        return dropTimeMs;
    }
}