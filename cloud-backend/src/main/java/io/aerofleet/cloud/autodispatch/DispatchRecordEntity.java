package io.aerofleet.cloud.autodispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 出警历史持久化实体（V31；审计 §2.1 第 4 条）。
 *
 * <p>dispatched_drones 以 JSON 文本存事件快照（拆子表换不来查询价值——这是
 * "当时派了哪几台"，不是可聚合实体）。反序列化失败降级为空列表并 WARN：
 * 历史读取要的是"能看"，一条坏 JSON 不该炸整个历史接口。
 */
@Entity
@Table(name = "dispatch_history")
public class DispatchRecordEntity {

    private static final Logger log = LoggerFactory.getLogger(DispatchRecordEntity.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @Column(name = "dispatch_id", length = 64)
    private String dispatchId;

    @Column(name = "alarm_id", length = 128)
    private String alarmId;

    @Column(name = "trigger_time", nullable = false)
    private long triggerTime;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lon", nullable = false)
    private double lon;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "dispatched_drones", length = 4096)
    private String dispatchedDronesJson;

    @Column(name = "created_at")
    private Instant createdAt;

    protected DispatchRecordEntity() {
        // JPA 用
    }

    /** 事件快照 DTO：只做 JSON 形状，与 DispatchedDrone 的 getter 面一一对应。 */
    public record DroneJson(int sysid, boolean taskAssigned, int estimatedArrivalSec) {
    }

    public static DispatchRecordEntity fromRecord(DispatchRecord r) {
        DispatchRecordEntity e = new DispatchRecordEntity();
        e.dispatchId = r.getDispatchId();
        e.alarmId = r.getAlarmId();
        e.triggerTime = r.getTriggerTime();
        e.lat = r.getLat();
        e.lon = r.getLon();
        e.status = r.getStatus();
        e.createdAt = Instant.now();
        try {
            List<DroneJson> drones = new ArrayList<>();
            for (DispatchResult.DispatchedDrone d : r.getDispatchedDrones()) {
                drones.add(new DroneJson(d.getSysid(), d.isTaskAssigned(), d.getEstimatedArrivalSec()));
            }
            e.dispatchedDronesJson = MAPPER.writeValueAsString(drones);
        } catch (Exception ex) {
            log.warn("dispatch drones 序列化失败（dispatchId={}），按空列表落库：{}",
                    r.getDispatchId(), ex.getMessage());
            e.dispatchedDronesJson = "[]";
        }
        return e;
    }

    public DispatchRecord toRecord() {
        List<DispatchResult.DispatchedDrone> drones = new ArrayList<>();
        try {
            DroneJson[] parsed = MAPPER.readValue(dispatchedDronesJson == null ? "[]" : dispatchedDronesJson,
                    DroneJson[].class);
            for (DroneJson d : parsed) {
                drones.add(new DispatchResult.DispatchedDrone(d.sysid(), d.taskAssigned(),
                        d.estimatedArrivalSec()));
            }
        } catch (Exception ex) {
            log.warn("dispatch drones 反序列化失败（dispatchId={}），按空列表降级：{}",
                    dispatchId, ex.getMessage());
        }
        return new DispatchRecord(dispatchId, alarmId, triggerTime, lat, lon, status, drones);
    }

    public String getDispatchId() {
        return dispatchId;
    }

    public long getTriggerTime() {
        return triggerTime;
    }
}
