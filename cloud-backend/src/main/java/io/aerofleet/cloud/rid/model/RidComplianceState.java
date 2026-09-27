package io.aerofleet.cloud.rid.model;

/**
 * Remote ID 合规状态枚举。
 * <p>
 * 表示无人机 Remote ID 广播的当前合规状态：
 * <ul>
 *   <li>{@code NOT_BROADCASTING} — 未广播</li>
 *   <li>{@code BROADCASTING} — 正在广播</li>
 *   <li>{@code BROADCASTING_ERROR} — 广播错误</li>
 * </ul>
 */
public enum RidComplianceState {
    /** 未广播 Remote ID。 */
    NOT_BROADCASTING,
    /** 正在正常广播 Remote ID。 */
    BROADCASTING,
    /** Remote ID 广播出现错误。 */
    BROADCASTING_ERROR
}