package com.braify.feature.pdf.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.braify.feature.branding.model.OrgBranding;
import com.braify.feature.quota.service.QuotaService;
import com.braify.feature.pdf.model.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGenerationService {

    private final PlaceholderService placeholderService;
    private final QuotaService       quotaService;
    private final com.braify.feature.placeholder.service.GlobalPlaceholderService globalPlaceholderService;

    // Timeouts for fetching remote resources (images / CSS) during rendering.
    private static final int RESOURCE_CONNECT_TIMEOUT_MS = 4000;
    private static final int RESOURCE_READ_TIMEOUT_MS    = 6000;

    /**
     * Timeout-bounded, fail-soft stream factory for http(s) resources.
     *
     * <p>openhtmltopdf's default resolves every remote {@code <img src="http…">}
     * (or remote CSS) by opening a {@link java.net.URL} stream on the render
     * thread with the JVM-global timeout, which is effectively infinite. A single
     * slow or unreachable image host therefore blocks the entire PDF render (and
     * the HTTP worker thread) indefinitely — the main cause of "PDF generation
     * hangs / is slow". This caps each fetch and returns {@code null} on failure
     * so openhtmltopdf simply skips the missing resource instead of blocking.
     */
    private static final FSStreamFactory TIMEOUT_STREAM_FACTORY = (String uri) -> new FSStream() {
        private InputStream open() {
            try {
                URLConnection conn = new URL(uri).openConnection();
                conn.setConnectTimeout(RESOURCE_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(RESOURCE_READ_TIMEOUT_MS);
                return conn.getInputStream();
            } catch (Exception e) {
                log.warn("PDF resource fetch failed/timed out, skipping {}: {}", uri, e.getMessage());
                return null;
            }
        }
        @Override public InputStream getStream() { return open(); }
        @Override public Reader getReader() {
            InputStream is = open();
            return is == null ? null : new InputStreamReader(is, StandardCharsets.UTF_8);
        }
    };

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

        // Layer org-level global placeholders under the caller-supplied data
        // (explicit non-blank values win; globals fill everything else).
        Map<String, Object> merged = globalPlaceholderService.mergeForOrg(template.getOrganizationId(), data);
        String rawTemplate = template.getHtmlContent();
        String html        = placeholderService.replacePlaceholders(rawTemplate, merged);

        log.debug("Generating PDF for template: {} (standalone={})",
                template.getId(), isFullHtmlDocument(rawTemplate));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // Bound remote image/CSS fetches so a slow host can't hang the render.
            builder.useHttpStreamImplementation(TIMEOUT_STREAM_FACTORY);
            builder.useSVGDrawer(new BatikSVGDrawer());

            if (isFullHtmlDocument(rawTemplate)) {
                // The template IS a complete HTML document (e.g. an uploaded design with its own
                // <head>/<style>/@page). Render it verbatim so its own styling and page setup are
                // honored — do NOT flatten it into a body fragment or wrap it in the system chrome.
                // Parse with jsoup's lenient HTML parser and hand openhtmltopdf a W3C DOM, which
                // preserves the <style> CSS intact (no XML re-serialization / escaping of selectors).
                org.jsoup.nodes.Document jsoup = Jsoup.parse(html);
                scrubOfficeJunk(jsoup);
                org.w3c.dom.Document w3c = new org.jsoup.helper.W3CDom().fromJsoup(jsoup);
                builder.withW3cDocument(w3c, null);
            } else {
                // Legacy path: template stores a body fragment (+ separate cssContent); wrap it in
                // the system document shell and apply org branding (logo / footer / brand colour).
                String cleaned  = sanitizeHtml(html);
                String fullHtml = buildHtmlDocument(cleaned, template, branding);
                builder.withHtmlContent(fullHtml, null);
            }

            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        }
    }

    /**
     * True when the template content is a self-contained HTML document (has a doctype, an
     * {@code <html>} root, or a {@code <head>}/{@code <style>} block) rather than the body-only
     * fragment the builder normally produces. Such documents carry their own styling and page
     * setup and must be rendered verbatim.
     */
    private static boolean isFullHtmlDocument(String html) {
        if (html == null) return false;
        String head = html.stripLeading();
        String lower = head.length() > 4000 ? head.substring(0, 4000).toLowerCase() : head.toLowerCase();
        return lower.startsWith("<!doctype")
                || lower.contains("<html")
                || lower.contains("<head")
                || lower.contains("<style");
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
            // Prefer the hosted logo URL (works after cloud offload); fall back to inline base64.
            String pdfLogo = (branding.getLogoUrl() != null && !branding.getLogoUrl().isBlank())
                    ? branding.getLogoUrl()
                    : branding.getLogoBase64();
            if (pdfLogo != null && !pdfLogo.isBlank()) {
                headerHtml = """
                        <div style="text-align:left;margin-bottom:12px;">
                          <img src="%s" alt="Logo" style="max-height:48px;max-width:200px;"/>
                        </div>
                        """.formatted(pdfLogo);
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
                  position: relative;
                }
                table { border-collapse: collapse; width: 100%%; }
                th, td { border: 1px solid #ddd; padding: 6px 10px; }
                th { background-color: #f2f2f2; font-weight: bold; }
                img { display: inline-block; }
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

    // ── HTML sanitiser ────────────────────────────────────────────────────────

    /**
     * Strips Microsoft Office / Word-specific markup that breaks openhtmltopdf's
     * XML parser.  Typical culprits are content pasted from Word into the builder:
     *
     *  • Namespace-prefixed elements: &lt;o:p&gt;, &lt;w:*&gt;, &lt;m:*&gt;, &lt;v:*&gt;, &lt;st1:*&gt;
     *  • xmlns:* attributes that declare Office namespaces on HTML elements
     *  • mso-* CSS properties inside style attributes
     *  • XML processing instructions (&lt;?xml …?&gt;)
     *  • IE conditional comments (&lt;!--[if …]&gt; … &lt;![endif]--&gt;)
     */
    private static final Pattern MSO_STYLE_PROP =
            Pattern.compile("(?i)\\s*mso-[^;\"']*(?:;|(?=[\"']))", Pattern.DOTALL);
    private static final Pattern PANOSE_PROP =
            Pattern.compile("(?i)\\s*panose-[^;\"']*(?:;|(?=[\"']))", Pattern.DOTALL);

    private static String sanitizeHtml(String html) {
        if (html == null || html.isBlank()) return html;

        // 1. Strip XML processing instructions and IE conditional comments first
        //    (Jsoup won't parse these well, so handle with regex before parsing)
        String s = html
                .replaceAll("(?s)<\\?xml[^>]*\\?>", "")
                .replaceAll("(?s)<!--\\[if[^]]*]>.*?<!\\[endif]-->", "")
                .replaceAll("(?s)<!--\\[if[^]]*]>.*?<![\\s\\S]*?-->", "");

        // 2. Parse as HTML fragment with Jsoup (lenient parser handles malformed markup)
        Document doc = Jsoup.parseBodyFragment(s);
        doc.outputSettings()
           .syntax(Document.OutputSettings.Syntax.xml)   // emit XHTML for openhtmltopdf
           .charset(java.nio.charset.StandardCharsets.UTF_8)
           .prettyPrint(false);

        // 3-5. Strip Office/Word junk (namespaced elements, xmlns decls, mso-*/panose-* props)
        scrubOfficeJunk(doc);

        // 6. Return just the cleaned body inner HTML
        return doc.body().html();
    }

    /**
     * Removes Microsoft Office / Word artefacts from a parsed document, in place:
     * namespace-prefixed elements ({@code <o:p>}, {@code <w:*>}, …), {@code xmlns:*} attribute
     * declarations, and {@code mso-*}/{@code panose-*} inline-style properties. Shared by the
     * body-fragment path and the full-document path.
     */
    private static void scrubOfficeJunk(Document doc) {
        // Remove elements whose tag name contains a colon (namespace-prefixed), keeping their text.
        for (Element el : doc.select("*")) {
            if (el.tagName().contains(":")) el.unwrap();
        }
        // Strip Office XML namespace declarations from every element's attributes.
        for (Element el : doc.getAllElements()) {
            el.attributes().asList().stream()
              .filter(a -> a.getKey().startsWith("xmlns:") ||
                           a.getKey().equalsIgnoreCase("xmlns"))
              .map(org.jsoup.nodes.Attribute::getKey)
              .toList()
              .forEach(el::removeAttr);
        }
        // Scrub mso-* and panose-* properties from inline style attributes.
        for (Element el : doc.getAllElements()) {
            String style = el.attr("style");
            if (!style.isBlank()) {
                style = MSO_STYLE_PROP.matcher(style).replaceAll("");
                style = PANOSE_PROP.matcher(style).replaceAll("");
                if (style.isBlank()) el.removeAttr("style");
                else el.attr("style", style.trim());
            }
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
