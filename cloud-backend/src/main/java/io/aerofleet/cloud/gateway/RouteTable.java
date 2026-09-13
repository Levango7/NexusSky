package io.aerofleet.cloud.gateway;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sysid -> route table with aging (D3). Extracted from UdpGateway so the
 * aging policy is unit-testable without binding a UDP socket.
 *
 * A route whose drone has been silent for {@link #STALE_MS} is dropped: a
 * replaced/rebound drone otherwise leaves a stale address that silently
 * eats every command until the new drone happens to heartbeat first.
 */
final class RouteTable {

    /** Routes without inbound traffic for this long are pruned. */
    static final long STALE_MS = 5 * 60_000L;

    private final ConcurrentHashMap<Integer, SocketAddress> routes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> lastSeen = new ConcurrentHashMap<>();

    /** Learn/refresh a route from an inbound frame. */
    void learn(int sysid, SocketAddress addr) {
        routes.put(sysid, addr);
        lastSeen.put(sysid, System.currentTimeMillis());
    }

    SocketAddress get(int sysid) {
        return routes.get(sysid);
    }

    boolean isEmpty() {
        return routes.isEmpty();
    }

    int size() {
        return routes.size();
    }

    /** Snapshot of live routes (heartbeat fan-out). */
    Iterable<SocketAddress> addresses() {
        return routes.values();
    }

    /** Drop routes silent longer than STALE_MS. Returns pruned sysids. */
    java.util.List<Integer> prune() {
        long now = System.currentTimeMillis();
        java.util.List<Integer> pruned = new java.util.ArrayList<>();
        routes.entrySet().removeIf(e -> {
            Long seen = lastSeen.get(e.getKey());
            if (seen != null && now - seen > STALE_MS) {
                lastSeen.remove(e.getKey());
                pruned.add(e.getKey());
                return true;
            }
            return false;
        });
        return pruned;
    }

    /** Test hook: age one route artificially. */
    void ageForTest(int sysid, long ageMs) {
        lastSeen.put(sysid, System.currentTimeMillis() - ageMs);
    }
}
