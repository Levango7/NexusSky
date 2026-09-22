package io.aerofleet.sim.mesh;

/**
 * 动态跳数上限（灾害应急通讯组网，FR-18）。
 * <p>
 * MAX_HOPS 根据网络规模动态调整：
 * <ul>
 *   <li>节点数 ≤ 20：MAX_HOPS = 15</li>
 *   <li>节点数 21-50：MAX_HOPS = 20</li>
 *   <li>节点数 > 50：MAX_HOPS = 25（但不超过 30）</li>
 * </ul>
 * 不可变值对象。
 */
public final class DynamicMaxHops {

    /** 节点数 ≤ 20 时的 MAX_HOPS。 */
    public static final int SMALL_NETWORK_HOPS = 15;
    /** 节点数 21-50 时的 MAX_HOPS。 */
    public static final int MEDIUM_NETWORK_HOPS = 20;
    /** 节点数 > 50 时的 MAX_HOPS。 */
    public static final int LARGE_NETWORK_HOPS = 25;
    /** MAX_HOPS 绝对上限。 */
    public static final int ABSOLUTE_MAX_HOPS = 30;

    /** 小网络节点数阈值。 */
    public static final int SMALL_NETWORK_THRESHOLD = 20;
    /** 中网络节点数阈值。 */
    public static final int MEDIUM_NETWORK_THRESHOLD = 50;

    /**
     * 根据节点数计算 MAX_HOPS（FR-18）。
     *
     * @param nodeCount 当前网络节点数
     * @return 动态 MAX_HOPS 值
     */
    public static int calculateMaxHops(int nodeCount) {
        if (nodeCount <= SMALL_NETWORK_THRESHOLD) {
            return SMALL_NETWORK_HOPS;
        } else if (nodeCount <= MEDIUM_NETWORK_THRESHOLD) {
            return MEDIUM_NETWORK_HOPS;
        } else {
            return LARGE_NETWORK_HOPS;
        }
    }

    /**
     * 计算并限制 MAX_HOPS 不超过绝对上限。
     *
     * @param nodeCount 当前网络节点数
     * @return 限制后的 MAX_HOPS 值
     */
    public static int calculateMaxHopsCapped(int nodeCount) {
        return Math.min(calculateMaxHops(nodeCount), ABSOLUTE_MAX_HOPS);
    }

    /**
     * 判断节点数变化是否需要调整 MAX_HOPS。
     *
     * @param oldNodeCount 旧节点数
     * @param newNodeCount 新节点数
     * @return true 若 MAX_HOPS 需要调整
     */
    public static boolean needsAdjustment(int oldNodeCount, int newNodeCount) {
        return calculateMaxHops(oldNodeCount) != calculateMaxHops(newNodeCount);
    }

    /**
     * 获取网络规模分级。
     *
     * @param nodeCount 节点数
     * @return "SMALL" / "MEDIUM" / "LARGE"
     */
    public static String getNetworkScale(int nodeCount) {
        if (nodeCount <= SMALL_NETWORK_THRESHOLD) {
            return "SMALL";
        } else if (nodeCount <= MEDIUM_NETWORK_THRESHOLD) {
            return "MEDIUM";
        } else {
            return "LARGE";
        }
    }
}