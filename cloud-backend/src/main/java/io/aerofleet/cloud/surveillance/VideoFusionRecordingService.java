package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频融合的录制登记簿（内存态）。
 * <p>
 * GCS 的 {@code VideoFusionPanel} 允许对任一路画面（安防摄像头或无人机航拍）开关录制。
 * 骨架阶段**不落盘、不转码**：本服务只维护「谁在录、从何时开始」的登记状态，
 * 供面板查询与停止使用。
 *
 * <h2>为什么是登记簿而不是录制器</h2>
 * 真实录制需要拉 RTSP、转码、写存储并对外提供回放地址——那是媒体服务器的职责，
 * 不是本控制面的职责。骨架阶段把「录制」诚实地降级为状态登记：
 * 调用方拿到的 {@code recorded=false / storageUrl=null} 是**当前的真实情况**，
 * 而不是伪造的成功。若把它写成"已保存到 /recordings/x.mp4"，那是一个凭空捏造的路径，
 * 后续接真录像服务时反而要回头清理这个假象。
 *
 * <h2>持久化边界</h2>
 * 与本仓其他内存态一致：进程重启后登记清空。这是有意的——录制本来就是随会话的
 * 瞬时状态，跨重启"恢复录制"没有业务含义。
 *
 * <h2>线程安全</h2>
 * 用 {@link ConcurrentHashMap}；录制开关频率极低（人工点击），不引入额外锁。
 *
 * @see VideoFusionController
 */
@Service
public class VideoFusionRecordingService {

    private static final Logger log = LoggerFactory.getLogger(VideoFusionRecordingService.class);

    /**
     * 单条录制登记（不可变值对象；start/stop 用整体替换而非原地改字段）。
     * <p>
     * tenantId 为登记发起者的可写租户（全局管理员为 null）；NO_ACCESS 归一为 null
     * 由 {@link TenantContext#getWritableTenantId()} 保证，哨兵值永不落进注册表。
     */
    public record Recording(String sourceId, String sourceType, long startedAtMs, Integer tenantId) {
    }

    /** sourceId（设备号或 sysid 字面量）→ 登记。 */
    private final Map<String, Recording> active = new ConcurrentHashMap<>();

    /**
     * 开启录制登记。
     *
     * @param sourceId   源标识：安防设备 id 或无人机 sysid（面板原样传入字符串）
     * @param sourceType {@code surveillance} / {@code drone}，仅用于回读时标注来源
     * @return 登记后的状态（幂等：已在录则原样返回，不重置起始时刻）
     */
    public Recording start(String sourceId, String sourceType) {
        Integer tenantId = TenantContext.getWritableTenantId();
        Recording rec = active.computeIfAbsent(sourceId,
                id -> new Recording(id, sourceType, System.currentTimeMillis(), tenantId));
        if (!rec.sourceType().equals(sourceType)) {
            // 同一 id 先被当作安防设备、后被当作无人机登记（id 命名空间不重叠，
            // 正常不会发生）。保留首次登记的 sourceType，不静默改写来源标签。
            log.warn("录制源 id 冲突，保留首次登记的来源类型: id={} 首次={} 再次={}",
                    sourceId, rec.sourceType(), sourceType);
        }
        return rec;
    }

    /**
     * 停止录制登记。
     *
     * @return 被移除的登记；此前未在录则返回 {@code null}（调用方据此回 404）
     */
    public Recording stop(String sourceId) {
        Recording existing = active.get(sourceId);
        if (existing == null || !visibleTo(TenantContext.getEffectiveTenantId(), existing)) {
            return null;
        }
        Recording removed = active.remove(sourceId);
        if (removed != null) {
            log.info("录制登记已停止: id={} 时长={}ms", sourceId,
                    System.currentTimeMillis() - removed.startedAtMs());
        }
        return removed;
    }

    /** 当前是否在录（跨租户的登记视为未在录）。 */
    public boolean isRecording(String sourceId) {
        Recording rec = active.get(sourceId);
        return rec != null && visibleTo(TenantContext.getEffectiveTenantId(), rec);
    }

    /** 当前全部录制登记（快照，按开始时刻升序；仅本租户可见）。 */
    public List<Recording> list() {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        List<Recording> out = new ArrayList<>(active.values());
        out.removeIf(rec -> !visibleTo(tenantId, rec));
        out.sort((a, b) -> Long.compare(a.startedAtMs(), b.startedAtMs()));
        return out;
    }

    /** 当前在录数量（仅计本租户可见的登记）。 */
    public int activeCount() {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        return (int) active.values().stream()
                .filter(rec -> visibleTo(tenantId, rec))
                .count();
    }

    /**
     * 跨租户可见性，与 {@code DeviceRegistry.isVisibleTo} 同一约定：
     * 全局管理员（effective==null）看全部；其余只看到自己租户的登记。
     * NO_ACCESS 哨兵永不等于任何 tenantId，故看不到任何登记。
     */
    private static boolean visibleTo(Integer effective, Recording rec) {
        return effective == null || effective.equals(rec.tenantId());
    }
}