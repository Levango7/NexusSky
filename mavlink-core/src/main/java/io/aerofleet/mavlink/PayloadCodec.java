package io.aerofleet.mavlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** payload 小端编解码工具。MAVLink 所有字段均为小端。 */
public final class PayloadCodec {

    private PayloadCodec() {
    }

    public static ByteBuffer littleEndian(byte[] payload) {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static byte[] alloc(int size) {
        return new byte[size];
    }

    public static int u8(ByteBuffer b, int offset) {
        return b.get(offset) & 0xFF;
    }

    public static int i8(ByteBuffer b, int offset) {
        return b.get(offset);
    }

    public static int u16(ByteBuffer b, int offset) {
        return b.getShort(offset) & 0xFFFF;
    }

    public static int i16(ByteBuffer b, int offset) {
        return b.getShort(offset);
    }

    public static long u32(ByteBuffer b, int offset) {
        return b.getInt(offset) & 0xFFFFFFFFL;
    }

    public static int i32(ByteBuffer b, int offset) {
        return b.getInt(offset);
    }

    public static long u64(ByteBuffer b, int offset) {
        return b.getLong(offset);
    }

    public static float f32(ByteBuffer b, int offset) {
        return b.getFloat(offset);
    }

    public static void putU8(byte[] buf, int offset, int v) {
        buf[offset] = (byte) v;
    }

    public static void putI8(byte[] buf, int offset, int v) {
        buf[offset] = (byte) v;
    }

    public static void putU16(byte[] buf, int offset, int v) {
        buf[offset] = (byte) (v & 0xFF);
        buf[offset + 1] = (byte) ((v >> 8) & 0xFF);
    }

    public static void putI16(byte[] buf, int offset, int v) {
        putU16(buf, offset, v);
    }

    public static void putU32(byte[] buf, int offset, long v) {
        buf[offset] = (byte) (v & 0xFF);
        buf[offset + 1] = (byte) ((v >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((v >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((v >> 24) & 0xFF);
    }

    public static void putI32(byte[] buf, int offset, int v) {
        putU32(buf, offset, v);
    }

    public static void putU64(byte[] buf, int offset, long v) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) ((v >> (8 * i)) & 0xFF);
        }
    }

    public static void putF32(byte[] buf, int offset, float v) {
        putU32(buf, offset, Float.floatToIntBits(v));
    }

    public static String chars(ByteBuffer b, int offset, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = b.get(offset + i);
        }
        int end = 0;
        while (end < len && out[end] != 0) {
            end++;
        }
        return new String(out, 0, end, StandardCharsets.UTF_8);
    }

    public static void putChars(byte[] buf, int offset, String s, int len) {
        byte[] src = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(src.length, len);
        System.arraycopy(src, 0, buf, offset, n);
        for (int i = n; i < len; i++) {
            buf[offset + i] = 0;
        }
    }
}
