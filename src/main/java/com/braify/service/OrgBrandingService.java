package com.braify.service;

import com.braify.dto.OrgBrandingRequest;
import com.braify.dto.OrgBrandingResponse;
import com.braify.model.AppUser;
import com.braify.model.AuditLog;
import com.braify.model.OrgBranding;
import com.braify.model.Organization;
import com.braify.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrgBrandingService {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final OrganizationRepository orgRepository;
    private final AuditLogService        auditLogService;

    // ── Read ─────────────────────────────────────────────────────────────────

    public OrgBrandingResponse getBranding(String orgId, AppUser caller) {
        assertAccess(orgId, caller);
        Organization org = findOrg(orgId);
        return toResponse(org);
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public OrgBrandingResponse updateBranding(String orgId, OrgBrandingRequest req, AppUser caller) {
        assertAccess(orgId, caller);

        // Validate colour format
        if (req.getPrimaryColor() != null && !req.getPrimaryColor().isBlank()) {
            if (!HEX_COLOR.matcher(req.getPrimaryColor()).matches()) {
                throw new RuntimeException(
                        "Invalid primary colour '" + req.getPrimaryColor() + "'. Must be a 6-digit hex colour, e.g. #1a73e8");
            }
        }

        // Validate email reply-to format (basic)
        if (req.getEmailReplyTo() != null && !req.getEmailReplyTo().isBlank()) {
            if (!req.getEmailReplyTo().contains("@")) {
                throw new RuntimeException("Invalid reply-to email address: " + req.getEmailReplyTo());
            }
        }

        // Validate footer text length
        if (req.getFooterText() != null && req.getFooterText().length() > 500) {
            throw new RuntimeException("Footer text must not exceed 500 characters.");
        }

        Organization org = findOrg(orgId);

        OrgBranding branding = OrgBranding.builder()
                .logoBase64(req.getLogoBase64())
                .primaryColor(req.getPrimaryColor())
                .emailSenderName(req.getEmailSenderName())
                .emailReplyTo(req.getEmailReplyTo())
                .footerText(req.getFooterText())
                .build();

        org.setBranding(branding);
        orgRepository.save(org);
        log.info("Updated branding for org '{}' by '{}'", org.getName(), caller.getEmail());

        auditLogService.log(
                org.getId(), org.getName(),
                AuditLog.Action.BRANDING_UPDATED, AuditLog.ResourceType.ORGANIZATION,
                0, null,
                caller.getEmail(),
                org.getId());

        return toResponse(org);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertAccess(String orgId, AppUser caller) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!orgId.equals(caller.getOrganizationId())) {
            throw new AccessDeniedException("You can only manage your own organisation's branding.");
        }
    }

    private Organization findOrg(String orgId) {
        return orgRepository.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Organization not found: " + orgId));
    }

    private OrgBrandingResponse toResponse(Organization org) {
        OrgBranding b = org.getBranding();
        boolean configured = b != null && (
                (b.getLogoBase64()     != null && !b.getLogoBase64().isBlank())     ||
                (b.getPrimaryColor()   != null && !b.getPrimaryColor().isBlank())   ||
                (b.getEmailSenderName()!= null && !b.getEmailSenderName().isBlank())||
                (b.getEmailReplyTo()   != null && !b.getEmailReplyTo().isBlank())   ||
                (b.getFooterText()     != null && !b.getFooterText().isBlank()));

        return OrgBrandingResponse.builder()
                .organizationId(org.getId())
                .organizationName(org.getName())
                .logoBase64(b != null ? b.getLogoBase64() : null)
                .primaryColor(b != null ? b.getPrimaryColor() : null)
                .emailSenderName(b != null ? b.getEmailSenderName() : null)
                .emailReplyTo(b != null ? b.getEmailReplyTo() : null)
                .footerText(b != null ? b.getFooterText() : null)
                .configured(configured)
                .build();
    }
}
