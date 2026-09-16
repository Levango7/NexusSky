package io.aerofleet.cloud.twin;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/** M13 数字孪生 REST API */
@RestController
@RequestMapping("/api/twin")
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
        TwinState s = twinService.getTwin(sysid);
        if (s == null) return new ComparisonResult(sysid, 0, 0, 0);
        return comparisonService.compare(s, s);
    }
}
