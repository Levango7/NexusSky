package io.aerofleet.cloud.uspace;

import io.aerofleet.cloud.rid.model.RidSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remote ID 三套标准字段映射（E3，spec D1）——纯函数。
 * <p>
 * 内部真值 = 既有 {@link RidSnapshot}（GB 46750 语义，C2 摄入）。本类做
 * **字段级语义换名/换算**（不做字节级帧编码——那是 MAVLink OPEN_DRONE_ID
 * 通道的既有职责）：
 * <ul>
 *   <li>{@link #toGb46750}：内部口径直出（国标语义）；</li>
 *   <li>{@link #toAstm}：ASTM F3411（美 Part 89），速度换算为节、精度换算厘米表；</li>
 *   <li>{@link #toEu}：EU 2019/945（直连识别模块）——ASTM 超集 + EU 注册号/UA 分类标注。</li>
 * </ul>
 * 三套共享同一脱敏口径（operatorId 与 RidController 一致：前 4 位 + *）。
 */
public final class RemoteIdMapper {

    private RemoteIdMapper() {
    }

    /** GB 46750（内部真值直出；字段名为内部语义）。 */
    public static Map<String, Object> toGb46750(RidSnapshot s) {
        Map<String, Object> m = base(s, "GB46750");
        m.put("ridStatus", s.ridStatus().name());
        // 字段级容错：RID 广播是分段到达的（sim 可能只播了 BasicId），
        // 缺失段输出 null 而非 NPE——e2e 实测撞出（诚实呈现缺报，不编造）
        m.put("basicId", s.basicId() == null ? null : Map.of(
                "idType", s.basicId().idType(),
                "uaType", s.basicId().uaType(),
                "uasId", s.basicId().uasId()));
        m.put("location", s.location() == null ? null : Map.of(
                "status", s.location().status(),
                "latitude", s.location().latitude(),
                "longitude", s.location().longitude(),
                "altitudeBarometric", s.location().altitudeBarometric(),
                "altitudeGeodetic", s.location().altitudeGeodetic(),
                "height", s.location().height(),
                "direction", s.location().direction(),
                "speedHorizontal", s.location().speedHorizontal(),
                "speedVertical", s.location().speedVertical()));
        m.put("system", s.system() == null ? null : Map.of(
                "operatorLocationType", s.system().operatorLocationType(),
                "operatorLatitude", s.system().operatorLatitude(),
                "operatorLongitude", s.system().operatorLongitude(),
                "areaCount", s.system().areaCount(),
                "areaRadius", s.system().areaRadius(),
                "areaCeiling", s.system().areaCeiling(),
                "areaFloor", s.system().areaFloor()));
        m.put("operatorId", s.operatorId() == null ? null : Map.of(
                "operatorIdType", s.operatorId().operatorIdType(),
                "operatorId", desensitize(s.operatorId().operatorId())));
        return m;
    }

    /**
     * ASTM F3411（Part 89 / Remote ID）：字段名按 ASTM 惯例；
     * 速度 m/s→节（×1.94384）、精度档位换厘米表。
     */
    public static Map<String, Object> toAstm(RidSnapshot s) {
        Map<String, Object> m = base(s, "ASTM_F3411");
        m.put("uasId", s.basicId() == null ? null : s.basicId().uasId());
        m.put("idType", s.basicId() == null ? null : astmIdType(s.basicId().idType()));
        m.put("uaType", s.basicId() == null ? null : astmUaType(s.basicId().uaType()));
        if (s.location() == null) {
            m.put("location", null);
        } else {
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("status", s.location().status());
            loc.put("latitude", s.location().latitude());
            loc.put("longitude", s.location().longitude());
            loc.put("geodeticAltitude", s.location().altitudeGeodetic());
            loc.put("barometricAltitude", s.location().altitudeBarometric());
            loc.put("height", s.location().height());
            loc.put("trackDirectionDeg", s.location().direction());
            // 单位换算（OpenDroneId 语义）：speedHorizontal 0.25m/s、speedVertical 0.5m/s
            loc.put("speedKn", round2(s.location().speedHorizontal() * 0.25 * 1.94384));
            loc.put("verticalSpeedMps", round2(s.location().speedVertical() * 0.5));
            loc.put("horizontalAccuracyCm", accuracyCm(s.location().horizontalAccuracy()));
            loc.put("verticalAccuracyCm", accuracyCm(s.location().verticalAccuracy()));
            m.put("location", loc);
        }
        m.put("system", s.system() == null ? null : Map.of(
                "operatorLatitude", s.system().operatorLatitude(),
                "operatorLongitude", s.system().operatorLongitude(),
                "areaCount", s.system().areaCount(),
                "areaRadiusM", s.system().areaRadius(),
                "areaCeilingM", s.system().areaCeiling(),
                "areaFloorM", s.system().areaFloor()));
        return m;
    }

    /**
     * EU 2019/945（直连识别模块口径）：ASTM 超集——补 EU 专属字段
     * （UAS 注册号、运营者号 UOM 语义换名、UA 分类简化为标注位）。
     */
    public static Map<String, Object> toEu(RidSnapshot s) {
        Map<String, Object> m = base(s, "EU_2019_945");
        m.put("directRemoteId", s.basicId() == null ? null : s.basicId().uasId());  // UAS 注册号
        m.put("uasId", s.basicId() == null ? null : s.basicId().uasId());
        m.put("uaClassification", s.basicId() == null ? null : euClass(s.basicId().uaType()));
        m.put("categoryEu", "OPEN");                        // PoC：本仓场景均为开放类
        m.put("location", toAstm(s).get("location"));       // 位置字段与 ASTM 同口径
        m.put("system", s.system() == null ? null : Map.of(
                "operatorLatitude", s.system().operatorLatitude(),
                "operatorLongitude", s.system().operatorLongitude(),
                "areaCount", s.system().areaCount(),
                "areaRadiusM", s.system().areaRadius(),
                "areaCeilingM", s.system().areaCeiling(),
                "areaFloorM", s.system().areaFloor()));
        m.put("uomId", s.operatorId() == null ? null
                : desensitize(s.operatorId().operatorId()));   // 运营者号（GB→EU 换名）
        m.put("uomIdType", s.operatorId() == null ? null : s.operatorId().operatorIdType());
        return m;
    }

    /** 统一入口。 */
    public static Map<String, Object> map(RidSnapshot s, String format) {
        return switch (format == null ? "astm" : format) {
            case "gb46750" -> toGb46750(s);
            case "astm" -> toAstm(s);
            case "eu" -> toEu(s);
            default -> throw new IllegalArgumentException(
                    "unknown format: " + format + " (gb46750|astm|eu)");
        };
    }

    // ------------------------------------------------------------------
    // 换算与脱敏
    // ------------------------------------------------------------------

    /**
     * idType 语义表（GB OPEN_DRONE_ID id_or_type 与 ASTM ID Type 对照）：
     * 1=serial number(ANSI/CTA-2063) / 2=CAA registration ID / 3=UTM UUID / 4=specific session ID。
     * 内部 GB 口径：0=None / 1=SerialNumber / 2=CAA / 3=UTM / 4=Specific —— 直通但补语义名。
     */
    static String astmIdType(int gbIdType) {
        return switch (gbIdType) {
            case 1 -> "SERIAL_NUMBER";
            case 2 -> "CAA_REGISTRATION_ID";
            case 3 -> "UTM_ASSIGNED_UUID";
            case 4 -> "SPECIFIC_SESSION_ID";
            default -> "NONE";
        };
    }

    /** uaType 语义名（GB 0-15 与 ASTM UA Type 基本同构）。 */
    static String astmUaType(int gbUaType) {
        return switch (gbUaType) {
            case 1 -> "AEROPLANE";
            case 2 -> "HELICOPTER_OR_MULTIROTOR";
            case 3 -> "GYROPLANE";
            case 4 -> "HYBRID_LIFT";
            case 5 -> "ORNITHOPTER";
            case 6 -> "GLIDER";
            case 7 -> "KITE";
            case 8 -> "FREE_BALLOON";
            case 9 -> "CAPTIVE_BALLOON";
            case 10 -> "AIRSHIP";
            case 11 -> "FREE_FALL_PARACHUTE";
            case 12 -> "ROCKET";
            case 13 -> "TETHERED_POWERED_AIRCRAFT";
            case 14 -> "GROUND_OBSTACLE";
            default -> "NONE";
        };
    }

    /** EU UA 分类简化标注（PoC：旋翼机→C1，其余未知→C0；细分分类属生产阶段配置）。 */
    static String euClass(int uaType) {
        return switch (uaType) {
            case 2 -> "C1";    // 直升机/多旋翼
            case 1 -> "C2";    // 固定翼
            default -> "C0";
        };
    }

    /**
     * GB horizontalAccuracy 档位→厘米（国标/ASTM 同表）：
     * 0=Unknown(≥18520cm) 1=<18520 2=<7408 3=<3704 4=<1852 5=<926 6=<556
     * 7=<185 8=<93 9=<30 10=<10 11=<3 12=<1（ASTM F3411 精度表）。
     */
    static int accuracyCm(int level) {
        return switch (level) {
            case 0 -> 18520;
            case 1 -> 18520;
            case 2 -> 7408;
            case 3 -> 3704;
            case 4 -> 1852;
            case 5 -> 926;
            case 6 -> 556;
            case 7 -> 185;
            case 8 -> 93;
            case 9 -> 30;
            case 10 -> 10;
            case 11 -> 3;
            case 12 -> 1;
            default -> 18520;
        };
    }

    static String desensitize(String operatorId) {
        if (operatorId == null || operatorId.length() <= 4) {
            return "****";
        }
        return operatorId.substring(0, 4) + "*".repeat(operatorId.length() - 4);
    }

    private static Map<String, Object> base(RidSnapshot s, String standard) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("standard", standard);
        m.put("sysid", s.sysid());
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
