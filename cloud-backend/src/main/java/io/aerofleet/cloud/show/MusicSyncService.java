package io.aerofleet.cloud.show;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音乐同步服务。
 * <p>
 * 根据 BPM（每分钟节拍数）计算动作时间点，将动作序列与音乐节拍对齐。
 * <p>
 * 同步策略：将动作序列中每个动作的 startTime 调整到最近的节拍时间点，
 * 保持动作间的相对时间关系不变，整体偏移使第一个动作对齐到音乐起始偏移。
 */
@Service
public class MusicSyncService {

    private static final Logger log = LoggerFactory.getLogger(MusicSyncService.class);

    private final Map<String, MusicSync> taskMusicSync = new ConcurrentHashMap<>();
    private final ShowTaskService taskService;
    private final ActionSequenceService actionSequenceService;

    public MusicSyncService(ShowTaskService taskService,
                            ActionSequenceService actionSequenceService) {
        this.taskService = taskService;
        this.actionSequenceService = actionSequenceService;
    }

    /**
     * 为表演任务配置音乐同步。
     *
     * @param taskId           表演任务 ID
     * @param musicUrl         音乐文件 URL
     * @param bpm              每分钟节拍数
     * @param startTimeOffsetSec 音乐起始偏移（秒）
     * @return 音乐同步配置
     * @throws NotFoundException  任务不存在
     * @throws BadRequestException 参数非法
     */
    public MusicSync configureMusicSync(String taskId, String musicUrl,
                                        double bpm, double startTimeOffsetSec) {
        taskService.getTask(taskId); // 校验任务存在
        if (musicUrl == null || musicUrl.isBlank()) {
            throw new BadRequestException("musicUrl is required");
        }
        if (bpm <= 0) {
            throw new BadRequestException("bpm must be positive");
        }
        if (startTimeOffsetSec < 0) {
            throw new BadRequestException("startTimeOffsetSec must not be negative");
        }
        MusicSync sync = new MusicSync(taskId, musicUrl, bpm, startTimeOffsetSec);
        taskMusicSync.put(taskId, sync);
        log.info("Music sync configured: taskId={} bpm={} offset={}s",
                taskId, bpm, startTimeOffsetSec);
        return sync;
    }

    /** 获取任务的音乐同步配置。 */
    public MusicSync getMusicSync(String taskId) {
        taskService.getTask(taskId); // 校验任务存在
        MusicSync sync = taskMusicSync.get(taskId);
        if (sync == null) {
            throw new NotFoundException("music sync not configured for task: " + taskId);
        }
        return sync;
    }

    /**
     * 将动作序列同步到音乐节拍。
     * <p>
     * 计算每个动作最近的节拍时间点，返回调整后的动作时间点列表。
     *
     * @param taskId 表演任务 ID
     * @return 每个动作对应的节拍时间点列表（秒）
     * @throws NotFoundException 音乐同步未配置
     */
    public List<Double> syncActionsToBeat(String taskId) {
        MusicSync sync = getMusicSync(taskId);
        List<ShowAction> actions = actionSequenceService.getActions(taskId);
        List<Double> beatTimes = new ArrayList<>(actions.size());
        double beatDuration = sync.beatDurationSec();
        for (ShowAction action : actions) {
            // 计算最近的节拍索引
            int beatIndex = (int) Math.round(action.getStartTime() / beatDuration);
            double alignedTime = sync.beatTime(beatIndex);
            beatTimes.add(alignedTime);
        }
        log.info("Actions synced to beat: taskId={} actions={} beatDuration={}s",
                taskId, beatTimes.size(), beatDuration);
        return beatTimes;
    }
}