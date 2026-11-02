package com.braify.feature.esign.service;

import com.braify.config.infra.sms.SmsDispatcher;
import com.braify.feature.billing.service.PlanAccessService;
import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.quota.service.QuotaService;
import com.braify.feature.shortlink.service.ShortLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SMS signing reminders. Sent alongside the email reminder when the org's reminder policy has SMS
 * on, the plan allows it and the signatory has a phone number. The message carries a short link
 * ({@code /s/<code>}) to the same signing link the email uses; each SMS is metered per org/month.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ESignSmsService {

    /** E.164: '+', country code, up to 15 digits in total. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final SmsDispatcher          smsDispatcher;
    private final ShortLinkService       shortLinks;
    private final ESignTokenService      tokenService;
    private final ESignAuditService      auditService;
    private final PlanAccessService      planAccessService;
    private final QuotaService           quotaService;
    private final OrganizationRepository orgRepo;

    @Value("${app.base-url:https://braify.com}")
    private String baseUrl;

    /**
     * Normalises a phone number to E.164 ("+250 788-123 456" → "+250788123456"); null/blank → null.
     *
     * @throws IllegalArgumentException when present but not an international number
     */
    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String p = raw.trim().replaceAll("[\\s().-]", "");
        if (p.startsWith("00")) p = "+" + p.substring(2);
        if (!E164.matcher(p).matches()) {
            throw new IllegalArgumentException("Phone number '" + raw.trim()
                    + "' must be in international format, e.g. +250788123456");
        }
        return p;
    }

    /** @return true if the provider accepted the SMS. Never throws. */
    public boolean sendReminderSms(ESignDocument doc, ESignDocument.Signatory s) {
        if (s.getPhone() == null) return false;
        try {
            Organization org = orgRepo.findById(doc.getOrgId()).orElse(null);
            if (org == null || !planAccessService.smsEnabled(org)) return false;

            String token = tokenService.reissueSigningToken(doc, s);   // reuses the active link
            String code = shortLinks.create("/sign/" + token, doc.getOrgId(),
                    doc.getTokenExpiresAt() != null
                            ? doc.getTokenExpiresAt().atZone(ZoneId.systemDefault()).toInstant()
                            : java.time.Instant.now().plus(java.time.Duration.ofDays(7)));
            String link = baseUrl.replaceAll("/+$", "") + "/s/" + code;

            smsDispatcher.sendSms(doc.getOrgId(), s.getPhone(), body(org.getName(), doc, link));
            quotaService.incrementSms(doc.getOrgId());
            auditService.log(doc.getId(), "system", ESignAuditEvent.ActorType.SYSTEM,
                    ESignAuditEvent.EventType.DOCUMENT_SENT, null, null,
                    Map.of("action", "SMS_REMINDER_SENT", "signatory", s.getEmail() != null ? s.getEmail() : ""));
            log.info("SMS reminder sent for doc {} signatory {}", doc.getId(), s.getId());
            return true;
        } catch (Exception e) {
            log.warn("SMS reminder failed for doc {} signatory {}: {}", doc.getId(), s.getId(), e.getMessage());
            return false;
        }
    }

    /** Kept to one SMS segment where possible: "<Org>: please sign "<title>" (due 4 Jul): <link>". */
    static String body(String orgName, ESignDocument doc, String link) {
        String org = orgName == null ? "" : clip(orgName, 30);
        if (org.isEmpty()) org = "Braify";
        String title = clip(doc.getTitle() != null ? doc.getTitle() : "", 50);
        if (title.isEmpty()) title = "your document";
        String due = doc.getTokenExpiresAt() != null ? " (due " + doc.getTokenExpiresAt().format(DUE) + ")" : "";
        return org + ": please sign \"" + title + "\"" + due + ": " + link;
    }

    /** Plain ASCII (a single non-GSM character switches the SMS to UCS-2 = 70 chars/segment), clipped. */
    static String clip(String s, int max) {
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")                       // é → e
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-')
                .replaceAll("[^\\x20-\\x7E]", "")              // drop emoji and anything else non-ASCII
                .replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 3) + "...";
    }
}
