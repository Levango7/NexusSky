package io.aerofleet.cloud.gateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Fixed-capacity in-memory ring buffer for the most recent N points.
 * Not thread-safe by itself: the owning DroneSnapshot serializes access.
 */
public class BoundedHistory<T> {

    private final int capacity;
    private final Deque<T> items;

    public BoundedHistory(int capacity) {
        this.capacity = capacity;
        this.items = new ArrayDeque<>(Math.min(capacity, 64));
    }

    /** Append a point; silently drops the oldest when full. */
    public synchronized void add(T item) {
        if (items.size() == capacity) {
            items.pollFirst();
        }
        items.addLast(item);
    }

    /** Snapshot copy, oldest first. */
    public synchronized List<T> toList() {
        return new ArrayList<>(items);
    }

    public synchronized int size() {
        return items.size();
    }
}
