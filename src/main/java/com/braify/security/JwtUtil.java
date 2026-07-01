package com.braify.security;

import com.braify.feature.user.model.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-hours:24}")
    private int expirationHours;

    @Value("${jwt.mfa-challenge-minutes:5}")
    private int mfaChallengeMinutes;

    private SecretKey key() {
        // Derive a guaranteed 256-bit key by SHA-256-hashing the configured secret, so
        // any secret length is accepted (mirrors EncryptionService). Using the raw secret
        // bytes directly fails JWT's RFC 7518 minimum-key-size check when the secret is
        // shorter than 32 bytes.
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public String generateToken(AppUser user) {
        String jti = UUID.randomUUID().toString();
        return Jwts.builder()
                .id(jti)
                .subject(user.getId())
                .claim("role", user.getRole().name())
                .claim("orgId", user.getOrganizationId())
                .claim("email", user.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationHours * 3600_000L))
                .signWith(key())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractJti(String token) {
        return parseToken(token).getId();
    }

    public String extractUserId(String token) {
        return parseToken(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── MFA challenge-token support ─────────────────────────────────────────

    /**
     * Short-lived token issued after a correct password when MFA is required.
     * Carries {@code type = "MFA_CHALLENGE"} and the userId as subject. It is NOT
     * a session token — no {@link com.braify.feature.session.model.UserSession} is
     * created for it, so {@link JwtAuthFilter} (which requires an active session)
     * will never authenticate a request with it. It is only accepted by
     * POST /api/auth/login/mfa.
     */
    public String generateMfaChallengeToken(AppUser user) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId())
                .claim("type", "MFA_CHALLENGE")
                .claim("email", user.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + mfaChallengeMinutes * 60_000L))
                .signWith(key())
                .compact();
    }

    /** Returns true only if the token is valid AND carries {@code type = "MFA_CHALLENGE"}. */
    public boolean isValidMfaChallengeToken(String token) {
        try {
            return "MFA_CHALLENGE".equals(parseToken(token).get("type", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    // ── E-Sign signing-token support ────────────────────────────────────────

    /**
     * Generates a short-lived JWT specifically for the client signing flow.
     * Distinguished from user JWTs by {@code type = "ESIGN"} claim.
     */
    public String generateSigningToken(String jti, String clientEmail, String documentId, java.util.Date expiresAt) {
        return Jwts.builder()
                .id(jti)
                .subject(clientEmail)
                .claim("type", "ESIGN")
                .claim("documentId", documentId)
                .issuedAt(new Date())
                .expiration(expiresAt)
                .signWith(key())
                .compact();
    }

    /** Returns true only if the token is valid AND carries {@code type = "ESIGN"}. */
    public boolean isValidSigningToken(String token) {
        try {
            Claims claims = parseToken(token);
            return "ESIGN".equals(claims.get("type", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns true if the token is a USER auth token (not an e-sign signing or view token). */
    public boolean isUserToken(String token) {
        try {
            String type = parseToken(token).get("type", String.class);
            return !"ESIGN".equals(type) && !"ESIGN_VIEW".equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    // ── E-Sign view-only token support (read-only access for CC recipients) ──

    /**
     * Generates a read-only view token for a document. Carries {@code type = "ESIGN_VIEW"} so it
     * can NEVER be used to sign (signing requires {@code type = "ESIGN"}). Used for the view-only
     * links sent to CC recipients.
     */
    public String generateViewToken(String documentId, java.util.Date expiresAt) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(documentId)
                .claim("type", "ESIGN_VIEW")
                .claim("documentId", documentId)
                .issuedAt(new Date())
                .expiration(expiresAt)
                .signWith(key())
                .compact();
    }

    /** Returns true only if the token is valid AND carries {@code type = "ESIGN_VIEW"}. */
    public boolean isValidViewToken(String token) {
        try {
            return "ESIGN_VIEW".equals(parseToken(token).get("type", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    public String extractDocumentId(String token) {
        return parseToken(token).get("documentId", String.class);
    }

    public String extractClientEmail(String token) {
        return parseToken(token).getSubject();
    }

    public LocalDateTime expiresAt(String token) {
        Date exp = parseToken(token).getExpiration();
        return exp.toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime();
    }
}
