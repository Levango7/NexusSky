package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CVE-2026-47890（CRITICAL，CVSS 9.8）的可达性前提断言 —— 与 {@link XsltViewReachabilityTest}
 * 是<b>两条不同 CVE 的两条独立守卫</b>，不可互相替代。
 *
 * <h2>为什么需要这份测试</h2>
 * CI 用 {@code .trivyignore.yaml} 压掉了这条发现。没有这份测试，那个忽略就是一句静态承诺：
 * 将来任何人引入视图片段渲染路径，忽略条目仍然生效，于是「已评估为不可达」悄悄变成
 * 「真的可达且仍被忽略」。本测试让前提失效时<b>测试先红</b>，而不是等真被打。
 *
 * <h2>advisory 的触发前提（三条须同时成立）</h2>
 * <ol>
 *   <li>应用使用 Spring MVC 或 Spring WebFlux；</li>
 *   <li><b>应用通过 SSE 向客户端发送 view fragments</b>；</li>
 *   <li>攻击者能控制要推送给其他用户的数据。</li>
 * </ol>
 * 本仓满足 ①（纯 Spring MVC）。<b>②不满足</b>——这是本测试要钉的东西。
 * 上游修复说明进一步限定了机制：「view fragments rendered by
 * {@code ViewResolutionResultHandler} and {@code ResponseBodyEmitterReturnValueHandler}」
 * 中的裸回车可截断 SSE 字段。也就是说触发路径必须经过<b>视图解析</b>。
 *
 * <h2>实测基线（2026-10-08）</h2>
 * 全仓零 {@code @Controller}（只有 {@code @RestController}，返回 Map/DTO/ResponseEntity）⇒
 * {@code ViewResolutionResultHandler} 永不参与；4 个 {@code ViewResolver} bean
 * （BeanNameViewResolver / ViewResolverComposite / InternalResourceViewResolver /
 * ContentNegotiatingViewResolver）无一带 XSLT；{@code AlarmController#streamEvents}
 * 是唯一 SSE 端点，只发 {@code .comment()} 与 {@code .data(Map)}，从不发视图片段。
 */
@SpringBootTest(properties = {
        "aerofleet.security.dev-mode=true",
        "aerofleet.udp-port=0",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("CVE-2026-47890 可达性前提：无视图片段 SSE 渲染路径")
class SseViewFragmentReachabilityTest {

    @Autowired
    private ApplicationContext ctx;

    /** 定位仓库根（从 cloud-backend 模块目录往上两级）。 */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            if (Files.exists(p.resolve("pom.xml")) && Files.isDirectory(p.resolve("cloud-backend"))) {
                return p;
            }
        }
        throw new IllegalStateException("未定位到仓库根（当前工作目录: " + Path.of("").toAbsolutePath() + "）");
    }

    private List<HandlerMethod> allHandlerMethods() {
        return ctx.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
                .flatMap(m -> m.getHandlerMethods().entrySet().stream()
                        .map(e -> e.getValue()))
                .toList();
    }

    @Test
    @DisplayName("无任何非 @RestController 的 @Controller 视图控制器")
    void noPlainControllerBeans() {
        // @RestController 是 @Controller 的**元注解**，所以"类上带 @Controller"并不能区分两者；
        // 而 CGLIB 代理类又不会继承目标类上的注解（@Controller 非 @Inherited），
        // 因此不能用 bean.getClass().getAnnotation() 判断 —— 那会把全部 @RestController 误判。
        // 正确做法：取 HandlerMethod.getBeanType()（用户类，非代理类），再按注解区分。
        //
        // 语义：@RestController 的 handler 一律走 @ResponseBody 体序列化，**不会**触发视图解析；
        // 只有裸 @Controller 返回视图名（String/ModelAndView/View）才会进入
        // ViewResolutionResultHandler —— 那正是本 CVE 的唯一入口。
        List<String> plainControllers = allHandlerMethods().stream()
                .map(HandlerMethod::getBeanType)
                // 排除框架自带的控制器：Spring Boot 的 BasicErrorController 确实是
                // 非 @RestController 的 @Controller（errorHtml 返回 ModelAndView），
                // 属框架内建行为、且只在错误响应下渲染视图，**不经 SSE**，
                // 不构成本 CVE 的触发路径。只对本仓自己的控制器设约束。
                .filter(t -> !t.getName().startsWith("org.springframework."))
                .filter(t -> t.getAnnotation(Controller.class) != null)
                .filter(t -> t.getAnnotation(
                        org.springframework.web.bind.annotation.RestController.class) == null)
                .map(Class::getSimpleName)
                .distinct()
                .toList();
        assertTrue(plainControllers.isEmpty(),
                "出现非 @RestController 的应用级视图控制器 → ViewResolutionResultHandler 可能参与，"
                        + "CVE-2026-47890 变为可达，必须复核并考虑撤销 .trivyignore.yaml 的条目: "
                        + plainControllers);

        // 自证：确实扫到了 controller（否则上面的阴性结论是空断言）
        long distinctBeans = allHandlerMethods().stream()
                .map(HandlerMethod::getBeanType).distinct().count();
        assertTrue(distinctBeans > 50,
                "只扫到 " + distinctBeans + " 个 controller 类型，样本过少，阴性结论不可信");
    }

    @Test
    @DisplayName("没有任何 handler 方法返回视图名类型（String/ModelAndView/View）")
    void noHandlerReturnsViewName() {
        // 双保险：即便将来有人加了 @Controller，也拦下"返回 String 视图名"这一形态。
        // 注意 @RestController 返回 String 是响应体而非视图名，故这里按注解区分。
        List<String> viewReturning = allHandlerMethods().stream()
                .filter(hm -> {
                    Class<?> rt = hm.getMethod().getReturnType();
                    boolean viewish = String.class.equals(rt)
                            || org.springframework.web.servlet.ModelAndView.class.equals(rt)
                            || org.springframework.web.servlet.View.class.equals(rt);
                    if (!viewish) {
                        return false;
                    }
                    // @RestController 下 String 是 body，不是视图名
                    Class<?> beanType = hm.getBeanType();
                    return beanType.getAnnotation(Controller.class) == null;
                })
                .map(hm -> hm.getMethod().getName() + " -> " + hm.getMethod().getReturnType().getSimpleName())
                .toList();
        assertTrue(viewReturning.isEmpty(),
                "出现返回视图名的 handler → 视图解析路径存在，SSE 场景下 CVE-2026-47890 可达: "
                        + viewReturning);
    }

    @Test
    @DisplayName("SSE 端点存在且只发数据/注释，不发视图片段（避免空断言）")
    void sseEndpointsExistButSendNoViewFragments() throws IOException {
        // 前置自证：若仓内根本没有 SSE，本测试的阴性结论毫无意义。
        Path src = repoRoot().resolve("cloud-backend/src/main/java");
        List<Path> sseFiles;
        try (Stream<Path> walk = Files.walk(src)) {
            sseFiles = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).contains("SseEmitter");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        }
        assertTrue(!sseFiles.isEmpty(),
                "未找到任何使用 SseEmitter 的源文件 —— 若 SSE 真被移除，本条忽略的前提需重新评估"
                        + "（CVE-2026-47890 随 SSE 一起消失）");

        // 逐个 SSE 端点源文件核对：不得出现视图解析相关调用
        List<String> offenders = new java.util.ArrayList<>();
        for (Path f : sseFiles) {
            String src2 = Files.readString(f, StandardCharsets.UTF_8);
            for (String forbidden : List.of("setViewName", "ModelAndView", "addObject",
                    "resolveViewName", "ViewResolutionResultHandler")) {
                if (src2.contains(forbidden)) {
                    offenders.add(f.getFileName() + " -> " + forbidden);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "SSE 相关代码出现视图解析调用 → view fragments 可能进入事件流，"
                        + "CVE-2026-47890 变为可达: " + offenders);
    }

    @Test
    @DisplayName("基线自证：上下文确有 handler 映射，且 ViewResolver 数量与基线一致")
    void contextBaselineUnchanged() {
        // 防"空断言"：上下文若没装配 controller，下面几条 0 命中的断言会同样通过，
        // 但那不代表安全，只代表没测到东西。
        long mapped = ctx.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
                .mapToLong(m -> m.getHandlerMethods().size())
                .sum();
        assertTrue(mapped > 100,
                "web 上下文映射数异常偏小（基线 360+），本测试的阴性结果不可信: " + mapped);
        assertEquals(4, ctx.getBeanNamesForType(ViewResolver.class).length,
                "ViewResolver 数量与基线不一致：装配面变了，可达性结论需重新评估（基线 4）");
    }
}
