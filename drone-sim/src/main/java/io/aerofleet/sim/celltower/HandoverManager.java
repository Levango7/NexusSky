package io.aerofleet.sim.celltower;

import io.aerofleet.mavlink.messages.CellHandoverMsg;
import io.aerofleet.sim.SimLog;
import io.aerofleet.sim.mesh.MeshRouter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 漫游切换管理器（M6 移动基站载荷抽象，FR-HO-01~07）。
 * <p>
 * 管理终端在机间覆盖区的无缝切换：
 * <ol>
 *   <li>源机断开终端</li>
 *   <li>通过 mesh 信令通道通知目标机注册终端（FR-HO-03）</li>
 *   <li>目标机确认注册</li>
 *   <li>通知源机释放资源</li>
 * </ol>
 * 全程对终端无感（无缝切换，FR-HO-02）。
 * <p>
 * mesh 信令通道复用 M5 {@link MeshRouter#sendTo}（FR-NFR-COMP-01）。
 */
public final class HandoverManager {

    /** 漫游切换超时阈值（ms，FR-HO-07）。 */
    public static final long HANDOVER_TIMEOUT_MS = 500;

    private final int selfSysid;
    private final MeshRouter meshRouter;
    /** 进行中的漫游记录：terminalId → record。 */
    private final ConcurrentMap<Integer, HandoverRecord> pendingHandovers = new ConcurrentHashMap<>();

    public HandoverManager(int selfSysid, MeshRouter meshRouter) {
        this.selfSysid = selfSysid;
        this.meshRouter = meshRouter;
    }

    /**
     * 发起漫游切换（FR-HO-01 / FR-HO-02）。
     * <p>
     * 构造 CellHandoverMsg 并通过 mesh 路由发送到目标机。
     *
     * @param terminalId  被切换终端 ID
     * @param toSysid     目标无人机 sysid
     * @param reason      切换原因
     * @param nowMs       当前时间戳
     * @return 漫游记录（status=INITIATED）
     */
    public HandoverRecord initiateHandover(int terminalId, int toSysid,
                                           HandoverReason reason, long nowMs) {
        if (toSysid == selfSysid) {
            SimLog.warn("handover ignored: toSysid == fromSysid == " + selfSysid);
            return null;
        }
        HandoverRecord record = HandoverRecord.initiated(terminalId, selfSysid, toSysid, reason, nowMs);
        pendingHandovers.put(terminalId, record);

        // 构造 CellHandoverMsg 并通过 mesh 路由发送（FR-HO-03）
        CellHandoverMsg msg = new CellHandoverMsg(terminalId, selfSysid, toSysid, reason.ordinalCode());
        if (meshRouter != null) {
            MeshRouter.SendResult result = meshRouter.sendTo(toSysid, msg.encode());
            SimLog.info("handover initiated: terminal=" + terminalId
                    + " from=" + selfSysid + " to=" + toSysid
                    + " reason=" + reason + " sendResult=" + result);
            if (result == MeshRouter.SendResult.DROPPED_NO_ROUTE) {
                // mesh 不可达：降级本地注销（FR-HO-06）
                SimLog.warn("HANDOVER_MESH_UNREACHABLE: terminal=" + terminalId + " to=" + toSysid);
                return record.rolledBack(nowMs);
            }
        } else {
            SimLog.warn("handover mesh router null, cannot send: terminal=" + terminalId);
        }
        return record;
    }

    /**
     * 接收漫游切换信令（目标机侧，FR-HO-02 步骤 2-3）。
     * <p>
     * 目标机收到 CellHandoverMsg 后，执行终端注册。
     *
     * @param msg         CellHandoverMsg
     * @param localCell   本机基站载荷（用于注册终端）
     * @param nowMs       当前时间戳
     * @return true 接受漫游；false 拒绝（容量满等）
     */
    public boolean onHandoverReceived(CellHandoverMsg msg, CellTowerPayload localCell, long nowMs) {
        if (msg.toSysid != selfSysid) {
            return false; // 不是发给我的
        }
        // 构造终端并尝试注册
        GroundTerminal terminal = new GroundTerminal(
                msg.terminalId, TerminalType.PHONE, 0, 0, nowMs, nowMs, 1.4);
        AccessResult result = localCell.handleRegister(terminal);
        if (result.success) {
            SimLog.info("handover accepted: terminal=" + msg.terminalId
                    + " from=" + msg.fromSysid + " to=" + selfSysid);
            return true;
        } else {
            SimLog.warn("handover rejected: terminal=" + msg.terminalId
                    + " errorCode=" + result.errorCode);
            return false;
        }
    }

    /**
     * 确认漫游完成（源机侧，FR-HO-02 步骤 4）。
     * <p>
     * 目标机确认注册成功后，源机释放终端资源。
     *
     * @param terminalId  被切换终端 ID
     * @param localCell   本机基站载荷（用于释放终端）
     * @param nowMs       当前时间戳
     * @return 完成的漫游记录
     */
    public HandoverRecord confirmHandover(int terminalId, CellTowerPayload localCell, long nowMs) {
        HandoverRecord record = pendingHandovers.remove(terminalId);
        if (record == null) {
            return null;
        }
        localCell.handleLogout(terminalId);
        HandoverRecord completed = record.completed(nowMs);
        SimLog.info("handover completed: terminal=" + terminalId
                + " dur=" + completed.durationMs() + "ms");
        return completed;
    }

    /**
     * 回滚漫游（FR-HO-05 目标机满载 / FR-HO-07 超时）。
     *
     * @param terminalId  被切换终端 ID
     * @param nowMs       当前时间戳
     * @return 回滚的漫游记录
     */
    public HandoverRecord rollback(int terminalId, long nowMs) {
        HandoverRecord record = pendingHandovers.remove(terminalId);
        if (record == null) {
            return null;
        }
        HandoverRecord rolledBack = record.rolledBack(nowMs);
        SimLog.warn("handover rolled back: terminal=" + terminalId
                + " reason=" + rolledBack.reason);
        return rolledBack;
    }

    /**
     * 扫描超时漫游（FR-HO-07）。
     *
     * @param nowMs 当前时间戳
     * @return 超时回滚的记录列表
     */
    public java.util.List<HandoverRecord> scanTimeouts(long nowMs) {
        java.util.List<HandoverRecord> timeouts = new java.util.ArrayList<>();
        for (var entry : pendingHandovers.entrySet()) {
            HandoverRecord r = entry.getValue();
            if (nowMs - r.startedAtMs > HANDOVER_TIMEOUT_MS) {
                HandoverRecord timeout = r.timeout(nowMs);
                pendingHandovers.remove(entry.getKey());
                timeouts.add(timeout);
                SimLog.warn("HANDOVER_TIMEOUT: terminal=" + r.terminalId
                        + " from=" + r.fromSysid + " to=" + r.toSysid
                        + " dur=" + timeout.durationMs() + "ms");
            }
        }
        return timeouts;
    }

    /** 进行中的漫游数。 */
    public int pendingCount() {
        return pendingHandovers.size();
    }
}