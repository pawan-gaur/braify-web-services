package com.braify.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption service for sensitive at-rest fields
 * (cloud storage credentials, API secrets, etc.).
 *
 * <h3>Storage format</h3>
 * Each encrypted value is stored as a single Base64 string:
 * <pre>
 *   Base64( IV[12] || CipherText+GCMTag[variable] )
 * </pre>
 * The 12-byte IV (nonce) is randomly generated per encryption call, so repeated
 * encryptions of the same plaintext produce different ciphertexts — preventing
 * dictionary attacks on stored values.
 *
 * <h3>Key derivation</h3>
 * The AES-256 key is derived from {@code app.encryption.secret} via SHA-256,
 * ensuring the key is always exactly 32 bytes regardless of the configured secret length.
 *
 * <h3>Migration / legacy values</h3>
 * {@link #decryptSafe(String)} falls back to returning the raw stored value when
 * decryption fails. This allows existing plain-text data to be read transparently;
 * on the next write the value will be properly encrypted.
 *
 * <p>Configure the secret in {@code application.properties}:
 * <pre>
 * app.encryption.secret=your-strong-secret-at-least-32-chars
 * </pre>
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM     = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LENGTH  = 12;   // 96-bit nonce
    private static final int    GCM_TAG_BITS   = 128;  // 128-bit authentication tag

    private final SecretKeySpec secretKey;
    private final SecureRandom  secureRandom = new SecureRandom();

    public EncryptionService(
            @Value("${app.encryption.secret}") String secret) throws Exception {

        // Derive a 32-byte (256-bit) key from the configured secret via SHA-256
        byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    // ── Encrypt ───────────────────────────────────────────────────────────────

    /**
     * Encrypts {@code plainText} using AES-256-GCM and returns a Base64-encoded
     * string containing the random IV followed by the ciphertext + authentication tag.
     *
     * @param plainText the value to encrypt; {@code null} returns {@code null}
     * @return Base64-encoded encrypted value, or {@code null} if input is {@code null}
     */
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext so we can recover it during decryption
            byte[] combined = new byte[GCM_IV_LENGTH + cipherBytes.length];
            System.arraycopy(iv,          0, combined, 0,             GCM_IV_LENGTH);
            System.arraycopy(cipherBytes, 0, combined, GCM_IV_LENGTH, cipherBytes.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    // ── Decrypt ───────────────────────────────────────────────────────────────

    /**
     * Decrypts a value previously produced by {@link #encrypt(String)}.
     *
     * @param cipherText Base64-encoded IV || ciphertext; {@code null} returns {@code null}
     * @return the original plaintext
     * @throws RuntimeException if decryption fails (tampered data or wrong key)
     */
    public String decrypt(String cipherText) {
        if (cipherText == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv          = Arrays.copyOfRange(combined, 0,             GCM_IV_LENGTH);
            byte[] cipherBytes = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * Like {@link #decrypt(String)} but falls back to returning the raw stored
     * value when decryption fails.
     *
     * <p>This is used during migration: if a credential was stored as plain text
     * before encryption was introduced it can still be read. On the next save the
     * caller must re-encrypt by calling {@link #encrypt(String)} on the plain value.
     *
     * @param storedValue the Base64-encoded ciphertext, or a legacy plain-text value
     * @return decrypted plaintext, or the original {@code storedValue} on failure
     */
    public String decryptSafe(String storedValue) {
        if (storedValue == null) return null;
        try {
            return decrypt(storedValue);
        } catch (Exception e) {
            log.warn("Decryption failed for stored value — returning as-is (possible legacy plain-text). " +
                     "Value will be encrypted on next save.");
            return storedValue;
        }
    }
}
