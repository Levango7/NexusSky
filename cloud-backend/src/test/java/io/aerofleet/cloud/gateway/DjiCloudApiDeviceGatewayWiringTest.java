package io.aerofleet.cloud.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 装配不变式——与 MqttDockGatewayWiringTest 同型：
 * 本类有两个构造器（生产：{@code DjiCloudApiProperties + ObjectMapper + DockRepository}；
 * 测试：包私有 fake 注入），恰好一个必须 {@code @Autowired}，
 * 否则 Spring 退找无参构造器 → 启动失败。
 */
@DisplayName("DjiCloudApiDeviceGateway：Spring 装配不变式（多构造器必须显式 @Autowired）")
class DjiCloudApiDeviceGatewayWiringTest {

    @Test
    @DisplayName("存在多个构造器时，必须恰好有一个标注 @Autowired")
    void multipleConstructorsRequireExactlyOneAutowired() {
        Constructor<?>[] ctors = DjiCloudApiDeviceGateway.class.getDeclaredConstructors();

        assertThat(ctors.length)
                .as("DjiCloudApiDeviceGateway 若将来收敛为单构造器，这条断言会提醒更新本测试")
                .isGreaterThan(1);

        List<Constructor<?>> autowired = Arrays.stream(ctors)
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .toList();

        assertThat(autowired)
                .as("多个构造器 + 零个 @Autowired ⇒ Spring 退去找无参构造器 ⇒ 起不来")
                .hasSize(1);
    }

    @Test
    @DisplayName("被 Spring 选中的构造器是生产构造器（DjiCloudApiProperties + ObjectMapper + DockRepository）")
    void autowiredConstructorIsTheProductionOne() {
        Constructor<?> selected = Arrays.stream(DjiCloudApiDeviceGateway.class.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 @Autowired 构造器"));

        assertThat(selected.getParameterTypes())
                .containsExactly(DjiCloudApiProperties.class,
                        com.fasterxml.jackson.databind.ObjectMapper.class,
                        io.aerofleet.cloud.dock.DockRepository.class);
        assertThat(java.lang.reflect.Modifier.isPublic(selected.getModifiers())).isTrue();
    }
}
