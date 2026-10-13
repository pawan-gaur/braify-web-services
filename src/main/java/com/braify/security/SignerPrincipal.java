package com.braify.security;

import java.security.Principal;

/**
 * Authenticated principal for a signer-portal session (JWT {@code type = "SIGNER"}).
 *
 * <p>Set by {@link JwtAuthFilter} with authority {@code ROLE_SIGNER}, and ONLY for
 * requests under {@code /api/signer/**} — a signer token never authenticates anywhere
 * else, so controllers that cast the principal to {@link UserDetailsImpl} are unreachable.
 *
 * @param accountId {@code SignerAccount} id
 * @param email     normalized email — the key used to resolve the signer's documents
 */
public record SignerPrincipal(String accountId, String email) implements Principal {

    @Override
    public String getName() {
        return "signer:" + email;
    }
}
