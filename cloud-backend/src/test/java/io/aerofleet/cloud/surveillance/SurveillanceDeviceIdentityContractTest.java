package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SurveillanceDevice} 的构造校验边界与 equals/hashCode 契约
 * （P1 变异审计收口）。
 *
 * <p>构造器是全包的"参数.validated 进出口"：ip/port/username 的边界此前只有
 * "合法值通过"的正例，端口号 0 与 65535 两个边界从未被反例挑战（对应两个
 * 存活变异体：0 被放行 / 65535 被误拒）。equals 按 id 判等（设备身份由 id 承载），
 * 不同 id 的两个实例必须不等——{@code this == o} 短路分支被取反后，
 * 不同对象会被误判相等，这类语义错误只有反例能拦。
 */
@DisplayName("SurveillanceDevice 构造边界与身份契约")
class SurveillanceDeviceIdentityContractTest {

    private static SurveillanceDevice device(int port) {
        return new SurveillanceDevice("cam-1", "东门", SurveillanceDevice.Vendor.HIKVISION,
                "192.168.1.100", port, "admin", "pass");
    }

    @Test
    @DisplayName("port 边界：0 与 65536 拒绝；1 与 65535 通过")
    void portBoundaries() {
        assertThatThrownBy(() -> device(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("port must be in [1, 65535]");
        assertThatThrownBy(() -> device(65536))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("port must be in [1, 65535]");

        assertThat(device(1).port).isEqualTo(1);
        assertThat(device(65535).port).isEqualTo(65535);
    }

    @Test
    @DisplayName("equals 按 id 判等：同 id 不同实例相等，不同 id 不等")
    void equalsById() {
        SurveillanceDevice a = device(80);
        SurveillanceDevice sameId = new SurveillanceDevice("cam-1", "别名机位",
                SurveillanceDevice.Vendor.DAHUA, "10.0.0.9", 554, "admin", "x");
        SurveillanceDevice other = new SurveillanceDevice("cam-2", "西门",
                SurveillanceDevice.Vendor.HIKVISION, "192.168.1.100", 80, "admin", "pass");

        assertThat(a).isEqualTo(a);                      // 自反（短路分支）
        assertThat(a).isEqualTo(sameId);                 // id 相同即同设备
        assertThat(a).isNotEqualTo(other);               // id 不同绝不相等
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("cam-1");             // 异类型 false，不抛异常

        assertThat(a).hasSameHashCodeAs(sameId);
        // 变异收口（hashCode 被替换成常量 0）：equals⇒同哈希只保证"相同 id"一侧，
        // 还必须钉住"不同 id 不碰撞"的一半契约——否则恒 0 的 hashCode
        // （把设备塞进 HashSet/HashMap 后全部挤进同一个桶）也满足上面的断言。
        // cam-1 与 cam-2 只差末字符，String 哈希必不等（无巧合碰撞）。
        assertThat(a.hashCode()).isNotEqualTo(other.hashCode());
    }

    @Test
    @DisplayName("setCapabilities(null) 归一为空集而非 null（下游依赖此不变式）")
    void capabilitiesNeverNull() {
        SurveillanceDevice d = device(80);
        d.setCapabilities(null);
        assertThat(d.getCapabilities()).isNotNull().isEmpty();

        d.setCapabilities(Set.of("Media"));
        assertThat(d.getCapabilities()).containsExactly("Media");
    }
}
