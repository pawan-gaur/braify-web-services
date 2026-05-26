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
     * Returns HTML with all CSS rules from {@code css} inlined as {@code style}
     * attributes on matching elements.
     *
     * <p>Falls back to wrapping the HTML with a {@code <style>} block if anything
     * goes wrong (e.g. malformed CSS), so the email is never completely unstyled.
     *
     * @param html  GrapesJS HTML fragment (body content, not a full document)
     * @param css   GrapesJS CSS string
     * @return      HTML ready for sending via Resend / any email provider
     */
    public String inline(String html, String css) {
        if (html == null || html.isBlank()) return "";
        if (css  == null || css.isBlank())  return html;   // already uses inline styles

        try {
            Document doc = Jsoup.parseBodyFragment(html);
            applyRules(doc, css);
            return doc.body().html();
        } catch (Exception ex) {
            log.warn("CSS inlining failed — falling back to <style> block: {}", ex.getMessage());
            return "<style>" + css + "</style>\n" + html;
        }
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
