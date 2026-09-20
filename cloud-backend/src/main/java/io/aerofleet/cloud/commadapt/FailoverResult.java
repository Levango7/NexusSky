package io.aerofleet.cloud.commadapt;

/**
 * 故障切换执行结果。
 * <p>
 * 描述一次故障切换操作的执行状态：从哪条链路切换到哪条链路，
 * 切换是否成功、回滚或失败，以及附带的消息。
 *
 * @see FailoverManager
 */
public class FailoverResult {

    /** 切换状态 */
    public enum Status {
        SUCCESS,
        FAILED,
        ROLLBACK
    }

    /** 无人机 systemId */
    private int sysid;
    /** 原链路类型 */
    private LinkQuality.LinkType fromLink;
    /** 目标链路类型 */
    private LinkQuality.LinkType toLink;
    /** 切换状态 */
    private Status status;
    /** 切换时间戳（epoch ms） */
    private long timestamp;
    /** 附加消息 */
    private String message;

    public FailoverResult() {
    }

    public FailoverResult(int sysid, LinkQuality.LinkType fromLink,
                          LinkQuality.LinkType toLink, Status status,
                          long timestamp, String message) {
        this.sysid = sysid;
        this.fromLink = fromLink;
        this.toLink = toLink;
        this.status = status;
        this.timestamp = timestamp;
        this.message = message;
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

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "FailoverResult{sysid=" + sysid
                + ", fromLink=" + fromLink
                + ", toLink=" + toLink
                + ", status=" + status
                + ", timestamp=" + timestamp
                + ", message='" + message + '\'' + '}';
    }
}