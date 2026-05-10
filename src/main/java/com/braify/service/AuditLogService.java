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

    /**
     * Write a single audit entry for either a PDF or email template.
     *
     * @param resourceId   ID of the template or email template
     * @param resourceName Display name at time of action
     * @param action       CREATED | UPDATED | DELETED | RESTORED
     * @param resourceType TEMPLATE | EMAIL_TEMPLATE
     * @param version      Resulting version number (0 for DELETE)
     * @param changes      Optional field-level diff map
     * @param performedBy  Email of the user performing the action
     */
    public AuditLog log(String resourceId,
                        String resourceName,
                        AuditLog.Action action,
                        AuditLog.ResourceType resourceType,
                        int version,
                        Map<String, Object> changes,
                        String performedBy) {

        AuditLog entry = AuditLog.builder()
                .templateId(resourceId)
                .templateName(resourceName)
                .action(action)
                .resourceType(resourceType)
                .versionNumber(version)
                .performedBy(performedBy != null ? performedBy : "system")
                .changes(changes)
                .build();

        return auditLogRepository.save(entry);
    }

    /**
     * Overload for backward compatibility — uses "system" as performer.
     */
    public AuditLog log(String resourceId,
                        String resourceName,
                        AuditLog.Action action,
                        AuditLog.ResourceType resourceType,
                        int version,
                        Map<String, Object> changes) {
        return log(resourceId, resourceName, action, resourceType, version, changes, "system");
    }

    /** All logs for a specific resource (PDF or email template), newest first. */
    public List<AuditLog> getForResource(String resourceId) {
        return auditLogRepository.findByTemplateIdOrderByTimestampDesc(resourceId);
    }

    /**
     * Paginated audit log scoped by the caller's role:
     * <ul>
     *   <li>PLATFORM_ADMIN → all entries</li>
     *   <li>ORG_ADMIN      → entries performed by any user in their org</li>
     *   <li>ADMIN          → entries performed by ADMIN + USER roles in their org</li>
     *   <li>USER           → only entries performed by themselves</li>
     * </ul>
     *
     * @param caller       the authenticated user requesting the log
     * @param resourceType null = all types; otherwise filtered
     */
    public Page<AuditLog> getAll(int page, int size,
                                 AuditLog.ResourceType resourceType,
                                 AppUser caller) {
        PageRequest pr = PageRequest.of(page, size);

        return switch (caller.getRole()) {
            case PLATFORM_ADMIN -> resourceType != null
                    ? auditLogRepository.findByResourceTypeOrderByTimestampDesc(resourceType, pr)
                    : auditLogRepository.findAllByOrderByTimestampDesc(pr);

            case ORG_ADMIN -> {
                // All performer emails in their org
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
