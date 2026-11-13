package com.braify.feature.ai.service;

import com.braify.feature.ai.config.AiProperties;
import com.braify.feature.ai.dto.DocumentTemplateDraft;
import com.braify.feature.ai.service.provider.AiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drafts a PDF or email template from an uploaded document: extract the document's content,
 * ask the active provider to rebuild it with {{placeholders}}, then validate and sanitise the
 * reply. Nothing is persisted — the client reviews the draft and saves it through the normal
 * template API (which handles codes, versions, quota and audit).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDocumentTemplateService {

    private final AiProviderRegistry registry;
    private final DocumentTextExtractor extractor;
    private final DocumentTemplatePromptBuilder prompts;
    private final AiProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    /** {{ key }} with optional inner spaces; normalised to {{key}}. */
    private static final Pattern TOKEN      = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");
    /** CSS never needs markup; dropping every tag also blocks a </style> breakout. */
    private static final Pattern ANY_TAG    = Pattern.compile("<[^>]*>");
    private static final Pattern CSS_JS     = Pattern.compile("(?i)javascript:|expression\\s*\\(");

    /**
     * @throws IllegalArgumentException (→ 400) for unsupported, unreadable, empty or too-long documents.
     */
    public DocumentTemplateDraft draft(String fileName, byte[] bytes, String context, String instructions) {
        if (!registry.isAvailable()) {
            return DocumentTemplateDraft.unavailable(
                    "AI is not configured. Set braify.ai.enabled=true and an API key for the active provider.");
        }
        String kind = "EMAIL".equalsIgnoreCase(context) ? "EMAIL" : "PDF";

        DocumentTextExtractor.Extracted doc = extractor.extract(fileName, bytes);
        if (doc.content().length() > props.getDocumentMaxChars()) {
            throw new IllegalArgumentException(String.format(
                    "This document is too long to convert in one go (%,d characters; the limit is %,d). "
                    + "Split it into smaller documents.", doc.content().length(), props.getDocumentMaxChars()));
        }

        AiProvider provider = registry.active().orElseThrow();
        long started = System.currentTimeMillis();
        String raw;
        try {
            raw = provider.complete(prompts.systemPrompt(kind),
                    prompts.userPrompt(fileName, doc.format(), doc.content(), instructions),
                    props.getDocumentMaxOutputTokens());
        } catch (AiProvider.AiProviderException e) {
            log.warn("AI template-from-document call failed: {}", e.getMessage());
            return DocumentTemplateDraft.error("The AI provider could not complete the request. " + e.getMessage(),
                    provider.id(), provider.model());
        }
        log.info("AI template-from-document: file='{}' type={} chars={} → {} chars in {} ms ({}/{})",
                fileName, doc.type(), doc.content().length(), raw == null ? 0 : raw.length(),
                System.currentTimeMillis() - started, provider.id(), provider.model());

        String out = raw == null ? "" : raw.trim();
        if (out.startsWith(TemplatePromptBuilder.REFUSAL_MARKER)) {
            String msg = out.substring(TemplatePromptBuilder.REFUSAL_MARKER.length()).trim();
            return DocumentTemplateDraft.refused(msg.isBlank() ? "This file can't be turned into a template." : msg,
                    provider.id(), provider.model());
        }

        int m = out.indexOf(DocumentTemplatePromptBuilder.META);
        int h = out.indexOf(DocumentTemplatePromptBuilder.HTML);
        int c = out.indexOf(DocumentTemplatePromptBuilder.CSS);
        if (h < 0) {
            return DocumentTemplateDraft.error(
                    "The AI reply was incomplete, which usually means the document is too long. Try a shorter document.",
                    provider.id(), provider.model());
        }

        String html = out.substring(h + DocumentTemplatePromptBuilder.HTML.length(), c > h ? c : out.length());
        html = normaliseTokens(bodyOnly(AiHtmlSanitizer.clean(html)));
        if (!html.contains("<")) {
            return DocumentTemplateDraft.error("The AI did not return a usable template. Please try again.",
                    provider.id(), provider.model());
        }
        String css = c > h ? cleanCss(out.substring(c + DocumentTemplatePromptBuilder.CSS.length())) : "";

        JsonNode meta = parseMeta(m >= 0 && m < h ? out.substring(m + DocumentTemplatePromptBuilder.META.length(), h) : "");
        String subject = "EMAIL".equals(kind) ? normaliseTokens(text(meta, "subject")) : null;

        return DocumentTemplateDraft.builder()
                .status("OK")
                .name(firstNonBlank(text(meta, "name"), baseName(fileName)))
                .description(text(meta, "description"))
                .subject(subject)
                .htmlContent(html)
                .cssContent(css)
                .placeholders(placeholders(meta, html + " " + (subject == null ? "" : subject)))
                .sourceType(doc.type())
                .sourceFileName(fileName)
                .provider(provider.id())
                .model(provider.model())
                .build();
    }

    /**
     * The placeholders actually used in the template, in order of first use: labels/examples from
     * the model's META where it gave them, a label derived from the key otherwise. Declared keys
     * the template doesn't use are dropped.
     */
    private List<DocumentTemplateDraft.Placeholder> placeholders(JsonNode meta, String usedIn) {
        Map<String, DocumentTemplateDraft.Placeholder> declared = new LinkedHashMap<>();
        JsonNode list = meta.path("placeholders");
        if (list.isArray()) {
            for (JsonNode p : list) {
                String key = p.path("key").asText("").trim();
                if (!key.isEmpty()) declared.put(key, new DocumentTemplateDraft.Placeholder(
                        key, firstNonBlank(p.path("label").asText(""), humanise(key)), p.path("example").asText("")));
            }
        }
        Set<String> used = new LinkedHashSet<>();
        Matcher mt = TOKEN.matcher(usedIn);
        while (mt.find()) used.add(mt.group(1));

        List<DocumentTemplateDraft.Placeholder> out = new ArrayList<>();
        for (String key : used) {
            DocumentTemplateDraft.Placeholder p = declared.get(key);
            out.add(p != null ? p : new DocumentTemplateDraft.Placeholder(key, humanise(key), ""));
        }
        return out;
    }

    private JsonNode parseMeta(String s) {
        String json = AiHtmlSanitizer.stripFences(s);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return mapper.createObjectNode();
        try {
            return mapper.readTree(json.substring(start, end + 1));
        } catch (Exception e) {
            log.debug("AI template META was not valid JSON: {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /** If the model wrapped the template in a full page, keep only the body markup. */
    private static String bodyOnly(String html) {
        if (!html.toLowerCase(Locale.ROOT).contains("<body")) return html;
        org.jsoup.nodes.Document d = Jsoup.parse(html);
        return d.body().html();
    }

    private static String normaliseTokens(String s) {
        return s == null ? null : TOKEN.matcher(s).replaceAll("{{$1}}");
    }

    private static String cleanCss(String s) {
        String css = AiHtmlSanitizer.stripFences(s);
        css = ANY_TAG.matcher(css).replaceAll("").replace("<", "");
        return CSS_JS.matcher(css).replaceAll("").trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText().trim() : null;
    }

    private static String humanise(String key) {
        String s = key.replaceAll("[_.\\-]+", " ").trim();
        return s.isEmpty() ? key : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "Untitled template";
        String n = fileName.replaceAll("^.*[/\\\\]", "");
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
