package io.aerofleet.sim;

import io.aerofleet.mavlink.messages.MavlinkMessage;
import io.aerofleet.mavlink.messages.EdgeTaskStatusMsg;
import io.aerofleet.mavlink.messages.SensorFusionDataMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M12 边缘推理接线层的单元测试：用假 {@link EdgeInferenceRunner.Host} 驱动，
 * 不依赖真实 VirtualDrone/物理层。断言四类行为：
 * <ol>
 *   <li>30053/30054 的产生节奏与字段（含 LiDAR 掩码位、融合 resultSize=30054 LEN）；</li>
 *   <li>EKF 收敛性（无噪 GPS 下融合位姿=真值、恒东向速度下航向≈90°）；</li>
 *   <li>EdgeNode 任务登记表与任务 id 的一致性；</li>
 *   <li>故障隔离（send 抛异常/相机返回 null/dt=0 都不许打断 tick）。</li>
 * </ol>
 */
class EdgeInferenceRunnerTest {

    private static final double DT = 0.05;   // 20Hz tick

    // ------------------------------------------------------------------
    // 假宿主
    // ------------------------------------------------------------------

    private static final class FakeHost implements EdgeInferenceRunner.Host {
        int sysid = 7;
        double lat = 30.0;
        double lon = 120.0;
        double alt = 50.0;
        double vn;
        double ve;
        double vu;
        Double lidar;                                  // null = 无注入式 LiDAR
        Supplier<CameraModel.Shot> shots;             // null = 相机不可用
        final List<MavlinkMessage> sent = new ArrayList<>();
        int failFirstNSends = 0;

        @Override
        public int sysid() {
            return sysid;
        }

        @Override
        public double gpsLat() {
            return lat;
        }

        @Override
        public double gpsLon() {
            return lon;
        }

        @Override
        public double gpsAltM() {
            return alt;
        }

        @Override
        public double velNorthMps() {
            return vn;
        }

        @Override
        public double velEastMps() {
            return ve;
        }

        @Override
        public double velUpMps() {
            return vu;
        }

        @Override
        public Double lidarAltM() {
            return lidar;
        }

        @Override
        public CameraModel.Shot captureShot() {
            return shots != null ? shots.get() : null;
        }

        @Override
        public void send(MavlinkMessage msg) {
            if (failFirstNSends > 0) {
                failFirstNSends--;
                throw new RuntimeException("send boom #" + failFirstNSends);
            }
            sent.add(msg);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static CameraModel.Shot shot(long seq, double altM, double... uvPairs) {
        List<CameraModel.CapturedTarget> targets = new ArrayList<>();
        for (int i = 0; i + 1 < uvPairs.length; i += 2) {
            targets.add(new CameraModel.CapturedTarget(i / 2, "vehicle",
                    uvPairs[i], uvPairs[i + 1], 30.0, 120.0));
        }
        return new CameraModel.Shot(seq, seq * 500, 30.0, 120.0, altM,
                0, 0, 0, 0, 0, targets);
    }

    /** 以 50ms 步进驱动 [fromMs, toMs]。 */
    private static void drive(EdgeInferenceRunner r, long fromMs, long toMs, double dt) {
        for (long t = fromMs; t <= toMs; t += 50) {
            r.tick(t, dt);
        }
    }

    private static List<EdgeTaskStatusMsg> taskMsgs(FakeHost h, int taskType) {
        List<EdgeTaskStatusMsg> out = new ArrayList<>();
        for (MavlinkMessage m : h.sent) {
            if (m instanceof EdgeTaskStatusMsg e && e.taskType == taskType) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<SensorFusionDataMsg> fusionMsgs(FakeHost h) {
        List<SensorFusionDataMsg> out = new ArrayList<>();
        for (MavlinkMessage m : h.sent) {
            if (m instanceof SensorFusionDataMsg f) {
                out.add(f);
            }
        }
        return out;
    }

    private static FakeHost stationaryHost() {
        FakeHost h = new FakeHost();
        h.shots = () -> shot(1, 60.0);
        return h;
    }

    // ------------------------------------------------------------------
    // 视频分析链路（30053, taskType=VIDEO_ANALYSIS）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("视觉周期 2Hz：每个周期发一条 30053(视频分析, 完成)，首帧只缓存故检测结果为空")
    void visionEmitsEdgeTaskStatusPerCycle() {
        FakeHost h = stationaryHost();
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 1200, DT);
        List<EdgeTaskStatusMsg> msgs = taskMsgs(h, 0);
        assertEquals(3, msgs.size(), "0/500/1000ms 三个视觉周期应各发一条 30053");
        for (EdgeTaskStatusMsg m : msgs) {
            assertEquals(0, m.taskType, "taskType=VIDEO_ANALYSIS(0)");
            assertEquals(2, m.status, "status=COMPLETED(2)");
            assertEquals(7, m.sysId);
        }
        assertEquals(0, msgs.get(0).resultSize, "首帧只缓存前帧，无检测");
        assertEquals(0, msgs.get(0).edgeTaskId, "任务 id 从 0 递增");
        assertEquals(2, msgs.get(2).edgeTaskId);
    }

    @Test
    @DisplayName("目标在帧间移动 → 帧差/连通域真实检出，第二个周期 resultSize>0")
    void movingTargetYieldsNonZeroResultSize() {
        FakeHost h = new FakeHost();
        CameraModel.Shot s1 = shot(1, 60.0, 960, 540);
        CameraModel.Shot s2 = shot(2, 60.0, 1100, 540);
        CameraModel.Shot[] seq = {s1, s2, s2};
        AtomicInteger i = new AtomicInteger();
        h.shots = () -> seq[Math.min(i.getAndIncrement(), seq.length - 1)];
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 1200, DT);
        List<EdgeTaskStatusMsg> msgs = taskMsgs(h, 0);
        assertEquals(3, msgs.size());
        assertEquals(0, msgs.get(0).resultSize, "首帧缓存，无检测");
        assertTrue(msgs.get(1).resultSize > 0,
                "第二帧相对第一帧存在位移（u 960→1100，160 宽下 80→92 px），"
                        + "帧差应点亮新旧两个位置，resultSize 应非 0");
        assertTrue(msgs.get(2).resultSize == 0,
                "第三帧与第二帧同位（悬停），无运动 → 无检测");
    }

    @Test
    @DisplayName("相机返回 null 时跳过视觉周期，不产生 30053 也不登记任务")
    void nullShotSkipsVisionCycle() {
        FakeHost h = stationaryHost();
        h.shots = null;
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 1100, DT);
        for (MavlinkMessage m : h.sent) {
            if (m instanceof EdgeTaskStatusMsg e) {
                assertEquals(1, e.taskType, "只剩融合任务状态，不应有视频分析 30053");
            }
        }
        assertTrue(fusionMsgs(h).size() >= 1, "融合链路不受相机缺失影响");
    }

    // ------------------------------------------------------------------
    // 传感器融合链路（30054 + 30053, taskType=SENSOR_FUSION）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("融合 1Hz 下发：首个周期只建时间基准，1s 后发 30054，无噪 GPS 下融合位姿=真值")
    void fusionEmitsSensorFusionDataAfterOneSecond() {
        FakeHost h = stationaryHost();
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 950, DT);
        assertEquals(0, fusionMsgs(h).size(), "1s 内只更新不下发");
        drive(r, 1000, 1050, DT);
        List<SensorFusionDataMsg> msgs = fusionMsgs(h);
        assertEquals(1, msgs.size(), "t=1000ms 恰发一条");
        SensorFusionDataMsg m = msgs.get(0);
        assertEquals(300_000_000, m.fusedLat, "无噪 GPS 观测下融合纬度=真值 30°");
        assertEquals(1_200_000_000, m.fusedLon, "融合经度=真值 120°");
        assertEquals(50_000, m.fusedAlt, "融合高度=50m（mm）");
        assertEquals(7, m.sysId);
        assertEquals(3, m.sensorMask, "GPS(1)|IMU(2)，无 LiDAR 源故无 bit3");
    }

    @Test
    @DisplayName("注入式 LiDAR 在环时 sensorMask 含 LIDAR 位（1|2|8=11）")
    void lidarSourcePresentAddsLidarMaskBit() {
        FakeHost h = stationaryHost();
        h.lidar = 50.0;
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 1050, DT);
        List<SensorFusionDataMsg> msgs = fusionMsgs(h);
        assertEquals(1, msgs.size());
        assertEquals(11, msgs.get(0).sensorMask, "GPS|IMU|LIDAR");
    }

    @Test
    @DisplayName("恒东向 8m/s：速度观测收敛后融合航向≈90°（9000 cdeg 附近）")
    void eastMotionYieldsEastHeading() {
        FakeHost h = stationaryHost();
        h.ve = 8.0;
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 1050, DT);
        SensorFusionDataMsg m = fusionMsgs(h).get(0);
        assertTrue(m.fusedHeading >= 8500 && m.fusedHeading <= 9500,
                "东向航向应≈9000 cdeg，实测 " + m.fusedHeading);
        assertTrue(m.fusedVelocity > 7.0f, "速度状态应收敛到≈8m/s，实测 " + m.fusedVelocity);
    }

    @Test
    @DisplayName("融合任务状态 30053：taskType=SENSOR_FUSION(1)、resultSize=30054 LEN(24)、status=完成")
    void fusionTaskStatusAnnounces24ByteResult() {
        FakeHost h = stationaryHost();
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 1050, DT);
        List<EdgeTaskStatusMsg> fusion = taskMsgs(h, 1);
        assertEquals(1, fusion.size(), "与 30054 同拍，1Hz 一条");
        EdgeTaskStatusMsg m = fusion.get(0);
        assertEquals(SensorFusionDataMsg.LEN, m.resultSize, "融合结果即 30054 payload");
        assertEquals(2, m.status);
        assertEquals(0, m.edgeTaskId, "融合任务 id 从 0 递增");
    }

    @Test
    @DisplayName("dt=0（时钟停摆）不崩溃：预测跳过，更新与下发照常")
    void zeroDtStillFusesAndEmits() {
        FakeHost h = stationaryHost();
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 1050, 0.0);
        List<SensorFusionDataMsg> msgs = fusionMsgs(h);
        assertEquals(1, msgs.size(), "更新/下发不依赖 dt");
        assertEquals(300_000_000, msgs.get(0).fusedLat);
    }

    // ------------------------------------------------------------------
    // EdgeNode 任务登记与故障隔离
    // ------------------------------------------------------------------

    @Test
    @DisplayName("EdgeNode 任务登记表随周期增长，已知任务=COMPLETED、未知=UNKNOWN")
    void edgeNodeRegistryTracksTasks() {
        FakeHost h = stationaryHost();
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        drive(r, 0, 600, DT);
        assertEquals(2, r.edgeTaskCount(), "video-0/video-1 两条任务");
        assertEquals("COMPLETED", r.edgeTaskStatus("video-0"));
        assertEquals("COMPLETED", r.edgeTaskStatus("video-1"));
        assertEquals("UNKNOWN", r.edgeTaskStatus("nope"));
    }

    @Test
    @DisplayName("send 抛异常只影响当轮阶段：tick 不传播异常，后续消息照发")
    void sendFailureIsIsolated() {
        FakeHost h = stationaryHost();
        h.failFirstNSends = 1;
        EdgeInferenceRunner r = new EdgeInferenceRunner(h);
        r.tick(0, DT);   // 首条 30053 的 send 抛 RuntimeException，被 tick 就地吞掉
        h.failFirstNSends = 0;
        drive(r, 50, 600, DT);
        assertEquals(1, taskMsgs(h, 0).size(),
                "故障后下一视觉周期（t=500）的 30053 正常发出且任务 id 未跳号");
        assertEquals(0, taskMsgs(h, 0).get(0).edgeTaskId, "失败的周期不消耗任务 id");
        assertEquals("COMPLETED", r.edgeTaskStatus("video-0"));
    }
}
