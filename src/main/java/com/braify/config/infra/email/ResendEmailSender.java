package com.braify.config.infra.email;

import com.braify.feature.emailconfig.model.OrgEmailConfig.EmailProvider;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.Attachment;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;

/** Sends via the Resend HTTP API using the {@code resend-java} SDK. */
@Slf4j
@Component
public class ResendEmailSender implements EmailSender {

    @Override
    public EmailProvider provider() {
        return EmailProvider.RESEND;
    }

    @Override
    public EmailSendResult send(ResolvedEmailConfig cfg, OutboundEmail email) {
        CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                .from(email.formattedFrom())
                .to(email.to())
                .subject(email.subject())
                .html(email.html());

        if (email.hasCc()) {
            builder.cc(email.cc());
        }
        if (email.hasReplyTo()) {
            builder.replyTo(email.replyTo());
        }
        if (email.hasAttachments()) {
            builder.attachments(email.attachments().stream()
                    .map(a -> Attachment.builder()
                            .fileName(a.fileName())
                            .content(Base64.getEncoder().encodeToString(a.content()))
                            .build())
                    .toList());
        }

        try {
            CreateEmailResponse response = new Resend(cfg.apiKey()).emails().send(builder.build());
            String id = response != null ? response.getId() : null;
            log.info("Email sent to {} via Resend (id={})", email.to(), id);
            return new EmailSendResult(id, "RESEND");
        } catch (ResendException e) {
            log.error("Resend send failed to {}: {}", email.to(), e.getMessage());
            throw new IllegalStateException("Could not send email to " + email.to() + ": " + e.getMessage(), e);
        }
    }
}
