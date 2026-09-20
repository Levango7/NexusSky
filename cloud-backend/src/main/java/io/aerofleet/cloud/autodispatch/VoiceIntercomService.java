package io.aerofleet.cloud.autodispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音对讲管理服务（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 管理无人机语音对讲状态，支持启动/停止双向语音对讲与广播喊话。
 * <p>
 * <b>广播喊话</b>：将文本转为语音指令发送给无人机（模拟 TTS + MAVLink 指令下发）。
 * <p>
 * <b>线程安全</b>：使用 {@link ConcurrentHashMap} 维护每架无人机的语音对讲状态。
 *
 * @see VoiceIntercomController
 */
@Service
public class VoiceIntercomService {

    private static final Logger log = LoggerFactory.getLogger(VoiceIntercomService.class);

    /** 默认广播音量（0-100）。 */
    public static final int DEFAULT_VOLUME = 70;
    /** 最大广播音量。 */
    public static final int MAX_VOLUME = 100;
    /** 最大广播文本长度。 */
    public static final int MAX_TEXT_LENGTH = 500;

    /** sysid → 语音对讲状态。 */
    private final Map<Integer, IntercomState> intercomStates = new ConcurrentHashMap<>();
    /** 广播历史（最新在前）。 */
    private final List<BroadcastRecord> broadcastHistory = new ArrayList<>();

    /** 语音对讲状态枚举。 */
    public enum IntercomStatus {
        /** 对讲中。 */
        ACTIVE,
        /** 未对讲。 */
        INACTIVE
    }

    /**
     * 启动双向语音对讲。
     * <p>
     * 将状态置为 {@link IntercomStatus#ACTIVE}，若已是 ACTIVE 则幂等返回。
     *
     * @param sysid 无人机 systemId
     * @return 启动后的对讲状态
     */
    public IntercomState startIntercom(int sysid) {
        IntercomState state = intercomStates.computeIfAbsent(sysid,
                id -> new IntercomState(id, IntercomStatus.INACTIVE));
        if (state.status == IntercomStatus.ACTIVE) {
            log.info("intercom already active: sysid={}", sysid);
            return state;
        }
        state.status = IntercomStatus.ACTIVE;
        state.startTimeMs = System.currentTimeMillis();
        log.info("intercom started: sysid={}", sysid);
        return state;
    }

    /**
     * 停止语音对讲。
     * <p>
     * 将状态置为 {@link IntercomStatus#INACTIVE}，若已是 INACTIVE 则幂等返回。
     *
     * @param sysid 无人机 systemId
     * @return 停止后的对讲状态
     */
    public IntercomState stopIntercom(int sysid) {
        IntercomState state = intercomStates.computeIfAbsent(sysid,
                id -> new IntercomState(id, IntercomStatus.INACTIVE));
        if (state.status == IntercomStatus.INACTIVE) {
            log.info("intercom already inactive: sysid={}", sysid);
            return state;
        }
        state.status = IntercomStatus.INACTIVE;
        state.stopTimeMs = System.currentTimeMillis();
        log.info("intercom stopped: sysid={}", sysid);
        return state;
    }

    /**
     * 获取语音对讲状态。
     * <p>
     * 未启动过对讲的无人机返回 {@link IntercomStatus#INACTIVE} 状态。
     *
     * @param sysid 无人机 systemId
     * @return 对讲状态
     */
    public IntercomState getStatus(int sysid) {
        return intercomStates.computeIfAbsent(sysid,
                id -> new IntercomState(id, IntercomStatus.INACTIVE));
    }

    /**
     * 广播喊话：将文本转为语音指令发送给无人机。
     * <p>
     * 流程：
     * <ol>
     *   <li>校验文本非空且长度 ≤ {@link #MAX_TEXT_LENGTH}</li>
     *   <li>校验音量在 [0, {@link #MAX_VOLUME}] 范围</li>
     *   <li>生成 TTS 语音指令（模拟）</li>
     *   <li>记录广播历史</li>
     * </ol>
     *
     * @param sysid  无人机 systemId
     * @param text   广播文本
     * @param volume 音量（0-100，<=0 时使用默认值）
     * @return 广播结果
     */
    public BroadcastResult broadcast(int sysid, String text, int volume) {
        if (text == null || text.isBlank()) {
            log.warn("broadcast text empty: sysid={}", sysid);
            return new BroadcastResult(sysid, "FAILED", "text must not be empty", 0);
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            log.warn("broadcast text too long: sysid={} length={}", sysid, text.length());
            return new BroadcastResult(sysid, "FAILED",
                    "text length exceeds " + MAX_TEXT_LENGTH, 0);
        }
        int effectiveVolume = volume > 0 ? Math.min(volume, MAX_VOLUME) : DEFAULT_VOLUME;

        // 模拟 TTS + MAVLink 指令下发
        String ttsInstruction = "TTS:" + text + "@volume=" + effectiveVolume;
        log.info("broadcast: sysid={} text='{}' volume={}", sysid, text, effectiveVolume);

        BroadcastRecord record = new BroadcastRecord(
                sysid, text, effectiveVolume, ttsInstruction, System.currentTimeMillis());
        synchronized (broadcastHistory) {
            broadcastHistory.add(record);
        }

        return new BroadcastResult(sysid, "SENT", "broadcast sent", effectiveVolume);
    }

    /**
     * 获取广播历史。
     *
     * @param sysid 无人机 systemId（<=0 表示全部）
     * @param limit 最多返回条数（<=0 表示不限制）
     * @return 广播历史列表
     */
    public List<BroadcastRecord> getBroadcastHistory(int sysid, int limit) {
        List<BroadcastRecord> snapshot;
        synchronized (broadcastHistory) {
            snapshot = new ArrayList<>(broadcastHistory);
        }
        List<BroadcastRecord> filtered = new ArrayList<>();
        for (BroadcastRecord r : snapshot) {
            if (sysid > 0 && r.sysid != sysid) {
                continue;
            }
            filtered.add(r);
        }
        if (limit > 0 && filtered.size() > limit) {
            return new ArrayList<>(filtered.subList(filtered.size() - limit, filtered.size()));
        }
        return filtered;
    }

    /** 当前活跃对讲数量。 */
    public int activeCount() {
        int count = 0;
        for (IntercomState state : intercomStates.values()) {
            if (state.status == IntercomStatus.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    /**
     * 语音对讲状态值对象。
     */
    public static final class IntercomState {
        /** 无人机 systemId。 */
        public final int sysid;
        /** 对讲状态。 */
        public volatile IntercomStatus status;
        /** 启动时间戳（毫秒）。 */
        public volatile long startTimeMs;
        /** 停止时间戳（毫秒）。 */
        public volatile long stopTimeMs;

        public IntercomState(int sysid, IntercomStatus status) {
            this.sysid = sysid;
            this.status = status;
            this.startTimeMs = 0L;
            this.stopTimeMs = 0L;
        }

        public int getSysid() {
            return sysid;
        }

        public IntercomStatus getStatus() {
            return status;
        }

        public long getStartTimeMs() {
            return startTimeMs;
        }

        public long getStopTimeMs() {
            return stopTimeMs;
        }

        @Override
        public String toString() {
            return "IntercomState{sysid=" + sysid + ", status=" + status + '}';
        }
    }

    /**
     * 广播结果。
     */
    public static final class BroadcastResult {
        /** 无人机 systemId。 */
        public final int sysid;
        /** 状态（SENT/FAILED）。 */
        public final String status;
        /** 消息。 */
        public final String message;
        /** 实际使用的音量。 */
        public final int volume;

        public BroadcastResult(int sysid, String status, String message, int volume) {
            this.sysid = sysid;
            this.status = status;
            this.message = message;
            this.volume = volume;
        }

        public int getSysid() {
            return sysid;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public int getVolume() {
            return volume;
        }
    }

    /**
     * 广播历史记录。
     */
    public static final class BroadcastRecord {
        /** 无人机 systemId。 */
        public final int sysid;
        /** 广播文本。 */
        public final String text;
        /** 音量。 */
        public final int volume;
        /** TTS 指令。 */
        public final String ttsInstruction;
        /** 时间戳（毫秒）。 */
        public final long timestampMs;

        public BroadcastRecord(int sysid, String text, int volume,
                               String ttsInstruction, long timestampMs) {
            this.sysid = sysid;
            this.text = text;
            this.volume = volume;
            this.ttsInstruction = ttsInstruction;
            this.timestampMs = timestampMs;
        }

        public int getSysid() {
            return sysid;
        }

        public String getText() {
            return text;
        }

        public int getVolume() {
            return volume;
        }

        public String getTtsInstruction() {
            return ttsInstruction;
        }

        public long getTimestampMs() {
            return timestampMs;
        }
    }
}