package io.aerofleet.cloud.surveillance;

import java.util.Map;
import java.util.Set;

/**
 * GB28181 国标 20 位设备编码（E5，GB/T 28181-2016 §附录C 中心编码规则）。
 * <pre>
 * 位段：[0..10] 中心编码（11 位，前 8 行政区划 + 3 位中心序号）
 *      [11..12] 行业编码（2 位：10 公安 / 20 视频 / 00 其他）
 *      [13..15] 设备类型码（3 位：131 摄像机 / 132 网络摄像机 / 134 录像机 / 200 报警输入 / 215 报警输出）
 *      [16..19] 序号（4 位）
 * </pre>
 * 全部为数字。纯构造/校验/解析，可全量单测。
 */
public final class Gb28181DeviceId {

    /** 行业编码（国标 §C.2.3 常用值）。 */
    public static final Map<String, String> INDUSTRY_CODES = Map.of(
            "10", "公安", "20", "视频图像信息", "00", "其他行业");

    /** 设备类型码（国标 §C.2.4 摘录——接入常用面）。 */
    public static final Map<String, String> TYPE_CODES = Map.of(
            "131", "摄像机", "132", "网络摄像机", "133", "编码器",
            "134", "解码器/录像机", "200", "报警输入", "215", "报警输出");

    private static final Set<String> KNOWN_TYPES = TYPE_CODES.keySet();

    public final String center;      // 11 位
    public final String industry;    // 2 位
    public final String typeCode;    // 3 位
    public final String serial;      // 4 位

    private Gb28181DeviceId(String center, String industry, String typeCode, String serial) {
        this.center = center;
        this.industry = industry;
        this.typeCode = typeCode;
        this.serial = serial;
    }

    /** 20 位完整编码。 */
    public String value() {
        return center + industry + typeCode + serial;
    }

    @Override
    public String toString() {
        return value();
    }

    /** 行业名（未知行业码返回原码，不造假名）。 */
    public String industryName() {
        return INDUSTRY_CODES.getOrDefault(industry, industry);
    }

    /** 类型名（未知类型码返回原码）。 */
    public String typeName() {
        return TYPE_CODES.getOrDefault(typeCode, typeCode);
    }

    /** 是否报警类设备（类型码 2xx）。 */
    public boolean isAlarmDevice() {
        return typeCode.startsWith("2");
    }

    /** 是否摄像类设备（类型码 13x）。 */
    public boolean isCameraDevice() {
        return typeCode.startsWith("13");
    }

    /**
     * 解析并校验 20 位编码。
     *
     * @throws IllegalArgumentException 长度非 20 / 含非数字 / 类型码未收录
     */
    public static Gb28181DeviceId parse(String id) {
        if (id == null || id.length() != 20 || !id.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "GB28181 DeviceID must be 20 digits, got: " + id);
        }
        String typeCode = id.substring(13, 16);
        // 类型码用"已收录"而非"全部合法"做校验——国标类型码表远大于接入常用面，
        // 未收录的类型码明确拒绝而非静默接受（新类型码加进 TYPE_CODES 即可放行）。
        if (!KNOWN_TYPES.contains(typeCode)) {
            throw new IllegalArgumentException(
                    "unknown GB28181 type code: " + typeCode + " (supported: " + KNOWN_TYPES + ")");
        }
        return new Gb28181DeviceId(id.substring(0, 11), id.substring(11, 13),
                typeCode, id.substring(16, 20));
    }

    /** 构造（同 parse 校验）。 */
    public static Gb28181DeviceId of(String center, String industry, String typeCode, String serial) {
        if (center == null || center.length() != 11 || industry == null || industry.length() != 2
                || typeCode == null || typeCode.length() != 3 || serial == null || serial.length() != 4) {
            throw new IllegalArgumentException("GB28181 segments must be 11/2/3/4 digits");
        }
        return parse(center + industry + typeCode + serial);
    }
}
