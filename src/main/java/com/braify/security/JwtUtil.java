package com.braify.security;

import com.braify.model.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

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

    public LocalDateTime expiresAt(String token) {
        Date exp = parseToken(token).getExpiration();
        return exp.toInstant()
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime();
    }
}
