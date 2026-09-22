package io.aerofleet.cloud.drone;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 无人机远程锁定/解锁 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /api/drone-lock/{sysid}/lock} — 锁定无人机</li>
 *   <li>{@code POST /api/drone-lock/{sysid}/unlock} — 解锁无人机</li>
 *   <li>{@code GET /api/drone-lock/{sysid}} — 获取锁定状态</li>
 *   <li>{@code GET /api/drone-lock/locked} — 获取所有已锁定无人机</li>
 *   <li>{@code GET /api/drone-lock/all} — 获取所有无人机锁定状态</li>
 *   <li>{@code DELETE /api/drone-lock/{sysid}} — 清除锁定状态记录</li>
 * </ul>
 * <p>
 * 认证由 {@link io.aerofleet.cloud.security.SecurityConfig} 统一处理。
 */
@Tag(name = "Drone Lock", description = "无人机远程锁定/解锁")
@RestController
@RequestMapping("/api/drone-lock")
public class DroneLockController {


    private final DroneLockService lockService;
    private final DeviceRegistry registry;

    public DroneLockController(DroneLockService lockService, DeviceRegistry registry) {
        this.lockService = lockService;
        this.registry = registry;
    }

    /**
     * 锁定无人机。
     * <p>
     * Body: {@code {"reason": "...", "lockedBy": "...", "action": "DISARM|FORCE_LAND|RETURN_TO_LAUNCH"}}
     */
    @Operation(summary = "锁定无人机", description = "远程锁定指定无人机，禁止起飞/强制降落/返航锁定")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "锁定成功或已锁定（幂等）"),
            @ApiResponse(responseCode = "404", description = "无人机未注册"),
            @ApiResponse(responseCode = "400", description = "请求参数非法")
    })
    @PostMapping("/{sysid}/lock")
    public LockState lock(@PathVariable("sysid") int sysid, @RequestBody LockRequest request) {
        requireRegistered(sysid);
        if (request.getLockedBy() == null || request.getLockedBy().isBlank()) {
            throw new BadRequestException("lockedBy is required");
        }
        LockState.Action action = parseAction(request.getAction());
        return lockService.lock(sysid, request.getReason(), request.getLockedBy(), action);
    }

    /**
     * 解锁无人机。
     * <p>
     * Body: {@code {"unlockedBy": "..."}}
     */
    @Operation(summary = "解锁无人机", description = "远程解锁指定无人机，恢复起飞能力")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "解锁成功"),
            @ApiResponse(responseCode = "404", description = "无人机未注册"),
            @ApiResponse(responseCode = "400", description = "无人机未锁定或参数非法")
    })
    @PostMapping("/{sysid}/unlock")
    public LockState unlock(@PathVariable("sysid") int sysid, @RequestBody LockRequest request) {
        requireRegistered(sysid);
        if (request.getUnlockedBy() == null || request.getUnlockedBy().isBlank()) {
            throw new BadRequestException("unlockedBy is required");
        }
        try {
            return lockService.unlock(sysid, request.getUnlockedBy());
        } catch (IllegalStateException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    /** 获取锁定状态。 */
    @Operation(summary = "获取锁定状态", description = "查询指定无人机当前锁定状态")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "锁定状态"),
            @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @GetMapping("/{sysid}")
    public LockState getLockStatus(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        return lockService.getLockState(sysid);
    }

    /** 获取所有已锁定无人机。 */
    @Operation(summary = "获取所有已锁定无人机", description = "返回当前处于锁定状态的无人机列表")
    @ApiResponse(responseCode = "200", description = "已锁定无人机列表")
    @GetMapping("/locked")
    public List<LockState> getLockedDrones() {
        return lockService.getLockedDrones();
    }

    /** 获取所有无人机锁定状态。 */
    @Operation(summary = "获取所有无人机锁定状态", description = "返回所有有锁定记录的无人机状态")
    @ApiResponse(responseCode = "200", description = "所有锁定状态")
    @GetMapping("/all")
    public List<LockState> getAllLockStates() {
        return lockService.getAllLockStates();
    }

    /** 清除锁定状态记录（管理端点）。 */
    @Operation(summary = "清除锁定状态记录", description = "直接删除锁定记录，不生成解锁事件")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "清除成功"),
            @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @DeleteMapping("/{sysid}")
    public Map<String, Object> clearLockState(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        lockService.clearLockState(sysid);
        return Map.of("status", "ok", "sysid", sysid);
    }

    private void requireRegistered(int sysid) {
        if (registry.get(sysid) == null) {
            throw new NotFoundException("unknown drone sysid " + sysid);
        }
    }

    private LockState.Action parseAction(String action) {
        if (action == null || action.isBlank()) {
            return LockState.Action.DISARM; // 默认动作
        }
        try {
            return LockState.Action.valueOf(action.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid action: " + action
                    + " (expected DISARM, FORCE_LAND, or RETURN_TO_LAUNCH)");
        }
    }
}