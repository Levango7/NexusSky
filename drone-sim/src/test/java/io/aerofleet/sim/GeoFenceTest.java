package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GeoFence 参数化单元测试：验证禁用围栏、解析规格、多边形包含（射线法）、
 * 高度天花板违规判定与摘要输出。
 *
 * <p>测试模式：直接实例化 + AssertJ 断言，不依赖 Spring 上下文。
 * 覆盖 fail-open 语义（null/空/off/顶点不足/格式错误 → disabled）、
 * 射线法包含测试的多点参数化、LATERAL/CEILING 违规判定。
 */
@DisplayName("GeoFence 地理围栏")
class GeoFenceTest {

    /** 正方形围栏规格：以原点为中心，north/east 范围 [-600,600]，天花板 120m。 */
    private static final String SQUARE_SPEC = "-600,-600:600,-600:600,600:-600,600:120";

    // ---- 禁用围栏 ----

    @Test
    @DisplayName("disabled().contains(任意) 总是 true")
    void disabled_fence_containsAlwaysTrue() {
        GeoFence f = GeoFence.disabled();
        assertThat(f.contains(0, 0)).isTrue();
        assertThat(f.contains(1e9, 1e9)).isTrue();
        assertThat(f.contains(-1e9, -1e9)).isTrue();
        assertThat(f.contains(12345.678, -98765.432)).isTrue();
    }

    @Test
    @DisplayName("disabled().violationAt(任意) 总是 null")
    void disabled_fence_violationAtAlwaysNull() {
        GeoFence f = GeoFence.disabled();
        assertThat(f.violationAt(0, 0, 0)).isNull();
        assertThat(f.violationAt(1e9, 1e9, 1e9)).isNull();
        assertThat(f.violationAt(-1e9, -1e9, -1e9)).isNull();
    }

    // ---- parse: 禁用输入 ----

    @Test
    @DisplayName("parse(null) 返回 disabled")
    void parse_null_returnsDisabled() {
        GeoFence f = GeoFence.parse(null);
        assertThat(f.enabled()).isFalse();
        assertThat(f.contains(0, 0)).isTrue();
        assertThat(f.violationAt(0, 0, 0)).isNull();
    }

    @Test
    @DisplayName("parse(\"\") 返回 disabled")
    void parse_empty_returnsDisabled() {
        GeoFence f = GeoFence.parse("");
        assertThat(f.enabled()).isFalse();
    }

    @Test
    @DisplayName("parse(\"off\") 返回 disabled")
    void parse_off_returnsDisabled() {
        GeoFence f = GeoFence.parse("off");
        assertThat(f.enabled()).isFalse();
    }

    // ---- parse: 有效/无效输入 ----

    @Test
    @DisplayName("parse(正方形规格) 返回 enabled")
    void parse_validSquare_enabled() {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.enabled()).isTrue();
    }

    @Test
    @DisplayName("parse(<3 顶点) 返回 disabled（fail-open）")
    void parse_tooFewVertices_returnsDisabled() {
        GeoFence f = GeoFence.parse("0,0:1,1");
        assertThat(f.enabled()).isFalse();
    }

    @Test
    @DisplayName("parse(格式错误) 返回 disabled（fail-open）")
    void parse_malformed_returnsDisabled() {
        GeoFence f = GeoFence.parse("abc:def");
        assertThat(f.enabled()).isFalse();
    }

    // ---- contains ----

    @Test
    @DisplayName("正方形中心点在内部")
    void contains_centerInside_returnsTrue() {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.contains(0, 0)).isTrue();
    }

    @Test
    @DisplayName("远离多边形的点在外部")
    void contains_outside_returnsFalse() {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.contains(10000, 10000)).isFalse();
    }

    @ParameterizedTest(name = "fence contains({0}, {1}) = {2}")
    @CsvSource({
        "0, 0, true",          // 中心
        "599, 599, true",      // 接近边界
        "601, 601, false",     // 边界外
        "0, 1000, false",      // 远离
        "-500, -500, true"     // 左下角内
    })
    @DisplayName("contains 参数化：多点验证射线法")
    void contains_parameterized(double north, double east, boolean expected) {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.contains(north, east)).isEqualTo(expected);
    }

    // ---- violationAt ----

    @Test
    @DisplayName("围栏内且低于天花板 → null")
    void violationAt_insideBelowCeiling_returnsNull() {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.violationAt(0, 0, 100)).isNull();
    }

    @Test
    @DisplayName("围栏外 → LATERAL")
    void violationAt_outside_returnsLateral() {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.violationAt(700, 0, 50)).isEqualTo(GeoFence.Violation.LATERAL);
    }

    @Test
    @DisplayName("围栏内但高于天花板 → CEILING")
    void violationAt_aboveCeiling_returnsCeiling() {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.violationAt(0, 0, 121)).isEqualTo(GeoFence.Violation.CEILING);
    }

    // ---- ceilingM ----

    @Test
    @DisplayName("parse 后 ceilingM() 返回正确值")
    void ceilingM_parsedCorrectly() {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.ceilingM()).isEqualTo(120.0);
    }

    // ---- summary ----

    @Test
    @DisplayName("disabled().summary() 包含 \"disabled\"")
    void summary_disabled_returnsDisabledString() {
        GeoFence f = GeoFence.disabled();
        assertThat(f.summary()).contains("disabled");
    }

    @Test
    @DisplayName("enabled fence summary 包含顶点数")
    void summary_enabled_returnsVertexCount() {
        GeoFence f = GeoFence.parse(SQUARE_SPEC);
        assertThat(f.summary()).contains("4-vertex");
    }
}