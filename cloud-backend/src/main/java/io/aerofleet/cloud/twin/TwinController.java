package io.aerofleet.cloud.twin;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/** M13 数字孪生 REST API */
@RestController
@RequestMapping("/api/v1/twin")
public class TwinController {
    private final DigitalTwinService twinService;
    private final PredictionService predictionService;
    private final TwinComparisonService comparisonService;

    public TwinController(DigitalTwinService twinService, PredictionService predictionService,
                          TwinComparisonService comparisonService) {
        this.twinService = twinService;
        this.predictionService = predictionService;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/state")
    public Collection<TwinState> allStates() { return twinService.getAllTwins(); }

    @GetMapping("/state/{sysid}")
    public TwinState droneState(@PathVariable int sysid) { return twinService.getTwin(sysid); }

    @GetMapping("/predict/{sysid}")
    public PredictionResult predict(@PathVariable int sysid, @RequestParam(defaultValue = "30") int horizon) {
        TwinState s = twinService.getTwin(sysid);
        if (s == null) return new PredictionResult(sysid, Collections.emptyList(), horizon, 0);
        return predictionService.predict(sysid, s.lat, s.lon, s.alt, s.heading, s.velocity, horizon);
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
