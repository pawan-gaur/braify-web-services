package com.braify.feature.signer.service;

/**
 * Email normalization shared by the signer login + inbox code.
 *
 * <p>Only ASCII letters are case-folded. Unicode-aware folding would let look-alike
 * characters collide with a different address (e.g. KELVIN SIGN U+212A lower-cases to
 * "k"), which matters here because the email is the key that grants inbox access.
 */
public final class SignerEmails {

    private SignerEmails() {}

    /** Trimmed email with ASCII A–Z lower-cased, or {@code null} for blank input. */
    public static String normalize(String email) {
        if (email == null) return null;
        String e = email.trim();
        if (e.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(e.length());
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return sb.toString();
    }

    /** Equality after {@link #normalize} — ASCII case-insensitive, exact otherwise. */
    public static boolean same(String a, String b) {
        String na = normalize(a), nb = normalize(b);
        return na != null && na.equals(nb);
    }
}
