package com.braify.feature.session.service;

import com.braify.feature.session.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Purges inactive (logged-out / revoked) UserSession records that are older
 * than the configured JWT expiration window.  Sessions are kept for one full
 * expiration cycle after becoming inactive so that any in-flight requests that
 * still reference a recently-invalidated JTI can detect the revocation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupScheduler {

    private final UserSessionRepository sessionRepository;

    @Value("${jwt.expiration-hours:24}")
    private int expirationHours;

    /** Runs once every 6 hours. */
    //@Scheduled(fixedDelay = 6 * 3_600_000L)
    public void purgeInactiveSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(expirationHours);
        long deleted = sessionRepository.deleteByActiveFalseAndLastUsedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("[SessionCleanup] Purged {} inactive session record(s) older than {} hours",
                    deleted, expirationHours);
        } else {
            log.debug("[SessionCleanup] No stale inactive sessions to purge");
        }
    }
}
