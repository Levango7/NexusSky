package io.aerofleet.sim;

/**
 * 灾害应急丐版配置（不可变值对象）。
 * <p>
 * 当 {@link BudgetMode} 为 {@link BudgetMode#EMERGENCY_TOY} 或
 * {@link BudgetMode#EMERGENCY_STANDARD} 时，此配置描述灾害应急场景下的
 * 传感器降级方案、通信方式、组网参数等。
 * <p>
 * 传感器降级映射：
 * <pre>
 * | 传感器      | Full Mode       | Emergency-Standard | Emergency-Toy    |
 * |-------------|-----------------|--------------------|--------------------|
 * | RadarSource | PhasedArrayRadar| ToF VL53L0X        | HC-SR04 超声波     |
 * | ThermalSource| 640×480 热成像  | AMG8833 8×8        | 无                |
 * | GpsSource   | RTK 厘米级      | NEO-M8N 2-3m       | 无（IMU积分）      |
 * | CommType    | 卫星+LTE+WiFi   | LoRa Mesh          | WiFi ESP-NOW      |
 * | MeshRouter  | AODV-lite 全功能| AODV-lite 简化     | ESP-NOW 广播      |
 * </pre>
 */
public final class EmergencyBudgetConfig {

    /** 通信类型。 */
    public enum CommType {
        /** WiFi ESP-NOW 广播（百元级，无路由表）。 */
        WIFI_ESP_NOW,
        /** LoRa Mesh 5km（千元级，简化 AODV-lite）。 */
        LORA_MESH
    }

    /** 避障传感器类型。 */
    public enum ObstacleSensorType {
        /** HC-SR04 超声波，2m 量程。 */
        HC_SR04_ULTRASONIC,
        /** VL53L0X ToF，4m 量程。 */
        VL53L0X_TOF
    }

    /** GPS 类型。 */
    public enum GpsType {
        /** 无 GPS（百元级，仅 IMU 积分定位）。 */
        NONE,
        /** NEO-M8N，2-3m 精度。 */
        NEO_M8N
    }

    /** 热成像类型。 */
    public enum ThermalType {
        /** 无热成像（百元级）。 */
        NONE,
        /** AMG8833 8×8 红外阵列热源检测。 */
        AMG8833_8X8
    }

    /** 摄像头/图传类型。 */
    public enum CameraType {
        /** ESP32-CAM WiFi 图传。 */
        ESP32_CAM,
        /** 5.8G FPV 模拟图传。 */
        FPV_5_8G
    }

    /** 对应的 BudgetMode。 */
    public final BudgetMode budgetMode;
    /** 通信类型。 */
    public final CommType commType;
    /** 避障传感器类型。 */
    public final ObstacleSensorType obstacleSensorType;
    /** 避障传感器量程（米）。 */
    public final double obstacleRangeM;
    /** GPS 类型。 */
    public final GpsType gpsType;
    /** GPS 精度（米），0 表示无 GPS。 */
    public final double gpsAccuracyM;
    /** 热成像类型。 */
    public final ThermalType thermalType;
    /** 摄像头/图传类型。 */
    public final CameraType cameraType;
    /** Mesh 最大跳数。 */
    public final int maxHops;
    /** Mesh 最大节点数。 */
    public final int maxNodes;
    /** LED 搜救信号灯启用。 */
    public final boolean ledEnabled;
    /** 蜂鸣器声光报警启用。 */
    public final boolean buzzerEnabled;
    /** 预估硬件成本（元）。 */
    public final double estimatedCostYuan;

    private EmergencyBudgetConfig(BudgetMode budgetMode, CommType commType,
                                   ObstacleSensorType obstacleSensorType, double obstacleRangeM,
                                   GpsType gpsType, double gpsAccuracyM,
                                   ThermalType thermalType, CameraType cameraType,
                                   int maxHops, int maxNodes,
                                   boolean ledEnabled, boolean buzzerEnabled,
                                   double estimatedCostYuan) {
        this.budgetMode = budgetMode;
        this.commType = commType;
        this.obstacleSensorType = obstacleSensorType;
        this.obstacleRangeM = obstacleRangeM;
        this.gpsType = gpsType;
        this.gpsAccuracyM = gpsAccuracyM;
        this.thermalType = thermalType;
        this.cameraType = cameraType;
        this.maxHops = maxHops;
        this.maxNodes = maxNodes;
        this.ledEnabled = ledEnabled;
        this.buzzerEnabled = buzzerEnabled;
        this.estimatedCostYuan = estimatedCostYuan;
    }

    /**
     * 百元级灾害应急配置（~74元）。
     * <ul>
     *   <li>WiFi ESP-NOW Mesh（无 LoRa）</li>
     *   <li>HC-SR04 超声波避障（2m）</li>
     *   <li>ESP32-CAM 航拍图传</li>
     *   <li>LED WS2812 搜救信号灯</li>
     *   <li>蜂鸣器声光报警</li>
     *   <li>无 GPS、无热成像、无航点飞行</li>
     *   <li>MAX_HOPS=5，最多 5-8 节点</li>
     * </ul>
     */
    public static EmergencyBudgetConfig emergencyToy() {
        return new EmergencyBudgetConfig(
                BudgetMode.EMERGENCY_TOY,
                CommType.WIFI_ESP_NOW,
                ObstacleSensorType.HC_SR04_ULTRASONIC,
                2.0,
                GpsType.NONE,
                0.0,
                ThermalType.NONE,
                CameraType.ESP32_CAM,
                5,
                8,
                true,
                true,
                74.0
        );
    }

    /**
     * 千元级灾害应急配置（~429元）。
     * <ul>
     *   <li>LoRa Mesh 5km</li>
     *   <li>VL53L0X ToF 避障（4m）</li>
     *   <li>NEO-M8N GPS 2-3m 精度</li>
     *   <li>5.8G FPV 图传</li>
     *   <li>AMG8833 8×8 红外热源检测</li>
     *   <li>LED + 蜂鸣器</li>
     *   <li>MAX_HOPS=10，最多 20 节点</li>
     * </ul>
     */
    public static EmergencyBudgetConfig emergencyStandard() {
        return new EmergencyBudgetConfig(
                BudgetMode.EMERGENCY_STANDARD,
                CommType.LORA_MESH,
                ObstacleSensorType.VL53L0X_TOF,
                4.0,
                GpsType.NEO_M8N,
                2.5,
                ThermalType.AMG8833_8X8,
                CameraType.FPV_5_8G,
                10,
                20,
                true,
                true,
                429.0
        );
    }

    /**
     * 根据 BudgetMode 创建对应的 EmergencyBudgetConfig。
     *
     * @param mode 灾害应急 BudgetMode
     * @return 对应配置，非应急模式返回 null
     */
    public static EmergencyBudgetConfig forMode(BudgetMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case EMERGENCY_TOY -> emergencyToy();
            case EMERGENCY_STANDARD -> emergencyStandard();
            default -> null;
        };
    }
}