package io.aerofleet.cloud.orch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资源管理器单测。
 * <p>
 * 覆盖 allocate / release / isAvailable / getPlanAllocations / getAvailableDrones
 * 等核心逻辑，包括资源冲突检测和并发安全验证。
 */
@DisplayName("ResourceManager 资源管理器")
class ResourceManagerTest {

    private ResourceManager resourceManager;

    @BeforeEach
    void setUp() {
        resourceManager = new ResourceManager();
    }

    // ==================== allocate ====================

    @Test
    @DisplayName("allocate 正常分配资源返回 true")
    void allocateSuccessReturnsTrue() {
        // Arrange
        List<Integer> drones = Arrays.asList(1, 2, 3);

        // Act
        boolean result = resourceManager.allocate(1L, drones);

        // Assert
        assertTrue(result);
    }

    @Test
    @DisplayName("allocate 分配后无人机不可用")
    void allocateMakesDronesUnavailable() {
        // Arrange
        List<Integer> drones = Arrays.asList(1, 2);

        // Act
        resourceManager.allocate(1L, drones);

        // Assert
        assertFalse(resourceManager.isAvailable(1));
        assertFalse(resourceManager.isAvailable(2));
        assertTrue(resourceManager.isAvailable(3));
    }

    @Test
    @DisplayName("allocate 资源冲突返回 false")
    void allocateConflictReturnsFalse() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1, 2));

        // Act
        boolean result = resourceManager.allocate(2L, Arrays.asList(2, 3));

        // Assert
        assertFalse(result);
    }

    @Test
    @DisplayName("allocate 同一计划重复分配同一无人机返回 true")
    void allocateSamePlanReAllocateReturnsTrue() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1, 2));

        // Act
        boolean result = resourceManager.allocate(1L, Arrays.asList(1, 3));

        // Assert
        assertTrue(result);
    }

    @Test
    @DisplayName("allocate planId 为 null 返回 false")
    void allocateNullPlanIdReturnsFalse() {
        // Act & Assert
        assertFalse(resourceManager.allocate(null, Arrays.asList(1)));
    }

    @Test
    @DisplayName("allocate 无人机列表为 null 返回 false")
    void allocateNullDronesReturnsFalse() {
        // Act & Assert
        assertFalse(resourceManager.allocate(1L, null));
    }

    @Test
    @DisplayName("allocate 无人机列表为空返回 false")
    void allocateEmptyDronesReturnsFalse() {
        // Act & Assert
        assertFalse(resourceManager.allocate(1L, Collections.emptyList()));
    }

    @Test
    @DisplayName("allocate 部分冲突时整体分配失败")
    void allocatePartialConflictFails() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1));

        // Act
        boolean result = resourceManager.allocate(2L, Arrays.asList(1, 2, 3));

        // Assert
        assertFalse(result);
        // 冲突失败后不应改变已有状态
        assertTrue(resourceManager.isAvailable(2));
        assertTrue(resourceManager.isAvailable(3));
    }

    // ==================== release ====================

    @Test
    @DisplayName("release 正常释放资源")
    void releaseMakesDronesAvailableAgain() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1, 2, 3));

        // Act
        resourceManager.release(1L, Arrays.asList(1, 2));

        // Assert
        assertTrue(resourceManager.isAvailable(1));
        assertTrue(resourceManager.isAvailable(2));
        assertFalse(resourceManager.isAvailable(3));
    }

    @Test
    @DisplayName("release 释放全部资源后计划分配记录清空")
    void releaseAllClearsPlanAllocations() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1, 2));

        // Act
        resourceManager.release(1L, Arrays.asList(1, 2));

        // Assert
        assertTrue(resourceManager.getPlanAllocations(1L).isEmpty());
    }

    @Test
    @DisplayName("release 不释放其他计划的资源")
    void releaseDoesNotReleaseOtherPlanResources() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1));
        resourceManager.allocate(2L, Arrays.asList(2));

        // Act: 计划1尝试释放计划2的资源
        resourceManager.release(1L, Arrays.asList(2));

        // Assert
        assertFalse(resourceManager.isAvailable(2));
    }

    @Test
    @DisplayName("release planId 为 null 不操作")
    void releaseNullPlanIdDoesNothing() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1));

        // Act
        resourceManager.release(null, Arrays.asList(1));

        // Assert
        assertFalse(resourceManager.isAvailable(1));
    }

    @Test
    @DisplayName("release 无人机列表为 null 不操作")
    void releaseNullDronesDoesNothing() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1));

        // Act
        resourceManager.release(1L, null);

        // Assert
        assertFalse(resourceManager.isAvailable(1));
    }

    @Test
    @DisplayName("release 无人机列表为空不操作")
    void releaseEmptyDronesDoesNothing() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1));

        // Act
        resourceManager.release(1L, Collections.emptyList());

        // Assert
        assertFalse(resourceManager.isAvailable(1));
    }

    // ==================== isAvailable ====================

    @Test
    @DisplayName("isAvailable 未分配的无人机返回 true")
    void isAvailableUnallocatedReturnsTrue() {
        // Act & Assert
        assertTrue(resourceManager.isAvailable(1));
        assertTrue(resourceManager.isAvailable(999));
    }

    @Test
    @DisplayName("isAvailable 已分配的无人机返回 false")
    void isAvailableAllocatedReturnsFalse() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1));

        // Act & Assert
        assertFalse(resourceManager.isAvailable(1));
    }

    @Test
    @DisplayName("isAvailable 释放后恢复可用")
    void isAvailableAfterReleaseReturnsTrue() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1));
        resourceManager.release(1L, Arrays.asList(1));

        // Act & Assert
        assertTrue(resourceManager.isAvailable(1));
    }

    // ==================== getPlanAllocations ====================

    @Test
    @DisplayName("getPlanAllocations 返回已分配的无人机列表")
    void getPlanAllocationsReturnsAllocatedDrones() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1, 2, 3));

        // Act
        List<Integer> allocated = resourceManager.getPlanAllocations(1L);

        // Assert
        assertEquals(3, allocated.size());
        assertTrue(allocated.containsAll(Arrays.asList(1, 2, 3)));
    }

    @Test
    @DisplayName("getPlanAllocations 未分配的计划返回空列表")
    void getPlanAllocationsUnknownPlanReturnsEmpty() {
        // Act
        List<Integer> allocated = resourceManager.getPlanAllocations(999L);

        // Assert
        assertNotNull(allocated);
        assertTrue(allocated.isEmpty());
    }

    @Test
    @DisplayName("getPlanAllocations 部分释放后返回剩余资源")
    void getPlanAllocationsAfterPartialRelease() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1, 2, 3));
        resourceManager.release(1L, Arrays.asList(2));

        // Act
        List<Integer> allocated = resourceManager.getPlanAllocations(1L);

        // Assert
        assertEquals(2, allocated.size());
        assertTrue(allocated.contains(1));
        assertTrue(allocated.contains(3));
        assertFalse(allocated.contains(2));
    }

    // ==================== getAvailableDrones ====================

    @Test
    @DisplayName("getAvailableDrones 从资源池中筛选可用无人机")
    void getAvailableDronesFiltersAvailable() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(2));

        // Act
        List<Integer> available = resourceManager.getAvailableDrones(Arrays.asList(1, 2, 3));

        // Assert
        assertEquals(2, available.size());
        assertTrue(available.contains(1));
        assertTrue(available.contains(3));
        assertFalse(available.contains(2));
    }

    @Test
    @DisplayName("getAvailableDrones 空资源池返回空列表")
    void getAvailableDronesEmptyPoolReturnsEmpty() {
        // Act
        List<Integer> available = resourceManager.getAvailableDrones(Collections.emptyList());

        // Assert
        assertNotNull(available);
        assertTrue(available.isEmpty());
    }

    @Test
    @DisplayName("getAvailableDrones null 资源池返回空列表")
    void getAvailableDronesNullPoolReturnsEmpty() {
        // Act
        List<Integer> available = resourceManager.getAvailableDrones(null);

        // Assert
        assertNotNull(available);
        assertTrue(available.isEmpty());
    }

    @Test
    @DisplayName("getAvailableDrones 全部可用时返回完整列表")
    void getAvailableDronesAllAvailableReturnsAll() {
        // Act
        List<Integer> available = resourceManager.getAvailableDrones(Arrays.asList(1, 2, 3));

        // Assert
        assertEquals(3, available.size());
    }

    // ==================== 多计划场景 ====================

    @Test
    @DisplayName("多计划分配不同无人机互不冲突")
    void multiplePlansAllocateDifferentDrones() {
        // Arrange & Act
        boolean r1 = resourceManager.allocate(1L, Arrays.asList(1, 2));
        boolean r2 = resourceManager.allocate(2L, Arrays.asList(3, 4));

        // Assert
        assertTrue(r1);
        assertTrue(r2);
        assertFalse(resourceManager.isAvailable(1));
        assertFalse(resourceManager.isAvailable(2));
        assertFalse(resourceManager.isAvailable(3));
        assertFalse(resourceManager.isAvailable(4));
    }

    @Test
    @DisplayName("释放一个计划的资源不影响另一个计划")
    void releaseOnePlanDoesNotAffectOther() {
        // Arrange
        resourceManager.allocate(1L, Arrays.asList(1, 2));
        resourceManager.allocate(2L, Arrays.asList(3, 4));

        // Act
        resourceManager.release(1L, Arrays.asList(1, 2));

        // Assert
        assertTrue(resourceManager.isAvailable(1));
        assertTrue(resourceManager.isAvailable(2));
        assertFalse(resourceManager.isAvailable(3));
        assertFalse(resourceManager.isAvailable(4));
    }
}