package io.aerofleet.cloud.commadapt;

/**
 * 故障切换历史记录。
 * <p>
 * 记录每次故障切换的完整信息：触发时间、完成时间、切换状态与原因，
 * 用于故障切换历史查询与审计。
 *
 * @see FailoverManager
 */
public class FailoverRecord {

    /** 记录 ID */
    private String id;
    /** 无人机 systemId */
    private int sysid;
    /** 原链路类型 */
    private LinkQuality.LinkType fromLink;
    /** 目标链路类型 */
    private LinkQuality.LinkType toLink;
    /** 触发时间戳（epoch ms） */
    private long triggerTime;
    /** 完成时间戳（epoch ms） */
    private long completeTime;
    /** 切换状态 */
    private FailoverResult.Status status;
    /** 切换原因 */
    private String reason;

    public FailoverRecord() {
    }

    public FailoverRecord(String id, int sysid, LinkQuality.LinkType fromLink,
                          LinkQuality.LinkType toLink, long triggerTime,
                          long completeTime, FailoverResult.Status status,
                          String reason) {
        this.id = id;
        this.sysid = sysid;
        this.fromLink = fromLink;
        this.toLink = toLink;
        this.triggerTime = triggerTime;
        this.completeTime = completeTime;
        this.status = status;
        this.reason = reason;
    }

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

    public LinkQuality.LinkType getFromLink() {
        return fromLink;
    }

    public void setFromLink(LinkQuality.LinkType fromLink) {
        this.fromLink = fromLink;
    }

    public LinkQuality.LinkType getToLink() {
        return toLink;
    }

    public void setToLink(LinkQuality.LinkType toLink) {
        this.toLink = toLink;
    }

    public long getTriggerTime() {
        return triggerTime;
    }

    public void setTriggerTime(long triggerTime) {
        this.triggerTime = triggerTime;
    }

    public long getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(long completeTime) {
        this.completeTime = completeTime;
    }

    public FailoverResult.Status getStatus() {
        return status;
    }

    public void setStatus(FailoverResult.Status status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "FailoverRecord{id='" + id + '\''
                + ", sysid=" + sysid
                + ", fromLink=" + fromLink
                + ", toLink=" + toLink
                + ", triggerTime=" + triggerTime
                + ", completeTime=" + completeTime
                + ", status=" + status
                + ", reason='" + reason + '\'' + '}';
    }
}