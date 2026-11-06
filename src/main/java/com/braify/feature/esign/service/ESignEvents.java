package com.braify.feature.esign.service;

import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.events.EventPublisher;
import com.braify.feature.events.PlatformEvent;
import com.braify.feature.events.PlatformEventTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds e-sign {@link PlatformEvent}s with one consistent payload (part of the public webhook
 * contract): document id/title/status, signing mode, signatories and — on completion — the
 * public verification URL. Never includes PDF bytes or signing tokens.
 */
@Component
@RequiredArgsConstructor
public class ESignEvents {

    private final EventPublisher publisher;

    @Value("${app.base-url:https://braify.com}")
    private String baseUrl;

    public void sent(ESignDocument doc) {
        publish(PlatformEventTypes.ESIGN_DOCUMENT_SENT, doc, null, "Sent for signature: " + title(doc));
    }

    public void viewed(ESignDocument doc, ESignDocument.Signatory who) {
        publish(PlatformEventTypes.ESIGN_DOCUMENT_VIEWED, doc, who, name(who, doc) + " opened “" + title(doc) + "”");
    }

    public void signed(ESignDocument doc, ESignDocument.Signatory who) {
        publish(PlatformEventTypes.ESIGN_SIGNER_SIGNED, doc, who, name(who, doc) + " signed “" + title(doc) + "”");
    }

    public void completed(ESignDocument doc) {
        publish(PlatformEventTypes.ESIGN_DOCUMENT_COMPLETED, doc, null, "Completed: “" + title(doc) + "” is signed by everyone");
    }

    public void cancelled(ESignDocument doc) {
        publish(PlatformEventTypes.ESIGN_DOCUMENT_CANCELLED, doc, null, "Cancelled: “" + title(doc) + "”");
    }

    public void expired(ESignDocument doc) {
        publish(PlatformEventTypes.ESIGN_DOCUMENT_EXPIRED, doc, null, "Expired before everyone signed: “" + title(doc) + "”");
    }

    private void publish(String type, ESignDocument doc, ESignDocument.Signatory who, String summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", doc.getId());
        data.put("title", doc.getTitle());
        data.put("status", doc.getStatus() != null ? doc.getStatus().name() : null);
        data.put("signingMode", doc.getSigningMode() != null ? doc.getSigningMode().name() : null);
        List<Map<String, Object>> sigs = doc.getSignatories() == null ? List.of() : doc.getSignatories().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.getName());
                    m.put("email", s.getEmail());
                    m.put("signingOrder", s.getSigningOrder());
                    m.put("status", s.getStatus() != null ? s.getStatus().name() : null);
                    m.put("signedAt", s.getSignedAt() != null ? s.getSignedAt().toString() + "Z" : null);
                    return m;
                }).toList();
        data.put("signatories", sigs);
        if (who != null) data.put("signatory", Map.of("name", nz(who.getName()), "email", nz(who.getEmail())));
        if (doc.getSentAt() != null)      data.put("sentAt", doc.getSentAt().toString() + "Z");
        if (doc.getCompletedAt() != null) data.put("completedAt", doc.getCompletedAt().toString() + "Z");
        if (PlatformEventTypes.ESIGN_DOCUMENT_COMPLETED.equals(type)) {
            data.put("verifyUrl", baseUrl.replaceAll("/$", "") + "/verify/" + doc.getId());
        }
        publisher.publish(PlatformEvent.of(type, doc.getOrgId(), doc.getCreatedBy(), summary, "/esign/" + doc.getId(), data));
    }

    private static String title(ESignDocument doc) {
        return doc.getTitle() != null ? doc.getTitle() : "Untitled document";
    }

    private static String name(ESignDocument.Signatory s, ESignDocument doc) {
        if (s != null && s.getName() != null && !s.getName().isBlank()) return s.getName();
        if (s != null && s.getEmail() != null) return s.getEmail();
        return doc.getClientName() != null ? doc.getClientName() : "A signer";
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
