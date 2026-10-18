package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeviceCapability} 单元测试。
 *
 * <p>重点盯两件容易做错的事：<b>别名折叠</b>（否则 GB28181 相机的取流会被误判成
 * 不支持）和<b>三态语义</b>（把 UNKNOWN 压成布尔值就是失真）。
 */
@DisplayName("DeviceCapability 能力口径归一与三态判定")
class DeviceCapabilityTest {

    // ===== 别名折叠 =====

    @Test
    @DisplayName("STREAM 折叠成 MEDIA —— GB28181 相机的取流不能被误判成不支持")
    void streamAliasFoldsToMedia() {
        Set<String> gb28181 = Set.of("PTZ", "STREAM", "RECORD");

        assertThat(DeviceCapability.normalize(gb28181))
                .contains(DeviceCapability.MEDIA, DeviceCapability.PTZ,
                        DeviceCapability.RECORDING)
                .doesNotContain("STREAM", "RECORD");

        assertThat(DeviceCapability.supportOf(gb28181, DeviceCapability.MEDIA))
                .isEqualTo(DeviceCapability.Support.YES);
    }

    @Test
    @DisplayName("大小写/空白无关：ONVIF 系的 Device/Media/Events/PTZ 能被认出来")
    void normalizesCaseAndWhitespace() {
        Set<String> onvif = Set.of("Device", " Media", "Events", "PTZ ", "imaging");

        assertThat(DeviceCapability.normalize(onvif))
                .containsExactlyInAnyOrder(DeviceCapability.DEVICE, DeviceCapability.MEDIA,
                        DeviceCapability.EVENTS, DeviceCapability.PTZ, DeviceCapability.IMAGING);
    }

    @Test
    @DisplayName("未知名字保留而不是静默丢弃（丢了就看不出设备声明过什么）")
    void keepsUnknownNames() {
        assertThat(DeviceCapability.normalize(Set.of("PTZ", "SomeVendorThing")))
                .contains(DeviceCapability.PTZ, "SOMEVENDORTHING");
    }

    @Test
    @DisplayName("null / 空 / 空白项都不炸，且归一后可能为空集")
    void toleratesNullAndBlanks() {
        assertThat(DeviceCapability.normalize(null)).isEmpty();
        assertThat(DeviceCapability.normalize(Set.of())).isEmpty();
        assertThat(DeviceCapability.normalize(java.util.Arrays.asList(null, "  ", ""))).isEmpty();
    }

    // ===== 三态语义 =====

    @Test
    @DisplayName("空能力集 = UNKNOWN，不是 NO（把未知当不支持就是功能减法）")
    void emptyMeansUnknown() {
        assertThat(DeviceCapability.supportOf(Set.of(), DeviceCapability.PTZ))
                .isEqualTo(DeviceCapability.Support.UNKNOWN);
        assertThat(DeviceCapability.supportOf(null, DeviceCapability.PTZ))
                .isEqualTo(DeviceCapability.Support.UNKNOWN);
    }

    @Test
    @DisplayName("声明了能力集但缺该项 = NO（这才是「明确不支持」）")
    void declaredButMissingIsNo() {
        Set<String> alarmOnly = Set.of("ALARM");

        assertThat(DeviceCapability.supportOf(alarmOnly, DeviceCapability.PTZ))
                .isEqualTo(DeviceCapability.Support.NO);
        assertThat(DeviceCapability.supportOf(alarmOnly, DeviceCapability.ALARM))
                .isEqualTo(DeviceCapability.Support.YES);
    }

    // ===== 两个便捷判定的语义差异（这是本类存在的理由） =====

    @Test
    @DisplayName("手动操作口径：未声明即放行，明确不支持才拦")
    void permissivePolicyForManualActions() {
        Set<String> undeclared = Set.of();
        Set<String> alarmOnly = Set.of("ALARM");

        assertThat(DeviceCapability.notExplicitlyUnsupported(undeclared, DeviceCapability.PTZ))
                .isTrue();
        assertThat(DeviceCapability.notExplicitlyUnsupported(alarmOnly, DeviceCapability.PTZ))
                .isFalse();
        assertThat(DeviceCapability.notExplicitlyUnsupported(Set.of("PTZ"), DeviceCapability.PTZ))
                .isTrue();
    }

    @Test
    @DisplayName("自动选设备口径：必须明确声明才认（保守）")
    void conservativePolicyForAutoSelection() {
        assertThat(DeviceCapability.explicitlySupported(Set.of(), DeviceCapability.PTZ)).isFalse();
        assertThat(DeviceCapability.explicitlySupported(Set.of("ALARM"), DeviceCapability.PTZ)).isFalse();
        assertThat(DeviceCapability.explicitlySupported(Set.of("PTZ"), DeviceCapability.PTZ)).isTrue();
        assertThat(DeviceCapability.explicitlySupported(Set.of("STREAM", "PTZ"), DeviceCapability.PTZ))
                .isTrue();
    }

    /**
     * 两种口径在「未声明」上**故意相反**。这不是 bug，是两种场景的正当差异；
     * 这条用例把差异钉住，防止有人后来把它们"统一"成同一个（那会悄悄
     * 要么削功能、要么自动误选设备）。
     */
    @Test
    @DisplayName("两种口径在 UNKNOWN 上故意相反 —— 钉住这个差异")
    void policiesDifferOnUnknownByDesign() {
        Set<String> undeclared = Set.of();

        assertThat(DeviceCapability.notExplicitlyUnsupported(undeclared, DeviceCapability.PTZ))
                .isNotEqualTo(DeviceCapability.explicitlySupported(undeclared, DeviceCapability.PTZ));
    }

    // ===== 归一化不修改入参 =====

    @Test
    @DisplayName("normalize 不修改调用方传入的集合")
    void doesNotMutateInput() {
        Set<String> input = new LinkedHashSet<>(List.of("STREAM", "PTZ"));
        Set<String> snapshot = new LinkedHashSet<>(input);

        DeviceCapability.normalize(input);

        assertThat(input).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("normalize 返回的集合不可改（防止调用方绕过归一口径）")
    void resultIsImmutable() {
        Set<String> out = DeviceCapability.normalize(Set.of("PTZ"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> out.add("MEDIA"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}