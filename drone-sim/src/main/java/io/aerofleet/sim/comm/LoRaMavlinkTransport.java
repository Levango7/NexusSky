package io.aerofleet.sim.comm;

import io.aerofleet.sim.SimLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LoRaMavlinkTransport：MAVLink 帧与 LoRa 分片之间的适配器。
 * <p>
 * 包装 {@link LoRaTransport}，向上提供帧级 {@code send(byte[])/receive()} 接口，
 * 向下通过分片队列衔接 LoRa 物理层（SX1278）。
 * <p>
 * 发送流程：MAVLink 帧字节 → {@link LoRaTransport#fragment} → 分片入待发送队列
 * （{@link #getPendingFragments()} 供物理层取出逐片发送）。
 * <p>
 * 接收流程：物理层收到单个 LoRa 分片 → {@link #receive(byte[])} 喂入重组器 →
 * 收齐后入完成队列 → {@link #receive()} 弹出完整 MAVLink 帧字节。
 * <p>
 * 线程安全：frameIdSeq 原子递增；待发送分片队列与完成帧队列均为线程安全并发结构。
 * 重组缓冲由 {@link LoRaTransport} 维护，非线程安全，故 {@link #receive(byte[])}
 * 调用方需自行同步（单线程物理层收包场景天然安全）。
 */
public final class LoRaMavlinkTransport {

    /** 分片头长度：frameId(1) + totalFragments(1) + fragIndex(1)，与 LoRaTransport 一致。 */
    private static final int FRAG_HEADER_BYTES = 3;

    private final LoRaTransport loRa;

    /** 待发送分片队列：send() 入队，物理层 poll/get 出队。 */
    private final Deque<byte[]> pendingFragments = new ArrayDeque<>();

    /** 已重组完成的 MAVLink 帧队列：receive(byte[]) 入队，receive() 出队。 */
    private final Deque<byte[]> completedFrames = new ArrayDeque<>();

    /** 帧序号生成器，0-255 循环。 */
    private final AtomicInteger frameIdSeq = new AtomicInteger(0);

    /**
     * 构造 LoRaMavlinkTransport，包装指定 LoRaTransport。
     *
     * @param loRa 底层 LoRa 分片/重组层，不能为 null
     */
    public LoRaMavlinkTransport(LoRaTransport loRa) {
        if (loRa == null) {
            throw new IllegalArgumentException("loRa must not be null");
        }
        this.loRa = loRa;
    }

    /** 使用默认 LoRa 配置（433MHz/SF7/125kHz/4/5/20dBm）构造。 */
    public static LoRaMavlinkTransport defaultConfig() {
        return new LoRaMavlinkTransport(LoRaTransport.defaultConfig());
    }

    /** 使用远距离 LoRa 配置（SF12/4/8）构造。 */
    public static LoRaMavlinkTransport longRange() {
        return new LoRaMavlinkTransport(LoRaTransport.longRange());
    }

    /** 底层 LoRaTransport（供调用方读取物理层参数）。 */
    public LoRaTransport loRa() {
        return loRa;
    }

    /**
     * 发送一个完整 MAVLink 帧：分片后入待发送队列。
     * <p>
     * 帧序号自动递增（0-255 循环）。调用后通过 {@link #getPendingFragments()} 或
     * {@link #pollFragment()} 取出分片逐片发送到 LoRa 物理层。
     *
     * @param mavlinkFrame 序列化的 MAVLink 帧字节（如 {@code frame.encodeV2()}），不能为 null
     * @return 本次发送的帧序号（0-255）
     */
    public int send(byte[] mavlinkFrame) {
        if (mavlinkFrame == null) {
            throw new IllegalArgumentException("mavlinkFrame must not be null");
        }
        int frameId = frameIdSeq.getAndIncrement() & 0xFF;
        byte[][] frags = loRa.fragment(mavlinkFrame, frameId);
        synchronized (pendingFragments) {
            for (byte[] frag : frags) {
                pendingFragments.addLast(frag);
            }
        }
        SimLog.info("LoRaMavlink send: frameId=" + frameId + " frameLen=" + mavlinkFrame.length
                + " fragments=" + frags.length);
        return frameId;
    }

    /**
     * 接收一个 LoRa 分片并尝试重组。
     * <p>
     * 当该 frameId 的全部分片收齐时，完整 MAVLink 帧入完成队列，可通过
     * {@link #receive()} 取出。未收齐时仅缓存分片。
     *
     * @param loraFragment 单个 LoRa 分片（含 3 字节头），不能为 null
     * @return 若本片使帧收齐则返回完整 MAVLink 帧字节，否则返回 null
     */
    public byte[] receive(byte[] loraFragment) {
        if (loraFragment == null || loraFragment.length < FRAG_HEADER_BYTES) {
            SimLog.warn("LoRaMavlink receive: invalid fragment length "
                    + (loraFragment == null ? "null" : loraFragment.length));
            return null;
        }
        int frameId = loraFragment[0] & 0xFF;
        byte[] complete = loRa.reassemble(frameId, loraFragment);
        if (complete != null) {
            synchronized (completedFrames) {
                completedFrames.addLast(complete);
            }
            SimLog.info("LoRaMavlink receive: frameId=" + frameId + " complete, len=" + complete.length);
        }
        return complete;
    }

    /**
     * 从完成队列弹出一个已重组完整的 MAVLink 帧。
     *
     * @return 完整 MAVLink 帧字节，若队列空则返回 null
     */
    public byte[] receive() {
        synchronized (completedFrames) {
            return completedFrames.pollFirst();
        }
    }

    /**
     * 获取所有待发送分片（不弹出，仅快照）。
     * <p>
     * 供物理层批量读取待发分片。取出后应调用 {@link #clearPendingFragments()} 或
     * 使用 {@link #pollFragment()} 逐片弹出。
     *
     * @return 待发送分片列表（不可变快照），可能为空
     */
    public List<byte[]> getPendingFragments() {
        synchronized (pendingFragments) {
            return Collections.unmodifiableList(new ArrayList<>(pendingFragments));
        }
    }

    /**
     * 弹出一个待发送分片（FIFO）。
     *
     * @return 队首分片，队列空则返回 null
     */
    public byte[] pollFragment() {
        synchronized (pendingFragments) {
            return pendingFragments.pollFirst();
        }
    }

    /** 待发送分片数量。 */
    public int pendingFragmentCount() {
        synchronized (pendingFragments) {
            return pendingFragments.size();
        }
    }

    /** 已重组完成但尚未被 {@link #receive()} 取走的帧数量。 */
    public int completedFrameCount() {
        synchronized (completedFrames) {
            return completedFrames.size();
        }
    }

    /** 清空待发送分片队列（如物理层已全部发送完毕后重置）。 */
    public void clearPendingFragments() {
        synchronized (pendingFragments) {
            pendingFragments.clear();
        }
    }

    /**
     * 估算传输指定大小 MAVLink 帧的延迟（毫秒）。
     *
     * @param frameBytes 帧字节数
     * @return 估算延迟（毫秒），≥ 1
     */
    public long estimatedLatencyMs(int frameBytes) {
        return loRa.estimatedLatencyMs(frameBytes);
    }
}