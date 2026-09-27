package io.aerofleet.cloud.geofence;

/**
 * 围栏类型：KEEP_IN（保持在内） / KEEP_OUT（保持在外）。
 * <p>
 * <ul>
 *   <li>{@link #KEEP_IN}：允许活动区，无人机应在围栏内部，越出围栏触发 EXIT 事件。</li>
 *   <li>{@link #KEEP_OUT}：禁飞区，无人机应在围栏外部，进入围栏触发 ENTER 事件；
 *       支持接近缓冲区告警（proximityBufferM）。</li>
 * </ul>
 */
public enum FenceType {
    /** 允许活动区：无人机应在围栏内部，越出围栏触发 EXIT 事件。 */
    KEEP_IN,
    /** 禁飞区：无人机应在围栏外部，进入围栏触发 ENTER 事件；支持接近缓冲区告警。 */
    KEEP_OUT
}