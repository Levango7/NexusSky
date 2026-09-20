package io.aerofleet.cloud.commadapt;

import java.util.Map;

/**
 * 通信综合质量评分。
 * <p>
 * 对某架无人机的所有通信链路进行综合评分，给出总体分数、等级、最优链路类型
 * 以及各链路的详细质量数据。
 *
 * @see LinkQualityMonitor
 * @see AdaptiveRouter
 */
public class CommQualityScore {

    /** 质量等级 */
    public enum Grade {
        A, B, C, D, F
    }

    /** 综合评分（0-100） */
    private int overallScore;
    /** 质量等级 */
    private Grade grade;
    /** 最优链路类型 */
    private LinkQuality.LinkType bestLinkType;
    /** 各链路详细质量数据 */
    private Map<LinkQuality.LinkType, LinkQuality> details;

    public CommQualityScore() {
    }

    public CommQualityScore(int overallScore, Grade grade,
                            LinkQuality.LinkType bestLinkType,
                            Map<LinkQuality.LinkType, LinkQuality> details) {
        this.overallScore = overallScore;
        this.grade = grade;
        this.bestLinkType = bestLinkType;
        this.details = details;
    }

    public int getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(int overallScore) {
        this.overallScore = overallScore;
    }

    public Grade getGrade() {
        return grade;
    }

    public void setGrade(Grade grade) {
        this.grade = grade;
    }

    public LinkQuality.LinkType getBestLinkType() {
        return bestLinkType;
    }

    public void setBestLinkType(LinkQuality.LinkType bestLinkType) {
        this.bestLinkType = bestLinkType;
    }

    public Map<LinkQuality.LinkType, LinkQuality> getDetails() {
        return details;
    }

    public void setDetails(Map<LinkQuality.LinkType, LinkQuality> details) {
        this.details = details;
    }

    @Override
    public String toString() {
        return "CommQualityScore{overallScore=" + overallScore
                + ", grade=" + grade
                + ", bestLinkType=" + bestLinkType
                + ", details=" + (details != null ? details.keySet() : "null") + '}';
    }
}