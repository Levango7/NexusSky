package io.aerofleet.cloud.rid.model;

/**
 * Operator ID 数据，对应 ASTM F3411 Remote ID Operator ID Message。
 * <p>
 * 包含操作者标识信息：
 * <ul>
 *   <li>{@code operatorIdType} — 操作者 ID 类型（0=CAA Registration ID）</li>
 *   <li>{@code operatorId} — 操作者标识字符串（最长 20 字符）</li>
 * </ul>
 *
 * @param operatorIdType 操作者 ID 类型
 * @param operatorId     操作者标识字符串
 */
public record OperatorIdData(
        int operatorIdType,
        String operatorId
) {
}