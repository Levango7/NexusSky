package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.aerofleet.cloud.api.exception.ApiExceptionHandler.BadRequestException;

/**
 * V43 三张场景表的读写服务：阳台落点 / 农资流水 / 应急节点角色。
 *
 * <p>直接用 JdbcTemplate，不新增 JPA 实体：这三张表当前只是数据模型就位
 * （见 docs/capacity-planning.md §7），没有领域对象与复杂查询，实体层会放大改动面。
 * 归属校验按仓库既有口径放服务层，不在库里设外键/级联。
 */
@Service
public class ScenarioRegistryService {

    private final JdbcTemplate jdbc;

    public ScenarioRegistryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 无请求上下文的后台调用按默认租户 1；与单租户部署口径一致。 */
    private static int tenant() {
        Integer t = TenantContext.getEffectiveTenantId();
        return t == null ? 1 : t;
    }

    private static Object require(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw new BadRequestException("field '" + key + "' is required");
        }
        return v;
    }

    private static Object opt(Map<String, Object> body, String key) {
        return body == null ? null : body.get(key);
    }

    private static Map<String, Object> created(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("created", true);
        return out;
    }

    // ---------- 阳台落点 ----------

    public Map<String, Object> createBalconyDropPoint(Map<String, Object> body) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into balcony_drop_point "
                        + "(point_id,tenant_id,building_id,floor,lat,lon,alt_m,max_payload_kg,"
                        + "allowed_from_ms,allowed_to_ms,status,created_at_ms) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant(),
                require(body, "buildingId").toString(),
                ((Number) require(body, "floor")).intValue(),
                ((Number) require(body, "lat")).doubleValue(),
                ((Number) require(body, "lon")).doubleValue(),
                ((Number) require(body, "altM")).doubleValue(),
                opt(body, "maxPayloadKg"),
                opt(body, "allowedFromMs"),
                opt(body, "allowedToMs"),
                String.valueOf(opt(body, "status") == null ? "ACTIVE" : opt(body, "status")),
                System.currentTimeMillis());
        return created(id);
    }

    public List<Map<String, Object>> balconyDropPoints(String buildingId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select * from balcony_drop_point where tenant_id=?");
        args.add(tenant());
        if (buildingId != null && !buildingId.isBlank()) {
            sql.append(" and building_id=?");
            args.add(buildingId);
        }
        sql.append(" order by created_at_ms desc limit 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------- 农资流水 ----------

    public Map<String, Object> createAgriSupply(Map<String, Object> body) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into agri_supply_ledger "
                        + "(entry_id,tenant_id,task_id,sysid,supply_type,action,quantity,unit,"
                        + "balance_after,operator,recorded_at_ms) values (?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant(), opt(body, "taskId"), opt(body, "sysid"),
                require(body, "supplyType").toString(),
                require(body, "action").toString(),
                ((Number) require(body, "quantity")).doubleValue(),
                require(body, "unit").toString(),
                opt(body, "balanceAfter"), opt(body, "operator"), System.currentTimeMillis());
        return created(id);
    }

    public List<Map<String, Object>> agriSupply(String taskId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select * from agri_supply_ledger where tenant_id=?");
        args.add(tenant());
        if (taskId != null && !taskId.isBlank()) {
            sql.append(" and task_id=?");
            args.add(taskId);
        }
        sql.append(" order by recorded_at_ms desc limit 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------- 应急节点角色 ----------

    public Map<String, Object> createEmergencyRole(Map<String, Object> body) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into emergency_node_role "
                        + "(role_id,tenant_id,plan_id,task_id,sysid,role,link_budget_ms,seq,"
                        + "status,created_at_ms) values (?,?,?,?,?,?,?,?,?,?)",
                id, tenant(), opt(body, "planId"), opt(body, "taskId"),
                ((Number) require(body, "sysid")).intValue(),
                require(body, "role").toString(),
                opt(body, "linkBudgetMs"), opt(body, "seq"),
                String.valueOf(opt(body, "status") == null ? "ACTIVE" : opt(body, "status")),
                System.currentTimeMillis());
        return created(id);
    }

    public List<Map<String, Object>> emergencyRoles(String planId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select * from emergency_node_role where tenant_id=?");
        args.add(tenant());
        if (planId != null && !planId.isBlank()) {
            sql.append(" and plan_id=?");
            args.add(planId);
        }
        sql.append(" order by created_at_ms desc limit 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }
}
