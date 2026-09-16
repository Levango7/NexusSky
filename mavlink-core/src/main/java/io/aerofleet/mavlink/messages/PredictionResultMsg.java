package io.aerofleet.mavlink.messages;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.PayloadCodec;

import java.nio.ByteBuffer;

/**
 * PREDICTION_RESULT (msgId=476, LEN=20) —— NexusSky M13 轨迹预测结果自定义扩展消息。
 * <p>
 * 承载轨迹预测结果：预测经纬度/高度 + 预测时域 + 置信度 + 轨迹点数。
 * <p>
 * 字段布局（小端，大字段在前以自然对齐）：
 * <pre>
 * 偏移  字段                  类型   单位/精度
 * 0    predictedLat          i32   预测纬度（1E7 度）
 * 4    predictedLon          i32   预测经度（1E7 度）
 * 8    predictedAlt          i32   预测高度（mm，AMSL）
 * 12   confidence            f32   预测置信度（0.0-1.0）
 * 16   predictionHorizonSec  u16   预测时域（s）
 * 18   sysId                 u8    发送方系统 ID
 * 19   trajectoryPoints      u8    轨迹点数
 * </pre>
 * CRC_EXTRA = 260（M13 自定义扩展）。
 */
public final class PredictionResultMsg extends MavlinkMessage {

    public static final int ID = 476;
    public static final int LEN = 20;
    public static final int CRC_EXTRA = 260;

    public final int predictedLat;          // 1E7 度
    public final int predictedLon;          // 1E7 度
    public final int predictedAlt;          // mm
    public final float confidence;          // 0.0-1.0
    public final int predictionHorizonSec;  // s
    public final int sysId;                 // 发送方系统 ID
    public final int trajectoryPoints;      // 轨迹点数

    public PredictionResultMsg(int predictedLat, int predictedLon, int predictedAlt,
                               float confidence, int predictionHorizonSec,
                               int sysId, int trajectoryPoints) {
        this.predictedLat = predictedLat;
        this.predictedLon = predictedLon;
        this.predictedAlt = predictedAlt;
        this.confidence = confidence;
        this.predictionHorizonSec = predictionHorizonSec;
        this.sysId = sysId;
        this.trajectoryPoints = trajectoryPoints;
    }

    @Override
    public int messageId() {
        return ID;
    }

    @Override
    public byte[] encode() {
        byte[] buf = PayloadCodec.alloc(LEN);
        PayloadCodec.putI32(buf, 0, predictedLat);
        PayloadCodec.putI32(buf, 4, predictedLon);
        PayloadCodec.putI32(buf, 8, predictedAlt);
        PayloadCodec.putF32(buf, 12, confidence);
        PayloadCodec.putU16(buf, 16, predictionHorizonSec);
        PayloadCodec.putU8(buf, 18, sysId);
        PayloadCodec.putU8(buf, 19, trajectoryPoints);
        return buf;
    }

    /** 从帧解码；payload 短于 LEN 时容忍（缺失字段填 0）。 */
    public static PredictionResultMsg decode(MavlinkFrame f) {
        ByteBuffer b = PayloadCodec.littleEndian(f.getPayload());
        int len = f.getPayloadLength();
        return new PredictionResultMsg(
                len > 3 ? PayloadCodec.i32(b, 0) : 0,
                len > 7 ? PayloadCodec.i32(b, 4) : 0,
                len > 11 ? PayloadCodec.i32(b, 8) : 0,
                len > 15 ? PayloadCodec.f32(b, 12) : 0f,
                len > 17 ? PayloadCodec.u16(b, 16) : 0,
                len > 18 ? PayloadCodec.u8(b, 18) : 0,
                len > 19 ? PayloadCodec.u8(b, 19) : 0);
    }

    @Override
    public String toString() {
        return "PredictionResultMsg{sysId=" + sysId
                + ", pos=(" + predictedLat + "," + predictedLon + "," + predictedAlt + "mm)"
                + ", horizon=" + predictionHorizonSec + "s"
                + ", conf=" + confidence
                + ", points=" + trajectoryPoints + "}";
    }
}