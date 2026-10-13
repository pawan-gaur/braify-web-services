package com.braify.feature.signer.service;

import com.braify.feature.esign.model.ESignAuditEvent;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.repository.ESignDocumentRepository;
import com.braify.feature.esign.service.ESignAuditService;
import com.braify.feature.esign.service.ESignStorageService;
import com.braify.feature.esign.service.ESignTokenService;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.signer.dto.SignerDocumentItem;
import com.braify.feature.signer.dto.SignerDocumentItem.Bucket;
import com.braify.feature.signer.dto.SignerInboxResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The signer inbox: every e-sign document where an email is a signatory or CC recipient,
 * across all organizations, classified into tabs.
 *
 * <p>Access rule used by every method: a document is visible to an email only if that
 * email is one of its signatories (or the legacy single {@code clientEmail}) or one of its
 * CC lists, and the document has been sent (never DRAFT). Signing is only possible when
 * it is that signatory's turn; it is delegated to the existing token-based signing flow
 * by handing out that signatory's own signing token for that one document.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignerInboxService {

    /** Hard cap on documents loaded for one inbox — far above any realistic signer volume. */
    private static final int MAX_DOCS = 2000;
    private static final int VIEW_TOKEN_DAYS = 1;

    private static final Set<ESignDocument.Status> OPEN = EnumSet.of(
            ESignDocument.Status.PENDING, ESignDocument.Status.IN_REVIEW, ESignDocument.Status.PARTIALLY_SIGNED);

    private final MongoTemplate            mongoTemplate;
    private final ESignDocumentRepository  docRepo;
    private final OrganizationRepository   orgRepo;
    private final ESignTokenService        tokenService;
    private final ESignStorageService      esignStorage;
    private final ESignAuditService        auditService;

    // ── Lookups used by sign-in ───────────────────────────────────────────────

    /** True when at least one sent document lists {@code email} as signatory or CC. */
    public boolean hasAnyDocuments(String email) {
        return mongoTemplate.exists(new Query(matchCriteria(email)), ESignDocument.class);
    }

    /** Name the email was most recently addressed by as a signatory, for the account display name. */
    public String latestSignerName(String email) {
        Query q = new Query(matchCriteria(email)).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(20);
        q.fields().include("signatories").include("clientEmail").include("clientName");
        for (ESignDocument d : mongoTemplate.find(q, ESignDocument.class)) {
            Optional<ESignDocument.Signatory> me = findSignatory(d, email);
            if (me.isPresent() && notBlank(me.get().getName())) return me.get().getName();
            if (SignerEmails.same(d.getClientEmail(), email) && notBlank(d.getClientName())) return d.getClientName();
        }
        return null;
    }

    // ── Inbox ─────────────────────────────────────────────────────────────────

    public SignerInboxResponse list(String email, String tab, String orgId, String search, int page, int size) {
        Bucket bucket = parseBucket(tab);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);

        List<SignerDocumentItem> all = loadItems(email);

        Map<String, Long> counts = new LinkedHashMap<>();
        for (Bucket b : Bucket.values()) counts.put(b.name(), 0L);
        all.forEach(i -> counts.merge(i.getBucket().name(), 1L, Long::sum));

        List<SignerInboxResponse.OrgOption> orgs = all.stream()
                .filter(i -> i.getOrgId() != null)
                .collect(Collectors.toMap(SignerDocumentItem::getOrgId, SignerDocumentItem::getOrgName,
                        (a, b) -> a, LinkedHashMap::new))
                .entrySet().stream()
                .map(e -> new SignerInboxResponse.OrgOption(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(o -> String.valueOf(o.name()).toLowerCase()))
                .toList();

        String q = search != null ? search.trim().toLowerCase() : "";
        List<SignerDocumentItem> filtered = all.stream()
                .filter(i -> i.getBucket() == bucket)
                .filter(i -> orgId == null || orgId.isBlank() || orgId.equals(i.getOrgId()))
                .filter(i -> q.isEmpty()
                        || (i.getTitle() != null && i.getTitle().toLowerCase().contains(q))
                        || (i.getOrgName() != null && i.getOrgName().toLowerCase().contains(q)))
                .sorted(comparatorFor(bucket))
                .toList();

        int from = Math.min(safePage * safeSize, filtered.size());
        int to   = Math.min(from + safeSize, filtered.size());

        return SignerInboxResponse.builder()
                .items(filtered.subList(from, to))
                .total(filtered.size())
                .page(safePage)
                .size(safeSize)
                .counts(counts)
                .organizations(orgs)
                .build();
    }

    /** Per-tab counts only (dashboard badge / sidebar). */
    public Map<String, Long> counts(String email) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Bucket b : Bucket.values()) counts.put(b.name(), 0L);
        loadItems(email).forEach(i -> counts.merge(i.getBucket().name(), 1L, Long::sum));
        return counts;
    }

    // ── Actions on a single document ──────────────────────────────────────────

    /**
     * Returns the signer's own signing token for this document so the client can open the
     * standard signing page. Reuses the active emailed token when there is one (so the email
     * link keeps working); otherwise mints one against the document's existing deadline.
     */
    public String openForSigning(String email, String documentId, String ip, String ua) {
        ESignDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        SignerDocumentItem item = classify(doc, email, Map.of())
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));

        if (!item.isCanSign()) {
            throw new IllegalStateException(switch (item.getBucket()) {
                case UPCOMING -> "It's not your turn to sign yet — this document is waiting on "
                        + (item.getWaitingOnName() != null ? item.getWaitingOnName() : "another signer") + ".";
                case SIGNED   -> "You have already signed this document.";
                default       -> "This document is no longer available for signing.";
            });
        }

        ESignDocument.Signatory me = findSignatory(doc, email).orElseThrow(() -> new IllegalStateException(
                "This document can only be signed from the link in your invitation email."));

        String previousJti = me.getTokenJti();
        String token = tokenService.reissueSigningToken(doc, me);
        if (!Objects.equals(previousJti, me.getTokenJti())) docRepo.save(doc);

        auditService.log(doc.getId(), me.getEmail(), ESignAuditEvent.ActorType.CLIENT,
                ESignAuditEvent.EventType.LINK_OPENED, ip, ua, Map.of("via", "SIGNER_PORTAL"));
        log.info("Signer portal: '{}' opened document '{}' for signing", email, doc.getId());
        return token;
    }

    /** Short-lived read-only view token for any document in the signer's inbox. */
    public String openForView(String email, String documentId) {
        ESignDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        classify(doc, email, Map.of()).orElseThrow(() -> new IllegalArgumentException("Document not found"));
        return tokenService.issueViewToken(doc.getId(), VIEW_TOKEN_DAYS);
    }

    public record SignedPdf(String fileName, byte[] bytes) {}

    /** The final signed PDF, available once the document is COMPLETED. */
    public SignedPdf signedPdf(String email, String documentId, String ip, String ua) {
        ESignDocument doc = docRepo.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        SignerDocumentItem item = classify(doc, email, Map.of())
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
        if (!item.isCanDownload()) {
            throw new IllegalStateException("The signed copy is available once every signer has signed.");
        }
        byte[] bytes = esignStorage.resolveSignedBytes(doc);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("The signed copy is still being prepared. Please try again shortly.");
        }
        auditService.log(doc.getId(), email, ESignAuditEvent.ActorType.CLIENT,
                ESignAuditEvent.EventType.DOCUMENT_DOWNLOADED, ip, ua, Map.of("via", "SIGNER_PORTAL"));
        String base = doc.getTitle() != null ? doc.getTitle().replaceAll("[^A-Za-z0-9._ -]", "_") : "document";
        return new SignedPdf(base + "-signed.pdf", bytes);
    }

    // ── Classification ────────────────────────────────────────────────────────

    private List<SignerDocumentItem> loadItems(String email) {
        Query q = new Query(matchCriteria(email))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(MAX_DOCS);
        // Never pull embedded PDF / attachment bytes into memory for a listing.
        q.fields().exclude("sourcePdfData").exclude("signedPdfData").exclude("clientAttachments")
                  .exclude("completionNotifications");
        List<ESignDocument> docs = mongoTemplate.find(q, ESignDocument.class);

        Set<String> orgIds = docs.stream().map(ESignDocument::getOrgId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> orgNames = orgRepo.findAllById(orgIds).stream()
                .collect(Collectors.toMap(Organization::getId, Organization::getName, (a, b) -> a));

        return docs.stream()
                .map(d -> classify(d, email, orgNames))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Classifies {@code doc} for {@code email}. Empty when the email has no relation to the
     * document or the document is a DRAFT — this is the single access check for the inbox.
     */
    private Optional<SignerDocumentItem> classify(ESignDocument doc, String email, Map<String, String> orgNames) {
        if (doc.getStatus() == null || doc.getStatus() == ESignDocument.Status.DRAFT) return Optional.empty();

        List<ESignDocument.Signatory> sigs = doc.getSignatories() != null ? doc.getSignatories() : List.of();
        Optional<ESignDocument.Signatory> meOpt = findSignatory(doc, email);
        boolean legacySigner = sigs.isEmpty() && SignerEmails.same(doc.getClientEmail(), email);
        boolean isSigner = meOpt.isPresent() || legacySigner;
        boolean isCc = containsEmail(doc.getCcEmails(), email) || containsEmail(doc.getCompletionCcEmails(), email);
        if (!isSigner && !isCc) return Optional.empty();

        LocalDateTime now = LocalDateTime.now();
        ESignDocument.Status status = doc.getStatus();
        boolean deadlinePassed = doc.getTokenExpiresAt() != null && doc.getTokenExpiresAt().isBefore(now);
        boolean openForSigning = OPEN.contains(status) && !deadlinePassed;

        int total  = sigs.isEmpty() ? 1 : sigs.size();
        int signed = sigs.isEmpty()
                ? (status == ESignDocument.Status.SIGNED || status == ESignDocument.Status.COMPLETED ? 1 : 0)
                : (int) sigs.stream().filter(s -> s.getStatus() == ESignDocument.SignatoryStatus.SIGNED).count();

        Bucket bucket;
        String waitingOn = null;
        if (isSigner) {
            boolean mySigned = meOpt.map(s -> s.getStatus() == ESignDocument.SignatoryStatus.SIGNED)
                    .orElse(status == ESignDocument.Status.SIGNED || status == ESignDocument.Status.COMPLETED);
            if (mySigned) {
                bucket = Bucket.SIGNED;
            } else if (openForSigning) {
                Optional<ESignDocument.Signatory> turn = currentTurn(doc);
                boolean myTurn = legacySigner
                        || doc.getSigningMode() != ESignDocument.SigningMode.SEQUENTIAL
                        || turn.map(t -> t.getId() != null && t.getId().equals(meOpt.get().getId())).orElse(false);
                bucket = myTurn ? Bucket.ACTION : Bucket.UPCOMING;
                if (!myTurn) waitingOn = turn.map(t -> notBlank(t.getName()) ? t.getName() : t.getEmail()).orElse(null);
            } else {
                bucket = Bucket.OTHER;
            }
        } else {
            bucket = Bucket.OTHER;
        }

        ESignDocument.Signatory me = meOpt.orElse(null);
        return Optional.of(SignerDocumentItem.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .orgId(doc.getOrgId())
                .orgName(doc.getOrgId() != null ? orgNames.get(doc.getOrgId()) : null)
                .status(status.name())
                .bucket(bucket)
                .myRole(isSigner ? SignerDocumentItem.Role.SIGNER : SignerDocumentItem.Role.CC)
                .mySignatoryStatus(me != null && me.getStatus() != null ? me.getStatus().name() : null)
                .mySigningOrder(me != null ? me.getSigningOrder() : null)
                .signingMode(doc.getSigningMode() != null ? doc.getSigningMode().name() : null)
                .signatoriesTotal(total)
                .signatoriesSigned(signed)
                .waitingOnName(waitingOn)
                .sentAt(doc.getSentAt())
                .expiresAt(doc.getTokenExpiresAt())
                .mySignedAt(me != null ? me.getSignedAt() : (legacySigner ? doc.getSubmittedAt() : null))
                .completedAt(doc.getCompletedAt())
                .canSign(bucket == Bucket.ACTION && !legacySigner)
                .canView(true)
                .canDownload(status == ESignDocument.Status.COMPLETED)
                .build());
    }

    /** The signatory whose turn it is in SEQUENTIAL mode (lowest unsigned order). */
    private Optional<ESignDocument.Signatory> currentTurn(ESignDocument doc) {
        if (doc.getSignatories() == null) return Optional.empty();
        return doc.getSignatories().stream()
                .filter(s -> s.getStatus() != ESignDocument.SignatoryStatus.SIGNED)
                .min(Comparator.comparingInt(ESignDocument.Signatory::getSigningOrder));
    }

    private Optional<ESignDocument.Signatory> findSignatory(ESignDocument doc, String email) {
        if (doc.getSignatories() == null) return Optional.empty();
        return doc.getSignatories().stream().filter(s -> SignerEmails.same(s.getEmail(), email)).findFirst();
    }

    /**
     * Mongo criteria for "documents related to this email". Emails were not normalized
     * historically, so the match is an anchored, case-insensitive exact match.
     */
    private Criteria matchCriteria(String email) {
        Pattern exact = Pattern.compile("^" + Pattern.quote(email.trim()) + "$", Pattern.CASE_INSENSITIVE);
        return new Criteria().andOperator(
                Criteria.where("status").ne(ESignDocument.Status.DRAFT),
                new Criteria().orOperator(
                        Criteria.where("signatories.email").regex(exact),
                        Criteria.where("clientEmail").regex(exact),
                        Criteria.where("ccEmails").regex(exact),
                        Criteria.where("completionCcEmails").regex(exact)));
    }

    private Comparator<SignerDocumentItem> comparatorFor(Bucket bucket) {
        Function<SignerDocumentItem, LocalDateTime> key = switch (bucket) {
            case ACTION, UPCOMING -> SignerDocumentItem::getExpiresAt;   // soonest deadline first
            case SIGNED           -> i -> i.getMySignedAt() != null ? i.getMySignedAt() : i.getSentAt();
            case OTHER            -> SignerDocumentItem::getSentAt;
        };
        Comparator<LocalDateTime> order = (bucket == Bucket.ACTION || bucket == Bucket.UPCOMING)
                ? Comparator.nullsLast(Comparator.naturalOrder())
                : Comparator.nullsLast(Comparator.reverseOrder());
        return Comparator.comparing(key, order);
    }

    private static Bucket parseBucket(String tab) {
        if (tab == null || tab.isBlank()) return Bucket.ACTION;
        try {
            return Bucket.valueOf(tab.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown tab '" + tab + "'. Use ACTION, UPCOMING, SIGNED or OTHER.");
        }
    }

    private static boolean containsEmail(List<String> list, String email) {
        return list != null && list.stream().anyMatch(e -> SignerEmails.same(e, email));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
