package io.aerofleet.cloud.mission.formation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FormationCreateRequest} 的 JSR-303 约束契约测试。
 * <p>
 * <b>这个 DTO 与前四个不同，这是重点</b>：它的入口
 * {@code FormationController.create} <b>没有 {@code @Valid}</b>（全仓唯一一个），
 * 所以这里的注解<b>根本不会执行</b> —— 真正生效的是 {@code FormationService.create()}
 * 里手写的那几条检查（成员数上下界、shape 缺省、spacing/heading/经纬高）。
 * <p>
 * 因此本测试只回答一个问题：<b>注解本身是良构的吗</b>（改成不适用的类型、或者
 * value 拼错时会不会被 validator 立刻发现）。它<b>不</b>声称这些约束在 REST 入口生效。
 * 断言"入口真的会拦"的是 {@code FormationServiceTest} 里的
 * {@code memberCountAboveDeclaredMaxRejected} / {@code nullShapeRejectedWithBadRequest} /
 * {@code memberCountExactlyAtMaxAccepted} 三条。
 */
@DisplayName("FormationCreateRequest 的 JSR-303 约束良构性（入口无 @Valid，注解不执行）")
class FormationCreateRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static final Set<Integer> MEMBERS_2 = Set.of(1, 2);
    private static final Set<Integer> MEMBERS_32 = membersOf(32);
    private static final Set<Integer> MEMBERS_33 = membersOf(33);

    private static Set<Integer> membersOf(int n) {
        Set<Integer> s = new LinkedHashSet<>();
        for (int i = 1; i <= n; i++) {
            s.add(i);
        }
        return s;
    }

    private FormationCreateRequest req(Set<Integer> members, FormationGeometry.Shape shape) {
        return new FormationCreateRequest(members, shape, 5.0, 0,
                22.5907, 113.9345, 50.0, 0);
    }

    private static boolean hasViolationOn(Set<ConstraintViolation<FormationCreateRequest>> v, String path) {
        return v.stream().anyMatch(c -> c.getPropertyPath().toString().endsWith(path));
    }

    @Test
    @DisplayName("注解良构：validator 能正常求值，不抛 UnexpectedTypeException")
    void annotationsAreWellFormed() {
        // 走一遍求值，确认注解与字段类型匹配（不匹配会抛 ValidationException）
        java.util.List<Set<ConstraintViolation<FormationCreateRequest>>> results = java.util.Arrays.asList(
                validator.validate(req(MEMBERS_32, FormationGeometry.Shape.LINE)),
                validator.validate(req(MEMBERS_33, FormationGeometry.Shape.CIRCLE)));
        assertThat(results).as("每个组合都能求值，不能抛异常").hasSize(2);
    }

    @Test
    @DisplayName("成员数 @Size(min=2, max=MAX_MEMBERS) 在注解层求值正确")
    void memberSizeAnnotationEvaluates() {
        assertThat(hasViolationOn(validator.validate(req(Set.of(1), FormationGeometry.Shape.LINE)), "members"))
                .as("1 个成员应违反 @Size(min = 2)").isTrue();
        assertThat(hasViolationOn(validator.validate(req(MEMBERS_32, FormationGeometry.Shape.LINE)), "members"))
                .as("32 个正好等于上限，注解层应放行").isFalse();
        assertThat(hasViolationOn(validator.validate(req(MEMBERS_33, FormationGeometry.Shape.LINE)), "members"))
                .as("33 个应违反 @Size(max = MAX_MEMBERS)").isTrue();
    }

    @Test
    @DisplayName("shape 的 @NotNull 能被报告")
    void shapeNotNullAnnotationEvaluates() {
        assertThat(hasViolationOn(validator.validate(req(MEMBERS_2, null)), "shape")).isTrue();
        assertThat(hasViolationOn(validator.validate(req(MEMBERS_2, FormationGeometry.Shape.VEE)), "shape")).isFalse();
    }

    @Test
    @DisplayName("spacing / heading / 经纬高的注解都能求值")
    void numericAnnotationsEvaluate() {
        double[][] bad = {
                {0.0, 0, 22.5907, 113.9345, 50.0},        // spacing=1 → @Min(2)
                {5.0, 360.0, 22.5907, 113.9345, 50.0},    // heading=360 → @Max(359)
                {5.0, 0, 91.0, 113.9345, 50.0},           // refLat=91 → @Max(90)
                {5.0, 0, 22.5907, 181.0, 50.0},           // refLon=181 → @Max(180)
                {5.0, 0, 22.5907, 113.9345, -1.0},        // refAlt=-1 → @Min(0)
        };
        String[] which = {"spacing", "heading", "refLat", "refLon", "refAlt"};

        for (int i = 0; i < bad.length; i++) {
            FormationCreateRequest r = new FormationCreateRequest(
                    MEMBERS_2, FormationGeometry.Shape.LINE,
                    bad[i][0], bad[i][1], bad[i][2], bad[i][3], bad[i][4], 0);
            assertThat(hasViolationOn(validator.validate(r), which[i]))
                    .as("%s=%s 应被拦下", which[i], bad[i][0] == 0 ? "越界" : "越界")
                    .isTrue();
        }

        // 全部取合法值时，这五个字段都不该报
        FormationCreateRequest ok = new FormationCreateRequest(
                MEMBERS_2, FormationGeometry.Shape.LINE,
                2.0, 359.0, -90.0, -180.0, 0.0, 0);
        Set<ConstraintViolation<FormationCreateRequest>> v = validator.validate(ok);
        for (String f : which) {
            assertThat(hasViolationOn(v, f)).as(f + " 合法时不应报").isFalse();
        }
    }

    @Test
    @DisplayName("合法请求零违规")
    void validRequestHasNoViolations() {
        assertThat(validator.validate(req(MEMBERS_2, FormationGeometry.Shape.LINE))).isEmpty();
    }
}
