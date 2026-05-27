package com.braify.config.infra.email;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inlines CSS rules into HTML element {@code style} attributes so emails render
 * correctly in Gmail and other clients that strip {@code <style>} blocks.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Parse CSS text into (selector → declarations) pairs.</li>
 *   <li>For each rule, use jsoup to select matching elements in the HTML.</li>
 *   <li>Merge CSS declarations onto each element's existing {@code style}
 *       attribute (existing inline styles keep their higher priority).</li>
 * </ol>
 *
 * <p>At-rules ({@code @media}, {@code @keyframes}, etc.) and pseudo-class /
 * pseudo-element selectors are skipped — they cannot be represented inline.
 */
@Slf4j
@Component
public class CssInliner {

    /** Matches a single CSS rule block: selector { declarations } */
    private static final Pattern RULE = Pattern.compile(
            "([^{@]+)\\{([^}]*)\\}", Pattern.DOTALL);

    /**
     * Returns a complete, email-client-safe HTML document with all CSS rules from
     * {@code css} inlined as {@code style} attributes on matching elements.
     *
     * <p>The output is always wrapped in a full XHTML document shell
     * (DOCTYPE + {@code <html>/<head>/<body>}) containing an email reset
     * {@code <style>} block.  This ensures Gmail, Outlook, and other clients
     * render in standards mode, honour image references, and respect layout.
     *
     * <p>Falls back to embedding a {@code <style>} block inside the wrapper if
     * anything goes wrong (e.g. malformed CSS), so the email is never unstyled.
     *
     * @param html  GrapesJS HTML fragment (body content, not a full document)
     * @param css   GrapesJS CSS string
     * @return      Complete HTML document ready for sending via Resend / any email provider
     */
    public String inline(String html, String css) {
        if (html == null || html.isBlank()) return "";

        // No external CSS — blocks already carry inline styles; just add the document shell
        if (css == null || css.isBlank()) return wrapEmailDocument(html);

        try {
            Document doc = Jsoup.parseBodyFragment(html);
            applyRules(doc, css);
            return wrapEmailDocument(doc.body().html());
        } catch (Exception ex) {
            log.warn("CSS inlining failed — falling back to <style> block: {}", ex.getMessage());
            return wrapEmailDocument("<style>" + css + "</style>\n" + html);
        }
    }

    /**
     * Wraps a body-fragment string in a complete, email-client-safe HTML document.
     *
     * <ul>
     *   <li>XHTML 1.0 Transitional DOCTYPE — keeps Outlook out of quirks mode.</li>
     *   <li>Content-Type + viewport meta — correct charset and mobile scaling.</li>
     *   <li>MSO conditional comment — enables PNG support in Outlook.</li>
     *   <li>Baseline reset rules — collapses table borders, removes body margins,
     *       prevents iOS auto-linking, and suppresses phantom gaps under images.</li>
     * </ul>
     */
    private static String wrapEmailDocument(String bodyContent) {
        return "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\"\n"
             + "  \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\">\n"
             + "<head>\n"
             + "  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n"
             + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n"
             + "  <!--[if mso]><xml><o:OfficeDocumentSettings>"
             +     "<o:AllowPNG/></o:OfficeDocumentSettings></xml><![endif]-->\n"
             + "  <style type=\"text/css\">\n"
             + "    body,#bodyTable{margin:0;padding:0;width:100%!important;}\n"
             + "    body{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;}\n"
             + "    table,td{border-collapse:collapse;mso-table-lspace:0pt;mso-table-rspace:0pt;}\n"
             + "    img{border:0;height:auto;line-height:100%;outline:none;text-decoration:none;}\n"
             + "    a[x-apple-data-detectors]{color:inherit!important;text-decoration:none!important;"
             +   "font-size:inherit!important;font-family:inherit!important;}\n"
             + "  </style>\n"
             + "</head>\n"
             + "<body style=\"margin:0;padding:0;\">\n"
             + bodyContent + "\n"
             + "</body>\n</html>";
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void applyRules(Document doc, String css) {
        Matcher m = RULE.matcher(css);
        while (m.find()) {
            String selectorBlock = m.group(1).trim();
            String declarations  = m.group(2).trim();
            if (selectorBlock.isEmpty() || declarations.isEmpty()) continue;

            // A rule block can list comma-separated selectors
            for (String raw : selectorBlock.split(",")) {
                String selector = raw.trim();
                if (shouldSkip(selector)) continue;

                try {
                    Elements elements = doc.select(selector);
                    Map<String, String> newProps = parseDeclarations(declarations);
                    for (Element el : elements) {
                        // Start from the CSS-class properties (lower priority)
                        Map<String, String> merged = new LinkedHashMap<>(newProps);
                        // Existing inline styles override CSS-class rules
                        merged.putAll(parseDeclarations(el.attr("style")));
                        el.attr("style", joinStyle(merged));
                    }
                } catch (Exception ignored) {
                    // jsoup cannot handle every CSS3 selector — skip silently
                }
            }
        }
    }

    /** Returns {@code true} for selectors that cannot meaningfully be inlined. */
    private boolean shouldSkip(String selector) {
        return selector.isEmpty()
                || selector.equals("*")    // universal selector — skip to avoid enormous style attrs
                || selector.contains(":")  // pseudo-class / pseudo-element (:hover, ::before …)
                || selector.contains("@"); // at-rule fragment
    }

    /** Parses a CSS declaration block into a property → value map. */
    private Map<String, String> parseDeclarations(String decls) {
        Map<String, String> props = new LinkedHashMap<>();
        if (decls == null || decls.isBlank()) return props;
        for (String decl : decls.split(";")) {
            int colon = decl.indexOf(':');
            if (colon > 0) {
                String prop  = decl.substring(0, colon).trim().toLowerCase();
                String value = decl.substring(colon + 1).trim();
                if (!prop.isEmpty() && !value.isEmpty()) {
                    props.put(prop, value);
                }
            }
        }
        return props;
    }

    /** Serialises a property-map back into a CSS {@code style} attribute value. */
    private String joinStyle(Map<String, String> props) {
        StringBuilder sb = new StringBuilder();
        props.forEach((k, v) -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append(": ").append(v);
        });
        return sb.toString();
    }
}
