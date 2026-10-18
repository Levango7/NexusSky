package io.aerofleet.cloud.surveillance;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 安防设备数据模型。
 * <p>
 * 本系统兼容海康威视、大华、宇视三大安防厂商协议。
 * ONVIF 是这三家都支持的标准协议，用于设备发现/视频流获取/PTZ控制/事件订阅。
 * <p>
 * 该类为可变快照（心跳/状态/能力随时间变化），但 {@code id} 与 {@code vendor}
 * 在构造后不可变，便于在注册表中作为稳定键使用。
 */
public class SurveillanceDevice {

    /** 安防设备厂商枚举。 */
    public enum Vendor {
        /** 海康威视。 */
        HIKVISION,
        /** 大华。 */
        DAHUA,
        /** 宇视。 */
        UNIVIEW,
        /** ONVIF 标准协议（fallback，适用于所有支持 ONVIF 的设备）。 */
        ONVIF,
        /** GB28181 政企视频网标准（国标 SIP 信令，E5 形状层接入）。 */
        GB28181
    }

    /**
     * 设备状态（值与协议 {@code SurveillanceStatusMsg.status} 值域一致：0-3）。
     * <p>
     * FAULT/MAINTENANCE 是人工/检测置位（设备报障待修 / 运维检修中）——
     * {@link #heartbeat()} 不覆盖它们（心跳不代表故障恢复或检修结束）。
     */
    public enum Status {
        ONLINE,
        OFFLINE,
        FAULT,
        MAINTENANCE
    }

    /** 设备唯一标识（外部传入，注册表以此为键）。 */
    public final String id;
    /** 设备名称（人类可读）。 */
    public volatile String name;
    /** 厂商。 */
    public final Vendor vendor;
    /** 设备 IP 地址。 */
    public volatile String ip;
    /** ONVIF 服务端口（默认 80）。 */
    public volatile int port;
    /** 登录用户名。 */
    public volatile String username;
    /** 登录密码（明文，由调用方负责加密传输）。 */
    public volatile String password;
    /** 在线状态。 */
    public volatile Status status;
    /**
     * 视频通道数（默认 1 = 单路 RTSP）。多通道设备注册时指定。
     */
    public volatile int totalCameras = 1;

    /**
     * 在线视频通道数（<b>部分通道可离线</b>：4 路中 2 路在线的真实表达）。
     * <p>
     * 维护规则：
     * <ul>
     *   <li>注册时默认 = totalCameras（全在线）</li>
     *   <li>链路断（{@code pruneStaleDevices} 置 OFFLINE）时归零——整体离线则通道全不可用</li>
     *   <li>心跳把 OFFLINE 拉回 ONLINE 时恢复 = totalCameras（默认全恢复，坏通道由运维经
     *       {@code POST /devices/{id}/channels} 下调）</li>
     *   <li>FAULT/MAINTENANCE 人工态期间不自动改动</li>
     * </ul>
     */
    public volatile int onlineCameras = 1;
    /** 设备能力集合（如 "Media"、"PTZ"、"Events"、"Device"）。 */
    private volatile Set<String> capabilities = Collections.emptySet();
    /** RTSP 流地址（由 OnvifClient 解析得到）。 */
    public volatile String rtspUrl;
    /**
     * 单帧快照地址（HTTP，返回 {@code image/jpeg}），<b>由调用方显式给出</b>。
     *
     * <p>为什么是"显式给出"而不是按厂商规范推导：{@link #rtspUrl} 是推导出来的
     * （{@code rtsp://user:pass@ip:554/…}），格式对真实设备可能正确，但代码从未
     * 向设备确认过（见 {@code StreamUrlOrigin.deviceVerified}）。若快照地址也照
     * 厂商惯例拼一个（海康 {@code /ISAPI/Streaming/channels/N/httppreview} 之类），
     * 就会再造一个"看着对但没验证过"的地址 —— 这正是本轮一直在清的东西。
     *
     * <p>所以这里是<b>配置</b>而非<b>推导</b>：注册时给一个真实可达的 HTTP 端点，
     * 它要么连得上（画面真的出来），要么连不上（{@code /mjpeg} 明确报错）。
     * 没有这一项的设备，界面如实显示"无浏览器可播的信号源"，而不是挂一个空格子。
     *
     * <p>它同时是<b>唯一</b>让浏览器能真正看到画面的通道：浏览器不能原生播 RTSP，
     * 而本平台没有 RTSP 解码器（无 ffmpeg/javacv 依赖），所以"浏览器可播"只能走
     * HTTP 上的帧序列。
     */
    public volatile String snapshotUrl;
    /** 最近一次心跳时间戳（System.currentTimeMillis()）。 */
    public volatile long lastHeartbeatMs;
    /**
     * 首次注册/进程内首次加载时间戳（System.currentTimeMillis()）。
     * <p>
     * 运行时长（uptime）推导基准：{@code now - firstSeenMs}。JPA 实体不持久化
     * 该字段，进程重启后从加载时刻重新计数（设备模型无出厂/激活时间字段）。
     */
    public volatile long firstSeenMs;
    /** 租户 ID（用于租户隔离，null 表示全局管理员或未设置）。 */
    public volatile Integer tenantId;

    public SurveillanceDevice(String id, String name, Vendor vendor, String ip, int port,
                              String username, String password) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("device id must not be blank");
        }
        if (vendor == null) {
            throw new IllegalArgumentException("vendor must not be null");
        }
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("ip must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in [1, 65535]");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        this.id = id;
        this.name = name;
        this.vendor = vendor;
        this.ip = ip;
        this.port = port;
        this.username = username;
        this.password = password;
        this.status = Status.ONLINE;
        this.lastHeartbeatMs = System.currentTimeMillis();
        this.firstSeenMs = this.lastHeartbeatMs;
    }

    /** 获取设备能力集合（不可变视图）。 */
    public Set<String> getCapabilities() {
        return capabilities;
    }

    /** 设置设备能力集合（防御性拷贝）。 */
    public void setCapabilities(Set<String> capabilities) {
        this.capabilities = capabilities == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
    }

    /**
     * 触发一次心跳：更新 lastHeartbeatMs；OFFLINE → ONLINE。
     * <p>
     * **不覆盖 FAULT/MAINTENANCE**：它们是人工/检测置位（报障待修、检修中），
     * 心跳只证明链路可达，不代表故障恢复或检修结束——恢复需显式置回 ONLINE
     * （REST {@code /devices/{id}/status}）。离线扫描（{@code pruneStaleDevices}）
     * 同样只动 ONLINE 设备，四态语义在这些既有路径上闭环。
     */
    public synchronized void heartbeat() {
        this.lastHeartbeatMs = System.currentTimeMillis();
        if (this.status == Status.OFFLINE) {
            this.status = Status.ONLINE;
            // 链路恢复：通道默认全恢复（坏通道由运维经 REST 下调；
            // 心跳不探知设备侧通道故障——它只证明链路可达）
            this.onlineCameras = this.totalCameras;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SurveillanceDevice)) return false;
        SurveillanceDevice that = (SurveillanceDevice) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SurveillanceDevice{id=" + id
                + ", name=" + name
                + ", vendor=" + vendor
                + ", ip=" + ip + ":" + port
                + ", status=" + status + "}";
    }
}