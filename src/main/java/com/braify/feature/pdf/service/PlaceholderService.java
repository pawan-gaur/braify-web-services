package com.braify.feature.pdf.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlaceholderService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    /**
     * Replace all {{path}} placeholders in html with values from data map.
     * Supports: {{name}}, {{user.name}}, {{items[0].price}}
     */
    public String replacePlaceholders(String html, Map<String, Object> data) {
        if (html == null || data == null) return html;

        StringBuffer result = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(html);

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            String value = resolvePath(data, path);
            // Escape $ and \ so they're not treated as regex group references
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Extract placeholder names from HTML (for template metadata) */
    public List<String> extractPlaceholders(String html) {
        List<String> found = new ArrayList<>();
        if (html == null) return found;

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(html);
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            if (!found.contains(path)) {
                found.add(path);
            }
        }
        return found;
    }

    /**
     * Resolve a dot-notation path against a nested Map.
     * Examples:
     *   "name"              → data.get("name")
     *   "user.name"         → data.get("user").get("name")
     *   "items[0].price"    → data.get("items")[0].get("price")
     */
    @SuppressWarnings("unchecked")
    private String resolvePath(Map<String, Object> data, String path) {
        try {
            String[] segments = path.split("\\.");
            Object current = data;

            for (String segment : segments) {
                if (current == null) return "";

                if (segment.contains("[")) {
                    // array access: items[0]
                    String key = segment.substring(0, segment.indexOf('['));
                    int index = Integer.parseInt(
                            segment.substring(segment.indexOf('[') + 1, segment.indexOf(']')));

                    current = ((Map<String, Object>) current).get(key);
                    if (current instanceof List<?> list) {
                        current = list.get(index);
                    } else {
                        return "";
                    }
                } else {
                    current = ((Map<String, Object>) current).get(segment);
                }
            }
            return current != null ? current.toString() : "";
        } catch (Exception e) {
            return "{{" + path + "}}"; // leave unresolved placeholder visible
        }
    }
}
