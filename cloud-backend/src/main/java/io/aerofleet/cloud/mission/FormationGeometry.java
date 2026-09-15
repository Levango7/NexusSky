package io.aerofleet.cloud.mission;

import java.util.ArrayList;
import java.util.List;

/**
 * 队形几何算法（FR-01/FR-02，纯几何无状态静态工具类）。
 *
 * 给定队形类型 + 机间距 + 方向 + 飞机数量 → 返回 N 个本地米偏移（north, east），
 * 再经 {@code GeoUtil.latOf/lonOf} 转为经纬度。所有队形以参考点为中心，沿 heading 方向定向。
 *
 * 覆盖五种队形：LINE/COLUMN/VEE/CIRCLE/DIAMOND。
 *
 * 参数约束（FR-02）：
 *   - spacingM >= {@link #MIN_SPACING_M}（默认 2m，安全下界，DFX 4.3）
 *   - headingDeg ∈ [0, 359]
 *   - n >= 2（编队最小规模）；VEE 要求 n >= 3，CIRCLE 要求 n >= 3，DIAMOND 要求 n >= 4
 *
 * 性能（DFX 4.1）：<50ms（纯算术，无 IO）。
 */
public final class FormationGeometry {

    /** 安全下界：机间距最小值（DFX 4.3）。 */
    static final double MIN_SPACING_M = 2.0;

    private FormationGeometry() {
    }

    /** 队形类型。 */
    public enum Shape { LINE, COLUMN, VEE, CIRCLE, DIAMOND }

    /** 本地米偏移（north, east），相对于参考点。 */
    public record LocalPos(double north, double east) {}

    /**
     * 计算 N 个目标位置的本地米偏移。
     *
     * @param shape      队形类型
     * @param n          飞机数量（>= 2；VEE/CIRCLE >= 3；DIAMOND >= 4）
     * @param spacingM   机间距（>= 2m）
     * @param headingDeg 队形方向（0-359°）
     * @return N 个 LocalPos，按 slotIndex 0..N-1 排列
     * @throws IllegalArgumentException 参数非法（FR-02）
     */
    public static List<LocalPos> compute(Shape shape, int n, double spacingM, double headingDeg) {
        // FR-02 参数校验
        if (n < 2) {
            throw new IllegalArgumentException("formation requires >= 2 members");
        }
        if (spacingM < MIN_SPACING_M) {
            throw new IllegalArgumentException("spacing must be >= " + MIN_SPACING_M + "m");
        }
        if (headingDeg < 0 || headingDeg > 359) {
            throw new IllegalArgumentException("heading must be 0-359");
        }
        double hRad = Math.toRadians(headingDeg);
        return switch (shape) {
            case LINE    -> line(n, spacingM, hRad);
            case COLUMN  -> column(n, spacingM, hRad);
            case VEE     -> vee(n, spacingM, hRad);
            case CIRCLE  -> circle(n, spacingM, hRad);
            case DIAMOND -> diamond(n, spacingM, hRad);
        };
    }

    /**
     * LINE（横列）：沿垂轴排列，参考点为中心。
     * offset = (i - (n-1)/2.0) * S
     */
    private static List<LocalPos> line(int n, double s, double hRad) {
        double perpN = -Math.sin(hRad), perpE = Math.cos(hRad);
        List<LocalPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double offset = (i - (n - 1) / 2.0) * s;
            out.add(new LocalPos(offset * perpN, offset * perpE));
        }
        return out;
    }

    /**
     * COLUMN（纵列）：沿主轴排列，参考点为中心。
     */
    private static List<LocalPos> column(int n, double s, double hRad) {
        double dirN = Math.cos(hRad), dirE = Math.sin(hRad);
        List<LocalPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double offset = (i - (n - 1) / 2.0) * s;
            out.add(new LocalPos(offset * dirN, offset * dirE));
        }
        return out;
    }

    /**
     * VEE（V形）：顶点在参考点前方，两翼对称后展（45°夹角）。
     * 要求 n >= 3。顶点 (i=0) 前移 (n-1)/2 * S * 0.5，奇数左翼偶数右翼。
     */
    private static List<LocalPos> vee(int n, double s, double hRad) {
        if (n < 3) {
            throw new IllegalArgumentException("VEE formation requires >= 3 members");
        }
        double dirN = Math.cos(hRad), dirE = Math.sin(hRad);
        double perpN = -Math.sin(hRad), perpE = Math.cos(hRad);
        double apexFwd = ((n - 1) / 2.0) * s * 0.5;  // 顶点前移量
        List<LocalPos> out = new ArrayList<>(n);
        out.add(new LocalPos(apexFwd * dirN, apexFwd * dirE));  // 顶点 (i=0)
        for (int i = 1; i < n; i++) {
            int side = (i % 2 == 1) ? -1 : 1;  // 奇数左翼，偶数右翼
            int depth = (i + 1) / 2;            // 向后层数
            double back = depth * s;
            double lateral = depth * s;         // 45°夹角：横向=纵向
            double north = apexFwd * dirN - back * dirN + lateral * side * perpN;
            double east  = apexFwd * dirE - back * dirE + lateral * side * perpE;
            out.add(new LocalPos(north, east));
        }
        return out;
    }

    /**
     * CIRCLE（圆环）：均匀分布圆周，半径由弦长=间距反推。
     * 要求 n >= 3。radius = S / (2 * sin(PI / n))。
     */
    private static List<LocalPos> circle(int n, double s, double hRad) {
        if (n < 3) {
            throw new IllegalArgumentException("CIRCLE formation requires >= 3 members");
        }
        double radius = s / (2 * Math.sin(Math.PI / n));
        List<LocalPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double angle = 2 * Math.PI * i / n + hRad;  // 从 heading 方向起
            out.add(new LocalPos(radius * Math.cos(angle), radius * Math.sin(angle)));
        }
        return out;
    }

    /**
     * DIAMOND（菱形）：四个顶点在主轴/垂轴上，边按间距 S 采样取前 N 个。
     * 要求 n >= 4。半对角线 = S * ceil(N/4)。
     */
    private static List<LocalPos> diamond(int n, double s, double hRad) {
        if (n < 4) {
            throw new IllegalArgumentException("DIAMOND formation requires >= 4 members");
        }
        double dirN = Math.cos(hRad), dirE = Math.sin(hRad);
        double perpN = -Math.sin(hRad), perpE = Math.cos(hRad);
        double halfDiag = s * Math.ceil(n / 4.0);  // 半对角线长度

        // 四顶点（本地 north-east，未旋转）：
        //   top   = (0, +halfDiag)     // 前方
        //   right = (+halfDiag, 0)     // 右侧
        //   bottom= (0, -halfDiag)     // 后方
        //   left  = (-halfDiag, 0)     // 左侧
        // 沿四边按弧长 S 采样，收集点列表，取前 N 个（以最接近参考点前方为起点）。
        // 每条边长 = halfDiag * sqrt(2)（菱形对角线垂直，边长 = sqrt(halfDiag^2 + halfDiag^2)）。
        double edgeLen = halfDiag * Math.sqrt(2);
        int samplesPerEdge = Math.max(1, (int) Math.round(edgeLen / s));

        // 四顶点（本地坐标，north=前，east=右）
        double[][] vertices = {
                {0, halfDiag},      // top（前方）
                {halfDiag, 0},      // right
                {0, -halfDiag},     // bottom
                {-halfDiag, 0}      // left
        };

        List<LocalPos> out = new ArrayList<>(n);
        // 第一个点是顶点 top
        out.add(rotateAndAdd(vertices[0][0], vertices[0][1], dirN, dirE, perpN, perpE));
        // 沿四边采样：top→right→bottom→left→top
        for (int edge = 0; edge < 4 && out.size() < n; edge++) {
            double[] from = vertices[edge];
            double[] to = vertices[(edge + 1) % 4];
            for (int k = 1; k <= samplesPerEdge && out.size() < n; k++) {
                double t = (double) k / samplesPerEdge;
                double north = from[0] + (to[0] - from[0]) * t;
                double east = from[1] + (to[1] - from[1]) * t;
                out.add(rotateAndAdd(north, east, dirN, dirE, perpN, perpE));
            }
        }
        // 保证恰好返回 n 个点（截断或补齐）
        while (out.size() > n) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * 将本地 (north, east) 按 heading 方向旋转后添加到输出。
     * 旋转矩阵：[dirN, perpN; dirE, perpE] 把本地"前方"对齐到 heading 方向。
     */
    private static LocalPos rotateAndAdd(double north, double east,
                                         double dirN, double dirE,
                                         double perpN, double perpE) {
        double rNorth = north * dirN + east * perpN;
        double rEast  = north * dirE + east * perpE;
        return new LocalPos(rNorth, rEast);
    }
}