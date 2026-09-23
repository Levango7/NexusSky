package io.aerofleet.sim.orch;

import io.aerofleet.sim.BudgetMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 覆盖优化算法核心（M9 应急任务编排，T3 覆盖优化算法）。
 * <p>
 * 在给定灾区范围内，对可用无人机列表进行贪心部署 + 局部优化 + 连通性修复，
 * 输出最优部署方案 {@link DeploymentPlan}。
 * <p>
 * 算法流程：
 * <pre>
 * 1. greedyDeploy     贪心部署：按电量降序，依次选覆盖增益最大位置
 * 2. localOptimize    局部优化：±localRangeM 范围 5×5 网格微调，重复 localIterations 轮
 * 3. fixConnectivity  连通性修复：DFS 检查 mesh 连通性，调整孤立节点或分配 HAPS 中继
 * </pre>
 * <p>
 * 默认参数：
 * <pre>
 * stepM           = 500 m   贪心网格步长
 * localRangeM     = 100 m   局部优化范围
 * localIterations = 3       局部优化轮数
 * meshRangeM      = 2000 m  mesh 一跳范围
 * </pre>
 * <p>
 * 基站覆盖半径模型（@ 20dBm 线性缩放）：
 * <pre>
 * LTE  : 2000 m
 * WiFi : 500 m
 * LoRa : 5000 m
 * </pre>
 */
public class CoverageOptimizer {

    /** 地球半径（m），用于 Haversine 公式。 */
    private static final double EARTH_RADIUS_M = 6371000.0;
    /** 1 度纬度对应的距离（m）。 */
    private static final double M_PER_DEGREE_LAT = 111000.0;
    /** 默认发射功率（dBm）。 */
    private static final int DEFAULT_TX_POWER = 20;
    /** 默认部署高度（m）。 */
    private static final double DEFAULT_ALT = 100.0;
    /** 1% 电量对应的服务时长（ms）。 */
    private static final long SERVICE_MS_PER_PERCENT = 60_000L;

    /** 贪心网格步长（m），默认 500m。 */
    private final double stepM;
    /** 局部优化范围（m），默认 100m。 */
    private final double localRangeM;
    /** 局部优化轮数，默认 3。 */
    private final int localIterations;
    /** mesh 一跳范围（m），默认 2000m。 */
    private final double meshRangeM;

    /** 当前优化的场景类型（optimize 入口设置，供 greedyDeploy 使用）。 */
    private int currentScenarioType;

    /**
     * Haversine 距离缓存（optimize 作用域内有效）。
     * <p>
     * key 由量化后的 (lat1, lon1, lat2, lon2) 四元组格式化为字符串，
     * 量化精度约 1m。每次 optimize 调用前清空，避免跨调用污染。
     * <p>
     * 使用字符串 key 而非 XOR hash 组合，彻底消除哈希碰撞风险
     * （XOR 组合在不同坐标对间可能产生相同 key 导致返回错误距离）。
     */
    private final java.util.Map<String, Double> distanceCache = new java.util.HashMap<>();

    /**
     * 构造覆盖优化器。
     *
     * @param stepM           贪心网格步长（m），必须 &gt; 0
     * @param localRangeM     局部优化范围（m），必须 &gt; 0
     * @param localIterations 局部优化轮数，必须 &gt; 0
     * @param meshRangeM      mesh 一跳范围（m），必须 &gt; 0
     */
    public CoverageOptimizer(double stepM, double localRangeM, int localIterations, double meshRangeM) {
        if (stepM <= 0) {
            throw new IllegalArgumentException("stepM must be > 0, got " + stepM);
        }
        if (localRangeM <= 0) {
            throw new IllegalArgumentException("localRangeM must be > 0, got " + localRangeM);
        }
        if (localIterations <= 0) {
            throw new IllegalArgumentException("localIterations must be > 0, got " + localIterations);
        }
        if (meshRangeM <= 0) {
            throw new IllegalArgumentException("meshRangeM must be > 0, got " + meshRangeM);
        }
        this.stepM = stepM;
        this.localRangeM = localRangeM;
        this.localIterations = localIterations;
        this.meshRangeM = meshRangeM;
    }

    /** 默认构造器：stepM=500, localRangeM=100, localIterations=3, meshRangeM=2000。 */
    public CoverageOptimizer() {
        this(500.0, 100.0, 3, 2000.0);
    }

    /**
     * 主优化入口。
     *
     * @param centerLat    灾区中心纬度（度）
     * @param centerLon    灾区中心经度（度）
     * @param radius       灾区半径（m），必须 &gt; 0
     * @param drones       可用无人机列表，null 或空返回空方案
     * @param scenarioType 场景类型（0=地震, 1=泥石流, 2=火灾）
     * @return 最优部署方案
     */
    public DeploymentPlan optimize(double centerLat, double centerLon, double radius,
                                   List<DroneInfo> drones, int scenarioType) {
        if (drones == null || drones.isEmpty()) {
            return new DeploymentPlan(Collections.emptyList(), 0.0, 0.0, 0L);
        }
        if (radius <= 0) {
            return new DeploymentPlan(Collections.emptyList(), 0.0, 0.0, 0L);
        }

        this.currentScenarioType = scenarioType;

        // 清空距离缓存，避免跨 optimize 调用污染
        distanceCache.clear();

        // 1. 贪心部署
        List<DroneDeployment> greedy = greedyDeploy(centerLat, centerLon, radius, drones);

        // 2. 局部优化
        List<DroneDeployment> optimized = localOptimize(greedy, centerLat, centerLon, radius);

        // 3. 连通性修复
        List<DroneDeployment> connected = fixConnectivity(optimized);

        // 4. 计算指标
        double coverageRate = calculateCoverage(connected, centerLat, centerLon, radius);
        double connectRate = calculateConnectivity(connected);
        long expectedServiceMs = estimateServiceMs(connected);

        return new DeploymentPlan(connected, coverageRate, connectRate, expectedServiceMs);
    }

    /**
     * 贪心部署：在灾区范围内生成候选位置网格，按电量降序依次选覆盖增益最大位置。
     *
     * @param centerLat 灾区中心纬度（度）
     * @param centerLon 灾区中心经度（度）
     * @param radius    灾区半径（m）
     * @param drones    可用无人机列表
     * @return 部署列表
     */
    List<DroneDeployment> greedyDeploy(double centerLat, double centerLon,
                                       double radius, List<DroneInfo> drones) {
        // 按电量降序排序
        List<DroneInfo> sorted = new ArrayList<>(drones);
        sorted.sort((a, b) -> Integer.compare(b.batteryPercent, a.batteryPercent));

        List<double[]> candidates = generateGridPoints(centerLat, centerLon, radius, stepM);
        int n = candidates.size();
        boolean[] occupied = new boolean[n];
        boolean[] covered = new boolean[n];

        List<DroneDeployment> result = new ArrayList<>();

        for (DroneInfo drone : sorted) {
            int bestIdx = -1;
            int bestGain = 0;
            int cellType = selectCellType(drone, currentScenarioType);
            int txPower = DEFAULT_TX_POWER;
            double r = coverageRadiusM(cellType, txPower);

            for (int i = 0; i < n; i++) {
                if (occupied[i]) {
                    continue;
                }
                double[] pos = candidates.get(i);
                double cosLatI = Math.cos(Math.toRadians(pos[0]));
                double rThreshold = r * 1.5;
                double rThresholdSq = rThreshold * rThreshold;
                int gain = 0;
                for (int j = 0; j < n; j++) {
                    if (covered[j]) {
                        continue;
                    }
                    double[] cj = candidates.get(j);
                    // 快速平面距离估算（用于早期淘汰）
                    double dLatM = (cj[0] - pos[0]) * M_PER_DEGREE_LAT;
                    double dLonM = (cj[1] - pos[1]) * M_PER_DEGREE_LAT * cosLatI;
                    double approxDistSq = dLatM * dLatM + dLonM * dLonM;
                    if (approxDistSq > rThresholdSq) {
                        continue;
                    }
                    if (haversineMeters(pos[0], pos[1], cj[0], cj[1]) <= r) {
                        gain++;
                    }
                }
                if (gain > bestGain) {
                    bestGain = gain;
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0) {
                occupied[bestIdx] = true;
                double[] pos = candidates.get(bestIdx);
                double cosLatBest = Math.cos(Math.toRadians(pos[0]));
                double rThreshold2 = r * 1.5;
                double rThresholdSq2 = rThreshold2 * rThreshold2;
                for (int j = 0; j < n; j++) {
                    if (covered[j]) {
                        continue;
                    }
                    double[] cj = candidates.get(j);
                    // 快速平面距离估算（用于早期淘汰）
                    double dLatM = (cj[0] - pos[0]) * M_PER_DEGREE_LAT;
                    double dLonM = (cj[1] - pos[1]) * M_PER_DEGREE_LAT * cosLatBest;
                    double approxDistSq = dLatM * dLatM + dLonM * dLonM;
                    if (approxDistSq > rThresholdSq2) {
                        continue;
                    }
                    if (haversineMeters(pos[0], pos[1], cj[0], cj[1]) <= r) {
                        covered[j] = true;
                    }
                }
                double singleCoverage = singleCoverageRate(r, radius);
                result.add(new DroneDeployment(drone.droneId, pos[0], pos[1], DEFAULT_ALT,
                        cellType, 1, txPower, singleCoverage, drone.batteryPercent));
            }
        }
        return result;
    }

    /**
     * 局部优化：对每架无人机位置做 ±localRangeM 范围 5×5 网格微调，重复 localIterations 轮。
     *
     * @param initial   初始部署列表
     * @param centerLat 灾区中心纬度（度）
     * @param centerLon 灾区中心经度（度）
     * @param radius    灾区半径（m）
     * @return 优化后部署列表
     */
    List<DroneDeployment> localOptimize(List<DroneDeployment> initial,
                                        double centerLat, double centerLon, double radius) {
        if (initial.isEmpty()) {
            return new ArrayList<>();
        }
        List<DroneDeployment> current = new ArrayList<>(initial);

        double cosLat = Math.cos(Math.toRadians(centerLat));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double latStep = (localRangeM / 2.0) / M_PER_DEGREE_LAT;
        double lonStep = (localRangeM / 2.0) / (M_PER_DEGREE_LAT * cosLat);

        for (int iter = 0; iter < localIterations; iter++) {
            for (int i = 0; i < current.size(); i++) {
                DroneDeployment d = current.get(i);
                double bestLat = d.targetLat;
                double bestLon = d.targetLon;
                double bestCoverage = calculateCoverage(current, centerLat, centerLon, radius);

                for (int di = -2; di <= 2; di++) {
                    for (int dj = -2; dj <= 2; dj++) {
                        double newLat = d.targetLat + di * latStep;
                        double newLon = d.targetLon + dj * lonStep;

                        List<DroneDeployment> temp = new ArrayList<>(current);
                        temp.set(i, new DroneDeployment(d.droneId, newLat, newLon, d.targetAlt,
                                d.cellType, d.relayRole, d.txPower, d.expectedCoverage, d.batteryBudget));
                        double coverage = calculateCoverage(temp, centerLat, centerLon, radius);

                        if (coverage > bestCoverage) {
                            bestCoverage = coverage;
                            bestLat = newLat;
                            bestLon = newLon;
                        }
                    }
                }

                current.set(i, new DroneDeployment(d.droneId, bestLat, bestLon, d.targetAlt,
                        d.cellType, d.relayRole, d.txPower, d.expectedCoverage, d.batteryBudget));
            }
        }
        return current;
    }

    /**
     * 连通性修复：DFS 检查 mesh 连通性，调整孤立节点或分配 HAPS 中继。
     *
     * @param deployments 部署列表
     * @return 修复后部署列表
     */
    List<DroneDeployment> fixConnectivity(List<DroneDeployment> deployments) {
        return fixConnectivity(deployments, this.meshRangeM);
    }

    /**
     * 修复连通性（使用指定的 mesh 范围）。
     * <p>
     * 当 effectiveMeshRangeM 与实例字段 meshRangeM 不同时（如 SIMPLIFIED/MINIMAL 模式），
     * 使用传入的 effectiveMeshRangeM 进行连通性判断和位置调整。
     *
     * @param deployments        部署列表
     * @param effectiveMeshRangeM 实际生效的 mesh 一跳范围（m）
     * @return 修复后部署列表
     */
    List<DroneDeployment> fixConnectivity(List<DroneDeployment> deployments, double effectiveMeshRangeM) {
        if (deployments.size() <= 1) {
            return new ArrayList<>(deployments);
        }

        List<DroneDeployment> result = new ArrayList<>(deployments);
        int n = result.size();

        int[] component = computeComponents(result, effectiveMeshRangeM);
        int numComponents = 0;
        for (int c : component) {
            if (c >= numComponents) {
                numComponents = c + 1;
            }
        }

        if (numComponents <= 1) {
            return result;
        }

        // 找最大连通分量作为主连通分量
        int[] compSize = new int[numComponents];
        for (int c : component) {
            compSize[c]++;
        }
        int mainComp = 0;
        for (int c = 1; c < numComponents; c++) {
            if (compSize[c] > compSize[mainComp]) {
                mainComp = c;
            }
        }

        // 对每个非主连通分量
        for (int c = 0; c < numComponents; c++) {
            if (c == mainComp) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                if (component[i] != c) {
                    continue;
                }

                // 找离主连通分量最近的无人机
                double minDist = Double.MAX_VALUE;
                int nearestJ = -1;
                for (int j = 0; j < n; j++) {
                    if (component[j] != mainComp) {
                        continue;
                    }
                    double dist = haversineMeters(result.get(i).targetLat, result.get(i).targetLon,
                            result.get(j).targetLat, result.get(j).targetLon);
                    if (dist < minDist) {
                        minDist = dist;
                        nearestJ = j;
                    }
                }

                if (nearestJ < 0) {
                    continue;
                }

                DroneDeployment d = result.get(i);
                if (minDist < effectiveMeshRangeM * 1.5) {
                    // 调整位置向主连通分量移动
                    DroneDeployment target = result.get(nearestJ);
                    double ratio = (effectiveMeshRangeM * 0.9) / minDist;
                    double moveRatio = 1.0 - ratio;
                    double newLat = d.targetLat + (target.targetLat - d.targetLat) * moveRatio;
                    double newLon = d.targetLon + (target.targetLon - d.targetLon) * moveRatio;
                    result.set(i, new DroneDeployment(d.droneId, newLat, newLon, d.targetAlt,
                            d.cellType, 1, d.txPower, d.expectedCoverage, d.batteryBudget));
                } else {
                    // 分配 HAPS 中继
                    result.set(i, new DroneDeployment(d.droneId, d.targetLat, d.targetLon, d.targetAlt,
                            d.cellType, 2, d.txPower, d.expectedCoverage, d.batteryBudget));
                }
            }
        }
        return result;
    }

    /**
     * 计算覆盖率：基站覆盖圆并集面积 / 灾区面积 × 100。
     * <p>
     * 用网格采样法估算：在灾区圆内生成采样点，计算被覆盖的比例。
     *
     * @param deployments 部署列表
     * @param centerLat   灾区中心纬度（度）
     * @param centerLon   灾区中心经度（度）
     * @param radius      灾区半径（m）
     * @return 覆盖率（0-100）
     */
    double calculateCoverage(List<DroneDeployment> deployments,
                             double centerLat, double centerLon, double radius) {
        if (deployments.isEmpty()) {
            return 0.0;
        }
        List<double[]> samples = generateGridPoints(centerLat, centerLon, radius, stepM);
        if (samples.isEmpty()) {
            return 0.0;
        }
        int covered = 0;
        for (double[] sample : samples) {
            for (DroneDeployment d : deployments) {
                double r = coverageRadiusM(d.cellType, d.txPower);
                if (r > 0 && haversineMeters(d.targetLat, d.targetLon, sample[0], sample[1]) <= r) {
                    covered++;
                    break;
                }
            }
        }
        return 100.0 * covered / samples.size();
    }

    /**
     * 计算连通率：连通对数 / 总对数 × 100。
     *
     * @param deployments 部署列表
     * @return 连通率（0-100）
     */
    double calculateConnectivity(List<DroneDeployment> deployments) {
        int n = deployments.size();
        if (n <= 1) {
            return 100.0;
        }

        int[] component = computeComponents(deployments);
        int numComponents = 0;
        for (int c : component) {
            if (c >= numComponents) {
                numComponents = c + 1;
            }
        }

        int totalPairs = n * (n - 1) / 2;
        int[] compSize = new int[numComponents];
        for (int c : component) {
            compSize[c]++;
        }
        int connectedPairs = 0;
        for (int size : compSize) {
            connectedPairs += size * (size - 1) / 2;
        }
        return 100.0 * connectedPairs / totalPairs;
    }

    /**
     * 带缓存的 Haversine 距离计算（m）。
     * <p>
     * 将坐标量化到约 0.1m 精度（%.6f 约对应纬度 0.11m）后格式化为字符串 key，缓存距离结果。
     * 在 optimize 一次调用内，同一对（量化后相同的）坐标只计算一次 Haversine，
     * 后续命中缓存直接返回。使用字符串 key 避免 XOR hash 碰撞。
     *
     * @param lat1Deg 点1 纬度（度）
     * @param lon1Deg 点1 经度（度）
     * @param lat2Deg 点2 纬度（度）
     * @param lon2Deg 点2 经度（度）
     * @return 两点间球面距离（m）
     */
    private double haversineMeters(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        // 量化到 ~0.1m 精度（%.6f 约对应纬度 0.11m），用字符串 key 避免 XOR hash 碰撞；
        // Locale.ROOT 保证小数点始终为 '.'，避免本地化环境（如小数点为 ','）导致缓存 key 不一致
        String key = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", lat1Deg, lon1Deg, lat2Deg, lon2Deg);
        Double cached = distanceCache.get(key);
        if (cached != null) {
            return cached;
        }

        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        double dist = EARTH_RADIUS_M * c;
        distanceCache.put(key, dist);
        return dist;
    }

    /**
     * Haversine 公式计算两点距离（m）。
     *
     * @param lat1 点1 纬度（度）
     * @param lon1 点1 经度（度）
     * @param lat2 点2 纬度（度）
     * @param lon2 点2 经度（度）
     * @return 两点间球面距离（m）
     */
    double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_M * c;
    }

    /**
     * 根据基站类型和功率估算覆盖半径（简化模型）。
     * <p>
     * LTE: 2000m @ 20dBm, WiFi: 500m @ 20dBm, LoRa: 5000m @ 20dBm。
     * 线性缩放: radius = baseRadius * (txPower / 20.0)。
     *
     * @param cellType 基站类型（0=无, 1=LTE, 2=WiFi, 3=LoRa）
     * @param txPower  发射功率（dBm）
     * @return 覆盖半径（m），cellType=0 返回 0
     */
    double coverageRadiusM(int cellType, int txPower) {
        double baseRadius;
        switch (cellType) {
            case 1: baseRadius = 2000.0; break;  // LTE
            case 2: baseRadius = 500.0;  break;  // WiFi
            case 3: baseRadius = 5000.0; break;  // LoRa
            default: return 0.0;
        }
        return baseRadius * (txPower / 20.0);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 生成灾区圆内的网格点。
     *
     * @param centerLat 中心纬度（度）
     * @param centerLon 中心经度（度）
     * @param radius    灾区半径（m）
     * @param step      网格步长（m）
     * @return 网格点列表，每个元素 [lat, lon]
     */
    private List<double[]> generateGridPoints(double centerLat, double centerLon,
                                              double radius, double step) {
        List<double[]> points = new ArrayList<>();
        double latStep = step / M_PER_DEGREE_LAT;
        double cosLat = Math.cos(Math.toRadians(centerLat));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double lonStep = step / (M_PER_DEGREE_LAT * cosLat);
        int n = (int) Math.ceil(radius / step);
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                double lat = centerLat + i * latStep;
                double lon = centerLon + j * lonStep;
                if (haversineMeters(centerLat, centerLon, lat, lon) <= radius) {
                    points.add(new double[]{lat, lon});
                }
            }
        }
        return points;
    }

    /**
     * 根据场景类型和无人机支持的基站类型选择基站类型。
     * <p>
     * 场景优先级：
     * <pre>
     * 0=地震   : LTE > LoRa > WiFi  （广覆盖优先）
     * 1=泥石流 : LoRa > LTE > WiFi  （长距离山区）
     * 2=火灾   : WiFi > LTE > LoRa  （高带宽视频）
     * </pre>
     *
     * @param drone        无人机信息
     * @param scenarioType 场景类型
     * @return 基站类型（0=无可用）
     */
    private int selectCellType(DroneInfo drone, int scenarioType) {
        int[] priority;
        switch (scenarioType) {
            case 0: priority = new int[]{1, 3, 2}; break;  // 地震：LTE > LoRa > WiFi
            case 1: priority = new int[]{3, 1, 2}; break;  // 泥石流：LoRa > LTE > WiFi
            case 2: priority = new int[]{2, 1, 3}; break;  // 火灾：WiFi > LTE > LoRa
            default: priority = new int[]{1, 2, 3}; break;
        }
        for (int t : priority) {
            if (drone.supportedCellTypes.contains(t)) {
                return t;
            }
        }
        return 0;
    }

    /**
     * 计算单架无人机在灾区中心的覆盖率近似值。
     *
     * @param coverageR 覆盖半径（m）
     * @param radius    灾区半径（m）
     * @return 覆盖率（0-100）
     */
    private double singleCoverageRate(double coverageR, double radius) {
        if (radius <= 0) {
            return 0.0;
        }
        double droneArea = Math.PI * coverageR * coverageR;
        double disasterArea = Math.PI * radius * radius;
        return Math.min(100.0, 100.0 * droneArea / disasterArea);
    }

    /**
     * 构建邻接图并 DFS 计算连通分量。
     *
     * @param deployments 部署列表
     * @return 连通分量数组，component[i] = 无人机 i 所属分量编号
     */
    private int[] computeComponents(List<DroneDeployment> deployments) {
        return computeComponents(deployments, this.meshRangeM);
    }

    /**
     * 计算连通分量（使用指定的 mesh 范围）。
     *
     * @param deployments        部署列表
     * @param effectiveMeshRangeM 实际生效的 mesh 一跳范围（m）
     * @return 连通分量数组，component[i] = 无人机 i 所属分量编号
     */
    private int[] computeComponents(List<DroneDeployment> deployments, double effectiveMeshRangeM) {
        int n = deployments.size();
        int[] component = new int[n];
        Arrays.fill(component, -1);
        int numComponents = 0;
        for (int i = 0; i < n; i++) {
            if (component[i] == -1) {
                dfs(i, numComponents, deployments, component, effectiveMeshRangeM);
                numComponents++;
            }
        }
        return component;
    }

    /**
     * DFS 遍历连通分量。
     *
     * @param node        当前节点
     * @param comp        分量编号
     * @param deployments 部署列表
     * @param component   分量数组
     */
    private void dfs(int node, int comp, List<DroneDeployment> deployments, int[] component,
                     double effectiveMeshRangeM) {
        component[node] = comp;
        for (int j = 0; j < deployments.size(); j++) {
            if (component[j] != -1) {
                continue;
            }
            double dist = haversineMeters(deployments.get(node).targetLat, deployments.get(node).targetLon,
                    deployments.get(j).targetLat, deployments.get(j).targetLon);
            if (dist <= effectiveMeshRangeM) {
                dfs(j, comp, deployments, component, effectiveMeshRangeM);
            }
        }
    }

    /**
     * 估算预期服务时长：最低电量预算 × 60s/1%。
     *
     * @param deployments 部署列表
     * @return 预期服务时长（ms）
     */
    private long estimateServiceMs(List<DroneDeployment> deployments) {
        if (deployments.isEmpty()) {
            return 0L;
        }
        int minBudget = Integer.MAX_VALUE;
        for (DroneDeployment d : deployments) {
            if (d.batteryBudget < minBudget) {
                minBudget = d.batteryBudget;
            }
        }
        return (long) minBudget * SERVICE_MS_PER_PERCENT;
    }

    // ==================== M9 丐版约束感知 ====================

    /**
     * 带预算约束的覆盖优化入口（M9 丐版约束感知）。
     * <p>
     * 在原有 optimize 流程基础上，根据 {@link OrchestrationConfig.BudgetConstraints}
     * 调整覆盖优化参数：
     * <ul>
     *   <li>根据 maxNodes 限制参与编排的无人机数量（截取电量最高的前 N 架）</li>
     *   <li>根据 maxCoverageRadiusKm 限制覆盖半径（取 min(请求半径, 约束半径)）</li>
     *   <li>根据 maxEnduranceMin 计算轮换需求</li>
     *   <li>根据 orchestrationComplexity 决定编排层级</li>
     *   <li>根据 searchRescueCapability 推荐搜救策略</li>
     * </ul>
     *
     * @param drones     可用无人机列表
     * @param centerLat  灾区中心纬度（度）
     * @param centerLon  灾区中心经度（度）
     * @param radiusKm   请求覆盖半径（km），必须 &gt; 0
     * @param constraints 预算约束，不能为 null
     * @return 预算感知部署方案
     */
    public BudgetAwareDeploymentPlan optimizeWithConstraints(
            List<DroneInfo> drones, double centerLat, double centerLon,
            double radiusKm, OrchestrationConfig.BudgetConstraints constraints) {
        if (drones == null || drones.isEmpty()) {
            return new BudgetAwareDeploymentPlan(
                    Collections.emptyList(), 0.0, 0.0, 0L,
                    constraints, null, "无可用无人机");
        }
        if (radiusKm <= 0) {
            return new BudgetAwareDeploymentPlan(
                    Collections.emptyList(), 0.0, 0.0, 0L,
                    constraints, null, "覆盖半径必须 > 0");
        }
        if (constraints == null) {
            throw new IllegalArgumentException("constraints must not be null");
        }

        // 1. 限制参与编排的无人机数量：按电量降序截取前 maxNodes 架
        List<DroneInfo> selectedDrones = selectDronesByBudget(drones, constraints.maxNodes);

        // 2. 限制覆盖半径：取 min(请求半径, 约束最大半径)
        double effectiveRadiusKm = Math.min(radiusKm, constraints.maxCoverageRadiusKm);
        double effectiveRadiusM = effectiveRadiusKm * 1000.0;

        // 3. 根据编排复杂度调整 meshRangeM
        double effectiveMeshRangeM = adjustMeshRangeByComplexity(constraints);

        // 4. 根据编排复杂度决定是否跳过连通性修复（MINIMAL 模式跳过）
        boolean skipConnectivityFix =
                constraints.orchestrationComplexity
                        == OrchestrationConfig.BudgetConstraints.OrchestrationComplexity.MINIMAL;

        // 5. 执行核心优化流程（复用现有逻辑）
        this.currentScenarioType = 0; // 默认地震场景
        distanceCache.clear();

        List<DroneDeployment> greedy =
                greedyDeploy(centerLat, centerLon, effectiveRadiusM, selectedDrones);
        List<DroneDeployment> optimized =
                localOptimize(greedy, centerLat, centerLon, effectiveRadiusM);

        List<DroneDeployment> finalDeployments;
        if (skipConnectivityFix) {
            // MINIMAL 模式：WiFi 单跳，不做连通性修复
            finalDeployments = optimized;
        } else {
            // FULL / SIMPLIFIED 模式：执行连通性修复
            finalDeployments = fixConnectivity(optimized, effectiveMeshRangeM);
        }

        // 6. 计算指标
        double coverageRate =
                calculateCoverage(finalDeployments, centerLat, centerLon, effectiveRadiusM);
        double connectRate = calculateConnectivity(finalDeployments);
        long expectedServiceMs = estimateServiceMs(finalDeployments);

        // 7. 推荐轮换调度方案
        RotationSchedule rotation =
                recommendRotationSchedule(selectedDrones, constraints);

        // 8. 推荐搜救策略
        String searchRescueStrategy = recommendSearchRescueStrategy(constraints);

        // 9. 生成约束说明
        String constraintSummary = buildConstraintSummary(
                constraints, effectiveRadiusKm, selectedDrones.size(), skipConnectivityFix);

        return new BudgetAwareDeploymentPlan(
                finalDeployments, coverageRate, connectRate, expectedServiceMs,
                constraints, rotation, searchRescueStrategy + "; " + constraintSummary);
    }

    /**
     * 推荐轮换调度方案（基于续航约束）。
     * <p>
     * 根据约束中的 maxEnduranceMin 计算轮换需求：
     * <ul>
     *   <li>每架无人机的预期服务时长 = batteryPercent × 60s</li>
     *   <li>若预期服务时长 &gt; maxEnduranceMin，则需要轮换</li>
     *   <li>轮换批次 = ceil(总任务时长 / 单批最大续航)</li>
     *   <li>每批所需无人机数 = 部署位置数</li>
     *   <li>总无人机需求 = 部署位置数 × 轮换批次</li>
     * </ul>
     *
     * @param drones      可用无人机列表
     * @param constraints 预算约束
     * @return 轮换调度方案
     */
    public RotationSchedule recommendRotationSchedule(
            List<DroneInfo> drones, OrchestrationConfig.BudgetConstraints constraints) {
        if (drones == null || drones.isEmpty()) {
            return new RotationSchedule(0, 0, 0, 0, "无可用无人机");
        }
        if (constraints == null) {
            throw new IllegalArgumentException("constraints must not be null");
        }

        int maxEnduranceMin = constraints.maxEnduranceMin;
        long maxEnduranceMs = (long) maxEnduranceMin * 60_000L;

        // 计算每架无人机的预期服务时长（batteryPercent × 60s）
        long minServiceMs = Long.MAX_VALUE;
        for (DroneInfo drone : drones) {
            long serviceMs = (long) drone.batteryPercent * SERVICE_MS_PER_PERCENT;
            if (serviceMs < minServiceMs) {
                minServiceMs = serviceMs;
            }
        }

        // 若最低电量无人机续航已超过约束最大续航，需要轮换
        if (minServiceMs <= maxEnduranceMs) {
            // 无需轮换
            return new RotationSchedule(
                    1, drones.size(), 0, maxEnduranceMin,
                    "当前无人机续航满足约束，无需轮换");
        }

        // 计算轮换批次
        int rotationBatches = (int) Math.ceil((double) minServiceMs / maxEnduranceMs);
        // 每批所需无人机数 = 约束最大节点数
        int dronesPerBatch = Math.min(constraints.maxNodes, drones.size());
        // 总无人机需求
        int totalDronesNeeded = dronesPerBatch * rotationBatches;
        // 可用无人机是否足够
        boolean sufficient = drones.size() >= totalDronesNeeded;

        String description;
        if (sufficient) {
            description = String.format(Locale.ROOT,
                    "需 %d 批轮换，每批 %d 架，共需 %d 架（当前可用 %d 架，满足需求）",
                    rotationBatches, dronesPerBatch, totalDronesNeeded, drones.size());
        } else {
            description = String.format(Locale.ROOT,
                    "需 %d 批轮换，每批 %d 架，共需 %d 架（当前可用 %d 架，不足 %d 架）",
                    rotationBatches, dronesPerBatch, totalDronesNeeded,
                    drones.size(), totalDronesNeeded - drones.size());
        }

        return new RotationSchedule(
                rotationBatches, dronesPerBatch,
                totalDronesNeeded - drones.size(),
                maxEnduranceMin, description);
    }

    /**
     * 获取预算感知的部署方案（便捷入口）。
     * <p>
     * 从 BudgetMode 字符串创建约束，然后调用 {@link #optimizeWithConstraints}。
     *
     * @param drones       可用无人机列表
     * @param centerLat    灾区中心纬度（度）
     * @param centerLon    灾区中心经度（度）
     * @param radiusKm     请求覆盖半径（km）
     * @param budgetModeCli BudgetMode CLI 值（如 "emergency-toy"）
     * @return 预算感知部署方案
     */
    public BudgetAwareDeploymentPlan getBudgetAwareDeploymentPlan(
            List<DroneInfo> drones, double centerLat, double centerLon,
            double radiusKm, String budgetModeCli) {
        OrchestrationConfig.BudgetConstraints constraints =
                OrchestrationConfig.BudgetConstraints.fromBudgetMode(budgetModeCli);
        return optimizeWithConstraints(drones, centerLat, centerLon, radiusKm, constraints);
    }

    /**
     * 获取预算感知的部署方案（从 BudgetMode 枚举创建约束）。
     *
     * @param drones       可用无人机列表
     * @param centerLat    灾区中心纬度（度）
     * @param centerLon    灾区中心经度（度）
     * @param radiusKm     请求覆盖半径（km）
     * @param budgetMode   BudgetMode 枚举
     * @return 预算感知部署方案
     */
    public BudgetAwareDeploymentPlan getBudgetAwareDeploymentPlan(
            List<DroneInfo> drones, double centerLat, double centerLon,
            double radiusKm, BudgetMode budgetMode) {
        OrchestrationConfig.BudgetConstraints constraints =
                OrchestrationConfig.BudgetConstraints.fromBudgetMode(budgetMode);
        return optimizeWithConstraints(drones, centerLat, centerLon, radiusKm, constraints);
    }

    // ==================== 丐版约束感知 - 内部辅助方法 ====================

    /**
     * 按电量降序选择前 maxNodes 架无人机。
     *
     * @param drones   可用无人机列表
     * @param maxNodes 最大节点数
     * @return 截取后的无人机列表
     */
    private List<DroneInfo> selectDronesByBudget(List<DroneInfo> drones, int maxNodes) {
        List<DroneInfo> sorted = new ArrayList<>(drones);
        sorted.sort((a, b) -> Integer.compare(b.batteryPercent, a.batteryPercent));
        if (sorted.size() <= maxNodes) {
            return sorted;
        }
        return sorted.subList(0, maxNodes);
    }

    /**
     * 根据编排复杂度调整 mesh 一跳范围。
     * <p>
     * FULL: 保持默认 meshRangeM（2000m）<br>
     * SIMPLIFIED: LoRa Mesh 5000m<br>
     * MINIMAL: WiFi ESP-NOW 500m
     *
     * @param constraints 预算约束
     * @return 调整后的 mesh 一跳范围（m）
     */
    private double adjustMeshRangeByComplexity(OrchestrationConfig.BudgetConstraints constraints) {
        return switch (constraints.orchestrationComplexity) {
            case FULL -> meshRangeM;
            case SIMPLIFIED -> Math.max(meshRangeM, 5000.0); // LoRa Mesh 5km
            case MINIMAL -> Math.min(meshRangeM, 500.0);     // WiFi ESP-NOW ~500m
        };
    }

    /**
     * 根据搜救能力枚举推荐搜救策略描述。
     *
     * @param constraints 预算约束
     * @return 搜救策略描述
     */
    private String recommendSearchRescueStrategy(
            OrchestrationConfig.BudgetConstraints constraints) {
        return switch (constraints.searchRescueCapability) {
            case THERMAL_HIGH_RES ->
                    "搜救策略：高分辨率热成像（640×480），可远距离识别人体热源，适合大面积搜索";
            case THERMAL_LOW_RES ->
                    "搜救策略：低分辨率热源检测（8×8 AMG8833），近距离热源定位，适合小范围确认";
            case LED_BUZZER ->
                    "搜救策略：LED 信号灯 + 蜂鸣器声光报警，仅适合近距离引导，无主动搜索能力";
        };
    }

    /**
     * 构建约束应用摘要说明。
     *
     * @param constraints       预算约束
     * @param effectiveRadiusKm 实际使用的覆盖半径（km）
     * @param selectedDroneCount 选中的无人机数量
     * @param skipConnectivityFix 是否跳过连通性修复
     * @return 约束摘要
     */
    private String buildConstraintSummary(
            OrchestrationConfig.BudgetConstraints constraints,
            double effectiveRadiusKm, int selectedDroneCount,
            boolean skipConnectivityFix) {
        StringBuilder sb = new StringBuilder();
        sb.append("约束应用：");
        sb.append("覆盖半径=").append(String.format(Locale.ROOT, "%.2f", effectiveRadiusKm))
                .append("km");
        sb.append(", 节点数=").append(selectedDroneCount)
                .append("/").append(constraints.maxNodes);
        sb.append(", 续航上限=").append(constraints.maxEnduranceMin).append("min");
        sb.append(", 避障距离=").append(constraints.obstacleAvoidanceDistanceM).append("m");
        sb.append(", 编排复杂度=").append(constraints.orchestrationComplexity);
        if (skipConnectivityFix) {
            sb.append("（跳过连通性修复）");
        }
        return sb.toString();
    }

    // ==================== 丐版约束感知 - 数据类 ====================

    /**
     * 轮换调度方案。
     * <p>
     * 当无人机续航无法满足任务时长时，需要分批轮换部署。
     */
    public static final class RotationSchedule {
        /** 轮换批次总数。 */
        public final int rotationBatches;
        /** 每批所需无人机数。 */
        public final int dronesPerBatch;
        /** 不足的无人机数（0 表示满足需求）。 */
        public final int dronesShortfall;
        /** 单批最大续航（分钟）。 */
        public final int maxEndurancePerBatchMin;
        /** 方案描述。 */
        public final String description;

        public RotationSchedule(int rotationBatches, int dronesPerBatch,
                                 int dronesShortfall, int maxEndurancePerBatchMin,
                                 String description) {
            this.rotationBatches = rotationBatches;
            this.dronesPerBatch = dronesPerBatch;
            this.dronesShortfall = dronesShortfall;
            this.maxEndurancePerBatchMin = maxEndurancePerBatchMin;
            this.description = description;
        }

        /** 是否需要轮换（rotationBatches > 1）。 */
        public boolean needsRotation() {
            return rotationBatches > 1;
        }

        /** 无人机数量是否充足（dronesShortfall <= 0）。 */
        public boolean isSufficient() {
            return dronesShortfall <= 0;
        }

        @Override
        public String toString() {
            return "RotationSchedule{batches=" + rotationBatches
                    + ", dronesPerBatch=" + dronesPerBatch
                    + ", shortfall=" + dronesShortfall
                    + ", maxEndurance=" + maxEndurancePerBatchMin + "min"
                    + ", " + description + "}";
        }
    }

    /**
     * 预算感知部署方案。
     * <p>
     * 在 {@link DeploymentPlan} 基础上扩展，包含预算约束信息、轮换调度方案和搜救策略。
     */
    public static final class BudgetAwareDeploymentPlan {
        /** 部署列表。 */
        public final List<DroneDeployment> deployments;
        /** 覆盖率（0-100）。 */
        public final double coverageRate;
        /** 连通率（0-100）。 */
        public final double connectRate;
        /** 预期服务时长（ms）。 */
        public final long expectedServiceMs;
        /** 应用的预算约束。 */
        public final OrchestrationConfig.BudgetConstraints constraints;
        /** 轮换调度方案。 */
        public final RotationSchedule rotationSchedule;
        /** 策略说明（搜救策略 + 约束摘要）。 */
        public final String strategyNotes;

        public BudgetAwareDeploymentPlan(
                List<DroneDeployment> deployments,
                double coverageRate, double connectRate, long expectedServiceMs,
                OrchestrationConfig.BudgetConstraints constraints,
                RotationSchedule rotationSchedule,
                String strategyNotes) {
            this.deployments = deployments;
            this.coverageRate = coverageRate;
            this.connectRate = connectRate;
            this.expectedServiceMs = expectedServiceMs;
            this.constraints = constraints;
            this.rotationSchedule = rotationSchedule;
            this.strategyNotes = strategyNotes;
        }

        @Override
        public String toString() {
            return "BudgetAwareDeploymentPlan{deployments=" + deployments.size()
                    + ", coverage=" + String.format(Locale.ROOT, "%.1f", coverageRate) + "%"
                    + ", connectivity=" + String.format(Locale.ROOT, "%.1f", connectRate) + "%"
                    + ", serviceMs=" + expectedServiceMs
                    + ", constraints=" + constraints
                    + ", rotation=" + rotationSchedule
                    + ", notes=" + strategyNotes + "}";
        }
    }
}