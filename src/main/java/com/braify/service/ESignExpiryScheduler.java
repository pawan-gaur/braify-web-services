package com.braify.service;

import com.braify.model.ESignAuditEvent;
import com.braify.model.ESignDocument;
import com.braify.repository.ESignDocumentRepository;
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
        List<ESignDocument> toExpire = docRepo.findAll().stream()
                .filter(d -> (d.getStatus() == ESignDocument.Status.PENDING ||
                              d.getStatus() == ESignDocument.Status.IN_REVIEW)
                          && d.getTokenExpiresAt() != null
                          && d.getTokenExpiresAt().isBefore(LocalDateTime.now()))
                .toList();

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
