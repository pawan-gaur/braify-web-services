package com.braify.feature.platform.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.config.infra.sms.SmsDispatcher;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.cloudconfig.dto.OrgCloudConfigRequest;
import com.braify.feature.cloudconfig.dto.OrgCloudConfigResponse;
import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.cloudconfig.service.CloudConfigMapper;
import com.braify.feature.emailconfig.dto.OrgEmailConfigRequest;
import com.braify.feature.emailconfig.dto.OrgEmailConfigResponse;
import com.braify.feature.emailconfig.model.OrgEmailConfig;
import com.braify.feature.emailconfig.service.EmailConfigMapper;
import com.braify.feature.emailconfig.service.OrgEmailConfigService;
import com.braify.feature.platform.model.PlatformProviderDefaults;
import com.braify.feature.platform.repository.PlatformProviderDefaultsRepository;
import com.braify.feature.smsconfig.dto.OrgSmsConfigRequest;
import com.braify.feature.smsconfig.dto.OrgSmsConfigResponse;
import com.braify.feature.smsconfig.model.OrgSmsConfig;
import com.braify.feature.smsconfig.service.SmsConfigMapper;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes the single platform-wide provider-defaults document.
 * Only PLATFORM_ADMIN may mutate (enforced at the controller). Organisations
 * without their own provider config fall back to these defaults.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformProviderDefaultsService {

    private final PlatformProviderDefaultsRepository repo;
    private final AuditLogService                    auditLogService;
    private final EmailConfigMapper                  emailMapper;
    private final EmailDispatcher                    emailDispatcher;
    private final SmsConfigMapper                    smsMapper;
    private final SmsDispatcher                      smsDispatcher;
    private final CloudConfigMapper                  cloudMapper;

    @Value("${resend.api-key:}")
    private String envResendApiKey;

    @Value("${resend.from-email:}")
    private String envResendFromEmail;

    /** Lazily creates the singleton document on first access. */
    public PlatformProviderDefaults getOrCreate() {
        return repo.findById(PlatformProviderDefaults.SINGLETON_ID)
                .orElseGet(() -> repo.save(PlatformProviderDefaults.builder()
                        .id(PlatformProviderDefaults.SINGLETON_ID)
                        .build()));
    }

    // ── Email default ─────────────────────────────────────────────────────────

    /** Raw (still-encrypted) email default for the resolver. Null when unset. */
    public OrgEmailConfig getEmailDefaultRaw() {
        return getOrCreate().getEmail();
    }

    public OrgEmailConfigResponse getEmailConfig() {
        PlatformProviderDefaults defaults = getOrCreate();
        return withEnvFallback(emailMapper.toResponse(defaults.getEmail()), defaults);
    }

    public OrgEmailConfigResponse updateEmailConfig(OrgEmailConfigRequest req, AppUser caller) {
        PlatformProviderDefaults defaults = getOrCreate();
        OrgEmailConfig updated = emailMapper.applyRequest(
                req, defaults.getEmail(), caller != null ? caller.getId() : "system");
        defaults.setEmail(updated);
        if (req.getEnvFallbackEnabled() != null) {
            defaults.setEmailEnvFallbackEnabled(req.getEnvFallbackEnabled());
        }
        defaults.setUpdatedBy(caller != null ? caller.getEmail() : "system");
        repo.save(defaults);

        if (caller != null) {
            auditLogService.logByUser(
                    PlatformProviderDefaults.SINGLETON_ID, "Platform email provider default",
                    AuditLog.Action.PLATFORM_SETTINGS_UPDATED, AuditLog.ResourceType.PLATFORM,
                    0,
                    Map.of("action", "PLATFORM_EMAIL_DEFAULT_UPDATED",
                           "provider", updated.getProvider() != null ? updated.getProvider().name() : "none",
                           "envFallbackEnabled", String.valueOf(
                                   defaults.getEmailEnvFallbackEnabled() == null || defaults.getEmailEnvFallbackEnabled())),
                    caller);
        }
        log.info("Platform email default updated by '{}'", caller != null ? caller.getEmail() : "system");
        return withEnvFallback(emailMapper.toResponse(updated), defaults);
    }

    /** Adds the platform-only env-fallback status fields to an email config response. */
    private OrgEmailConfigResponse withEnvFallback(OrgEmailConfigResponse resp, PlatformProviderDefaults defaults) {
        boolean enabled = defaults.getEmailEnvFallbackEnabled() == null || defaults.getEmailEnvFallbackEnabled();
        boolean available = envResendApiKey != null && !envResendApiKey.isBlank()
                && envResendFromEmail != null && !envResendFromEmail.isBlank();
        resp.setEnvFallbackEnabled(enabled);
        resp.setEnvFallbackAvailable(available);
        return resp;
    }

    /** Sends a probe email using the platform default (orgId = null resolves to it). */
    public Map<String, Object> sendEmailTest(OrgEmailConfigRequest req, AppUser caller) {
        String recipient = req != null && req.getTestRecipient() != null && !req.getTestRecipient().isBlank()
                ? req.getTestRecipient().trim() : null;
        if (recipient == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", false);
            m.put("message", "A test recipient email address is required.");
            return m;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            var res = emailDispatcher.sendHtmlEmail(
                    null, recipient, "Braify — platform email default test",
                    OrgEmailConfigService.probeHtml(), null);
            log.info("Platform email default test sent to '{}' (id={})",
                    recipient, res != null ? res.getId() : null);
            m.put("success", true);
            m.put("message", "Test email sent to " + recipient + ".");
        } catch (Exception ex) {
            log.warn("Platform email default test FAILED: {}", ex.getMessage());
            m.put("success", false);
            m.put("message", "Test failed: " + ex.getMessage());
        }
        return m;
    }

    // ── SMS default ─────────────────────────────────────────────────────────────

    public OrgSmsConfigResponse getSmsConfig() {
        return smsMapper.toResponse(getOrCreate().getSms());
    }

    public OrgSmsConfigResponse updateSmsConfig(OrgSmsConfigRequest req, AppUser caller) {
        PlatformProviderDefaults defaults = getOrCreate();
        OrgSmsConfig updated = smsMapper.applyRequest(
                req, defaults.getSms(), caller != null ? caller.getId() : "system");
        defaults.setSms(updated);
        defaults.setUpdatedBy(caller != null ? caller.getEmail() : "system");
        repo.save(defaults);

        if (caller != null) {
            auditLogService.logByUser(
                    PlatformProviderDefaults.SINGLETON_ID, "Platform SMS provider default",
                    AuditLog.Action.PLATFORM_SETTINGS_UPDATED, AuditLog.ResourceType.PLATFORM,
                    0,
                    Map.of("action", "PLATFORM_SMS_DEFAULT_UPDATED",
                           "provider", updated.getProvider() != null ? updated.getProvider().name() : "none"),
                    caller);
        }
        log.info("Platform SMS default updated by '{}'", caller != null ? caller.getEmail() : "system");
        return smsMapper.toResponse(updated);
    }

    public Map<String, Object> sendSmsTest(OrgSmsConfigRequest req, AppUser caller) {
        String recipient = req != null && req.getTestRecipient() != null && !req.getTestRecipient().isBlank()
                ? req.getTestRecipient().trim() : null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (recipient == null) {
            m.put("success", false);
            m.put("message", "A test recipient phone number is required.");
            return m;
        }
        try {
            var res = smsDispatcher.sendSms(null, recipient,
                    "Braify platform SMS default test — your provider is working.");
            log.info("Platform SMS default test sent to '{}' (id={})",
                    recipient, res != null ? res.getId() : null);
            m.put("success", true);
            m.put("message", "Test SMS sent to " + recipient + ".");
        } catch (Exception ex) {
            log.warn("Platform SMS default test FAILED: {}", ex.getMessage());
            m.put("success", false);
            m.put("message", "Test failed: " + ex.getMessage());
        }
        return m;
    }

    // ── Cloud default ───────────────────────────────────────────────────────────

    public OrgCloudConfigResponse getCloudConfig() {
        return cloudMapper.toResponse(getOrCreate().getCloud());
    }

    public OrgCloudConfigResponse updateCloudConfig(OrgCloudConfigRequest req, AppUser caller) {
        PlatformProviderDefaults defaults = getOrCreate();
        OrgCloudConfig updated = cloudMapper.applyRequest(
                req, defaults.getCloud(), caller != null ? caller.getId() : "system");
        defaults.setCloud(updated);
        defaults.setUpdatedBy(caller != null ? caller.getEmail() : "system");
        repo.save(defaults);

        if (caller != null) {
            auditLogService.logByUser(
                    PlatformProviderDefaults.SINGLETON_ID, "Platform cloud provider default",
                    AuditLog.Action.PLATFORM_SETTINGS_UPDATED, AuditLog.ResourceType.PLATFORM,
                    0,
                    Map.of("action", "PLATFORM_CLOUD_DEFAULT_UPDATED",
                           "cloud", updated.getCloud() != null ? updated.getCloud().name() : "none"),
                    caller);
        }
        log.info("Platform cloud default updated by '{}'", caller != null ? caller.getEmail() : "system");
        return cloudMapper.toResponse(updated);
    }
}
