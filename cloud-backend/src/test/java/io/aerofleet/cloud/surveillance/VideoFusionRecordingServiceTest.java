package io.aerofleet.cloud.surveillance;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VideoFusionRecordingService} 登记簿契约（P1 变异审计收口）。
 *
 * <p>登记簿是骨架阶段的"诚实降级"：start 幂等不重置起点、来源类型冲突保留首次
 * 并发出 warn、stop 只在真实存在时返回并记录毫秒级时长。三个变异体全在
 * 日志与判等分支上（重登记静默改判 warn、冲突告警反向、时长算术翻成天文数字），
 * 因此经 {@link LogWiretap} 把日志当可观测行为断言。
 */
@DisplayName("VideoFusionRecordingService 录制登记簿")
class VideoFusionRecordingServiceTest {

    private static final Pattern DURATION_LINE = Pattern.compile("时长=(\\d+)ms");

    @Test
    @DisplayName("start 幂等：同 id 重登记返回原记录，不重置起点，也不告警")
    void startIsIdempotentWithoutNoise() {
        VideoFusionRecordingService svc = new VideoFusionRecordingService();

        VideoFusionRecordingService.Recording first;
        VideoFusionRecordingService.Recording again;
        try (LogWiretap tap = LogWiretap.on(VideoFusionRecordingService.class)) {
            first = svc.start("cam-1", "surveillance");
            again = svc.start("cam-1", "surveillance");

            assertThat(again).isSameAs(first);
            assertThat(again.startedAtMs()).isEqualTo(first.startedAtMs());
            assertThat(tap.linesAtLeast(Level.WARN)).isEmpty();   // 无来源冲突就不许告警
        }
    }

    @Test
    @DisplayName("来源类型冲突：保留首次登记的 sourceType，且发出 warn")
    void startKeepsFirstSourceTypeAndWarns() {
        VideoFusionRecordingService svc = new VideoFusionRecordingService();

        try (LogWiretap tap = LogWiretap.on(VideoFusionRecordingService.class)) {
            VideoFusionRecordingService.Recording rec = svc.start("pid-7", "drone");
            VideoFusionRecordingService.Recording reused = svc.start("pid-7", "surveillance");

            assertThat(reused).isSameAs(rec);
            assertThat(reused.sourceType()).isEqualTo("drone");   // 不静默改写来源标签

            List<String> warns = tap.linesAtLeast(Level.WARN);
            assertThat(warns).singleElement().asString()
                    .contains("保留首次登记的来源类型")
                    .contains("首次=drone")
                    .contains("再次=surveillance");
        }
    }

    @Test
    @DisplayName("stop 记录的时长是真实毫秒差（不是 now+start 的天文数字）")
    void stopLogsRealDurationMs() {
        VideoFusionRecordingService svc = new VideoFusionRecordingService();
        svc.start("cam-2", "surveillance");

        VideoFusionRecordingService.Recording removed;
        try (LogWiretap tap = LogWiretap.on(VideoFusionRecordingService.class)) {
            removed = svc.stop("cam-2");
            assertThat(removed).isNotNull();
            assertThat(svc.isRecording("cam-2")).isFalse();

            String durationLine = tap.lines().stream()
                    .filter(s -> s.contains("时长="))
                    .findFirst().orElseThrow();
            Matcher m = DURATION_LINE.matcher(durationLine);
            assertThat(m.find()).isTrue();
            long durationMs = Long.parseLong(m.group(1));
            // 登记到停止就几毫秒；变异体把减法翻成加法会得到 ~3.4e12
            assertThat(durationMs).isLessThan(1_000_000);
        }
    }

    @Test
    @DisplayName("stop 未在录的源返回 null（调用方据此回 404）")
    void stopUnknownReturnsNull() {
        VideoFusionRecordingService svc = new VideoFusionRecordingService();
        assertThat(svc.stop("never-started")).isNull();
        assertThat(svc.activeCount()).isZero();
    }
}
