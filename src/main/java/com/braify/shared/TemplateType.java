package com.braify.shared;

/**
 * Classifies a template record.
 *
 * <ul>
 *   <li>{@code EXTERNAL} — user / organisation authored templates (the default for every
 *       record created through the normal template APIs).</li>
 *   <li>{@code INTERNAL} — platform-owned system templates (transactional emails such as
 *       user invitation, password reset, onboarding and e-sign notifications). These are
 *       seeded once, are org-agnostic ({@code organizationId == null}) and are addressed by a
 *       stable {@code code} rather than a generated id.</li>
 * </ul>
 */
public enum TemplateType {
    INTERNAL,
    EXTERNAL
}
