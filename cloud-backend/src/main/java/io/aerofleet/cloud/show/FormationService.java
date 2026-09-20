package io.aerofleet.cloud.show;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队形管理服务。
 * <p>
 * 负责队形定义的创建、查询，以及根据队形类型、无人机数量和间距
 * 生成每架无人机在队形中的相对位置坐标（以队形中心为原点，单位米）。
 * <p>
 * 支持的队形类型：
 * <ul>
 *   <li>{@link FormationType#LINE} 直线 — 沿 X 轴等间距排列</li>
 *   <li>{@link FormationType#GRID} 网格 — 行列网格排列</li>
 *   <li>{@link FormationType#CIRCLE} 圆形 — 均匀分布在圆周上</li>
 *   <li>{@link FormationType#SPIRAL} 螺旋 — 沿阿基米德螺旋线分布</li>
 *   <li>{@link FormationType#V_SHAPE} V字形 — 雁阵排列</li>
 *   <li>{@link FormationType#DIAMOND} 菱形 — 菱形轮廓分布</li>
 *   <li>{@link FormationType#HEART} 心形 — 心形轮廓分布</li>
 *   <li>{@link FormationType#STAR} 星形 — 五角星轮廓分布</li>
 * </ul>
 */
@Service("showFormationService")
public class FormationService {

    private static final Logger log = LoggerFactory.getLogger(FormationService.class);

    private final Map<String, FormationDefinition> formations = new ConcurrentHashMap<>();

    /**
     * 创建队形定义。
     *
     * @param name       队形名称
     * @param type       队形类型
     * @param droneCount 无人机数量
     * @param spacingM   间距（米）
     * @param parameters 队形特定参数（可为 null）
     * @return 创建的队形定义
     * @throws IllegalArgumentException 参数非法
     */
    public FormationDefinition createFormation(String name, FormationType type,
                                                int droneCount, double spacingM,
                                                Map<String, Double> parameters) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (droneCount <= 0) {
            throw new IllegalArgumentException("droneCount must be positive");
        }
        if (spacingM <= 0) {
            throw new IllegalArgumentException("spacingM must be positive");
        }
        String id = UUID.randomUUID().toString();
        FormationDefinition formation = new FormationDefinition(
                id, name, type, droneCount, spacingM, parameters);
        formations.put(id, formation);
        log.info("Formation created: id={} name={} type={} drones={} spacing={}m",
                id, name, type, droneCount, spacingM);
        return formation;
    }

    /** 列出所有队形定义。 */
    public List<FormationDefinition> listFormations() {
        return new ArrayList<>(formations.values());
    }

    /** 获取队形定义详情。 */
    public FormationDefinition getFormation(String formationId) {
        FormationDefinition formation = formations.get(formationId);
        if (formation == null) {
            throw new IllegalArgumentException("formation not found: " + formationId);
        }
        return formation;
    }

    /**
     * 计算队形位置坐标。
     * <p>
     * 根据队形类型、无人机数量和间距，生成每架无人机在队形中的相对位置。
     * 坐标以队形中心为原点，X 轴指向东，Y 轴指向北，单位为米。
     *
     * @param type       队形类型
     * @param droneCount 无人机数量
     * @param spacingM   间距（米）
     * @param parameters 队形特定参数（可为 null）
     * @return 位置坐标列表，每个元素为 {x, y}（米）
     */
    public List<double[]> computePositions(FormationType type, int droneCount,
                                           double spacingM, Map<String, Double> parameters) {
        if (droneCount <= 0) {
            throw new IllegalArgumentException("droneCount must be positive");
        }
        if (spacingM <= 0) {
            throw new IllegalArgumentException("spacingM must be positive");
        }
        List<double[]> positions = switch (type) {
            case LINE -> computeLine(droneCount, spacingM);
            case GRID -> computeGrid(droneCount, spacingM, parameters);
            case CIRCLE -> computeCircle(droneCount, spacingM, parameters);
            case SPIRAL -> computeSpiral(droneCount, spacingM, parameters);
            case V_SHAPE -> computeVShape(droneCount, spacingM);
            case DIAMOND -> computeDiamond(droneCount, spacingM);
            case HEART -> computeHeart(droneCount, spacingM);
            case STAR -> computeStar(droneCount, spacingM);
        };
        log.info("Positions computed: type={} drones={} spacing={}m result={}",
                type, droneCount, spacingM, positions.size());
        return positions;
    }

    /** 直线队形：沿 X 轴等间距排列，中心对称。 */
    private List<double[]> computeLine(int droneCount, double spacingM) {
        List<double[]> positions = new ArrayList<>(droneCount);
        double totalWidth = (droneCount - 1) * spacingM;
        double startX = -totalWidth / 2.0;
        for (int i = 0; i < droneCount; i++) {
            positions.add(new double[]{startX + i * spacingM, 0.0});
        }
        return positions;
    }

    /** 网格队形：行列网格排列。 */
    private List<double[]> computeGrid(int droneCount, double spacingM, Map<String, Double> parameters) {
        int cols = (int) Math.ceil(Math.sqrt(droneCount));
        if (parameters != null && parameters.containsKey("cols")) {
            cols = (int) Math.round(parameters.get("cols"));
            if (cols <= 0) cols = (int) Math.ceil(Math.sqrt(droneCount));
        }
        int rows = (int) Math.ceil((double) droneCount / cols);
        List<double[]> positions = new ArrayList<>(droneCount);
        double totalWidth = (cols - 1) * spacingM;
        double totalHeight = (rows - 1) * spacingM;
        double startX = -totalWidth / 2.0;
        double startY = -totalHeight / 2.0;
        int count = 0;
        for (int r = 0; r < rows && count < droneCount; r++) {
            for (int c = 0; c < cols && count < droneCount; c++) {
                positions.add(new double[]{
                        startX + c * spacingM,
                        startY + r * spacingM
                });
                count++;
            }
        }
        return positions;
    }

    /** 圆形队形：均匀分布在圆周上。 */
    private List<double[]> computeCircle(int droneCount, double spacingM, Map<String, Double> parameters) {
        double radius = spacingM * droneCount / (2 * Math.PI);
        if (parameters != null && parameters.containsKey("radius")) {
            radius = parameters.get("radius");
        }
        if (radius <= 0) {
            radius = spacingM * droneCount / (2 * Math.PI);
        }
        List<double[]> positions = new ArrayList<>(droneCount);
        for (int i = 0; i < droneCount; i++) {
            double angle = 2 * Math.PI * i / droneCount;
            positions.add(new double[]{
                    radius * Math.cos(angle),
                    radius * Math.sin(angle)
            });
        }
        return positions;
    }

    /** 螺旋队形：沿阿基米德螺旋线分布。 */
    private List<double[]> computeSpiral(int droneCount, double spacingM, Map<String, Double> parameters) {
        double turns = 2.0;
        if (parameters != null && parameters.containsKey("turns")) {
            turns = parameters.get("turns");
        }
        if (turns <= 0) turns = 2.0;
        List<double[]> positions = new ArrayList<>(droneCount);
        // 阿基米德螺旋: r = a * theta，间距沿弧长近似为 spacingM
        // 总角度 = turns * 2 * PI
        double totalAngle = turns * 2 * Math.PI;
        // 螺旋参数 a: 使相邻圈间距约等于 spacingM
        // r = a * theta, 相邻圈半径差 = a * 2 * PI ≈ spacingM → a = spacingM / (2 * PI)
        double a = spacingM / (2 * Math.PI);
        for (int i = 0; i < droneCount; i++) {
            double theta = totalAngle * i / Math.max(1, droneCount - 1);
            double r = a * theta;
            positions.add(new double[]{
                    r * Math.cos(theta),
                    r * Math.sin(theta)
            });
        }
        return positions;
    }

    /** V字形队形：雁阵排列，两臂从顶点向后展开。 */
    private List<double[]> computeVShape(int droneCount, double spacingM) {
        List<double[]> positions = new ArrayList<>(droneCount);
        // V字形：顶点在 (0, 0)，两臂分别向左后和右后展开
        // 每臂角度约 30 度（与 X 轴夹角）
        double armAngle = Math.toRadians(30);
        int half = droneCount / 2;
        // 顶点无人机
        positions.add(new double[]{0.0, 0.0});
        int placed = 1;
        // 左臂（负 X 方向）
        for (int i = 1; i <= half && placed < droneCount; i++) {
            double dist = i * spacingM;
            positions.add(new double[]{
                    -dist * Math.cos(armAngle),
                    -dist * Math.sin(armAngle)
            });
            placed++;
        }
        // 右臂（正 X 方向）
        for (int i = 1; placed < droneCount; i++) {
            double dist = i * spacingM;
            positions.add(new double[]{
                    dist * Math.cos(armAngle),
                    -dist * Math.sin(armAngle)
            });
            placed++;
        }
        return positions;
    }

    /** 菱形队形：无人机沿菱形轮廓分布。 */
    private List<double[]> computeDiamond(int droneCount, double spacingM) {
        List<double[]> positions = new ArrayList<>(droneCount);
        // 菱形四个顶点：上(0, +R)、右(+R, 0)、下(0, -R)、左(-R, 0)
        // 周长 = 4 * sqrt(2) * R，每边长 = sqrt(2) * R
        // 间距 spacingM → 每边无人机数 = max(1, round(sqrt(2)*R / spacingM))
        // 反推 R：使每边能容纳合理数量的无人机
        double perimeter = droneCount * spacingM;
        double R = perimeter / (4 * Math.sqrt(2));
        double sideLen = Math.sqrt(2) * R;
        int perSide = Math.max(1, (int) Math.round(sideLen / spacingM));
        // 四条边：上→右、右→下、下→左、左→上
        // 顶点（不重复）
        double[][] vertices = {
                {0, R},      // 上
                {R, 0},      // 右
                {0, -R},     // 下
                {-R, 0}      // 左
        };
        // 沿四条边均匀分布无人机
        int count = 0;
        for (int edge = 0; edge < 4 && count < droneCount; edge++) {
            double[] v0 = vertices[edge];
            double[] v1 = vertices[(edge + 1) % 4];
            int pointsOnEdge = perSide;
            // 最后一条边放置剩余的无人机
            if (edge == 3) {
                pointsOnEdge = droneCount - count;
            }
            for (int i = 0; i < pointsOnEdge && count < droneCount; i++) {
                double t = (double) i / pointsOnEdge;
                positions.add(new double[]{
                        v0[0] + t * (v1[0] - v0[0]),
                        v0[1] + t * (v1[1] - v0[1])
                });
                count++;
            }
        }
        return positions;
    }

    /** 心形队形：无人机沿心形轮廓分布。 */
    private List<double[]> computeHeart(int droneCount, double spacingM) {
        List<double[]> positions = new ArrayList<>(droneCount);
        // 心形参数方程: x = 16*sin^3(t), y = 13*cos(t) - 5*cos(2t) - 2*cos(3t) - cos(4t)
        // 缩放因子使心形大小与 spacingM 和 droneCount 相关
        double scale = spacingM * droneCount / 40.0;
        for (int i = 0; i < droneCount; i++) {
            double t = 2 * Math.PI * i / droneCount;
            double x = 16 * Math.pow(Math.sin(t), 3) * scale;
            double y = (13 * Math.cos(t) - 5 * Math.cos(2 * t)
                    - 2 * Math.cos(3 * t) - Math.cos(4 * t)) * scale;
            positions.add(new double[]{x, y});
        }
        return positions;
    }

    /** 星形队形：无人机沿五角星轮廓分布。 */
    private List<double[]> computeStar(int droneCount, double spacingM) {
        List<double[]> positions = new ArrayList<>(droneCount);
        // 五角星 10 个顶点（5 外 + 5 内），沿星形轮廓均匀分布
        double outerR = spacingM * droneCount / 10.0;
        double innerR = outerR * 0.4; // 内外半径比约 0.4
        // 10 个顶点，交替外/内
        double[] radii = new double[10];
        for (int i = 0; i < 10; i++) {
            radii[i] = (i % 2 == 0) ? outerR : innerR;
        }
        // 沿 10 条边均匀分布无人机
        int count = 0;
        for (int edge = 0; edge < 10 && count < droneCount; edge++) {
            double angle0 = Math.PI / 2 + edge * Math.PI / 5;
            double angle1 = Math.PI / 2 + (edge + 1) * Math.PI / 5;
            double r0 = radii[edge];
            double r1 = radii[(edge + 1) % 10];
            double x0 = r0 * Math.cos(angle0);
            double y0 = r0 * Math.sin(angle0);
            double x1 = r1 * Math.cos(angle1);
            double y1 = r1 * Math.sin(angle1);
            int perEdge = Math.max(1, droneCount / 10);
            if (edge == 9) {
                perEdge = droneCount - count;
            }
            for (int i = 0; i < perEdge && count < droneCount; i++) {
                double t = (double) i / perEdge;
                positions.add(new double[]{
                        x0 + t * (x1 - x0),
                        y0 + t * (y1 - y0)
                });
                count++;
            }
        }
        return positions;
    }
}