package io.aerofleet.cloud.autodispatch;

import java.util.Collections;
import java.util.List;

/**
 * 自动出警结果（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 描述一次 {@link AutoDispatchService#dispatchDrone} 调用的结果：
 * <ul>
 *   <li>{@link Status#SUCCESS} — 全部请求的无人机已成功派遣</li>
 *   <li>{@link Status#PARTIAL} — 部分无人机派遣成功（可用无人机不足）</li>
 *   <li>{@link Status#NO_DRONE} — 无可用无人机（无在线/电量不足/距离超限）</li>
 * </ul>
 *
 * @see AutoDispatchService
 * @see DispatchedDrone
 */
public class DispatchResult {

    /** 派遣状态。 */
    public enum Status {
        /** 全部无人机派遣成功。 */
        SUCCESS,
        /** 无可用无人机。 */
        NO_DRONE,
        /** 部分无人机派遣成功。 */
        PARTIAL
    }

    /** 派遣 ID（UUID）。 */
    private final String dispatchId;
    /** 派遣状态。 */
    private final Status status;
    /** 已派遣的无人机列表。 */
    private final List<DispatchedDrone> dispatchedDrones;
    /** 结果消息（成功/失败描述）。 */
    private final String message;

    public DispatchResult(String dispatchId, Status status,
                          List<DispatchedDrone> dispatchedDrones, String message) {
        this.dispatchId = dispatchId;
        this.status = status;
        this.dispatchedDrones = dispatchedDrones == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(dispatchedDrones);
        this.message = message;
    }

    public String getDispatchId() {
        return dispatchId;
    }

    public Status getStatus() {
        return status;
    }

    public List<DispatchedDrone> getDispatchedDrones() {
        return dispatchedDrones;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "DispatchResult{dispatchId=" + dispatchId
                + ", status=" + status
                + ", dispatchedDrones=" + dispatchedDrones.size()
                + ", message=" + message + '}';
    }

    /**
     * 单架无人机派遣信息。
     */
    public static class DispatchedDrone {
        /** 无人机 MAVLink systemId。 */
        private final int sysid;
        /** 是否已分配任务。 */
        private final boolean taskAssigned;
        /** 预计到达时间（秒）。 */
        private final int estimatedArrivalSec;

        public DispatchedDrone(int sysid, boolean taskAssigned, int estimatedArrivalSec) {
            this.sysid = sysid;
            this.taskAssigned = taskAssigned;
            this.estimatedArrivalSec = estimatedArrivalSec;
        }

        public int getSysid() {
            return sysid;
        }

        public boolean isTaskAssigned() {
            return taskAssigned;
        }

        public int getEstimatedArrivalSec() {
            return estimatedArrivalSec;
        }

        @Override
        public String toString() {
            return "DispatchedDrone{sysid=" + sysid
                    + ", taskAssigned=" + taskAssigned
                    + ", estimatedArrivalSec=" + estimatedArrivalSec + '}';
        }
    }
}