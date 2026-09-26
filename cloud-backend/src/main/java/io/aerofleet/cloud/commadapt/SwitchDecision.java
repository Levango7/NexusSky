package io.aerofleet.cloud.commadapt;

/**
 * 链路切换决策。
 * <p>
 * 描述是否需要从当前链路切换到推荐链路，包含当前链路与推荐链路的评分、
 * 切换原因及紧急程度。
 *
 * @see AdaptiveRouter
 */
public class SwitchDecision {

    /** 紧急程度 */
    public enum Urgency {
        IMMEDIATE,
        DELAYED,
        NO_SWITCH
    }

    /** 无人机 systemId */
    private int sysid;
    /** 当前链路类型 */
    private LinkQuality.LinkType currentLink;
    /** 推荐链路类型 */
    private LinkQuality.LinkType recommendedLink;
    /** 当前链路评分 */
    private int currentScore;
    /** 推荐链路评分 */
    private int recommendedScore;
    /** 切换原因 */
    private String reason;
    /** 紧急程度 */
    private Urgency urgency;

    public SwitchDecision() {
    }

    public SwitchDecision(int sysid, LinkQuality.LinkType currentLink,
                          LinkQuality.LinkType recommendedLink,
                          int currentScore, int recommendedScore,
                          String reason, Urgency urgency) {
        this.sysid = sysid;
        this.currentLink = currentLink;
        this.recommendedLink = recommendedLink;
        this.currentScore = currentScore;
        this.recommendedScore = recommendedScore;
        this.reason = reason;
        this.urgency = urgency;
    }

    public int getSysid() {
        return sysid;
    }

    public void setSysid(int sysid) {
        this.sysid = sysid;
    }

    public LinkQuality.LinkType getCurrentLink() {
        return currentLink;
    }

    public void setCurrentLink(LinkQuality.LinkType currentLink) {
        this.currentLink = currentLink;
    }

    public LinkQuality.LinkType getRecommendedLink() {
        return recommendedLink;
    }

    public void setRecommendedLink(LinkQuality.LinkType recommendedLink) {
        this.recommendedLink = recommendedLink;
    }

    public int getCurrentScore() {
        return currentScore;
    }

    public void setCurrentScore(int currentScore) {
        this.currentScore = currentScore;
    }

    public int getRecommendedScore() {
        return recommendedScore;
    }

    public void setRecommendedScore(int recommendedScore) {
        this.recommendedScore = recommendedScore;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Urgency getUrgency() {
        return urgency;
    }

    public void setUrgency(Urgency urgency) {
        this.urgency = urgency;
    }

    @Override
    public String toString() {
        return "SwitchDecision{sysid=" + sysid
                + ", currentLink=" + currentLink
                + ", recommendedLink=" + recommendedLink
                + ", currentScore=" + currentScore
                + ", recommendedScore=" + recommendedScore
                + ", reason='" + reason + '\''
                + ", urgency=" + urgency + '}';
    }
}