package io.aerofleet.cloud.vision;

import java.util.ArrayList;
import java.util.List;

/**
 * 投影简化实现（M3 感知成像增强，FR-01/FR-03/FR-37）。
 * <p>
 * 从 {@link CameraShot#projectedTargets()} 直接读取 (u,v,kind)，
 * confidence 固定为 1.0（投影真值），trackId = targetId。
 * <p>
 * 这是 Baseline 的检测方式（直接从 CameraModel.Shot 的投影目标读取，不做像素级检测），
 * 封装为 VisionSource 实现以保持接口统一。{@code source=truth}（默认）时使用本类，
 * 行为与 Baseline 一致（FR-37）。
 */
public class ProjectionVisionSource implements VisionSource {

    @Override
    public List<VisionDetection> detect(CameraShot shot, CameraPose cameraPose) {
        List<VisionDetection> out = new ArrayList<>();
        if (shot == null || shot.projectedTargets() == null) {
            return out;  // 空 Shot → 空列表（非 null）
        }
        for (CameraShot.ProjectedTarget target : shot.projectedTargets()) {
            // 投影简化：confidence=1.0（真值），trackId=targetId
            out.add(new VisionDetection(target.u(), target.v(), target.kind(),
                    1.0, target.id()));
        }
        return out;
    }
}