package com.braify.config.infra.email;

import com.braify.feature.emailconfig.model.OrgEmailConfig.EmailProvider;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Sends via a caller-supplied SMTP server using a per-config JavaMailSender. */
@Slf4j
@Component
public class SmtpEmailSender implements EmailSender {

    @Override
    public EmailProvider provider() {
        return EmailProvider.SMTP;
    }

    @Override
    public EmailSendResult send(ResolvedEmailConfig cfg, OutboundEmail email) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(cfg.smtpHost());
        mailSender.setPort(cfg.smtpPort() != null ? cfg.smtpPort() : 587);
        mailSender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (cfg.smtpUsername() != null && !cfg.smtpUsername().isBlank()) {
            mailSender.setUsername(cfg.smtpUsername());
        }
        if (cfg.smtpPassword() != null && !cfg.smtpPassword().isBlank()) {
            mailSender.setPassword(cfg.smtpPassword());
        }

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(cfg.smtpUsername() != null && !cfg.smtpUsername().isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(cfg.smtpStartTls()));
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, email.hasAttachments(), StandardCharsets.UTF_8.name());

            if (email.hasFromName()) {
                helper.setFrom(cfg.fromEmail(), email.fromName());
            } else {
                helper.setFrom(cfg.fromEmail());
            }
            helper.setTo(email.to());
            if (email.hasCc()) {
                helper.setCc(email.cc().toArray(new String[0]));
            }
            if (email.hasReplyTo()) {
                helper.setReplyTo(email.replyTo());
            }
            helper.setSubject(email.subject());
            helper.setText(email.html(), true);

            if (email.hasAttachments()) {
                for (OutboundEmail.Attachment a : email.attachments()) {
                    helper.addAttachment(a.fileName(), new ByteArrayResource(a.content()));
                }
            }

            mailSender.send(message);
            String id = message.getMessageID();
            log.info("Email sent to {} via SMTP {} (id={})", email.to(), cfg.smtpHost(), id);
            return new EmailSendResult(id, "SMTP");
        } catch (Exception e) {
            log.error("SMTP send failed to {}: {}", email.to(), e.getMessage());
            throw new IllegalStateException("Could not send email to " + email.to() + " via SMTP: " + e.getMessage(), e);
        }
    }
}
