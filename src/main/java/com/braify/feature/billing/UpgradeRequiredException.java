package com.braify.feature.billing;

/**
 * The organisation's plan doesn't include this feature. Mapped to HTTP 403 with
 * {@code reason = "UPGRADE_REQUIRED"} so the client can show an upgrade prompt.
 */
public class UpgradeRequiredException extends RuntimeException {
    public UpgradeRequiredException(String message) {
        super(message);
    }
}
