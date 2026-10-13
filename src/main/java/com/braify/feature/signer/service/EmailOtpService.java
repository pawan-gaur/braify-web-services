package com.braify.feature.signer.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.emaillog.model.EmailLog;
import com.braify.feature.emaillog.service.EmailLogService;
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
public class EmailOtpService {

    private static final String PLATFORM_NAME = "Braify";

    private final EmailOtpRepository otpRepository;
    private final PasswordEncoder    passwordEncoder;
    private final EmailDispatcher    emailDispatcher;
    private final EmailLogService    emailLogService;
    private final MongoTemplate      mongoTemplate;

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

    public int getResendSeconds() { return resendSeconds; }
    public int getTtlMinutes()    { return ttlMinutes; }

    /**
     * Generates a code for {@code email} and emails it. Silently skips the send when the
     * email is being throttled.
     *
     * @param email normalized email
     */
    public void issue(String email, String ip) {
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

        sendCodeEmail(email, code);
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

    private void sendCodeEmail(String email, String code) {
        String subject = "{{code}} is your " + PLATFORM_NAME + " sign-in code";
        Map<String, Object> vars = new HashMap<>();
        vars.put("code", code);
        vars.put("ttlMinutes", String.valueOf(ttlMinutes));
        vars.put("platformName", PLATFORM_NAME);
        try {
            emailLogService.recorded(
                    EmailLog.builder()
                            .category(EmailLog.Category.LOGIN_OTP)
                            .recipient(email)
                            .subject("Your " + PLATFORM_NAME + " sign-in code")   // never log the code itself
                            .senderName(PLATFORM_NAME)
                            .build(),
                    () -> emailDispatcher.sendHtmlEmail(email, subject, OTP_HTML, vars, PLATFORM_NAME));
            log.info("Login code sent to '{}'", email);
        } catch (Exception e) {
            log.error("Failed to send login code to '{}': {}", email, e.getMessage());
        }
    }

    private static final String OTP_HTML = """
            <!DOCTYPE html>
            <html><body style="margin:0;padding:0;background:#F4F6FA;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
              <div style="max-width:520px;margin:32px auto;background:#FFFFFF;border-radius:14px;border:1px solid #E5E9F0;overflow:hidden;">
                <div style="height:4px;background:#0066FF;"></div>
                <div style="padding:36px 32px;">
                  <div style="font-size:11px;font-weight:700;letter-spacing:0.14em;color:#0066FF;margin-bottom:14px;">SIGN-IN CODE</div>
                  <h1 style="margin:0 0 12px;font-size:24px;line-height:1.3;font-weight:700;color:#0F172A;">Your {{platformName}} sign-in code</h1>
                  <p style="margin:0 0 24px;font-size:15px;line-height:1.6;color:#475569;">Enter this code to sign in. It expires in {{ttlMinutes}} minutes and can be used once.</p>
                  <div style="text-align:center;margin:0 0 24px;">
                    <span style="display:inline-block;font-size:34px;font-weight:700;letter-spacing:0.32em;color:#0F172A;background:#F1F5F9;border:1px solid #E2E8F0;border-radius:12px;padding:16px 28px;font-family:'SFMono-Regular',Menlo,Consolas,monospace;">{{code}}</span>
                  </div>
                  <p style="margin:0;font-size:12.5px;line-height:1.5;color:#94A3B8;">If you didn't try to sign in, you can safely ignore this email. Never share this code with anyone.</p>
                </div>
              </div>
            </body></html>
            """;
}
