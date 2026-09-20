package io.aerofleet.cloud.autodispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频流管理服务（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 管理无人机视频流状态，生成 RTSP URL，支持启动/停止视频流推送。
 * <p>
 * <b>RTSP URL 格式</b>：{@code rtsp://drone-{sysid}:8554/live}（模拟）。
 * <p>
 * <b>线程安全</b>：使用 {@link ConcurrentHashMap} 维护每架无人机的视频流状态。
 *
 * @see VideoStreamController
 */
@Service
public class VideoStreamService {

    private static final Logger log = LoggerFactory.getLogger(VideoStreamService.class);

    /** RTSP 默认端口。 */
    public static final int RTSP_PORT = 8554;
    /** RTSP URL 路径。 */
    public static final String RTSP_PATH = "/live";

    /** sysid → 视频流状态。 */
    private final Map<Integer, StreamState> streamStates = new ConcurrentHashMap<>();

    /** 视频流状态枚举。 */
    public enum StreamStatus {
        /** 推流中。 */
        STREAMING,
        /** 空闲。 */
        IDLE,
        /** 错误。 */
        ERROR
    }

    /**
     * 生成无人机的 RTSP URL。
     * <p>
     * 格式：{@code rtsp://drone-{sysid}:{port}{path}}。
     *
     * @param sysid 无人机 systemId
     * @return RTSP URL
     */
    public String getRtspUrl(int sysid) {
        return "rtsp://drone-" + sysid + ":" + RTSP_PORT + RTSP_PATH;
    }

    /**
     * 启动视频流推送。
     * <p>
     * 将状态置为 {@link StreamStatus#STREAMING}，若已是 STREAMING 则幂等返回。
     *
     * @param sysid 无人机 systemId
     * @return 启动后的视频流状态
     */
    public StreamState startStream(int sysid) {
        StreamState state = streamStates.computeIfAbsent(sysid,
                id -> new StreamState(id, getRtspUrl(id), StreamStatus.IDLE));
        if (state.status == StreamStatus.STREAMING) {
            log.info("video stream already streaming: sysid={}", sysid);
            return state;
        }
        state.status = StreamStatus.STREAMING;
        state.startTimeMs = System.currentTimeMillis();
        log.info("video stream started: sysid={} url={}", sysid, state.url);
        return state;
    }

    /**
     * 停止视频流推送。
     * <p>
     * 将状态置为 {@link StreamStatus#IDLE}，若已是 IDLE 则幂等返回。
     *
     * @param sysid 无人机 systemId
     * @return 停止后的视频流状态
     */
    public StreamState stopStream(int sysid) {
        StreamState state = streamStates.computeIfAbsent(sysid,
                id -> new StreamState(id, getRtspUrl(id), StreamStatus.IDLE));
        if (state.status == StreamStatus.IDLE) {
            log.info("video stream already idle: sysid={}", sysid);
            return state;
        }
        state.status = StreamStatus.IDLE;
        state.stopTimeMs = System.currentTimeMillis();
        log.info("video stream stopped: sysid={}", sysid);
        return state;
    }

    /**
     * 获取视频流状态。
     * <p>
     * 未启动过视频流的无人机返回 {@link StreamStatus#IDLE} 状态。
     *
     * @param sysid 无人机 systemId
     * @return 视频流状态
     */
    public StreamState getStatus(int sysid) {
        return streamStates.computeIfAbsent(sysid,
                id -> new StreamState(id, getRtspUrl(id), StreamStatus.IDLE));
    }

    /**
     * 获取所有活跃视频流（状态为 STREAMING）。
     *
     * @return 活跃视频流列表
     */
    public List<StreamState> getActiveStreams() {
        List<StreamState> active = new ArrayList<>();
        for (StreamState state : streamStates.values()) {
            if (state.status == StreamStatus.STREAMING) {
                active.add(state);
            }
        }
        return active;
    }

    /**
     * 标记视频流为错误状态。
     *
     * @param sysid 无人机 systemId
     * @param errorMsg 错误信息
     */
    public void markError(int sysid, String errorMsg) {
        StreamState state = streamStates.computeIfAbsent(sysid,
                id -> new StreamState(id, getRtspUrl(id), StreamStatus.IDLE));
        state.status = StreamStatus.ERROR;
        state.errorMsg = errorMsg;
        log.warn("video stream error: sysid={} error={}", sysid, errorMsg);
    }

    /** 当前活跃视频流数量。 */
    public int activeCount() {
        int count = 0;
        for (StreamState state : streamStates.values()) {
            if (state.status == StreamStatus.STREAMING) {
                count++;
            }
        }
        return count;
    }

    /**
     * 视频流状态值对象。
     */
    public static final class StreamState {
        /** 无人机 systemId。 */
        public final int sysid;
        /** RTSP URL。 */
        public final String url;
        /** 流状态。 */
        public volatile StreamStatus status;
        /** 启动时间戳（毫秒）。 */
        public volatile long startTimeMs;
        /** 停止时间戳（毫秒）。 */
        public volatile long stopTimeMs;
        /** 错误信息（ERROR 状态时填充）。 */
        public volatile String errorMsg;

        public StreamState(int sysid, String url, StreamStatus status) {
            this.sysid = sysid;
            this.url = url;
            this.status = status;
            this.startTimeMs = 0L;
            this.stopTimeMs = 0L;
            this.errorMsg = null;
        }

        public int getSysid() {
            return sysid;
        }

        public String getUrl() {
            return url;
        }

        public StreamStatus getStatus() {
            return status;
        }

        public long getStartTimeMs() {
            return startTimeMs;
        }

        public long getStopTimeMs() {
            return stopTimeMs;
        }

        public String getErrorMsg() {
            return errorMsg;
        }

        @Override
        public String toString() {
            return "StreamState{sysid=" + sysid
                    + ", url=" + url
                    + ", status=" + status + '}';
        }
    }
}