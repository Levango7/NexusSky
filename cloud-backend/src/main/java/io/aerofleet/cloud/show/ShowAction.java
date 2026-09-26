package io.aerofleet.cloud.show;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个表演动作（动作序列中的一个节点）。
 * <p>
 * 每个动作包含序号、类型、起始时间、持续时间和参数，由 {@link ActionSequenceService}
 * 编排为完整动作序列，下发给无人机执行。
 */
public final class ShowAction {

    private final String id;
    private final String taskId;
    private final int seq;
    private final ActionType type;
    private final double startTime;
    private final double durationSec;
    private final Map<String, Double> parameters;

    public ShowAction(String id, String taskId, int seq, ActionType type,
                      double startTime, double durationSec,
                      Map<String, Double> parameters) {
        this.id = id;
        this.taskId = taskId;
        this.seq = seq;
        this.type = type;
        this.startTime = startTime;
        this.durationSec = durationSec;
        this.parameters = parameters == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    public String getId() { return id; }
    public String getTaskId() { return taskId; }
    public int getSeq() { return seq; }
    public ActionType getType() { return type; }
    public double getStartTime() { return startTime; }
    public double getDurationSec() { return durationSec; }
    public Map<String, Double> getParameters() { return parameters; }
}