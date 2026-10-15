package io.aerofleet.cloud.mission.spray;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SprayTaskRequest} 的 JSR-303 约束契约测试。
 * <p>
 * <b>为什么要有这个类</b>：入口 {@code SprayController.create} 有 {@code @Valid}，
 * 而这些约束是<b>唯一防线</b> —— 服务层 {@code SprayTaskService.create()} 只查
 * {@code waypoints >= 2} 与三个字段为正，<b>不查 sysid 范围、不查航点数上界</b>。
 * 这里把「约束确实会报告违规」钉住，防止哪天 {@code @Valid} 掉了而没人发现。
 * <p>
 * <b>关于 0</b>：表面积量三个字段用 {@code @Positive}，与 {@code SprayTaskService.create()}
 * 的 {@code > 0} 同口径（sprayWidth=0 会让 totalArea 恒为 0，capacityMl=0 会让余量
 * 百分比走 0 分支）。两层同时拒绝 0，被拒绝的输入集合与只由服务层拒绝时完全一致 ——
 * 变的只是哪一层先拒、以及错误信息是谁给的。
 */
@DisplayName("SprayTaskRequest 的 JSR-303 约束契约")
class SprayTaskRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /** 两个航点的合法请求。 */
    private SprayTaskRequest valid() {
        SprayTaskRequest r = new SprayTaskRequest();
        r.targetSysid = 7;
        r.waypoints = List.of(new double[]{30.0, 120.0}, new double[]{30.001, 120.0});
        r.targetRate = 1.5;
        r.capacityMl = 2000.0;
        r.sprayWidth = 4.0;
        return r;
    }

    private static boolean hasViolationOn(java.util.Set<ConstraintViolation<SprayTaskRequest>> v, String path) {
        return v.stream().anyMatch(c -> c.getPropertyPath().toString().endsWith(path));
    }

    @Test
    @DisplayName("sysid 0~255 由 Min/Max 捕获（服务层不查这条）")
    void targetSysidRangeIsEnforcedByAnnotation() {
        SprayTaskRequest lo = valid();
        lo.targetSysid = -1;
        assertThat(hasViolationOn(validator.validate(lo), "targetSysid")).isTrue();

        SprayTaskRequest hi = valid();
        hi.targetSysid = 256;
        assertThat(hasViolationOn(validator.validate(hi), "targetSysid")).isTrue();

        SprayTaskRequest ok = valid();
        ok.targetSysid = 0;      // 0 = 任意在线无人机，DTO 明确允许
        assertThat(hasViolationOn(validator.validate(ok), "targetSysid")).isFalse();
    }

    @Test
    @DisplayName("航点必须非空且不超过 1024（服务层只查下限）")
    void waypointCountBoundsComeFromAnnotation() {
        SprayTaskRequest none = valid();
        none.waypoints = null;
        assertThat(validator.validate(none)).as("waypoints=null 应违反 @NotNull").isNotEmpty();

        SprayTaskRequest empty = valid();
        empty.waypoints = List.of();
        assertThat(hasViolationOn(validator.validate(empty), "waypoints")).isTrue();

        SprayTaskRequest tooMany = valid();
        tooMany.waypoints = java.util.Collections.nCopies(1025, new double[]{30.0, 120.0});
        assertThat(hasViolationOn(validator.validate(tooMany), "waypoints"))
                .as("1025 个航点应违反 @Size(max = 1024)").isTrue();

        SprayTaskRequest atMax = valid();
        atMax.waypoints = java.util.Collections.nCopies(1024, new double[]{30.0, 120.0});
        assertThat(hasViolationOn(validator.validate(atMax), "waypoints"))
                .as("1024 个正好等于上限，应放行").isFalse();
    }

    @Test
    @DisplayName("表面积量三个字段必须为正：负值与 0 都被拦（与服务层 >0 同口径）")
    void physicalQuantitiesMustBePositive() {
        SprayTaskRequest r = valid();
        r.targetRate = -1.0;
        r.capacityMl = -1.0;
        r.sprayWidth = -1.0;

        java.util.Set<ConstraintViolation<SprayTaskRequest>> v = validator.validate(r);
        assertThat(hasViolationOn(v, "targetRate")).isTrue();
        assertThat(hasViolationOn(v, "capacityMl")).isTrue();
        assertThat(hasViolationOn(v, "sprayWidth")).isTrue();

        // 0 也要拦 —— 服务层要求 >0，注解曾写 @Min(0) 会与它讲两套话
        SprayTaskRequest zero = valid();
        zero.targetRate = 0.0;
        zero.capacityMl = 0.0;
        zero.sprayWidth = 0.0;
        java.util.Set<ConstraintViolation<SprayTaskRequest>> vz = validator.validate(zero);
        assertThat(hasViolationOn(vz, "targetRate")).as("targetRate=0 应被 @Positive 拦下").isTrue();
        assertThat(hasViolationOn(vz, "capacityMl")).as("capacityMl=0 应被 @Positive 拦下").isTrue();
        assertThat(hasViolationOn(vz, "sprayWidth")).as("sprayWidth=0 应被 @Positive 拦下").isTrue();
    }

    @Test
    @DisplayName("合法请求零违规（sysid=0 是允许的「任意在线无人机」）")
    void validRequestHasNoViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }
}
