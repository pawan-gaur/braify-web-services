package com.braify.feature.internaltemplate;

import java.util.List;

/**
 * A single INTERNAL template definition contributed by a feature module.
 * The {@code htmlWithTokens} / {@code subjectWithTokens} carry {@code {{placeholder}}}
 * tokens that are substituted at send time.
 *
 * @param code              stable {@link InternalTemplateCodes} value
 * @param name              human-friendly name shown in the platform admin UI
 * @param subjectWithTokens subject line (may contain {@code {{tokens}}})
 * @param htmlWithTokens    email body HTML (contains {@code {{tokens}}})
 * @param placeholders      declared placeholder names (for metadata / UI)
 */
public record InternalTemplateSeed(
        String code,
        String name,
        String subjectWithTokens,
        String htmlWithTokens,
        List<String> placeholders
) {}
