package com.braify.security;

import java.util.Set;

/**
 * Security principal placed into the SecurityContext when a request is
 * authenticated via an X-API-Key header.
 *
 * @param orgId           The organisation that owns this API key
 * @param apiKeyId        The database ID of the OrgApiKey document
 * @param keyPrefix       The display prefix (first 12 chars of the plain key)
 * @param allowedFeatures The feature set this key is permitted to access
 */
public record ApiKeyPrincipal(
        String orgId,
        String apiKeyId,
        String keyPrefix,
        Set<String> allowedFeatures
) {}
