package io.aerofleet.cloud.mission;

import java.util.ArrayList;
import java.util.List;

/**
 * 配送站点序列（FR-23~FR-25）。
 * <p>
 * 维护有序站点列表与状态推进：
 * <ul>
 *   <li>{@link #onPositionUpdate(double, double)}：无人机位置更新，到达站点半径内 → PENDING→EN_ROUTE（FR-24）</li>
 *   <li>{@link #markDropped(int)}：投放成功 → EN_ROUTE→DROPPED（FR-24）</li>
 *   <li>{@link #markSkipped(int)}：超时/失败 → 任意状态→SKIPPED（FR-24）</li>
 *   <li>{@link #skipRemaining()}：任务结束剩余 PENDING → SKIPPED + COMPLETED_WITH_SKIPS（FR-25 异常场景）</li>
 * </ul>
 *
 * <p>线程安全：sites 为 ArrayList，由 {@link DeliveryService} 在 REST 线程写；
 * 查询方法返回不可变拷贝。
 */
public final class DeliverySequence {

    /** 配送任务状态机。 */
    public enum State { PENDING, RUNNING, COMPLETED, COMPLETED_WITH_SKIPS, FAILED }

    /** 地球半径 m（Haversine 公式用）。 */
    private static final double EARTH_R = 6371000.0;

    private final int deliveryId;
    private final int targetSysid;
    private final List<DeliverySite> sites;
    private volatile int currentIndex = 0;
    private volatile State state = State.PENDING;

    public DeliverySequence(int deliveryId, int targetSysid, List<DeliverySite> sites) {
        this.deliveryId = deliveryId;
        this.targetSysid = targetSysid;
        this.sites = new ArrayList<>(sites);
    }

    // ---- 查询方法（FR-25）----

    public int deliveryId() { return deliveryId; }
    public int targetSysid() { return targetSysid; }
    public int currentIndex() { return currentIndex; }
    public State state() { return state; }

    /** 站点列表的不可变拷贝。 */
    public List<DeliverySite> sites() {
        synchronized (sites) {
            return List.copyOf(sites);
        }
    }

    public int siteCount() { return sites.size(); }

    /** 总体进度 = DROPPED 站点数 / 总站点数 × 100（FR-25）。 */
    public double progress() {
        if (sites.isEmpty()) return 0;
        long dropped = sites.stream()
                .filter(s -> s.state() == DeliverySiteState.DROPPED).count();
        return (double) dropped / sites.size() * 100;
    }

    /** 剩余未投放站点数（PENDING + EN_ROUTE）。 */
    public int remainingSites() {
        return (int) sites.stream()
                .filter(s -> s.state() == DeliverySiteState.PENDING
                        || s.state() == DeliverySiteState.EN_ROUTE).count();
    }

    // ---- 状态推进方法（FR-24）----

    /** 启动配送任务：PENDING → RUNNING。 */
    public void start() {
        if (state == State.PENDING) {
            state = State.RUNNING;
        }
    }

    /**
     * 位置更新回调（FR-24）。
     * <p>
     * 当前站点为 PENDING 且距离 ≤ dropAccuracy → 转 EN_ROUTE + 记录到达时间。
     *
     * @param lat 无人机纬度
     * @param lon 无人机经度
     */
    public void onPositionUpdate(double lat, double lon) {
        if (state != State.RUNNING) return;
        if (currentIndex >= sites.size()) return;
        synchronized (sites) {
            DeliverySite site = sites.get(currentIndex);
            if (site.state() == DeliverySiteState.PENDING) {
                double dist = haversine(lat, lon, site.lat(), site.lon());
                if (dist <= site.dropAccuracyM()) {
                    sites.set(currentIndex, site
                            .withState(DeliverySiteState.EN_ROUTE)
                            .withArriveTime(System.currentTimeMillis()));
                }
            }
        }
    }

    /**
     * 标记站点投放成功（FR-24）。
     * <p>
     * EN_ROUTE → DROPPED + 记录投放时间 + 推进 currentIndex；
     * 全部站点 DROPPED → state = COMPLETED。
     *
     * @param index 站点序号
     * @return true 成功；false 站点不存在或非 EN_ROUTE
     */
    public boolean markDropped(int index) {
        if (index < 0 || index >= sites.size()) return false;
        synchronized (sites) {
            DeliverySite site = sites.get(index);
            if (site.state() != DeliverySiteState.EN_ROUTE) return false;
            sites.set(index, site
                    .withState(DeliverySiteState.DROPPED)
                    .withDropTime(System.currentTimeMillis()));
            // 推进 currentIndex 到下一个未完成站点
            while (currentIndex < sites.size()
                    && sites.get(currentIndex).state() == DeliverySiteState.DROPPED) {
                currentIndex++;
            }
            // 检查是否全部完成
            boolean allDropped = sites.stream()
                    .allMatch(s -> s.state() == DeliverySiteState.DROPPED);
            if (allDropped) {
                state = State.COMPLETED;
            }
            return true;
        }
    }

    /**
     * 标记站点跳过（FR-24，超时/失败）。
     * <p>
     * 任意状态 → SKIPPED + 推进 currentIndex。
     *
     * @param index 站点序号
     * @return true 成功；false 站点不存在
     */
    public boolean markSkipped(int index) {
        if (index < 0 || index >= sites.size()) return false;
        synchronized (sites) {
            DeliverySite site = sites.get(index);
            sites.set(index, site.withState(DeliverySiteState.SKIPPED));
            while (currentIndex < sites.size()
                    && (sites.get(currentIndex).state() == DeliverySiteState.DROPPED
                    || sites.get(currentIndex).state() == DeliverySiteState.SKIPPED)) {
                currentIndex++;
            }
            return true;
        }
    }

    /**
     * 跳过所有剩余 PENDING 站点（FR-25 异常场景：任务结束仍有 PENDING → SKIPPED）。
     * <p>
     * 若有站点被跳过 → state = COMPLETED_WITH_SKIPS；否则 → COMPLETED。
     */
    public void skipRemaining() {
        boolean anySkipped = false;
        synchronized (sites) {
            for (int i = 0; i < sites.size(); i++) {
                DeliverySite site = sites.get(i);
                if (site.state() == DeliverySiteState.PENDING
                        || site.state() == DeliverySiteState.EN_ROUTE) {
                    sites.set(i, site.withState(DeliverySiteState.SKIPPED));
                    anySkipped = true;
                }
            }
            state = anySkipped ? State.COMPLETED_WITH_SKIPS : State.COMPLETED;
        }
    }

    /** 标记失败。 */
    public void fail() {
        state = State.FAILED;
    }

    // ---- Haversine 公式 ----

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}