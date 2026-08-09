package com.braify.feature.emaillog.service;

import com.braify.feature.emaillog.dto.EmailLogResponse;
import com.braify.feature.emaillog.model.EmailLog;
import com.braify.feature.emaillog.repository.EmailLogRepository;
import com.braify.feature.esign.dto.PageResponse;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Persists an {@link EmailLog} row for every outbound email. Two entry points:
 * <ul>
 *   <li>{@link #recorded(EmailLog, Supplier)} — wraps a send that returns a Resend response,
 *       recording SENT (+ provider id) on success or FAILED (+ error) on exception, then rethrows.</li>
 *   <li>{@link #record(EmailLog)} — persists a row the caller has already populated with status
 *       (used where the send doesn't fit the supplier shape, e.g. bulk email's own retry/status logic).</li>
 * </ul>
 *
 * <p>Logging must never break a send flow: persistence failures here are swallowed and logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailLogService {

    private static final int MAX_ERROR_LEN = 500;

    private final EmailLogRepository    repo;
    private final MongoTemplate         mongoTemplate;
    private final OrganizationRepository orgRepo;

    /**
     * Runs {@code action}, records the outcome against {@code spec}, and returns the response.
     * On failure the row is saved as FAILED and the original exception is rethrown so the caller's
     * existing error handling is unchanged.
     */
    public CreateEmailResponse recorded(EmailLog spec, Supplier<CreateEmailResponse> action) {
        try {
            CreateEmailResponse resp = action.get();
            spec.setStatus(EmailLog.Status.SENT);
            spec.setProviderMessageId(resp != null ? resp.getId() : null);
            record(spec);
            return resp;
        } catch (RuntimeException e) {
            spec.setStatus(EmailLog.Status.FAILED);
            spec.setErrorMessage(truncate(e.getMessage()));
            record(spec);
            throw e;
        }
    }

    /** Persists a fully-populated log row. Swallows persistence errors so it never breaks a send. */
    public void record(EmailLog entry) {
        try {
            if (entry.getCreatedAt() == null) entry.setCreatedAt(LocalDateTime.now());
            repo.save(entry);
        } catch (Exception e) {
            log.warn("Failed to persist email log ({} → {}): {}",
                    entry.getCategory(), entry.getRecipient(), e.getMessage());
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }

    // ── Viewer query ──────────────────────────────────────────────────────────

    /**
     * Paginated, role-scoped email-log listing for the Email Activity viewer.
     * <ul>
     *   <li>PLATFORM_ADMIN — all orgs; optional {@code orgIdFilter} narrows to one org.</li>
     *   <li>ORG_ADMIN      — locked to their own org ({@code orgIdFilter} is ignored).</li>
     *   <li>Any other role — {@link AccessDeniedException}.</li>
     * </ul>
     *
     * @param category optional category filter; null = all
     * @param status   optional status filter; null = all
     * @param search   optional free-text match on recipient/subject/senderName; null/blank = no filter
     */
    public PageResponse<EmailLogResponse> list(UserDetailsImpl principal,
                                               String orgIdFilter,
                                               EmailLog.Category category,
                                               EmailLog.Status status,
                                               Boolean ccFilter,
                                               String search,
                                               LocalDateTime dateFrom,
                                               LocalDateTime dateTo,
                                               int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        AppUser.Role role = principal.getAppUser().getRole();

        List<Criteria> and = new ArrayList<>();
        switch (role) {
            case PLATFORM_ADMIN -> {
                if (orgIdFilter != null && !orgIdFilter.isBlank())
                    and.add(Criteria.where("orgId").is(orgIdFilter));
            }
            case ORG_ADMIN -> and.add(Criteria.where("orgId").is(principal.getOrgId()));
            default -> throw new AccessDeniedException("You do not have access to email activity");
        }

        if (category != null)  and.add(Criteria.where("category").is(category));
        if (status != null)    and.add(Criteria.where("status").is(status));
        if (ccFilter != null)  and.add(Criteria.where("cc").is(ccFilter));

        if (dateFrom != null || dateTo != null) {
            Criteria c = Criteria.where("createdAt");
            if (dateFrom != null) c = c.gte(dateFrom);
            if (dateTo != null)   c = c.lte(dateTo);
            and.add(c);
        }

        if (search != null && !search.isBlank()) {
            String rx = Pattern.quote(search.trim());
            and.add(new Criteria().orOperator(
                    Criteria.where("recipient").regex(rx, "i"),
                    Criteria.where("subject").regex(rx, "i"),
                    Criteria.where("senderName").regex(rx, "i")));
        }

        Query query = new Query();
        if (!and.isEmpty()) query.addCriteria(new Criteria().andOperator(and.toArray(new Criteria[0])));

        long total = mongoTemplate.count(query, EmailLog.class);
        query.with(pageable);
        List<EmailLog> rows = mongoTemplate.find(query, EmailLog.class);

        Map<String, String> orgNames = resolveOrgNames(rows);
        List<EmailLogResponse> content = rows.stream()
                .map(e -> EmailLogResponse.from(e, e.getOrgId() != null ? orgNames.get(e.getOrgId()) : null))
                .toList();

        return PageResponse.of(new PageImpl<>(rows, pageable, total), content);
    }

    /** Batch-resolves org id → display name for the rows on this page. */
    private Map<String, String> resolveOrgNames(List<EmailLog> rows) {
        List<String> ids = rows.stream()
                .map(EmailLog::getOrgId).filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, String> names = new java.util.HashMap<>();
        orgRepo.findAllById(ids).forEach(o -> names.put(o.getId(), o.getName()));
        return names;
    }
}
