package com.braify.feature.ai.service;

import java.util.regex.Pattern;

/**
 * Second-line output guard for model-generated HTML: strips anything the model shouldn't emit
 * even if it slips past the prompt (scripts, iframes, event handlers, javascript: URLs) and
 * removes markdown code fences.
 */
public final class AiHtmlSanitizer {

    private static final Pattern SCRIPT  = Pattern.compile("(?is)<script\\b.*?</script>");
    private static final Pattern IFRAME  = Pattern.compile("(?is)<iframe\\b.*?</iframe>");
    private static final Pattern ON_ATTR = Pattern.compile("(?is)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JS_URL  = Pattern.compile("(?is)(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2");
    private static final Pattern FENCE   = Pattern.compile("(?is)^```[a-z]*\\s*|\\s*```$");

    private AiHtmlSanitizer() {}

    /** Remove markdown fences and any disallowed active content. */
    public static String clean(String s) {
        if (s == null) return "";
        String html = stripFences(s);
        html = SCRIPT.matcher(html).replaceAll("");
        html = IFRAME.matcher(html).replaceAll("");
        html = ON_ATTR.matcher(html).replaceAll("");
        html = JS_URL.matcher(html).replaceAll("$1=\"#\"");
        return html.trim();
    }

    /** Remove a leading/trailing markdown code fence, if any. */
    public static String stripFences(String s) {
        return s == null ? "" : FENCE.matcher(s.trim()).replaceAll("").trim();
    }
}
