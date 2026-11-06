package com.braify.feature.fileupload.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cache keys for the per-credential SDK clients the uploaders keep (S3Client / S3Presigner,
 * Azure BlobServiceClient, GCP Storage are all thread-safe and meant to be reused).
 * Secrets never appear in a key: they are reduced to a SHA-256 digest.
 */
final class CloudClientKeys {

    static final String DEFAULT_CHAIN = "default-chain";

    private CloudClientKeys() {}

    /** {@code provider|scope|sha256(secret parts)} — parts are joined with a NUL separator. */
    static String of(String provider, String scope, String... secretParts) {
        return provider + "|" + (scope == null ? "" : scope) + "|" + sha256(secretParts);
    }

    static String sha256(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
