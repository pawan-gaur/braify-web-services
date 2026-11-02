package com.braify.feature.branding.service;

import com.braify.feature.storage.model.StoragePurpose;
import com.braify.feature.storage.model.StoredFile;
import com.braify.feature.storage.service.StorageService;
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
    private final StorageService         storage;

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

        LogoResult logo = resolveLogo(orgId, existing, req, caller);

        OrgBranding kept = logo.legacy();   // an unchanged logo still stored the old way keeps its fields
        OrgBranding branding = OrgBranding.builder()
                .logoFileId(logo.fileId())
                .logoUrl(logo.url())
                .logoBase64(kept != null ? kept.getLogoBase64() : null)
                .logoBucket(kept != null ? kept.getLogoBucket() : null)
                .logoKey(kept != null ? kept.getLogoKey() : null)
                .logoProvider(kept != null ? kept.getLogoProvider() : null)
                .logoContentType(kept != null ? kept.getLogoContentType() : null)
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

    // ── Logo storage ──────────────────────────────────────────────────────────

    /** {@code legacy}: the existing branding when its old-style logo (base64 / raw key) is kept unchanged. */
    private record LogoResult(String fileId, String url, OrgBranding legacy) {
        static final LogoResult NONE = new LogoResult(null, null, null);
        LogoResult(String fileId, String url) { this(fileId, url, null); }
    }

    /** Public logo bytes for the streaming endpoint, or null when the org has no stored logo. */
    public record LogoData(byte[] bytes, String contentType) {}

    public LogoData getLogoData(String orgId) {
        Organization org = findOrg(orgId);
        OrgBranding b = org.getBranding();
        if (b == null) return null;
        if (b.getLogoFileId() != null) {
            StoredFile f = storage.get(b.getLogoFileId());
            return new LogoData(storage.read(f), f.getContentType());
        }
        if (b.getLogoKey() != null) {   // LEGACY — until LegacyFileMigration runs
            byte[] bytes = storage.read(legacyLogo(orgId, b));
            return new LogoData(bytes, b.getLogoContentType() != null ? b.getLogoContentType() : "image/png");
        }
        DecodedImage d = decodeDataUrl(b.getLogoBase64());   // LEGACY inline logo
        return d != null ? new LogoData(d.bytes(), d.contentType()) : null;
    }

    private String logoEndpointUrl(String orgId) {
        // Absolute URL so it works inside emails. Cache-busted so a new upload shows immediately.
        return baseUrl.replaceAll("/$", "") + "/api/public/branding/" + orgId + "/logo?v=" + System.currentTimeMillis();
    }

    /**
     * Decides the logo to keep:
     * <ul>
     *   <li>new upload ({@code logoFileId} from {@code POST /api/storage/upload?purpose=BRANDING_LOGO}) → adopt it;</li>
     *   <li>unchanged (client echoed {@code logoUrl}) → keep the existing logo;</li>
     *   <li>an external http(s) URL → use it as-is;</li>
     *   <li>nothing → remove the logo.</li>
     * </ul>
     * A replaced or removed stored logo is deleted from storage.
     */
    private LogoResult resolveLogo(String orgId, OrgBranding existing, OrgBrandingRequest req, AppUser caller) {
        String reqFileId = req.getLogoFileId() != null ? req.getLogoFileId().trim() : "";
        String reqUrl    = req.getLogoUrl()    != null ? req.getLogoUrl().trim()    : "";
        log.info("resolveLogo org={} newFileId='{}' logoUrl='{}'", orgId, reqFileId, reqUrl);

        // Older clients still send a data URL — store it like a new upload (never kept in Mongo).
        DecodedImage legacyUpload = decodeDataUrl(req.getLogoBase64());
        if (reqFileId.isEmpty() && legacyUpload != null) {
            String fileId = storage.store(orgId, StoragePurpose.BRANDING_LOGO, legacyUpload.bytes(), "logo." + legacyUpload.ext(),
                    legacyUpload.contentType(), caller.getId(), "branding-logo:" + orgId).getId();
            deleteStoredLogo(orgId, existing);
            return new LogoResult(fileId, logoEndpointUrl(orgId));
        }

        if (!reqFileId.isEmpty()) {
            if (existing != null && reqFileId.equals(existing.getLogoFileId()))
                return new LogoResult(existing.getLogoFileId(), existing.getLogoUrl());
            StoredFile upload = storage.getForUser(reqFileId, StoragePurpose.BRANDING_LOGO, caller);
            if (!orgId.equals(upload.getOrganizationId()))
                throw new IllegalArgumentException("The logo file belongs to another organisation");
            String fileId = storage.claim(upload, "branding-logo:" + orgId).getId();
            deleteStoredLogo(orgId, existing);
            return new LogoResult(fileId, logoEndpointUrl(orgId));
        }

        if (!reqUrl.isEmpty()) {
            // Unchanged logo — client echoed our own endpoint URL back; keep as-is.
            if (existing != null && hasStoredLogo(existing)
                    && (reqUrl.equals(existing.getLogoUrl()) || isOwnLogoEndpoint(reqUrl, orgId)))
                return new LogoResult(existing.getLogoFileId(),
                        existing.getLogoUrl() != null ? existing.getLogoUrl() : logoEndpointUrl(orgId),
                        existing.getLogoFileId() == null ? existing : null);
            if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                deleteStoredLogo(orgId, existing);
                log.info("Branding logo set to external URL for org {} -> {}", orgId, reqUrl);
                return new LogoResult(null, reqUrl);
            }
            throw new IllegalArgumentException("Logo URL must start with http:// or https://");
        }

        deleteStoredLogo(orgId, existing);
        return LogoResult.NONE;
    }

    private static boolean isOwnLogoEndpoint(String url, String orgId) {
        return url.contains("/api/public/branding/" + orgId + "/logo");
    }

    private static boolean hasStoredLogo(OrgBranding b) {
        return b.getLogoFileId() != null || b.getLogoKey() != null || b.getLogoBase64() != null;
    }

    private void deleteStoredLogo(String orgId, OrgBranding existing) {
        if (existing == null) return;
        if (existing.getLogoFileId() != null) storage.deleteQuietly(existing.getLogoFileId());
        else if (existing.getLogoKey() != null) storage.deleteObjectQuietly(legacyLogo(orgId, existing));
    }

    /** Unsaved reference to a pre-registry logo object, routed by its bucket. */
    private static StoredFile legacyLogo(String orgId, OrgBranding b) {
        return StoredFile.builder().organizationId(orgId).bucket(b.getLogoBucket()).storageKey(b.getLogoKey()).build();
    }

    private record DecodedImage(byte[] bytes, String contentType, String ext) {}

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
                .logoUrl(b == null ? null
                        : b.getLogoUrl() != null ? b.getLogoUrl()
                        : hasStoredLogo(b) ? logoEndpointUrl(org.getId())   // old rows saved before logoUrl existed
                        : null)
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
