package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四个厂商适配器的 CIDR 前缀提取契约（P1 变异审计 #4 收口）。
 *
 * <p>{@code extractPrefix} 是各适配器私有静态方法，共同语义是<b>"先剥掉 CIDR
 * 后缀（/24），再把最后一个 '.' 之后的部分丢弃"</b>，所得前缀直接拼进设备 id
 * 与 ip（{@code hik-192.168.1-100} / {@code 192.168.1.100}），因此经公共入口
 * {@code discover(…)} 观察输出即可钉住它，不必反射开私有方法。
 *
 * <p>用例覆盖三类输入：带 {@code /}、不带 {@code /}、无点（把整段当主机名前缀）。
 * 对" Returning 空串抹掉前缀 / 丢弃前缀截取（回到整段）"两类变异体，前两类
 * 输入就能杀死；"contains 取反 / lastDot&gt;0 边界"两类在真实输入域上不可区分
 * （'/suffix' 与末尾点都会在 substring(0,lastDot) 处被丢掉，lastDot==0 只出现在
 * 以 '.' 开头的畸形输入上），按第四轮分诊口径记为行为等价，不造垃圾输入用例。
 */
@DisplayName("厂商适配器子网前缀提取契约（extractPrefix）")
class VendorAdapterPrefixContractTest {

    private static final String CIDR = "192.168.1.7/24";
    private static final String NO_SLASH = "10.20.30.55";
    private static final String NO_DOT = "camhost";

    @Test
    @DisplayName("HikAdapter：/24、无斜线、无点三类输入各产出精确 id/ip")
    void hikAdapter_prefixShapes() {
        List<SurveillanceDevice> cidr = new HikAdapter().discover(CIDR);
        assertThat(cidr).extracting(d -> d.id)
                .containsExactly("hik-192.168.1-100", "hik-192.168.1-101");
        assertThat(cidr).extracting(d -> d.ip)
                .containsExactly("192.168.1.100", "192.168.1.101");

        List<SurveillanceDevice> plain = new HikAdapter().discover(NO_SLASH);
        assertThat(plain).extracting(d -> d.id)
                .containsExactly("hik-10.20.30-100", "hik-10.20.30-101");
        assertThat(plain).extracting(d -> d.ip)
                .containsExactly("10.20.30.100", "10.20.30.101");

        List<SurveillanceDevice> host = new HikAdapter().discover(NO_DOT);
        assertThat(host).extracting(d -> d.id)
                .containsExactly("hik-camhost-100", "hik-camhost-101");
        assertThat(host).extracting(d -> d.ip)
                .containsExactly("camhost.100", "camhost.101");
    }

    @Test
    @DisplayName("DahuaAdapter：/24、无斜线、无点三类输入各产出精确 id/ip")
    void dahuaAdapter_prefixShapes() {
        List<SurveillanceDevice> cidr = new DahuaAdapter().discover(CIDR);
        assertThat(cidr).extracting(d -> d.id)
                .containsExactly("dahua-192.168.1-101", "dahua-192.168.1-102");
        assertThat(cidr).extracting(d -> d.ip)
                .containsExactly("192.168.1.101", "192.168.1.102");

        List<SurveillanceDevice> plain = new DahuaAdapter().discover(NO_SLASH);
        assertThat(plain).extracting(d -> d.id)
                .containsExactly("dahua-10.20.30-101", "dahua-10.20.30-102");
        assertThat(plain).extracting(d -> d.ip)
                .containsExactly("10.20.30.101", "10.20.30.102");

        List<SurveillanceDevice> host = new DahuaAdapter().discover(NO_DOT);
        assertThat(host).extracting(d -> d.id)
                .containsExactly("dahua-camhost-101", "dahua-camhost-102");
        assertThat(host).extracting(d -> d.ip)
                .containsExactly("camhost.101", "camhost.102");
    }

    @Test
    @DisplayName("UniviewAdapter：/24、无斜线、无点三类输入各产出精确 id/ip")
    void univiewAdapter_prefixShapes() {
        List<SurveillanceDevice> cidr = new UniviewAdapter().discover(CIDR);
        assertThat(cidr).extracting(d -> d.id)
                .containsExactly("uniview-192.168.1-102", "uniview-192.168.1-103");
        assertThat(cidr).extracting(d -> d.ip)
                .containsExactly("192.168.1.102", "192.168.1.103");

        List<SurveillanceDevice> plain = new UniviewAdapter().discover(NO_SLASH);
        assertThat(plain).extracting(d -> d.id)
                .containsExactly("uniview-10.20.30-102", "uniview-10.20.30-103");
        assertThat(plain).extracting(d -> d.ip)
                .containsExactly("10.20.30.102", "10.20.30.103");

        List<SurveillanceDevice> host = new UniviewAdapter().discover(NO_DOT);
        assertThat(host).extracting(d -> d.id)
                .containsExactly("uniview-camhost-102", "uniview-camhost-103");
        assertThat(host).extracting(d -> d.ip)
                .containsExactly("camhost.102", "camhost.103");
    }

    @Test
    @DisplayName("OnvifClient：/24 前缀进入 3 个预设设备的 id 与 ip")
    void onvifClient_cidrPrefixShapes() {
        List<SurveillanceDevice> devices = new OnvifClient().discoverDevices(CIDR);
        assertThat(devices).extracting(d -> d.id)
                .containsExactly("hik-192.168.1-100", "dahua-192.168.1-101", "uniview-192.168.1-102");
        assertThat(devices).extracting(d -> d.ip)
                .containsExactly("192.168.1.100", "192.168.1.101", "192.168.1.102");
    }

    /**
     * 易变形状（变异收口）：不带 {@code /} 的子网同样要走"最后一个点之前"
     * 规则。{@code contains("/")} 三元被取反时，无斜杠输入会走
     * {@code substring(0, indexOf('/'))}（indexOf 为 -1）直接炸掉
     * —— 而这在只测 CIDR 的旧用例下完全不可见。
     */
    @Test
    @DisplayName("OnvifClient：不带 / 的子网也按最后一个点取前缀")
    void onvifClient_noSlashShapes() {
        List<SurveillanceDevice> devices = new OnvifClient().discoverDevices("10.20.30.55");
        assertThat(devices).extracting(d -> d.id)
                .containsExactly("hik-10.20.30-100", "dahua-10.20.30-101", "uniview-10.20.30-102");
        assertThat(devices).extracting(d -> d.ip)
                .containsExactly("10.20.30.100", "10.20.30.101", "10.20.30.102");
    }

    @Test
    @DisplayName("StreamUrlOrigin 三值语义钉死：playable 与 deviceVerified 是两个独立维度")
    void streamUrlOrigin_semantics() {
        // "可能可播但未经验证"——两件事必须同时说出来（厂商规范推导）
        assertThat(StreamUrlOrigin.VENDOR_STANDARD_FORMAT.playable()).isTrue();
        assertThat(StreamUrlOrigin.VENDOR_STANDARD_FORMAT.deviceVerified()).isFalse();
        // 唯一"设备确认过"的值
        assertThat(StreamUrlOrigin.ONVIF_GETSTREAM_URI.playable()).isTrue();
        assertThat(StreamUrlOrigin.ONVIF_GETSTREAM_URI.deviceVerified()).isTrue();
        // 描述性标识：不可播
        assertThat(StreamUrlOrigin.DESCRIPTIVE_ONLY.playable()).isFalse();
        assertThat(StreamUrlOrigin.DESCRIPTIVE_ONLY.deviceVerified()).isFalse();
    }
}
