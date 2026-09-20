package io.aerofleet.cloud.show;

import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FormationService} 单测。
 * <p>
 * 测试 8 种队形类型的位置坐标生成、队形定义管理。
 */
@DisplayName("FormationService 队形位置生成 (P4-2)")
class FormationServiceTest {

    private FormationService formationService;

    @BeforeEach
    void setUp() {
        formationService = new FormationService();
    }

    // ---- 队形定义管理 ----

    @Test
    @DisplayName("创建队形定义并查询")
    void createAndQueryFormation() {
        FormationDefinition f = formationService.createFormation(
                "直线队形", FormationType.LINE, 5, 10.0, null);
        assertThat(f.getId()).isNotBlank();
        assertThat(f.getName()).isEqualTo("直线队形");
        assertThat(f.getType()).isEqualTo(FormationType.LINE);
        assertThat(f.getDroneCount()).isEqualTo(5);
        assertThat(f.getSpacingM()).isEqualTo(10.0);

        FormationDefinition retrieved = formationService.getFormation(f.getId());
        assertThat(retrieved).isSameAs(f);
    }

    @Test
    @DisplayName("列出所有队形定义")
    void listFormations() {
        formationService.createFormation("队形1", FormationType.LINE, 5, 10.0, null);
        formationService.createFormation("队形2", FormationType.CIRCLE, 8, 5.0, null);
        List<FormationDefinition> list = formationService.listFormations();
        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("查询不存在的队形抛 NotFoundException")
    void getFormationNotFound() {
        assertThatThrownBy(() -> formationService.getFormation("nonexistent"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("创建队形参数校验")
    void createFormationValidation() {
        assertThatThrownBy(() -> formationService.createFormation("", FormationType.LINE, 5, 10.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> formationService.createFormation("test", null, 5, 10.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> formationService.createFormation("test", FormationType.LINE, 0, 10.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> formationService.createFormation("test", FormationType.LINE, 5, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- LINE 直线队形 ----

    @Test
    @DisplayName("LINE 直线队形：5架无人机沿X轴等间距排列")
    void lineFormation() {
        List<double[]> positions = formationService.computePositions(
                FormationType.LINE, 5, 10.0, null);
        assertThat(positions).hasSize(5);
        // 中心对称：总宽度 = 4 * 10 = 40m，起点 = -20m
        assertThat(positions.get(0)[0]).isCloseTo(-20.0, within(0.001));
        assertThat(positions.get(4)[0]).isCloseTo(20.0, within(0.001));
        // 所有 Y 坐标为 0
        for (double[] pos : positions) {
            assertThat(pos[1]).isEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("LINE 直线队形：间距正确")
    void lineFormationSpacing() {
        List<double[]> positions = formationService.computePositions(
                FormationType.LINE, 3, 5.0, null);
        assertThat(positions).hasSize(3);
        double dist01 = Math.abs(positions.get(1)[0] - positions.get(0)[0]);
        double dist12 = Math.abs(positions.get(2)[0] - positions.get(1)[0]);
        assertThat(dist01).isCloseTo(5.0, within(0.001));
        assertThat(dist12).isCloseTo(5.0, within(0.001));
    }

    // ---- GRID 网格队形 ----

    @Test
    @DisplayName("GRID 网格队形：9架无人机排列成3x3网格")
    void gridFormation() {
        List<double[]> positions = formationService.computePositions(
                FormationType.GRID, 9, 5.0, null);
        assertThat(positions).hasSize(9);
        // 3x3 网格，中心对称
        // 总宽度 = 2 * 5 = 10m，起点 = -5m
        assertThat(positions.get(0)[0]).isCloseTo(-5.0, within(0.001));
        assertThat(positions.get(0)[1]).isCloseTo(-5.0, within(0.001));
    }

    @Test
    @DisplayName("GRID 网格队形：自定义列数")
    void gridFormationCustomCols() {
        List<double[]> positions = formationService.computePositions(
                FormationType.GRID, 6, 5.0, Map.of("cols", 3.0));
        assertThat(positions).hasSize(6);
        // 3列2行
        // 第一行前3个X坐标应为 -5, 0, 5
        assertThat(positions.get(0)[0]).isCloseTo(-5.0, within(0.001));
        assertThat(positions.get(1)[0]).isCloseTo(0.0, within(0.001));
        assertThat(positions.get(2)[0]).isCloseTo(5.0, within(0.001));
    }

    // ---- CIRCLE 圆形队形 ----

    @Test
    @DisplayName("CIRCLE 圆形队形：8架无人机均匀分布在圆周上")
    void circleFormation() {
        List<double[]> positions = formationService.computePositions(
                FormationType.CIRCLE, 8, 5.0, null);
        assertThat(positions).hasSize(8);
        // 所有无人机距圆心距离应大致相等（在圆周上）
        for (double[] pos : positions) {
            double dist = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1]);
            // 默认半径 = spacingM * droneCount / (2 * PI) = 5 * 8 / (2 * PI) ≈ 6.37
            assertThat(dist).isCloseTo(6.366, within(0.1));
        }
    }

    @Test
    @DisplayName("CIRCLE 圆形队形：自定义半径")
    void circleFormationCustomRadius() {
        List<double[]> positions = formationService.computePositions(
                FormationType.CIRCLE, 4, 5.0, Map.of("radius", 20.0));
        assertThat(positions).hasSize(4);
        for (double[] pos : positions) {
            double dist = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1]);
            assertThat(dist).isCloseTo(20.0, within(0.001));
        }
    }

    // ---- SPIRAL 螺旋队形 ----

    @Test
    @DisplayName("SPIRAL 螺旋队形：生成螺旋分布坐标")
    void spiralFormation() {
        List<double[]> positions = formationService.computePositions(
                FormationType.SPIRAL, 10, 5.0, null);
        assertThat(positions).hasSize(10);
        // 第一个点应靠近原点
        double firstDist = Math.sqrt(positions.get(0)[0] * positions.get(0)[0]
                + positions.get(0)[1] * positions.get(0)[1]);
        assertThat(firstDist).isLessThan(5.0);
        // 最后一个点应远离原点
        double lastDist = Math.sqrt(positions.get(9)[0] * positions.get(9)[0]
                + positions.get(9)[1] * positions.get(9)[1]);
        assertThat(lastDist).isGreaterThan(firstDist);
    }

    // ---- V_SHAPE V字形队形 ----

    @Test
    @DisplayName("V_SHAPE V字形队形：5架无人机V形排列")
    void vShapeFormation() {
        List<double[]> positions = formationService.computePositions(
                FormationType.V_SHAPE, 5, 10.0, null);
        assertThat(positions).hasSize(5);
        // 顶点在原点
        assertThat(positions.get(0)[0]).isCloseTo(0.0, within(0.001));
        assertThat(positions.get(0)[1]).isCloseTo(0.0, within(0.001));
        // 左臂和右臂的 Y 坐标应为负（向后展开）
        assertThat(positions.get(1)[1]).isLessThan(0);
        assertThat(positions.get(3)[1]).isLessThan(0);
    }

    // ---- DIAMOND 菱形队形 ----

    @Test
    @DisplayName("DIAMOND 菱形队形：生成菱形轮廓坐标")
    void diamondFormation() {
        List<double[]> positions = formationService.computePositions(
                FormationType.DIAMOND, 8, 5.0, null);
        assertThat(positions).hasSize(8);
        // 菱形应有上下左右四个方向的极值点
        double maxX = 0, maxY = 0, minX = 0, minY = 0;
        for (double[] pos : positions) {
            maxX = Math.max(maxX, pos[0]);
            maxY = Math.max(maxY, pos[1]);
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
        }
        assertThat(maxX).isGreaterThan(0);
        assertThat(maxY).isGreaterThan(0);
        assertThat(minX).isLessThan(0);
        assertThat(minY).isLessThan(0);
    }

    // ---- HEART 心形队形 ----

    @Test
    @DisplayName("HEART 心形队形：生成心形轮廓坐标")
    void heartFormation() {
        List<double[]> positions = formationService.computePositions(
                FormationType.HEART, 20, 5.0, null);
        assertThat(positions).hasSize(20);
        // 心形顶部应有正Y值（两个凸起），底部应有负Y值（尖底）
        double maxY = 0, minY = 0;
        for (double[] pos : positions) {
            maxY = Math.max(maxY, pos[1]);
            minY = Math.min(minY, pos[1]);
        }
        assertThat(maxY).isGreaterThan(0);
        assertThat(minY).isLessThan(0);
    }

    // ---- STAR 星形队形 ----

    @Test
    @DisplayName("STAR 星形队形：生成五角星轮廓坐标")
    void starFormation() {
        List<double[]> positions = formationService.computePositions(
                FormationType.STAR, 10, 5.0, null);
        assertThat(positions).hasSize(10);
        // 五角星应有外圆半径和内圆半径交替的特征
        double maxR = 0, minR = Double.MAX_VALUE;
        for (double[] pos : positions) {
            double r = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1]);
            maxR = Math.max(maxR, r);
            minR = Math.min(minR, r);
        }
        assertThat(maxR).isGreaterThan(minR);
    }

    // ---- 参数校验 ----

    @Test
    @DisplayName("computePositions 参数校验")
    void computePositionsValidation() {
        assertThatThrownBy(() -> formationService.computePositions(
                FormationType.LINE, 0, 10.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> formationService.computePositions(
                FormationType.LINE, 5, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("LINE 直线队形：奇数架无人机中心对称")
    void lineFormationOddCount() {
        List<double[]> positions = formationService.computePositions(
                FormationType.LINE, 3, 10.0, null);
        assertThat(positions).hasSize(3);
        // 3架：-10, 0, 10
        assertThat(positions.get(0)[0]).isCloseTo(-10.0, within(0.001));
        assertThat(positions.get(1)[0]).isCloseTo(0.0, within(0.001));
        assertThat(positions.get(2)[0]).isCloseTo(10.0, within(0.001));
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}