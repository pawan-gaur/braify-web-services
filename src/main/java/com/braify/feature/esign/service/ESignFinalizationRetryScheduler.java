package com.braify.feature.esign.service;

import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.repository.ESignDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Recovers documents that were fully signed (status SIGNED) but never reached COMPLETED because the
 * async finalization (signed-PDF generation + completion emails) threw — typically a transient
 * cloud-storage blip. Without this, such a document is stranded forever with no completion email.
 *
 * <p>Every 30 minutes it re-runs finalization for stuck documents, up to
 * {@link ESignClientService#MAX_FINALIZE_ATTEMPTS} automatic tries (a manual "Finalize now" always
 * works and resets the counter).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ESignFinalizationRetryScheduler {

    private final ESignDocumentRepository docRepo;
    private final ESignClientService      clientService;

    @Scheduled(fixedDelay = 1_800_000)   // every 30 minutes
    public void retryStuckFinalizations() {
        // A little grace so we don't race the async finalize that may still be running on a fresh submit.
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);

        List<ESignDocument> stuck = docRepo.findByStatus(ESignDocument.Status.SIGNED).stream()
                .filter(d -> d.getSignedPdfKey() == null && d.getSignedPdfData() == null)   // finalization incomplete
                .filter(d -> d.getFinalizeAttempts() < ESignClientService.MAX_FINALIZE_ATTEMPTS)
                .filter(d -> d.getSubmittedAt() == null || d.getSubmittedAt().isBefore(cutoff))
                .toList();

        if (stuck.isEmpty()) return;
        log.info("[ESignFinalizeRetry] Retrying finalization for {} stuck document(s)", stuck.size());

        for (ESignDocument doc : stuck) {
            try {
                boolean ok = clientService.retryFinalization(doc, null, null, false);
                log.info("[ESignFinalizeRetry] Document {} finalize retry -> {}", doc.getId(), ok ? "COMPLETED" : "still failing");
            } catch (Exception e) {
                log.warn("[ESignFinalizeRetry] Document {} retry threw: {}", doc.getId(), e.getMessage());
            }
        }
    }
}
