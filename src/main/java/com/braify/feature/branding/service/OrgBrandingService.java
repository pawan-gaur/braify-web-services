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
    private final BrandingLogoStorage    logoStorage;

    @org.springframework.beans.factory.annotation.Value("${app.base-url:http://localhost:5173}")
    private String baseUrl;

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

        LogoResult logo = resolveLogo(orgId, existing, req);

        OrgBranding branding = OrgBranding.builder()
                .logoBase64(logo.base64())
                .logoUrl(logo.url())
                .logoBucket(logo.bucket())
                .logoKey(logo.key())
                .logoProvider(logo.provider())
                .logoContentType(logo.contentType())
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

    // ── Logo storage ────────────────────────────────────────────────────────────

    private record LogoResult(String base64, String url, String bucket, String key,
                              String provider, String contentType) {}
    private record DecodedImage(byte[] bytes, String contentType, String ext) {}

    /** Public logo bytes for the streaming endpoint (from cloud, else decoded base64), or null. */
    public record LogoData(byte[] bytes, String contentType) {}

    public LogoData getLogoData(String orgId) {
        Organization org = orgRepository.findById(orgId).orElse(null);
        if (org == null || org.getBranding() == null) return null;
        OrgBranding b = org.getBranding();
        if (b.getLogoKey() != null) {
            byte[] bytes = logoStorage.download(orgId, b.getLogoBucket(), b.getLogoKey());
            return new LogoData(bytes, b.getLogoContentType() != null ? b.getLogoContentType() : "image/png");
        }
        DecodedImage d = decodeDataUrl(b.getLogoBase64());
        return d != null ? new LogoData(d.bytes(), d.contentType()) : null;
    }

    private String logoEndpointUrl(String orgId) {
        // Stable endpoint + a version query param so a re-upload busts the browser / email-proxy
        // cache (the URL path is identical every time, otherwise the old image keeps showing).
        return baseUrl.replaceAll("/$", "") + "/api/public/branding/" + orgId + "/logo?v=" + System.currentTimeMillis();
    }

    /**
     * Decides the logo fields to persist:
     * <ul>
     *   <li>new data-URL → offload bytes to the org cloud bucket (fallback: keep inline base64),
     *       serve via the public endpoint;</li>
     *   <li>unchanged (client echoed {@code logoUrl}) → keep existing fields;</li>
     *   <li>nothing → remove the logo (best-effort delete of any cloud object).</li>
     * </ul>
     */
    private LogoResult resolveLogo(String orgId, OrgBranding existing, OrgBrandingRequest req) {
        String reqLogo = req.getLogoBase64();
        log.info("resolveLogo org={} newUpload={} logoUrl='{}'",
                orgId, reqLogo != null && reqLogo.startsWith("data:"), req.getLogoUrl());

        if (reqLogo != null && reqLogo.startsWith("data:")) {
            DecodedImage d = decodeDataUrl(reqLogo);
            if (d != null && logoStorage.isCloudConfigured(orgId)) {
                try {
                    if (existing != null && existing.getLogoKey() != null)
                        logoStorage.deleteQuietly(orgId, existing.getLogoBucket(), existing.getLogoKey());
                    var s = logoStorage.upload(orgId, d.bytes(), d.contentType(), d.ext());
                    return new LogoResult(null, logoEndpointUrl(orgId),
                            s.bucket(), s.key(), s.provider(), s.contentType());
                } catch (Exception e) {
                    log.warn("Logo cloud offload failed for org {} — keeping inline base64: {}", orgId, e.getMessage());
                }
            }
            // No cloud (or offload failed): keep the data URL, still served via the endpoint.
            return new LogoResult(reqLogo, logoEndpointUrl(orgId), null, null, null,
                    d != null ? d.contentType() : null);
        }

        String reqUrl = req.getLogoUrl() != null ? req.getLogoUrl().trim() : "";
        if (!reqUrl.isBlank()) {
            // Unchanged uploaded logo — client echoed our own endpoint URL back; keep as-is.
            if (existing != null && reqUrl.equals(existing.getLogoUrl())
                    && (existing.getLogoKey() != null || existing.getLogoBase64() != null)) {
                return new LogoResult(existing.getLogoBase64(), existing.getLogoUrl(),
                        existing.getLogoBucket(), existing.getLogoKey(),
                        existing.getLogoProvider(), existing.getLogoContentType());
            }
            // A directly-provided external image URL — store it as-is (no cloud upload).
            if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                if (existing != null && existing.getLogoKey() != null)   // drop any prior cloud object
                    logoStorage.deleteQuietly(orgId, existing.getLogoBucket(), existing.getLogoKey());
                log.info("Branding logo set to external URL for org {} -> {}", orgId, reqUrl);
                return new LogoResult(null, reqUrl, null, null, null, null);
            }
            throw new RuntimeException("Logo URL must start with http:// or https://");
        }

        // Removed.
        if (existing != null && existing.getLogoKey() != null)
            logoStorage.deleteQuietly(orgId, existing.getLogoBucket(), existing.getLogoKey());
        return new LogoResult(null, null, null, null, null, null);
    }

    private DecodedImage decodeDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return null;
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return null;
            String meta = dataUrl.substring(5, comma);                 // after "data:"
            String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            if (mime.isBlank()) mime = "image/png";
            String ext = mime.contains("/") ? mime.substring(mime.indexOf('/') + 1) : "png";
            if (ext.equalsIgnoreCase("svg+xml")) ext = "svg";
            byte[] bytes = java.util.Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            return new DecodedImage(bytes, mime, ext);
        } catch (Exception e) {
            log.warn("Could not decode logo data URL: {}", e.getMessage());
            return null;
        }
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
                (b.getLogoUrl()        != null && !b.getLogoUrl().isBlank())         ||
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
                .logoUrl(b != null ? b.getLogoUrl() : null)
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
