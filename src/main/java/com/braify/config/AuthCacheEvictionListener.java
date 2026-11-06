package com.braify.config;

import com.braify.feature.apikey.model.OrgApiKey;
import com.braify.feature.apikey.service.OrgApiKeyService;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.user.model.AppUser;
import com.braify.security.AuthCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.data.mongodb.core.mapping.event.AfterDeleteEvent;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.data.mongodb.core.mapping.event.MongoMappingEvent;
import org.springframework.stereotype.Component;

/**
 * Central, write-side eviction for the in-memory auth caches ({@link AuthCache}) and the
 * API-key caches in {@link OrgApiKeyService}.
 *
 * <p>Spring Data publishes {@link AfterSaveEvent} for every repository {@code save/saveAll}
 * and {@code MongoTemplate.save/insert}, and {@link AfterDeleteEvent} for every repository
 * delete (incl. derived {@code deleteBy…}) and {@code MongoTemplate.remove}. Hooking these
 * means every code path that persists a session revoke, logout, password/MFA change, role /
 * status change, user deactivation, API-key revoke/toggle or org update evicts the cache —
 * without having to remember an explicit evict call at each (scattered) call site.
 *
 * <p>NOT covered: {@code updateFirst/updateMulti/findAndModify} (no mapping events). Paths
 * that use them on these collections must evict explicitly (JwtAuthFilter does for its
 * session timeout); anything else is bounded by the cache TTL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthCacheEvictionListener implements ApplicationListener<MongoMappingEvent<?>> {

    private final AuthCache authCache;
    /** Resolved lazily so this listener never drags the service graph into early (startup) event publishing. */
    private final ObjectProvider<OrgApiKeyService> orgApiKeyServiceProvider;

    @Override
    public void onApplicationEvent(MongoMappingEvent<?> event) {
        try {
            if (event instanceof AfterSaveEvent<?> save) {
                onSave(save.getSource());
            } else if (event instanceof AfterDeleteEvent<?> del) {
                onDelete(del.getType());
            }
        } catch (Exception e) {
            // Never fail the write because of cache bookkeeping — fall back to dropping everything.
            log.warn("Cache eviction failed ({}), clearing auth caches", e.getMessage());
            authCache.evictAllSessions();
            authCache.evictAllUsers();
            try {
                OrgApiKeyService keys = orgApiKeyServiceProvider.getIfAvailable();
                if (keys != null) keys.evictAllCaches();
            } catch (Exception ignored) {
                // service not created yet (startup) → it has no cache entries to drop
            }
        }
    }

    private void onSave(Object source) {
        if (source instanceof UserSession s) {
            authCache.evictSessionRow(s.getId(), s.getJti());
        } else if (source instanceof AppUser u) {
            authCache.evictUser(u.getId(), u.getEmail());
        } else if (source instanceof OrgApiKey k) {
            OrgApiKeyService keys = orgApiKeyServiceProvider.getIfAvailable();
            if (keys != null) keys.evictKey(k.getKeyHash());
        } else if (source instanceof Organization o) {
            OrgApiKeyService keys = orgApiKeyServiceProvider.getIfAvailable();
            if (keys != null) keys.evictOrg(o.getId());
        }
    }

    /** Delete events only carry the query, not the entity — evict the whole cache for that type. */
    private void onDelete(Class<?> type) {
        if (type == null) return;
        if (UserSession.class.isAssignableFrom(type)) {
            authCache.evictAllSessions();
        } else if (AppUser.class.isAssignableFrom(type)) {
            authCache.evictAllUsers();
            authCache.evictAllSessions();
        } else if (OrgApiKey.class.isAssignableFrom(type) || Organization.class.isAssignableFrom(type)) {
            OrgApiKeyService keys = orgApiKeyServiceProvider.getIfAvailable();
            if (keys != null) keys.evictAllCaches();
        }
    }
}
