package io.github.isagroup.spaceclient;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Remembers the {@code eventId}s seen recently, so that a second copy of a SPACE event can be
 * dropped. Memory is bounded: an id is forgotten {@code window} after it was first seen, or
 * earlier when more than {@code maxEntries} ids are held (oldest first).
 *
 * <p>SPACE sends one copy of an event per connection and the copies of one event are emitted
 * together, so a short window is enough. Ids are compared for equality only; they do not order
 * events. Thread-safe.
 */
public class EventIdWindow {

    /** Default number of ids remembered. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** Default time an id is remembered. */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(10);

    private final int maxEntries;
    private final long windowMs;
    private final LongSupplier clock;
    /** id -> time first seen (ms); insertion order, so the oldest id comes first. */
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    public EventIdWindow() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_WINDOW);
    }

    public EventIdWindow(int maxEntries, Duration window) {
        this(maxEntries, window, System::currentTimeMillis);
    }

    /**
     * @param maxEntries at most this many ids are remembered (positive)
     * @param window     how long an id is remembered after it was first seen (positive)
     * @param clock      the current time in milliseconds
     */
    public EventIdWindow(int maxEntries, Duration window, LongSupplier clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive duration, got " + window);
        }
        this.maxEntries = maxEntries;
        this.windowMs = window.toMillis();
        this.clock = clock;
    }

    /**
     * Records {@code eventId}.
     *
     * @param eventId the {@code eventId} of a received event
     * @return {@code true} the first time it is seen within the window, {@code false} for a repeat
     *         (which should be dropped)
     */
    public synchronized boolean accept(String eventId) {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Long>> oldest = seen.entrySet().iterator();
        while (oldest.hasNext()) {
            if (now - oldest.next().getValue() <= windowMs) {
                break;
            }
            oldest.remove();
        }
        if (seen.containsKey(eventId)) {
            return false;
        }
        seen.put(eventId, now);
        while (seen.size() > maxEntries) {
            Iterator<String> first = seen.keySet().iterator();
            first.next();
            first.remove();
        }
        return true;
    }

    /** The number of ids currently remembered. */
    public synchronized int size() {
        return seen.size();
    }

    /** Forgets every id. */
    public synchronized void clear() {
        seen.clear();
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public Duration getWindow() {
        return Duration.ofMillis(windowMs);
    }
}
