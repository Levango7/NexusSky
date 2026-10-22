package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安防包的两条"**动作没人盯**"契约（2026-10-22 变异审计的 P0 两条）。
 *
 * <p>这两处的共同形状：**把生产代码里的一个动作删掉，现有测试照样全绿** ——
 * 也就是说测试根本没有覆盖那个动作。
 *
 * <ul>
 *   <li>{@code MjpegStreamService.pump} 写完一段 multipart 后必须 {@code flush()}：
 *       不 flush，浏览器端可能一直收不到帧（表现是"面板在转圈、没有画面"）。
 *       PIT 存活变异体："removed call to OutputStream::flush"。</li>
 *   <li>{@code SurveillanceDeviceRegistry.pruneStaleDevices} 把超时设备置为 OFFLINE 后
 *       必须把状态**落库**（{@code persistStaleStatus}）：
 *       不落库，重启后设备又是 ONLINE —— 内存里改了、数据库没改。
 *       PIT 存活变异体："removed call to persistStaleStatus"。</li>
 * </ul>
 *
 * <p><b>怎么验证它们真的在守着那件事</b>：把生产代码改坏（删掉 flush / 删掉落库调用），
 * 本类必须**变红** —— 见 PR 描述里的变异验证记录。
 */
@DisplayName("安防：动作级契约（flush / 落库，变异审计 P0）")
class SurveillanceStreamAndPruneContractTest {

    /** 计数的输出流：看得见 write 与 flush 各被调了几次。 */
    private static final class CountingOutputStream extends OutputStream {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        int writeCount;
        int flushCount;

        @Override
        public void write(int b) {
            writeCount++;
            sink.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writeCount++;
            sink.write(b, off, len);
        }

        @Override
        public void flush() {
            flushCount++;
        }

        String text() {
            return new String(sink.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    private static byte[] jpegFrame() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB, 0x00, 0x01};
    }

    @Test
    @DisplayName("1. pump 写完一段 multipart 后必须 flush（不 flush 浏览器收不到帧）")
    void pumpMustFlushAfterWritingPart() throws IOException {
        MjpegStreamService svc = new MjpegStreamService();
        CountingOutputStream out = new CountingOutputStream();

        // 帧源不可达（127.0.0.1:1 连不上 ⇒ fetchFrame 返回 null ⇒ pump 写完首帧即返回）
        svc.pump("http://127.0.0.1:1/snapshot", 5, jpegFrame(), out);

        assertThat(out.writeCount)
                .as("首帧的 multipart 分段必须真的写出去")
                .isGreaterThan(0);
        assertThat(out.text()).contains("Content-Type: image/jpeg");
        assertThat(out.flushCount)
                .as("写完分段必须 flush（删掉 flush() 这个用例就要红）")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("2. pruneStaleDevices 把设备置 OFFLINE 后必须落库（不落库重启后又变 ONLINE）")
    void pruneStaleDevicesMustPersistOfflineStatus() {
        SurveillanceDeviceRepository repo = Mockito.mock(SurveillanceDeviceRepository.class);
        SurveillanceDeviceEntity entity = new SurveillanceDeviceEntity();
        entity.setId("cam-stale");
        entity.setStatus(SurveillanceDevice.Status.ONLINE.name());
        Mockito.when(repo.findById("cam-stale")).thenReturn(Optional.of(entity));

        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry(repo);
        SurveillanceDevice device = new SurveillanceDevice("cam-stale", "超时摄像头",
                SurveillanceDevice.Vendor.HIKVISION, "192.168.1.77", 80, "admin", "pass");
        device.status = SurveillanceDevice.Status.ONLINE;
        device.lastHeartbeatMs = System.currentTimeMillis() - 60_000L;
        registry.register(device);

        List<String> stale = registry.pruneStaleDevices(1_000L);

        assertThat(stale).containsExactly("cam-stale");
        assertThat(device.status).isEqualTo(SurveillanceDevice.Status.OFFLINE);
        Mockito.verify(repo, Mockito.atLeastOnce())
                .save(entity);
        assertThat(entity.getStatus())
                .as("落库的实体状态必须是 OFFLINE（内存改了、库里没改 ⇒ 重启就变回去）")
                .isEqualTo(SurveillanceDevice.Status.OFFLINE.name());
    }

    @Test
    @DisplayName("3. 心跳新鲜的设备不该被置 OFFLINE（别把活着的设备误杀）")
    void pruneMustKeepFreshDevices() {
        SurveillanceDeviceRepository repo = Mockito.mock(SurveillanceDeviceRepository.class);
        SurveillanceDeviceRegistry registry = new SurveillanceDeviceRegistry(repo);
        SurveillanceDevice fresh = new SurveillanceDevice("cam-fresh", "在线摄像头",
                SurveillanceDevice.Vendor.HIKVISION, "192.168.1.78", 80, "admin", "pass");
        fresh.status = SurveillanceDevice.Status.ONLINE;
        fresh.lastHeartbeatMs = System.currentTimeMillis();
        registry.register(fresh);
        // 注册本身会落库一次（persistDevice）⇒ 清掉交互，只看 prune 做了什么
        Mockito.clearInvocations(repo);

        List<String> stale = registry.pruneStaleDevices(60_000L);

        assertThat(stale).isEmpty();
        assertThat(fresh.status).isEqualTo(SurveillanceDevice.Status.ONLINE);
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
    }
}
