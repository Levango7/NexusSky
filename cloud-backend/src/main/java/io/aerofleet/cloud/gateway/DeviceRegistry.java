package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.security.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 设备注册表，以 MAVLink systemId 为键。
 * <p>
 * 快照在离线期间保留（机队列表持续显示），可选 JPA 持久化：
 * <ul>
 *   <li>{@code aerofleet.device-registry.persist=false}（默认）：纯内存，向后兼容。</li>
 *   <li>{@code aerofleet.device-registry.persist=true}：同时持久化到 H2/JPA，
 *       重启后恢复已知设备列表，支持设备 provisioning/auth。</li>
 * </ul>
 */
@Component
public class DeviceRegistry { // MUTANT

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    @Value("${aerofleet.heartbeat-timeout-seconds:10}")
    private int heartbeatTimeoutSeconds;

    @Value("${aerofleet.device-registry.persist:false}")
    private boolean persist;

    @Autowired(required = false)
    private DeviceRepository repository;

    private final Map<Integer, DroneSnapshot> drones = new ConcurrentHashMap<>();

    /**
     * 启动时从数据库恢复已知设备列表到内存缓存。
     * <p>
     * 所有恢复的设备初始状态为 offline，等待心跳确认后转为 online。
     * 当 persist=false 或 repository 不可用时直接 return，不影响现有行为。
     *
     * <p>同时还原 V25 新增的三个"最后已知飞行态"（last_mode / last_armed /
     * last_protocol），让 GCS 首屏能显示这架机重启前在做什么。
     * 这三个字段可空，NULL 时**保持 DroneSnapshot 的默认值**（UNKNOWN / false / mavlink）
     * ——不把"没有记录"伪装成"记录为某个确定值"。
     */
    @PostConstruct
    public void restoreFromRepository() {
        if (!persist || repository == null) {
            return;
        }
        try {
            List<DeviceEntity> entities = repository.findAll();
            int count = 0;
            int withFlightState = 0;
            for (DeviceEntity entity : entities) {
                DroneSnapshot snapshot = new DroneSnapshot(entity.getSysid());
                snapshot.online = false;
                snapshot.tenantId = entity.getTenantId();
                // V25：还原上次已知的飞行态（NULL 则维持默认值）
                if (entity.getLastMode() != null) {
                    snapshot.mode = entity.getLastMode();
                    withFlightState++;
                }
                if (entity.getLastArmed() != null) {
                    snapshot.armed = entity.getLastArmed();
                }
                if (entity.getLastProtocol() != null) {
                    snapshot.protocol = entity.getLastProtocol();
                }
                drones.put(entity.getSysid(), snapshot);
                count++;
            }
            log.info("Restored {} devices from repository (all offline, awaiting heartbeat), "
                    + "{} with last-known flight state", count, withFlightState);
        } catch (Exception e) {
            log.warn("设备恢复失败，降级为空缓存: {}", e.getMessage());
        }
    }

    /** Get or create the snapshot for a systemId (called from the receive thread). */
    public DroneSnapshot registerIfAbsent(int sysid) {
        return drones.computeIfAbsent(sysid, id -> {
            DroneSnapshot s = new DroneSnapshot(id);
            s.online = true;
            s.tenantId = resolveTenantFor(id);
            log.info("Drone registered: sysid={} tenantId={}", id, s.tenantId);
            if (persist && repository != null) {
                try {
                    DeviceEntity entity = repository.findById(id)
                            .orElseGet(() -> new DeviceEntity(id));
                    entity.setOnline(true);
                    entity.setLastSeen(java.time.Instant.now());
                    if (s.tenantId != null) {
                        entity.setTenantId(s.tenantId);
                    }
                    // V25：首次见面的飞行态一并记录（此刻通常是 UNKNOWN/未锁定，
                    // 但写下来能让"从没同步过"与"同步过但确实未知"在库里有区分）。
                    writeFlightState(entity, s);
                    repository.save(entity);
                } catch (Exception e) {
                    log.warn("设备持久化失败 sysid={}: {}", id, e.getMessage());
                }
            }
            return s;
        });
    }

    /**
     * 显式把一台设备登记进白名单，不等它先发包。
     * <p>
     * 为什么需要这条腿：白名单开启时陌生 sysid 的帧在 {@code UdpGateway.onFrame} 就被丢弃，
     * 而内存快照原本只由这些被放行的帧创建 —— 于是 prod 首台设备永远注册不上。
     * <p>
     * 登记出的快照是 offline 的（尚未收到心跳），设备首帧到达后由 {@link #registerIfAbsent}
     * 接管在线状态：它对已存在的快照不生效，所以不会重复入库、也不会覆盖这里的归属。
     *
     * @param sysid    MAVLink system id，合法范围 1..254（0 保留，255 是 GCS 自身且始终放行）
     * @param tenantId 归属租户，null 表示未归属（只对全局管理员上下文可见）
     * @return true 本次新建；false 设备已知（内存里已有，未做任何改动）
     * @throws IllegalArgumentException sysid 越界
     */
    public boolean provision(int sysid, Integer tenantId) {
        if (sysid < 1 || sysid > 254) {
            throw new IllegalArgumentException("sysid must be in 1..254, got " + sysid);
        }
        DroneSnapshot snapshot = new DroneSnapshot(sysid);
        snapshot.online = false;
        snapshot.tenantId = tenantId;
        // putIfAbsent：provision 与 UDP 注册线程可能同时建同一台设备的快照
        if (drones.putIfAbsent(sysid, snapshot) != null) {
            return false;
        }
        if (isPersisting()) {
            try {
                DeviceEntity entity = repository.findById(sysid).orElseGet(() -> new DeviceEntity(sysid));
                entity.setOnline(false);
                // 只在显式给了租户时改写：库里已有归属而请求未带的情况，不该被一次重登记抹掉
                // （改归属是 PUT /api/v1/devices/{sysid}/tenant 的职责）
                if (tenantId != null) {
                    entity.setTenantId(tenantId);
                }
                repository.save(entity);
            } catch (Exception e) {
                log.warn("设备登记持久化失败 sysid={}: {}", sysid, e.getMessage());
            }
        }
        log.info("Device provisioned: sysid={} tenantId={} persisted={}", sysid, tenantId, isPersisting());
        return true;
    }

    /** 注册表是否会写数据库（{@code persist=true} 且数据源可用）。 */
    public boolean isPersisting() {
        return persist && repository != null;
    }

    /**
     * 撤销一台已登记设备：从白名单里移除，并在持久化开启时删掉库里的行。
     * <p>
     * 撤销后该 sysid 的帧重新被 {@code UdpGateway} 丢弃，机队列表里也不再出现它。
     * 正在飞的设备被撤销会立刻失联——这是运维意图，不做"在线就拒绝撤销"的额外保护。
     *
     * @param sysid 设备 MAVLink system id
     * @return true 此前确有该条目（内存或库中）；false 设备未知，什么都没做
     */
    public boolean deregister(int sysid) {
        boolean knownInMemory = drones.remove(sysid) != null;
        boolean existedInDb = false;
        if (isPersisting()) {
            try {
                existedInDb = repository.existsById(sysid);
                if (existedInDb) {
                    repository.deleteById(sysid);
                }
            } catch (Exception e) {
                log.warn("设备撤销持久化删除失败 sysid={}: {}", sysid, e.getMessage());
            }
        }
        if (knownInMemory || existedInDb) {
            log.info("Device deregistered: sysid={} removedFromDb={}", sysid, existedInDb);
        }
        return knownInMemory || existedInDb;
    }

    public DroneSnapshot get(int sysid) {
        DroneSnapshot snapshot = drones.get(sysid);
        if (snapshot == null) {
            return null;
        }
        return isVisibleTo(snapshot) ? snapshot : null;
    }

    /**
     * 设备的归属租户，供服务端投递路径（WS 广播、事件监听）使用——这些线程没有请求上下文，
     * 不能走 {@link #get(int)} 的可见性过滤。
     *
     * @return 归属租户 ID；设备未知或未归属时为 null
     */
    public Integer tenantOf(int sysid) {
        DroneSnapshot snapshot = drones.get(sysid);
        return snapshot == null ? null : snapshot.tenantId;
    }

    /**
     * 该 sysid 是否是本机队里真实存在的无人机。
     * <p>
     * 用于区分「设备但未归属租户」与「压根不是设备（基站/卫星/地面中继等基础设施）」：
     * 前者的数据不该出现在任何租户的界面上，后者本就没有租户归属，属公共态势。
     *
     * @param sysid 待判定的 system id
     * @return 已知设备返回 true
     */
    public boolean isKnownDevice(int sysid) {
        return drones.containsKey(sysid);
    }

    /** All known drones, sorted by sysid. Includes offline ones. */
    public List<DroneSnapshot> all() {
        return drones.values().stream()
                .filter(this::isVisibleTo)
                .sorted(Comparator.comparingInt(s -> s.sysid))
                .collect(Collectors.toList());
    }

    /**
     * Sysids with no tenant assigned (visible only to the global-admin context).
     * Used by provisioning to find devices that still need ownership binding.
     */
    public List<Integer> unassigned() {
        return drones.values().stream()
                .filter(s -> s.tenantId == null)
                .map(s -> s.sysid)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Bind a device to a tenant. Applies to the in-memory snapshot and, when
     * {@code aerofleet.device-registry.persist=true}, to the persisted row.
     *
     * @return false when the device is unknown (never seen and not persisted)
     */
    public boolean assignTenant(int sysid, Integer tenantId) {
        DroneSnapshot snapshot = drones.get(sysid);
        if (snapshot == null && !(persist && repository != null && repository.existsById(sysid))) {
            return false;
        }
        if (snapshot != null) {
            snapshot.tenantId = tenantId;
        }
        if (persist && repository != null) {
            try {
                DeviceEntity entity = repository.findById(sysid).orElseGet(() -> {
                    DeviceEntity created = new DeviceEntity(sysid);
                    created.setFirstSeen(java.time.Instant.now());
                    return created;
                });
                entity.setTenantId(tenantId);
                repository.save(entity);
            } catch (Exception e) {
                log.warn("设备租户归属持久化失败 sysid={}: {}", sysid, e.getMessage());
            }
        }
        log.info("Device tenant assigned: sysid={} tenantId={}", sysid, tenantId);
        return true;
    }

    /**
     * Tenant for a newly created snapshot: the request context when present
     * (REST-initiated registration), otherwise the persisted row's tenant so an
     * offline device that was already assigned does not lose its ownership on
     * the next heartbeat. The UDP receive thread has no request context, so it
     * takes the second branch.
     */
    private Integer resolveTenantFor(int sysid) {
        Integer contextTenant = TenantContext.getEffectiveTenantId();
        if (contextTenant != null) {
            return contextTenant;
        }
        if (persist && repository != null) {
            try {
                return repository.findById(sysid)
                        .map(DeviceEntity::getTenantId)
                        .orElse(null);
            } catch (Exception e) {
                log.warn("读取设备归属失败 sysid={}: {}", sysid, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Tenant visibility. A null effective tenant means the global-admin context
     * (including dev-mode, where no TenantFilter runs) and sees everything.
     * A device with no tenant is <em>unassigned</em> and must not be visible or
     * controllable by any tenant — UDP-registered devices get their snapshot
     * created on the receive thread, where there is no request context.
     */
    private boolean isVisibleTo(DroneSnapshot snapshot) {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        return tenantId == null || tenantId.equals(snapshot.tenantId);
    }

    /**
     * Sweep run by the background scanner: mark snapshots stale when no
     * HEARTBEAT arrived within the timeout window. Returns the sysids that
     * transitioned online -> offline on this pass.
     */
    /**
     * 把内存快照里的三个"最后已知飞行态"搬到实体上（V25）。
     *
     * <p><b>为什么只在注册与离线两个时刻写</b>：mode/armed/protocol 是低频语义状态，
     * 逐帧写会直接放大成遥测频率的 DB 写（那正是 FlightTrackStore 要用
     * {@code PERSIST_INTERVAL} 节流的原因）。注册与离线转换都是**低频且语义明确**的
     * 时刻，且这两处本来就要写 devices 行，因此不新增写放大。
     *
     * <p><b>已知局限（诚实记录，不假装解决）</b>：后端异常杀掉（kill -9 / OOM /
     * 断电）时不会经历离线转换，最近一次的 mode/armed 会丢，库里仍是上一次
     * 成功写入的值。要覆盖这种场景就得引入周期节流写，那是另一个取舍——
     * 现在刻意不做，改为把局限写明。
     */
    private void writeFlightState(DeviceEntity entity, DroneSnapshot snapshot) {
        entity.setLastMode(snapshot.mode);
        entity.setLastArmed(snapshot.armed);
        entity.setLastProtocol(snapshot.protocol);
    }

    public List<Integer> sweepOffline() {
        long cutoff = heartbeatTimeoutSeconds * 1000L;
        return drones.values().stream()
                .filter(s -> s.online && s.lastHeartbeatMs > 0
                        && System.currentTimeMillis() - s.lastHeartbeatMs > cutoff)
                .peek(s -> {
                    s.online = false;
                    log.warn("Drone offline (heartbeat timeout {}s): sysid={}",
                            heartbeatTimeoutSeconds, s.sysid);
                    if (persist && repository != null) {
                        try {
                            repository.findById(s.sysid).ifPresent(entity -> {
                                entity.setOnline(false);
                                // V25：离线这一刻正是"这架机是怎么收场的"最有价值的时刻，
                                // 顺路把飞行态落库。走的是本来就要写的那一行，不新增写放大。
                                writeFlightState(entity, s);
                                repository.save(entity);
                            });
                        } catch (Exception e) {
                            log.warn("设备离线持久化失败 sysid={}: {}", s.sysid, e.getMessage());
                        }
                    }
                })
                .map(s -> s.sysid)
                .collect(Collectors.toList());
    }
}
