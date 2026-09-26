package io.aerofleet.cloud.commadapt;

/**
 * 通信链路质量模型。
 * <p>
 * 描述某一时刻某条通信链路（mesh/卫星/基站）的质量指标，
 * 包括延迟、带宽、信号强度、丢包率、抖动等。
 *
 * @see LinkQualityMonitor
 * @see CommQualityScore
 */
public class LinkQuality {

    /** 链路类型 */
    public enum LinkType {
        MESH,
        SATELLITE,
        CELLULAR
    }

    /** 链路类型 */
    private LinkType linkType;
    /** 延迟（毫秒） */
    private double latencyMs;
    /** 带宽（kbps） */
    private double bandwidthKbps;
    /** 信号强度 RSSI（dBm） */
    private double rssiDbm;
    /** 丢包率（百分比，0-100） */
    private double packetLossPct;
    /** 抖动（毫秒） */
    private double jitterMs;
    /** 采集时间戳（epoch ms） */
    private long timestamp;
    /** 无人机 systemId */
    private int sysid;

    public LinkQuality() {
    }

    public LinkQuality(LinkType linkType, double latencyMs, double bandwidthKbps,
                       double rssiDbm, double packetLossPct, double jitterMs,
                       long timestamp, int sysid) {
        this.linkType = linkType;
        this.latencyMs = latencyMs;
        this.bandwidthKbps = bandwidthKbps;
        this.rssiDbm = rssiDbm;
        this.packetLossPct = packetLossPct;
        this.jitterMs = jitterMs;
        this.timestamp = timestamp;
        this.sysid = sysid;
    }

    public LinkType getLinkType() {
        return linkType;
    }

    public void setLinkType(LinkType linkType) {
        this.linkType = linkType;
    }

    public double getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(double latencyMs) {
        this.latencyMs = latencyMs;
    }

    public double getBandwidthKbps() {
        return bandwidthKbps;
    }

    public void setBandwidthKbps(double bandwidthKbps) {
        this.bandwidthKbps = bandwidthKbps;
    }

    public double getRssiDbm() {
        return rssiDbm;
    }

    public void setRssiDbm(double rssiDbm) {
        this.rssiDbm = rssiDbm;
    }

    public double getPacketLossPct() {
        return packetLossPct;
    }

    public void setPacketLossPct(double packetLossPct) {
        this.packetLossPct = packetLossPct;
    }

    public double getJitterMs() {
        return jitterMs;
    }

    public void setJitterMs(double jitterMs) {
        this.jitterMs = jitterMs;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getSysid() {
        return sysid;
    }

    public void setSysid(int sysid) {
        this.sysid = sysid;
    }

    @Override
    public String toString() {
        return "LinkQuality{linkType=" + linkType
                + ", latencyMs=" + latencyMs
                + ", bandwidthKbps=" + bandwidthKbps
                + ", rssiDbm=" + rssiDbm
                + ", packetLossPct=" + packetLossPct
                + ", jitterMs=" + jitterMs
                + ", timestamp=" + timestamp
                + ", sysid=" + sysid + '}';
    }
}