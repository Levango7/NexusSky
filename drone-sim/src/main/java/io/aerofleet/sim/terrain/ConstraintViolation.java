package io.aerofleet.sim.terrain;

/**
 * 约束违反项（FR-21）。
 * <p>
 * 飞行约束检查器综合检查后返回的违反项，携带限制类型、描述、限制值与实际值。
 *
 * @param type        限制类型
 * @param description 描述
 * @param limitValue  限制值
 * @param actualValue 实际值
 */
public record ConstraintViolation(
        RestrictionType type,
        String description,
        double limitValue,
        double actualValue
) {
}