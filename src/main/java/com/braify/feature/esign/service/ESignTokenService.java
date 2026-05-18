package com.braify.feature.esign.service;

import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.ESignSigningToken;
import com.braify.feature.esign.repository.ESignSigningTokenRepository;
import com.braify.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;

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
        // Revoke previous active token if any
        tokenRepo.findByDocumentIdAndUsedFalseAndRevokedAtIsNull(doc.getId())
                 .ifPresent(t -> {
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

        return jwt;
    }

    /**
     * Validates a signing JWT:
     * 1. Signature / expiry check via JwtUtil
     * 2. Token record exists, not used, not revoked
     */
    public Optional<ESignSigningToken> validateSigningToken(String jwt) {
        try {
            if (!jwtUtil.isValidSigningToken(jwt)) return Optional.empty();

            String jti = jwtUtil.extractJti(jwt);
            return tokenRepo.findByJti(jti)
                    .filter(t -> !t.isUsed() && t.getRevokedAt() == null
                                 && t.getExpiresAt().isAfter(LocalDateTime.now()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Marks the token as used after successful document submission. */
    public void markUsed(String jti) {
        tokenRepo.findByJti(jti).ifPresent(t -> {
            t.setUsed(true);
            t.setUsedAt(LocalDateTime.now());
            tokenRepo.save(t);
        });
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
        return stale.size();
    }
}
