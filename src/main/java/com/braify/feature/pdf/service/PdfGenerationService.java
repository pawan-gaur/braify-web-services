package com.braify.feature.pdf.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.braify.feature.branding.model.OrgBranding;
import com.braify.feature.quota.service.QuotaService;
import com.braify.feature.pdf.model.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGenerationService {

    private final PlaceholderService placeholderService;
    private final QuotaService       quotaService;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a PDF for the given template with data substitution.
     * Enforces the org's monthly document quota before rendering.
     *
     * @param template the PDF template (must include organizationId)
     * @param data     placeholder substitution map
     * @param branding optional org branding (logo, colour, footer); null = no branding
     */
    public byte[] generate(Template template, Map<String, Object> data, OrgBranding branding) throws Exception {
        // Enforce monthly document quota
        quotaService.checkAndIncrementDocs(template.getOrganizationId());

        String html     = placeholderService.replacePlaceholders(template.getHtmlContent(), data);
        String fullHtml = buildHtmlDocument(html, template, branding);

        log.debug("Generating PDF for template: {}", template.getId());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useSVGDrawer(new BatikSVGDrawer());
            builder.withHtmlContent(fullHtml, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        }
    }

    /**
     * Backward-compatible overload — no branding applied.
     * Used by preview endpoints and any caller that hasn't migrated yet.
     */
    public byte[] generate(Template template, Map<String, Object> data) throws Exception {
        return generate(template, data, null);
    }

    // ── HTML document builder ─────────────────────────────────────────────────

    private String buildHtmlDocument(String bodyHtml, Template template, OrgBranding branding) {
        String css         = template.getCssContent() != null ? template.getCssContent() : "";
        String pageSize    = template.getPageSize() != null ? template.getPageSize() : "A4";
        String orientation = "landscape".equalsIgnoreCase(template.getOrientation()) ? " landscape" : "";

        int mt = template.getMarginTop()    != null ? template.getMarginTop()    : 20;
        int mb = template.getMarginBottom() != null ? template.getMarginBottom() : 20;
        int ml = template.getMarginLeft()   != null ? template.getMarginLeft()   : 15;
        int mr = template.getMarginRight()  != null ? template.getMarginRight()  : 15;

        // ── Branding extras ──────────────────────────────────────────────────
        String brandColorVar = "";
        String headerHtml    = "";
        String footerHtml    = "";

        if (branding != null) {
            if (branding.getPrimaryColor() != null && !branding.getPrimaryColor().isBlank()) {
                brandColorVar = "--brand-color: " + branding.getPrimaryColor() + ";";
            }
            if (branding.getLogoBase64() != null && !branding.getLogoBase64().isBlank()) {
                headerHtml = """
                        <div style="text-align:left;margin-bottom:12px;">
                          <img src="%s" alt="Logo" style="max-height:48px;max-width:200px;"/>
                        </div>
                        """.formatted(branding.getLogoBase64());
            }
            if (branding.getFooterText() != null && !branding.getFooterText().isBlank()) {
                footerHtml = """
                        <div style="margin-top:24px;padding-top:8px;border-top:1px solid #e5e7eb;
                                    font-size:9px;color:#9ca3af;">
                          %s
                        </div>
                        """.formatted(escapeHtml(branding.getFooterText()));
            }
        }

        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8"/>
                <style>
                :root { %s }
                @page {
                  size: %s%s;
                  margin: %dmm %dmm %dmm %dmm;
                }
                * { box-sizing: border-box; }
                body {
                  font-family: Arial, Helvetica, sans-serif;
                  font-size: 12px;
                  margin: 0;
                  padding: 0;
                }
                table { border-collapse: collapse; width: 100%%; }
                th, td { border: 1px solid #ddd; padding: 6px 10px; }
                th { background-color: #f2f2f2; font-weight: bold; }
                img { max-width: 100%%; }
                .dynamic-field { color: inherit; }
                %s
                </style>
                </head>
                <body>
                %s
                %s
                %s
                </body>
                </html>
                """.formatted(
                brandColorVar,
                pageSize, orientation, mt, mr, mb, ml,
                css,
                headerHtml,
                bodyHtml,
                footerHtml);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
