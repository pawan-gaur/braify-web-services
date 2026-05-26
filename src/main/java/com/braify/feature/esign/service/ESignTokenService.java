package com.braify.feature.esign.service;

import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignSigningToken;
import com.braify.feature.esign.repository.ESignSigningTokenRepository;
import com.braify.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignTokenService {

    private final JwtUtil jwtUtil;
    private final ESignSigningTokenRepository tokenRepo;

    /**
     * Generates a signing JWT and persists the token record.
     * Revokes any previously active token for the same document.
     */
    public String issueSigningToken(ESignDocument doc, int validDays) {
        log.info("Issuing signing token for document='{}' validDays={}", doc.getId(), validDays);
        // Revoke previous active token if any
        tokenRepo.findByDocumentIdAndUsedFalseAndRevokedAtIsNull(doc.getId())
                 .ifPresent(t -> {
                     log.debug("Revoking previous active token jti='{}' for document='{}'", t.getJti(), doc.getId());
                     t.setRevokedAt(LocalDateTime.now());
                     tokenRepo.save(t);
                 });

        LocalDateTime expiresAt = LocalDateTime.now().plusDays(validDays);
        Date expiryDate = Date.from(expiresAt.atZone(ZoneId.systemDefault()).toInstant());

        String jti = java.util.UUID.randomUUID().toString();
        String jwt = jwtUtil.generateSigningToken(jti, doc.getClientEmail(), doc.getId(), expiryDate);

        ESignSigningToken record = ESignSigningToken.builder()
                .jti(jti)
                .documentId(doc.getId())
                .clientEmail(doc.getClientEmail())
                .issuedAt(LocalDateTime.now())
                .expiresAt(expiresAt)
                .build();
        tokenRepo.save(record);

        log.debug("Signing token issued jti='{}' for document='{}' expires={}", jti, doc.getId(), expiresAt);
        return jwt;
    }

    /**
     * Validates a signing JWT:
     * 1. Signature / expiry check via JwtUtil
     * 2. Token record exists, not used, not revoked
     */
    public Optional<ESignSigningToken> validateSigningToken(String jwt) {
        try {
            if (!jwtUtil.isValidSigningToken(jwt)) {
                log.warn("Signing token validation failed: invalid or expired JWT");
                return Optional.empty();
            }

            String jti = jwtUtil.extractJti(jwt);
            return tokenRepo.findByJti(jti)
                    .filter(t -> !t.isUsed() && t.getRevokedAt() == null
                                 && t.getExpiresAt().isAfter(LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("Signing token validation exception: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Marks the token as used after successful document submission. */
    public void markUsed(String jti) {
        tokenRepo.findByJti(jti).ifPresent(t -> {
            t.setUsed(true);
            t.setUsedAt(LocalDateTime.now());
            tokenRepo.save(t);
            log.info("Signing token jti='{}' marked as used", jti);
        });
    }

    /**
     * Extracts the document ID from a signing JWT <em>without</em> checking whether the
     * token has already been used.  Used exclusively for post-submission attachment uploads
     * where the token was legitimately consumed during {@code submitDocument()} but the
     * JWT itself is still cryptographically valid (not expired, correct type).
     *
     * @return the documentId claim, or {@link Optional#empty()} if the JWT is invalid/expired
     */
    public Optional<String> extractDocumentIdFromToken(String jwt) {
        try {
            if (!jwtUtil.isValidSigningToken(jwt)) return Optional.empty();
            String documentId = jwtUtil.extractDocumentId(jwt);
            return documentId != null && !documentId.isBlank()
                    ? Optional.of(documentId)
                    : Optional.empty();
        } catch (Exception e) {
            log.warn("extractDocumentIdFromToken failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Expires all signing tokens whose expiresAt has passed (called by scheduler). */
    public int expireStaleTokens() {
        // MongoDB query done in-service: fetch all unused, unrevoked, past expiry
        var stale = tokenRepo.findAll().stream()
                .filter(t -> !t.isUsed()
                          && t.getRevokedAt() == null
                          && t.getExpiresAt().isBefore(LocalDateTime.now()))
                .toList();
        stale.forEach(t -> {
            t.setRevokedAt(LocalDateTime.now());
            tokenRepo.save(t);
        });
        if (!stale.isEmpty()) {
            log.info("Expired {} stale signing token(s)", stale.size());
        }
        return stale.size();
    }
}
