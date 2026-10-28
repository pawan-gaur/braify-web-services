package com.braify.feature.organization.service;

import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Creates a new organisation (tenant). Shared by the approval-based onboarding flow and
 * instant self-serve sign-up so both produce identical org records.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgProvisioningService {

    private final OrganizationRepository orgRepository;

    /**
     * Saves a new active organisation on the default (FREE) plan. Quota limits are initialised
     * lazily from the plan defaults by {@code QuotaService} on first use.
     *
     * @param features already-sanitised feature keys
     */
    public Organization createOrganization(String name, String description, List<String> features) {
        Organization org = Organization.builder()
                .name(name)
                .code(uniqueCode(name))
                .description(description)
                .features(features)
                .active(true)
                .deleted(false)
                .build();
        org = orgRepository.save(org);
        log.info("Organisation created: id={} code={} name='{}'", org.getId(), org.getCode(), name);
        return org;
    }

    /** Short code from the name: alphanumeric, upper-case, max 8 chars, numeric suffix if taken. */
    String uniqueCode(String name) {
        String code = name.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (code.length() > 8) code = code.substring(0, 8);
        if (code.isEmpty()) code = "ORG";
        String baseCode = code;
        int suffix = 1;
        while (orgRepository.existsByCode(code)) {
            code = baseCode + suffix++;
        }
        return code;
    }
}
