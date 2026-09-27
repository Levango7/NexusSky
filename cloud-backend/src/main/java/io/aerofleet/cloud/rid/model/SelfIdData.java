package io.aerofleet.cloud.rid.model;

/**
 * Self ID 数据，对应 ASTM F3411 Remote ID Self-ID Message。
 * <p>
 * 包含无人机自描述信息：
 * <ul>
 *   <li>{@code descriptionType} — 描述类型（0=Text Description, 1=Private Use）</li>
 *   <li>{@code description} — 描述字符串（最长 23 字符）</li>
 * </ul>
 *
 * @param descriptionType 描述类型
 * @param description     描述字符串
 */
public record SelfIdData(
        int descriptionType,
        String description
) {
}