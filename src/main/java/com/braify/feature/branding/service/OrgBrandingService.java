package com.braify.feature.branding.service;

import com.braify.feature.branding.dto.OrgBrandingRequest;
import com.braify.feature.branding.dto.OrgBrandingResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.branding.model.OrgBranding;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.*;
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

        // Validate colour formats
        validateHexColor(req.getPrimaryColor(), "Primary colour");
        validateHexColor(req.getAccentColor(),  "Accent colour");

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

        // Normalise featureRoleAccess — ORG_ADMIN must always be present in every feature list
        Map<String, List<String>> normalised = normaliseFeatureRoleAccess(req.getFeatureRoleAccess());

        Organization org = findOrg(orgId);

        // Preserve the original creator's ID — only set on first save
        OrgBranding existing  = org.getBranding();
        String      createdBy = (existing != null && existing.getCreatedBy() != null)
                ? existing.getCreatedBy()
                : caller.getId();

        OrgBranding branding = OrgBranding.builder()
                .logoBase64(req.getLogoBase64())
                .primaryColor(req.getPrimaryColor())
                .accentColor(req.getAccentColor())
                .emailSenderName(req.getEmailSenderName())
                .emailReplyTo(req.getEmailReplyTo())
                .footerText(req.getFooterText())
                .featureRoleAccess(normalised)
                .createdBy(createdBy)
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

    private void validateHexColor(String color, String fieldName) {
        if (color != null && !color.isBlank()) {
            if (!HEX_COLOR.matcher(color).matches()) {
                throw new RuntimeException(
                        "Invalid " + fieldName + " '" + color + "'. Must be a 6-digit hex colour, e.g. #1a73e8");
            }
        }
    }

    /**
     * Ensures every feature list in the map contains "ORG_ADMIN".
     * Returns null if the input map is null (no restrictions configured).
     */
    private Map<String, List<String>> normaliseFeatureRoleAccess(Map<String, List<String>> raw) {
        if (raw == null || raw.isEmpty()) return null;

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            List<String> roles = new ArrayList<>(entry.getValue() != null ? entry.getValue() : List.of());
            if (!roles.contains("ORG_ADMIN")) {
                roles.add(0, "ORG_ADMIN"); // always prepend ORG_ADMIN
            }
            result.put(entry.getKey(), Collections.unmodifiableList(roles));
        }
        return result;
    }

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

    public OrgBrandingResponse toResponse(Organization org) {
        OrgBranding b = org.getBranding();
        boolean configured = b != null && (
                (b.getLogoBase64()     != null && !b.getLogoBase64().isBlank())     ||
                (b.getPrimaryColor()   != null && !b.getPrimaryColor().isBlank())   ||
                (b.getAccentColor()    != null && !b.getAccentColor().isBlank())    ||
                (b.getEmailSenderName()!= null && !b.getEmailSenderName().isBlank())||
                (b.getEmailReplyTo()   != null && !b.getEmailReplyTo().isBlank())   ||
                (b.getFooterText()     != null && !b.getFooterText().isBlank())     ||
                (b.getFeatureRoleAccess() != null && !b.getFeatureRoleAccess().isEmpty()));

        return OrgBrandingResponse.builder()
                .organizationId(org.getId())
                .organizationName(org.getName())
                .logoBase64(b != null ? b.getLogoBase64() : null)
                .primaryColor(b != null ? b.getPrimaryColor() : null)
                .accentColor(b != null ? b.getAccentColor() : null)
                .emailSenderName(b != null ? b.getEmailSenderName() : null)
                .emailReplyTo(b != null ? b.getEmailReplyTo() : null)
                .footerText(b != null ? b.getFooterText() : null)
                .featureRoleAccess(b != null ? b.getFeatureRoleAccess() : null)
                .configured(configured)
                .build();
    }
}
