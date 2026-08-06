package com.braify.config.infra.email;

import com.braify.feature.emailconfig.model.OrgEmailConfig.EmailProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes a {@link ResolvedEmailConfig} to the matching {@link EmailSender}.
 * All senders are auto-discovered from the Spring context.
 */
@Component
public class EmailSenderFactory {

    private final Map<EmailProvider, EmailSender> byProvider = new EnumMap<>(EmailProvider.class);

    public EmailSenderFactory(List<EmailSender> senders) {
        for (EmailSender s : senders) {
            byProvider.put(s.provider(), s);
        }
    }

    public EmailSender get(EmailProvider provider) {
        EmailSender sender = byProvider.get(provider);
        if (sender == null) {
            throw new IllegalStateException("No email sender registered for provider: " + provider);
        }
        return sender;
    }
}
