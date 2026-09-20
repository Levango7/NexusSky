package io.aerofleet.cloud.alarm;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 报警联动规则（M10 报警联动编排，FR-31）。
 * <p>
 * 描述「当某类报警发生时，触发何种联动动作」的规则。{@link #matches(AlarmEvent)}
 * 判断事件是否满足规则条件（事件类型 + 最低严重程度 + 设备 ID 白名单）。
 * <p>
 * 规则字段在创建后不可变（除 enabled 外），便于在 {@code ConcurrentHashMap}
 * 中安全共享。{@code enabled} 为 volatile，支持运行时启停。
 *
 * @see AlarmLinkageEngine
 * @see AlarmToOrchBridge
 */
public class AlarmLinkageRule {

    /** 联动动作类型。 */
    public enum ActionType {
        /** 部署无人机侦察。 */
        DEPLOY_DRONE,
        /** 仅通知（短信/邮件/IM）。 */
        NOTIFY_ONLY,
        /** 录制视频。 */
        RECORD_VIDEO,
        /** 自动出警（P0-1：调用 AutoDispatchService 派遣无人机飞往报警位置）。 */
        AUTO_DISPATCH,
        /** 语音广播（P0-1：调用 VoiceIntercomService 向现场喊话）。 */
        VOICE_BROADCAST
    }

    /** 规则 ID。 */
    private final String id;
    /** 规则名称。 */
    private final String name;
    /** 是否启用（volatile 支持运行时切换）。 */
    private volatile boolean enabled;
    /** 匹配的事件类型（null 表示匹配任意类型）。 */
    private final AlarmEvent.EventType matchEventType;
    /** 匹配的最低严重程度（事件 severity.level >= 此值才匹配）。 */
    private final AlarmEvent.Severity matchSeverity;
    /** 匹配的设备 ID 白名单（空集合表示匹配全部设备）。 */
    private final Set<String> matchDeviceIds;
    /** 联动动作类型。 */
    private final ActionType actionType;
    /** 部署无人机数量（DEPLOY_DRONE 动作有效）。 */
    private final int droneCount;
    /** 目标纬度（覆盖规则默认值，使用事件位置时为 Double.NaN）。 */
    private final double targetLat;
    /** 目标经度。 */
    private final double targetLon;
    /** 目标半径（米）。 */
    private final double targetRadiusM;
    /** 飞行高度（米）。 */
    private final double altitudeM;
    /** 侦察任务模板 JSON（透传给 EmergencyOrchService）。 */
    private final String taskTemplate;

    public AlarmLinkageRule(String id, String name, boolean enabled,
                            AlarmEvent.EventType matchEventType,
                            AlarmEvent.Severity matchSeverity,
                            Set<String> matchDeviceIds,
                            ActionType actionType,
                            int droneCount,
                            double targetLat, double targetLon,
                            double targetRadiusM, double altitudeM,
                            String taskTemplate) {
        this.id = id;
        this.name = name;
        this.enabled = enabled;
        this.matchEventType = matchEventType;
        this.matchSeverity = matchSeverity;
        // 不可变快照，避免外部修改影响规则内部状态
        this.matchDeviceIds = matchDeviceIds == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(matchDeviceIds));
        this.actionType = actionType;
        this.droneCount = droneCount;
        this.targetLat = targetLat;
        this.targetLon = targetLon;
        this.targetRadiusM = targetRadiusM;
        this.altitudeM = altitudeM;
        this.taskTemplate = taskTemplate;
    }

    /**
     * 判断事件是否匹配本规则。
     * <p>
     * 匹配条件（全部满足）：
     * <ol>
     *   <li>规则已启用（{@code enabled == true}）</li>
     *   <li>事件类型匹配：{@code matchEventType == null} 表示匹配任意类型</li>
     *   <li>严重程度达标：{@code event.severity.level() >= matchSeverity.level()}</li>
     *   <li>设备 ID 匹配：{@code matchDeviceIds} 为空表示匹配全部设备，
     *       否则要求事件设备 ID 在白名单中</li>
     * </ol>
     *
     * @param event 报警事件
     * @return true 若事件匹配本规则
     */
    public boolean matches(AlarmEvent event) {
        if (!enabled) {
            return false;
        }
        if (matchEventType != null && event.getEventType() != matchEventType) {
            return false;
        }
        if (event.getSeverity().level() < matchSeverity.level()) {
            return false;
        }
        if (!matchDeviceIds.isEmpty()
                && !matchDeviceIds.contains(event.getSourceDeviceId())) {
            return false;
        }
        return true;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AlarmEvent.EventType getMatchEventType() {
        return matchEventType;
    }

    public AlarmEvent.Severity getMatchSeverity() {
        return matchSeverity;
    }

    public Set<String> getMatchDeviceIds() {
        return matchDeviceIds;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public int getDroneCount() {
        return droneCount;
    }

    public double getTargetLat() {
        return targetLat;
    }

    public double getTargetLon() {
        return targetLon;
    }

    public double getTargetRadiusM() {
        return targetRadiusM;
    }

    public double getAltitudeM() {
        return altitudeM;
    }

    public String getTaskTemplate() {
        return taskTemplate;
    }

    @Override
    public String toString() {
        return "AlarmLinkageRule{id=" + id
                + ", name=" + name
                + ", enabled=" + enabled
                + ", type=" + matchEventType
                + ", minSeverity=" + matchSeverity
                + ", devices=" + matchDeviceIds
                + ", action=" + actionType + '}';
    }
}