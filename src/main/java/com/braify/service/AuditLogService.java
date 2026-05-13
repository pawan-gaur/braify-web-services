package com.braify.service;

import com.braify.model.AppUser;
import com.braify.model.AuditLog;
import com.braify.repository.AppUserRepository;
import com.braify.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository  userRepository;

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Full audit entry with organization scope.
     *
     * @param resourceId     ID of the affected resource
     * @param resourceName   Display name at the time of the action
     * @param action         Action performed (CREATED, UPDATED, …)
     * @param resourceType   TEMPLATE | EMAIL_TEMPLATE | USER | ORGANIZATION | E_SIGN
     * @param version        Resulting version number (0 when not applicable)
     * @param changes        Optional field-level diff map (UPDATED only)
     * @param performedBy    Email of the acting user
     * @param organizationId Organisation that owns the affected resource (may be null for legacy entries)
     */
    public AuditLog log(String resourceId,
                        String resourceName,
                        AuditLog.Action action,
                        AuditLog.ResourceType resourceType,
                        int version,
                        Map<String, Object> changes,
                        String performedBy,
                        String organizationId) {

        AuditLog entry = AuditLog.builder()
                .templateId(resourceId)
                .templateName(resourceName)
                .action(action)
                .resourceType(resourceType)
                .versionNumber(version)
                .performedBy(performedBy != null ? performedBy : "system")
                .changes(changes)
                .organizationId(organizationId)
                .build();

        return auditLogRepository.save(entry);
    }

    /**
     * Backward-compatible 7-param overload (organizationId defaults to null).
     * Existing callers (TemplateService, EmailTemplateService, UserService) that
     * already pass the caller's email are still fully functional.
     */
    public AuditLog log(String resourceId,
                        String resourceName,
                        AuditLog.Action action,
                        AuditLog.ResourceType resourceType,
                        int version,
                        Map<String, Object> changes,
                        String performedBy) {
        return log(resourceId, resourceName, action, resourceType, version, changes, performedBy, null);
    }

    /** Overload for backward compatibility — uses "system" as performer. */
    public AuditLog log(String resourceId,
                        String resourceName,
                        AuditLog.Action action,
                        AuditLog.ResourceType resourceType,
                        int version,
                        Map<String, Object> changes) {
        return log(resourceId, resourceName, action, resourceType, version, changes, "system", null);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** All logs for a specific resource (PDF template, email template, user…), newest first. */
    public List<AuditLog> getForResource(String resourceId) {
        return auditLogRepository.findByTemplateIdOrderByTimestampDesc(resourceId);
    }

    /**
     * Paginated audit log scoped by the caller's role:
     *
     * <ul>
     *   <li>PLATFORM_ADMIN → all entries; optionally filtered by {@code orgId}</li>
     *   <li>ORG_ADMIN      → entries performed by any user in their org</li>
     *   <li>ADMIN          → entries performed by ADMIN + USER roles in their org</li>
     *   <li>USER           → only entries performed by themselves</li>
     * </ul>
     *
     * @param page         zero-based page index
     * @param size         page size
     * @param resourceType null = all types; otherwise filtered
     * @param orgId        PLATFORM_ADMIN only: scope to a specific organisation (null = all orgs)
     * @param caller       the authenticated user requesting the log
     */
    public Page<AuditLog> getAll(int page, int size,
                                 AuditLog.ResourceType resourceType,
                                 String orgId,
                                 AppUser caller) {
        PageRequest pr = PageRequest.of(page, size);

        return switch (caller.getRole()) {

            case PLATFORM_ADMIN -> {
                if (orgId != null && !orgId.isBlank()) {
                    // Filter by the organizationId field stored on each log entry
                    yield resourceType != null
                            ? auditLogRepository.findByOrganizationIdAndResourceTypeOrderByTimestampDesc(orgId, resourceType, pr)
                            : auditLogRepository.findByOrganizationIdOrderByTimestampDesc(orgId, pr);
                }
                yield resourceType != null
                        ? auditLogRepository.findByResourceTypeOrderByTimestampDesc(resourceType, pr)
                        : auditLogRepository.findAllByOrderByTimestampDesc(pr);
            }

            case ORG_ADMIN -> {
                // All performer emails in their org (all roles)
                List<String> emails = performerEmails(caller.getOrganizationId(), null);
                yield resourceType != null
                        ? auditLogRepository.findByPerformedByInAndResourceTypeOrderByTimestampDesc(emails, resourceType, pr)
                        : auditLogRepository.findByPerformedByInOrderByTimestampDesc(emails, pr);
            }

            case ADMIN -> {
                // Only ADMIN + USER emails in their org
                List<String> emails = performerEmails(caller.getOrganizationId(),
                        List.of(AppUser.Role.ADMIN, AppUser.Role.USER));
                yield resourceType != null
                        ? auditLogRepository.findByPerformedByInAndResourceTypeOrderByTimestampDesc(emails, resourceType, pr)
                        : auditLogRepository.findByPerformedByInOrderByTimestampDesc(emails, pr);
            }

            default -> {  // USER — own activity only
                String email = caller.getEmail();
                yield resourceType != null
                        ? auditLogRepository.findByPerformedByAndResourceTypeOrderByTimestampDesc(email, resourceType, pr)
                        : auditLogRepository.findByPerformedByOrderByTimestampDesc(email, pr);
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the emails of all active users in {@code orgId}.
     * If {@code roles} is non-null, further filters to those roles only.
     */
    private List<String> performerEmails(String orgId, List<AppUser.Role> roles) {
        List<AppUser> users = (roles != null)
                ? userRepository.findByOrganizationIdAndActiveTrueAndRoleIn(orgId, roles)
                : userRepository.findByOrganizationIdAndActiveTrue(orgId);
        return users.stream().map(AppUser::getEmail).toList();
    }
}
