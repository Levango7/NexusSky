package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * GeoUtil 单元测试：验证本地切平面几何工具的精度与边界条件。
 *
 * <p>测试模式：直接调用静态方法 + AssertJ 精度断言。
 * 覆盖 metersPerDegLat/Lon 在赤道/极点/中纬度的值、east/north 偏移方向与零点、
 * latOf/lonOf 逆运算的往返一致性，以及 EARTH_R 常量。
 *
 * <p>测试顺序遵循防御性测试原则：特殊位置（赤道/极点/同点）优先，再覆盖正常场景与往返精度。
 */
@DisplayName("GeoUtil 几何工具")
class GeoUtilTest {

    /** 测试用容差：浮点比较精度 1e-6。 */
    private static final double EPS = 1e-6;

    /** 1 度纬度对应的米数（恒定）：π/180 * EARTH_R。 */
    private static final double METERS_PER_DEG_LAT = Math.PI / 180.0 * GeoUtil.EARTH_R;

    // ==================== 常量 ====================

    @Test
    @DisplayName("EARTH_R 常量 = 6371000.0 米")
    void earthRadius_constantValue() {
        assertThat(GeoUtil.EARTH_R).isEqualTo(6371000.0);
    }

    // ==================== metersPerDegLat（恒定，不依赖参数） ====================

    @Nested
    @DisplayName("metersPerDegLat 恒定值")
    class MetersPerDegLat {

        @Test
        @DisplayName("任意纬度返回相同值 ≈ 111194.93 m/deg")
        void metersPerDegLat_constantRegardlessOfLatitude() {
            double atEquator = GeoUtil.metersPerDegLat(0);
            double atPole = GeoUtil.metersPerDegLat(90);
            double atMid = GeoUtil.metersPerDegLat(45.0);
            // 三者应相等（实现中参数未参与计算）
            assertThat(atEquator).isCloseTo(METERS_PER_DEG_LAT, within(EPS));
            assertThat(atPole).isCloseTo(METERS_PER_DEG_LAT, within(EPS));
            assertThat(atMid).isCloseTo(METERS_PER_DEG_LAT, within(EPS));
            assertThat(atEquator).isCloseTo(atPole, within(EPS));
        }

        @Test
        @DisplayName("负纬度与正纬度返回相同值")
        void metersPerDegLat_symmetricForNegativeLatitude() {
            assertThat(GeoUtil.metersPerDegLat(45.0))
                    .isCloseTo(GeoUtil.metersPerDegLat(-45.0), within(EPS));
        }
    }

    // ==================== metersPerDegLon（依赖纬度 cos） ====================

    @Nested
    @DisplayName("metersPerDegLon 纬度依赖")
    class MetersPerDegLon {

        @Test
        @DisplayName("赤道（lat=0）处等于 metersPerDegLat")
        void metersPerDegLon_atEquator_equalsLat() {
            assertThat(GeoUtil.metersPerDegLon(0))
                    .isCloseTo(GeoUtil.metersPerDegLat(0), within(EPS));
        }

        @Test
        @DisplayName("极点（lat=90）处 ≈ 0")
        void metersPerDegLon_atPole_approxZero() {
            assertThat(GeoUtil.metersPerDegLon(90)).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("负极点（lat=-90）处 ≈ 0")
        void metersPerDegLon_atNegativePole_approxZero() {
            assertThat(GeoUtil.metersPerDegLon(-90)).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("lat=60 处 = metersPerDegLat * cos(60°) = 0.5 倍")
        void metersPerDegLon_at60deg_halfOfLat() {
            double expected = METERS_PER_DEG_LAT * 0.5;
            assertThat(GeoUtil.metersPerDegLon(60.0)).isCloseTo(expected, within(EPS));
        }

        @Test
        @DisplayName("南北纬对称（cos 偶函数）：lon(45) == lon(-45)")
        void metersPerDegLon_symmetricNorthSouth() {
            assertThat(GeoUtil.metersPerDegLon(45.0))
                    .isCloseTo(GeoUtil.metersPerDegLon(-45.0), within(EPS));
        }

        @ParameterizedTest(name = "lat={0}° → metersPerDegLon 应为正且 ≤ metersPerDegLat")
        @CsvSource({
                "0", "10", "23.45", "45", "60", "80", "89.9", "-30", "-75"
        })
        @DisplayName("metersPerDegLon 在各纬度均为正且不超过赤道值")
        void metersPerDegLon_alwaysPositiveAndBounded(double lat) {
            double value = GeoUtil.metersPerDegLon(lat);
            assertThat(value).isGreaterThanOrEqualTo(0.0);
            assertThat(value).isLessThanOrEqualTo(METERS_PER_DEG_LAT + EPS);
        }
    }

    // ==================== east / north 偏移 ====================

    @Nested
    @DisplayName("east / north 偏移计算")
    class EastNorth {

        @Test
        @DisplayName("同点 east=0 且 north=0")
        void eastNorth_samePoint_zero() {
            assertThat(GeoUtil.east(47.0, 8.0, 47.0, 8.0)).isCloseTo(0.0, within(EPS));
            assertThat(GeoUtil.north(47.0, 8.0, 47.0, 8.0)).isCloseTo(0.0, within(EPS));
        }

        @Test
        @DisplayName("向东（lon 增大）east > 0")
        void east_eastward_positive() {
            double e = GeoUtil.east(47.0, 8.0, 47.0, 9.0);
            assertThat(e).isPositive();
        }

        @Test
        @DisplayName("向西（lon 减小）east < 0")
        void east_westward_negative() {
            double e = GeoUtil.east(47.0, 8.0, 47.0, 7.0);
            assertThat(e).isNegative();
        }

        @Test
        @DisplayName("向北（lat 增大）north > 0")
        void north_northward_positive() {
            double n = GeoUtil.north(47.0, 8.0, 48.0, 8.0);
            assertThat(n).isPositive();
        }

        @Test
        @DisplayName("向南（lat 减小）north < 0")
        void north_southward_negative() {
            double n = GeoUtil.north(47.0, 8.0, 46.0, 8.0);
            assertThat(n).isNegative();
        }

        @Test
        @DisplayName("east 不受 lat 差影响（仅 lon 差与 refLat）")
        void east_independentOfLatDifference() {
            double e1 = GeoUtil.east(47.0, 8.0, 47.0, 9.0);
            double e2 = GeoUtil.east(47.0, 8.0, 48.0, 9.0);
            assertThat(e1).isCloseTo(e2, within(EPS));
        }

        @Test
        @DisplayName("north 不受 lon 差影响（仅 lat 差）")
        void north_independentOfLonDifference() {
            double n1 = GeoUtil.north(47.0, 8.0, 48.0, 8.0);
            double n2 = GeoUtil.north(47.0, 8.0, 48.0, 9.0);
            assertThat(n1).isCloseTo(n2, within(EPS));
        }

        @Test
        @DisplayName("1 度经度在赤道处 ≈ 111194.93 m")
        void east_oneDegreeAtEquator_matchesMetersPerDegLat() {
            double e = GeoUtil.east(0.0, 0.0, 0.0, 1.0);
            assertThat(e).isCloseTo(METERS_PER_DEG_LAT, within(EPS));
        }

        @Test
        @DisplayName("1 度纬度 ≈ 111194.93 m")
        void north_oneDegree_matchesMetersPerDegLat() {
            double n = GeoUtil.north(0.0, 0.0, 1.0, 0.0);
            assertThat(n).isCloseTo(METERS_PER_DEG_LAT, within(EPS));
        }
    }

    // ==================== latOf / lonOf 逆运算 ====================

    @Nested
    @DisplayName("latOf / lonOf 逆运算")
    class LatOfLonOf {

        @Test
        @DisplayName("latOf 零偏移返回 refLat")
        void latOf_zeroOffset_returnsRefLat() {
            assertThat(GeoUtil.latOf(47.123, 8.456, 0.0, 0.0))
                    .isCloseTo(47.123, within(EPS));
        }

        @Test
        @DisplayName("lonOf 零偏移返回 refLon")
        void lonOf_zeroOffset_returnsRefLon() {
            assertThat(GeoUtil.lonOf(47.123, 8.456, 0.0, 0.0))
                    .isCloseTo(8.456, within(EPS));
        }

        @Test
        @DisplayName("latOf 不受 eastM 影响")
        void latOf_independentOfEast() {
            double lat1 = GeoUtil.latOf(47.0, 8.0, 1000.0, 0.0);
            double lat2 = GeoUtil.latOf(47.0, 8.0, 1000.0, 5000.0);
            assertThat(lat1).isCloseTo(lat2, within(EPS));
        }

        @Test
        @DisplayName("lonOf 不受 northM 影响")
        void lonOf_independentOfNorth() {
            double lon1 = GeoUtil.lonOf(47.0, 8.0, 0.0, 1000.0);
            double lon2 = GeoUtil.lonOf(47.0, 8.0, 5000.0, 1000.0);
            assertThat(lon1).isCloseTo(lon2, within(EPS));
        }
    }

    // ==================== 往返一致性（精度验证） ====================

    @Nested
    @DisplayName("east/north 与 latOf/lonOf 往返一致性")
    class RoundTrip {

        @Test
        @DisplayName("latOf(refLat, refLon, north(...), 0) ≈ 原 lat")
        void roundTrip_north_latOf() {
            double refLat = 47.3769;
            double refLon = 8.5417;
            double targetLat = 47.40;
            double targetLon = refLon;
            double n = GeoUtil.north(refLat, refLon, targetLat, targetLon);
            double recoveredLat = GeoUtil.latOf(refLat, refLon, n, 0.0);
            assertThat(recoveredLat).isCloseTo(targetLat, within(1e-9));
        }

        @Test
        @DisplayName("lonOf(refLat, refLon, 0, east(...)) ≈ 原 lon")
        void roundTrip_east_lonOf() {
            double refLat = 47.3769;
            double refLon = 8.5417;
            double targetLat = refLat;
            double targetLon = 8.60;
            double e = GeoUtil.east(refLat, refLon, targetLat, targetLon);
            double recoveredLon = GeoUtil.lonOf(refLat, refLon, 0.0, e);
            assertThat(recoveredLon).isCloseTo(targetLon, within(1e-9));
        }

        @Test
        @DisplayName("完整往返：点 → east/north → latOf/lonOf → 原点")
        void roundTrip_fullPoint() {
            double refLat = 47.3769;
            double refLon = 8.5417;
            double targetLat = 47.50;
            double targetLon = 8.70;
            double e = GeoUtil.east(refLat, refLon, targetLat, targetLon);
            double n = GeoUtil.north(refLat, refLon, targetLat, targetLon);
            double recoveredLat = GeoUtil.latOf(refLat, refLon, n, e);
            double recoveredLon = GeoUtil.lonOf(refLat, refLon, n, e);
            assertThat(recoveredLat).isCloseTo(targetLat, within(1e-9));
            assertThat(recoveredLon).isCloseTo(targetLon, within(1e-9));
        }

        @ParameterizedTest(name = "往返 ref=({0},{1}) target=({2},{3})")
        @CsvSource({
                "0, 0, 0.01, 0.01",
                "47.3769, 8.5417, 47.50, 8.70",
                "-33.86, 151.21, -33.90, 151.30",
                "40.7128, -74.0060, 40.72, -73.98",
                "90, 0, 89.99, 0.001"
        })
        @DisplayName("参数化往返：多组坐标往返误差 < 1e-9")
        void roundTrip_parameterized(double refLat, double refLon,
                                     double targetLat, double targetLon) {
            double e = GeoUtil.east(refLat, refLon, targetLat, targetLon);
            double n = GeoUtil.north(refLat, refLon, targetLat, targetLon);
            double recoveredLat = GeoUtil.latOf(refLat, refLon, n, e);
            double recoveredLon = GeoUtil.lonOf(refLat, refLon, n, e);
            assertThat(recoveredLat).isCloseTo(targetLat, within(1e-9));
            assertThat(recoveredLon).isCloseTo(targetLon, within(1e-9));
        }
    }
}