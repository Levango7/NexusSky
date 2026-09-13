package io.aerofleet.mavlink;

/**
 * MAVLink 帧校验算法：CRC-16/X.25（初始值 0xFFFF、多项式反射 0x8408、结果异或 0）。
 * 该算法是 MAVLink 线上协议的事实标准，与 pymavlink / c_library_v2 完全一致。
 * 标准测试向量：ASCII "123456789" 的 CRC 为 0x906E。
 */
public final class MavlinkCrc {

    private static final int POLY_REFLECTED = 0x8408;

    private MavlinkCrc() {
    }

    public static int init() {
        return 0xFFFF;
    }

    /** 逐字节累积计算（低位在前，与官方实现逐 bit 等价）。 */
    public static int accumulate(int crc, int dataByte) {
        int c = crc ^ (dataByte & 0xFF);
        for (int i = 0; i < 8; i++) {
            if ((c & 1) != 0) {
                c = (c >>> 1) ^ POLY_REFLECTED;
            } else {
                c = c >>> 1;
            }
        }
        return c & 0xFFFF;
    }

    public static int accumulate(int crc, byte[] data, int offset, int length) {
        int c = crc;
        for (int i = 0; i < length; i++) {
            c = accumulate(c, data[offset + i]);
        }
        return c;
    }

    public static int compute(byte[] data, int offset, int length) {
        return accumulate(init(), data, offset, length);
    }
}
