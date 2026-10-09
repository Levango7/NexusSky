package io.aerofleet.cloud.uspace;

import io.aerofleet.cloud.geofence.FenceType;
import io.aerofleet.cloud.geofence.GeofenceZone;
import io.aerofleet.cloud.geofence.RestrictionZone;
import io.aerofleet.cloud.rid.model.BasicIdData;
import io.aerofleet.cloud.rid.model.LocationData;
import io.aerofleet.cloud.rid.model.OperatorIdData;
import io.aerofleet.cloud.rid.model.RidComplianceState;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import io.aerofleet.cloud.rid.model.SelfIdData;
import io.aerofleet.cloud.rid.model.SystemData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** E3 出海合规：三套 Remote ID 映射（字段级）+ 几何判定（授权/感知共用）。 */
@DisplayName("U-space 合规抽象")
class UspaceComplianceTest {

    private static RidSnapshot snapshot() {
        return new RidSnapshot(
                9,
                RidComplianceState.BROADCASTING,
                new BasicIdData(1, 2, "1596F3A2B4C5D6E7"),
                // speedHorizontal=320 → 80m/s（0.25m/s 单位）；speedVertical=0
                new LocationData(1, 90, 320, 0,
                        22.5910, 113.9340,
                        60.5f, 61.2f, 0, 40.0f, 9, 9, 9, 9, 0f),
                new SystemData(0, 22.5907, 113.9345, 1, 500, 120f, 0f),
                new SelfIdData(0, "巡检作业"),
                new OperatorIdData(1, "CN-OP-12345678"),
                0L);
    }

    // ------------------------------------------------------------------
    // D1 三套映射
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("D1 Remote ID 三套映射")
    class MapperTest {
        private final RidSnapshot s = snapshot();

        @Test
        void GB46750直出内部口径() {
            Map<String, Object> m = RemoteIdMapper.toGb46750(s);
            assertThat(m).containsEntry("standard", "GB46750").containsEntry("sysid", 9);
            @SuppressWarnings("unchecked")
            Map<String, Object> basic = (Map<String, Object>) m.get("basicId");
            assertThat(basic).containsEntry("uasId", "1596F3A2B4C5D6E7");
            // operatorId 脱敏（与 RidController 同口径：前 4 位 + *）
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) m.get("operatorId");
            assertThat((String) op.get("operatorId")).isEqualTo("CN-O" + "*".repeat(10));
        }

        @Test
        void ASTM字段级换算() {
            Map<String, Object> m = RemoteIdMapper.toAstm(s);
            assertThat(m).containsEntry("standard", "ASTM_F3411");
            assertThat(m).containsEntry("uasId", "1596F3A2B4C5D6E7");
            assertThat(m).containsEntry("idType", "SERIAL_NUMBER");
            assertThat(m).containsEntry("uaType", "HELICOPTER_OR_MULTIROTOR");
            @SuppressWarnings("unchecked")
            Map<String, Object> loc = (Map<String, Object>) m.get("location");
            // 速度换算：320（0.25m/s 单位）= 80m/s → 155.51 kn
            double speedKn = (Double) loc.get("speedKn");
            assertThat(speedKn).isBetween(155.0, 156.0);
            // 精度档位 9 → 30cm（ASTM 精度表）
            assertThat(loc).containsEntry("horizontalAccuracyCm", 30);
        }

        @Test
        void EU在ASTM上补专属字段() {
            Map<String, Object> m = RemoteIdMapper.toEu(s);
            assertThat(m).containsEntry("standard", "EU_2019_945");
            assertThat(m).containsEntry("directRemoteId", "1596F3A2B4C5D6E7");
            assertThat(m).containsEntry("uaClassification", "C1");   // 多旋翼→C1
            assertThat(m).containsEntry("categoryEu", "OPEN");
            // uomId = GB operatorId 语义换名 + 同口径脱敏
            assertThat((String) m.get("uomId")).startsWith("CN-O");
        }

        @Test
        void 三套标准名互异且map入口分发正确() {
            assertThat(RemoteIdMapper.map(s, "gb46750")).containsEntry("standard", "GB46750");
            assertThat(RemoteIdMapper.map(s, "astm")).containsEntry("standard", "ASTM_F3411");
            assertThat(RemoteIdMapper.map(s, "eu")).containsEntry("standard", "EU_2019_945");
            assertThatThrownBy(() -> RemoteIdMapper.map(s, "faa"))
                    .hasMessageContaining("unknown format");
        }

        @Test
        void 精度表边界() {
            assertThat(RemoteIdMapper.accuracyCm(0)).isEqualTo(18520);
            assertThat(RemoteIdMapper.accuracyCm(12)).isEqualTo(1);
            assertThat(RemoteIdMapper.accuracyCm(99)).isEqualTo(18520);   // 未知档位保守值
        }

        @Test
        void 脱敏短值全遮蔽() {
            assertThat(RemoteIdMapper.desensitize("CN")).isEqualTo("****");
            assertThat(RemoteIdMapper.desensitize(null)).isEqualTo("****");
        }

        @Test
        void 分段到达容错_仅BasicId不NPE() {
            // e2e 实测形态：sim 只播了 BasicId，其余段 null——缺失段输出 null 不抛
            RidSnapshot partial = new RidSnapshot(9, RidComplianceState.BROADCASTING,
                    new BasicIdData(1, 1, "UA-ONLY-BASIC-ID"),
                    null, null, null, null, 0L);
            for (String fmt : new String[]{"gb46750", "astm", "eu"}) {
                Map<String, Object> m = RemoteIdMapper.map(partial, fmt);
                assertThat(m).containsKey("location");
                assertThat(m.get("location")).isNull();
                assertThat(m.get("system")).isNull();
                // 键存在但值为 null（缺报如实呈现——不是键缺失，也不是编造）
                switch (fmt) {
                    case "gb46750" -> {
                        assertThat(m).containsKey("operatorId");
                        assertThat(m.get("operatorId")).isNull();
                    }
                    case "eu" -> {
                        assertThat(m).containsKey("uomId");
                        assertThat(m.get("uomId")).isNull();
                    }
                    default -> { /* astm 无 operatorId 段属设计 */ }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // D2-3 几何判定
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("几何判定（授权/感知共用）")
    class GeometryTest {
        @Test
        void 圆区命中与距离() {
            RestrictionZone circle = RestrictionZone.circleRestriction(
                    "PEK", "机场禁飞区", 40.0801, 116.5846, 5000, "test");
            assertThat(UspaceController.containsPoint(circle, 40.0801, 116.5846)).isTrue();
            assertThat(UspaceController.containsPoint(circle, 41.0, 116.5846)).isFalse();
            // 环距：区外 5km 处 ≈ 0（紧贴边缘）与远点正值
            double near = UspaceController.distanceToZoneM(circle, 40.1251, 116.5846); // ≈5km 北
            assertThat(near).isLessThan(200);
        }

        @Test
        void 多边形射线法() {
            RestrictionZone poly = RestrictionZone.polygonRestriction(
                    "SZ", "城区限飞", List.of(
                            new GeofenceZone.GeoPoint(22.54, 113.92),
                            new GeofenceZone.GeoPoint(22.54, 114.08),
                            new GeofenceZone.GeoPoint(22.48, 114.08),
                            new GeofenceZone.GeoPoint(22.48, 113.92)),
                    "test");
            assertThat(UspaceController.containsPoint(poly, 22.51, 114.00)).isTrue();
            assertThat(UspaceController.containsPoint(poly, 22.60, 114.00)).isFalse();
            assertThat(poly.getFenceType()).isEqualTo(FenceType.KEEP_OUT);
        }
    }
}
