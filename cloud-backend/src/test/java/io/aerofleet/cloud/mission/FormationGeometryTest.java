package io.aerofleet.cloud.mission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 队形几何算法单测（FR-01/FR-02，异常 5.1.3-3）。
 *
 * 覆盖 5 种队形几何拓扑正确性 + 参数校验 + 边界。
 * 纯静态方法测试，不起 Spring。
 */
class FormationGeometryTest {

    private static final double EPS = 1e-6;

    @Test
    void lineFormationCorrectSpacing() {
        // LINE + N=3 + S=5 → 3 位置相邻间距=5m，沿垂轴排列，中间机在参考点（FR-01）
        List<FormationGeometry.LocalPos> pos = FormationGeometry.compute(
                FormationGeometry.Shape.LINE, 3, 5.0, 0);
        assertEquals(3, pos.size());
        // heading=0：垂轴为 east 方向，north=0，east = (i-1)*5
        assertEquals(0, pos.get(0).north(), EPS, "LINE heading=0 north=0");
        assertEquals(-5, pos.get(0).east(), EPS, "slot0 east=-5");
        assertEquals(0, pos.get(1).east(), EPS, "slot1 (middle) at reference");
        assertEquals(5, pos.get(2).east(), EPS, "slot2 east=+5");
        // 相邻间距 = 5m
        assertEquals(5.0, dist(pos.get(0), pos.get(1)), EPS, "adjacent spacing 5m");
        assertEquals(5.0, dist(pos.get(1), pos.get(2)), EPS, "adjacent spacing 5m");
    }

    @Test
    void circleFormationUniformDistribution() {
        // CIRCLE + N=4 + S=5 → 4 位置均匀圆周，相邻弧长≈5m，圆心=参考点（FR-01）
        List<FormationGeometry.LocalPos> pos = FormationGeometry.compute(
                FormationGeometry.Shape.CIRCLE, 4, 5.0, 0);
        assertEquals(4, pos.size());
        // 圆心 = 参考点（0,0）：所有点到原点距离 = radius
        double radius = 5.0 / (2 * Math.sin(Math.PI / 4));
        for (FormationGeometry.LocalPos p : pos) {
            assertEquals(radius, Math.hypot(p.north(), p.east()), EPS,
                    "all points on circle radius");
        }
        // 相邻弦长 = 间距 5m
        for (int i = 0; i < 4; i++) {
            assertEquals(5.0, dist(pos.get(i), pos.get((i + 1) % 4)), EPS,
                    "adjacent chord = spacing");
        }
    }

    @Test
    void veeFormationSymmetric() {
        // VEE + N=5 → 顶点在前 + 两翼对称后展（FR-01）
        List<FormationGeometry.LocalPos> pos = FormationGeometry.compute(
                FormationGeometry.Shape.VEE, 5, 5.0, 0);
        assertEquals(5, pos.size());
        // heading=0：dirN=1, dirE=0, perpN=0, perpE=1
        // 顶点 (i=0) north = apexFwd = (5-1)/2 * 5 * 0.5 = 5, east = 0
        assertEquals(5.0, pos.get(0).north(), EPS, "apex forward");
        assertEquals(0.0, pos.get(0).east(), EPS, "apex centered");
        // 两翼对称：i=1(左) 与 i=2(右) 应 east 相反、north 相同
        assertEquals(pos.get(1).north(), pos.get(2).north(), EPS, "wing pair same north");
        assertEquals(-pos.get(1).east(), pos.get(2).east(), EPS, "wing pair opposite east");
        // i=3(左) 与 i=4(右) 对称
        assertEquals(pos.get(3).north(), pos.get(4).north(), EPS, "wing pair same north");
        assertEquals(-pos.get(3).east(), pos.get(4).east(), EPS, "wing pair opposite east");
        // 翼点在顶点后方（north < apex.north）
        assertTrue(pos.get(1).north() < pos.get(0).north(), "wings behind apex");
    }

    @Test
    void columnFormationAlongHeading() {
        // COLUMN + N=3 + H=90° → 沿正东方向排列（FR-01）
        List<FormationGeometry.LocalPos> pos = FormationGeometry.compute(
                FormationGeometry.Shape.COLUMN, 3, 5.0, 90);
        assertEquals(3, pos.size());
        // heading=90：dirN=cos(90)=0, dirE=sin(90)=1 → 沿 east 排列，north=0
        for (FormationGeometry.LocalPos p : pos) {
            assertEquals(0, p.north(), EPS, "COLUMN heading=90 north=0");
        }
        assertEquals(-5, pos.get(0).east(), EPS, "slot0 east=-5");
        assertEquals(0, pos.get(1).east(), EPS, "slot1 at reference");
        assertEquals(5, pos.get(2).east(), EPS, "slot2 east=+5");
    }

    @Test
    void diamondFormationFourVertices() {
        // DIAMOND + N=4 → 四顶点在主轴/垂轴上（FR-01）
        List<FormationGeometry.LocalPos> pos = FormationGeometry.compute(
                FormationGeometry.Shape.DIAMOND, 4, 5.0, 0);
        assertEquals(4, pos.size());
        // heading=0：四顶点应在 north 轴或 east 轴上（north=0 或 east=0）
        for (FormationGeometry.LocalPos p : pos) {
            boolean onAxis = Math.abs(p.north()) < EPS || Math.abs(p.east()) < EPS;
            assertTrue(onAxis, "diamond vertex on axis: " + p);
        }
    }

    @Test
    void spacingBelowMinimumRejected() {
        // S=1 → IllegalArgumentException "spacing must be >= 2m"（FR-02）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                FormationGeometry.compute(FormationGeometry.Shape.LINE, 3, 1.0, 0));
        assertTrue(ex.getMessage().contains("spacing"), "message mentions spacing");
    }

    @Test
    void headingOutOfRangeRejected() {
        // H=400 → IllegalArgumentException "heading must be 0-359"（FR-02）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                FormationGeometry.compute(FormationGeometry.Shape.LINE, 3, 5.0, 400));
        assertTrue(ex.getMessage().contains("heading"), "message mentions heading");
    }

    @Test
    void tooFewMembersRejected() {
        // N=1 → IllegalArgumentException "formation requires >= 2 members"（FR-02）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                FormationGeometry.compute(FormationGeometry.Shape.LINE, 1, 5.0, 0));
        assertTrue(ex.getMessage().contains("members"), "message mentions members");
    }

    @Test
    void veeRequiresThreeMembers() {
        // VEE + N=2 → IllegalArgumentException（异常 5.1.3-3）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                FormationGeometry.compute(FormationGeometry.Shape.VEE, 2, 5.0, 0));
        assertTrue(ex.getMessage().contains("VEE"), "message mentions VEE");
    }

    @Test
    void circleRequiresThreeMembers() {
        // CIRCLE + N=2 → IllegalArgumentException（异常 5.1.3-3）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                FormationGeometry.compute(FormationGeometry.Shape.CIRCLE, 2, 5.0, 0));
        assertTrue(ex.getMessage().contains("CIRCLE"), "message mentions CIRCLE");
    }

    @Test
    void diamondRequiresFourMembers() {
        // DIAMOND + N=3 → IllegalArgumentException（异常 5.1.3-3）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                FormationGeometry.compute(FormationGeometry.Shape.DIAMOND, 3, 5.0, 0));
        assertTrue(ex.getMessage().contains("DIAMOND"), "message mentions DIAMOND");
    }

    /** 两点间欧氏距离（本地米）。 */
    private static double dist(FormationGeometry.LocalPos a, FormationGeometry.LocalPos b) {
        return Math.hypot(a.north() - b.north(), a.east() - b.east());
    }
}