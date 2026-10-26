package io.aerofleet.cloud.surveillance.onvif;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OnvifDiscoveryClient.Ipv4Cidr} 的解析/匹配边界契约。
 *
 * <p>这个类只在 live 发现的结果过滤路上用，但它是唯一一个手写位运算的地方：
 * 八位组 0/255、前缀 0/32 的边界一旦写错，"网段过滤"会在边界地址上静默失效，
 * 而常规发现测试（只用一个落在 /24 里的地址）根本看不出来。
 */
@DisplayName("Ipv4Cidr 解析与匹配边界")
class OnvifDiscoveryClientIpv4CidrTest {

    @Test
    @DisplayName("合法 CIDR 解析并匹配网段内/外地址")
    void parseAndMatch() {
        OnvifDiscoveryClient.Ipv4Cidr cidr = OnvifDiscoveryClient.Ipv4Cidr.parse("10.1.2.0/24");

        assertThat(cidr).isNotNull();
        assertThat(cidr.contains("10.1.2.200")).isTrue();
        assertThat(cidr.contains("10.1.3.1")).isFalse();
    }

    @Test
    @DisplayName("非 CIDR 输入一律返回 null（live 发现不据此过滤，只记 WARN）")
    void parse_rejectsMalformed() {
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse(null)).isNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("")).isNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("10.1.2.0")).isNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("10.1.2/24")).isNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("a.b.c.d/24")).isNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("10.1.2.3/x")).isNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("10.1.2.256/24")).isNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("10.1.2.0/33")).isNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("10.1.2.0/-1")).isNull();
    }

    @Test
    @DisplayName("边界八位组 0 与 255 是合法值")
    void parse_octetBoundariesAreValid() {
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("0.0.0.0/0")).isNotNull();
        assertThat(OnvifDiscoveryClient.Ipv4Cidr.parse("255.255.255.255/32")).isNotNull();
    }

    @Test
    @DisplayName("/0 匹配一切（含 0.0.0.0 与 255.255.255.255），/32 只匹配它自己")
    void prefixLengthBoundaries() {
        OnvifDiscoveryClient.Ipv4Cidr all = OnvifDiscoveryClient.Ipv4Cidr.parse("10.0.0.0/0");
        assertThat(all).isNotNull();
        assertThat(all.contains("0.0.0.0")).isTrue();
        assertThat(all.contains("255.255.255.255")).isTrue();

        OnvifDiscoveryClient.Ipv4Cidr host = OnvifDiscoveryClient.Ipv4Cidr.parse("10.1.2.3/32");
        assertThat(host).isNotNull();
        assertThat(host.contains("10.1.2.3")).isTrue();
        assertThat(host.contains("10.1.2.4")).isFalse();
    }

    @Test
    @DisplayName("contains 对畸形地址返回 false（含边界八位组的畸形形式）")
    void contains_rejectsMalformedIp() {
        OnvifDiscoveryClient.Ipv4Cidr cidr = OnvifDiscoveryClient.Ipv4Cidr.parse("0.0.0.0/0");

        assertThat(cidr.contains("10.1.2")).isFalse();
        assertThat(cidr.contains("10.1.2.999")).isFalse();
        assertThat(cidr.contains("10.1.2.x")).isFalse();
    }
}
