package io.aerofleet.cloud.dock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机巢 MQTT 网关的 **Spring 装配不变式**测试（CI 无 Broker 也能跑）。
 *
 * <p>为什么需要这条：#254 交付的 {@link MqttDockGateway} 有**两个**构造器（生产用 +
 * 包私有的测试注入 fake client 用），且都没标注 {@code @Autowired}。Spring 的
 * {@code AutowiredAnnotationBeanPostProcessor} 在"多个构造器且都未标注"时**不会**挑一个，
 * 而是退回去找**无参构造器**——找不到就抛 {@code No default constructor found}，
 * 整个应用起不来。
 *
 * <p>这个缺陷在单测里**抓不到**（单测直连构造器，绕过了 Spring 容器），直到
 * {@code scripts/e2e-dock-mqtt.ps1} 用**真后端**起 {@code transport=mqtt} 才暴露。
 * 那条 E2E 依赖 docker（本地 broker），进不了 CI 的常规单测门禁——所以这里用一条
 * **反射不变式**把同类缺陷钉死：只要这个类还有多个构造器，就必须显式标出 Spring
 * 要用的那一个。
 */
@DisplayName("MqttDockGateway：Spring 装配不变式（多构造器必须显式 @Autowired）")
class MqttDockGatewayWiringTest {

    @Test
    @DisplayName("存在多个构造器时，必须恰好有一个标注 @Autowired（否则 Spring 找无参构造器→起不来）")
    void multipleConstructorsRequireExactlyOneAutowired() {
        Constructor<?>[] ctors = MqttDockGateway.class.getDeclaredConstructors();

        assertThat(ctors.length)
                .as("MqttDockGateway 若将来收敛为单构造器，这条断言会提醒更新本测试")
                .isGreaterThan(1);

        List<Constructor<?>> autowired = Arrays.stream(ctors)
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .toList();

        assertThat(autowired)
                .as("多个构造器 + 零个 @Autowired ⇒ Spring 退去找无参构造器 ⇒ No default constructor found")
                .hasSize(1);
    }

    @Test
    @DisplayName("被 Spring 选中的构造器是生产构造器（ObjectMapper + DockProperties）")
    void autowiredConstructorIsTheProductionOne() {
        Constructor<?> selected = Arrays.stream(MqttDockGateway.class.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 @Autowired 构造器"));

        assertThat(selected.getParameterTypes())
                .containsExactly(ObjectMapper.class, DockProperties.class);
        assertThat(java.lang.reflect.Modifier.isPublic(selected.getModifiers())).isTrue();
    }
}
