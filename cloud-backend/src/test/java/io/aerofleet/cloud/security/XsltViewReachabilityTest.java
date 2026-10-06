package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CVE-2026-47884（GHSA-pc63-qcmh-9cmg，CRITICAL，`spring-webmvc` 6.2.x 线**无修复版**，
 * 唯一 fixed 是 7.0.9）的可达性前提断言。
 *
 * <p>存在理由：CI 用 `.trivyignore.yaml` 把这条发现压掉了。没有这份测试的话，那个忽略就是
 * 一句静态承诺——将来任何人加上 XSLT 视图层或一条 {@code /**} 视图映射，忽略条目仍然生效，
 * 于是「已评估为不可达」悄悄变成「真的可达且仍被忽略」。本测试让前提失效时**测试先红**，
 * 而不是等真被打。
 *
 * <p>advisory 的触发条件（原文摘要）：应用存在导致视图渲染的 {@code /**} 映射，
 * 且视图名未转义，并使用了 {@code XsltView}。三条里任何一条成立都必须撤销忽略。
 *
 * <p>实测基线（2026-10-06 本机，dev profile）：ViewResolver bean 4 个
 * （BeanNameViewResolver / ViewResolverComposite / InternalResourceViewResolver /
 * ContentNegotiatingViewResolver，均非 Xslt），RequestMappingHandlerMapping 的
 * handler method 360 个，其中含 {@code /**} 的模式 0 个。
 */
@SpringBootTest(properties = {
        "aerofleet.security.dev-mode=true",
        "aerofleet.udp-port=0",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("CVE-2026-47884 可达性前提：无 XsltView 解析路径、无 /** 视图映射")
class XsltViewReachabilityTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("上下文里不存在任何 XsltViewResolver / XsltView 形态的视图解析器")
    void noXsltViewResolverRegistered() {
        List<String> xsltResolvers = Arrays.stream(ctx.getBeanNamesForType(ViewResolver.class))
                .filter(name -> ctx.getType(name).getName().toLowerCase().contains("xslt"))
                .toList();
        assertTrue(xsltResolvers.isEmpty(),
                "出现 XSLT 视图解析器 → CVE-2026-47884 变为可达，必须撤销 .trivyignore.yaml 里的条目："
                        + xsltResolvers);

        // 兜住"通过 viewClass 指向 XsltView"这条非典型配置路径
        List<String> xsltViewClasses = ctx.getBeansOfType(ViewResolver.class).values().stream()
                .map(v -> v.getClass().getName())
                .filter(n -> n.toLowerCase().contains("xslt"))
                .toList();
        assertTrue(xsltViewClasses.isEmpty(), "同上：" + xsltViewClasses);
    }

    @Test
    @DisplayName("没有任何 handler 映射使用 /** 兜底模式")
    void noCatchAllViewMapping() {
        List<String> catchAll = ctx.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
                .flatMap(m -> m.getHandlerMethods().keySet().stream())
                .map(Object::toString)
                .filter(p -> p.contains("/**"))
                .toList();
        assertTrue(catchAll.isEmpty(),
                "出现 /** 映射 → 满足 advisory 的前提条件，必须撤销忽略并复核视图名转义：" + catchAll);
    }

    @Test
    @DisplayName("基线自证：本测试确实跑在有真实 controller 映射的完整 web 上下文里")
    void contextActuallyHasHandlerMappings() {
        // 防"空断言"：如果上下文里根本没有 controller（比如条件装配失效），
        // 上面两条 0 命中的断言会同样通过，但那不代表安全，只代表没测到东西。
        long mapped = ctx.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
                .mapToLong(m -> m.getHandlerMethods().size())
                .sum();
        assertTrue(mapped > 100,
                "web 上下文映射数异常偏小（实测基线 360），本测试的阴性结果不可信: " + mapped);
        assertEquals(4, ctx.getBeanNamesForType(ViewResolver.class).length,
                "ViewResolver 数量与基线不一致：装配面变了，可达性结论需要重新区分（基线 4）");
    }
}
