package io.aerofleet.cloud.api.pusher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.service.CellTowerTopologyService;
import io.aerofleet.cloud.api.service.MeshTopologyService;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 拓扑推送 debounce 缓存的有界性（真时钟，一次 1.1s 等待）。
 *
 * <p>判据：第一轮产生 60 个不同事件 key；等过 debounce 窗口后再推 1 个新事件，
 * 窗口内应只剩**本轮那 1 个** key。若 pusher 忘了 prune，旧 60 个 key 会一直留着
 * （实现前就是 61），本用例即红。key 空间是「类型 × 源 × 目标」= O(N²)，
 * 机队越大，这个缺口涨得越快。
 */
@DisplayName("拓扑推送 debounce 缓存有界（不随历史事件组合增长）")
class PusherDebounceCacheBoundedTest {

    private static final int DISTINCT_EVENTS = 60;
    private static final long WINDOW_WAIT_MS = 1100;

    @Test
    @DisplayName("CellTowerPusher：窗口外的 60 个旧 key 被裁掉，只剩本轮 1 个")
    void cellTowerCachePrunesExpiredKeys() throws Exception {
        TelemetryWebSocketHandler handler = mock(TelemetryWebSocketHandler.class);
        when(handler.connectionCount()).thenReturn(1);
        when(handler.tryBroadcastByDeviceOwner(any(), any())).thenReturn(true);
        CellTowerTopologyService topology = mock(CellTowerTopologyService.class);
        CellTowerPusher pusher = new CellTowerPusher(handler, topology, new ObjectMapper());

        when(topology.detectChanges()).thenReturn(cellTowerEvents(DISTINCT_EVENTS, 0));
        pusher.pushOnce();
        assertThat(pusher.debounceCacheSize()).isEqualTo(DISTINCT_EVENTS);

        Thread.sleep(WINDOW_WAIT_MS);

        when(topology.detectChanges()).thenReturn(cellTowerEvents(1, 9_000));
        pusher.pushOnce();

        assertThat(pusher.debounceCacheSize())
                .as("过窗后旧 key 应被裁剪，缓存只保留本轮新事件")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("MeshTopologyPusher：窗口外的 60 个旧 key 被裁掉，只剩本轮 1 个")
    void meshCachePrunesExpiredKeys() throws Exception {
        TelemetryWebSocketHandler handler = mock(TelemetryWebSocketHandler.class);
        when(handler.connectionCount()).thenReturn(1);
        when(handler.tryBroadcastByDeviceOwner(any(), any())).thenReturn(true);
        MeshTopologyService topology = mock(MeshTopologyService.class);
        MeshTopologyPusher pusher = new MeshTopologyPusher(handler, topology, new ObjectMapper());

        when(topology.detectChanges()).thenReturn(meshEvents(DISTINCT_EVENTS, 0));
        pusher.pushOnce();
        assertThat(pusher.debounceCacheSize()).isEqualTo(DISTINCT_EVENTS);

        Thread.sleep(WINDOW_WAIT_MS);

        when(topology.detectChanges()).thenReturn(meshEvents(1, 9_000));
        pusher.pushOnce();

        assertThat(pusher.debounceCacheSize())
                .as("过窗后旧 key 应被裁剪，缓存只保留本轮新事件")
                .isEqualTo(1);
    }

    private static List<CellTowerTopologyService.CellTowerEvent> cellTowerEvents(int count, int toBase) {
        List<CellTowerTopologyService.CellTowerEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new CellTowerTopologyService.CellTowerEvent(
                    CellTowerTopologyService.EventType.HANDOVER, 1, toBase + i, 0L));
        }
        return events;
    }

    private static List<MeshTopologyService.MeshTopologyEvent> meshEvents(int count, int toBase) {
        List<MeshTopologyService.MeshTopologyEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new MeshTopologyService.MeshTopologyEvent(
                    MeshTopologyService.EventType.ROUTE_CHANGED, 1, toBase + i, 0L));
        }
        return events;
    }
}
