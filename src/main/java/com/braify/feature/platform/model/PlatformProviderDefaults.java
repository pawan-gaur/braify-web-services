package com.braify.feature.platform.model;

import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.emailconfig.model.OrgEmailConfig;
import com.braify.feature.smsconfig.model.OrgSmsConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Platform-wide default provider configuration set by PLATFORM_ADMIN.
 *
 * <p>Single-document collection (like {@link PlatformSettings}). Organisations
 * that do not configure their own provider fall back to these defaults.
 *
 * <p>Currently holds the email default; SMS and cloud defaults will be added here
 * as those channels are made configurable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "platform_provider_defaults")
public class PlatformProviderDefaults {

    public static final String SINGLETON_ID = "PLATFORM_PROVIDER_DEFAULTS";

    @Id
    private String id;

    /** Default outbound email provider config (secrets encrypted at rest). */
    private OrgEmailConfig email;

    /**
     * Whether the resolver may fall back to the built-in Resend credentials from
     * {@code application.yml} when neither the org nor this platform default is usable.
     * {@code null} is treated as enabled (backwards-compatible default). Toggled by PLATFORM_ADMIN.
     */
    private Boolean emailEnvFallbackEnabled;

    /** Default outbound SMS provider config (secrets encrypted at rest). */
    private OrgSmsConfig sms;

    /** Default cloud storage provider config (credentials encrypted at rest). */
    private OrgCloudConfig cloud;

    private String updatedBy;
}
