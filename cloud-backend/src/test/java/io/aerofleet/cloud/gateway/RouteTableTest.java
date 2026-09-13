package io.aerofleet.cloud.gateway;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RouteTable aging tests (D3): pure logic, no socket, no Spring.
 */
class RouteTableTest {

    private static InetSocketAddress addr(int port) {
        return new InetSocketAddress("127.0.0.1", port);
    }

    @Test
    void staleRouteIsPruned() {
        RouteTable t = new RouteTable();
        t.learn(9, addr(14542));
        t.learn(1, addr(14540));
        t.ageForTest(9, 6 * 60_000L);   // 6 min: past the 5-min horizon
        List<Integer> pruned = t.prune();
        assertEquals(List.of(9), pruned, "only the stale sysid is pruned");
        assertNull(t.get(9));
        assertNotNull(t.get(1), "fresh route kept");
    }

    @Test
    void freshRoutesSurvive() {
        RouteTable t = new RouteTable();
        t.learn(7, addr(14541));
        t.ageForTest(7, 30_000);       // 30s: well within 5 min
        assertTrue(t.prune().isEmpty());
        assertNotNull(t.get(7));
        assertEquals(1, t.size());
    }

    @Test
    void rebindingDroneReplacesRoute() {
        RouteTable t = new RouteTable();
        t.learn(9, addr(14542));
        t.learn(9, addr(15100));       // new process, new port, heartbeats first
        assertEquals(15100, ((InetSocketAddress) t.get(9)).getPort());
        t.ageForTest(9, 0);
        assertTrue(t.prune().isEmpty(), "fresh replacement survives pruning");
    }

    @Test
    void pruneOnEmptyIsNoop() {
        RouteTable t = new RouteTable();
        assertTrue(t.prune().isEmpty());
        assertTrue(t.isEmpty());
    }

    @Test
    void boundaryExactlyAtHorizonIsKept() {
        // "silent > STALE_MS" semantics: exactly 5 min is NOT stale yet
        RouteTable t = new RouteTable();
        t.learn(3, addr(14540));
        t.ageForTest(3, RouteTable.STALE_MS);
        assertTrue(t.prune().isEmpty(), "exactly at horizon is kept");
        t.ageForTest(3, RouteTable.STALE_MS + 1);
        assertEquals(List.of(3), t.prune(), "1ms past horizon is pruned");
    }
}
