package io.aerofleet.cloud.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟视觉数据源（M3 感知成像增强，FR-04）。
 * <p>
 * 基于合成目标世界产出带置信度的检测 + 跟踪关联，模拟真实 CV 模型输出。
 * 对相机视野内的合成目标，计算其像素投影 (u,v) + 类别 kind + 合成置信度（0.7-1.0 随机）
 * + 跟踪 ID。
 * <p>
 * 与 {@link ProjectionVisionSource} 的差异：置信度合成（非 1.0）+ 跟踪 ID 关联。
 * 视野外目标不出现在检测结果中（由 CameraShot.projectedTargets() 已过滤）。
 * <p>
 * 注：design.md 原设计本类在 drone-sim/sim 包，但 drone-sim 不依赖 cloud-backend，
 * 无法 implements cloud-backend 的 VisionSource 接口。务实调整为本类放在 cloud-backend/vision 包，
 * 功能不变（合成置信度 + 跟踪关联，不需要 drone-sim 的任何类）。
 */
public class SimulatedVisionSource implements VisionSource {

    private final Random rng;
    /** 简单跟踪 ID 计数器（模拟 TargetTracker.associate）。 */
    private int nextTrackId = 1;

    public SimulatedVisionSource() {
        this(new Random());
    }

    /** 测试可注入确定性 Random。 */
    public SimulatedVisionSource(Random rng) {
        this.rng = rng;
    }

    @Override
    public List<VisionDetection> detect(CameraShot shot, CameraPose cameraPose) {
        List<VisionDetection> out = new ArrayList<>();
        if (shot == null || shot.projectedTargets() == null) {
            return out;  // 空 Shot → 空列表（非 null）
        }
        for (CameraShot.ProjectedTarget target : shot.projectedTargets()) {
            // FR-04 合成置信度 0.7-1.0 随机
            double confidence = 0.7 + rng.nextDouble() * 0.3;
            // 跟踪 ID 关联：基于 target.id 简单映射（同 id → 同 trackId）
            int trackId = target.id() >= 0 ? target.id() : nextTrackId++;
            // 置信度保留 2 位小数（避免浮点精度噪声）
            confidence = Math.round(confidence * 100) / 100.0;
            out.add(new VisionDetection(target.u(), target.v(), target.kind(),
                    confidence, trackId));
        }
        return out;
    }
}