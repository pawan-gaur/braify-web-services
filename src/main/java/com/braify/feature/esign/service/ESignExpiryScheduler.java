package com.braify.feature.esign.service;

import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.repository.ESignDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Runs every hour to:
 *  1. Revoke stale signing tokens
 *  2. Transition PENDING/IN_REVIEW documents past their tokenExpiresAt to EXPIRED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ESignExpiryScheduler {

    private final ESignTokenService       tokenService;
    private final ESignDocumentRepository docRepo;
    private final ESignAuditService       auditService;

    @Scheduled(fixedDelay = 3_600_000) // every 1 hour
    public void expireTokensAndDocuments() {
        // 1. Revoke stale JWT records
        int revokedTokens = tokenService.expireStaleTokens();
        if (revokedTokens > 0)
            log.info("[ESignExpiry] Revoked {} stale signing tokens", revokedTokens);

        // 2. Mark documents as EXPIRED
        // Use a targeted query instead of findAll() — the previous findAll() loaded
        // every document including embedded PDF byte arrays into JVM heap on every tick.
        List<ESignDocument> toExpire = docRepo.findByStatusInAndTokenExpiresAtBefore(
                List.of(ESignDocument.Status.PENDING,
                        ESignDocument.Status.IN_REVIEW,
                        ESignDocument.Status.PARTIALLY_SIGNED),
                LocalDateTime.now());

        toExpire.forEach(doc -> {
            doc.setStatus(ESignDocument.Status.EXPIRED);
            docRepo.save(doc);
            auditService.log(doc.getId(), "SYSTEM",
                    ESignAuditEvent.ActorType.SYSTEM,
                    ESignAuditEvent.EventType.LINK_EXPIRED, null, null,
                    Map.of("expiredAt", LocalDateTime.now().toString()));
            log.info("[ESignExpiry] Document {} marked EXPIRED", doc.getId());
        });
    }
}
