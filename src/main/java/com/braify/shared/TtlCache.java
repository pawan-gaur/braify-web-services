package com.braify.shared;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Tiny in-memory TTL cache (no caching library is on the classpath).
 *
 * <ul>
 *   <li>Entries expire {@code ttl} after being loaded; expiry is checked on read.</li>
 *   <li>Loader results of {@code null} are not cached (negative lookups always hit the source).</li>
 *   <li>Race-safe invalidation: every invalidation bumps a generation counter, and a load that
 *       started before an invalidation is returned to its caller but NOT stored — so a reader
 *       that fetched the old value just before a write can never re-populate the cache with it
 *       after the write's eviction.</li>
 *   <li>Bounded: when {@code maxSize} is exceeded, expired entries are purged; if still too
 *       large the whole cache is cleared (simple and safe).</li>
 * </ul>
 * Single-JVM only — evictions are not propagated to other instances.
 */
public final class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtNanos) {}

    private final Map<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final long ttlNanos;
    private final int maxSize;

    public TtlCache(Duration ttl, int maxSize) {
        this.ttlNanos = ttl.toNanos();
        this.maxSize = maxSize;
    }

    /** Returns the cached value, or loads it via {@code loader} (which may return null). */
    public V get(K key, Function<K, V> loader) {
        Entry<V> e = map.get(key);
        long now = System.nanoTime();
        if (e != null) {
            if (now - e.expiresAtNanos() < 0) return e.value();
            map.remove(key, e);
        }
        long gen = generation.get();
        V value = loader.apply(key);
        if (value != null && generation.get() == gen) {
            if (map.size() >= maxSize) purge();
            map.put(key, new Entry<>(value, now + ttlNanos));
            // An invalidation may have raced between the check above and the put.
            if (generation.get() != gen) map.remove(key);
        }
        return value;
    }

    public void invalidate(K key) {
        generation.incrementAndGet();
        map.remove(key);
    }

    /** Removes every entry whose key/value matches. */
    public void invalidateIf(BiPredicate<K, V> predicate) {
        generation.incrementAndGet();
        map.entrySet().removeIf(en -> predicate.test(en.getKey(), en.getValue().value()));
    }

    public void clear() {
        generation.incrementAndGet();
        map.clear();
    }

    private void purge() {
        long now = System.nanoTime();
        map.entrySet().removeIf(en -> now - en.getValue().expiresAtNanos() >= 0);
        if (map.size() >= maxSize) map.clear();
    }
}
