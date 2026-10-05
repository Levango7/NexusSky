package io.aerofleet.cloud.twin;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.PredictionResultMsg;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;

/** M13 数字孪生 REST API */
@RestController
@RequestMapping("/api/v1/twin")
@RequireRole(Role.OBSERVER)
public class TwinController {
    private final DigitalTwinService twinService;
    private final PredictionService predictionService;
    private final TwinComparisonService comparisonService;
    private final ApplicationEventPublisher eventPublisher;

    public TwinController(DigitalTwinService twinService, PredictionService predictionService,
                          TwinComparisonService comparisonService,
                          ApplicationEventPublisher eventPublisher) {
        this.twinService = twinService;
        this.predictionService = predictionService;
        this.comparisonService = comparisonService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/state")
    public Collection<TwinState> allStates() { return twinService.getAllTwins(); }

    @GetMapping("/state/{sysid}")
    public TwinState droneState(@PathVariable int sysid) { return twinService.getTwin(sysid); }

    @GetMapping("/predict/{sysid}")
    public PredictionResult predict(@PathVariable int sysid, @RequestParam(defaultValue = "30") int horizon) {
        TwinState s = twinService.getTwin(sysid);
        if (s == null) return new PredictionResult(sysid, Collections.emptyList(), horizon, 0);
        PredictionResult result = predictionService.predict(
                sysid, s.lat, s.lon, s.alt, s.heading, s.velocity, horizon);
        publishPredictionFrame(result);
        return result;
    }

    /**
     * 把预测结果发布为 PREDICTION_RESULT(30056) 事件：TelemetryWebSocketHandler
     * 按 WS_TYPE_MAP 以 "prediction-result" 帧转发给 GCS（2026-10-05 起 30056
     * 全仓首个生产者，此前协议帧定义了但无人产出）。
     * <p>
     * 语义：predictedLat/Lon/Alt 取<b>预测时域末端点</b>（horizon 秒后的落点），
     * trajectoryPoints 为完整轨迹点数，conf 为模型置信度；事件 sysid 用被预测
     * 无人机的 sysid（WS 转发侧按它做租户可见性判定）。空轨迹（孪生无状态等）
     * 不发布。compare 端点内部的 1 秒近似预测属虚实对比用途，不在此发布。
     */
    private void publishPredictionFrame(PredictionResult r) {
        if (r.trajectoryPoints.isEmpty()) {
            return;
        }
        double[] last = r.trajectoryPoints.get(r.trajectoryPoints.size() - 1);
        PredictionResultMsg msg = new PredictionResultMsg(
                (int) Math.round(last[0] * 1e7),
                (int) Math.round(last[1] * 1e7),
                (int) Math.round(last[2] * 1000.0),
                (float) r.confidence,
                r.horizonSec,
                r.sysid,
                r.trajectoryPoints.size());
        eventPublisher.publishEvent(new MavlinkMessageEvent(
                this, r.sysid, PredictionResultMsg.ID, msg, System.currentTimeMillis()));
    }

    @GetMapping("/compare/{sysid}")
    public ComparisonResult compare(@PathVariable int sysid) {
        TwinState actual = twinService.getTwin(sysid);
        if (actual == null) return new ComparisonResult(sysid, 0, 0, 0);
        // 虚实对比：actual 为最新遥测实测态，predicted 为基于当前状态前向预测 1 秒的模型预测态。
        // 理想实现应对比"t-1 时刻对 t 的预测"与"t 时刻实测"，但 DigitalTwinService 未持久化历史预测，
        // 此处用当前状态前向预测 1 秒作为近似，评估模型短期推演与实测的偏差。
        PredictionResult prediction = predictionService.predict(
                sysid, actual.lat, actual.lon, actual.alt,
                actual.heading, actual.velocity, 1);
        TwinState predicted;
        if (prediction.trajectoryPoints.isEmpty()) {
            predicted = actual;
        } else {
            double[] p = prediction.trajectoryPoints.get(0);
            predicted = new TwinState(sysid, p[0], p[1], p[2],
                    actual.heading, actual.velocity, actual.battery,
                    actual.syncTimestamp, 0.0);
        }
        return comparisonService.compare(actual, predicted);
    }
}
