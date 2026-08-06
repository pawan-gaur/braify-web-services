package com.braify.feature.emailconfig.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.emailconfig.dto.OrgEmailConfigRequest;
import com.braify.feature.emailconfig.dto.OrgEmailConfigResponse;
import com.braify.feature.emailconfig.model.OrgEmailConfig;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the per-organisation outbound email provider configuration.
 *
 * <p>Secrets are encrypted with AES-256-GCM before persistence and masked on read.
 * When an org has no config (or an incomplete one), sends fall back to the
 * platform-admin default and finally to the built-in Resend credentials — the
 * resolution happens in {@code EmailConfigResolver}, not here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgEmailConfigService {

    private final OrganizationRepository orgRepository;
    private final AuditLogService        auditLogService;
    private final EmailConfigMapper      mapper;
    private final EmailDispatcher        emailDispatcher;

    // ── Read ─────────────────────────────────────────────────────────────────

    public OrgEmailConfigResponse getEmailConfig(String orgId, AppUser caller) {
        assertAccess(caller, orgId);
        return mapper.toResponse(requireOrg(orgId).getEmailConfig());
    }

    // ── Write ────────────────────────────────────────────────────────────────

    public OrgEmailConfigResponse updateEmailConfig(String orgId,
                                                    OrgEmailConfigRequest req,
                                                    AppUser caller) {
        assertAccess(caller, orgId);
        Organization org = requireOrg(orgId);

        OrgEmailConfig updated = mapper.applyRequest(req, org.getEmailConfig(), caller.getId());
        org.setEmailConfig(updated);
        orgRepository.save(org);

        auditLogService.log(
                org.getId(), org.getName(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.ORGANIZATION,
                0,
                Map.of("action", "EMAIL_CONFIG_UPDATED",
                       "provider", updated.getProvider() != null ? updated.getProvider().name() : "none"),
                caller.getEmail(),
                orgId
        );
        log.info("Email config updated for org '{}' by '{}'", orgId, caller.getEmail());
        return mapper.toResponse(updated);
    }

    // ── Send test email ──────────────────────────────────────────────────────

    /**
     * Sends a probe email to {@code req.testRecipient} using the org's effective
     * (resolved) email configuration, proving the credentials actually deliver.
     */
    public Map<String, Object> sendTest(String orgId, OrgEmailConfigRequest req, AppUser caller) {
        assertAccess(caller, orgId);
        String recipient = req != null ? trimOrNull(req.getTestRecipient()) : null;
        if (recipient == null) {
            return result(false, "A test recipient email address is required.");
        }
        try {
            var res = emailDispatcher.sendHtmlEmail(
                    orgId, recipient, "Braify — email configuration test",
                    probeHtml(), null);
            log.info("Email config test sent for org '{}' to '{}' (id={})",
                    orgId, recipient, res != null ? res.getId() : null);
            return result(true, "Test email sent to " + recipient + ".");
        } catch (Exception ex) {
            log.warn("Email config test FAILED for org '{}': {}", orgId, ex.getMessage());
            return result(false, "Test failed: " + ex.getMessage());
        }
    }

    public static String probeHtml() {
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#0f172a\">"
             + "<p>This is a test email from <strong>Braify</strong>.</p>"
             + "<p>If you received this, your email provider configuration is working.</p>"
             + "</div>";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Organization requireOrg(String orgId) {
        return orgRepository.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + orgId));
    }

    private void assertAccess(AppUser caller, String orgId) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!orgId.equals(caller.getOrganizationId())) {
            throw new AccessDeniedException("You can only manage your own organisation's email config");
        }
    }

    private static Map<String, Object> result(boolean success, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("message", message);
        return m;
    }

    private static String trimOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        return v.trim();
    }
}
