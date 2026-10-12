package io.aerofleet.cloud.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 设备注册 JPA 实体，用于持久化已知设备列表。
 * <p>
 * 仅保存设备标识与基本状态，完整遥测数据仍在内存 {@link DroneSnapshot} 中。
 * 用于重启后恢复已知设备列表和设备 provisioning/auth。
 */
@Entity
@Table(name = "devices")
public class DeviceEntity {

    @Id
    @Column(name = "sysid")
    private Integer sysid;

    @Column(name = "online")
    private Boolean online;

    @Column(name = "last_heartbeat_ms")
    private Long lastHeartbeatMs;

    @Column(name = "first_seen")
    private Instant firstSeen;

    @Column(name = "last_seen")
    private Instant lastSeen;

    /** 设备名称（provisioning 时设置）。 */
    @Column(name = "name")
    private String name;

    /** 租户 ID（数据隔离）。 */
    @Column(name = "tenant_id")
    private Integer tenantId;

    /**
     * 最后已知飞行模式标签（如 {@code AUTO.MISSION}）。
     *
     * <p><b>为什么加这三个字段</b>：重启后 {@link DeviceRegistry#restoreFromRepository()}
     * 只还原 sysid 与 tenantId，GCS 首屏拿不到"这架机最后在做什么"。
     * 位置/电量已由 {@code drone_last_known_position} 走 FlightTrackStore 持久化
     * （见 {@code C4TelemetryRestoreTest}），但这三个飞行态字段没有任何持久化出路。
     *
     * <p><b>为什么不做"全量快照"</b>：电量/经纬/姿态本就属高频瞬时态，
     * 已有专门的 last-known 表按节流落库；这里只补"低频、有决策价值"的三个标量，
     * 且与 lastSeen 同批写（心跳路径本来就在写 devices 行），不新增写放大。
     *
     * <p>可空：历史行没有这三个列，NULL = 未知，与 {@code DroneSnapshot} 的
     * {@code UNKNOWN}/{@code mavlink} 默认值语义区分开（NULL 表示"没有记录"）。
     */
    @Column(name = "last_mode")
    private String lastMode;

    /** 最后已知armed状态；NULL = 无记录。 */
    @Column(name = "last_armed")
    private Boolean lastArmed;

    /** 最后已知设备协议（{@code mavlink} / {@code dji-cloud}）；NULL = 无记录。 */
    @Column(name = "last_protocol")
    private String lastProtocol;

    /**
     * 最近一次收到该设备**通过验签的签名帧**的时刻；NULL = 从未见过它的签名帧。
     *
     * <p>这是 C5 收紧姿态唯一的地面证据：{@code reject-unsigned=true} 之后，没配密钥的设备不会报错、
     * 不会掉线通知，只是遥测静默消失。所以"能不能翻 strict"只能问"在线设备是不是都有这个时间戳"。
     *
     * <p>由 {@link DeviceRegistry#markSigningVerified(int)} 按节流写入（每帧写库会把 ingestion 压垮）。
     */
    @Column(name = "signing_verified_at")
    private Instant signingVerifiedAt;

    /**
     * 地面为该设备生成/轮换过的签名密钥**指纹**（非明文）；NULL = 地面没给过它密钥。
     *
     * <p>与 {@link #signingVerifiedAt} 配对使用，用来把两种常被混为一谈的状态分开：
     * "已生成、等现场写进飞控"（只有指纹）与"真的在签名"（有 verified 时间）。
     * 只有后者才算过线。
     */
    @Column(name = "signing_key_fingerprint")
    private String signingKeyFingerprint;

    public DeviceEntity() {
    }

    public DeviceEntity(Integer sysid) {
        this.sysid = sysid;
        this.firstSeen = Instant.now();
        this.lastSeen = Instant.now();
    }

    public Integer getSysid() {
        return sysid;
    }

    public void setSysid(Integer sysid) {
        this.sysid = sysid;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public Long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public void setLastHeartbeatMs(Long lastHeartbeatMs) {
        this.lastHeartbeatMs = lastHeartbeatMs;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }

    public String getLastMode() {
        return lastMode;
    }

    public void setLastMode(String lastMode) {
        this.lastMode = lastMode;
    }

    public Boolean getLastArmed() {
        return lastArmed;
    }

    public void setLastArmed(Boolean lastArmed) {
        this.lastArmed = lastArmed;
    }

    public String getLastProtocol() {
        return lastProtocol;
    }

    public void setLastProtocol(String lastProtocol) {
        this.lastProtocol = lastProtocol;
    }

    public Instant getSigningVerifiedAt() {
        return signingVerifiedAt;
    }

    public void setSigningVerifiedAt(Instant signingVerifiedAt) {
        this.signingVerifiedAt = signingVerifiedAt;
    }

    public String getSigningKeyFingerprint() {
        return signingKeyFingerprint;
    }

    public void setSigningKeyFingerprint(String signingKeyFingerprint) {
        this.signingKeyFingerprint = signingKeyFingerprint;
    }
}