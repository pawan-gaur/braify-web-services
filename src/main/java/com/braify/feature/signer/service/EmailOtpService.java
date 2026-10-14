package com.braify.feature.signer.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.emaillog.model.EmailLog;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.emaillog.service.EmailLogService;
import com.braify.feature.esign.service.ESignEmailTemplates;
import com.braify.feature.internaltemplate.InternalEmailTemplateService;
import com.braify.feature.internaltemplate.InternalTemplateCodes;
import com.braify.feature.internaltemplate.InternalTemplateProvider;
import com.braify.feature.internaltemplate.InternalTemplateSeed;
import com.braify.feature.signer.model.EmailOtp;
import com.braify.feature.signer.repository.EmailOtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Issues and verifies one-time email login codes.
 *
 * <p>Codes are numeric, single-use, short-lived and stored only as BCrypt hashes.
 * Issuing a new code invalidates any earlier unused one. Sends are throttled per
 * email (minimum gap between sends + an hourly cap); a throttled request is silently
 * dropped so the endpoint never reveals whether an email is known.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOtpService implements InternalTemplateProvider {

    private final EmailOtpRepository otpRepository;
    private final PasswordEncoder    passwordEncoder;
    private final EmailDispatcher    emailDispatcher;
    private final EmailLogService    emailLogService;
    private final MongoTemplate      mongoTemplate;
    private final InternalEmailTemplateService internalTemplateService;

    private final SecureRandom random = new SecureRandom();

    @Value("${app.otp.length:6}")
    private int codeLength;

    @Value("${app.otp.ttl-minutes:10}")
    private int ttlMinutes;

    @Value("${app.otp.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.otp.resend-seconds:60}")
    private int resendSeconds;

    @Value("${app.otp.max-per-hour:5}")
    private int maxPerHour;

    /** Product name shown in the sign-in email (brand bar, copy, sender name). */
    @Value("${app.mail.product-name:Braify E-Sign}")
    private String productName;

    @Value("${app.mail.accent:#4F46E5}")
    private String accent;

    /** Optional footer contact line ("Need help? … · address"). */
    @Value("${app.mail.support-email:}")
    private String supportEmail;

    @Value("${app.mail.postal-address:}")
    private String postalAddress;

    public int getResendSeconds() { return resendSeconds; }
    public int getTtlMinutes()    { return ttlMinutes; }

    /**
     * Generates a code for {@code email} and emails it. Silently skips the send when the
     * email is being throttled.
     *
     * @param email         normalized email
     * @param recipientName greeting name (null → "there")
     * @param userAgent     requesting browser, shown as "Chrome on macOS" in the email
     */
    public void issue(String email, String recipientName, String userAgent, String ip) {
        LocalDateTime now = LocalDateTime.now();

        var last = otpRepository.findFirstByEmailOrderByCreatedAtDesc(email);
        if (last.isPresent() && last.get().getCreatedAt() != null
                && last.get().getCreatedAt().plusSeconds(resendSeconds).isAfter(now)) {
            log.info("OTP request throttled (resend gap) for '{}'", email);
            return;
        }
        if (otpRepository.countByEmailAndCreatedAtAfter(email, now.minusHours(1)) >= maxPerHour) {
            log.warn("OTP request throttled (hourly cap) for '{}'", email);
            return;
        }

        // Only the newest code is ever valid.
        otpRepository.findByEmailAndConsumedFalse(email).forEach(o -> {
            o.setConsumed(true);
            otpRepository.save(o);
        });

        String code = generateCode();
        otpRepository.save(EmailOtp.builder()
                .email(email)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plusMinutes(ttlMinutes))
                .requestIp(ip)
                .build());

        sendCodeEmail(email, code, recipientName, userAgent, now);
    }

    /** One message for every failure, so a guess never reveals whether a code exists for an email. */
    private static final String INVALID = "Incorrect or expired code. Check your latest email or request a new code.";

    /**
     * Verifies and consumes the latest code for {@code email}.
     *
     * <p>Each guess first claims an attempt with an atomic conditional increment, so parallel
     * requests cannot exceed {@code max-attempts} guesses per code; a correct code is then
     * consumed atomically, so it can open at most one session.
     *
     * @throws IllegalArgumentException when the code is wrong, expired, exhausted or already used
     */
    public void verify(String email, String code) {
        String cleaned = code == null ? "" : code.replaceAll("\\s", "");
        LocalDateTime now = LocalDateTime.now();

        // Claim one attempt on the newest live code (atomic: attempts < max, unexpired, unused).
        EmailOtp otp = mongoTemplate.findAndModify(
                new Query(Criteria.where("email").is(email)
                        .and("consumed").is(false)
                        .and("attempts").lt(maxAttempts)
                        .and("expiresAt").gt(now))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt")),
                new Update().inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true),
                EmailOtp.class);
        if (otp == null) throw new IllegalArgumentException(INVALID);

        if (!passwordEncoder.matches(cleaned, otp.getCodeHash())) {
            log.warn("OTP verification failed for '{}' (attempt {}/{})", email, otp.getAttempts(), maxAttempts);
            throw new IllegalArgumentException(INVALID);
        }

        // Consume — only one concurrent correct guess wins.
        EmailOtp consumed = mongoTemplate.findAndModify(
                new Query(Criteria.where("_id").is(otp.getId()).and("consumed").is(false)),
                new Update().set("consumed", true),
                EmailOtp.class);
        if (consumed == null) throw new IllegalArgumentException(INVALID);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }

    private void sendCodeEmail(String email, String code, String recipientName,
                               String userAgent, LocalDateTime requestedAt) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("organizationName", productName);
        vars.put("brandMark",        brandMark());
        vars.put("accent",           accent);
        vars.put("footerContact",    footerContact());
        vars.put("recipientName",    recipientName != null && !recipientName.isBlank() ? recipientName.trim() : "there");
        vars.put("code",             code);
        vars.put("codeBoxes",        codeBoxes(code));
        vars.put("ttlMinutes",       String.valueOf(ttlMinutes));
        vars.put("accountEmail",     email);
        vars.put("requestedAt",      requestedAt.format(REQUESTED_FMT) + " UTC");
        vars.put("requestedFrom",    describeDevice(userAgent));

        var tpl = internalTemplateService.find(InternalTemplateCodes.AUTH_SIGN_IN_CODE);
        String subject = tpl.map(EmailTemplate::getSubject).filter(x -> x != null && !x.isBlank()).orElse(SUBJECT);
        String html    = tpl.map(EmailTemplate::getHtmlContent).filter(x -> x != null && !x.isBlank())
                            .orElse(ESignEmailTemplates.SIGN_IN_CODE);
        try {
            emailLogService.recorded(
                    EmailLog.builder()
                            .category(EmailLog.Category.LOGIN_OTP)
                            .recipient(email)
                            .subject("Your " + productName + " sign-in code")   // never log the code itself
                            .senderName(productName)
                            .build(),
                    () -> emailDispatcher.sendTransactionalEmail(
                            email, subject, html, ESignEmailTemplates.SIGN_IN_CODE_TEXT, vars, productName));
            log.info("Login code sent to '{}'", email);
        } catch (Exception e) {
            log.error("Failed to send login code to '{}': {}", email, e.getMessage());
        }
    }

    /* ── Template 05 helpers ─────────────────────────────────────────────── */

    private static final String SUBJECT = "{{code}} is your {{organizationName}} sign-in code";
    private static final java.time.format.DateTimeFormatter REQUESTED_FMT =
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", java.util.Locale.ENGLISH);

    /** One bordered box per digit (table cells — reliable in Gmail / Outlook). */
    static String codeBoxes(String code) {
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" align=\"center\" style=\"margin:0 auto;\"><tr>");
        for (char c : code.toCharArray()) {
            sb.append("<td style=\"padding:0 3px;\"><div style=\"width:34px;height:42px;line-height:42px;border:1px solid #CBD5E1;")
              .append("border-radius:8px;background:#FFFFFF;text-align:center;font-size:22px;font-weight:700;color:#0F172A;")
              .append("font-family:'SFMono-Regular',Menlo,Consolas,monospace;\">").append(c).append("</div></td>");
        }
        return sb.append("</tr></table>").toString();
    }

    private String brandMark() {
        String initial = productName.isBlank() ? "B" : productName.trim().substring(0, 1).toUpperCase();
        return "<div style=\"width:28px;height:28px;line-height:28px;border-radius:7px;background:" + accent
                + ";color:#fff;text-align:center;font-weight:700;font-size:14px;\">" + initial + "</div>";
    }

    /** "Need help? support@… · address" — each part only when configured. */
    private String footerContact() {
        boolean hasS = supportEmail != null && !supportEmail.isBlank();
        boolean hasA = postalAddress != null && !postalAddress.isBlank();
        if (!hasS && !hasA) return "";
        StringBuilder sb = new StringBuilder("<div style=\"font-size:11px;line-height:1.6;color:#94A3B8;\">");
        if (hasS) sb.append("<span>Need help? <a href=\"mailto:").append(supportEmail).append("\" style=\"color:")
                    .append(accent).append(";font-weight:600;text-decoration:none;\">").append(supportEmail).append("</a></span>");
        if (hasS && hasA) sb.append("<span style=\"color:#CBD5E1;\"> · </span>");
        if (hasA) sb.append("<span>").append(postalAddress).append("</span>");
        return sb.append("</div>").toString();
    }

    /** "Chrome on macOS" from a User-Agent header; "Unknown device" when it can't be read. */
    static String describeDevice(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String browser = ua.contains("Edg/") ? "Edge"
                : ua.contains("OPR/") || ua.contains("Opera") ? "Opera"
                : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") || ua.contains("CriOS/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari"
                : "a browser";
        String os = ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Android") ? "Android"
                : ua.contains("Mac OS X") || ua.contains("Macintosh") ? "macOS"
                : ua.contains("Windows") ? "Windows"
                : ua.contains("Linux") ? "Linux"
                : null;
        return os != null ? browser + " on " + os : browser;
    }

    /** Registers Template 05 as an editable INTERNAL template (seeded / re-synced on startup). */
    @Override
    public List<InternalTemplateSeed> internalTemplateSeeds() {
        return List.of(new InternalTemplateSeed(
                InternalTemplateCodes.AUTH_SIGN_IN_CODE,
                "System — Auth: Sign-in Code",
                SUBJECT,
                ESignEmailTemplates.SIGN_IN_CODE,
                List.of("organizationName", "brandMark", "accent", "footerContact", "recipientName",
                        "code", "codeBoxes", "ttlMinutes", "accountEmail", "requestedAt", "requestedFrom")));
    }
}
