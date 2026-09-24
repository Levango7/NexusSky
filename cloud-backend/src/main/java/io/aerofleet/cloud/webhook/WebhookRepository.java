package io.aerofleet.cloud.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@link WebhookEntity} 的 JPA Repository。
 * <p>
 * 提供按租户查找 webhook 和按事件类型查找已启用 webhook 的查询方法。
 * 事件推送时通过 {@link #findByEnabledTrueAndEventsContaining(String)} 查找
 * 所有订阅了该事件且处于启用状态的 webhook。
 */
public interface WebhookRepository extends JpaRepository<WebhookEntity, Long> {

    /**
     * 查找指定租户的所有 webhook。
     *
     * @param tenantId 租户 ID
     * @return webhook 实体列表
     */
    List<WebhookEntity> findByTenantId(Integer tenantId);

    /**
     * 查找所有已启用且订阅了指定事件的 webhook。
     * <p>
     * events 字段为逗号分隔的事件类型列表，
     * 使用 LIKE %event% 匹配，因此 event 参数应精确匹配单个事件类型。
     *
     * @param event 事件类型
     * @return 已启用且订阅了该事件的 webhook 列表
     */
    List<WebhookEntity> findByEnabledTrueAndEventsContaining(String event);
}