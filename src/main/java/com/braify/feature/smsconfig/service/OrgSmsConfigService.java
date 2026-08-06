package com.braify.feature.smsconfig.service;

import com.braify.config.infra.sms.SmsDispatcher;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.smsconfig.dto.OrgSmsConfigRequest;
import com.braify.feature.smsconfig.dto.OrgSmsConfigResponse;
import com.braify.feature.smsconfig.model.OrgSmsConfig;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Manages the per-organisation outbound SMS provider configuration. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgSmsConfigService {

    private final OrganizationRepository orgRepository;
    private final AuditLogService        auditLogService;
    private final SmsConfigMapper        mapper;
    private final SmsDispatcher          smsDispatcher;

    public OrgSmsConfigResponse getSmsConfig(String orgId, AppUser caller) {
        assertAccess(caller, orgId);
        return mapper.toResponse(requireOrg(orgId).getSmsConfig());
    }

    public OrgSmsConfigResponse updateSmsConfig(String orgId, OrgSmsConfigRequest req, AppUser caller) {
        assertAccess(caller, orgId);
        Organization org = requireOrg(orgId);

        OrgSmsConfig updated = mapper.applyRequest(req, org.getSmsConfig(), caller.getId());
        org.setSmsConfig(updated);
        orgRepository.save(org);

        auditLogService.log(
                org.getId(), org.getName(),
                AuditLog.Action.UPDATED, AuditLog.ResourceType.ORGANIZATION,
                0,
                Map.of("action", "SMS_CONFIG_UPDATED",
                       "provider", updated.getProvider() != null ? updated.getProvider().name() : "none"),
                caller.getEmail(),
                orgId
        );
        log.info("SMS config updated for org '{}' by '{}'", orgId, caller.getEmail());
        return mapper.toResponse(updated);
    }

    public Map<String, Object> sendTest(String orgId, OrgSmsConfigRequest req, AppUser caller) {
        assertAccess(caller, orgId);
        String recipient = req != null ? trimOrNull(req.getTestRecipient()) : null;
        if (recipient == null) {
            return result(false, "A test recipient phone number is required.");
        }
        try {
            var res = smsDispatcher.sendSms(orgId, recipient,
                    "Braify SMS configuration test — your provider is working.");
            log.info("SMS config test sent for org '{}' to '{}' (id={})",
                    orgId, recipient, res != null ? res.getId() : null);
            return result(true, "Test SMS sent to " + recipient + ".");
        } catch (Exception ex) {
            log.warn("SMS config test FAILED for org '{}': {}", orgId, ex.getMessage());
            return result(false, "Test failed: " + ex.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Organization requireOrg(String orgId) {
        return orgRepository.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + orgId));
    }

    private void assertAccess(AppUser caller, String orgId) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!orgId.equals(caller.getOrganizationId())) {
            throw new AccessDeniedException("You can only manage your own organisation's SMS config");
        }
    }

    private static Map<String, Object> result(boolean success, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", success);
        m.put("message", message);
        return m;
    }

    private static String trimOrNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
