package io.aerofleet.cloud.voicecmd;

/**
 * 语音指令解析结果。
 * <p>
 * 从自然语言文本中提取的无人机指令结构化表示，包含动作类型、目标设备、
 * 飞行参数（高度/速度/目标位置）及优先级等信息。
 */
public class ParsedCommand {

    /** 动作枚举 */
    public enum Action {
        TAKEOFF,   // 起飞
        LAND,      // 降落
        RETURN,    // 返航
        HOVER,     // 悬停
        PHOTO,     // 拍照
        RECORD,    // 录像
        FLY_TO,    // 前往指定位置
        SET_ALTITUDE, // 设置高度
        SET_SPEED,    // 设置速度
        UNKNOWN   // 未知指令
    }

    /** 优先级枚举 */
    public enum Priority {
        HIGH,   // 紧急/高优先级
        NORMAL  // 普通
    }

    private String rawText;
    private Action action = Action.UNKNOWN;
    private int sysid = -1;
    private Double altitudeM;
    private Double speedMps;
    private String targetName;
    private Double targetLat;
    private Double targetLon;
    private Priority priority = Priority.NORMAL;
    private int confidencePct = 0;

    public ParsedCommand() {
    }

    public ParsedCommand(String rawText) {
        this.rawText = rawText;
    }

    // --- getters / setters ---

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public int getSysid() {
        return sysid;
    }

    public void setSysid(int sysid) {
        this.sysid = sysid;
    }

    public Double getAltitudeM() {
        return altitudeM;
    }

    public void setAltitudeM(Double altitudeM) {
        this.altitudeM = altitudeM;
    }

    public Double getSpeedMps() {
        return speedMps;
    }

    public void setSpeedMps(Double speedMps) {
        this.speedMps = speedMps;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public Double getTargetLat() {
        return targetLat;
    }

    public void setTargetLat(Double targetLat) {
        this.targetLat = targetLat;
    }

    public Double getTargetLon() {
        return targetLon;
    }

    public void setTargetLon(Double targetLon) {
        this.targetLon = targetLon;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public int getConfidencePct() {
        return confidencePct;
    }

    public void setConfidencePct(int confidencePct) {
        this.confidencePct = confidencePct;
    }

    @Override
    public String toString() {
        return "ParsedCommand{action=" + action
                + ", sysid=" + sysid
                + ", altitudeM=" + altitudeM
                + ", speedMps=" + speedMps
                + ", targetName='" + targetName + "'"
                + ", priority=" + priority
                + ", confidencePct=" + confidencePct
                + ", rawText='" + rawText + "'}";
    }
}