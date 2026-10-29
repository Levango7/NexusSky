package io.aerofleet.cloud.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditController} 查询上限单测。
 * <p>
 * 背景（2026-10-29）：{@code GET /api/v1/audit/logs} 不传 {@code limit} 时走
 * {@code auditService.findAll()}；落库模式（{@code aerofleet.audit.persist-to-db=true}）下
 * 那是 {@code findAllByOrderByIdDesc()}，把**整张审计表**读进堆再序列化。而 {@code audit_log}
 * 是只增表（每个 POST/PUT/DELETE/PATCH 一行），且 {@code aerofleet.audit.retention-days}
 * 默认 0（永不删除）——单机演示（内存模式，上限 1000 条）看不出来，落库跑几个月后
 * 一次不带 limit 的查询就能把堆打满，且调用方从参数上看不出自己在请求全量。
 * <p>
 * 这里钉住四件事：
 * ① 缺省 limit ⇒ 走 {@code findRecent(DEFAULT_LIMIT)}，**绝不**走 {@code findAll()}；
 * ② 超过上限 ⇒ 收敛到 {@value AuditController#MAX_LIMIT}；
 * ③ 非正数 ⇒ 收敛到 1（不再保留"0 = 全部"的旧语义）；
 * ④ 返回体字段映射不变（timestamp/userId/action/target/detail/ip/prevHash/entryHash）。
 */
class AuditControllerTest {

    private static AuditLog log() {
        return new AuditLog(Instant.parse("2026-10-29T03:00:00Z"), "admin", "POST",
                "/api/v1/users", null, "10.0.0.1", "0".repeat(64), "a".repeat(64));
    }

    private static AuditService serviceReturning(AuditLog... logs) {
        AuditService service = mock(AuditService.class);
        when(service.findRecent(anyInt())).thenReturn(List.of(logs));
        return service;
    }

    @Test
    @DisplayName("缺省 limit 走 findRecent(200)，不碰 findAll（旧实现这里是全表）")
    void defaultLimitIsBounded() {
        AuditService service = serviceReturning(log());

        ResponseEntity<List<Map<String, Object>>> resp =
                new AuditController(service).getLogs(null);

        verify(service).findRecent(AuditController.DEFAULT_LIMIT);
        verify(service, never()).findAll();
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("显式 limit 原样透传")
    void explicitLimitPassesThrough() {
        AuditService service = serviceReturning();

        new AuditController(service).getLogs(50);

        verify(service).findRecent(50);
        verify(service, never()).findAll();
    }

    @Test
    @DisplayName("超大 limit 收敛到上限，不把整表读进堆")
    void hugeLimitIsClamped() {
        AuditService service = serviceReturning();

        new AuditController(service).getLogs(9_999_999);

        verify(service).findRecent(AuditController.MAX_LIMIT);
        verify(service, never()).findAll();
    }

    @Test
    @DisplayName("limit=0 与负数收敛到 1（不再表示「全部」）")
    void nonPositiveLimitBecomesOne() {
        AuditService zero = serviceReturning();
        new AuditController(zero).getLogs(0);
        verify(zero).findRecent(1);
        verify(zero, never()).findAll();

        AuditService negative = serviceReturning();
        new AuditController(negative).getLogs(-7);
        verify(negative).findRecent(1);
        verify(negative, never()).findAll();
    }

    @Test
    @DisplayName("返回体字段映射保持原样")
    void responseFieldsUnchanged() {
        AuditLog entry = log();

        Map<String, Object> body = new AuditController(serviceReturning(entry))
                .getLogs(1).getBody().get(0);

        assertThat(body).containsKeys("timestamp", "userId", "action", "target",
                "detail", "ip", "prevHash", "entryHash");
        assertThat(body.get("timestamp")).isEqualTo("2026-10-29T03:00:00Z");
        assertThat(body.get("userId")).isEqualTo("admin");
        assertThat(body.get("action")).isEqualTo("POST");
        // detail/ip 为 null 时用空串兜底（前端不必再判空）
        assertThat(body.get("detail")).isEqualTo("");
        assertThat(body.get("ip")).isEqualTo("10.0.0.1");
    }
}
