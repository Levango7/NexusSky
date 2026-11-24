package io.aerofleet.cloud.mission.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.security.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MissionPlanStore {

    private static final Logger log = LoggerFactory.getLogger(MissionPlanStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<StoredMissionItem>> ITEM_LIST =
            new TypeReference<>() { };

    @Value("${aerofleet.mission.persist:false}")
    private boolean persist;

    @Autowired(required = false)
    private MissionPlanRepository repository;

    private final Map<Integer, MissionPlan> plans = new ConcurrentHashMap<>();

    public MissionPlanStore() {
    }

    public MissionPlanStore(MissionPlanRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void restoreFromRepository() {
        if (!isPersisting()) {
            return;
        }
        try {
            for (MissionPlanEntity entity : repository.findAll()) {
                MissionPlan plan = hydrate(entity);
                if (plan != null) {
                    plans.put(plan.sysid(), plan);
                }
            }
        } catch (Exception e) {
            log.warn("任务快照恢复失败，降级为空缓存: {}", e.getMessage());
        }
    }

    public void save(int sysid, Integer tenantId, List<StoredMissionItem> items, String source) {
        List<StoredMissionItem> snapshot = List.copyOf(items);
        MissionPlan plan = new MissionPlan(sysid, tenantId, snapshot, source, Instant.now());
        plans.put(sysid, plan);
        if (!isPersisting()) {
            return;
        }
        try {
            MissionPlanEntity entity = new MissionPlanEntity();
            entity.setSysid(sysid);
            entity.setTenantId(tenantId);
            entity.setItemsJson(JSON.writeValueAsString(snapshot));
            entity.setItemCount(snapshot.size());
            entity.setSource(source);
            entity.setUpdatedAt(plan.updatedAt());
            repository.save(entity);
        } catch (Exception e) {
            log.warn("任务快照落库失败 sysid={}: {}", sysid, e.getMessage());
        }
    }

    public MissionPlan findVisible(int sysid) {
        MissionPlan plan = plans.get(sysid);
        if (plan == null) {
            plan = readThrough(sysid);
        }
        return plan != null && visibleTo(plan) ? plan : null;
    }

    public void delete(int sysid) {
        if (isPersisting()) {
            try {
                repository.deleteById(sysid);
            } catch (Exception e) {
                log.warn("任务快照删除失败 sysid={}: {}", sysid, e.getMessage());
                throw new IllegalStateException("任务快照删除失败 sysid=" + sysid, e);
            }
        }
        afterCommit(() -> plans.remove(sysid));
    }

    public void assignTenant(int sysid, Integer tenantId) {
        if (isPersisting()) {
            try {
                repository.findById(sysid).ifPresent(entity -> {
                    entity.setTenantId(tenantId);
                    repository.save(entity);
                });
            } catch (Exception e) {
                log.warn("任务快照租户同步失败 sysid={}: {}", sysid, e.getMessage());
                throw new IllegalStateException("任务快照租户同步失败 sysid=" + sysid, e);
            }
        }
        MissionPlan plan = plans.get(sysid);
        if (plan != null) {
            afterCommit(() -> plans.put(sysid,
                    new MissionPlan(sysid, tenantId, plan.items(), plan.source(), plan.updatedAt())));
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private MissionPlan readThrough(int sysid) {
        if (!isPersisting()) {
            return null;
        }
        try {
            MissionPlan plan = repository.findById(sysid).map(this::hydrate).orElse(null);
            if (plan != null) {
                plans.put(sysid, plan);
            }
            return plan;
        } catch (Exception e) {
            log.warn("任务快照读取失败 sysid={}: {}", sysid, e.getMessage());
            return null;
        }
    }

    private MissionPlan hydrate(MissionPlanEntity entity) {
        try {
            List<StoredMissionItem> items = JSON.readValue(entity.getItemsJson(), ITEM_LIST);
            return new MissionPlan(entity.getSysid(), entity.getTenantId(), List.copyOf(items),
                    entity.getSource(), entity.getUpdatedAt());
        } catch (Exception e) {
            log.warn("任务快照 JSON 损坏 sysid={}: {}", entity.getSysid(), e.getMessage());
            return null;
        }
    }

    private boolean visibleTo(MissionPlan plan) {
        Integer effectiveTenant = TenantContext.getEffectiveTenantId();
        return effectiveTenant == null || effectiveTenant.equals(plan.tenantId());
    }

    private boolean isPersisting() {
        return persist && repository != null;
    }

    public record StoredMissionItem(int seq, int command, double lat, double lon,
                                    double alt, double holdTime, int frame) {
        public Map<String, Object> toView() {
            return Map.of("seq", seq, "command", command, "lat", lat, "lon", lon,
                    "alt", alt, "holdTime", holdTime, "frame", frame);
        }

        public static StoredMissionItem from(io.aerofleet.mavlink.messages.MissionItemInt item) {
            return new StoredMissionItem(item.seq, item.command, item.x / 1e7, item.y / 1e7,
                    item.z, item.param1, item.frame);
        }
    }

    public record MissionPlan(int sysid, Integer tenantId, List<StoredMissionItem> items,
                              String source, Instant updatedAt) {
        public List<Map<String, Object>> toView() {
            return items.stream().map(StoredMissionItem::toView).toList();
        }
    }
}
