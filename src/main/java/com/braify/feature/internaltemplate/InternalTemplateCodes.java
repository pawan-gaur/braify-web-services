package com.braify.feature.internaltemplate;

/**
 * Stable codes for the platform's INTERNAL (system) email templates.
 * These replace the previously hardcoded HTML — the template body now lives in the
 * {@code email_templates} collection and is resolved by these codes at send time.
 */
public final class InternalTemplateCodes {

    private InternalTemplateCodes() {}

    // Auth
    public static final String INVITE_EMAIL          = "INVITE_EMAIL";
    public static final String PASSWORD_RESET_EMAIL  = "PASSWORD_RESET_EMAIL";

    // Onboarding
    public static final String ONBOARDING_CONFIRMATION  = "ONBOARDING_CONFIRMATION";
    public static final String ONBOARDING_REJECTION     = "ONBOARDING_REJECTION";
    public static final String ONBOARDING_INFO_REQUIRED = "ONBOARDING_INFO_REQUIRED";

    // E-Sign
    public static final String ESIGN_SIGNING_INVITATION = "ESIGN_SIGNING_INVITATION";
    public static final String ESIGN_SIGNING_REMINDER   = "ESIGN_SIGNING_REMINDER";
    public static final String ESIGN_COMPLETION_SIGNER  = "ESIGN_COMPLETION_SIGNER";
    public static final String ESIGN_CC_NOTIFICATION    = "ESIGN_CC_NOTIFICATION";
    public static final String ESIGN_CC_COMPLETION      = "ESIGN_CC_COMPLETION";
}
