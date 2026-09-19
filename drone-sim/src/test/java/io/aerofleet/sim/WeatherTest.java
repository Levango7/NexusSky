package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Weather 单元测试：验证天气枚举的 code/visibilityM 映射与 of() 容错查找。
 *
 * <p>测试模式：直接访问枚举常量 + AssertJ 断言。
 * 覆盖所有 5 个枚举值的 code 与能见度映射（FR-14/FR-17）、
 * of() 对已知/未知/边界 code 的查找行为（未知回退 CLEAR，FR-14 异常容错）。
 *
 * <p>注意：当前 Weather 枚举仅含 code 与 visibilityM 两个字段，
 * 不含风速/风向/温度/湿度（这些属于 EnvironmentModel 范畴）。
 * 本测试聚焦 Weather 枚举实际暴露的属性与方法。
 *
 * <p>测试顺序遵循防御性测试原则：未知/边界 code 优先，再覆盖已知映射。
 */
@DisplayName("Weather 天气枚举")
class WeatherTest {

    // ==================== 枚举常量映射（FR-14 / FR-17） ====================

    @Nested
    @DisplayName("枚举常量 code 与 visibilityM 映射")
    class ConstantMapping {

        @Test
        @DisplayName("CLEAR: code=0, visibilityM=10000")
        void clear_mapping() {
            assertThat(Weather.CLEAR.code).isZero();
            assertThat(Weather.CLEAR.visibilityM).isEqualTo(10_000);
        }

        @Test
        @DisplayName("CLOUDY: code=1, visibilityM=10000")
        void cloudy_mapping() {
            assertThat(Weather.CLOUDY.code).isEqualTo(1);
            assertThat(Weather.CLOUDY.visibilityM).isEqualTo(10_000);
        }

        @Test
        @DisplayName("RAIN: code=2, visibilityM=3000")
        void rain_mapping() {
            assertThat(Weather.RAIN.code).isEqualTo(2);
            assertThat(Weather.RAIN.visibilityM).isEqualTo(3_000);
        }

        @Test
        @DisplayName("SNOW: code=3, visibilityM=1000")
        void snow_mapping() {
            assertThat(Weather.SNOW.code).isEqualTo(3);
            assertThat(Weather.SNOW.visibilityM).isEqualTo(1_000);
        }

        @Test
        @DisplayName("FOG: code=4, visibilityM=300")
        void fog_mapping() {
            assertThat(Weather.FOG.code).isEqualTo(4);
            assertThat(Weather.FOG.visibilityM).isEqualTo(300);
        }

        @Test
        @DisplayName("5 个枚举值，code 从 0 连续递增")
        void valuesCount_andSequentialCodes() {
            Weather[] all = Weather.values();
            assertThat(all).hasSize(5);
            for (int i = 0; i < all.length; i++) {
                assertThat(all[i].code).isEqualTo(i);
            }
        }

        @ParameterizedTest(name = "{0} 能见度 {1}m 在合理范围 [0, 10000]")
        @CsvSource({
                "CLEAR, 10000",
                "CLOUDY, 10000",
                "RAIN, 3000",
                "SNOW, 1000",
                "FOG, 300"
        })
        @DisplayName("各天气能见度均为正值且 ≤ 10000m")
        void visibilityBounded(Weather w, int expectedVis) {
            assertThat(w.visibilityM).isEqualTo(expectedVis);
            assertThat(w.visibilityM).isPositive();
            assertThat(w.visibilityM).isLessThanOrEqualTo(10_000);
        }
    }

    // ==================== of() 未知/边界 code（防御性优先） ====================

    @Nested
    @DisplayName("of() 未知 code 回退 CLEAR")
    class OfUnknownCode {

        @Test
        @DisplayName("of(-1) 回退 CLEAR")
        void of_negativeOne_returnsClear() {
            assertThat(Weather.of(-1)).isEqualTo(Weather.CLEAR);
        }

        @Test
        @DisplayName("of(Integer.MIN_VALUE) 回退 CLEAR")
        void of_minInt_returnsClear() {
            assertThat(Weather.of(Integer.MIN_VALUE)).isEqualTo(Weather.CLEAR);
        }

        @Test
        @DisplayName("of(5) 超出最大 code 回退 CLEAR")
        void of_five_returnsClear() {
            assertThat(Weather.of(5)).isEqualTo(Weather.CLEAR);
        }

        @Test
        @DisplayName("of(Integer.MAX_VALUE) 回退 CLEAR")
        void of_maxInt_returnsClear() {
            assertThat(Weather.of(Integer.MAX_VALUE)).isEqualTo(Weather.CLEAR);
        }

        @ParameterizedTest(name = "of({0}) 回退 CLEAR")
        @ValueSource(ints = {-100, -1, 5, 6, 100, 255, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE})
        @DisplayName("参数化：多个未知 code 均回退 CLEAR")
        void of_variousUnknownCodes_returnClear(int code) {
            assertThat(Weather.of(code)).isEqualTo(Weather.CLEAR);
        }
    }

    // ==================== of() 已知 code 正常查找 ====================

    @Nested
    @DisplayName("of() 已知 code 正常查找")
    class OfKnownCode {

        @Test
        @DisplayName("of(0) → CLEAR")
        void of_zero_returnsClear() {
            assertThat(Weather.of(0)).isEqualTo(Weather.CLEAR);
        }

        @Test
        @DisplayName("of(1) → CLOUDY")
        void of_one_returnsCloudy() {
            assertThat(Weather.of(1)).isEqualTo(Weather.CLOUDY);
        }

        @Test
        @DisplayName("of(2) → RAIN")
        void of_two_returnsRain() {
            assertThat(Weather.of(2)).isEqualTo(Weather.RAIN);
        }

        @Test
        @DisplayName("of(3) → SNOW")
        void of_three_returnsSnow() {
            assertThat(Weather.of(3)).isEqualTo(Weather.SNOW);
        }

        @Test
        @DisplayName("of(4) → FOG")
        void of_four_returnsFog() {
            assertThat(Weather.of(4)).isEqualTo(Weather.FOG);
        }

        @ParameterizedTest(name = "of({0}) → {1}")
        @CsvSource({
                "0, CLEAR",
                "1, CLOUDY",
                "2, RAIN",
                "3, SNOW",
                "4, FOG"
        })
        @DisplayName("参数化：所有已知 code 映射正确")
        void of_allKnownCodes_correctMapping(int code, Weather expected) {
            assertThat(Weather.of(code)).isEqualTo(expected);
        }
    }

    // ==================== of() 与 code 的一致性 ====================

    @Nested
    @DisplayName("of() 与 code 往返一致性")
    class OfConsistency {

        @Test
        @DisplayName("对每个枚举值：of(w.code) == w")
        void ofCodeRoundTrip() {
            for (Weather w : Weather.values()) {
                assertThat(Weather.of(w.code)).isEqualTo(w);
            }
        }

        @Test
        @DisplayName("of() 返回值的 code 与输入一致（已知 code 时）")
        void ofPreservesCodeForKnown() {
            for (Weather w : Weather.values()) {
                Weather result = Weather.of(w.code);
                assertThat(result.code).isEqualTo(w.code);
            }
        }
    }

    // ==================== valueOf 字符串查找 ====================

    @Test
    @DisplayName("valueOf(\"CLEAR\") 返回 CLEAR 枚举")
    void valueOf_clear_returnsConstant() {
        assertThat(Weather.valueOf("CLEAR")).isEqualTo(Weather.CLEAR);
    }

    @Test
    @DisplayName("valueOf(\"FOG\") 返回 FOG 枚举")
    void valueOf_fog_returnsConstant() {
        assertThat(Weather.valueOf("FOG")).isEqualTo(Weather.FOG);
    }
}