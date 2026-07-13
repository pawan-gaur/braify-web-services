package com.braify.security;

import com.braify.feature.user.model.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    /**
     * Access-token lifetime in minutes. Deliberately short — the token is silently
     * re-minted through the refresh-cookie flow, so a leaked access token is only
     * usable for this window.
     */
    @Value("${jwt.access-minutes:30}")
    private int accessMinutes;

    @Value("${jwt.mfa-challenge-minutes:5}")
    private int mfaChallengeMinutes;

    /** Cryptographically strong source for opaque refresh tokens. */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Fail fast on startup if the JWT secret is missing, unresolved, or too weak — so the app
     * can never sign tokens with a predictable/known key (which would allow token forgery).
     */
    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.isBlank()
                || secret.equals("{JWT_SECRET}") || secret.equals("${JWT_SECRET}")) {
            throw new IllegalStateException(
                    "jwt.secret is not configured. Set the JWT_SECRET environment variable to a strong random value (>= 32 chars).");
        }
        if (secret.trim().length() < 32) {
            throw new IllegalStateException(
                    "jwt.secret is too short (" + secret.trim().length() + " chars). Use a random value of at least 32 characters.");
        }
    }

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
                .expiration(new Date(System.currentTimeMillis() + accessMinutes * 60_000L))
                .signWith(key())
                .compact();
    }

    // ── Refresh-token support ───────────────────────────────────────────────

    /**
     * Generates an opaque, high-entropy refresh token (256-bit, URL-safe). This is
     * NOT a JWT — it carries no claims and is only meaningful as a lookup key against
     * the {@code refreshTokenHash} persisted on the {@link com.braify.feature.session.model.UserSession}.
     * Returned to the client once, as an httpOnly cookie; only its hash is stored.
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hash (URL-safe base64) of a raw refresh token, for at-rest storage + lookup. */
    public String hashRefreshToken(String rawToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
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
