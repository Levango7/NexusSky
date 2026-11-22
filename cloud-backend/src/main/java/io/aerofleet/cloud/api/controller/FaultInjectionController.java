package io.aerofleet.cloud.api.controller;

import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受控 5xx 故障端点——仅用于 CD4 自动回滚演练（验证 5xx 错误率指标与 abort 链路）。
 *
 * <p><b>默认不存在</b>：只有显式设置 {@code aerofleet.test.fault-endpoint-enabled=true}
 * 时才注册该 Bean；生产/常规部署里这个 URL 返回 404（由 ApiExceptionHandler 的
 * noRoute 处理），不会成为攻击面。
 *
 * <p>抛出的异常经全局 {@code ApiExceptionHandler} 转成 500（生产模式返回通用错误体），
 * Micrometer 由此记录 {@code http_server_requests_seconds_count{status="500"}}——
 * 正是 AnalysisTemplate 的 5xx 错误率查询所依赖的指标。
 */
@RestController
@RequestMapping("/api/v1/test/fault")
@RequireRole(Role.OBSERVER)
@ConditionalOnProperty(name = "aerofleet.test.fault-endpoint-enabled", havingValue = "true")
public class FaultInjectionController {

    /** 返回 500；用于演练时按固定频率调用，把 5xx 错误率抬过 Analysis 阈值。 */
    @GetMapping("/500")
    public void internalError() {
        throw new IllegalStateException("injected 5xx for rollback rehearsal");
    }
}
