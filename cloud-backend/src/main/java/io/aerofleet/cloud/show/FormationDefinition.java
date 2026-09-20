package io.aerofleet.cloud.show;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 队形定义（编队表演的队形参数化配置）。
 * <p>
 * 封装队形类型、无人机数量、间距等参数，由 {@link FormationService} 根据定义
 * 生成每架无人机在队形中的相对位置坐标。
 * <p>
 * parameters 可携带队形特定参数（如圆形半径、网格行列数、螺旋圈数等）。
 */
public final class FormationDefinition {

    private final String id;
    private final String name;
    private final FormationType type;
    private final int droneCount;
    private final double spacingM;
    private final Map<String, Double> parameters;

    public FormationDefinition(String id, String name, FormationType type,
                               int droneCount, double spacingM,
                               Map<String, Double> parameters) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.droneCount = droneCount;
        this.spacingM = spacingM;
        this.parameters = parameters == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public FormationType getType() { return type; }
    public int getDroneCount() { return droneCount; }
    public double getSpacingM() { return spacingM; }
    public Map<String, Double> getParameters() { return parameters; }
}