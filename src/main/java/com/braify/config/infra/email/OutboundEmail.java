package com.braify.config.infra.email;

import java.util.List;

/**
 * A provider-neutral, fully-rendered outbound email. Built by {@link EmailDispatcher}
 * and handed to the resolved {@link EmailSender} adapter.
 */
public record OutboundEmail(
        String fromAddress,
        String fromName,
        String replyTo,
        String to,
        List<String> cc,
        String subject,
        String html,
        List<Attachment> attachments,
        /** Optional plain-text alternative (transactional mail — better inbox placement). */
        String text,
        /** Optional extra headers (e.g. a unique X-Entity-Ref-ID so codes never thread). */
        java.util.Map<String, String> headers
) {
    /** Backwards-compatible constructor: no plain-text part, no extra headers. */
    public OutboundEmail(String fromAddress, String fromName, String replyTo, String to, List<String> cc,
                         String subject, String html, List<Attachment> attachments) {
        this(fromAddress, fromName, replyTo, to, cc, subject, html, attachments, null, java.util.Map.of());
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public boolean hasHeaders() {
        return headers != null && !headers.isEmpty();
    }


    /** Binary attachment (raw bytes, not base64). */
    public record Attachment(String fileName, byte[] content) {}

    /** True when a non-blank display name should be shown in the From header. */
    public boolean hasFromName() {
        return fromName != null && !fromName.isBlank();
    }

    /** Composes {@code "Display Name <addr>"}, or just {@code "addr"} when no name. */
    public String formattedFrom() {
        if (hasFromName()) {
            return fromName.trim() + " <" + fromAddress + ">";
        }
        return fromAddress;
    }

    public boolean hasCc() {
        return cc != null && !cc.isEmpty();
    }

    public boolean hasReplyTo() {
        return replyTo != null && !replyTo.isBlank();
    }

    public boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }
}
