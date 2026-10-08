package io.aerofleet.cloud.defect;

/** 缺陷严重度（对表国网机巡口径，P0 最高）。 */
public enum DefectSeverity {
    P0, P1, P2, P3;

    /** kind×confidence → 严重度。人工可改，此映射是立案缺省值。 */
    public static DefectSeverity classify(String kind, double confidence) {
        // 无类别先验时按置信度分档：≥0.9 P1、≥0.75 P2、其余 P3。
        // 有类别先验（如 person/vehicle 等已知高价值目标）再加权——
        // PoC 检测器只有 blob/person/vehicle 三类，统一走置信度分档，
        // 避免为不存在类别编造先验。
        if (confidence >= 0.9) {
            return P1;
        }
        if (confidence >= 0.75) {
            return P2;
        }
        return P3;
    }
}
