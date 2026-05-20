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

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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

    /** Returns true if the token is a USER auth token (no ESIGN claim). */
    public boolean isUserToken(String token) {
        try {
            Claims claims = parseToken(token);
            return !"ESIGN".equals(claims.get("type", String.class));
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
