package io.aerofleet.cloud.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TenantController 租户管理 CRUD 单测（直接实例化，无 Spring 上下文）。
 * <p>
 * 参考 AuthControllerTest 风格：mock Repository，直接实例化 Controller，
 * 使用 MockHttpServletRequest 提供 request 信息。
 */
@DisplayName("TenantController 租户管理 CRUD")
class TenantControllerTest {

    private TenantRepository tenantRepository;
    private UserRepository userRepository;
    private TenantController controller;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        userRepository = mock(UserRepository.class);
        controller = new TenantController(tenantRepository, userRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ===== listTenants =====

    @Test
    @DisplayName("listTenants 返回所有租户列表")
    void listTenants_returnsAllTenants() {
        TenantEntity t1 = new TenantEntity(1, "租户A", "TENANT_A", true, Instant.now());
        TenantEntity t2 = new TenantEntity(2, "租户B", "TENANT_B", false, Instant.now());
        when(tenantRepository.findAll()).thenReturn(List.of(t1, t2));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listTenants();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody().get(0).get("name")).isEqualTo("租户A");
        assertThat(resp.getBody().get(1).get("name")).isEqualTo("租户B");
    }

    @Test
    @DisplayName("listTenants 无租户时返回空列表")
    void listTenants_emptyList() {
        when(tenantRepository.findAll()).thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> resp = controller.listTenants();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEmpty();
    }

    // ===== getTenant =====

    @Test
    @DisplayName("getTenant 存在的租户返回 200 + 详情")
    void getTenant_found_returns200() {
        TenantEntity tenant = new TenantEntity(1, "租户A", "TENANT_A", true, Instant.now());
        when(tenantRepository.findById(1)).thenReturn(Optional.of(tenant));

        ResponseEntity<?> resp = controller.getTenant(1);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("name")).isEqualTo("租户A");
        assertThat(body.get("code")).isEqualTo("TENANT_A");
        assertThat(body.get("enabled")).isEqualTo(true);
    }

    @Test
    @DisplayName("getTenant 不存在的租户返回 404")
    void getTenant_notFound_returns404() {
        when(tenantRepository.findById(999)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getTenant(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("tenant not found");
    }

    // ===== createTenant =====

    @Test
    @DisplayName("createTenant 正常创建返回 201")
    void createTenant_success_returns201() {
        TenantRequest request = new TenantRequest();
        request.setName("新租户");
        request.setCode("NEW_TENANT");
        request.setEnabled(true);

        when(tenantRepository.findByCode("NEW_TENANT")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(invocation -> {
            TenantEntity entity = invocation.getArgument(0);
            entity.setId(10);
            return entity;
        });

        ResponseEntity<?> resp = controller.createTenant(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("name")).isEqualTo("新租户");
        assertThat(body.get("code")).isEqualTo("NEW_TENANT");
        assertThat(body.get("id")).isEqualTo(10);
    }

    @Test
    @DisplayName("createTenant code 重复返回 400")
    void createTenant_duplicateCode_returns400() {
        TenantRequest request = new TenantRequest();
        request.setName("重复租户");
        request.setCode("EXISTING_CODE");
        request.setEnabled(true);

        TenantEntity existing = new TenantEntity(1, "已有租户", "EXISTING_CODE", true, Instant.now());
        when(tenantRepository.findByCode("EXISTING_CODE")).thenReturn(Optional.of(existing));

        ResponseEntity<?> resp = controller.createTenant(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("tenant code already exists");
    }

    @Test
    @DisplayName("createTenant enabled 为 null 时默认为 true")
    void createTenant_enabledNull_defaultsTrue() {
        TenantRequest request = new TenantRequest();
        request.setName("默认启用租户");
        request.setCode("DEFAULT_ENABLED");
        request.setEnabled(null);

        when(tenantRepository.findByCode("DEFAULT_ENABLED")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(invocation -> {
            TenantEntity entity = invocation.getArgument(0);
            entity.setId(11);
            return entity;
        });

        ResponseEntity<?> resp = controller.createTenant(request);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("enabled")).isEqualTo(true);
    }

    @Test
    @DisplayName("createTenant：enabled 落库 + createdAt 落库（mint 字段缺失让租户半成品入库）")
    void createTenant_persistsEnabledAndCreatedAt() {
        TenantRequest request = new TenantRequest();
        request.setName("新租户");
        request.setCode("PERSIST_TENANT");
        request.setEnabled(null); // 缺省：三态默认必须真落库，不只是响应体里好看

        when(tenantRepository.findByCode("PERSIST_TENANT")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        controller.createTenant(request);

        org.mockito.ArgumentCaptor<TenantEntity> captor =
                org.mockito.ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).save(captor.capture());
        // setEnabled 删除：实体 enabled 停在 null/默认——响应体还能显示 true，库里已是 NULL
        assertThat(captor.getValue().isEnabled()).isTrue();
        // setCreatedAt 删除：租户的"寿命"从此处断档（审计/按创建时间清理全部失真）
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("createTenant：显式 enabled=false 落库（字段默认 true 会把漏 setEnabled 的变异体藏起来）")
    void createTenant_persistsExplicitFalse() {
        TenantRequest request = new TenantRequest();
        request.setName("停用租户");
        request.setCode("DISABLED_TENANT");
        request.setEnabled(false); // 显式停用：与字段默认值相反，漏 setEnabled 必现形

        when(tenantRepository.findByCode("DISABLED_TENANT")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        controller.createTenant(request);

        org.mockito.ArgumentCaptor<TenantEntity> captor =
                org.mockito.ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).save(captor.capture());
        // PIT 第五轮：setEnabled 删除变异体在 enabled=null（默认即 true）下与原实现
        // 全同，唯独 enabled=false 请求会把"停用租户"创建成"启用租户"——权限闸门方向
        // 的数据错误，只有显式 false 用例能抓住。
        assertThat(captor.getValue().isEnabled())
                .as("显式停用的租户不许被落成启用")
                .isFalse();
    }

    // ===== updateTenant =====

    @Test
    @DisplayName("updateTenant 正常更新返回 200")
    void updateTenant_success_returns200() {
        TenantEntity existing = new TenantEntity(1, "旧名称", "OLD_CODE", true, Instant.now());
        TenantRequest request = new TenantRequest();
        request.setName("新名称");
        request.setCode("NEW_CODE");
        request.setEnabled(false);

        when(tenantRepository.findById(1)).thenReturn(Optional.of(existing));
        when(tenantRepository.findByCode("NEW_CODE")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> resp = controller.updateTenant(1, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("name")).isEqualTo("新名称");
        assertThat(body.get("code")).isEqualTo("NEW_CODE");
        assertThat(body.get("enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("updateTenant 不存在的租户返回 404")
    void updateTenant_notFound_returns404() {
        TenantRequest request = new TenantRequest();
        request.setName("新名称");
        request.setCode("NEW_CODE");

        when(tenantRepository.findById(999)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.updateTenant(999, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("tenant not found");
    }

    @Test
    @DisplayName("updateTenant code 与其他租户冲突抛 IllegalStateException")
    void updateTenant_codeConflict_throwsIllegalState() {
        TenantEntity existing = new TenantEntity(1, "租户A", "CODE_A", true, Instant.now());
        TenantEntity other = new TenantEntity(2, "租户B", "CODE_B", true, Instant.now());
        TenantRequest request = new TenantRequest();
        request.setName("租户A更新");
        request.setCode("CODE_B");

        when(tenantRepository.findById(1)).thenReturn(Optional.of(existing));
        when(tenantRepository.findByCode("CODE_B")).thenReturn(Optional.of(other));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.updateTenant(1, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant code already exists");
    }

    @Test
    @DisplayName("updateTenant code 与自己相同不冲突，正常更新")
    void updateTenant_sameCodeNoConflict_returns200() {
        TenantEntity existing = new TenantEntity(1, "租户A", "CODE_A", true, Instant.now());
        TenantRequest request = new TenantRequest();
        request.setName("租户A更新");
        request.setCode("CODE_A");

        when(tenantRepository.findById(1)).thenReturn(Optional.of(existing));
        when(tenantRepository.findByCode("CODE_A")).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> resp = controller.updateTenant(1, request);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    // ===== deleteTenant =====

    @Test
    @DisplayName("deleteTenant 正常删除返回 204")
    void deleteTenant_success_returns204() {
        TenantEntity tenant = new TenantEntity(1, "租户A", "CODE_A", true, Instant.now());
        when(tenantRepository.findById(1)).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantId(1)).thenReturn(List.of());

        ResponseEntity<?> resp = controller.deleteTenant(1, new MockHttpServletRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(tenantRepository).delete(tenant);
    }

    @Test
    @DisplayName("deleteTenant 不存在的租户返回 404")
    void deleteTenant_notFound_returns404() {
        when(tenantRepository.findById(999)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.deleteTenant(999, new MockHttpServletRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("deleteTenant 有用户时拒绝删除返回 400")
    void deleteTenant_hasUsers_returns400() {
        TenantEntity tenant = new TenantEntity(1, "租户A", "CODE_A", true, Instant.now());
        UserEntity user = new UserEntity(1, "user1", "hash", "ADMIN", 1, true, Instant.now());
        when(tenantRepository.findById(1)).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantId(1)).thenReturn(List.of(user));

        ResponseEntity<?> resp = controller.deleteTenant(1, new MockHttpServletRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("cannot delete tenant with existing users");
    }

    @Test
    @DisplayName("deleteTenant 删除自己所属租户拒绝返回 400")
    void deleteTenant_ownTenant_returns400() {
        TenantEntity tenant = new TenantEntity(1, "租户A", "CODE_A", true, Instant.now());
        when(tenantRepository.findById(1)).thenReturn(Optional.of(tenant));
        TenantContext.setTenantId(1);

        ResponseEntity<?> resp = controller.deleteTenant(1, new MockHttpServletRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("error")).isEqualTo("cannot delete your own tenant");
    }

    @Test
    @DisplayName("deleteTenant 删除其他租户（非自己所属）正常返回 204")
    void deleteTenant_otherTenant_returns204() {
        TenantEntity tenant = new TenantEntity(2, "租户B", "CODE_B", true, Instant.now());
        when(tenantRepository.findById(2)).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantId(2)).thenReturn(List.of());
        TenantContext.setTenantId(1);

        ResponseEntity<?> resp = controller.deleteTenant(2, new MockHttpServletRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }
}