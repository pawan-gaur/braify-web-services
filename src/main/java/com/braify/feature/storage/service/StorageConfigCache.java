package com.braify.feature.storage.service;

import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Short-lived (60 s) in-memory cache of the storage config {@link StorageService} resolves for a
 * file / an upload — the Organization load, the {@code StorageConfigResolver} decision (incl. the
 * platform-defaults read) and the two AES decrypts of the credentials. Without it every
 * pre-signed URL paid all of that.
 *
 * <p>Only the resolved config is cached, never an access decision: callers still check access
 * to the file per call. Entries are evicted when an org's cloud config is saved
 * ({@link #evictOrg}) or the Platform-Admin cloud default changes ({@link #evictAll}); in a
 * multi-instance deployment other instances converge within the TTL.
 *
 * <p>Deliberately dependency-free so the config services can call it without bean cycles.
 */
@Slf4j
@Component
public class StorageConfigCache {

    static final long TTL_MILLIS = 60_000L;
    private static final String PLATFORM_OWNER = "\u0000platform";

    /** A storage config with its credentials already decrypted. Never serialised or logged. */
    public record Resolved(OrgCloudConfig cfg, String accessKey, String secretKey) {
        @Override public String toString() { return "Resolved[cloud=" + (cfg != null ? cfg.getCloud() : null) + "]"; }
    }

    private record Entry(Resolved value, long expiresAt) {}

    /** owner (orgId, or the platform marker for no-org files) → (purpose|bucket) → entry. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>> byOwner = new ConcurrentHashMap<>();

    /**
     * The cached value for (orgId, slot), or {@code loader.get()} cached for {@link #TTL_MILLIS}.
     * A loader that throws caches nothing.
     */
    Resolved get(String orgId, String slot, Supplier<Resolved> loader) {
        String owner = orgId != null ? orgId : PLATFORM_OWNER;
        long now = System.currentTimeMillis();
        Map<String, Entry> slots = byOwner.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        Entry e = slots.get(slot);
        if (e != null && e.expiresAt() > now) return e.value();
        Resolved fresh = loader.get();
        slots.put(slot, new Entry(fresh, now + TTL_MILLIS));
        return fresh;
    }

    /** Drop everything cached for one organisation (call after its cloud config is saved). */
    public void evictOrg(String orgId) {
        if (orgId == null) return;
        byOwner.remove(orgId);
        log.debug("Storage config cache evicted for org {}", orgId);
    }

    /** Drop everything (call after the platform-managed storage default changes: it affects every org). */
    public void evictAll() {
        byOwner.clear();
        log.debug("Storage config cache cleared");
    }
}
