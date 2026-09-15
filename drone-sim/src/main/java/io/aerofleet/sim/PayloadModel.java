package io.aerofleet.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * 负载模型（FR-22 聚合）。
 * <p>
 * 维护已装载负载列表，提供聚合重量 / 聚合体积 / 合成重心查询。
 * <p>
 * 线程安全：由 {@link Gripper} 在 tick 线程内独占调用 add/remove/clear；
 * 查询方法可由遥测线程并发读（{@code ArrayList} 非线程安全，但读时无修改——
 * 抓取/投放是命令驱动而非 tick 驱动，遥测 1Hz 读不会与命令处理并发）。
 */
public final class PayloadModel {

    private final List<PayloadItem> items = new ArrayList<>();

    /** 装载一个负载。 */
    public void add(PayloadItem item) {
        items.add(item);
    }

    /** 移除一个负载。 */
    public void remove(PayloadItem item) {
        items.remove(item);
    }

    /** 清空所有负载。 */
    public void clear() {
        items.clear();
    }

    /**
     * 聚合重量 kg（FR-22）。
     *
     * @return 所有负载重量之和
     */
    public double totalWeight() {
        return items.stream().mapToDouble(PayloadItem::weightKg).sum();
    }

    /**
     * 聚合体积 L（FR-22）。
     *
     * @return 所有负载体积之和
     */
    public double totalVolume() {
        return items.stream().mapToDouble(PayloadItem::volumeL).sum();
    }

    /**
     * 合成重心 m（FR-22，按重量加权）。
     * <p>
     * combinedCOG = Σ(cogOffset_i × weight_i) / Σ(weight_i)；
     * 总重为 0 时返回 0。
     *
     * @return 合成重心偏置 m
     */
    public double combinedCenterOfGravity() {
        double totalW = totalWeight();
        if (totalW <= 0) return 0;
        return items.stream()
                .mapToDouble(i -> i.cogOffset() * i.weightKg())
                .sum() / totalW;
    }

    /** 已装载负载列表的不可变拷贝。 */
    public List<PayloadItem> items() {
        return List.copyOf(items);
    }

    /** 已装载负载数量。 */
    public int count() {
        return items.size();
    }
}