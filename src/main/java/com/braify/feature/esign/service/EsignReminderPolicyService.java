package com.braify.feature.esign.service;

import com.braify.feature.esign.dto.EsignReminderPolicyRequest;
import com.braify.feature.esign.model.EsignReminderPolicy;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Reads and updates an organization's automatic e-sign reminder policy. ORG_ADMIN may manage only
 * their own org; PLATFORM_ADMIN may manage any. Falls back to {@link EsignReminderPolicy#defaults()}
 * when the org has never saved one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsignReminderPolicyService {

    private final OrganizationRepository orgRepository;

    /** Returns the org's effective reminder policy (its saved one, or the built-in defaults). */
    public EsignReminderPolicy getPolicy(String orgId, AppUser caller) {
        assertAccess(orgId, caller);
        return findOrg(orgId).effectiveReminderPolicy();
    }

    /** Replaces the org's reminder policy and returns the saved value. */
    public EsignReminderPolicy updatePolicy(String orgId, EsignReminderPolicyRequest req, AppUser caller) {
        assertAccess(orgId, caller);
        Organization org = findOrg(orgId);

        EsignReminderPolicy policy = EsignReminderPolicy.builder()
                .enabled(req.isEnabled())
                .firstReminderAfterHours(req.getFirstReminderAfterHours())
                .repeatEveryHours(req.getRepeatEveryHours())
                .maxReminders(req.getMaxReminders())
                .build();

        org.setEsignReminderPolicy(policy);
        orgRepository.save(org);
        log.info("E-sign reminder policy updated for org '{}' by '{}' (enabled={} first={}h repeat={}h max={})",
                orgId, caller.getEmail(), policy.isEnabled(), policy.getFirstReminderAfterHours(),
                policy.getRepeatEveryHours(), policy.getMaxReminders());
        return policy;
    }

    private Organization findOrg(String orgId) {
        return orgRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + orgId));
    }

    private void assertAccess(String orgId, AppUser caller) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!orgId.equals(caller.getOrganizationId()))
            throw new AccessDeniedException("You can only manage your own organisation's reminder policy.");
    }
}
