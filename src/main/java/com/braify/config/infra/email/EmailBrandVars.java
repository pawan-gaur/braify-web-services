package com.braify.config.infra.email;

import com.braify.feature.branding.model.OrgBranding;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the shared brand tokens used by system email templates (logo, accent colours,
 * footer contact line). Kept email-client-safe: a hosted http(s) logo becomes an
 * {@code <img>}, otherwise a coloured initial badge (Gmail blocks {@code data:} images).
 */
@Service
@RequiredArgsConstructor
public class EmailBrandVars {

    private final OrganizationRepository orgRepository;

    /**
     * @return map with {@code organizationName, brandMark, accent, accentSoft, accentBorder, footerContact}.
     */
    public Map<String, Object> forOrg(String orgId, String orgName) {
        OrgBranding b = loadBranding(orgId);
        String accent  = (b != null && notBlank(b.getPrimaryColor())) ? b.getPrimaryColor().trim() : "#4F46E5";
        String logo    = (b != null) ? b.getLogoUrl() : null;
        String initial = (orgName != null && !orgName.isBlank()) ? orgName.trim().substring(0, 1).toUpperCase() : "B";

        boolean hostedLogo = notBlank(logo) && (logo.startsWith("http://") || logo.startsWith("https://"));
        String brandMark = hostedLogo
                ? "<img src=\"" + logo + "\" alt=\"\" width=\"34\" height=\"34\" style=\"width:34px;height:34px;border-radius:8px;object-fit:contain;display:block;\">"
                : "<div style=\"width:34px;height:34px;line-height:34px;border-radius:8px;background:" + accent + ";color:#fff;text-align:center;font-weight:700;font-size:16px;\">" + initial + "</div>";

        Map<String, Object> m = new HashMap<>();
        m.put("organizationName", orgName != null ? orgName : "");
        m.put("orgName",          orgName != null ? orgName : "");
        m.put("brandMark",        brandMark);
        m.put("accent",           accent);
        m.put("accentSoft",       hexToRgba(accent, 0.10));
        m.put("accentBorder",     hexToRgba(accent, 0.55));
        m.put("footerContact",    buildFooterContact(b, accent));
        return m;
    }

    private OrgBranding loadBranding(String orgId) {
        if (orgId == null || orgId.isBlank()) return null;
        return orgRepository.findById(orgId).map(Organization::getBranding).orElse(null);
    }

    private String buildFooterContact(OrgBranding b, String accent) {
        String support = (b != null) ? b.getEmailReplyTo() : null;
        String address = (b != null) ? b.getFooterText()   : null;
        boolean hasS = notBlank(support), hasA = notBlank(address);
        if (!hasS && !hasA) return "";
        StringBuilder sb = new StringBuilder("<div style=\"font-size:11.5px;line-height:1.7;color:#94A3B8;\">");
        if (hasS) sb.append("<span>Need help? <a href=\"mailto:").append(support)
                    .append("\" style=\"color:").append(accent).append(";font-weight:600;text-decoration:none;\">")
                    .append(support).append("</a></span>");
        if (hasS && hasA) sb.append("<span style=\"color:#CBD5E1;\"> · </span>");
        if (hasA) sb.append("<span>").append(address).append("</span>");
        return sb.append("</div>").toString();
    }

    private String hexToRgba(String hex, double alpha) {
        try {
            String h = (hex == null ? "#4F46E5" : hex.trim()).replace("#", "");
            if (h.length() == 3) {
                StringBuilder e = new StringBuilder();
                for (char c : h.toCharArray()) e.append(c).append(c);
                h = e.toString();
            }
            int n = Integer.parseInt(h, 16);
            return "rgba(" + ((n >> 16) & 255) + ", " + ((n >> 8) & 255) + ", " + (n & 255) + ", " + alpha + ")";
        } catch (Exception e) {
            return "rgba(79, 70, 229, " + alpha + ")";
        }
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
