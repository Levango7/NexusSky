package io.aerofleet.cloud.show;

/**
 * 音乐同步配置（将动作序列与音乐节拍对齐）。
 * <p>
 * 通过 BPM（每分钟节拍数）和起始偏移量，将动作时间点对齐到音乐节拍，
 * 实现编队表演与音乐的同步效果。
 */
public final class MusicSync {

    private final String taskId;
    private final String musicUrl;
    private final double bpm;
    private final double startTimeOffsetSec;

    public MusicSync(String taskId, String musicUrl, double bpm, double startTimeOffsetSec) {
        this.taskId = taskId;
        this.musicUrl = musicUrl;
        this.bpm = bpm;
        this.startTimeOffsetSec = startTimeOffsetSec;
    }

    public String getTaskId() { return taskId; }
    public String getMusicUrl() { return musicUrl; }
    public double getBpm() { return bpm; }
    public double getStartTimeOffsetSec() { return startTimeOffsetSec; }

    /** 计算单个节拍的持续时间（秒）。 */
    public double beatDurationSec() {
        return 60.0 / bpm;
    }

    /** 计算第 n 个节拍的时间点（秒，含起始偏移）。 */
    public double beatTime(int beatIndex) {
        return startTimeOffsetSec + beatIndex * beatDurationSec();
    }
}