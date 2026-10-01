package io.aerofleet.mavlink;

/**
 * MAVLink 消息 CRC_EXTRA 的官方计算算法。
 * <p>
 * CRC_EXTRA 不是可以任意指定的常数，而是消息「签名」的函数：把消息名与各字段的
 * 「类型 + 字段名」（数组字段再加一个字节的数组长度）按 {@link #orderFields} 排好序后，
 * 逐段喂进 CRC-16/MCRF4XX（{@link MavlinkCrc}，初值 0xFFFF），最后做
 * {@code (crc & 0xFF) ^ (crc >> 8)} 的高低字节交换。
 * <p>
 * 官方参考实现：{@code pymavlink/generator/mavparse.py} 的 {@code message_checksum}，
 * 与 {@code c_library_v2} 头文件里各消息的 {@code CRC_EXTRA} 宏同源。
 * <p>
 * <b>为什么必须算而不是手填：</b>CRC_EXTRA 的唯一职责是让「对同一 msgId 持有不同字段
 * 定义」的两端在帧 CRC 上必然不一致，从而被拒。若填一个与字段无关的常数，两份字段
 * 布局完全不同、实现只要抄同一个数就能互通，该机制形同虚设。
 * <p>
 * 本类只做计算，不参与热路径；线上取值仍来自 {@link MavlinkMessageInfo} 的常量表。
 * 它的用途是让 {@code MavlinkCrcExtraTest} 能从字段签名独立重算并与常量表比对，
 * 从而在「改了字段却忘了重算 CRC_EXTRA」时让构建失败。
 */
public final class MavlinkMessageChecksum {

    /** 字段宽度（字节）。用于复刻官方的排序规则。 */
    public enum FieldType {
        UINT8("uint8_t", 1),
        INT8("int8_t", 1),
        UINT16("uint16_t", 2),
        INT16("int16_t", 2),
        UINT32("uint32_t", 4),
        INT32("int32_t", 4),
        UINT64("uint64_t", 8),
        FLOAT("float", 4),
        CHAR("char", 1);

        private final String wireName;
        private final int width;

        FieldType(String wireName, int width) {
            this.wireName = wireName;
            this.width = width;
        }

        /** MAVLink XML 里书写的类型全名，参与 CRC 累积。 */
        public String wireName() {
            return wireName;
        }

        /** 线上字节宽度。 */
        public int width() {
            return width;
        }
    }

    /**
     * 一个字段。{@code name} 为 MAVLink 字段名（小驼峰，与 XML 一致）。
     * {@code arrayLength} 为 0 表示标量。
     */
    public record Field(FieldType type, String name, int arrayLength) {
        public Field(FieldType type, String name) {
            this(type, name, 0);
        }
    }

    private MavlinkMessageChecksum() {
    }

    /**
     * 复刻 {@code mavparse.py:426} 的字段排序：按 {@code type_length} 降序稳定排序。
     * <p>
     * 注意是「按宽度降序」而不是「按字段名排序」——MAVLink 这么排是为了让多字节字段
     * 自然对齐、不产生填充。等宽字段之间保持传入顺序（Python 的 sorted 是稳定排序）。
     * 同宽字段的相对次序会改变 CRC 结果，因此传入顺序必须是 XML 里的声明顺序。
     *
     * @param fields 字段声明顺序下的字段列表
     * @return 排序后的新列表
     */
    public static Field[] orderFields(Field... fields) {
        Field[] copy = fields.clone();
        java.util.Arrays.sort(copy, (a, b) -> Integer.compare(b.type().width(), a.type().width()));
        return copy;
    }

    /**
     * 计算消息的 CRC_EXTRA。传入的 {@code fields} 需已按声明顺序排列；
     * 本方法内部会先调用 {@link #orderFields} 复刻官方排序。
     *
     * @param messageName MAVLink 消息名（SCREAMING_SNAKE_CASE）
     * @param fields      字段声明顺序下的字段列表
     * @return 0-255 的 CRC_EXTRA
     */
    public static int crcExtra(String messageName, Field... fields) {
        int crc = acc(MavlinkCrc.init(), messageName + ' ');
        for (Field f : orderFields(fields)) {
            crc = acc(crc, f.type().wireName() + ' ');
            crc = acc(crc, f.name() + ' ');
            if (f.arrayLength() > 0) {
                crc = MavlinkCrc.accumulate(crc, f.arrayLength());
            }
        }
        return (crc & 0xFF) ^ ((crc >> 8) & 0xFF);
    }

    /** 累积一段 ASCII 文本（含结尾空格），与官方 message_checksum 的分段方式一致。 */
    private static int acc(int crc, String segment) {
        byte[] b = segment.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return MavlinkCrc.accumulate(crc, b, 0, b.length);
    }
}
