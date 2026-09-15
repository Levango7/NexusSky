package io.aerofleet.sim;

/**
 * 单个负载 record（FR-22）。
 * <p>
 * 不可变值对象，描述一个负载的物理属性：
 * <ul>
 *   <li>{@code id}：负载标识（用于 GRIPPER_COMMAND.payloadId）</li>
 *   <li>{@code weightKg}：重量 kg（用于抓取校验 ≤ payloadMax）</li>
 *   <li>{@code volumeL}：体积 L</li>
 *   <li>{@code cogOffset}：重心偏置 m（相对载具重心，用于合成重心计算）</li>
 * </ul>
 *
 * @param id        负载标识
 * @param weightKg  重量 kg
 * @param volumeL   体积 L
 * @param cogOffset 重心偏置 m
 */
public record PayloadItem(int id, double weightKg, double volumeL, double cogOffset) {
}