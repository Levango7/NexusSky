package io.aerofleet.cloud.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC 声明覆盖率门禁：每个 HTTP 端点方法都必须显式声明角色归属
 * ——自身或其控制器类上有 {@link RequireRole}，或显式 {@link PermitAll}。
 * <p>
 * <b>为什么用反射而不是文本扫描</b>：曾写过一版 shell+awk 的门禁，它把方法签名里的
 * {@code @RequestBody} 当成注解行，导致"未声明"数从真实的 200+ 误报成 99——
 * 一个会<b>静默少报</b>的安全门禁比没有门禁更糟。这里直接读 Spring 的注解模型，
 * 判定与实际生效的东西同源，不可能漂移。
 * <p>
 * <b>为什么需要这条门禁</b>：{@link RoleInterceptor} 的默认值已从"无注解即放行"翻成
 * "无注解即 403"。翻转之后，"忘了标注"的表现是被 403 挡住——对已授权用户是故障，
 * 而修复动机很强（把它标成 {@code @PermitAll} 就能立刻通），所以必须有个东西在合并前
 * 拦住这种顺手放宽。
 */
@DisplayName("RBAC 端点声明覆盖率 (P0-2 fail-closed)")
class RbacEndpointCoverageTest {

    private static final String BASE_PACKAGE = "io.aerofleet.cloud";

    @Test
    @DisplayName("每个端点方法都有 @RequireRole 或 @PermitAll 声明")
    void everyEndpointDeclaresRbac() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(
                RestController.class));

        List<String> missing = new ArrayList<>();
        int endpointCount = 0;

        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            if (!(candidate instanceof AnnotatedBeanDefinition)) {
                continue;
            }
            Class<?> type = Class.forName(candidate.getBeanClassName());
            // 嵌套类形态（Outer$Inner）在本仓里只有测试夹具（RoleInterceptorTest 等内部造的
            // @RestController），不是生产端点；不过滤会让门禁数多出十条假端点。
            if (type.getName().contains("$")) {
                continue;
            }
            boolean classDeclares = type.isAnnotationPresent(RequireRole.class)
                    || type.isAnnotationPresent(PermitAll.class);

            for (Method method : type.getDeclaredMethods()) {
                // findMergedAnnotation 会把 @GetMapping/@PostMapping 等合并成 @RequestMapping，
                // 因此无需逐个枚举那五个注解，也不会漏掉方法级裸 @RequestMapping
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                endpointCount++;
                if (classDeclares
                        || method.isAnnotationPresent(RequireRole.class)
                        || method.isAnnotationPresent(PermitAll.class)) {
                    continue;
                }
                missing.add(String.format("%s#%s [%s %s]", type.getSimpleName(), method.getName(),
                        verbs(mapping), fullPath(type, mapping)));
            }
        }

        assertTrue(endpointCount > 0, "扫描到 0 个端点，说明扫描本身失效了，这条门禁已无意义");
        if (!missing.isEmpty()) {
            throw new AssertionError(String.format(
                    "%d/%d 个端点未声明 RBAC 归属（读=%s 写=%s 管理面=%s，确实需匿名才用 %s）：\n%s",
                    missing.size(), endpointCount, Role.OBSERVER, Role.OPERATOR, Role.ADMIN,
                    PermitAll.class.getSimpleName(), String.join("\n", missing)));
        }
    }

    /** 方法上的 HTTP 动词，供失败信息定位。 */
    private static String verbs(RequestMapping mapping) {
        Set<RequestMethod> methods = new TreeSet<>(Arrays.asList(mapping.method()));
        return methods.isEmpty() ? "ANY" : methods.toString();
    }

    /** 类级路径 + 方法级路径，仅为可读性，不追求与 Spring 完全一致的拼接。 */
    private static String fullPath(Class<?> type, RequestMapping methodMapping) {
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        String base = classMapping == null ? "" : first(classMapping.path(), classMapping.value());
        String sub = first(methodMapping.path(), methodMapping.value());
        if (base.isEmpty() && sub.isEmpty()) {
            return "/";
        }
        return (base + (sub.startsWith("/") || sub.isEmpty() ? "" : "/") + sub).replace("//", "/");
    }

    private static String first(String[] primary, String[] fallback) {
        if (primary.length > 0 && !primary[0].isEmpty()) {
            return primary[0];
        }
        return fallback.length > 0 ? fallback[0] : "";
    }
}
