package io.aerofleet.cloud.twin;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.PredictionResultMsg;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M13 孪生 REST API 测试 + PREDICTION_RESULT(30056) 生产者断言：
 * /predict 在孪生有状态时发布 MavlinkMessageEvent（经 WS_TYPE_MAP 以
 * "prediction-result" 帧转发 GCS），无状态/空轨迹不发布。
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class TwinControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private DigitalTwinService twinService;
    @Autowired private ApplicationEvents events;

    @Test
    void testGetAllStates() throws Exception {
        mockMvc.perform(get("/api/v1/twin/state")).andExpect(status().isOk());
    }

    @Test
    void testPredict() throws Exception {
        mockMvc.perform(get("/api/v1/twin/predict/1").param("horizon", "10")).andExpect(status().isOk());
    }

    @Test
    void testPredictPublishesPredictionResultFrame() throws Exception {
        // sysid=42：30°N/120°E/50m，航向 90°（正东）10m/s
        twinService.syncTwin(42, 30.0, 120.0, 50.0, 90.0, 10.0, 80.0);
        mockMvc.perform(get("/api/v1/twin/predict/42").param("horizon", "10"))
                .andExpect(status().isOk());

        List<PredictionResultMsg> frames = predictionFramesOf(42);
        assertEquals(1, frames.size(), "每次 /predict 发布一条 30056");
        PredictionResultMsg m = frames.get(0);
        assertEquals(42, m.sysId);
        assertEquals(10, m.predictionHorizonSec);
        assertEquals(10, m.trajectoryPoints, "步数 = min(horizon, 30)");
        assertEquals(300_000_000, m.predictedLat, "正东航向 → 纬度不变（1E7）");
        assertEquals(50_000, m.predictedAlt, "50 m → 50000 mm");
        assertTrue(m.predictedLon > 1_200_000_000, "正东航向 → 经度东移（1E7）: " + m.predictedLon);
        assertEquals(0.8f, m.confidence, 1e-6);
    }

    @Test
    void testPredictWithoutTwinStatePublishesNoFrame() throws Exception {
        mockMvc.perform(get("/api/v1/twin/predict/424242")).andExpect(status().isOk());
        assertEquals(0, predictionFramesOf(424242).size(), "无孪生状态 → 空轨迹 → 不发布");
    }

    /** 从本测试方法录制的事件流中筛出指定 sysid 的 30056 消息。 */
    private List<PredictionResultMsg> predictionFramesOf(int sysid) {
        return events.stream(MavlinkMessageEvent.class)
                .filter(e -> e.getMsgId() == PredictionResultMsg.ID)
                .map(MavlinkMessageEvent::getMessage)
                .map(PredictionResultMsg.class::cast)
                .filter(m -> m.sysId == sysid)
                .collect(Collectors.toList());
    }
}
