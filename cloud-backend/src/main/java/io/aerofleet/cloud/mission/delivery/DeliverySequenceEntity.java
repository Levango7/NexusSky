package io.aerofleet.cloud.mission.delivery;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DeliverySequence 的 JPA 持久化实体。
 * <p>
 * 采用混合模式：内存缓存（{@link DeliveryService} 中的 {@link java.util.concurrent.ConcurrentHashMap}）
 * 保证并发读性能，本实体负责重启后的状态恢复。
 * <p>
 * sites 字段以 JSON 格式存储站点列表，包含每个站点的坐标、负载、状态等信息。
 */
@Entity
@Table(name = "delivery_sequences")
public class DeliverySequenceEntity {

    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "sysid", nullable = false)
    private int sysid;

    /** 配送任务状态：PENDING / RUNNING / COMPLETED / COMPLETED_WITH_SKIPS / FAILED。 */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    /** 当前站点序号。 */
    @Column(name = "current_index")
    private int currentIndex;

    /** 站点列表 JSON（包含坐标、负载、状态等完整信息）。 */
    @Column(name = "sites", length = 8000)
    @Convert(converter = DeliverySiteListConverter.class)
    private List<DeliverySite> sites;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** 租户 ID（数据隔离）。 */
    @Column(name = "tenant_id")
    private Integer tenantId;

    /** JPA 要求的无参构造器。 */
    public DeliverySequenceEntity() {
    }

    // --- getter / setter ---

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getSysid() { return sysid; }
    public void setSysid(int sysid) { this.sysid = sysid; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getCurrentIndex() { return currentIndex; }
    public void setCurrentIndex(int currentIndex) { this.currentIndex = currentIndex; }

    public List<DeliverySite> getSites() { return sites; }
    public void setSites(List<DeliverySite> sites) { this.sites = sites; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer tenantId) { this.tenantId = tenantId; }

    // --- 与值对象的转换 ---

    /**
     * 从 DeliverySequence 值对象创建 JPA Entity。
     *
     * @param seq      DeliverySequence 值对象
     * @param tenantId 租户 ID
     * @return JPA Entity
     */
    public static DeliverySequenceEntity fromSequence(DeliverySequence seq, Integer tenantId) {
        DeliverySequenceEntity entity = new DeliverySequenceEntity();
        entity.setId(seq.deliveryId());
        entity.setSysid(seq.targetSysid());
        entity.setStatus(seq.state().name());
        entity.setCurrentIndex(seq.currentIndex());
        entity.setSites(new ArrayList<>(seq.sites()));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setTenantId(tenantId);
        return entity;
    }

    /**
     * 将 JPA Entity 转换回 DeliverySequence 值对象。
     * <p>
     * 使用 sites 列表重建 DeliverySequence，然后恢复运行时状态。
     *
     * @return DeliverySequence 值对象
     */
    public DeliverySequence toSequence() {
        DeliverySequence seq = new DeliverySequence(id, sysid, sites != null ? sites : new ArrayList<>());
        // 恢复运行时状态
        DeliverySequence.State stateEnum = DeliverySequence.State.valueOf(status);
        switch (stateEnum) {
            case RUNNING -> seq.start();
            case COMPLETED -> { seq.start(); seq.skipRemaining(); }
            case COMPLETED_WITH_SKIPS -> { seq.start(); seq.skipRemaining(); }
            case FAILED -> seq.fail();
            case PENDING -> { /* 默认 PENDING，无需操作 */ }
        }
        return seq;
    }

    /**
     * 更新 Entity 的运行时字段（状态变更时调用）。
     *
     * @param seq 当前 DeliverySequence 值对象
     */
    public void updateFromSequence(DeliverySequence seq) {
        this.status = seq.state().name();
        this.currentIndex = seq.currentIndex();
        this.sites = new ArrayList<>(seq.sites());
        this.updatedAt = Instant.now();
    }
}

/**
 * {@link DeliverySite} 列表的 JSON 序列化转换器。
 * <p>
 * 不引入 Jackson 依赖，手动拼接 JSON 格式。
 * 空列表序列化为空字符串，反序列化空字符串为空列表。
 */
class DeliverySiteListConverter implements AttributeConverter<List<DeliverySite>, String> {

    @Override
    public String convertToDatabaseColumn(List<DeliverySite> sites) {
        if (sites == null || sites.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sites.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            DeliverySite s = sites.get(i);
            sb.append("{")
              .append("\"index\":").append(s.index()).append(",")
              .append("\"lat\":").append(s.lat()).append(",")
              .append("\"lon\":").append(s.lon()).append(",")
              .append("\"alt\":").append(s.alt()).append(",")
              .append("\"payloadId\":").append(s.payloadId()).append(",")
              .append("\"payloadWeightKg\":").append(s.payloadWeightKg()).append(",")
              .append("\"payloadVolumeL\":").append(s.payloadVolumeL()).append(",")
              .append("\"dropAccuracyM\":").append(s.dropAccuracyM()).append(",")
              .append("\"state\":\"").append(s.state().name()).append("\",")
              .append("\"arriveTimeMs\":").append(s.arriveTimeMs()).append(",")
              .append("\"dropTimeMs\":").append(s.dropTimeMs())
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public List<DeliverySite> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        List<DeliverySite> result = new ArrayList<>();
        // 手动解析 JSON 数组格式
        int i = 0;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart == -1) {
                break;
            }
            int objEnd = json.indexOf('}', objStart);
            if (objEnd == -1) {
                break;
            }
            String objStr = json.substring(objStart, objEnd + 1);

            int index = (int) extractNumber(objStr, "\"index\":");
            double lat = extractNumber(objStr, "\"lat\":");
            double lon = extractNumber(objStr, "\"lon\":");
            double alt = extractNumber(objStr, "\"alt\":");
            int payloadId = (int) extractNumber(objStr, "\"payloadId\":");
            double payloadWeightKg = extractNumber(objStr, "\"payloadWeightKg\":");
            double payloadVolumeL = extractNumber(objStr, "\"payloadVolumeL\":");
            double dropAccuracyM = extractNumber(objStr, "\"dropAccuracyM\":");
            String stateStr = extractString(objStr, "\"state\":\"");
            long arriveTimeMs = (long) extractNumber(objStr, "\"arriveTimeMs\":");
            long dropTimeMs = (long) extractNumber(objStr, "\"dropTimeMs\":");

            DeliverySiteState state = DeliverySiteState.valueOf(stateStr);
            result.add(new DeliverySite(index, lat, lon, alt, payloadId,
                    payloadWeightKg, payloadVolumeL, dropAccuracyM,
                    state, arriveTimeMs, dropTimeMs));

            i = objEnd + 1;
        }
        return result;
    }

    /** 从 JSON 对象字符串中提取数值字段。 */
    private static double extractNumber(String json, String key) {
        int start = json.indexOf(key);
        if (start == -1) {
            return 0;
        }
        start += key.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '-') {
                end++;
            } else {
                break;
            }
        }
        return Double.parseDouble(json.substring(start, end));
    }

    /** 从 JSON 对象字符串中提取字符串字段。 */
    private static String extractString(String json, String key) {
        int start = json.indexOf(key);
        if (start == -1) {
            return "";
        }
        start += key.length();
        int end = json.indexOf('"', start);
        if (end == -1) {
            return "";
        }
        return json.substring(start, end);
    }
}