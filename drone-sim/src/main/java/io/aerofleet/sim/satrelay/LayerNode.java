package io.aerofleet.sim.satrelay;

/**
 * 层级节点抽象（M7 星-空-地多层级中继，FR-5.2，数据约束 6.3）。
 * <p>
 * 每个中继节点注册到且仅注册到一个层级（FR-5.2.1.4）。
 * L0 地面终端为层级路径端点，不承担中继转发（FR-5.2.1.6）。
 * <p>
 * 构造器校验高度落在对应层级区间（FR-5.2.1.2），L0/L4 端点层高度不校验。
 */
public final class LayerNode {

    private final int nodeId;
    private final RelayLayer layer;
    private final double altitudeM;
    private final double coverageRadiusKm;
    private volatile boolean reachable;

    /**
     * 构造器。
     *
     * @param nodeId          节点唯一标识
     * @param layer           所属层级
     * @param altitudeM       节点高度（米）
     * @param coverageRadiusKm 覆盖半径（km），必须 > 0
     * @throws IllegalArgumentException 层级为 null、高度不在区间内、覆盖半径 <= 0
     */
    public LayerNode(int nodeId, RelayLayer layer, double altitudeM, double coverageRadiusKm) {
        if (layer == null) {
            throw new IllegalArgumentException("layer must not be null");
        }
        if (layer.isRelay()) {
            if (altitudeM < layer.altitudeLowerM() || altitudeM > layer.altitudeUpperM()) {
                throw new IllegalArgumentException(
                        "altitudeM=" + altitudeM + " not in layer " + layer
                                + " range [" + layer.altitudeLowerM() + ", " + layer.altitudeUpperM() + "]");
            }
        }
        if (coverageRadiusKm <= 0) {
            throw new IllegalArgumentException("coverageRadiusKm must be > 0, got " + coverageRadiusKm);
        }
        this.nodeId = nodeId;
        this.layer = layer;
        this.altitudeM = altitudeM;
        this.coverageRadiusKm = coverageRadiusKm;
        this.reachable = false;
    }

    public int nodeId() {
        return nodeId;
    }

    public RelayLayer layer() {
        return layer;
    }

    public double altitudeM() {
        return altitudeM;
    }

    public double coverageRadiusKm() {
        return coverageRadiusKm;
    }

    public boolean isReachable() {
        return reachable;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    /** 是否为端点层（L0/L4），不承担中继转发。 */
    public boolean isEndpoint() {
        return layer.isEndpoint();
    }

    @Override
    public String toString() {
        return "LayerNode{id=" + nodeId + ", layer=" + layer + ", alt=" + altitudeM + "m"
                + ", cov=" + coverageRadiusKm + "km, reachable=" + reachable + "}";
    }
}