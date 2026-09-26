package io.aerofleet.cloud.voicecmd;

/**
 * 语音播报结果。
 * <p>
 * 描述一次语音播报操作的结果，包含播报文本、目标无人机、时间戳和发送状态。
 */
public class BroadcastResult {

    /** 播报状态枚举 */
    public enum Status {
        SENT,   // 已发送
        FAILED  // 发送失败
    }

    private String id;
    private int sysid;
    private String text;
    private long timestamp;
    private Status status;

    public BroadcastResult() {
    }

    public BroadcastResult(String id, int sysid, String text, long timestamp, Status status) {
        this.id = id;
        this.sysid = sysid;
        this.text = text;
        this.timestamp = timestamp;
        this.status = status;
    }

    // --- getters / setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getSysid() {
        return sysid;
    }

    public void setSysid(int sysid) {
        this.sysid = sysid;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "BroadcastResult{id='" + id + "'"
                + ", sysid=" + sysid
                + ", text='" + text + "'"
                + ", timestamp=" + timestamp
                + ", status=" + status + "}";
    }
}