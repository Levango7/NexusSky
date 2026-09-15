package io.aerofleet.cloud.vision;

import java.util.List;

/**
 * 视觉数据源抽象接口（M3 感知成像增强，FR-01）。
 * <p>
 * 检测数据源的接入点：输入相机帧 + 相机位姿，输出检测结果列表
 * {@code List<VisionDetection>}（每个含 u,v,kind,confidence,trackId）。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link ProjectionVisionSource}：投影简化（从 Shot.projectedTargets 直接读取，confidence=1.0）</li>
 *   <li>{@code io.aerofleet.sim.SimulatedVisionSource}：模拟检测（合成置信度 0.7-1.0 + 跟踪关联）</li>
 *   <li>既有 {@link BlobDetector} 路径：source=pixels 时绕过本接口</li>
 * </ul>
 * 投影简化与真实 CV 模型均为本接口的实现，未来接真模型只需新增实现类。
 * <p>
 * 契约：detect() 必须返回非 null 列表（无目标时返回空列表）。
 */
public interface VisionSource {

    /**
     * 从相机帧产出检测结果。
     *
     * @param shot       相机帧元数据（含投影目标 + 相机位姿）
     * @param cameraPose 相机位姿（roll/pitch/yaw/gimbalPitch/gimbalYaw）
     * @return 检测结果列表（空列表表示无目标，非 null）
     */
    List<VisionDetection> detect(CameraShot shot, CameraPose cameraPose);
}