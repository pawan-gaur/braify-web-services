package com.braify.feature.cloudconfig.service;

import com.braify.feature.cloudconfig.model.OrgCloudConfig;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.platform.model.PlatformProviderDefaults;
import com.braify.feature.platform.repository.PlatformProviderDefaultsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective cloud storage config for an organisation:
 * the org's own {@code cloudConfig} (if usable), else the platform-admin default.
 *
 * <p>Returns the config with credentials still encrypted — downstream callers
 * decrypt as before, so both org and platform-default configs (encrypted with the
 * same key) work identically.
 *
 * <p>Depends on the platform-defaults repository (not its service) to avoid a bean cycle.
 */
@Component
@RequiredArgsConstructor
public class CloudConfigResolver {

    private final OrganizationRepository             orgRepository;
    private final PlatformProviderDefaultsRepository platformDefaultsRepository;

    /** Resolve using an already-loaded organisation (avoids a second fetch). */
    public OrgCloudConfig resolve(Organization org) {
        OrgCloudConfig own = (org != null) ? org.getCloudConfig() : null;
        if (isUsable(own)) return own;

        OrgCloudConfig platform = platformDefaultsRepository
                .findById(PlatformProviderDefaults.SINGLETON_ID)
                .map(PlatformProviderDefaults::getCloud)
                .orElse(null);
        if (isUsable(platform)) return platform;

        throw new RuntimeException(
                "Cloud storage is not configured for this organisation, and no platform default is set. "
                + "Configure it under Settings → Cloud Storage.");
    }

    /** Resolve by org id (fetches the organisation first). */
    public OrgCloudConfig resolveByOrgId(String orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new RuntimeException("Organisation not found: " + orgId));
        return resolve(org);
    }

    private boolean isUsable(OrgCloudConfig c) {
        return c != null
                && c.getCloud() != null
                && notBlank(c.getBucket())
                && notBlank(c.getAccessKey());
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
