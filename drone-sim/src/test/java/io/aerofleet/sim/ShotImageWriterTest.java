package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * renderGray（M12 边缘视觉链的灰度帧渲染）的契约测试：分辨率/噪声地板/确定性/
 * 亮斑投影。几何契约（u,v 均匀缩放自 1920×1080 针孔空间）与 JPEG 路径一致。
 */
class ShotImageWriterTest {

    private static CameraModel.Shot shot(long seq, double altM, double... uvPairs) {
        List<CameraModel.CapturedTarget> targets = new ArrayList<>();
        for (int i = 0; i + 1 < uvPairs.length; i += 2) {
            targets.add(new CameraModel.CapturedTarget(i / 2, "vehicle",
                    uvPairs[i], uvPairs[i + 1], 30.0, 120.0));
        }
        return new CameraModel.Shot(seq, seq * 500, 30.0, 120.0, altM,
                0, 0, 0, 0, 0, targets);
    }

    @Test
    @DisplayName("空场景帧：尺寸 w×h、噪声地板低于检测阈值、非法尺寸抛 IAE")
    void grayFrameDimensionsAndNoiseFloor() {
        byte[] f = ShotImageWriter.renderGray(shot(1, 60.0), 160, 90);
        assertEquals(160 * 90, f.length);
        int max = 0;
        for (byte b : f) {
            max = Math.max(max, b & 0xFF);
        }
        assertTrue(max <= 100, "无目标时噪声地板峰值应远离亮斑值 220，实测 " + max);
        assertThrows(IllegalArgumentException.class,
                () -> ShotImageWriter.renderGray(shot(1, 60.0), 0, 90));
        assertThrows(IllegalArgumentException.class,
                () -> ShotImageWriter.renderGray(shot(1, 60.0), 160, -1));
    }

    @Test
    @DisplayName("确定性：同一 frameSeq 渲染结果逐字节相等；frameSeq 变化则噪声不同")
    void grayFrameIsDeterministicPerFrameSeq() {
        CameraModel.Shot s = shot(7, 60.0, 960, 540);
        byte[] a = ShotImageWriter.renderGray(s, 160, 90);
        byte[] b = ShotImageWriter.renderGray(shot(7, 60.0, 960, 540), 160, 90);
        assertTrue(Arrays.equals(a, b), "噪声由 frameSeq 播种，应可复现");
        byte[] c = ShotImageWriter.renderGray(shot(8, 60.0, 960, 540), 160, 90);
        assertFalse(Arrays.equals(a, c), "不同 frameSeq 的噪声底不同");
    }

    @Test
    @DisplayName("亮斑投影：目标 (u,v) 均匀缩放到 (80,45) 处渲染为亮值，远处仍是噪声")
    void blobRendersBrightAtProjectedPosition() {
        byte[] f = ShotImageWriter.renderGray(shot(1, 60.0, 960, 540), 160, 90);
        assertEquals(220, f[45 * 160 + 80] & 0xFF, "960*160/1920=80, 540*90/1080=45");
        assertTrue((f[0] & 0xFF) < 100, "左上角无目标，仍是噪声地板");
    }
}
