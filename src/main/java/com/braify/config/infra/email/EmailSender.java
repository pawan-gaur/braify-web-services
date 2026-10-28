package com.braify.config.infra.email;

import com.braify.feature.emailconfig.model.OrgEmailConfig.EmailProvider;

/**
 * Provider adapter that actually delivers an {@link OutboundEmail} using a
 * {@link ResolvedEmailConfig}. One implementation per {@link EmailProvider}.
 */
public interface EmailSender {

    /** The provider this adapter handles. */
    EmailProvider provider();

    /**
     * Sends the message. Implementations should throw an unchecked exception
     * (e.g. {@link IllegalStateException}) on failure.
     */
    EmailSendResult send(ResolvedEmailConfig cfg, OutboundEmail email);
}
