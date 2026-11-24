package io.aerofleet.cloud.mission.common;

import io.aerofleet.cloud.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionPlanStoreTest {

    private static final MissionPlanStore.StoredMissionItem ITEM =
            new MissionPlanStore.StoredMissionItem(0, 16, 22.59, 113.93, 30, 0, 3);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void memoryModeDoesNotTouchRepository() {
        MissionPlanRepository repository = mock(MissionPlanRepository.class);
        MissionPlanStore store = new MissionPlanStore(repository);

        store.save(1, 7, List.of(ITEM), "upload");

        assertThat(store.findVisible(1).items()).containsExactly(ITEM);
        verify(repository, never()).save(any());
    }

    @Test
    void persistedSnapshotHydratesAfterRestart() {
        MissionPlanRepository repository = mock(MissionPlanRepository.class);
        MissionPlanStore store = new MissionPlanStore(repository);
        ReflectionTestUtils.setField(store, "persist", true);
        ArgumentCaptor<MissionPlanEntity> captor = ArgumentCaptor.forClass(MissionPlanEntity.class);

        store.save(1, 7, List.of(ITEM), "upload");
        verify(repository).save(captor.capture());
        MissionPlanEntity saved = captor.getValue();
        assertThat(saved.getItemCount()).isEqualTo(1);
        assertThat(saved.getTenantId()).isEqualTo(7);
        assertThat(saved.getItemsJson()).contains("22.59");

        when(repository.findAll()).thenReturn(List.of(saved));
        MissionPlanStore restarted = new MissionPlanStore(repository);
        ReflectionTestUtils.setField(restarted, "persist", true);
        restarted.restoreFromRepository();

        assertThat(restarted.findVisible(1).items()).containsExactly(ITEM);
        assertThat(restarted.findVisible(1).source()).isEqualTo("upload");
    }

    @Test
    void corruptSnapshotDoesNotBlockOtherRows() {
        MissionPlanRepository repository = mock(MissionPlanRepository.class);
        MissionPlanEntity corrupt = new MissionPlanEntity();
        corrupt.setSysid(1);
        corrupt.setItemsJson("not-json");
        corrupt.setUpdatedAt(Instant.now());
        when(repository.findAll()).thenReturn(List.of(corrupt));
        MissionPlanStore store = new MissionPlanStore(repository);
        ReflectionTestUtils.setField(store, "persist", true);

        store.restoreFromRepository();

        assertThat(store.findVisible(1)).isNull();
    }

    @Test
    void tenantCannotReadAnotherTenantsSnapshot() {
        MissionPlanStore store = new MissionPlanStore();
        store.save(1, 7, List.of(ITEM), "upload");

        TenantContext.setTenantId(8);
        assertThat(store.findVisible(1)).isNull();
        TenantContext.setTenantId(TenantContext.NO_ACCESS);
        assertThat(store.findVisible(1)).isNull();
        TenantContext.setTenantId(7);
        assertThat(store.findVisible(1)).isNotNull();
    }

    @Test
    void repositoryFailureKeepsMemoryUsable() {
        MissionPlanRepository repository = mock(MissionPlanRepository.class);
        when(repository.save(any())).thenThrow(new RuntimeException("db unavailable"));
        when(repository.findById(any())).thenThrow(new RuntimeException("db unavailable"));
        MissionPlanStore store = new MissionPlanStore(repository);
        ReflectionTestUtils.setField(store, "persist", true);

        store.save(1, 7, List.of(ITEM), "upload");

        assertThat(store.findVisible(1).items()).containsExactly(ITEM);
    }

    @Test
    void tenantChangeAndDeleteUpdatePersistedSnapshot() {
        MissionPlanRepository repository = mock(MissionPlanRepository.class);
        MissionPlanEntity entity = new MissionPlanEntity();
        entity.setSysid(1);
        entity.setTenantId(7);
        entity.setItemsJson("[]");
        entity.setUpdatedAt(Instant.now());
        when(repository.findById(1)).thenReturn(Optional.of(entity));
        MissionPlanStore store = new MissionPlanStore(repository);
        ReflectionTestUtils.setField(store, "persist", true);

        store.assignTenant(1, 8);
        assertThat(entity.getTenantId()).isEqualTo(8);
        verify(repository).save(entity);

        store.delete(1);
        verify(repository).deleteById(1);
    }
}
